package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, GovernmentBondCircuit, LedgerFinancialState}
import com.boombustgroup.amorfati.engine.markets.CorporateBondMarket
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.Distribute

import scala.IArray

/** Projects same-month economics outputs into the flow-emission contract. */
private[flows] object MonthFlowPlanBuilder:

  def build(source: SameMonthFlowPlanSource)(using p: SimParams): MonthlyCalculus =
    val openingFinancial   = source.openingFinancial
    val laborOpening       = source.laborOpening
    val firmDemography     = source.firmDemography
    val ledger             = openingFinancial.ledger
    val banks              = openingFinancial.banks
    val execution          = source.execution
    val w                  = execution.openingWorld
    val fiscal             = execution.fiscal
    val labor              = execution.labor
    val householdIncome    = execution.householdIncome
    val firm               = execution.firm
    val householdFinancial = execution.householdFinancial
    val priceEquity        = execution.priceEquity
    val openEconomy        = execution.openEconomy
    val banking            = execution.banking
    val prevBankAgg        = Banking.aggregateFromBankStocks(
      banks,
      ledger.banks.map(LedgerFinancialState.projectBankFinancialStocks),
      bankId => CorporateBondOwnership.bankHolderFor(ledger, bankId),
    )
    val agg                = householdIncome.hhAgg
    val h                  = banking.housingAfterFlows
    val externalFlowBop    = openEconomy.external.flowBop
    val openingCorpBonds   = CorporateBondOwnership.stockStateFromLedger(ledger)
    val corpBondCoupon     = CorporateBondMarket.computeCoupon(w.financialMarkets.corporateBonds, openingCorpBonds)
    val corpBondIssuance   = CorporateBondMarket.processIssuance(CorporateBondMarket.StockState.zero, firm.actualBondIssuance)
    val laborForce         = labor.newDemographics.workingAgePop.max(1)
    val unemploymentRate   = Share.One - Share.fraction(Math.max(0, Math.min(labor.employed, laborForce)), laborForce)
    val equityRevaluation  = equityRevaluationInput(ledger, banking.ledgerFinancialState)

    MonthlyCalculus(
      fiscal = MonthlyCalculus.FiscalBoundary(
        month = fiscal.month,
        resWage = fiscal.resWage,
        lendingBaseRate = fiscal.lendingBaseRate,
        baseMinWage = fiscal.baseMinWage,
        minWagePriceLevel = fiscal.updatedMinWagePriceLevel,
      ),
      labor = MonthlyCalculus.LaborAndSocial(
        wage = labor.newWage,
        employed = labor.employed,
        payroll = laborOpening.payroll,
        zus = labor.newZus,
        ppk = labor.newPpk,
        earmarked = labor.newEarmarked,
        unemploymentRate = unemploymentRate,
        laborDemand = labor.laborDemand,
        livingFirms = firm.ioFirms.count(Firm.isAlive),
        retirees = laborOpening.retirees,
        workingAgePop = laborOpening.workingAgePopulation,
        nfz = labor.newNfz,
        nBankruptFirms = firmDemography.bankruptFirms,
        avgFirmWorkers = firmDemography.averageFirmWorkers,
      ),
      household = MonthlyCalculus.HouseholdFlows(
        totalIncome = householdIncome.totalIncome,
        consumption = agg.consumption,
        domesticConsumption = householdIncome.domesticCons,
        importConsumption = householdIncome.importCons,
        totalRent = agg.totalRent,
        pitRevenue = banking.pitAfterEvasion,
        totalDepositInterest = agg.totalDepositInterest,
        totalRemittances = agg.totalRemittances,
        totalUnempBenefits = agg.totalUnempBenefits,
        totalSocialTransfers = agg.totalSocialTransfers,
        totalCcOrigination = agg.totalConsumerOrigination,
        approvedCcOrigination = agg.totalConsumerApprovedOrigination,
        liquidityShortfallFinancing = agg.totalLiquidityShortfallFinancing,
        totalCcDebtService = agg.totalConsumerDebtService,
        totalCcPrincipal = agg.totalConsumerPrincipal,
        totalCcDefault = agg.totalConsumerDefault,
      ),
      government = MonthlyCalculus.GovernmentBudgetFlows(
        govCurrentSpend = banking.newGovWithYield.govCurrentSpend,
        govVatRevenue = banking.vatAfterEvasion,
        govExciseRevenue = banking.exciseAfterEvasion,
        govCustomsDutyRevenue = banking.customsDutyRevenue,
        govDebtService = openEconomy.banking.monthlyDebtService,
        govDebtServiceRecipients = GovBudgetFlows.DebtServiceRecipients.fromCircuit(GovernmentBondCircuit.from(ledger), openEconomy.banking.monthlyDebtService),
        govBondRuntimeMovements = banking.govBondRuntimeMovements,
        govEuCofinancing = banking.newGovWithYield.euCofinancing,
        govCapitalSpend = banking.newGovWithYield.govCapitalSpend,
      ),
      firm = MonthlyCalculus.FirmFlows(
        firmTax = firm.sumTax,
        firmNewLoans = firm.sumNewLoans,
        firmPrincipal = firm.sumFirmPrincipal,
        firmInterestIncome = firm.intIncome,
        firmCapex = firm.sumCapex,
        firmEquityIssuance = firm.sumEquityIssuance,
        firmIoPayments = firm.totalIoPaid,
        firmNplLoss = firm.nplLoss,
        firmProfitShifting = firm.sumProfitShifting,
        firmFdiRepatriation = firm.sumFdiRepatriation,
        firmGrossInvestment = firm.sumGrossInvestment,
        investNetDepositFlow = banking.investNetDepositFlow,
      ),
      priceEquity = MonthlyCalculus.PriceEquityFlows(
        gdp = priceEquity.gdp,
        inflation = priceEquity.newInfl,
        equityDomDividends = priceEquity.netDomesticDividends,
        equityForDividends = priceEquity.foreignDividendOutflow,
        equityDivTax = priceEquity.dividendTax,
        equityGovDividends = priceEquity.stateOwnedGovDividends,
        equityReturn = priceEquity.equityAfterForeignStock.monthlyReturn,
        equityRevaluation = equityRevaluation,
      ),
      external = MonthlyCalculus.ExternalFlows(
        exports = externalFlowBop.exports,
        totalImports = externalFlowBop.totalImports,
        tourismExport = householdFinancial.tourismExport,
        tourismImport = householdFinancial.tourismImport,
        fdi = externalFlowBop.fdi,
        portfolioFlows = externalFlowBop.portfolioFlows,
        carryTradeFlow = externalFlowBop.carryTradeFlow,
        capitalFlightOutflow = externalFlowBop.capitalFlightOutflow,
        primaryIncome = externalFlowBop.primaryIncome,
        euFunds = externalFlowBop.euFundsMonthly,
        diasporaInflow = householdFinancial.diasporaInflow,
      ),
      corpBonds = MonthlyCalculus.CorpBondFlowsPlan(
        corpBondCoupon = openEconomy.corpBonds.corpBondCoupon,
        corpBondDefaultAmount = firm.totalBondDefault,
        corpBondIssuance = firm.actualBondIssuance,
        corpBondAmortization = openEconomy.corpBonds.corpBondAmort,
        corpBondCouponRecipients = corpBondCouponRecipients(corpBondCoupon),
        corpBondDefaultRecipients = allocateCorpBondReduction(firm.totalBondDefault, openingCorpBonds),
        corpBondIssuanceRecipients = corpBondStockRecipients(corpBondIssuance),
        corpBondAmortizationRecipients = allocateCorpBondReduction(openEconomy.corpBonds.corpBondAmort, openingCorpBonds),
      ),
      housing = MonthlyCalculus.HousingFlows(
        mortgageOrigination = h.lastOrigination,
        mortgageRepayment = h.lastRepayment,
        mortgageInterest = h.mortgageInterestIncome,
        mortgageDefault = h.lastDefault,
      ),
      banking = MonthlyCalculus.BankingFlowsPlan(
        bankGovBondIncome = prevBankAgg.govBondHoldings * openEconomy.monetary.newBondYield.monthly,
        bankReserveInterest = openEconomy.banking.totalReserveInterest,
        bankStandingFacility = openEconomy.banking.totalStandingFacilityIncome,
        bankStandingFacilityBackstop = banking.standingFacilityBackstop,
        bankInterbankInterest = openEconomy.banking.totalInterbankInterest,
        bankCorpBondCoupon = openEconomy.corpBonds.corpBondBankCoupon,
        bankCorpBondLoss = openEconomy.corpBonds.corpBondBankDefaultLoss,
        bankFxReserveSettlement = openEconomy.monetary.fxPlnInjection,
        bankBfgLevy = banking.bfgLevy,
        bankPolishLevyTax = banking.polishBankLevyTax,
        bankUnrealizedLoss = banking.unrealizedBondLoss,
        bankBailIn = banking.bailInLoss,
        bankNbpRemittance = openEconomy.banking.nbpRemittance,
      ),
      nonBank = MonthlyCalculus.NonBankFlows(
        nbfiDepositDrain = openEconomy.nonBank.nbfiDepositDrain,
        nbfiOrigination = banking.finalNbfi.lastNbfiOrigination,
        nbfiRepayment = banking.finalNbfi.lastNbfiRepayment,
        nbfiDefaultAmount = banking.finalNbfi.lastNbfiDefaultAmount,
      ),
      quasiFiscal = MonthlyCalculus.QuasiFiscalFlowsPlan(
        qfBankBondIssuance = banking.newQuasiFiscal.monthlyBankBondIssuance,
        qfNbpBondAbsorption = banking.newQuasiFiscal.monthlyNbpBondAbsorption,
        qfBankBondAmortization = banking.newQuasiFiscal.monthlyBankBondAmortization,
        qfNbpBondAmortization = banking.newQuasiFiscal.monthlyNbpBondAmortization,
        qfLending = banking.newQuasiFiscal.monthlyLending,
        qfRepayment = banking.newQuasiFiscal.monthlyLoanRepayment,
      ),
      insurance = MonthlyCalculus.InsuranceOpeningStocks(
        insuranceCurrentLifeReserves = ledger.insurance.lifeReserve,
        insuranceCurrentNonLifeReserves = ledger.insurance.nonLifeReserve,
        insurancePrevGovBonds = ledger.insurance.govBondHoldings,
        insurancePrevCorpBonds = ledger.insurance.corpBondHoldings,
        insuranceCorpBondDefaultLoss = openEconomy.corpBonds.corpBondInsuranceDefaultLoss,
        insurancePrevEquity = ledger.insurance.equityHoldings,
        govBondYield = openEconomy.monetary.newBondYield,
        corpBondYield = openEconomy.corpBonds.newCorpBonds.corpBondYield,
      ),
    )

  private def corpBondCouponRecipients(coupon: CorporateBondMarket.CouponResult): CorpBondFlows.HolderBreakdown =
    CorpBondFlows.HolderBreakdown(
      banks = coupon.bank,
      ppk = coupon.ppk,
      other = coupon.other,
      insurance = coupon.insurance,
      nbfi = coupon.nbfi,
    )

  private def corpBondStockRecipients(stock: CorporateBondMarket.StockState): CorpBondFlows.HolderBreakdown =
    CorpBondFlows.HolderBreakdown(
      banks = stock.bankHoldings,
      ppk = stock.ppkHoldings,
      other = stock.otherHoldings,
      insurance = stock.insuranceHoldings,
      nbfi = stock.nbfiHoldings,
    )

  private def equityRevaluationInput(
      opening: LedgerFinancialState,
      closing: LedgerFinancialState,
  ): EquityFlows.RevaluationInput =
    val householdDeltas = Array.fill(opening.households.length)(PLN.Zero)
    var i               = 0
    while i < opening.households.length do
      val closingEquity = if i < closing.households.length then closing.households(i).equity else PLN.Zero
      householdDeltas(i) = closingEquity - opening.households(i).equity
      i += 1

    EquityFlows.RevaluationInput(
      // Runtime topology is keyed to opening households; entrants become
      // holder-addressable at the next month boundary.
      householdDeltas = IArray.unsafeFromArray(householdDeltas),
      insuranceDelta = closing.insurance.equityHoldings - opening.insurance.equityHoldings,
      fundsDelta = closing.funds.nbfi.equityHoldings - opening.funds.nbfi.equityHoldings,
      foreignDelta = closing.foreign.equityHoldings - opening.foreign.equityHoldings,
    )

  private def allocateCorpBondReduction(
      amount: PLN,
      opening: CorporateBondMarket.StockState,
  ): CorpBondFlows.HolderBreakdown =
    if amount <= PLN.Zero then CorpBondFlows.HolderBreakdown.zero
    else
      val weights = Array(
        opening.bankHoldings.distributeRaw,
        opening.ppkHoldings.distributeRaw,
        opening.otherHoldings.distributeRaw,
        opening.insuranceHoldings.distributeRaw,
        opening.nbfiHoldings.distributeRaw,
      )
      if weights.forall(_ <= 0L) then CorpBondFlows.HolderBreakdown.copyToOther(amount)
      else
        val allocated = Distribute.distribute(amount.distributeRaw, weights)
        CorpBondFlows.HolderBreakdown(
          banks = PLN.fromRaw(allocated(0)),
          ppk = PLN.fromRaw(allocated(1)),
          other = PLN.fromRaw(allocated(2)),
          insurance = PLN.fromRaw(allocated(3)),
          nbfi = PLN.fromRaw(allocated(4)),
        )
