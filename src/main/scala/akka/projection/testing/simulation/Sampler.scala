/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.simulation

import scala.concurrent.duration.FiniteDuration
import scala.util.hashing.MurmurHash3

import akka.projection.testing.simulation.Sampled.Continuous
import akka.projection.testing.simulation.Sampled.Discrete
import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.sampling.ArraySampler
import org.apache.commons.statistics.distribution.ContinuousDistribution
import org.apache.commons.statistics.distribution.ExponentialDistribution
import org.apache.commons.statistics.distribution.GammaDistribution
import org.apache.commons.statistics.distribution.LogNormalDistribution
import org.apache.commons.statistics.distribution.ParetoDistribution
import org.apache.commons.statistics.distribution.UniformContinuousDistribution
import org.apache.commons.statistics.distribution.UniformDiscreteDistribution
import org.apache.commons.statistics.distribution.WeibullDistribution
import org.apache.commons.statistics.distribution.ZipfDistribution

sealed trait Sampled[A] {
  def isDiscrete: Boolean
}

object Sampled {
  final case class Discrete[A](value: DiscreteValue[A]) extends Sampled[A] {
    override def isDiscrete: Boolean = true
  }

  final case class Continuous[A](value: ContinuousValue[A]) extends Sampled[A] {
    override def isDiscrete: Boolean = false
  }

  implicit val SampledInt: Sampled[Int] = Discrete(DiscreteValue.IntValue)
  implicit val SampledSlice: Sampled[Slice] = Discrete(DiscreteValue.SliceValue)
  implicit val SampledCount: Sampled[Count] = Discrete(DiscreteValue.CountValue)
  implicit val SampledDataSize: Sampled[DataSize] = Discrete(DiscreteValue.DataSizeValue)
  implicit val SampledDuration: Sampled[FiniteDuration] = Continuous(ContinuousValue.DurationValue)
}

sealed trait Sampler[A] {
  def min: A
  def max: A
  def sample(): A
}

object Sampler {

  def apply[A](settings: SamplerSettings[A], random: RandomProvider)(implicit sampled: Sampled[A]): Sampler[A] = {
    sampled match {
      case Discrete(value)   => new FromDiscrete(DiscreteSampler(settings, random)(value), value)
      case Continuous(value) => new FromContinuous(ContinuousSampler(settings, random)(value), value)
    }
  }

  final class FromDiscrete[A](sampler: DiscreteSampler, value: DiscreteValue[A]) extends Sampler[A] {
    override def min: A = value.fromInt(sampler.min)
    override def max: A = value.fromInt(sampler.max)
    override def sample(): A = value.fromInt(sampler.sample())
  }

  final class FromContinuous[A](sampler: ContinuousSampler, value: ContinuousValue[A]) extends Sampler[A] {
    override def min: A = value.fromDouble(sampler.min)
    override def max: A = value.fromDouble(sampler.max)
    override def sample(): A = value.fromDouble(sampler.sample())
  }
}

sealed trait DiscreteValue[A] {
  def toInt(value: A): Int
  def fromInt(value: Int): A
}

object DiscreteValue {
  implicit object IntValue extends DiscreteValue[Int] {
    override def toInt(value: Int): Int = value
    override def fromInt(value: Int): Int = value
  }

  implicit object SliceValue extends DiscreteValue[Slice] {
    override def toInt(slice: Slice): Int = slice.value
    override def fromInt(value: Int): Slice = Slice(value)
  }

  implicit object CountValue extends DiscreteValue[Count] {
    override def toInt(count: Count): Int = count.toInt
    override def fromInt(value: Int): Count = Count(value)
  }

  implicit object DataSizeValue extends DiscreteValue[DataSize] {
    override def toInt(dataSize: DataSize): Int = dataSize.toBytes
    override def fromInt(bytes: Int): DataSize = DataSize(bytes)
  }
}

sealed trait DiscreteSampler {
  def min: Int
  def max: Int
  def sample(): Int
}

object DiscreteSampler {

