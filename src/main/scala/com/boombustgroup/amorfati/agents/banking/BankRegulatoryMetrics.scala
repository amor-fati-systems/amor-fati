package com.boombustgroup.amorfati.agents.banking

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.agents.Banking.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

private[agents] object BankRegulatoryMetrics:

  val CorpBondRiskWeight: Share  = Share.decimal(50, 2)
  val SafeRatioFloor: Multiplier = Multiplier(10)
  val MinBalanceThreshold: PLN   = PLN(1)

  private val AsfTermWeight: Share   = Share.decimal(95, 2)
  private val AsfDemandWeight: Share = Share.decimal(90, 2)
  private val RsfShort: Share        = Share.decimal(50, 2)
  private val RsfMedium: Share       = Share.decimal(65, 2)
  private val RsfLong: Share         = Share.decimal(85, 2)
  private val RsfGovBond: Share      = Share.decimal(5, 2)
  private val RsfCorpBond: Share     = Share.decimal(50, 2)

  def noBankCorpBondHoldings: BankCorpBondHoldings = _ => PLN.Zero

  def bankCorpBondHoldingsFromVector(holdings: Vector[PLN]): BankCorpBondHoldings =
    bankId => holdings.lift(bankId.toInt).getOrElse(PLN.Zero)

  def nplRatio(totalLoans: PLN, nplAmount: PLN): Share =
    if totalLoans > MinBalanceThreshold then nplAmount.ratioTo(totalLoans).toShare else Share.Zero

  private[banking] def riskWeightedAssets(firmLoans: PLN, consumerLoans: PLN, corpBondHoldings: PLN): PLN =
    firmLoans + consumerLoans + corpBondHoldings * CorpBondRiskWeight

  def capitalAdequacyRatio(capital: PLN, firmLoans: PLN, consumerLoans: PLN, corpBondHoldings: PLN): Multiplier =
    val totalRwa = riskWeightedAssets(firmLoans, consumerLoans, corpBondHoldings)
    if totalRwa > MinBalanceThreshold then capital.ratioTo(totalRwa).toMultiplier else SafeRatioFloor

  def aggregateFromBankStocks(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      bankCorpBondHoldings: BankCorpBondHoldings,
  ): Aggregate =
    require(
      banks.length == financialStocks.length,
      s"Banking.aggregateFromBankStocks requires aligned banks and financial stocks, got ${banks.length} banks and ${financialStocks.length} stock rows",
    )
    Aggregate(
      totalLoans = financialStocks.foldLeft(PLN.Zero)(_ + _.firmLoan),
      nplAmount = banks.foldLeft(PLN.Zero)(_ + _.nplAmount),
      capital = banks.foldLeft(PLN.Zero)(_ + _.capital),
      deposits = financialStocks.foldLeft(PLN.Zero)(_ + _.totalDeposits),
      afsBonds = financialStocks.foldLeft(PLN.Zero)(_ + _.govBondAfs),
      htmBonds = financialStocks.foldLeft(PLN.Zero)(_ + _.govBondHtm),
      consumerLoans = financialStocks.foldLeft(PLN.Zero)(_ + _.consumerLoan),
      consumerNpl = banks.foldLeft(PLN.Zero)(_ + _.consumerNpl),
      corpBondHoldings = banks.foldLeft(PLN.Zero)((acc, bank) => acc + bankCorpBondHoldings(bank.id)),
    )

  def govBondHoldings(stocks: BankFinancialStocks): PLN =
    stocks.govBondAfs + stocks.govBondHtm

  def nplRatio(bank: BankState, stocks: BankFinancialStocks): Share =
    nplRatio(stocks.firmLoan, bank.nplAmount)

  def car(bank: BankState, stocks: BankFinancialStocks, corpBondHoldings: PLN): Multiplier =
    capitalAdequacyRatio(bank.capital, stocks.firmLoan, stocks.consumerLoan, corpBondHoldings)

  def hqla(stocks: BankFinancialStocks): PLN =
    stocks.reserve + govBondHoldings(stocks)

  def netCashOutflows(stocks: BankFinancialStocks)(using p: SimParams): PLN =
    stocks.demandDeposit * p.banking.demandDepositRunoff

  def lcr(stocks: BankFinancialStocks)(using p: SimParams): Multiplier =
    val outflows = netCashOutflows(stocks)
    if outflows > MinBalanceThreshold then hqla(stocks).ratioTo(outflows).toMultiplier
    else SafeRatioFloor

  def asf(bank: BankState, stocks: BankFinancialStocks): PLN =
    bank.capital + stocks.termDeposit * AsfTermWeight + stocks.demandDeposit * AsfDemandWeight

  def rsf(bank: BankState, stocks: BankFinancialStocks, corpBondHoldings: PLN): PLN =
    bank.loansShort * RsfShort + bank.loansMedium * RsfMedium + bank.loansLong * RsfLong +
      govBondHoldings(stocks) * RsfGovBond + corpBondHoldings * RsfCorpBond

  def nsfr(bank: BankState, stocks: BankFinancialStocks, corpBondHoldings: PLN): Multiplier =
    val requiredStableFunding = rsf(bank, stocks, corpBondHoldings)
    if requiredStableFunding > MinBalanceThreshold then asf(bank, stocks).ratioTo(requiredStableFunding).toMultiplier else SafeRatioFloor

  def computeMonetaryAggregates(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      tfiAum: PLN,
      corpBondOutstanding: PLN,
  ): Banking.MonetaryAggregates =
    require(
      banks.length == financialStocks.length,
      s"Banking.MonetaryAggregates.computeFromBankStocks requires aligned banks and financial stocks, got ${banks.length} banks and ${financialStocks.length} stock rows",
    )
    val alive  = banks.zip(financialStocks).filterNot(_._1.failed).map(_._2)
    val m0     = alive.map(_.reserve).sumPln
    val demand = alive.map(_.demandDeposit).sumPln
    val term   = alive.map(_.termDeposit).sumPln
    val m1     = demand
    val m2     = demand + term
    val m3     = m2 + tfiAum + corpBondOutstanding
    Banking.MonetaryAggregates(m0, m1, m2, m3, m2.ratioTo(m0.max(MinBalanceThreshold)).toMultiplier)
