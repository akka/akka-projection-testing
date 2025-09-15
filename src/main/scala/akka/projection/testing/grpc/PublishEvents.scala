/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.grpc

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.projection.grpc.producer.EventProducerSettings
import akka.projection.grpc.producer.scaladsl.EventProducer
import akka.projection.grpc.producer.scaladsl.EventProducer.Transformation
import akka.projection.testing.ConfigurablePersistentActor

import scala.concurrent.Future

object PublishEvents {

  val streamId = "events"

  def eventProducerService(system: ActorSystem[_]): PartialFunction[HttpRequest, Future[HttpResponse]] =
    if (!GrpcTestSetup.isGrpcRun(system)) PartialFunction.empty
    else {
      val transformation = Transformation.identity

      val eventProducerSource = EventProducer
        .EventProducerSource(
          ConfigurablePersistentActor.Key.name,
          streamId,
          transformation,
          EventProducerSettings(system))

      EventProducer.grpcServiceHandler(eventProducerSource)(system)
    }
}
