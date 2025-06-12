/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.simulation

import scala.concurrent.duration._

/**
 * Simulation configuration.
 *
 * @param name
 *   Optional name for this simulation for identification
 * @param description
 *   Optional description for this simulation
 * @param stages
 *   Sequence of stage configurations that will be executed sequentially
 * @param engine
 *   Optional settings for the simulation execution engine
 */
final case class SimulationSettings(
    name: Option[String],
    description: Option[String],
    stages: Seq[StageSettings],
    engine: Option[EngineSettings])

/**
 * Settings for a simulation stage. Stages are executed sequentially in the order they are defined.
 *
 * @param name
 *   Optional name for this stage for identification
 * @param duration
 *   Total runtime for this stage
 * @param delay
 *   Optional delay before this stage starts (relative to the end of the previous stage)
 * @param generators
 *   Generator configurations controlling workload creation for this stage
 */
final case class StageSettings(
    name: Option[String],
    duration: FiniteDuration,
    delay: Option[FiniteDuration],
    generators: Seq[GeneratorSettings])

/**
 * Settings that control a specific workload generator's behavior.
 *
 * @param entityId
 *   Configuration for the entity access patterns
 * @param activity
 *   Configuration for the activity patterns
 * @param random
 *   Optional random number generator settings
 */
final case class GeneratorSettings(
    entityId: EntityIdSettings,
    activity: ActivitySettings,
    random: Option[RandomSettings])

/**
 * Configuration for entity access patterns in the simulation.
 *
 * @param entity
 *   Sampler settings for selecting entities
 * @param slices
 *   Optional range of slices that the entities are distributed over, otherwise all slices
 * @param sliceDistribution
 *   Optional distribution shape for assigning slices to entities, otherwise uniform
 */
final case class EntityIdSettings(
    entity: SamplerSettings[Int],
    slices: Option[Range[Slice]],
    sliceDistribution: Option[DistributionShape])

/**
 * Configuration for activity patterns in the simulation.
 *
 * @param frequency
 *   Frequency of new activities
 * @param perSlice
 *   Mapping of slices to their respective settings
 */
final case class ActivitySettings(
    frequency: PointProcessSettings,
    perSlice: RangeMap[Slice, ActivitySettings.PerSliceSettings])

object ActivitySettings {

  /**
   * Activity settings for a specific slice.
   *
   * @param perEntity
   *   Mapping of entity indices to their respective settings
   */
  final case class PerSliceSettings(perEntity: RangeMap[Int, PerEntitySettings])

  /**
   * Activity settings for a specific entity.
   *
   * @param duration
   *   Sampler for how long the activity with the entity lasts
   * @param event
   *   Configuration for event generation during an activity.
   */
  final case class PerEntitySettings(duration: SamplerSettings[FiniteDuration], event: EventSettings)
}

/**
 * Settings for event generation during an activity.
 *
 * @param frequency
 *   Frequency of events in an activity
 * @param dataSize
 *   Data size for each generated event
 */
final case class EventSettings(frequency: PointProcessSettings, dataSize: SamplerSettings[DataSize])

/**
 * Configuration for random number generation.
 *
 * @param algorithm
 *   Optional random algorithm specification (from Apache Commons random sources)
 * @param seed
 *   Optional seed value for deterministic randomization
 */
final case class RandomSettings(algorithm: Option[String], seed: Option[Long])

/**
 * Settings for the simulation execution engine.
 *
 * @param tick
 *   Time interval for simulation clock advancement
 * @param parallelism
 *   Degree of parallel execution
 * @param ackPersists
 *   Whether to wait for acknowledge for entity persists
 * @param validationTimeout
 *   Timeout for projection validation after simulation completes
 */
final case class EngineSettings(
    tick: Option[FiniteDuration],
    parallelism: Option[Int],
    ackPersists: Option[Boolean],
    validationTimeout: Option[FiniteDuration])

/**
 * Settings that define the behavior of a point process (the occurrence of discrete events in continuous time).
 */
sealed trait PointProcessSettings

object PointProcessSettings {

