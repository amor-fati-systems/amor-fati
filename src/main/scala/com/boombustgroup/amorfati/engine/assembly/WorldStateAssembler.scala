package com.boombustgroup.amorfati.engine.assembly

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.*
import com.boombustgroup.amorfati.engine.ledger.BankReserveDiagnostics
import com.boombustgroup.amorfati.engine.markets.EquityMarket
import com.boombustgroup.amorfati.engine.mechanisms.{ClimatePolicy, PopulationLifecycleTransitions, SectoralMobility, TourismSeasonality}
import com.boombustgroup.amorfati.types.*

/** Constructs the post-stage World value before population transitions finish
  * the month.
  */
object WorldStateAssembler:

  def assemble(context: WorldAssemblyEconomics.AssemblyContext)(using p: SimParams): World =
    val in                    = context.step
    val elapsedMonths         = in.s1.m.previousCompleted.toInt
    val tourismSeasonalFactor = TourismSeasonality.factor(in.s1.m.monthInYear, p.tourism.peakMonth, p.tourism.seasonality)
    val depositFacilityUsage  = BankReserveDiagnostics.depositFacilityUsage(in.s9.banks, in.s9.ledgerFinancialState)
    val productivityNext      = in.w.real.productivityIndex * p.firm.productivityGrowth.monthly.growthMultiplier
    val social                = SocialState(
      jst = in.s9.newJst,
      zus = in.s2.newZus,
      nfz = in.s2.newNfz,
      ppk = in.s9.finalPpk,
      demographics = in.s2.newDemographics,
      earmarked = in.s2.newEarmarked,
    )
    val crossSectorHires      = in.s5.postFirmCrossSectorHires + in.s3.hhAgg.crossSectorHires
    World(
      inflation = in.s7.newInfl,
      priceLevel = in.s7.newPrice,
      currentSigmas = in.s7.newSigmas,
      gov = in.s9.newGovWithYield.copy(
        policy = in.s9.newGovWithYield.policy.copy(
          minWageLevel = in.s1.baseMinWage,
          minWagePriceLevel = in.s1.updatedMinWagePriceLevel,
        ),
      ),
      nbp = in.s9.finalNbp,
      bankingSector = in.s9.bankingMarket,
      forex = in.s8.external.newForex,
      bop = in.s8.external.newBop,
      householdMarket = HouseholdMarketState.fromAggregates(in.s9.finalHhAgg),
      social = social,
      financialMarkets = FinancialMarketsState(
        equity = finalizeEquity(in),
        corporateBonds = in.s8.corpBonds.newCorpBonds,
        insurance = in.s9.finalInsurance,
        nbfi = in.s9.finalNbfi,
        quasiFiscal = in.s9.newQuasiFiscal,
      ),
      external = ExternalState(
        gvc = in.s8.external.newGvc,
        immigration = in.s2.newImmig,
        tourismSeasonalFactor = tourismSeasonalFactor,
      ),
      real = RealState(
        housing = in.s9.housingAfterFlows,
        sectoralMobility = SectoralMobility.State(
          crossSectorHires = crossSectorHires,
          voluntaryQuits = in.s3.hhAgg.voluntaryQuits,
          sectorMobilityRate = SectoralMobility.mobilityRate(crossSectorHires, in.s9.finalHhAgg.employed),
        ),
        grossInvestment = in.s5.sumGrossInvestment,
        aggGreenInvestment = in.s5.sumGreenInvestment,
        aggGreenCapital = in.s7.aggGreenCapital,
        etsPrice = ClimatePolicy.etsPrice(elapsedMonths),
        productivityIndex = productivityNext,
        automationRatio = in.s7.autoR,
        hybridRatio = in.s7.hybR,
      ),
      mechanisms = MechanismsState(
        macropru = in.s7.newMacropru,
        expectations = in.s8.monetary.newExp,
        bfgFundBalance = in.w.mechanisms.bfgFundBalance + in.s9.bfgLevy,
        informalCyclicalAdj = context.informal.cyclicalAdj,
        nextTaxShadowShare = context.informal.nextTaxShadowShare,
      ),
      plumbing = MonetaryPlumbingState(
        reserveInterestTotal = in.s8.banking.totalReserveInterest,
        standingFacilityNet = in.s8.banking.totalStandingFacilityIncome,
        interbankInterestNet = in.s8.banking.totalInterbankInterest,
        depositFacilityUsage = depositFacilityUsage,
        fofResidual = context.fofResidual,
      ),
      pipeline = in.w.pipeline,
      flows = FlowStateAssembler.build(context),
    )

  def withPopulationLifecycle(
      world: World,
      in: WorldAssemblyEconomics.StepInput,
      population: PopulationLifecycleTransitions.Result,
  ): World =
    val finalFlows            = world.flows.copy(
      firmBirths = population.births,
      firmDeaths = in.s5.firmDeaths,
      netFirmBirths = population.netBirths,
      automationNewFullAi = world.flows.automationNewFullAi + population.automationTransitions.newFullAi,
      automationNewHybrid = world.flows.automationNewHybrid + population.automationTransitions.newHybrid,
    )
    val finalCrossSectorHires = world.real.sectoralMobility.crossSectorHires + population.crossSectorHires
    val finalReal             = world.real.copy(
      sectoralMobility = world.real.sectoralMobility.copy(
        crossSectorHires = finalCrossSectorHires,
        sectorMobilityRate = SectoralMobility.mobilityRate(finalCrossSectorHires, population.householdAggregates.employed),
      ),
    )
    world.copy(
      pipeline = in.w.pipeline.withSameMonthDiagnostics(
        operationalHiringSlack = in.s2.operationalHiringSlack,
        fiscalRuleSeverity = in.s4.fiscalRuleStatus.bindingRule,
        govSpendingCutRatio = in.s4.fiscalRuleStatus.spendingCutRatio,
      ),
      flows = finalFlows,
      real = finalReal,
      regionalWages = in.s2.regionalWages,
    )

  private def finalizeEquity(in: WorldAssemblyEconomics.StepInput): EquityMarket.State =
    in.s7.equityAfterForeignStock.copy(
      lastWealthEffect = PLN.Zero,
      lastDomesticDividends = in.s7.netDomesticDividends,
      lastForeignDividends = in.s7.foreignDividendOutflow,
      lastDividendTax = in.s7.dividendTax,
    )
