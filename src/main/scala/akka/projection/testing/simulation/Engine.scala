/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.simulation

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.pattern.StatusReply
import akka.projection.testing.ConfigurablePersistentActor
import akka.projection.testing.EventProcessorSettings
import akka.projection.testing.TestSetup
import akka.projection.testing.TestValidation
import akka.stream.scaladsl.Source
import akka.util.PrettyDuration
import akka.util.Timeout
import org.slf4j.Logger

object Engine {

  sealed trait Command
  sealed trait Response

  final case class RunSimulation(runId: String, settings: SimulationSettings, replyTo: ActorRef[StatusReply[Response]])
      extends Command

  case object SimulationStarted extends Response

  final case class TellSimulationEntities(
      minSlice: Int,
      maxSlice: Int,
      minEntity: Int,
      maxEntity: Int,
      command: ConfigurablePersistentActor.Command)
      extends Command

  object Settings {
    def tick(settings: Option[EngineSettings]): FiniteDuration =
      settings.flatMap(_.tick).getOrElse(200.millis)

    def parallelism(settings: Option[EngineSettings]): Int =
      settings.flatMap(_.parallelism).getOrElse(4)

    def ackPersists(settings: Option[EngineSettings]): Boolean =
      settings.flatMap(_.ackPersists).getOrElse(false)

    def validationTimeout(settings: Option[EngineSettings]): FiniteDuration =
      settings.flatMap(_.validationTimeout).getOrElse(10.seconds)
  }

  def apply(
      processorSettings: EventProcessorSettings,
      shardedEntity: ActorRef[ShardingEnvelope[ConfigurablePersistentActor.Command]],
      setup: TestSetup): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage[Command] {
        case RunSimulation(runId, settings, replyTo) =>
          import context.system
          val persist: Event => Future[Done] =
            if (Settings.ackPersists(settings.engine)) persistEventWithAck(runId, shardedEntity)
            else persistEvent(runId, shardedEntity)
          context.spawn(Runner(processorSettings, persist, setup), s"runner-$runId") ! Runner.Start(runId, settings)
          replyTo ! StatusReply.success(SimulationStarted)
          Behaviors.same

        case TellSimulationEntities(minSlice, maxSlice, minEntity, maxEntity, command) =>
          val entityType = ConfigurablePersistentActor.Key.name
          for (slice <- minSlice to maxSlice; entity <- minEntity to maxEntity) {
            val entityId = EntityId.create(entityType, Slice(slice), entity)
            shardedEntity ! ShardingEnvelope(entityId.toString, command)
          }
          Behaviors.same
      }
    }

  private def persistEvent(
      testName: String,
      shardedEntity: ActorRef[ShardingEnvelope[ConfigurablePersistentActor.Command]])(event: Event): Future[Done] = {
    shardedEntity ! ShardingEnvelope(
      event.activity.entityId.toString,
      ConfigurablePersistentActor.PersistSingle(testName, event.identifier, event.dataBytes))
    Future.successful(Done)
  }

  private def persistEventWithAck(
      testName: String,
      entity: ActorRef[ShardingEnvelope[ConfigurablePersistentActor.Command]])(event: Event)(implicit
      system: ActorSystem[_]): Future[Done] = {
    implicit val timeout: Timeout = 1.second
    entity.askWithStatus[Done] { replyTo =>
      ShardingEnvelope(
        event.activity.entityId.toString,
        ConfigurablePersistentActor.PersistSingleAndAck(testName, event.identifier, event.dataBytes, replyTo))
    }
  }
}

object Runner {

  sealed trait Command

  final case class Start(runId: String, settings: SimulationSettings) extends Command

  final case class Summary(totalActivities: Long, totalEvents: Long)

  object Summary {
    val Empty = Summary(0L, 0L)
  }

  final case class Validate(summary: Summary) extends Command

  final case class Fail(error: Throwable) extends Command

