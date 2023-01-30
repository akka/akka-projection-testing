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

import java.time.Instant

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Random
import scala.util.control.NoStackTrace

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.persistence.query.NoOffset
import akka.persistence.query.Offset
import akka.persistence.query.PersistenceQuery
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.query.typed.scaladsl.EventTimestampQuery
import akka.persistence.query.typed.scaladsl.EventsBySliceQuery
import akka.persistence.query.typed.scaladsl.LoadEventQuery
import akka.projection.BySlicesSourceProvider
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.SourceProvider
import akka.stream.scaladsl.Source

object FailingEventsBySlicesProvider {

  def eventsBySlices[Event](
      system: ActorSystem[_],
      readJournalPluginId: String,
      entityType: String,
      minSlice: Int,
      maxSlice: Int): SourceProvider[Offset, EventEnvelope[Event]] = {

    system.settings.config.getString("test.projection-failure-every").toLowerCase() match {
      case "off" =>
        // use the ordinary from Akka Projections
        EventSourcedProvider.eventsBySlices[Event](system, readJournalPluginId, entityType, minSlice, maxSlice)
      case _ =>
        val failEvery = system.settings.config.getInt("test.projection-failure-every")
        val eventsBySlicesQuery =
          PersistenceQuery(system).readJournalFor[EventsBySliceQuery](readJournalPluginId)
        new FailingEventsBySlicesSourceProvider(failEvery, eventsBySlicesQuery, entityType, minSlice, maxSlice, system)
    }
  }

  private class FailingEventsBySlicesSourceProvider[Event](
      failEvery: Int,
      eventsBySlicesQuery: EventsBySliceQuery,
      entityType: String,
      val minSlice: Int,
      val maxSlice: Int,
      system: ActorSystem[_])
      extends SourceProvider[Offset, EventEnvelope[Event]]
      with BySlicesSourceProvider
      with EventTimestampQuery
      with LoadEventQuery {
    implicit val executionContext: ExecutionContext = system.executionContext

    override def source(offset: () => Future[Option[Offset]]): Future[Source[EventEnvelope[Event], NotUsed]] =
      offset().map { offsetOpt =>
        val offset = offsetOpt.getOrElse(NoOffset)
        eventsBySlicesQuery.eventsBySlices[Event](entityType, minSlice, maxSlice, offset).map { env =>
          if (failEvery != Int.MaxValue && env.eventOption.isDefined && Random.nextInt(failEvery) == 1) {
            throw new RuntimeException(
              s"Persistence id ${env.persistenceId} sequence nr ${env.sequenceNr} offset ${env.offset} Restart the stream!")
              with NoStackTrace
          }
          env
        }
      }

    override def extractOffset(envelope: EventEnvelope[Event]): Offset = envelope.offset

    override def extractCreationTime(envelope: EventEnvelope[Event]): Long = envelope.timestamp

    override def timestampOf(persistenceId: String, sequenceNr: Long): Future[Option[Instant]] =
      eventsBySlicesQuery match {
        case timestampQuery: EventTimestampQuery =>
          timestampQuery.timestampOf(persistenceId, sequenceNr)
        case _ =>
          Future.failed(
            new IllegalStateException(
              s"[${eventsBySlicesQuery.getClass.getName}] must implement [${classOf[EventTimestampQuery].getName}]"))
      }

    override def loadEnvelope[Evt](
        persistenceId: String,
        sequenceNr: Long): Future[akka.persistence.query.typed.EventEnvelope[Evt]] =
      eventsBySlicesQuery match {
        case laodEventQuery: LoadEventQuery =>
          laodEventQuery.loadEnvelope(persistenceId, sequenceNr)
        case _ =>
          Future.failed(
            new IllegalStateException(
              s"[${eventsBySlicesQuery.getClass.getName}] must implement [${classOf[LoadEventQuery].getName}]"))
      }
  }
}
