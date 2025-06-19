/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.r2dbc

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.Done
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.r2dbc.internal.Sql.Interpolation
import akka.projection.ProjectionId
import akka.projection.r2dbc.scaladsl.R2dbcHandler
import akka.projection.r2dbc.scaladsl.R2dbcSession
import akka.projection.testing.ConfigurablePersistentActor
import akka.projection.testing.TestProjectionHandler

class R2dbcProjectionHandler(
    val projectionId: ProjectionId,
    projectionIndex: Int,
    readOnly: Boolean,
    val failEvery: Int)(implicit ec: ExecutionContext)
    extends R2dbcHandler[EventEnvelope[ConfigurablePersistentActor.Event]]
    with TestProjectionHandler[EventEnvelope] {

  override def process(
      session: R2dbcSession,
      envelope: EventEnvelope[ConfigurablePersistentActor.Event]): Future[Done] = {
    testProcessing(envelope)

    if (readOnly)
      Future.successful(Done)
    else {
      val stmt = session
        .createStatement(sql"""
          INSERT INTO events(name, projection_id, event)
          VALUES (?, ?, ?)
          ON CONFLICT (name, projection_id, event)
          DO UPDATE SET updates = events.updates + 1;
        """)
        .bind(0, envelope.event.testName)
        .bind(1, projectionIndex)
        .bind(2, envelope.event.payload)
      session.updateOne(stmt).map(_ => Done)
    }
  }
}

// when using this consider reducing failure otherwise a high change of at least one grouped envelope causing an error
// and no progress will be made
class R2dbcGroupedProjectionHandler(
    val projectionId: ProjectionId,
    projectionIndex: Int,
    readOnly: Boolean,
    val failEvery: Int)(implicit ec: ExecutionContext)
    extends R2dbcHandler[Seq[EventEnvelope[ConfigurablePersistentActor.Event]]]
    with TestProjectionHandler[EventEnvelope] {

  override def process(
      session: R2dbcSession,
      envelopes: Seq[EventEnvelope[ConfigurablePersistentActor.Event]]): Future[Done] = {
    testProcessingGroup(envelopes)

    if (readOnly)
      Future.successful(Done)
    else {
      // TODO batch statements
      val stmts =
        envelopes.map { env =>
          session
            .createStatement(sql"""
              INSERT INTO events(name, projection_id, event)
              VALUES (?, ?, ?)
              ON CONFLICT (name, projection_id, event)
              DO UPDATE SET updates = events.updates + 1;
            """)
            .bind(0, env.event.testName)
            .bind(1, projectionIndex)
            .bind(2, env.event.payload)
        }.toVector

      session.update(stmts).map(_ => Done)
    }
  }
}
