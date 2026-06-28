package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.engine.markets.{CalvoPricing, IntermediateMarket, LaborMarket}
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Market-facing stages after firm decisions and financing are fixed. */
private[firm] object FirmMarketStages:

  def intermediateMarket(
      firms: Vector[Firm.State],
      financialStocks: Vector[Firm.FinancialStocks],
      in: StepInput,
  )(using p: SimParams): IntermediateResult =
    val r              = IntermediateMarket.process(
      IntermediateMarket.Input(
        firms = firms,
        sectorMults = in.demand.sectorMults,
        price = in.w.priceLevel,
        ioMatrix = p.io.matrix,
        columnSums = p.io.columnSums,
        scale = p.io.scale,
        productivityIndex = in.w.real.productivityIndex,
      ),
    )
    val adjustedStocks = financialStocks
      .zip(r.cashAdjustments)
      .map: (stocks, cashAdjustment) =>
        stocks.copy(cash = stocks.cash + cashAdjustment)
    IntermediateResult(r.firms, adjustedStocks, r.totalPaid, r.effectiveCapacities)

  def calvoPricing(stepIn: StepInput, intermediate: IntermediateResult, rng: RandomStream)(using p: SimParams): PricingResult =
    val repriced = intermediate.firms.map: f =>
      val sectorPressure     = stepIn.demand.sectorDemandPressure(f.sector.toInt)
      val passthrough        =
        if f.stateOwned then StateOwned.effectiveEnergyPassthrough(f.sector.toInt)
        else Share.One
      val energyCostPressure =
        CalvoPricing.energyCostPressure(
          stepIn.w.external.gvc.commodityPriceIndex,
          p.climate.energyCostShares(f.sector.toInt),
          passthrough,
        )
      val calvo              = CalvoPricing.updateFirmMarkup(f.markup, sectorPressure, stepIn.labor.wageGrowth, energyCostPressure, rng)
      f.copy(markup = calvo.newMarkup)

    PricingResult(
      firms = repriced,
      markupInflation = CalvoPricing.aggregateMarkupInflationFromCapacities(repriced, intermediate.firms, intermediate.effectiveCapacities).annualize,
    )

  def laborMarket(
      ioFirms: Vector[Firm.State],
      in: StepInput,
      rng: RandomStream,
  )(using p: SimParams): LaborMarketResult =
    val afterSep       = LaborMarket.separations(in.householdIncome.updatedHouseholds, in.firms, ioFirms)
    val afterRemoval   = Immigration.removeReturnMigrants(afterSep, in.labor.newImmig.monthlyOutflow)
    val startId        = afterRemoval.map(_.id.toInt).maxOption.getOrElse(-1) + 1
    val newImmigrants  = Immigration.spawnImmigrantPopulation(in.labor.newImmig.monthlyInflow, startId, rng)
    val availableLabor = afterRemoval ++ newImmigrants.households
    val maxHires       = LaborMarket.monthlyMatchingCapacity(availableLabor, in.labor.newDemographics.workingAgePop)
    val employedBefore = availableLabor.count(_.status.isInstanceOf[HhStatus.Employed])
    val searchResult   = LaborMarket.jobSearch(
      availableLabor,
      ioFirms,
      in.labor.newWage,
      rng,
      in.labor.regionalWages,
      maxHires = Some(maxHires),
      priorityHouseholdIds = newImmigrants.households.map(_.id).toSet,
    )
    val postWages      = LaborMarket.updateWages(searchResult.households, ioFirms, in.labor.newWage)
    val hires          = Math.max(0, postWages.count(_.status.isInstanceOf[HhStatus.Employed]) - employedBefore)
    val newStocksById  = newImmigrants.households.zip(newImmigrants.financialStocks).map((household, stocks) => household.id -> stocks).toMap

    LaborMarketResult(postWages, searchResult.crossSectorHires, hires, maxHires, newStocksById)

  def staffedFirms(pricing: PricingResult, laborMarket: LaborMarketResult): Vector[Firm.State] =
    LaborMarket.syncFirmStaffing(pricing.firms, laborMarket.households)

  def defaultsAndInterest(
      preFirms: Vector[Firm.State],
      postFirms: Vector[Firm.State],
      openingFinancialStocks: Vector[Firm.FinancialStocks],
      closingFinancialStocks: Vector[Firm.FinancialStocks],
      ledgerFinancialState: LedgerFinancialState,
      lending: LendingConditions,
  )(using p: SimParams): NplResult =
    val prevAlive            = preFirms.filter(Firm.isAlive).map(_.id).toSet
    val newlyDead            = postFirms.filter(f => !Firm.isAlive(f) && prevAlive.contains(f.id))
    val closingStocksById    = postFirms.zip(closingFinancialStocks).map((firm, stocks) => firm.id -> stocks).toMap
    val openingStocksById    = preFirms.zip(openingFinancialStocks).map((firm, stocks) => firm.id -> stocks).toMap
    val nplNew               = newlyDead.flatMap(f => closingStocksById.get(f.id).map(_.firmLoan)).sumPln
    val nplLoss              = nplNew * (Share.One - p.banking.loanRecovery)
    val defaultedBondFirmIds = newlyDead.map(_.id).toSet
    val totalBondDefault     = CorporateBondOwnership.defaultedIssuerDebt(ledgerFinancialState.firms, defaultedBondFirmIds)

    val emptyPln  = Vector.fill(lending.nBanks)(PLN.Zero)
    val emptyInts = Vector.fill(lending.nBanks)(0)

    val perBankNplDebt = newlyDead.foldLeft(emptyPln): (acc, f) =>
      acc.updated(f.bankId.toInt, acc(f.bankId.toInt) + closingStocksById.get(f.id).fold(PLN.Zero)(_.firmLoan))

    val perBankIntIncome = preFirms
      .filter(Firm.isAlive)
      .foldLeft(emptyPln): (acc, f) =>
        val interest = openingStocksById.get(f.id).fold(PLN.Zero)(_.firmLoan) * lending.rates(f.bankId.toInt).monthly
        acc.updated(f.bankId.toInt, acc(f.bankId.toInt) + interest)

    val perBankWorkers = postFirms
      .filter(Firm.isAlive)
      .foldLeft(emptyInts): (acc, f) =>
        acc.updated(f.bankId.toInt, acc(f.bankId.toInt) + Firm.workerCount(f))

    NplResult(
      nplNew,
      nplLoss,
      totalBondDefault,
      newlyDead.length,
      perBankIntIncome.foldLeft(PLN.Zero)(_ + _),
      perBankNplDebt,
      perBankIntIncome,
      perBankWorkers,
      defaultedBondFirmIds,
    )

  def issuerDebtSettlement(bonded: BondAbsorptionResult, npl: NplResult): LedgerFinancialState =
    bonded.ledgerFinancialState.copy(
      firms = CorporateBondOwnership.clearDefaultedIssuerDebt(bonded.ledgerFinancialState.firms, npl.defaultedBondFirmIds),
    )
