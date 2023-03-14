/*
 * Copyright (C) 2020 - 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing

import scala.concurrent.Future

import akka.Done
import akka.projection.ProjectionId
import akka.projection.scaladsl.Handler
import org.HdrHistogram.Histogram
import org.slf4j.LoggerFactory

class LoggingHandler[Envelope](projectionId: ProjectionId) extends Handler[Envelope] {

  private val log =
    LoggerFactory.getLogger(getClass)

  // JVM System property
  private val lagThresholdMillis =
    Integer.getInteger("log-projection-lag-threshold-ms", 2000)

  private var reportingStartTime = System.nanoTime()

  private var totalCount = 0
  private var throughputCount = 0
  private var lagCount = 0L
  private var reportingCount = 0

  private val percentiles = List(50.0, 75.0, 90.0, 95.0, 99.0, 99.9)
  private val maxHistogramValue = 60L * 1000L
  private var histogram: Histogram = new Histogram(maxHistogramValue, 2)

  private val logId: String = projectionId.id

  override def process(envelope: Envelope): Future[Done] = {
    totalCount += 1
    val timestamp = envelope match {
      case env: akka.projection.eventsourced.EventEnvelope[_] => env.timestamp
      case env: akka.persistence.query.typed.EventEnvelope[_] => env.timestamp
      case _                                                  => throw new IllegalArgumentException(s"Expected EventEnvelope, but was ${envelope.getClass.getName}")
    }
    val lagMillis = System.currentTimeMillis() - timestamp

    histogram.recordValue(math.max(0L, math.min(lagMillis, maxHistogramValue)))

    throughputCount += 1
    val durationMs: Long =
      (System.nanoTime - reportingStartTime) / 1000 / 1000
    // more frequent reporting in the beginning
    val reportAfter =
      if (reportingCount <= 5) 30000 else 180000
    if (durationMs >= reportAfter) {
      reportingCount += 1

      log.info(
        s"$logId #$reportingCount: Processed ${histogram.getTotalCount} events in $durationMs ms, " +
        s"throughput [${1000L * throughputCount / durationMs}] events/s, " +
        s"max lag [${histogram.getMaxValue}] ms, " +
        s"lag percentiles [${percentiles.map(p => s"$p%=${histogram.getValueAtPercentile(p)}ms").mkString("; ")}]")
      println(
        s"$logId #$reportingCount: HDR histogram [${percentiles.map(p => s"$p%=${histogram.getValueAtPercentile(p)}ms").mkString("; ")}]")
      histogram.outputPercentileDistribution(System.out, 1.0)

      throughputCount = 0
      histogram = new Histogram(maxHistogramValue, 2)
      reportingStartTime = System.nanoTime
    }

    if (lagMillis > lagThresholdMillis) {
      lagCount += 1
      if ((lagCount == 1) || (lagCount % 1000 == 0))
        log.info("Projection [{}] lag [{}] ms. Total [{}] events.", logId, lagMillis, totalCount)
    } else {
      lagCount = 0
    }

    Future.successful(Done)
  }
}
