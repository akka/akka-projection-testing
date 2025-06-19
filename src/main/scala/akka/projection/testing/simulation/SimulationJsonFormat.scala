/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.simulation

import scala.concurrent.duration._

import spray.json._

object SimulationJsonFormat extends DefaultJsonProtocol {

  implicit val sliceFormat: JsonFormat[Slice] = new JsonFormat[Slice] {
    override def write(slice: Slice): JsValue = JsNumber(slice.value)

    override def read(json: JsValue): Slice = json match {
      case JsNumber(value) => Slice(value.intValue)
      case JsString(value) => Slice.parse(value)
      case _               => deserializationError("Expected number or string for slice")
    }
  }

  implicit val durationFormat: JsonFormat[FiniteDuration] = new JsonFormat[FiniteDuration] {
    override def write(duration: FiniteDuration): JsValue = JsString(duration.toCoarsest.toString)

    override def read(json: JsValue): FiniteDuration = json match {
      case JsString(duration) =>
        Duration(duration) match {
          case finite: FiniteDuration => finite
          case _                      => deserializationError("Expected finite duration")
        }
      case _ => deserializationError("Expected string for duration")
    }
  }
  implicit val countFormat: JsonFormat[Count] = new JsonFormat[Count] {
    override def write(count: Count): JsValue = JsString(count.toString)
    override def read(json: JsValue): Count = json match {
      case JsString(value) => Count.parse(value)
      case JsNumber(value) => Count(value.toInt)
      case _               => deserializationError("Expected string or number for count")
    }
  }

  implicit val dataSizeFormat: JsonFormat[DataSize] = new JsonFormat[DataSize] {
    override def write(size: DataSize): JsValue = JsString(size.toString)

    override def read(json: JsValue): DataSize = json match {
      case JsNumber(bytes) => DataSize(bytes.intValue.toDouble, DataUnit.Bytes)
      case JsString(size)  => DataSize.parse(size)
      case _               => deserializationError("Expected number or string for data size")
    }
  }

  implicit def rangeFormat[A: Rangeable: Ordering]: JsonFormat[Range[A]] = new JsonFormat[Range[A]] {
    override def write(range: Range[A]): JsValue = JsString(range.toString)

    override def read(json: JsValue): Range[A] = json match {
      case JsString(range) => Range.parse[A](range)
      case _               => deserializationError("Expected string for range")
    }
  }

  implicit def rangeMapFormat[A: Rangeable: Ordering, B: JsonFormat]: RootJsonFormat[RangeMap[A, B]] =
    new RootJsonFormat[RangeMap[A, B]] {
      override def write(rangeMap: RangeMap[A, B]): JsValue = {
        val fields = rangeMap.ranges.map { case (range, value) =>
          range.toString -> value.toJson
        }
        JsObject(fields.toMap)
      }

      override def read(json: JsValue): RangeMap[A, B] = {
        json match {
          case JsObject(fields) =>
            val ranges = fields.map { case (key, value) =>
              (rangeFormat.read(JsString(key)), value.convertTo[B])
            }.toSeq
            RangeMap(ranges: _*)
          case _ => deserializationError("Expected object for int range map")
        }
      }
    }

  implicit val ratePerUnitFormat: JsonFormat[RatePerUnit] = new JsonFormat[RatePerUnit] {
    override def write(rate: RatePerUnit): JsValue = JsString(rate.toString)

    override def read(json: JsValue): RatePerUnit = json match {
      case JsString(rate) => RatePerUnit.parse(rate)
      case _              => deserializationError("Expected string for rate")
    }
  }

  private object RateFunctionType {
    val Field = "function"

    val Constant = "constant"
    val Linear = "linear"
    val Sinusoidal = "sinusoidal"
    val RandomWalk = "random-walk"
    val Burst = "burst"
    val Additive = "additive"
    val Modulated = "modulated"
    val Composite = "composite"