  def apply(
      processorSettings: EventProcessorSettings,
      persist: Event => Future[Done],
      setup: TestSetup): Behavior[Command] =
    Behaviors.setup { context =>
      context.setLoggerName(Runner.getClass)

      Behaviors.receiveMessagePartial[Command] { case Start(runId, settings) =>
        val startTime = System.nanoTime()
        val summary = run(runId, settings, persist, context.log)(context.system)

        context.pipeToSelf(summary) {
          case Success(summary) => Validate(summary)
          case Failure(error)   => Fail(error)
        }

        Behaviors
          .receiveMessagePartial[Command] {
            case Validate(Summary(totalActivities, totalEvents)) =>
              val finishTime = System.nanoTime()
              val totalTime = finishTime - startTime
              context.log.info(
                "Simulation completed in: {}. Total activities: {}. Total events: {}. Averages: {} activities/s, {} events/activity, {} events/s.",
                PrettyDuration.format(totalTime.nanos),
                totalActivities,
                totalEvents,
                totalActivities / math.max(totalTime.nanos.toSeconds, 1),
                totalEvents / totalActivities,
                totalEvents / math.max(totalTime.nanos.toSeconds, 1))
              if (processorSettings.readOnly) {
                context.log.info("No validation (read-only)")
                Behaviors.stopped
              } else {
                context.log.info("Starting validation")
                val validation = context.spawn(
                  TestValidation(
                    runId,
                    processorSettings.nrProjections,
                    totalEvents,
                    Engine.Settings.validationTimeout(settings.engine),
                    setup),
                  s"validation-$runId")
                context.watch(validation)
                Behaviors.same
              }
            case Fail(error) =>
              context.log.error("Simulation failed", error)
              Behaviors.stopped
          }
          .receiveSignal { case (signalContext, Terminated(_)) =>
            signalContext.log.info("Validation completed")
            Behaviors.stopped
          }
      }
    }

  def run(id: String, settings: SimulationSettings, process: Event => Future[Done], log: Logger)(implicit
      system: ActorSystem[_]): Future[Summary] = {
    implicit val ec = system.executionContext
    val generator = SimulationGenerator(settings)
    val tickInterval = Engine.Settings.tick(settings.engine)
    val parallelism = Engine.Settings.parallelism(settings.engine)
    val reportEvery = (5.seconds / tickInterval) * parallelism
    val groupedEvents = Source.fromIterator(() => generator.groupedEvents(tickInterval))
    val ticks = Source.tick(0.seconds, tickInterval, ())
    val startTime = System.nanoTime()
    ticks
      .zip(groupedEvents)
      .mapConcat { case (_, events) =>
        events.groupBy(_.activity.id.seqNr % parallelism).values
      }
      .mapAsyncUnordered(parallelism) { group =>
        Future.foldLeft(group.map(event => process(event).map(_ => event)(ExecutionContext.parasitic)))(Summary.Empty) {
          case (Summary(totalActivities, totalEvents), event) =>
            val newTotalActivities = if (event.seqNr == 1) totalActivities + 1 else totalActivities
            val newTotalEvents = totalEvents + 1
            Summary(newTotalActivities, newTotalEvents)
        }
      }
      .runFold((0, Summary.Empty)) {
        case ((totalGroups, Summary(totalActivities, totalEvents)), Summary(groupActivities, groupEvents)) =>
          val newTotalActivities = totalActivities + groupActivities
          val newTotalEvents = totalEvents + groupEvents
          if (totalGroups % reportEvery == 0) {
            val runningTime = System.nanoTime() - startTime
            log.info(
              "Generated {} events. Running time: {}. Average rate of {} events/s.",
              newTotalEvents,
              PrettyDuration.format(runningTime.nanos),
              newTotalEvents / math.max(runningTime.nanos.toSeconds, 1))
          }
          (totalGroups + 1, Summary(newTotalActivities, newTotalEvents))
      }
      .map { case (_, summary) => summary }(ExecutionContext.parasitic)
  }
}
