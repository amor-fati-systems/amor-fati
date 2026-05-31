package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.Household
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Mortgage amortization helpers for household financial-stock rows. */
private[agents] object HouseholdMortgageSchedule:

  /** Resolves the active mortgage maturity used for scheduled principal. */
  def activeMortgageRemainingMonths(stocks: Household.FinancialStocks)(using p: SimParams): Int =
    if stocks.mortgageLoan <= PLN.Zero then 0
    else if stocks.mortgageRemainingMonths > 0 then stocks.mortgageRemainingMonths
    else p.housing.mortgageMaturity

  /** Computes the scheduled secured principal repayment for the current month.
    */
  def scheduledMortgagePrincipal(stocks: Household.FinancialStocks)(using p: SimParams): PLN =
    val remainingMonths = activeMortgageRemainingMonths(stocks)
    if remainingMonths <= 0 then PLN.Zero
    else stocks.mortgageLoan / remainingMonths

  /** Advances contractual mortgage maturity after a monthly principal payment.
    */
  def remainingMortgageMonthsAfterPayment(stocks: Household.FinancialStocks, closingMortgageLoan: PLN)(using p: SimParams): Int =
    if closingMortgageLoan <= PLN.Zero then 0
    else (activeMortgageRemainingMonths(stocks).max(1) - 1).max(1)
