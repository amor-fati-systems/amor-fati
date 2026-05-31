package com.boombustgroup.amorfati.agents.banking

import com.boombustgroup.amorfati.agents.Banking.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.Distribute

private[agents] object BankInterbankMarket:

  private val DepositSpreadFromRef: Rate = Rate.decimal(1, 2)
  private val LombardSpreadFromRef: Rate = Rate.decimal(1, 2)

  def interbankRate(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], refRate: Rate)(using p: SimParams): Rate =
    val rows        = BankRows.from(banks, financialStocks, "Banking.interbankRate")
    val aggNpl      = rows.foldLeft(PLN.Zero)((acc, bank, _) => if bank.failed then acc else acc + bank.nplAmount)
    val aggLoans    = rows.foldLeft(PLN.Zero)((acc, bank, stocks) => if bank.failed then acc else acc + stocks.firmLoan)
    val aggDeposits = rows.foldLeft(PLN.Zero)((acc, bank, stocks) => if bank.failed then acc else acc + stocks.totalDeposits)
    val aggReserves = rows.foldLeft(PLN.Zero)((acc, bank, stocks) => if bank.failed then acc else acc + stocks.reserve)

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
    val rows = BankRows.from(banks, financialStocks, "Banking.clearInterbank")
    require(
      rows.length == configs.length,
      s"Banking.clearInterbank requires aligned banks and configs, got ${rows.length} banks and ${configs.length} configs",
    )

    val excess: Vector[PLN] =
      rows.map: (b, stocks) =>
        if b.failed then PLN.Zero
        else stocks.totalDeposits * (Share.One - p.banking.reserveReq) - stocks.firmLoan - BankRegulatoryMetrics.govBondHoldings(stocks)

    val lenderIdxs     = excess.indices.filter(i => excess(i) > PLN.Zero && !banks(i).failed).toVector
    val borrowerIdxs   = excess.indices.filter(i => excess(i) < PLN.Zero && !banks(i).failed).toVector
    val lenderCaps     = lenderIdxs.map(i => (excess(i) * hoarding).toLong.max(0L))
    val borrowerNeeds  = borrowerIdxs.map(i => (-excess(i)).toLong)
    val totalLending   = lenderCaps.sum
    val totalBorrowing = borrowerNeeds.sum

    if totalLending <= 0L || totalBorrowing <= 0L then
      BankStockState(
        banks,
        rows.map((_, stocks) => stocks.copy(interbankLoan = PLN.Zero, reserve = PLN.Zero)),
      )
    else
      val matched        = math.min(totalLending, totalBorrowing)
      val lenderLoans    = Distribute.distribute(matched, lenderCaps.toArray)
      val borrowerLoans  = Distribute.distribute(matched, borrowerNeeds.toArray)
      val lenderLoanById = lenderIdxs.zip(lenderLoans.iterator).toMap
      val borrowerById   = borrowerIdxs.zip(borrowerLoans.iterator).toMap

      val updatedStocks = rows
        .mapWithIndex: (i, b, stocks) =>
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
    val rows    = BankRows.from(banks, financialStocks, "Banking.computeReserveInterestFromBankStocks")
    val perBank = rows.map((bank, stocks) => reserveInterest(bank, stocks, refRate))
    PerBankAmounts(perBank, perBank.sumPln)

  def computeStandingFacilities(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], refRate: Rate)(using
      p: SimParams,
  ): PerBankAmounts =
    computeStandingFacilitiesFromBankStocks(banks, financialStocks, refRate)

  def computeStandingFacilitiesFromBankStocks(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], refRate: Rate)(using
      p: SimParams,
  ): PerBankAmounts =
    val rows        = BankRows.from(banks, financialStocks, "Banking.computeStandingFacilitiesFromBankStocks")
    val depositRate = (refRate - p.monetary.depositFacilitySpread).max(Rate.Zero)
    val lombardRate = refRate + p.monetary.lombardSpread
    val perBank     =
      rows.map: (bank, stocks) =>
        if bank.failed then PLN.Zero
        else if stocks.reserve > PLN.Zero then stocks.reserve * depositRate.monthly
        else if stocks.interbankLoan < PLN.Zero then -(stocks.interbankLoan.abs * lombardRate.monthly)
        else PLN.Zero
    PerBankAmounts(perBank, perBank.sumPln)

  def interbankInterestFlows(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], rate: Rate): PerBankAmounts =
    interbankInterestFlowsFromBankStocks(banks, financialStocks, rate)

  def interbankInterestFlowsFromBankStocks(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], rate: Rate): PerBankAmounts =
    val rows    = BankRows.from(banks, financialStocks, "Banking.interbankInterestFlowsFromBankStocks")
    val perBank =
      rows.map: (bank, stocks) =>
        if bank.failed then PLN.Zero
        else stocks.interbankLoan * rate.monthly
    PerBankAmounts(perBank, perBank.sumPln)
