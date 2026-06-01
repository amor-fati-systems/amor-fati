package com.boombustgroup.amorfati.montecarlo

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.{HousingConfig, SimParams}
import com.boombustgroup.amorfati.engine.*
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.engine.markets.HousingMarket
import com.boombustgroup.amorfati.engine.mechanisms.Macroprudential
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.amorfati.agents.Region

/** Typed column schema for Monte Carlo timeseries output.
  *
  * Composable: each group is a `Vector[ColumnDef]`, all composed with `++`.
  * Column handles (`Col`) are derived by name lookup — no mutable counter.
  */
object McTimeseriesSchema:

  private case class SectorColumns(expectedSectorName: String, columnStem: String):
    def autoColName: String   = s"${columnStem}_Auto"
    def outputColName: String = s"${columnStem}_Output"
    def sigmaColName: String  = s"${columnStem}_Sigma"

  private val sectorSchemaPairs: Vector[(String, String)] =
    SimParams.SchemaSectors.map(schemaSector => schemaSector.name -> schemaSector.outputStem)

  require(
    sectorSchemaPairs.length == SimParams.SchemaSectorCount &&
      sectorSchemaPairs.map(_._1) == SimParams.SchemaSectorNames &&
      sectorSchemaPairs.map(_._2).distinct.length == sectorSchemaPairs.length,
    s"McTimeseriesSchema sector schema must define ${SimParams.SchemaSectorCount} unique (name, stem) pairs, got ${sectorSchemaPairs.mkString(", ")}",
  )

  private val sectorColumns: Vector[SectorColumns] =
    SimParams.SchemaSectors.map(schemaSector => SectorColumns(schemaSector.name, schemaSector.outputStem))

  // -------------------------------------------------------------------------
  //  ColumnDef + Ctx
  // -------------------------------------------------------------------------

  /** Column definition: name paired with its computation. */
  private final case class ColumnDef private (name: String, compute: Ctx => MetricValue)

  /** Named schema section. The flat CSV contract is the deterministic
    * concatenation of these domain groups.
    */
  private final case class ColumnGroup(name: String, columns: Vector[ColumnDef])

  private object ColumnDef:
    def apply[A](name: String, compute: Ctx => A)(using encoder: MetricEncoder[A]): ColumnDef =
      new ColumnDef(name, ctx => encoder.encode(compute(ctx)))

    def macroPln(name: String, compute: Ctx => PLN): ColumnDef =
      new ColumnDef(name, ctx => summon[MetricEncoder[PLN]].encode(ctx.polandScale(compute(ctx))))

  /** Shared pre-computed context (computed once per timestep). */
  private class Ctx(
      val executionMonth: ExecutionMonth,
      val world: World,
      val firms: Vector[Firm.State],
      val households: Vector[Household.State],
      val banks: Vector[Banking.BankState],
      val householdAggregates: Household.Aggregates,
      val ledgerFinancialState: LedgerFinancialState,
      val living: Vector[Firm.State],
      val nLiving: Int,
      val aliveBanks: Vector[Banking.BankState],
      val p: SimParams,
  ):
    require(
      world.currentSigmas.length == p.sectorDefs.length,
      s"McTimeseriesSchema requires world.currentSigmas to have ${p.sectorDefs.length} entries to match sectorDefs, got ${world.currentSigmas.length}",
    )

    given SimParams                                                                                 = p
    private val sectorIndexByName: Map[String, Int]                                                 = p.sectorDefs.iterator.map(_.name).zipWithIndex.map((name, idx) => name -> idx).toMap
    lazy val bankCorpBondHoldings: Banking.BankCorpBondHoldings                                     =
      Banking.bankCorpBondHoldingsFromVector(ledgerFinancialState.banks.map(_.corpBond))
    lazy val bankAgg: Banking.Aggregate                                                             =
      Banking.aggregateFromBankStocks(banks, ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks), bankCorpBondHoldings)
    lazy val bankCapital: BankCapitalDiagnostics                                                    = world.flows.bankCapital
    lazy val bankFailure: BankFailureDiagnostics                                                    = world.flows.bankFailure
    lazy val bankResolution: BankResolutionDiagnostics                                              = world.flows.bankResolution
    lazy val bankReconciliation: BankReconciliationDiagnostics                                      = world.flows.bankReconciliation
    lazy val bankEcl: BankEclDiagnostics                                                            = world.flows.bankEcl
    lazy val ledgerBankStocksById: Map[BankId, Banking.BankFinancialStocks]                         =
      banks.zip(ledgerFinancialState.banks).map((bank, balances) => bank.id -> LedgerFinancialState.projectBankFinancialStocks(balances)).toMap
    lazy val aliveBankRows: Vector[(Banking.BankState, Banking.BankFinancialStocks)]                =
      aliveBanks.map: bank =>
        val stocks = ledgerBankStocksById.getOrElse(bank.id, throw IllegalStateException(s"Missing ledger bank stocks for bank ${bank.id.toInt}"))
        bank -> stocks
    lazy val ledgerBankGovBondHoldings: PLN                                                         =
      ledgerFinancialState.banks.foldLeft(PLN.Zero)((acc, bank) => acc + bank.govBondAfs + bank.govBondHtm)
    lazy val ledgerHouseholdEquityWealth: PLN                                                       =
      ledgerFinancialState.households.foldLeft(PLN.Zero)((acc, household) => acc + household.equity)
    lazy val ledgerHouseholdMortgageStock: PLN                                                      =
      LedgerFinancialState.householdMortgageStock(ledgerFinancialState)
    lazy val householdLiquidity: McHouseholdLiquidityDiagnostics.Summary                            =
      McHouseholdLiquidityDiagnostics.fromBalances(ledgerFinancialState.households)
    lazy val bankFirmLoans: PLN                                                                     = bankAgg.totalLoans
    lazy val firmDefaultRecovery: PLN                                                               = world.flows.firmGrossDefault * p.banking.loanRecovery
    lazy val firmCreditDemand: PLN                                                                  = world.flows.firmInvestmentCreditDemand + world.flows.firmTechCreditDemand
    lazy val firmCreditApproved: PLN                                                                = world.flows.firmInvestmentCreditApproved + world.flows.firmTechCreditApproved
    lazy val firmCreditRejected: PLN                                                                = world.flows.firmInvestmentCreditRejected + world.flows.firmTechCreditRejected
    lazy val firmCashFinancedInvestment: PLN                                                        =
      (world.real.grossInvestment - world.flows.firmInvestmentCreditApproved).max(PLN.Zero)
    lazy val consumerLoanStock: PLN                                                                 = bankAgg.consumerLoans
    lazy val mortgageOrigination: PLN                                                               = world.real.housing.lastOrigination
    lazy val mortgageRepayment: PLN                                                                 = world.real.housing.lastRepayment
    lazy val mortgageDefault: PLN                                                                   = world.real.housing.lastDefault
    lazy val mortgageNetStockFlow: PLN                                                              = mortgageOrigination - mortgageRepayment - mortgageDefault
    lazy val nbfiLoanStock: PLN                                                                     = ledgerFinancialState.funds.nbfi.nbfiLoanStock
    lazy val nbfiOrigination: PLN                                                                   = world.financialMarkets.nbfi.lastNbfiOrigination
    lazy val nbfiRepayment: PLN                                                                     = world.financialMarkets.nbfi.lastNbfiRepayment
    lazy val nbfiDefaults: PLN                                                                      = world.financialMarkets.nbfi.lastNbfiDefaultAmount
    lazy val nbfiNetStockFlow: PLN                                                                  = nbfiOrigination - nbfiRepayment - nbfiDefaults
    lazy val eclStage1: PLN                                                                         = banks.map(b => b.eclStaging.stage1).sumPln
    lazy val eclStage2: PLN                                                                         = banks.map(b => b.eclStaging.stage2).sumPln
    lazy val eclStage3: PLN                                                                         = banks.map(b => b.eclStaging.stage3).sumPln
    lazy val eclStageTotal: PLN                                                                     = eclStage1 + eclStage2 + eclStage3
    lazy val totalCreditStock: PLN                                                                  =
      bankFirmLoans + consumerLoanStock + ledgerHouseholdMortgageStock + nbfiLoanStock
    lazy val ledgerFirmBalancesById: Map[FirmId, LedgerFinancialState.FirmBalances]                 =
      firms.zip(ledgerFinancialState.firms).map((firm, balances) => firm.id -> balances).toMap
    lazy val hhAgg: Household.Aggregates                                                            = householdAggregates
    lazy val monetaryAgg: Option[Banking.MonetaryAggregates]                                        = Some(
      Banking.MonetaryAggregates.computeFromBankStocks(
        banks,
        ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks),
        ledgerFinancialState.funds.nbfi.tfiUnit,
        CorporateBondOwnership.issuerOutstanding(ledgerFinancialState),
      ),
    )
    lazy val monthlyGdp: PLN                                                                        = world.cachedMonthlyGdpProxy
    lazy val annualizedGdp: PLN                                                                     = monthlyGdp * 12
    lazy val govSocialFundSubventions: PLN                                                          =
      world.social.zus.govSubvention + world.social.nfz.govSubvention + world.social.earmarked.totalGovSubvention
    lazy val govTotalOutlays: PLN                                                                   = world.gov.domesticBudgetOutlays + govSocialFundSubventions
    lazy val govPrimaryDeficit: PLN                                                                 = world.gov.deficit - world.gov.debtServiceSpend
    lazy val currentAccountClosureResidual: PLN                                                     =
      val reconstructed =
        world.bop.tradeBalance +
          world.bop.primaryIncome +
          world.bop.secondaryIncome -
          world.financialMarkets.equity.lastForeignDividends -
          world.flows.fdiRepatriation
      world.bop.currentAccount - reconstructed
    lazy val sectorOutputs: Vector[PLN]                                                             =
      world.flows.sectorOutputs match
        case outputs if outputs.length == p.sectorDefs.length => outputs
        case outputs                                          =>
          throw IllegalArgumentException(
            s"McTimeseriesSchema requires FlowState.sectorOutputs to have ${p.sectorDefs.length} entries, got ${outputs.length}",
          )
    lazy val sectorAuto: Vector[Share]                                                              = sectorColumns.map { sector =>
      val sectorIdx = sectorIndexByName(sector.expectedSectorName)
      val secFirms  = living.filter(_.sector.toInt == sectorIdx)
      if secFirms.isEmpty then Share.Zero
      else Share.fraction(secFirms.count(f => f.tech.isInstanceOf[TechState.Automated] || f.tech.isInstanceOf[TechState.Hybrid]), secFirms.length)
    }
    lazy val adoptionMicroShare: Share                                                              = adoptionShare(living.filter(f => McFirmSizeClass.fromWorkerCount(Firm.workerCount(f)) == McFirmSizeClass.Micro))
    lazy val adoptionSmallShare: Share                                                              = adoptionShare(living.filter(f => McFirmSizeClass.fromWorkerCount(Firm.workerCount(f)) == McFirmSizeClass.Small))
    lazy val adoptionMediumShare: Share                                                             = adoptionShare(living.filter(f => McFirmSizeClass.fromWorkerCount(Firm.workerCount(f)) == McFirmSizeClass.Medium))
    lazy val adoptionLargeShare: Share                                                              = adoptionShare(living.filter(f => McFirmSizeClass.fromWorkerCount(Firm.workerCount(f)) == McFirmSizeClass.Large))
    lazy val adoptionCashQuartiles: Vector[Share]                                                   = adoptionQuartiles(_.cash)
    lazy val adoptionDebtQuartiles: Vector[Share]                                                   = adoptionQuartiles(_.firmLoan)
    lazy val housingRegionsByMarket: Map[HousingConfig.RegionalMarket, HousingMarket.RegionalState] =
      world.real.housing.regions
        .map(_.iterator.map(state => state.market -> state).toMap)
        .getOrElse(Map.empty)

    private lazy val polandScaleFactor: Multiplier =
      if p.gdpRatio > Scalar.Zero then p.gdpRatio.toMultiplier else Multiplier.One

    def polandScale(value: PLN): PLN = value / polandScaleFactor

    def annualizedGdpRatio(stock: PLN): Scalar =
      if annualizedGdp > PLN.Zero then stock / annualizedGdp else Scalar.Zero

    def monthlyFlowToGdpRatio(flow: PLN): Scalar =
      if monthlyGdp > PLN.Zero then flow / monthlyGdp else Scalar.Zero

    def flowToStockRate(flow: PLN, stock: PLN): Scalar =
      if stock > PLN.Zero then flow / stock else Scalar.Zero

    def flowToFlowRatio(flow: PLN, denominator: PLN): Scalar =
      if denominator != PLN.Zero then flow / denominator else Scalar.Zero

    def grossDefaultFromLoss(loss: PLN, recovery: Share): PLN =
      val lossRate = Share.One - recovery
      if loss > PLN.Zero && lossRate > Share.Zero then loss / lossRate else PLN.Zero

    lazy val meanEmployedWage: PLN =
      var total = PLN.Zero
      var count = 0
      households.foreach: hh =>
        hh.status match
          case HhStatus.Employed(_, _, wage) =>
            total += wage
            count += 1
          case _                             =>
      if count > 0 then total.divideBy(count) else PLN.Zero

    def runtimeSectorIndex(name: String): Int =
      sectorIndexByName.getOrElse(name, throw IllegalStateException(s"Missing runtime sector named '$name' in SimParams.sectorDefs"))

    private def adopted(firm: Firm.State): Boolean =
      firm.tech.isInstanceOf[TechState.Automated] || firm.tech.isInstanceOf[TechState.Hybrid]

    private def adoptionShare(firms: Vector[Firm.State]): Share =
      if firms.isEmpty then Share.Zero
      else Share.fraction(firms.count(adopted), firms.length)

    private def firmBalances(firm: Firm.State): LedgerFinancialState.FirmBalances =
      ledgerFirmBalancesById.getOrElse(firm.id, throw IllegalStateException(s"Missing ledger firm balances for firm ${firm.id.toInt}"))

    private def adoptionQuartiles(metric: LedgerFinancialState.FirmBalances => PLN): Vector[Share] =
      if living.isEmpty then Vector.fill(4)(Share.Zero)
      else
        val sorted        = living.sortBy(firm => (metric(firmBalances(firm)).toLong, firm.id.toInt))
        val counts        = Array.fill(4)(0)
        val adoptedCounts = Array.fill(4)(0)
        sorted.zipWithIndex.foreach: (firm, idx) =>
          val quartile = idx * 4 / sorted.length
          counts(quartile) += 1
          if adopted(firm) then adoptedCounts(quartile) += 1
        Vector.tabulate(4): quartile =>
          if counts(quartile) > 0 then Share.fraction(adoptedCounts(quartile), counts(quartile)) else Share.Zero

    def sectorSigma(idx: Int): Sigma                                        = world.currentSigmas(idx)
    def housingRegionHpi(market: HousingConfig.RegionalMarket): MetricValue =
      housingRegionsByMarket.get(market).map(regionState => MetricValue.fromRaw(regionState.priceIndex.toLong)).getOrElse(MetricValue.Zero)

    inline def unemployPct: Share = world.unemploymentRate(hhAgg.employed)

  // -------------------------------------------------------------------------
  //  Schema groups — composed with ++
  // -------------------------------------------------------------------------

  private def macroGroup: Vector[ColumnDef] = Vector(
    ColumnDef("Month", ctx => ctx.executionMonth.toInt),
    ColumnDef("Inflation", ctx => ctx.world.inflation),
    ColumnDef("Unemployment", ctx => ctx.unemployPct),
    ColumnDef("UnemployedShare", ctx => Share.fraction(ctx.hhAgg.unemployed, ctx.world.laborForcePopulation)),
    ColumnDef("RetrainingShare", ctx => Share.fraction(ctx.hhAgg.retraining, ctx.world.laborForcePopulation)),
    ColumnDef(
      "PermanentShare",
      ctx =>
        if ctx.hhAgg.employed > 0 then
          Share.fraction(ctx.households.count(h => h.contractType == ContractType.Permanent && h.status.isInstanceOf[HhStatus.Employed]), ctx.hhAgg.employed)
        else Share.Zero,
    ),
    ColumnDef(
      "ZlecenieShare",
      ctx =>
        if ctx.hhAgg.employed > 0 then
          Share.fraction(ctx.households.count(h => h.contractType == ContractType.Zlecenie && h.status.isInstanceOf[HhStatus.Employed]), ctx.hhAgg.employed)
        else Share.Zero,
    ),
    ColumnDef(
      "B2BShare",
      ctx =>
        if ctx.hhAgg.employed > 0 then
          Share.fraction(ctx.households.count(h => h.contractType == ContractType.B2B && h.status.isInstanceOf[HhStatus.Employed]), ctx.hhAgg.employed)
        else Share.Zero,
    ),
    ColumnDef("TotalAdoption", ctx => ctx.world.real.automationRatio + ctx.world.real.hybridRatio),
    ColumnDef("ExRate", ctx => ctx.world.forex.exchangeRate),
    ColumnDef("MarketWage", ctx => ctx.world.householdMarket.marketWage),
    ColumnDef("MeanEmployedWage", ctx => ctx.meanEmployedWage),
    ColumnDef.macroPln("GovDebt", ctx => ctx.world.gov.cumulativeDebt),
    ColumnDef("NPL", ctx => ctx.bankAgg.nplRatio),
    ColumnDef("RefRate", ctx => ctx.world.nbp.referenceRate),
    ColumnDef("PriceLevel", ctx => ctx.world.priceLevel),
    ColumnDef.macroPln("MonthlyGdpProxy", ctx => ctx.monthlyGdp),
    ColumnDef.macroPln("AnnualizedGdpProxy", ctx => ctx.annualizedGdp),
    ColumnDef("AutoRatio", ctx => ctx.world.real.automationRatio),
    ColumnDef("HybridRatio", ctx => ctx.world.real.hybridRatio),
  )

  private def firmAutomationGroup: Vector[ColumnDef] = Vector(
    ColumnDef.macroPln("Automation_TechCapex", ctx => ctx.world.flows.automationTechCapex),
    ColumnDef.macroPln("Automation_TechImports", ctx => ctx.world.flows.automationTechImports),
    ColumnDef.macroPln("Automation_TechLoans", ctx => ctx.world.flows.automationTechLoans),
    ColumnDef("Automation_UpgradeFailures", ctx => ctx.world.flows.automationUpgradeFailures),
    ColumnDef("Automation_AiDebtTrap", ctx => ctx.world.flows.automationAiDebtTrap),
    ColumnDef("Automation_NewFullAi", ctx => ctx.world.flows.automationNewFullAi),
    ColumnDef("Automation_NewHybrid", ctx => ctx.world.flows.automationNewHybrid),
  )

  private def firmAdoptionHeterogeneityGroup: Vector[ColumnDef] = Vector(
    ColumnDef("Adoption_MicroShare", ctx => ctx.adoptionMicroShare),
    ColumnDef("Adoption_SmallShare", ctx => ctx.adoptionSmallShare),
    ColumnDef("Adoption_MediumShare", ctx => ctx.adoptionMediumShare),
    ColumnDef("Adoption_LargeShare", ctx => ctx.adoptionLargeShare),
    ColumnDef("Adoption_CashQ1", ctx => ctx.adoptionCashQuartiles(0)),
    ColumnDef("Adoption_CashQ2", ctx => ctx.adoptionCashQuartiles(1)),
    ColumnDef("Adoption_CashQ3", ctx => ctx.adoptionCashQuartiles(2)),
    ColumnDef("Adoption_CashQ4", ctx => ctx.adoptionCashQuartiles(3)),
    ColumnDef("Adoption_DebtQ1", ctx => ctx.adoptionDebtQuartiles(0)),
    ColumnDef("Adoption_DebtQ2", ctx => ctx.adoptionDebtQuartiles(1)),
    ColumnDef("Adoption_DebtQ3", ctx => ctx.adoptionDebtQuartiles(2)),
    ColumnDef("Adoption_DebtQ4", ctx => ctx.adoptionDebtQuartiles(3)),
  )

  private def firmSectoralGroup: Vector[ColumnDef] =
    val autoColumns   = sectorColumns.zipWithIndex.map: (sector, idx) =>
      ColumnDef(sector.autoColName, ctx => ctx.sectorAuto(idx))
    val outputColumns = sectorColumns.map: sector =>
      ColumnDef.macroPln(sector.outputColName, ctx => ctx.sectorOutputs(ctx.runtimeSectorIndex(sector.expectedSectorName)))
    val sigmaColumns  = sectorColumns.zipWithIndex.map: (sector, idx) =>
      ColumnDef(sector.sigmaColName, ctx => ctx.sectorSigma(idx))

    (autoColumns ++ outputColumns ++ sigmaColumns ++ Vector(
      ColumnDef("MeanDegree", ctx => MetricValue.fraction(ctx.firms.map(_.neighbors.length).sum, ctx.firms.length)),
      ColumnDef.macroPln("IoFlows", ctx => ctx.world.flows.ioFlows),
      ColumnDef(
        "IoGdpRatio",
        ctx => if ctx.monthlyGdp > PLN.Zero then ctx.world.flows.ioFlows / ctx.monthlyGdp else Scalar.Zero,
      ),
    )).toVector

  private def firmsGroup: Vector[ColumnDef] =
    firmAutomationGroup ++ firmAdoptionHeterogeneityGroup ++ firmSectoralGroup

  private def externalGroup: Vector[ColumnDef] = Vector(
    ColumnDef.macroPln("NFA", ctx => ctx.world.bop.nfa),
    ColumnDef("NfaToGdp", ctx => ctx.annualizedGdpRatio(ctx.world.bop.nfa)),
    ColumnDef.macroPln("CurrentAccount", ctx => ctx.world.bop.currentAccount),
    ColumnDef("CurrentAccountToGdp", ctx => ctx.monthlyFlowToGdpRatio(ctx.world.bop.currentAccount)),
    ColumnDef.macroPln("CurrentAccountPrimaryIncome", ctx => ctx.world.bop.primaryIncome),
    ColumnDef("CurrentAccountPrimaryIncomeToGdp", ctx => ctx.monthlyFlowToGdpRatio(ctx.world.bop.primaryIncome)),
    ColumnDef.macroPln("CurrentAccountSecondaryIncome", ctx => ctx.world.bop.secondaryIncome),
    ColumnDef("CurrentAccountSecondaryIncomeToGdp", ctx => ctx.monthlyFlowToGdpRatio(ctx.world.bop.secondaryIncome)),
    ColumnDef.macroPln("CurrentAccountClosureResidual", ctx => ctx.currentAccountClosureResidual),
    ColumnDef.macroPln("CapitalAccount", ctx => ctx.world.bop.capitalAccount),
    ColumnDef("CapitalAccountToGdp", ctx => ctx.monthlyFlowToGdpRatio(ctx.world.bop.capitalAccount)),
    ColumnDef.macroPln("TradeBalance_OE", ctx => ctx.world.bop.tradeBalance),
    ColumnDef("TradeBalanceToGdp", ctx => ctx.monthlyFlowToGdpRatio(ctx.world.bop.tradeBalance)),
    ColumnDef.macroPln("Exports_OE", ctx => ctx.world.bop.exports),
    ColumnDef("ExportsToGdp", ctx => ctx.monthlyFlowToGdpRatio(ctx.world.bop.exports)),
    ColumnDef.macroPln("TotalImports_OE", ctx => ctx.world.bop.totalImports),
    ColumnDef("ImportsToGdp", ctx => ctx.monthlyFlowToGdpRatio(ctx.world.bop.totalImports)),
    ColumnDef.macroPln("ImportedInterm", ctx => ctx.world.bop.importedIntermediates),
    ColumnDef("ImportedIntermToImports", ctx => ctx.flowToFlowRatio(ctx.world.bop.importedIntermediates, ctx.world.bop.totalImports)),
    ColumnDef.macroPln("FDI", ctx => ctx.world.bop.fdi),
    // GVC / Deep External Sector
    ColumnDef("GvcDisruptionIndex", ctx => ctx.world.external.gvc.disruptionIndex),
    ColumnDef("ForeignPriceIndex", ctx => ctx.world.external.gvc.foreignPriceIndex),
    ColumnDef("GvcTradeConcentration", ctx => ctx.world.external.gvc.tradeConcentration),
    ColumnDef("GvcExportDemandShock", ctx => ctx.world.external.gvc.exportDemandShockMag),
    ColumnDef("GvcImportCostIndex", ctx => ctx.world.external.gvc.importCostIndex),
    ColumnDef("CommodityPriceIndex", ctx => ctx.world.external.gvc.commodityPriceIndex),
    // Immigration
    ColumnDef("ImmigrantStock", ctx => ctx.world.external.immigration.immigrantStock),
    ColumnDef("MonthlyImmigInflow", ctx => ctx.world.external.immigration.monthlyInflow),
    ColumnDef.macroPln("RemittanceOutflow", ctx => ctx.world.external.immigration.remittanceOutflow),
    ColumnDef(
      "ImmigrantUnempRate",
      ctx =>
        if ctx.world.external.immigration.immigrantStock > 0 then
          val immigrants = ctx.households.filter(_.isImmigrant)
          if immigrants.nonEmpty then Share.fraction(immigrants.count(h => !h.status.isInstanceOf[HhStatus.Employed]), immigrants.length)
          else Share.Zero
        else Share.Zero,
    ),
    // FX
    ColumnDef.macroPln("FxReserves", ctx => ctx.ledgerFinancialState.nbp.foreignAssets),
    ColumnDef.macroPln("FxInterventionAmt", ctx => ctx.world.nbp.lastFxTraded),
    ColumnDef("FxInterventionActive", _ => true),
    // Diaspora Remittances
    ColumnDef.macroPln("DiasporaRemittanceInflow", ctx => ctx.world.flows.diasporaRemittanceInflow),
    ColumnDef(
      "NetRemittances",
      ctx => ctx.polandScale(ctx.world.flows.diasporaRemittanceInflow - ctx.world.external.immigration.remittanceOutflow),
    ),
    // Tourism
    ColumnDef.macroPln("TourismExport", ctx => ctx.world.flows.tourismExport),
    ColumnDef.macroPln("TourismImport", ctx => ctx.world.flows.tourismImport),
    ColumnDef.macroPln("NetTourismBalance", ctx => ctx.world.flows.tourismExport - ctx.world.flows.tourismImport),
    ColumnDef("TourismSeasonalFactor", ctx => ctx.world.external.tourismSeasonalFactor),
  )

  private def fiscalGroup: Vector[ColumnDef] = Vector(
    ColumnDef.macroPln("UnempBenefitSpend", ctx => ctx.world.gov.unempBenefitSpend),
    ColumnDef(
      "OutputGap",
      ctx => (ctx.unemployPct - ctx.p.monetary.nairu) / ctx.p.monetary.nairu,
    ),
    ColumnDef(
      "EffectivePitRate",
      ctx => {
        val agg   = ctx.hhAgg
        val gross = agg.totalIncome + agg.totalPit
        if gross > PLN.Zero then agg.totalPit / gross else Scalar.Zero
      },
    ),
    ColumnDef.macroPln("GovTaxRevenue", ctx => ctx.world.gov.taxRevenue),
    ColumnDef.macroPln("GovDividendRevenue", ctx => ctx.world.gov.govDividendRevenue),
    ColumnDef.macroPln("GovTotalRevenue", ctx => ctx.world.gov.totalRevenue),
    ColumnDef("GovRevenueToGdp", ctx => if ctx.monthlyGdp > PLN.Zero then ctx.world.gov.totalRevenue / ctx.monthlyGdp else Scalar.Zero),
    ColumnDef.macroPln("SocialTransferSpend", ctx => ctx.world.gov.socialTransferSpend),
    ColumnDef.macroPln("GovCurrentSpend", ctx => ctx.world.gov.govCurrentSpend),
    ColumnDef.macroPln("GovCapitalSpendDomestic", ctx => ctx.world.gov.govCapitalSpend),
    ColumnDef.macroPln("GovDomesticBudgetDemand", ctx => ctx.world.gov.domesticBudgetDemand),
    ColumnDef.macroPln("GovDomesticBudgetOutlays", ctx => ctx.world.gov.domesticBudgetOutlays),
    ColumnDef.macroPln("GovSocialFundSubventions", ctx => ctx.govSocialFundSubventions),
    ColumnDef.macroPln("GovTotalOutlays", ctx => ctx.govTotalOutlays),
    ColumnDef.macroPln("GovDeficit", ctx => ctx.world.gov.deficit),
    ColumnDef.macroPln("GovPrimaryDeficit", ctx => ctx.govPrimaryDeficit),
    ColumnDef("GovOutlaysToGdp", ctx => if ctx.monthlyGdp > PLN.Zero then ctx.world.gov.domesticBudgetOutlays / ctx.monthlyGdp else Scalar.Zero),
    ColumnDef("GovTotalOutlaysToGdp", ctx => if ctx.monthlyGdp > PLN.Zero then ctx.govTotalOutlays / ctx.monthlyGdp else Scalar.Zero),
    ColumnDef("GovPrimaryDeficitToGdp", ctx => if ctx.monthlyGdp > PLN.Zero then ctx.govPrimaryDeficit / ctx.monthlyGdp else Scalar.Zero),
    ColumnDef.macroPln("EuProjectCapitalTotal", ctx => ctx.world.gov.euProjectCapital),
    ColumnDef.macroPln("PublicCapitalStock", ctx => ctx.world.gov.publicCapitalStock),
    ColumnDef.macroPln("EuCofinancingDomestic", ctx => ctx.world.gov.euCofinancing),
    ColumnDef.macroPln("EuFundsMonthly", ctx => ctx.world.bop.euFundsMonthly),
    ColumnDef.macroPln("EuCumulativeAbsorption", ctx => ctx.world.bop.euCumulativeAbsorption),
    ColumnDef("MinWageLevel", ctx => ctx.world.gov.minWageLevel),
    ColumnDef.macroPln("ExciseRevenue", ctx => ctx.world.gov.exciseRevenue),
    ColumnDef.macroPln("CustomsDutyRevenue", ctx => ctx.world.gov.customsDutyRevenue),
    // Fiscal rules: domestic PDP-style debt metric; ESA/EDP debt is reported
    // separately in the quasi-fiscal group.
    ColumnDef(
      "DebtToGdp",
      ctx => if ctx.monthlyGdp > PLN.Zero then ctx.world.gov.cumulativeDebt / (ctx.monthlyGdp * 12) else Scalar.Zero,
    ),
    ColumnDef(
      "DeficitToGdp",
      ctx => if ctx.monthlyGdp > PLN.Zero then ctx.world.gov.deficit / ctx.monthlyGdp else Scalar.Zero,
    ),
    ColumnDef("FiscalRuleBinding", ctx => ctx.world.pipeline.fiscalRuleSeverity),
    ColumnDef("GovSpendingCutRatio", ctx => ctx.world.pipeline.govSpendingCutRatio),
  )

  private def bankingMonetaryGroup: Vector[ColumnDef] = Vector(
    // Bond market
    ColumnDef("BondYield", ctx => ctx.world.gov.bondYield),
    ColumnDef("WeightedCoupon", ctx => ctx.world.gov.weightedCoupon),
    ColumnDef.macroPln("BondsOutstanding", ctx => ctx.ledgerFinancialState.government.govBondOutstanding),
    ColumnDef.macroPln("BankBondHoldings", ctx => ctx.ledgerBankGovBondHoldings),
    ColumnDef.macroPln("ForeignBondHoldings", ctx => ctx.ledgerFinancialState.foreign.govBondHoldings),
    ColumnDef.macroPln("NbpBondHoldings", ctx => ctx.ledgerFinancialState.nbp.govBondHoldings),
    ColumnDef("QeActive", ctx => ctx.world.nbp.qeActive),
    ColumnDef.macroPln("DebtService", ctx => ctx.world.gov.debtServiceSpend),
    ColumnDef.macroPln("NbpRemittance", ctx => ctx.ledgerFinancialState.nbp.govBondHoldings * ctx.world.gov.bondYield.monthly),
    // Monetary plumbing
    ColumnDef.macroPln("ReserveInterest", ctx => ctx.world.plumbing.reserveInterestTotal),
    ColumnDef.macroPln("StandingFacilityNet", ctx => ctx.world.plumbing.standingFacilityNet),
    ColumnDef.macroPln("DepositFacilityUsage", ctx => ctx.world.plumbing.depositFacilityUsage),
    ColumnDef.macroPln("InterbankInterestNet", ctx => ctx.world.plumbing.interbankInterestNet),
    // Monetary aggregates
    ColumnDef.macroPln("M0", ctx => ctx.monetaryAgg.map(a => a.m0).getOrElse(PLN.Zero)),
    ColumnDef.macroPln("M1", ctx => ctx.monetaryAgg.map(a => a.m1).getOrElse(ctx.bankAgg.deposits)),
    ColumnDef.macroPln("M2", ctx => ctx.monetaryAgg.map(a => a.m2).getOrElse(ctx.bankAgg.deposits)),
    ColumnDef.macroPln("M3", ctx => ctx.monetaryAgg.map(a => a.m3).getOrElse(ctx.bankAgg.deposits)),
    ColumnDef("CreditMultiplier", ctx => ctx.monetaryAgg.map(a => a.creditMultiplier).getOrElse(Multiplier.Zero)),
    ColumnDef.macroPln("FofResidual", ctx => ctx.world.plumbing.fofResidual),
  )

  private def bankingSectorGroup: Vector[ColumnDef] = Vector(
    // Interbank
    ColumnDef("InterbankRate", ctx => ctx.world.bankingSector.interbankRate),
    ColumnDef(
      "MinBankCAR",
      ctx =>
        if ctx.aliveBankRows.isEmpty then Multiplier.Zero
        else ctx.aliveBankRows.map((bank, stocks) => Banking.car(bank, stocks, ctx.bankCorpBondHoldings(bank.id))).min,
    ),
    ColumnDef(
      "MaxBankNPL",
      ctx => if ctx.aliveBankRows.isEmpty then Share.Zero else ctx.aliveBankRows.map((bank, stocks) => Banking.nplRatio(bank, stocks)).max,
    ),
    ColumnDef("BankFailures", ctx => ctx.banks.count(_.failed)),
    ColumnDef("BankResolution_ActiveBanks", ctx => ctx.banks.count(bank => !bank.failed)),
    ColumnDef("BankResolution_FailedBanks", ctx => ctx.banks.count(_.failed)),
    ColumnDef("BankResolution_NewFailures", ctx => ctx.bankResolution.newFailures),
    ColumnDef("BankResolution_BailInEvents", ctx => ctx.bankResolution.bailInEvents),
    ColumnDef("BankResolution_ResolvedBanks", ctx => ctx.bankResolution.resolvedBanks),
    ColumnDef("BankResolution_AllFailedFallback", ctx => ctx.bankResolution.allFailedFallback),
    ColumnDef.macroPln("BankResolution_BridgeRecapitalization", ctx => ctx.bankResolution.bridgeRecapitalization),
    ColumnDef("BankResolution_InvalidActiveBankInvariant", ctx => ctx.bankResolution.invalidActiveBankInvariant),
    ColumnDef("BankFailure_NewNegativeCapital", ctx => ctx.bankFailure.newNegativeCapital),
    ColumnDef("BankFailure_NewCarBreach", ctx => ctx.bankFailure.newCarBreach),
    ColumnDef("BankFailure_NewLiquidityBreach", ctx => ctx.bankFailure.newLiquidityBreach),
    ColumnDef("BankFailure_AllFailedFallback", ctx => ctx.bankFailure.allFailedFallback),
    ColumnDef("BankFailure_InvariantViolation", ctx => ctx.bankFailure.invariantViolation),
    ColumnDef("BankFailure_FirstNewReasonCode", ctx => ctx.bankFailure.firstNewReasonCode),
    ColumnDef("BankFailure_FirstNewBankId", ctx => ctx.bankFailure.firstNewBankId),
    ColumnDef("BankReconciliation_TargetBankId", ctx => ctx.bankReconciliation.targetBankId),
    ColumnDef.macroPln("BankReconciliation_CapitalResidual", ctx => ctx.bankReconciliation.capitalResidual),
    ColumnDef.macroPln("BankReconciliation_TargetCapitalBefore", ctx => ctx.bankReconciliation.targetCapitalBefore),
    ColumnDef.macroPln("BankReconciliation_TargetCapitalAfter", ctx => ctx.bankReconciliation.targetCapitalAfter),
    ColumnDef("BankReconciliation_TargetCarBefore", ctx => ctx.bankReconciliation.targetCarBefore),
    ColumnDef("BankReconciliation_TargetCarAfter", ctx => ctx.bankReconciliation.targetCarAfter),
    ColumnDef("BankReconciliation_ResidualToTargetCapital", ctx => ctx.bankReconciliation.residualToTargetCapital),
    ColumnDef("BankReconciliation_MaterialResidual", ctx => ctx.bankReconciliation.materialResidual),
    ColumnDef("BankReconciliation_CrossedFailureThreshold", ctx => ctx.bankReconciliation.crossedFailureThreshold),
    ColumnDef("BankReconciliation_PostResidualReasonCode", ctx => ctx.bankReconciliation.postResidualReasonCode),
    // LCR/NSFR
    ColumnDef(
      "MinBankLCR",
      ctx => { given SimParams = ctx.p; if ctx.aliveBankRows.isEmpty then Multiplier.Zero else ctx.aliveBankRows.map((_, stocks) => Banking.lcr(stocks)).min },
    ),
    ColumnDef(
      "MinBankNSFR",
      ctx =>
        if ctx.aliveBankRows.isEmpty then Multiplier.Zero
        else ctx.aliveBankRows.map((bank, stocks) => Banking.nsfr(bank, stocks, ctx.bankCorpBondHoldings(bank.id))).min,
    ),
    ColumnDef(
      "AvgTermDepositFrac",
      ctx =>
        if ctx.aliveBankRows.isEmpty then Scalar.Zero
        else
          val total = ctx.aliveBankRows.foldLeft(Scalar.Zero): (acc, row) =>
            val (_, stocks) = row
            acc + (if stocks.totalDeposits > PLN.Zero then stocks.termDeposit / stocks.totalDeposits else Scalar.Zero)
          total / ctx.aliveBankRows.length,
    ),
    // Term structure
    ColumnDef("WIBOR_1M", ctx => ctx.world.bankingSector.interbankCurve.map(c => c.wibor1m).getOrElse(Rate.Zero)),
    ColumnDef("WIBOR_3M", ctx => ctx.world.bankingSector.interbankCurve.map(c => c.wibor3m).getOrElse(Rate.Zero)),
    ColumnDef("WIBOR_6M", ctx => ctx.world.bankingSector.interbankCurve.map(c => c.wibor6m).getOrElse(Rate.Zero)),
  )

  private def bankingGroup: Vector[ColumnDef] =
    bankingMonetaryGroup ++ bankingSectorGroup

  private def creditGroup: Vector[ColumnDef] = Vector(
    // Validation-ready credit aggregates: stocks use annualized GDP as denominator.
    ColumnDef.macroPln("BankFirmLoans", ctx => ctx.bankFirmLoans),
    // Firm Credit
    ColumnDef.macroPln("FirmCredit_NewLoans", ctx => ctx.world.flows.firmNewLoans),
    ColumnDef.macroPln("FirmCredit_PrincipalRepaid", ctx => ctx.world.flows.firmPrincipalRepaid),
    ColumnDef.macroPln("FirmCredit_GrossDefault", ctx => ctx.world.flows.firmGrossDefault),
    ColumnDef.macroPln("FirmCredit_NplRecovery", ctx => ctx.firmDefaultRecovery),
    ColumnDef.macroPln("FirmCredit_NplLoss", ctx => ctx.world.flows.firmNplLoss),
    ColumnDef.macroPln(
      "FirmCredit_NetStockFlow",
      ctx => ctx.world.flows.firmNewLoans - ctx.world.flows.firmPrincipalRepaid - ctx.firmDefaultRecovery,
    ),
    ColumnDef.macroPln("FirmCredit_CreditDemand", ctx => ctx.firmCreditDemand),
    ColumnDef.macroPln("FirmCredit_CreditApproved", ctx => ctx.firmCreditApproved),
    ColumnDef.macroPln("FirmCredit_BankRejected", ctx => ctx.firmCreditRejected),
    ColumnDef.macroPln("FirmCredit_RejectedFailedBank", ctx => ctx.world.flows.firmCreditRejectedByReason.failedBank),
    ColumnDef.macroPln("FirmCredit_RejectedCarGate", ctx => ctx.world.flows.firmCreditRejectedByReason.carGate),
    ColumnDef.macroPln("FirmCredit_RejectedLcrGate", ctx => ctx.world.flows.firmCreditRejectedByReason.lcrGate),
    ColumnDef.macroPln("FirmCredit_RejectedNsfrGate", ctx => ctx.world.flows.firmCreditRejectedByReason.nsfrGate),
    ColumnDef.macroPln("FirmCredit_RejectedStochastic", ctx => ctx.world.flows.firmCreditRejectedByReason.stochastic),
    ColumnDef.macroPln("FirmCredit_RejectedUnclassified", ctx => ctx.world.flows.firmCreditRejectedByReason.unclassified),
    ColumnDef("FirmCredit_ApprovalRate", ctx => ctx.flowToFlowRatio(ctx.firmCreditApproved, ctx.firmCreditDemand)),
    ColumnDef.macroPln("FirmCredit_InvestmentDemand", ctx => ctx.world.flows.firmInvestmentCreditDemand),
    ColumnDef.macroPln("FirmCredit_InvestmentApproved", ctx => ctx.world.flows.firmInvestmentCreditApproved),
    ColumnDef.macroPln("FirmCredit_InvestmentBankRejected", ctx => ctx.world.flows.firmInvestmentCreditRejected),
    ColumnDef.macroPln("FirmCredit_CashFinancedInvestment", ctx => ctx.firmCashFinancedInvestment),
    ColumnDef(
      "FirmCredit_CashFinancedInvestmentToGrossInvestment",
      ctx => ctx.flowToFlowRatio(ctx.firmCashFinancedInvestment, ctx.world.real.grossInvestment),
    ),
    ColumnDef.macroPln("FirmCredit_TechDemand", ctx => ctx.world.flows.firmTechCreditDemand),
    ColumnDef.macroPln("FirmCredit_TechApproved", ctx => ctx.world.flows.firmTechCreditApproved),
    ColumnDef.macroPln("FirmCredit_TechBankRejected", ctx => ctx.world.flows.firmTechCreditRejected),
    ColumnDef.macroPln("FirmCredit_TechSelectedDemand", ctx => ctx.world.flows.firmTechSelectedCreditDemand),
    ColumnDef.macroPln("FirmCredit_TechSelectedApproved", ctx => ctx.world.flows.firmTechSelectedCreditApproved),
    ColumnDef.macroPln("FirmCredit_TechSelectedBankRejected", ctx => ctx.world.flows.firmTechSelectedCreditRejected),
    ColumnDef.macroPln("FirmCredit_TechCandidateDemand", ctx => ctx.world.flows.firmTechCandidateCreditDemand),
    ColumnDef.macroPln("FirmCredit_TechCandidateApproved", ctx => ctx.world.flows.firmTechCandidateCreditApproved),
    ColumnDef.macroPln("FirmCredit_TechCandidateBankRejected", ctx => ctx.world.flows.firmTechCandidateCreditRejected),
    ColumnDef(
      "FirmCredit_TechCandidateApprovalRate",
      ctx => ctx.flowToFlowRatio(ctx.world.flows.firmTechCandidateCreditApproved, ctx.world.flows.firmTechCandidateCreditDemand),
    ),
    // Consumer Credit
    ColumnDef.macroPln("ConsumerLoans", ctx => ctx.consumerLoanStock),
    ColumnDef(
      "ConsumerNplRatio",
      ctx =>
        if ctx.consumerLoanStock > PLN.Zero then ctx.bankAgg.consumerNpl / ctx.consumerLoanStock
        else Scalar.Zero,
    ),
    ColumnDef.macroPln("ConsumerOrigination", ctx => ctx.hhAgg.totalConsumerOrigination),
    ColumnDef.macroPln("ConsumerApprovedOrigination", ctx => ctx.hhAgg.totalConsumerApprovedOrigination),
    ColumnDef.macroPln("ConsumerCreditDemand", ctx => ctx.hhAgg.totalConsumerCreditDemand),
    ColumnDef.macroPln("ConsumerRejectedOrigination", ctx => ctx.hhAgg.totalConsumerRejectedOrigination),
    ColumnDef.macroPln("ConsumerBankRejectedOrigination", ctx => ctx.hhAgg.totalConsumerBankRejectedOrigination),
    ColumnDef.macroPln("ConsumerDebtService", ctx => ctx.hhAgg.totalConsumerDebtService),
    ColumnDef.macroPln("ConsumerPrincipal", ctx => ctx.hhAgg.totalConsumerPrincipal),
    ColumnDef.macroPln("ConsumerDefault", ctx => ctx.hhAgg.totalConsumerDefault),
    ColumnDef.macroPln("ConsumerLoanDefault", ctx => ctx.hhAgg.totalConsumerLoanDefault),
    ColumnDef.macroPln("LiquidityBridgeChargeOff", ctx => ctx.hhAgg.totalLiquidityBridgeChargeOff),
    ColumnDef
      .macroPln("ConsumerCredit_NetStockFlow", ctx => ctx.hhAgg.totalConsumerOrigination - ctx.hhAgg.totalConsumerPrincipal - ctx.hhAgg.totalConsumerDefault),
    ColumnDef.macroPln(
      "ConsumerCredit_UnderwrittenNetFlow",
      ctx => ctx.hhAgg.totalConsumerApprovedOrigination - ctx.hhAgg.totalConsumerPrincipal - ctx.hhAgg.totalConsumerLoanDefault,
    ),
    ColumnDef.macroPln("ConsumerCredit_BridgeNetFlow", ctx => ctx.hhAgg.totalLiquidityShortfallFinancing - ctx.hhAgg.totalLiquidityBridgeChargeOff),
    ColumnDef.macroPln("ConsumerCredit_NplStock", ctx => ctx.bankAgg.consumerNpl),
    ColumnDef("ConsumerCredit_NplRatioGross", ctx => ctx.flowToStockRate(ctx.bankAgg.consumerNpl, ctx.consumerLoanStock + ctx.bankAgg.consumerNpl)),
    ColumnDef("ConsumerCredit_ApprovedToDemand", ctx => ctx.flowToFlowRatio(ctx.hhAgg.totalConsumerApprovedOrigination, ctx.hhAgg.totalConsumerCreditDemand)),
    ColumnDef("ConsumerCredit_RejectedToDemand", ctx => ctx.flowToFlowRatio(ctx.hhAgg.totalConsumerRejectedOrigination, ctx.hhAgg.totalConsumerCreditDemand)),
    ColumnDef(
      "ConsumerCredit_BankRejectedToDemand",
      ctx => ctx.flowToFlowRatio(ctx.hhAgg.totalConsumerBankRejectedOrigination, ctx.hhAgg.totalConsumerCreditDemand),
    ),
    ColumnDef(
      "ConsumerCredit_ShortfallToApprovedOrigination",
      ctx => ctx.flowToFlowRatio(ctx.hhAgg.totalLiquidityShortfallFinancing, ctx.hhAgg.totalConsumerApprovedOrigination),
    ),
    ColumnDef.macroPln("TotalCreditStock", ctx => ctx.totalCreditStock),
    ColumnDef("BankFirmLoansToGdp", ctx => ctx.annualizedGdpRatio(ctx.bankFirmLoans)),
    ColumnDef("ConsumerLoansToGdp", ctx => ctx.annualizedGdpRatio(ctx.consumerLoanStock)),
    ColumnDef("NbfiLoansToGdp", ctx => ctx.annualizedGdpRatio(ctx.nbfiLoanStock)),
    ColumnDef("TotalCreditToGdp", ctx => ctx.annualizedGdpRatio(ctx.totalCreditStock)),
  )

  private def capitalMarketsGroup: Vector[ColumnDef] = Vector(
    // GPW Equity Market
    ColumnDef("GpwIndex", ctx => ctx.world.financialMarkets.equity.index),
    ColumnDef.macroPln("GpwMarketCap", ctx => ctx.world.financialMarkets.equity.marketCap),
    ColumnDef(
      "GpwPE",
      ctx =>
        val ey = ctx.world.financialMarkets.equity.earningsYield
        if ey > Rate.Zero then ey.toScalar.reciprocal else Scalar.Zero,
    ),
    ColumnDef("GpwDivYield", ctx => ctx.world.financialMarkets.equity.dividendYield),
    ColumnDef.macroPln("EquityIssuanceTotal", ctx => ctx.world.financialMarkets.equity.lastIssuance),
    ColumnDef(
      "EquityFinancedFrac",
      ctx =>
        val balances = ctx.living.flatMap(f => ctx.ledgerFirmBalancesById.get(f.id))
        val equity   = balances.foldLeft(PLN.Zero)((acc, b) => acc + b.equity)
        val funding  = balances.foldLeft(PLN.Zero)((acc, b) => acc + b.firmLoan + b.equity)
        if funding > PLN.Zero then equity / funding else Scalar.Zero,
    ),
    ColumnDef.macroPln("HhEquityWealth", ctx => ctx.ledgerHouseholdEquityWealth),
    ColumnDef.macroPln("EquityWealthEffect", ctx => ctx.world.financialMarkets.equity.lastWealthEffect),
    ColumnDef.macroPln("DomesticDividends", ctx => ctx.world.financialMarkets.equity.lastDomesticDividends),
    ColumnDef.macroPln("ForeignDividendOutflow", ctx => ctx.world.financialMarkets.equity.lastForeignDividends),
    ColumnDef.macroPln("GovernmentDividends", ctx => ctx.world.gov.govDividendRevenue),
    // Corporate Bonds / Catalyst
    ColumnDef.macroPln("CorpBondOutstanding", ctx => CorporateBondOwnership.issuerOutstanding(ctx.ledgerFinancialState)),
    ColumnDef("CorpBondYield", ctx => ctx.world.financialMarkets.corporateBonds.corpBondYield),
    ColumnDef.macroPln("CorpBondIssuance", ctx => ctx.world.financialMarkets.corporateBonds.lastIssuance),
    ColumnDef("CorpBondSpread", ctx => ctx.world.financialMarkets.corporateBonds.creditSpread),
    ColumnDef.macroPln("BankCorpBondHoldings", ctx => ctx.ledgerFinancialState.banks.foldLeft(PLN.Zero)((acc, bank) => acc + bank.corpBond)),
    ColumnDef.macroPln("PpkCorpBondHoldings", ctx => ctx.ledgerFinancialState.funds.ppkCorpBondHoldings),
    ColumnDef("CorpBondAbsorptionRate", ctx => ctx.world.financialMarkets.corporateBonds.lastAbsorptionRate),
    // Insurance Sector
    ColumnDef.macroPln("InsLifeReserves", ctx => ctx.ledgerFinancialState.insurance.lifeReserve),
    ColumnDef.macroPln("InsNonLifeReserves", ctx => ctx.ledgerFinancialState.insurance.nonLifeReserve),
    ColumnDef.macroPln("InsGovBondHoldings", ctx => ctx.ledgerFinancialState.insurance.govBondHoldings),
    ColumnDef.macroPln("InsLifePremium", ctx => ctx.world.financialMarkets.insurance.lastLifePremium),
    ColumnDef.macroPln("InsNonLifePremium", ctx => ctx.world.financialMarkets.insurance.lastNonLifePremium),
    ColumnDef.macroPln("InsLifeClaims", ctx => ctx.world.financialMarkets.insurance.lastLifeClaims),
    ColumnDef.macroPln("InsNonLifeClaims", ctx => ctx.world.financialMarkets.insurance.lastNonLifeClaims),
    // Shadow Banking / NBFI
    ColumnDef.macroPln("NbfiTfiAum", ctx => ctx.ledgerFinancialState.funds.nbfi.tfiUnit),
    ColumnDef.macroPln("NbfiTfiGovBondHoldings", ctx => ctx.ledgerFinancialState.funds.nbfi.govBondHoldings),
    ColumnDef.macroPln("NbfiLoanStock", ctx => ctx.nbfiLoanStock),
    ColumnDef.macroPln("NbfiOrigination", ctx => ctx.nbfiOrigination),
    ColumnDef.macroPln("NbfiRepayment", ctx => ctx.nbfiRepayment),
    ColumnDef.macroPln("NbfiDefaults", ctx => ctx.nbfiDefaults),
    ColumnDef.macroPln("NbfiNetStockFlow", ctx => ctx.nbfiNetStockFlow),
    ColumnDef("NbfiOriginationToStock", ctx => ctx.flowToStockRate(ctx.nbfiOrigination, ctx.nbfiLoanStock)),
    ColumnDef("NbfiRepaymentToStock", ctx => ctx.flowToStockRate(ctx.nbfiRepayment, ctx.nbfiLoanStock)),
    ColumnDef("NbfiDefaultsToStock", ctx => ctx.flowToStockRate(ctx.nbfiDefaults, ctx.nbfiLoanStock)),
    ColumnDef("NbfiBankTightness", ctx => ctx.world.financialMarkets.nbfi.lastBankTightness),
    // Quasi-fiscal (BGK/PFR)
    ColumnDef.macroPln("QfBondsOutstanding", ctx => ctx.ledgerFinancialState.funds.quasiFiscal.bondsOutstanding),
    ColumnDef.macroPln("QfNbpHoldings", ctx => ctx.ledgerFinancialState.funds.quasiFiscal.nbpHoldings),
    ColumnDef.macroPln("QfLoanPortfolio", ctx => ctx.ledgerFinancialState.funds.quasiFiscal.loanPortfolio),
    ColumnDef.macroPln("QfIssuance", ctx => ctx.world.financialMarkets.quasiFiscal.monthlyIssuance),
    ColumnDef(
      "Esa2010DebtToGdp",
      ctx =>
        val annualGdp = ctx.monthlyGdp * 12
        if annualGdp > PLN.Zero then
          QuasiFiscal.esa2010Debt(ctx.world.gov.cumulativeDebt, ctx.ledgerFinancialState.funds.quasiFiscal.bondsOutstanding) / annualGdp
        else Scalar.Zero,
    ),
    ColumnDef.macroPln("NbfiDepositDrain", ctx => ctx.world.financialMarkets.nbfi.lastDepositDrain),
    ColumnDef(
      "NbfiDepositDrainToAum",
      ctx => ctx.flowToStockRate(ctx.world.financialMarkets.nbfi.lastDepositDrain, ctx.ledgerFinancialState.funds.nbfi.tfiUnit),
    ),
    // AFS/HTM bond portfolio split
    ColumnDef.macroPln("BankAfsBonds", ctx => ctx.bankAgg.afsBonds),
    ColumnDef.macroPln("BankHtmBonds", ctx => ctx.bankAgg.htmBonds),
  )

  private def diagnosticsGroup: Vector[ColumnDef] = Vector(
    // IFRS 9 ECL staging (aggregate across banks)
    ColumnDef.macroPln("EclStage1", ctx => ctx.eclStage1),
    ColumnDef.macroPln("EclStage2", ctx => ctx.eclStage2),
    ColumnDef.macroPln("EclStage3", ctx => ctx.eclStage3),
    ColumnDef.macroPln("BankEcl_OpeningAllowance", ctx => ctx.bankEcl.openingAllowance),
    ColumnDef.macroPln("BankEcl_ClosingAllowance", ctx => ctx.bankEcl.closingAllowance),
    ColumnDef.macroPln("BankEcl_BaselineStage1Allowance", ctx => ctx.bankEcl.baselineStage1Allowance),
    ColumnDef.macroPln("BankEcl_ExcessAllowance", ctx => ctx.bankEcl.excessAllowance),
    ColumnDef("BankEcl_ExcessAllowanceShare", ctx => ctx.flowToStockRate(ctx.bankEcl.excessAllowance, ctx.bankEcl.closingAllowance)),
    ColumnDef("BankEcl_ProvisionChangeToOpeningCapital", ctx => ctx.flowToStockRate(ctx.bankCapital.eclProvisionChange, ctx.bankCapital.openingCapital)),
    ColumnDef("BankEcl_ProvisionChangeToRealizedLoss", ctx => ctx.flowToFlowRatio(ctx.bankCapital.eclProvisionChange, ctx.bankCapital.realizedCreditLoss)),
    ColumnDef("BankEcl_Stage2Share", ctx => ctx.flowToStockRate(ctx.eclStage2, ctx.eclStageTotal)),
    ColumnDef("BankEcl_Stage3Share", ctx => ctx.flowToStockRate(ctx.eclStage3, ctx.eclStageTotal)),
    ColumnDef("BankEcl_MigrationRate", ctx => ctx.bankEcl.migrationRate),
    ColumnDef("BankEcl_GdpGrowthMonthly", ctx => ctx.bankEcl.gdpGrowthMonthly),
    // KNF/BFG
    ColumnDef.macroPln("BfgLevyTotal", ctx => ctx.world.flows.bfgLevyTotal),
    ColumnDef.macroPln("BfgFundBalance", ctx => ctx.world.mechanisms.bfgFundBalance),
    ColumnDef.macroPln("BailInLoss", ctx => ctx.world.flows.bailInLoss),
    // Bank-capital attribution: sector aggregate opening-to-closing waterfall.
    ColumnDef.macroPln("BankCapital_Opening", ctx => ctx.bankCapital.openingCapital),
    ColumnDef.macroPln("BankCapital_Closing", ctx => ctx.bankCapital.closingCapital),
    ColumnDef.macroPln("BankCapital_Delta", ctx => ctx.bankCapital.delta),
    ColumnDef.macroPln("BankCapital_RetainedIncome", ctx => ctx.bankCapital.retainedIncome),
    ColumnDef.macroPln("BankCapital_RealizedCreditLoss", ctx => ctx.bankCapital.realizedCreditLoss),
    ColumnDef.macroPln("BankCapital_FirmNplLoss", ctx => ctx.bankCapital.firmNplLoss),
    ColumnDef.macroPln("BankCapital_MortgageNplLoss", ctx => ctx.bankCapital.mortgageNplLoss),
    ColumnDef.macroPln("BankCapital_ConsumerNplLoss", ctx => ctx.bankCapital.consumerNplLoss),
    ColumnDef.macroPln("BankCapital_CorpBondDefaultLoss", ctx => ctx.bankCapital.corpBondDefaultLoss),
    ColumnDef.macroPln("BankCapital_InterbankContagionLoss", ctx => ctx.bankCapital.interbankContagionLoss),
    ColumnDef.macroPln("BankCapital_BfgLevy", ctx => ctx.bankCapital.bfgLevy),
    ColumnDef.macroPln("BankCapital_UnrealizedBondLoss", ctx => ctx.bankCapital.unrealizedBondLoss),
    ColumnDef.macroPln("BankCapital_HtmRealizedLoss", ctx => ctx.bankCapital.htmRealizedLoss),
    ColumnDef.macroPln("BankCapital_EclProvisionChange", ctx => ctx.bankCapital.eclProvisionChange),
    ColumnDef.macroPln("BankCapital_CapitalDestruction", ctx => ctx.bankCapital.capitalDestruction),
    ColumnDef.macroPln("BankCapital_ReconciliationResidual", ctx => ctx.bankCapital.reconciliationResidual),
    ColumnDef.macroPln("BankCapital_WaterfallResidual", ctx => ctx.bankCapital.waterfallResidual),
    ColumnDef.macroPln("BankCapital_DepositBailInLoss", ctx => ctx.bankCapital.depositBailInLoss),
    ColumnDef("BankCapital_NewFailures", ctx => ctx.bankCapital.newFailures),
    ColumnDef("BankCreditLoss_RealizedToOpeningCapital", ctx => ctx.flowToStockRate(ctx.bankCapital.realizedCreditLoss, ctx.bankCapital.openingCapital)),
    ColumnDef(
      "BankCreditLoss_FirmDefaultRate",
      ctx => ctx.flowToStockRate(ctx.grossDefaultFromLoss(ctx.bankCapital.firmNplLoss, ctx.p.banking.loanRecovery), ctx.bankFirmLoans),
    ),
    ColumnDef("BankCreditLoss_FirmLossRate", ctx => ctx.flowToStockRate(ctx.bankCapital.firmNplLoss, ctx.bankFirmLoans)),
    ColumnDef("BankCreditLoss_MortgageDefaultRate", ctx => ctx.flowToStockRate(ctx.world.real.housing.lastDefault, ctx.ledgerHouseholdMortgageStock)),
    ColumnDef("BankCreditLoss_MortgageLossRate", ctx => ctx.flowToStockRate(ctx.bankCapital.mortgageNplLoss, ctx.ledgerHouseholdMortgageStock)),
    ColumnDef("BankCreditLoss_ConsumerLoanDefaultRate", ctx => ctx.flowToStockRate(ctx.hhAgg.totalConsumerLoanDefault, ctx.consumerLoanStock)),
    ColumnDef("BankCreditLoss_LiquidityBridgeChargeOffRate", ctx => ctx.flowToStockRate(ctx.hhAgg.totalLiquidityBridgeChargeOff, ctx.consumerLoanStock)),
    ColumnDef("BankCreditLoss_ConsumerLossRate", ctx => ctx.flowToStockRate(ctx.bankCapital.consumerNplLoss, ctx.consumerLoanStock)),
    ColumnDef(
      "BankCreditLoss_CorpBondDefaultRate",
      ctx => ctx.flowToStockRate(ctx.grossDefaultFromLoss(ctx.bankCapital.corpBondDefaultLoss, ctx.p.corpBond.recovery), ctx.bankAgg.corpBondHoldings),
    ),
    ColumnDef("BankCreditLoss_CorpBondLossRate", ctx => ctx.flowToStockRate(ctx.bankCapital.corpBondDefaultLoss, ctx.bankAgg.corpBondHoldings)),
  )

  private def housingGroup: Vector[ColumnDef] =
    val regionalHousingColumns = HousingConfig.RegionalMarket.all.map: market =>
      ColumnDef(market.hpiColName, ctx => ctx.housingRegionHpi(market))

    (Vector(
      ColumnDef("HousingPriceIndex", ctx => ctx.world.real.housing.priceIndex),
      ColumnDef.macroPln("HousingMarketValue", ctx => ctx.world.real.housing.totalValue),
      ColumnDef.macroPln("MortgageStock", ctx => ctx.ledgerHouseholdMortgageStock),
      ColumnDef("AvgMortgageRate", ctx => ctx.world.real.housing.avgMortgageRate),
      ColumnDef.macroPln("MortgageOrigination", ctx => ctx.mortgageOrigination),
      ColumnDef.macroPln("MortgageRepayment", ctx => ctx.mortgageRepayment),
      ColumnDef.macroPln("MortgageDefault", ctx => ctx.mortgageDefault),
      ColumnDef.macroPln("MortgageNetStockFlow", ctx => ctx.mortgageNetStockFlow),
      ColumnDef("MortgageOriginationToStock", ctx => ctx.flowToStockRate(ctx.mortgageOrigination, ctx.ledgerHouseholdMortgageStock)),
      ColumnDef("MortgageRepaymentToStock", ctx => ctx.flowToStockRate(ctx.mortgageRepayment, ctx.ledgerHouseholdMortgageStock)),
      ColumnDef("MortgageDefaultToStock", ctx => ctx.flowToStockRate(ctx.mortgageDefault, ctx.ledgerHouseholdMortgageStock)),
      ColumnDef("MortgageNetStockFlowToStock", ctx => ctx.flowToStockRate(ctx.mortgageNetStockFlow, ctx.ledgerHouseholdMortgageStock)),
      ColumnDef("MortgageOriginationSupplyConstrained", ctx => ctx.world.real.housing.lastOriginationSupplyConstrained),
      ColumnDef.macroPln("MortgageInterestIncome", ctx => ctx.world.real.housing.mortgageInterestIncome),
      ColumnDef.macroPln("HhHousingWealth", ctx => ctx.world.real.housing.hhHousingWealth),
      ColumnDef.macroPln("HousingWealthEffect", ctx => ctx.world.real.housing.lastWealthEffect),
      ColumnDef(
        "MortgageToGdp",
        ctx =>
          if ctx.monthlyGdp > PLN.Zero && ctx.ledgerHouseholdMortgageStock > PLN.Zero
          then ctx.ledgerHouseholdMortgageStock / (ctx.monthlyGdp * 12)
          else Scalar.Zero,
      ),
    ) ++ regionalHousingColumns).toVector

  private def firmRealAssetsGroup: Vector[ColumnDef] = Vector(
    // Sectoral Labor Mobility
    ColumnDef("SectorMobilityRate", ctx => ctx.world.real.sectoralMobility.sectorMobilityRate),
    ColumnDef("CrossSectorHires", ctx => ctx.world.real.sectoralMobility.crossSectorHires),
    ColumnDef("VoluntaryQuits", ctx => ctx.world.real.sectoralMobility.voluntaryQuits),
    // Physical Capital
    ColumnDef.macroPln("AggCapitalStock", ctx => ctx.living.map(f => f.capitalStock).sumPln),
    ColumnDef.macroPln("GrossInvestment", ctx => ctx.world.real.grossInvestment),
    ColumnDef.macroPln(
      "TotalGrossFixedCapitalFormation",
      ctx => ctx.world.real.grossInvestment + ctx.world.real.aggGreenInvestment + ctx.world.gov.govCapitalSpend + ctx.world.gov.euProjectCapital,
    ),
    ColumnDef(
      "PrivateGrossInvestmentToGdp",
      ctx => if ctx.monthlyGdp > PLN.Zero then ctx.world.real.grossInvestment / ctx.monthlyGdp else Scalar.Zero,
    ),
    ColumnDef(
      "GrossFixedCapitalFormationToGdp",
      ctx =>
        if ctx.monthlyGdp > PLN.Zero then
          (ctx.world.real.grossInvestment + ctx.world.real.aggGreenInvestment + ctx.world.gov.govCapitalSpend + ctx.world.gov.euProjectCapital) / ctx.monthlyGdp
        else Scalar.Zero,
    ),
    ColumnDef.macroPln(
      "CapitalDepreciation",
      ctx => ctx.living.map(f => f.capitalStock * ctx.p.capital.depRates(f.sector.toInt).monthly).sumPln,
    ),
    // Inventories
    ColumnDef.macroPln("AggInventoryStock", ctx => ctx.world.flows.aggInventoryStock),
    ColumnDef.macroPln("InventoryChange", ctx => ctx.world.flows.aggInventoryChange),
    ColumnDef(
      "InventoryToGdp",
      ctx => if ctx.monthlyGdp > PLN.Zero then ctx.world.flows.aggInventoryStock / ctx.monthlyGdp else Scalar.Zero,
    ),
    // Energy / Climate
    ColumnDef.macroPln("AggEnergyCost", ctx => ctx.world.flows.aggEnergyCost),
    ColumnDef(
      "EnergyCostToGdp",
      ctx => if ctx.monthlyGdp > PLN.Zero then ctx.world.flows.aggEnergyCost / ctx.monthlyGdp else Scalar.Zero,
    ),
    ColumnDef("EtsPrice", ctx => ctx.world.real.etsPrice),
    ColumnDef.macroPln("AggGreenCapital", ctx => ctx.world.real.aggGreenCapital),
    ColumnDef.macroPln("GreenInvestment", ctx => ctx.world.real.aggGreenInvestment),
    ColumnDef(
      "GreenCapitalRatio",
      ctx => {
        val aggK = ctx.living.map(f => f.capitalStock).sumPln
        if ctx.world.real.aggGreenCapital > PLN.Zero && aggK > PLN.Zero then ctx.world.real.aggGreenCapital / aggK else Scalar.Zero
      },
    ),
  )

  private def householdSocialGroup: Vector[ColumnDef] = Vector(
    // JST
    ColumnDef.macroPln("JstRevenue", ctx => ctx.world.social.jst.revenue),
    ColumnDef.macroPln("JstSpending", ctx => ctx.world.social.jst.spending),
    ColumnDef.macroPln("JstDebt", ctx => ctx.world.social.jst.debt),
    ColumnDef.macroPln("JstDeposits", ctx => ctx.ledgerFinancialState.funds.jstCash),
    ColumnDef.macroPln("JstDeficit", ctx => ctx.world.social.jst.deficit),
    // ZUS/PPK
    ColumnDef.macroPln("ZusContributions", ctx => ctx.world.social.zus.contributions),
    ColumnDef.macroPln("ZusPensionPayments", ctx => ctx.world.social.zus.pensionPayments),
    ColumnDef.macroPln("ZusGovSubvention", ctx => ctx.world.social.zus.govSubvention),
    ColumnDef.macroPln("FusBalance", ctx => ctx.ledgerFinancialState.funds.zusCash),
    ColumnDef.macroPln("NfzContributions", ctx => ctx.world.social.nfz.contributions),
    ColumnDef.macroPln("NfzSpending", ctx => ctx.world.social.nfz.spending),
    ColumnDef.macroPln("NfzBalance", ctx => ctx.ledgerFinancialState.funds.nfzCash),
    ColumnDef.macroPln("NfzGovSubvention", ctx => ctx.world.social.nfz.govSubvention),
    ColumnDef.macroPln("PpkContributions", ctx => ctx.world.social.ppk.contributions),
    ColumnDef.macroPln("PpkBondHoldings", ctx => ctx.ledgerFinancialState.funds.ppkGovBondHoldings),
    ColumnDef("NRetirees", ctx => ctx.world.social.demographics.retirees),
    ColumnDef("WorkingAgePop", ctx => ctx.world.social.demographics.workingAgePop),
    ColumnDef("MonthlyRetirements", ctx => ctx.world.social.demographics.monthlyRetirements),
    // Earmarked funds (FP, PFRON, FGŚP)
    ColumnDef.macroPln("FpBalance", ctx => ctx.ledgerFinancialState.funds.fpCash),
    ColumnDef.macroPln("FpContributions", ctx => ctx.world.social.earmarked.fpContributions),
    ColumnDef.macroPln("PfronBalance", ctx => ctx.ledgerFinancialState.funds.pfronCash),
    ColumnDef.macroPln("FgspBalance", ctx => ctx.ledgerFinancialState.funds.fgspCash),
    ColumnDef.macroPln("FgspSpending", ctx => ctx.world.social.earmarked.fgspSpending),
    ColumnDef.macroPln("EarmarkedGovSubvention", ctx => ctx.world.social.earmarked.totalGovSubvention),
    // Forward-Looking Expectations
    ColumnDef("ExpectedInflation", ctx => ctx.world.mechanisms.expectations.expectedInflation),
    ColumnDef("NbpCredibility", ctx => ctx.world.mechanisms.expectations.credibility),
    ColumnDef("ForwardGuidanceRate", ctx => ctx.world.mechanisms.expectations.forwardGuidanceRate),
    ColumnDef("InflationForecastError", ctx => ctx.world.mechanisms.expectations.forecastError),
  )

  private def bankingMacroprudentialGroup: Vector[ColumnDef] = Vector(
    ColumnDef("CCyB", ctx => ctx.world.mechanisms.macropru.ccyb),
    ColumnDef("CreditToGdpGap", ctx => ctx.world.mechanisms.macropru.creditToGdpGap),
    ColumnDef(
      "EffectiveMinCar",
      ctx =>
        if ctx.aliveBanks.isEmpty then Multiplier.Zero
        else {
          given SimParams = ctx.p;
          ctx.aliveBanks.map(b => Macroprudential.effectiveMinCar(b.id.toInt, ctx.world.mechanisms.macropru.ccyb)).max
        },
    ),
  )

  private def firmOwnershipAndEntryGroup: Vector[ColumnDef] = Vector(
    // FDI Composition
    ColumnDef.macroPln("FdiProfitShifting", ctx => ctx.world.flows.fdiProfitShifting),
    ColumnDef.macroPln("FdiRepatriation", ctx => ctx.world.flows.fdiRepatriation),
    ColumnDef.macroPln("FdiGrossOutflow", ctx => ctx.world.flows.fdiProfitShifting + ctx.world.flows.fdiRepatriation),
    ColumnDef(
      "ForeignOwnedFrac",
      ctx => if ctx.nLiving > 0 then Share.fraction(ctx.living.count(_.foreignOwned), ctx.nLiving) else Share.Zero,
    ),
    ColumnDef.macroPln("FdiCitLoss", ctx => ctx.world.flows.fdiCitLoss),
    // Endogenous Firm Entry
    ColumnDef("FirmBirths", ctx => ctx.world.flows.firmBirths),
    ColumnDef("FirmDeaths", ctx => ctx.world.flows.firmDeaths),
  )

  private def householdDistressGroup: Vector[ColumnDef] = Vector(
    // Legacy activity-status bankruptcy; personal insolvency is reported by HouseholdDistress_Bankruptcy.
    ColumnDef("HouseholdBankruptcies", ctx => ctx.hhAgg.bankrupt),
    ColumnDef("HouseholdBankruptcyRate", ctx => ctx.hhAgg.bankruptcyRate),
    // Financial-distress state machine counts and population shares.
    ColumnDef("HouseholdDistress_Current", ctx => ctx.hhAgg.distressCurrent),
    ColumnDef("HouseholdDistress_LiquidityStress", ctx => ctx.hhAgg.distressLiquidityStress),
    ColumnDef("HouseholdDistress_Arrears", ctx => ctx.hhAgg.distressArrears),
    ColumnDef("HouseholdDistress_Restructuring", ctx => ctx.hhAgg.distressRestructuring),
    ColumnDef("HouseholdDistress_Defaulted", ctx => ctx.hhAgg.distressDefaulted),
    ColumnDef("HouseholdDistress_Bankruptcy", ctx => ctx.hhAgg.distressBankruptcy),
    ColumnDef("HouseholdDistress_CurrentShare", ctx => Share.fraction(ctx.hhAgg.distressCurrent, ctx.households.length)),
    ColumnDef("HouseholdDistress_LiquidityStressShare", ctx => Share.fraction(ctx.hhAgg.distressLiquidityStress, ctx.households.length)),
    ColumnDef("HouseholdDistress_ArrearsShare", ctx => Share.fraction(ctx.hhAgg.distressArrears, ctx.households.length)),
    ColumnDef("HouseholdDistress_RestructuringShare", ctx => Share.fraction(ctx.hhAgg.distressRestructuring, ctx.households.length)),
    ColumnDef("HouseholdDistress_DefaultedShare", ctx => Share.fraction(ctx.hhAgg.distressDefaulted, ctx.households.length)),
    ColumnDef("HouseholdDistress_BankruptcyShare", ctx => Share.fraction(ctx.hhAgg.distressBankruptcy, ctx.households.length)),
    ColumnDef("HouseholdDistress_ActiveShare", ctx => ctx.hhAgg.distressActiveShare(ctx.households.length)),
  )

  private def firmPopulationGroup: Vector[ColumnDef] = Vector(
    ColumnDef("NetEntry", ctx => ctx.world.flows.firmBirths - ctx.world.flows.firmDeaths),
    ColumnDef("LivingFirmCount", ctx => ctx.nLiving),
    ColumnDef("NetFirmBirths", ctx => ctx.world.flows.netFirmBirths),
    ColumnDef("TotalFirmCount", ctx => ctx.firms.length),
  )

  private def informalEconomyDiagnosticsGroup: Vector[ColumnDef] = Vector(
    ColumnDef("RealizedTaxShadowShare", ctx => ctx.world.flows.realizedTaxShadowShare),
    ColumnDef("NextTaxShadowShare", ctx => ctx.world.mechanisms.nextTaxShadowShare),
    ColumnDef.macroPln("TaxEvasionLoss", ctx => ctx.world.flows.taxEvasionLoss),
    ColumnDef(
      "EvasionToGdpRatio",
      ctx => if ctx.monthlyGdp > PLN.Zero then ctx.world.flows.taxEvasionLoss / ctx.monthlyGdp else Scalar.Zero,
    ),
  )

  /** Regional unemployment rates per NUTS-1 macroregion. */
  private def householdRegionalGroup: Vector[ColumnDef] =
    def regionUnemp(region: Region, label: String): ColumnDef =
      ColumnDef(
        s"Unemp_$label",
        ctx =>
          val regHh = ctx.households.filter(_.region == region)
          if regHh.isEmpty then Share.Zero
          else Share.fraction(regHh.count(h => !h.status.isInstanceOf[HhStatus.Employed]), regHh.length),
      )
    Vector(
      regionUnemp(Region.Central, "Central"),
      regionUnemp(Region.South, "South"),
      regionUnemp(Region.East, "East"),
      regionUnemp(Region.Northwest, "Northwest"),
      regionUnemp(Region.Southwest, "Southwest"),
      regionUnemp(Region.North, "North"),
    )

  private def householdLiquidityGroup: Vector[ColumnDef] = Vector(
    ColumnDef.macroPln("HouseholdLiquidity_NetDemandDeposit", ctx => ctx.householdLiquidity.netDemandDeposit),
    ColumnDef.macroPln("HouseholdLiquidity_PositiveDemandDeposits", ctx => ctx.householdLiquidity.positiveDemandDeposits),
    ColumnDef.macroPln("HouseholdLiquidity_ImplicitOverdraft", ctx => ctx.householdLiquidity.implicitOverdraft),
    ColumnDef("HouseholdLiquidity_NegativeDepositCount", ctx => ctx.householdLiquidity.negativeDepositCount),
    ColumnDef("HouseholdLiquidity_NegativeDepositShare", ctx => ctx.householdLiquidity.negativeDepositShare),
    ColumnDef.macroPln("HouseholdLiquidity_MinDemandDeposit", ctx => ctx.householdLiquidity.minDemandDeposit),
    ColumnDef.macroPln("HouseholdLiquidity_DepositP01", ctx => ctx.householdLiquidity.depositP01),
    ColumnDef.macroPln("HouseholdLiquidity_DepositP05", ctx => ctx.householdLiquidity.depositP05),
    ColumnDef.macroPln("HouseholdLiquidity_DepositP10", ctx => ctx.householdLiquidity.depositP10),
    ColumnDef.macroPln("HouseholdLiquidity_DepositP25", ctx => ctx.householdLiquidity.depositP25),
    ColumnDef.macroPln("HouseholdLiquidity_DepositP50", ctx => ctx.householdLiquidity.depositP50),
    ColumnDef.macroPln("HouseholdLiquidity_DepositP75", ctx => ctx.householdLiquidity.depositP75),
    ColumnDef.macroPln("HouseholdLiquidity_DepositP90", ctx => ctx.householdLiquidity.depositP90),
    ColumnDef.macroPln("HouseholdLiquidity_DepositP95", ctx => ctx.householdLiquidity.depositP95),
    ColumnDef.macroPln("HouseholdLiquidity_DepositP99", ctx => ctx.householdLiquidity.depositP99),
    ColumnDef.macroPln("HouseholdLiquidity_ShortfallFinancing", ctx => ctx.hhAgg.totalLiquidityShortfallFinancing),
    ColumnDef.macroPln("HouseholdLiquidity_UnmetBasicConsumption", ctx => ctx.hhAgg.totalUnmetBasicConsumption),
    ColumnDef.macroPln("HouseholdLiquidity_DiscretionaryConsumptionCompression", ctx => ctx.hhAgg.totalDiscretionaryConsumptionCompression),
    ColumnDef.macroPln("HouseholdLiquidity_ConsumptionShortfall", ctx => ctx.hhAgg.totalConsumptionShortfall),
    ColumnDef.macroPln("HouseholdLiquidity_RentArrears", ctx => ctx.hhAgg.totalRentArrears),
    ColumnDef.macroPln("HouseholdLiquidity_MortgageArrears", ctx => ctx.hhAgg.totalMortgageArrears),
    ColumnDef.macroPln("HouseholdLiquidity_ConsumerDebtArrears", ctx => ctx.hhAgg.totalConsumerDebtArrears),
    ColumnDef.macroPln("HouseholdLiquidity_TemporaryOverdraft", ctx => ctx.hhAgg.totalTemporaryOverdraft),
  )

  // -------------------------------------------------------------------------
  //  Flat schema — deterministic composition of domain groups
  // -------------------------------------------------------------------------

  // Group order is CSV order. Domain-prefixed subgroups preserve the historical
  // column contract where conceptual domains are not contiguous.
  private val schemaGroups: Vector[ColumnGroup] = Vector(
    ColumnGroup("macro", macroGroup),
    ColumnGroup("firms", firmsGroup),
    ColumnGroup("external", externalGroup),
    ColumnGroup("fiscal", fiscalGroup),
    ColumnGroup("banking", bankingGroup),
    ColumnGroup("credit", creditGroup),
    ColumnGroup("capital-markets", capitalMarketsGroup),
    ColumnGroup("diagnostics", diagnosticsGroup),
    ColumnGroup("housing", housingGroup),
    ColumnGroup("firms-real-assets", firmRealAssetsGroup),
    ColumnGroup("households", householdSocialGroup),
    ColumnGroup("banking-macroprudential", bankingMacroprudentialGroup),
    ColumnGroup("firms-ownership-entry", firmOwnershipAndEntryGroup),
    ColumnGroup("households-distress", householdDistressGroup),
    ColumnGroup("firms-population", firmPopulationGroup),
    ColumnGroup("diagnostics-informal-economy", informalEconomyDiagnosticsGroup),
    ColumnGroup("households-regional", householdRegionalGroup),
    ColumnGroup("households-liquidity", householdLiquidityGroup),
  )

  private[amorfati] val columnGroupNames: Vector[String] =
    schemaGroups.map(_.name)

  private[amorfati] val colNamesByGroup: Vector[(String, Vector[String])] =
    schemaGroups.map(group => group.name -> group.columns.map(_.name))

  private val schema: Array[ColumnDef] =
    schemaGroups.iterator.flatMap(_.columns).toArray

  // -------------------------------------------------------------------------
  //  Col — opaque Int, derived by name lookup
  // -------------------------------------------------------------------------

  /** A typed column handle. Wraps an ordinal — prevents raw Int column access.
    */
  opaque type Col = Int
  object Col:
    private val nameToIdx: Map[String, Int] =
      schema.iterator.zipWithIndex.map((cd, i) => cd.name -> i).toMap

    private def lookup(name: String): Col =
      nameToIdx.getOrElse(name, throw new NoSuchElementException(s"Unknown column: $name"))

    val Month: Col                                     = lookup("Month")
    val Inflation: Col                                 = lookup("Inflation")
    val Unemployment: Col                              = lookup("Unemployment")
    val UnemployedShare: Col                           = lookup("UnemployedShare")
    val RetrainingShare: Col                           = lookup("RetrainingShare")
    val TotalAdoption: Col                             = lookup("TotalAdoption")
    val ExRate: Col                                    = lookup("ExRate")
    val MarketWage: Col                                = lookup("MarketWage")
    val MeanEmployedWage: Col                          = lookup("MeanEmployedWage")
    val GovDebt: Col                                   = lookup("GovDebt")
    val NPL: Col                                       = lookup("NPL")
    val RefRate: Col                                   = lookup("RefRate")
    val PriceLevel: Col                                = lookup("PriceLevel")
    val MonthlyGdpProxy: Col                           = lookup("MonthlyGdpProxy")
    val AnnualizedGdpProxy: Col                        = lookup("AnnualizedGdpProxy")
    val AutoRatio: Col                                 = lookup("AutoRatio")
    val HybridRatio: Col                               = lookup("HybridRatio")
    val AutomationTechCapex: Col                       = lookup("Automation_TechCapex")
    val AutomationTechImports: Col                     = lookup("Automation_TechImports")
    val AutomationTechLoans: Col                       = lookup("Automation_TechLoans")
    val AutomationUpgradeFailures: Col                 = lookup("Automation_UpgradeFailures")
    val AutomationAiDebtTrap: Col                      = lookup("Automation_AiDebtTrap")
    val AutomationNewFullAi: Col                       = lookup("Automation_NewFullAi")
    val AutomationNewHybrid: Col                       = lookup("Automation_NewHybrid")
    val AdoptionMicroShare: Col                        = lookup("Adoption_MicroShare")
    val AdoptionSmallShare: Col                        = lookup("Adoption_SmallShare")
    val AdoptionMediumShare: Col                       = lookup("Adoption_MediumShare")
    val AdoptionLargeShare: Col                        = lookup("Adoption_LargeShare")
    val AdoptionCashQ1: Col                            = lookup("Adoption_CashQ1")
    val AdoptionCashQ2: Col                            = lookup("Adoption_CashQ2")
    val AdoptionCashQ3: Col                            = lookup("Adoption_CashQ3")
    val AdoptionCashQ4: Col                            = lookup("Adoption_CashQ4")
    val AdoptionDebtQ1: Col                            = lookup("Adoption_DebtQ1")
    val AdoptionDebtQ2: Col                            = lookup("Adoption_DebtQ2")
    val AdoptionDebtQ3: Col                            = lookup("Adoption_DebtQ3")
    val AdoptionDebtQ4: Col                            = lookup("Adoption_DebtQ4")
    val HouseholdLiquidityNetDemandDeposit: Col        = lookup("HouseholdLiquidity_NetDemandDeposit")
    val HouseholdLiquidityPositiveDemandDeposits: Col  = lookup("HouseholdLiquidity_PositiveDemandDeposits")
    val HouseholdLiquidityImplicitOverdraft: Col       = lookup("HouseholdLiquidity_ImplicitOverdraft")
    val HouseholdLiquidityNegativeDepositCount: Col    = lookup("HouseholdLiquidity_NegativeDepositCount")
    val HouseholdLiquidityNegativeDepositShare: Col    = lookup("HouseholdLiquidity_NegativeDepositShare")
    val HouseholdLiquidityMinDemandDeposit: Col        = lookup("HouseholdLiquidity_MinDemandDeposit")
    val HouseholdLiquidityDepositP01: Col              = lookup("HouseholdLiquidity_DepositP01")
    val HouseholdLiquidityDepositP05: Col              = lookup("HouseholdLiquidity_DepositP05")
    val HouseholdLiquidityDepositP10: Col              = lookup("HouseholdLiquidity_DepositP10")
    val HouseholdLiquidityDepositP25: Col              = lookup("HouseholdLiquidity_DepositP25")
    val HouseholdLiquidityDepositP50: Col              = lookup("HouseholdLiquidity_DepositP50")
    val HouseholdLiquidityDepositP75: Col              = lookup("HouseholdLiquidity_DepositP75")
    val HouseholdLiquidityDepositP90: Col              = lookup("HouseholdLiquidity_DepositP90")
    val HouseholdLiquidityDepositP95: Col              = lookup("HouseholdLiquidity_DepositP95")
    val HouseholdLiquidityDepositP99: Col              = lookup("HouseholdLiquidity_DepositP99")
    val HouseholdLiquidityShortfallFinancing: Col      = lookup("HouseholdLiquidity_ShortfallFinancing")
    val BpoAuto: Col                                   = lookup("BPO_Auto")
    val ManufAuto: Col                                 = lookup("Manuf_Auto")
    val RetailAuto: Col                                = lookup("Retail_Auto")
    val HealthAuto: Col                                = lookup("Health_Auto")
    val PublicAuto: Col                                = lookup("Public_Auto")
    val AgriAuto: Col                                  = lookup("Agri_Auto")
    val BpoOutput: Col                                 = lookup("BPO_Output")
    val ManufOutput: Col                               = lookup("Manuf_Output")
    val RetailOutput: Col                              = lookup("Retail_Output")
    val HealthOutput: Col                              = lookup("Health_Output")
    val PublicOutput: Col                              = lookup("Public_Output")
    val AgriOutput: Col                                = lookup("Agri_Output")
    val BpoSigma: Col                                  = lookup("BPO_Sigma")
    val ManufSigma: Col                                = lookup("Manuf_Sigma")
    val RetailSigma: Col                               = lookup("Retail_Sigma")
    val HealthSigma: Col                               = lookup("Health_Sigma")
    val PublicSigma: Col                               = lookup("Public_Sigma")
    val AgriSigma: Col                                 = lookup("Agri_Sigma")
    val MeanDegree: Col                                = lookup("MeanDegree")
    val IoFlows: Col                                   = lookup("IoFlows")
    val IoGdpRatio: Col                                = lookup("IoGdpRatio")
    val NFA: Col                                       = lookup("NFA")
    val NfaToGdp: Col                                  = lookup("NfaToGdp")
    val CurrentAccount: Col                            = lookup("CurrentAccount")
    val CurrentAccountToGdp: Col                       = lookup("CurrentAccountToGdp")
    val CurrentAccountPrimaryIncome: Col               = lookup("CurrentAccountPrimaryIncome")
    val CurrentAccountPrimaryIncomeToGdp: Col          = lookup("CurrentAccountPrimaryIncomeToGdp")
    val CurrentAccountSecondaryIncome: Col             = lookup("CurrentAccountSecondaryIncome")
    val CurrentAccountSecondaryIncomeToGdp: Col        = lookup("CurrentAccountSecondaryIncomeToGdp")
    val CurrentAccountClosureResidual: Col             = lookup("CurrentAccountClosureResidual")
    val CapitalAccount: Col                            = lookup("CapitalAccount")
    val CapitalAccountToGdp: Col                       = lookup("CapitalAccountToGdp")
    val TradeBalance: Col                              = lookup("TradeBalance_OE")
    val TradeBalanceToGdp: Col                         = lookup("TradeBalanceToGdp")
    val Exports: Col                                   = lookup("Exports_OE")
    val ExportsToGdp: Col                              = lookup("ExportsToGdp")
    val TotalImports: Col                              = lookup("TotalImports_OE")
    val ImportsToGdp: Col                              = lookup("ImportsToGdp")
    val ImportedInterm: Col                            = lookup("ImportedInterm")
    val ImportedIntermToImports: Col                   = lookup("ImportedIntermToImports")
    val FDI: Col                                       = lookup("FDI")
    val UnempBenefitSpend: Col                         = lookup("UnempBenefitSpend")
    val OutputGap: Col                                 = lookup("OutputGap")
    val BondYield: Col                                 = lookup("BondYield")
    val BondsOutstanding: Col                          = lookup("BondsOutstanding")
    val BankBondHoldings: Col                          = lookup("BankBondHoldings")
    val ForeignBondHoldings: Col                       = lookup("ForeignBondHoldings")
    val NbpBondHoldings: Col                           = lookup("NbpBondHoldings")
    val PpkBondHoldings: Col                           = lookup("PpkBondHoldings")
    val InsGovBondHoldings: Col                        = lookup("InsGovBondHoldings")
    val NbfiTfiAum: Col                                = lookup("NbfiTfiAum")
    val NbfiTfiGovBondHoldings: Col                    = lookup("NbfiTfiGovBondHoldings")
    val NbfiLoanStock: Col                             = lookup("NbfiLoanStock")
    val NbfiOrigination: Col                           = lookup("NbfiOrigination")
    val NbfiRepayment: Col                             = lookup("NbfiRepayment")
    val NbfiDefaults: Col                              = lookup("NbfiDefaults")
    val NbfiNetStockFlow: Col                          = lookup("NbfiNetStockFlow")
    val NbfiOriginationToStock: Col                    = lookup("NbfiOriginationToStock")
    val NbfiRepaymentToStock: Col                      = lookup("NbfiRepaymentToStock")
    val NbfiDefaultsToStock: Col                       = lookup("NbfiDefaultsToStock")
    val NbfiDepositDrain: Col                          = lookup("NbfiDepositDrain")
    val NbfiDepositDrainToAum: Col                     = lookup("NbfiDepositDrainToAum")
    val QeActive: Col                                  = lookup("QeActive")
    val DebtService: Col                               = lookup("DebtService")
    val NbpRemittance: Col                             = lookup("NbpRemittance")
    val FxReserves: Col                                = lookup("FxReserves")
    val FxInterventionAmt: Col                         = lookup("FxInterventionAmt")
    val FxInterventionActive: Col                      = lookup("FxInterventionActive")
    val InterbankRate: Col                             = lookup("InterbankRate")
    val MinBankCAR: Col                                = lookup("MinBankCAR")
    val MaxBankNPL: Col                                = lookup("MaxBankNPL")
    val BankFailures: Col                              = lookup("BankFailures")
    val BankResolutionActiveBanks: Col                 = lookup("BankResolution_ActiveBanks")
    val BankResolutionFailedBanks: Col                 = lookup("BankResolution_FailedBanks")
    val BankResolutionNewFailures: Col                 = lookup("BankResolution_NewFailures")
    val BankResolutionBailInEvents: Col                = lookup("BankResolution_BailInEvents")
    val BankResolutionResolvedBanks: Col               = lookup("BankResolution_ResolvedBanks")
    val BankResolutionAllFailedFallback: Col           = lookup("BankResolution_AllFailedFallback")
    val BankResolutionBridgeRecapitalization: Col      = lookup("BankResolution_BridgeRecapitalization")
    val BankResolutionInvalidActiveBankInvariant: Col  = lookup("BankResolution_InvalidActiveBankInvariant")
    val BankFailureNewNegativeCapital: Col             = lookup("BankFailure_NewNegativeCapital")
    val BankFailureNewCarBreach: Col                   = lookup("BankFailure_NewCarBreach")
    val BankFailureNewLiquidityBreach: Col             = lookup("BankFailure_NewLiquidityBreach")
    val BankFailureAllFailedFallback: Col              = lookup("BankFailure_AllFailedFallback")
    val BankFailureInvariantViolation: Col             = lookup("BankFailure_InvariantViolation")
    val BankFailureFirstNewReasonCode: Col             = lookup("BankFailure_FirstNewReasonCode")
    val BankFailureFirstNewBankId: Col                 = lookup("BankFailure_FirstNewBankId")
    val BankReconciliationTargetBankId: Col            = lookup("BankReconciliation_TargetBankId")
    val BankReconciliationCapitalResidual: Col         = lookup("BankReconciliation_CapitalResidual")
    val BankReconciliationTargetCapitalBefore: Col     = lookup("BankReconciliation_TargetCapitalBefore")
    val BankReconciliationTargetCapitalAfter: Col      = lookup("BankReconciliation_TargetCapitalAfter")
    val BankReconciliationTargetCarBefore: Col         = lookup("BankReconciliation_TargetCarBefore")
    val BankReconciliationTargetCarAfter: Col          = lookup("BankReconciliation_TargetCarAfter")
    val BankReconciliationResidualToTargetCapital: Col = lookup("BankReconciliation_ResidualToTargetCapital")
    val BankReconciliationMaterialResidual: Col        = lookup("BankReconciliation_MaterialResidual")
    val BankReconciliationCrossedFailureThreshold: Col = lookup("BankReconciliation_CrossedFailureThreshold")
    val BankReconciliationPostResidualReasonCode: Col  = lookup("BankReconciliation_PostResidualReasonCode")
    val BankFirmLoans: Col                             = lookup("BankFirmLoans")
    val FirmCreditNewLoans: Col                        = lookup("FirmCredit_NewLoans")
    val FirmCreditPrincipalRepaid: Col                 = lookup("FirmCredit_PrincipalRepaid")
    val FirmCreditGrossDefault: Col                    = lookup("FirmCredit_GrossDefault")
    val FirmCreditNplRecovery: Col                     = lookup("FirmCredit_NplRecovery")
    val FirmCreditNplLoss: Col                         = lookup("FirmCredit_NplLoss")
    val FirmCreditNetStockFlow: Col                    = lookup("FirmCredit_NetStockFlow")
    val FirmCreditCreditDemand: Col                    = lookup("FirmCredit_CreditDemand")
    val FirmCreditCreditApproved: Col                  = lookup("FirmCredit_CreditApproved")
    val FirmCreditBankRejected: Col                    = lookup("FirmCredit_BankRejected")
    val FirmCreditRejectedFailedBank: Col              = lookup("FirmCredit_RejectedFailedBank")
    val FirmCreditRejectedCarGate: Col                 = lookup("FirmCredit_RejectedCarGate")
    val FirmCreditRejectedLcrGate: Col                 = lookup("FirmCredit_RejectedLcrGate")
    val FirmCreditRejectedNsfrGate: Col                = lookup("FirmCredit_RejectedNsfrGate")
    val FirmCreditRejectedStochastic: Col              = lookup("FirmCredit_RejectedStochastic")
    val FirmCreditRejectedUnclassified: Col            = lookup("FirmCredit_RejectedUnclassified")
    val FirmCreditApprovalRate: Col                    = lookup("FirmCredit_ApprovalRate")
    val FirmCreditInvestmentDemand: Col                = lookup("FirmCredit_InvestmentDemand")
    val FirmCreditInvestmentApproved: Col              = lookup("FirmCredit_InvestmentApproved")
    val FirmCreditInvestmentBankRejected: Col          = lookup("FirmCredit_InvestmentBankRejected")
    val FirmCreditCashFinancedInvestment: Col          = lookup("FirmCredit_CashFinancedInvestment")
    val FirmCreditCashFinancedInvestmentToGfcf: Col    = lookup("FirmCredit_CashFinancedInvestmentToGrossInvestment")
    val FirmCreditTechDemand: Col                      = lookup("FirmCredit_TechDemand")
    val FirmCreditTechApproved: Col                    = lookup("FirmCredit_TechApproved")
    val FirmCreditTechBankRejected: Col                = lookup("FirmCredit_TechBankRejected")
    val FirmCreditTechSelectedDemand: Col              = lookup("FirmCredit_TechSelectedDemand")
    val FirmCreditTechSelectedApproved: Col            = lookup("FirmCredit_TechSelectedApproved")
    val FirmCreditTechSelectedBankRejected: Col        = lookup("FirmCredit_TechSelectedBankRejected")
    val FirmCreditTechCandidateDemand: Col             = lookup("FirmCredit_TechCandidateDemand")
    val FirmCreditTechCandidateApproved: Col           = lookup("FirmCredit_TechCandidateApproved")
    val FirmCreditTechCandidateBankRejected: Col       = lookup("FirmCredit_TechCandidateBankRejected")
    val FirmCreditTechCandidateApprovalRate: Col       = lookup("FirmCredit_TechCandidateApprovalRate")
    val ConsumerPrincipal: Col                         = lookup("ConsumerPrincipal")
    val ConsumerCreditNetStockFlow: Col                = lookup("ConsumerCredit_NetStockFlow")
    val ConsumerCreditUnderwrittenNetFlow: Col         = lookup("ConsumerCredit_UnderwrittenNetFlow")
    val ConsumerCreditBridgeNetFlow: Col               = lookup("ConsumerCredit_BridgeNetFlow")
    val ConsumerCreditNplStock: Col                    = lookup("ConsumerCredit_NplStock")
    val ConsumerCreditNplRatioGross: Col               = lookup("ConsumerCredit_NplRatioGross")
    val ConsumerCreditApprovedToDemand: Col            = lookup("ConsumerCredit_ApprovedToDemand")
    val ConsumerCreditRejectedToDemand: Col            = lookup("ConsumerCredit_RejectedToDemand")
    val ConsumerCreditBankRejectedToDemand: Col        = lookup("ConsumerCredit_BankRejectedToDemand")
    val ConsumerCreditShortfallToApproved: Col         = lookup("ConsumerCredit_ShortfallToApprovedOrigination")
    val MortgageStock: Col                             = lookup("MortgageStock")
    val MortgageOrigination: Col                       = lookup("MortgageOrigination")
    val MortgageRepayment: Col                         = lookup("MortgageRepayment")
    val MortgageDefault: Col                           = lookup("MortgageDefault")
    val MortgageNetStockFlow: Col                      = lookup("MortgageNetStockFlow")
    val MortgageOriginationToStock: Col                = lookup("MortgageOriginationToStock")
    val MortgageRepaymentToStock: Col                  = lookup("MortgageRepaymentToStock")
    val MortgageDefaultToStock: Col                    = lookup("MortgageDefaultToStock")
    val MortgageNetStockFlowToStock: Col               = lookup("MortgageNetStockFlowToStock")
    val MortgageOriginationSupplyConstrained: Col      = lookup("MortgageOriginationSupplyConstrained")
    val MortgageToGdp: Col                             = lookup("MortgageToGdp")
    val TotalCreditStock: Col                          = lookup("TotalCreditStock")
    val TotalCreditToGdp: Col                          = lookup("TotalCreditToGdp")
    val ReserveInterest: Col                           = lookup("ReserveInterest")
    val StandingFacilityNet: Col                       = lookup("StandingFacilityNet")
    val DepositFacilityUsage: Col                      = lookup("DepositFacilityUsage")
    val InterbankInterestNet: Col                      = lookup("InterbankInterestNet")
    val BankCapitalOpening: Col                        = lookup("BankCapital_Opening")
    val BankCapitalClosing: Col                        = lookup("BankCapital_Closing")
    val BankCapitalDelta: Col                          = lookup("BankCapital_Delta")
    val BankCapitalRetainedIncome: Col                 = lookup("BankCapital_RetainedIncome")
    val BankCapitalRealizedCreditLoss: Col             = lookup("BankCapital_RealizedCreditLoss")
    val BankCapitalFirmNplLoss: Col                    = lookup("BankCapital_FirmNplLoss")
    val BankCapitalMortgageNplLoss: Col                = lookup("BankCapital_MortgageNplLoss")
    val BankCapitalConsumerNplLoss: Col                = lookup("BankCapital_ConsumerNplLoss")
    val BankCapitalCorpBondDefaultLoss: Col            = lookup("BankCapital_CorpBondDefaultLoss")
    val BankCapitalInterbankContagionLoss: Col         = lookup("BankCapital_InterbankContagionLoss")
    val BankCapitalBfgLevy: Col                        = lookup("BankCapital_BfgLevy")
    val BankCapitalUnrealizedBondLoss: Col             = lookup("BankCapital_UnrealizedBondLoss")
    val BankCapitalHtmRealizedLoss: Col                = lookup("BankCapital_HtmRealizedLoss")
    val BankCapitalEclProvisionChange: Col             = lookup("BankCapital_EclProvisionChange")
    val BankCapitalCapitalDestruction: Col             = lookup("BankCapital_CapitalDestruction")
    val BankCapitalReconciliationResidual: Col         = lookup("BankCapital_ReconciliationResidual")
    val BankCapitalWaterfallResidual: Col              = lookup("BankCapital_WaterfallResidual")
    val BankCapitalDepositBailInLoss: Col              = lookup("BankCapital_DepositBailInLoss")
    val BankCapitalNewFailures: Col                    = lookup("BankCapital_NewFailures")
    private val sectorAutoNames                        = sectorColumns.map(_.autoColName)
    private val sectorOutputNames                      = sectorColumns.map(_.outputColName)
    private val sectorSigmaNames                       = sectorColumns.map(_.sigmaColName)

    private def sectorCol(names: Vector[String], sectorIndex: Int, kind: String): Col =
      names
        .lift(sectorIndex)
        .map(lookup)
        .getOrElse(throw new IndexOutOfBoundsException(s"$kind sector index must be between 0 and ${names.length - 1}, got $sectorIndex"))

    def sectorAuto(s: Int): Col   = sectorCol(sectorAutoNames, s, "sectorAuto")
    def sectorOutput(s: Int): Col = sectorCol(sectorOutputNames, s, "sectorOutput")
    def sectorSigma(s: Int): Col  = sectorCol(sectorSigmaNames, s, "sectorSigma")

  extension (c: Col) def ordinal: Int = c

  // -------------------------------------------------------------------------
  //  Public API
  // -------------------------------------------------------------------------

  /** Column names — derived from schema. */
  val colNames: Array[String] = schema.map(_.name)

  /** Number of columns — derived from schema. */
  val nCols: Int = schema.length

  private[amorfati] val csvSchema: McCsvSchema[(ExecutionMonth, Array[MetricValue])] =
    McCsvSchema(
      header = colNames.mkString(";"),
      render = (month, row) =>
        val sb = new StringBuilder
        sb.append(month.toInt)
        for c <- 1 until nCols do sb.append(";").append(row(c).format(6))
        sb.toString,
    )

  /** Compute one row. Returns fixed-point metric values for MC aggregation. */
  def compute(
      executionMonth: ExecutionMonth,
      world: World,
      firms: Vector[Firm.State],
      households: Vector[Household.State],
      banks: Vector[Banking.BankState],
      householdAggregates: Household.Aggregates,
      ledgerFinancialState: LedgerFinancialState,
  )(using p: SimParams): Array[MetricValue] =
    val living     = firms.filter(Firm.isAlive)
    val aliveBanks = banks.filterNot(_.failed).toVector
    val ctx        = Ctx(executionMonth, world, firms, households, banks, householdAggregates, ledgerFinancialState, living, living.length, aliveBanks, p)
    val result     = new Array[MetricValue](schema.length)
    var i          = 0
    while i < schema.length do
      result(i) = schema(i).compute(ctx)
      i += 1
    result

  /** Typed row access: row.at(Col.Inflation) instead of row(1). */
  extension (row: Array[MetricValue]) def at(c: Col): MetricValue = row(c.ordinal)