    def apply(rate: RateFunctionSettings): String = rate match {
      case _: RateFunctionSettings.ConstantSettings   => RateFunctionType.Constant
      case _: RateFunctionSettings.LinearSettings     => RateFunctionType.Linear
      case _: RateFunctionSettings.SinusoidalSettings => RateFunctionType.Sinusoidal
      case _: RateFunctionSettings.RandomWalkSettings => RateFunctionType.RandomWalk
      case _: RateFunctionSettings.BurstSettings      => RateFunctionType.Burst
      case _: RateFunctionSettings.AdditiveSettings   => RateFunctionType.Additive
      case _: RateFunctionSettings.ModulatedSettings  => RateFunctionType.Modulated
      case _: RateFunctionSettings.CompositeSettings  => RateFunctionType.Composite
    }
  }

  implicit val constantRateFunctionFormat: RootJsonFormat[RateFunctionSettings.ConstantSettings] =
    jsonFormat1(RateFunctionSettings.ConstantSettings)

  implicit val linearRateFunctionFormat: RootJsonFormat[RateFunctionSettings.LinearSettings] =
    jsonFormat2(RateFunctionSettings.LinearSettings)

  implicit val sinusoidalRateFunctionFormat: RootJsonFormat[RateFunctionSettings.SinusoidalSettings] =
    jsonFormat4(RateFunctionSettings.SinusoidalSettings)

  implicit val randomWalkRateFunctionFormat: RootJsonFormat[RateFunctionSettings.RandomWalkSettings] =
    jsonFormat5(RateFunctionSettings.RandomWalkSettings)

  implicit val burstRateFunctionFormat: RootJsonFormat[RateFunctionSettings.BurstSettings] =
    jsonFormat6(RateFunctionSettings.BurstSettings)

  implicit val additiveRateFunctionFormat: RootJsonFormat[RateFunctionSettings.AdditiveSettings] =
    jsonFormat1(RateFunctionSettings.AdditiveSettings)

  implicit val modulatedRateFunctionFormat: RootJsonFormat[RateFunctionSettings.ModulatedSettings] =
    jsonFormat3(RateFunctionSettings.ModulatedSettings)

  implicit val weightedRateFunctionFormat: RootJsonFormat[RateFunctionSettings.WeightedSettings] =
    jsonFormat2(RateFunctionSettings.WeightedSettings)

  implicit val compositeRateFunctionFormat: RootJsonFormat[RateFunctionSettings.CompositeSettings] =
    jsonFormat1(RateFunctionSettings.CompositeSettings)

  implicit def rateFunctionFormat: RootJsonFormat[RateFunctionSettings] = new RootJsonFormat[RateFunctionSettings] {
    override def write(rateFunction: RateFunctionSettings): JsValue = rateFunction match {
      // simplified format: constant rate can just be a string
      case RateFunctionSettings.ConstantSettings(value) => value.toJson
      case _ =>
        JsObject((rateFunction match {
          case constant: RateFunctionSettings.ConstantSettings     => constant.toJson
          case linear: RateFunctionSettings.LinearSettings         => linear.toJson
          case sinusoidal: RateFunctionSettings.SinusoidalSettings => sinusoidal.toJson
          case randomWalk: RateFunctionSettings.RandomWalkSettings => randomWalk.toJson
          case burst: RateFunctionSettings.BurstSettings           => burst.toJson
          case additive: RateFunctionSettings.AdditiveSettings     => additive.toJson
          case modulated: RateFunctionSettings.ModulatedSettings   => modulated.toJson
          case composite: RateFunctionSettings.CompositeSettings   => composite.toJson
        }).asJsObject.fields + (RateFunctionType.Field -> JsString(RateFunctionType(rateFunction))))
    }

    override def read(json: JsValue): RateFunctionSettings = json match {
      // simplified format: just string is a constant rate
      case jsString: JsString => RateFunctionSettings.ConstantSettings(jsString.convertTo[RatePerUnit])
      case jsObject: JsObject =>
        jsObject.getFields(RateFunctionType.Field) match {
          case Seq(JsString(RateFunctionType.Constant)) => jsObject.convertTo[RateFunctionSettings.ConstantSettings]
          case Seq(JsString(RateFunctionType.Linear))   => jsObject.convertTo[RateFunctionSettings.LinearSettings]
          case Seq(JsString(RateFunctionType.Sinusoidal)) =>
            jsObject.convertTo[RateFunctionSettings.SinusoidalSettings]
          case Seq(JsString(RateFunctionType.RandomWalk)) =>
            jsObject.convertTo[RateFunctionSettings.RandomWalkSettings]
          case Seq(JsString(RateFunctionType.Burst))     => jsObject.convertTo[RateFunctionSettings.BurstSettings]
          case Seq(JsString(RateFunctionType.Additive))  => jsObject.convertTo[RateFunctionSettings.AdditiveSettings]
          case Seq(JsString(RateFunctionType.Modulated)) => jsObject.convertTo[RateFunctionSettings.ModulatedSettings]
          case Seq(JsString(RateFunctionType.Composite)) => jsObject.convertTo[RateFunctionSettings.CompositeSettings]
          case _                                         => deserializationError("Expected known rate function type")
        }

      case _ => deserializationError("Expected string or object for rate function")
    }
  }

