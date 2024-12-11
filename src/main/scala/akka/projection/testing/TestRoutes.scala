/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing

import javax.sql.DataSource

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.projection.testing.Main.Journal
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import akka.util.Timeout
import org.slf4j.LoggerFactory
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat

object TestRoutes {

  case class RunTest(
      name: String,
      nrActors: Int,
      messagesPerActor: Int,
      bytesPerEvent: Int,
      concurrentActors: Int,
      timeout: Int)

  case class ActivateActors(name: String, nrActors: Int)

  case class Response(testName: String, expectedMessages: Long)

  implicit val runTestFormat: RootJsonFormat[RunTest] = jsonFormat6(RunTest)
  implicit val activateActorsFormat: RootJsonFormat[ActivateActors] = jsonFormat2(ActivateActors)
  implicit val testResultFormat: RootJsonFormat[Response] = jsonFormat2(Response)
}

class TestRoutes(loadGeneration: ActorRef[LoadGeneration.Command], dataSource: DataSource, journal: Journal)(implicit
    val system: ActorSystem[_]) {

  private val log = LoggerFactory.getLogger(classOf[TestRoutes])

  import TestRoutes._
  import system.executionContext

  val route: Route = {
    concat(
      get {
        pathPrefix("test" / Segment) { testName =>
          println("get test result: " + testName)
          val connection = dataSource.getConnection
          val result =
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
          println("Returning " + result)
          complete(result)
        }
      },
      post {
        path("test") {
          entity(as[RunTest]) { runTest =>
            implicit val timeout: Timeout = Timeout(60.seconds)
            import akka.actor.typed.scaladsl.AskPattern._
            val name = if (runTest.name.isBlank) s"test-${System.currentTimeMillis()}" else runTest.name
            val keyspace = system.settings.config.getString("akka.persistence.cassandra.journal.keyspace")

            val truncates: Seq[Future[Any]] = journal match {
              case Main.Cassandra =>
                val session = CassandraSessionRegistry(system).sessionFor("akka.persistence.cassandra")
                List(
                  session.executeWrite(s"truncate $keyspace.tag_views"),
                  session.executeWrite(s"truncate $keyspace.tag_write_progress"),
                  session.executeWrite(s"truncate $keyspace.tag_scanning"),
                  session.executeWrite(s"truncate $keyspace.messages"),
                  session.executeWrite(s"truncate $keyspace.all_persistence_ids"))
              case Main.JDBC =>
                val truncate = Future {
                  val connection = dataSource.getConnection
                  val stmt = connection.createStatement()
                  try {
                    stmt.execute("TRUNCATE events")
                    stmt.execute("TRUNCATE event_tag")
                    stmt.execute("DELETE FROM event_journal CASCADE")
                    connection.commit()
                  } finally {
                    stmt.close()
                    connection.close()
                  }
                }
                Seq(truncate)
              case Main.R2DBC =>
                val truncate = Future {
                  val connection = dataSource.getConnection
                  val stmt = connection.createStatement()
                  try {
                    stmt.execute("TRUNCATE events")
                    // stmt.execute("DELETE FROM event_journal CASCADE")
                    connection.commit()
                  } finally {
                    stmt.close()
                    connection.close()
                  }
                }
                Seq(truncate)
            }

            val test = for {
              _ <- Future.sequence(truncates).recover {
                case t if t.getMessage.contains("does not exist") => Done
              }
              result <- {
                log.info("Finished cleanup. Starting load generation")
                loadGeneration.ask(replyTo =>
                  LoadGeneration.RunTest(
                    name,
                    runTest.nrActors,
                    runTest.messagesPerActor,
                    runTest.bytesPerEvent,
                    replyTo,
                    runTest.concurrentActors,
                    runTest.timeout))
              }
            } yield result

            onComplete(test) {
              case Success(summary) =>
                complete(Response(summary.name, summary.expectedMessages))
              case Failure(t) =>
                complete(StatusCodes.InternalServerError, s"test failed to start: " + t.getMessage)
            }
          }
        }
      },
      post {
        path("activate") {
          entity(as[ActivateActors]) { activateActors =>
            loadGeneration ! LoadGeneration.ActivateActors(activateActors.name, activateActors.nrActors)
            complete(StatusCodes.Accepted)
          }
        }
      })
  }

}
