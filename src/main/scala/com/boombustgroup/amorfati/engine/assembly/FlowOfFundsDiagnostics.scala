package com.boombustgroup.amorfati.engine.assembly

import com.boombustgroup.amorfati.agents.Firm
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Flow-of-funds diagnostics derived at the post-month boundary. */
object FlowOfFundsDiagnostics:

  /** Total firm revenue minus adjusted demand. Both sides use the same PLN path
    * to avoid mixed-representation rounding mismatch.
    */
  def residual(in: WorldAssemblyEconomics.StepInput)(using p: SimParams): PLN =
    val nSectors     = p.sectorDefs.length
    val sectorCapPln = Array.fill[PLN](nSectors)(PLN.Zero)
    var firmIndex    = 0
    while firmIndex < in.s2.living.length do
      val firm        = in.s2.living(firmIndex)
      val sectorIndex = firm.sector.toInt
      if sectorIndex >= 0 && sectorIndex < nSectors then
        sectorCapPln(sectorIndex) = sectorCapPln(sectorIndex) + Firm.computeEffectiveCapacity(firm, in.w.real.productivityIndex)
      firmIndex += 1

    val priceMult      = in.w.priceLevel.toMultiplier
    var totalFirmRev   = PLN.Zero
    var adjustedDemand = PLN.Zero
    var sectorIndex    = 0
    while sectorIndex < nSectors do
      val sectorMult = in.s4.sectorMults(sectorIndex)
      totalFirmRev = totalFirmRev + (sectorCapPln(sectorIndex) * sectorMult * priceMult)
      adjustedDemand = adjustedDemand + (in.s4.sectorCapReal(sectorIndex) * sectorMult * priceMult)
      sectorIndex += 1

    totalFirmRev - adjustedDemand
