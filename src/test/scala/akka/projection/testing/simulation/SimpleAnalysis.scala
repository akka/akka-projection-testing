/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.simulation

import scala.collection.immutable.IntMap
import scala.collection.mutable
import scala.concurrent.duration._
import scala.io.Source

import spray.json._

/**
 * sbt "Test/runMain akka.projection.testing.simulation.SimpleAnalysis /path/to/simulation/run.json"
 */
object SimpleAnalysis {

  final case class Stats(activities: Int, events: Int, data: Long) {
    def +(that: Stats): Stats =
      Stats(this.activities + that.activities, this.events + that.events, this.data + that.data)
  }

  object Stats {
    val empty: Stats = Stats(0, 0, 0L)
  }

  final case class RateStats(min: Double, max: Double, avg: Double, windows: Int) {
    def combine(other: RateStats): RateStats = {
      val totalWindows = this.windows + other.windows
      val combinedAvg = (this.avg * this.windows + other.avg * other.windows) / totalWindows
      RateStats(math.min(this.min, other.min), math.max(this.max, other.max), combinedAvg, totalWindows)
    }
  }

  object RateStats {
    def fromSingleWindow(rate: Double): RateStats = RateStats(rate, rate, rate, 1)
    val empty: RateStats = RateStats(0.0, 0.0, 0.0, 0)
  }

  final case class WindowStats(start: FiniteDuration, stats: Stats, slices: IntMap[Stats])

  object WindowStats {
    val empty: WindowStats = WindowStats(Duration.Zero, Stats.empty, IntMap.empty)
  }

  final case class Summary(stats: Stats, windows: Seq[WindowStats])

  object Summary {
    val empty: Summary = Summary(Stats.empty, Seq.empty)
  }

  final class StatsBuilder {
    private var activities = 0
    private var events = 0
    private var data = 0L

    def +=(event: Event): Unit = {
      if (event.seqNr == 1) activities += 1
      events += 1
      data += event.dataBytes
    }

    def build(): Stats = Stats(activities, events, data)
  }

  val NumWindows = 500
  val ColumnWidth = 148

  def analyse(settings: SimulationSettings): Unit = {
    val generator = SimulationGenerator(settings)
    val totalDuration = calculateTotalDuration(settings)
    val windowDuration = totalDuration / NumWindows

    println("=" * ColumnWidth)
    println(s"SIMULATION ANALYSIS: ${settings.name.getOrElse("Unnamed")}")
    println("=" * ColumnWidth)

    println(f"Total simulation duration: ${formatDuration(totalDuration)}")
    println(f"Window duration: ${formatDuration(windowDuration)}")
    println(f"Number of windows: $NumWindows")
    println(" ")
    renderProgressHeader()

    val summary = generator
      .groupedEvents(windowDuration)
      .zipWithIndex
      .foldLeft(Summary.empty) { case (summary, (group, index)) =>
        val window = analyseWindow(windowDuration * index, group)
        val totalStats = summary.stats + window.stats
        displayProgress(totalStats, window, index, NumWindows, windowDuration)
        Summary(totalStats, summary.windows :+ window)
      }

    println()
    renderSummary(settings, summary, totalDuration, windowDuration)
  }

  def analyseWindow(start: FiniteDuration, group: Seq[Event]): WindowStats = {
    val stats = new StatsBuilder
    val slices = mutable.Map.empty[Int, StatsBuilder]

    group.foreach { event =>
      stats += event
      val slice = event.activity.entityId.slice.value
      slices.getOrElseUpdate(slice, new StatsBuilder) += event
    }

    WindowStats(start, stats.build(), slices.view.mapValues(_.build()).to(IntMap))
  }

  def calculateTotalDuration(settings: SimulationSettings): FiniteDuration = {
    settings.stages.foldLeft(Duration.Zero) { case (total, stage) =>
      total + stage.delay.getOrElse(Duration.Zero) + stage.duration
    }
  }

