package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.*
import com.boombustgroup.amorfati.engine.economics.*
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.engine.markets.{BondAuction, CorporateBondMarket, FiscalBudget, HousingMarket}
import com.boombustgroup.amorfati.engine.mechanisms.{TaxRevenue, YieldCurve}
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.Distribute

import com.boombustgroup.amorfati.random.RandomStream

/** Banking sector economics — aggregate values for MonthlyCalculus.
  *
  * Contains the full bank balance-sheet update pipeline: capital PnL, loan/NPL
  * dynamics, deposit flows, government bond allocation (PPK, insurance, TFI),
  * multi-bank resolution path with interbank clearing, failure detection,
  * bail-in, and firm/household reassignment. Also computes tax revenue, housing
  * mortgage flows, and monetary aggregates (M1/M2/M3).
  */
object BankingStepRunner:

  // ---- Calibration constants ----
  private val NplMonthlyWriteOff: Share = Share.decimal(5, 2)  // monthly NPL write-off rate (aggregate and per-bank)
  private val ShortLoanFrac: Share      = Share.decimal(20, 2) // fraction of loans in short-term maturity bucket
  private val MediumLoanFrac: Share     = Share.decimal(30, 2) // fraction of loans in medium-term maturity bucket
  private val LongLoanFrac: Share       = Share.decimal(50, 2) // fraction of loans in long-term maturity bucket

  case class StepInput(
      world: World,
      ledgerFinancialState: LedgerFinancialState,
      fiscal: FiscalConstraintEconomics.StepOutput,
      labor: LaborEconomics.StepOutput,
      householdIncome: HouseholdIncomeEconomics.StepOutput,
      demand: DemandEconomics.StepOutput,
      firm: FirmEconomics.StepOutput,
      householdFinancial: HouseholdFinancialEconomics.StepOutput,
      priceEquity: PriceEquityEconomics.StepOutput,
      openEconomy: OpenEconEconomics.StepOutput,
      banks: Vector[Banking.BankState],
      depositRng: RandomStream,
  )

  case class StepOutput(
      resolvedBank: Banking.Aggregate,                              // aggregate bank balance sheet after resolution
      banks: Vector[Banking.BankState],                             // explicit post-step bank population
      bankingMarket: Banking.MarketState,                           // banking market wrapper after interbank clearing
      reassignedFirms: Vector[Firm.State],                          // firms with bankId reassigned after bank failure
      reassignedHouseholds: Vector[Household.State],                // HH with bankId reassigned after bank failure
      finalNbp: Nbp.State,                                          // NBP policy/QE state after bond-waterfall settlement
      finalPpk: SocialSecurity.PpkState,                            // PPK monthly contribution state
      finalInsurance: Insurance.State,                              // insurance monthly state
      finalInsuranceBalances: Insurance.ClosingBalances,            // insurance non-corporate-bond closing balances
      finalNbfi: Nbfi.State,                                        // NBFI/TFI monthly state
      finalNbfiBalances: Nbfi.ClosingBalances,                      // NBFI/TFI non-corporate-bond closing balances
      newGovWithYield: FiscalBudget.GovState,                       // gov state with updated bond yield
      newJst: Jst.State,                                            // local government state
      housingAfterFlows: HousingMarket.State,                       // housing market after mortgage flows
      bfgLevy: PLN,                                                 // BFG resolution fund levy (aggregate)
      bailInLoss: PLN,                                              // bail-in deposit destruction (aggregate)
      multiCapDestruction: PLN,                                     // capital wiped when banks fail
      interbankContagionLoss: PLN,                                  // counterparty losses from failed-bank interbank exposures
      bankCapitalDiagnostics: BankCapitalDiagnostics,               // aggregate monthly bank-capital waterfall diagnostics
      bankFailureDiagnostics: BankFailureDiagnostics,               // monthly bank-failure trigger diagnostics
      bankResolutionDiagnostics: BankResolutionDiagnostics,         // monthly bank-resolution count diagnostics
      bankReconciliationDiagnostics: BankReconciliationDiagnostics, // exactness-patch impact on target bank capital/CAR
      bankEclDiagnostics: BankEclDiagnostics,                       // IFRS 9 ECL allowance and migration diagnostics
      monAgg: Option[Banking.MonetaryAggregates],                   // M0/M1/M2/M3 (when credit diagnostics on)
      finalHhAgg: Household.Aggregates,                             // recomputed HH aggregates
      vat: PLN,                                                     // gross VAT revenue
      vatAfterEvasion: PLN,                                         // VAT after informal evasion
      pitAfterEvasion: PLN,                                         // PIT after informal evasion
      exciseRevenue: PLN,                                           // gross excise revenue
      exciseAfterEvasion: PLN,                                      // excise after informal evasion
      customsDutyRevenue: PLN,                                      // customs duty revenue
      realizedTaxShadowShare: Share,                                // current-period realized aggregate tax-side shadow share
      mortgageInterestIncome: PLN,                                  // mortgage interest income (bank share)
      mortgagePrincipal: PLN,                                       // mortgage principal repaid
      mortgageDefaultLoss: PLN,                                     // mortgage default loss (bank share)
      mortgageDefaultAmount: PLN,                                   // gross mortgage default amount
      jstDepositChange: PLN,                                        // JST deposit flow (Identity 2)
      investNetDepositFlow: PLN,                                    // investment timing deposit settlement
      actualBondChange: PLN,                                        // net change in gov bonds outstanding
      standingFacilityBackstop: PLN,                                // reserve shortfall funded by explicit NBP standing-facility backstop
      unrealizedBondLoss: PLN,                                      // mark-to-market loss on gov bond portfolio (interest rate risk channel)
      htmRealizedLoss: PLN,                                         // realized loss from HTM forced reclassification
      eclProvisionChange: PLN,                                      // aggregate IFRS 9 ECL provision change
      newQuasiFiscal: QuasiFiscal.State,                            // BGK/PFR market memory after issuance and lending
      govBondRuntimeMovements: GovBondRuntimeMovements,             // holder-resolved SPW runtime movements from the bond waterfall
      ledgerFinancialState: LedgerFinancialState,                   // ledger-backed financial state at the banking stage boundary
  )

  private def bankCorpBondHoldings(ledgerFinancialState: LedgerFinancialState): Banking.BankCorpBondHoldings =
    bankId => CorporateBondOwnership.bankHolderFor(ledgerFinancialState, bankId)

  def runStep(rawIn: StepInput)(using p: SimParams): StepOutput =
    val in                       = rawIn
    val openingBankStocks        = in.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks)
    val prevBankAgg              =
      Banking.aggregateFromBankStocks(
        in.banks,
        openingBankStocks,
        bankCorpBondHoldings(in.ledgerFinancialState),
      )
    val govJst                   = computeGovAndJst(in)
    val housing                  = computeHousingFlows(in)
    val bfgLevy                  = Banking.computeBfgLevy(in.banks, openingBankStocks).total
    val investNetDepositFlow     = computeInvestNetDepositFlow(in)
    val finalHhAgg               = computeHhAgg(in)
    val quasiFiscalStep          =
      QuasiFiscal.step(
        LedgerFinancialState.quasiFiscalStock(in.ledgerFinancialState),
        govJst.newGovWithYield.govCapitalSpend,
        govJst.newGovWithYield.euCofinancing,
        in.world.nbp.qeActive,
      )
    val newQuasiFiscal           = quasiFiscalStep.state
    val quasiFiscalDepositChange = newQuasiFiscal.monthlyLending - newQuasiFiscal.monthlyLoanRepayment
    val wf                       = computeWaterfallInputs(in, govJst.newGovBondOutstanding)
    val multi                    = processMultiBankPath(
      in,
      govJst.jstDepositChange,
      investNetDepositFlow,
      quasiFiscalDepositChange,
      housing.mortgageFlows,
      wf,
    )
    val ledgerClosing            = closeLedgerAndDiagnostics(
      in = in,
      govJst = govJst,
      housing = housing,
      quasiFiscalStep = quasiFiscalStep,
      multi = multi,
      prevBankAgg = prevBankAgg,
      bfgLevy = bfgLevy,
    )
    val bankCapitalTerms         = multi.bankCapitalTerms

    StepOutput(
      resolvedBank = multi.resolvedBank,
      banks = multi.finalBanks,
      bankingMarket = multi.finalBankingMarket,
      reassignedFirms = multi.reassignedFirms,
      reassignedHouseholds = multi.reassignedHouseholds,
      finalNbp = multi.finalNbp,
      finalPpk = multi.finalPpk,
      finalInsurance = multi.finalInsurance,
      finalInsuranceBalances = multi.finalInsuranceBalances,
      finalNbfi = multi.finalNbfi,
      finalNbfiBalances = multi.finalNbfiBalances,
      newGovWithYield = govJst.newGovWithYield,
      newJst = govJst.newJst,
      housingAfterFlows = housing.housingAfterFlows,
      bfgLevy = bfgLevy,
      bailInLoss = multi.bailInLoss,
      multiCapDestruction = multi.multiCapDestruction,
      interbankContagionLoss = multi.interbankContagionLoss,
      bankCapitalDiagnostics = ledgerClosing.bankCapitalDiagnostics,
      bankFailureDiagnostics = multi.bankFailureDiagnostics,
      bankResolutionDiagnostics = multi.bankResolutionDiagnostics,
      bankReconciliationDiagnostics = multi.bankReconciliationDiagnostics,
      bankEclDiagnostics = multi.bankEclDiagnostics,
      monAgg = ledgerClosing.monAgg,
      finalHhAgg = finalHhAgg,
      vat = govJst.tax.vat,
      vatAfterEvasion = govJst.tax.vatAfterEvasion,
      pitAfterEvasion = govJst.tax.pitAfterEvasion,
      exciseRevenue = govJst.tax.exciseRevenue,
      exciseAfterEvasion = govJst.tax.exciseAfterEvasion,
      customsDutyRevenue = govJst.tax.customsDutyRevenue,
      realizedTaxShadowShare = govJst.tax.realizedTaxShadowShare,
      mortgageInterestIncome = housing.mortgageFlows.interest,
      mortgagePrincipal = housing.mortgageFlows.principal,
      mortgageDefaultLoss = housing.mortgageFlows.defaultLoss,
      mortgageDefaultAmount = housing.mortgageFlows.defaultAmount,
      jstDepositChange = govJst.jstDepositChange,
      investNetDepositFlow = investNetDepositFlow,
      actualBondChange = multi.actualBondChange,
      standingFacilityBackstop = multi.standingFacilityBackstop,
      unrealizedBondLoss = bankCapitalTerms.unrealizedBondLoss,
      htmRealizedLoss = multi.htmRealizedLoss,
      eclProvisionChange = bankCapitalTerms.eclProvisionChange,
      newQuasiFiscal = newQuasiFiscal,
      govBondRuntimeMovements = multi.govBondRuntimeMovements,
      ledgerFinancialState = ledgerClosing.ledgerFinancialState,
    )

  // ---- Sub-stage boundaries ----

  private[banking] def closeLedgerAndDiagnostics(
      in: StepInput,
      govJst: GovJstResult,
      housing: HousingResult,
      quasiFiscalStep: QuasiFiscal.StepResult,
      multi: MultiBankResult,
      prevBankAgg: Banking.Aggregate,
      bfgLevy: PLN,
  )(using p: SimParams): LedgerClosingResult =
    val issuerSettledFirmBalances =
      CorporateBondOwnership.applyAmortization(in.firm.ledgerFinancialState.firms, multi.reassignedFirms, in.openEconomy.corpBonds.corpBondAmort)

    val rawLedgerFinancialState =
      in.firm.ledgerFinancialState.copy(
        households = LedgerFinancialState.settleHouseholdMortgageStock(
          in.firm.ledgerFinancialState.households,
          housing.housingAfterFlows.mortgageStock,
          housing.housingAfterFlows.lastOrigination,
        ),
        firms = issuerSettledFirmBalances,
        banks = multi.finalBankLedgerBalances,
        government = LedgerFinancialState.GovernmentBalances(govBondOutstanding = govJst.newGovBondOutstanding),
        foreign = LedgerFinancialState.ForeignBalances(
          govBondHoldings = multi.foreignBondHoldings,
          equityHoldings = in.priceEquity.foreignEquityHoldings,
        ),
        nbp = LedgerFinancialState.nbpBalances(
          multi.finalNbpFinancialStocks,
          reserveLiability = LedgerFinancialState.nbpReserveLiabilityFromBanks(multi.finalBankLedgerBalances),
        ),
        insurance = LedgerFinancialState.insuranceBalances(multi.finalInsuranceBalances, in.openEconomy.corpBonds.closingCorpBondProjection.insuranceHoldings),
        funds = LedgerFinancialState.fundBalances(
          zusCash = SocialSecurity.zusCashAfter(in.ledgerFinancialState.funds.zusCash, in.labor.newZus),
          nfzCash = SocialSecurity.nfzCashAfter(in.ledgerFinancialState.funds.nfzCash, in.labor.newNfz),
          fpCash = EarmarkedFunds.fpCashAfter(in.ledgerFinancialState.funds.fpCash, in.labor.newEarmarked),
          pfronCash = EarmarkedFunds.pfronCashAfter(in.ledgerFinancialState.funds.pfronCash, in.labor.newEarmarked),
          fgspCash = EarmarkedFunds.fgspCashAfter(in.ledgerFinancialState.funds.fgspCash, in.labor.newEarmarked),
          jstCash = govJst.jstCash,
          ppkGovBondHoldings = multi.finalPpkGovBondHoldings,
          corporateBonds = in.openEconomy.corpBonds.closingCorpBondProjection,
          nbfi = multi.finalNbfiBalances,
          quasiFiscal = quasiFiscalStep.stock,
        ),
      )
    val ledgerFinancialState    =
      LedgerFinancialState.withBankMortgageAssets(
        LedgerFinancialState.withHouseholdInsuranceReserveAssets(rawLedgerFinancialState),
      )
    val monAgg                  = computeMonetaryAggregates(multi.finalBanks, ledgerFinancialState)
    val bankCapitalTerms        = multi.bankCapitalTerms
    val bankCapitalDiagnostics  = BankCapitalDiagnostics(
      openingCapital = prevBankAgg.capital,
      closingCapital = multi.resolvedBank.capital,
      retainedIncome = bankCapitalTerms.retainedIncome,
      firmNplLoss = in.firm.nplLoss,
      mortgageNplLoss = housing.mortgageFlows.defaultLoss,
      consumerNplLoss = in.householdFinancial.consumerNplLoss,
      corpBondDefaultLoss = in.openEconomy.corpBonds.corpBondBankDefaultLoss,
      bfgLevy = bfgLevy,
      unrealizedBondLoss = bankCapitalTerms.unrealizedBondLoss,
      htmRealizedLoss = multi.htmRealizedLoss,
      eclProvisionChange = bankCapitalTerms.eclProvisionChange,
      capitalDestruction = multi.multiCapDestruction,
      interbankContagionLoss = multi.interbankContagionLoss,
      reconciliationResidual = multi.capitalReconciliationResidual,
      depositBailInLoss = multi.bailInLoss,
      newFailures = multi.newFailures,
    )

    LedgerClosingResult(
      ledgerFinancialState = ledgerFinancialState,
      monAgg = monAgg,
      bankCapitalDiagnostics = bankCapitalDiagnostics,
    )

  /** Government budget update (deficit, debt, bonds) and JST local government
    * step.
    */
  private[banking] def computeGovAndJst(in: StepInput)(using p: SimParams): GovJstResult =
    val tax = TaxRevenue.compute(
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
      tax = tax,
    )

  /** Housing market: price step, origination, mortgage flows. */
  private[banking] def computeHousingFlows(in: StepInput)(using p: SimParams): HousingResult =
    val unempRate              = in.world.unemploymentRate(in.labor.employed)
    val openingHousing         =
      HousingMarket.withMortgageStock(in.world.real.housing, LedgerFinancialState.householdMortgageStock(in.ledgerFinancialState))
    val prevMortgageRate       = openingHousing.avgMortgageRate
    val mortgageBaseRate: Rate =
      val exp = in.world.mechanisms.expectations
      YieldCurve
        .compute(
          in.world.bankingSector.interbankRate,
          nplRatio = Banking
            .aggregateFromBankStocks(
              in.banks,
              in.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks),
              bankCorpBondHoldings(in.ledgerFinancialState),
            )
            .nplRatio,
          credibility = exp.credibility,
          expectedInflation = exp.expectedInflation,
          targetInflation = p.monetary.targetInfl,
        )
        .wibor3m
    val mortgageRate           = mortgageBaseRate + p.housing.mortgageSpread
    val housingAfterPrice      = HousingMarket.step(
      HousingMarket.StepInput(
        prev = openingHousing,
        mortgageRate = mortgageRate,
        inflation = in.priceEquity.newInfl,
        incomeGrowth = in.labor.wageGrowth.toMultiplier.toRate,
        employed = in.labor.employed,
        prevMortgageRate = prevMortgageRate,
      ),
    )
    val housingAfterOrig       =
      HousingMarket.processOrigination(housingAfterPrice, in.householdIncome.totalIncome, mortgageRate, true)
    val scheduledPrincipal     = Some(in.householdIncome.hhAgg.totalMortgagePrincipal)
    val mortgageFlows          = HousingMarket.processMortgageFlows(housingAfterOrig, mortgageRate, unempRate, scheduledPrincipal)
    val housingAfterFlows      = HousingMarket.applyFlows(housingAfterOrig, mortgageFlows)

    HousingResult(housingAfterFlows = housingAfterFlows, mortgageFlows = mortgageFlows)

  /** Investment timing deposit settlement: lagged domestic investment demand
    * minus current domestic investment spending.
    */
  private def computeInvestNetDepositFlow(in: StepInput)(using p: SimParams): PLN =
    val currentInvestDomestic = in.firm.sumGrossInvestment * (Share.One - p.capital.importShare) +
      in.firm.sumGreenInvestment * (Share.One - p.climate.greenImportShare)
    in.demand.laggedInvestDemand - currentInvestDomestic

  /** Re-distribute the opening unsecured consumer-loan book across banks using
    * the current household bank routing as weights, while preserving the exact
    * aggregate stock.
    *
    * The model has a single household `bankId` reused for origination, debt
    * service, and default routing. Deposit mobility / bank reassignment can
    * change that routing key without changing the aggregate unsecured loan
    * stock. If we leave the pre-scaling per-bank book untouched, a bank can end
    * up receiving more consumer-loan outflows than the stock it still carries,
    * forcing per-bank zero clipping and eventually breaking aggregate SFC
    * exactness. This helper keeps the aggregate book constant but rebalances
    * its distribution to the routing key used by the current-month household
    * flows.
    */
  private[economics] def alignConsumerLoanBookToHouseholdRouting(
      households: Vector[Household.State],
      householdBalances: Vector[LedgerFinancialState.HouseholdBalances],
      bankStocks: Vector[Banking.BankFinancialStocks],
  ): Vector[Banking.BankFinancialStocks] =
    require(
      households.length == householdBalances.length,
      s"BankingStepRunner.alignConsumerLoanBookToHouseholdRouting requires aligned households and balances, got ${households.length} households and ${householdBalances.length} balance rows",
    )
    val totalBook = bankStocks.map(_.consumerLoan).sumPln
    if bankStocks.isEmpty || totalBook <= PLN.Zero then bankStocks
    else
      val bankWeights = Array.fill(bankStocks.length)(0L)
      households
        .zip(householdBalances)
        .foreach: (hh, balances) =>
          val bankIndex = hh.bankId.toInt
          if bankIndex >= 0 && bankIndex < bankWeights.length && balances.consumerLoan > PLN.Zero then
            bankWeights(bankIndex) += balances.consumerLoan.distributeRaw
      if !bankWeights.exists(_ > 0L) then bankStocks
      else
        val redistributed = Distribute.distribute(totalBook.distributeRaw, bankWeights).map(PLN.fromRaw).toVector
        bankStocks.zip(redistributed).map((stocks, consumerLoan) => stocks.copy(consumerLoan = consumerLoan))

  /** Recompute household aggregates from final households. */
  private def computeHhAgg(in: StepInput)(using SimParams): Household.Aggregates =
    Household
      .computeAggregates(
        in.firm.households,
        in.firm.ledgerFinancialState.households.map(LedgerFinancialState.projectHouseholdFinancialStocks),
        in.labor.newWage,
        in.fiscal.resWage,
        in.householdIncome.importAdj,
        in.householdIncome.hhAgg.retrainingAttempts,
        in.householdIncome.hhAgg.retrainingSuccesses,
      )
      .withFlowTotalsFrom(in.householdIncome.hhAgg)

  /** Compute raw bond waterfall inputs — requests only, no final allocations.
    * Actual allocations happen in runMultiBankStage via sellToBuyer.
    */
  private[banking] def computeWaterfallInputs(
      in: StepInput,
      newGovBondOutstanding: PLN,
  ): BondWaterfallInputs =
    val actualBondChange = newGovBondOutstanding - in.ledgerFinancialState.government.govBondOutstanding
    val insRequested     =
      (in.openEconomy.nonBank.newInsuranceBalances.govBondHoldings - in.ledgerFinancialState.insurance.govBondHoldings).max(PLN.Zero)
    val tfiRequested     =
      (in.openEconomy.nonBank.newNbfiBalances.tfiGovBondHoldings - in.ledgerFinancialState.funds.nbfi.govBondHoldings).max(PLN.Zero)
    val prevEr           = in.world.forex.exchangeRate
    val currEr           = in.openEconomy.external.newForex.exchangeRate
    val erChange         = currEr.deviationFrom(prevEr).toCoefficient
    BondWaterfallInputs(
      actualBondChange = actualBondChange,
      qeRequested = in.openEconomy.monetary.qePurchaseAmount,
      ppkRequested = in.labor.rawPpkBondPurchase,
      insRequested = insRequested,
      tfiRequested = tfiRequested,
      erChange = erChange,
    )

  /** Resolve per-bank household flows from tracked data or worker-share
    * fallback.
    */
  private def resolvePerBankHhFlows(
      bId: Int,
      perBankHhFlowsOpt: Option[Vector[PerBankFlow]],
      totalWorkers: Int,
      perBankWorkers: Vector[Int],
      in: StepInput,
  ): PerBankHhFlows =
    perBankHhFlowsOpt match
      case Some(pbf) =>
        val f = pbf(bId)
        PerBankHhFlows(
          incomeShare = f.income,
          consShare = f.consumption,
          mortgageInterest = f.mortgageInterest,
          depInterest = f.depositInterest,
          ccDebtService = f.consumerDebtService,
          ccPrincipal = f.consumerPrincipal,
          ccOrigination = f.consumerOrigination,
          ccDefault = f.consumerDefault,
          ccLoanDefault = f.consumerLoanDefault,
        )
      case None      =>
        val ws = if totalWorkers > 0 then Share.fraction(perBankWorkers(bId), totalWorkers) else Share.Zero
        PerBankHhFlows(
          incomeShare = in.householdIncome.totalIncome * ws,
          consShare = in.householdIncome.consumption * ws,
          mortgageInterest = in.householdIncome.hhAgg.totalMortgageInterest * ws,
          depInterest = PLN.Zero,
          ccDebtService = in.householdFinancial.consumerDebtService * ws,
          ccPrincipal = in.householdFinancial.consumerPrincipal * ws,
          ccOrigination = in.householdFinancial.consumerOrigination * ws,
          ccDefault = in.householdFinancial.consumerDefaultAmt * ws,
          ccLoanDefault = in.householdFinancial.consumerLoanDefaultAmt * ws,
        )

  private def allocateBankCorpBondIssuance(issuance: PLN, perBankWorkers: Vector[Int]): Vector[PLN] =
    if perBankWorkers.isEmpty then Vector.empty
    else if issuance <= PLN.Zero then Vector.fill(perBankWorkers.length)(PLN.Zero)
    else
      val weights =
        val workerWeights = perBankWorkers.map(_.toLong.max(0L)).toArray
        if workerWeights.exists(_ > 0L) then workerWeights
        else Array.fill(perBankWorkers.length)(1L)
      Distribute.distribute(issuance.distributeRaw, weights).map(PLN.fromRaw).toVector

  private def reduceBankCorpBondHoldings(holdings: Vector[PLN], reduction: PLN): Vector[PLN] =
    if holdings.isEmpty then Vector.empty
    else if reduction <= PLN.Zero then holdings
    else
      val total           = holdings.sumPln
      val actualReduction = reduction.min(total)
      val reductions      = Distribute.distribute(actualReduction.distributeRaw, holdings.map(_.distributeRaw).toArray)
      holdings.zip(reductions).map((holding, rawReduction) => (holding - PLN.fromRaw(rawReduction)).max(PLN.Zero))

  private def settleBankCorpBondHoldings(
      previous: Vector[PLN],
      previousAggregateStock: CorporateBondMarket.StockState,
      nextAggregateStock: CorporateBondMarket.StockState,
      totalBondIssuance: PLN,
      perBankWorkers: Vector[Int],
  )(using p: SimParams): Vector[PLN] =
    val bankIssuance   = CorporateBondMarket.processIssuance(CorporateBondMarket.StockState.zero, totalBondIssuance).bankHoldings
    val bankReduction  = (previousAggregateStock.bankHoldings + bankIssuance - nextAggregateStock.bankHoldings).max(PLN.Zero)
    val afterReduction = reduceBankCorpBondHoldings(previous, bankReduction)
    val issuance       = allocateBankCorpBondIssuance(bankIssuance, perBankWorkers)
    afterReduction.zip(issuance).map(_ + _)

  /** Compute updated state for a single bank in the multi-bank path. */
  private def updateSingleBank(
      b: Banking.BankState,
      stocks: Banking.BankFinancialStocks,
      hhFlows: PerBankHhFlows,
      workerShare: Share,
      mortgageFlows: HousingMarket.MortgageFlows,
      perBankReserveInt: Banking.PerBankAmounts,
      perBankStandingFac: Banking.PerBankAmounts,
      perBankInterbankInt: Banking.PerBankAmounts,
      jstDepositChange: PLN,
      investNetDepositFlow: PLN,
      quasiFiscalDepositChange: PLN,
      in: StepInput,
  )(using p: SimParams): SingleBankUpdate =
    if b.failed then
      // Failed rows are inert shells; resolution/reconciliation owns any explicit shell changes.
      return SingleBankUpdate(bank = b, financialStocks = stocks)

    val bId           = b.id.toInt
    val bankNplNew    = in.firm.perBankNplDebt(bId)
    val bankNplLoss   = bankNplNew * (Share.One - p.banking.loanRecovery)
    val bankIntIncome = in.firm.perBankIntIncome(bId)
    val bankBondInc   = Banking.govBondHoldings(stocks) * in.openEconomy.monetary.newBondYield.monthly
    val bankResInt    = perBankReserveInt.perBank(bId)
    val bankSfInc     = perBankStandingFac.perBank(bId)
    val bankIbInt     = perBankInterbankInt.perBank(bId)
    val newLoansTotal =
      (stocks.firmLoan + in.firm.perBankNewLoans(bId) - in.firm.perBankFirmPrincipal(bId) - bankNplNew * p.banking.loanRecovery).max(PLN.Zero)

    val newDep = stocks.totalDeposits +
      hhFlows.incomeShare - hhFlows.consShare +
      investNetDepositFlow * workerShare +
      jstDepositChange * workerShare +
      quasiFiscalDepositChange * workerShare +
      in.priceEquity.netDomesticDividends * workerShare -
      in.priceEquity.foreignDividendOutflow * workerShare -
      in.householdFinancial.remittanceOutflow * workerShare +
      in.householdFinancial.diasporaInflow * workerShare +
      in.householdFinancial.tourismExport * workerShare -
      in.householdFinancial.tourismImport * workerShare +
      in.firm.perBankNewLoans(bId) -
      in.firm.perBankFirmPrincipal(bId) +
      hhFlows.ccOrigination +
      in.openEconomy.nonBank.insNetDepositChange * workerShare +
      in.openEconomy.nonBank.nbfiDepositDrain * workerShare

    val bankMortgageIntIncome     = hhFlows.mortgageInterest
    val bankMortgageNplLoss       = mortgageFlows.defaultLoss * workerShare
    val bankCcNplLoss             = hhFlows.ccLoanDefault * (Share.One - p.household.ccNplRecovery)
    val bankCcStockReduction: PLN = in.householdIncome.perBankHhFlowsOpt match
      case Some(pbf) => pbf(bId).consumerPrincipal
      case _         => hhFlows.ccPrincipal
    val bankCcInterestIncome      = hhFlows.ccDebtService - hhFlows.ccPrincipal
    val bankCorpBondCoupon        = in.openEconomy.corpBonds.corpBondBankCoupon * workerShare
    val bankCorpBondDefaultLoss   = in.openEconomy.corpBonds.corpBondBankDefaultLoss * workerShare
    val bankBfgLevy               =
      if !b.failed then stocks.totalDeposits * p.banking.bfgLevyRate.monthly
      else PLN.Zero

    // Per-bank mark-to-market loss on AFS bonds only (HTM losses hidden until forced reclassification)
    val bankYieldChange    = in.openEconomy.monetary.newBondYield - in.world.gov.bondYield
    val bankUnrealizedLoss = if bankYieldChange > Rate.Zero then stocks.govBondAfs * bankYieldChange * p.banking.govBondDuration else PLN.Zero

    val capitalPnl = Banking.computeCapitalDelta(
      Banking.CapitalPnlInput(
        prevCapital = b.capital,
        nplLoss = bankNplLoss,
        mortgageNplLoss = bankMortgageNplLoss,
        consumerNplLoss = bankCcNplLoss,
        corpBondDefaultLoss = bankCorpBondDefaultLoss,
        bfgLevy = bankBfgLevy,
        unrealizedBondLoss = bankUnrealizedLoss,
        intIncome = bankIntIncome,
        bondIncome = bankBondInc,
        depositInterest = hhFlows.depInterest,
        reserveInterest = bankResInt,
        standingFacilityIncome = bankSfInc,
        interbankInterest = bankIbInt,
        mortgageInterestIncome = bankMortgageIntIncome,
        consumerInterestIncome = bankCcInterestIncome,
        corpBondCoupon = bankCorpBondCoupon,
      ),
    )

    // IFRS 9 ECL staging: provision change hits capital
    val unemployment: Share              = in.world.unemploymentRate(in.labor.employed)
    val referenceUnemployment: Share     = eclReferenceUnemployment(in)
    val gdpGrowth: Coefficient           = eclGdpGrowth(in)
    val eclResult: EclStaging.StepResult =
      EclStaging.step(b.eclStaging, newLoansTotal + stocks.consumerLoan, bankNplNew, unemployment, referenceUnemployment, gdpGrowth)

    SingleBankUpdate(
      bank = b.copy(
        nplAmount = (b.nplAmount + bankNplNew - b.nplAmount * NplMonthlyWriteOff).max(PLN.Zero),
        capital = capitalPnl.newCapital - eclResult.provisionChange,
        eclStaging = eclResult.newStaging,
        loansShort = newLoansTotal * ShortLoanFrac,
        loansMedium = newLoansTotal * MediumLoanFrac,
        loansLong = newLoansTotal * LongLoanFrac,
        consumerNpl = (b.consumerNpl + hhFlows.ccLoanDefault - b.consumerNpl * NplMonthlyWriteOff).max(PLN.Zero),
      ),
      financialStocks = stocks.copy(
        firmLoan = newLoansTotal,
        totalDeposits = newDep,
        demandDeposit = newDep * (Share.One - p.banking.termDepositFrac),
        termDeposit = newDep * p.banking.termDepositFrac,
        consumerLoan = (stocks.consumerLoan + hhFlows.ccOrigination - bankCcStockReduction - hhFlows.ccDefault).max(PLN.Zero),
      ),
    )

  /** Multi-bank update: per-bank loop, interbank clearing, bond allocation,
    * failure resolution.
    */
  private[banking] def processMultiBankPath(
      in: StepInput,
      jstDepositChange: PLN,
      investNetDepositFlow: PLN,
      quasiFiscalDepositChange: PLN,
      mortgageFlows: HousingMarket.MortgageFlows,
      wf: BondWaterfallInputs,
  )(using p: SimParams): MultiBankResult =
    val banks               = in.banks
    val openingBankStocks   = in.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks)
    // We keep the opening bank-side consumer-loan stock but realign it to the
    // current household routing keys and household-level consumer-loan balances
    // carried into s5. This assumes no stage between the opening ledger snapshot
    // and s5 mutates household consumerLoan principal; only routing may drift.
    val bankStocks          = alignConsumerLoanBookToHouseholdRouting(
      in.firm.households,
      in.firm.ledgerFinancialState.households,
      openingBankStocks,
    )
    require(
      bankStocks.map(_.consumerLoan).sumPln == openingBankStocks.map(_.consumerLoan).sumPln,
      "BankingStepRunner consumer-loan realignment must preserve the aggregate opening bank loan book",
    )
    val perBankReserveInt   = Banking.computeReserveInterest(banks, bankStocks, in.world.nbp.referenceRate)
    val perBankStandingFac  = Banking.computeStandingFacilities(banks, bankStocks, in.world.nbp.referenceRate)
    val perBankInterbankInt = Banking.interbankInterestFlows(banks, bankStocks, in.world.bankingSector.interbankRate)
    val totalWorkers        = in.firm.perBankWorkers.sum

    val workerShares: Vector[Share] =
      if totalWorkers <= 0 then Vector.fill(banks.length)(Share.Zero)
      else
        val n    = banks.length
        val raw  = (0 until n - 1).map(i => Share.fraction(in.firm.perBankWorkers(i), totalWorkers)).toVector
        val last = Share.One - raw.foldLeft(Share.Zero)(_ + _)
        raw :+ last

    val settledBankCorpBonds = settleBankCorpBondHoldings(
      previous = CorporateBondOwnership.bankHolderBalances(in.ledgerFinancialState),
      previousAggregateStock = CorporateBondOwnership.stockStateFromLedger(in.ledgerFinancialState),
      nextAggregateStock = in.openEconomy.corpBonds.closingCorpBondProjection,
      totalBondIssuance = in.firm.actualBondIssuance,
      perBankWorkers = in.firm.perBankWorkers,
    )

    val updatedRows       = banks.zip(bankStocks).map { case (b, stocks) =>
      val bId         = b.id.toInt
      val workerShare = workerShares(bId)
      val hhFlows     = resolvePerBankHhFlows(bId, in.householdIncome.perBankHhFlowsOpt, totalWorkers, in.firm.perBankWorkers, in)
      updateSingleBank(
        b,
        stocks,
        hhFlows,
        workerShare,
        mortgageFlows,
        perBankReserveInt,
        perBankStandingFac,
        perBankInterbankInt,
        jstDepositChange,
        investNetDepositFlow,
        quasiFiscalDepositChange,
        in,
      )
    }
    val updatedBanks      = updatedRows.map(_.bank)
    val updatedBankStocks = updatedRows.map(_.financialStocks)

    runMultiBankStage(
      in,
      updatedBanks,
      updatedBankStocks,
      in.world.bankingSector.configs,
      wf,
      perBankReserveInt,
      perBankStandingFac,
      perBankInterbankInt,
      jstDepositChange,
      investNetDepositFlow,
      quasiFiscalDepositChange,
      mortgageFlows,
      settledBankCorpBonds,
    )

  /** Runs the multi-bank settlement and resolution boundary after per-bank
    * balance-sheet updates.
    */
  private[banking] def runMultiBankStage(
      in: StepInput,
      updatedBanks: Vector[Banking.BankState],
      updatedBankStocks: Vector[Banking.BankFinancialStocks],
      bankConfigs: Vector[Banking.Config],
      wf: BondWaterfallInputs,
      perBankReserveInt: Banking.PerBankAmounts,
      perBankStandingFac: Banking.PerBankAmounts,
      perBankInterbankInt: Banking.PerBankAmounts,
      jstDepositChange: PLN,
      investNetDepositFlow: PLN,
      quasiFiscalDepositChange: PLN,
      mortgageFlows: HousingMarket.MortgageFlows,
      settledBankCorpBonds: Vector[PLN],
  )(using p: SimParams): MultiBankResult =
    val prevBankAgg =
      Banking.aggregateFromBankStocks(
        in.banks,
        in.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks),
        bankCorpBondHoldings(in.ledgerFinancialState),
      )

    val settlement            = runInterbankSettlement(
      in = in,
      updatedBanks = updatedBanks,
      updatedBankStocks = updatedBankStocks,
      bankConfigs = bankConfigs,
      prevBankAgg = prevBankAgg,
      reserveInterest = perBankReserveInt,
      standingFacilityIncome = perBankStandingFac,
      interbankInterest = perBankInterbankInt,
    )
    val waterfall             = runBondWaterfall(in, wf, settlement.banks, settlement.financialStocks)
    val finalBankingMarket    = bankingMarketAfterSettlement(settlement, bankConfigs, prevBankAgg, in)
    val resolved              = runFailureResolutionPipeline(
      in = in,
      prevBankAgg = prevBankAgg,
      waterfall = waterfall,
      settledBankCorpBonds = settledBankCorpBonds,
      jstDepositChange = jstDepositChange,
      investNetDepositFlow = investNetDepositFlow,
      quasiFiscalDepositChange = quasiFiscalDepositChange,
      mortgageFlows = mortgageFlows,
      htmRealizedLoss = settlement.htmRealizedLoss,
    )
    val finalResolution       = resolved.finalResolution
    val failureDetection      = resolved.failureDetection
    val finalFailureBanks     = finalResolution.banks
    val finalFailureStocks    = finalResolution.financialStocks
    val finalCorpBondHoldings = finalResolution.bankCorpBondHoldings
    val finalCorpBondLookup   = Banking.bankCorpBondHoldingsFromVector(finalCorpBondHoldings)
    val invariantMismatches   = math.abs(finalResolution.newFailures - finalResolution.failureEvents.length)
    val failureDiagnostics    =
      BankFailureDiagnostics.fromEvents(finalResolution.failureEvents, finalResolution.allFailedFallbackUsed, invariantMismatches)
    val resolutionDiagnostics =
      BankResolutionDiagnostics.fromState(
        banks = finalFailureBanks,
        newFailures = finalResolution.newFailures,
        bailInEvents = finalResolution.failureEvents.map(_.bankId).distinct.size,
        resolvedBanks = finalResolution.resolvedBankCount,
        allFailedFallbackUsed = finalResolution.allFailedFallbackUsed,
      )
    val anyFailureForMobility = failureDetection.anyFailed || resolved.aggregateReconciliation.newFailuresDelta > 0
    val eclGdpGrowthMonthly   = eclGdpGrowth(in)
    val eclMigrationRate      = EclStaging.migrationRate(in.world.unemploymentRate(in.labor.employed), eclReferenceUnemployment(in), eclGdpGrowthMonthly)
    val eclDiagnostics        = computeBankEclDiagnostics(in.banks, finalFailureBanks, eclMigrationRate, eclGdpGrowthMonthly)

    // Repair stale failed-bank routing every month; mobility validates survivor bankIds.
    val reassignedFirms =
      in.firm.ioFirms.map: f =>
        val nextBankId = Banking.reassignBankId(f.bankId, finalFailureBanks, finalFailureStocks, finalCorpBondLookup)
        if nextBankId == f.bankId then f else f.copy(bankId = nextBankId)
    val postFailureHh   =
      in.firm.households.map: h =>
        val nextBankId = Banking.reassignBankId(h.bankId, finalFailureBanks, finalFailureStocks, finalCorpBondLookup)
        if nextBankId == h.bankId then h else h.copy(bankId = nextBankId)

    // Deposit mobility: HH may switch banks based on health signals and panic
    val mobilityResult       = DepositMobility(postFailureHh, finalFailureBanks, finalFailureStocks, anyFailureForMobility, in.depositRng, finalCorpBondLookup)
    val reassignedHouseholds = mobilityResult.households

    // Deposit flows take effect next month when HH income/consumption routes
    // to new bankId. No immediate balance sheet transfer — consistent with
    // 1-month account transfer lag and avoids SFC flow mismatch.
    MultiBankResult(
      finalBanks = finalFailureBanks,
      finalBankCorpBondHoldings = finalCorpBondHoldings,
      finalBankLedgerBalances = finalFailureBanks
        .zip(finalFailureStocks)
        .map { case (bank, stocks) =>
          val bankIndex    = bank.id.toInt
          val mortgageLoan =
            if bank.failed then PLN.Zero
            else in.ledgerFinancialState.banks.lift(bankIndex).fold(PLN.Zero)(_.mortgageLoan)
          LedgerFinancialState.bankBalances(
            stocks,
            finalCorpBondHoldings.lift(bankIndex).getOrElse(PLN.Zero),
            mortgageLoan = mortgageLoan,
          )
        },
      finalBankingMarket = finalBankingMarket,
      reassignedFirms = reassignedFirms,
      reassignedHouseholds = reassignedHouseholds,
      bailInLoss = finalResolution.bailInLoss,
      multiCapDestruction = finalResolution.capitalDestruction,
      interbankContagionLoss = failureDetection.contagion.totalLoss,
      newFailures = finalResolution.newFailures,
      capitalReconciliationResidual = finalResolution.capitalReconciliationResidual,
      bankCapitalTerms = resolved.bankCapitalTerms,
      bankFailureDiagnostics = failureDiagnostics,
      bankResolutionDiagnostics = resolutionDiagnostics,
      bankReconciliationDiagnostics = finalResolution.bankReconciliationDiagnostics,
      bankEclDiagnostics = eclDiagnostics,
      resolvedBank = Banking.aggregateFromBankStocks(finalFailureBanks, finalFailureStocks, finalCorpBondLookup),
      htmRealizedLoss = settlement.htmRealizedLoss,
      finalNbp = waterfall.finalNbp,
      finalNbpFinancialStocks = waterfall.finalNbpFinancialStocks,
      finalPpk = waterfall.finalPpk,
      finalPpkGovBondHoldings = waterfall.finalPpkGovBondHoldings,
      finalInsurance = waterfall.finalInsurance,
      finalInsuranceBalances = waterfall.finalInsuranceBalances,
      finalNbfi = waterfall.finalNbfi,
      finalNbfiBalances = waterfall.finalNbfiBalances,
      actualBondChange = wf.actualBondChange,
      standingFacilityBackstop = settlement.standingFacilityBackstop,
      foreignBondHoldings = waterfall.foreignBondHoldings,
      bidToCover = waterfall.bidToCover,
      govBondRuntimeMovements = waterfall.govBondRuntimeMovements,
    )

  /** Clears the interbank market, settles reserve-side NBP flows, and applies
    * HTM forced-sale reclassification before the government-bond waterfall.
    */
  private[banking] def runInterbankSettlement(
      in: StepInput,
      updatedBanks: Vector[Banking.BankState],
      updatedBankStocks: Vector[Banking.BankFinancialStocks],
      bankConfigs: Vector[Banking.Config],
      prevBankAgg: Banking.Aggregate,
      reserveInterest: Banking.PerBankAmounts,
      standingFacilityIncome: Banking.PerBankAmounts,
      interbankInterest: Banking.PerBankAmounts,
  )(using p: SimParams): InterbankSettlementResult =
    val interbankRate  = Banking.interbankRate(updatedBanks, updatedBankStocks, in.world.nbp.referenceRate)
    val hoarding       = InterbankContagion.hoardingFactor(prevBankAgg.nplRatio)
    val afterInterbank = Banking.clearInterbank(updatedBanks, updatedBankStocks, bankConfigs, hoarding)
    val nbpSettlement  = applyNbpReserveSettlement(
      afterInterbank.banks,
      afterInterbank.financialStocks,
      reserveInterest,
      standingFacilityIncome,
      interbankInterest,
      in.openEconomy.monetary.fxPlnInjection,
    )
    if nbpSettlement.residual != PLN.Zero then
      throw IllegalStateException(
        s"NBP reserve settlement left unallocated FX residual ${nbpSettlement.residual} after reserve-side settlement.",
      )
    val htmResult      = Banking.processHtmForcedSale(nbpSettlement.banks, nbpSettlement.financialStocks, in.openEconomy.monetary.newBondYield)

    InterbankSettlementResult(
      banks = htmResult.banks,
      financialStocks = htmResult.financialStocks,
      interbankRate = interbankRate,
      standingFacilityBackstop = nbpSettlement.standingFacilityBackstop,
      htmRealizedLoss = htmResult.totalRealizedLoss,
    )

  private def bankingMarketAfterSettlement(
      settlement: InterbankSettlementResult,
      bankConfigs: Vector[Banking.Config],
      prevBankAgg: Banking.Aggregate,
      in: StepInput,
  )(using p: SimParams): Banking.MarketState =
    val exp = in.world.mechanisms.expectations
    Banking.MarketState(
      interbankRate = settlement.interbankRate,
      configs = bankConfigs,
      interbankCurve = Some(
        YieldCurve.compute(
          settlement.interbankRate,
          nplRatio = prevBankAgg.nplRatio,
          credibility = exp.credibility,
          expectedInflation = exp.expectedInflation,
          targetInflation = p.monetary.targetInfl,
        ),
      ),
    )

  /** Runs the failure-resolution subpipeline after bond allocation has produced
    * the settled bank portfolios used for failure checks and exactness repair.
    */
  private[banking] def runFailureResolutionPipeline(
      in: StepInput,
      prevBankAgg: Banking.Aggregate,
      waterfall: BondWaterfallResult,
      settledBankCorpBonds: Vector[PLN],
      jstDepositChange: PLN,
      investNetDepositFlow: PLN,
      quasiFiscalDepositChange: PLN,
      mortgageFlows: HousingMarket.MortgageFlows,
      htmRealizedLoss: PLN,
  )(using p: SimParams): FailureResolutionPipelineResult =
    val bankCorpBondHoldingsAfterSettlement = Banking.bankCorpBondHoldingsFromVector(settledBankCorpBonds)
    val failureDetection                    = runFailureDetection(in, waterfall, bankCorpBondHoldingsAfterSettlement)
    val bailIn                              = runBailIn(failureDetection)
    val bankResolution                      = runBankResolution(failureDetection, bailIn, settledBankCorpBonds)
    val bankCapitalTerms                    = computeBankCapitalTerms(prevBankAgg, bankResolution.banks, in, mortgageFlows)
    val aggregateReconciliation             = reconcileAggregateExactness(
      banks = bankResolution.banks,
      financialStocks = bankResolution.financialStocks,
      bankCorpBondHoldings = bankResolution.bankCorpBondHoldings,
      prevBankAgg = prevBankAgg,
      in = in,
      jstDepositChange = jstDepositChange,
      investNetDepositFlow = investNetDepositFlow,
      quasiFiscalDepositChange = quasiFiscalDepositChange,
      mortgageFlows = mortgageFlows,
      bailInLoss = bailIn.loss,
      multiCapDestruction = failureDetection.capitalDestruction,
      interbankContagionLoss = failureDetection.contagion.totalLoss,
      htmRealizedLoss = htmRealizedLoss,
      bankCapitalTerms = bankCapitalTerms,
    )
    val finalResolution                     = reconcileResolution(failureDetection, bailIn, bankResolution, aggregateReconciliation)

    FailureResolutionPipelineResult(
      failureDetection = failureDetection,
      bailIn = bailIn,
      bankResolution = bankResolution,
      aggregateReconciliation = aggregateReconciliation,
      finalResolution = finalResolution,
      bankCapitalTerms = bankCapitalTerms,
    )

  /** Detects primary failures, applies interbank contagion losses, and then
    * performs the secondary failure check caused by those counterparty losses.
    */
  private[banking] def runFailureDetection(
      in: StepInput,
      waterfall: BondWaterfallResult,
      bankCorpBondHoldings: Banking.BankCorpBondHoldings,
  )(using p: SimParams): FailureDetectionResult =
    val primary   =
      Banking.checkFailures(
        waterfall.banks,
        waterfall.financialStocks,
        in.fiscal.m,
        true,
        in.priceEquity.newMacropru.ccyb,
        bankCorpBondHoldings,
      )
    val exposures = InterbankContagion.buildExposureMatrix(waterfall.banks, waterfall.financialStocks)
    val contagion =
      if primary.anyFailed then InterbankContagion.applyContagionLosses(primary.banks, exposures)
      else InterbankContagion.ContagionLossResult.unchanged(primary.banks)
    val secondary =
      Banking.checkFailures(contagion.banks, waterfall.financialStocks, in.fiscal.m, true, in.priceEquity.newMacropru.ccyb, bankCorpBondHoldings)
    val anyFailed = primary.anyFailed || secondary.anyFailed
    val events    = primary.events ++ secondary.events

    val newFailures                 =
      if anyFailed then waterfall.banks.zip(secondary.banks).count { case (pre, post) => !pre.failed && post.failed } else 0
    val primaryCapitalDestruction   =
      waterfall.banks
        .zip(primary.banks)
        .collect { case (pre, post) if !pre.failed && post.failed => pre.capital }
        .sumPln
    val secondaryCapitalDestruction =
      contagion.banks
        .zip(secondary.banks)
        .collect { case (pre, post) if !pre.failed && post.failed => pre.capital }
        .sumPln

    FailureDetectionResult(
      banks = secondary.banks,
      financialStocks = waterfall.financialStocks,
      primary = primary,
      secondary = secondary,
      contagion = contagion,
      failedBankIds = events.map(_.bankId).toSet,
      anyFailed = anyFailed,
      newFailures = newFailures,
      capitalDestruction = if anyFailed then primaryCapitalDestruction + secondaryCapitalDestruction else PLN.Zero,
      events = events,
    )

  /** Applies the depositor-side BRRD bail-in leg for banks that entered failure
    * in this month.
    */
  private[banking] def runBailIn(failure: FailureDetectionResult)(using p: SimParams): BailInStageResult =
    val result =
      if failure.failedBankIds.nonEmpty then Banking.applyBailIn(failure.banks, failure.financialStocks, failure.failedBankIds)
      else Banking.BailInResult(failure.banks, failure.financialStocks, PLN.Zero)
    BailInStageResult(
      banks = result.banks,
      financialStocks = result.financialStocks,
      eligibleBankIds = failure.failedBankIds,
      loss = result.totalLoss,
    )

  /** Resolves newly failed banks after bail-in through the purchase-and-
    * assumption path, preserving explicit bank shell slots.
    */
  private[banking] def runBankResolution(
      failure: FailureDetectionResult,
      bailIn: BailInStageResult,
      bankCorpBondHoldings: Vector[PLN],
  ): BankResolutionStageResult =
    val result =
      if failure.anyFailed then Banking.resolveFailures(bailIn.banks, bailIn.financialStocks, bankCorpBondHoldings)
      else Banking.ResolutionResult(bailIn.banks, bailIn.financialStocks, BankId.NoBank, bankCorpBondHoldings)
    BankResolutionStageResult(
      banks = result.banks,
      financialStocks = result.financialStocks,
      bankCorpBondHoldings = result.bankCorpBondHoldings,
      absorberBankId = result.absorberId,
      resolvedBankCount = result.resolvedBankCount,
      allFailedFallbackUsed = result.allFailedFallbackUsed,
    )

  /** Combines the explicit resolution path with the aggregate exactness
    * reconciliation, which may itself create one additional failure event.
    */
  private[banking] def reconcileResolution(
      failure: FailureDetectionResult,
      bailIn: BailInStageResult,
      resolution: BankResolutionStageResult,
      reconciled: AggregateReconciliationResult,
  ): ReconciledResolutionResult =
    ReconciledResolutionResult(
      banks = reconciled.banks,
      financialStocks = reconciled.financialStocks,
      bankCorpBondHoldings = reconciled.bankCorpBondHoldings,
      bailInLoss = bailIn.loss + reconciled.bailInLossDelta,
      capitalDestruction = failure.capitalDestruction + reconciled.capitalDestructionDelta,
      newFailures = failure.newFailures + reconciled.newFailuresDelta,
      failureEvents = failure.events ++ reconciled.failureEvents,
      resolvedBankCount = resolution.resolvedBankCount + reconciled.resolvedBanksDelta,
      allFailedFallbackUsed = resolution.allFailedFallbackUsed || reconciled.allFailedFallbackUsed,
      bankReconciliationDiagnostics = reconciled.bankReconciliationDiagnostics,
      capitalReconciliationResidual = reconciled.capitalResidual,
    )

  /** Executes the government-bond waterfall after reserve settlement and HTM
    * reclassification. Issuer stock changes land on bank portfolios first; the
    * waterfall then sells actual available bonds to foreign, NBP, PPK,
    * insurance, and TFI buyers in sequence.
    */
  private[banking] def runBondWaterfall(
      in: StepInput,
      wf: BondWaterfallInputs,
      banks: Vector[Banking.BankState],
      financialStocks: Vector[Banking.BankFinancialStocks],
  )(using p: SimParams): BondWaterfallResult =
    val afterBonds    =
      if wf.actualBondChange > PLN.Zero then Banking.allocateBondIssuance(banks, financialStocks, wf.actualBondChange, in.openEconomy.monetary.newBondYield)
      else if wf.actualBondChange < PLN.Zero then
        Banking.allocateBondRedemption(banks, financialStocks, PLN.fromRaw(-wf.actualBondChange.toLong), in.openEconomy.monetary.newBondYield)
      else Banking.BankStockState(banks, financialStocks)
    val primaryByBank = afterBonds.financialStocks
      .zip(financialStocks)
      .map((after, before) => Banking.govBondHoldings(after) - Banking.govBondHoldings(before))

    val bankDeposits  = afterBonds.financialStocks.map(_.totalDeposits).sumPln
    val auctionResult = BondAuction.auction(
      newIssuance = wf.actualBondChange.max(PLN.Zero),
      bankBondCapacity = bankDeposits * p.fiscal.bankBondAbsorptionShare,
      marketYield = in.openEconomy.monetary.newBondYield,
      erChange = wf.erChange,
    )
    val foreignSale   = Banking.sellToBuyer(afterBonds.banks, afterBonds.financialStocks, auctionResult.foreignAbsorbed)
    val qeSale        = Banking.sellToBuyer(foreignSale.banks, foreignSale.financialStocks, wf.qeRequested)
    val ppkSale       = Banking.sellToBuyer(qeSale.banks, qeSale.financialStocks, wf.ppkRequested)
    val insSale       = Banking.sellToBuyer(ppkSale.banks, ppkSale.financialStocks, wf.insRequested)
    val tfiSale       = Banking.sellToBuyer(insSale.banks, insSale.financialStocks, wf.tfiRequested)

    val finalNbp                = in.openEconomy.monetary.postFxNbp.copy(qeCumulative = in.openEconomy.monetary.postFxNbp.qeCumulative + qeSale.actualSold)
    val finalNbpFinancialStocks = in.openEconomy.monetary.postFxNbpFinancialStocks.copy(
      govBondHoldings = in.openEconomy.monetary.postFxNbpFinancialStocks.govBondHoldings + qeSale.actualSold,
    )
    val finalInsuranceBalances  = in.openEconomy.nonBank.newInsuranceBalances.copy(
      govBondHoldings = in.ledgerFinancialState.insurance.govBondHoldings + insSale.actualSold,
    )
    val finalNbfiBalances       = in.openEconomy.nonBank.newNbfiBalances.copy(
      tfiGovBondHoldings = in.ledgerFinancialState.funds.nbfi.govBondHoldings + tfiSale.actualSold,
    )

    BondWaterfallResult(
      banks = tfiSale.banks,
      financialStocks = tfiSale.financialStocks,
      finalNbp = finalNbp,
      finalNbpFinancialStocks = finalNbpFinancialStocks,
      finalPpk = in.labor.newPpk,
      finalPpkGovBondHoldings = in.ledgerFinancialState.funds.ppkGovBondHoldings + ppkSale.actualSold,
      finalInsurance = in.openEconomy.nonBank.newInsurance,
      finalInsuranceBalances = finalInsuranceBalances,
      finalNbfi = in.openEconomy.nonBank.newNbfi,
      finalNbfiBalances = finalNbfiBalances,
      foreignBondHoldings = in.ledgerFinancialState.foreign.govBondHoldings + foreignSale.actualSold,
      bidToCover = auctionResult.bidToCover,
      govBondRuntimeMovements = GovBondRuntimeMovements(
        primaryByBank = primaryByBank,
        foreignPurchaseByBank = foreignSale.soldByBank,
        nbpQePurchaseByBank = qeSale.soldByBank,
        ppkPurchaseByBank = ppkSale.soldByBank,
        insurancePurchaseByBank = insSale.soldByBank,
        tfiPurchaseByBank = tfiSale.soldByBank,
      ),
    )

  private def computeBankCapitalTerms(
      prevBankAgg: Banking.Aggregate,
      finalBanks: Vector[Banking.BankState],
      in: StepInput,
      mortgageFlows: HousingMarket.MortgageFlows,
  )(using p: SimParams): BankCapitalTerms =
    require(
      finalBanks.length == in.banks.length,
      s"BankingStepRunner bank-capital terms require aligned bank rows, got ${finalBanks.length} final banks and ${in.banks.length} opening banks",
    )
    val yieldChange        = in.openEconomy.monetary.newBondYield - in.world.gov.bondYield
    val unrealizedBondLoss =
      if yieldChange > Rate.Zero then prevBankAgg.afsBonds * yieldChange * p.banking.govBondDuration
      else PLN.Zero
    var eclRaw             = 0L
    var i                  = 0
    while i < finalBanks.length do
      val curr     = finalBanks(i)
      val prev     = in.banks(i)
      val currProv = EclStaging.allowance(curr.eclStaging)
      val prevProv = EclStaging.allowance(prev.eclStaging)
      eclRaw += (currProv - prevProv).toLong
      i += 1
    val eclProvisionChange = PLN.fromRaw(eclRaw)
    val capitalGrossIncome = in.firm.intIncome +
      prevBankAgg.govBondHoldings * in.openEconomy.monetary.newBondYield.monthly -
      in.householdFinancial.depositInterestPaid + in.openEconomy.banking.totalReserveInterest +
      in.openEconomy.banking.totalStandingFacilityIncome + in.openEconomy.banking.totalInterbankInterest +
      mortgageFlows.interest + (in.householdFinancial.consumerDebtService - in.householdFinancial.consumerPrincipal) +
      in.openEconomy.corpBonds.corpBondBankCoupon
    BankCapitalTerms(
      unrealizedBondLoss = unrealizedBondLoss,
      eclProvisionChange = eclProvisionChange,
      capitalGrossIncome = capitalGrossIncome,
      retainedIncome = capitalGrossIncome * p.banking.profitRetention,
    )

  private def eclGdpGrowth(in: StepInput): Coefficient =
    val prevGdp = in.world.cachedMonthlyGdpProxy
    if prevGdp > PLN.Zero then (in.priceEquity.gdp.ratioTo(prevGdp) - Scalar.One).toCoefficient else Coefficient.Zero

  private def eclReferenceUnemployment(in: StepInput)(using p: SimParams): Share =
    val lagged = in.world.pipeline.laggedUnemploymentRate
    if lagged > Share.Zero then lagged else p.pop.initialUnemploymentRate

  private def computeBankEclDiagnostics(
      openingBanks: Vector[Banking.BankState],
      closingBanks: Vector[Banking.BankState],
      migrationRate: Share,
      gdpGrowthMonthly: Coefficient,
  )(using p: SimParams): BankEclDiagnostics =
    val openingAllowance        = openingBanks.iterator.map(bank => EclStaging.allowance(bank.eclStaging)).sumPln
    val closingAllowance        = closingBanks.iterator.map(bank => EclStaging.allowance(bank.eclStaging)).sumPln
    val closingStages           = closingBanks.iterator.map(_.eclStaging)
    var stageTotalRaw           = 0L
    closingStages.foreach: staging =>
      stageTotalRaw += staging.stage1.toLong + staging.stage2.toLong + staging.stage3.toLong
    val stageTotal              = PLN.fromRaw(stageTotalRaw)
    val baselineStage1Allowance = stageTotal * p.banking.eclRate1
    BankEclDiagnostics(
      openingAllowance = openingAllowance,
      closingAllowance = closingAllowance,
      baselineStage1Allowance = baselineStage1Allowance,
      excessAllowance = (closingAllowance - baselineStage1Allowance).max(PLN.Zero),
      migrationRate = migrationRate,
      gdpGrowthMonthly = gdpGrowthMonthly,
    )

  private def reconcileAggregateExactness(
      banks: Vector[Banking.BankState],
      financialStocks: Vector[Banking.BankFinancialStocks],
      bankCorpBondHoldings: Vector[PLN],
      prevBankAgg: Banking.Aggregate,
      in: StepInput,
      jstDepositChange: PLN,
      investNetDepositFlow: PLN,
      quasiFiscalDepositChange: PLN,
      mortgageFlows: HousingMarket.MortgageFlows,
      bailInLoss: PLN,
      multiCapDestruction: PLN,
      interbankContagionLoss: PLN,
      htmRealizedLoss: PLN,
      bankCapitalTerms: BankCapitalTerms,
  )(using p: SimParams): AggregateReconciliationResult =
    if banks.isEmpty then
      AggregateReconciliationResult(
        banks,
        financialStocks,
        bankCorpBondHoldings,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        0,
        Vector.empty,
        false,
        BankReconciliationDiagnostics.zero,
      )
    else
      val target         = aggregateReconciliationTarget(
        prevBankAgg = prevBankAgg,
        in = in,
        jstDepositChange = jstDepositChange,
        investNetDepositFlow = investNetDepositFlow,
        quasiFiscalDepositChange = quasiFiscalDepositChange,
        mortgageFlows = mortgageFlows,
        bailInLoss = bailInLoss,
        multiCapDestruction = multiCapDestruction,
        interbankContagionLoss = interbankContagionLoss,
        htmRealizedLoss = htmRealizedLoss,
        bankCapitalTerms = bankCapitalTerms,
      )
      val actualDeposits = financialStocks.iterator.map(_.totalDeposits).sumPln
      val actualCapital  = banks.iterator.map(_.capital).sumPln
      val depResidual    = target.depositsResidual - actualDeposits
      val capResidual    = target.capitalResidual - actualCapital
      if depResidual == PLN.Zero && capResidual == PLN.Zero then
        AggregateReconciliationResult(
          banks,
          financialStocks,
          bankCorpBondHoldings,
          PLN.Zero,
          PLN.Zero,
          PLN.Zero,
          PLN.Zero,
          0,
          Vector.empty,
          false,
          BankReconciliationDiagnostics.zero,
        )
      else
        val targetIdx                 = banks.lastIndexWhere(!_.failed) match
          case -1 => banks.indices.last
          case i  => i
        val reconciled                = reconcileSingleBank(banks(targetIdx), financialStocks(targetIdx), depResidual, capResidual)
        val nextBanks                 = banks.updated(targetIdx, reconciled._1)
        val nextStocks                = financialStocks.updated(targetIdx, reconciled._2)
        val targetCorpBonds           = (bankId: BankId) => bankCorpBondHoldings.lift(bankId.toInt).getOrElse(PLN.Zero)
        val beforeBank                = banks(targetIdx)
        val beforeStocks              = financialStocks(targetIdx)
        val afterBank                 = nextBanks(targetIdx)
        val afterStocks               = nextStocks(targetIdx)
        val reasonBefore              = Banking.failureReason(beforeBank, beforeStocks, in.priceEquity.newMacropru.ccyb, targetCorpBonds)
        val reasonAfter               = Banking.failureReason(afterBank, afterStocks, in.priceEquity.newMacropru.ccyb, targetCorpBonds)
        val reconciliationDiagnostics = BankReconciliationDiagnostics.fromPatch(
          targetBankId = beforeBank.id,
          capitalResidual = capResidual,
          targetCapitalBefore = beforeBank.capital,
          targetCapitalAfter = afterBank.capital,
          targetCarBefore = Banking.car(beforeBank, beforeStocks, targetCorpBonds(beforeBank.id)),
          targetCarAfter = Banking.car(afterBank, afterStocks, targetCorpBonds(afterBank.id)),
          reasonBefore = reasonBefore,
          reasonAfter = reasonAfter,
        )
        if reasonAfter.isEmpty || afterBank.failed then
          AggregateReconciliationResult(
            nextBanks,
            nextStocks,
            bankCorpBondHoldings,
            depResidual,
            capResidual,
            PLN.Zero,
            PLN.Zero,
            0,
            Vector.empty,
            false,
            reconciliationDiagnostics,
          )
        else
          val failCheck =
            Banking.checkFailures(Vector(afterBank), Vector(afterStocks), in.fiscal.m, true, in.priceEquity.newMacropru.ccyb, targetCorpBonds)
          if !failCheck.anyFailed then
            AggregateReconciliationResult(
              nextBanks,
              nextStocks,
              bankCorpBondHoldings,
              depResidual,
              capResidual,
              PLN.Zero,
              PLN.Zero,
              0,
              Vector.empty,
              false,
              reconciliationDiagnostics,
            )
          else
            val failedBank = failCheck.banks.head
            val bailIn     = Banking.applyBailIn(nextBanks.updated(targetIdx, failedBank), nextStocks, failCheck.events.map(_.bankId).toSet)
            val resolved   = Banking.resolveFailures(bailIn.banks, bailIn.financialStocks, bankCorpBondHoldings)
            AggregateReconciliationResult(
              resolved.banks,
              resolved.financialStocks,
              resolved.bankCorpBondHoldings,
              depResidual,
              capResidual,
              bailIn.totalLoss,
              nextBanks(targetIdx).capital,
              1,
              failCheck.events,
              resolved.allFailedFallbackUsed,
              reconciliationDiagnostics,
              resolvedBanksDelta = resolved.resolvedBankCount,
            )

  private def aggregateReconciliationTarget(
      prevBankAgg: Banking.Aggregate,
      in: StepInput,
      jstDepositChange: PLN,
      investNetDepositFlow: PLN,
      quasiFiscalDepositChange: PLN,
      mortgageFlows: HousingMarket.MortgageFlows,
      bailInLoss: PLN,
      multiCapDestruction: PLN,
      interbankContagionLoss: PLN,
      htmRealizedLoss: PLN,
      bankCapitalTerms: BankCapitalTerms,
  )(using p: SimParams): AggregateReconciliation =
    val capitalLosses  = in.firm.nplLoss + mortgageFlows.defaultLoss + in.householdFinancial.consumerNplLoss +
      in.openEconomy.corpBonds.corpBondBankDefaultLoss +
      Banking.computeBfgLevy(in.banks, in.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks)).total +
      interbankContagionLoss + bankCapitalTerms.unrealizedBondLoss + htmRealizedLoss +
      bankCapitalTerms.eclProvisionChange + multiCapDestruction
    val targetCapital  = prevBankAgg.capital - capitalLosses + bankCapitalTerms.retainedIncome
    val targetDeposits = prevBankAgg.deposits + in.householdIncome.totalIncome - in.householdIncome.consumption +
      investNetDepositFlow + jstDepositChange + quasiFiscalDepositChange + in.priceEquity.netDomesticDividends -
      in.priceEquity.foreignDividendOutflow - in.householdFinancial.remittanceOutflow + in.householdFinancial.diasporaInflow +
      in.householdFinancial.tourismExport - in.householdFinancial.tourismImport - bailInLoss + in.firm.sumNewLoans -
      in.firm.sumFirmPrincipal + in.householdFinancial.consumerOrigination + in.openEconomy.nonBank.insNetDepositChange +
      in.openEconomy.nonBank.nbfiDepositDrain
    AggregateReconciliation(
      depositsResidual = targetDeposits,
      capitalResidual = targetCapital,
    )

  private def reconcileSingleBank(
      bank: Banking.BankState,
      stocks: Banking.BankFinancialStocks,
      depositResidual: PLN,
      capitalResidual: PLN,
  )(using p: SimParams): (Banking.BankState, Banking.BankFinancialStocks) =
    val newDeposits = stocks.totalDeposits + depositResidual
    (
      bank.copy(capital = bank.capital + capitalResidual),
      stocks.copy(
        totalDeposits = newDeposits,
        demandDeposit = newDeposits * (Share.One - p.banking.termDepositFrac),
        termDeposit = newDeposits * p.banking.termDepositFrac,
      ),
    )

  /** Monetary aggregates (M0/M1/M2/M3) when credit diagnostics enabled. */
  private def computeMonetaryAggregates(
      finalBanks: Vector[Banking.BankState],
      ledgerFinancialState: LedgerFinancialState,
  ): Option[Banking.MonetaryAggregates] =
    Some(
      Banking.MonetaryAggregates.computeFromBankStocks(
        finalBanks,
        ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks),
        ledgerFinancialState.funds.nbfi.tfiUnit,
        CorporateBondOwnership.issuerOutstanding(ledgerFinancialState),
      ),
    )

  /** Apply NBP-side reserve settlement deltas to bank reserve balances.
    *
    * Reserve remuneration, standing-facility settlement, interbank settlement
    * routed via NBP, and FX intervention PLN injection/drain all land on the
    * same reserve-side settlement channel. If a drain would push a bank below
    * zero reserves, the shortfall is converted into explicit standing-facility
    * borrowing while reserve balances stay non-negative.
    */
  private[amorfati] def applyNbpReserveSettlement(
      banks: Vector[Banking.BankState],
      financialStocks: Vector[Banking.BankFinancialStocks],
      reserveInterest: Banking.PerBankAmounts,
      standingFacilityIncome: Banking.PerBankAmounts,
      interbankInterest: Banking.PerBankAmounts,
      fxInjection: PLN,
  ): ReserveSettlementResult =
    val distributedFx                        = distributeFxInjectionByDeposits(financialStocks, fxInjection)
    val updatedStocks                        = Vector.newBuilder[Banking.BankFinancialStocks]
    val (standingFacilityBackstop, residual) =
      banks.zip(financialStocks).zipWithIndex.foldLeft((PLN.Zero, distributedFx.residual)) { (acc, rowAndIdx) =>
        val (accBackstop, accResidual) = acc
        val ((_, stocks), idx)         = rowAndIdx
        val delta                      =
          reserveInterest.perBank(idx) +
            standingFacilityIncome.perBank(idx) +
            interbankInterest.perBank(idx) +
            distributedFx.allocations(idx)
        val updated                    = stocks.reserve + delta
        if updated >= PLN.Zero then
          updatedStocks += stocks.copy(reserve = updated)
          (accBackstop, accResidual)
        else
          updatedStocks += stocks.copy(reserve = PLN.Zero)
          (accBackstop - updated, accResidual)
      }

    ReserveSettlementResult(banks, updatedStocks.result(), standingFacilityBackstop, residual)

  /** Distribute FX intervention PLN injection across banks proportional to
    * deposit market share, adjusting reservesAtNbp. EUR purchase → PLN injected
    * into banking system; EUR sale → PLN drained. Any amount that cannot be
    * allocated is surfaced via `residual`.
    */
  private[amorfati] def distributeFxInjection(
      banks: Vector[Banking.BankState],
      financialStocks: Vector[Banking.BankFinancialStocks],
      injection: PLN,
  ): ReserveSettlementResult =
    val zeros = Banking.PerBankAmounts(Vector.fill(banks.size)(PLN.Zero), PLN.Zero)
    applyNbpReserveSettlement(
      banks,
      financialStocks,
      reserveInterest = zeros,
      standingFacilityIncome = zeros,
      interbankInterest = zeros,
      fxInjection = injection,
    )

  private def distributeFxInjectionByDeposits(
      financialStocks: Vector[Banking.BankFinancialStocks],
      injection: PLN,
  ): FxSettlementAllocation =
    if injection == PLN.Zero then FxSettlementAllocation(Vector.fill(financialStocks.size)(PLN.Zero), PLN.Zero)
    else
      val weights = financialStocks.map(_.totalDeposits.toLong.max(0L)).toArray
      if weights.sum <= 0L then FxSettlementAllocation(Vector.fill(financialStocks.size)(PLN.Zero), injection)
      else
        val allocations = Distribute
          .distribute(math.abs(injection.toLong), weights)
          .iterator
          .map { rawAllocated =>
            if injection >= PLN.Zero then PLN.fromRaw(rawAllocated) else PLN.fromRaw(-rawAllocated)
          }
          .toVector
        FxSettlementAllocation(
          allocations = allocations,
          residual = injection - allocations.foldLeft(PLN.Zero)(_ + _),
        )
