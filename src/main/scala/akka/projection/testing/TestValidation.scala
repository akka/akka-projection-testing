/*
 * Copyright 2020 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.projection.testing

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import javax.sql.DataSource

import scala.concurrent.duration.FiniteDuration

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
          val connection = source.getConnection
          try {
            val resultSet = connection
              .createStatement()
              .executeQuery(s"select count(*) from events where name = '$testName' and projection_id = $projectionId")
            if (resultSet.next()) {
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
          } finally {
            connection.close()
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

      Behaviors.withTimers { timers =>
        timers.startTimerAtFixedRate("test", 2.seconds)
        timers.startSingleTimer("timeout", timeout)
        Behaviors.receiveMessage {
          case "test" =>
            validate() match {
              case Pass =>
                ctx.log.info("TestPhase: Validated. Stopping")
                Behaviors.stopped
              case Fail =>
                Behaviors.same
              case NoChange =>
                ctx.log.error("TestPhase: Results are not changing. Stopping")
                Behaviors.stopped
            }
          case "timeout" =>
            ctx.log.error("TestPhase: Timout out")
            Behaviors.stopped
        }
      }
    }
  }
}
