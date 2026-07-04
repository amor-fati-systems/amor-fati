package com.boombustgroup.amorfati.diagnostics

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path

class HouseholdCreditStressCalibrationExportSpec extends AnyFlatSpec with Matchers:

  import HouseholdCreditStressCalibrationExport.*

  private def tsv(fields: String*): String =
    fields.mkString("\t")

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
      "LiquidityBridgeChargeOffShareOfHouseholdCreditWriteOff",
      "MortgageDefaultToMortgageLoans",
      "HouseholdBankruptcyShare",
      "PositiveDepositsToMonthlyIncome",
      "MedianDepositToMeanMonthlyIncome",
      "NegativeDepositShare",
      "DebtArrearsToShortfall",
      "UnmetBasicConsumptionToIncome",
      "DiscretionaryConsumptionCompressionToIncome",
      "ShortfallToIncome",
      "ShortfallToApprovedOrigination",
      "RejectedConsumerCreditDemandToApprovedOrigination",
      "RejectedConsumerCreditDemandToShortfall",
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

  it should "stream seed metric windows without retaining monthly states" in {
    val target       = Targets.find(_.id == "DebtArrearsToShortfall").get
    val observations = Vector(
      MetricObservation(1, ObservedValue.Finite(BigDecimal("1.0"))),
      MetricObservation(2, ObservedValue.Finite(BigDecimal("3.0"))),
      MetricObservation(3, ObservedValue.Finite(BigDecimal("5.0"))),
      MetricObservation(4, ObservedValue.Finite(BigDecimal("4.0"))),
    )

    val accumulator = observations.foldLeft(MetricAccumulator.Empty): (acc, observation) =>
      acc.observe(observation, windowSize = 3)
    val rows        = accumulator.toSeedMetrics(Config(runId = "stress-spec", months = 4), 7L, target, 3).toOption.get

    val terminal = rows.find(_.observationWindow == ObservationWindow.Terminal).get
    val peak     = rows.find(_.observationWindow == ObservationWindow.PeakMonthly).get
    val rolling  = rows.find(_.observationWindow == ObservationWindow.PeakRolling3).get

    terminal.observationMonth shouldBe 4
    terminal.value.finite shouldBe Some(BigDecimal("4.0"))
    peak.observationMonth shouldBe 3
    peak.value.finite shouldBe Some(BigDecimal("5.0"))
    rolling.observationMonth shouldBe 4
    rolling.value.finite shouldBe Some(BigDecimal("4.0"))
  }

  it should "render seed and summary TSV artifacts with calibration metadata" in {
    val target  = Targets.find(_.id == "ShortfallToIncome").get
    val rows    = Vector(
      SeedMetric("stress-spec", 1L, 60, ObservationWindow.Terminal, 60, target, ObservedValue.Finite(BigDecimal("0.02")), Status.Pass),
      SeedMetric("stress-spec", 1L, 60, ObservationWindow.PeakMonthly, 13, target, ObservedValue.Finite(BigDecimal("0.08")), Status.Warn),
      SeedMetric("stress-spec", 2L, 60, ObservationWindow.Terminal, 60, target, ObservedValue.Finite(BigDecimal("0.01")), Status.Pass),
      SeedMetric("stress-spec", 2L, 60, ObservationWindow.PeakMonthly, 17, target, ObservedValue.Finite(BigDecimal("0.03")), Status.Pass),
      SeedMetric("stress-spec", 2L, 60, ObservationWindow.PeakRolling3, 18, target, ObservedValue.Finite(BigDecimal("0.025")), Status.Pass),
    )
    val summary = summarize(Config(runId = "stress-spec", seeds = 2, months = 60), rows)
    val seedTsv = renderSeedMetricsTsv(rows)
    val sumTsv  = renderSummaryTsv(summary)
    val report  = renderReport(Config(seedStart = 2L, runId = "stress-spec", seeds = 2, months = 60), summary)

    seedTsv.linesIterator.next() shouldBe tsv(
      "RunId",
      "Seed",
      "Months",
      "ObservationWindow",
      "ObservationMonth",
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
    seedTsv should include("ShortfallToIncome")
    seedTsv should include("PEAK_MONTHLY\t13")
    seedTsv should include("2026-04-30 model-start baseline")
    sumTsv.linesIterator.next() shouldBe tsv(
      "RunId",
      "Months",
      "Seeds",
      "ObservationWindow",
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
    sumTsv should include("SOFT_CALIBRATION_WARNING")
    sumTsv should include("PEAK_ROLLING_3")
    report should include("--seed-start 2")
  }

end HouseholdCreditStressCalibrationExportSpec