  def apply[Value](settings: SamplerSettings[Value], random: RandomProvider)(implicit
      discreteValue: DiscreteValue[Value]): DiscreteSampler = {
    val sampler = settings match {
      case SamplerSettings.ConstantSettings(value) =>
        Constant(discreteValue.toInt(value))

      case SamplerSettings.UniformSettings(minValue, maxValue) =>
        Uniform(discreteValue.toInt(minValue), discreteValue.toInt(maxValue), random.create())

      case SamplerSettings.IncrementalSettings(startValue, stepValue, limitValue) =>
        Incremental(
          discreteValue.toInt(startValue),
          discreteValue.toInt(stepValue),
          limitValue.map(discreteValue.toInt))

      case SamplerSettings.ZipfSettings(minValue, maxValue, exponent, _) =>
        val min = discreteValue.toInt(minValue)
        val max = discreteValue.toInt(maxValue)
        Zipf(min, max, exponent, random.create())

      case SamplerSettings.ExponentialSettings(meanValue, minValue, maxValue, _) =>
        val mean = discreteValue.toInt(meanValue).toDouble
        val min = minValue.map(discreteValue.toInt).getOrElse(0)
        val max = maxValue.map(discreteValue.toInt).getOrElse(Int.MaxValue)
        val continuousSampler =
          ContinuousSampler.Exponential(mean, min.toDouble, max.toDouble, random.create())
        Discretized(continuousSampler, min, max)

      case SamplerSettings.WeibullSettings(shape, scaleValue, minValue, maxValue, _) =>
        val scale = discreteValue.toInt(scaleValue).toDouble
        val min = minValue.map(discreteValue.toInt).getOrElse(0)
        val max = maxValue.map(discreteValue.toInt).getOrElse(Int.MaxValue)
        val continuousSampler =
          ContinuousSampler.Weibull(shape, scale, min.toDouble, max.toDouble, random.create())
        Discretized(continuousSampler, min, max)

      case SamplerSettings.GammaSettings(shape, scaleValue, minValue, maxValue, _) =>
        val scale = discreteValue.toInt(scaleValue).toDouble
        val min = minValue.map(discreteValue.toInt).getOrElse(0)
        val max = maxValue.map(discreteValue.toInt).getOrElse(Int.MaxValue)
        val continuousSampler =
          ContinuousSampler.Gamma(shape, scale, min.toDouble, max.toDouble, random.create())
        Discretized(continuousSampler, min, max)

      case logNormal: SamplerSettings.LogNormalSettings[Value] =>
        val min = logNormal.min.map(discreteValue.toInt).getOrElse(0)
        val max = logNormal.max.map(discreteValue.toInt).getOrElse(Int.MaxValue)

        val continuousSampler = logNormal match {
          case SamplerSettings.LogNormalSettings.MuSigmaSettings(mu, sigma, _, _, _) =>
            ContinuousSampler.LogNormal(mu, sigma, min.toDouble, max.toDouble, random.create())

          case SamplerSettings.LogNormalSettings.ScaleShapeSettings(scaleValue, shape, _, _, _) =>
            val scale = discreteValue.toInt(scaleValue).toDouble
            ContinuousSampler.LogNormal.fromScaleShape(scale, shape, min.toDouble, max.toDouble, random.create())

          case SamplerSettings.LogNormalSettings.MeanStdDevSettings(meanValue, stdDevValue, _, _, _) =>
            val mean = discreteValue.toInt(meanValue).toDouble
            val stdDev = discreteValue.toInt(stdDevValue).toDouble
            ContinuousSampler.LogNormal.fromMeanStdDev(mean, stdDev, min.toDouble, max.toDouble, random.create())

          case SamplerSettings.LogNormalSettings.MedianP95Settings(medianValue, p95Value, _, _, _) =>
            val median = discreteValue.toInt(medianValue).toDouble
            val p95 = discreteValue.toInt(p95Value).toDouble
            ContinuousSampler.LogNormal.fromMedianAndP95(median, p95, min.toDouble, max.toDouble, random.create())
        }

        Discretized(continuousSampler, min, max)

      case pareto: SamplerSettings.ParetoSettings[Value] =>
        val min = pareto match {
          case SamplerSettings.ParetoSettings.ScaleShapeSettings(scale, _, _, _) => discreteValue.toInt(scale)
          case SamplerSettings.ParetoSettings.MinP95Settings(min, _, _, _)       => discreteValue.toInt(min)
        }
        val max = pareto.max.map(discreteValue.toInt).getOrElse(Int.MaxValue)

        val continuousSampler = pareto match {
          case SamplerSettings.ParetoSettings.ScaleShapeSettings(scaleValue, shape, _, _) =>
            val scale = discreteValue.toInt(scaleValue).toDouble
            ContinuousSampler.Pareto(scale, shape, max.toDouble, random.create())

          case SamplerSettings.ParetoSettings.MinP95Settings(minValue, p95Value, _, _) =>
            val min = discreteValue.toInt(minValue).toDouble
            val p95 = discreteValue.toInt(p95Value).toDouble
            ContinuousSampler.Pareto.fromMinAndP95(min, p95, max.toDouble, random.create())
        }

        Discretized(continuousSampler, min, max)

      case SamplerSettings.CategoricalSettings(categories) =>
        val intCategories = categories.map(category => Category(discreteValue.toInt(category.value), category.weight))
        Categorical(intCategories, random.create())

      case SamplerSettings.CompositeSettings(samplers) =>
        val weightedSamplers =
          samplers.map(weighted => WeightedSampler(DiscreteSampler(weighted.sampler, random), weighted.weight))
        Composite(weightedSamplers, random.create())
    }

    if (settings.shuffled.getOrElse(false)) ShuffledSampler(sampler, random) else sampler
  }

