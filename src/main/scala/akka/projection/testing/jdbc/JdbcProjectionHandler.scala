/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.jdbc

import scala.util.Try

import akka.projection.ProjectionId
import akka.projection.eventsourced.EventEnvelope
import akka.projection.jdbc.scaladsl.JdbcHandler
import akka.projection.testing.ConfigurablePersistentActor
import akka.projection.testing.TestProjectionHandler

class JdbcProjectionHandler(val projectionId: ProjectionId, projectionIndex: Int, readOnly: Boolean, val failEvery: Int)
    extends JdbcHandler[EventEnvelope[ConfigurablePersistentActor.Event], HikariJdbcSession]
    with TestProjectionHandler[EventEnvelope] {

  override def process(session: HikariJdbcSession, envelope: EventEnvelope[ConfigurablePersistentActor.Event]): Unit = {
    testProcessing(envelope)

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
class JdbcGroupedProjectionHandler(
    val projectionId: ProjectionId,
    projectionIndex: Int,
    readOnly: Boolean,
    val failEvery: Int)
    extends JdbcHandler[Seq[EventEnvelope[ConfigurablePersistentActor.Event]], HikariJdbcSession]
    with TestProjectionHandler[EventEnvelope] {

  override def process(
      session: HikariJdbcSession,
      envelopes: Seq[EventEnvelope[ConfigurablePersistentActor.Event]]): Unit = {
    testProcessingGroup(envelopes)

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
