package com.boombustgroup.amorfati.agents.banking

import com.boombustgroup.amorfati.agents.Banking.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.Distribute

private[agents] object BankInterbankMarket:

  private val DepositSpreadFromRef: Rate = Rate.decimal(1, 2)
  private val LombardSpreadFromRef: Rate = Rate.decimal(1, 2)

  def interbankRate(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], refRate: Rate)(using p: SimParams): Rate =
    require(
      banks.length == financialStocks.length,
      s"Banking.interbankRate requires aligned banks and financial stocks, got ${banks.length} banks and ${financialStocks.length} stock rows",
    )
    val alive       = banks.zip(financialStocks).filterNot(_._1.failed)
    val aggNpl      = alive.map(_._1.nplAmount).sumPln
    val aggLoans    = alive.map(_._2.firmLoan).sumPln
    val aggDeposits = alive.map(_._2.totalDeposits).sumPln
    val aggReserves = alive.map(_._2.reserve).sumPln

    val aggNplShare  =
      if aggLoans > BankRegulatoryMetrics.MinBalanceThreshold then aggNpl.ratioTo(aggLoans).toShare else Share.Zero
    val creditStress = aggNplShare.ratioTo(p.banking.stressThreshold).toShare.clamp(Share.Zero, Share.One)

    val requiredReserves = aggDeposits * p.banking.reserveReq
    val excessReserves   = aggReserves - requiredReserves
    val liquidityRatio   =
      if requiredReserves > PLN.Zero then excessReserves.ratioTo(requiredReserves).toShare.clamp(Share.Zero, Share.One)
      else Share.One

    val depositRate  = Rate.Zero.max(refRate - DepositSpreadFromRef)
    val lombardRate  = refRate + LombardSpreadFromRef
    val corridor     = lombardRate - depositRate
    val stressFactor = (Share.One - liquidityRatio) * creditStress
    depositRate + corridor * stressFactor

  def clearInterbank(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      configs: Vector[Config],
      hoarding: Share,
  )(using
      p: SimParams,
  ): BankStockState =
    require(
      banks.length == financialStocks.length,
      s"Banking.clearInterbank requires aligned banks and financial stocks, got ${banks.length} banks and ${financialStocks.length} stock rows",
    )
    val excess: Vector[PLN] = banks
      .zip(financialStocks)
      .zip(configs)
      .map { case ((b, stocks), _) =>
        if b.failed then PLN.Zero
        else stocks.totalDeposits * (Share.One - p.banking.reserveReq) - stocks.firmLoan - BankRegulatoryMetrics.govBondHoldings(stocks)
      }

    val lenderIdxs     = excess.indices.filter(i => excess(i) > PLN.Zero && !banks(i).failed).toVector
    val borrowerIdxs   = excess.indices.filter(i => excess(i) < PLN.Zero && !banks(i).failed).toVector
    val lenderCaps     = lenderIdxs.map(i => (excess(i) * hoarding).toLong.max(0L))
    val borrowerNeeds  = borrowerIdxs.map(i => (-excess(i)).toLong)
    val totalLending   = lenderCaps.sum
    val totalBorrowing = borrowerNeeds.sum

    if totalLending <= 0L || totalBorrowing <= 0L then BankStockState(banks, financialStocks.map(_.copy(interbankLoan = PLN.Zero, reserve = PLN.Zero)))
    else
      val matched        = math.min(totalLending, totalBorrowing)
      val lenderLoans    = Distribute.distribute(matched, lenderCaps.toArray)
      val borrowerLoans  = Distribute.distribute(matched, borrowerNeeds.toArray)
      val lenderLoanById = lenderIdxs.zip(lenderLoans.iterator).toMap
      val borrowerById   = borrowerIdxs.zip(borrowerLoans.iterator).toMap

      val updatedStocks = banks.indices
        .map: i =>
          val b      = banks(i)
          val stocks = financialStocks(i)
          if b.failed then stocks.copy(interbankLoan = PLN.Zero, reserve = PLN.Zero)
          else
            lenderLoanById.get(i) match
              case Some(lent) =>
                stocks.copy(interbankLoan = PLN.fromRaw(lent), reserve = excess(i) - PLN.fromRaw(lent))
              case None       =>
                borrowerById.get(i) match
                  case Some(borrowed) =>
                    stocks.copy(interbankLoan = PLN.fromRaw(-borrowed), reserve = PLN.Zero)
                  case None           =>
                    stocks.copy(interbankLoan = PLN.Zero, reserve = PLN.Zero)
        .toVector
      BankStockState(banks, updatedStocks)

  def reserveInterest(bank: BankState, stocks: BankFinancialStocks, refRate: Rate)(using p: SimParams): PLN =
    if bank.failed || stocks.reserve <= PLN.Zero then PLN.Zero
    else stocks.reserve * (refRate * p.monetary.reserveRateMult.toMultiplier).monthly

  def computeReserveInterest(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], refRate: Rate)(using
      SimParams,
  ): PerBankAmounts =
    computeReserveInterestFromBankStocks(banks, financialStocks, refRate)

  def computeReserveInterestFromBankStocks(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], refRate: Rate)(using
      SimParams,
  ): PerBankAmounts =
    require(
      banks.length == financialStocks.length,
      s"Banking.computeReserveInterestFromBankStocks requires aligned banks and financial stocks, got ${banks.length} banks and ${financialStocks.length} stock rows",
    )
    val perBank = banks.zip(financialStocks).map((bank, stocks) => reserveInterest(bank, stocks, refRate))
    PerBankAmounts(perBank, perBank.sumPln)

  def computeStandingFacilities(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], refRate: Rate)(using
      p: SimParams,
  ): PerBankAmounts =
    computeStandingFacilitiesFromBankStocks(banks, financialStocks, refRate)

  def computeStandingFacilitiesFromBankStocks(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], refRate: Rate)(using
      p: SimParams,
  ): PerBankAmounts =
    require(
      banks.length == financialStocks.length,
      s"Banking.computeStandingFacilitiesFromBankStocks requires aligned banks and financial stocks, got ${banks.length} banks and ${financialStocks.length} stock rows",
    )
    val depositRate = (refRate - p.monetary.depositFacilitySpread).max(Rate.Zero)
    val lombardRate = refRate + p.monetary.lombardSpread
    val perBank     = banks
      .zip(financialStocks)
      .map: (bank, stocks) =>
        if bank.failed then PLN.Zero
        else if stocks.reserve > PLN.Zero then stocks.reserve * depositRate.monthly
        else if stocks.interbankLoan < PLN.Zero then -(stocks.interbankLoan.abs * lombardRate.monthly)
        else PLN.Zero
    PerBankAmounts(perBank, perBank.sumPln)

  def interbankInterestFlows(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], rate: Rate): PerBankAmounts =
    interbankInterestFlowsFromBankStocks(banks, financialStocks, rate)

  def interbankInterestFlowsFromBankStocks(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], rate: Rate): PerBankAmounts =
    require(
      banks.length == financialStocks.length,
      s"Banking.interbankInterestFlowsFromBankStocks requires aligned banks and financial stocks, got ${banks.length} banks and ${financialStocks.length} stock rows",
    )
    val perBank = banks
      .zip(financialStocks)
      .map: (bank, stocks) =>
        if bank.failed then PLN.Zero
        else stocks.interbankLoan * rate.monthly
    PerBankAmounts(perBank, perBank.sumPln)
