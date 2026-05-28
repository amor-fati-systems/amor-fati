package com.boombustgroup.amorfati.engine.assembly

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.*
import com.boombustgroup.amorfati.engine.ledger.BankReserveDiagnostics
import com.boombustgroup.amorfati.engine.markets.EquityMarket
import com.boombustgroup.amorfati.engine.mechanisms.{ClimatePolicy, PopulationLifecycleTransitions, SectoralMobility, TourismSeasonality}
import com.boombustgroup.amorfati.types.*

/** Constructs the realized month-`t` World from closing inputs and completed
  * population lifecycle transitions.
  */
object WorldStateAssembler:

  private[assembly] def assemble(
      closingInput: MonthClosingInput,
      lifecycle: PopulationLifecycleTransitions.Result,
  )(using p: SimParams): World =
    val in                    = closingInput.execution
    val informal              = closingInput.mechanisms.informalEconomy
    val diagnostics           = closingInput.diagnostics
    val elapsedMonths         = in.fiscal.m.previousCompleted.toInt
    val tourismSeasonalFactor = TourismSeasonality.factor(in.fiscal.m.monthInYear, p.tourism.peakMonth, p.tourism.seasonality)
    val depositFacilityUsage  = BankReserveDiagnostics.depositFacilityUsage(in.banking.banks, in.banking.ledgerFinancialState)
    val productivityNext      = in.openingWorld.real.productivityIndex * p.firm.productivityGrowth.monthly.growthMultiplier
    val social                = SocialState(
      jst = in.banking.newJst,
      zus = in.labor.newZus,
      nfz = in.labor.newNfz,
      ppk = in.banking.finalPpk,
      demographics = in.labor.newDemographics,
      earmarked = in.labor.newEarmarked,
    )
    val crossSectorHires      = in.firm.postFirmCrossSectorHires + in.householdIncome.hhAgg.crossSectorHires + lifecycle.crossSectorHires
    World(
      inflation = in.priceEquity.newInfl,
      priceLevel = in.priceEquity.newPrice,
      currentSigmas = in.priceEquity.newSigmas,
      gov = in.banking.newGovWithYield.copy(
        policy = in.banking.newGovWithYield.policy.copy(
          minWageLevel = in.fiscal.baseMinWage,
          minWagePriceLevel = in.fiscal.updatedMinWagePriceLevel,
        ),
      ),
      nbp = in.banking.finalNbp,
      bankingSector = in.banking.bankingMarket,
      forex = in.openEconomy.external.newForex,
      bop = in.openEconomy.external.newBop,
      householdMarket = HouseholdMarketState.fromAggregates(in.banking.finalHhAgg),
      social = social,
      financialMarkets = FinancialMarketsState(
        equity = finalizeEquity(in),
        corporateBonds = in.openEconomy.corpBonds.newCorpBonds,
        insurance = in.banking.finalInsurance,
        nbfi = in.banking.finalNbfi,
        quasiFiscal = in.banking.newQuasiFiscal,
      ),
      external = ExternalState(
        gvc = in.openEconomy.external.newGvc,
        immigration = in.labor.newImmig,
        tourismSeasonalFactor = tourismSeasonalFactor,
      ),
      real = RealState(
        housing = in.banking.housingAfterFlows,
        sectoralMobility = SectoralMobility.State(
          crossSectorHires = crossSectorHires,
          voluntaryQuits = in.householdIncome.hhAgg.voluntaryQuits,
          sectorMobilityRate = SectoralMobility.mobilityRate(crossSectorHires, lifecycle.householdAggregates.employed),
        ),
        grossInvestment = in.firm.sumGrossInvestment,
        aggGreenInvestment = in.firm.sumGreenInvestment,
        aggGreenCapital = in.priceEquity.aggGreenCapital,
        etsPrice = ClimatePolicy.etsPrice(elapsedMonths),
        productivityIndex = productivityNext,
        automationRatio = in.priceEquity.autoR,
        hybridRatio = in.priceEquity.hybR,
      ),
      mechanisms = MechanismsState(
        macropru = in.priceEquity.newMacropru,
        expectations = in.openEconomy.monetary.newExp,
        bfgFundBalance = in.openingWorld.mechanisms.bfgFundBalance + in.banking.bfgLevy,
        informalCyclicalAdj = informal.cyclicalAdj,
        nextTaxShadowShare = informal.nextTaxShadowShare,
      ),
      plumbing = MonetaryPlumbingState(
        reserveInterestTotal = in.openEconomy.banking.totalReserveInterest,
        standingFacilityNet = in.openEconomy.banking.totalStandingFacilityIncome,
        interbankInterestNet = in.openEconomy.banking.totalInterbankInterest,
        depositFacilityUsage = depositFacilityUsage,
        fofResidual = diagnostics.flowOfFundsResidual,
      ),
      pipeline = in.openingWorld.pipeline.withSameMonthDiagnostics(
        operationalHiringSlack = in.labor.operationalHiringSlack,
        fiscalRuleSeverity = in.demand.fiscalRuleStatus.bindingRule,
        govSpendingCutRatio = in.demand.fiscalRuleStatus.spendingCutRatio,
      ),
      flows = FlowStateAssembler.build(closingInput, lifecycle),
      regionalWages = in.labor.regionalWages,
    )

  private def finalizeEquity(in: MonthExecution): EquityMarket.State =
    in.priceEquity.equityAfterForeignStock.copy(
      lastWealthEffect = PLN.Zero,
      lastDomesticDividends = in.priceEquity.netDomesticDividends,
      lastForeignDividends = in.priceEquity.foreignDividendOutflow,
      lastDividendTax = in.priceEquity.dividendTax,
    )
