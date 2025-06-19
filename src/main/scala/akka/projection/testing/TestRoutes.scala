/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing

import java.util.UUID

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.projection.testing.simulation.Engine
import akka.projection.testing.simulation.SimulationJsonFormat
import akka.projection.testing.simulation.SimulationSettings
import akka.util.Timeout
import org.slf4j.LoggerFactory
import spray.json.RootJsonFormat

object TestRoutes {
  import SimulationJsonFormat._

  final case class RunTest(
      name: Option[String],
      nrActors: Int,
      messagesPerActor: Int,
      bytesPerEvent: Int,
      concurrentActors: Int,
      timeout: Int)

  final case class ActivateTestActors(name: String, nrActors: Int)

  final case class RunTestResponse(testName: String, expectedMessages: Long)

  final case class RunSimulation(simulation: SimulationSettings, resetBeforeRun: Option[Boolean])

  final case class RunSimulationResponse(runId: String)

  final case class ActivateSimulationEntities(minSlice: Int, maxSlice: Int, minEntity: Int, maxEntity: Int)

  final case class PassivateSimulationEntities(minSlice: Int, maxSlice: Int, minEntity: Int, maxEntity: Int)

  implicit val runTestFormat: RootJsonFormat[RunTest] = jsonFormat6(RunTest)
  implicit val activateTestActorsFormat: RootJsonFormat[ActivateTestActors] = jsonFormat2(ActivateTestActors)
  implicit val runTestResponseFormat: RootJsonFormat[RunTestResponse] = jsonFormat2(RunTestResponse)

  implicit val runSimulationFormat: RootJsonFormat[RunSimulation] = jsonFormat2(RunSimulation)
  implicit val runSimulationResponseFormat: RootJsonFormat[RunSimulationResponse] = jsonFormat1(RunSimulationResponse)
  implicit val activateSimulationEntitiesFormat: RootJsonFormat[ActivateSimulationEntities] =
    jsonFormat4(ActivateSimulationEntities)
  implicit val passivateSimulationEntitiesFormat: RootJsonFormat[PassivateSimulationEntities] =
    jsonFormat4(PassivateSimulationEntities)
}

class TestRoutes(
    loadGeneration: ActorRef[LoadGeneration.Command],
    simulationEngine: ActorRef[Engine.Command],
    setup: TestSetup)(implicit val system: ActorSystem[_]) {

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
            val name = runTest.name.getOrElse(s"test-${System.currentTimeMillis()}")
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
                complete(RunTestResponse(summary.name, summary.expectedMessages))
              case Failure(t) =>
                complete(StatusCodes.InternalServerError, s"test failed to start: " + t.getMessage)
            }
          }
        }
      },
      post {
        path("activate") {
          entity(as[ActivateTestActors]) { activateActors =>
            loadGeneration ! LoadGeneration.ActivateActors(activateActors.name, activateActors.nrActors)
            complete(StatusCodes.Accepted)
          }
        }
      },
      post {
        path("simulation" / "run") {
          entity(as[RunSimulation]) { run =>
            val runId = UUID.randomUUID().toString
            log.info("Running simulation [{}] ...", runId)

            val reset = run.resetBeforeRun.getOrElse(true)

            val started =
              for {
                _ <- if (reset) setup.reset() else Future.successful(Done)
                response <- {
                  log.info("Starting simulation [{}] {} ...", runId, if (reset) "(after reset)" else "(without reset)")
                  implicit val timeout: Timeout = Timeout(10.seconds)
                  simulationEngine.askWithStatus[Engine.Response](replyTo =>
                    Engine.RunSimulation(runId, run.simulation, replyTo))
                }
              } yield response

            onComplete(started) {
              case Success(_) =>
                complete(RunSimulationResponse(runId))
              case Failure(error) =>
                complete(StatusCodes.InternalServerError, s"Simulation [$runId] failed to start: " + error.getMessage)
            }
          }
        }
      },
      get {
        pathPrefix("simulation" / "status" / Segment) { name =>
          complete(setup.readResult(name))
        }
      },
      post {
        path("simulation" / "activate") {
          entity(as[ActivateSimulationEntities]) {
            case ActivateSimulationEntities(minSlice, maxSlice, minEntity, maxEntity) =>
              simulationEngine ! Engine.TellSimulationEntities(
                minSlice,
                maxSlice,
                minEntity,
                maxEntity,
                ConfigurablePersistentActor.WakeUp)
              complete(StatusCodes.Accepted)
          }
        }
      },
      post {
        path("simulation" / "passivate") {
          entity(as[PassivateSimulationEntities]) {
            case PassivateSimulationEntities(minSlice, maxSlice, minEntity, maxEntity) =>
              simulationEngine ! Engine.TellSimulationEntities(
                minSlice,
                maxSlice,
                minEntity,
                maxEntity,
                ConfigurablePersistentActor.Stop)
              complete(StatusCodes.Accepted)
          }
        }
      })
  }

}
