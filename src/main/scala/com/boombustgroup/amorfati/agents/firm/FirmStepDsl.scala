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
    val in = opening.input
    MonthWorkflow.pure(
      FirmStepSemantics.selectedDecision(
        FirmDecisionEngine.decide(
          in.firm,
          in.financialStocks,
          in.world,
          in.executionMonth,
          in.operationalSignals,
          in.lendRate,
          in.bankCreditDecision,
          in.allFirms,
          in.rng,
          in.corpBondDebt,
        ),
      ),
    )

  /** Applies the selected decision into primary state and flow fields. */
  def execute(opening: OpeningInput, decision: SelectedDecision)(using SimParams): Program[PrimaryExecution] =
    val in       = opening.input
    val selected = decision.decisionWithAudit
    MonthWorkflow.pure(FirmStepSemantics.primaryExecution(FirmResultExecution.execute(in.firm, in.financialStocks, selected.decision)))

  /** Settles deterministic same-month effects after primary execution.
    *
    * This preserves trace timing: opening trace is projected after scheduled
    * debt service, while closing trace waits until the audited closed result.
    */
  def settle(opening: OpeningInput, decision: SelectedDecision, executed: PrimaryExecution)(using SimParams): Program[SettledResult] =
    val in                        = opening.input
    val selected                  = decision.decisionWithAudit
    val signaled                  = FirmPostProcessing.updateHiringSignalState(executed.result, in.firm, in.world, in.operationalSignals)
    val debtSettled               = FirmPostProcessing.applyLoanAmortization(signaled)
    val tracedOpening             =
      if in.traceDecision then
        debtSettled.copy(decisionTrace = Some(FirmDecisionTraceBuilder.buildDecisionTrace(in.firm, in.financialStocks, in.lendRate, selected, debtSettled)))
      else debtSettled
    val greenInvested             = FirmPostProcessing.applyGreenInvestment(tracedOpening)
    val capitalInvested           =
      FirmPostProcessing.applyInvestment(greenInvested, in.operationalSignals.sectorDemandPressure(in.firm.sector.toInt), in.bankCreditDecision)
    val digitalReadinessDrifted   = FirmPostProcessing.applyDigitalDrift(capitalInvested)
    val inventorySettled          =
      FirmPostProcessing.applyInventory(digitalReadinessDrifted, sectorDemandMult = in.operationalSignals.sectorDemandMult(in.firm.sector.toInt))
    val fdiRepatriationSettled    = FirmPostProcessing.applyFdiFlows(inventorySettled)
    val informalCitEvasionSettled = FirmPostProcessing.applyInformalCitEvasion(fdiRepatriationSettled, in.world.mechanisms.informalCyclicalAdj)
    MonthWorkflow.pure(FirmStepSemantics.settledResult(informalCitEvasionSettled))

  /** Enriches closed monetary fields with selected and candidate credit
    * diagnostics.
    */
  def audit(decision: SelectedDecision, settled: SettledResult): Program[AuditedResult] =
    MonthWorkflow.pure(FirmStepSemantics.auditedResult(FirmCreditAudit.applyTechCreditDiagnostics(settled.result, decision.decisionWithAudit)))

  /** Finalizes the firm month and refreshes closing trace fields when tracing
    * is enabled.
    */
  def close(opening: OpeningInput, audited: AuditedResult)(using SimParams): Program[ClosedResult] =
    val in     = opening.input
    val result = audited.result
    val closed =
      if in.traceDecision then result.copy(decisionTrace = result.decisionTrace.map(FirmDecisionTraceBuilder.refreshDecisionTraceClosing(_, result)))
      else result
    MonthWorkflow.pure(FirmStepSemantics.closedResult(closed))