  /**
   * Settings for a Poisson point process, where events occur continuously and independently.
   *
   * @param rate
   *   The rate function settings that determine how the intensity changes over time
   */
  final case class PoissonSettings(rate: RateFunctionSettings) extends PointProcessSettings
}

/**
 * Settings that define the rate function behavior for point processes.
 *
 * The rate function determines the intensity of events over time.
 */
sealed trait RateFunctionSettings

object RateFunctionSettings {

  /**
   * Constant rate that doesn't change over time.
   *
   * @param value
   *   The fixed rate at which events occur
   */
  final case class ConstantSettings(value: RatePerUnit) extends RateFunctionSettings

  /**
   * Linear rate function that interpolates between initial and target rates over time.
   *
   * @param initial
   *   The rate at the beginning of the time period
   * @param target
   *   The rate at the end of the time period
   */
  final case class LinearSettings(initial: RatePerUnit, target: RatePerUnit) extends RateFunctionSettings

  /**
   * Sinusoidal rate function that oscillates around a base rate.
   *
   * @param base
   *   The center rate around which oscillation occurs
   * @param amplitude
   *   The maximum deviation from the base rate
   * @param period
   *   The time taken for one complete oscillation
   * @param shift
   *   The time offset for the wave pattern
   */
  final case class SinusoidalSettings(
      base: RatePerUnit,
      amplitude: RatePerUnit,
      period: FiniteDuration,
      shift: Option[FiniteDuration])
      extends RateFunctionSettings

  /**
   * Random walk rate that models stochastic fluctuations within bounds.
   *
   * @param initial
   *   The starting rate
   * @param min
   *   The minimum allowed rate
   * @param max
   *   The maximum allowed rate
   * @param volatility
   *   The magnitude of random fluctuations
   * @param trend
   *   Optional directional bias (positive values create an upward trend, while negative values create a downward trend)
   */
  final case class RandomWalkSettings(
      initial: RatePerUnit,
      min: RatePerUnit,
      max: RatePerUnit,
      volatility: Double,
      trend: Option[Double])
      extends RateFunctionSettings

  /**
   * Burst rate function that simulates temporary spikes in activity.
   *
   * @param base
   *   The normal background rate
   * @param peak
   *   The maximum rate during the burst
   * @param start
   *   When the burst begins after simulation start
   * @param rampUp
   *   Duration over which rate increases to peak
   * @param burst
   *   Duration at peak rate
   * @param rampDown
   *   Duration over which rate returns to base
   */
  final case class BurstSettings(
      base: RatePerUnit,
      peak: RatePerUnit,
      start: FiniteDuration,
      rampUp: FiniteDuration,
      burst: FiniteDuration,
      rampDown: FiniteDuration)
      extends RateFunctionSettings

  /**
   * Additive combination of multiple rate functions. The resulting rate is the sum of all component rates.
   *
   * @param additiveRates
   *   Rate functions to combine by addition
   */
  final case class AdditiveSettings(additiveRates: Seq[RateFunctionSettings]) extends RateFunctionSettings

  /**
   * Modulated rate function where one function modulates another. The carrier rate is multiplied by a modulation factor
   * derived from the modulator.
   *
   * @param carrier
   *   The primary rate function
   * @param modulator
   *   The rate function that provides modulation
   * @param strength
   *   Optional intensity of modulation (0.0 = no modulation, 1.0 = full modulation)
   */
  final case class ModulatedSettings(
      carrier: RateFunctionSettings,
      modulator: RateFunctionSettings,
      strength: Option[Double])
      extends RateFunctionSettings

  /**
   * Weighted combination of multiple rate functions. The resulting rate is a weighted average of component rates.
   *
   * @param compositeRates
   *   A sequence of weighted rate function settings, each with a relative probability weight.
   */
  final case class CompositeSettings(compositeRates: Seq[WeightedSettings]) extends RateFunctionSettings

  /**
   * Defines a rate function with its relative weight for use in composite rate function.
   *
   * @param rate
   *   The rate function settings to use
   * @param weight
   *   The relative weight for this rate function's contribution to the composite rate function.
   */
  final case class WeightedSettings(rate: RateFunctionSettings, weight: Double)
}

