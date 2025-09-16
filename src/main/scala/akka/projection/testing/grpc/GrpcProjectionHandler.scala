/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.grpc

import akka.Done
import akka.actor.typed.ActorSystem
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.r2dbc.internal.R2dbcExecutor
import akka.persistence.r2dbc.internal.Sql.Interpolation
import akka.projection.ProjectionId
import akka.projection.r2dbc.scaladsl.R2dbcSession
import akka.projection.scaladsl.Handler
import akka.projection.testing.ConfigurablePersistentActor

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class GrpcProjectionHandler(
    val projectionId: ProjectionId,
    projectionIndex: Int,
    readOnly: Boolean,
    val failEvery: Int,
    executor: R2dbcExecutor)
    extends Handler[EventEnvelope[ConfigurablePersistentActor.Event]] {

  override def process(envelope: EventEnvelope[ConfigurablePersistentActor.Event]): Future[Done] =
    if (readOnly)
      Future.successful(Done)
    else {
      executor
        .updateOne("insert") { connection =>
          connection
            .createStatement(sql"""
          INSERT INTO events(name, projection_id, event)
          VALUES (?, ?, ?)
          ON CONFLICT (name, projection_id, event)
          DO UPDATE SET updates = events.updates + 1;
        """).bind(0, envelope.event.testName)
            .bind(1, projectionIndex)
            .bind(2, envelope.event.payload)
        }
        .map(_ => Done)(ExecutionContext.parasitic)
    }
}