  private object PointProcess {
    val Field = "process"

    val Poisson = "poisson"

    def apply(points: PointProcessSettings): String = points match {
      case _: PointProcessSettings.PoissonSettings => PointProcess.Poisson
    }
  }

  implicit val poissonPointsFormat: RootJsonFormat[PointProcessSettings.PoissonSettings] =
    jsonFormat1(PointProcessSettings.PoissonSettings)

  implicit val pointsFormat: RootJsonFormat[PointProcessSettings] = new RootJsonFormat[PointProcessSettings] {
    override def write(points: PointProcessSettings): JsValue = points match {
      // simplified format: poisson process with constant rate represented just as a rate string
      case PointProcessSettings.PoissonSettings(RateFunctionSettings.ConstantSettings(value)) => value.toJson
      case _ =>
        JsObject((points match {
          case poisson: PointProcessSettings.PoissonSettings => poisson.toJson
        }).asJsObject.fields + (PointProcess.Field -> JsString(PointProcess(points))))
    }

    override def read(json: JsValue): PointProcessSettings = json match {
      // simplified format: just string is treated as poisson process with constant rate
      case jsString: JsString =>
        PointProcessSettings.PoissonSettings(RateFunctionSettings.ConstantSettings(jsString.convertTo[RatePerUnit]))
      case jsObject: JsObject =>
        jsObject.getFields(PointProcess.Field) match {
          case Seq(JsString(PointProcess.Poisson)) => jsObject.convertTo[PointProcessSettings.PoissonSettings]
          case Nil => // no process field default to Poisson
            if (jsObject.fields.contains("rate")) {
              PointProcessSettings.PoissonSettings(jsObject.fields("rate").convertTo[RateFunctionSettings])
            } else if (jsObject.fields.contains(RateFunctionType.Field)) { // assume direct rate function
              PointProcessSettings.PoissonSettings(jsObject.convertTo[RateFunctionSettings])
            } else {
              deserializationError("Expected rate field or rate function fields in point process object")
            }
          case _ => deserializationError("Expected known points process")
        }
      case _ => deserializationError("Expected string or object for point process")
    }
  }

  private object SamplerDistribution {
    val Field = "distribution"

    val Constant = "constant"
    val Uniform = "uniform"
    val Incremental = "incremental"
    val Zipf = "zipf"
    val Exponential = "exponential"
    val Weibull = "weibull"
    val Gamma = "gamma"
    val LogNormal = "log-normal"
    val Pareto = "pareto"
    val Categorical = "categorical"
    val Composite = "composite"

    val values: Seq[String] =
      Seq(Constant, Uniform, Zipf, Exponential, Weibull, Gamma, LogNormal, Pareto, Categorical, Composite)

