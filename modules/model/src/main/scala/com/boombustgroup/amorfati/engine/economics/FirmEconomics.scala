package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.agents.{Banking, Firm, Household}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.World
import com.boombustgroup.amorfati.engine.economics.firm.FirmStepRunner
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Public compatibility facade for the firm economics boundary.
  *
  * The implementation lives in [[firm.FirmStepRunner]] so firm-specific
  * sub-stage results can evolve inside `engine.economics.firm` without forcing
  * broad import churn across the month-calculus pipeline.
  */
object FirmEconomics:
  type StepOutput = FirmStepRunner.StepOutput
  val StepOutput: FirmStepRunner.StepOutput.type = FirmStepRunner.StepOutput

  private[amorfati] type FinancingChannelAmounts = FirmStepRunner.FinancingChannelAmounts

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
    FirmStepRunner.runStep(w, firms, households, banks, ledgerFinancialState, fiscal, labor, householdIncome, demand, rng, traceDecisions)

  private[amorfati] def financingChannelAmounts(newLoan: PLN, techNewLoan: PLN, workers: Int)(using p: SimParams): FinancingChannelAmounts =
    FirmStepRunner.financingChannelAmounts(newLoan, techNewLoan, workers)

  private[amorfati] def allocateAbsorbedBondIssuance(
      requestedByFirm: Vector[(FirmId, PLN)],
      actualBondIssuance: PLN,
      executionMonth: ExecutionMonth = ExecutionMonth.First,
  ): Map[FirmId, PLN] =
    FirmStepRunner.allocateAbsorbedBondIssuance(requestedByFirm, actualBondIssuance, executionMonth)

  private[amorfati] def automationTechLoanAmount(techBankLoan: PLN, techBondAmt: PLN, bondAmt: PLN, revertedBond: PLN): PLN =
    FirmStepRunner.automationTechLoanAmount(techBankLoan, techBondAmt, bondAmt, revertedBond)
