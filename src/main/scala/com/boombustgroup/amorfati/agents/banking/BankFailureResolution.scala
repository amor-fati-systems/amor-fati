package com.boombustgroup.amorfati.agents.banking

import com.boombustgroup.amorfati.agents.Banking.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.mechanisms.Macroprudential
import com.boombustgroup.amorfati.types.*

/** Detects bank failures and applies depositor and survivor-bank resolution
  * mechanics.
  *
  * Failure detection classifies regulatory triggers. Resolution keeps the
  * failed-bank shell explicit while transferring supported financial stocks to
  * the healthiest survivor.
  */
private[agents] object BankFailureResolution:

  /** Classifies the first active failure trigger for a bank, if any. */
  def failureReason(
      bank: BankState,
      financialStocks: BankFinancialStocks,
      ccyb: Multiplier,
      bankCorpBondHoldings: BankCorpBondHoldings,
  )(using p: SimParams): Option[BankFailureReason] =
    if bank.failed then None
    else
      val minCar    = Macroprudential.effectiveMinCar(bank.id.toInt, ccyb)
      val lowCar    = BankRegulatoryMetrics.car(bank, financialStocks, bankCorpBondHoldings(bank.id)) < minCar
      val lcrBreach = BankRegulatoryMetrics.lcr(financialStocks) < p.banking.lcrMin * Share.decimal(5, 1)
      val newConsec = if lowCar then bank.consecutiveLowCar + 1 else 0
      val insolvent = bank.capital < PLN.Zero
      if insolvent then Some(BankFailureReason.NegativeCapital)
      else if newConsec >= 3 then Some(BankFailureReason.CarBreach)
      else if lcrBreach then Some(BankFailureReason.LiquidityBreach)
      else None

  /** Advances failure status for all banks and emits failure events for newly
    * failed rows.
    */
  def checkFailures(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      month: ExecutionMonth,
      enabled: Boolean,
      ccyb: Multiplier,
      bankCorpBondHoldings: BankCorpBondHoldings,
  )(using p: SimParams): FailureCheckResult =
    val rows = BankRows.from(banks, financialStocks, "Banking.checkFailures")
    if !enabled then
      FailureCheckResult(
        banks = banks.map: b =>
          b.status match
            case BankStatus.Active(_) => b.copy(status = BankStatus.Active(0))
            case _                    => b
        ,
        anyFailed = false,
      )
    else
      val checked = rows.map: (b, stocks) =>
        if b.failed then (b, None)
        else
          val minCar    = Macroprudential.effectiveMinCar(b.id.toInt, ccyb)
          val lowCar    = BankRegulatoryMetrics.car(b, stocks, bankCorpBondHoldings(b.id)) < minCar
          val newConsec = if lowCar then b.consecutiveLowCar + 1 else 0
          val reason    = failureReason(b, stocks, ccyb, bankCorpBondHoldings)
          reason match
            case Some(r) => (b.copy(status = BankStatus.Failed(month), capital = PLN.Zero), Some(FailureEvent(b.id, month, r)))
            case None    => (b.copy(status = BankStatus.Active(newConsec)), None)
      val updated = checked.map(_._1)
      val events  = checked.flatMap(_._2)
      FailureCheckResult(updated, events.nonEmpty, events)

  /** Computes the monthly BFG levy from live-bank deposit stocks. */
  def computeBfgLevy(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks])(using p: SimParams): PerBankAmounts =
    val rows    = BankRows.from(banks, financialStocks, "Banking.computeBfgLevy")
    val perBank =
      rows.map: (b, stocks) =>
        if b.failed then PLN.Zero
        else stocks.totalDeposits * p.banking.bfgLevyRate.monthly
    PerBankAmounts(perBank, perBank.sumPln)

  /** Applies one-time uninsured-deposit haircuts for failed banks selected for
    * bail-in.
    */
  def applyBailIn(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], eligibleBankIds: Set[BankId])(using
      p: SimParams,
  ): BailInResult =
    val rows        = BankRows.from(banks, financialStocks, "Banking.applyBailIn")
    val withHaircut =
      rows.map: (b, stocks) =>
        val unprocessedDeposits = (stocks.totalDeposits - stocks.bailedInDeposits).max(PLN.Zero)
        if b.failed && eligibleBankIds.contains(b.id) && unprocessedDeposits > PLN.Zero then
          val guaranteed        = unprocessedDeposits.min(p.banking.bfgDepositGuarantee)
          val uninsured         = unprocessedDeposits - guaranteed
          val haircut           = uninsured * p.banking.bailInDepositHaircut
          val closingDeposits   = stocks.totalDeposits - haircut
          val processedDeposits = (stocks.bailedInDeposits + unprocessedDeposits - haircut).min(closingDeposits).max(PLN.Zero)
          (stocks.copy(totalDeposits = closingDeposits, bailedInDeposits = processedDeposits), haircut)
        else (stocks, PLN.Zero)
    BailInResult(banks, withHaircut.map(_._1), withHaircut.map(_._2).sumPln)

  /** Transfers failed-bank financial stocks to the healthiest survivor and
    * leaves failed shells emptied.
    */
  def resolveFailures(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      bankCorpBondHoldings: Vector[PLN],
  ): ResolutionResult =
    val rows           = BankRows.from(banks, financialStocks, "Banking.resolveFailures")
    val holderBalances = Vector.tabulate(rows.length)(index => bankCorpBondHoldings.lift(index).getOrElse(PLN.Zero))
    val newlyFailed    = rows.filter((b, stocks) => b.failed && stocks.totalDeposits > PLN.Zero)
    if newlyFailed.isEmpty then ResolutionResult(banks, financialStocks, BankId.NoBank, holderBalances)
    else
      if rows.forallBanks(_.failed) then
        throw IllegalStateException(
          "Banking.resolveFailures encountered an all-failed banking sector with unresolved deposits. Explicit bridge-bank recapitalization or all-failed shutdown semantics are required; refusing to resurrect a failed bank as Active(0).",
        )
      val absorberId     = healthiestBankId(banks, financialStocks, BankRegulatoryMetrics.bankCorpBondHoldingsFromVector(holderBalances))
      val toAbsorb       = newlyFailed.filter((bank, _) => bank.id != absorberId)
      val addDep         = toAbsorb.map(_._2.totalDeposits).sumPln
      val addLoans       = toAbsorb.map((bank, stocks) => stocks.firmLoan - bank.nplAmount).sumPln
      val addAfs         = toAbsorb.map(_._2.govBondAfs).sumPln
      val addHtm         = toAbsorb.map(_._2.govBondHtm).sumPln
      val addCorpB       = toAbsorb.flatMap((bank, _) => holderBalances.lift(bank.id.toInt)).sumPln
      val addCC          = toAbsorb.map(_._2.consumerLoan).sumPln
      val addIB          = toAbsorb.map(_._2.interbankLoan).sumPln
      val addBailedInDep = toAbsorb.map(_._2.bailedInDeposits).sumPln
      val htmYieldWt     = toAbsorb.map((bank, stocks) => stocks.govBondHtm * bank.htmBookYield).sumPln

      val resolvedRows      = rows.map: (b, stocks) =>
        if b.id == absorberId then
          val combinedHtm      = stocks.govBondHtm + addHtm
          val combinedHtmYield =
            if combinedHtm > PLN.Zero then (stocks.govBondHtm * b.htmBookYield + htmYieldWt).ratioTo(combinedHtm).toRate
            else b.htmBookYield
          (
            b.copy(htmBookYield = combinedHtmYield, status = BankStatus.Active(0)),
            stocks.copy(
              totalDeposits = stocks.totalDeposits + addDep,
              firmLoan = (stocks.firmLoan + addLoans).max(PLN.Zero),
              govBondAfs = stocks.govBondAfs + addAfs,
              govBondHtm = combinedHtm,
              consumerLoan = stocks.consumerLoan + addCC,
              interbankLoan = stocks.interbankLoan + addIB,
              bailedInDeposits = (stocks.bailedInDeposits + addBailedInDep).min(stocks.totalDeposits + addDep).max(PLN.Zero),
            ),
          )
        else if b.failed && stocks.totalDeposits > PLN.Zero then
          (
            b.copy(htmBookYield = Rate.Zero, nplAmount = PLN.Zero, consumerNpl = PLN.Zero),
            stocks.copy(
              totalDeposits = PLN.Zero,
              firmLoan = PLN.Zero,
              govBondAfs = PLN.Zero,
              govBondHtm = PLN.Zero,
              reserve = PLN.Zero,
              interbankLoan = PLN.Zero,
              consumerLoan = PLN.Zero,
              bailedInDeposits = PLN.Zero,
            ),
          )
        else (b, stocks)
      val resolved          = resolvedRows.map(_._1)
      val resolvedStocks    = resolvedRows.map(_._2)
      val resolvedCorpBonds = resolved.zip(resolvedStocks).zipWithIndex.map { case ((bank, stocks), index) =>
        if bank.id == absorberId then holderBalances(index) + addCorpB
        else if bank.failed && stocks.totalDeposits == PLN.Zero then PLN.Zero
        else holderBalances(index)
      }
      ResolutionResult(resolved, resolvedStocks, absorberId, resolvedCorpBonds, resolvedBankCount = toAbsorb.size)

  /** Selects the strongest live absorber bank for resolution transfers. */
  def healthiestBankId(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      bankCorpBondHoldings: BankCorpBondHoldings,
  ): BankId =
    val rows  = BankRows.from(banks, financialStocks, "Banking.healthiestBankId")
    val alive = rows.filter((bank, _) => !bank.failed)
    if alive.isEmpty then
      throw IllegalStateException(
        "Banking.healthiestBankId cannot select an absorber because every bank is failed. Explicit bridge-bank recapitalization or all-failed shutdown semantics are required.",
      )
    else
      val riskBearingAlive = alive.filter: (bank, stocks) =>
        BankRegulatoryMetrics.riskWeightedAssets(
          stocks.firmLoan,
          stocks.consumerLoan,
          bankCorpBondHoldings(bank.id),
        ) > BankRegulatoryMetrics.MinBalanceThreshold
      if riskBearingAlive.nonEmpty then
        riskBearingAlive.maxBy((bank, stocks) => (BankRegulatoryMetrics.car(bank, stocks, bankCorpBondHoldings(bank.id)).toLong, bank.capital.toLong))._1.id
      else alive.maxBy(_._1.capital.toLong)._1.id

  /** Repairs a client bank reference away from failed banks. */
  def reassignBankId(
      currentBankId: BankId,
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      bankCorpBondHoldings: BankCorpBondHoldings,
  ): BankId =
    if currentBankId.toInt < banks.length && !banks(currentBankId.toInt).failed then currentBankId
    else healthiestBankId(banks, financialStocks, bankCorpBondHoldings)