  final case class Constant(value: Int) extends DiscreteSampler {
    override def min: Int = value
    override def max: Int = value
    override def sample(): Int = value
  }

  final case class Uniform(min: Int, max: Int, random: UniformRandomProvider) extends DiscreteSampler {

    private val distribution = UniformDiscreteDistribution.of(min, max)
    private val sampler = distribution.createSampler(random)

    override def sample(): Int = sampler.sample()
  }

  final case class Incremental(start: Int, step: Int, limit: Option[Int]) extends DiscreteSampler {
    private var counter = start

    override def min: Int = start
    override def max: Int = limit.getOrElse(Int.MaxValue)

    override def sample(): Int = {
      val value = counter
      counter += step
      if (limit.exists(counter.>=)) counter = start
      value
    }
  }

  final case class Zipf(min: Int, max: Int, exponent: Double, random: UniformRandomProvider) extends DiscreteSampler {

    private val numberOfElements = max - min + 1

    private val distribution = ZipfDistribution.of(numberOfElements, exponent)
    private val sampler = distribution.createSampler(random)

    override def sample(): Int = min + sampler.sample() - 1
  }

  // Converts any continuous sampler into a discrete sampler by rounding.
  final case class Discretized(sampler: ContinuousSampler, min: Int, max: Int) extends DiscreteSampler {
    override def sample(): Int = {
      val value = math.round(sampler.sample()).toInt
      math.min(math.max(value, min), max) // recheck bounds
    }
  }

  final case class Category(value: Int, weight: Double)

  object Category {
    implicit val weightedItem: WeightedItem[Category] = new WeightedItem[Category] {
      def weight(category: Category): Double = category.weight
    }
  }

  final case class Categorical(categories: Seq[Category], random: UniformRandomProvider) extends DiscreteSampler {
    private val weightedChoice = new WeightedChoice(categories, random)

    override val min: Int = categories.map(_.value).min
    override val max: Int = categories.map(_.value).max

    override def sample(): Int = weightedChoice.choose().value
  }

  final case class WeightedSampler(sampler: DiscreteSampler, weight: Double)

  object WeightedSampler {
    implicit val weightedItem: WeightedItem[WeightedSampler] = new WeightedItem[WeightedSampler] {
      def weight(sampler: WeightedSampler): Double = sampler.weight
    }
  }

  final case class Composite(weightedSamplers: Seq[WeightedSampler], random: UniformRandomProvider)
      extends DiscreteSampler {
    private val weightedChoice = new WeightedChoice(weightedSamplers, random)

    override val min: Int = weightedSamplers.map(_.sampler.min).min
    override val max: Int = weightedSamplers.map(_.sampler.max).max

    override def sample(): Int = weightedChoice.choose().sampler.sample()
  }

  object ShuffledSampler {
    val MaxShuffledElements = 5_000 // use fully shuffled array up to this size
    val MaxStretchedElements = 1_000_000 // use stretched sampling up to this size
    val StretchFactor = 100 // stretch factor for medium-range distributions

    def apply(underlying: DiscreteSampler, random: RandomProvider): DiscreteSampler = {
      val range = underlying.max.toLong - underlying.min.toLong + 1
      if (range <= MaxShuffledElements) {
        // small range: use full shuffling to maintain statistical properties
        FullyShuffled(underlying, random.create())
      } else if (range <= MaxStretchedElements) {
        // medium range: use stretched scrambling for good statistical approximation
        StretchedScrambled(underlying, random.create(), StretchFactor)
      } else {
        // large range: use direct scrambling
        DirectScrambled(underlying, random.create())
      }
    }

    // for small ranges: fully shuffle all possible values
    final case class FullyShuffled(underlying: DiscreteSampler, random: UniformRandomProvider) extends DiscreteSampler {

      override def min: Int = underlying.min
      override def max: Int = underlying.max

      private val shuffled: Array[Int] = {
        ArraySampler.shuffle(random, (min to max).toArray)
      }

      override def sample(): Int = shuffled(underlying.sample() - min)
    }

