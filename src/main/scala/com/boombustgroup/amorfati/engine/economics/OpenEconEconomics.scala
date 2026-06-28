package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.World
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.engine.markets.{CorporateBondMarket, GvcTrade, OpenEconomy}
import com.boombustgroup.amorfati.engine.mechanisms.Expectations
import com.boombustgroup.amorfati.types.*

import com.boombustgroup.amorfati.random.RandomStream

/** Self-contained open economy economics — calls market functions directly.
  *
  * Replaces OpenEconomyStep.run() wrapper. Same economic logic
  * (Marshall-Lerner, Taylor rule, Gordon equity, Meen housing), but does NOT
  * produce World state updates via .copy(). Instead, produces values for
  * MonthlyCalculus that feed flow mechanisms.
  *
  * Calculus: trade model, ER, monetary policy, bond yield, interbank, corp
  * bonds, insurance Plumbing: handled by flow mechanisms (OpenEconFlows,
  * BankingFlows, CorpBondFlows, etc.)
  */
object OpenEconEconomics:

  private val MaxDebtServiceGdpShare = Share.decimal(50, 2)

  case class MonetaryPolicy(
      newRefRate: Rate,
      newExp: Expectations.State,
      newGovBondMarketYield: Rate,
      newGovDebtWeightedCoupon: Rate,
      qePurchaseAmount: PLN,
      postFxNbp: Nbp.State,
      postFxNbpFinancialStocks: Nbp.FinancialStocks,
      fxPlnInjection: PLN,
  )

  case class BankingFlows(
      totalReserveInterest: PLN,
      totalStandingFacilityIncome: PLN,
      totalInterbankInterest: PLN,
      bankBondIncome: PLN,
      nbpRemittance: PLN,
      monthlyDebtService: PLN,
  )

  case class ExternalSector(
      flowBop: OpenEconomy.BopState,
      newForex: OpenEconomy.ForexState,
      newBop: OpenEconomy.BopState,
      newGvc: GvcTrade.State,
      oeValuationEffect: PLN,
      fdiCitLoss: PLN,
  )

  case class CorporateBonds(
      newCorpBonds: CorporateBondMarket.State,
      closingCorpBondProjection: CorporateBondMarket.StockState, // ledger-owned stock projection for downstream settlement
      corpBondCoupon: PLN,
      corpBondBankCoupon: PLN,
      corpBondBankDefaultLoss: PLN,
      corpBondInsuranceDefaultLoss: PLN,
      corpBondNbfiDefaultLoss: PLN,
      corpBondAmort: PLN,
  )

  case class NonBankFinancials(
      newInsurance: Insurance.State,
      newInsuranceBalances: Insurance.ClosingBalances,
      insNetDepositChange: PLN,
      newNbfi: Nbfi.State,
      newNbfiBalances: Nbfi.ClosingBalances,
      nbfiDepositDrain: PLN,
  )

  case class StepOutput(
      monetary: MonetaryPolicy,
      banking: BankingFlows,
      external: ExternalSector,
      corpBonds: CorporateBonds,
      nonBank: NonBankFinancials,
  )

  /** Public WAM coupon update — exposed for tests (DebtMaturitySpec). */
  private[amorfati] def updateGovDebtWeightedCouponPublic(
      prevCoupon: Rate,
      govBondMarketYield: Rate,
      bondsOutstanding: PLN,
      deficit: PLN,
      avgMaturityMonths: Int,
  ): Rate =
    val rolloverFrac: Share = Share.fraction(1, avgMaturityMonths.max(1))
    val deficitFrac: Share  =
      if bondsOutstanding > PLN.Zero then Share(deficit.max(PLN.Zero) / bondsOutstanding)
      else Share.Zero
    val freshFrac: Share    = (rolloverFrac + deficitFrac).min(Share.One)
    prevCoupon * (Share.One - freshFrac) + govBondMarketYield * freshFrac

  // ---------------------------------------------------------------------------
  // runStep — full open economy pipeline (migrated from OpenEconomyStep.run)
  // ---------------------------------------------------------------------------

  case class StepInput(
      world: World,
      ledgerFinancialState: LedgerFinancialState,
      fiscal: FiscalConstraintEconomics.StepOutput,
      labor: LaborEconomics.StepOutput,
      householdIncome: HouseholdIncomeEconomics.StepOutput,
      demand: DemandEconomics.StepOutput,
      firm: FirmEconomics.StepOutput,
      householdFinancial: HouseholdFinancialEconomics.StepOutput,
      priceEquity: PriceEquityEconomics.StepOutput,
      banks: Vector[Banking.BankState],
      commodityRng: RandomStream,
  )

  private val NbfiDepositRateSpread: Rate = Rate.decimal(2, 2)

  // Internal intermediate types for sub-method returns
  private case class ForexResult(
      forex: OpenEconomy.ForexState,
      bop: OpenEconomy.BopState,
      valuationEffect: PLN,
      fxIntervention: Nbp.FxInterventionResult,
  )

  private case class ExternalResult(
      flowBop: OpenEconomy.BopState,
      newForex: OpenEconomy.ForexState,
      newBop: OpenEconomy.BopState,
      newGvc: GvcTrade.State,
      oeValuationEffect: PLN,
      fdiCitLoss: PLN,
      fxIntervention: Nbp.FxInterventionResult,
  )

  private case class RateExpResult(
      refRate: Rate,
      expectations: Expectations.State,
  )

  private case class InterbankResult(
      reserveInterest: PLN,
      standingFacilityIncome: PLN,
      interbankInterest: PLN,
  )

  private case class BondQeResult(
      govBondMarketYield: Rate,
      newGovDebtWeightedCoupon: Rate,
      bankBondIncome: PLN,
      nbpRemittance: PLN,
      monthlyDebtService: PLN,
      qePurchaseAmount: PLN,
      postFxNbp: Nbp.State,
      postFxNbpFinancialStocks: Nbp.FinancialStocks,
  )

  private case class InsuranceResult(state: Insurance.State, closing: Insurance.ClosingBalances)
  private case class NbfiResult(state: Nbfi.State, closing: Nbfi.ClosingBalances)

  def runStep(in: StepInput)(using p: SimParams): StepOutput =
    val bankFinancialStocks = in.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks)
    val bankAgg             = Banking.aggregateFromBankStocks(
      in.banks,
      bankFinancialStocks,
      bankId => CorporateBondOwnership.bankHolderFor(in.ledgerFinancialState, bankId),
    )
    val sectorOutputs       = in.priceEquity.realizedSectorOutputs
    val external            = runStepExternalSector(in, sectorOutputs)
    val rateAndExp          = runStepRateAndExpectations(in, external.newForex)
    val interbank           = runStepInterbankFlows(in.world, in.banks, bankFinancialStocks)
    val bondQe              = runStepGovBondMarketYieldAndQe(in, bankAgg, rateAndExp.refRate, rateAndExp.expectations, external.fxIntervention, interbank)
    val corpBonds           = runStepCorporateBonds(in, bankAgg, bondQe.govBondMarketYield)
    val insurance           = runStepInsurance(
      in,
      bondQe.govBondMarketYield,
      corpBonds.newCorpBonds.corpBondYield,
      corpBonds.corpBondInsuranceDefaultLoss,
    )
    val nbfi                = runStepNbfi(
      in,
      bankAgg,
      bondQe.postFxNbp,
      bondQe.govBondMarketYield,
      corpBonds.newCorpBonds.corpBondYield,
      corpBonds.corpBondNbfiDefaultLoss,
    )

    StepOutput(
      monetary = MonetaryPolicy(
        newRefRate = rateAndExp.refRate,
        newExp = rateAndExp.expectations,
        newGovBondMarketYield = bondQe.govBondMarketYield,
        newGovDebtWeightedCoupon = bondQe.newGovDebtWeightedCoupon,
        qePurchaseAmount = bondQe.qePurchaseAmount,
        postFxNbp = bondQe.postFxNbp,
        postFxNbpFinancialStocks = bondQe.postFxNbpFinancialStocks,
        fxPlnInjection = external.fxIntervention.plnInjection,
      ),
      banking = BankingFlows(
        totalReserveInterest = interbank.reserveInterest,
        totalStandingFacilityIncome = interbank.standingFacilityIncome,
        totalInterbankInterest = interbank.interbankInterest,
        bankBondIncome = bondQe.bankBondIncome,
        nbpRemittance = bondQe.nbpRemittance,
        monthlyDebtService = bondQe.monthlyDebtService,
      ),
      external = ExternalSector(
        flowBop = external.flowBop,
        newForex = external.newForex,
        newBop = external.newBop,
        newGvc = external.newGvc,
        oeValuationEffect = external.oeValuationEffect,
        fdiCitLoss = external.fdiCitLoss,
      ),
      corpBonds = corpBonds,
      nonBank = NonBankFinancials(
        newInsurance = insurance.state,
        newInsuranceBalances = insurance.closing,
        insNetDepositChange = insurance.state.lastNetDepositChange,
        newNbfi = nbfi.state,
        newNbfiBalances = nbfi.closing,
        nbfiDepositDrain = nbfi.state.lastDepositDrain,
      ),
    )

  private[economics] def aggregateSectorOutputs(
      priceLevel: PriceIndex,
      sectorCount: Int,
      firms: Vector[Firm.State],
      sectorMultiplier: Int => Multiplier,
  )(using p: SimParams): Vector[PLN] =
    GdpAccounting.realizedSectorOutputs(priceLevel, sectorCount, firms, sectorMultiplier)

  private def runStepGvc(in: StepInput, sectorOutputs: Vector[PLN])(using p: SimParams): GvcTrade.State =
    GvcTrade.step(
      GvcTrade.StepInput(
        prev = in.world.external.gvc,
        sectorOutputs = sectorOutputs,
        priceLevel = in.world.priceLevel,
        exchangeRate = in.world.forex.exchangeRate,
        autoRatio = in.priceEquity.autoR,
        month = in.fiscal.m,
        rng = in.commodityRng,
      ),
    )

  private def runStepForex(in: StepInput, sectorOutputs: Vector[PLN], newGvc: GvcTrade.State)(using p: SimParams): ForexResult =
    val (gvcExp, gvcImp) = (Some(newGvc.totalExports), Some(newGvc.sectorImports))

    val totalTechAndInvImports = in.firm.sumTechImp + in.priceEquity.investmentImports
    val oeResult               = OpenEconomy.step(
      OpenEconomy.StepInput(
        prevBop = in.world.bop,
        prevForex = in.world.forex,
        importCons = in.householdIncome.importCons,
        techImports = totalTechAndInvImports,
        autoRatio = in.priceEquity.autoR,
        domesticRate = in.world.nbp.referenceRate,
        gdp = in.priceEquity.gdp,
        inflation = in.world.inflation,
        priceLevel = in.world.priceLevel,
        sectorOutputs = sectorOutputs,
        month = in.fiscal.m,
        nbpFxReserves = in.ledgerFinancialState.nbp.foreignAssets,
        gvcExports = gvcExp,
        gvcIntermImports = gvcImp,
        remittanceOutflow = in.householdFinancial.remittanceOutflow,
        euFundsMonthly = in.priceEquity.euMonthly,
        diasporaInflow = in.householdFinancial.diasporaInflow,
        tourismExport = in.householdFinancial.tourismExport,
        tourismImport = in.householdFinancial.tourismImport,
      ),
    )
    ForexResult(oeResult.forex, oeResult.bop, oeResult.valuationEffect, oeResult.fxIntervention)

  private def runStepAdjustBop(in: StepInput, bop0: OpenEconomy.BopState)(using p: SimParams): (OpenEconomy.BopState, PLN) =
    val bop1             =
      if in.priceEquity.foreignDividendOutflow > PLN.Zero then
        bop0.copy(
          currentAccount = bop0.currentAccount - in.priceEquity.foreignDividendOutflow,
          nfa = bop0.nfa - in.priceEquity.foreignDividendOutflow,
        )
      else bop0
    val fdiTotalBopDebit = in.firm.sumProfitShifting + in.firm.sumFdiRepatriation
    val bop2             =
      if fdiTotalBopDebit > PLN.Zero then
        bop1.copy(
          currentAccount = bop1.currentAccount - fdiTotalBopDebit,
          nfa = bop1.nfa - fdiTotalBopDebit,
          tradeBalance = bop1.tradeBalance - in.firm.sumProfitShifting,
          totalImports = bop1.totalImports + in.firm.sumProfitShifting,
        )
      else bop1
    val fdiCitLoss       = in.firm.sumProfitShifting * p.fiscal.citRate
    val bop              = bop2.copy(
      euFundsMonthly = in.priceEquity.euMonthly,
      euCumulativeAbsorption = in.world.bop.euCumulativeAbsorption + in.priceEquity.euMonthly,
    )
    (bop, fdiCitLoss)

  private def runStepExternalSector(in: StepInput, sectorOutputs: Vector[PLN])(using p: SimParams): ExternalResult =
    val newGvc               = runStepGvc(in, sectorOutputs)
    val fxResult             = runStepForex(in, sectorOutputs, newGvc)
    val (newBop, fdiCitLoss) = runStepAdjustBop(in, fxResult.bop)
    ExternalResult(
      flowBop = fxResult.bop,
      newForex = fxResult.forex,
      newBop = newBop,
      newGvc = newGvc,
      oeValuationEffect = fxResult.valuationEffect,
      fdiCitLoss = fdiCitLoss,
      fxIntervention = fxResult.fxIntervention,
    )

  private def runStepRateAndExpectations(in: StepInput, newForex: OpenEconomy.ForexState)(using p: SimParams): RateExpResult =
    val exRateChg       = newForex.exchangeRate.deviationFrom(in.world.forex.exchangeRate).toCoefficient
    val newRefRate      = Nbp.updateRate(
      in.world.nbp.referenceRate,
      in.priceEquity.newInfl,
      exRateChg,
      in.labor.employed,
      in.world.laborForcePopulation,
      expectedInflation = in.world.mechanisms.expectations.expectedInflation,
    )
    val unempRateForExp = in.world.unemploymentRate(in.labor.employed)
    val newExp          =
      Expectations.step(in.world.mechanisms.expectations, in.priceEquity.newInfl, newRefRate, unempRateForExp)
    RateExpResult(newRefRate, newExp)

  private def runStepInterbankFlows(w: World, banks: Vector[Banking.BankState], bankFinancialStocks: Vector[Banking.BankFinancialStocks])(using
      SimParams,
  ): InterbankResult =
    val bsec = w.bankingSector
    InterbankResult(
      reserveInterest = Banking.computeReserveInterestFromBankStocks(banks, bankFinancialStocks, w.nbp.referenceRate).total,
      standingFacilityIncome = Banking.computeStandingFacilitiesFromBankStocks(banks, bankFinancialStocks, w.nbp.referenceRate).total,
      interbankInterest = Banking.interbankInterestFlowsFromBankStocks(banks, bankFinancialStocks, bsec.interbankRate).total,
    )

  private def runStepGovBondMarketYieldAndQe(
      in: StepInput,
      bankAgg: Banking.Aggregate,
      newRefRate: Rate,
      newExp: Expectations.State,
      fxResult: Nbp.FxInterventionResult,
      interbank: InterbankResult,
  )(using p: SimParams): BondQeResult =
    val annualGdpForBonds  = in.priceEquity.gdp * 12
    val debtToGdp          = if annualGdpForBonds > PLN.Zero then (in.world.gov.cumulativeDebt / annualGdpForBonds).toShare else Share.Zero
    val nbpBondGdpShare    = if annualGdpForBonds > PLN.Zero then (in.world.nbp.qeCumulative / annualGdpForBonds).toShare else Share.Zero
    val credPremium        =
      val deAnchor = (Share.One - in.world.mechanisms.expectations.credibility) *
        (in.world.mechanisms.expectations.expectedInflation - p.monetary.targetInfl).abs.toScalar.toShare
      (deAnchor * p.labor.expBondSensitivity).toRate
    val govBondMarketYield = Nbp.govBondMarketYield(newRefRate, debtToGdp, nbpBondGdpShare, in.world.bop.nfa, credPremium)

    val newGovDebtWeightedCoupon = updateGovDebtWeightedCouponPublic(
      prevCoupon = in.world.gov.govDebtWeightedCoupon,
      govBondMarketYield = govBondMarketYield,
      bondsOutstanding = in.ledgerFinancialState.government.govBondOutstanding,
      deficit = in.world.gov.deficit,
      avgMaturityMonths = p.fiscal.govAvgMaturityMonths,
    )

    val rawDebtService     = in.ledgerFinancialState.government.govBondOutstanding * newGovDebtWeightedCoupon.monthly
    val monthlyDebtService = rawDebtService.min(in.priceEquity.gdp * MaxDebtServiceGdpShare)
    val bankBondIncome     = bankAgg.govBondHoldings * govBondMarketYield.monthly
    val nbpBondIncome      = in.ledgerFinancialState.nbp.govBondHoldings * govBondMarketYield.monthly
    val nbpRemittance      = nbpBondIncome - interbank.reserveInterest - interbank.standingFacilityIncome

    val qeActivate       = Nbp.shouldActivateQe(newRefRate, in.priceEquity.newInfl, newExp.expectedInflation)
    val qeTaper          = Nbp.shouldTaperQe(in.priceEquity.newInfl, newExp.expectedInflation)
    val qeActive         =
      if qeActivate then true
      else if qeTaper then false
      else in.world.nbp.qeActive
    val preQeNbp         = Nbp.State(
      newRefRate,
      qeActive,
      in.world.nbp.qeCumulative,
      in.world.nbp.lastFxTraded,
    )
    val preQeNbpStocks   = Nbp.FinancialStocks(
      govBondHoldings = in.ledgerFinancialState.nbp.govBondHoldings,
      foreignAssets = in.ledgerFinancialState.nbp.foreignAssets,
    )
    val qeRequest        =
      Nbp.executeQe(preQeNbp, preQeNbpStocks, bankAgg.govBondHoldings, annualGdpForBonds, in.priceEquity.newInfl, newExp.expectedInflation)
    val qePurchaseAmount = qeRequest.requestedPurchase
    val postFxNbp        = qeRequest.nbpState.copy(monthly = qeRequest.nbpState.monthly.copy(lastFxTraded = fxResult.eurTraded))
    val postFxNbpStocks  = preQeNbpStocks.copy(
      foreignAssets = fxResult.newReserves,
    )

    BondQeResult(
      govBondMarketYield,
      newGovDebtWeightedCoupon,
      bankBondIncome,
      nbpRemittance,
      monthlyDebtService,
      qePurchaseAmount,
      postFxNbp,
      postFxNbpStocks,
    )

  private def runStepCorporateBonds(in: StepInput, bankAgg: Banking.Aggregate, newGovBondMarketYield: Rate)(using SimParams): CorporateBonds =
    val openingCorpBondProjection = CorporateBondOwnership.stockStateFromLedger(in.ledgerFinancialState)
    val corpBondAmort             = CorporateBondMarket.amortization(openingCorpBondProjection)
    val corpBondStep              = CorporateBondMarket
      .step(
        CorporateBondMarket.StepInput(
          prevState = in.world.financialMarkets.corporateBonds,
          prevStock = openingCorpBondProjection,
          govBondMarketYield = newGovBondMarketYield,
          nplRatio = bankAgg.nplRatio,
          totalBondDefault = in.firm.totalBondDefault,
          totalBondIssuance = in.firm.actualBondIssuance,
        ),
      )
    val newCorpBonds              = corpBondStep.state.copy(lastAbsorptionRate = in.firm.corpBondAbsorption)
    val corpBondCoupon            = CorporateBondMarket.computeCoupon(in.world.financialMarkets.corporateBonds, openingCorpBondProjection)
    val corpBondDefaults          = CorporateBondMarket.processDefaults(openingCorpBondProjection, in.firm.totalBondDefault)
    CorporateBonds(
      newCorpBonds = newCorpBonds,
      closingCorpBondProjection = corpBondStep.stock,
      corpBondCoupon = corpBondCoupon.total,
      corpBondBankCoupon = corpBondCoupon.bank,
      corpBondBankDefaultLoss = corpBondDefaults.bankLoss,
      corpBondInsuranceDefaultLoss = corpBondDefaults.insuranceLoss,
      corpBondNbfiDefaultLoss = corpBondDefaults.nbfiLoss,
      corpBondAmort = corpBondAmort,
    )

  private def runStepInsurance(
      in: StepInput,
      newGovBondMarketYield: Rate,
      newCorpBondYield: Rate,
      corpBondDefaultLoss: PLN,
  )(using p: SimParams): InsuranceResult =
    val laborForce    = in.labor.newDemographics.workingAgePop.max(1)
    val employed      = Math.max(0, Math.min(in.labor.employed, laborForce))
    val unempRate     = Share.One - Share.fraction(employed, laborForce)
    // Insurance remains anchored to the reconciled labor state.
    // Unlike social payroll funds, moving premiums to the opening payroll
    // boundary would require changing both Insurance.step and runtime emission.
    val insuranceStep =
      Insurance.step(
        Insurance.StepInput(
          opening = LedgerFinancialState.insuranceOpeningBalances(in.ledgerFinancialState),
          employed = in.labor.employed,
          wage = in.labor.newWage,
          unempRate = unempRate,
          govBondMarketYield = newGovBondMarketYield,
          corpBondYield = newCorpBondYield,
          equityReturn = in.priceEquity.equityAfterForeignStock.monthlyReturn,
          corpBondDefaultLoss = corpBondDefaultLoss,
        ),
      )
    InsuranceResult(insuranceStep.state, insuranceStep.closing)

  private def runStepNbfi(
      in: StepInput,
      bankAgg: Banking.Aggregate,
      postFxNbp: Nbp.State,
      newGovBondMarketYield: Rate,
      newCorpBondYield: Rate,
      corpBondDefaultLoss: PLN,
  )(using p: SimParams): NbfiResult =
    val nbfiDepositRate = (postFxNbp.referenceRate - NbfiDepositRateSpread).max(Rate.Zero)
    val nbfiUnempRate   = in.world.unemploymentRate(in.labor.employed)
    val nbfiStep        =
      Nbfi.step(
        Nbfi.StepInput(
          opening = LedgerFinancialState.nbfiOpeningBalances(in.ledgerFinancialState),
          employed = in.labor.employed,
          wage = in.labor.newWage,
          priceLevel = in.world.priceLevel,
          unempRate = nbfiUnempRate,
          bankNplRatio = bankAgg.nplRatio,
          govBondMarketYield = newGovBondMarketYield,
          corpBondYield = newCorpBondYield,
          equityReturn = in.priceEquity.equityAfterForeignStock.monthlyReturn,
          depositRate = nbfiDepositRate,
          corpBondDefaultLoss = corpBondDefaultLoss,
        ),
      )
    NbfiResult(nbfiStep.state, nbfiStep.closing)
