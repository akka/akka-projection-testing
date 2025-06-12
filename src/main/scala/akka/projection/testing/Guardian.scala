/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import akka.projection.ProjectionBehavior
import akka.projection.testing.simulation.Engine

object Guardian {

  def apply(shouldBootstrap: Boolean = false): Behavior[String] = {
    Behaviors.setup[String] { context =>
      implicit val system: ActorSystem[_] = context.system
      AkkaManagement(system).start()
      if (shouldBootstrap) {
        ClusterBootstrap(system).start()
      }

      val settings = EventProcessorSettings(system)

      val setup = TestSetup(settings)
      Await.result(setup.init(), 10.seconds)

      val shardRegion = ConfigurablePersistentActor.init(settings, system)

      val loadGeneration: ActorRef[LoadGeneration.Command] =
        context.spawn(LoadGeneration(settings, shardRegion, setup), "load-generation")

      val simulationEngine: ActorRef[Engine.Command] =
        context.spawn(Engine(settings, shardRegion, setup), "simulation-engine")

      val httpPort = system.settings.config.getInt("test.http.port")

      val server = new HttpServer(new TestRoutes(loadGeneration, simulationEngine, setup).route, httpPort)
      server.start()

      // we only want to run the daemon processes on the read-model nodes
      val shardedDaemonProcessSettings = ShardedDaemonProcessSettings(system).withRole("read-model")

      (0 until settings.nrProjections).foreach { projectionIndex =>
        ShardedDaemonProcess(system).init(
          name = s"test-projection-$projectionIndex",
          settings.parallelism,
          n => setup.createProjection(projectionIndex, n),
          shardedDaemonProcessSettings,
          Some(ProjectionBehavior.Stop))
      }

      Behaviors.receiveMessage[String](_ => Behaviors.same).receiveSignal { case (_, PostStop) =>
        setup.cleanUp()
        Behaviors.stopped
      }
    }
  }
}