    def apply(sampler: SamplerSettings[_]): String = sampler match {
      case _: SamplerSettings.ConstantSettings[_]    => SamplerDistribution.Constant
      case _: SamplerSettings.UniformSettings[_]     => SamplerDistribution.Uniform
      case _: SamplerSettings.IncrementalSettings[_] => SamplerDistribution.Incremental
      case _: SamplerSettings.ZipfSettings[_]        => SamplerDistribution.Zipf
      case _: SamplerSettings.ExponentialSettings[_] => SamplerDistribution.Exponential
      case _: SamplerSettings.WeibullSettings[_]     => SamplerDistribution.Weibull
      case _: SamplerSettings.GammaSettings[_]       => SamplerDistribution.Gamma
      case _: SamplerSettings.LogNormalSettings[_]   => SamplerDistribution.LogNormal
      case _: SamplerSettings.ParetoSettings[_]      => SamplerDistribution.Pareto
      case _: SamplerSettings.CategoricalSettings[_] => SamplerDistribution.Categorical
      case _: SamplerSettings.CompositeSettings[_]   => SamplerDistribution.Composite
    }
  }

  implicit def constantSamplerFormat[A: Sampled: JsonFormat]: RootJsonFormat[SamplerSettings.ConstantSettings[A]] =
    jsonFormat1(SamplerSettings.ConstantSettings[A])

  implicit def uniformSamplerFormat[A: Sampled: JsonFormat]: RootJsonFormat[SamplerSettings.UniformSettings[A]] =
    jsonFormat2(SamplerSettings.UniformSettings[A])

  implicit def incrementalSamplerFormat[A: Sampled: JsonFormat]
      : RootJsonFormat[SamplerSettings.IncrementalSettings[A]] =
    jsonFormat3(SamplerSettings.IncrementalSettings[A])

  implicit def zipfSamplerFormat[A: Sampled: JsonFormat]: RootJsonFormat[SamplerSettings.ZipfSettings[A]] =
    jsonFormat4(SamplerSettings.ZipfSettings[A])

  implicit def exponentialSamplerFormat[A: Sampled: JsonFormat]
      : RootJsonFormat[SamplerSettings.ExponentialSettings[A]] =
    jsonFormat4(SamplerSettings.ExponentialSettings[A])

  implicit def weibullSamplerFormat[A: Sampled: JsonFormat]: RootJsonFormat[SamplerSettings.WeibullSettings[A]] =
    jsonFormat5(SamplerSettings.WeibullSettings[A])

  implicit def gammaSamplerFormat[A: Sampled: JsonFormat]: RootJsonFormat[SamplerSettings.GammaSettings[A]] =
    jsonFormat5(SamplerSettings.GammaSettings[A])

  implicit def muSigmaFormat[A: Sampled: JsonFormat]
      : RootJsonFormat[SamplerSettings.LogNormalSettings.MuSigmaSettings[A]] =
    jsonFormat5(SamplerSettings.LogNormalSettings.MuSigmaSettings[A])

  implicit def scaleShapeFormat[A: Sampled: JsonFormat]
      : RootJsonFormat[SamplerSettings.LogNormalSettings.ScaleShapeSettings[A]] =
    jsonFormat5(SamplerSettings.LogNormalSettings.ScaleShapeSettings[A])

  implicit def meanStdDevFormat[A: Sampled: JsonFormat]
      : RootJsonFormat[SamplerSettings.LogNormalSettings.MeanStdDevSettings[A]] =
    jsonFormat5(SamplerSettings.LogNormalSettings.MeanStdDevSettings[A])

  implicit def medianP95Format[A: Sampled: JsonFormat]
      : RootJsonFormat[SamplerSettings.LogNormalSettings.MedianP95Settings[A]] =
    jsonFormat5(SamplerSettings.LogNormalSettings.MedianP95Settings[A])

