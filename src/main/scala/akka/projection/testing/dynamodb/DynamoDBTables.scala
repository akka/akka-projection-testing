/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.dynamodb

object DynamoDBTables {
  object Events {
    val TableName = "events"

    object Attributes {
      val Id = "id"
      val Event = "event"
    }
  }

  object Results {
    val TableName = "results"

    object Attributes {
      val Name = "test_name" // 'name' is reserved word
      val Result = "test_result" // 'result' is reserved word
    }
  }
}
