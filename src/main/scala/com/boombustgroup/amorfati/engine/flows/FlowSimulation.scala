package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.accounting.Sfc
import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.*
import com.boombustgroup.amorfati.engine.SimulationMonth.{CompletedMonth, ExecutionMonth}
import com.boombustgroup.amorfati.engine.closedmonth.MonthClosing
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

  type MonthlyCalculus = com.boombustgroup.amorfati.engine.flows.MonthlyCalculus
  val MonthlyCalculus: com.boombustgroup.amorfati.engine.flows.MonthlyCalculus.type =
    com.boombustgroup.amorfati.engine.flows.MonthlyCalculus

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
    *
    * Each field is a non-batch identity term needed by `Sfc.SemanticFlows`.
    * Runtime-covered cash legs stay sourced from executed batch evidence.
    */
  private[engine] case class SemanticFlowInputs(
      firmCredit: SemanticFlowInputs.FirmCreditEvidence,
      external: SemanticFlowInputs.ExternalBalanceEvidence,
      banking: SemanticFlowInputs.BankingBalanceSheetEvidence,
  )

  private[engine] object SemanticFlowInputs:

    /** Firm-credit stock movement that is validated by SFC outside runtime
      * batch execution.
      */
    case class FirmCreditEvidence(
        newNonPerformingLoans: PLN,
    )

    /** External-account stock-flow facts that currently enter SFC as same-month
      * semantic terms rather than emitted runtime batches.
      */
    case class ExternalBalanceEvidence(
        currentAccount: PLN,
        valuationEffect: PLN,
    )

    /** Bank balance-sheet identity terms not represented as first-class runtime
      * transfer batches.
      */
    case class BankingBalanceSheetEvidence(
        netGovernmentBondChange: PLN,
        capitalDestruction: PLN,
        interbankContagionLoss: PLN,
        htmRealizedLoss: PLN,
        eclProvisionChange: PLN,
        retainedIncome: PLN,
    )

    def fromExecution(execution: MonthExecution): SemanticFlowInputs =
      SemanticFlowInputs(
        firmCredit = FirmCreditEvidence(
          newNonPerformingLoans = execution.firm.nplNew,
        ),
        external = ExternalBalanceEvidence(
          currentAccount = execution.openEconomy.external.newBop.currentAccount,
          valuationEffect = execution.openEconomy.external.oeValuationEffect,
        ),
        banking = BankingBalanceSheetEvidence(
          netGovernmentBondChange = execution.banking.actualBondChange,
          capitalDestruction = execution.banking.multiCapDestruction,
          interbankContagionLoss = execution.banking.interbankContagionLoss,
          htmRealizedLoss = execution.banking.htmRealizedLoss,
          eclProvisionChange = execution.banking.eclProvisionChange,
          retainedIncome = execution.banking.bankCapitalDiagnostics.retainedIncome,
        ),
      )

  /** Same-month evidence needed by `StepOutput` diagnostics and exports.
    *
    * This is deliberately separate from SFC semantic projection so trace and
    * snapshot payloads do not widen the accounting validation boundary.
    */
  private[engine] case class StepEvidenceInputs(
      firmDecisionTraces: Vector[Firm.DecisionTrace],
      householdSnapshotState: HouseholdSnapshotState,
      householdMonthlyFlows: Vector[Household.MonthlyFlow],
  )

  private[engine] object StepEvidenceInputs:
    def fromExecution(execution: MonthExecution): StepEvidenceInputs =
      StepEvidenceInputs(
        firmDecisionTraces = execution.firm.decisionTraces,
        householdSnapshotState = HouseholdSnapshotState(
          households = execution.householdIncome.updatedHouseholds,
          ledgerFinancialState = execution.householdIncome.ledgerFinancialState,
        ),
        householdMonthlyFlows = execution.householdIncome.householdMonthlyFlows,
      )

  /** Downstream-specific same-month boundary views derived from one economics
    * execution.
    */
  private[flows] case class SameMonthBoundaryViews(
      flowPlan: MonthlyCalculus,
      signals: SignalBoundaryInputs,
      closing: MonthClosingInput,
      semanticProjection: SemanticFlowInputs,
      stepEvidence: StepEvidenceInputs,
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
      stepEvidence: MonthSemantics.StepEvidence,
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

    def stepEvidence(boundaries: SameMonthBoundaryViews): Program[MonthSemantics.StepEvidence] =
      MonthWorkflow.pure(MonthSemantics.stepEvidence(boundaries.stepEvidence))

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
      firmDecisionTraces = outcome.stepEvidence.firmDecisionTraces,
      householdSnapshotState = outcome.stepEvidence.householdSnapshotState,
      householdMonthlyFlows = outcome.stepEvidence.householdMonthlyFlows,
      nextState = nextState,
    )

  /** Typed month-step boundary for callers that need classifiable fail-fast
    * errors, such as diagnostics and Monte Carlo orchestration.
    */
  def stepEither(
      stateIn: SimState,
      randomness: MonthRandomness.Contract,
      traceFirmDecisions: Boolean = false,
  )(using p: SimParams): Either[EngineFailure, StepOutput] =
    try Right(step(stateIn, randomness, traceFirmDecisions = traceFirmDecisions))
    catch case failure: EngineFailure => Left(failure)

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
        stepEvidence       <- MonthStepDsl.stepEvidence(sameMonth)
        operational        <- MonthStepDsl.operational(signalView)
        closed             <- MonthStepDsl.closedMonth(pre, closingInput, signalView)
        seedOut            <- MonthStepDsl.seedOut(signalView, closed)
        traceCore          <- MonthStepDsl.traceCore(pre, closed, seedOut)
      yield MonthOutcome(
        operational = operational,
        flowPlan = flowPlan,
        semanticProjection = semanticProjection,
        stepEvidence = stepEvidence,
        closed = closed,
        seedOut = seedOut,
        traceCore = traceCore,
      )
