/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing

import java.util.concurrent.ThreadLocalRandom

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior

object ConfigurablePersistentActor {

  val Key: EntityTypeKey[Command] = EntityTypeKey[Command]("configurable")

  def init(settings: EventProcessorSettings, system: ActorSystem[_]): ActorRef[ShardingEnvelope[Command]] = {
    ClusterSharding(system).init(
      Entity(Key)(ctx => apply(settings, ctx.entityId)).withRole("write-model").withStopMessage(Stop))
  }

  sealed trait Command

  case object WakeUp extends Command with CborSerializable

  case object Stop extends Command with CborSerializable

  final case class PersistAndAck(
      totalEvents: Long,
      toPersist: String,
      bytesPerEvent: Int,
      replyTo: ActorRef[StatusReply[Done]],
      testName: String)
      extends Command
      with CborSerializable

  final case class Event(
      testName: String,
      payload: String,
      data: Array[Byte],
      timeCreated: Long = System.currentTimeMillis())
      extends CborSerializable

  private final case class InternalPersist(
      totalEvents: Long,
      testName: String,
      toPersist: String,
      bytesPerEvent: Int,
      replyTo: ActorRef[StatusReply[Done]])
      extends Command

  final case class PersistSingle(testName: String, payload: String, dataBytes: Int)
      extends Command
      with CborSerializable

  final case class PersistSingleAndAck(
      testName: String,
      payload: String,
      dataBytes: Int,
      replyTo: ActorRef[StatusReply[Done]])
      extends Command
      with CborSerializable

  final case class State(eventsProcessed: Long) extends CborSerializable

  def randomPayload(size: Int): Array[Byte] =
    if (size == 0) Array.empty
    else {
      val payload = Array.ofDim[Byte](size)
      ThreadLocalRandom.current().nextBytes(payload)
      payload
    }

  def apply(settings: EventProcessorSettings, entityId: String): Behavior[Command] =
    Behaviors.setup { ctx =>
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.of(Key.name, entityId),
        State(0),
        (state, command) =>
          command match {
            case PersistAndAck(totalEvents, toPersist, bytesPerEvent, ack, testName) =>
              ctx.log.debug("persisting {} events", totalEvents)
              ctx.self ! InternalPersist(totalEvents, testName, toPersist, bytesPerEvent, ack)
              Effect.none
            case InternalPersist(totalEvents, testName, toPersist, bytesPerEvent, replyTo) =>
              if (state.eventsProcessed == totalEvents) {
                ctx.log.debug("Finished persisting {} events. Replying to {}", totalEvents, replyTo)
                replyTo ! StatusReply.ack()
                Effect.stop()
              } else {
                val msg = s"${toPersist}-${state.eventsProcessed}"
                Effect.persist(Event(testName, payload = msg, data = randomPayload(bytesPerEvent))).thenRun { _ =>
                  ctx.self ! InternalPersist(totalEvents, testName, toPersist, bytesPerEvent, replyTo)
                }
              }
            case PersistSingle(testName, payload, dataBytes) =>
              Effect.persist(Event(testName, payload, data = randomPayload(dataBytes)))
            case PersistSingleAndAck(testName, payload, dataBytes, replyTo) =>
              Effect
                .persist(Event(testName, payload, data = randomPayload(dataBytes)))
                .thenReply(replyTo)(_ => StatusReply.ack())
            case WakeUp =>
              ctx.log.debug("WakeUp {}", ctx.self.path.name)
              Effect.none
            case Stop =>
              ctx.log.debug("Stop {}", ctx.self.path.name)
              Effect.stop()
          },
        (state, _) => state.copy(eventsProcessed = state.eventsProcessed + 1)).withTagger(event =>
        (0 until settings.nrProjections).map { projection =>
          val tagIndex = math.abs(event.hashCode() % settings.parallelism)
          tagFor(projection, tagIndex)
        }.toSet)
    }

  def tagFor(projectionIndex: Int, tagIndex: Int): String =
    s"projection-$projectionIndex-tag-${tagIndex}"
}
