/*
 * Copyright (C) 2020 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing

import akka.actor.typed.ActorSystem
import com.typesafe.config.Config

object EventProcessorSettings {

  def apply(system: ActorSystem[_]): EventProcessorSettings = {
    apply(system.settings.config.getConfig("event-processor"))
  }

  def apply(config: Config): EventProcessorSettings = {
    val parallelism: Int = config.getInt("parallelism")
    val nrProjections: Int = config.getInt("nr-projections")
    val readOny = config.getBoolean("read-only")
    val failEvery = config.getString("projection-failure-every").toLowerCase() match {
      case "off" => Int.MaxValue
      case _     => config.getInt("projection-failure-every")
    }
    EventProcessorSettings(parallelism, nrProjections, readOny, failEvery)
  }
}

final case class EventProcessorSettings(parallelism: Int, nrProjections: Int, readOnly: Boolean, failEvery: Int)
