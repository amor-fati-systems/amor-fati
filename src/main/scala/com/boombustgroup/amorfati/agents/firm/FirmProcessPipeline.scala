package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.agents.Firm.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.MonthWorkflow
import com.boombustgroup.amorfati.engine.{OperationalSignals, World}
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.amorfati.agents.firm.FirmStepSemantics.*

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
    val stepInput = Input(
      firm = firm,
      financialStocks = financialStocks,
      world = w,
      executionMonth = executionMonth,
      operationalSignals = operationalSignals,
      lendRate = lendRate,
      bankCreditDecision = bankCreditDecision,
      allFirms = allFirms,
      rng = rng,
      corpBondDebt = corpBondDebt,
      traceDecision = traceDecision,
    )
    MonthWorkflow.run:
      for
        opening  <- FirmStepDsl.open(stepInput)
        decision <- FirmStepDsl.decide(opening)
        executed <- FirmStepDsl.execute(opening, decision)
        settled  <- FirmStepDsl.settle(opening, decision, executed)
        audited  <- FirmStepDsl.audit(decision, settled)
        closed   <- FirmStepDsl.close(opening, audited)
      yield closed.result
