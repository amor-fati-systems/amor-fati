package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.init.{InitRandomness, WorldInit}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path

class BankBalanceSheetBenchmarkExportSpec extends AnyFlatSpec with Matchers:

  import BankBalanceSheetBenchmarkExport.*
  import com.boombustgroup.amorfati.types.*

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
    val bankRow = BankRow(
      runId = "bank-spec",
      seed = 1L,
      bankId = 0,
      bankName = "PKO BP",
      capital = PLN(10),
      assets = PLN(100),
      deposits = PLN(80),
      totalCredit = PLN(50),
      govBondHoldings = PLN(20),
      govBondShareOfAssets = BigDecimal("0.20"),
      polishBankLevyTaxableAssets = PLN(30),
      polishBankLevyTaxableAssetsShare = BigDecimal("0.30"),
      capitalAdequacyRatio = BigDecimal("0.20"),
      effectiveMinCar = BigDecimal("0.125"),
      carBuffer = BigDecimal("0.075"),
      lcr = BigDecimal("1.20"),
      nsfr = BigDecimal("1.10"),
      creditShare = BigDecimal("0.25"),
      depositShare = BigDecimal("0.30"),
      assetShare = BigDecimal("0.20"),
    )

    val seedCsv   = renderSeedMetricsCsv(rows)
    val sumCsv    = renderSummaryCsv(summary)
    val targetCsv = renderTargetsCsv(Targets)
    val bankCsv   = renderBankRowsCsv(Vector(bankRow))
    val report    = renderReport(Config(seedStart = 2L, runId = "bank-spec", seeds = 2), summary)

    seedCsv.linesIterator.next() shouldBe "RunId;Seed;Metric;Label;Value;Unit;GuardrailClass;Vintage;Lower;Upper;Status;SourceNote;Interpretation"
    seedCsv should include("ReserveToDeposits")
    sumCsv.linesIterator.next() shouldBe "RunId;Seeds;Metric;Label;Mean;Min;Max;Unit;GuardrailClass;Vintage;Lower;Upper;Status;SourceNote;Interpretation"
    targetCsv should include("2026-04-30 model-start baseline")
    bankCsv.linesIterator.next() shouldBe
      "RunId;Seed;BankId;BankName;Capital;Assets;Deposits;TotalCredit;GovBondHoldings;GovBondShareOfAssets;PolishBankLevyTaxableAssets;PolishBankLevyTaxableAssetsShare;CapitalAdequacyRatio;EffectiveMinCar;CarBuffer;Lcr;Nsfr;CreditShare;DepositShare;AssetShare"
    bankCsv should include("PKO BP")
    report should include("--seed-start 2")
    report should include("ReserveToDeposits")
    report should include(BankCapitalSourceStatement)
    report should include("unsupported persisted diagnostic stock")
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

  private def metricValue(rows: Vector[SeedMetric], id: String): ObservedValue =
    rows.find(_.target.id == id).map(_.value).getOrElse(fail(s"Missing metric $id"))

end BankBalanceSheetBenchmarkExportSpec
