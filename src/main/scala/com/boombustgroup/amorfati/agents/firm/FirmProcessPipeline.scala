package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.agents.Firm.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.{OperationalSignals, World}
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Orchestrates one firm month from decision selection to final diagnostics.
  *
  * The pipeline makes temporal order explicit: stochastic decision, pure
  * execution, debt service, trace projection, deterministic post-processing,
  * and final audit enrichment.
  */
private[agents] object FirmProcessPipeline:

  /** Runs the complete firm month while preserving the public `Firm` facade. */
  def processWithCreditAudit(
      firm: State,
      financialStocks: FinancialStocks,
      w: World,
      executionMonth: ExecutionMonth,
      operationalSignals: OperationalSignals,
      lendRate: Rate,
      bankCreditDecision: PLN => CreditDecision,
      allFirms: Vector[State],
      rng: RandomStream,
      corpBondDebt: PLN,
      traceDecision: Boolean,
  )(using p: SimParams): Result =
    val decision                =
      FirmDecisionEngine.decide(
        firm,
        financialStocks,
        w,
        executionMonth,
        operationalSignals,
        lendRate,
        bankCreditDecision,
        allFirms,
        rng,
        corpBondDebt,
      )
    val executed                = FirmResultExecution.execute(firm, financialStocks, decision.decision)
    val withHiringSignal        = FirmPostProcessing.updateHiringSignalState(executed, firm, w, operationalSignals)
    val afterDebtService        = FirmPostProcessing.applyLoanAmortization(withHiringSignal)
    val withOpeningTrace        =
      if traceDecision then
        afterDebtService.copy(decisionTrace = Some(FirmDecisionTraceBuilder.buildDecisionTrace(firm, financialStocks, lendRate, decision, afterDebtService)))
      else afterDebtService
    val afterGreenInvestment    = FirmPostProcessing.applyGreenInvestment(withOpeningTrace)
    val afterCapitalInvestment  =
      FirmPostProcessing.applyInvestment(afterGreenInvestment, operationalSignals.sectorDemandPressure(firm.sector.toInt), bankCreditDecision)
    val afterDigitalDrift       = FirmPostProcessing.applyDigitalDrift(afterCapitalInvestment)
    val afterInventory          = FirmPostProcessing.applyInventory(afterDigitalDrift, sectorDemandMult = operationalSignals.sectorDemandMult(firm.sector.toInt))
    val afterFdiFlows           = FirmPostProcessing.applyFdiFlows(afterInventory)
    val afterInformalCitEvasion = FirmPostProcessing.applyInformalCitEvasion(afterFdiFlows, w.mechanisms.informalCyclicalAdj)
    val withCreditDiagnostics   = FirmCreditAudit.applyTechCreditDiagnostics(afterInformalCitEvasion, decision)
    if traceDecision then
      withCreditDiagnostics.copy(decisionTrace =
        withCreditDiagnostics.decisionTrace.map(FirmDecisionTraceBuilder.refreshDecisionTraceClosing(_, withCreditDiagnostics)),
      )
    else withCreditDiagnostics
