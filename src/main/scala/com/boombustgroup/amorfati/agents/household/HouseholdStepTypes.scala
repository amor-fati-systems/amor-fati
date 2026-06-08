package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.{Banking, HhStatus, Household}
import com.boombustgroup.amorfati.types.*

/** Internal result types shared by household monthly execution modules. */
private[household] object HouseholdStepTypes:

  /** Consumer-credit and shortfall settlement result for one household. */
  final case class CreditResult(
      debtService: PLN,
      principal: PLN,
      interest: PLN,
      newLoan: PLN,
      creditDemand: PLN,
      rejectedCreditDemand: PLN,
      bankRejectedCreditDemand: PLN,
      creditCapacity: PLN,
      creditAccessEligible: Boolean,
      bankApproval: Option[Banking.CreditApproval],
      liquidityShortfall: Household.LiquidityShortfallComponents,
      defaultAmt: PLN,
      updatedDebt: PLN,
  ):
    def liquidityShortfallFinancing: PLN = liquidityShortfall.total
    def liquidityBridgeChargeOff: PLN    = liquidityShortfallFinancing
    def consumerLoanDefault: PLN         = (defaultAmt - liquidityBridgeChargeOff).max(PLN.Zero)
    def totalOrigination: PLN            = newLoan + liquidityShortfallFinancing

  /** Cash-priority result before residual liquidity settlement. */
  final case class ConsumptionWaterfall(
      desiredConsumption: PLN,
      actualConsumption: PLN,
      unmetBasicConsumption: PLN,
      discretionaryConsumptionCompression: PLN,
  )

  /** Income, consumption, credit, and stock deltas before the survival/default
    * branch is finalized.
    */
  final case class MonthlyFlows(
      hh: Household.State,
      financialStocks: Household.FinancialStocks,
      income: PLN,
      benefit: PLN,
      newStatus: HhStatus,
      debtService: PLN,
      mortgagePrincipal: PLN,
      mortgageInterest: PLN,
      depositInterest: PLN,
      remittance: PLN,
      pitTax: PLN,
      socialTransfer: PLN,
      credit: CreditResult,
      desiredConsumption: PLN,
      consumption: PLN,
      unmetBasicConsumption: PLN,
      discretionaryConsumptionCompression: PLN,
      newEquityWealth: PLN,
      newSavings: PLN,
      newDebt: PLN,
      newMortgageRemainingMonths: Int,
      neighborDistress: Share,
  )

  /** Finalized result for one household after labor, credit, liquidity, and
    * distress transitions.
    */
  final case class HhMonthlyResult(
      newState: Household.State,
      income: PLN,
      benefit: PLN,
      consumption: PLN,
      debtService: PLN,
      mortgagePrincipal: PLN,
      mortgageInterest: PLN,
      depositInterest: PLN,
      remittance: PLN,
      pitTax: PLN,
      socialTransfer: PLN,
      credit: CreditResult,
      voluntaryQuit: Int,
      retrainingAttempt: Int,
      retrainingSuccess: Int,
      equityWealth: PLN,
      rent: PLN,
      financialStocks: Household.FinancialStocks,
      unmetBasicConsumption: PLN,
      discretionaryConsumptionCompression: PLN,
  )

  /** Pairing used while preserving the bank id that should receive a household
    * flow.
    */
  type BankedMonthlyResult = (BankId, HhMonthlyResult)
