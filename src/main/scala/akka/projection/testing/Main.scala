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
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.jdbc.query.javadsl.JdbcReadJournal
import com.typesafe.config.{ Config, ConfigFactory }

object Main {

  sealed trait Journal {
    def readJournal: String
  }
  case object Cassandra extends Journal {
    override def readJournal: String = CassandraReadJournal.Identifier
  }
  case object JDBC extends Journal {
    override def readJournal: String = JdbcReadJournal.Identifier
  }

  def main(args: Array[String]): Unit = {
    args.headOption match {

      case Some(portString) if portString.matches("""\d+""") =>
        System.setProperty("config.resource", "local.conf")
        val port = portString.toInt
        val httpPort = ("80" + portString.takeRight(2)).toInt
        val prometheusPort = ("900" + portString.takeRight(1)).toInt
        val akkaManagementPort = ("85" + portString.takeRight(2)).toInt
        startNode(port, httpPort, prometheusPort, akkaManagementPort)

      case None =>
        println("No port number provided. Using defaults. Assuming running in k8s")
        ActorSystem[String](Guardian(shouldBootstrap = true), "test")
    }
  }

  def startNode(port: Int, httpPort: Int, prometheusPort: Int, akkaManagementPort: Int): ActorSystem[_] = {
    ActorSystem[String](Guardian(), "test", localConfig(port, httpPort, prometheusPort, akkaManagementPort))

  }

  def localConfig(port: Int, httpPort: Int, prometheusPort: Int, akkaManagementPort: Int): Config = {
    println(s"using port $port http port $httpPort prometheus port $prometheusPort")
    ConfigFactory.parseString(s"""
      akka.cluster {
        seed-nodes = [
          "akka://test@127.0.0.1:2551",
          "akka://test@127.0.0.1:2552"
        ]
        roles = ["write-model", "read-model"]
      }
      akka.remote.artery.canonical.port = $port
      akka.remote.artery.canonical.hostname = "127.0.0.1"
      test.http.port = $httpPort
      akka.management.http.port = $akkaManagementPort
      akka.management.http.hostname = "127.0.0.1"
      cinnamon.prometheus.http-server.port = $prometheusPort
       """).withFallback(ConfigFactory.load())
  }

}
