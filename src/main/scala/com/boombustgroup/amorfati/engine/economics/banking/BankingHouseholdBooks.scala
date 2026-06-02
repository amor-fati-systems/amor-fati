package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.{Banking, Household}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.Distribute

/** Household-facing banking book helpers: consumer-loan routing alignment,
  * final aggregate recomputation, and investment deposit timing.
  */
private[amorfati] object BankingHouseholdBooks:

  /** Investment timing deposit settlement: lagged domestic investment demand
    * minus current domestic investment spending.
    */
  private[banking] def investmentTimingDepositFlow(in: StepInput)(using p: SimParams): PLN =
    val currentInvestDomestic = in.firm.sumGrossInvestment * (Share.One - p.capital.importShare) +
      in.firm.sumGreenInvestment * (Share.One - p.climate.greenImportShare)
    in.demand.laggedInvestDemand - currentInvestDomestic

  /** Re-distribute the opening unsecured consumer-loan book across banks using
    * the current household bank routing as weights, while preserving the exact
    * aggregate stock.
    *
    * The model has a single household `bankId` reused for origination, debt
    * service, and default routing. Deposit mobility / bank reassignment can
    * change that routing key without changing the aggregate unsecured loan
    * stock. If we leave the pre-scaling per-bank book untouched, a bank can
    * receive more consumer-loan outflows than the stock it still carries,
    * forcing per-bank zero clipping and eventually breaking aggregate SFC
    * exactness. This helper keeps the aggregate book constant but rebalances
    * its distribution to the routing key used by the current-month household
    * flows.
    */
  private[economics] def alignConsumerLoanBookToHouseholdRouting(
      households: Vector[Household.State],
      householdBalances: Vector[LedgerFinancialState.HouseholdBalances],
      bankStocks: Vector[Banking.BankFinancialStocks],
  ): Vector[Banking.BankFinancialStocks] =
    require(
      households.length == householdBalances.length,
      s"BankingHouseholdBooks.alignConsumerLoanBookToHouseholdRouting requires aligned households and balances, got ${households.length} households and ${householdBalances.length} balance rows",
    )
    val totalBook = bankStocks.map(_.consumerLoan).sumPln
    if bankStocks.isEmpty || totalBook <= PLN.Zero then bankStocks
    else
      val bankWeights       = Array.fill(bankStocks.length)(0L)
      var i                 = 0
      while i < households.length do
        val hh        = households(i)
        val balances  = householdBalances(i)
        val bankIndex = hh.bankId.toInt
        if bankIndex >= 0 && bankIndex < bankWeights.length && balances.consumerLoan > PLN.Zero then
          bankWeights(bankIndex) += balances.consumerLoan.distributeRaw
        i += 1
      var hasPositiveWeight = false
      var weightIndex       = 0
      while weightIndex < bankWeights.length && !hasPositiveWeight do
        hasPositiveWeight = bankWeights(weightIndex) > 0L
        weightIndex += 1
      if !hasPositiveWeight then bankStocks
      else
        val redistributed     = Distribute.distribute(totalBook.distributeRaw, bankWeights)
        val redistributedRows = new Array[Banking.BankFinancialStocks](bankStocks.length)
        var bankIndex         = 0
        while bankIndex < bankStocks.length do
          redistributedRows(bankIndex) = bankStocks(bankIndex).copy(consumerLoan = PLN.fromRaw(redistributed(bankIndex)))
          bankIndex += 1
        redistributedRows.toVector

  /** Recompute household aggregates from final households. */
  private[banking] def computeFinalAggregates(in: StepInput)(using SimParams): Household.Aggregates =
    Household
      .computeAggregates(
        in.firm.households,
        in.firm.ledgerFinancialState.households.map(LedgerFinancialState.projectHouseholdFinancialStocks),
        in.labor.newWage,
        in.fiscal.resWage,
        in.householdIncome.importAdj,
        in.householdIncome.hhAgg.retrainingAttempts,
        in.householdIncome.hhAgg.retrainingSuccesses,
      )
      .withFlowTotalsFrom(in.householdIncome.hhAgg)
