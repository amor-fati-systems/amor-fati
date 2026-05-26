package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.agents.Firm
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Flow-of-funds diagnostics derived at the post-month boundary. */
object FlowOfFundsDiagnostics:

  /** Total firm revenue minus adjusted demand. Both sides use the same PLN path
    * to avoid mixed-representation rounding mismatch.
    */
  def residual(in: WorldAssemblyEconomics.StepInput)(using p: SimParams): PLN =
    val nSectors       = p.sectorDefs.length
    val sectorCapPln   = (0 until nSectors).map: s =>
      in.s2.living
        .filter(_.sector.toInt == s)
        .foldLeft(PLN.Zero): (acc, f) =>
          acc + Firm.computeEffectiveCapacity(f, in.w.real.productivityIndex)
    val priceMult      = in.w.priceLevel.toMultiplier
    val totalFirmRev   = (0 until nSectors).foldLeft(PLN.Zero): (acc, s) =>
      acc + (sectorCapPln(s) * in.s4.sectorMults(s) * priceMult)
    val adjustedDemand = (0 until nSectors).foldLeft(PLN.Zero): (acc, s) =>
      acc + (in.s4.sectorCapReal(s) * in.s4.sectorMults(s) * priceMult)
    totalFirmRev - adjustedDemand
