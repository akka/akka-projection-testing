/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.simulation

import scala.collection.immutable.IntMap
import scala.concurrent.duration._

import akka.projection.testing.ConfigurablePersistentActor
import org.apache.commons.rng.UniformRandomProvider
import org.apache.commons.rng.simple.RandomSource

final case class Activity(id: ActivityId, entityId: EntityId, start: Point, duration: Point)

final case class Event(activity: Activity, seqNr: Int, point: Point, dataBytes: Int) {
  def time: Point = activity.start + point
  def identifier: String = s"${activity.id}-${activity.entityId}-$seqNr"
}

object Event {
  implicit val ordering: Ordering[Event] = Ordering.by[Event, Point](_.time)
}

object SimulationGenerator {
  def apply(settings: SimulationSettings): SimulationGenerator = {
    val (_, stageGenerators) =
      settings.stages.zipWithIndex.foldLeft((Duration.Zero, Seq.empty[StageGenerator])) {
        case ((previousEndTime, generators), (stageSettings, index)) =>
          val startTime = previousEndTime + stageSettings.delay.getOrElse(Duration.Zero)
          val endTime = startTime + stageSettings.duration
          val stageGenerator = StageGenerator(stageId = index + 1, startTime, stageSettings)
          (endTime, generators :+ stageGenerator)
      }
    new SimulationGenerator(stageGenerators)
  }
}

final class SimulationGenerator(generators: Seq[StageGenerator]) {
  def events(): Iterator[Event] = {
    new MergedIterator(generators.map(_.events()))
  }

  def groupedEvents(windowDuration: FiniteDuration): Iterator[Seq[Event]] = {
    val window = Point(windowDuration)
    val boundaries = Iterator.iterate(window)(_ + window)
    new GroupedIterator(events(), boundaries, (event: Event) => event.time)
  }
}

object StageGenerator {
  def apply(stageId: Int, start: FiniteDuration, settings: StageSettings): StageGenerator = {
    val generators = settings.generators.zipWithIndex.map { case (generatorSettings, index) =>
      Generator(stageId, generatorId = index + 1, generatorSettings, start, settings.duration)
    }
    new StageGenerator(generators)
  }
}

final class StageGenerator(generators: Seq[Generator]) {
  def events(): Iterator[Event] = {
    new MergedIterator(generators.map(_.events()))
  }
}

object Generator {
  def apply(
      stageId: Int,
      generatorId: Int,
      settings: GeneratorSettings,
      start: FiniteDuration,
      duration: FiniteDuration): Generator = {
    val baseRandom = RandomProvider(settings.random)
    val random = baseRandom.expand(stageId, generatorId)
    val activityGenerator =
      ActivityGenerator(stageId, generatorId, start, duration, settings.entityId, settings.activity, baseRandom)
    val eventSettings = EntityIdMap(settings.activity, _.event)
    new Generator(
      activityGenerator,
      activity => ActivityEventGenerator(activity, eventSettings, random.expand(activity.id.seqNr)))
  }
}

final class Generator(
    activityGenerator: ActivityGenerator,
    activityEventGenerator: Activity => ActivityEventGenerator) {

  def events(): Iterator[Event] = {
    val eventIterators = activityGenerator.activities().map(activity => activityEventGenerator(activity).events())
    val mergedEventIterator = new FlatMergedIterator[Event](eventIterators)
    mergedEventIterator.takeWhile(_.time < activityGenerator.maxPoint)
  }
}

object ActivityGenerator {
  def apply(
      stageId: Int,
      generatorId: Int,
      start: FiniteDuration,
      duration: FiniteDuration,
      entityIdSettings: EntityIdSettings,
      activitySettings: ActivitySettings,
      baseRandom: RandomProvider): ActivityGenerator = {
    // note: entity id's sampled with baseRandom (reproducible across stages and generators)
    val random = baseRandom.expand(stageId, generatorId)
    new ActivityGenerator(
      stageId,
      generatorId,
      PointProcess(activitySettings.frequency, start, duration, random),
      () => EntityIdSampler(entityIdSettings, baseRandom),
      () => EntityIdMap(activitySettings, perEntitySettings => ContinuousSampler(perEntitySettings.duration, random)))
  }
}

