/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.simulation

import java.text.DecimalFormat

import scala.concurrent.duration._
import scala.util.matching.Regex

final class Slice private (val value: Int) extends AnyVal {
  override def toString: String = s"Slice($value)"
}

object Slice {
  val MinValue = 0
  val MaxValue = 1023

  def apply(value: Int): Slice = {
    require(
      value >= Slice.MinValue && value <= Slice.MaxValue,
      s"Slice value must be between ${Slice.MinValue} and ${Slice.MaxValue} (inclusive), got: $value")
    new Slice(value)
  }

  def parse(s: String): Slice = {
    try {
      val intValue = s.trim.toInt
      apply(intValue)
    } catch {
      case e: NumberFormatException =>
        throw new IllegalArgumentException(s"Cannot parse [$s] as an Int for Slice.", e)
      case e: IllegalArgumentException =>
        throw new IllegalArgumentException(s"Invalid value for Slice: $s. ${e.getMessage}", e)
    }
  }

  implicit val ordering: Ordering[Slice] = Ordering.by(_.value)

  implicit val sliceRangeable: Rangeable[Slice] = new Rangeable[Slice] {
    def parse(s: String): Slice = Slice.parse(s)
    def format(slice: Slice): String = slice.value.toString
  }
}

sealed abstract class CountUnit(val symbol: String, val factor: Long) {
  override def toString: String = symbol
}

object CountUnit {
  case object One extends CountUnit("", 1)
  case object Kilo extends CountUnit("k", 1_000)
  case object Mega extends CountUnit("M", 1_000_000)
  case object Giga extends CountUnit("G", 1_000_000_000)

  val values: Seq[CountUnit] = Seq(One, Kilo, Mega, Giga)

  private val unitsMap: Map[String, CountUnit] =
    values.map(unit => unit.symbol -> unit).toMap

  def parse(symbol: String): CountUnit =
    unitsMap.getOrElse(symbol, One)
}

final class Count private (val value: Double, val unit: CountUnit) {
  def toInt: Int = (value * unit.factor).toInt
  def toLong: Long = (value * unit.factor).toLong

  def to(targetUnit: CountUnit): Count = {
    val newValue = value * unit.factor / targetUnit.factor
    Count(newValue, targetUnit)
  }

  override def equals(other: Any): Boolean = other match {
    case that: Count => this.toLong == that.toLong
    case _           => false
  }

  override def hashCode(): Int = toLong.hashCode()

  override def toString: String = {
    val formattedValue = new DecimalFormat("#.######").format(value)
    s"$formattedValue$unit"
  }
}

object Count {
  def apply(value: Double, unit: CountUnit = CountUnit.One): Count =
    new Count(value, unit)

  def apply(value: Int): Count = Count(value.toDouble)

  def parse(s: String): Count = {
    val pattern = """^\s*(\d+(?:\.\d+)?)\s*([kMG]?)\s*$""".r
    s match {
      case pattern(valueStr, suffix) =>
        val value = valueStr.toDouble
        val unit = CountUnit.parse(suffix)
        new Count(value, unit)
      case _ =>
        throw new IllegalArgumentException(s"Not parseable as a count: $s")
    }
  }
}

final class RatePerUnit(val value: Double, val unit: TimeUnit) {

  def toPerUnit(targetUnit: TimeUnit): RatePerUnit = {
    if (targetUnit == unit) this
    else {
      val sourceNanos = 1.second.toNanos / unit.toNanos(1)
      val targetNanos = 1.second.toNanos / targetUnit.toNanos(1)
      val conversionFactor = sourceNanos / targetNanos
      RatePerUnit(value * conversionFactor, targetUnit)
    }
  }

  def toRate: Rate = Rate(toPerUnit(SECONDS).value)

  override def equals(other: Any): Boolean = other match {
    case that: RatePerUnit =>
      val thisRate = this.toPerUnit(SECONDS).value
      val thatRate = that.toPerUnit(SECONDS).value
      math.abs(thisRate - thatRate) < 0.000001 // handle floating point precision issues
    case _ => false
  }

  override def hashCode(): Int = {
    val normalizedRate = toPerUnit(SECONDS).value
    normalizedRate.hashCode()
  }