  implicit def logNormalSamplerFormat[A: Sampled: JsonFormat]: RootJsonFormat[SamplerSettings.LogNormalSettings[A]] =
    new RootJsonFormat[SamplerSettings.LogNormalSettings[A]] {
      override def write(settings: SamplerSettings.LogNormalSettings[A]): JsValue = {
        settings match {
          case s: SamplerSettings.LogNormalSettings.MuSigmaSettings[A]    => muSigmaFormat[A].write(s)
          case s: SamplerSettings.LogNormalSettings.ScaleShapeSettings[A] => scaleShapeFormat[A].write(s)
          case s: SamplerSettings.LogNormalSettings.MeanStdDevSettings[A] => meanStdDevFormat[A].write(s)
          case s: SamplerSettings.LogNormalSettings.MedianP95Settings[A]  => medianP95Format[A].write(s)
        }
      }

      override def read(json: JsValue): SamplerSettings.LogNormalSettings[A] = {
        val fields = json.asJsObject.fields

        if (fields.contains("mu") && fields.contains("sigma")) {
          muSigmaFormat[A].read(json)
        } else if (fields.contains("scale") && fields.contains("shape")) {
          scaleShapeFormat[A].read(json)
        } else if (fields.contains("mean") && fields.contains("stdDev")) {
          meanStdDevFormat[A].read(json)
        } else if (fields.contains("median") && fields.contains("p95")) {
          medianP95Format[A].read(json)
        } else {
          throw deserializationError(
            "Unknown log-normal parameters - must include pairs: mu/sigma, scale/shape, mean/stdDev, or median/p95")
        }
      }
    }

  implicit def paretoScaleShapeFormat[A: Sampled: JsonFormat]
      : RootJsonFormat[SamplerSettings.ParetoSettings.ScaleShapeSettings[A]] =
    jsonFormat4(SamplerSettings.ParetoSettings.ScaleShapeSettings[A])

  implicit def paretoMinP95Format[A: Sampled: JsonFormat]
      : RootJsonFormat[SamplerSettings.ParetoSettings.MinP95Settings[A]] =
    jsonFormat4(SamplerSettings.ParetoSettings.MinP95Settings[A])

  implicit def paretoSamplerFormat[A: Sampled: JsonFormat]: RootJsonFormat[SamplerSettings.ParetoSettings[A]] =
    new RootJsonFormat[SamplerSettings.ParetoSettings[A]] {
      override def write(settings: SamplerSettings.ParetoSettings[A]): JsValue = {
        settings match {
          case s: SamplerSettings.ParetoSettings.ScaleShapeSettings[A] => paretoScaleShapeFormat[A].write(s)
          case s: SamplerSettings.ParetoSettings.MinP95Settings[A]     => paretoMinP95Format[A].write(s)
        }
      }

      override def read(json: JsValue): SamplerSettings.ParetoSettings[A] = {
        val fields = json.asJsObject.fields

        if (fields.contains("scale") && fields.contains("shape")) {
          paretoScaleShapeFormat[A].read(json)
        } else if (fields.contains("min") && fields.contains("p95")) {
          paretoMinP95Format[A].read(json)
        } else {
          throw deserializationError("Unknown Pareto parameters - must include pairs: scale/shape or min/p95")
        }
      }
    }

  implicit def categoryFormat[A: Sampled: JsonFormat]: RootJsonFormat[SamplerSettings.CategorySettings[A]] =
    jsonFormat2(SamplerSettings.CategorySettings[A])

  implicit def categoricalSamplerFormat[A: Sampled: JsonFormat]
      : RootJsonFormat[SamplerSettings.CategoricalSettings[A]] =
    jsonFormat1(SamplerSettings.CategoricalSettings[A])

  implicit def weightedSamplerFormat[A: Sampled: JsonFormat]
      : RootJsonFormat[SamplerSettings.WeightedSamplerSettings[A]] =
    new RootJsonFormat[SamplerSettings.WeightedSamplerSettings[A]] {
      override def write(obj: SamplerSettings.WeightedSamplerSettings[A]): JsValue =
        JsObject("sampler" -> samplerFormat[A].write(obj.sampler), "weight" -> JsNumber(obj.weight))

      override def read(json: JsValue): SamplerSettings.WeightedSamplerSettings[A] = {
        val fields = json.asJsObject.fields
        SamplerSettings.WeightedSamplerSettings(
          samplerFormat[A].read(fields("sampler")),
          fields("weight").convertTo[Double])
      }
    }