    // for medium ranges: stretch the distribution before scrambling
    final case class StretchedScrambled(underlying: DiscreteSampler, random: UniformRandomProvider, stretchFactor: Int)
        extends DiscreteSampler {

      override def min: Int = underlying.min
      override def max: Int = underlying.max

      private val numberOfElements = max - min + 1

      private val seed = random.nextInt()

      override def sample(): Int = {
        val sampled = underlying.sample()
        // stretched value with random offset
        val stretched = (sampled - min) * stretchFactor + random.nextInt(stretchFactor)
        min + math.abs(hash(stretched)) % numberOfElements
      }

      private def hash(value: Int): Int = {
        MurmurHash3.mixLast(MurmurHash3.mixLast(0x9747b28c, value), seed)
      }
    }

    // for large ranges: direct scrambling
    final case class DirectScrambled(underlying: DiscreteSampler, random: UniformRandomProvider)
        extends DiscreteSampler {

      override def min: Int = underlying.min
      override def max: Int = underlying.max

      private val numberOfElements = max.toLong - min.toLong + 1

      private val seed = random.nextInt()

      override def sample(): Int = scramble(underlying.sample())

      private def scramble(value: Int): Int = {
        if (numberOfElements > Int.MaxValue) {
          min + (hash64(value) % numberOfElements).toInt
        } else {
          min + math.abs(hash(value)) % numberOfElements.toInt
        }
      }

      private def hash(value: Int): Int = {
        MurmurHash3.mixLast(MurmurHash3.mixLast(0x9747b28c, value), seed)
      }

      private def hash64(value: Int): Long = {
        val h1 = MurmurHash3.mixLast(0x9747b28c, value)
        val h2 = MurmurHash3.mixLast(seed, value)
        math.abs((h1.toLong << 32) | (h2.toLong & 0xffffffffL))
      }
    }
  }

}

sealed trait ContinuousValue[A] {
  def toDouble(value: A): Double
  def fromDouble(value: Double): A
}

object ContinuousValue {
  implicit object DurationValue extends ContinuousValue[FiniteDuration] {
    override def toDouble(duration: FiniteDuration): Double = Point(duration).value
    override def fromDouble(value: Double): FiniteDuration = Point(value).toDuration
  }
}

sealed trait ContinuousSampler {
  def min: Double
  def max: Double
  def sample(): Double
}

object ContinuousSampler {