  def calculateSliceRates(summary: Summary, windowDuration: FiniteDuration): Map[Int, RateStats] = {
    val windowSeconds = windowDuration.toNanos / 1_000_000_000.0
    val sliceRates = mutable.Map.empty[Int, mutable.ArrayBuffer[Double]]

    summary.windows.foreach { window =>
      window.slices.foreach { case (slice, stats) =>
        val eventRate = stats.events.toDouble / windowSeconds
        if (eventRate > 0.0) {
          sliceRates.getOrElseUpdate(slice, mutable.ArrayBuffer.empty) += eventRate
        }
      }
    }

    sliceRates.view.mapValues(calculateRateStats).toMap
  }

  def calculateProjectionRates(summary: Summary, windowDuration: FiniteDuration): Map[Int, RateStats] = {
    val windowSeconds = windowDuration.toNanos / 1_000_000_000.0
    val projectionRates = mutable.Map.empty[Int, mutable.ArrayBuffer[Double]]

    summary.windows.foreach { window =>
      (0 until 8).foreach { projectionId =>
        val startSlice = projectionId * 128
        val endSlice = startSlice + 127
        val projectionStats = window.slices
          .filter { case (slice, _) =>
            slice >= startSlice && slice <= endSlice
          }
          .values
          .foldLeft(Stats.empty)(_ + _)

        val eventRate = projectionStats.events.toDouble / windowSeconds
        if (eventRate > 0.0) {
          projectionRates.getOrElseUpdate(projectionId, mutable.ArrayBuffer.empty) += eventRate
        }
      }
    }

    projectionRates.view.mapValues(calculateRateStats).toMap
  }

  def calculateRateStats(rates: mutable.ArrayBuffer[Double]): RateStats = {
    if (rates.isEmpty) return RateStats.empty

    RateStats(rates.min, rates.max, rates.sum / rates.length, rates.length)
  }

  def renderProgressHeader(): Unit = {
    println {
      f"${"progress"}%24s " +
      f"${"#"}%6s " +
      f"${"time"}%12s " +
      f"${"activities/s"}%15s " +
      f"${"events/s"}%15s " +
      f"${"activities"}%15s " +
      f"${"events"}%15s " +
      f"${"bytes"}%18s"
    }
    println("-" * ColumnWidth)
  }

  def displayProgress(
      totals: Stats,
      window: WindowStats,
      windowIndex: Int,
      numWindows: Int,
      windowDuration: FiniteDuration): Unit = {
    val progress = (windowIndex + 1).toDouble / numWindows * 100
    val barWidth = 15
    val filled = (progress / 100.0 * barWidth).toInt
    val progressBar = "█" * filled + "░" * (barWidth - filled)
    val windowSeconds = windowDuration.toNanos / 1_000_000_000.0
    print {
      f"\r$progressBar ${progress}%7.1f%% " +
      f"${windowIndex + 1}%6d " +
      f"${formatDuration(window.start)}%12s " +
      f"${window.stats.activities / windowSeconds}%,13.1f/s " +
      f"${window.stats.events / windowSeconds}%,13.1f/s " +
      f"${totals.activities}%,15d " +
      f"${totals.events}%,15d " +
      f"${totals.data}%,18d"
    }
    System.out.flush()
  }

  def renderSummary(
      settings: SimulationSettings,
      summary: Summary,
      totalDuration: FiniteDuration,
      windowDuration: FiniteDuration): Unit = {
    val stats = summary.stats
    val avgEventsPerActivity = if (stats.activities > 0) stats.events.toDouble / stats.activities else 0.0
    val avgActivitiesPerSec = stats.activities.toDouble / totalDuration.toNanos * 1_000_000_000.0
    val avgEventsPerSec = stats.events.toDouble / totalDuration.toNanos * 1_000_000_000.0

    println(" ")
    println("=" * 120)
    println(s"Simulation: ${settings.name.getOrElse("unnamed")}")
    println("=" * 120)

    settings.description.foreach { desc =>
      println(" ")
      println(s"Description: $desc")
    }

    println(" ")
    println("SUMMARY")
    println("-" * ColumnWidth)
    println(f"Total duration: ${formatDuration(totalDuration)}")
    println(f"Total activities: ${stats.activities}%,d")
    println(f"Total events: ${stats.events}%,d")
    println(f"Average events/activity: ${avgEventsPerActivity}%.2f")
    println(f"Average activity rate: ${avgActivitiesPerSec}%.2f activities/s")
    println(f"Average event rate: ${avgEventsPerSec}%.2f events/s")
    println(f"Total data volume: ${formatBytes(stats.data)}")

    renderPatterns(summary, windowDuration)
    renderProjections(summary, windowDuration)
    renderSlices(summary, windowDuration)

    println(" ")
  }

