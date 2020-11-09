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

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity, EntityTypeKey }
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }

object ConfigurablePersistentActor {

  val Key: EntityTypeKey[Command] = EntityTypeKey[Command]("configurable")

  def init(settings: EventProcessorSettings, system: ActorSystem[_]): ActorRef[ShardingEnvelope[Command]] = {
    ClusterSharding(system).init(Entity(Key)(ctx => apply(settings, ctx.entityId)).withRole("write-model"))
  }

  trait Command

  final case class PersistAndAck(
      totalEvents: Long,
      toPersist: String,
      replyTo: ActorRef[StatusReply[Done]],
      testName: String)
      extends Command
      with CborSerializable

  final case class Event(testName: String, payload: String, timeCreated: Long = System.currentTimeMillis())
      extends CborSerializable

  private final case class InternalPersist(
      totalEvents: Long,
      eventNr: Long,
      testName: String,
      toPersist: String,
      replyTo: ActorRef[StatusReply[Done]])
      extends Command

  final case class State(eventsProcessed: Long) extends CborSerializable

  def apply(settings: EventProcessorSettings, persistenceId: String): Behavior[Command] =
    Behaviors.setup { ctx =>
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(persistenceId),
        State(0),
        (state, command) =>
          command match {
            case PersistAndAck(totalEvents, toPersist, ack, testName) =>
              ctx.log.debug("persisting {} events", totalEvents)
              ctx.self ! InternalPersist(totalEvents, 1, testName, toPersist, ack)
              Effect.none
            case InternalPersist(totalEvents, eventNr, testName, toPersist, replyTo) =>
              if (state.eventsProcessed == totalEvents) {
                ctx.log.debug("Finished persisting {} events. Replying to {}", totalEvents, replyTo)
                replyTo ! StatusReply.ack()
                Effect.none
              } else {
                Effect.persist(Event(testName, payload = s"${toPersist}-${eventNr}")).thenRun { _ =>
                  ctx.self ! InternalPersist(totalEvents, eventNr + 1, testName, toPersist, replyTo)
                }
              }
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
