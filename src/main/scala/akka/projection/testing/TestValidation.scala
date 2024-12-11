/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing

import javax.sql.DataSource

import scala.concurrent.duration.FiniteDuration

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop

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
      source: DataSource): Behavior[String] = {
    import scala.concurrent.duration._
    Behaviors.setup { ctx =>
      // Don't do this at home
      var checksSinceChange = 0
      var previousResult: Seq[Int] = Nil
      def validate(): ValidationResult = {
        val results: Seq[Int] = (0 until nrProjections).map { projectionId =>
          val conn = source.getConnection()
          try {
            val statement = conn.createStatement()
            val resultSet = statement.executeQuery(
              s"select count(*) from events where name = '$testName' and projection_id = $projectionId")
            val result = if (resultSet.next()) {
              val count = resultSet.getInt("count")
              ctx.log.info(
                "Test [{}]. Projection id: [{}]. Expected {} got {}!",
                testName,
                projectionId,
                expectedNrEvents,
                count)
              count
            } else {
              throw new RuntimeException("Expected single row")
            }
            resultSet.close()
            result
          } finally {
            conn.close()
          }
        }

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
        val conn = source.getConnection()
        try {
          conn.createStatement().execute(s"insert into results(name, result) values ('${testName}', '$result')")
          conn.commit()
        } finally {
          conn.close()
        }
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
              ctx.log.error("TestPhase: Timout out")
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
