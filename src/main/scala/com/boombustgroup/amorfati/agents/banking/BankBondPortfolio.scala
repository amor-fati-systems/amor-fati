package com.boombustgroup.amorfati.agents.banking

import com.boombustgroup.amorfati.agents.Banking.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.Distribute

private[agents] object BankBondPortfolio:

  def allocateBondIssuance(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      issuance: PLN,
      currentYield: Rate,
  )(using p: SimParams): BankStockState =
    if issuance <= PLN.Zero then return BankStockState(banks, financialStocks)
    allocateBondChange(banks, financialStocks, issuance, currentYield)

  def allocateBondRedemption(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      redemption: PLN,
      currentYield: Rate,
  )(using p: SimParams): BankStockState =
    if redemption <= PLN.Zero then return BankStockState(banks, financialStocks)
    allocateBondChange(banks, financialStocks, PLN.fromRaw(-redemption.toLong), currentYield)

  private def allocateBondChange(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      signedChange: PLN,
      currentYield: Rate,
  )(using p: SimParams): BankStockState =
    val rows      = BankRows.from(banks, financialStocks, "Banking.allocateBondChange")
    if signedChange == PLN.Zero then return BankStockState(banks, financialStocks)
    val aliveRows = rows.filter((bank, _) => !bank.failed)
    if aliveRows.isEmpty then return BankStockState(banks, financialStocks)
    if signedChange < PLN.Zero then return allocateBondRedemptionChange(rows, aliveRows, math.abs(signedChange.toLong))

    val weights =
      val nonNegativeDeposits = aliveRows.map((_, stocks) => stocks.totalDeposits.toLong.max(0L))
      val totalDep            = nonNegativeDeposits.sum
      if totalDep > 0L then nonNegativeDeposits.toArray
      else Array.fill(aliveRows.length)(1L)

    val magnitudes = Distribute.distribute(math.abs(signedChange.toLong), weights)
    val signedById = aliveRows
      .zip(magnitudes.iterator)
      .map { case ((b, _), amount) =>
        val signedAmount = if signedChange.toLong > 0L then PLN.fromRaw(amount) else PLN.fromRaw(-amount)
        b.id -> signedAmount
      }
      .toMap

    val updatedRows = rows.map((b, stocks) => signedById.get(b.id).fold((b, stocks))(amount => applyBondAllocation(b, stocks, amount, currentYield)))
    BankStockState(updatedRows.map(_._1), updatedRows.map(_._2))

  private def allocateBondRedemptionChange(
      rows: BankRows,
      aliveRows: Vector[(BankState, BankFinancialStocks)],
      requestedRaw: Long,
  ): BankStockState =
    val capacities   = aliveRows.map((_, stocks) => BankRegulatoryMetrics.govBondHoldings(stocks).toLong.max(0L)).toArray
    val availableRaw = capacities.sum
    require(
      requestedRaw <= availableRaw,
      s"Banking.allocateBondRedemption cannot redeem ${PLN.fromRaw(requestedRaw).compact} from bank portfolios with only ${PLN.fromRaw(availableRaw).compact} available",
    )

    val weights =
      val nonNegativeDeposits = aliveRows.map((_, stocks) => stocks.totalDeposits.toLong.max(0L))
      val totalDep            = nonNegativeDeposits.sum
      if totalDep > 0L then nonNegativeDeposits.toArray
      else capacities.map(capacity => if capacity > 0L then capacity else 0L)

    val initialReductions = Distribute.distribute(requestedRaw, weights)
    val reductions        = Array.fill(aliveRows.length)(0L)
    var leftover          = 0L
    var i                 = 0
    while i < aliveRows.length do
      val capped = math.min(initialReductions(i), capacities(i))
      reductions(i) = capped
      leftover += initialReductions(i) - capped
      i += 1

    while leftover > 0L do
      val remainingWeights = Array.fill(aliveRows.length)(0L)
      var remainingTotal   = 0L
      i = 0
      while i < aliveRows.length do
        val remaining = capacities(i) - reductions(i)
        if remaining > 0L then
          remainingWeights(i) = remaining
          remainingTotal += remaining
        i += 1

      require(
        remainingTotal > 0L,
        s"Banking.allocateBondRedemption could not allocate residual ${PLN.fromRaw(leftover).compact} after clamping bank holdings",
      )

      val residualReductions = Distribute.distribute(leftover, remainingWeights)
      var nextLeftover       = 0L
      var progressed         = false
      i = 0
      while i < aliveRows.length do
        val room   = capacities(i) - reductions(i)
        val reduce = math.min(residualReductions(i), room)
        if reduce > 0L then progressed = true
        reductions(i) += reduce
        nextLeftover += residualReductions(i) - reduce
        i += 1

      if progressed then leftover = nextLeftover
      else
        var greedyLeftover = leftover
        i = 0
        while i < aliveRows.length && greedyLeftover > 0L do
          val room   = capacities(i) - reductions(i)
          val reduce = math.min(room, greedyLeftover)
          if reduce > 0L then
            reductions(i) += reduce
            greedyLeftover -= reduce
          i += 1
        require(
          greedyLeftover < leftover,
          s"Banking.allocateBondRedemption could not make progress allocating residual ${PLN.fromRaw(leftover).compact}",
        )
        leftover = greedyLeftover

    val reductionById = aliveRows
      .zip(reductions.iterator)
      .collect {
        case ((b, _), reductionRaw) if reductionRaw > 0L =>
          b.id -> PLN.fromRaw(reductionRaw)
      }
      .toMap
    val updatedRows   = rows.map((b, stocks) => reductionById.get(b.id).fold((b, stocks))(reduction => applyBondRedemption(b, stocks, reduction)))
    BankStockState(updatedRows.map(_._1), updatedRows.map(_._2))

  private def applyBondAllocation(b: BankState, stocks: BankFinancialStocks, amount: PLN, currentYield: Rate)(using
      p: SimParams,
  ): (BankState, BankFinancialStocks) =
    if amount > PLN.Zero then
      val htmPortion   = amount * p.banking.htmShare
      val afsPortion   = amount - htmPortion
      val newHtmTotal  = stocks.govBondHtm + htmPortion
      val newBookYield =
        if newHtmTotal > PLN.Zero then (stocks.govBondHtm * b.htmBookYield + htmPortion * currentYield).ratioTo(newHtmTotal).toRate
        else b.htmBookYield
      (b.copy(htmBookYield = newBookYield), stocks.copy(govBondAfs = stocks.govBondAfs + afsPortion, govBondHtm = newHtmTotal))
    else if amount < PLN.Zero then applyBondRedemption(b, stocks, amount.abs)
    else (b, stocks)

  private def applyBondRedemption(b: BankState, stocks: BankFinancialStocks, reduction: PLN): (BankState, BankFinancialStocks) =
    val total = BankRegulatoryMetrics.govBondHoldings(stocks)
    require(
      reduction <= total,
      s"Banking.allocateBondRedemption allocated ${reduction.compact} to bank ${b.id.toInt} with only ${total.compact} available",
    )
    if reduction <= PLN.Zero then (b, stocks)
    else if reduction == total then (b, stocks.copy(govBondAfs = PLN.Zero, govBondHtm = PLN.Zero))
    else
      val afsReduceBase = (reduction * stocks.govBondAfs.ratioTo(total).toShare).min(stocks.govBondAfs)
      val htmReduceBase = (reduction - afsReduceBase).min(stocks.govBondHtm)
      val residual      = reduction - afsReduceBase - htmReduceBase
      val extraAfs      = residual.min(stocks.govBondAfs - afsReduceBase)
      val extraHtm      = (residual - extraAfs).min(stocks.govBondHtm - htmReduceBase)
      val afsReduce     = afsReduceBase + extraAfs
      val htmReduce     = htmReduceBase + extraHtm
      (
        b,
        stocks.copy(
          govBondAfs = (stocks.govBondAfs - afsReduce).max(PLN.Zero),
          govBondHtm = (stocks.govBondHtm - htmReduce).max(PLN.Zero),
        ),
      )

  def sellToBuyer(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], requested: PLN): BondSaleResult =
    val rows      = BankRows.from(banks, financialStocks, "Banking.sellToBuyer")
    val zeroSales = Vector.fill(banks.length)(PLN.Zero)
    if requested <= PLN.Zero then BondSaleResult(banks, financialStocks, PLN.Zero, zeroSales)
    else
      val eligible   = rows.filter((b, stocks) => !b.failed && BankRegulatoryMetrics.govBondHoldings(stocks) > PLN.Zero)
      val totalBonds = eligible.map((_, stocks) => BankRegulatoryMetrics.govBondHoldings(stocks)).sumPln
      if totalBonds <= PLN.Zero then BondSaleResult(banks, financialStocks, PLN.Zero, zeroSales)
      else
        val requestedSale  = requested.min(totalBonds)
        val soldMagnitudes =
          Distribute.distribute(requestedSale.toLong, eligible.map((_, stocks) => BankRegulatoryMetrics.govBondHoldings(stocks).toLong).toArray)
        val soldById       = eligible
          .zip(soldMagnitudes.iterator)
          .map { case ((b, _), sold) =>
            b.id -> PLN.fromRaw(sold)
          }
          .toMap
        val resultStocks   = rows.map: (b, stocks) =>
          soldById
            .get(b.id)
            .fold(stocks): sold =>
              val afsReduce = sold.min(stocks.govBondAfs)
              val htmReduce = sold - afsReduce
              stocks.copy(
                govBondAfs = (stocks.govBondAfs - afsReduce).max(PLN.Zero),
                govBondHtm = (stocks.govBondHtm - htmReduce).max(PLN.Zero),
              )
        val soldByBank     = banks.map(bank => soldById.getOrElse(bank.id, PLN.Zero))
        BondSaleResult(banks, resultStocks, requestedSale, soldByBank)

  def processHtmForcedSale(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], currentYield: Rate)(using
      p: SimParams,
  ): HtmForcedSaleResult =
    val rows      = BankRows.from(banks, financialStocks, "Banking.processHtmForcedSale")
    val threshold = p.banking.htmForcedSaleThreshold * p.banking.lcrMin
    var totalLoss = PLN.Zero
    val updated   =
      rows.map: (b, stocks) =>
        if b.failed || stocks.govBondHtm <= PLN.Zero || BankRegulatoryMetrics.lcr(stocks) >= threshold then (b, stocks)
        else
          val reclassified = stocks.govBondHtm * p.banking.htmForcedSaleRate
          val yieldGap     = (currentYield - b.htmBookYield).max(Rate.Zero)
          val loss         = reclassified * yieldGap * p.banking.govBondDuration
          totalLoss = totalLoss + loss
          (b.copy(capital = b.capital - loss), stocks.copy(govBondHtm = stocks.govBondHtm - reclassified, govBondAfs = stocks.govBondAfs + reclassified))
    HtmForcedSaleResult(updated.map(_._1), updated.map(_._2), totalLoss)
