package com.boombustgroup.amorfati.agents.banking

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.agents.Banking.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Polish bank-tax calculations over the explicit bank asset perimeter.
  *
  * The tax is separate from the BFG resolution levy: it is a monthly central
  * government revenue flow and a bank-capital P&L cost. The modeled base
  * follows the bank-archetype balance-sheet surface: explicit assets minus own
  * funds and Polish sovereign securities, then the statutory threshold.
  */
private[agents] object BankTaxation:

  /** Taxable bank-asset base after modeled statutory deductions. */
  def polishBankLevyTaxableAssets(bank: BankState, balanceSheet: TaxableBankBalanceSheet)(using p: SimParams): PLN =
    val deductions = bank.capital.max(PLN.Zero) + balanceSheet.govBondHoldings
    (balanceSheet.explicitAssets - deductions - p.banking.polishBankLevyAssetThreshold).max(PLN.Zero)

  /** Computes the monthly Polish bank levy for each live bank row. */
  def computePolishBankLevy(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      bankCorpBondHoldings: BankCorpBondHoldings,
  )(using p: SimParams): Banking.PerBankAmounts =
    val rows    = BankRows.from(banks, financialStocks, "Banking.computePolishBankLevy")
    val perBank =
      rows.map: (bank, stocks) =>
        if bank.failed then PLN.Zero
        else
          val taxableBalanceSheet = TaxableBankBalanceSheet.from(stocks, bankCorpBondHoldings(bank.id))
          polishBankLevyTaxableAssets(bank, taxableBalanceSheet) * p.banking.polishBankLevyMonthlyRate
    Banking.PerBankAmounts(perBank, perBank.sumPln)