/**
 * Settings that define how values are sampled from various probability distributions.
 *
 * The specific type determines the distribution's shape, parameters, and constraints.
 *
 * @tparam Value
 *   The type of value that will be generated by the sampler
 */
sealed trait SamplerSettings[Value] {

  /**
   * Applies shuffling to a discrete sampler.
   *
   * Shuffling is most meaningful for distributions where values have different probabilities (like Zipf or exponential)
   * and is not supported for distributions where values already have equal probability (uniform) or are constant.
   */
  def shuffled: Option[Boolean]
}

object SamplerSettings {

  /**
   * Constant sampler that always returns the same value.
   *
   * @param value
   *   The fixed value that will always be returned
   */
  final case class ConstantSettings[Value: Sampled](value: Value) extends SamplerSettings[Value] {
    override def shuffled: Option[Boolean] = None
  }

  /**
   * Uniform distribution settings where all values within the specified range have equal probability.
   *
   * @param min
   *   The minimum value (inclusive) that can be generated
   * @param max
   *   The maximum value (inclusive) that can be generated
   */
  final case class UniformSettings[Value: Sampled](min: Value, max: Value) extends SamplerSettings[Value] {
    override def shuffled: Option[Boolean] = None
  }

  /**
   * Incremental sampler settings.
   *
   * @param start
   *   The starting value for the counter.
   * @param step
   *   The step value by which the counter is incremented.
   * @param limit
   *   Optional limit value. If set, the counter will cycle back to the start value once the limit is reached.
   */
  final case class IncrementalSettings[Value: Sampled](start: Value, step: Value, limit: Option[Value] = None)
      extends SamplerSettings[Value] {
    override def shuffled: Option[Boolean] = None
  }

  /**
   * Zipf distribution settings for modeling power-law relationships.
   *
   * In a Zipf distribution, the frequency of an item is inversely proportional to its rank. This is commonly used to
   * model real-world phenomena where a small number of items occur with high frequency while most items occur rarely.
   *
   * @param min
   *   The minimum value (inclusive) that can be generated
   *
   * @param max
   *   The maximum value (inclusive) that can be generated
   *
   * @param exponent
   *   Controls the "steepness" of the distribution. Higher values create more skewed distributions where the
   *   highest-ranked items occur much more frequently than others. Typical values range from 1.0 to 3.0, with 1.0 being
   *   classic Zipf's law.
   *
   * @param shuffled
   *   Whether to apply shuffling to the distribution. Shuffling preserves the frequency distribution but randomizes the
   *   values, creating a more realistic distribution for scenarios where frequency shouldn't be directly tied to rank
   *   (i.e., the smallest values shouldn't necessarily be the most common). Default is false.
   */
  final case class ZipfSettings[Value: Sampled](
      min: Value,
      max: Value,
      exponent: Double,
      shuffled: Option[Boolean] = None)
      extends SamplerSettings[Value]

  /**
   * Exponential distribution settings.
   *
   * When applied to discrete samplers, generated values will be rounded to the nearest integer.
   *
   * @param mean
   *   The mean (average) value
   *
   * @param min
   *   Optional minimum value bound (inclusive). For discrete samplers, setting a reasonable minimum can be useful as
   *   the exponential distribution technically includes very small values.
   *
   * @param max
   *   Optional maximum value bound (inclusive). For discrete samplers, setting a reasonable maximum prevents the
   *   occasional very large values that the distribution can theoretically generate.
   *
   * @param shuffled
   *   Whether to apply shuffling to the distribution. Shuffling preserves the frequency distribution but randomizes the
   *   values, creating a more realistic distribution for scenarios where frequency shouldn't be directly tied to value.
   */
  final case class ExponentialSettings[Value: Sampled](
      mean: Value,
      min: Option[Value] = None,
      max: Option[Value] = None,
      shuffled: Option[Boolean] = None)
      extends SamplerSettings[Value]