final class ActivityGenerator(
    stageId: Int,
    generatorId: Int,
    activityStart: PointProcess,
    createEntityIdSampler: () => EntityIdSampler,
    createDurationSamplers: () => EntityIdMap[ContinuousSampler]) {

  import IteratorExtensions._

  def maxPoint: Point = activityStart.max

  def activities(): Iterator[Activity] = {
    val entityIdSampler = createEntityIdSampler()
    val durationSampler = createDurationSamplers()
    activityStart.points().zipWithSeqNr.map { case (start, seqNr) =>
      val activityId = ActivityId(stageId, generatorId, seqNr)
      val entityId = entityIdSampler.sample()
      val duration = Point(durationSampler(entityId).sample())
      Activity(activityId, entityId, start, duration)
    }
  }
}

object ActivityEventGenerator {
  def apply(
      activity: Activity,
      eventSettings: EntityIdMap[EventSettings],
      random: RandomProvider): ActivityEventGenerator = {
    new ActivityEventGenerator(
      activity,
      PointProcess(eventSettings(activity.entityId).frequency, Point.Zero, activity.duration, random),
      () => DiscreteSampler(eventSettings(activity.entityId).dataSize, random))
  }
}

final class ActivityEventGenerator(
    activity: Activity,
    activityEvents: PointProcess,
    createEventDataSizeSampler: () => DiscreteSampler) {
  import IteratorExtensions._

  // note: always start with an event at the beginning of the activity
  def events(): Iterator[Event] = {
    val eventDataSize = createEventDataSizeSampler()
    Iterator.single(Point.Zero).concat(activityEvents.points()).zipWithSeqNr.map { case (point, seqNr) =>
      Event(activity, seqNr, point, eventDataSize.sample())
    }
  }
}

final case class ActivityId(stage: Int, generator: Int, seqNr: Int) {
  override def toString: String = s"$stage-$generator-$seqNr"
}

final case class EntityId(slice: Slice, entity: Int, suffix: String) {
  override def toString: String = s"${slice.value}-$entity-$suffix"
}

object EntityId {
  final case class IdSuffix(shift: Int, suffix: String, hash: Int)

  private val (lowSuffixes, highSuffixes) = {
    val chars = 'a' to 'z'
    val suffixes =
      for (c1 <- chars; c2 <- chars; c3 <- chars; suffix = s"$c1$c2$c3"; hash = suffix.hashCode)
        yield IdSuffix(hash % 1024, suffix, hash)
    val suffixesByShift = suffixes.groupBy(_.shift)
    val lowSuffixes = suffixesByShift.map { case (shift, suffixes) => shift -> suffixes.minBy(_.hash) }.to(IntMap)
    val highSuffixes = suffixesByShift.map { case (shift, suffixes) => shift -> suffixes.maxBy(_.hash) }.to(IntMap)
    (lowSuffixes, highSuffixes)
  }

  // Utility for generating deterministic entity ids that map to specific slices.
  // Does this by adding an appropriate suffix that will shift the hash of the persistence id to the desired slice.
  // For example, `EntityId.create(entityType = "test", slice = Slice(123), entity = 42)` will return "123-42-alz",
  // where `math.abs("test|123-42-alz".hashCode) % 1024` = 123.
  def create(entityType: String, slice: Slice, entity: Int): EntityId = {
    val baseEntityId = s"${slice.value}-$entity-"
    val basePersistenceId = s"$entityType|$baseEntityId"
    val baseHash = basePersistenceId.hashCode * 31 * 31 * 31 // suffixes are always 3 chars
    val baseSlice = math.abs(baseHash) % 1024
    val diff = (1024 + (slice.value - baseSlice)) % 1024
    val shift = if (baseHash < 0) (1024 - diff) % 1024 else diff
    val lowSuffix = lowSuffixes(shift)
    val shiftedHash = baseHash + lowSuffix.hash
    val entityId = if (differentSigns(baseHash, shiftedHash)) {
      // shifted from negative to positive, or positive to negative (overflowed)
      // need to use an "inverted" shift from the high suffixes
      val invertedShift = (shift + 2 * (1024 + (if (shiftedHash < 0) -slice.value else slice.value))) % 1024
      val highSuffix = highSuffixes(invertedShift)
      EntityId(slice, entity, highSuffix.suffix)
    } else {
      EntityId(slice, entity, lowSuffix.suffix)
    }
    assert(
      math.abs(s"$entityType|$entityId".hashCode) % 1024 == slice.value,
      s"Generated entity id [$entityType|$entityId] is not for slice [$slice]")
    entityId
  }

