package com.boombustgroup.amorfati.diagnostics

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues.*
import zio.stream.ZStream

import java.nio.file.Path

class BankFailureAblationExportSpec extends AnyFlatSpec with Matchers:

  "BankFailureAblationExport" should "parse CLI run controls" in {
    val parsed = BankFailureAblationExport.parseArgs(
      Vector("--seed-start", "3", "--seeds", "4", "--months", "24", "--out", "target/x", "--run-id", "probe"),
    )

    parsed shouldBe Right(
      BankFailureAblationExport.Config(
        seedStart = 3L,
        seeds = 4,
        months = 24,
        out = Path.of("target/x"),
        runId = "probe",
      ),
    )
  }

  it should "cover baseline and targeted banking ablation scenarios" in {
    BankFailureAblationExport.Scenarios.map(_.id) should contain theSameElementsInOrderAs Vector(
      "baseline",
      "no-ecl-stress-migration",
      "no-realized-credit-loss-capital-hit",
      "no-resolution-feedback",
      "initial-capital-150pct",
    )
  }

  it should "summarize first failure timing only over failed seeds" in {
    val config = BankFailureAblationExport.Config(seeds = 2, months = 12, runId = "test")
    val rows   = Vector(
      seed("baseline", "Baseline", 1L, Some(3), terminalFailures = 2, peakFailures = 3),
      seed("baseline", "Baseline", 2L, None, terminalFailures = 0, peakFailures = 0),
    )

    val summary = BankFailureAblationExport.summarize(config, rows).find(_.scenarioId == "baseline").get

    summary.failedSeeds shouldBe 1
    summary.firstFailureMonthMean shouldBe Some(BigDecimal(3))
    summary.terminalFailuresMean shouldBe BigDecimal(1)
    summary.peakFailuresMean shouldBe BigDecimal("1.5")
  }

  it should "validate run controls and required schema columns" in {
    BankFailureAblationExport.validate(BankFailureAblationExport.Config(seeds = 0)).left.value should include("--seeds")
    BankFailureAblationExport.validate(BankFailureAblationExport.Config(months = 0)).left.value should include("--months")
    BankFailureAblationExport.validate(BankFailureAblationExport.Config(runId = " ")).left.value should include("--run-id")
    BankFailureAblationExport.validate(BankFailureAblationExport.Config()).isRight shouldBe true
  }

  it should "fail when a seed stream emits fewer months than requested" in {
    val config = BankFailureAblationExport.Config(seeds = 1, months = 2, runId = "test")

    val result = DiagnosticIo.unsafeRun:
      BankFailureAblationExport.computeSeedResult(config, BankFailureAblationExport.Scenarios.head, 1L, ZStream.empty)

    result.left.value should include("expected 2 monthly rows")
    result.left.value should include("observed 0")
  }

  private def seed(
      scenarioId: String,
      scenarioLabel: String,
      seed: Long,
      firstFailureMonth: Option[Int],
      terminalFailures: Int,
      peakFailures: Int,
  ): BankFailureAblationExport.SeedResult =
    BankFailureAblationExport.SeedResult(
      runId = "test",
      scenarioId = scenarioId,
      scenarioLabel = scenarioLabel,
      seed = seed,
      months = 12,
      firstFailureMonth = firstFailureMonth,
      firstFailureReasonCode = firstFailureMonth.fold(0)(_ => 1),
      firstFailureBankId = firstFailureMonth.fold(-1)(_ => 0),
      terminalFailures = terminalFailures,
      peakFailures = peakFailures,
      cumulativeNewFailures = BigDecimal(terminalFailures),
      cumulativeRealizedCreditLoss = BigDecimal(10),
      cumulativeEclProvisionChange = BigDecimal(2),
      cumulativeBailInLoss = BigDecimal(1),
      cumulativeCapitalDestruction = BigDecimal(3),
      cumulativeReconciliationResidualAbs = BigDecimal("0.1"),
      firstFailureRealizedCreditLoss = firstFailureMonth.fold(BigDecimal(0))(_ => BigDecimal(4)),
      firstFailureEclProvisionChange = firstFailureMonth.fold(BigDecimal(0))(_ => BigDecimal(1)),
      firstFailureConsumerNplLoss = BigDecimal(0),
      firstFailureFirmNplLoss = BigDecimal(0),
      firstFailureMortgageNplLoss = BigDecimal(0),
      firstFailureCorpBondDefaultLoss = BigDecimal(0),
      firstFailureReconciliationResidual = BigDecimal(0),
      firstFailureDepositBailInLoss = BigDecimal(0),
      terminalTotalCreditToGdp = BigDecimal("0.2"),
      terminalUnemployment = BigDecimal("0.05"),
      terminalInflation = BigDecimal("0.03"),
      interpretation = "test",
    )
