/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing

import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
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

class TestRoutes(loadGeneration: ActorRef[LoadGeneration.Command], setup: TestSetup)(implicit
    val system: ActorSystem[_]) {

  private val log = LoggerFactory.getLogger(classOf[TestRoutes])

  import TestRoutes._
  import system.executionContext

  val route: Route = {
    concat(
      get {
        pathPrefix("test" / Segment) { testName =>
          complete(setup.readResult(testName))
        }
      },
      post {
        path("test") {
          entity(as[RunTest]) { runTest =>
            implicit val timeout: Timeout = Timeout(60.seconds)
            import akka.actor.typed.scaladsl.AskPattern._
            val name = if (runTest.name.isBlank) s"test-${System.currentTimeMillis()}" else runTest.name
            log.info("Starting test run [{}] ...", name)

            val test = for {
              _ <- setup.reset()
              result <- {
                log.info("Finished reset. Starting load generation...")
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
