package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.agents.Firm
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Production-side GDP diagnostics.
  *
  * `MonthlyGdpProxy` is intentionally anchored to realized output, not desired
  * expenditure. Demand pressure can exceed capacity, but GDP should not count
  * unmet household or government demand as production.
  */
object GdpAccounting:

  private val PublicCapitalOutputElasticity = Coefficient.decimal(15, 2)

  def publicCapitalCapacityBoost(publicCapitalStock: PLN, baselinePublicCapitalStock: PLN, monthlyGdp: PLN): Multiplier =
    val annualGdp = monthlyGdp * 12
    if annualGdp <= PLN.Zero then Multiplier.One
    else
      val netPublicCapital = publicCapitalStock - baselinePublicCapitalStock
      val boost            = netPublicCapital.ratioTo(annualGdp).toCoefficient * PublicCapitalOutputElasticity
      (Multiplier.One + boost.toMultiplier).max(Multiplier.Zero)

  def realizedSectorOutputs(
      priceLevel: PriceIndex,
      sectorCount: Int,
      firms: Vector[Firm.State],
      sectorMultiplier: Int => Multiplier,
      capacityBoost: Multiplier = Multiplier.One,
  )(using p: SimParams): Vector[PLN] =
    val outputs = Array.fill(sectorCount)(PLN.Zero)
    var i       = 0
    while i < firms.length do
      val firm = firms(i)
      if Firm.isAlive(firm) then
        val idx = firm.sector.toInt
        if idx >= 0 && idx < sectorCount then outputs(idx) = outputs(idx) + Firm.computeCapacity(firm) * capacityBoost * sectorMultiplier(idx)
        else
          throw IllegalArgumentException(
            s"Invalid sector id ${firm.sector.toInt} for firm ${firm.id.toInt}; expected 0 until $sectorCount",
          )
      i += 1
    outputs.iterator.map(output => priceLevel * output).toVector

  def outputBasedMonthlyGdp(sectorOutputs: Vector[PLN], inventoryChange: PLN): PLN =
    (sectorOutputs.sumPln + inventoryChange).max(PLN.Zero)
