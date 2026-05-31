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
    else if amount < PLN.Zero then
      val total = BankRegulatoryMetrics.govBondHoldings(stocks)
      if total <= PLN.Zero then
        (
          b,
          stocks.copy(
            govBondAfs = stocks.govBondAfs + amount * Share.decimal(5, 1),
            govBondHtm = stocks.govBondHtm + amount * Share.decimal(5, 1),
          ),
        )
      else
        val afsFrac   = stocks.govBondAfs.ratioTo(total).toShare
        val afsReduce = amount * afsFrac
        val htmReduce = amount - afsReduce
        (b, stocks.copy(govBondAfs = stocks.govBondAfs + afsReduce, govBondHtm = stocks.govBondHtm + htmReduce))
    else (b, stocks)

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