  def apply[A](settings: SamplerSettings[A], random: RandomProvider)(implicit
      continuousValue: ContinuousValue[A]): ContinuousSampler = {
    settings match {
      case SamplerSettings.ConstantSettings(value) =>
        Constant(continuousValue.toDouble(value))

      case SamplerSettings.UniformSettings(minValue, maxValue) =>
        val min = continuousValue.toDouble(minValue)
        val max = continuousValue.toDouble(maxValue)
        Uniform(min, max, random.create())

      case SamplerSettings.IncrementalSettings(startValue, stepValue, limitValue) =>
        Incremental(
          continuousValue.toDouble(startValue),
          continuousValue.toDouble(stepValue),
          limitValue.map(continuousValue.toDouble))

      case SamplerSettings.ZipfSettings(_, _, _, _) =>
        throw new IllegalArgumentException("Zipf distribution is not supported for continuous samplers")

      case SamplerSettings.ExponentialSettings(meanValue, minValue, maxValue, _) =>
        val mean = continuousValue.toDouble(meanValue)
        val min = minValue.map(continuousValue.toDouble).getOrElse(0.0)
        val max = maxValue.map(continuousValue.toDouble).getOrElse(Double.PositiveInfinity)
        Exponential(mean, min, max, random.create())

      case SamplerSettings.WeibullSettings(shape, scaleValue, minValue, maxValue, _) =>
        val scale = continuousValue.toDouble(scaleValue)
        val min = minValue.map(continuousValue.toDouble).getOrElse(0.0)
        val max = maxValue.map(continuousValue.toDouble).getOrElse(Double.PositiveInfinity)
        Weibull(shape, scale, min, max, random.create())

      case SamplerSettings.GammaSettings(shape, scaleValue, minValue, maxValue, _) =>
        val scale = continuousValue.toDouble(scaleValue)
        val min = minValue.map(continuousValue.toDouble).getOrElse(0.0)
        val max = maxValue.map(continuousValue.toDouble).getOrElse(Double.PositiveInfinity)
        Gamma(shape, scale, min, max, random.create())

      case logNormal: SamplerSettings.LogNormalSettings[A] =>
        val min = logNormal.min.map(continuousValue.toDouble).getOrElse(0.0)
        val max = logNormal.max.map(continuousValue.toDouble).getOrElse(Double.PositiveInfinity)

        logNormal match {
          case SamplerSettings.LogNormalSettings.MuSigmaSettings(mu, sigma, _, _, _) =>
            LogNormal(mu, sigma, min, max, random.create())

          case SamplerSettings.LogNormalSettings.ScaleShapeSettings(scaleValue, shape, _, _, _) =>
            val scale = continuousValue.toDouble(scaleValue)
            LogNormal.fromScaleShape(scale, shape, min, max, random.create())

          case SamplerSettings.LogNormalSettings.MeanStdDevSettings(meanValue, stdDevValue, _, _, _) =>
            val mean = continuousValue.toDouble(meanValue)
            val stdDev = continuousValue.toDouble(stdDevValue)
            LogNormal.fromMeanStdDev(mean, stdDev, min, max, random.create())

          case SamplerSettings.LogNormalSettings.MedianP95Settings(medianValue, p95Value, _, _, _) =>
            val median = continuousValue.toDouble(medianValue)
            val p95 = continuousValue.toDouble(p95Value)
            LogNormal.fromMedianAndP95(median, p95, min, max, random.create())
        }

      case pareto: SamplerSettings.ParetoSettings[A] =>
        val max = pareto.max.map(continuousValue.toDouble).getOrElse(Double.PositiveInfinity)

        pareto match {
          case SamplerSettings.ParetoSettings.ScaleShapeSettings(scaleValue, shape, _, _) =>
            val scale = continuousValue.toDouble(scaleValue)
            Pareto(scale, shape, max, random.create())

          case SamplerSettings.ParetoSettings.MinP95Settings(minValue, p95Value, _, _) =>
            val min = continuousValue.toDouble(minValue)
            val p95 = continuousValue.toDouble(p95Value)
            Pareto.fromMinAndP95(min, p95, max, random.create())
        }

      case SamplerSettings.CategoricalSettings(categories) =>
        val doubleCategories = categories.map { category =>
          Category(continuousValue.toDouble(category.value), category.weight)
        }
        Categorical(doubleCategories, random.create())

      case SamplerSettings.CompositeSettings(samplers) =>
        val weightedSamplers = samplers.map { weighted =>
          WeightedSampler(ContinuousSampler(weighted.sampler, random), weighted.weight)
        }
        Composite(weightedSamplers, random.create())
    }
  }

  abstract class BoundedContinuousSampler(min: Double, max: Double) extends ContinuousSampler {

    protected def sampleUnbounded(): Double

    override final def sample(): Double = {
      val value = sampleUnbounded()
      math.min(math.max(value, min), max)
    }
  }

  final case class Constant(value: Double) extends ContinuousSampler {
    override def min: Double = value
    override def max: Double = value
    override def sample(): Double = value
  }

  // https://en.wikipedia.org/wiki/Continuous_uniform_distribution
  final case class Uniform(min: Double, max: Double, random: UniformRandomProvider) extends ContinuousSampler {

    private val distribution = UniformContinuousDistribution.of(min, max)
    private val sampler = distribution.createSampler(random)

    override def sample(): Double = sampler.sample()
  }

  final case class Incremental(start: Double, step: Double, limit: Option[Double]) extends ContinuousSampler {
    private var counter = start

    override def min: Double = start
    override def max: Double = limit.getOrElse(Double.PositiveInfinity)

    override def sample(): Double = {
      val value = counter
      counter += step
      if (limit.exists(counter.>=)) counter = start
      value
    }
  }

  // https://en.wikipedia.org/wiki/Exponential_distribution
  final case class Exponential(mean: Double, min: Double, max: Double, random: UniformRandomProvider)
      extends BoundedContinuousSampler(min, max) {

    private val distribution = ExponentialDistribution.of(mean)
    private val sampler = distribution.createSampler(random)

    override protected def sampleUnbounded(): Double = sampler.sample()
  }

  // https://en.wikipedia.org/wiki/Weibull_distribution
  final case class Weibull(shape: Double, scale: Double, min: Double, max: Double, random: UniformRandomProvider)
      extends BoundedContinuousSampler(min, max) {

    private val distribution = WeibullDistribution.of(shape, scale)
    private val sampler = distribution.createSampler(random)

    override protected def sampleUnbounded(): Double = sampler.sample()
  }

  // https://en.wikipedia.org/wiki/Gamma_distribution
  final case class Gamma(shape: Double, scale: Double, min: Double, max: Double, random: UniformRandomProvider)
      extends BoundedContinuousSampler(min, max) {

    private val distribution = GammaDistribution.of(shape, scale)
    private val sampler = distribution.createSampler(random)

    override protected def sampleUnbounded(): Double = sampler.sample()
  }

