package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.{Banking, Firm, Household}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.economics.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.engine.{MonthWorkflow, World}
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Firm sector economics runner.
  *
  * The runner owns the public entry point and the ordered workflow only. Stage
  * implementation details live in package-local files so the firm economics
  * package evolves like the banking package: explicit boundary types, focused
  * stage modules, and a small orchestrator.
  */
object FirmStepRunner:
  type StepOutput = com.boombustgroup.amorfati.engine.economics.firm.StepOutput
  val StepOutput: com.boombustgroup.amorfati.engine.economics.firm.StepOutput.type =
    com.boombustgroup.amorfati.engine.economics.firm.StepOutput

  private[amorfati] type FinancingChannelAmounts =
    com.boombustgroup.amorfati.engine.economics.firm.FinancingChannelAmounts

  /** Run the full firm processing pipeline from already-computed same-month
    * stage outputs.
    */
  def runStep(
      w: World,
      firms: Vector[Firm.State],
      households: Vector[Household.State],
      banks: Vector[Banking.BankState],
      ledgerFinancialState: LedgerFinancialState,
      fiscal: FiscalConstraintEconomics.StepOutput,
      labor: LaborEconomics.StepOutput,
      householdIncome: HouseholdIncomeEconomics.StepOutput,
      demand: DemandEconomics.StepOutput,
      rng: RandomStream,
      traceDecisions: Boolean = false,
  )(using p: SimParams): StepOutput =
    val stepIn = StepInput(w, firms, households, banks, ledgerFinancialState, fiscal, labor, householdIncome, demand, traceDecisions)
    runInternal(stepIn, rng)

  private def runInternal(stepIn: StepInput, rng: RandomStream)(using p: SimParams): StepOutput =
    MonthWorkflow.run:
      for
        lending             <- FirmEconomicsStepDsl.lending(stepIn, rng)
        firmProcessing      <- FirmEconomicsStepDsl.firmProcessing(stepIn, lending, rng)
        bonded              <- FirmEconomicsStepDsl.bondAbsorption(stepIn, firmProcessing, lending)
        intermediate        <- FirmEconomicsStepDsl.intermediateMarket(stepIn, bonded)
        pricing             <- FirmEconomicsStepDsl.calvoPricing(stepIn, intermediate, rng)
        laborMarket         <- FirmEconomicsStepDsl.laborMarket(stepIn, pricing, rng)
        staffedFirms        <- FirmEconomicsStepDsl.staffing(pricing, laborMarket)
        npl                 <- FirmEconomicsStepDsl.defaultsAndInterest(stepIn, intermediate, bonded, staffedFirms, lending)
        issuerSettledLedger <- FirmEconomicsStepDsl.issuerDebtSettlement(bonded, npl)
        output              <- FirmEconomicsStepDsl.close(
          stepIn,
          firmProcessing,
          bonded,
          intermediate,
          pricing,
          laborMarket,
          staffedFirms,
          npl,
          issuerSettledLedger,
          lending,
        )
      yield output

  private[amorfati] def financingChannelAmounts(newLoan: PLN, techNewLoan: PLN, workers: Int)(using p: SimParams): FinancingChannelAmounts =
    FirmFinancing.financingChannelAmounts(newLoan, techNewLoan, workers)

  private[amorfati] def allocateAbsorbedBondIssuance(
      requestedByFirm: Vector[(FirmId, PLN)],
      actualBondIssuance: PLN,
      executionMonth: ExecutionMonth = ExecutionMonth.First,
  ): Map[FirmId, PLN] =
    FirmBondAbsorption.allocateAbsorbedBondIssuance(requestedByFirm, actualBondIssuance, executionMonth)

  private[amorfati] def automationTechLoanAmount(techBankLoan: PLN, techBondAmt: PLN, bondAmt: PLN, revertedBond: PLN): PLN =
    FirmFinancing.automationTechLoanAmount(techBankLoan, techBondAmt, bondAmt, revertedBond)
