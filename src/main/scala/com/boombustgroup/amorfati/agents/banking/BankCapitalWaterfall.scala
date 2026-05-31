package com.boombustgroup.amorfati.agents.banking

import com.boombustgroup.amorfati.agents.Banking.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Computes the monthly regulatory-capital P&L waterfall for a single bank.
  *
  * This module keeps ordinary retained income and realized banking losses in
  * one auditable formula. Failure destruction, bail-in, and exactness
  * reconciliation remain separate stages.
  */
private[agents] object BankCapitalWaterfall:

  /** Applies the ordinary bank-income and loss waterfall used by the
    * BankCapital SFC identity.
    */
  def computeCapitalDelta(in: CapitalPnlInput)(using p: SimParams): CapitalPnlOutput =
    val losses         = in.nplLoss + in.mortgageNplLoss + in.consumerNplLoss +
      in.corpBondDefaultLoss + in.bfgLevy + in.unrealizedBondLoss
    val grossIncome    = in.intIncome + in.bondIncome - in.depositInterest +
      in.reserveInterest + in.standingFacilityIncome + in.interbankInterest +
      in.mortgageInterestIncome + in.consumerInterestIncome + in.corpBondCoupon
    val retainedIncome = grossIncome * p.banking.profitRetention
    CapitalPnlOutput(newCapital = in.prevCapital - losses + retainedIncome)
