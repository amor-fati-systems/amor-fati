package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.MonthWorkflow
import com.boombustgroup.amorfati.engine.MonthWorkflow.Program
import com.boombustgroup.amorfati.engine.MonthWorkflow.*
import com.boombustgroup.amorfati.agents.firm.FirmStepSemantics.*

/** Deterministic settlement sub-pipeline after primary firm decision execution.
  *
  * Each method advances one named settlement boundary. This keeps `FirmStepDsl`
  * focused on the high-level month shape while preserving the exact trace
  * timing used by diagnostics.
  */
private[agents] object FirmSettlementDsl:

  /** Runs all deterministic settlement phases after primary execution. */
  def run(opening: OpeningInput, decision: SelectedDecision, executed: PrimaryExecution)(using SimParams): Program[SettledResult] =
    for
      signaled      <- hiringSignal(opening, decision, executed)
      debtSettled   <- debtService(signaled)
      traced        <- openingTrace(opening, decision, debtSettled)
      green         <- greenInvestment(traced)
      capital       <- capitalInvestment(opening, green)
      digitalDrift  <- digitalReadinessDrift(capital)
      inventory     <- inventorySettlement(opening, digitalDrift)
      fdi           <- fdiSettlement(inventory)
      informalTaxes <- informalCitEvasion(opening, fdi)
    yield informalTaxes

  /** Persists the demand signal used by next-month labor planning. */
  private def hiringSignal(opening: OpeningInput, decision: SelectedDecision, executed: PrimaryExecution)(using SimParams): Program[HiringSignalUpdate] =
    val state         = opening.input.opening
    val cachedDesired = decision.decisionWithAudit.audit.desiredWorkers
    MonthWorkflow.pure(
      FirmStepSemantics.hiringSignalUpdate(
        FirmPostProcessing.updateHiringSignalState(executed.result, state.firm, state.world, state.operationalSignals, cachedDesiredWorkers = cachedDesired),
      ),
    )

  /** Applies scheduled loan amortization before opening trace projection. */
  private def debtService(signaled: HiringSignalUpdate)(using SimParams): Program[DebtSettlement] =
    MonthWorkflow.pure(FirmStepSemantics.debtSettlement(FirmPostProcessing.applyLoanAmortization(signaled.result)))

  /** Projects the opening decision trace after debt service and before
    * deterministic post-processing mutates closing values.
    */
  private def openingTrace(opening: OpeningInput, decision: SelectedDecision, debtSettled: DebtSettlement)(using SimParams): Program[OpeningTrace] =
    val in       = opening.input
    val state    = in.opening
    val credit   = in.credit
    val env      = in.environment
    val selected = decision.decisionWithAudit
    val result   =
      if env.traceDecision then
        debtSettled.result.copy(decisionTrace =
          Some(FirmDecisionTraceBuilder.buildDecisionTrace(state.firm, state.financialStocks, credit.lendRate, selected, debtSettled.result)),
        )
      else debtSettled.result
    MonthWorkflow.pure(FirmStepSemantics.openingTrace(result))

  /** Applies green-capital replacement and expansion investment. */
  private def greenInvestment(traced: OpeningTrace)(using SimParams): Program[GreenInvestment] =
    MonthWorkflow.pure(FirmStepSemantics.greenInvestment(FirmPostProcessing.applyGreenInvestment(traced.result)))

  /** Applies physical-capital investment and investment-credit diagnostics. */
  private def capitalInvestment(opening: OpeningInput, green: GreenInvestment)(using SimParams): Program[CapitalInvestment] =
    val in     = opening.input
    val state  = in.opening
    val credit = in.credit
    MonthWorkflow.pure(
      FirmStepSemantics.capitalInvestment(
        FirmPostProcessing.applyInvestment(green.result, state.operationalSignals.sectorDemandPressure(state.firm.sector.toInt), credit.bankCreditDecision),
      ),
    )

  /** Applies natural monthly digital-readiness drift. */
  private def digitalReadinessDrift(capital: CapitalInvestment)(using SimParams): Program[DigitalDrift] =
    MonthWorkflow.pure(FirmStepSemantics.digitalDrift(FirmPostProcessing.applyDigitalDrift(capital.result)))

  /** Settles inventory adjustment and stress liquidation. */
  private def inventorySettlement(opening: OpeningInput, digitalDrift: DigitalDrift)(using SimParams): Program[InventorySettlement] =
    val state = opening.input.opening
    MonthWorkflow.pure(
      FirmStepSemantics.inventorySettlement(
        FirmPostProcessing.applyInventory(digitalDrift.result, sectorDemandMult = state.operationalSignals.sectorDemandMult(state.firm.sector.toInt)),
      ),
    )

  /** Applies FDI profit repatriation for foreign-owned firms. */
  private def fdiSettlement(inventory: InventorySettlement)(using SimParams): Program[FdiSettlement] =
    MonthWorkflow.pure(FirmStepSemantics.fdiSettlement(FirmPostProcessing.applyFdiFlows(inventory.result)))

  /** Applies informal-economy CIT evasion using the current carried adjustment.
    */
  private def informalCitEvasion(opening: OpeningInput, fdi: FdiSettlement)(using SimParams): Program[SettledResult] =
    val state = opening.input.opening
    MonthWorkflow.pure(FirmStepSemantics.settledResult(FirmPostProcessing.applyInformalCitEvasion(fdi.result, state.world.mechanisms.informalCyclicalAdj)))