  // https://en.wikipedia.org/wiki/Log-normal_distribution
  final case class LogNormal(mu: Double, sigma: Double, min: Double, max: Double, random: UniformRandomProvider)
      extends BoundedContinuousSampler(min, max) {

    private val distribution = LogNormalDistribution.of(mu, sigma)
    private val sampler = distribution.createSampler(random)

    override protected def sampleUnbounded(): Double = sampler.sample()
  }

  object LogNormal {
    def fromScaleShape(
        scale: Double,
        shape: Double,
        min: Double,
        max: Double,
        random: UniformRandomProvider): LogNormal = {
      val (mu, sigma) = DistributionParameters.LogNormal.muSigmaFromScaleShape(scale, shape)
      LogNormal(mu, sigma, min, max, random)
    }

    def fromMeanStdDev(
        mean: Double,
        stdDev: Double,
        min: Double,
        max: Double,
        random: UniformRandomProvider): LogNormal = {
      val (mu, sigma) = DistributionParameters.LogNormal.muSigmaFromMeanStdDev(mean, stdDev)
      LogNormal(mu, sigma, min, max, random)
    }

    def fromMedianAndP95(
        median: Double,
        p95: Double,
        min: Double,
        max: Double,
        random: UniformRandomProvider): LogNormal = {
      val (mu, sigma) = DistributionParameters.LogNormal.muSigmaFromMedianP95(median, p95)
      LogNormal(mu, sigma, min, max, random)
    }

    def fromMinMax(min: Double, max: Double, shape: Option[Double], random: UniformRandomProvider): LogNormal = {
      val (mu, sigma) = DistributionParameters.LogNormal.muSigmaFromMinMax(min, max, shape)
      LogNormal(mu, sigma, min, max, random)
    }
  }

  // https://en.wikipedia.org/wiki/Pareto_distribution
  final case class Pareto(scale: Double, shape: Double, max: Double, random: UniformRandomProvider)
      extends ContinuousSampler {

    private val distribution = ParetoDistribution.of(scale, shape)
    private val sampler = distribution.createSampler(random)

    override def min: Double = scale // scale parameter is the minimum value in Pareto

    override def sample(): Double = {
      val value = sampler.sample()
      math.min(value, max) // upper bound only - lower bound is already enforced by Pareto distribution
    }
  }

  object Pareto {
    def fromMinAndP95(min: Double, p95: Double, max: Double, random: UniformRandomProvider): Pareto = {
      val shape = math.log(0.05) / -math.log(p95 / min)
      Pareto(min, shape, max, random)
    }
  }

  final case class Category(value: Double, weight: Double)

  object Category {
    implicit val weightedItem: WeightedItem[Category] = new WeightedItem[Category] {
      def weight(category: Category): Double = category.weight
    }
  }

  final case class Categorical(categories: Seq[Category], random: UniformRandomProvider) extends ContinuousSampler {
    private val weightedChoice = new WeightedChoice(categories, random)

    override val min: Double = categories.map(_.value).min
    override val max: Double = categories.map(_.value).max

    override def sample(): Double = weightedChoice.choose().value
  }

  final case class WeightedSampler(sampler: ContinuousSampler, weight: Double)

  object WeightedSampler {
    implicit val weightedItem: WeightedItem[WeightedSampler] = new WeightedItem[WeightedSampler] {
      def weight(sampler: WeightedSampler): Double = sampler.weight
    }
  }

  final case class Composite(weightedSamplers: Seq[WeightedSampler], random: UniformRandomProvider)
      extends ContinuousSampler {
    private val weightedChoice = new WeightedChoice(weightedSamplers, random)

    override val min: Double = weightedSamplers.map(_.sampler.min).min
    override val max: Double = weightedSamplers.map(_.sampler.max).max

    override def sample(): Double = weightedChoice.choose().sampler.sample()
  }
}

trait WeightedItem[A] {
  def weight(item: A): Double
}

private final class WeightedChoice[A: WeightedItem](items: Seq[A], random: UniformRandomProvider) {
  private val weighted = implicitly[WeightedItem[A]]
  private val indexedItems = items.toIndexedSeq
  private val weights = items.map(weighted.weight)
  private val totalWeight = weights.sum
  private val cumulativeWeights = weights.scanLeft(0.0)(_ + _).tail

  def choose(): A = {
    val r = random.nextDouble() * totalWeight
    val index = cumulativeWeights.indexWhere(_ >= r)
    indexedItems(index)
  }
}

