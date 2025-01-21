/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.dynamodb

import java.util.concurrent.CompletionException

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.persistence.dynamodb.util.ClientProvider
import akka.persistence.query.Offset
import akka.persistence.query.typed.EventEnvelope
import akka.projection.Projection
import akka.projection.ProjectionBehavior
import akka.projection.ProjectionId
import akka.projection.dynamodb.scaladsl.DynamoDBProjection
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.SourceProvider
import akka.projection.testing.ConfigurablePersistentActor
import akka.projection.testing.EventProcessorSettings
import akka.projection.testing.LoggingHandler
import akka.projection.testing.TestSetup
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest
import software.amazon.awssdk.services.dynamodb.model.QueryRequest
import software.amazon.awssdk.services.dynamodb.model.Select

final class DynamoDBTestSetup(settings: EventProcessorSettings)(implicit system: ActorSystem[_]) extends TestSetup {
  override val journal: TestSetup.Journal = TestSetup.DynamoDB

  private val useClient: String = system.settings.config.getString("test.dynamodb.use-client")
  private val client = ClientProvider(system).clientFor(useClient)

  override def init(): Future[Done] = {
    LocalDynamoDB.createTables(system, client)
  }

  override def reset(): Future[Done] = {
    Future.successful(Done) // not dropping projected events or table
  }

  override def createProjection(projectionIndex: Int, sliceIndex: Int): Behavior[ProjectionBehavior.Command] = {

    val ranges = EventSourcedProvider.sliceRanges(system, journal.readJournal, settings.parallelism)
    val sliceRange = ranges(sliceIndex)

    val sourceProvider: SourceProvider[Offset, EventEnvelope[ConfigurablePersistentActor.Event]] =
      EventSourcedProvider.eventsBySlices(
        system,
        journal.readJournal,
        ConfigurablePersistentActor.Key.name,
        sliceRange.min,
        sliceRange.max)

    val projection: Projection[EventEnvelope[ConfigurablePersistentActor.Event]] = {
      val projectionId = ProjectionId(s"test-projection-id-$projectionIndex", s"${sliceRange.min}-${sliceRange.max}")
      if (settings.readOnly) {
        DynamoDBProjection.atLeastOnce(
          projectionId,
          settings = None,
          sourceProvider,
          () => new LoggingHandler(projectionId))
      } else {
        DynamoDBProjection.atLeastOnceGroupedWithin(
          projectionId,
          settings = None,
          sourceProvider,
          () =>
            new DynamoDBGroupedProjectionHandler(
              projectionId,
              projectionIndex,
              settings.readOnly,
              settings.failEvery,
              client))

        // DynamoDBProjection.exactlyOnce(
        //   projectionId,
        //   settings = None,
        //   sourceProvider,
        //   () => new DynamoDBProjectionHandler(projectionId, projectionIndex, settings.readOnly, settings.failEvery))
      }
    }

    ProjectionBehavior(projection)
  }

  // FIXME: count of items not expected to be efficient in DynamoDB
  override def countEvents(testName: String, projectionId: Int): Future[Int] = {
    import DynamoDBTables.Events
    import system.executionContext

    val request = QueryRequest.builder
      .tableName(Events.TableName)
      .consistentRead(true)
      .keyConditionExpression(s"${Events.Attributes.Id} = :id")
      .expressionAttributeValues(Map(":id" -> AttributeValue.fromS(s"$testName-$projectionId")).asJava)
      .select(Select.COUNT)
      .build()

    client
      .query(request)
      .asScala
      .map(_.count().intValue)
      .recoverWith { case c: CompletionException => Future.failed(c.getCause) }(ExecutionContext.parasitic)
  }

  override def writeResult(testName: String, result: String): Future[Done] = {
    import DynamoDBTables.Results

    val attributes = Map(
      Results.Attributes.Name -> AttributeValue.fromS(testName),
      Results.Attributes.Result -> AttributeValue.fromS(result)).asJava

    val request = PutItemRequest.builder
      .tableName(Results.TableName)
      .item(attributes)
      .build

    client
      .putItem(request)
      .asScala
      .map(_ => Done)(ExecutionContext.parasitic)
      .recoverWith { case c: CompletionException => Future.failed(c.getCause) }(ExecutionContext.parasitic)
  }

  override def readResult(testName: String): Future[String] = {
    import DynamoDBTables.Results
    import system.executionContext

    val request = QueryRequest.builder
      .tableName(Results.TableName)
      .consistentRead(true)
      .keyConditionExpression(s"${Results.Attributes.Name} = :name")
      .expressionAttributeValues(Map(":name" -> AttributeValue.fromS(testName)).asJava)
      .projectionExpression(s"${Results.Attributes.Result}")
      .build()

    client
      .query(request)
      .asScala
      .map { response =>
        val items = response.items()
        if (items.isEmpty) "not finished"
        else items.get(0).get(Results.Attributes.Result).s()
      }
      .recoverWith { case c: CompletionException => Future.failed(c.getCause) }(ExecutionContext.parasitic)
  }

  override def cleanUp(): Unit = {
    client.close()
  }
}
