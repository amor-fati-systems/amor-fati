package com.boombustgroup.amorfati.montecarlo

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import com.boombustgroup.amorfati.agents.{Firm, Household, TechState}
import com.boombustgroup.amorfati.config.{HousingConfig, SimParams}
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.{BankFailureDiagnostics, BankReconciliationDiagnostics, World}
import com.boombustgroup.amorfati.engine.flows.FlowSimulation
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.init.{InitRandomness, WorldInit}
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class McTimeseriesSchemaSpec extends AnyFlatSpec with Matchers:

  given SimParams = SimParams.defaults

  private lazy val init      = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
  private lazy val initState = FlowSimulation.SimState.fromInit(init)

  private val expectedColNames = Vector(
    "Month",
    "Inflation",
    "Unemployment",
    "UnemployedShare",
    "RetrainingShare",
    "PermanentShare",
    "ZlecenieShare",
    "B2BShare",
    "TotalAdoption",
    "ExRate",
    "MarketWage",
    "MeanEmployedWage",
    "GovDebt",
    "NPL",
    "RefRate",
    "PriceLevel",
    "MonthlyGdpProxy",
    "AnnualizedGdpProxy",
    "AutoRatio",
    "HybridRatio",
    "Automation_TechCapex",
    "Automation_TechImports",
    "Automation_TechLoans",
    "Automation_UpgradeFailures",
    "Automation_AiDebtTrap",
    "Automation_NewFullAi",
    "Automation_NewHybrid",
    "Adoption_MicroShare",
    "Adoption_SmallShare",
    "Adoption_MediumShare",
    "Adoption_LargeShare",
    "Adoption_CashQ1",
    "Adoption_CashQ2",
    "Adoption_CashQ3",
    "Adoption_CashQ4",
    "Adoption_DebtQ1",
    "Adoption_DebtQ2",
    "Adoption_DebtQ3",
    "Adoption_DebtQ4",
    "BPO_Auto",
    "Manuf_Auto",
    "Retail_Auto",
    "Health_Auto",
    "Public_Auto",
    "Agri_Auto",
    "BPO_Output",
    "Manuf_Output",
    "Retail_Output",
    "Health_Output",
    "Public_Output",
    "Agri_Output",
    "BPO_Sigma",
    "Manuf_Sigma",
    "Retail_Sigma",
    "Health_Sigma",
    "Public_Sigma",
    "Agri_Sigma",
    "MeanDegree",
    "IoFlows",
    "IoGdpRatio",
    "NFA",
    "CurrentAccount",
    "CurrentAccountToGdp",
    "CapitalAccount",
    "TradeBalance_OE",
    "Exports_OE",
    "TotalImports_OE",
    "ImportedInterm",
    "FDI",
    "GvcDisruptionIndex",
    "ForeignPriceIndex",
    "GvcTradeConcentration",
    "GvcExportDemandShock",
    "GvcImportCostIndex",
    "CommodityPriceIndex",
    "ImmigrantStock",
    "MonthlyImmigInflow",
    "RemittanceOutflow",
    "ImmigrantUnempRate",
    "FxReserves",
    "FxInterventionAmt",
    "FxInterventionActive",
    "DiasporaRemittanceInflow",
    "NetRemittances",
    "TourismExport",
    "TourismImport",
    "NetTourismBalance",
    "TourismSeasonalFactor",
    "UnempBenefitSpend",
    "OutputGap",
    "EffectivePitRate",
    "GovTaxRevenue",
    "GovDividendRevenue",
    "GovTotalRevenue",
    "GovRevenueToGdp",
    "SocialTransferSpend",
    "GovCurrentSpend",
    "GovCapitalSpendDomestic",
    "GovDomesticBudgetDemand",
    "GovDomesticBudgetOutlays",
    "GovDeficit",
    "GovOutlaysToGdp",
    "EuProjectCapitalTotal",
    "PublicCapitalStock",
    "EuCofinancingDomestic",
    "EuFundsMonthly",
    "EuCumulativeAbsorption",
    "MinWageLevel",
    "ExciseRevenue",
    "CustomsDutyRevenue",
    "DebtToGdp",
    "DeficitToGdp",
    "FiscalRuleBinding",
    "GovSpendingCutRatio",
    "BondYield",
    "WeightedCoupon",
    "BondsOutstanding",
    "BankBondHoldings",
    "ForeignBondHoldings",
    "NbpBondHoldings",
    "QeActive",
    "DebtService",
    "NbpRemittance",
    "ReserveInterest",
    "StandingFacilityNet",
    "DepositFacilityUsage",
    "InterbankInterestNet",
    "M0",
    "M1",
    "M2",
    "M3",
    "CreditMultiplier",
    "FofResidual",
    "InterbankRate",
    "MinBankCAR",
    "MaxBankNPL",
    "BankFailures",
    "BankFailure_NewNegativeCapital",
    "BankFailure_NewCarBreach",
    "BankFailure_NewLiquidityBreach",
    "BankFailure_AllFailedFallback",
    "BankFailure_InvariantViolation",
    "BankFailure_FirstNewReasonCode",
    "BankFailure_FirstNewBankId",
    "BankReconciliation_TargetBankId",
    "BankReconciliation_CapitalResidual",
    "BankReconciliation_TargetCapitalBefore",
    "BankReconciliation_TargetCapitalAfter",
    "BankReconciliation_TargetCarBefore",
    "BankReconciliation_TargetCarAfter",
    "BankReconciliation_ResidualToTargetCapital",
    "BankReconciliation_MaterialResidual",
    "BankReconciliation_CrossedFailureThreshold",
    "BankReconciliation_PostResidualReasonCode",
    "MinBankLCR",
    "MinBankNSFR",
    "AvgTermDepositFrac",
    "WIBOR_1M",
    "WIBOR_3M",
    "WIBOR_6M",
    "BankFirmLoans",
    "ConsumerLoans",
    "ConsumerNplRatio",
    "ConsumerOrigination",
    "ConsumerApprovedOrigination",
    "ConsumerCreditDemand",
    "ConsumerRejectedOrigination",
    "ConsumerDebtService",
    "ConsumerDefault",
    "ConsumerLoanDefault",
    "LiquidityBridgeChargeOff",
    "TotalCreditStock",
    "BankFirmLoansToGdp",
    "ConsumerLoansToGdp",
    "NbfiLoansToGdp",
    "TotalCreditToGdp",
    "GpwIndex",
    "GpwMarketCap",
    "GpwPE",
    "GpwDivYield",
    "EquityIssuanceTotal",
    "EquityFinancedFrac",
    "HhEquityWealth",
    "EquityWealthEffect",
    "DomesticDividends",
    "ForeignDividendOutflow",
    "GovernmentDividends",
    "CorpBondOutstanding",
    "CorpBondYield",
    "CorpBondIssuance",
    "CorpBondSpread",
    "BankCorpBondHoldings",
    "PpkCorpBondHoldings",
    "CorpBondAbsorptionRate",
    "InsLifeReserves",
    "InsNonLifeReserves",
    "InsGovBondHoldings",
    "InsLifePremium",
    "InsNonLifePremium",
    "InsLifeClaims",
    "InsNonLifeClaims",
    "NbfiTfiAum",
    "NbfiTfiGovBondHoldings",
    "NbfiLoanStock",
    "NbfiOrigination",
    "NbfiDefaults",
    "NbfiBankTightness",
    "QfBondsOutstanding",
    "QfNbpHoldings",
    "QfLoanPortfolio",
    "QfIssuance",
    "Esa2010DebtToGdp",
    "NbfiDepositDrain",
    "BankAfsBonds",
    "BankHtmBonds",
    "EclStage1",
    "EclStage2",
    "EclStage3",
    "BfgLevyTotal",
    "BfgFundBalance",
    "BailInLoss",
    "BankCapital_Opening",
    "BankCapital_Closing",
    "BankCapital_Delta",
    "BankCapital_RetainedIncome",
    "BankCapital_RealizedCreditLoss",
    "BankCapital_FirmNplLoss",
    "BankCapital_MortgageNplLoss",
    "BankCapital_ConsumerNplLoss",
    "BankCapital_CorpBondDefaultLoss",
    "BankCapital_BfgLevy",
    "BankCapital_UnrealizedBondLoss",
    "BankCapital_HtmRealizedLoss",
    "BankCapital_EclProvisionChange",
    "BankCapital_CapitalDestruction",
    "BankCapital_ReconciliationResidual",
    "BankCapital_WaterfallResidual",
    "BankCapital_DepositBailInLoss",
    "BankCapital_NewFailures",
    "HousingPriceIndex",
    "HousingMarketValue",
    "MortgageStock",
    "AvgMortgageRate",
    "MortgageOrigination",
    "MortgageRepayment",
    "MortgageDefault",
    "MortgageInterestIncome",
    "HhHousingWealth",
    "HousingWealthEffect",
    "MortgageToGdp",
    "WawHpi",
    "KrkHpi",
    "WroHpi",
    "GdnHpi",
    "LdzHpi",
    "PozHpi",
    "RestHpi",
    "SectorMobilityRate",
    "CrossSectorHires",
    "VoluntaryQuits",
    "AggCapitalStock",
    "GrossInvestment",
    "TotalGrossFixedCapitalFormation",
    "PrivateGrossInvestmentToGdp",
    "GrossFixedCapitalFormationToGdp",
    "CapitalDepreciation",
    "AggInventoryStock",
    "InventoryChange",
    "InventoryToGdp",
    "AggEnergyCost",
    "EnergyCostToGdp",
    "EtsPrice",
    "AggGreenCapital",
    "GreenInvestment",
    "GreenCapitalRatio",
    "JstRevenue",
    "JstSpending",
    "JstDebt",
    "JstDeposits",
    "JstDeficit",
    "ZusContributions",
    "ZusPensionPayments",
    "ZusGovSubvention",
    "FusBalance",
    "NfzContributions",
    "NfzSpending",
    "NfzBalance",
    "NfzGovSubvention",
    "PpkContributions",
    "PpkBondHoldings",
    "NRetirees",
    "WorkingAgePop",
    "MonthlyRetirements",
    "FpBalance",
    "FpContributions",
    "PfronBalance",
    "FgspBalance",
    "FgspSpending",
    "EarmarkedGovSubvention",
    "ExpectedInflation",
    "NbpCredibility",
    "ForwardGuidanceRate",
    "InflationForecastError",
    "CCyB",
    "CreditToGdpGap",
    "EffectiveMinCar",
    "FdiProfitShifting",
    "FdiRepatriation",
    "FdiGrossOutflow",
    "ForeignOwnedFrac",
    "FdiCitLoss",
    "FirmBirths",
    "FirmDeaths",
    "HouseholdBankruptcies",
    "HouseholdBankruptcyRate",
    "HouseholdDistress_Current",
    "HouseholdDistress_LiquidityStress",
    "HouseholdDistress_Arrears",
    "HouseholdDistress_Restructuring",
    "HouseholdDistress_Defaulted",
    "HouseholdDistress_Bankruptcy",
    "HouseholdDistress_CurrentShare",
    "HouseholdDistress_LiquidityStressShare",
    "HouseholdDistress_ArrearsShare",
    "HouseholdDistress_RestructuringShare",
    "HouseholdDistress_DefaultedShare",
    "HouseholdDistress_BankruptcyShare",
    "HouseholdDistress_ActiveShare",
    "NetEntry",
    "LivingFirmCount",
    "NetFirmBirths",
    "TotalFirmCount",
    "RealizedTaxShadowShare",
    "NextTaxShadowShare",
    "TaxEvasionLoss",
    "EvasionToGdpRatio",
    "Unemp_Central",
    "Unemp_South",
    "Unemp_East",
    "Unemp_Northwest",
    "Unemp_Southwest",
    "Unemp_North",
    "HouseholdLiquidity_NetDemandDeposit",
    "HouseholdLiquidity_PositiveDemandDeposits",
    "HouseholdLiquidity_ImplicitOverdraft",
    "HouseholdLiquidity_NegativeDepositCount",
    "HouseholdLiquidity_NegativeDepositShare",
    "HouseholdLiquidity_MinDemandDeposit",
    "HouseholdLiquidity_DepositP01",
    "HouseholdLiquidity_DepositP05",
    "HouseholdLiquidity_DepositP10",
    "HouseholdLiquidity_DepositP25",
    "HouseholdLiquidity_DepositP50",
    "HouseholdLiquidity_DepositP75",
    "HouseholdLiquidity_DepositP90",
    "HouseholdLiquidity_DepositP95",
    "HouseholdLiquidity_DepositP99",
    "HouseholdLiquidity_ShortfallFinancing",
    "HouseholdLiquidity_UnmetBasicConsumption",
    "HouseholdLiquidity_DiscretionaryConsumptionCompression",
    "HouseholdLiquidity_ConsumptionShortfall",
    "HouseholdLiquidity_RentArrears",
    "HouseholdLiquidity_MortgageArrears",
    "HouseholdLiquidity_ConsumerDebtArrears",
    "HouseholdLiquidity_TemporaryOverdraft",
  )

  private def computeRow(
      world: World,
      ledgerFinancialState: LedgerFinancialState = initState.ledgerFinancialState,
      firms: Vector[Firm.State] = init.firms,
      householdAggregates: Household.Aggregates = init.householdAggregates,
      preserveSectorOutputs: Boolean = false,
  ): Array[MetricValue] =
    val effectiveWorld =
      if preserveSectorOutputs || world.flows.sectorOutputs.nonEmpty then world
      else world.copy(flows = world.flows.copy(sectorOutputs = Vector.fill(summon[SimParams].sectorDefs.length)(PLN.Zero)))
    McTimeseriesSchema.compute(
      executionMonth = ExecutionMonth.First,
      world = effectiveWorld,
      firms = firms,
      households = init.households,
      banks = init.banks,
      householdAggregates = householdAggregates,
      ledgerFinancialState = ledgerFinancialState,
    )

  private def valueAt(row: Array[MetricValue], name: String): MetricValue =
    val idx = McTimeseriesSchema.colNames.indexOf(name)
    idx.should(be >= 0)
    row(idx)

  private def polandScale(value: PLN): MetricValue =
    MetricValue.fromRaw((value / summon[SimParams].gdpRatio.toMultiplier).toLong)

  private def shareMetric(numerator: Int, denominator: Int): MetricValue =
    MetricValue.fromRaw(Share.fraction(numerator, denominator).toLong)

  "McTimeseriesSchema" should "expose the stable schema contract" in {
    McTimeseriesSchema.nCols shouldBe 365
    McTimeseriesSchema.colNames.toVector shouldBe expectedColNames
  }

  it should "fail fast when currentSigmas does not match the sector schema" in {
    val err = intercept[IllegalArgumentException]:
      computeRow(init.world.copy(currentSigmas = init.world.currentSigmas.dropRight(1)))

    err.getMessage.should(include("world.currentSigmas"))
  }

  it should "keep sector sigma columns aligned with the schema" in {
    val updatedSigma = Sigma(17)
    val updatedWorld = init.world.copy(currentSigmas = init.world.currentSigmas.updated(1, updatedSigma))
    val updatedRow   = computeRow(updatedWorld)

    valueAt(updatedRow, "Manuf_Sigma") shouldBe MetricValue(17)
    valueAt(updatedRow, "BPO_Sigma") shouldBe valueAt(computeRow(init.world), "BPO_Sigma")
  }

  it should "emit Poland-scale GDP proxy and sector output columns" in {
    val sectorOutputs = Vector(PLN(10), PLN(20), PLN(30), PLN(40), PLN(50), PLN(60))
    val world         = init.world.copy(
      flows = init.world.flows.copy(
        monthlyGdpProxy = PLN(123),
        sectorOutputs = sectorOutputs,
      ),
    )
    val row           = computeRow(world)

    valueAt(row, "MonthlyGdpProxy") shouldBe polandScale(PLN(123))
    valueAt(row, "AnnualizedGdpProxy") shouldBe polandScale(PLN(1476))
    valueAt(row, "BPO_Output") shouldBe polandScale(PLN(10))
    valueAt(row, "Manuf_Output") shouldBe polandScale(PLN(20))
    valueAt(row, "Retail_Output") shouldBe polandScale(PLN(30))
    valueAt(row, "Health_Output") shouldBe polandScale(PLN(40))
    valueAt(row, "Public_Output") shouldBe polandScale(PLN(50))
    valueAt(row, "Agri_Output") shouldBe polandScale(PLN(60))
  }

  it should "emit generic automation financing and transition diagnostics" in {
    val world = init.world.copy(
      flows = init.world.flows.copy(
        sectorOutputs = Vector.fill(summon[SimParams].sectorDefs.length)(PLN.Zero),
        automationTechCapex = PLN(10),
        automationTechImports = PLN(3),
        automationTechLoans = PLN(7),
        automationUpgradeFailures = 2,
        automationAiDebtTrap = 1,
        automationNewFullAi = 4,
        automationNewHybrid = 5,
      ),
    )
    val row   = computeRow(world)

    valueAt(row, "Automation_TechCapex") shouldBe polandScale(PLN(10))
    valueAt(row, "Automation_TechImports") shouldBe polandScale(PLN(3))
    valueAt(row, "Automation_TechLoans") shouldBe polandScale(PLN(7))
    valueAt(row, "Automation_UpgradeFailures") shouldBe MetricValue.fromInt(2)
    valueAt(row, "Automation_AiDebtTrap") shouldBe MetricValue.fromInt(1)
    valueAt(row, "Automation_NewFullAi") shouldBe MetricValue.fromInt(4)
    valueAt(row, "Automation_NewHybrid") shouldBe MetricValue.fromInt(5)
  }

  it should "emit adoption heterogeneity by firm size and cash/debt quartile" in {
    val baseFirm                                                 = init.firms.head
    def firm(id: Int, tech: TechState, workers: Int): Firm.State =
      baseFirm.copy(id = FirmId(id), tech = tech, initialSize = workers)

    val firms  = Vector(
      firm(0, TechState.Traditional(5), 5),
      firm(1, TechState.Hybrid(5, Multiplier.One), 5),
      firm(2, TechState.Traditional(20), 20),
      firm(3, TechState.Hybrid(20, Multiplier.One), 20),
      firm(4, TechState.Hybrid(100, Multiplier.One), 100),
      firm(5, TechState.Hybrid(100, Multiplier.One), 100),
      firm(6, TechState.Traditional(300), 300),
      firm(7, TechState.Hybrid(300, Multiplier.One), 300),
    )
    val ledger = initState.ledgerFinancialState.copy(
      firms = Vector(
        LedgerFinancialState.FirmBalances(cash = PLN(10), firmLoan = PLN(80), corpBond = PLN.Zero, equity = PLN.Zero),
        LedgerFinancialState.FirmBalances(cash = PLN(20), firmLoan = PLN(70), corpBond = PLN.Zero, equity = PLN.Zero),
        LedgerFinancialState.FirmBalances(cash = PLN(30), firmLoan = PLN(60), corpBond = PLN.Zero, equity = PLN.Zero),
        LedgerFinancialState.FirmBalances(cash = PLN(40), firmLoan = PLN(50), corpBond = PLN.Zero, equity = PLN.Zero),
        LedgerFinancialState.FirmBalances(cash = PLN(50), firmLoan = PLN(40), corpBond = PLN.Zero, equity = PLN.Zero),
        LedgerFinancialState.FirmBalances(cash = PLN(60), firmLoan = PLN(30), corpBond = PLN.Zero, equity = PLN.Zero),
        LedgerFinancialState.FirmBalances(cash = PLN(70), firmLoan = PLN(20), corpBond = PLN.Zero, equity = PLN.Zero),
        LedgerFinancialState.FirmBalances(cash = PLN(80), firmLoan = PLN(10), corpBond = PLN.Zero, equity = PLN.Zero),
      ),
    )
    val world  = init.world.copy(
      flows = init.world.flows.copy(sectorOutputs = Vector.fill(summon[SimParams].sectorDefs.length)(PLN.Zero)),
    )
    val row    = computeRow(world, ledgerFinancialState = ledger, firms = firms)

    valueAt(row, "Adoption_MicroShare") shouldBe shareMetric(1, 2)
    valueAt(row, "Adoption_SmallShare") shouldBe shareMetric(1, 2)
    valueAt(row, "Adoption_MediumShare") shouldBe shareMetric(2, 2)
    valueAt(row, "Adoption_LargeShare") shouldBe shareMetric(1, 2)
    valueAt(row, "Adoption_CashQ1") shouldBe shareMetric(1, 2)
    valueAt(row, "Adoption_CashQ2") shouldBe shareMetric(1, 2)
    valueAt(row, "Adoption_CashQ3") shouldBe shareMetric(2, 2)
    valueAt(row, "Adoption_CashQ4") shouldBe shareMetric(1, 2)
    valueAt(row, "Adoption_DebtQ1") shouldBe shareMetric(1, 2)
    valueAt(row, "Adoption_DebtQ2") shouldBe shareMetric(2, 2)
    valueAt(row, "Adoption_DebtQ3") shouldBe shareMetric(1, 2)
    valueAt(row, "Adoption_DebtQ4") shouldBe shareMetric(1, 2)
  }

  it should "emit household liquidity distribution diagnostics from ledger balances" in {
    def balance(deposit: PLN): LedgerFinancialState.HouseholdBalances =
      LedgerFinancialState.HouseholdBalances(
        demandDeposit = deposit,
        mortgageLoan = PLN.Zero,
        consumerLoan = PLN.Zero,
        equity = PLN.Zero,
      )

    val ledger = initState.ledgerFinancialState.copy(
      households = Vector(
        balance(PLN(-100)),
        balance(PLN.Zero),
        balance(PLN(50)),
        balance(PLN(200)),
      ),
    )
    val row    = computeRow(init.world, ledgerFinancialState = ledger)

    valueAt(row, "HouseholdLiquidity_NetDemandDeposit") shouldBe polandScale(PLN(150))
    valueAt(row, "HouseholdLiquidity_PositiveDemandDeposits") shouldBe polandScale(PLN(250))
    valueAt(row, "HouseholdLiquidity_ImplicitOverdraft") shouldBe polandScale(PLN(100))
    valueAt(row, "HouseholdLiquidity_NegativeDepositCount") shouldBe MetricValue.fromInt(1)
    valueAt(row, "HouseholdLiquidity_NegativeDepositShare") shouldBe shareMetric(1, 4)
    valueAt(row, "HouseholdLiquidity_MinDemandDeposit") shouldBe polandScale(PLN(-100))
    valueAt(row, "HouseholdLiquidity_DepositP01") shouldBe polandScale(PLN(-100))
    valueAt(row, "HouseholdLiquidity_DepositP05") shouldBe polandScale(PLN(-100))
    valueAt(row, "HouseholdLiquidity_DepositP10") shouldBe polandScale(PLN(-100))
    valueAt(row, "HouseholdLiquidity_DepositP25") shouldBe polandScale(PLN.Zero)
    valueAt(row, "HouseholdLiquidity_DepositP50") shouldBe polandScale(PLN(50))
    valueAt(row, "HouseholdLiquidity_DepositP75") shouldBe polandScale(PLN(200))
    valueAt(row, "HouseholdLiquidity_DepositP90") shouldBe polandScale(PLN(200))
    valueAt(row, "HouseholdLiquidity_DepositP95") shouldBe polandScale(PLN(200))
    valueAt(row, "HouseholdLiquidity_DepositP99") shouldBe polandScale(PLN(200))
    valueAt(row, "HouseholdLiquidity_ShortfallFinancing") shouldBe MetricValue.Zero
    valueAt(row, "HouseholdLiquidity_UnmetBasicConsumption") shouldBe MetricValue.Zero
    valueAt(row, "HouseholdLiquidity_DiscretionaryConsumptionCompression") shouldBe MetricValue.Zero
    valueAt(row, "HouseholdLiquidity_ConsumptionShortfall") shouldBe MetricValue.Zero
    valueAt(row, "HouseholdLiquidity_RentArrears") shouldBe MetricValue.Zero
    valueAt(row, "HouseholdLiquidity_MortgageArrears") shouldBe MetricValue.Zero
    valueAt(row, "HouseholdLiquidity_ConsumerDebtArrears") shouldBe MetricValue.Zero
    valueAt(row, "HouseholdLiquidity_TemporaryOverdraft") shouldBe MetricValue.Zero
  }

  it should "emit validation-ready wage and current-account diagnostics" in {
    val world = init.world.copy(
      bop = init.world.bop.copy(currentAccount = PLN(5)),
      flows = init.world.flows.copy(
        monthlyGdpProxy = PLN(100),
        sectorOutputs = Vector.fill(summon[SimParams].sectorDefs.length)(PLN.Zero),
      ),
    )
    val row   = computeRow(world)

    valueAt(row, "MeanEmployedWage") should be > MetricValue.Zero
    valueAt(row, "CurrentAccount") shouldBe polandScale(PLN(5))
    valueAt(row, "CurrentAccountToGdp") shouldBe MetricValue.fromRaw(Share.decimal(5, 2).toLong)
  }

  it should "emit validation-ready credit stock splits and GDP ratios" in {
    val bankFirmLoans = PLN(10) * initState.ledgerFinancialState.banks.length
    val consumerLoans = PLN(2) * initState.ledgerFinancialState.banks.length
    val nbfiLoans     = PLN(6)
    val totalCredit   = bankFirmLoans + consumerLoans + nbfiLoans
    val annualGdp     = PLN(10) * 12
    val ledger        = initState.ledgerFinancialState.copy(
      households = initState.ledgerFinancialState.households.map(_.copy(mortgageLoan = PLN.Zero)),
      banks = initState.ledgerFinancialState.banks.map(_.copy(firmLoan = PLN(10), consumerLoan = PLN(2))),
      funds = initState.ledgerFinancialState.funds.copy(
        nbfi = initState.ledgerFinancialState.funds.nbfi.copy(nbfiLoanStock = nbfiLoans),
      ),
    )
    val world         = init.world.copy(
      flows = init.world.flows.copy(
        monthlyGdpProxy = PLN(10),
        sectorOutputs = Vector.fill(summon[SimParams].sectorDefs.length)(PLN.Zero),
      ),
    )
    val hhAgg         = init.householdAggregates.copy(
      totalConsumerDefault = PLN(7),
      totalConsumerLoanDefault = PLN(3),
      totalLiquidityBridgeChargeOff = PLN(4),
    )
    val row           = computeRow(world, ledger, householdAggregates = hhAgg)

    valueAt(row, "BankFirmLoans") shouldBe polandScale(bankFirmLoans)
    valueAt(row, "ConsumerLoans") shouldBe polandScale(consumerLoans)
    valueAt(row, "ConsumerDefault") shouldBe polandScale(PLN(7))
    valueAt(row, "ConsumerLoanDefault") shouldBe polandScale(PLN(3))
    valueAt(row, "LiquidityBridgeChargeOff") shouldBe polandScale(PLN(4))
    valueAt(row, "NbfiLoanStock") shouldBe polandScale(nbfiLoans)
    valueAt(row, "TotalCreditStock") shouldBe polandScale(totalCredit)
    valueAt(row, "BankFirmLoansToGdp") shouldBe MetricValue.fromRaw((bankFirmLoans / annualGdp).toLong)
    valueAt(row, "ConsumerLoansToGdp") shouldBe MetricValue.fromRaw((consumerLoans / annualGdp).toLong)
    valueAt(row, "NbfiLoansToGdp") shouldBe MetricValue.fromRaw((nbfiLoans / annualGdp).toLong)
    valueAt(row, "TotalCreditToGdp") shouldBe MetricValue.fromRaw((totalCredit / annualGdp).toLong)
  }

  it should "emit household bankruptcy validation fields alongside firm and bank failures" in {
    val row = computeRow(init.world)

    valueAt(row, "FirmDeaths") shouldBe MetricValue.fromInt(init.world.flows.firmDeaths)
    valueAt(row, "HouseholdBankruptcies") shouldBe MetricValue.fromInt(init.householdAggregates.bankrupt)
    valueAt(row, "HouseholdBankruptcyRate") shouldBe MetricValue.fromRaw(init.householdAggregates.bankruptcyRate.toLong)
    valueAt(row, "HouseholdDistress_Current") shouldBe MetricValue.fromInt(init.householdAggregates.distressCurrent)
    valueAt(row, "HouseholdDistress_ActiveShare") shouldBe MetricValue.fromRaw(init.householdAggregates.distressActiveShare(init.households.length).toLong)
    valueAt(row, "BankFailures") shouldBe MetricValue.fromInt(init.banks.count(_.failed))
  }

  it should "emit bank failure trigger diagnostics" in {
    val diagnostics = BankFailureDiagnostics(
      newNegativeCapital = 1,
      newCarBreach = 2,
      newLiquidityBreach = 3,
      allFailedFallback = 1,
      invariantViolation = 0,
      firstNewReasonCode = 3,
      firstNewBankId = 4,
    )
    val world       = init.world.copy(
      flows = init.world.flows.copy(
        sectorOutputs = Vector.fill(summon[SimParams].sectorDefs.length)(PLN.Zero),
        bankFailure = diagnostics,
      ),
    )
    val row         = computeRow(world)

    valueAt(row, "BankFailure_NewNegativeCapital") shouldBe MetricValue.fromInt(1)
    valueAt(row, "BankFailure_NewCarBreach") shouldBe MetricValue.fromInt(2)
    valueAt(row, "BankFailure_NewLiquidityBreach") shouldBe MetricValue.fromInt(3)
    valueAt(row, "BankFailure_AllFailedFallback") shouldBe MetricValue.fromInt(1)
    valueAt(row, "BankFailure_InvariantViolation") shouldBe MetricValue.Zero
    valueAt(row, "BankFailure_FirstNewReasonCode") shouldBe MetricValue.fromInt(3)
    valueAt(row, "BankFailure_FirstNewBankId") shouldBe MetricValue.fromInt(4)
  }

  it should "emit bank reconciliation residual impact diagnostics" in {
    val diagnostics = BankReconciliationDiagnostics(
      targetBankId = 2,
      capitalResidual = PLN(-7),
      targetCapitalBefore = PLN(100),
      targetCapitalAfter = PLN(93),
      targetCarBefore = Multiplier.decimal(12, 2),
      targetCarAfter = Multiplier.decimal(9, 2),
      residualToTargetCapital = Scalar.decimal(7, 2),
      materialResidual = 1,
      crossedFailureThreshold = 1,
      postResidualReasonCode = 2,
    )
    val world       = init.world.copy(
      flows = init.world.flows.copy(
        sectorOutputs = Vector.fill(summon[SimParams].sectorDefs.length)(PLN.Zero),
        bankReconciliation = diagnostics,
      ),
    )
    val row         = computeRow(world)

    valueAt(row, "BankReconciliation_TargetBankId") shouldBe MetricValue.fromInt(2)
    valueAt(row, "BankReconciliation_CapitalResidual") shouldBe polandScale(PLN(-7))
    valueAt(row, "BankReconciliation_TargetCapitalBefore") shouldBe polandScale(PLN(100))
    valueAt(row, "BankReconciliation_TargetCapitalAfter") shouldBe polandScale(PLN(93))
    valueAt(row, "BankReconciliation_TargetCarBefore") shouldBe MetricValue.fromRaw(Multiplier.decimal(12, 2).toLong)
    valueAt(row, "BankReconciliation_TargetCarAfter") shouldBe MetricValue.fromRaw(Multiplier.decimal(9, 2).toLong)
    valueAt(row, "BankReconciliation_ResidualToTargetCapital") shouldBe MetricValue.fromRaw(Scalar.decimal(7, 2).toLong)
    valueAt(row, "BankReconciliation_MaterialResidual") shouldBe MetricValue.fromInt(1)
    valueAt(row, "BankReconciliation_CrossedFailureThreshold") shouldBe MetricValue.fromInt(1)
    valueAt(row, "BankReconciliation_PostResidualReasonCode") shouldBe MetricValue.fromInt(2)
  }

  it should "emit annualized deficit-to-GDP consistently with fiscal rules" in {
    val world = init.world.copy(
      gov = init.world.gov.copy(monthly = init.world.gov.monthly.copy(deficit = PLN(3))),
      flows = init.world.flows.copy(
        monthlyGdpProxy = PLN(100),
        sectorOutputs = Vector.fill(summon[SimParams].sectorDefs.length)(PLN.Zero),
      ),
    )
    val row   = computeRow(world)

    valueAt(row, "DeficitToGdp") shouldBe MetricValue.fromRaw(Share.decimal(3, 2).toLong)
  }

  it should "emit ready fiscal revenue and outlay diagnostics" in {
    val world = init.world.copy(
      gov = init.world.gov.copy(
        monthly = init.world.gov.monthly.copy(
          taxRevenue = PLN(30),
          govDividendRevenue = PLN(5),
          deficit = PLN(4),
          unempBenefitSpend = PLN.Zero,
          socialTransferSpend = PLN.Zero,
          govCurrentSpend = PLN(20),
          govCapitalSpend = PLN(10),
          debtServiceSpend = PLN(2),
          euCofinancing = PLN.Zero,
        ),
      ),
      flows = init.world.flows.copy(
        monthlyGdpProxy = PLN(100),
        sectorOutputs = Vector.fill(summon[SimParams].sectorDefs.length)(PLN.Zero),
      ),
    )
    val row   = computeRow(world)

    valueAt(row, "GovTaxRevenue") shouldBe polandScale(PLN(30))
    valueAt(row, "GovDividendRevenue") shouldBe polandScale(PLN(5))
    valueAt(row, "GovTotalRevenue") shouldBe polandScale(PLN(35))
    valueAt(row, "GovDeficit") shouldBe polandScale(PLN(4))
    valueAt(row, "GovRevenueToGdp") shouldBe MetricValue.fromRaw(Share.decimal(35, 2).toLong)
    valueAt(row, "GovOutlaysToGdp") shouldBe MetricValue.fromRaw(Share.decimal(32, 2).toLong)
  }

  it should "emit total GFCF and investment-to-GDP ratios" in {
    val world = init.world.copy(
      gov = init.world.gov.copy(
        monthly = init.world.gov.monthly.copy(
          govCapitalSpend = PLN(3),
          euProjectCapital = PLN(5),
        ),
      ),
      real = init.world.real.copy(
        grossInvestment = PLN(10),
        aggGreenInvestment = PLN(2),
      ),
      flows = init.world.flows.copy(
        monthlyGdpProxy = PLN(100),
        sectorOutputs = Vector.fill(summon[SimParams].sectorDefs.length)(PLN.Zero),
      ),
    )
    val row   = computeRow(world)

    valueAt(row, "TotalGrossFixedCapitalFormation") shouldBe polandScale(PLN(20))
    valueAt(row, "PrivateGrossInvestmentToGdp") shouldBe MetricValue.fromRaw(Share.decimal(10, 2).toLong)
    valueAt(row, "GrossFixedCapitalFormationToGdp") shouldBe MetricValue.fromRaw(Share.decimal(20, 2).toLong)
  }

  it should "reject malformed sector output vectors before output indexing" in {
    val err = intercept[IllegalArgumentException]:
      computeRow(init.world.copy(flows = init.world.flows.copy(sectorOutputs = Vector(PLN(1)))))

    err.getMessage.should(include("FlowState.sectorOutputs"))
  }

  it should "reject missing sector output payloads before output indexing" in {
    val err = intercept[IllegalArgumentException]:
      computeRow(init.world.copy(flows = init.world.flows.copy(sectorOutputs = Vector.empty)), preserveSectorOutputs = true)

    err.getMessage.should(include("FlowState.sectorOutputs"))
    err.getMessage.should(include(s"${summon[SimParams].sectorDefs.length} entries"))
    err.getMessage.should(include("got 0"))
  }

  it should "source ledger-owned household, public, and fund stock columns from LedgerFinancialState" in {
    val ledger = initState.ledgerFinancialState.copy(
      households = initState.ledgerFinancialState.households.map(_.copy(mortgageLoan = PLN(12), equity = PLN(11))),
      banks = initState.ledgerFinancialState.banks.map(_.copy(govBondAfs = PLN(10), govBondHtm = PLN(20))),
      government = initState.ledgerFinancialState.government.copy(govBondOutstanding = PLN(123)),
      foreign = initState.ledgerFinancialState.foreign.copy(govBondHoldings = PLN(45)),
      nbp = initState.ledgerFinancialState.nbp.copy(govBondHoldings = PLN(67)),
      funds = initState.ledgerFinancialState.funds.copy(
        jstCash = PLN(89),
        zusCash = PLN(90),
        nfzCash = PLN(91),
        ppkGovBondHoldings = PLN(92),
        fpCash = PLN(93),
        pfronCash = PLN(94),
        fgspCash = PLN(95),
        quasiFiscal = initState.ledgerFinancialState.funds.quasiFiscal.copy(
          bondsOutstanding = PLN(96),
          loanPortfolio = PLN(97),
          nbpHoldings = PLN(98),
        ),
      ),
    )
    val row    = computeRow(init.world, ledger)

    valueAt(row, "HhEquityWealth") shouldBe polandScale(PLN(11) * initState.ledgerFinancialState.households.length)
    valueAt(row, "MortgageStock") shouldBe polandScale(PLN(12) * initState.ledgerFinancialState.households.length)
    valueAt(row, "BondsOutstanding") shouldBe polandScale(PLN(123))
    valueAt(row, "BankBondHoldings") shouldBe polandScale(PLN(30) * initState.ledgerFinancialState.banks.length)
    valueAt(row, "ForeignBondHoldings") shouldBe polandScale(PLN(45))
    valueAt(row, "NbpBondHoldings") shouldBe polandScale(PLN(67))
    valueAt(row, "QfBondsOutstanding") shouldBe polandScale(PLN(96))
    valueAt(row, "QfLoanPortfolio") shouldBe polandScale(PLN(97))
    valueAt(row, "QfNbpHoldings") shouldBe polandScale(PLN(98))
    valueAt(row, "JstDeposits") shouldBe polandScale(PLN(89))
    valueAt(row, "FusBalance") shouldBe polandScale(PLN(90))
    valueAt(row, "NfzBalance") shouldBe polandScale(PLN(91))
    valueAt(row, "PpkBondHoldings") shouldBe polandScale(PLN(92))
    valueAt(row, "FpBalance") shouldBe polandScale(PLN(93))
    valueAt(row, "PfronBalance") shouldBe polandScale(PLN(94))
    valueAt(row, "FgspBalance") shouldBe polandScale(PLN(95))
  }

  it should "map regional HPI columns by market identity and preserve schema order" in {
    val regions     = init.world.real.housing.regions.getOrElse(fail("expected initialized regional housing data"))
    val hpiByMarket = Map(
      HousingConfig.RegionalMarket.Warsaw       -> BigDecimal("101.0"),
      HousingConfig.RegionalMarket.Krakow       -> BigDecimal("102.0"),
      HousingConfig.RegionalMarket.Wroclaw      -> BigDecimal("103.0"),
      HousingConfig.RegionalMarket.Gdansk       -> BigDecimal("104.0"),
      HousingConfig.RegionalMarket.Lodz         -> BigDecimal("105.0"),
      HousingConfig.RegionalMarket.Poznan       -> BigDecimal("106.0"),
      HousingConfig.RegionalMarket.RestOfPoland -> BigDecimal("107.0"),
    )
    val updated     = regions.reverse.map(region => region.copy(priceIndex = priceIndexBD(hpiByMarket(region.market))))
    val updatedRow  = computeRow(init.world.copy(real = init.world.real.copy(housing = init.world.real.housing.copy(regions = Some(updated)))))

    valueAt(updatedRow, "WawHpi") shouldBe MetricValue(101)
    valueAt(updatedRow, "KrkHpi") shouldBe MetricValue(102)
    valueAt(updatedRow, "WroHpi") shouldBe MetricValue(103)
    valueAt(updatedRow, "GdnHpi") shouldBe MetricValue(104)
    valueAt(updatedRow, "LdzHpi") shouldBe MetricValue(105)
    valueAt(updatedRow, "PozHpi") shouldBe MetricValue(106)
    valueAt(updatedRow, "RestHpi") shouldBe MetricValue(107)
  }

  it should "reject malformed regional housing state shapes before output indexing" in {
    val regions = init.world.real.housing.regions.getOrElse(fail("expected initialized regional housing data"))
    val err     = intercept[IllegalArgumentException]:
      init.world.real.housing.copy(regions = Some(regions.dropRight(1)))

    err.getMessage.should(include("HousingMarket.State.regions must contain 7 entries covering markets"))
  }
