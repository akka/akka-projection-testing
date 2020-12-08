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

import akka.actor.typed.ActorSystem
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import org.slf4j.{ Logger, LoggerFactory }

import scala.util.Try

class ProjectionHandler(tag: String, projectionId: Int, system: ActorSystem[_])
    extends JdbcHandler[EventEnvelope[ConfigurablePersistentActor.Event], HikariJdbcSession] {
  private val log: Logger = LoggerFactory.getLogger(getClass)
  private var startTime = System.nanoTime()
  private var count = 0

  override def process(session: HikariJdbcSession, envelope: EventEnvelope[ConfigurablePersistentActor.Event]): Unit = {
    log.trace("Event {} for tag {} test {}", envelope.event.payload, tag, envelope.event.testName)
    count += 1
    if (count == 1000) {
      val durationMs = (System.nanoTime() - startTime) / 1000 / 1000
      log.info("Projection [{}] throughput [{}] events/s in [{}] ms", tag, 1000 * count / durationMs, durationMs)
      count = 0
      startTime = System.nanoTime()
    }
    session.withConnection { connection =>
      require(!connection.getAutoCommit)
      val pstmt = connection.prepareStatement("insert into events(name, projection_id, event) values (?, ?, ?)")
      pstmt.setString(1, envelope.event.testName)
      pstmt.setInt(2, projectionId)
      pstmt.setString(3, envelope.event.payload)
      pstmt.executeUpdate()
      Try(pstmt.close())
    }
  }
}

// when using this consider reducing failure otherwise a high change of at least one grouped evenvelope causing an error
// and no progress will be made
class GroupedProjectionHandler(tag: String, system: ActorSystem[_])
    extends JdbcHandler[Seq[EventEnvelope[ConfigurablePersistentActor.Event]], HikariJdbcSession] {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  override def process(
      session: HikariJdbcSession,
      envelopes: Seq[EventEnvelope[ConfigurablePersistentActor.Event]]): Unit = {
    log.trace(
      "Persisting {} events for tag {} for test {}",
      envelopes.size,
      tag,
      envelopes.headOption.map(_.event.testName).getOrElse("<unknown>"))
    session.withConnection { connection =>
      require(!connection.getAutoCommit)
      val values = envelopes.map(e => s"('${e.event.testName}', '${e.event.payload}')").mkString(",")

      connection.createStatement().execute(s"insert into events(name, event) values $values")
    }
  }
}
