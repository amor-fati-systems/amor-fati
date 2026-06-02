package com.boombustgroup.amorfati.engine.closedmonth

import com.boombustgroup.amorfati.agents.Firm
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.MonthClosingStateInput
import com.boombustgroup.amorfati.engine.economics.GdpAccounting
import com.boombustgroup.amorfati.types.*

/** Flow-of-funds diagnostics derived at the month-closing boundary. */
object FlowOfFundsDiagnostics:

  final case class Input(
      priceLevel: PriceIndex,
      productivityIndex: Multiplier,
      livingFirms: Vector[Firm.State],
      sectorCapReal: Vector[PLN],
      sectorMults: Vector[Multiplier],
  )

  object Input:
    def fromClosingState(state: MonthClosingStateInput): Input =
      Input(
        priceLevel = state.openingWorld.priceLevel,
        productivityIndex = state.openingWorld.real.productivityIndex,
        livingFirms = state.labor.living,
        sectorCapReal = state.demand.sectorCapReal,
        sectorMults = state.demand.sectorMults,
      )

  def residual(in: Input)(using p: SimParams): PLN =
    val realizedOutput    = GdpAccounting
      .realizedSectorOutputs(
        priceLevel = in.priceLevel,
        sectorCount = p.sectorDefs.length,
        firms = in.livingFirms,
        sectorMultiplier = s => in.sectorMults(s),
        capacityBoost = in.productivityIndex,
      )
      .sumPln
    val demandStageOutput = GdpAccounting
      .capacityWeightedSectorOutputs(
        priceLevel = in.priceLevel,
        sectorCapReal = in.sectorCapReal,
        sectorMultiplier = s => in.sectorMults(s),
      )
      .sumPln

    realizedOutput - demandStageOutput
