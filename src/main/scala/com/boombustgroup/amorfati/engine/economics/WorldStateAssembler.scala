package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.*
import com.boombustgroup.amorfati.engine.markets.EquityMarket
import com.boombustgroup.amorfati.engine.mechanisms.SectoralMobility
import com.boombustgroup.amorfati.types.*

/** Constructs the post-stage World value before population transitions finish
  * the month.
  */
object WorldStateAssembler:

  def assemble(
      in: WorldAssemblyEconomics.StepInput,
      fofResidual: PLN,
      informal: WorldInformalEconomy.Result,
      observables: WorldObservables.Values,
  )(using p: SimParams): World =
    val productivityNext = in.w.real.productivityIndex * p.firm.productivityGrowth.monthly.growthMultiplier
    val social           = SocialState(
      jst = in.s9.newJst,
      zus = in.s2.newZus,
      nfz = in.s2.newNfz,
      ppk = in.s9.finalPpk,
      demographics = in.s2.newDemographics,
      earmarked = in.s2.newEarmarked,
    )
    val crossSectorHires = in.s5.postFirmCrossSectorHires + in.s3.hhAgg.crossSectorHires
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
        tourismSeasonalFactor = observables.tourismSeasonalFactor,
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
        etsPrice = observables.etsPrice,
        productivityIndex = productivityNext,
        automationRatio = in.s7.autoR,
        hybridRatio = in.s7.hybR,
      ),
      mechanisms = MechanismsState(
        macropru = in.s7.newMacropru,
        expectations = in.s8.monetary.newExp,
        bfgFundBalance = in.w.mechanisms.bfgFundBalance + in.s9.bfgLevy,
        informalCyclicalAdj = informal.cyclicalAdj,
        nextTaxShadowShare = informal.nextTaxShadowShare,
      ),
      plumbing = MonetaryPlumbingState(
        reserveInterestTotal = in.s8.banking.totalReserveInterest,
        standingFacilityNet = in.s8.banking.totalStandingFacilityIncome,
        interbankInterestNet = in.s8.banking.totalInterbankInterest,
        depositFacilityUsage = observables.depositFacilityUsage,
        fofResidual = fofResidual,
      ),
      pipeline = in.w.pipeline,
      flows = FlowStateAssembler.build(in, informal),
    )

  private def finalizeEquity(in: WorldAssemblyEconomics.StepInput): EquityMarket.State =
    in.s7.equityAfterForeignStock.copy(
      lastWealthEffect = PLN.Zero,
      lastDomesticDividends = in.s7.netDomesticDividends,
      lastForeignDividends = in.s7.foreignDividendOutflow,
      lastDividendTax = in.s7.dividendTax,
    )
