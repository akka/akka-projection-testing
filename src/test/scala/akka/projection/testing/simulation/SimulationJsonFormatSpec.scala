/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.simulation

import scala.concurrent.duration._

import akka.projection.testing.simulation.DistributionShape.LogNormal
import akka.projection.testing.simulation.DistributionShape.Pareto
import akka.projection.testing.simulation.DistributionShape.Weibull
import akka.projection.testing.simulation.DistributionShape.Zipf
import akka.projection.testing.simulation.SamplerSettings.CategoricalSettings
import akka.projection.testing.simulation.SamplerSettings.CategorySettings
import akka.projection.testing.simulation.SamplerSettings.CompositeSettings
import akka.projection.testing.simulation.SamplerSettings.ConstantSettings
import akka.projection.testing.simulation.SamplerSettings.ExponentialSettings
import akka.projection.testing.simulation.SamplerSettings.GammaSettings
import akka.projection.testing.simulation.SamplerSettings.IncrementalSettings
import akka.projection.testing.simulation.SamplerSettings.LogNormalSettings
import akka.projection.testing.simulation.SamplerSettings.ParetoSettings
import akka.projection.testing.simulation.SamplerSettings.UniformSettings
import akka.projection.testing.simulation.SamplerSettings.WeibullSettings
import akka.projection.testing.simulation.SamplerSettings.WeightedSamplerSettings
import akka.projection.testing.simulation.SamplerSettings.ZipfSettings
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

class SimulationJsonFormatSpec extends AnyWordSpec with Matchers {

  import SimulationJsonFormat._

