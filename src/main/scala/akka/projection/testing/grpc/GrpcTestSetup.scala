/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.grpc

import akka.Done
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.MemberStatus.Up
import akka.cluster.typed.Cluster
import akka.grpc.GrpcClientSettings
import akka.persistence.query.Offset
import akka.persistence.query.typed.EventEnvelope
import akka.persistence.r2dbc.ConnectionFactoryProvider
import akka.persistence.r2dbc.internal.R2dbcExecutor
import akka.persistence.r2dbc.internal.Sql.Interpolation
import akka.projection.ProjectionBehavior
import akka.projection.ProjectionId
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.grpc.consumer.GrpcQuerySettings
import akka.projection.grpc.consumer.scaladsl.GrpcReadJournal
import akka.projection.r2dbc.scaladsl.R2dbcProjection
import akka.projection.scaladsl.SourceProvider
import akka.projection.testing.ConfigurablePersistentActor
import akka.projection.testing.EventProcessorSettings
import akka.projection.testing.TestSetup
import org.slf4j.LoggerFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Random

/**
 * Uses R2DBC for actual DB but gRPC for the projection
 */
object GrpcTestSetup {
  def isGrpcRun(system: ActorSystem[_]): Boolean =
    system.settings.config.getString("akka.projection.grpc.consumer.stream-id") != ""
}

class GrpcTestSetup(settings: EventProcessorSettings)(implicit system: ActorSystem[_]) extends TestSetup {
  override val journal: TestSetup.Journal = TestSetup.GRPC
  private val log = LoggerFactory.getLogger(this.getClass)

  private val executor = new R2dbcExecutor(
    ConnectionFactoryProvider(system).connectionFactoryFor("akka.persistence.r2dbc.connection-factory"),
    log,
    logDbCallsExceeding = 5.seconds,
    closeCallsExceeding = None)(system.executionContext, system)

  override def init(): Future[Done] =
    Future.successful(Done)

  override def reset(): Future[Done] = {
    executor
      .executeDdls(s"reset") { connection =>
        Vector(connection.createStatement("TRUNCATE events"))
      }
      .map(_ => Done)(ExecutionContext.parasitic)
  }

  override def createProjection(projectionIndex: Int, sliceIndex: Int): Behavior[ProjectionBehavior.Command] = {

    val grpcQuerySettings = GrpcQuerySettings(PublishEvents.streamId)

    val portFromConfig = system.settings.config.getInt("test.http.port")
    val (grpcHost: String, grpcPort: Int) =
      if (Cluster(system).state.members.size == 1) (Cluster(system).selfMember.address.host.get, portFromConfig)
      else if (!Cluster(system).selfMember.address.host.contains("127.0.0.1")) {
        // poor man's way to detect if we are non-local/k8 - canonical is not localhost

        val dnsName = system.settings.config.getString("test.service-dns-name")
        if (dnsName != "") {
          log.info("Using configured service dns entry for gRPC projection [{}:{}]", dnsName, portFromConfig)
          // configured dns name to go via
          (dnsName, portFromConfig)
        } else {
          // random other cluster node - directly to canonical ip
          val hostsAndPorts = Cluster(system).state.members
            .filter(_.status == Up)
            .filterNot(_ == Cluster(system).selfMember)
            .map(member => member.address.host.get -> portFromConfig)
          val hostAndPort @ (host, port) = Random.shuffle(hostsAndPorts).head
          log.info("Using random node ip for gRPC projection [{}:{}]", host, port)
          hostAndPort
        }
      } else {
        // random other cluster node
        val hostsAndPorts = Cluster(system).state.members
          .filter(_.status == Up)
          .filterNot(_.address.port.contains(portFromConfig))
          .map(member => member.address.host.get -> ("80" + member.address.port.get.toString.takeRight(2)).toInt)
        val hostAndPort @ (host, port) = Random.shuffle(hostsAndPorts).head
        log.info("Using random local port for gRPC projection [{}:{}]", host, port)
        hostAndPort
      }

    val grpcClientSettings =
      GrpcClientSettings.connectToServiceAt(grpcHost, grpcPort).withTls(false)

    val eventsBySlicesQuery = GrpcReadJournal(grpcQuerySettings, grpcClientSettings, Nil)

    val ranges = EventSourcedProvider.sliceRanges(system, journal.readJournal, settings.parallelism)
    val sliceRange = ranges(sliceIndex)

    val projectionId = ProjectionId(s"test-projection-id-$projectionIndex", s"${sliceRange.min}-${sliceRange.max}")

    val sourceProvider: SourceProvider[Offset, EventEnvelope[ConfigurablePersistentActor.Event]] = EventSourcedProvider
      .eventsBySlices(system, eventsBySlicesQuery, eventsBySlicesQuery.streamId, sliceRange.min, sliceRange.max)

    ProjectionBehavior(
      R2dbcProjection.atLeastOnceAsync(
        projectionId,
        None,
        sourceProvider,
        () =>
          new GrpcProjectionHandler(projectionId, projectionIndex, settings.readOnly, settings.failEvery, executor)))
  }

  override def countEvents(testName: String, projectionId: Int): Future[Int] = {
    executor
      .selectOne("count events")(
        _.createStatement(sql"SELECT count(*) FROM events WHERE name = ? AND projection_id = ?")
          .bind(0, testName)
          .bind(1, projectionId),
        { row =>
          row.get(0, classOf[Integer]).intValue
        })
      .map(_.getOrElse(0))(ExecutionContext.parasitic)
  }

  override def writeResult(testName: String, result: String): Future[Done] = {
    executor
      .updateOne("write result")(
        _.createStatement(sql"INSERT INTO results(name, result) VALUES (?, ?)").bind(0, testName).bind(1, result))
      .map(_ => Done)(ExecutionContext.parasitic)
  }

  override def readResult(testName: String): Future[String] = {
    executor
      .selectOne("read result")(
        _.createStatement(sql"SELECT result FROM results WHERE name = ?")
          .bind(0, testName),
        { row =>
          row.get(0, classOf[String])
        })
      .map(_.getOrElse("not finished"))(ExecutionContext.parasitic)
  }

  override def cleanUp(): Unit = ()
}
