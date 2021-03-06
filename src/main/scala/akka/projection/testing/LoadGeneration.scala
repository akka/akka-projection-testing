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

import akka.actor.Scheduler
import akka.pattern.retry
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter.TypedActorSystemOps
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, DispatcherSelector, Terminated }
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.pattern.StatusReply
import akka.projection.testing.LoadGeneration.{ RunTest, TestSummary }
import akka.projection.testing.LoadTest.Start
import akka.stream.scaladsl.Source
import akka.util.Timeout
import akka.{ Done, NotUsed }
import javax.sql.DataSource
import org.slf4j.{ Logger, LoggerFactory }

import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.concurrent.duration.{ DurationInt, DurationLong }
import scala.util.{ Failure, Success }

object LoadGeneration {

  case class RunTest(
      name: String,
      actors: Int,
      eventsPerActor: Int,
      bytesPerEvent: Int,
      reply: ActorRef[TestSummary],
      numberOfConcurrentActors: Int,
      timeout: Long)

  case class TestSummary(name: String, expectedMessages: Long)

  def apply(
      settings: EventProcessorSettings,
      shardRegion: ActorRef[ShardingEnvelope[ConfigurablePersistentActor.Command]],
      source: DataSource): Behavior[RunTest] = Behaviors.setup { ctx =>
    Behaviors.receiveMessage[RunTest] { rt: RunTest =>
      ctx.spawn(LoadTest(settings, rt.name, shardRegion, source), s"test-${rt.name}") ! Start(rt)
      Behaviors.same
    }
  }

}

object LoadTest {

  val threadSafeLog: Logger = LoggerFactory.getLogger("load-test")

  sealed trait Command

  case class Start(test: RunTest) extends Command

  private case class StartValidation() extends Command

  private case class LoadGenerationFailed(t: Throwable) extends Command

  def apply(
      settings: EventProcessorSettings,
      testName: String,
      shardRegion: ActorRef[ShardingEnvelope[ConfigurablePersistentActor.Command]],
      source: DataSource): Behavior[Command] = Behaviors.setup { ctx =>
    import akka.actor.typed.scaladsl.AskPattern._
    // asks are retried
    implicit val timeout: Timeout = 1.seconds // the ask is for all events for an actor so this is likely to be large
    implicit val system: ActorSystem[Nothing] = ctx.system
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val scheduler: Scheduler = system.toClassic.scheduler

    Behaviors.receiveMessagePartial[Command] {
      case Start(RunTest(name, actors, eventsPerActor, bytesPerEvent, replyTo, numberOfConcurrentActors, t)) =>
        threadSafeLog.info("TestPhase: Starting load generation")
        val expected: Int = actors * eventsPerActor
        val total = expected * settings.nrProjections
        replyTo ! TestSummary(name, expected * settings.nrProjections)
        val startTime = System.nanoTime()

        // The operation is idempotent so retries will not affect the final event count
        val testRun: Source[StatusReply[Done], NotUsed] =
          Source(1 to actors).mapAsyncUnordered(numberOfConcurrentActors) { id =>
            val pid = s"${testName}-$id"
            val retried: Future[StatusReply[Done]] = retry(
              () => {
                threadSafeLog.debug("Sending message to pid {}", pid)
                shardRegion.ask[StatusReply[Done]] { replyTo =>
                  ShardingEnvelope(
                    pid,
                    ConfigurablePersistentActor
                      .PersistAndAck(eventsPerActor, s"actor-$id-message", bytesPerEvent, replyTo, testName))
                }
              },
              500, // this is retried quite quickly
              1.second,
              5.seconds,
              0.1)

            retried.transform {
              case s @ Success(_) => s
              case Failure(t) =>
                Failure(new RuntimeException(s"Load generation failed for persistence id ${pid}", t)) // this will be an ask timeout
            }
          }

        ctx.pipeToSelf(testRun.run()) {
          case Success(_) => StartValidation()
          case Failure(t) => LoadGenerationFailed(t)
        }

        Behaviors
          .receiveMessagePartial[Command] {
            case StartValidation() =>
              val finishTime = System.nanoTime()
              val totalTime = finishTime - startTime
              ctx.log.info(
                "TestPhase: Starting validation. All writes acked in: {}. Rough rate {}",
                akka.util.PrettyDuration.format(totalTime.nanos),
                total / math.max(totalTime.nanos.toSeconds, 1))
              val validation = ctx.spawn(
                TestValidation(testName, settings.nrProjections, expected, t.seconds, source),
                s"TestValidation=$testName",
                DispatcherSelector.blocking())
              ctx.watch(validation)
              Behaviors.same
            case LoadGenerationFailed(t) =>
              ctx.log.error("TestPhase: Load generation failed", t)
              Behaviors.stopped
          }
          .receiveSignal {
            case (ctx, Terminated(_)) =>
              val finishTime = System.nanoTime()
              val totalTime = finishTime - startTime
              ctx.log.info(
                "TestPhase: Validation finished for test {}, terminating. Total time for {} events. {}. Rough rate: {}",
                testName,
                total,
                akka.util.PrettyDuration.format(totalTime.nanos),
                total / math.max(1, totalTime.nanos.toSeconds))
              Behaviors.stopped
          }
    }
  }

}
