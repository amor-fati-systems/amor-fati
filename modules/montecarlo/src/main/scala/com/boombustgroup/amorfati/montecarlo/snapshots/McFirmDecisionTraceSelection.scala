package com.boombustgroup.amorfati.montecarlo.snapshots

/** Optional firm decision trace selector for Monte Carlo runs.
  *
  * Selection is by firm id and is evaluated before writing the trace export. It
  * does not sample through the simulation RNG and therefore cannot change model
  * decisions.
  */
sealed trait McFirmDecisionTraceSelection:
  def enabled: Boolean
  def includes(firmId: Int): Boolean

object McFirmDecisionTraceSelection:
  case object Disabled extends McFirmDecisionTraceSelection:
    override val enabled: Boolean               = false
    override def includes(firmId: Int): Boolean = false

  case object All extends McFirmDecisionTraceSelection:
    override val enabled: Boolean               = true
    override def includes(firmId: Int): Boolean = true

  final case class ExplicitFirmIds(firmIds: Set[Int]) extends McFirmDecisionTraceSelection:
    require(firmIds.nonEmpty, "firm decision trace explicit firm id list must be non-empty")
    require(firmIds.forall(_ >= 0), s"firm decision trace firm ids must be >= 0, got ${firmIds.toVector.sorted.mkString(",")}")
    override val enabled: Boolean               = true
    override def includes(firmId: Int): Boolean = firmIds.contains(firmId)

  final case class FirstN(count: Int) extends McFirmDecisionTraceSelection:
    require(count > 0, s"firm decision trace first:N count must be > 0, got $count")
    override val enabled: Boolean               = true
    override def includes(firmId: Int): Boolean = firmId >= 0 && firmId < count