  override def toString: String = {
    val formattedValue = new DecimalFormat("#.######").format(value)
    val formattedUnit = unit match {
      case NANOSECONDS  => "ns"
      case MICROSECONDS => "μs"
      case MILLISECONDS => "ms"
      case SECONDS      => "s"
      case MINUTES      => "min"
      case HOURS        => "h"
      case DAYS         => "d"
    }
    s"$formattedValue / $formattedUnit"
  }
}

object RatePerUnit {
  def apply(value: Double, unit: TimeUnit): RatePerUnit = new RatePerUnit(value, unit)

  def parse(s: String): RatePerUnit = {
    val parts = s.split('/')
    if (parts.length != 2) throw new IllegalArgumentException("Frequency must be in the format 'value / unit'")
    val value = parts(0).trim.toDouble
    s"1 ${parts(1)}" match {
      case Duration(_, unit) => RatePerUnit(value, unit)
      case _                 => throw new IllegalArgumentException("Frequency must be in the format 'value / unit'")
    }
  }
}

sealed abstract class DataUnit(val prefix: String, val short: String, val base: Int, val exponent: Int) {
  val bytes: Int = math.pow(base, exponent).toInt
  override def toString: String = short + "B"
}

object DataUnit {
  case object Bytes extends DataUnit("", "", 1024, 0)

  case object Kilobytes extends DataUnit("kilo", "k", 1000, 1)
  case object Megabytes extends DataUnit("mega", "M", 1000, 2)
  case object Gigabytes extends DataUnit("giga", "G", 1000, 3)

  case object Kibibytes extends DataUnit("kibi", "Ki", 1024, 1)
  case object Mebibytes extends DataUnit("mebi", "Mi", 1024, 2)
  case object Gibibytes extends DataUnit("gibi", "Gi", 1024, 3)

  val values: Seq[DataUnit] = Seq(Bytes, Kilobytes, Megabytes, Gigabytes, Kibibytes, Mebibytes, Gibibytes)

  private val unitsMap: Map[String, DataUnit] = {
    val units = Map.newBuilder[String, DataUnit]
    for (unit <- values) {
      units += (unit.prefix + "byte" -> unit)
      units += (unit.prefix + "bytes" -> unit)
      units += (unit.short -> unit)
      units += (unit.short + "B" -> unit)
    }
    units.result()
  }

  def parse(unit: String): DataUnit =
    unitsMap.getOrElse(unit, throw new IllegalArgumentException("Not parseable as a data unit"))
}

final class DataSize(val value: Double, val unit: DataUnit) {
  def toBytes: Int = (value * unit.bytes).toInt

  def to(targetUnit: DataUnit): DataSize = {
    val newValue = value * unit.bytes / targetUnit.bytes
    DataSize(newValue, targetUnit)
  }

  override def equals(other: Any): Boolean = other match {
    case that: DataSize => this.toBytes == that.toBytes
    case _              => false
  }

  override def hashCode(): Int = {
    toBytes.hashCode()
  }

  override def toString: String = {
    val formattedValue = new DecimalFormat("#.######").format(value)
    s"$formattedValue $unit"
  }
}

object DataSize {
  private val SizePattern: Regex = """^\s*(\d+(?:\.\d+)?)\s*([a-zA-Z]*)?\s*$""".r

  def apply(value: Double, unit: DataUnit): DataSize = new DataSize(value, unit)

  def apply(value: Int): DataSize = DataSize(value.toDouble, DataUnit.Bytes)

  def parse(size: String): DataSize = {
    size match {
      case SizePattern(valueStr, unitStr) =>
        val value = valueStr.toDouble
        val unit = DataUnit.parse(Option(unitStr).getOrElse(""))
        new DataSize(value, unit)
      case _ => throw new IllegalArgumentException(s"Not parseable as a data size: $size")
    }
  }
}

trait Rangeable[A] {
  def parse(s: String): A
  def format(a: A): String
}

object Rangeable {
  implicit val intRangeable: Rangeable[Int] = new Rangeable[Int] {
    def parse(s: String): Int = s.toInt
    def format(i: Int): String = i.toString
  }
}

sealed trait Range[+A] {
  def min: Option[A]
  def max: Option[A]
  def contains[B >: A](value: B)(implicit ordering: Ordering[B]): Boolean
}

object Range {
  case object Default extends Range[Nothing] {
    override def min: Option[Nothing] = None
    override def max: Option[Nothing] = None
    override def contains[B >: Nothing](value: B)(implicit ordering: Ordering[B]): Boolean = true
    override def toString: String = "*"
  }