object DistributionParameters {
  private val Z95 = 1.645 // 95th percentile z-score
  private val Z99 = 2.326 // 99th percentile z-score

  def exponentialMean(min: Double, max: Double): Double = {
    // use percentile-based approach - 95% of values between min and max
    -(max - min) / math.log(0.05)
  }

  def weibullScale(min: Double, max: Double, shape: Double): Double = {
    (max - min) / scaleFactor(shape)
  }

  def gammaScale(min: Double, max: Double, shape: Double): Double = {
    (max - min) / (shape * scaleFactor(shape))
  }

  private def scaleFactor(shape: Double): Double = {
    val maxDivisor = 3.0
    val minDivisor = 1.5

    if (shape <= 0.5) {
      maxDivisor // max divisor for very small shapes
    } else if (shape >= 4.0) {
      minDivisor // min divisor for normal-like shapes
    } else {
      // smooth transition function between scale factor values
      val normalizedShape = (shape - 0.5) / 3.5
      val transition = math.tanh(2.5 * normalizedShape - 1.25) / 2 + 0.5
      maxDivisor - ((maxDivisor - minDivisor) * transition)
    }
  }

  object LogNormal {

    def muSigmaFromScaleShape(scale: Double, shape: Double): (Double, Double) = {
      val mu = math.log(scale)
      (mu, shape)
    }

    def muSigmaFromMeanStdDev(mean: Double, stdDev: Double): (Double, Double) = {
      val sigma = math.sqrt(math.log(1 + (stdDev * stdDev) / (mean * mean)))
      val mu = math.log(mean) - (sigma * sigma / 2)
      (mu, sigma)
    }

    def muSigmaFromMedianP95(median: Double, p95: Double): (Double, Double) = {
      val sigma = math.log(p95 / median) / Z95
      val mu = math.log(median) // for median, z-score = 0
      (mu, sigma)
    }

    def muSigmaFromMinMax(min: Double, max: Double, shape: Option[Double] = None): (Double, Double) = {
      // ensure min is positive for log-normal distribution
      val adjustedMin = if (min <= 0) math.max(0.001, max / 10000.0) else min
      val median = math.sqrt(adjustedMin * max) // geometric mean for median
      val mu = math.log(median)
      val sigma = shape.getOrElse {
        val logRatio = math.log(max / adjustedMin)
        val percentileFactor = if (logRatio > 6.0) Z99 else Z95
        logRatio / (2 * percentileFactor)
      }
      (mu, sigma)
    }
  }
}

object DeterministicIndexMapper {
  // Reuse the same thresholds as ShuffledSampler for consistency
  val MaxShuffledElements = 5_000 // use fully shuffled array up to this size
  val MaxStretchedElements = 1_000_000 // use stretched sampling up to this size
  val StretchFactor = 100 // stretch factor for medium-range distributions

  private def hashInputToUniform(input: Int, seed: Seq[Long]): Double = {
    var h = 0x9747b28c
    seed.foreach { s => h = MurmurHash3.mixLast(h, s.toInt) }
    h = MurmurHash3.mixLast(h, input)
    val finalHash = MurmurHash3.finalizeHash(h, 4) // length=4 for Int
    (finalHash.toDouble - Int.MinValue.toDouble) / (1L << 32)
  }

  private def scrambleIndex(index: Int, numIndices: Int, input: Int, seed: Seq[Long]): Int = {
    if (numIndices <= 0) return 0
    var h = 0x85ebca6b
    seed.foreach { s => h = MurmurHash3.mixLast(h, s.toInt) }
    h = MurmurHash3.mixLast(h, input)
    val scrambleSeed = MurmurHash3.finalizeHash(h, 4) // length=4 for Int
    val scrambledHash = MurmurHash3.mixLast(MurmurHash3.mixLast(0x9747b28c, index), scrambleSeed)
    val finalScrambledHash = MurmurHash3.finalizeHash(scrambledHash, 4) // length=4 for Int
    math.floorMod(finalScrambledHash, numIndices)
  }

  private def stretchedScrambleIndex(index: Int, numIndices: Int, input: Int, seed: Seq[Long]): Int = {
    if (numIndices <= 0) return 0
    var h = 0x9747b28c
    seed.foreach { s => h = MurmurHash3.mixLast(h, s.toInt) }
    h = MurmurHash3.mixLast(h, input)
    val scrambleSeed = MurmurHash3.finalizeHash(h, 4) // length=4 for Int

    // Apply stretch factor with deterministic offset based on input
    val stretchedIndex = index * StretchFactor + (math.abs(scrambleSeed) % StretchFactor)
    val finalHash = MurmurHash3.mixLast(MurmurHash3.mixLast(0x9747b28c, stretchedIndex), scrambleSeed)
    math.abs(finalHash) % numIndices
  }
}