  /**
   * Weibull distribution settings. Versatile for modeling values with different variability patterns.
   *
   * When applied to discrete samplers, generated values will be rounded to the nearest integer.
   *
   * @param shape
   *   The shape parameter (k) that determines the distribution's behavior:
   *   - shape < 1: decreasing rate (many smaller values, with long tails)
   *   - shape = 1: constant rate (equivalent to exponential distribution)
   *   - shape > 1: increasing rate (samples cluster around the characteristic value)
   *   - shape ≈ 3.5: approximates normal distribution (symmetric around the mean)
   *
   * @param scale
   *   The scale parameter (λ) that determines the characteristic scale of the distribution
   *
   * @param min
   *   Optional minimum value bound (inclusive). For discrete samplers, provides a lower truncation point.
   *
   * @param max
   *   Optional maximum value bound (inclusive). For discrete samplers, provides an upper truncation point.
   *
   * @param shuffled
   *   Whether to apply shuffling to the distribution. Shuffling preserves the frequency distribution but randomizes the
   *   values, creating a more realistic distribution for scenarios where frequency shouldn't be directly tied to value.
   */
  final case class WeibullSettings[Value: Sampled](
      shape: Double,
      scale: Value,
      min: Option[Value] = None,
      max: Option[Value] = None,
      shuffled: Option[Boolean] = None)
      extends SamplerSettings[Value]

  /**
   * Gamma distribution settings. Good for modeling the sum of exponential random variables.
   *
   * When applied to discrete samplers, generated values will be rounded to the nearest integer.
   *
   * @param shape
   *   The shape parameter (k) that controls the basic form of the distribution
   *
   * @param scale
   *   The scale parameter (θ) that stretches or compresses the distribution
   *
   * @param min
   *   Optional minimum value bound (inclusive)
   *
   * @param max
   *   Optional maximum value bound (inclusive)
   *
   * @param shuffled
   *   Whether to apply shuffling to the distribution. Shuffling preserves the frequency distribution but randomizes the
   *   values, creating a more realistic distribution for scenarios where frequency shouldn't be directly tied to value.
   */
  final case class GammaSettings[Value: Sampled](
      shape: Double,
      scale: Value,
      min: Option[Value] = None,
      max: Option[Value] = None,
      shuffled: Option[Boolean] = None)
      extends SamplerSettings[Value]

  /**
   * Log-normal distribution settings.
   *
   * Useful for modelling values with positive skew, where most are clustered around a central value but with a long
   * tail of occasional larger values. When applied to discrete samplers, generated values will be rounded to the
   * nearest integer.
   */
  sealed trait LogNormalSettings[Value] extends SamplerSettings[Value] {
    def min: Option[Value]
    def max: Option[Value]
  }

  object LogNormalSettings {

    /**
     * Log-normal distribution parameters using direct mathematical parameters μ (mu) and σ (sigma).
     *
     * @param mu
     *   Location parameter of the log-normal distribution (mean in log-space). This is a dimensionless quantity that
     *   affects the scale of the distribution.
     *
     * @param sigma
     *   The shape parameter of the log-normal distribution (standard deviation in log-space). Controls the
     *   spread/dispersion of the distribution:
     *   - Small values (< 0.25): distribution is nearly symmetric
     *   - Medium values (0.25-0.5): moderate right skew
     *   - Large values (> 1.0): heavy right skew with extreme values
     *
     * @param min
     *   Optional minimum value bound (inclusive)
     *
     * @param max
     *   Optional maximum value bound (inclusive)
     *
     * @param shuffled
     *   Whether to apply shuffling to the distribution. Shuffling preserves the frequency distribution but randomizes
     *   the values, creating a more realistic distribution for scenarios where frequency shouldn't be directly tied to
     *   value.
     */
    final case class MuSigmaSettings[Value: Sampled](
        mu: Double,
        sigma: Double,
        min: Option[Value] = None,
        max: Option[Value] = None,
        shuffled: Option[Boolean] = None)
        extends LogNormalSettings[Value]

