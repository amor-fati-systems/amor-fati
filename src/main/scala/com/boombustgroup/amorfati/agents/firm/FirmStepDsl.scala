package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.MonthWorkflow
import com.boombustgroup.amorfati.engine.MonthWorkflow.Program
import com.boombustgroup.amorfati.agents.firm.FirmStepSemantics.*

/** Identity-DSL for the firm agent month.
  *
  * The methods are deliberately thin: they name the temporal boundary, call the
  * existing pure module, and return the next phase-tagged value.
  */
private[agents] object FirmStepDsl:

  /** Opens one firm month from the public `Firm.process` argument surface. */
  def open(input: Input): Program[OpeningInput] =
    MonthWorkflow.pure(FirmStepSemantics.opening(input))

  /** Selects the stochastic firm decision without mutating firm state. */
  def decide(opening: OpeningInput)(using SimParams): Program[SelectedDecision] =
    val in     = opening.input
    val state  = in.opening
    val credit = in.credit
    val env    = in.environment
    MonthWorkflow.pure(
      FirmStepSemantics.selectedDecision(
        FirmDecisionEngine.decide(
          state.firm,
          state.financialStocks,
          state.world,
          state.executionMonth,
          state.operationalSignals,
          credit.lendRate,
          credit.bankCreditDecision,
          env.allFirms,
          env.rng,
          state.corpBondDebt,
        ),
      ),
    )

  /** Applies the selected decision into primary state and flow fields. */
  def execute(opening: OpeningInput, decision: SelectedDecision)(using SimParams): Program[PrimaryExecution] =
    val state    = opening.input.opening
    val selected = decision.decisionWithAudit
    MonthWorkflow.pure(FirmStepSemantics.primaryExecution(FirmResultExecution.execute(state.firm, state.financialStocks, selected.decision)))

  /** Settles deterministic same-month effects after primary execution. */
  def settle(opening: OpeningInput, decision: SelectedDecision, executed: PrimaryExecution)(using SimParams): Program[SettledResult] =
    FirmSettlementDsl.run(opening, decision, executed)

  /** Enriches closed monetary fields with selected and candidate credit
    * diagnostics.
    */
  def audit(decision: SelectedDecision, settled: SettledResult): Program[AuditedResult] =
    MonthWorkflow.pure(FirmStepSemantics.auditedResult(FirmCreditAudit.applyTechCreditDiagnostics(settled.result, decision.decisionWithAudit)))

  /** Finalizes the firm month and refreshes closing trace fields when tracing
    * is enabled.
    */
  def close(opening: OpeningInput, audited: AuditedResult)(using SimParams): Program[ClosedResult] =
    val env    = opening.input.environment
    val result = audited.result
    val closed =
      if env.traceDecision then result.copy(decisionTrace = result.decisionTrace.map(FirmDecisionTraceBuilder.refreshDecisionTraceClosing(_, result)))
      else result
    MonthWorkflow.pure(FirmStepSemantics.closedResult(closed))
