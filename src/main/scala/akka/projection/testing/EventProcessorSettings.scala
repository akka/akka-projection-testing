/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing

import akka.actor.typed.ActorSystem
import akka.projection.testing.EventProcessorSettings.ProjectionType
import akka.util.Helpers.toRootLowerCase
import com.typesafe.config.Config

object EventProcessorSettings {

  sealed trait ProjectionType

  object ProjectionType {
    def apply(typeName: String): ProjectionType = {
      toRootLowerCase(typeName) match {
        case "at-least-once" => AtLeastOnce
        case "exactly-once"  => ExactlyOnce
        case "grouped"       => Grouped
        case "logging-only"  => LoggingOnly
      }
    }

    case object AtLeastOnce extends ProjectionType
    case object ExactlyOnce extends ProjectionType
    case object Grouped extends ProjectionType
    case object LoggingOnly extends ProjectionType
  }

  def apply(system: ActorSystem[_]): EventProcessorSettings = {
    apply(system.settings.config.getConfig("event-processor"))
  }

  def apply(config: Config): EventProcessorSettings = {
    val projectionType = ProjectionType(config.getString("projection-type"))
    val parallelism: Int = config.getInt("parallelism")
    val nrProjections: Int = config.getInt("nr-projections")
    val readOnly = config.getBoolean("read-only")
    val failEvery = config.getString("projection-failure-every").toLowerCase() match {
      case "off" => Int.MaxValue
      case _     => config.getInt("projection-failure-every")
    }
    EventProcessorSettings(projectionType, parallelism, nrProjections, readOnly, failEvery)
  }
}

final case class EventProcessorSettings(
    projectionType: ProjectionType,
    parallelism: Int,
    nrProjections: Int,
    readOnly: Boolean,
    failEvery: Int)
