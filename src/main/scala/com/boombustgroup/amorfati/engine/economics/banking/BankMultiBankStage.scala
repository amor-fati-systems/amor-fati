package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.diagnostics.banking.{BankEclDiagnostics, BankFailureDiagnostics, BankResolutionDiagnostics}
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.engine.markets.HousingMarket
import com.boombustgroup.amorfati.types.*

/** Multi-bank monthly balance-sheet stage: per-bank P&L updates, market
  * settlement, failure pipeline, client reassignment, and closing bank rows.
  */
private[banking] object BankMultiBankStage:
  private val NplMonthlyWriteOff: Share = Share.decimal(5, 2)  // monthly NPL write-off rate (aggregate and per-bank)
  private val ShortLoanFrac: Share      = Share.decimal(20, 2) // fraction of loans in short-term maturity bucket
  private val MediumLoanFrac: Share     = Share.decimal(30, 2) // fraction of loans in medium-term maturity bucket
  private val LongLoanFrac: Share       = Share.decimal(50, 2) // fraction of loans in long-term maturity bucket

  private[banking] def closingFirmLoanBook(openingFirmLoan: PLN, newLoans: PLN, principalRepaid: PLN, grossDefault: PLN): PLN =
    (openingFirmLoan + newLoans - principalRepaid - grossDefault).max(PLN.Zero)

  /** Multi-bank update: per-bank loop, interbank clearing, bond allocation,
    * failure resolution.
    */
  def process(
      in: StepInput,
      jstDepositChange: PLN,
      investNetDepositFlow: PLN,
      quasiFiscalDepositChange: PLN,
      mortgageFlows: HousingMarket.MortgageFlows,
      wf: BondWaterfallInputs,
      perBankPolishLevy: Banking.PerBankAmounts,
  )(using p: SimParams): MultiBankResult =
    val banks               = in.banks
    val openingBankStocks   = in.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks)
    // We keep the opening bank-side consumer-loan stock but realign it to the
    // current household routing keys and household-level consumer-loan balances
    // carried into the firm stage. This assumes no stage between the opening
    // ledger snapshot and firm stage mutates household consumerLoan principal;
    // only routing may drift.
    val bankStocks          = BankingHouseholdBooks.alignConsumerLoanBookToHouseholdRouting(
      in.firm.households,
      in.firm.ledgerFinancialState.households,
      openingBankStocks,
    )
    require(
      bankStocks.map(_.consumerLoan).sumPln == openingBankStocks.map(_.consumerLoan).sumPln,
      "BankMultiBankStage consumer-loan realignment must preserve the aggregate opening bank loan book",
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

    val settledBankCorpBonds = BankBondWaterfall.settleCorpBondHoldings(
      previous = CorporateBondOwnership.bankHolderBalances(in.ledgerFinancialState),
      previousAggregateStock = CorporateBondOwnership.stockStateFromLedger(in.ledgerFinancialState),
      nextAggregateStock = in.openEconomy.corpBonds.closingCorpBondProjection,
      totalBondIssuance = in.firm.actualBondIssuance,
      perBankWorkers = in.firm.perBankWorkers,
    )

    val updatedRows           = new Array[SingleBankUpdate](banks.length)
    var bankIndex             = 0
    while bankIndex < banks.length do
      val b           = banks(bankIndex)
      val stocks      = bankStocks(bankIndex)
      val bId         = b.id.toInt
      val workerShare = workerShares(bId)
      val hhFlows     = resolvePerBankHhFlows(bId, in.householdIncome.perBankHhFlowsOpt, totalWorkers, in.firm.perBankWorkers, in)
      updatedRows(bankIndex) = updateSingleBank(
        b,
        stocks,
        hhFlows,
        workerShare,
        mortgageFlows,
        perBankReserveInt,
        perBankStandingFac,
        perBankInterbankInt,
        perBankPolishLevy,
        jstDepositChange,
        investNetDepositFlow,
        quasiFiscalDepositChange,
        in,
      )
      bankIndex += 1
    val updatedBanks          = new Array[Banking.BankState](updatedRows.length)
    val updatedBankStocks     = new Array[Banking.BankFinancialStocks](updatedRows.length)
    var creditLosses          = BankCreditLossAccounting.Breakdown.zero
    var updatedIndex          = 0
    while updatedIndex < updatedRows.length do
      val updated = updatedRows(updatedIndex)
      updatedBanks(updatedIndex) = updated.bank
      updatedBankStocks(updatedIndex) = updated.financialStocks
      creditLosses = creditLosses + updated.creditLosses
      updatedIndex += 1
    val aggregateCreditLosses = creditLosses.copy(
      corpBondDefaultLoss = in.openEconomy.corpBonds.corpBondBankDefaultLoss,
    )

    runSettlementBoundary(
      in,
      updatedBanks.toVector,
      updatedBankStocks.toVector,
      aggregateCreditLosses,
      in.world.bankingSector.configs,
      wf,
      perBankReserveInt,
      perBankStandingFac,
      perBankInterbankInt,
      jstDepositChange,
      investNetDepositFlow,
      quasiFiscalDepositChange,
      mortgageFlows,
      perBankPolishLevy.total,
      settledBankCorpBonds,
    )

  /** Runs the multi-bank settlement and resolution boundary after per-bank
    * balance-sheet updates.
    */
  private def runSettlementBoundary(
      in: StepInput,
      updatedBanks: Vector[Banking.BankState],
      updatedBankStocks: Vector[Banking.BankFinancialStocks],
      creditLosses: BankCreditLossAccounting.Breakdown,
      bankConfigs: Vector[Banking.Config],
      wf: BondWaterfallInputs,
      perBankReserveInt: Banking.PerBankAmounts,
      perBankStandingFac: Banking.PerBankAmounts,
      perBankInterbankInt: Banking.PerBankAmounts,
      jstDepositChange: PLN,
      investNetDepositFlow: PLN,
      quasiFiscalDepositChange: PLN,
      mortgageFlows: HousingMarket.MortgageFlows,
      polishBankLevyTax: PLN,
      settledBankCorpBonds: Vector[PLN],
  )(using p: SimParams): MultiBankResult =
    val prevBankAgg =
      Banking.aggregateFromBankStocks(
        in.banks,
        in.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks),
        bankId => CorporateBondOwnership.bankHolderFor(in.ledgerFinancialState, bankId),
      )

    val settlement            = BankInterbankSettlement.run(
      in = in,
      updatedBanks = updatedBanks,
      updatedBankStocks = updatedBankStocks,
      bankConfigs = bankConfigs,
      prevBankAgg = prevBankAgg,
      reserveInterest = perBankReserveInt,
      standingFacilityIncome = perBankStandingFac,
      interbankInterest = perBankInterbankInt,
    )
    val waterfall             = BankBondWaterfall.run(in, wf, settlement.banks, settlement.financialStocks)
    val finalBankingMarket    = BankInterbankSettlement.marketState(settlement, bankConfigs, prevBankAgg, in)
    val resolved              = BankFailurePipeline.run(
      in = in,
      prevBankAgg = prevBankAgg,
      waterfall = waterfall,
      creditLosses = creditLosses,
      settledBankCorpBonds = settledBankCorpBonds,
      jstDepositChange = jstDepositChange,
      investNetDepositFlow = investNetDepositFlow,
      quasiFiscalDepositChange = quasiFiscalDepositChange,
      mortgageFlows = mortgageFlows,
      polishBankLevyTax = polishBankLevyTax,
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
    val reassignedFirmRows = new Array[Firm.State](in.firm.ioFirms.length)
    var firmIndex          = 0
    while firmIndex < in.firm.ioFirms.length do
      val f          = in.firm.ioFirms(firmIndex)
      val nextBankId = Banking.reassignBankId(f.bankId, finalFailureBanks, finalFailureStocks, finalCorpBondLookup)
      reassignedFirmRows(firmIndex) =
        if nextBankId == f.bankId then f else f.copy(bankId = nextBankId)
      firmIndex += 1
    val reassignedFirms    = reassignedFirmRows.toVector
    val postFailureHhRows  = new Array[Household.State](in.firm.households.length)
    var householdIndex     = 0
    while householdIndex < in.firm.households.length do
      val h          = in.firm.households(householdIndex)
      val nextBankId = Banking.reassignBankId(h.bankId, finalFailureBanks, finalFailureStocks, finalCorpBondLookup)
      postFailureHhRows(householdIndex) =
        if nextBankId == h.bankId then h else h.copy(bankId = nextBankId)
      householdIndex += 1
    val postFailureHh      = postFailureHhRows.toVector

    // Deposit mobility: HH may switch banks based on health signals and panic
    val mobilityResult       = DepositMobility(postFailureHh, finalFailureBanks, finalFailureStocks, anyFailureForMobility, in.depositRng, finalCorpBondLookup)
    val reassignedHouseholds = mobilityResult.households

    // Deposit flows take effect next month when HH income/consumption routes
    // to new bankId. No immediate balance sheet transfer - consistent with
    // 1-month account transfer lag and avoids SFC flow mismatch.
    MultiBankResult(
      finalBanks = finalFailureBanks,
      finalBankCorpBondHoldings = finalCorpBondHoldings,
      finalBankLedgerBalances = finalFailureBanks
        .zip(finalFailureStocks)
        .map { case (bank, stocks) =>
          val mortgageLoan =
            if bank.failed then PLN.Zero
            else stocks.mortgageLoan
          LedgerFinancialState.bankBalances(
            stocks,
            finalCorpBondLookup(bank.id),
            mortgageLoan = mortgageLoan,
          )
        },
      finalBankingMarket = finalBankingMarket,
      reassignedFirms = reassignedFirms,
      reassignedHouseholds = reassignedHouseholds,
      bailInLoss = finalResolution.bailInLoss,
      polishBankLevyTax = polishBankLevyTax,
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
      perBankPolishLevy: Banking.PerBankAmounts,
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
    val firmLoss      = BankCreditLossAccounting.firm(bankNplNew)
    val bankNplLoss   = firmLoss.netCapitalLoss
    val bankIntIncome = in.firm.perBankIntIncome(bId)
    val bankBondInc   = Banking.govBondHoldings(stocks) * in.openEconomy.monetary.newBondYield.monthly
    val bankResInt    = perBankReserveInt.perBank(bId)
    val bankSfInc     = perBankStandingFac.perBank(bId)
    val bankIbInt     = perBankInterbankInt.perBank(bId)
    val newLoansTotal =
      closingFirmLoanBook(
        openingFirmLoan = stocks.firmLoan,
        newLoans = in.firm.perBankNewLoans(bId),
        principalRepaid = in.firm.perBankFirmPrincipal(bId),
        grossDefault = bankNplNew,
      )

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
    val mortgageLoss              = BankCreditLossAccounting.mortgage(mortgageFlows.defaultAmount * workerShare)
    val bankMortgageNplLoss       = mortgageLoss.netCapitalLoss
    val consumerBridgeChargeOff   = hhFlows.ccDefault - hhFlows.ccLoanDefault
    require(consumerBridgeChargeOff >= PLN.Zero, s"Consumer default ${hhFlows.ccDefault} must cover consumer-loan default ${hhFlows.ccLoanDefault}")
    val consumerLoss              = BankCreditLossAccounting.consumer(hhFlows.ccLoanDefault)
    val bankCcNplLoss             = consumerLoss.netCapitalLoss
    val bankCcStockReduction: PLN = in.householdIncome.perBankHhFlowsOpt match
      case Some(pbf) => pbf(bId).consumerPrincipal
      case _         => hhFlows.ccPrincipal
    val bankCcInterestIncome      = hhFlows.ccDebtService - hhFlows.ccPrincipal
    val bankCorpBondCoupon        = in.openEconomy.corpBonds.corpBondBankCoupon * workerShare
    val bankCorpBondDefaultLoss   = in.openEconomy.corpBonds.corpBondBankDefaultLoss * workerShare
    val creditLosses              = BankCreditLossAccounting.Breakdown(
      firm = firmLoss,
      mortgage = mortgageLoss,
      consumer = consumerLoss,
      corpBondDefaultLoss = bankCorpBondDefaultLoss,
    )
    val bankBfgLevy               =
      if !b.failed then stocks.totalDeposits * p.banking.bfgLevyRate.monthly
      else PLN.Zero
    val bankPolishLevyTax         = perBankPolishLevy.perBank(bId)

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
        polishBankLevyTax = bankPolishLevyTax,
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
      creditLosses = creditLosses,
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
