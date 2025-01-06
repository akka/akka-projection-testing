/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.cassandra

import scala.concurrent.Future

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.projection.ProjectionBehavior
import akka.projection.testing.EventProcessorSettings
import akka.projection.testing.TestSetup
import akka.projection.testing.jdbc.JdbcTestSetup
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry

final class CassandraTestSetup(settings: EventProcessorSettings)(implicit system: ActorSystem[_]) extends TestSetup {
  override val journal: TestSetup.Journal = TestSetup.Cassandra

  private val keyspace = system.settings.config.getString("akka.persistence.cassandra.journal.keyspace")

  private val jdbc = new JdbcTestSetup(settings)

  override def init(): Future[Done] =
    Future.successful(Done)

  override def reset(): Future[Done] = {
    import system.executionContext
    val session = CassandraSessionRegistry(system).sessionFor("akka.persistence.cassandra")
    Future
      .sequence(
        Seq(
          session.executeWrite(s"truncate $keyspace.tag_views"),
          session.executeWrite(s"truncate $keyspace.tag_write_progress"),
          session.executeWrite(s"truncate $keyspace.tag_scanning"),
          session.executeWrite(s"truncate $keyspace.messages"),
          session.executeWrite(s"truncate $keyspace.all_persistence_ids")))
      .flatMap(_ => jdbc.reset())
  }

  override def createProjection(projectionIndex: Int, tagIndex: Int): Behavior[ProjectionBehavior.Command] =
    jdbc.createProjection(projectionIndex, tagIndex, journal)

  override def countEvents(testName: String, projectionId: Int): Future[Int] =
    jdbc.countEvents(testName, projectionId)

  override def writeResult(testName: String, result: String): Future[Done] =
    jdbc.writeResult(testName, result)

  override def readResult(testName: String): Future[String] =
    jdbc.readResult(testName)

  override def finish(): Unit =
    jdbc.finish()
}
