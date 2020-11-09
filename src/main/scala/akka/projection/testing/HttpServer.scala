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

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route

import scala.concurrent.duration._
import scala.util.{ Failure, Success }

class HttpServer(routes: Route, port: Int)(implicit system: ActorSystem[_]) {
  import system.executionContext

  def start(): Unit = {
    Http().newServerAt("localhost", port).bind(routes).map(_.addToCoordinatedShutdown(3.seconds)).onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info("Online at http://{}:{}/", address.getHostString, address.getPort)

      case Failure(ex) =>
        system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }
  }

}
