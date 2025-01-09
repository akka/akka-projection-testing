import sbt._
import sbt.Keys._

import com.lightbend.cinnamon.sbt.Cinnamon
import com.lightbend.cinnamon.sbt.Cinnamon.CinnamonKeys._

object Insights extends AutoPlugin {
  val enabled = sys.props.getOrElse("insights.enabled", "false").toBoolean

  override def requires = if (enabled) Cinnamon else plugins.JvmPlugin

  override def projectSettings = if (enabled) cinnamonSettings else Seq.empty

  def cinnamonSettings = Seq(
    run / cinnamon := true,
    libraryDependencies ++= Seq(
      Cinnamon.library.cinnamonAkkaTyped,
      Cinnamon.library.cinnamonAkkaPersistence,
      Cinnamon.library.cinnamonAkkaProjection,
      Cinnamon.library.cinnamonPrometheus,
      Cinnamon.library.cinnamonPrometheusHttpServer))
}
