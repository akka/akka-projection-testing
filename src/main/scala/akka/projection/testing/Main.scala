/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing

import akka.actor.typed.ActorSystem
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.persistence.jdbc.query.scaladsl.JdbcReadJournal
import akka.persistence.r2dbc.query.scaladsl.R2dbcReadJournal
import com.typesafe.config.{ Config, ConfigFactory }

object Main {

  sealed trait Journal {
    def journalPluginId: String
    def readJournal: String
  }

  case object Cassandra extends Journal {
    override val journalPluginId: String = "akka.persistence.cassandra.journal"
    override def readJournal: String = CassandraReadJournal.Identifier
  }

  case object JDBC extends Journal {
    override val journalPluginId: String = "jdbc-journal"
    override def readJournal: String = JdbcReadJournal.Identifier
  }

  case object R2DBC extends Journal {
    override val journalPluginId: String = "akka.persistence.r2dbc.journal"
    override def readJournal: String = R2dbcReadJournal.Identifier
  }

  def main(args: Array[String]): Unit = {
    args.headOption match {
      case Some(portString) if portString.matches("""\d+""") =>
        val config = ConfigFactory.load("local.conf")
        val port = portString.toInt
        val httpPort = ("80" + portString.takeRight(2)).toInt
        val prometheusPort = ("900" + portString.takeRight(1)).toInt
        val akkaManagementPort = ("85" + portString.takeRight(2)).toInt
        startNode(port, httpPort, prometheusPort, akkaManagementPort, config)

      case _ =>
        println("No port number provided. Using defaults. Assuming running in k8s")
        ActorSystem[String](Guardian(shouldBootstrap = true), "appka")
    }
  }

  def startNode(
      port: Int,
      httpPort: Int,
      prometheusPort: Int,
      akkaManagementPort: Int,
      config: Config): ActorSystem[_] = {
    ActorSystem[String](Guardian(), "test", localConfig(port, httpPort, prometheusPort, akkaManagementPort, config))
  }

  def localConfig(port: Int, httpPort: Int, prometheusPort: Int, akkaManagementPort: Int, fallback: Config): Config = {
    println(s"using port $port http port $httpPort prometheus port $prometheusPort")
    ConfigFactory
      .parseString(s"""
        akka.remote.artery.canonical.port = $port
        test.http.port = $httpPort
        akka.management.http.port = $akkaManagementPort
        cinnamon.prometheus.http-server.port = $prometheusPort
      """)
      .withFallback(fallback)
  }

}