    /**
     * Log-normal distribution parameters using a scale-shape parameterization.
     *
     * Note: scale defines "where" the distribution is centered, while shape defines "how spread out" it is.
     *
     * @param scale
     *   The scale parameter (`e^μ`), which equals the median of the distribution. This parameter sets the central
     *   tendency of the distribution.
     *
     * @param shape
     *   The shape parameter (σ), which is the standard deviation in logarithmic space. Higher values create more
     *   dispersed distributions with heavier tails.
     *   - shape ≈ 0.1: very tight distribution close to the median
     *   - shape ≈ 0.5: moderate variation around the median
     *   - shape ≈ 1.0: substantial variation with many extreme values
     *
     * @param min
     *   Optional minimum value bound (inclusive)
     *
     * @param max
     *   Optional maximum value bound (inclusive)
     *
     * @param shuffled
     *   Whether to apply shuffling to the distribution. Shuffling preserves the frequency distribution but randomizes
     *   the values, creating a more realistic distribution for scenarios where frequency shouldn't be directly tied to
     *   value.
     */
    final case class ScaleShapeSettings[Value: Sampled](
        scale: Value,
        shape: Double,
        min: Option[Value] = None,
        max: Option[Value] = None,
        shuffled: Option[Boolean] = None)
        extends LogNormalSettings[Value]

    /**
     * Log-normal distribution parameters defined by arithmetic mean and standard deviation.
     *
     * @param mean
     *   The arithmetic mean of the distribution. Note that this is not the same as the median due to the skewed nature
     *   of log-normal distributions. The mean is always larger than the median.
     *
     * @param stdDev
     *   The standard deviation, which measures the amount of variation in the distribution. Larger values create more
     *   variable distributions.
     *
     * @param min
     *   Optional minimum value bound (inclusive)
     *
     * @param max
     *   Optional maximum value bound (inclusive)
     *
     * @param shuffled
     *   Whether to apply shuffling to the distribution. Shuffling preserves the frequency distribution but randomizes
     *   the values, creating a more realistic distribution for scenarios where frequency shouldn't be directly tied to
     *   value.
     */
    final case class MeanStdDevSettings[Value: Sampled](
        mean: Value,
        stdDev: Value,
        min: Option[Value] = None,
        max: Option[Value] = None,
        shuffled: Option[Boolean] = None)
        extends LogNormalSettings[Value]

    /**
     * Log-normal distribution parameters defined by median (50th percentile) and 95th percentile values.
     *
     * Note: useful for modeling by defining the "normal" case (median) and "almost maximum" (p95) scenarios.
     *
     * @param median
     *   The median value (50th percentile) - half of all generated values will be below this
     *
     * @param p95
     *   The 95th percentile value - 95% of all generated values will be below this
     *
     * @param min
     *   Optional minimum value bound (inclusive). If not specified, defaults to 0 for discrete samplers.
     *
     * @param max
     *   Optional maximum value bound (inclusive). If not specified, allows for occasional very large values.
     *
     * @param shuffled
     *   Whether to apply shuffling to the distribution. Shuffling preserves the frequency distribution but randomizes
     *   the values, creating a more realistic distribution for scenarios where frequency shouldn't be directly tied to
     *   value.
     */
    final case class MedianP95Settings[Value: Sampled](
        median: Value,
        p95: Value,
        min: Option[Value] = None,
        max: Option[Value] = None,
        shuffled: Option[Boolean] = None)
        extends LogNormalSettings[Value]
  }

  /**
   * Pareto distribution settings.
   *
   * Useful for modelling heavy-tailed samples following power-law behavior ("80/20 rule"), where a small percentage of
   * values are disproportionately larger. When applied to discrete samplers, generated values will be rounded to the
   * nearest integer.
   */
  sealed trait ParetoSettings[Value] extends SamplerSettings[Value] {

    /** Optional maximum value bound (inclusive) */
    def max: Option[Value]
  }

  object ParetoSettings {