  def renderPatterns(summary: Summary, windowDuration: FiniteDuration): Unit = {
    println(" ")
    println("PATTERNS")
    println("-" * ColumnWidth)

    val windowSeconds = windowDuration.toNanos / 1_000_000_000.0
    val activityRates = summary.windows.map(w => w.stats.activities.toDouble / windowSeconds)
    val eventRates = summary.windows.map(w => w.stats.events.toDouble / windowSeconds)

    val maxActivityRate = activityRates.maxOption.getOrElse(0.0)
    val maxEventRate = eventRates.maxOption.getOrElse(0.0)

    println(f"Peak activity rate: ${maxActivityRate}%.2f activities/s")
    println(f"Peak event rate: ${maxEventRate}%.2f events/s")
    println(" ")

    println {
      f"${"#"}%4s " +
      f"${"Time"}%8s " +
      f"${"Activities/s"}%50s " +
      f"${""}%12s " +
      f"${"Events/s"}%50s " +
      f"${""}%12s"
    }
    println("-" * ColumnWidth)

    summary.windows.zipWithIndex.foreach { case (window, index) =>
      val activityRate = window.stats.activities.toDouble / windowSeconds
      val eventRate = window.stats.events.toDouble / windowSeconds

      val activityBar = renderBar(activityRate, maxActivityRate, 40)
      val eventBar = renderBar(eventRate, maxEventRate, 40)

      println {
        f"${index + 1}%4d " +
        f"${formatDuration(window.start)}%8s " +
        f"$activityBar%50s " +
        f"${activityRate}%10.1f/s " +
        f"$eventBar%50s " +
        f"${eventRate}%10.1f/s"
      }
    }

    println(" ")
    println(
      f"Activity rates - min: ${activityRates.minOption.getOrElse(0.0)}%.1f, " +
        f"max: ${maxActivityRate}%.1f, " +
        f"avg: ${activityRates.sum / activityRates.length}%.1f activities/s")
    println(
      f"Event rates - min: ${eventRates.minOption.getOrElse(0.0)}%.1f, " +
        f"max: ${maxEventRate}%.1f, " +
        f"avg: ${eventRates.sum / eventRates.length}%.1f events/s")
  }

  def renderBar(value: Double, maxValue: Double, width: Int): String = {
    if (maxValue == 0.0) return " " * width

    val normalizedValue = value / maxValue
    val filledWidth = (normalizedValue * width).toInt
    val remainder = (normalizedValue * width) - filledWidth

    val fullChar = "█"
    val partialChars = Array(" ", "▏", "▎", "▍", "▌", "▋", "▊", "▉")
    val partialChar = partialChars((remainder * 8).toInt)

    val bar = fullChar * filledWidth + (if (filledWidth < width && remainder > 0) partialChar else "")
    val padding = " " * (width - bar.length)

    bar + padding
  }

