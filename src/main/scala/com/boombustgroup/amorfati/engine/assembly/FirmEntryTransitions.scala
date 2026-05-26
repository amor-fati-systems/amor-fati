package com.boombustgroup.amorfati.engine.assembly

import com.boombustgroup.amorfati.agents.{Firm, TechState}
import com.boombustgroup.amorfati.types.FirmId

/** Diagnostics for technology state transitions caused by firm entry. */
object FirmEntryTransitions:

  final case class AutomationEntryTransitions(newFullAi: Int, newHybrid: Int)

  def automationEntryTransitions(firms: Vector[Firm.State], newFirmIds: Set[FirmId]): AutomationEntryTransitions =
    val newFirms = firms.filter(firm => newFirmIds.contains(firm.id))
    AutomationEntryTransitions(
      newFullAi = newFirms.count(_.tech.isInstanceOf[TechState.Automated]),
      newHybrid = newFirms.count(_.tech.isInstanceOf[TechState.Hybrid]),
    )
