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

import scala.util.Random

import akka.actor.typed.ActorSystem
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import org.slf4j.{ Logger, LoggerFactory }
import scala.util.Try
import scala.util.control.NoStackTrace

import akka.projection.ProjectionId

class ProjectionHandler(
    projectionId: ProjectionId,
    projectionIndex: Int,
    system: ActorSystem[_],
    readOnly: Boolean,
    failEvery: Int)
    extends JdbcHandler[EventEnvelope[ConfigurablePersistentActor.Event], HikariJdbcSession] {
  private val log: Logger = LoggerFactory.getLogger(getClass)
  private var startTime = System.nanoTime()
  private var count = 0

  override def process(session: HikariJdbcSession, envelope: EventEnvelope[ConfigurablePersistentActor.Event]): Unit = {
    log.trace(
      "Event {} for projection {} sequence {} test {}",
      envelope.event.payload,
      projectionId.id,
      envelope.offset,
      envelope.event.testName)

    if (failEvery != Int.MaxValue && Random.nextInt(failEvery) == 1) {
      throw new RuntimeException(
        s"Simulated failure when processing persistence id ${envelope.persistenceId} sequence nr ${envelope.sequenceNr} offset ${envelope.offset}")
        with NoStackTrace
    }

    count += 1
    if (count == 1000) {
      val durationMs = (System.nanoTime() - startTime) / 1000 / 1000
      log.info(
        "Projection [{}] throughput [{}] events/s in [{}] ms",
        projectionId.id,
        1000 * count / durationMs,
        durationMs)
      count = 0
      startTime = System.nanoTime()
    }

    if (!readOnly) {
      session.withConnection { connection =>
        require(!connection.getAutoCommit)
        val pstmt = connection.prepareStatement("insert into events(name, projection_id, event) values (?, ?, ?)")
        pstmt.setString(1, envelope.event.testName)
        pstmt.setInt(2, projectionIndex)
        pstmt.setString(3, envelope.event.payload)
        pstmt.executeUpdate()
        Try(pstmt.close())
      }
    }
  }
}

// when using this consider reducing failure otherwise a high change of at least one grouped envelope causing an error
// and no progress will be made
class GroupedProjectionHandler(
    projectionId: ProjectionId,
    projectionIndex: Int,
    system: ActorSystem[_],
    readOnly: Boolean,
    failEvery: Int)
    extends JdbcHandler[Seq[EventEnvelope[ConfigurablePersistentActor.Event]], HikariJdbcSession] {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  override def process(
      session: HikariJdbcSession,
      envelopes: Seq[EventEnvelope[ConfigurablePersistentActor.Event]]): Unit = {
    log.trace(
      "Persisting {} events for projection {} for test {}",
      envelopes.size,
      projectionId.id,
      envelopes.headOption.map(_.event.testName).getOrElse("<unknown>"))

    envelopes.foreach { envelope =>
      if (failEvery != Int.MaxValue && Random.nextInt(failEvery) == 1) {
        throw new RuntimeException(
          s"Simulated failure when processing persistence id ${envelope.persistenceId} sequence nr ${envelope.sequenceNr} offset ${envelope.offset}")
          with NoStackTrace
      }
    }

    if (!readOnly) {
      session.withConnection { connection =>
        require(!connection.getAutoCommit)
        // TODO ps
        val values =
          envelopes.map(e => s"('${e.event.testName}', '$projectionIndex', '${e.event.payload}')").mkString(",")

        connection.createStatement().execute(s"insert into events(name, projection_id, event) values $values")
      }
    }
  }
}