  "SimulationJsonFormat" should {

    "parse basic SimulationSettings" in {
      val json = """
        {
          "name": "Basic Test",
          "description": "A simple simulation",
          "stages": [
            {
              "name": "Stage 1",
              "duration": "10s",
              "generators": [
                {
                  "entityId": {
                    "entity": { "distribution": "uniform", "min": 1, "max": 100 },
                    "slices": "0-9"
                  },
                  "activity": {
                    "frequency": "10/s",
                    "duration": "100ms",
                    "event": {
                      "frequency": "5/s",
                      "dataSize": "1kB"
                    }
                  }
                }
              ]
            }
          ],
          "engine": {
            "tick": "100ms",
            "parallelism": 8,
            "ackPersists": true,
            "validationTimeout": "30s"
          }
        }
      """.parseJson

      val settings = json.convertTo[SimulationSettings]

      settings.name shouldBe Some("Basic Test")
      settings.description shouldBe Some("A simple simulation")
      settings.stages.size shouldBe 1
      settings.engine shouldBe Some(EngineSettings(Some(100.millis), Some(8), Some(true), Some(30.seconds)))

      val stage = settings.stages.head
      stage.name shouldBe Some("Stage 1")
      stage.duration shouldBe 10.seconds
      stage.delay shouldBe None
      stage.generators.size shouldBe 1

      val generator = stage.generators.head
      generator.entityId shouldBe EntityIdSettings(
        UniformSettings(1, 100),
        Some(Range.Inclusive(Slice(0), Slice(9))),
        sliceDistribution = None)

      val activity = generator.activity
      activity.frequency shouldBe PointProcessSettings.PoissonSettings(
        RateFunctionSettings.ConstantSettings(RatePerUnit(10, SECONDS)))
      activity.perSlice.ranges.size shouldBe 1
      val (sliceRange, perSliceSettings) = activity.perSlice.ranges.head
      sliceRange shouldBe Range.Default

      perSliceSettings.perEntity.ranges.size shouldBe 1
      val (entityRange, perEntitySettings) = perSliceSettings.perEntity.ranges.head
      entityRange shouldBe Range.Default

      perEntitySettings.duration shouldBe ConstantSettings(100.millis)
      perEntitySettings.event shouldBe EventSettings(
        PointProcessSettings.PoissonSettings(RateFunctionSettings.ConstantSettings(RatePerUnit(5, SECONDS))),
        ConstantSettings(DataSize(1, DataUnit.Kilobytes)))

      generator.random shouldBe None
    }

    "parse nested ActivitySettings" in {
      val json = """
        {
          "entityId": {
            "entity": {
              "count": 100
            },
            "slices": "0-127"
          },
          "activity": {
            "frequency": "1/s",
            "perSlice": {
              "0-9": {
                 "perEntity": {
                   "1-10": {
                     "duration": "1s",
                     "event": { "frequency": "10/s", "dataSize": "100B" }
                   },
                   "11-20": {
                     "duration": "2s",
                     "event": { "frequency": "5/s", "dataSize": "200B" }
                   },
                   "*": {
                     "duration": "500ms",
                     "event": { "frequency": "2/s", "dataSize": "50B" }
                   }
                 }
              },
              "*": {
                 "duration": "100ms",
                 "event": { "frequency": "1/s", "dataSize": "10B" }
              }
            }
          }
        }
       """.parseJson

      val settings = json.convertTo[GeneratorSettings]

      settings.activity.perSlice.ranges should have size 2
      val slices_0_9 = settings.activity.perSlice.get(Slice(5)).get
      val slicesDefault = settings.activity.perSlice.get(Slice(100)).get

      slices_0_9.perEntity.ranges should have size 3
      val slices_0_9_entities_1_10 = slices_0_9.perEntity.get(5).get
      val slices_0_9_entities_11_20 = slices_0_9.perEntity.get(15).get
      val slices_0_9_entitiesDefault = slices_0_9.perEntity.get(25).get

      slices_0_9_entities_1_10 shouldBe ActivitySettings.PerEntitySettings(
        ConstantSettings(1.second),
        EventSettings(
          PointProcessSettings.PoissonSettings(RateFunctionSettings.ConstantSettings(RatePerUnit(10, SECONDS))),
          ConstantSettings(DataSize(100))))

      slices_0_9_entities_11_20 shouldBe ActivitySettings.PerEntitySettings(
        ConstantSettings(2.seconds),
        EventSettings(
          PointProcessSettings.PoissonSettings(RateFunctionSettings.ConstantSettings(RatePerUnit(5, SECONDS))),
          ConstantSettings(DataSize(200))))

      slices_0_9_entitiesDefault shouldBe ActivitySettings.PerEntitySettings(
        ConstantSettings(500.millis),
        EventSettings(
          PointProcessSettings.PoissonSettings(RateFunctionSettings.ConstantSettings(RatePerUnit(2, SECONDS))),
          ConstantSettings(DataSize(50))))

      slicesDefault.perEntity.ranges should have size 1
      val slicesDefaultEntitiesDefault = slicesDefault.perEntity.get(0).get
      `slicesDefaultEntitiesDefault` shouldBe ActivitySettings.PerEntitySettings(
        ConstantSettings(100.millis),
        EventSettings(
          PointProcessSettings.PoissonSettings(RateFunctionSettings.ConstantSettings(RatePerUnit(1, SECONDS))),
          ConstantSettings(DataSize(10))))
    }

    "parse RateFunction shorthand (constant)" in {
      val json = """ "50 / min" """.parseJson
      val rate = json.convertTo[RateFunctionSettings]
      rate shouldBe RateFunctionSettings.ConstantSettings(RatePerUnit(50, MINUTES))
    }

    "parse RateFunction object (linear)" in {
      val json = """
        {
          "function": "linear",
          "initial": "10/s",
          "target": "100/s"
        }
      """.parseJson
      val rate = json.convertTo[RateFunctionSettings]
      rate shouldBe RateFunctionSettings.LinearSettings(RatePerUnit(10, SECONDS), RatePerUnit(100, SECONDS))
    }

    "parse RateFunction object (sinusoidal)" in {
      val json = """
        {
          "function": "sinusoidal",
          "base": "50/s",
          "amplitude": "20/s",
          "period": "60s",
          "shift": "10s"
        }
      """.parseJson
      val rate = json.convertTo[RateFunctionSettings]
      rate shouldBe RateFunctionSettings.SinusoidalSettings(
        RatePerUnit(50, SECONDS),
        RatePerUnit(20, SECONDS),
        60.seconds,
        Some(10.seconds))
    }

    "parse PointProcess shorthand (constant poisson)" in {
      val json = """ "100 / s" """.parseJson
      val points = json.convertTo[PointProcessSettings]
      points shouldBe PointProcessSettings.PoissonSettings(
        RateFunctionSettings.ConstantSettings(RatePerUnit(100, SECONDS)))
    }

    "parse PointProcess object (poisson with linear rate)" in {
      val json = """
        {
          "process": "poisson",
          "rate": {
            "function": "linear",
            "initial": "1/s",
            "target": "10/s"
          }
        }
      """.parseJson
      val points = json.convertTo[PointProcessSettings]
      points shouldBe PointProcessSettings.PoissonSettings(
        RateFunctionSettings.LinearSettings(RatePerUnit(1, SECONDS), RatePerUnit(10, SECONDS)))
    }

    "parse PointProcess object (implicit poisson with rate function)" in {
      val json = """
        {
          "function": "linear",
          "initial": "1/s",
          "target": "10/s"
        }
      """.parseJson
      val points = json.convertTo[PointProcessSettings]
      points shouldBe PointProcessSettings.PoissonSettings(
        RateFunctionSettings.LinearSettings(RatePerUnit(1, SECONDS), RatePerUnit(10, SECONDS)))
    }

    "parse Sampler shorthand (constant)" in {
      val jsonInt = """ 123 """.parseJson
      val samplerInt = jsonInt.convertTo[SamplerSettings[Int]]
      samplerInt shouldBe ConstantSettings(123)

      val jsonDuration = """ "5s" """.parseJson
      val samplerDuration = jsonDuration.convertTo[SamplerSettings[FiniteDuration]]
      samplerDuration shouldBe ConstantSettings(5.seconds)

      val jsonDataSize = """ "2MB" """.parseJson
      val samplerDataSize = jsonDataSize.convertTo[SamplerSettings[DataSize]]
      samplerDataSize shouldBe ConstantSettings(DataSize(2, DataUnit.Megabytes))
    }

    "parse Sampler shorthand (count for uniform)" in {
      val json = """ { "count": 1000 } """.parseJson
      val sampler = json.convertTo[SamplerSettings[Int]]
      sampler shouldBe UniformSettings(1, 1000)

      val jsonStr = """ { "count": "10k" } """.parseJson
      val samplerStr = jsonStr.convertTo[SamplerSettings[Int]]
      samplerStr shouldBe UniformSettings(1, 10000)

      a[DeserializationException] should be thrownBy {
        """ { "count": 100 } """.parseJson.convertTo[SamplerSettings[FiniteDuration]]
      }
    }

    "parse Sampler object (uniform)" in {
      val json = """
        {
          "distribution": "uniform",
          "min": 10,
          "max": 20
        }
      """.parseJson
      val sampler = json.convertTo[SamplerSettings[Int]]
      sampler shouldBe UniformSettings(10, 20)
    }

    "parse Sampler object (zipf)" in {
      val json = """
        {
          "distribution": "zipf",
          "min": 1,
          "max": 1000,
          "exponent": 1.2,
          "shuffled": true
        }
      """.parseJson
      val sampler = json.convertTo[SamplerSettings[Int]]
      sampler shouldBe ZipfSettings(1, 1000, 1.2, shuffled = Some(true))
    }

    "parse Sampler object (exponential)" in {
      val json = """
        {
          "distribution": "exponential",
          "mean": "10ms",
          "min": "1ms",
          "max": "1s"
        }
      """.parseJson
      val sampler = json.convertTo[SamplerSettings[FiniteDuration]]
      sampler shouldBe ExponentialSettings(10.millis, Some(1.millis), Some(1.second), shuffled = None)
    }

    "parse Sampler object (weibull)" in {
      val json = """
        {
          "distribution": "weibull",
          "shape": 1.5,
          "scale": "500ms",
          "shuffled": true
        }
      """.parseJson
      val sampler = json.convertTo[SamplerSettings[FiniteDuration]]
      sampler shouldBe WeibullSettings(1.5, 500.millis, None, None, shuffled = Some(true))
    }

    "parse Sampler object (gamma)" in {
      val json = """
        {
          "distribution": "gamma",
          "shape": 2.0,
          "scale": "1kB"
        }
      """.parseJson
      val sampler = json.convertTo[SamplerSettings[DataSize]]
      sampler shouldBe GammaSettings(2.0, DataSize(1, DataUnit.Kilobytes), None, None, shuffled = None)
    }

    "parse Sampler object (log-normal mu/sigma)" in {
      val json = """
        {
          "distribution": "log-normal",
          "mu": 1.0,
          "sigma": 0.5,
          "min": "10ms",
          "max": "2s"
        }
      """.parseJson
      val sampler = json.convertTo[SamplerSettings[FiniteDuration]]
      sampler shouldBe LogNormalSettings.MuSigmaSettings(1.0, 0.5, Some(10.millis), Some(2.seconds), shuffled = None)
    }

    "parse Sampler object (log-normal scale/shape)" in {
      val json = """
        {
          "distribution": "log-normal",
          "scale": "100ms",
          "shape": 0.8,
          "shuffled": true
        }
      """.parseJson
      val sampler = json.convertTo[SamplerSettings[FiniteDuration]]
      sampler shouldBe LogNormalSettings.ScaleShapeSettings(100.millis, 0.8, None, None, shuffled = Some(true))
    }

    "parse Sampler object (log-normal mean/stdDev)" in {
      val json = """
        {
          "distribution": "log-normal",
          "mean": "500B",
          "stdDev": "100B"
        }
      """.parseJson
      val sampler = json.convertTo[SamplerSettings[DataSize]]
      sampler shouldBe LogNormalSettings.MeanStdDevSettings(DataSize(500), DataSize(100), None, None, shuffled = None)
    }

    "parse Sampler object (log-normal median/p95)" in {
      val json = """
        {
          "distribution": "log-normal",
          "median": "1s",
          "p95": "10s"
        }
      """.parseJson
      val sampler = json.convertTo[SamplerSettings[FiniteDuration]]
      sampler shouldBe LogNormalSettings.MedianP95Settings(1.second, 10.seconds, None, None, shuffled = None)
    }

    "parse Sampler object (pareto scale/shape)" in {
      val json = """
        {
          "distribution": "pareto",
          "scale": "1kB",
          "shape": 1.5,
          "max": "1MB"
        }
      """.parseJson
      val sampler = json.convertTo[SamplerSettings[DataSize]]
      sampler shouldBe ParetoSettings.ScaleShapeSettings(
        DataSize(1, DataUnit.Kilobytes),
        1.5,
        Some(DataSize(1, DataUnit.Megabytes)),
        shuffled = None)
    }

    "parse Sampler object (pareto min/p95)" in {
      val json = """
        {
          "distribution": "pareto",
          "min": "10ms",
          "p95": "1s",
          "shuffled": true
        }
      """.parseJson
      val sampler = json.convertTo[SamplerSettings[FiniteDuration]]
      sampler shouldBe ParetoSettings.MinP95Settings(10.millis, 1.second, None, shuffled = Some(true))
    }

    "parse Sampler object (categorical)" in {
      val json = """
        {
          "distribution": "categorical",
          "categories": [
            { "value": 10, "weight": 0.5 },
            { "value": 20, "weight": 0.3 },
            { "value": 30, "weight": 0.2 }
          ]
        }
      """.parseJson
      val sampler = json.convertTo[SamplerSettings[Int]]
      sampler shouldBe CategoricalSettings(
        Seq(CategorySettings(10, 0.5), CategorySettings(20, 0.3), CategorySettings(30, 0.2)))
    }

    "parse Sampler object (composite)" in {
      val json = """
        {
          "distribution": "composite",
          "samplers": [
            {
              "sampler": { "distribution": "uniform", "min": 1, "max": 10 },
              "weight": 1.0
            },
            {
              "sampler": { "distribution": "exponential", "mean": 50 },
              "weight": 2.0
            }
          ]
        }
      """.parseJson
      val sampler = json.convertTo[SamplerSettings[Int]]
      sampler shouldBe CompositeSettings(
        Seq(
          WeightedSamplerSettings(UniformSettings(1, 10), 1.0),
          WeightedSamplerSettings(ExponentialSettings(50, None, None, shuffled = None), 2.0)))
    }

    "parse Sampler object (incremental)" in {
      val json = """
        {
          "distribution": "incremental",
          "start": 100,
          "step": 5,
          "limit": 200
        }
      """.parseJson
      val sampler = json.convertTo[SamplerSettings[Int]]
      sampler shouldBe IncrementalSettings(100, 5, Some(200))
    }

    "parse DistributionShape" in {
      """{ "type": "uniform" }""".parseJson.convertTo[DistributionShape] shouldBe DistributionShape.Uniform
      """{ "type": "zipf", "exponent": 1.1, "shuffled": true }""".parseJson
        .convertTo[DistributionShape] shouldBe Zipf(1.1, shuffled = Some(true))
      """{ "type": "exponential", "shuffled": false }""".parseJson
        .convertTo[DistributionShape] shouldBe DistributionShape.Exponential(shuffled = Some(false))
      """{ "type": "weibull", "shape": 2.5 }""".parseJson
        .convertTo[DistributionShape] shouldBe Weibull(2.5, shuffled = None)
      """{ "type": "gamma", "shape": 3.0, "shuffled": true }""".parseJson
        .convertTo[DistributionShape] shouldBe DistributionShape.Gamma(3.0, shuffled = Some(true))
      """{ "type": "log-normal", "shape": 0.7 }""".parseJson
        .convertTo[DistributionShape] shouldBe LogNormal(0.7, shuffled = None)
      """{ "type": "pareto", "shape": 1.8 }""".parseJson
        .convertTo[DistributionShape] shouldBe Pareto(1.8, shuffled = None)
    }

    "parse Slice Range" in {
      Range.parse[Slice]("*") shouldBe Range.Default
      Range.parse[Slice]("10") shouldBe Range.Single(Slice(10))
      Range.parse[Slice]("100-200") shouldBe Range.Inclusive(Slice(100), Slice(200))
      Range.parse[Slice]("1,5,10-12") shouldBe Range.Multi(
        Seq(Range.Single(Slice(1)), Range.Single(Slice(5)), Range.Inclusive(Slice(10), Slice(12))))
    }

    "parse Int Range" in {
      Range.parse[Int]("*") shouldBe Range.Default
      Range.parse[Int]("42") shouldBe Range.Single(42)
      Range.parse[Int]("1000-2000") shouldBe Range.Inclusive(1000, 2000)
      Range.parse[Int]("1,5,10-12") shouldBe Range.Multi(Seq(Range.Single(1), Range.Single(5), Range.Inclusive(10, 12)))
    }

    "parse RangeMap" in {
      val json = """
        {
          "0-9": "low",
          "10-99": "medium",
          "*": "high"
        }
      """.parseJson
      val rangeMap = json.convertTo[RangeMap[Slice, String]]
      rangeMap.ranges should contain theSameElementsAs Seq(
        Range.Inclusive(Slice(0), Slice(9)) -> "low",
        Range.Inclusive(Slice(10), Slice(99)) -> "medium",
        Range.Default -> "high")
      rangeMap(Slice(5)) shouldBe "low"
      rangeMap(Slice(50)) shouldBe "medium"
      rangeMap(Slice(500)) shouldBe "high"
    }

    "parse RandomSettings" in {
      val json = """
        {
          "algorithm": "XO_RO_SHI_RO_128_PP",
          "seed": 1234567890
        }
      """.parseJson
      val random = json.convertTo[RandomSettings]
      random shouldBe RandomSettings(Some("XO_RO_SHI_RO_128_PP"), Some(1234567890L))
    }

    "write and read back complex SimulationSettings" in {
      val originalSettings = SimulationSettings(
        name = Some("Complex Test"),
        description = Some("Test complex features"),
        stages = Seq(
          StageSettings(
            name = Some("Warmup"),
            duration = 1.minute,
            delay = Some(5.seconds),
            generators = Seq(GeneratorSettings(
              entityId = EntityIdSettings(
                entity = ZipfSettings(1, 10000, 1.2, shuffled = Some(true)),
                slices = Some(Range.Multi(Seq(Range.Inclusive(Slice(0), Slice(99)), Range.Single(Slice(500))))),
                sliceDistribution = Some(DistributionShape.Exponential(shuffled = Some(true)))),
              activity = ActivitySettings(
                frequency = PointProcessSettings.PoissonSettings(RateFunctionSettings
                  .SinusoidalSettings(RatePerUnit(100, SECONDS), RatePerUnit(50, SECONDS), 30.seconds, None)),
                perSlice = RangeMap(
                  Range.Inclusive(Slice(0), Slice(99)) -> ActivitySettings.PerSliceSettings(perEntity = RangeMap(
                    Range.Inclusive(1, 1000) -> ActivitySettings.PerEntitySettings(
                      duration = LogNormalSettings.MedianP95Settings(500.millis, 2.seconds),
                      event = EventSettings(
                        frequency = PointProcessSettings.PoissonSettings(
                          RateFunctionSettings.ConstantSettings(RatePerUnit(20, SECONDS))),
                        dataSize = ParetoSettings.MinP95Settings(
                          DataSize(100),
                          DataSize(5, DataUnit.Kilobytes),
                          max = Some(DataSize(1, DataUnit.Megabytes))))),
                    Range.Default -> ActivitySettings.PerEntitySettings(
                      duration = ConstantSettings(100.millis),
                      event = EventSettings(
                        frequency = PointProcessSettings.PoissonSettings(
                          RateFunctionSettings.ConstantSettings(RatePerUnit(5, SECONDS))),
                        dataSize = ConstantSettings(DataSize(50)))))),
                  Range.Default -> ActivitySettings.PerSliceSettings(perEntity =
                    RangeMap(Range.Default -> ActivitySettings.PerEntitySettings(
                      duration = ConstantSettings(10.millis),
                      event = EventSettings(
                        frequency = PointProcessSettings.PoissonSettings(
                          RateFunctionSettings.ConstantSettings(RatePerUnit(1, SECONDS))),
                        dataSize = ConstantSettings(DataSize(10)))))))),
              random = Some(RandomSettings(Some("WELL_44497_B"), Some(98765L))))))),
        engine = Some(EngineSettings(Some(50.millis), Some(16), Some(false), Some(1.minute))))

      val json = originalSettings.toJson
      // println(json.prettyPrint)
      val parsedSettings = json.convertTo[SimulationSettings]

      parsedSettings shouldBe originalSettings
    }

  }
}
