package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.PerBankFlow
import com.boombustgroup.amorfati.agents.household.HouseholdStepTypes.*

/** Per-bank aggregation of finalized household monthly flows. */
private[agents] object HouseholdPerBankFlowAggregation:

  /** Builds per-bank household-flow totals from finalized per-household
    * results.
    */
  def buildPerBankFlows(flows: Vector[BankedMonthlyResult], nBanks: Int): Vector[PerBankFlow] =
    val acc = Array.fill(nBanks)(PerBankFlow.zero)
    var i   = 0
    while i < flows.length do
      val (bankId, r) = flows(i)
      val b           = bankId.toInt
      val cur         = acc(b)
      acc(b) = PerBankFlow(
        income = cur.income + r.income,
        consumption = cur.consumption + r.consumption + r.rent,
        debtService = cur.debtService + r.debtService,
        mortgageInterest = cur.mortgageInterest + r.mortgageInterest,
        depositInterest = cur.depositInterest + r.depositInterest,
        consumerDebtService = cur.consumerDebtService + r.credit.debtService,
        consumerOrigination = cur.consumerOrigination + r.credit.totalOrigination,
        consumerApprovedOrigination = cur.consumerApprovedOrigination + r.credit.newLoan,
        consumerBankRejectedOrigination = cur.consumerBankRejectedOrigination + r.credit.bankRejectedCreditDemand,
        liquidityShortfallFinancing = cur.liquidityShortfallFinancing + r.credit.liquidityShortfallFinancing,
        consumerDefault = cur.consumerDefault + r.credit.defaultAmt,
        consumerLoanDefault = cur.consumerLoanDefault + r.credit.consumerLoanDefault,
        consumerPrincipal = cur.consumerPrincipal + r.credit.principal,
      )
      i += 1
    acc.toVector
