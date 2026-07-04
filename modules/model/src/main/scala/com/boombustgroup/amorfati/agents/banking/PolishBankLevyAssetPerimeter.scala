package com.boombustgroup.amorfati.agents.banking

import com.boombustgroup.amorfati.agents.Banking.BankFinancialStocks
import com.boombustgroup.amorfati.types.*

/** Asset perimeter used to compute the Polish tax on selected financial
  * institutions.
  *
  * The perimeter is narrower than a generic bank balance-sheet snapshot: it
  * names the modeled asset buckets entering the statutory tax base before own
  * funds, sovereign-bond, and threshold deductions are applied.
  */
case class PolishBankLevyAssetPerimeter(
    firmLoan: PLN,
    consumerLoan: PLN,
    mortgageLoan: PLN,
    govBondHoldings: PLN,
    reserve: PLN,
    interbankAssets: PLN,
    corpBondHoldings: PLN,
):
  def explicitAssets: PLN =
    firmLoan + consumerLoan + mortgageLoan + govBondHoldings + reserve + interbankAssets + corpBondHoldings

object PolishBankLevyAssetPerimeter:
  def fromBankStocks(stocks: BankFinancialStocks, corpBondHoldings: PLN): PolishBankLevyAssetPerimeter =
    PolishBankLevyAssetPerimeter(
      firmLoan = stocks.firmLoan,
      consumerLoan = stocks.consumerLoan,
      mortgageLoan = stocks.mortgageLoan,
      govBondHoldings = BankRegulatoryMetrics.govBondHoldings(stocks),
      reserve = stocks.reserve,
      interbankAssets = stocks.interbankLoan.max(PLN.Zero),
      corpBondHoldings = corpBondHoldings,
    )
