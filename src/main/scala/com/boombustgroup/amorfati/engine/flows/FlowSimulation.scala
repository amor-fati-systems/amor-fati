package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.accounting.Sfc
import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.*
import com.boombustgroup.amorfati.engine.SimulationMonth.{CompletedMonth, ExecutionMonth}
import com.boombustgroup.amorfati.engine.assembly.MonthClosing
import com.boombustgroup.amorfati.engine.economics.*
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, GovernmentBondCircuit, LedgerFinancialState}
import com.boombustgroup.amorfati.engine.markets.CorporateBondMarket
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.*

import scala.IArray

/** New flow-based simulation pipeline.
  *
  * Three stages per month:
  *   1. CALCULUS — pure economics (CES, Phillips, Taylor, Meen, Calvo)
  *   2. TRANSLATION — map calculus results to flow mechanism inputs
  *   3. PLUMBING — emit flows, apply through verified interpreter
  *
  * Ledger execution guarantees exact conservation; SFC remains as a semantic
  * validation oracle over stocks and declared monthly flows.
  */
object FlowSimulation:

  /** Single typed input to one monthly step.
    *
    * The simulation loop should treat this as the month-`t` boundary state that
    * flows into one `step(state, randomness)` transition.
    */
  case class SimState(
      completedMonth: CompletedMonth,
      world: World,
      firms: Vector[Firm.State],
      households: Vector[Household.State],
      banks: Vector[Banking.BankState],
      householdAggregates: Household.Aggregates,
      ledgerFinancialState: LedgerFinancialState,
  )

  object SimState:
    def fromInit(init: com.boombustgroup.amorfati.init.WorldInit.InitResult): SimState =
      SimState(
        completedMonth = CompletedMonth.Zero,
        world = init.world,
        firms = init.firms,
        households = init.households,
        banks = init.banks,
        householdAggregates = init.householdAggregates,
        ledgerFinancialState = init.ledgerFinancialState,
      )

  /** Runtime ledger execution evidence produced by [[RuntimeFlowExecutor]]. */
  type ExecutionResult = RuntimeFlowExecutor.Result

  /** Household micro snapshot boundary aligned with household-income flows.
    */
  case class HouseholdSnapshotState(
      households: Vector[Household.State],
      ledgerFinancialState: LedgerFinancialState,
  )

  /** All calculus results needed to feed flow mechanisms. */
  case class MonthlyCalculus(
      // Stage 1: Fiscal constraint
      month: ExecutionMonth,
      resWage: PLN,
      lendingBaseRate: Rate,
      baseMinWage: PLN,
      minWagePriceLevel: PriceIndex,
      // Stage 2: Labor market
      wage: PLN,
      employed: Int,
      payroll: SocialSecurity.PayrollBase,
      zus: SocialSecurity.ZusState,
      ppk: SocialSecurity.PpkState,
      earmarked: EarmarkedFunds.State,
      unemploymentRate: Share,
      laborDemand: Int,
      livingFirms: Int,
      retirees: Int,
      workingAgePop: Int,
      nfz: SocialSecurity.NfzState,
      nBankruptFirms: Int,
      avgFirmWorkers: Int,
      // Stage 3: HH income (aggregates)
      totalIncome: PLN,
      consumption: PLN,
      domesticConsumption: PLN,
      importConsumption: PLN,
      totalRent: PLN,
      pitRevenue: PLN,
      totalDepositInterest: PLN,
      totalRemittances: PLN,
      totalUnempBenefits: PLN,
      totalSocialTransfers: PLN,
      totalCcOrigination: PLN,
      approvedCcOrigination: PLN,
      liquidityShortfallFinancing: PLN,
      totalCcDebtService: PLN,
      totalCcPrincipal: PLN,
      totalCcDefault: PLN,
      // Stage 9: Gov budget
      govCurrentSpend: PLN,
      // Stage 5: Firm
      firmTax: PLN,
      firmNewLoans: PLN,
      firmPrincipal: PLN,
      firmInterestIncome: PLN,
      firmCapex: PLN,
      firmEquityIssuance: PLN,
      firmIoPayments: PLN,
      firmNplLoss: PLN,
      firmProfitShifting: PLN,
      firmFdiRepatriation: PLN,
      firmGrossInvestment: PLN,
      investNetDepositFlow: PLN,
      // Stage 7: Price / Equity
      gdp: PLN,
      inflation: Rate,
      equityDomDividends: PLN,
      equityForDividends: PLN,
      equityDivTax: PLN,
      equityGovDividends: PLN,
      equityReturn: Rate,
      // Stage 8: Open economy
      exports: PLN,
      totalImports: PLN,
      tourismExport: PLN,
      tourismImport: PLN,
      fdi: PLN,
      portfolioFlows: PLN,
      carryTradeFlow: PLN,
      capitalFlightOutflow: PLN,
      primaryIncome: PLN,
      euFunds: PLN,
      diasporaInflow: PLN,
      // Stage 8: Corp bonds
      corpBondCoupon: PLN,
      corpBondDefaultAmount: PLN,
      corpBondIssuance: PLN,
      corpBondAmortization: PLN,
      corpBondCouponRecipients: CorpBondFlows.HolderBreakdown,
      corpBondDefaultRecipients: CorpBondFlows.HolderBreakdown,
      corpBondIssuanceRecipients: CorpBondFlows.HolderBreakdown,
      corpBondAmortizationRecipients: CorpBondFlows.HolderBreakdown,
      // Stage 8: Mortgage
      mortgageOrigination: PLN,
      mortgageRepayment: PLN,
      mortgageInterest: PLN,
      mortgageDefault: PLN,
      // Stage 9: Banking
      bankGovBondIncome: PLN,
      bankReserveInterest: PLN,
      bankStandingFacility: PLN,
      bankStandingFacilityBackstop: PLN,
      bankInterbankInterest: PLN,
      bankCorpBondCoupon: PLN,
      bankCorpBondLoss: PLN,
      bankFxReserveSettlement: PLN,
      bankBfgLevy: PLN,
      bankUnrealizedLoss: PLN,
      bankBailIn: PLN,
      bankNbpRemittance: PLN,
      // Stage 9: holder stock deltas after BankingEconomics.runStep
      equityRevaluation: EquityFlows.RevaluationInput,
      // Stage 8/9: NBFI / TFI monetary channels
      nbfiDepositDrain: PLN,
      nbfiOrigination: PLN,
      nbfiRepayment: PLN,
      nbfiDefaultAmount: PLN,
      // Stage 9: quasi-fiscal monetary channels
      qfBankBondIssuance: PLN,
      qfNbpBondAbsorption: PLN,
      qfBankBondAmortization: PLN,
      qfNbpBondAmortization: PLN,
      qfLending: PLN,
      qfRepayment: PLN,
      // Stage 8: Gov budget
      govVatRevenue: PLN,
      govExciseRevenue: PLN,
      govCustomsDutyRevenue: PLN,
      govDebtService: PLN,
      govDebtServiceRecipients: GovBudgetFlows.DebtServiceRecipients,
      govBondRuntimeMovements: BankingEconomics.GovBondRuntimeMovements,
      govEuCofinancing: PLN,
      govCapitalSpend: PLN,
      // Insurance
      insuranceCurrentLifeReserves: PLN,
      insuranceCurrentNonLifeReserves: PLN,
      insurancePrevGovBonds: PLN,
      insurancePrevCorpBonds: PLN,
      insuranceCorpBondDefaultLoss: PLN,
      insurancePrevEquity: PLN,
      govBondYield: Rate,
      corpBondYield: Rate,
  )

  /** Emit ALL flows from calculus results. Pure translation — no economics
    * here.
    */
  def emitAllBatches(c: MonthlyCalculus)(using p: SimParams, topology: RuntimeLedgerTopology): Vector[BatchedFlow] =
    MonthFlowEmitter.emitAllBatches(c)

  type ExecutedFlowEvidence = SfcSemanticProjection.ExecutedFlowEvidence
  val ExecutedFlowEvidence: SfcSemanticProjection.ExecutedFlowEvidence.type =
    SfcSemanticProjection.ExecutedFlowEvidence

  /** Typed month-`t` boundary input used internally by [[step]]. */
  case class StepInput(
      stateIn: SimState,
      executionMonth: ExecutionMonth,
      seedIn: MonthSemantics.SeedIn,
      randomness: MonthRandomness.Contract,
      boundaryIn: MonthBoundarySnapshot,
      traceFirmDecisions: Boolean,
  )

  /** Same-month signal surface reused by operational, timing, and seed
    * boundaries.
    */
  private[engine] case class SignalBoundaryInputs(
      labor: LaborEconomics.StepOutput,
      demand: DemandEconomics.StepOutput,
  )

  /** Same-month payload narrowed for executed-batch -> SFC semantic projection.
    */
  private[engine] case class SemanticFlowInputs(
      labor: LaborEconomics.StepOutput,
      hhIncome: HouseholdIncomeEconomics.StepOutput,
      firms: FirmEconomics.StepOutput,
      hhFinancial: HouseholdFinancialEconomics.StepOutput,
      prices: PriceEquityEconomics.StepOutput,
      openEcon: OpenEconEconomics.StepOutput,
      banking: BankingEconomics.StepOutput,
  )

  /** Downstream-specific same-month boundary views derived from one economics
    * execution.
    */
  private case class SameMonthBoundaryViews(
      flowPlan: MonthlyCalculus,
      signals: SignalBoundaryInputs,
      closing: MonthClosingInput,
      semanticProjection: SemanticFlowInputs,
  )

  /** Realized month-`t` closing boundary plus the narrow payload needed to
    * build [[MonthTrace]].
    */
  case class ClosedMonthBoundary(
      closing: MonthClosingResult,
      boundaryOut: MonthBoundarySnapshot,
      timing: MonthTimingTrace,
  )

  /** Full typed boundary carried through one step: pre-step seed, same-month
    * boundary views, closed month, then the extracted seed for `t + 1`.
    */
  case class MonthOutcome(
      operational: MonthSemantics.Operational,
      flowPlan: MonthSemantics.FlowPlan,
      semanticProjection: MonthSemantics.SemanticProjection,
      closed: MonthSemantics.ClosedMonth,
      seedOut: MonthSemantics.SeedOut,
      traceCore: MonthTraceCore,
  )

  private object MonthStepDsl:
    import MonthWorkflow.Program

    def pre(input: StepInput): Program[StepInput] =
      MonthWorkflow.pure(input)

    def sameMonth(input: StepInput)(using SimParams): Program[SameMonthBoundaryViews] =
      MonthWorkflow.pure(computeSameMonthBoundaryViews(input))

    def signalView(boundaries: SameMonthBoundaryViews): Program[MonthSemantics.SignalView] =
      MonthWorkflow.pure(MonthSemantics.signalView(boundaries.signals))

    def flowPlan(boundaries: SameMonthBoundaryViews): Program[MonthSemantics.FlowPlan] =
      MonthWorkflow.pure(MonthSemantics.flowPlan(boundaries.flowPlan))

    def closingInput(boundaries: SameMonthBoundaryViews): Program[MonthSemantics.ClosingInput] =
      MonthWorkflow.pure(MonthSemantics.closingInput(boundaries.closing))

    def semanticProjection(boundaries: SameMonthBoundaryViews): Program[MonthSemantics.SemanticProjection] =
      MonthWorkflow.pure(MonthSemantics.semanticProjection(boundaries.semanticProjection))

    def operational(signalView: MonthSemantics.SignalView): Program[MonthSemantics.Operational] =
      MonthWorkflow.pure(buildOperationalSignals(signalView))

    def closedMonth(
        pre: StepInput,
        closingInput: MonthSemantics.ClosingInput,
        signalView: MonthSemantics.SignalView,
    )(using SimParams): Program[MonthSemantics.ClosedMonth] =
      MonthWorkflow.pure(buildClosedMonthBoundary(closingInput, signalView, pre.randomness.closing.newStreams()))

    def seedOut(signalView: MonthSemantics.SignalView, closed: MonthSemantics.ClosedMonth): Program[MonthSemantics.SeedOut] =
      MonthWorkflow.pure(extractSeedOut(signalView, closed))

    def traceCore(
        pre: StepInput,
        closed: MonthSemantics.ClosedMonth,
        seedOut: MonthSemantics.SeedOut,
    ): Program[MonthTraceCore] =
      MonthWorkflow.pure(MonthTraceBuilder.core(pre, closed, seedOut))

  /** Full month-step contract.
    *
    * `stateIn -> StepOutput(nextState, trace)` is the explicit monthly
    * boundary. The runtime may still drive this incrementally, but the
    * architectural shape is a single transition from one [[SimState]] to the
    * next.
    */
  case class StepOutput(
      stateIn: SimState,
      executionMonth: ExecutionMonth,
      calculus: MonthlyCalculus,
      operationalSignals: OperationalSignals,
      signalExtraction: SignalExtraction.Output,
      randomness: MonthRandomness.Contract,
      flows: Vector[BatchedFlow],
      execution: ExecutionResult,
      semanticFlows: Sfc.SemanticFlows,
      sfcResult: Sfc.SfcResult,
      trace: MonthTrace,
      firmDecisionTraces: Vector[Firm.DecisionTrace],
      householdSnapshotState: HouseholdSnapshotState,
      householdMonthlyFlows: Vector[Household.MonthlyFlow],
      nextState: SimState,
  ):
    def transition: (SimState, MonthTrace) = (nextState, trace)

  /** Single-month explicit public boundary.
    *
    * [[com.boombustgroup.amorfati.engine.MonthDriver.unfoldSteps]] is the
    * preferred runtime entrypoint, but `step` remains public as the narrow
    * one-month contract used by tests, replay, and diagnostics.
    */
  def step(stateIn: SimState, randomness: MonthRandomness.Contract, traceFirmDecisions: Boolean = false)(using p: SimParams): StepOutput =
    given RuntimeLedgerTopology = RuntimeLedgerTopology.fromState(stateIn)
    val input                   = stepInput(stateIn, randomness, traceFirmDecisions)
    val outcome                 = computeMonthOutcome(input)
    val flows                   = MonthFlowEmitter.emitAllBatches(outcome.flowPlan.calculus)
    val execution               = RuntimeFlowExecutor.executeOrThrow(flows)
    val nextState               = NextStateAdvancer.advance(
      NextStateAdvancer.Input(
        stateIn = input.stateIn,
        executionMonth = input.executionMonth,
        seedIn = input.seedIn,
        closed = outcome.closed,
        seedOut = outcome.seedOut,
        execution = execution,
      ),
    )
    val sfcFlows                = SfcSemanticProjection.semanticFlows(outcome.semanticProjection, flows, nextState.world.plumbing.fofResidual)
    val sfcResult               = SfcSemanticProjection.validate(
      stateIn = stateIn,
      nextState = nextState,
      flows = sfcFlows,
      batches = flows,
      execution = execution,
    )
    val monthTrace              = MonthTraceBuilder.build(
      executionMonth = input.executionMonth,
      randomness = input.randomness,
      core = outcome.traceCore,
      endState = nextState,
      executedFlows = sfcFlows,
      sfcResult = sfcResult,
    )

    StepOutput(
      stateIn = stateIn,
      executionMonth = input.executionMonth,
      calculus = outcome.flowPlan.calculus,
      operationalSignals = outcome.operational.operationalSignals,
      signalExtraction = outcome.seedOut.signalExtraction,
      randomness = randomness,
      flows,
      execution,
      semanticFlows = sfcFlows,
      sfcResult,
      trace = monthTrace,
      firmDecisionTraces = outcome.semanticProjection.firms.decisionTraces,
      householdSnapshotState = HouseholdSnapshotState(
        households = outcome.semanticProjection.hhIncome.updatedHouseholds,
        ledgerFinancialState = outcome.semanticProjection.hhIncome.ledgerFinancialState,
      ),
      householdMonthlyFlows = outcome.semanticProjection.hhIncome.householdMonthlyFlows,
      nextState = nextState,
    )

  /** Public API: compute calculus only (for tests that need MonthlyCalculus).
    */
  def computeCalculus(state: SimState, randomness: MonthRandomness.Contract)(using p: SimParams): MonthlyCalculus =
    computeSameMonthBoundaryViews(stepInput(state, randomness, traceFirmDecisions = false)).flowPlan

  private def stepInput(
      stateIn: SimState,
      randomness: MonthRandomness.Contract,
      traceFirmDecisions: Boolean,
  ): StepInput =
    StepInput(
      stateIn = stateIn,
      executionMonth = stateIn.completedMonth.next,
      seedIn = MonthSemantics.seedIn(stateIn.world.seedIn),
      randomness = randomness,
      boundaryIn = MonthBoundarySnapshot.capture(stateIn.world, stateIn.firms, stateIn.households, stateIn.banks, stateIn.ledgerFinancialState),
      traceFirmDecisions = traceFirmDecisions,
    )

  /** Computes the same-month execution by chaining all Economics. Uses old
    * pipeline steps for HH/Demand/Firm/PriceEquity (pure calculus). Uses
    * self-contained OpenEconEconomics for monetary/external. Runs
    * BankingEconomics exactly once, then narrows the result into flow, signal,
    * closing, and SFC projection views so later boundaries do not depend on one
    * broad transport bag.
    */
  private def computeSameMonthBoundaryViews(input: StepInput)(using p: SimParams): SameMonthBoundaryViews =
    val randomness        = input.randomness.stages
    val stateIn           = input.stateIn
    val ledger            = stateIn.ledgerFinancialState
    val w                 = stateIn.world
    val firms             = stateIn.firms
    val households        = stateIn.households
    val banks             = stateIn.banks
    val s1                = FiscalConstraintEconomics.compute(w, banks, ledger, input.executionMonth)
    val s2Pre             = LaborEconomics.compute(w, firms, households, s1)
    val payroll           = SocialSecurity.payrollBase(households)
    val payrollZus        = SocialSecurity.zusStep(payroll, s2Pre.newDemographics.retirees)
    val s3                = HouseholdIncomeEconomics.compute(
      w,
      firms,
      households,
      banks,
      ledger,
      s1.lendingBaseRate,
      s1.resWage,
      s2Pre.newWage,
      randomness.householdIncomeEconomics.newStream(),
      pensionIncome = payrollZus.pensionPayments,
    )
    val s4                = DemandEconomics.compute(w, s2Pre.employed, s2Pre.living, s3.domesticCons)
    val s5                = FirmEconomics.runStep(
      w,
      firms,
      households,
      banks,
      ledger,
      s1,
      s2Pre,
      s3,
      s4,
      randomness.firmEconomics.newStream(),
      traceDecisions = input.traceFirmDecisions,
    )
    val postLivingFirms   = s5.ioFirms.filter(Firm.isAlive)
    val nBankruptFirms    = s5.firmDeaths
    val avgFirmWorkers    = if s2Pre.living.nonEmpty then s2Pre.employed / s2Pre.living.length else 0
    val payrollNfz        = SocialSecurity.nfzStep(payroll, s2Pre.newDemographics.workingAgePop, s2Pre.newDemographics.retirees)
    val payrollPpk        = SocialSecurity.ppkStep(payroll)
    val payrollEarmarked  = EarmarkedFunds.step(payroll, s3.hhAgg.totalUnempBenefits, nBankruptFirms, avgFirmWorkers)
    val s2Reconciled      = LaborEconomics.reconcilePostFirmStep(w, s1, s2Pre, postLivingFirms, s5.households)
    // Labor reconciliation refreshes employment/wage state after the firm step,
    // but social funds are pinned to the opening-boundary payroll for month t.
    val s2                = s2Reconciled.copy(
      newZus = payrollZus,
      newNfz = payrollNfz,
      newPpk = payrollPpk,
      newEarmarked = payrollEarmarked,
    )
    val s6                = HouseholdFinancialEconomics.compute(w, s1.m, s2.employed, s3.hhAgg, randomness.householdFinancialEconomics.newStream())
    val s7                = PriceEquityEconomics.compute(
      w = w,
      month = s1.m,
      wageGrowth = s2.wageGrowth,
      avgDemandMult = s4.avgDemandMult,
      sectorMults = s4.sectorMults,
      totalSystemLoans = ledger.banks.map(_.firmLoan).sumPln,
      firmStep = s5,
      ledgerFinancialState = ledger,
    )
    val s8                = OpenEconEconomics.runStep(
      OpenEconEconomics.StepInput(
        w,
        ledger,
        s1,
        s2,
        s3,
        s4,
        s5,
        s6,
        s7,
        banks,
        randomness.openEconEconomics.newStream(),
      ),
    )
    val bankingDepositRng = randomness.bankingEconomics.newStream()
    val s9                = BankingEconomics.runStep(
      BankingEconomics.StepInput(
        w,
        ledger,
        s1,
        s2,
        s3,
        s4,
        s5,
        s6,
        s7,
        s8,
        banks,
        bankingDepositRng,
      ),
    )
    val prevBankAgg       = Banking.aggregateFromBankStocks(
      banks,
      ledger.banks.map(LedgerFinancialState.projectBankFinancialStocks),
      bankId => CorporateBondOwnership.bankHolderFor(ledger, bankId),
    )
    val agg               = s3.hhAgg
    val h                 = s9.housingAfterFlows
    val externalFlowBop   = s8.external.flowBop
    val openingCorpBonds  = CorporateBondOwnership.stockStateFromLedger(ledger)
    val corpBondCoupon    = CorporateBondMarket.computeCoupon(w.financialMarkets.corporateBonds, openingCorpBonds)
    val corpBondIssuance  = CorporateBondMarket.processIssuance(CorporateBondMarket.StockState.zero, s5.actualBondIssuance)
    val laborForce        = s2.newDemographics.workingAgePop.max(1)
    val unemploymentRate  = Share.One - Share.fraction(Math.max(0, Math.min(s2.employed, laborForce)), laborForce)
    val equityRevaluation = equityRevaluationInput(ledger, s9.ledgerFinancialState)
    val calc              = MonthlyCalculus(
      month = s1.month,
      resWage = s1.resWage,
      lendingBaseRate = s1.lendingBaseRate,
      baseMinWage = s1.baseMinWage,
      minWagePriceLevel = s1.updatedMinWagePriceLevel,
      wage = s2.newWage,
      employed = s2.employed,
      payroll = payroll,
      zus = s2.newZus,
      ppk = s2.newPpk,
      earmarked = s2.newEarmarked,
      unemploymentRate = unemploymentRate,
      laborDemand = s2.laborDemand,
      livingFirms = s5.ioFirms.count(Firm.isAlive),
      retirees = s2Pre.newDemographics.retirees,
      workingAgePop = s2Pre.newDemographics.workingAgePop,
      nfz = s2.newNfz,
      nBankruptFirms = nBankruptFirms,
      avgFirmWorkers = avgFirmWorkers,
      totalIncome = s3.totalIncome,
      consumption = agg.consumption,
      domesticConsumption = s3.domesticCons,
      importConsumption = s3.importCons,
      totalRent = agg.totalRent,
      pitRevenue = s9.pitAfterEvasion,
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
      govCurrentSpend = s9.newGovWithYield.govCurrentSpend,
      firmTax = s5.sumTax,
      firmNewLoans = s5.sumNewLoans,
      firmPrincipal = s5.sumFirmPrincipal,
      firmInterestIncome = s5.intIncome,
      firmCapex = s5.sumCapex,
      firmEquityIssuance = s5.sumEquityIssuance,
      firmIoPayments = s5.totalIoPaid,
      firmNplLoss = s5.nplLoss,
      firmProfitShifting = s5.sumProfitShifting,
      firmFdiRepatriation = s5.sumFdiRepatriation,
      firmGrossInvestment = s5.sumGrossInvestment,
      investNetDepositFlow = s9.investNetDepositFlow,
      gdp = s7.gdp,
      inflation = s7.newInfl,
      equityDomDividends = s7.netDomesticDividends,
      equityForDividends = s7.foreignDividendOutflow,
      equityDivTax = s7.dividendTax,
      equityGovDividends = s7.stateOwnedGovDividends,
      equityReturn = s7.equityAfterForeignStock.monthlyReturn,
      exports = externalFlowBop.exports,
      totalImports = externalFlowBop.totalImports,
      tourismExport = s6.tourismExport,
      tourismImport = s6.tourismImport,
      fdi = externalFlowBop.fdi,
      portfolioFlows = externalFlowBop.portfolioFlows,
      carryTradeFlow = externalFlowBop.carryTradeFlow,
      capitalFlightOutflow = externalFlowBop.capitalFlightOutflow,
      primaryIncome = externalFlowBop.primaryIncome,
      euFunds = externalFlowBop.euFundsMonthly,
      diasporaInflow = s6.diasporaInflow,
      corpBondCoupon = s8.corpBonds.corpBondCoupon,
      corpBondDefaultAmount = s5.totalBondDefault,
      corpBondIssuance = s5.actualBondIssuance,
      corpBondAmortization = s8.corpBonds.corpBondAmort,
      corpBondCouponRecipients = corpBondCouponRecipients(corpBondCoupon),
      corpBondDefaultRecipients = allocateCorpBondReduction(s5.totalBondDefault, openingCorpBonds),
      corpBondIssuanceRecipients = corpBondStockRecipients(corpBondIssuance),
      corpBondAmortizationRecipients = allocateCorpBondReduction(s8.corpBonds.corpBondAmort, openingCorpBonds),
      mortgageOrigination = h.lastOrigination,
      mortgageRepayment = h.lastRepayment,
      mortgageInterest = h.mortgageInterestIncome,
      mortgageDefault = h.lastDefault,
      bankGovBondIncome = prevBankAgg.govBondHoldings * s8.monetary.newBondYield.monthly,
      bankReserveInterest = s8.banking.totalReserveInterest,
      bankStandingFacility = s8.banking.totalStandingFacilityIncome,
      bankStandingFacilityBackstop = s9.standingFacilityBackstop,
      bankInterbankInterest = s8.banking.totalInterbankInterest,
      bankCorpBondCoupon = s8.corpBonds.corpBondBankCoupon,
      bankCorpBondLoss = s8.corpBonds.corpBondBankDefaultLoss,
      bankFxReserveSettlement = s8.monetary.fxPlnInjection,
      bankBfgLevy = s9.bfgLevy,
      bankUnrealizedLoss = s9.unrealizedBondLoss,
      bankBailIn = s9.bailInLoss,
      bankNbpRemittance = s8.banking.nbpRemittance,
      equityRevaluation = equityRevaluation,
      nbfiDepositDrain = s8.nonBank.nbfiDepositDrain,
      nbfiOrigination = s9.finalNbfi.lastNbfiOrigination,
      nbfiRepayment = s9.finalNbfi.lastNbfiRepayment,
      nbfiDefaultAmount = s9.finalNbfi.lastNbfiDefaultAmount,
      qfBankBondIssuance = s9.newQuasiFiscal.monthlyBankBondIssuance,
      qfNbpBondAbsorption = s9.newQuasiFiscal.monthlyNbpBondAbsorption,
      qfBankBondAmortization = s9.newQuasiFiscal.monthlyBankBondAmortization,
      qfNbpBondAmortization = s9.newQuasiFiscal.monthlyNbpBondAmortization,
      qfLending = s9.newQuasiFiscal.monthlyLending,
      qfRepayment = s9.newQuasiFiscal.monthlyLoanRepayment,
      govVatRevenue = s9.vatAfterEvasion,
      govExciseRevenue = s9.exciseAfterEvasion,
      govCustomsDutyRevenue = s9.customsDutyRevenue,
      govDebtService = s8.banking.monthlyDebtService,
      govDebtServiceRecipients = GovBudgetFlows.DebtServiceRecipients.fromCircuit(GovernmentBondCircuit.from(ledger), s8.banking.monthlyDebtService),
      govBondRuntimeMovements = s9.govBondRuntimeMovements,
      govEuCofinancing = s9.newGovWithYield.euCofinancing,
      govCapitalSpend = s9.newGovWithYield.govCapitalSpend,
      insuranceCurrentLifeReserves = ledger.insurance.lifeReserve,
      insuranceCurrentNonLifeReserves = ledger.insurance.nonLifeReserve,
      insurancePrevGovBonds = ledger.insurance.govBondHoldings,
      insurancePrevCorpBonds = ledger.insurance.corpBondHoldings,
      insuranceCorpBondDefaultLoss = s8.corpBonds.corpBondInsuranceDefaultLoss,
      insurancePrevEquity = ledger.insurance.equityHoldings,
      govBondYield = s8.monetary.newBondYield,
      corpBondYield = s8.corpBonds.newCorpBonds.corpBondYield,
    )
    val execution         = MonthExecution(
      openingWorld = w,
      fiscal = s1,
      labor = s2,
      householdIncome = s3,
      demand = s4,
      firm = s5,
      householdFinancial = s6,
      priceEquity = s7,
      openEconomy = s8,
      banking = s9,
    )
    SameMonthBoundaryViews(
      flowPlan = calc,
      signals = SignalBoundaryInputs(
        labor = s2,
        demand = s4,
      ),
      closing = MonthClosing.prepareInput(execution),
      semanticProjection = SemanticFlowInputs(
        labor = s2,
        hhIncome = s3,
        firms = s5,
        hhFinancial = s6,
        prices = s7,
        openEcon = s8,
        banking = s9,
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

  private def operationalSignals(
      labor: LaborEconomics.StepOutput,
      demand: DemandEconomics.StepOutput,
  ): OperationalSignals =
    OperationalSignals(
      sectorDemandMult = demand.sectorMults,
      sectorDemandPressure = demand.sectorDemandPressure,
      sectorHiringSignal = demand.sectorHiringSignal,
      operationalHiringSlack = labor.operationalHiringSlack,
    )

  private def buildOperationalSignals(signalView: MonthSemantics.SignalView): MonthSemantics.Operational =
    MonthSemantics.operational(operationalSignals(signalView.labor, signalView.demand))

  private def buildClosedMonthBoundary(
      closingInput: MonthSemantics.ClosingInput,
      signalView: MonthSemantics.SignalView,
      randomness: MonthRandomness.ClosingStreams,
  )(using p: SimParams): MonthSemantics.ClosedMonth =
    val closing = MonthClosing.close(closingInput.monthClosingInput, randomness)
    // This stays at month `t`: the boundary seed is still `seedIn` here.
    MonthSemantics.closedMonth(
      ClosedMonthBoundary(
        closing = closing,
        boundaryOut = MonthBoundarySnapshot.capture(
          closing.world,
          closing.firms,
          closing.households,
          closing.banks,
          closing.ledgerFinancialState,
        ),
        timing = MonthTraceBuilder.timingTrace(signalView, closing),
      ),
    )

  private def extractSeedOut(signalView: MonthSemantics.SignalView, closed: MonthSemantics.ClosedMonth): MonthSemantics.SeedOut =
    val closing = closed.closing

    MonthSemantics.seedOut(
      // Seed extraction is the only place that derives the next boundary
      // signal from realized month-`t` outcomes.
      SignalExtraction.fromClosedMonth(
        world = closing.world,
        households = closing.households,
        operationalHiringSlack = signalView.labor.operationalHiringSlack,
        startupAbsorptionRate = closing.startupAbsorptionRate,
        demand = SignalExtraction.DemandOutcomes(
          sectorDemandMult = signalView.demand.sectorMults,
          sectorDemandPressure = signalView.demand.sectorDemandPressure,
          sectorHiringSignal = signalView.demand.sectorHiringSignal,
        ),
      ),
    )

  private def computeMonthOutcome(input: StepInput)(using p: SimParams): MonthOutcome =
    MonthWorkflow.run:
      for
        pre                <- MonthStepDsl.pre(input)
        sameMonth          <- MonthStepDsl.sameMonth(pre)
        signalView         <- MonthStepDsl.signalView(sameMonth)
        flowPlan           <- MonthStepDsl.flowPlan(sameMonth)
        closingInput       <- MonthStepDsl.closingInput(sameMonth)
        semanticProjection <- MonthStepDsl.semanticProjection(sameMonth)
        operational        <- MonthStepDsl.operational(signalView)
        closed             <- MonthStepDsl.closedMonth(pre, closingInput, signalView)
        seedOut            <- MonthStepDsl.seedOut(signalView, closed)
        traceCore          <- MonthStepDsl.traceCore(pre, closed, seedOut)
      yield MonthOutcome(
        operational = operational,
        flowPlan = flowPlan,
        semanticProjection = semanticProjection,
        closed = closed,
        seedOut = seedOut,
        traceCore = traceCore,
      )
