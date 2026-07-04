package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.config.{OpeningBankBalanceProfileBridge, SimParams}
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.init.{InitRandomness, WorldInit}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path

class BankBalanceSheetBenchmarkExportSpec extends AnyFlatSpec with Matchers:

  import BankBalanceSheetBenchmarkExport.*
  import com.boombustgroup.amorfati.types.*

  private def tsv(fields: String*): String =
    fields.mkString("\t")

  "BankBalanceSheetBenchmarkExport" should "parse CLI run controls" in {
    parseArgs(Vector("--seed-start", "2", "--seeds", "7", "--run-id", "bank-spec", "--out", "target/bank-spec")) shouldBe
      Right(Config(seedStart = 2L, seeds = 7, runId = "bank-spec", out = Path.of("target/bank-spec")))
  }

  it should "cover the initial bank balance-sheet benchmark contract" in {
    Targets.map(_.id).toSet should contain allOf (
      "CapitalToAssets",
      "AggregateCar",
      "MinimumCar",
      "MinimumEffectiveCarBuffer",
      "BanksBelowEffectiveCar",
      "FirmLoansToGdp",
      "ConsumerLoansToGdp",
      "MortgageLoansToGdp",
      "TotalBankLoansToGdp",
      "DepositsToLoans",
      "LiquidAssetsToDeposits",
      "ReserveToDeposits",
      "DepositSplitCoverage",
      "DemandDepositShare",
      "InitialEclAllowanceToLoans",
      "EclStagedShareOfCoveredLoans",
      "AggregateNplRatio",
      "LargestBankCreditShare",
      "BankCreditHhi",
    )
    Targets.map(_.guardrailClass).toSet should contain allOf (
      GuardrailClass.HardInvariant,
      GuardrailClass.SoftCalibrationWarning,
      GuardrailClass.ExploratoryDiagnostic,
    )
  }

  it should "map opening hard failures and calibration warnings to distinct statuses" in {
    val hardInvariant = Targets.find(_.id == "DepositSplitCoverage").get
    val softBand      = Targets.find(_.id == "ReserveToDeposits").get
    val exploratory   = Targets.find(_.id == "BankCreditHhi").get

    evaluate(ObservedValue.Finite(BigDecimal("0.00")), hardInvariant) shouldBe Status.Fail
    evaluate(ObservedValue.Finite(BigDecimal("1.00")), hardInvariant) shouldBe Status.Pass
    evaluate(ObservedValue.Finite(BigDecimal("0.035")), softBand) shouldBe Status.Pass
    evaluate(ObservedValue.Finite(BigDecimal("0.00")), softBand) shouldBe Status.Warn
    evaluate(ObservedValue.Finite(BigDecimal("0.15")), exploratory) shouldBe Status.Info
    evaluate(ObservedValue.Finite(BigDecimal("0.25")), exploratory) shouldBe Status.Warn
  }

  it should "summarize a metric with the worst per-seed status instead of the mean status" in {
    val target = Targets.find(_.id == "DepositSplitCoverage").get
    val rows   = Vector(
      SeedMetric("bank-spec", 1L, target, ObservedValue.Finite(BigDecimal("0.00")), Status.Fail),
      SeedMetric("bank-spec", 2L, target, ObservedValue.Finite(BigDecimal("2.00")), Status.Fail),
    )

    val summary = summarize(Config(runId = "bank-spec", seeds = 2), rows).find(_.target.id == "DepositSplitCoverage").get

    summary.mean shouldBe ObservedValue.Finite(BigDecimal("1.00"))
    summary.status shouldBe Status.Fail
  }

  it should "render seed, summary, target, bank-row and report artifacts" in {
    val target  = Targets.find(_.id == "ReserveToDeposits").get
    val rows    = Vector(
      SeedMetric("bank-spec", 1L, target, ObservedValue.Finite(BigDecimal("0.035")), Status.Pass),
      SeedMetric("bank-spec", 2L, target, ObservedValue.Finite(BigDecimal("0.000")), Status.Warn),
    )
    val summary = summarize(Config(runId = "bank-spec", seeds = 2), rows)
    val bankRow = sampleBankRow()

    val seedTsv   = renderSeedMetricsTsv(rows)
    val sumTsv    = renderSummaryTsv(summary)
    val targetTsv = renderTargetsTsv(Targets)
    val bankTsv   = renderBankRowsTsv(Vector(bankRow))
    val report    = renderReport(Config(seedStart = 2L, runId = "bank-spec", seeds = 2), summary)

    seedTsv.linesIterator.next() shouldBe tsv(
      "RunId",
      "Seed",
      "Metric",
      "Label",
      "Value",
      "Unit",
      "GuardrailClass",
      "Vintage",
      "Lower",
      "Upper",
      "Status",
      "SourceNote",
      "Interpretation",
    )
    seedTsv should include("ReserveToDeposits")
    sumTsv.linesIterator.next() shouldBe tsv(
      "RunId",
      "Seeds",
      "Metric",
      "Label",
      "Mean",
      "Min",
      "Max",
      "Unit",
      "GuardrailClass",
      "Vintage",
      "Lower",
      "Upper",
      "Status",
      "SourceNote",
      "Interpretation",
    )
    targetTsv should include("2026-04-30 model-start baseline")
    bankTsv.linesIterator.next() shouldBe
      tsv(
        "RunId",
        "Seed",
        "BankId",
        "BankName",
        "Capital",
        "Assets",
        "Deposits",
        "FirmLoans",
        "ConsumerLoans",
        "MortgageLoans",
        "TotalCredit",
        "GovBondHoldings",
        "GovBondShareOfAssets",
        "PolishBankLevyTaxableAssets",
        "PolishBankLevyTaxableAssetsShare",
        "CapitalAdequacyRatio",
        "EffectiveMinCar",
        "CarBuffer",
        "Lcr",
        "Nsfr",
        "CreditShare",
        "DepositShare",
        "AssetShare",
        "FirmLoanShare",
        "ConsumerLoanShare",
        "MortgageLoanShare",
        "GovBondSectorShare",
        "ProfileBridgeStatus",
        "RelationshipWeightPrior",
        "ProfileDepositsTarget",
        "ProfileDepositsTargetShare",
        "ProfileDepositsDelta",
        "ProfileFirmLoansTarget",
        "ProfileFirmLoansTargetShare",
        "ProfileFirmLoansDelta",
        "ProfileConsumerLoansTarget",
        "ProfileConsumerLoansTargetShare",
        "ProfileConsumerLoansDelta",
        "ProfileMortgageLoansTarget",
        "ProfileMortgageLoansTargetShare",
        "ProfileMortgageLoansDelta",
        "ProfileGovBondsTarget",
        "ProfileGovBondsTargetShare",
        "ProfileGovBondsDelta",
      )
    bankTsv should include("PKO BP")
    report should include("--seed-start 2")
    report should include("ReserveToDeposits")
    report should include(BankCapitalSourceStatement)
    report should include("unsupported persisted diagnostic stock")
  }

  it should "escape special characters in benchmark TSV payload cells" in {
    val target    = TargetBand(
      id = "Metric\t\"Id\"",
      label = "Label\t\"quoted\"",
      unit = "unit\nline",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = "vintage\r2026",
      lower = Some(BigDecimal("0.01")),
      upper = None,
      sourceNote = "source\nnote",
      interpretation = "interpretation\rnote",
    )
    val seedTsv   = renderSeedMetricsTsv(Vector(SeedMetric("run\t\"id\"", 1L, target, ObservedValue.Finite(BigDecimal("0.02")), Status.Warn)))
    val sumTsv    =
      renderSummaryTsv(Vector(SummaryMetric("run\t\"id\"", 1, target, ObservedValue.Finite(BigDecimal("0.02")), Some(BigDecimal("0.01")), None, Status.Warn)))
    val targetTsv = renderTargetsTsv(Vector(target))
    val bankTsv   = renderBankRowsTsv(
      Vector(
        sampleBankRow(
          runId = "run\t\"id\"",
          bankName = "Bank\t\"Name\"\nSA",
        ),
      ),
    )

    Vector(seedTsv, sumTsv, targetTsv).foreach: rendered =>
      rendered should include("\"Metric\t\"\"Id\"\"\"")
      rendered should include("\"Label\t\"\"quoted\"\"\"")
      rendered should include("\"unit\nline\"")
      rendered should include("\"vintage\r2026\"")
      rendered should include("\"source\nnote\"")
      rendered should include("\"interpretation\rnote\"")
    seedTsv should include("\"run\t\"\"id\"\"\"")
    sumTsv should include("\"run\t\"\"id\"\"\"")
    bankTsv should include("\"run\t\"\"id\"\"\"")
    bankTsv should include("\"Bank\t\"\"Name\"\"\nSA\"")
  }

  it should "source benchmark capital only from BankState.capital" in {
    given SimParams = SimParams.defaults

    val init         = WorldInit.initialize(InitRandomness.Contract.fromSeed(1L))
    val shiftedBanks = init.banks.zipWithIndex.map: (bank, idx) =>
      bank.copy(capital = PLN(100000000L + idx.toLong * 1000000L))
    val shiftedInit  = init.copy(banks = shiftedBanks)

    val (metrics, bankRows) = computeSeed(Config(runId = "bank-spec", seeds = 1), 1L, shiftedInit)
    val bankBalances        = shiftedInit.ledgerFinancialState.banks
    val bankStocks          = bankBalances.map(LedgerFinancialState.projectBankFinancialStocks)
    val corpBondHoldings    = Banking.bankCorpBondHoldingsFromVector(bankBalances.map(_.corpBond))
    val aggregate           = Banking.aggregateFromBankStocks(shiftedBanks, bankStocks, corpBondHoldings)
    val totalAssets         = bankRows.map(_.assets).sumPln

    bankRows.map(_.capital) shouldBe shiftedBanks.map(_.capital)
    metricValue(metrics, "CapitalToAssets") shouldBe ObservedValue.Finite(BigDecimal(aggregate.capital.toLong) / BigDecimal(totalAssets.toLong))
    metricValue(metrics, "AggregateCar") shouldBe ObservedValue.Finite(BigDecimal(aggregate.car.toLong) / BigDecimal(FixedPointBase.Scale))
  }

  it should "derive market-share columns from initialized bank stocks" in {
    given SimParams = SimParams.defaults

    val init           = WorldInit.initialize(InitRandomness.Contract.fromSeed(1L))
    val (_, bankRows)  = computeSeed(Config(runId = "bank-spec", seeds = 1), 1L, init)
    val totalCredit    = bankRows.map(_.totalCredit).sumPln
    val totalDeposits  = bankRows.map(_.deposits).sumPln
    val totalAssets    = bankRows.map(_.assets).sumPln
    val totalFirmLoans = bankRows.map(_.firmLoans).sumPln
    val totalConsumer  = bankRows.map(_.consumerLoans).sumPln
    val totalMortgages = bankRows.map(_.mortgageLoans).sumPln
    val totalGovBonds  = bankRows.map(_.govBondHoldings).sumPln
    val pekao          = bankRows.find(_.bankName == "Pekao").getOrElse(fail("Missing Pekao benchmark row"))
    val bpsCoop        = bankRows.find(_.bankName == "BPS/Coop").getOrElse(fail("Missing BPS/Coop benchmark row"))

    bankRows.foreach: row =>
      row.creditShare shouldBe BigDecimal(row.totalCredit.toLong) / BigDecimal(totalCredit.toLong)
      row.depositShare shouldBe BigDecimal(row.deposits.toLong) / BigDecimal(totalDeposits.toLong)
      row.assetShare shouldBe BigDecimal(row.assets.toLong) / BigDecimal(totalAssets.toLong)
      row.firmLoanShare shouldBe BigDecimal(row.firmLoans.toLong) / BigDecimal(totalFirmLoans.toLong)
      row.consumerLoanShare shouldBe BigDecimal(row.consumerLoans.toLong) / BigDecimal(totalConsumer.toLong)
      row.mortgageLoanShare shouldBe BigDecimal(row.mortgageLoans.toLong) / BigDecimal(totalMortgages.toLong)
      row.govBondSectorShare shouldBe BigDecimal(row.govBondHoldings.toLong) / BigDecimal(totalGovBonds.toLong)

    pekao.profileBridgeStatus shouldBe "EMPIRICAL_PROXY_RUNTIME_TARGET"
    pekao.profileDepositsTarget.get should be > PLN.Zero
    pekao.profileDepositsDelta shouldBe pekao.profileDepositsTarget.map(pekao.deposits - _)
    bpsCoop.profileBridgeStatus shouldBe "EMPIRICAL_PROXY_RUNTIME_TARGET"
    bpsCoop.profileConsumerLoansTarget.get should be > PLN.Zero
    bpsCoop.profileConsumerLoansDelta shouldBe bpsCoop.profileConsumerLoansTarget.map(bpsCoop.consumerLoans - _)
  }

  it should "use the opening profile rows supplied with the initialized world" in {
    given SimParams = SimParams.defaults

    val customRows = OpeningBankBalanceProfileBridge.Rows.map:
      case row if row.runtimeBankName == "Pekao" =>
        row.copy(
          bridgeStatus = "TEST_COMPLETE_RUNTIME_PROFILE",
          depositShare = Some(BigDecimal("0.000001")),
        )
      case row                                   => row
    val init       = WorldInit.initialize(InitRandomness.Contract.fromSeed(1L), openingBankProfileRows = customRows)
    val (_, rows)  = computeSeed(Config(runId = "bank-spec", seeds = 1), 1L, init, customRows)
    val pekao      = rows.find(_.bankName == "Pekao").getOrElse(fail("Missing Pekao benchmark row"))

    pekao.profileBridgeStatus shouldBe "TEST_COMPLETE_RUNTIME_PROFILE"
    pekao.profileDepositsTargetShare shouldBe Some(BigDecimal("0.000001"))
    pekao.profileDepositsDelta shouldBe pekao.profileDepositsTarget.map(pekao.deposits - _)
  }

  private def sampleBankRow(runId: String = "bank-spec", seed: Long = 1L, bankId: Int = 0, bankName: String = "PKO BP"): BankRow =
    BankRow(
      runId = runId,
      seed = seed,
      bankId = bankId,
      bankName = bankName,
      capital = PLN(10),
      assets = PLN(100),
      deposits = PLN(80),
      firmLoans = PLN(20),
      consumerLoans = PLN(10),
      mortgageLoans = PLN(20),
      totalCredit = PLN(50),
      govBondHoldings = PLN(20),
      govBondShareOfAssets = Share.decimal(20, 2),
      polishBankLevyTaxableAssets = PLN(30),
      polishBankLevyTaxableAssetsShare = Share.decimal(30, 2),
      capitalAdequacyRatio = BigDecimal("0.20"),
      effectiveMinCar = BigDecimal("0.125"),
      carBuffer = BigDecimal("0.075"),
      lcr = BigDecimal("1.20"),
      nsfr = BigDecimal("1.10"),
      creditShare = BigDecimal("0.25"),
      depositShare = BigDecimal("0.30"),
      assetShare = BigDecimal("0.20"),
      firmLoanShare = BigDecimal("0.10"),
      consumerLoanShare = BigDecimal("0.15"),
      mortgageLoanShare = BigDecimal("0.20"),
      govBondSectorShare = BigDecimal("0.25"),
      profileBridgeStatus = "EMPIRICAL_PROXY_RUNTIME_TARGET",
      relationshipWeightPrior = Some(BigDecimal("0.175")),
      profileDepositsTarget = Some(PLN(75)),
      profileDepositsTargetShare = Some(BigDecimal("0.29")),
      profileDepositsDelta = Some(PLN(5)),
      profileFirmLoansTarget = Some(PLN(19)),
      profileFirmLoansTargetShare = Some(BigDecimal("0.18")),
      profileFirmLoansDelta = Some(PLN(1)),
      profileConsumerLoansTarget = Some(PLN(12)),
      profileConsumerLoansTargetShare = Some(BigDecimal("0.16")),
      profileConsumerLoansDelta = Some(PLN(-2)),
      profileMortgageLoansTarget = Some(PLN(17)),
      profileMortgageLoansTargetShare = Some(BigDecimal("0.14")),
      profileMortgageLoansDelta = Some(PLN(3)),
      profileGovBondsTarget = None,
      profileGovBondsTargetShare = None,
      profileGovBondsDelta = None,
    )

  private def metricValue(rows: Vector[SeedMetric], id: String): ObservedValue =
    rows.find(_.target.id == id).map(_.value).getOrElse(fail(s"Missing metric $id"))

end BankBalanceSheetBenchmarkExportSpec
