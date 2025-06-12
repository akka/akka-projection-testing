/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.simulation

import scala.concurrent.duration._

import akka.projection.testing.simulation.SimulationJsonFormat._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

class SimulationGeneratorSpec extends AnyWordSpec with Matchers {

  "SimulationGenerator" should {

    "generate events for a simple single stage" in {
      val json = """
        {
          "stages": [
            {
              "duration": "10s",
              "generators": [
                {
                  "entityId": {
                    "entity": { "count": "10k" }
                  },
                  "activity": {
                    "frequency": "10/s",
                    "duration": "1s",
                    "event": {
                      "frequency": "10/s",
                      "dataSize": "10B"
                    }
                  }
                }
              ]
            }
          ]
        }
      """.parseJson

      val settings = json.convertTo[SimulationSettings]
      val generator = SimulationGenerator(settings)

      val events = generator.events().toSeq
      val activities = events.groupBy(_.activity.id)

      events.size shouldBe 1000 +- 300

      events.foreach { event =>
        event.activity.id.generator shouldBe 1
        event.activity.id.stage shouldBe 1
        event.activity.id.seqNr should (be >= 1 and be <= activities.size)
        event.activity.entityId.entity should (be >= 1 and be <= 10000)
        event.activity.entityId.slice.value should (be >= 0 and be <= 1023)
        event.activity.start.value should (be >= 0.0 and be <= 10.0)
        event.activity.duration.value shouldBe 1.0
        event.time.value should (be >= 0.0 and be <= 10.0)
        event.point.value should be <= event.activity.duration.value
        event.dataBytes shouldBe 10
      }

      activities.size shouldBe 100 +- 30

      activities.values.foreach { activityEvents =>
        activityEvents.map(_.seqNr) shouldBe (1 to activityEvents.size)
      }
    }

    "generate events for multiple stages" in {
      val json = """
        {
          "stages": [
            {
              "name": "Stage 1",
              "duration": "10s",
              "generators": [
                {
                  "entityId": {
                    "entity": 1
                  },
                  "activity": {
                    "frequency": "1/s",
                    "duration": "1s",
                    "event": {
                      "frequency": "1/s",
                      "dataSize": 1
                    }
                  }
                }
              ]
            },
            {
              "name": "Stage 2",
              "delay": "10s",
              "duration": "10s",
              "generators": [
                {
                  "entityId": {
                    "entity": 2
                  },
                  "activity": {
                    "frequency": "1/s",
                    "duration": "1s",
                    "event": {
                      "frequency": "1/s",
                      "dataSize": 2
                    }
                  }
                }
              ]
            }
          ]
        }
       """.parseJson

      val settings = json.convertTo[SimulationSettings]
      val generator = SimulationGenerator(settings)

      val events = generator.events().toSeq

      val stage1Events = events.filter(_.activity.id.stage == 1)
      val stage2Events = events.filter(_.activity.id.stage == 2)

      stage1Events.size should be >= 1
      stage2Events.size should be >= 1

      events.size shouldBe (stage1Events.size + stage2Events.size)

      stage1Events.foreach { event =>
        event.time.value should (be >= 0.0 and be < 10.0)
        event.activity.entityId.entity shouldBe 1
        event.dataBytes shouldBe 1
      }

      stage2Events.foreach { event =>
        event.time.value should (be >= 20.0 and be < 30.0)
        event.activity.entityId.entity shouldBe 2
        event.dataBytes shouldBe 2
      }
    }

    "generate events from multiple generators within a stage" in {
      val json = """
        {
          "stages": [
            {
              "duration": "10s",
              "generators": [
                {
                  "entityId": {
                    "entity": 1
                  },
                  "activity": {
                    "frequency": "1/s",
                    "duration": "1s",
                    "event": {
                      "frequency": "1/s",
                      "dataSize": 1
                    }
                  }
                },
                {
                  "entityId": {
                    "entity": 2
                  },
                  "activity": {
                    "frequency": "1/s",
                    "duration": "1s",
                    "event": {
                      "frequency": "1/s",
                      "dataSize": 2
                    }
                  }
                }
              ]
            }
          ]
        }
       """.parseJson

      val settings = json.convertTo[SimulationSettings]
      val generator = SimulationGenerator(settings)

      val events = generator.events().toSeq

      val gen1Events = events.filter(_.activity.id.generator == 1)
      val gen2Events = events.filter(_.activity.id.generator == 2)

      gen1Events.size should be >= 1
      gen2Events.size should be >= 1

      events.size shouldBe (gen1Events.size + gen2Events.size)

      gen1Events.foreach(_.dataBytes shouldBe 1)
      gen2Events.foreach(_.dataBytes shouldBe 2)

      // check events across generators are ordered by time
      events.sliding(2).foreach {
        case Seq(event1, event2) => event1.time.value should be <= event2.time.value
        case _                   =>
      }
    }

    "group events by time window" in {
      val json = """
        {
          "stages": [
            {
              "duration": "10s",
              "generators": [
                {
                  "entityId": {
                    "entity": 1
                  },
                  "activity": {
                    "frequency": "10/s",
                    "duration": "1s",
                    "event": {
                      "frequency": "10/s",
                      "dataSize": 1
                    }
                  }
                }
              ]
            }
          ]
        }
       """.parseJson

      val settings = json.convertTo[SimulationSettings]
      val generator = SimulationGenerator(settings)

      val window = 200.millis
      val groupedEvents = generator.groupedEvents(window).toSeq

      // check all grouped events are the same as regularly generated events

      groupedEvents.flatten shouldBe generator.events().toSeq

      // check grouped events are within expected boundaries

      val windowSeconds = Point(window).value
      val boundaries = Iterator.unfold(0.0) { startBoundary =>
        val endBoundary = startBoundary + windowSeconds
        Some(((startBoundary, endBoundary), endBoundary))
      }

      groupedEvents.zip(boundaries).foreach { case (group, (startBoundary, endBoundary)) =>
        group.foreach { event =>
          event.time.value should (be >= startBoundary and be < endBoundary)
        }
      }
    }

    "generate deterministic events with a random seed" in {
      val json = """
        {
          "stages": [
            {
              "duration": "10s",
              "generators": [
                {
                  "entityId": {
                    "entity": {
                      "count": "1k"
                    }
                  },
                  "activity": {
                    "frequency": "10/s",
                    "duration": "1s",
                    "event": {
                      "frequency": "20/s",
                      "dataSize": "10B"
                    }
                  },
                  "random": {
                    "seed": 12345
                  }
                }
              ]
            }
          ]
        }
       """.parseJson

      val settings1 = json.convertTo[SimulationSettings]
      val generator1 = SimulationGenerator(settings1)
      val events1 = generator1.events().toSeq

      val settings2 = json.convertTo[SimulationSettings]
      val generator2 = SimulationGenerator(settings2)
      val events2 = generator2.events().toSeq

      events1.size should be > 1000
      events1 shouldBe events2
    }

    "generate events with Zipf distribution" in {
      val json = """
        {
          "stages": [
            {
              "duration": "10s",
              "generators": [
                {
                  "entityId": {
                    "entity": {
                      "distribution": "zipf",
                      "min": 1,
                      "max": 1000,
                      "exponent": 1.5
                    }
                  },
                  "activity": {
                    "frequency": "100/s",
                    "duration": "1s",
                    "event": {
                      "frequency": "1/s",
                      "dataSize": "10B"
                    }
                  },
                  "random": {
                    "seed": 12345
                  }
                }
              ]
            }
          ]
        }
      """.parseJson

      val settings = json.convertTo[SimulationSettings]
      val generator = SimulationGenerator(settings)

      val events = generator.events().toSeq

      // verify that entities are selected with a Zipf distribution

      events.size should be > 1000

      val entityCounts = events
        .groupBy(_.activity.entityId.entity)
        .view
        .mapValues(_.size)
        .toSeq
        .sortBy(-_._2) // most frequent first

      entityCounts.size should be > 100

      // first entity should appear roughly 10^1.5 ≈ 32 times more than the 10th
      val firstEntityCount = entityCounts.head._2
      val tenthEntityCount = entityCounts(9)._2
      val firstToTenthRatio = firstEntityCount.toDouble / tenthEntityCount.toDouble
      firstToTenthRatio shouldBe 32.0 +- 2.0

      // verify the decay curve by checking that each step has a significant drop in frequency
      val countRatios = entityCounts
        .take(10)
        .map(_._2)
        .sliding(2)
        .map {
          case Seq(a, b) => a.toDouble / b.toDouble
          case _         => 0.0
        }
        .toSeq

      val averageRatio = countRatios.sum / countRatios.size
      averageRatio shouldBe 1.5 +- 0.1
    }

    "generate activity durations with log-normal distribution" in {
      val json = """
        {
          "stages": [
            {
              "duration": "10s",
              "generators": [
                {
                  "entityId": {
                    "entity": 1
                  },
                  "activity": {
                    "frequency": "100/s",
                    "duration": {
                      "distribution": "log-normal",
                      "median": "1s",
                      "p95": "5s",
                      "min": "0.1s",
                      "max": "10s"
                    },
                    "event": {
                      "frequency": "10/s",
                      "dataSize": "10B"
                    }
                  },
                  "random": {
                    "seed": 42
                  }
                }
              ]
            }
          ]
        }
      """.parseJson

      val settings = json.convertTo[SimulationSettings]
      val generator = SimulationGenerator(settings)

      val events = generator.events().toSeq

      // Log-normal distributions are:
      // - right-skewed (mean > median)
      // - most values cluster around the median, with a long tail

      events.size should be > 10000

      val activities = events.groupBy(_.activity.id)
      val activityDurations = activities
        .map { case (_, activityEvents) =>
          activityEvents.head.activity.duration.value
        }
        .toSeq
        .sorted

      activityDurations.size should be > 1000
      activityDurations.forall(_ >= 0.1) shouldBe true
      activityDurations.forall(_ <= 10.0) shouldBe true

      val median = if (activityDurations.size % 2 == 0) {
        val mid = activityDurations.size / 2
        (activityDurations(mid - 1) + activityDurations(mid)) / 2
      } else {
        activityDurations(activityDurations.size / 2)
      }

      val mean = activityDurations.sum / activityDurations.size

      // check expected median at ~1s
      median shouldBe 1.0 +- 0.05

      // for log-normal, mean should always be greater than median
      mean should be > median

      // check the 95th percentile at ~5s
      val p95Index = math.floor(activityDurations.size * 0.95).toInt
      val p95 = activityDurations(p95Index)
      p95 should be(5.0 +- 0.5)

      // roughly 50% below median (1.0)
      val below1Percentage = activityDurations.count(_ < 1.0).toDouble / activityDurations.size
      below1Percentage shouldBe 0.5 +- 0.15

      // verify the distribution is right-skewed
      val countFrom1To2 = activityDurations.count(duration => duration >= 1.0 && duration < 2.0)
      val countFrom2To5 = activityDurations.count(duration => duration >= 2.0 && duration < 5.0)
      val countFrom5To10 = activityDurations.count(duration => duration >= 5.0 && duration <= 10.0)
      countFrom1To2 should be > countFrom2To5
      countFrom2To5 should be > countFrom5To10
    }

    "generate activities with linear rate function" in {
      val json = """
        {
          "stages": [
            {
              "duration": "20s",
              "generators": [
                {
                  "entityId": {
                    "entity": 1
                  },
                  "activity": {
                    "frequency": {
                      "function": "linear",
                      "initial": "1/s",
                      "target": "10/s"
                    },
                    "duration": "1s",
                    "event": {
                      "frequency": "1/s",
                      "dataSize": "10B"
                    }
                  },
                  "random": {
                    "seed": 42
                  }
                }
              ]
            }
          ]
        }
      """.parseJson

      val settings = json.convertTo[SimulationSettings]
      val generator = SimulationGenerator(settings)

      val events = generator.events().toSeq
      val activities = events.map(_.activity).distinct

      activities.size shouldBe 100 +- 5

      // check activity density over time by dividing into 5 equal parts
      val timeSegments = 5
      val segmentDuration = 20.0 / timeSegments
      val eventCountBySegment = (0 until timeSegments).map { i =>
        val segmentStart = i * segmentDuration
        val segmentEnd = segmentStart + segmentDuration
        events.count(event => event.time.value >= segmentStart && event.time.value < segmentEnd)
      }

      // verify each subsequent segment has more events than the previous one
      for (i <- 0 until timeSegments - 1) {
        eventCountBySegment(i) should be < eventCountBySegment(i + 1)
      }

      val firstToLastRatio = eventCountBySegment.last.toDouble / eventCountBySegment.head.toDouble
      firstToLastRatio shouldBe 10.0 +- 5.0
    }

    "generate activities with sinusoidal rate function" in {
      val json = """
        {
          "stages": [
            {
              "duration": "1m",
              "generators": [
                {
                  "entityId": {
                    "entity": 1
                  },
                  "activity": {
                    "frequency": {
                      "function": "sinusoidal",
                      "base": "10/s",
                      "amplitude": "10/s",
                      "period": "20s",
                      "shift": "0s"
                    },
                    "duration": "0s",
                    "event": {
                      "frequency": "0/s",
                      "dataSize": "10B"
                    }
                  },
                  "random": {
                    "seed": 12345
                  }
                }
              ]
            }
          ]
        }
      """.parseJson

      val settings = json.convertTo[SimulationSettings]
      val generator = SimulationGenerator(settings)

      val events = generator.events().toSeq

      events.size should be > 600

      // check activity density over time by dividing into 30 equal parts (2s each)
      val timeSegments = 30
      val segmentDuration = 60.0 / timeSegments
      val eventCountsBySegment = (0 until timeSegments).map { i =>
        val segmentStart = i * segmentDuration
        val segmentEnd = segmentStart + segmentDuration
        events.count(event => event.time.value >= segmentStart && event.time.value < segmentEnd)
      }

      // for sinusoidal with period of 20s and no shift we expect:
      // - highest density at 5s, 25s, 45s
      // - lowest density at 15s, 35s, 55s
      val highPointSegments = Seq(2, 12, 22)
      val lowPointSegments = Seq(7, 17, 27)

      highPointSegments.zip(lowPointSegments).foreach { case (highSegment, lowSegment) =>
        val high = eventCountsBySegment(highSegment)
        val low = eventCountsBySegment(lowSegment)
        val diff = (high - low) / segmentDuration
        high should be > low
        diff shouldBe 20.0 +- 10.0
      }

      // check that the pattern repeats
      val firstPeriodHighCount = eventCountsBySegment(2).toDouble
      val secondPeriodHighCount = eventCountsBySegment(12).toDouble
      val thirdPeriodHighCount = eventCountsBySegment(22).toDouble

      // ensure the high points are roughly similar (within 20% of each other)
      secondPeriodHighCount shouldBe firstPeriodHighCount +- firstPeriodHighCount * 0.2
      thirdPeriodHighCount shouldBe firstPeriodHighCount +- firstPeriodHighCount * 0.2
    }

    "map entities to specific slices with skewed distribution" in {
      val json = """
        {
          "stages": [
            {
              "duration": "5s",
              "generators": [
                {
                  "entityId": {
                    "entity": {
                      "count": 1000
                    },
                    "slices": "100-200",
                    "sliceDistribution": {
                      "type": "zipf",
                      "exponent": 2.0
                    }
                  },
                  "activity": {
                    "frequency": "100/s",
                    "duration": "1s",
                    "event": {
                      "frequency": "1/s",
                      "dataSize": "10B"
                    }
                  },
                  "random": {
                    "seed": 12345
                  }
                }
              ]
            }
          ]
        }
      """.parseJson

      val settings = json.convertTo[SimulationSettings]
      val generator = SimulationGenerator(settings)

      val events = generator.events().toSeq

      events.size should be > 500

      val sliceDistribution = events.map(_.activity.entityId.slice.value).groupBy(identity).view.mapValues(_.size).toMap

      // check that only slices in the configured range (100-200) are used
      sliceDistribution.keys.forall(slice => slice >= 100 && slice <= 200) shouldBe true

      // check there's a very skewed distribution over slices (for zipf)
      val sortedCounts = sliceDistribution.values.toSeq.sorted.reverse
      val top10Percent = sortedCounts.take((sortedCounts.size * 0.1).toInt + 1)
      val total = sortedCounts.sum
      val topSum = top10Percent.sum
      val topPercentage = topSum.toDouble / total
      sortedCounts.head should be > (sortedCounts.last * 100)
      topPercentage should be > 0.8
    }

  }
}
