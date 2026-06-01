package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.{MonthExecution, MonthRandomness, MonthWorkflow, World}
import com.boombustgroup.amorfati.engine.closedmonth.MonthClosing
import com.boombustgroup.amorfati.engine.economics.*
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, GovernmentBondCircuit, LedgerFinancialState}
import com.boombustgroup.amorfati.engine.markets.CorporateBondMarket
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.*

import scala.IArray

/** Runs the deterministic same-month economics boundary.
  *
  * This is the one insertion point for the ordered same-month economics
  * boundary. It returns only month-`t` views: flow plan, operational signals,
  * closing input, and SFC semantic projection. Closed-month state and next-pre
  * seed extraction remain outside this runner.
  */
private[flows] object MonthCalculusRunner:
  import FlowSimulation.{MonthlyCalculus, SameMonthBoundaryViews, SemanticFlowInputs, SignalBoundaryInputs, StepInput}

  private final case class StageRun(
      openingLedger: LedgerFinancialState,
      openingBanks: Vector[Banking.BankState],
      execution: MonthExecution,
      laborPre: LaborEconomics.StepOutput,
      payroll: SocialSecurity.PayrollBase,
      nBankruptFirms: Int,
      avgFirmWorkers: Int,
  )

  private final case class EconomicsPre(
      input: StepInput,
      randomness: MonthRandomness.StageSeeds,
      ledger: LedgerFinancialState,
      world: World,
      firms: Vector[Firm.State],
      households: Vector[Household.State],
      banks: Vector[Banking.BankState],
  )

  private final case class OpeningPayroll(
      payroll: SocialSecurity.PayrollBase,
      zus: SocialSecurity.ZusState,
  )

  private final case class PostFirmBoundary(
      firm: FirmEconomics.StepOutput,
      livingFirms: Vector[Firm.State],
      nBankruptFirms: Int,
      avgFirmWorkers: Int,
  )

  private final case class SocialFundStages(
      zus: SocialSecurity.ZusState,
      nfz: SocialSecurity.NfzState,
      ppk: SocialSecurity.PpkState,
      earmarked: EarmarkedFunds.State,
  )

  private object SameMonthEconomicsDsl:
    import MonthWorkflow.Program

    def pre(input: StepInput): Program[EconomicsPre] =
      val stateIn = input.stateIn
      MonthWorkflow.pure(
        EconomicsPre(
          input = input,
          randomness = input.randomness.stages,
          ledger = stateIn.ledgerFinancialState,
          world = stateIn.world,
          firms = stateIn.firms,
          households = stateIn.households,
          banks = stateIn.banks,
        ),
      )

    def fiscal(pre: EconomicsPre)(using SimParams): Program[FiscalConstraintEconomics.StepOutput] =
      MonthWorkflow.pure(FiscalConstraintEconomics.compute(pre.world, pre.banks, pre.ledger, pre.input.executionMonth))

    def laborPre(pre: EconomicsPre, fiscal: FiscalConstraintEconomics.StepOutput)(using SimParams): Program[LaborEconomics.StepOutput] =
      MonthWorkflow.pure(LaborEconomics.compute(pre.world, pre.firms, pre.households, fiscal))

    def openingPayroll(pre: EconomicsPre, laborPre: LaborEconomics.StepOutput)(using SimParams): Program[OpeningPayroll] =
      val payroll = SocialSecurity.payrollBase(pre.households)
      MonthWorkflow.pure(
        OpeningPayroll(
          payroll = payroll,
          zus = SocialSecurity.zusStep(payroll, laborPre.newDemographics.retirees),
        ),
      )

    def householdIncome(
        pre: EconomicsPre,
        fiscal: FiscalConstraintEconomics.StepOutput,
        laborPre: LaborEconomics.StepOutput,
        payroll: OpeningPayroll,
    )(using SimParams): Program[HouseholdIncomeEconomics.StepOutput] =
      MonthWorkflow.pure(
        HouseholdIncomeEconomics.compute(
          pre.world,
          pre.firms,
          pre.households,
          pre.banks,
          pre.ledger,
          fiscal.lendingBaseRate,
          fiscal.resWage,
          laborPre.newWage,
          pre.randomness.householdIncomeEconomics.newStream(),
          pensionIncome = payroll.zus.pensionPayments,
        ),
      )

    def demand(
        pre: EconomicsPre,
        laborPre: LaborEconomics.StepOutput,
        householdIncome: HouseholdIncomeEconomics.StepOutput,
    )(using SimParams): Program[DemandEconomics.StepOutput] =
      MonthWorkflow.pure(DemandEconomics.compute(pre.world, laborPre.employed, laborPre.living, householdIncome.domesticCons))

    def firm(
        pre: EconomicsPre,
        fiscal: FiscalConstraintEconomics.StepOutput,
        laborPre: LaborEconomics.StepOutput,
        householdIncome: HouseholdIncomeEconomics.StepOutput,
        demand: DemandEconomics.StepOutput,
    )(using SimParams): Program[FirmEconomics.StepOutput] =
      MonthWorkflow.pure(
        FirmEconomics.runStep(
          pre.world,
          pre.firms,
          pre.households,
          pre.banks,
          pre.ledger,
          fiscal,
          laborPre,
          householdIncome,
          demand,
          pre.randomness.firmEconomics.newStream(),
          traceDecisions = pre.input.traceFirmDecisions,
        ),
      )

    def postFirm(laborPre: LaborEconomics.StepOutput, firm: FirmEconomics.StepOutput): Program[PostFirmBoundary] =
      MonthWorkflow.pure(
        PostFirmBoundary(
          firm = firm,
          livingFirms = firm.ioFirms.filter(Firm.isAlive),
          nBankruptFirms = firm.firmDeaths,
          avgFirmWorkers = if laborPre.living.nonEmpty then laborPre.employed / laborPre.living.length else 0,
        ),
      )

    def socialFunds(
        payroll: OpeningPayroll,
        laborPre: LaborEconomics.StepOutput,
        householdIncome: HouseholdIncomeEconomics.StepOutput,
        postFirm: PostFirmBoundary,
    )(using SimParams): Program[SocialFundStages] =
      MonthWorkflow.pure(
        SocialFundStages(
          zus = payroll.zus,
          nfz = SocialSecurity.nfzStep(payroll.payroll, laborPre.newDemographics.workingAgePop, laborPre.newDemographics.retirees),
          ppk = SocialSecurity.ppkStep(payroll.payroll),
          earmarked = EarmarkedFunds.step(payroll.payroll, householdIncome.hhAgg.totalUnempBenefits, postFirm.nBankruptFirms, postFirm.avgFirmWorkers),
        ),
      )

    def labor(
        pre: EconomicsPre,
        fiscal: FiscalConstraintEconomics.StepOutput,
        laborPre: LaborEconomics.StepOutput,
        postFirm: PostFirmBoundary,
        socialFunds: SocialFundStages,
    )(using SimParams): Program[LaborEconomics.StepOutput] =
      val reconciled = LaborEconomics.reconcilePostFirmStep(pre.world, fiscal, laborPre, postFirm.livingFirms, postFirm.firm.households)
      // Labor reconciliation refreshes employment/wage state after the firm step,
      // but social funds are pinned to the opening-boundary payroll for month t.
      MonthWorkflow.pure(
        reconciled.copy(
          newZus = socialFunds.zus,
          newNfz = socialFunds.nfz,
          newPpk = socialFunds.ppk,
          newEarmarked = socialFunds.earmarked,
        ),
      )

    def householdFinancial(
        pre: EconomicsPre,
        fiscal: FiscalConstraintEconomics.StepOutput,
        labor: LaborEconomics.StepOutput,
        householdIncome: HouseholdIncomeEconomics.StepOutput,
    )(using SimParams): Program[HouseholdFinancialEconomics.StepOutput] =
      MonthWorkflow.pure(
        HouseholdFinancialEconomics.compute(
          pre.world,
          fiscal.m,
          labor.employed,
          householdIncome.hhAgg,
          pre.randomness.householdFinancialEconomics.newStream(),
        ),
      )

    def priceEquity(
        pre: EconomicsPre,
        fiscal: FiscalConstraintEconomics.StepOutput,
        labor: LaborEconomics.StepOutput,
        demand: DemandEconomics.StepOutput,
        firm: FirmEconomics.StepOutput,
    )(using SimParams): Program[PriceEquityEconomics.StepOutput] =
      MonthWorkflow.pure(
        PriceEquityEconomics.compute(
          w = pre.world,
          month = fiscal.m,
          wageGrowth = labor.wageGrowth,
          avgDemandMult = demand.avgDemandMult,
          sectorMults = demand.sectorMults,
          totalSystemLoans = pre.ledger.banks.map(_.firmLoan).sumPln,
          firmStep = firm,
          ledgerFinancialState = pre.ledger,
        ),
      )

    def openEconomy(
        pre: EconomicsPre,
        fiscal: FiscalConstraintEconomics.StepOutput,
        labor: LaborEconomics.StepOutput,
        householdIncome: HouseholdIncomeEconomics.StepOutput,
        demand: DemandEconomics.StepOutput,
        firm: FirmEconomics.StepOutput,
        householdFinancial: HouseholdFinancialEconomics.StepOutput,
        priceEquity: PriceEquityEconomics.StepOutput,
    )(using SimParams): Program[OpenEconEconomics.StepOutput] =
      MonthWorkflow.pure(
        OpenEconEconomics.runStep(
          OpenEconEconomics.StepInput(
            pre.world,
            pre.ledger,
            fiscal,
            labor,
            householdIncome,
            demand,
            firm,
            householdFinancial,
            priceEquity,
            pre.banks,
            pre.randomness.openEconEconomics.newStream(),
          ),
        ),
      )

    def banking(
        pre: EconomicsPre,
        fiscal: FiscalConstraintEconomics.StepOutput,
        labor: LaborEconomics.StepOutput,
        householdIncome: HouseholdIncomeEconomics.StepOutput,
        demand: DemandEconomics.StepOutput,
        firm: FirmEconomics.StepOutput,
        householdFinancial: HouseholdFinancialEconomics.StepOutput,
        priceEquity: PriceEquityEconomics.StepOutput,
        openEconomy: OpenEconEconomics.StepOutput,
    )(using SimParams): Program[BankingEconomics.StepOutput] =
      MonthWorkflow.pure(
        BankingEconomics.runStep(
          BankingEconomics.StepInput(
            world = pre.world,
            ledgerFinancialState = pre.ledger,
            fiscal = fiscal,
            labor = labor,
            householdIncome = householdIncome,
            demand = demand,
            firm = firm,
            householdFinancial = householdFinancial,
            priceEquity = priceEquity,
            openEconomy = openEconomy,
            banks = pre.banks,
            depositRng = pre.randomness.bankingEconomics.newStream(),
          ),
        ),
      )

    def execution(
        pre: EconomicsPre,
        fiscal: FiscalConstraintEconomics.StepOutput,
        labor: LaborEconomics.StepOutput,
        householdIncome: HouseholdIncomeEconomics.StepOutput,
        demand: DemandEconomics.StepOutput,
        firm: FirmEconomics.StepOutput,
        householdFinancial: HouseholdFinancialEconomics.StepOutput,
        priceEquity: PriceEquityEconomics.StepOutput,
        openEconomy: OpenEconEconomics.StepOutput,
        banking: BankingEconomics.StepOutput,
    ): Program[MonthExecution] =
      MonthWorkflow.pure(
        MonthExecution(
          openingWorld = pre.world,
          fiscal = fiscal,
          labor = labor,
          householdIncome = householdIncome,
          demand = demand,
          firm = firm,
          householdFinancial = householdFinancial,
          priceEquity = priceEquity,
          openEconomy = openEconomy,
          banking = banking,
        ),
      )

  def run(input: StepInput)(using p: SimParams): SameMonthBoundaryViews =
    val stages   = runEconomicsStages(input)
    val flowPlan = buildFlowPlan(stages)
    buildBoundaryViews(stages.execution, flowPlan)

  private def runEconomicsStages(input: StepInput)(using p: SimParams): StageRun =
    MonthWorkflow.run:
      for
        pre                <- SameMonthEconomicsDsl.pre(input)
        fiscal             <- SameMonthEconomicsDsl.fiscal(pre)
        laborPre           <- SameMonthEconomicsDsl.laborPre(pre, fiscal)
        payroll            <- SameMonthEconomicsDsl.openingPayroll(pre, laborPre)
        householdIncome    <- SameMonthEconomicsDsl.householdIncome(pre, fiscal, laborPre, payroll)
        demand             <- SameMonthEconomicsDsl.demand(pre, laborPre, householdIncome)
        firm               <- SameMonthEconomicsDsl.firm(pre, fiscal, laborPre, householdIncome, demand)
        postFirm           <- SameMonthEconomicsDsl.postFirm(laborPre, firm)
        socialFunds        <- SameMonthEconomicsDsl.socialFunds(payroll, laborPre, householdIncome, postFirm)
        labor              <- SameMonthEconomicsDsl.labor(pre, fiscal, laborPre, postFirm, socialFunds)
        householdFinancial <- SameMonthEconomicsDsl.householdFinancial(pre, fiscal, labor, householdIncome)
        priceEquity        <- SameMonthEconomicsDsl.priceEquity(pre, fiscal, labor, demand, firm)
        openEconomy        <- SameMonthEconomicsDsl.openEconomy(pre, fiscal, labor, householdIncome, demand, firm, householdFinancial, priceEquity)
        banking            <- SameMonthEconomicsDsl.banking(pre, fiscal, labor, householdIncome, demand, firm, householdFinancial, priceEquity, openEconomy)
        execution          <- SameMonthEconomicsDsl.execution(pre, fiscal, labor, householdIncome, demand, firm, householdFinancial, priceEquity, openEconomy, banking)
      yield StageRun(
        openingLedger = pre.ledger,
        openingBanks = pre.banks,
        execution = execution,
        laborPre = laborPre,
        payroll = payroll.payroll,
        nBankruptFirms = postFirm.nBankruptFirms,
        avgFirmWorkers = postFirm.avgFirmWorkers,
      )

  private def buildFlowPlan(stages: StageRun)(using p: SimParams): MonthlyCalculus =
    val ledger             = stages.openingLedger
    val banks              = stages.openingBanks
    val execution          = stages.execution
    val w                  = execution.openingWorld
    val fiscal             = execution.fiscal
    val laborPre           = stages.laborPre
    val labor              = execution.labor
    val householdIncome    = execution.householdIncome
    val firm               = execution.firm
    val householdFinancial = execution.householdFinancial
    val priceEquity        = execution.priceEquity
    val openEconomy        = execution.openEconomy
    val banking            = execution.banking
    val prevBankAgg        = Banking.aggregateFromBankStocks(
      banks,
      ledger.banks.map(LedgerFinancialState.projectBankFinancialStocks),
      bankId => CorporateBondOwnership.bankHolderFor(ledger, bankId),
    )
    val agg                = householdIncome.hhAgg
    val h                  = banking.housingAfterFlows
    val externalFlowBop    = openEconomy.external.flowBop
    val openingCorpBonds   = CorporateBondOwnership.stockStateFromLedger(ledger)
    val corpBondCoupon     = CorporateBondMarket.computeCoupon(w.financialMarkets.corporateBonds, openingCorpBonds)
    val corpBondIssuance   = CorporateBondMarket.processIssuance(CorporateBondMarket.StockState.zero, firm.actualBondIssuance)
    val laborForce         = labor.newDemographics.workingAgePop.max(1)
    val unemploymentRate   = Share.One - Share.fraction(Math.max(0, Math.min(labor.employed, laborForce)), laborForce)
    val equityRevaluation  = equityRevaluationInput(ledger, banking.ledgerFinancialState)

    MonthlyCalculus(
      month = fiscal.month,
      resWage = fiscal.resWage,
      lendingBaseRate = fiscal.lendingBaseRate,
      baseMinWage = fiscal.baseMinWage,
      minWagePriceLevel = fiscal.updatedMinWagePriceLevel,
      wage = labor.newWage,
      employed = labor.employed,
      payroll = stages.payroll,
      zus = labor.newZus,
      ppk = labor.newPpk,
      earmarked = labor.newEarmarked,
      unemploymentRate = unemploymentRate,
      laborDemand = labor.laborDemand,
      livingFirms = firm.ioFirms.count(Firm.isAlive),
      retirees = laborPre.newDemographics.retirees,
      workingAgePop = laborPre.newDemographics.workingAgePop,
      nfz = labor.newNfz,
      nBankruptFirms = stages.nBankruptFirms,
      avgFirmWorkers = stages.avgFirmWorkers,
      totalIncome = householdIncome.totalIncome,
      consumption = agg.consumption,
      domesticConsumption = householdIncome.domesticCons,
      importConsumption = householdIncome.importCons,
      totalRent = agg.totalRent,
      pitRevenue = banking.pitAfterEvasion,
      totalDepositInterest = agg.totalDepositInterest,
      totalRemittances = agg.totalRemittances,
      totalUnempBenefits = agg.totalUnempBenefits,
      totalSocialTransfers = agg.totalSocialTransfers,
      totalCcOrigination = agg.totalConsumerOrigination,
      approvedCcOrigination = agg.totalConsumerApprovedOrigination,
      liquidityShortfallFinancing = agg.totalLiquidityShortfallFinancing,
      totalCcDebtService = agg.totalConsumerDebtService,
      totalCcPrincipal = agg.totalConsumerPrincipal,
      totalCcDefault = agg.totalConsumerDefault,
      govCurrentSpend = banking.newGovWithYield.govCurrentSpend,
      firmTax = firm.sumTax,
      firmNewLoans = firm.sumNewLoans,
      firmPrincipal = firm.sumFirmPrincipal,
      firmInterestIncome = firm.intIncome,
      firmCapex = firm.sumCapex,
      firmEquityIssuance = firm.sumEquityIssuance,
      firmIoPayments = firm.totalIoPaid,
      firmNplLoss = firm.nplLoss,
      firmProfitShifting = firm.sumProfitShifting,
      firmFdiRepatriation = firm.sumFdiRepatriation,
      firmGrossInvestment = firm.sumGrossInvestment,
      investNetDepositFlow = banking.investNetDepositFlow,
      gdp = priceEquity.gdp,
      inflation = priceEquity.newInfl,
      equityDomDividends = priceEquity.netDomesticDividends,
      equityForDividends = priceEquity.foreignDividendOutflow,
      equityDivTax = priceEquity.dividendTax,
      equityGovDividends = priceEquity.stateOwnedGovDividends,
      equityReturn = priceEquity.equityAfterForeignStock.monthlyReturn,
      exports = externalFlowBop.exports,
      totalImports = externalFlowBop.totalImports,
      tourismExport = householdFinancial.tourismExport,
      tourismImport = householdFinancial.tourismImport,
      fdi = externalFlowBop.fdi,
      portfolioFlows = externalFlowBop.portfolioFlows,
      carryTradeFlow = externalFlowBop.carryTradeFlow,
      capitalFlightOutflow = externalFlowBop.capitalFlightOutflow,
      primaryIncome = externalFlowBop.primaryIncome,
      euFunds = externalFlowBop.euFundsMonthly,
      diasporaInflow = householdFinancial.diasporaInflow,
      corpBondCoupon = openEconomy.corpBonds.corpBondCoupon,
      corpBondDefaultAmount = firm.totalBondDefault,
      corpBondIssuance = firm.actualBondIssuance,
      corpBondAmortization = openEconomy.corpBonds.corpBondAmort,
      corpBondCouponRecipients = corpBondCouponRecipients(corpBondCoupon),
      corpBondDefaultRecipients = allocateCorpBondReduction(firm.totalBondDefault, openingCorpBonds),
      corpBondIssuanceRecipients = corpBondStockRecipients(corpBondIssuance),
      corpBondAmortizationRecipients = allocateCorpBondReduction(openEconomy.corpBonds.corpBondAmort, openingCorpBonds),
      mortgageOrigination = h.lastOrigination,
      mortgageRepayment = h.lastRepayment,
      mortgageInterest = h.mortgageInterestIncome,
      mortgageDefault = h.lastDefault,
      bankGovBondIncome = prevBankAgg.govBondHoldings * openEconomy.monetary.newBondYield.monthly,
      bankReserveInterest = openEconomy.banking.totalReserveInterest,
      bankStandingFacility = openEconomy.banking.totalStandingFacilityIncome,
      bankStandingFacilityBackstop = banking.standingFacilityBackstop,
      bankInterbankInterest = openEconomy.banking.totalInterbankInterest,
      bankCorpBondCoupon = openEconomy.corpBonds.corpBondBankCoupon,
      bankCorpBondLoss = openEconomy.corpBonds.corpBondBankDefaultLoss,
      bankFxReserveSettlement = openEconomy.monetary.fxPlnInjection,
      bankBfgLevy = banking.bfgLevy,
      bankUnrealizedLoss = banking.unrealizedBondLoss,
      bankBailIn = banking.bailInLoss,
      bankNbpRemittance = openEconomy.banking.nbpRemittance,
      equityRevaluation = equityRevaluation,
      nbfiDepositDrain = openEconomy.nonBank.nbfiDepositDrain,
      nbfiOrigination = banking.finalNbfi.lastNbfiOrigination,
      nbfiRepayment = banking.finalNbfi.lastNbfiRepayment,
      nbfiDefaultAmount = banking.finalNbfi.lastNbfiDefaultAmount,
      qfBankBondIssuance = banking.newQuasiFiscal.monthlyBankBondIssuance,
      qfNbpBondAbsorption = banking.newQuasiFiscal.monthlyNbpBondAbsorption,
      qfBankBondAmortization = banking.newQuasiFiscal.monthlyBankBondAmortization,
      qfNbpBondAmortization = banking.newQuasiFiscal.monthlyNbpBondAmortization,
      qfLending = banking.newQuasiFiscal.monthlyLending,
      qfRepayment = banking.newQuasiFiscal.monthlyLoanRepayment,
      govVatRevenue = banking.vatAfterEvasion,
      govExciseRevenue = banking.exciseAfterEvasion,
      govCustomsDutyRevenue = banking.customsDutyRevenue,
      govDebtService = openEconomy.banking.monthlyDebtService,
      govDebtServiceRecipients = GovBudgetFlows.DebtServiceRecipients.fromCircuit(GovernmentBondCircuit.from(ledger), openEconomy.banking.monthlyDebtService),
      govBondRuntimeMovements = banking.govBondRuntimeMovements,
      govEuCofinancing = banking.newGovWithYield.euCofinancing,
      govCapitalSpend = banking.newGovWithYield.govCapitalSpend,
      insuranceCurrentLifeReserves = ledger.insurance.lifeReserve,
      insuranceCurrentNonLifeReserves = ledger.insurance.nonLifeReserve,
      insurancePrevGovBonds = ledger.insurance.govBondHoldings,
      insurancePrevCorpBonds = ledger.insurance.corpBondHoldings,
      insuranceCorpBondDefaultLoss = openEconomy.corpBonds.corpBondInsuranceDefaultLoss,
      insurancePrevEquity = ledger.insurance.equityHoldings,
      govBondYield = openEconomy.monetary.newBondYield,
      corpBondYield = openEconomy.corpBonds.newCorpBonds.corpBondYield,
    )

  private def buildBoundaryViews(execution: MonthExecution, flowPlan: MonthlyCalculus)(using p: SimParams): SameMonthBoundaryViews =
    SameMonthBoundaryViews(
      flowPlan = flowPlan,
      signals = SignalBoundaryInputs(
        labor = execution.labor,
        demand = execution.demand,
      ),
      closing = MonthClosing.prepareInput(execution),
      semanticProjection = SemanticFlowInputs(
        labor = execution.labor,
        hhIncome = execution.householdIncome,
        firms = execution.firm,
        hhFinancial = execution.householdFinancial,
        prices = execution.priceEquity,
        openEcon = execution.openEconomy,
        banking = execution.banking,
      ),
    )

  private def corpBondCouponRecipients(coupon: CorporateBondMarket.CouponResult): CorpBondFlows.HolderBreakdown =
    CorpBondFlows.HolderBreakdown(
      banks = coupon.bank,
      ppk = coupon.ppk,
      other = coupon.other,
      insurance = coupon.insurance,
      nbfi = coupon.nbfi,
    )

  private def corpBondStockRecipients(stock: CorporateBondMarket.StockState): CorpBondFlows.HolderBreakdown =
    CorpBondFlows.HolderBreakdown(
      banks = stock.bankHoldings,
      ppk = stock.ppkHoldings,
      other = stock.otherHoldings,
      insurance = stock.insuranceHoldings,
      nbfi = stock.nbfiHoldings,
    )

  private def equityRevaluationInput(
      opening: LedgerFinancialState,
      closing: LedgerFinancialState,
  ): EquityFlows.RevaluationInput =
    val householdDeltas = Array.fill(opening.households.length)(PLN.Zero)
    var i               = 0
    while i < opening.households.length do
      val closingEquity = if i < closing.households.length then closing.households(i).equity else PLN.Zero
      householdDeltas(i) = closingEquity - opening.households(i).equity
      i += 1

    EquityFlows.RevaluationInput(
      // Runtime topology is keyed to opening households; entrants become
      // holder-addressable at the next month boundary.
      householdDeltas = IArray.unsafeFromArray(householdDeltas),
      insuranceDelta = closing.insurance.equityHoldings - opening.insurance.equityHoldings,
      fundsDelta = closing.funds.nbfi.equityHoldings - opening.funds.nbfi.equityHoldings,
      foreignDelta = closing.foreign.equityHoldings - opening.foreign.equityHoldings,
    )

  private def allocateCorpBondReduction(
      amount: PLN,
      opening: CorporateBondMarket.StockState,
  ): CorpBondFlows.HolderBreakdown =
    if amount <= PLN.Zero then CorpBondFlows.HolderBreakdown.zero
    else
      val weights = Array(
        opening.bankHoldings.distributeRaw,
        opening.ppkHoldings.distributeRaw,
        opening.otherHoldings.distributeRaw,
        opening.insuranceHoldings.distributeRaw,
        opening.nbfiHoldings.distributeRaw,
      )
      if weights.forall(_ <= 0L) then CorpBondFlows.HolderBreakdown.copyToOther(amount)
      else
        val allocated = Distribute.distribute(amount.distributeRaw, weights)
        CorpBondFlows.HolderBreakdown(
          banks = PLN.fromRaw(allocated(0)),
          ppk = PLN.fromRaw(allocated(1)),
          other = PLN.fromRaw(allocated(2)),
          insurance = PLN.fromRaw(allocated(3)),
          nbfi = PLN.fromRaw(allocated(4)),
        )
