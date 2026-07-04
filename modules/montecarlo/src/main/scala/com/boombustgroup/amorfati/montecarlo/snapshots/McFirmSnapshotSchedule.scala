package com.boombustgroup.amorfati.montecarlo.snapshots

import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth

/** Optional firm-level microdata export schedule for Monte Carlo runs. */
sealed trait McFirmSnapshotSchedule:
  def enabled: Boolean
  def includes(month: ExecutionMonth, terminalMonth: ExecutionMonth): Boolean

object McFirmSnapshotSchedule:
  case object Disabled extends McFirmSnapshotSchedule:
    override val enabled: Boolean                                                        = false
    override def includes(month: ExecutionMonth, terminalMonth: ExecutionMonth): Boolean = false

  case object TerminalOnly extends McFirmSnapshotSchedule:
    override val enabled: Boolean                                                        = true
    override def includes(month: ExecutionMonth, terminalMonth: ExecutionMonth): Boolean =
      month == terminalMonth

  final case class EveryNMonths(months: Int) extends McFirmSnapshotSchedule:
    require(months > 0, s"firm snapshot cadence must be > 0 months, got $months")
    override val enabled: Boolean                                                        = true
    override def includes(month: ExecutionMonth, terminalMonth: ExecutionMonth): Boolean =
      month.toInt % months == 0

  final case class ExplicitMonths(months: Set[Int]) extends McFirmSnapshotSchedule:
    require(months.nonEmpty, "firm snapshot explicit month list must be non-empty")
    require(months.forall(_ >= 1), s"firm snapshot months must be >= 1, got ${months.toVector.sorted.mkString(",")}")
    override val enabled: Boolean                                                        = true
    override def includes(month: ExecutionMonth, terminalMonth: ExecutionMonth): Boolean =
      months.contains(month.toInt)
