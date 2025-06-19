/*
 * Copyright (C) 2020 - 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.testing.simulation

import scala.collection.BufferedIterator
import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.PriorityQueue

object IteratorExtensions {
  implicit class RichIterator[A](iterator: Iterator[A]) {
    def zipWithSeqNr: Iterator[(A, Int)] = {
      iterator.zipWithIndex.map { case (element, index) => (element, index + 1) }
    }
  }
}

final class MergedIterator[A](iterators: Seq[Iterator[A]])(implicit ordering: Ordering[A]) extends Iterator[A] {

  private val iteratorQueue =
    PriorityQueue.empty[BufferedIterator[A]](Ordering.by[BufferedIterator[A], A](_.head)(ordering.reverse))

  // initialize the queue with all non-empty iterators
  iterators.map(_.buffered).filter(_.hasNext).foreach(iterator => iteratorQueue.enqueue(iterator))

  def hasNext: Boolean = iteratorQueue.nonEmpty

  def next(): A = {
    if (!hasNext) throw new NoSuchElementException("next on empty iterator")

    val minIterator = iteratorQueue.dequeue()
    val result = minIterator.next()

    // re-insert the iterator if it still has elements
    if (minIterator.hasNext) iteratorQueue.enqueue(minIterator)

    result
  }
}

final class FlatMergedIterator[A](source: Iterator[Iterator[A]])(implicit ordering: Ordering[A]) extends Iterator[A] {
  private val bufferedSource = source.filter(_.hasNext).map(_.buffered).buffered

  private val iteratorQueue =
    PriorityQueue.empty[BufferedIterator[A]](Ordering.by[BufferedIterator[A], A](_.head)(ordering.reverse))

  private def mergeNewIterators(): Unit = {
    while (bufferedSource.hasNext &&
      (iteratorQueue.isEmpty || ordering.lteq(bufferedSource.head.head, iteratorQueue.head.head))) {
      iteratorQueue.enqueue(bufferedSource.next())
    }
  }

  def hasNext: Boolean = {
    mergeNewIterators()
    iteratorQueue.nonEmpty
  }

  def next(): A = {
    if (!hasNext) throw new NoSuchElementException("next on empty iterator")

    val minIterator = iteratorQueue.dequeue()
    val result = minIterator.next()

    // re-insert the iterator if it still has elements
    if (minIterator.hasNext) {
      iteratorQueue.enqueue(minIterator)
    }

    result
  }
}

final class GroupedIterator[A, B](iterator: Iterator[A], boundaries: Iterator[B], point: A => B)(implicit
    ordering: Ordering[B])
    extends Iterator[Seq[A]] {

  private val bufferedIterator = iterator.buffered

  override def hasNext: Boolean = bufferedIterator.hasNext

  override def next(): Seq[A] = {
    if (!hasNext) throw new NoSuchElementException("next on empty iterator")

    val group = ArrayBuffer.empty[A]
    val boundary = if (boundaries.hasNext) Some(boundaries.next()) else None

    while (bufferedIterator.hasNext && boundary.forall(ordering.lt(point(bufferedIterator.head), _))) {
      group += bufferedIterator.next()
    }

    group.toSeq
  }
}