  implicit def compositeSamplerFormat[A: Sampled: JsonFormat]: RootJsonFormat[SamplerSettings.CompositeSettings[A]] =
    jsonFormat1(SamplerSettings.CompositeSettings[A])

  implicit def samplerFormat[A: Sampled: JsonFormat]: RootJsonFormat[SamplerSettings[A]] =
    new RootJsonFormat[SamplerSettings[A]] {
      override def write(sampler: SamplerSettings[A]): JsValue = sampler match {
        // simplified format: constant samplers are just their value directly
        case SamplerSettings.ConstantSettings(value) => implicitly[JsonFormat[A]].write(value)
        case _ =>
          val baseJson = sampler match {
            case s: SamplerSettings.ConstantSettings[A]    => constantSamplerFormat[A].write(s)
            case s: SamplerSettings.UniformSettings[A]     => uniformSamplerFormat[A].write(s)
            case s: SamplerSettings.IncrementalSettings[A] => incrementalSamplerFormat[A].write(s)
            case s: SamplerSettings.ZipfSettings[A]        => zipfSamplerFormat[A].write(s)
            case s: SamplerSettings.ExponentialSettings[A] => exponentialSamplerFormat[A].write(s)
            case s: SamplerSettings.WeibullSettings[A]     => weibullSamplerFormat[A].write(s)
            case s: SamplerSettings.GammaSettings[A]       => gammaSamplerFormat[A].write(s)
            case s: SamplerSettings.LogNormalSettings[A]   => logNormalSamplerFormat[A].write(s)
            case s: SamplerSettings.ParetoSettings[A]      => paretoSamplerFormat[A].write(s)
            case s: SamplerSettings.CategoricalSettings[A] => categoricalSamplerFormat[A].write(s)
            case s: SamplerSettings.CompositeSettings[A]   => compositeSamplerFormat[A].write(s)
          }
          JsObject(baseJson.asJsObject.fields + (SamplerDistribution.Field -> JsString(SamplerDistribution(sampler))))
      }

      override def read(json: JsValue): SamplerSettings[A] = json match {
        // simplified format: constant values create a constant sampler directly
        case value if !value.isInstanceOf[JsObject] => SamplerSettings.ConstantSettings[A](value.convertTo[A])
        case jsObject: JsObject                     =>
          // special handling for count shorthand for uniform distribution
          jsObject.getFields("count") match {
            case Seq(countValue) if implicitly[Sampled[A]].isDiscrete =>
              // If 'count' is present and we're working with a discrete type, create a uniform distribution from 1 to count
              val count = countValue match {
                case JsString(s) => Count.parse(s).toInt
                case JsNumber(n) => n.toInt
                case _ => deserializationError(s"Expected count to be a number or count string, got: $countValue")
              }

              val discrete = implicitly[Sampled[A]] match {
                case Sampled.Discrete(value) => value
                case _ => deserializationError("Count shorthand can only be used with discrete types")
              }

              SamplerSettings.UniformSettings[A](discrete.fromInt(1), discrete.fromInt(count))
            case _ =>
              // Regular processing for non-count fields
              jsObject.getFields(SamplerDistribution.Field) match {
                case Seq(JsString(SamplerDistribution.Constant)) =>
                  constantSamplerFormat[A].read(jsObject)
                case Seq(JsString(SamplerDistribution.Uniform)) =>
                  uniformSamplerFormat[A].read(jsObject)
                case Seq(JsString(SamplerDistribution.Incremental)) =>
                  incrementalSamplerFormat[A].read(jsObject)
                case Seq(JsString(SamplerDistribution.Zipf)) =>
                  zipfSamplerFormat[A].read(jsObject)
                case Seq(JsString(SamplerDistribution.Exponential)) =>
                  exponentialSamplerFormat[A].read(jsObject)
                case Seq(JsString(SamplerDistribution.Weibull)) =>
                  weibullSamplerFormat[A].read(jsObject)
                case Seq(JsString(SamplerDistribution.Gamma)) =>
                  gammaSamplerFormat[A].read(jsObject)
                case Seq(JsString(SamplerDistribution.LogNormal)) =>
                  logNormalSamplerFormat[A].read(jsObject)
                case Seq(JsString(SamplerDistribution.Pareto)) =>
                  paretoSamplerFormat[A].read(jsObject)
                case Seq(JsString(SamplerDistribution.Categorical)) =>
                  categoricalSamplerFormat[A].read(jsObject)
                case Seq(JsString(SamplerDistribution.Composite)) =>
                  compositeSamplerFormat[A].read(jsObject)
                case _ =>
                  deserializationError(
                    s"Unknown sampler distribution type: expected one of: ${SamplerDistribution.values.mkString(", ")}")
              }
          }
        case _ => deserializationError("Expected primitive value or object for sampler")
      }
    }

