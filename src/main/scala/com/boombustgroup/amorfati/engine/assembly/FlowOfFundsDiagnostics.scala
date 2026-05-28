package com.boombustgroup.amorfati.engine.assembly

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.economics.GdpAccounting
import com.boombustgroup.amorfati.types.*

/** Flow-of-funds diagnostics derived at the post-month boundary. */
object FlowOfFundsDiagnostics:

  def residual(in: WorldAssemblyEconomics.StepInput)(using p: SimParams): PLN =
    val realizedOutput    = GdpAccounting
      .realizedSectorOutputs(
        priceLevel = in.w.priceLevel,
        sectorCount = p.sectorDefs.length,
        firms = in.s2.living,
        sectorMultiplier = s => in.s4.sectorMults(s),
        capacityBoost = in.w.real.productivityIndex,
      )
      .sumPln
    val demandStageOutput = GdpAccounting
      .capacityWeightedSectorOutputs(
        priceLevel = in.w.priceLevel,
        sectorCapReal = in.s4.sectorCapReal,
        sectorMultiplier = s => in.s4.sectorMults(s),
      )
      .sumPln

    realizedOutput - demandStageOutput
