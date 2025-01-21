/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.dynamodb

import scala.concurrent.Future
import scala.jdk.FutureConverters._

import akka.Done
import akka.actor.typed.ActorSystem
import akka.persistence.dynamodb.DynamoDBSettings
import akka.persistence.dynamodb.util.scaladsl.CreateTables.createJournalTable
import akka.persistence.dynamodb.util.scaladsl.CreateTables.createSnapshotsTable
import akka.projection.dynamodb.DynamoDBProjectionSettings
import akka.projection.dynamodb.scaladsl.CreateTables.createTimestampOffsetStoreTable
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient
import software.amazon.awssdk.services.dynamodb.model._

object LocalDynamoDB {
  def createTables(system: ActorSystem[_], client: DynamoDbAsyncClient): Future[Done] = {
    val localMode = system.settings.config.getBoolean("akka.persistence.dynamodb.client.local.enabled")

    if (localMode) {
      import system.executionContext

      val settings = DynamoDBSettings(system)
      val projectionSettings = DynamoDBProjectionSettings(system)

      Future
        .sequence(
          Seq(
            createJournalTable(system, settings, client, deleteIfExists = false),
            createSnapshotsTable(system, settings, client, deleteIfExists = false),
            createTimestampOffsetStoreTable(system, projectionSettings, client, deleteIfExists = false),
            createEventsTable(system, client),
            createResultsTable(system, client)))
        .map(_ => Done)
    } else {
      Future.successful(Done)
    }
  }

  private def createEventsTable(system: ActorSystem[_], client: DynamoDbAsyncClient): Future[Done] = {
    import DynamoDBTables.Events
    import system.executionContext
    val eventsTable = CreateTableRequest.builder
      .tableName(Events.TableName)
      .keySchema(
        KeySchemaElement.builder.attributeName(Events.Attributes.Id).keyType(KeyType.HASH).build,
        KeySchemaElement.builder.attributeName(Events.Attributes.Event).keyType(KeyType.RANGE).build)
      .attributeDefinitions(
        AttributeDefinition.builder.attributeName(Events.Attributes.Id).attributeType(ScalarAttributeType.S).build,
        AttributeDefinition.builder.attributeName(Events.Attributes.Event).attributeType(ScalarAttributeType.S).build)
      .provisionedThroughput(ProvisionedThroughput.builder.readCapacityUnits(5L).writeCapacityUnits(5L).build)
      .build
    client
      .createTable(eventsTable)
      .asScala
      .map(_ => Done)
      .recover { case _ => Done } // ignore preexisting table errors
  }

  private def createResultsTable(system: ActorSystem[_], client: DynamoDbAsyncClient): Future[Done] = {
    import DynamoDBTables.Results
    import system.executionContext
    val resultsTable = CreateTableRequest.builder
      .tableName(Results.TableName)
      .keySchema(KeySchemaElement.builder.attributeName(Results.Attributes.Name).keyType(KeyType.HASH).build)
      .attributeDefinitions(
        AttributeDefinition.builder.attributeName(Results.Attributes.Name).attributeType(ScalarAttributeType.S).build)
      .provisionedThroughput(ProvisionedThroughput.builder.readCapacityUnits(5L).writeCapacityUnits(5L).build)
      .build
    client
      .createTable(resultsTable)
      .asScala
      .map(_ => Done)
      .recover { case _ => Done } // ignore preexisting table errors
  }
}
