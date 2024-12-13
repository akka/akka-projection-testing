/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing

import scala.concurrent.Future

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.dynamodb.query.scaladsl.DynamoDBReadJournal
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import akka.projection.ProjectionBehavior
import akka.projection.testing.cassandra.CassandraTestSetup
import akka.projection.testing.dynamodb.DynamoDBTestSetup
import akka.projection.testing.jdbc.JdbcTestSetup
import akka.projection.testing.r2dbc.R2dbcTestSetup

object TestSetup {
  sealed trait Journal {
    def journalPluginId: String
    def readJournal: String
  }

  case object R2DBC extends Journal {
    override val journalPluginId: String = "akka.persistence.r2dbc.journal"
    override def readJournal: String = R2dbcReadJournal.Identifier
  }

  case object DynamoDB extends Journal {
    override val journalPluginId: String = "akka.persistence.dynamodb.journal"
    override def readJournal: String = DynamoDBReadJournal.Identifier
  }

  case object JDBC extends Journal {
    override val journalPluginId: String = "jdbc-journal"
    override def readJournal: String = JdbcReadJournal.Identifier
  }

  case object Cassandra extends Journal {
    override val journalPluginId: String = "akka.persistence.cassandra.journal"
    override def readJournal: String = CassandraReadJournal.Identifier
  }

  def apply(settings: EventProcessorSettings)(implicit system: ActorSystem[_]): TestSetup = {
    system.settings.config.getString("akka.persistence.journal.plugin") match {
      case TestSetup.R2DBC.journalPluginId     => new R2dbcTestSetup(settings)
      case TestSetup.DynamoDB.journalPluginId  => new DynamoDBTestSetup(settings)
      case TestSetup.JDBC.journalPluginId      => new JdbcTestSetup(settings)
      case TestSetup.Cassandra.journalPluginId => new CassandraTestSetup(settings)
      case other                               => throw new IllegalArgumentException(s"Unknown journal [$other]")
    }
  }
}

abstract class TestSetup {
  def journal: TestSetup.Journal

  def init(): Future[Done]

  def reset(): Future[Done]

  def createProjection(projectionIndex: Int, tagIndexOrSliceIndex: Int): Behavior[ProjectionBehavior.Command]

  def countEvents(testName: String, projectionId: Int): Future[Int]

  def writeResult(testName: String, result: String): Future[Done]

  def readResult(testName: String): Future[String]

  def cleanUp(): Unit
}
