package com.boombustgroup.amorfati.montecarlo

import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth

/** Optional household-level microdata export schedule for Monte Carlo runs. */
sealed trait McHouseholdSnapshotSchedule:
  def enabled: Boolean
  def includes(month: ExecutionMonth, terminalMonth: ExecutionMonth): Boolean

object McHouseholdSnapshotSchedule:
  case object Disabled extends McHouseholdSnapshotSchedule:
    override val enabled: Boolean                                                        = false
    override def includes(month: ExecutionMonth, terminalMonth: ExecutionMonth): Boolean = false

  case object TerminalOnly extends McHouseholdSnapshotSchedule:
    override val enabled: Boolean                                                        = true
    override def includes(month: ExecutionMonth, terminalMonth: ExecutionMonth): Boolean =
      month == terminalMonth

  final case class EveryNMonths(months: Int) extends McHouseholdSnapshotSchedule:
    require(months > 0, s"household snapshot cadence must be > 0 months, got $months")
    override val enabled: Boolean                                                        = true
    override def includes(month: ExecutionMonth, terminalMonth: ExecutionMonth): Boolean =
      month.toInt % months == 0

  final case class ExplicitMonths(months: Set[Int]) extends McHouseholdSnapshotSchedule:
    require(months.nonEmpty, "household snapshot explicit month list must be non-empty")
    require(months.forall(_ >= 1), s"household snapshot months must be >= 1, got ${months.toVector.sorted.mkString(",")}")
    override val enabled: Boolean                                                        = true
    override def includes(month: ExecutionMonth, terminalMonth: ExecutionMonth): Boolean =
      months.contains(month.toInt)
