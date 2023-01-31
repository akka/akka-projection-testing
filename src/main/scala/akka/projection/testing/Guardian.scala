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
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.SourceProvider
import akka.projection.{ ProjectionBehavior, ProjectionId }
import com.zaxxer.hikari.{ HikariConfig, HikariDataSource }
import scala.io.Source

import akka.projection.eventsourced.{ EventEnvelope => ProjectionEventEnvelope }
import akka.persistence.query.typed.EventEnvelope
import akka.projection.Projection
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.r2dbc.scaladsl.R2dbcProjection

object Guardian {

  def createProjectionFor(
      settings: EventProcessorSettings,
      projectionIndex: Int,
      tagIndexOrSliceIndex: Int,
      factory: HikariJdbcSessionFactory,
      journal: Main.Journal)(implicit system: ActorSystem[_]): Behavior[ProjectionBehavior.Command] = {

    journal match {
      case Main.Cassandra | Main.JDBC =>
        val tag = ConfigurablePersistentActor.tagFor(projectionIndex, tagIndexOrSliceIndex)
        val sourceProvider: SourceProvider[Offset, ProjectionEventEnvelope[ConfigurablePersistentActor.Event]] =
          EventSourcedProvider.eventsByTag(system, journal.readJournal, tag)

        val projection: Projection[ProjectionEventEnvelope[ConfigurablePersistentActor.Event]] = {
          val projectionId = ProjectionId(s"test-projection-id-$projectionIndex", tag)
          if (settings.readOnly) {
            JdbcProjection.atLeastOnceAsync(
              projectionId,
              sourceProvider,
              () => factory.newSession(),
              () => new LoggingHandler(projectionId))
          } else {
            JdbcProjection.groupedWithin(
              projectionId,
              sourceProvider,
              () => factory.newSession(),
              () =>
                new GroupedProjectionHandler(
                  projectionId,
                  projectionIndex,
                  system,
                  settings.readOnly,
                  settings.failEvery))
          }
          //          JdbcProjection.exactlyOnce(
          //            projectionId,
          //            sourceProvider,
          //            () => factory.newSession(),
          //            () => new ProjectionHandler(projectionId, projectionIndex, system, settings.readOnly, settings.failEvery))
        }
        ProjectionBehavior(projection)

      case Main.R2DBC =>
        val ranges = EventSourcedProvider.sliceRanges(system, journal.readJournal, settings.parallelism)
        val sliceRange = ranges(tagIndexOrSliceIndex)

        val sourceProvider: SourceProvider[Offset, EventEnvelope[ConfigurablePersistentActor.Event]] =
          EventSourcedProvider.eventsBySlices(
            system,
            journal.readJournal,
            ConfigurablePersistentActor.Key.name,
            sliceRange.min,
            sliceRange.max)

        val projection: Projection[EventEnvelope[ConfigurablePersistentActor.Event]] = {
          val projectionId =
            ProjectionId(s"test-projection-id-$projectionIndex", s"${sliceRange.min}-${sliceRange.max}")
          if (settings.readOnly) {
            R2dbcProjection.atLeastOnceAsync(
              projectionId,
              settings = None,
              sourceProvider,
              () => new LoggingHandler(projectionId))
          } else {
            R2dbcProjection.groupedWithin(
              projectionId,
              settings = None,
              sourceProvider,
              () =>
                new R2dbcGroupedProjectionHandler(projectionId, projectionIndex, settings.readOnly, settings.failEvery)(
                  system.executionContext))

//            R2dbcProjection.exactlyOnce(
//              projectionId,
//              settings = None,
//              sourceProvider,
//              () => new R2dbcProjectionHandler(projectionId, projectionIndex, settings.readOnly, settings.failEvery)(system.executionContext))
          }
        }
        ProjectionBehavior(projection)
    }
  }

  def apply(shouldBootstrap: Boolean = false): Behavior[String] = {
    Behaviors.setup[String] { context =>
      implicit val system: ActorSystem[_] = context.system
      AkkaManagement(system).start()
      if (shouldBootstrap) {
        ClusterBootstrap(system).start()
      }

      val journal = system.settings.config.getString("akka.persistence.journal.plugin") match {
        case Main.Cassandra.journalPluginId => Main.Cassandra
        case Main.JDBC.journalPluginId      => Main.JDBC
        case Main.R2DBC.journalPluginId     => Main.R2DBC
        case other                          => throw new IllegalArgumentException(s"Unknown journal [$other]")
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
      try {
        connection.createStatement().execute(postgresSchema)
        connection.commit()
      } catch {
        case t: Throwable => context.log.error("Failed to create postgres schema. Assuming it already exists.", t)
      } finally {
        connection.close()
      }

      if (journal == Main.JDBC)
        SchemaUtils.createIfNotExists()

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

        (0 until settings.nrProjections).foreach { projectionIndex =>
          ShardedDaemonProcess(system).init(
            name = s"test-projection-$projectionIndex",
            settings.parallelism,
            n => createProjectionFor(settings, projectionIndex, n, dbSessionFactory, journal),
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
