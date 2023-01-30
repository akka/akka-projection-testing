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

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.Done
import akka.persistence.query.typed.EventEnvelope
import akka.projection.r2dbc.scaladsl.R2dbcHandler
import akka.projection.r2dbc.scaladsl.R2dbcSession
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class R2dbcProjectionHandler(tag: String, projectionId: Int, readOnly: Boolean)(implicit ec: ExecutionContext)
    extends R2dbcHandler[EventEnvelope[ConfigurablePersistentActor.Event]] {

  private val log: Logger = LoggerFactory.getLogger(getClass)
  private var startTime = System.nanoTime()
  private var count = 0

  override def process(
      session: R2dbcSession,
      envelope: EventEnvelope[ConfigurablePersistentActor.Event]): Future[Done] = {
    log.trace(
      "Event {} for tag {} sequence {} test {}",
      envelope.event.payload,
      tag,
      envelope.offset,
      envelope.event.testName)
    count += 1
    if (count == 1000) {
      val durationMs = (System.nanoTime() - startTime) / 1000 / 1000
      log.info("Projection [{}] throughput [{}] events/s in [{}] ms", tag, 1000 * count / durationMs, durationMs)
      count = 0
      startTime = System.nanoTime()
    }

    if (readOnly)
      Future.successful(Done)
    else {
      val stmt = session
        .createStatement("insert into events(name, projection_id, event) values ($1, $2, $3)")
        .bind("$1", envelope.event.testName)
        .bind("$2", projectionId)
        .bind("$3", envelope.event.payload)
      session.updateOne(stmt).map(_ => Done)
    }
  }
}

// when using this consider reducing failure otherwise a high change of at least one grouped envelope causing an error
// and no progress will be made
class R2dbcGroupedProjectionHandler(tag: String, projectionId: Int, readOnly: Boolean)(implicit ec: ExecutionContext)
    extends R2dbcHandler[Seq[EventEnvelope[ConfigurablePersistentActor.Event]]] {
  private val log: Logger = LoggerFactory.getLogger(getClass)
  private var startTime = System.nanoTime()
  private var count = 0

  override def process(
      session: R2dbcSession,
      envelopes: Seq[EventEnvelope[ConfigurablePersistentActor.Event]]): Future[Done] = {
    log.trace(
      "Persisting {} events for tag {} for test {}",
      envelopes.size,
      tag,
      envelopes.headOption.map(_.event.testName).getOrElse("<unknown>"))

    count += envelopes.size
    if (count >= 1000) {
      val durationMs = (System.nanoTime() - startTime) / 1000 / 1000
      log.info("Projection [{}] throughput [{}] events/s in [{}] ms", tag, 1000 * count / durationMs, durationMs)
      count = 0
      startTime = System.nanoTime()
    }

    if (readOnly)
      Future.successful(Done)
    else {
      // TODO batch statements
      val stmts =
        envelopes.map { env =>
          session
            .createStatement("insert into events(name, projection_id, event) values ($1, $2, $3)")
            .bind("$1", env.event.testName)
            .bind("$2", projectionId)
            .bind("$3", env.event.payload)
        }.toVector

      session.update(stmts).map(_ => Done)
    }
  }
}
