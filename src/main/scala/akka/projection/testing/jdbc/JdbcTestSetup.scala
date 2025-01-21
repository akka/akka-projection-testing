/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.jdbc

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.DispatcherSelector
import akka.persistence.jdbc.testkit.scaladsl.SchemaUtils
import akka.persistence.query.Offset
import akka.projection.Projection
import akka.projection.ProjectionBehavior
import akka.projection.ProjectionId
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.eventsourced.{ EventEnvelope => ProjectionEventEnvelope }
import akka.projection.jdbc.scaladsl.JdbcProjection
import akka.projection.scaladsl.SourceProvider
import akka.projection.testing.ConfigurablePersistentActor
import akka.projection.testing.EventProcessorSettings
import akka.projection.testing.LoggingHandler
import akka.projection.testing.TestSetup
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

final class JdbcTestSetup(settings: EventProcessorSettings)(implicit system: ActorSystem[_]) extends TestSetup {
  override val journal: TestSetup.Journal = TestSetup.JDBC

  private val hikariConfig = new HikariConfig
  hikariConfig.setJdbcUrl(system.settings.config.getString("jdbc-connection-settings.url"))
  hikariConfig.setUsername(system.settings.config.getString("jdbc-connection-settings.user"))
  hikariConfig.setPassword(system.settings.config.getString("jdbc-connection-settings.password"))
  hikariConfig.setMaximumPoolSize(20)
  hikariConfig.setAutoCommit(false)
  Class.forName("org.postgresql.Driver")
  private val dataSource = new HikariDataSource(hikariConfig)
  private val factory: HikariJdbcSessionFactory = new HikariJdbcSessionFactory(dataSource)

  private implicit val blockingDispatcher: ExecutionContext =
    system.dispatchers.lookup(DispatcherSelector.blocking())

  override def init(): Future[Done] = {
    SchemaUtils.createIfNotExists()
  }

  override def reset(): Future[Done] = Future {
    val connection = dataSource.getConnection
    val stmt = connection.createStatement()
    try {
      stmt.execute("TRUNCATE events")
      stmt.execute("TRUNCATE event_tag")
      stmt.execute("DELETE FROM event_journal CASCADE")
      connection.commit()
      Done
    } finally {
      stmt.close()
      connection.close()
    }
  }.recover {
    case error if error.getMessage.contains("does not exist") => Done
  }

  override def createProjection(projectionIndex: Int, tagIndex: Int): Behavior[ProjectionBehavior.Command] =
    createProjection(projectionIndex, tagIndex, journal)

  def createProjection(
      projectionIndex: Int,
      tagIndex: Int,
      journal: TestSetup.Journal): Behavior[ProjectionBehavior.Command] = {

    val tag = ConfigurablePersistentActor.tagFor(projectionIndex, tagIndex)

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
            new JdbcGroupedProjectionHandler(
              projectionId,
              projectionIndex,
              system,
              settings.readOnly,
              settings.failEvery))
      }
      // JdbcProjection.exactlyOnce(
      //   projectionIndex,
      //   sourceProvider,
      //   () => factory.newSession(),
      //   () => new ProjectionHandler(projectionIndex, projectionIndex, system, settings.readOnly, settings.failEvery))
    }

    ProjectionBehavior(projection)
  }

  override def countEvents(testName: String, projectionId: Int): Future[Int] = Future {
    val connection = dataSource.getConnection()
    try {
      val statement = connection.createStatement()
      val resultSet = statement.executeQuery(
        s"select count(*) from events where name = '$testName' and projection_id = $projectionId")
      val result = if (resultSet.next()) {
        resultSet.getInt("count")
      } else {
        throw new RuntimeException("Expected single row")
      }
      resultSet.close()
      result
    } finally {
      connection.close()
    }
  }

  override def writeResult(testName: String, result: String): Future[Done] = Future {
    val connection = dataSource.getConnection()
    try {
      connection.createStatement().execute(s"insert into results(name, result) values ('${testName}', '$result')")
      connection.commit()
      Done
    } finally {
      connection.close()
    }
  }

  override def readResult(testName: String): Future[String] = Future {
    val connection = dataSource.getConnection
    try {
      val ps = connection.prepareStatement(s"select * from results where name = ?")
      ps.setString(1, testName)
      val resultSet = ps.executeQuery()
      val result = if (resultSet.next()) {
        resultSet.getString("result")
      } else {
        // no result yet
        "not finished"
      }
      resultSet.close()
      result
    } finally {
      connection.close()
    }
  }

  override def cleanUp(): Unit = {
    dataSource.close()
  }
}
