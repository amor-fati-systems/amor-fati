package com.boombustgroup.amorfati.diagnostics

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path

class HouseholdCreditStressCalibrationExportSpec extends AnyFlatSpec with Matchers:

  import HouseholdCreditStressCalibrationExport.*

  "HouseholdCreditStressCalibrationExport" should "parse CLI run controls" in {
    parseArgs(Vector("--seed-start", "2", "--seeds", "7", "--months", "84", "--run-id", "stress-spec", "--out", "target/stress-spec")) shouldBe
      Right(Config(seedStart = 2L, seeds = 7, months = 84, runId = "stress-spec", out = Path.of("target/stress-spec")))
  }

  it should "cover the household credit-stress metric contract" in {
    Targets.map(_.id).toSet should contain allOf (
      "ConsumerLoansToGdp",
      "MortgageLoansToGdp",
      "ConsumerDebtServiceToIncome",
      "MortgageDebtServiceToIncome",
      "MortgagePrincipalToIncome",
      "MortgageInterestToIncome",
      "ConsumerDefaultToConsumerLoans",
      "LiquidityBridgeChargeOffToConsumerLoans",
      "LiquidityBridgeChargeOffShareOfConsumerDefault",
      "MortgageDefaultToMortgageLoans",
      "PositiveDepositsToMonthlyIncome",
      "MedianDepositToMeanMonthlyIncome",
      "NegativeDepositShare",
      "DebtArrearsToShortfall",
      "ShortfallToIncome",
      "ShortfallToApprovedOrigination",
    )
    Targets.map(_.guardrailClass).toSet should contain allOf (
      GuardrailClass.HardInvariant,
      GuardrailClass.SoftCalibrationWarning,
      GuardrailClass.ExploratoryDiagnostic,
    )
  }

  it should "map hard failures, soft warnings, and exploratory diagnostics to distinct statuses" in {
    val hardInvariant = Targets.find(_.id == "NegativeDepositShare").get
    val softBand      = Targets.find(_.id == "ShortfallToApprovedOrigination").get
    val exploratory   = Targets.find(_.id == "DebtArrearsToShortfall").get

    evaluate(ObservedValue.Finite(BigDecimal("0.01")), hardInvariant) shouldBe Status.Fail
    evaluate(ObservedValue.Finite(BigDecimal("1.50")), softBand) shouldBe Status.Pass
    evaluate(ObservedValue.Finite(BigDecimal("3.00")), softBand) shouldBe Status.Warn
    evaluate(ObservedValue.Finite(BigDecimal("0.80")), exploratory) shouldBe Status.Info
    evaluate(ObservedValue.Finite(BigDecimal("1.20")), exploratory) shouldBe Status.Warn
  }

  it should "render seed and summary CSV artifacts with calibration metadata" in {
    val target  = Targets.find(_.id == "ShortfallToIncome").get
    val rows    = Vector(
      SeedMetric("stress-spec", 1L, 60, target, ObservedValue.Finite(BigDecimal("0.02")), Status.Pass),
      SeedMetric("stress-spec", 2L, 60, target, ObservedValue.Finite(BigDecimal("0.08")), Status.Warn),
    )
    val summary = summarize(Config(runId = "stress-spec", seeds = 2, months = 60), rows)
    val seedCsv = renderSeedMetricsCsv(rows)
    val sumCsv  = renderSummaryCsv(summary)
    val report  = renderReport(Config(seedStart = 2L, runId = "stress-spec", seeds = 2, months = 60), summary)

    seedCsv.linesIterator.next() shouldBe "RunId;Seed;Months;Metric;Label;Value;Unit;GuardrailClass;Vintage;Lower;Upper;Status;SourceNote;Interpretation"
    seedCsv should include("ShortfallToIncome")
    seedCsv should include("2026-04-30 model-start baseline")
    sumCsv.linesIterator.next() shouldBe "RunId;Months;Seeds;Metric;Label;Mean;Min;Max;Unit;GuardrailClass;Vintage;Lower;Upper;Status;SourceNote;Interpretation"
    sumCsv should include("SOFT_CALIBRATION_WARNING")
    report should include("--seed-start 2")
  }

end HouseholdCreditStressCalibrationExportSpec
