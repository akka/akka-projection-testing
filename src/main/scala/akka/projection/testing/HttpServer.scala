/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing

import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route

class HttpServer(routes: Route, port: Int)(implicit system: ActorSystem[_]) {
  import system.executionContext

  def start(): Unit = {
    Http().newServerAt("0.0.0.0", port).bind(routes).map(_.addToCoordinatedShutdown(3.seconds)).onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info("Online at http://{}:{}/", address.getHostString, address.getPort)

      case Failure(ex) =>
        system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }
  }

}
