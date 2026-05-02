package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.World
import com.boombustgroup.amorfati.engine.markets.FiscalRules
import com.boombustgroup.amorfati.types.*

/** Pure economic logic for aggregate demand formation — no state mutation, no
  * flows.
  *
  * Allocates household consumption, government purchases, investment, and
  * export demand across sectors via flow-of-funds weights. Excess demand in
  * capacity-constrained sectors spills over to sectors with slack.
  *
  * Extracted from DemandStep (Calculus vs Accounting split).
  */
object DemandEconomics:

  // ---- Calibration constants ----
  private val RealRateElasticity      = Scalar.decimal(2, 2)      // demand sensitivity to real interest rate gap
  private val PressureMaxBoost        = Multiplier.decimal(75, 2) // hiring signal can rise above 1.0, but only moderately
  private val PressureSaturationRate  = Scalar.decimal(125, 2)    // how quickly excess-demand pressure saturates above capacity
  private val PressureSignalSmoothing = Share.decimal(65, 2)      // persistent sector order-book pressure; avoids startup repricing spikes
  private val HiringSignalSmoothing   = Share.decimal(65, 2)      // persistence in sector hiring plans; avoids month-to-month whipsaw
  private val CrossSectorSpillover    = Share.decimal(65, 2)      // only part of unmet sector demand is substitutable across sectors

  case class Output(
      govPurchases: PLN,                        // total government purchases this month
      sectorMults: Vector[Multiplier],          // per-sector demand multiplier (0 = no demand, 1 = full capacity)
      sectorDemandPressure: Vector[Multiplier], // persistent demand/capacity pressure used for hiring and pricing
      sectorHiringSignal: Vector[Multiplier],   // smoothed sector hiring signal used by firm labor planning
      avgDemandMult: Multiplier,                // economy-wide average demand multiplier
      sectorCapReal: Vector[PLN],               // per-sector real production capacity before price-level repricing
      laggedInvestDemand: PLN,                  // lagged investment demand for deposit flow calculation
      fiscalRuleStatus: FiscalRules.RuleStatus, // fiscal rule compliance diagnostics
  )

  def compute(
      w: World,
      employed: Int,
      living: Vector[Firm.State],
      domesticCons: PLN,
  )(using p: SimParams): Output =
    val seedIn             = w.seedIn
    val rawGovPurchases    = computeGovPurchases(w, employed)
    val fiscalResult       = applyFiscalRules(w, employed, rawGovPurchases)
    val govPurchases       = fiscalResult.constrainedGovPurchases
    val sectorCapReal      = computeSectorCapacity(w, living)
    val sectorExports      = computeSectorExports(w)
    val laggedInvestDemand = computeLaggedInvestDemand(w)
    val sectorDemand       = computeSectorDemand(domesticCons, govPurchases, sectorExports, laggedInvestDemand)
    val rawPressure        = computeRawDemandPressure(sectorDemand, sectorCapReal, w.priceLevel)
    val sectorPressure     = smoothPressureSignal(seedIn.sectorDemandPressure, stabilizeDemandPressure(rawPressure))
    val sectorHiringSignal = smoothHiringSignal(seedIn.sectorHiringSignal, sectorPressure)
    val sectorMults        = applySpillover(rawPressure, sectorCapReal, w.priceLevel)
    val avgDemandMult      = computeAvgDemandMult(sectorMults, sectorCapReal, w)
    Output(govPurchases, sectorMults, sectorPressure, sectorHiringSignal, avgDemandMult, sectorCapReal, laggedInvestDemand, fiscalResult.status)

  /** Government purchases: base spending x price level plus a small automatic
    * stabilizer tied to labor-market slack. Revenue windfalls are not
    * mechanically recycled back into demand.
    */
  private def fiscalUnemploymentRate(w: World, employed: Int): Share =
    w.unemploymentRate(employed).max(w.seedIn.unemploymentRate)

  private def computeGovPurchases(w: World, employed: Int)(using p: SimParams): PLN =
    val unempRate = fiscalUnemploymentRate(w, employed)
    val unempGap  = (unempRate - p.monetary.nairu).max(Share.Zero)
    val stimulus  = p.fiscal.govBaseSpending * unempGap * p.fiscal.govAutoStabMult
    val priceIdx  = w.priceLevel.toMultiplier.max(Multiplier.One)
    val wageIdx   =
      if p.household.baseWage > PLN.Zero then w.householdMarket.marketWage.ratioTo(p.household.baseWage).toMultiplier.max(priceIdx)
      else priceIdx
    val costIdx   = priceIdx * (Share.One - p.fiscal.govWageIndexShare) + wageIdx * p.fiscal.govWageIndexShare
    val target    = p.fiscal.govBaseSpending * costIdx + stimulus
    target

  /** Apply fiscal rules to raw government purchases.
    *
    * `prevGovSpend` intentionally tracks only `GovState.domesticBudgetDemand`.
    * This excludes the separate domestic co-financing outlay and the total EU
    * project envelope, which are reported separately on `GovState`.
    */
  private def applyFiscalRules(w: World, employed: Int, rawTarget: PLN)(using p: SimParams): FiscalRules.Output =
    val prevGovSpend = w.gov.domesticBudgetDemand
    val unempRate    = fiscalUnemploymentRate(w, employed)
    val outputGap    = Coefficient((unempRate - p.monetary.nairu) / p.monetary.nairu)

    FiscalRules.constrain(
      FiscalRules.Input(
        rawGovPurchases = rawTarget,
        prevGovSpend = prevGovSpend,
        cumulativeDebt = w.gov.cumulativeDebt,
        monthlyGdp = w.cachedMonthlyGdpProxy,
        prevRevenue = w.gov.taxRevenue,
        prevDeficit = w.gov.deficit,
        inflation = w.inflation,
        outputGap = outputGap,
      ),
    )

  /** Per-sector real production capacity before repricing by CPI. */
  private def computeSectorCapacity(w: World, living: Vector[Firm.State])(using p: SimParams): Vector[PLN] =
    val caps = Array.fill(p.sectorDefs.length)(PLN.Zero)
    living.foreach: f =>
      val s = f.sector.toInt
      if 0 <= s && s < caps.length then caps(s) = caps(s) + Firm.computeEffectiveCapacity(f, w.real.productivityIndex)
      else
        throw IllegalArgumentException(
          s"Invalid sector id ${f.sector.toInt} for firm ${f.id.toInt}; expected 0 until ${caps.length}",
        )
    caps.toVector

  /** Per-sector export demand: from GVC foreign firms when enabled, otherwise
    * from lagged aggregate exports split by fixed shares. Falls back to
    * aggregate split when GVC sector exports are zero (init month).
    */
  private def computeSectorExports(w: World)(using p: SimParams): Vector[PLN] =
    val gvcExports = w.external.gvc.sectorExports
    if gvcExports.exists(_ > PLN.Zero) then gvcExports
    else p.fiscal.fofExportShares.map(_ * w.forex.exports)

  /** Lagged domestic investment demand (net of import content).
    *
    * Private firm investment is known only after the previous firm step. EU
    * project capital is also lagged here because the current-month EU envelope
    * is computed later in the price/equity stage.
    */
  private def computeLaggedInvestDemand(w: World)(using p: SimParams): PLN =
    w.real.grossInvestment * (Share.One - p.capital.importShare) +
      w.real.aggGreenInvestment * (Share.One - p.climate.greenImportShare) +
      w.gov.euProjectCapital * (Share.One - p.capital.importShare)

  /** Per-sector total demand: consumption + gov purchases + investment +
    * exports, allocated via flow-of-funds weights.
    */
  private def computeSectorDemand(
      domesticCons: PLN,
      govPurchases: PLN,
      sectorExports: Vector[PLN],
      laggedInvestDemand: PLN,
  )(using p: SimParams): Vector[PLN] =
    (0 until p.sectorDefs.length)
      .map: s =>
        p.fiscal.fofConsWeights(s) * domesticCons +
          p.fiscal.fofGovWeights(s) * govPurchases +
          p.fiscal.fofInvestWeights(s) * laggedInvestDemand +
          sectorExports(s)
      .toVector

  /** Redistribute substitutable excess demand from capacity-constrained sectors
    * to sectors with slack. Sectors above capacity are capped at 1.0; only the
    * calibrated substitutable share of their excess flows proportionally into
    * below-capacity sectors.
    */
  private def computeRawDemandPressure(
      sectorDemand: Vector[PLN],
      sectorCapReal: Vector[PLN],
      priceLevel: PriceIndex,
  ): Vector[Multiplier] =
    sectorDemand.indices
      .map: s =>
        val nominalCap = sectorCapReal(s) * priceLevel.toMultiplier
        if nominalCap > PLN.Zero then sectorDemand(s).ratioTo(nominalCap).toMultiplier else Multiplier.Zero
      .toVector

  private def stabilizeDemandPressure(rawPressure: Vector[Multiplier]): Vector[Multiplier] =
    rawPressure.map(stabilizedPressure)

  private def smoothPressureSignal(prevSignal: Vector[Multiplier], currentSignal: Vector[Multiplier]): Vector[Multiplier] =
    currentSignal.indices
      .map: i =>
        val prev = prevSignal.lift(i).getOrElse(Multiplier.One)
        prev * PressureSignalSmoothing + currentSignal(i) * (Share.One - PressureSignalSmoothing)
      .toVector

  private def smoothHiringSignal(prevSignal: Vector[Multiplier], currentSignal: Vector[Multiplier]): Vector[Multiplier] =
    currentSignal.indices
      .map: i =>
        val prev = prevSignal.lift(i).getOrElse(Multiplier.One)
        prev * HiringSignalSmoothing + currentSignal(i) * (Share.One - HiringSignalSmoothing)
      .toVector

  private def stabilizedPressure(raw: Multiplier): Multiplier =
    if raw <= Multiplier.One then raw
    else
      val excess = raw.deviationFromOne.max(Coefficient.Zero)
      Multiplier.One + (PressureMaxBoost * (Multiplier.One - (-(excess.toScalar * PressureSaturationRate)).toCoefficient.exp))

  private[amorfati] def applySpillover(
      rawMults: Vector[Multiplier],
      sectorCapReal: Vector[PLN],
      priceLevel: PriceIndex,
  )(using p: SimParams): Vector[Multiplier] =
    require(
      p.io.matrix.length == rawMults.length && p.io.matrix.forall(_.length == rawMults.length),
      s"DemandEconomics.applySpillover requires io.matrix to match ${rawMults.length} sector multipliers, got ${p.io.matrix.length} rows",
    )
    val nominalCapBySector = sectorCapReal.map(_ * priceLevel.toMultiplier)
    val slackCapacity      = rawMults.indices
      .map: s =>
        if rawMults(s) < Multiplier.One then nominalCapBySector(s) * (Multiplier.One - rawMults(s)).toCoefficient
        else PLN.Zero
      .toVector
    val spilloverAdd       = Array.fill(rawMults.length)(PLN.Zero)

    rawMults.indices.foreach: from =>
      val fromCap = nominalCapBySector(from)
      if fromCap > PLN.Zero && rawMults(from) > Multiplier.One then
        val excessDemand        = fromCap * rawMults(from).deviationFromOne
        val substitutableExcess = excessDemand * CrossSectorSpillover
        val weights             = rawMults.indices.map: to =>
          if to == from || slackCapacity(to) <= PLN.Zero then PLN.Zero
          else slackCapacity(to) * spilloverCompatibility(from, to)
        val totalWeight         = weights.foldLeft(PLN.Zero)(_ + _)
        if totalWeight > PLN.Zero then
          rawMults.indices.foreach: to =>
            val weight = weights(to)
            if weight > PLN.Zero then spilloverAdd(to) = spilloverAdd(to) + substitutableExcess * weight.ratioTo(totalWeight).toShare

    rawMults.indices
      .map: s =>
        if rawMults(s) > Multiplier.One then Multiplier.One
        else
          val cappedAdd = if spilloverAdd(s) < slackCapacity(s) then spilloverAdd(s) else slackCapacity(s)
          val addMult   =
            if nominalCapBySector(s) > PLN.Zero then cappedAdd.ratioTo(nominalCapBySector(s)).toMultiplier
            else Multiplier.Zero
          (rawMults(s) + addMult).min(Multiplier.One)
      .toVector

  private def spilloverCompatibility(from: Int, to: Int)(using p: SimParams): Share =
    (p.io.matrix(from)(to) + p.io.matrix(to)(from)).clamp(Share.Zero, Share.One)

  /** Economy-wide average demand multiplier, adjusted for real rate effect when
    * expectations mechanism is active.
    *
    * Uses post-spillover sector multipliers (capped at 1.0 per sector) weighted
    * by sector capacity — consistent with the demand firms actually see.
    */
  private def computeAvgDemandMult(
      sectorMults: Vector[Multiplier],
      sectorCapReal: Vector[PLN],
      w: World,
  ): Multiplier =
    val totalCapacity = sectorCapReal.foldLeft(PLN.Zero)(_ + _)
    val baseMult      =
      if totalCapacity > PLN.Zero then
        sectorMults.indices.foldLeft(PLN.Zero): (acc, s) =>
          acc + (sectorCapReal(s) * sectorMults(s))
      else PLN.Zero
    val weightedBase  =
      if totalCapacity > PLN.Zero then baseMult.ratioTo(totalCapacity).toMultiplier
      else Multiplier.One
    val realRateAdj   = ((w.nbp.referenceRate - w.mechanisms.expectations.expectedInflation).toScalar * RealRateElasticity).toCoefficient
    (Coefficient.One + weightedBase.deviationFromOne - realRateAdj).toMultiplier