    /**
     * Pareto distribution parameters using scale and shape.
     *
     * @param scale
     *   The scale parameter (xm), minimum possible value.
     *
     * @param shape
     *   The shape parameter (α), controlling the tail heaviness (Pareto index).
     *   - shape ≈ 1.1-1.5: Extremely heavy tail (many extreme outliers)
     *   - shape ≈ 1.5-2.0: Heavy tail (classic 80/20 rule)
     *   - shape > 2.0: Moderate tail with fewer extreme outliers
     *   - Must be greater than 1 for the distribution to have a finite mean
     *
     * @param max
     *   Optional maximum value bound (inclusive). For discrete samplers, setting a reasonable maximum is particularly
     *   important for Pareto distributions, as they can generate extreme outliers.
     *
     * @param shuffled
     *   Whether to apply shuffling to the distribution. Shuffling preserves the frequency distribution but randomizes
     *   the values, creating a more realistic distribution for scenarios where frequency shouldn't be directly tied to
     *   value.
     */
    final case class ScaleShapeSettings[Value: Sampled](
        scale: Value,
        shape: Double,
        max: Option[Value] = None,
        shuffled: Option[Boolean] = None)
        extends ParetoSettings[Value]

    /**
     * Pareto distribution parameters specified by minimum value and 95th percentile.
     *
     * Provides a more intuitive way to configure a Pareto distribution.
     *
     * @param min
     *   The minimum possible value.
     *
     * @param p95
     *   The 95th percentile value (95% of values will be below this)
     *
     * @param max
     *   Optional maximum value bound (inclusive). If not specified, the distribution can generate values much larger
     *   than p95 (the remaining 5% of the distribution). For discrete samplers, setting this prevents occasional
     *   extreme values.
     *
     * @param shuffled
     *   Whether to apply shuffling to the distribution. Shuffling preserves the frequency distribution but randomizes
     *   the values, creating a more realistic distribution for scenarios where frequency shouldn't be directly tied to
     *   value.
     */
    final case class MinP95Settings[Value: Sampled](
        min: Value,
        p95: Value,
        max: Option[Value] = None,
        shuffled: Option[Boolean] = None)
        extends ParetoSettings[Value]
  }

  /**
   * Categorical distribution settings for sampling from a discrete set of values with specified probabilities.
   *
   * @param categories
   *   A sequence of category settings, each with a value and an associated weight.
   */
  final case class CategoricalSettings[Value: Sampled](categories: Seq[CategorySettings[Value]])
      extends SamplerSettings[Value] {
    override def shuffled: Option[Boolean] = None
  }

  /**
   * Defines a single category with its value and relative weight for use in categorical sampling.
   *
   * @param value
   *   The value associated with this category
   * @param weight
   *   The relative probability weight for this category. Weights do not need to sum to 1.0.
   */
  final case class CategorySettings[Value: Sampled](value: Value, weight: Double)

  /**
   * Composite sampler settings that combine multiple samplers with different weights.
   *
   * @param samplers
   *   A sequence of weighted sampler settings, each with its own distribution and relative probability weight.
   */
  final case class CompositeSettings[Value: Sampled](samplers: Seq[WeightedSamplerSettings[Value]])
      extends SamplerSettings[Value] {
    override def shuffled: Option[Boolean] = None
  }

  /**
   * Defines a sampler with its relative weight for use in composite sampling.
   *
   * @param sampler
   *   The sampler settings to use
   * @param weight
   *   The relative weight determining how often this sampler is chosen from the composite. Higher weights increase the
   *   likelihood of this sampler being used. Weights do not need to sum to 1.0.
   */
  final case class WeightedSamplerSettings[Value: Sampled](sampler: SamplerSettings[Value], weight: Double)
}

/**
 * Represents the shape of a distribution used for sampling from a provided min-max range.
 */
sealed trait DistributionShape {

  /**
   * Whether to apply shuffling to the distribution output. Shuffling is most meaningful for distributions where values
   * have different probabilities. Shuffling is only supported for discrete distributions.
   */
  def shuffled: Option[Boolean]
}

object DistributionShape {

  /**
   * Uniform distribution where all values within the specified range have equal probability.
   */
  case object Uniform extends DistributionShape {
    override def shuffled: Option[Boolean] = None
  }

