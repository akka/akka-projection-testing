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
import com.typesafe.config.{ Config, ConfigFactory }

object Main {

  def main(args: Array[String]): Unit = {
    args.headOption match {

      case Some(portString) if portString.matches("""\d+""") =>
        val port = portString.toInt
        val httpPort = ("80" + portString.takeRight(2)).toInt
        val prometheusPort = ("900" + portString.takeRight(1)).toInt
        startNode(port, httpPort, prometheusPort)

      case None =>
        throw new IllegalArgumentException("port number required argument")
    }
  }

  def startNode(port: Int, httpPort: Int, prometheusPort: Int): Unit = {
    ActorSystem[String](Guardian(), "test", config(port, httpPort, prometheusPort))

  }

  def config(port: Int, httpPort: Int, prometheusPort: Int): Config = {
    println(s"using port $port http port $httpPort prometheus port $prometheusPort")
    ConfigFactory.parseString(s"""
      akka.remote.artery.canonical.port = $port
      test.http.port = $httpPort
      cinnamon.prometheus.http-server.port = $prometheusPort
       """).withFallback(ConfigFactory.load())
  }

}
