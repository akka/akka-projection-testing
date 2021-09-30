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
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, PostStop }
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.cluster.sharding.typed.{ ClusterShardingSettings, ShardedDaemonProcessSettings }
import akka.cluster.typed.Cluster
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.persistence.jdbc.testkit.scaladsl.SchemaUtils
import akka.persistence.query.Offset
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.SourceProvider
import akka.projection.{ ProjectionBehavior, ProjectionId }
import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import scala.io.Source

import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl.EventsBySliceQuery
import akka.projection.Projection
import akka.projection.eventsourced.scaladsl.EventSourcedProvider2
import akka.projection.r2dbc.scaladsl.R2dbcProjection

object Guardian {

  def createProjectionFor(
      settings: EventProcessorSettings,
      projectionIndex: Int,
      tagIndex: Int,
      factory: HikariJdbcSessionFactory,
      journal: Main.Journal)(
      implicit system: ActorSystem[_]): Projection[EventEnvelope[ConfigurablePersistentActor.Event]] = {

    val tag = ConfigurablePersistentActor.tagFor(projectionIndex, tagIndex)

    journal match {
      case Main.Cassandra | Main.JDBC =>
        val sourceProvider: SourceProvider[Offset, EventEnvelope[ConfigurablePersistentActor.Event]] =
          FailingEventsByTagSourceProvider.eventsByTag[ConfigurablePersistentActor.Event](
            system = system,
            readJournalPluginId = journal.readJournal,
            tag = tag)
        JdbcProjection.groupedWithin(
          projectionId = ProjectionId(s"test-projection-id-${projectionIndex}", tag),
          sourceProvider,
          () => factory.newSession(),
          () => new GroupedProjectionHandler(tag, projectionIndex, system))
      //    JdbcProjection.exactlyOnce(
      //      projectionId = ProjectionId(s"test-projection-id-${projectionIndex}", tag),
      //      sourceProvider,
      //      () => factory.newSession(),
      //      () => new ProjectionHandler(tag, projectionIndex, system))

      case Main.R2DBC =>
        // FIXME implement FailingEventsBySlicesSourceProvider

        // FIXME there should be a more convenient API for the slice util methods
        val eventsBySlicesQuery =
          PersistenceQuery(system).readJournalFor[EventsBySliceQuery](journal.readJournal)
        val ranges = eventsBySlicesQuery.sliceRanges(settings.parallelism)

        val sourceProvider: SourceProvider[Offset, EventEnvelope[ConfigurablePersistentActor.Event]] =
          EventSourcedProvider2.eventsBySlices[ConfigurablePersistentActor.Event](
            system = system,
            readJournalPluginId = journal.readJournal,
            entityTypeHint = ConfigurablePersistentActor.Key.name,
            minSlice = ranges(tagIndex).min,
            maxSlice = ranges(tagIndex).max)

        R2dbcProjection.groupedWithin(
          projectionId = ProjectionId(s"test-projection-id-${projectionIndex}", tag),
          settings = None,
          sourceProvider,
          () => new R2dbcGroupedProjectionHandler(tag, projectionIndex)(system.executionContext))
    }
  }

  def apply(shouldBootstrap: Boolean = false): Behavior[String] = {
    Behaviors.setup[String] { context =>
      implicit val system: ActorSystem[_] = context.system
      AkkaManagement(system).start()
      if (shouldBootstrap) {
        ClusterBootstrap(system).start()
      }
      val config = new HikariConfig
      config.setJdbcUrl(system.settings.config.getString("jdbc-connection-settings.url"))
      config.setUsername(system.settings.config.getString("jdbc-connection-settings.user"))
      config.setPassword(system.settings.config.getString("jdbc-connection-settings.password"))
      config.setMaximumPoolSize(20)
      config.setAutoCommit(false)
      Class.forName("org.postgresql.Driver")
      val dataSource = new HikariDataSource(config)

      val schemaFile = Source.fromResource("create_tables_postgres.sql")
      val postgresSchema = try schemaFile.mkString
      finally schemaFile.close()

      // Block until schema is created. Only one of these actors are created
      val dbSessionFactory: HikariJdbcSessionFactory = new HikariJdbcSessionFactory(dataSource)

      val connection = dataSource.getConnection()

      val journal = system.settings.config.getString("akka.persistence.journal.plugin") match {
        case Main.Cassandra.journalPluginId => Main.Cassandra
        case Main.JDBC.journalPluginId      => Main.JDBC
        case Main.R2DBC.journalPluginId     => Main.R2DBC
        case other                          => throw new IllegalArgumentException(s"Unknown journal [$other]")
      }

      SchemaUtils.createIfNotExists()

      try {
        connection.createStatement().execute(postgresSchema)
        connection.commit()
      } catch {
        case t: Throwable => context.log.error("Failed to create postgres schema. Assuming it already exists.", t)
      } finally {
        connection.close()
      }

      val settings = EventProcessorSettings(system)
      val shardRegion = ConfigurablePersistentActor.init(settings, system)

      val loadGeneration: ActorRef[LoadGeneration.Command] =
        context.spawn(LoadGeneration(settings, shardRegion, dataSource), "load-generation")

      val httpPort = system.settings.config.getInt("test.http.port")

      val server = new HttpServer(new TestRoutes(loadGeneration, dataSource, journal).route, httpPort)
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
            n => ProjectionBehavior(createProjectionFor(settings, projection, n, dbSessionFactory, journal)),
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
