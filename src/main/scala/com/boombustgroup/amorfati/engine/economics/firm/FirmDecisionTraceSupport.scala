package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.Firm
import com.boombustgroup.amorfati.types.PLN

/** Shared helpers for keeping firm decision traces aligned with final
  * financing.
  */
private[firm] object FirmDecisionTraceSupport:

  def traceLoanComponents(trace: Firm.DecisionTrace, finalLoan: PLN, finalTechCreditAmount: PLN): TraceLoanComponents =
    val techCap        = trace.techCreditNeed.max(finalTechCreditAmount)
    val techAmount     = finalTechCreditAmount.max(PLN.Zero).min(finalLoan.max(PLN.Zero)).min(techCap)
    val investmentBase = (finalLoan - techAmount).max(PLN.Zero)
    val hasInvestment  = trace.investmentCreditNeed.exists(_ > PLN.Zero) || trace.investmentCreditAmount.exists(_ > PLN.Zero) || investmentBase > PLN.Zero
    val investmentCap  = trace.investmentCreditNeed.orElse(trace.investmentCreditAmount).getOrElse(investmentBase)
    TraceLoanComponents(
      techCreditAmount = techAmount,
      investmentCreditAmount = Option.when(hasInvestment)(investmentBase.min(investmentCap.max(PLN.Zero))),
    )
