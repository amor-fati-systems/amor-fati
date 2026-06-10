package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.markets.FiscalBudget
import com.boombustgroup.amorfati.engine.mechanisms.TaxRevenue

/** Computes government, tax, and JST state used by the banking close. */
private[banking] object BankingPublicFinanceStage:

  def compute(in: StepInput)(using p: SimParams): GovJstResult =
    val openingBankBooks = OpeningBankBooks.from(in.ledgerFinancialState)
    val polishBankLevy   =
      Banking.computePolishBankLevy(
        in.banks,
        openingBankBooks.financialStocks,
        openingBankBooks.corpBondHoldings,
      )
    val tax              = TaxRevenue.compute(
      TaxRevenue.Input(
        consumption = in.householdIncome.consumption,
        pitRevenue = in.householdIncome.pitRevenue,
        totalImports = in.openEconomy.external.newBop.totalImports,
        informalCyclicalAdj = in.world.mechanisms.informalCyclicalAdj,
      ),
    )

    val unempBenefitSpend   = in.householdIncome.hhAgg.totalUnempBenefits
    val socialTransferSpend = in.householdIncome.hhAgg.totalSocialTransfers
    val prevGov             = in.world.gov
    val prevJst             = in.world.social.jst

    val newGov                = FiscalBudget.update(
      FiscalBudget.Input(
        prev = prevGov,
        priceLevel = in.priceEquity.newPrice,
        citPaid = in.firm.sumTax + in.priceEquity.dividendTax + tax.pitAfterEvasion,
        govDividendRevenue = in.priceEquity.stateOwnedGovDividends,
        vat = tax.vatAfterEvasion,
        nbpRemittance = in.openEconomy.banking.nbpRemittance,
        polishBankLevyTax = polishBankLevy.total,
        exciseRevenue = tax.exciseAfterEvasion,
        customsDutyRevenue = tax.customsDutyRevenue,
        unempBenefitSpend = unempBenefitSpend,
        debtService = in.openEconomy.banking.monthlyDebtService,
        zusGovSubvention = in.labor.newZus.govSubvention,
        nfzGovSubvention = in.labor.newNfz.govSubvention,
        earmarkedGovSubvention = in.labor.newEarmarked.totalGovSubvention,
        socialTransferSpend = socialTransferSpend,
        euCofinancing = in.priceEquity.euCofin,
        euProjectCapital = in.priceEquity.euProjectCapital,
        govPurchasesActual = in.demand.govPurchases,
      ),
    )
    val newGovWithYield       = newGov.copy(
      policy = newGov.policy.copy(
        bondYield = in.openEconomy.monetary.newBondYield,
        weightedCoupon = in.openEconomy.monetary.newWeightedCoupon,
      ),
    )
    val newGovBondOutstanding = FiscalBudget.nextGovBondOutstanding(in.ledgerFinancialState.government.govBondOutstanding, newGov.deficit)

    val nLivingFirms = in.firm.ioFirms.count(Firm.isAlive)
    val jstResult    =
      Jst.step(
        prevJst,
        in.ledgerFinancialState.funds.jstCash,
        in.firm.sumTax,
        in.householdIncome.totalIncome,
        in.priceEquity.gdp,
        nLivingFirms,
        tax.pitAfterEvasion,
      )

    GovJstResult(
      newGovWithYield = newGovWithYield,
      newGovBondOutstanding = newGovBondOutstanding,
      newJst = jstResult.state,
      jstCash = jstResult.closingDeposits,
      jstDepositChange = jstResult.depositChange,
      polishBankLevy = polishBankLevy,
      tax = tax,
    )