  final case class Inclusive[A](start: A, end: A)(implicit rangeable: Rangeable[A]) extends Range[A] {
    override def min: Option[A] = Some(start)
    override def max: Option[A] = Some(end)
    override def contains[B >: A](value: B)(implicit ordering: Ordering[B]): Boolean =
      ordering.gteq(value, start) && ordering.lteq(value, end)
    override def toString: String = s"${rangeable.format(start)}-${rangeable.format(end)}"
  }

  final case class Single[A](value: A)(implicit rangeable: Rangeable[A]) extends Range[A] {
    override def min: Option[A] = Some(value)
    override def max: Option[A] = Some(value)
    override def contains[B >: A](value: B)(implicit ordering: Ordering[B]): Boolean =
      ordering.equiv(this.value, value)
    override def toString: String = rangeable.format(value)
  }

  final case class Multi[A: Rangeable: Ordering](ranges: Seq[Range[A]]) extends Range[A] {
    override def min: Option[A] = ranges.flatMap(_.min).minOption
    override def max: Option[A] = ranges.flatMap(_.max).maxOption
    override def contains[B >: A](value: B)(implicit ordering: Ordering[B]): Boolean = ranges.exists(_.contains(value))
    override def toString: String = ranges.mkString(",")
  }

  def parse[A: Ordering](s: String)(implicit rangeable: Rangeable[A]): Range[A] = {
    s match {
      case "*" => Range.Default
      case values =>
        val rangeStrings = values.split(',').map(_.trim)
        val ranges = rangeStrings.map {
          case "*" => throw new IllegalArgumentException("Multi range cannot contain Default (*)")
          case range =>
            range.split('-') match {
              case Array(start, end) => Range.Inclusive(rangeable.parse(start.trim), rangeable.parse(end.trim))
              case Array(single)     => Range.Single(rangeable.parse(single.trim))
              case _                 => throw new IllegalArgumentException(s"Cannot parse [$s] as a range")
            }
        }
        if (ranges.length == 1) ranges.head else Range.Multi(ranges.toSeq)
    }
  }

  def ordering[A](implicit ordering: Ordering[A]): Ordering[Range[A]] =
    new Ordering[Range[A]] {
      def compare(x: Range[A], y: Range[A]): Int = {
        (x.min, y.min) match {
          case (None, None)       => 0
          case (None, _)          => 1 // default last
          case (_, None)          => -1 // default last
          case (Some(a), Some(b)) => ordering.compare(a, b)
        }
      }
    }
}

final class RangeMap[A: Rangeable: Ordering, B](val ranges: Seq[(Range[A], B)]) extends Function[A, B] {
  private val orderedRanges = ranges.sortBy(_._1)(Range.ordering)

  def get(key: A): Option[B] =
    orderedRanges.collectFirst { case (range, value) if range.contains(key) => value }

  def apply(key: A): B =
    get(key).getOrElse(throw new IllegalArgumentException(s"No range defined for [$key]"))

  def mapValues[C](f: B => C): RangeMap[A, C] =
    new RangeMap(ranges.map { case (range, value) => (range, f(value)) })

  override def equals(other: Any): Boolean = other match {
    case that: RangeMap[_, _] =>
      if (this.ranges.size != that.ranges.size) false
      else {
        this.orderedRanges.zip(that.orderedRanges).forall { case ((range1, value1), (range2, value2)) =>
          range1 == range2 && value1 == value2
        }
      }
    case _ => false
  }

  override def hashCode(): Int = {
    orderedRanges
      .map { case (range, value) =>
        41 * range.hashCode() + value.hashCode()
      }
      .foldLeft(0)(_ * 41 + _)
  }

  override def toString: String = {
    orderedRanges.map { case (range, value) => s"\"${range.toString}\" -> $value" }.mkString("RangeMap(", ", ", ")")
  }
}

object RangeMap {
  def apply[A: Rangeable: Ordering, B](mappings: (Range[A], B)*): RangeMap[A, B] =
    new RangeMap(mappings)

  def fromStrings[A: Rangeable: Ordering, B](mappings: (String, B)*): RangeMap[A, B] =
    new RangeMap(mappings.map { case (rangeStr, value) => (Range.parse[A](rangeStr), value) })
}
