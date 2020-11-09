/*
 * Copyright 2020 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.projection.testing

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, PostStop}
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.cluster.sharding.typed.{ClusterShardingSettings, ShardedDaemonProcessSettings}
import akka.cluster.typed.Cluster
import akka.management.scaladsl.AkkaManagement
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.SourceProvider
import akka.projection.{ProjectionBehavior, ProjectionId}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}

import scala.io.Source

object Guardian {

  def createProjectionFor(projectionIndex: Int, tagIndex: Int, factory: HikariFactory)(
      implicit system: ActorSystem[_]) = {
    val tag = ConfigurablePersistentActor.tagFor(projectionIndex, tagIndex)

    val sourceProvider: SourceProvider[Offset, EventEnvelope[ConfigurablePersistentActor.Event]] =
      FailingEventsByTagSourceProvider.eventsByTag[ConfigurablePersistentActor.Event](
        system = system,
        readJournalPluginId = CassandraReadJournal.Identifier,
        tag = tag)

    //    JdbcProjection.groupedWithin(
    //      projectionId = ProjectionId("test-projection-id", tag),
    //      sourceProvider,
    //      () => factory.newSession(),
    //      () => new GroupedProjectionHandler(tag, system)
    //    )

    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId(s"test-projection-id-${projectionIndex}", tag),
      sourceProvider,
      () => factory.newSession(),
      () => new ProjectionHandler(tag, projectionIndex, system))
  }

  def apply(): Behavior[String] = {
    Behaviors.setup[String] { context =>
      implicit val system: ActorSystem[_] = context.system
      AkkaManagement(system).start()
      // TODO config
      val config = new HikariConfig
      config.setJdbcUrl("jdbc:postgresql://127.0.0.1:5432/")
      config.setUsername("docker")
      config.setPassword("docker")
      config.setMaximumPoolSize(10)
      config.setAutoCommit(false)
      Class.forName("org.postgresql.Driver")
      val dataSource = new HikariDataSource(config)


      val schemaFile = Source.fromResource("projection.sql")
      val postgresSchema = try schemaFile.mkString finally schemaFile.close()

      // Block until schema is created. Only one of these actors are created
      val dbSessionFactory: HikariFactory = new HikariFactory(dataSource)

      val session = dbSessionFactory.newSession()

      session.withConnection { con =>
        con.createStatement().execute(postgresSchema)
      }

      session.commit()
      session.close()

      val settings = EventProcessorSettings(system)
      val shardRegion = ConfigurablePersistentActor.init(settings, system)

      val loadGeneration: ActorRef[LoadGeneration.RunTest] =
        context.spawn(LoadGeneration(settings, shardRegion, dbSessionFactory), "load-generation")

      val httpPort = system.settings.config.getInt("test.http.port")

      val server = new HttpServer(new TestRoutes(loadGeneration, dbSessionFactory).route, httpPort)
      server.start()

      if (Cluster(system).selfMember.hasRole("read-model")) {
        // we only want to run the daemon processes on the read-model nodes
        val shardingSettings = ClusterShardingSettings(system)
        val shardedDaemonProcessSettings =
          ShardedDaemonProcessSettings(system).withShardingSettings(shardingSettings.withRole("read-model"))

        (0 until settings.nrProjections).foreach { projection =>
          ShardedDaemonProcess(system).init(
            name = s"test-projection-${projection}",
            settings.parallelism,
            n => ProjectionBehavior(createProjectionFor(projection, n, dbSessionFactory)),
            shardedDaemonProcessSettings,
            Some(ProjectionBehavior.Stop))
        }

      }

      Behaviors.receiveMessage[String](_ => Behaviors.same).receiveSignal {
        case (_, PostStop) =>
          dataSource.close()
          Behaviors.stopped
      }
    }
  }
}
