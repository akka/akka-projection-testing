/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.r2dbc

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.persistence.query.Offset
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.r2dbc.ConnectionFactoryProvider
import akka.persistence.r2dbc.internal.R2dbcExecutor
import akka.persistence.r2dbc.internal.Sql.Interpolation
import akka.projection.Projection
import akka.projection.ProjectionBehavior
import akka.projection.ProjectionId
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.r2dbc.scaladsl.R2dbcProjection
import akka.projection.scaladsl.SourceProvider
import akka.projection.testing.ConfigurablePersistentActor
import akka.projection.testing.EventProcessorSettings
import akka.projection.testing.LoggingHandler
import akka.projection.testing.TestSetup
import org.slf4j.LoggerFactory

final class R2dbcTestSetup(settings: EventProcessorSettings)(implicit system: ActorSystem[_]) extends TestSetup {
  override val journal: TestSetup.Journal = TestSetup.R2DBC

  private val log = LoggerFactory.getLogger(this.getClass)

  private val executor = new R2dbcExecutor(
    ConnectionFactoryProvider(system).connectionFactoryFor("akka.persistence.r2dbc.connection-factory"),
    log,
    logDbCallsExceeding = 5.seconds,
    closeCallsExceeding = None)(system.executionContext, system)

  override def init(): Future[Done] =
    Future.successful(Done)

  override def reset(): Future[Done] = {
    executor
      .executeDdls(s"reset") { connection =>
        Vector(
          connection.createStatement("TRUNCATE events")
          // connection.createStatement("DELETE FROM event_journal CASCADE")
        )
      }
      .map(_ => Done)(ExecutionContext.parasitic)
  }

  override def createProjection(projectionIndex: Int, sliceIndex: Int): Behavior[ProjectionBehavior.Command] = {

    val ranges = EventSourcedProvider.sliceRanges(system, journal.readJournal, settings.parallelism)
    val sliceRange = ranges(sliceIndex)

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

        // R2dbcProjection.exactlyOnce(
        //   projectionIndex,
        //   settings = None,
        //   sourceProvider,
        //   () =>
        //     new R2dbcProjectionHandler(projectionIndex, projectionIndex, settings.readOnly, settings.failEvery)(
        //       system.executionContext))
      }
    }

    ProjectionBehavior(projection)
  }

  override def countEvents(testName: String, projectionId: Int): Future[Int] = {
    executor
      .selectOne("count events")(
        _.createStatement(sql"SELECT count(*) FROM events WHERE name = ? AND projection_id = ?")
          .bind(0, testName)
          .bind(1, projectionId),
        { row =>
          row.get(0, classOf[Integer]).intValue
        })
      .map(_.getOrElse(0))(ExecutionContext.parasitic)
  }

  override def writeResult(testName: String, result: String): Future[Done] = {
    executor
      .updateOne("write result")(
        _.createStatement(sql"INSERT INTO results(name, result) VALUES (?, ?)").bind(0, testName).bind(1, result))
      .map(_ => Done)(ExecutionContext.parasitic)
  }

  override def readResult(testName: String): Future[String] = {
    executor
      .selectOne("read result")(
        _.createStatement(sql"SELECT result FROM results WHERE name = ?")
          .bind(0, testName),
        { row =>
          row.get(0, classOf[String])
        })
      .map(_.getOrElse("not finished"))(ExecutionContext.parasitic)
  }

  override def finish(): Unit = ()
}