  def renderProjections(summary: Summary, windowDuration: FiniteDuration): Unit = {
    println(" ")
    println("PROJECTIONS")
    println("-" * ColumnWidth)

    val sliceStats = mutable.Map.empty[Int, Stats]
    summary.windows.foreach { window =>
      window.slices.foreach { case (slice, stats) =>
        sliceStats.updateWith(slice) {
          case Some(existing) => Some(existing + stats)
          case None           => Some(stats)
        }
      }
    }

    val projectionEventRates = calculateProjectionRates(summary, windowDuration)

    // Analyze 8 projection instances (128 slices each)
    val instanceLoads = (0 until 8).map { instance =>
      val startSlice = instance * 128
      val endSlice = startSlice + 127
      val instanceSliceStats = sliceStats.filter { case (slice, _) => slice >= startSlice && slice <= endSlice }
      val instanceStats = instanceSliceStats.values.foldLeft(Stats.empty)(_ + _)

      val sliceEventCounts = (startSlice to endSlice).map { slice =>
        sliceStats.get(slice).map(_.events).getOrElse(0)
      }
      val minEvents = if (sliceEventCounts.nonEmpty) sliceEventCounts.min else 0
      val maxEvents = if (sliceEventCounts.nonEmpty) sliceEventCounts.max else 0
      val avgEvents = if (sliceEventCounts.nonEmpty) sliceEventCounts.sum.toDouble / sliceEventCounts.length else 0.0

      val eventRateStats = projectionEventRates.getOrElse(instance, RateStats.empty)

      (instance, startSlice, endSlice, instanceStats, minEvents, maxEvents, avgEvents, eventRateStats)
    }

    val totalEvents = sliceStats.values.map(_.events).sum

    println(" ")
    println {
      f"${"#"}%3s " +
      f"${"Slices"}%11s " +
      f"${"Load %"}%8s " +
      f"${"Activities"}%12s " +
      f"${"Events"}%12s " +
      f"${"Min/second"}%12s " +
      f"${"Max/second"}%12s " +
      f"${"Avg/second"}%12s " +
      f"${"Min/slice"}%12s " +
      f"${"Max/slice"}%12s " +
      f"${"Avg/slice"}%12s"
    }
    println("-" * ColumnWidth)

    instanceLoads.foreach { case (instance, start, end, stats, minEvents, maxEvents, avgEvents, eventRateStats) =>
      val percentage = if (totalEvents > 0) (stats.events.toDouble / totalEvents) * 100 else 0.0
      println {
        f"$instance%3d " +
        f"$start%4s - $end%4s " +
        f"${percentage}%7.2f%% " +
        f"${stats.activities}%12d " +
        f"${stats.events}%12d " +
        f"${eventRateStats.min}%10.1f/s " +
        f"${eventRateStats.max}%10.1f/s " +
        f"${eventRateStats.avg}%10.1f/s " +
        f"${minEvents}%12d " +
        f"${maxEvents}%12d " +
        f"${avgEvents}%12.1f"
      }
    }
  }

  def renderSlices(summary: Summary, windowDuration: FiniteDuration): Unit = {
    println(" ")
    println("SLICES (top 10)")
    println("-" * ColumnWidth)

    val sliceStats = mutable.Map.empty[Int, Stats]
    summary.windows.foreach { window =>
      window.slices.foreach { case (slice, stats) =>
        sliceStats.updateWith(slice) {
          case Some(existing) => Some(existing + stats)
          case None           => Some(stats)
        }
      }
    }

    val sortedSlices = sliceStats.toSeq.sortBy(-_._2.events).take(10)
    val totalEvents = sliceStats.values.map(_.events).sum

    val sliceEventRates = calculateSliceRates(summary, windowDuration)

    println(" ")
    println {
      f"${"Slice"}%5s " +
      f"${"Total %"}%8s " +
      f"${"Activities"}%12s " +
      f"${"Events"}%12s " +
      f"${"Data"}%12s " +
      f"${"Min/second"}%12s " +
      f"${"Max/second"}%12s " +
      f"${"Avg/second"}%12s"
    }
    println("-" * ColumnWidth)

    sortedSlices.foreach { case (slice, stats) =>
      val percentage = if (totalEvents > 0) (stats.events.toDouble / totalEvents) * 100 else 0.0
      val eventRateStats = sliceEventRates.getOrElse(slice, RateStats.empty)
      println {
        f"${slice}%5d " +
        f"${percentage}%7.2f%% " +
        f"${stats.activities}%12d " +
        f"${stats.events}%12d " +
        f"${formatBytes(stats.data)}%12s " +
        f"${eventRateStats.min}%10.1f/s " +
        f"${eventRateStats.max}%10.1f/s " +
        f"${eventRateStats.avg}%10.1f/s"
      }
    }

    renderSliceDistribution(summary)
  }

