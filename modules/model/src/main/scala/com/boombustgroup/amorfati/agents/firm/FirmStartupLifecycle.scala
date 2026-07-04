package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.agents.Firm
import com.boombustgroup.amorfati.agents.Firm.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.amorfati.agents.firm.FirmCalibration.*

/** Startup lifecycle helpers shared by decisions, P&L, and execution.
  *
  * Keeping these rules out of the decision engine prevents startup runway and
  * ramp-up semantics from leaking into unrelated firm modules.
  */
private[agents] object FirmStartupLifecycle:

  /** Maximum temporary cash shortfall tolerated for a startup still in runway.
    */
  def startupRunwayLimit(firm: State)(using p: SimParams): PLN =
    if !Firm.isInStartup(firm) then PLN.Zero
    else
      val remainingShare = Share.fraction(firm.startupMonthsLeft, StartupRunwayMonths).clamp(Share.Zero, Share.One)
      p.firm.entryStartupCash * (StartupRunwayCashShare * remainingShare)

  /** Operating-cost ramp multiplier for firms still absorbing startup setup. */
  def startupCostMultiplier(firm: State): Multiplier =
    if !Firm.isInStartup(firm) then Multiplier.One
    else StartupCostFloor + (Multiplier.One - StartupCostFloor) * startupProgress(firm).toMultiplier

  /** Advances the one-month startup ramp-down counter for living firms. */
  def advanceStartupLifecycle(firm: State): State =
    if Firm.isAlive(firm) && firm.startupMonthsLeft > 0 then firm.copy(startupMonthsLeft = firm.startupMonthsLeft - 1)
    else firm

  private def startupProgress(firm: State): Share =
    if !Firm.isInStartup(firm) then Share.One
    else Share.One - Share.fraction(firm.startupMonthsLeft, StartupRunwayMonths).clamp(Share.Zero, Share.One)
