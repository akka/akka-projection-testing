/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing

import java.util.concurrent.ThreadLocalRandom.{ current => random }

import scala.util.control.NoStackTrace

import akka.persistence.query.Offset
import akka.projection.ProjectionId
import org.slf4j.LoggerFactory

trait EnvelopeAccess[Envelope[_]] {
  def event[Event](envelope: Envelope[Event]): Event
  def offset(envelope: Envelope[_]): Offset
  def persistenceId(envelope: Envelope[_]): String
  def sequenceNr(envelope: Envelope[_]): Long
}

object EnvelopeAccess {
  implicit object TestEventEnvelope extends EnvelopeAccess[akka.projection.eventsourced.EventEnvelope] {
    def event[Event](envelope: akka.projection.eventsourced.EventEnvelope[Event]): Event = envelope.event
    def offset(envelope: akka.projection.eventsourced.EventEnvelope[_]): Offset = envelope.offset
    def persistenceId(envelope: akka.projection.eventsourced.EventEnvelope[_]): String = envelope.persistenceId
    def sequenceNr(envelope: akka.projection.eventsourced.EventEnvelope[_]): Long = envelope.sequenceNr
  }

  implicit object TestTypedEventEnvelope extends EnvelopeAccess[akka.persistence.query.typed.EventEnvelope] {
    def event[Event](envelope: akka.persistence.query.typed.EventEnvelope[Event]): Event = envelope.event
    def offset(envelope: akka.persistence.query.typed.EventEnvelope[_]): Offset = envelope.offset
    def persistenceId(envelope: akka.persistence.query.typed.EventEnvelope[_]): String = envelope.persistenceId
    def sequenceNr(envelope: akka.persistence.query.typed.EventEnvelope[_]): Long = envelope.sequenceNr
  }
}

trait TestProjectionHandler[Envelope[_]] {
  def projectionId: ProjectionId
  def failEvery: Int

  private val log = LoggerFactory.getLogger(getClass)
  private var startTime = System.nanoTime()
  private var count = 0

  def testProcessing(envelope: Envelope[ConfigurablePersistentActor.Event])(implicit
      access: EnvelopeAccess[Envelope]): Unit = {

    log.trace(
      "Projection [{}] processing event [{}] with offset [{}] for test [{}]",
      projectionId.id,
      access.event(envelope).payload,
      access.offset(envelope),
      access.event(envelope).testName)

    if (failEvery != Int.MaxValue && random.nextInt(failEvery) == 1) {
      throw new RuntimeException(
        s"Simulated failure when processing persistence id [${access.persistenceId(envelope)}]," +
          s" sequence nr [${access.sequenceNr(envelope)}], offset [${access.offset(envelope)}]") with NoStackTrace
    }

    count += 1
    if (count == 1000) {
      val durationMs = (System.nanoTime() - startTime) / 1000 / 1000
      log.info(
        "Projection [{}] throughput [{}] events/s in [{}] ms",
        projectionId.id,
        1000 * count / durationMs,
        durationMs)
      count = 0
      startTime = System.nanoTime()
    }
  }

  def testProcessingGroup(envelopes: Seq[Envelope[ConfigurablePersistentActor.Event]])(implicit
      access: EnvelopeAccess[Envelope]): Unit = {
    log.trace(
      "Projection [{}] processing group of [{}] events for test [{}]",
      projectionId.id,
      envelopes.size,
      envelopes.headOption.map(envelope => access.event(envelope).testName).getOrElse("<unknown>"))

    envelopes.foreach(testProcessing)
  }
}
