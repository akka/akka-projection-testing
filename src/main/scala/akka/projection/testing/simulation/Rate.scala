/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.simulation

import java.text.DecimalFormat

import scala.concurrent.duration._

import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.sampling.distribution.ZigguratSampler

// Represents a rate in count per second as a value class over double
final class Rate(val value: Double) extends AnyVal {
  def +(that: Rate): Rate = new Rate(this.value + that.value)

  def -(that: Rate): Rate = new Rate(this.value - that.value)

  def *(x: Double): Rate = new Rate(this.value * x)

  override def toString: String = {
    new DecimalFormat("#.######").format(value) + "/s"
  }
}

object Rate {
  def apply(value: Double): Rate = new Rate(value)

  def max(x: Rate, y: Rate): Rate = new Rate(math.max(x.value, y.value))
}

sealed trait RateFunction {
  def max: Rate
  def apply(point: Point): Rate
}

object RateFunction {
  def apply(settings: RateFunctionSettings, duration: FiniteDuration, random: RandomProvider): RateFunction = {
    RateFunction(settings, Point(duration), random)
  }

  def apply(settings: RateFunctionSettings, maxPoint: Point, random: RandomProvider): RateFunction = {
    settings match {
      case RateFunctionSettings.ConstantSettings(value) =>
        Constant(value.toRate)
      case RateFunctionSettings.LinearSettings(initial, target) =>
        Linear(initial.toRate, target.toRate, maxPoint)
      case RateFunctionSettings.SinusoidalSettings(base, amplitude, period, shift) =>
        Sinusoidal(base.toRate, amplitude.toRate, Point(period), shift.map(Point.apply))
      case RateFunctionSettings.RandomWalkSettings(initial, min, max, volatility, trend) =>
        RandomWalk(initial.toRate, min.toRate, max.toRate, volatility, trend.getOrElse(0.0), random.create())
      case RateFunctionSettings.BurstSettings(base, peak, start, up, burst, down) =>
        Burst(base.toRate, peak.toRate, Point(start), Point(up), Point(burst), Point(down))
      case RateFunctionSettings.AdditiveSettings(rates) =>
        Additive(rates.map(apply(_, maxPoint, random)))
      case RateFunctionSettings.ModulatedSettings(carrier, modulator, strength) =>
        Modulated(apply(carrier, maxPoint, random), apply(modulator, maxPoint, random), strength.getOrElse(1.0))
      case RateFunctionSettings.CompositeSettings(rates) =>
        Composite(rates.map(weighted => Weighted(apply(weighted.rate, maxPoint, random), weighted.weight)))
    }
  }

  final case class Constant(value: Rate) extends RateFunction {

    override def max: Rate = value

    override def apply(point: Point): Rate = value
  }

  final case class Linear(initial: Rate, target: Rate, maxPoint: Point) extends RateFunction {

    override def max: Rate = Rate.max(initial, target)

    override def apply(point: Point): Rate = initial + (target - initial) * (point / maxPoint)
  }

  final case class Sinusoidal(base: Rate, amplitude: Rate, period: Point, shift: Option[Point]) extends RateFunction {

    private val frequency = 2 * math.Pi * (1 / period.value)

    private val phase = shift.fold(0.0)(shift => 2 * math.Pi * (shift.value / period.value))

    override def max: Rate = base + amplitude

    override def apply(point: Point): Rate = base + (amplitude * math.sin(frequency * point.value + phase))
  }

  final case class RandomWalk(
      initial: Rate,
      min: Rate,
      max: Rate,
      volatility: Double,
      trend: Double,
      random: UniformRandomProvider)
      extends RateFunction {

    private val sampler = ZigguratSampler.NormalizedGaussian.of(random)

    private var current = initial
    private var lastPoint: Option[Point] = None

    override def apply(point: Point): Rate = {
      lastPoint match {
        case Some(last) =>
          val delta = point.value - last.value
          val change = sampler.sample() * volatility * math.sqrt(delta) + trend * delta
          current = Rate(math.max(min.value, math.min(max.value, current.value + change)))
        case None =>
      }
      lastPoint = Some(point)
      current
    }
  }

  final case class Burst(base: Rate, peak: Rate, start: Point, up: Point, burst: Point, down: Point)
      extends RateFunction {

    override def max: Rate = peak

    override def apply(point: Point): Rate = {
      val peakStart = start + up
      val peakEnd = peakStart + burst
      val burstEnd = peakEnd + down

      if (point < start) { // before burst
        base
      } else if (point < peakStart) { // linear ramp up to peak
        val progress = (point - start) / up
        base + (peak - base) * progress
      } else if (point < peakEnd) { // burst peak
        peak
      } else if (point < burstEnd) { // linear ramp down from peak
        val progress = (point - peakEnd) / down
        peak - (peak - base) * progress
      } else { // after burst
        base
      }
    }
  }

  final case class Additive(functions: Seq[RateFunction]) extends RateFunction {
    override def max: Rate = Rate(functions.map(_.max.value).sum)

    override def apply(point: Point): Rate = Rate(functions.map(function => function(point).value).sum)
  }

  final case class Modulated(carrier: RateFunction, modulator: RateFunction, strength: Double) extends RateFunction {
    override def max: Rate = carrier.max * (1.0 + strength)

    override def apply(point: Point): Rate = {
      val baseRate = carrier(point)
      val modulatorFactor = 1.0 + (modulator(point).value / modulator.max.value) * strength
      baseRate * math.max(0.0, modulatorFactor)
    }
  }

  final case class Weighted(function: RateFunction, weight: Double)

  final case class Composite(weighted: Seq[Weighted]) extends RateFunction {
    private val totalWeight = weighted.map(_.weight).sum

    override def max: Rate = {
      Rate(weighted.map(rate => rate.function.max.value * (rate.weight / totalWeight)).sum)
    }

    override def apply(point: Point): Rate = {
      Rate(weighted.map(rate => rate.function(point).value * (rate.weight / totalWeight)).sum)
    }
  }
}