  def calculateSliceVariance(sliceStats: Map[Int, Stats]): Double = {
    if (sliceStats.isEmpty) return 0.0

    val eventCounts = sliceStats.values.map(_.events.toDouble).toSeq
    if (eventCounts.isEmpty) return 0.0

    val mean = eventCounts.sum / eventCounts.length
    if (mean == 0.0) return 0.0

    val variance = eventCounts.map(x => math.pow(x - mean, 2)).sum / eventCounts.length
    val stdDev = math.sqrt(variance)

    // Return coefficient of variation (CV)
    stdDev / mean
  }

  def renderSliceDistribution(summary: Summary): Unit = {
    val sliceStats = mutable.Map.empty[Int, Stats]
    summary.windows.foreach { window =>
      window.slices.foreach { case (slice, stats) =>
        sliceStats.updateWith(slice) {
          case Some(existing) => Some(existing + stats)
          case None           => Some(stats)
        }
      }
    }

    val varianceCoeff = calculateSliceVariance(sliceStats.toMap)

    println(" ")
    println("SLICE DISTRIBUTION")
    println("-" * ColumnWidth)
    println(f"Coefficient of variation: ${varianceCoeff * 100}%.1f%%")
    println(" ")

    // Group slices by projection instance (8 instances, 128 slices each)
    val instanceDistributions = (0 until 8).map { instance =>
      val startSlice = instance * 128
      val endSlice = startSlice + 127

      val sliceCounts = (startSlice to endSlice).map { slice =>
        sliceStats.get(slice).map(_.events).getOrElse(0).toDouble
      }

      (instance, startSlice, endSlice, sliceCounts)
    }

    val maxEvents = instanceDistributions.flatMap(_._4).maxOption.getOrElse(1.0)

    def getBarChar(value: Double, maxValue: Double): Char = {
      if (maxValue == 0.0) return ' '
      val intensity = value / maxValue
      if (intensity == 0.0) ' '
      else if (intensity <= 0.125) '▁'
      else if (intensity <= 0.25) '▂'
      else if (intensity <= 0.375) '▃'
      else if (intensity <= 0.5) '▄'
      else if (intensity <= 0.625) '▅'
      else if (intensity <= 0.75) '▆'
      else if (intensity <= 0.875) '▇'
      else '█'
    }

    println(f"${"#"}%3s ${"Slices"}%11s ${"Event distribution (128 slices, one character per slice)"}%132s")
    println("-" * ColumnWidth)

    instanceDistributions.foreach { case (instance, start, end, sliceCounts) =>
      val distributionBar = sliceCounts.map(count => getBarChar(count, maxEvents)).mkString("")

      println(f"$instance%3d $start%4s - $end%4s    $distributionBar")
    }
  }

  def formatDuration(duration: FiniteDuration): String = {
    val seconds = duration.toNanos / 1_000_000_000.0
    if (seconds < 60) f"${seconds}%.2fs"
    else if (seconds < 3600) f"${seconds / 60}%.2fm"
    else if (seconds < 86400) f"${seconds / 3600}%.2fh"
    else f"${seconds / 86400}%.2fd"
  }

  def formatBytes(bytes: Long): String = {
    val units = Array("B", "kB", "MB", "GB", "TB")
    var value = bytes.toDouble
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.length - 1) {
      value /= 1024
      unitIndex += 1
    }
    f"${value}%.1f${units(unitIndex)}"
  }

  def run(runSimulationFile: String): Unit = {
    import akka.projection.testing.TestRoutes._
    val runJson = Source.fromFile(runSimulationFile).mkString
    val settings = runJson.parseJson.convertTo[RunSimulation].simulation
    analyse(settings)
  }

  def main(args: Array[String]): Unit = {
    if (args.length < 1) {
      println("Usage: SimpleAnalysis <simulation.json>")
      sys.exit(1)
    }

    val runSimulationFile = args(0)
    run(runSimulationFile)
  }
}
