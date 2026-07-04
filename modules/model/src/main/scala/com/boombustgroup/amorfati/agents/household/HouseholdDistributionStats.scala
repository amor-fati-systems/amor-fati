package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.{HhStatus, Household}
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.types.*

/** Distribution statistics and sector-mobility metrics for household
  * aggregates.
  */
private[agents] object HouseholdDistributionStats:

  private inline def scaledDivRaw(numerator: BigInt, denominator: BigInt): Long =
    if denominator == 0 then 0L
    else
      val scaled         = numerator * BigInt(FixedPointBase.Scale)
      val quotient       = scaled / denominator
      val remainder      = (scaled % denominator).abs
      val denominatorAbs = denominator.abs
      val twiceRemainder = remainder * 2
      if twiceRemainder < denominatorAbs then quotient.toLong
      else
        val resultSign = scaled.signum * denominator.signum
        if twiceRemainder > denominatorAbs then (quotient + resultSign).toLong
        else if quotient % 2 == 0 then quotient.toLong
        else (quotient + resultSign).toLong

  /** Computes a Gini coefficient for a pre-sorted array, shifting negatives if
    * needed.
    */
  def giniSorted(sorted: Array[Long]): Share =
    try giniSortedLong(sorted)
    catch case _: ArithmeticException => giniSortedBig(sorted)

  private def giniSortedLong(sorted: Array[Long]): Share =
    val n           = sorted.length
    if n <= 1 then return Share.Zero
    val minVal      = sorted(0)
    if minVal == Long.MinValue then throw new ArithmeticException("gini shift overflows Long")
    val shift       = if minVal < 0L then -minVal else 0L
    var total       = 0L
    var weightedSum = 0L
    var i           = 0
    while i < n do
      val v      = java.lang.Math.addExact(sorted(i), shift)
      total = java.lang.Math.addExact(total, v)
      val weight = 2L * (i.toLong + 1L) - n.toLong - 1L
      weightedSum = java.lang.Math.addExact(weightedSum, java.lang.Math.multiplyExact(weight, v))
      i += 1
    if total <= 0L then Share.Zero
    else Share.fromRaw(FixedPointBase.ratioRaw(weightedSum, java.lang.Math.multiplyExact(n.toLong, total)))

  private def giniSortedBig(sorted: Array[Long]): Share =
    val n           = sorted.length
    if n <= 1 then return Share.Zero
    val minVal      = sorted(0)
    val shift       = if minVal < 0L then BigInt(minVal).abs else BigInt(0)
    var total       = BigInt(0)
    var weightedSum = BigInt(0)
    var i           = 0
    while i < n do
      val v = BigInt(sorted(i)) + shift
      total += v
      weightedSum += BigInt(2L * (i.toLong + 1L) - n.toLong - 1L) * v
      i += 1
    if total <= 0 then Share.Zero else Share.fromRaw(scaledDivRaw(weightedSum, BigInt(n) * total))

  /** Counts sorted elements strictly below the threshold. */
  def lowerBound(sorted: Array[Long], threshold: Long): Int =
    var lo = 0
    var hi = sorted.length
    while lo < hi do
      val mid = (lo + hi) >>> 1
      if sorted(mid) < threshold then lo = mid + 1
      else hi = mid
    lo

  /** Computes the share of employed households whose sector changed. */
  def sectorMobilityRate(updated: Vector[Household.State]): Share =
    var employed    = 0
    var crossSector = 0
    var i           = 0
    while i < updated.length do
      val hh = updated(i)
      hh.status match
        case HhStatus.Employed(_, sec, _) =>
          employed += 1
          if hh.lastSectorIdx.toInt >= 0 && hh.lastSectorIdx != sec then crossSector += 1
        case _                            =>
      i += 1
    if employed == 0 then Share.Zero else Share.fraction(crossSector, employed)
