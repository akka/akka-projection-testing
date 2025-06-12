/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.simulation

import scala.concurrent.duration._

import org.apache.commons.rng.sampling.distribution.ZigguratSampler

// Represents a point in time in seconds
final class Point(val value: Double) extends AnyVal {
  def +(that: Point): Point = new Point(this.value + that.value)

  def -(that: Point): Point = new Point(this.value - that.value)

  def /(that: Point): Double = this.value / that.value

  def <(that: Point): Boolean = this.value < that.value

  def <=(that: Point): Boolean = this.value <= that.value

  def >(that: Point): Boolean = this.value > that.value

  def >=(that: Point): Boolean = this.value >= that.value

  def toDuration: FiniteDuration = value.seconds

  override def toString: String = f"$value%.6fs"
}

object Point {
  val Zero = Point(0)

  def apply(value: Double): Point = new Point(value)

  def apply(duration: FiniteDuration): Point = Point(durationToSeconds(duration))

  def durationToSeconds(duration: FiniteDuration): Double = duration.toNanos / 1.second.toNanos.toDouble

  implicit val ordering: Ordering[Point] = Ordering.by[Point, Double](_.value)
}

sealed trait PointProcess {
  def min: Point
  def max: Point
  def points(): Iterator[Point]
}

object PointProcess {

  def apply(
      settings: PointProcessSettings,
      start: FiniteDuration,
      duration: FiniteDuration,
      random: RandomProvider): PointProcess = {
    val minPoint = Point(start)
    val maxPoint = minPoint + Point(duration)
    PointProcess(settings, minPoint, maxPoint, random)
  }

  def apply(settings: PointProcessSettings, minPoint: Point, maxPoint: Point, random: RandomProvider): PointProcess = {
    settings match {
      case PointProcessSettings.PoissonSettings(rate) =>
        Poisson(RateFunction(rate, maxPoint, random), minPoint, maxPoint, random)
    }
  }

  object Poisson {
    def apply(rate: RateFunction, min: Point, max: Point, random: RandomProvider): Poisson = {
      rate match {
        case RateFunction.Constant(rate) => HomogeneousPoisson(rate, min, max, random)
        case varying                     => NonHomogeneousPoisson(varying, min, max, random)
      }
    }
  }

  sealed trait Poisson extends PointProcess

  final case class HomogeneousPoisson(rate: Rate, min: Point, max: Point, random: RandomProvider) extends Poisson {

    override def points(): Iterator[Point] = {
      val exponentialSampler = ZigguratSampler.Exponential.of(random.create(), 1 / rate.value)
      def nextInterval() = Point(exponentialSampler.sample())
      Iterator
        .unfold(min) { previous =>
          val next = previous + nextInterval()
          if (next < max) Some((next, next)) else None
        }
    }
  }

  final case class NonHomogeneousPoisson(rate: RateFunction, min: Point, max: Point, random: RandomProvider)
      extends Poisson {

    override def points(): Iterator[Point] = {
      val homogenous = HomogeneousPoisson(rate.max, min, max, random)
      val acceptRandom = random.create()
      // apply thinning to create non-homogenous process with varying intensity
      def accept(point: Point): Boolean = acceptRandom.nextDouble() < (rate(point).value / rate.max.value)
      homogenous.points().filter(accept)
    }
  }
}