  private def differentSigns(a: Int, b: Int): Boolean = {
    (a >= 0 && b < 0) || (a < 0 && b >= 0)
  }
}

object EntityIdSampler {
  private val entityType = ConfigurablePersistentActor.Key.name

  def apply(settings: EntityIdSettings, random: RandomProvider): EntityIdSampler = {
    val entitySampler = DiscreteSampler(settings.entity, random)

    val slices = settings.slices.getOrElse(Range.Default)
    val sliceDistribution = settings.sliceDistribution.getOrElse(DistributionShape.Uniform)

    val sliceRange: IndexedSeq[Slice] = (slices match {
      case Range.Default               => (Slice.MinValue to Slice.MaxValue).map(Slice.apply)
      case Range.Inclusive(start, end) => (start.value to end.value).map(Slice.apply)
      case Range.Single(value)         => Seq(value)
      case Range.Multi(ranges) =>
        ranges.flatMap {
          case Range.Inclusive(start, end) => (start.value to end.value).map(Slice.apply)
          case Range.Single(value)         => Seq(value)
          case _                           => Seq.empty
        }
    }).toIndexedSeq

    val sliceIndexMapper = new DeterministicIndexMapper(0, sliceRange.size - 1, sliceDistribution, random)

    new EntityIdSampler(entityType, entitySampler, sliceRange, sliceIndexMapper)
  }
}

final class EntityIdSampler(
    entityType: String,
    entitySampler: DiscreteSampler,
    sliceRange: IndexedSeq[Slice],
    sliceIndexMapper: DeterministicIndexMapper) {

  def sample(): EntityId = {
    val entity = entitySampler.sample()
    val sliceIndex = sliceIndexMapper.map(entity)
    val slice = sliceRange(sliceIndex)
    EntityId.create(entityType, slice, entity)
  }
}

final class EntityIdMap[A](mapped: RangeMap[Slice, RangeMap[Int, A]]) {
  def apply(entityId: EntityId): A = mapped(entityId.slice)(entityId.entity)
}

object EntityIdMap {
  def apply[A](settings: ActivitySettings, mapToValue: ActivitySettings.PerEntitySettings => A): EntityIdMap[A] = {
    val mapped = settings.perSlice.mapValues { perSliceSettings =>
      perSliceSettings.perEntity.mapValues { perEntitySettings =>
        mapToValue(perEntitySettings)
      }
    }
    new EntityIdMap(mapped)
  }
}

object RandomProvider {

  def apply(settings: Option[RandomSettings]): RandomProvider = {
    new RandomProvider(algorithm(settings), seed(settings))
  }

  private def algorithm(settings: Option[RandomSettings]): String =
    settings.flatMap(_.algorithm).getOrElse("WELL_19937_C")

  private def seed(settings: Option[RandomSettings]): Seq[Long] =
    Seq(settings.flatMap(_.seed).getOrElse(System.currentTimeMillis()))
}

final class RandomProvider(val algorithm: String, val seed: Seq[Long]) {
  def create(): UniformRandomProvider = RandomSource.valueOf(algorithm).create(seed.toArray)
  def expand(seed: Long*): RandomProvider = new RandomProvider(algorithm, this.seed ++ seed)
}
