/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.dynamodb

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Random
import scala.util.control.NoStackTrace

import akka.Done
import akka.actor.typed.ActorSystem
import akka.persistence.query.typed.EventEnvelope
import akka.projection.ProjectionId
import akka.projection.dynamodb.scaladsl.DynamoDBTransactHandler
import akka.projection.dynamodb.scaladsl.Requests
import akka.projection.scaladsl.Handler
import akka.projection.testing.ConfigurablePersistentActor
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest
import software.amazon.awssdk.services.dynamodb.model.Put
import software.amazon.awssdk.services.dynamodb.model.PutRequest
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem
import software.amazon.awssdk.services.dynamodb.model.WriteRequest

class DynamoDBProjectionHandler(projectionId: ProjectionId, projectionIndex: Int, readOnly: Boolean, failEvery: Int)
    extends DynamoDBTransactHandler[EventEnvelope[ConfigurablePersistentActor.Event]] {

  import DynamoDBTables.Events

  private val log: Logger = LoggerFactory.getLogger(getClass)
  private var startTime = System.nanoTime()
  private var count = 0

  override def process(
      envelope: EventEnvelope[ConfigurablePersistentActor.Event]): Future[Iterable[TransactWriteItem]] = {
    log.trace(
      "Projection [{}] processing event [{}] with offset [{}]",
      projectionId.id,
      envelope.event.payload,
      envelope.offset)

    if (failEvery != Int.MaxValue && Random.nextInt(failEvery) == 1) {
      throw new RuntimeException(
        s"Simulated failure when processing persistence id [${envelope.persistenceId}]," +
          s" sequence nr [${envelope.sequenceNr}], offset [${envelope.offset}]") with NoStackTrace
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

    if (readOnly)
      Future.successful(Nil)
    else {
      val attributes = Map(
        Events.Attributes.Id -> AttributeValue.fromS(s"${envelope.event.testName}-$projectionIndex"),
        Events.Attributes.Event -> AttributeValue.fromS(envelope.event.payload)).asJava

      Future.successful(
        Seq(
          TransactWriteItem.builder
            .put(
              Put.builder
                .tableName(Events.TableName)
                .item(attributes)
                .build())
            .build()))
    }
  }
}

// when using this consider reducing failure otherwise a high chance of at least one grouped envelope
// causing an error and no progress will be made
class DynamoDBGroupedProjectionHandler(
    projectionId: ProjectionId,
    projectionIndex: Int,
    readOnly: Boolean,
    failEvery: Int,
    client: DynamoDbAsyncClient)(implicit system: ActorSystem[_])
    extends Handler[Seq[EventEnvelope[ConfigurablePersistentActor.Event]]] {

  import DynamoDBTables.Events

  private val log: Logger = LoggerFactory.getLogger(getClass)
  private var startTime = System.nanoTime()
  private var count = 0

  override def process(envelopes: Seq[EventEnvelope[ConfigurablePersistentActor.Event]]): Future[Done] = {
    log.trace("Projection [{}] processing group of [{}] events", projectionId.id, envelopes.size)

    envelopes.foreach { envelope =>
      if (failEvery != Int.MaxValue && Random.nextInt(failEvery) == 1) {
        throw new RuntimeException(
          s"Simulated failure when processing persistence id [${envelope.persistenceId}]," +
            s" sequence nr [${envelope.sequenceNr}], offset [${envelope.offset}]") with NoStackTrace
      }
    }

    count += envelopes.size
    if (count >= 1000) {
      val durationMs = (System.nanoTime() - startTime) / 1000 / 1000
      log.info(
        "Projection [{}] throughput [{}] events/s in [{}] ms",
        projectionId.id,
        1000 * count / durationMs,
        durationMs)
      count = 0
      startTime = System.nanoTime()
    }

    if (readOnly)
      Future.successful(Done)
    else {
      // TODO: multiple batches if groups are larger than 25 (hard limit on batch size)

      val items = envelopes.map { envelope =>
        val attributes = Map(
          Events.Attributes.Id -> AttributeValue.fromS(s"${envelope.event.testName}-$projectionIndex"),
          Events.Attributes.Event -> AttributeValue.fromS(envelope.event.payload)).asJava

        WriteRequest.builder
          .putRequest(
            PutRequest.builder
              .item(attributes)
              .build())
          .build()
      }.asJava

      val request = BatchWriteItemRequest.builder
        .requestItems(Map(Events.TableName -> items).asJava)
        .build()

      Requests
        .batchWriteWithRetries(
          client,
          request,
          maxRetries = 3,
          minBackoff = 200.millis,
          maxBackoff = 2.seconds,
          randomFactor = 0.3)
        .map(_ => Done)(ExecutionContext.parasitic)
    }
  }
}
