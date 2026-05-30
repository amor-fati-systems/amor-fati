package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.accounting.Sfc
import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.*
import com.boombustgroup.amorfati.engine.SimulationMonth.{CompletedMonth, ExecutionMonth}
import com.boombustgroup.amorfati.engine.assembly.MonthClosing
import com.boombustgroup.amorfati.engine.economics.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.*

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
  private[flows] case class SameMonthBoundaryViews(
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
      MonthWorkflow.pure(MonthCalculusRunner.run(input))

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
    MonthCalculusRunner.run(stepInput(state, randomness, traceFirmDecisions = false)).flowPlan

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