final class DeterministicIndexMapper(
    minIndex: Int,
    maxIndex: Int,
    distribution: DistributionShape,
    random: RandomProvider) {

  import DeterministicIndexMapper._

  private val numIndices = maxIndex - minIndex + 1
  private val baseSeed = random.seed

  // For small distributions that need shuffling, pre-compute a shuffled array
  private val shuffledIndices: Option[Array[Int]] = {
    if (distribution.shuffled.getOrElse(false) && numIndices > 0 && numIndices <= MaxShuffledElements) {
      val indices = (0 until numIndices).toArray
      val rng = random.create()
      Some(ArraySampler.shuffle(rng, indices))
    } else {
      None
    }
  }

  private val underlyingDistribution = distribution match {
    case _ if numIndices <= 0      => None
    case DistributionShape.Uniform => None
    case DistributionShape.Zipf(exponent, _) =>
      Some(ZipfDistribution.of(numIndices, exponent))
    case DistributionShape.Exponential(_) =>
      val mean = DistributionParameters.exponentialMean(0, numIndices - 1)
      Some(ExponentialDistribution.of(mean))
    case DistributionShape.Weibull(shape, _) =>
      val scaleFactor = DistributionParameters.weibullScale(0, numIndices - 1, shape)
      val scale = if (scaleFactor == 0) Double.MaxValue else (numIndices - 1) / scaleFactor
      Some(WeibullDistribution.of(shape, math.max(scale, 1e-9)))
    case DistributionShape.Gamma(shape, _) =>
      val scaleFactor = DistributionParameters.gammaScale(0, numIndices - 1, shape)
      val scale = if (shape == 0 || scaleFactor == 0) Double.MaxValue else (numIndices - 1) / (shape * scaleFactor)
      Some(GammaDistribution.of(shape, math.max(scale, 1e-9)))
    case DistributionShape.LogNormal(shape, _) =>
      val (mu, sigma) = DistributionParameters.LogNormal.muSigmaFromMinMax(0, numIndices - 1, Some(shape))
      Some(LogNormalDistribution.of(mu, sigma))
    case DistributionShape.Pareto(shape, _) =>
      val scale = 1.0 // use 1.0 as minimum scale (Pareto requires scale > 0)
      val adjustedShape = math.max(1.05, shape)
      Some(ParetoDistribution.of(scale, adjustedShape))
  }

  def map(input: Int): Int = {
    if (numIndices <= 0) {
      throw new NoSuchElementException(s"Cannot map input $input: index range [$minIndex, $maxIndex] is empty.")
    }

    if (numIndices == 1) {
      return minIndex
    }

    val uniformValue = hashInputToUniform(input, baseSeed)
    // clamp slightly away from exact 0.0 and 1.0 for robustness with inverse CDFs
    val clampedUniform = math.max(Double.MinPositiveValue, math.min(uniformValue, 1.0 - Double.MinPositiveValue))

    val randomIndex = distribution match {
      case DistributionShape.Uniform =>
        math.floor(clampedUniform * numIndices).toInt

      case DistributionShape.Zipf(_, _) =>
        underlyingDistribution match {
          case Some(dist: ZipfDistribution) => dist.inverseCumulativeProbability(clampedUniform) - 1 // Zipf is 1-based
          case _ => throw new IllegalStateException("ZipfDistribution not pre-calculated or invalid")
        }

      case _ =>
        underlyingDistribution match {
          case Some(dist: ContinuousDistribution) => math.floor(dist.inverseCumulativeProbability(clampedUniform)).toInt
          case _ =>
            throw new IllegalStateException(s"ContinuousDistribution not pre-calculated or invalid for $distribution")
        }
    }

    val boundedIndex = math.max(0, math.min(randomIndex, numIndices - 1))

    val finalIndex = if (distribution.shuffled.getOrElse(false)) {
      shuffledIndices match {
        case Some(shuffled) =>
          // Small range: use pre-computed shuffled array for perfect distribution preservation
          shuffled(boundedIndex)
        case None if numIndices <= MaxStretchedElements =>
          // Medium range: use stretched scrambling for good statistical approximation
          stretchedScrambleIndex(boundedIndex, numIndices, input, baseSeed)
        case None =>
          // Large range: use direct hash scrambling
          scrambleIndex(boundedIndex, numIndices, input, baseSeed)
      }
    } else {
      boundedIndex
    }

    minIndex + finalIndex
  }
}
