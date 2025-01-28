/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.dynamodb

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._

import akka.Done
import akka.actor.typed.ActorSystem
import akka.persistence.query.typed.EventEnvelope
import akka.projection.ProjectionId
import akka.projection.dynamodb.scaladsl.DynamoDBTransactHandler
import akka.projection.dynamodb.scaladsl.Requests
import akka.projection.scaladsl.Handler
import akka.projection.testing.ConfigurablePersistentActor
import akka.projection.testing.TestProjectionHandler
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemRequest
import software.amazon.awssdk.services.dynamodb.model.Put
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.PutRequest
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem
import software.amazon.awssdk.services.dynamodb.model.WriteRequest

class DynamoDBAtLeastOnceProjectionHandler(
    val projectionId: ProjectionId,
    projectionIndex: Int,
    readOnly: Boolean,
    val failEvery: Int,
    client: DynamoDbAsyncClient)
    extends Handler[EventEnvelope[ConfigurablePersistentActor.Event]]
    with TestProjectionHandler[EventEnvelope] {

  import DynamoDBTables.Events

  override def process(envelope: EventEnvelope[ConfigurablePersistentActor.Event]): Future[Done] = {
    testProcessing(envelope)

    if (readOnly)
      Future.successful(Done)
    else {
      val attributes = Map(
        Events.Attributes.Id -> AttributeValue.fromS(s"${envelope.event.testName}-$projectionIndex"),
        Events.Attributes.Event -> AttributeValue.fromS(envelope.event.payload)).asJava

      val request = PutItemRequest.builder
        .tableName(Events.TableName)
        .item(attributes)
        .build()

      client
        .putItem(request)
        .asScala
        .map(_ => Done)(ExecutionContext.parasitic)
    }
  }
}

class DynamoDBExactlyOnceProjectionHandler(
    val projectionId: ProjectionId,
    projectionIndex: Int,
    readOnly: Boolean,
    val failEvery: Int)
    extends DynamoDBTransactHandler[EventEnvelope[ConfigurablePersistentActor.Event]]
    with TestProjectionHandler[EventEnvelope] {

  import DynamoDBTables.Events

  override def process(
      envelope: EventEnvelope[ConfigurablePersistentActor.Event]): Future[Iterable[TransactWriteItem]] = {
    testProcessing(envelope)

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
    val projectionId: ProjectionId,
    projectionIndex: Int,
    readOnly: Boolean,
    val failEvery: Int,
    client: DynamoDbAsyncClient)(implicit system: ActorSystem[_])
    extends Handler[Seq[EventEnvelope[ConfigurablePersistentActor.Event]]]
    with TestProjectionHandler[EventEnvelope] {

  import DynamoDBTables.Events

  override def process(envelopes: Seq[EventEnvelope[ConfigurablePersistentActor.Event]]): Future[Done] = {
    testProcessingGroup(envelopes)

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
