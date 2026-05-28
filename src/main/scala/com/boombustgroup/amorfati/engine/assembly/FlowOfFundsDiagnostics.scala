package com.boombustgroup.amorfati.engine.assembly

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.MonthExecution
import com.boombustgroup.amorfati.engine.economics.GdpAccounting
import com.boombustgroup.amorfati.types.*

/** Flow-of-funds diagnostics derived at the month-closing boundary. */
object FlowOfFundsDiagnostics:

  def residual(in: MonthExecution)(using p: SimParams): PLN =
    val realizedOutput    = GdpAccounting
      .realizedSectorOutputs(
        priceLevel = in.openingWorld.priceLevel,
        sectorCount = p.sectorDefs.length,
        firms = in.labor.living,
        sectorMultiplier = s => in.demand.sectorMults(s),
        capacityBoost = in.openingWorld.real.productivityIndex,
      )
      .sumPln
    val demandStageOutput = GdpAccounting
      .capacityWeightedSectorOutputs(
        priceLevel = in.openingWorld.priceLevel,
        sectorCapReal = in.demand.sectorCapReal,
        sectorMultiplier = s => in.demand.sectorMults(s),
      )
      .sumPln

    realizedOutput - demandStageOutput