  private object DistributionShapeType {
    val Field = "type"

    val Uniform = "uniform"
    val Zipf = "zipf"
    val Exponential = "exponential"
    val Weibull = "weibull"
    val Gamma = "gamma"
    val LogNormal = "log-normal"
    val Pareto = "pareto"

    val values: Seq[String] = Seq(Uniform, Zipf, Exponential, Weibull, Gamma, LogNormal, Pareto)

    def apply(shape: DistributionShape): String = shape match {
      case DistributionShape.Uniform        => Uniform
      case _: DistributionShape.Zipf        => Zipf
      case _: DistributionShape.Exponential => Exponential
      case _: DistributionShape.Weibull     => Weibull
      case _: DistributionShape.Gamma       => Gamma
      case _: DistributionShape.LogNormal   => LogNormal
      case _: DistributionShape.Pareto      => Pareto
    }
  }

  implicit val exponentialDistributionShapeFormat: RootJsonFormat[DistributionShape.Exponential] =
    jsonFormat1(DistributionShape.Exponential)

  implicit val zipfDistributionShapeFormat: RootJsonFormat[DistributionShape.Zipf] =
    jsonFormat2(DistributionShape.Zipf)

  implicit val weibullDistributionShapeFormat: RootJsonFormat[DistributionShape.Weibull] =
    jsonFormat2(DistributionShape.Weibull)

  implicit val gammaDistributionShapeFormat: RootJsonFormat[DistributionShape.Gamma] =
    jsonFormat2(DistributionShape.Gamma)

  implicit val logNormalDistributionShapeFormat: RootJsonFormat[DistributionShape.LogNormal] =
    jsonFormat2(DistributionShape.LogNormal)

  implicit val paretoDistributionShapeFormat: RootJsonFormat[DistributionShape.Pareto] =
    jsonFormat2(DistributionShape.Pareto)

  implicit val distributionShapeFormat: RootJsonFormat[DistributionShape] = new RootJsonFormat[DistributionShape] {
    override def write(shape: DistributionShape): JsValue = {
      val baseJson = shape match {
        case DistributionShape.Uniform                  => JsObject()
        case zipf: DistributionShape.Zipf               => zipf.toJson.asJsObject
        case exponential: DistributionShape.Exponential => exponential.toJson.asJsObject
        case weibull: DistributionShape.Weibull         => weibull.toJson.asJsObject
        case gamma: DistributionShape.Gamma             => gamma.toJson.asJsObject
        case logNormal: DistributionShape.LogNormal     => logNormal.toJson.asJsObject
        case pareto: DistributionShape.Pareto           => pareto.toJson.asJsObject
      }
      JsObject(baseJson.fields + (DistributionShapeType.Field -> JsString(DistributionShapeType(shape))))
    }

    override def read(json: JsValue): DistributionShape = {
      json.asJsObject.getFields(DistributionShapeType.Field) match {
        case Seq(JsString(DistributionShapeType.Uniform))     => DistributionShape.Uniform
        case Seq(JsString(DistributionShapeType.Zipf))        => zipfDistributionShapeFormat.read(json)
        case Seq(JsString(DistributionShapeType.Exponential)) => exponentialDistributionShapeFormat.read(json)
        case Seq(JsString(DistributionShapeType.Weibull))     => weibullDistributionShapeFormat.read(json)
        case Seq(JsString(DistributionShapeType.Gamma))       => gammaDistributionShapeFormat.read(json)
        case Seq(JsString(DistributionShapeType.LogNormal))   => logNormalDistributionShapeFormat.read(json)
        case Seq(JsString(DistributionShapeType.Pareto))      => paretoDistributionShapeFormat.read(json)
        case _ =>
          deserializationError(
            s"Unknown distribution shape type: expected one of: ${DistributionShapeType.values.mkString(", ")}")
      }
    }
  }

