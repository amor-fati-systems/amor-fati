package com.boombustgroup.amorfati.agents.banking

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.agents.Banking.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Regulatory and monetary aggregate calculations over explicit bank stock
  * rows.
  *
  * The module centralizes ratios and holder-stock projections so credit,
  * failure, interbank, and diagnostics code share the same balance-sheet
  * semantics.
  */
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

  /** Provides a zero corporate-bond holder lookup for callers without that
    * balance surface.
    */
  def noBankCorpBondHoldings: BankCorpBondHoldings = _ => PLN.Zero

  /** Converts a position vector into the bank-id keyed lookup used by banking
    * calculations.
    */
  def bankCorpBondHoldingsFromVector(holdings: Vector[PLN]): BankCorpBondHoldings =
    bankId => holdings.lift(bankId.toInt).getOrElse(PLN.Zero)

  /** Computes the non-performing-loan ratio with a safe zero-loan floor. */
  def nplRatio(totalLoans: PLN, nplAmount: PLN): Share =
    if totalLoans > MinBalanceThreshold then nplAmount.ratioTo(totalLoans).toShare else Share.Zero

  /** Computes bank risk-weighted assets for CAR checks. */
  private[banking] def riskWeightedAssets(firmLoans: PLN, consumerLoans: PLN, corpBondHoldings: PLN): PLN =
    firmLoans + consumerLoans + corpBondHoldings * CorpBondRiskWeight

  /** Computes the capital adequacy ratio with a safe zero-RWA floor. */
  def capitalAdequacyRatio(capital: PLN, firmLoans: PLN, consumerLoans: PLN, corpBondHoldings: PLN): Multiplier =
    val totalRwa = riskWeightedAssets(firmLoans, consumerLoans, corpBondHoldings)
    if totalRwa > MinBalanceThreshold then capital.ratioTo(totalRwa).toMultiplier else SafeRatioFloor

  /** Builds the aggregate banking-sector view from aligned operational and
    * financial stock rows.
    */
  def aggregateFromBankStocks(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      bankCorpBondHoldings: BankCorpBondHoldings,
  ): Aggregate =
    val rows = BankRows.from(banks, financialStocks, "Banking.aggregateFromBankStocks")
    Aggregate(
      totalLoans = rows.foldLeft(PLN.Zero)((acc, _, stocks) => acc + stocks.firmLoan),
      nplAmount = rows.foldLeft(PLN.Zero)((acc, bank, _) => acc + bank.nplAmount),
      capital = rows.foldLeft(PLN.Zero)((acc, bank, _) => acc + bank.capital),
      deposits = rows.foldLeft(PLN.Zero)((acc, _, stocks) => acc + stocks.totalDeposits),
      afsBonds = rows.foldLeft(PLN.Zero)((acc, _, stocks) => acc + stocks.govBondAfs),
      htmBonds = rows.foldLeft(PLN.Zero)((acc, _, stocks) => acc + stocks.govBondHtm),
      consumerLoans = rows.foldLeft(PLN.Zero)((acc, _, stocks) => acc + stocks.consumerLoan),
      consumerNpl = rows.foldLeft(PLN.Zero)((acc, bank, _) => acc + bank.consumerNpl),
      corpBondHoldings = rows.foldLeft(PLN.Zero)((acc, bank, _) => acc + bankCorpBondHoldings(bank.id)),
    )

  /** Returns total government-bond holdings across AFS and HTM buckets. */
  def govBondHoldings(stocks: BankFinancialStocks): PLN =
    stocks.govBondAfs + stocks.govBondHtm

  /** Computes firm-loan NPL ratio for a bank row. */
  def nplRatio(bank: BankState, stocks: BankFinancialStocks): Share =
    nplRatio(stocks.firmLoan, bank.nplAmount)

  /** Computes CAR for a bank row using explicit firm, consumer, and corporate
    * bond exposures.
    */
  def car(bank: BankState, stocks: BankFinancialStocks, corpBondHoldings: PLN): Multiplier =
    capitalAdequacyRatio(bank.capital, stocks.firmLoan, stocks.consumerLoan, corpBondHoldings)

  /** Computes the HQLA stock used by the simplified LCR check. */
  def hqla(stocks: BankFinancialStocks): PLN =
    stocks.reserve + govBondHoldings(stocks)

  /** Computes modeled net cash outflows from demand-deposit runoff. */
  def netCashOutflows(stocks: BankFinancialStocks)(using p: SimParams): PLN =
    stocks.demandDeposit * p.banking.demandDepositRunoff

  /** Computes the liquidity coverage ratio. */
  def lcr(stocks: BankFinancialStocks)(using p: SimParams): Multiplier =
    val outflows = netCashOutflows(stocks)
    if outflows > MinBalanceThreshold then hqla(stocks).ratioTo(outflows).toMultiplier
    else SafeRatioFloor

  /** Computes available stable funding for the simplified NSFR check. */
  def asf(bank: BankState, stocks: BankFinancialStocks): PLN =
    bank.capital + stocks.termDeposit * AsfTermWeight + stocks.demandDeposit * AsfDemandWeight

  /** Computes required stable funding for the simplified NSFR check. */
  def rsf(bank: BankState, stocks: BankFinancialStocks, corpBondHoldings: PLN): PLN =
    bank.loansShort * RsfShort + bank.loansMedium * RsfMedium + bank.loansLong * RsfLong +
      govBondHoldings(stocks) * RsfGovBond + corpBondHoldings * RsfCorpBond

  /** Computes the net stable funding ratio. */
  def nsfr(bank: BankState, stocks: BankFinancialStocks, corpBondHoldings: PLN): Multiplier =
    val requiredStableFunding = rsf(bank, stocks, corpBondHoldings)
    if requiredStableFunding > MinBalanceThreshold then asf(bank, stocks).ratioTo(requiredStableFunding).toMultiplier else SafeRatioFloor

  /** Computes M0-M3 and the broad-money multiplier from live bank stocks and
    * non-bank financial balances.
    */
  def computeMonetaryAggregates(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      tfiAum: PLN,
      corpBondOutstanding: PLN,
  ): Banking.MonetaryAggregates =
    val rows   = BankRows.from(banks, financialStocks, "Banking.MonetaryAggregates.computeFromBankStocks")
    val m0     = rows.foldLeft(PLN.Zero)((acc, bank, stocks) => if bank.failed then acc else acc + stocks.reserve)
    val demand = rows.foldLeft(PLN.Zero)((acc, bank, stocks) => if bank.failed then acc else acc + stocks.demandDeposit)
    val term   = rows.foldLeft(PLN.Zero)((acc, bank, stocks) => if bank.failed then acc else acc + stocks.termDeposit)
    val m1     = demand
    val m2     = demand + term
    val m3     = m2 + tfiAum + corpBondOutstanding
    Banking.MonetaryAggregates(m0, m1, m2, m3, m2.ratioTo(m0.max(MinBalanceThreshold)).toMultiplier)
