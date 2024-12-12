/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.scaladsl.Behaviors

object TestValidation {

  sealed trait ValidationResult
  case object Pass extends ValidationResult
  case object Fail extends ValidationResult
  case object NoChange extends ValidationResult

  def apply(
      testName: String,
      nrProjections: Int,
      expectedNrEvents: Long,
      timeout: FiniteDuration,
      setup: TestSetup): Behavior[String] = {
    import scala.concurrent.duration._
    Behaviors.setup { ctx =>
      import ctx.executionContext

      // Don't do this at home
      var checksSinceChange = 0
      var previousResult: Seq[Int] = Nil

      // FIXME: awaiting futures (and running on blocking dispatcher)

      def validate(): ValidationResult = {
        val results: Seq[Int] = Await.result(
          Future.sequence {
            (0 until nrProjections).map { projectionId =>
              setup.countEvents(testName, projectionId)
            }
          },
          10.seconds)

        if (results.forall(_ == expectedNrEvents)) {
          Pass
        } else {
          if (results == previousResult) {
            checksSinceChange += 1
          } else {
            checksSinceChange = 0
          }
          previousResult = results
          if (checksSinceChange > 20) { // no change for 40 seconds
            NoChange
          } else {
            Fail
          }
        }
      }

      def writeResult(result: String): Unit = {
        Await.ready(setup.writeResult(testName, result), 5.seconds)
      }

      Behaviors.withTimers { timers =>
        timers.startTimerAtFixedRate("test", 2.seconds)
        timers.startSingleTimer("timeout", timeout)

        Behaviors
          .receiveMessage[String] {
            case "test" =>
              validate() match {
                case Pass =>
                  writeResult("pass")
                  ctx.log.info("TestPhase: Validated. Stopping")
                  Behaviors.stopped
                case Fail =>
                  Behaviors.same
                case NoChange =>
                  writeResult("stuck")
                  ctx.log.error("TestPhase: Results are not changing. Stopping")
                  Behaviors.stopped
              }
            case "timeout" =>
              ctx.log.error("TestPhase: Timed out. Stopping")
              writeResult("timeout")
              Behaviors.stopped
          }
          .receiveSignal { case (_, PostStop) =>
            Behaviors.stopped
          }
      }

    }
  }
}
