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

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.persistence.query.NoOffset
import akka.persistence.query.Offset
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.scaladsl.EventsByTagQuery
import akka.projection.eventsourced.EventEnvelope
import akka.projection.scaladsl.SourceProvider
import akka.stream.scaladsl.Source
import scala.util.Random
import scala.util.control.NoStackTrace

import akka.projection.eventsourced.scaladsl.EventSourcedProvider

object FailingEventsByTagSourceProvider {

  def eventsByTag[Event](
      system: ActorSystem[_],
      readJournalPluginId: String,
      tag: String): SourceProvider[Offset, EventEnvelope[Event]] = {

    system.settings.config.getString("test.projection-failure-every").toLowerCase() match {
      case "off" =>
        // use the ordinary from Akka Projections
        EventSourcedProvider.eventsByTag(system, readJournalPluginId, tag)
      case _ =>
        val failEvery = system.settings.config.getInt("test.projection-failure-every")
        val eventsByTagQuery =
          PersistenceQuery(system).readJournalFor[EventsByTagQuery](readJournalPluginId)
        new FailingEventsByTagSourceProvider(failEvery, eventsByTagQuery, tag, system)
    }
  }

  private class FailingEventsByTagSourceProvider[Event](
      failEvery: Int,
      eventsByTagQuery: EventsByTagQuery,
      tag: String,
      system: ActorSystem[_])
      extends SourceProvider[Offset, EventEnvelope[Event]] {
    implicit val executionContext: ExecutionContext = system.executionContext

    override def source(offset: () => Future[Option[Offset]]): Future[Source[EventEnvelope[Event], NotUsed]] =
      offset().map { offsetOpt =>
        val offset = offsetOpt.getOrElse(NoOffset)
        eventsByTagQuery
          .eventsByTag(tag, offset)
          .map { env =>
            if (failEvery != Int.MaxValue && Random.nextInt(failEvery) == 1) {
              throw new RuntimeException(
                s"Persistence id ${env.persistenceId} sequence nr ${env.sequenceNr} offset ${env.offset} Restart the stream!")
                with NoStackTrace
            }
            env
          }
          .map(env => EventEnvelope(env))

      }

    override def extractOffset(envelope: EventEnvelope[Event]): Offset = envelope.offset

    override def extractCreationTime(envelope: EventEnvelope[Event]): Long = envelope.timestamp
  }
}