  /**
   * Zipf distribution for modeling power-law relationships.
   *
   * @param exponent
   *   Controls the "steepness" of the distribution. Higher values create more skewed distributions where the
   *   highest-ranked items occur much more frequently than others. Typical values range from 1.0 to 3.0, with 1.0 being
   *   classic Zipf's law.
   *
   * @param shuffled
   *   Whether to apply shuffling to the distribution. Shuffling preserves the frequency distribution but randomizes the
   *   values, creating a more realistic distribution for scenarios where frequency shouldn't be directly tied to rank
   *   (i.e., the smallest values shouldn't necessarily be the most common). Default is false.
   */
  final case class Zipf(exponent: Double, shuffled: Option[Boolean] = None) extends DistributionShape

  /**
   * Exponential distribution where the probability of an event decreases exponentially with time.
   *
   * @param shuffled
   *   Whether to apply shuffling to the distribution. Shuffling preserves the frequency distribution but randomizes the
   *   values, creating a more realistic distribution for scenarios where frequency shouldn't be directly tied to value.
   */
  final case class Exponential(shuffled: Option[Boolean] = None) extends DistributionShape

  /**
   * Weibull distribution, versatile for modeling values with different variability patterns.
   *
   * @param shape
   *   The shape parameter (k) that determines the distribution's behavior:
   *   - shape < 1: decreasing rate (many smaller values, with long tails)
   *   - shape = 1: constant rate (equivalent to exponential distribution)
   *   - shape > 1: increasing rate (samples cluster around the characteristic value)
   *   - shape ≈ 3.5: approximates normal distribution (symmetric around the mean)
   *
   * @param shuffled
   *   Whether to apply shuffling to the distribution. Shuffling preserves the frequency distribution but randomizes the
   *   values, creating a more realistic distribution for scenarios where frequency shouldn't be directly tied to value.
   */
  final case class Weibull(shape: Double, shuffled: Option[Boolean] = None) extends DistributionShape

  /**
   * Gamma distribution, good for modeling the sum of exponential random variables.
   *
   * @param shape
   *   The shape parameter (k) that controls the basic form of the distribution.
   *
   * @param shuffled
   *   Whether to apply shuffling to the distribution. Shuffling preserves the frequency distribution but randomizes the
   *   values, creating a more realistic distribution for scenarios where frequency shouldn't be directly tied to value.
   */
  final case class Gamma(shape: Double, shuffled: Option[Boolean] = None) extends DistributionShape

  /**
   * Log-normal distribution, useful for modeling values with positive skew.
   *
   * @param shape
   *   The shape parameter (σ), which is the standard deviation in logarithmic space. Higher values create more
   *   dispersed distributions with heavier tails.
   *   - shape ≈ 0.1: very tight distribution close to the median
   *   - shape ≈ 0.5: moderate variation around the median
   *   - shape ≈ 1.0: substantial variation with many extreme values
   *
   * @param shuffled
   *   Whether to apply shuffling to the distribution. Shuffling preserves the frequency distribution but randomizes the
   *   values, creating a more realistic distribution for scenarios where frequency shouldn't be directly tied to value.
   */
  final case class LogNormal(shape: Double, shuffled: Option[Boolean] = None) extends DistributionShape

  /**
   * Pareto distribution, useful for modeling heavy-tailed samples following power-law behavior ("80/20 rule").
   *
   * @param shape
   *   The shape parameter (α), controlling the tail heaviness (Pareto index):
   *   - shape ≈ 1.1-1.5: Extremely heavy tail (many extreme outliers)
   *   - shape ≈ 1.5-2.0: Heavy tail (classic 80/20 rule)
   *   - shape > 2.0: Moderate tail with fewer extreme outliers
   *   - Must be greater than 1 for the distribution to have a finite mean.
   *
   * @param shuffled
   *   Whether to apply shuffling to the distribution. Shuffling preserves the frequency distribution but randomizes the
   *   values, creating a more realistic distribution for scenarios where frequency shouldn't be directly tied to value.
   */
  final case class Pareto(shape: Double, shuffled: Option[Boolean] = None) extends DistributionShape
}
