package com.boombustgroup.amorfati.agents.banking

import com.boombustgroup.amorfati.agents.Banking.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Authoritative regulatory RWA perimeter for bank CAR calculations.
  *
  * The perimeter is intentionally stylized, but explicit: loan, bond,
  * interbank, sovereign, reserve, operational-risk-floor, and empty-shell
  * backstop semantics are all named here instead of being reconstructed by
  * callers.
  */
private[agents] object BankRiskWeightedAssets:

  val SafeRatioFloor: Multiplier = Multiplier(10)
  val MinBalanceThreshold: PLN   = PLN(1)

  /** Balance-sheet exposures that enter the modeled RWA perimeter. */
  final case class Exposure(
      firmLoans: PLN = PLN.Zero,
      consumerLoans: PLN = PLN.Zero,
      mortgageLoans: PLN = PLN.Zero,
      corpBondHoldings: PLN = PLN.Zero,
      interbankAssets: PLN = PLN.Zero,
      govBondHoldings: PLN = PLN.Zero,
      reserves: PLN = PLN.Zero,
      capitalBackstop: PLN = PLN.Zero,
  ):
    def explicitAssetBase: PLN =
      firmLoans + consumerLoans + mortgageLoans + corpBondHoldings + interbankAssets + govBondHoldings + reserves

    def withAdditionalFirmLoan(amount: PLN): Exposure =
      copy(firmLoans = firmLoans + amount.max(PLN.Zero))

  /** Auditable decomposition of RWA before the final floor is applied. */
  final case class Components(
      firmLoanRwa: PLN,
      consumerLoanRwa: PLN,
      mortgageLoanRwa: PLN,
      corpBondRwa: PLN,
      interbankAssetRwa: PLN,
      sovereignRwa: PLN,
      reserveRwa: PLN,
      operationalRiskFloor: PLN,
      capitalBackstopFloor: PLN,
  ):
    def weightedExposureRwa: PLN =
      firmLoanRwa + consumerLoanRwa + mortgageLoanRwa + corpBondRwa + interbankAssetRwa + sovereignRwa + reserveRwa

    def total: PLN =
      weightedExposureRwa.max(operationalRiskFloor).max(capitalBackstopFloor)

  /** Builds exposure from a bank row and its ledger-projected financial stocks.
    */
  def exposure(bank: BankState, stocks: BankFinancialStocks, corpBondHoldings: PLN): Exposure =
    exposure(stocks, corpBondHoldings).copy(capitalBackstop = bank.capital.max(PLN.Zero))

  /** Builds exposure from financial stocks when bank capital is not needed. */
  def exposure(stocks: BankFinancialStocks, corpBondHoldings: PLN): Exposure =
    Exposure(
      firmLoans = stocks.firmLoan,
      consumerLoans = stocks.consumerLoan,
      mortgageLoans = stocks.mortgageLoan,
      corpBondHoldings = corpBondHoldings,
      interbankAssets = stocks.interbankLoan.max(PLN.Zero),
      govBondHoldings = stocks.govBondAfs + stocks.govBondHtm,
      reserves = stocks.reserve,
    )

  /** Builds aggregate-sector exposure from the aggregate balance-sheet DTO. */
  def aggregateExposure(aggregate: Aggregate): Exposure =
    Exposure(
      firmLoans = aggregate.totalLoans,
      consumerLoans = aggregate.consumerLoans,
      mortgageLoans = aggregate.mortgageLoans,
      corpBondHoldings = aggregate.corpBondHoldings,
      interbankAssets = aggregate.interbankAssets,
      govBondHoldings = aggregate.govBondHoldings,
      reserves = aggregate.reserves,
      capitalBackstop = aggregate.capital.max(PLN.Zero),
    )

  /** Computes the RWA component decomposition using configured risk weights. */
  def components(exposure: Exposure)(using p: SimParams): Components =
    Components(
      firmLoanRwa = exposure.firmLoans * p.banking.firmLoanRiskWeight,
      consumerLoanRwa = exposure.consumerLoans * p.banking.consumerLoanRiskWeight,
      mortgageLoanRwa = exposure.mortgageLoans * p.banking.mortgageLoanRiskWeight,
      corpBondRwa = exposure.corpBondHoldings * p.banking.corpBondRiskWeight,
      interbankAssetRwa = exposure.interbankAssets * p.banking.interbankAssetRiskWeight,
      sovereignRwa = exposure.govBondHoldings * p.banking.sovereignRiskWeight,
      reserveRwa = exposure.reserves * p.banking.reserveRiskWeight,
      operationalRiskFloor = exposure.explicitAssetBase * p.banking.rwaOperationalRiskFloor,
      capitalBackstopFloor = exposure.capitalBackstop * p.banking.rwaCapitalBackstop,
    )

  /** Computes total RWA after explicit exposure weights and configured floors.
    */
  def total(exposure: Exposure)(using p: SimParams): PLN =
    components(exposure).total

  /** Computes CAR for the supplied capital and RWA exposure. */
  def capitalAdequacyRatio(capital: PLN, exposure: Exposure)(using p: SimParams): Multiplier =
    val totalRwa = total(exposure)
    if totalRwa > MinBalanceThreshold then capital.ratioTo(totalRwa).toMultiplier else SafeRatioFloor