  implicit val entityIdFormat: RootJsonFormat[EntityIdSettings] = jsonFormat3(EntityIdSettings)

  implicit val eventFormat: RootJsonFormat[EventSettings] = jsonFormat2(EventSettings)

  implicit val activityPerEntityFormat: RootJsonFormat[ActivitySettings.PerEntitySettings] =
    jsonFormat2(ActivitySettings.PerEntitySettings)

  // support both the nested perEntity format, and flattened version which creates default range map
  implicit val activityPerSliceFormat: RootJsonFormat[ActivitySettings.PerSliceSettings] =
    new RootJsonFormat[ActivitySettings.PerSliceSettings] {
      // always write the fully nested format
      override def write(perSlice: ActivitySettings.PerSliceSettings): JsValue = {
        JsObject("perEntity" -> perSlice.perEntity.toJson)
      }

      override def read(json: JsValue): ActivitySettings.PerSliceSettings = {
        val fields = json.asJsObject.fields

        if (fields.contains("perEntity")) { // nested format for entities
          val perEntity = fields("perEntity").convertTo[RangeMap[Int, ActivitySettings.PerEntitySettings]]
          ActivitySettings.PerSliceSettings(perEntity)
        } else { // flattened format
          val duration = fields("duration").convertTo[SamplerSettings[FiniteDuration]]
          val event = fields("event").convertTo[EventSettings]
          val perEntitySettings = ActivitySettings.PerEntitySettings(duration, event)
          val perEntity = RangeMap(Range.Default -> perEntitySettings)
          ActivitySettings.PerSliceSettings(perEntity)
        }
      }
    }

  // support both the nested perSlice / perEntity format, and flattened versions which create default range maps
  implicit val activityFormat: RootJsonFormat[ActivitySettings] = new RootJsonFormat[ActivitySettings] {
    // always write the fully nested format
    override def write(settings: ActivitySettings): JsValue = {
      JsObject("frequency" -> settings.frequency.toJson, "perSlice" -> settings.perSlice.toJson)
    }

    override def read(json: JsValue): ActivitySettings = {
      val fields = json.asJsObject.fields

      val frequency = fields("frequency").convertTo[PointProcessSettings]

      if (fields.contains("perSlice")) { // nested format for slices
        val perSlice = fields("perSlice").convertTo[RangeMap[Slice, ActivitySettings.PerSliceSettings]]
        ActivitySettings(frequency, perSlice)
      } else { // flattened format for slices
        val perSliceSettings = json.convertTo[ActivitySettings.PerSliceSettings]
        val perSlice = RangeMap[Slice, ActivitySettings.PerSliceSettings](Range.Default -> perSliceSettings)
        ActivitySettings(frequency, perSlice)
      }
    }
  }

  implicit val randomFormat: RootJsonFormat[RandomSettings] = jsonFormat2(RandomSettings)

  implicit val generatorFormat: RootJsonFormat[GeneratorSettings] = jsonFormat3(GeneratorSettings)

  implicit val stageFormat: RootJsonFormat[StageSettings] = jsonFormat4(StageSettings)

  implicit val engineFormat: RootJsonFormat[EngineSettings] = jsonFormat4(EngineSettings)

  implicit val simulationFormat: RootJsonFormat[SimulationSettings] = jsonFormat4(SimulationSettings)
}
