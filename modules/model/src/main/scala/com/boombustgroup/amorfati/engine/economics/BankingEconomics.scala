package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.agents.{Banking, Household}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.economics.banking.{BankInterbankSettlement, BankingHouseholdBooks, BankingStepRunner}
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.types.PLN

/** Public compatibility facade for the banking economics boundary.
  *
  * The implementation lives in [[banking.BankingStepRunner]] so bank-specific
  * sub-stage results can evolve inside `engine.economics.banking` without
  * forcing broad import churn.
  */
object BankingEconomics:
  type StepInput = BankingStepRunner.StepInput
  val StepInput: BankingStepRunner.StepInput.type = BankingStepRunner.StepInput

  type StepOutput = BankingStepRunner.StepOutput
  val StepOutput: BankingStepRunner.StepOutput.type = BankingStepRunner.StepOutput

  type GovBondRuntimeMovements = com.boombustgroup.amorfati.engine.economics.banking.GovBondRuntimeMovements
  val GovBondRuntimeMovements: com.boombustgroup.amorfati.engine.economics.banking.GovBondRuntimeMovements.type =
    com.boombustgroup.amorfati.engine.economics.banking.GovBondRuntimeMovements

  private[amorfati] type ReserveSettlementResult = com.boombustgroup.amorfati.engine.economics.banking.ReserveSettlementResult

  def runStep(rawIn: StepInput)(using p: SimParams): StepOutput =
    BankingStepRunner.runStep(rawIn)

  private[economics] def alignConsumerLoanBookToHouseholdRouting(
      households: Vector[Household.State],
      householdBalances: Vector[LedgerFinancialState.HouseholdBalances],
      bankStocks: Vector[Banking.BankFinancialStocks],
  ): Vector[Banking.BankFinancialStocks] =
    BankingHouseholdBooks.alignConsumerLoanBookToHouseholdRouting(households, householdBalances, bankStocks)

  private[amorfati] def applyNbpReserveSettlement(
      banks: Vector[Banking.BankState],
      financialStocks: Vector[Banking.BankFinancialStocks],
      reserveInterest: Banking.PerBankAmounts,
      standingFacilityIncome: Banking.PerBankAmounts,
      interbankInterest: Banking.PerBankAmounts,
      fxInjection: PLN,
  ): ReserveSettlementResult =
    BankInterbankSettlement.applyNbpReserveSettlement(banks, financialStocks, reserveInterest, standingFacilityIncome, interbankInterest, fxInjection)

  private[amorfati] def distributeFxInjection(
      banks: Vector[Banking.BankState],
      financialStocks: Vector[Banking.BankFinancialStocks],
      injection: PLN,
  ): ReserveSettlementResult =
    BankInterbankSettlement.distributeFxInjection(banks, financialStocks, injection)
