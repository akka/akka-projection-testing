/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success

import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.scaladsl.Behaviors

object TestValidation {

  sealed trait ValidationResult
  case object Pass extends ValidationResult
  case object Fail extends ValidationResult
  case object NoChange extends ValidationResult

  sealed trait Message
  case object Check extends Message
  final case class CheckResults(results: Seq[Int]) extends Message
  case object CheckTimeout extends Message
  final case class CheckFailure(error: Throwable) extends Message
  case object ResultRecorded extends Message
  final case class ResultFailure(error: Throwable) extends Message

  def apply(
      testName: String,
      nrProjections: Int,
      expectedNrEvents: Long,
      timeout: FiniteDuration,
      setup: TestSetup): Behavior[Message] = {
    import scala.concurrent.duration._
    Behaviors.setup { ctx =>
      import ctx.executionContext

      var checksSinceChange = 0
      var previousResult: Seq[Int] = Nil

      def countEvents(): Unit = {
        val counts = Future.sequence {
          (0 until nrProjections).map { projectionId =>
            setup.countEvents(testName, projectionId)
          }
        }
        ctx.pipeToSelf(counts) {
          case Success(results) => CheckResults(results)
          case Failure(error)   => CheckFailure(error)
        }
      }

      def validate(results: Seq[Int]): ValidationResult = {
        if (results.forall(_ == expectedNrEvents)) {
          Pass
        } else {
          if (results == previousResult) {
            checksSinceChange += 1
          } else {
            checksSinceChange = 0
          }
          previousResult = results
          if (checksSinceChange > 20) {
            NoChange
          } else {
            Fail
          }
        }
      }

      def writeResult(result: String): Unit = {
        ctx.pipeToSelf(setup.writeResult(testName, result)) {
          case Success(_)     => ResultRecorded
          case Failure(error) => ResultFailure(error)
        }
      }

      Behaviors.withTimers { timers =>
        ctx.self ! Check
        timers.startSingleTimer(CheckTimeout, timeout)

        Behaviors
          .receiveMessage[Message] {
            case Check =>
              countEvents()
              Behaviors.same
            case CheckResults(results) =>
              validate(results) match {
                case Pass =>
                  ctx.log.info("TestPhase: Validated")
                  writeResult("pass")
                case Fail =>
                  timers.startSingleTimer(Check, 2.seconds)
                case NoChange =>
                  ctx.log.error("TestPhase: Results are not changing")
                  writeResult("stuck")
              }
              Behaviors.same
            case CheckTimeout =>
              ctx.log.error("TestPhase: Timed out")
              writeResult("timeout")
              Behaviors.same
            case CheckFailure(error) =>
              ctx.log.error("TestPhase: Validation check failed", error)
              Behaviors.stopped
            case ResultRecorded =>
              ctx.log.info("TestPhase: Result recorded")
              Behaviors.stopped
            case ResultFailure(error) =>
              ctx.log.error("TestPhase: Recording result failed", error)
              Behaviors.stopped
          }
          .receiveSignal { case (_, PostStop) =>
            Behaviors.stopped
          }
      }

    }
  }
}
