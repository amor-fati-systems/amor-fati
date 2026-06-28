package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.montecarlo.{McSeedMonth, McTimeseriesSchema, MetricValue}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues.*
import zio.{Runtime, Unsafe, ZIO}
import zio.stream.ZStream

import java.nio.file.Path

class BankFailureAblationExportSpec extends AnyFlatSpec with Matchers:

  "BankFailureAblationExport" should "parse CLI run controls" in {
    val parsed = BankFailureAblationExport.parseArgs(
      Vector("--seed-start", "3", "--seeds", "4", "--months", "24", "--parallelism", "1", "--out", "target/x", "--run-id", "probe"),
    )

    parsed shouldBe Right(
      BankFailureAblationExport.Config(
        seedStart = 3L,
        seeds = 4,
        months = 24,
        parallelism = 1,
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
    )
  }

  it should "summarize first failure timing only over failed seeds" in {
    val config = BankFailureAblationExport.Config(seeds = 2, months = 12, runId = "test")
    val rows   = Vector(
      seed("baseline", "Baseline", 1L, Some(3), terminalFailures = Some(2), peakFailures = 3),
      seed("baseline", "Baseline", 2L, None, terminalFailures = Some(0), peakFailures = 0),
    )

    val summary = BankFailureAblationExport.summarize(config, rows).find(_.scenarioId == "baseline").get

    summary.failedSeeds shouldBe 1
    summary.firstFailureMonthMean shouldBe Some(BigDecimal(3))
    summary.terminalFailuresMean shouldBe BigDecimal(1)
    summary.peakFailuresMean shouldBe BigDecimal("1.5")
  }

  it should "summarize terminal and cumulative metrics only over completed seeds" in {
    val config = BankFailureAblationExport.Config(seeds = 2, months = 12, runId = "test")
    val rows   = Vector(
      seed("baseline", "Baseline", 1L, Some(3), terminalFailures = Some(2), peakFailures = 3, cumulativeRealizedCreditLoss = BigDecimal(10)),
      seed(
        "baseline",
        "Baseline",
        2L,
        Some(4),
        terminalFailures = None,
        peakFailures = 99,
        observedMonths = 4,
        crashed = 1,
        crashReason = "synthetic crash",
        cumulativeRealizedCreditLoss = BigDecimal(999),
      ),
    )

    val summary = BankFailureAblationExport.summarize(config, rows).find(_.scenarioId == "baseline").get

    summary.crashedSeeds shouldBe 1
    summary.failedSeeds shouldBe 2
    summary.terminalFailuresMean shouldBe BigDecimal(2)
    summary.peakFailuresMean shouldBe BigDecimal(3)
    summary.cumulativeRealizedCreditLossMean shouldBe BigDecimal(10)
  }

  it should "validate run controls and required schema columns" in {
    BankFailureAblationExport.validate(BankFailureAblationExport.Config(seeds = 0)).left.value should include("--seeds")
    BankFailureAblationExport.validate(BankFailureAblationExport.Config(months = 0)).left.value should include("--months")
    BankFailureAblationExport.validate(BankFailureAblationExport.Config(parallelism = 0)).left.value should include("--parallelism")
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

  it should "return a partial seed result when a seed stream crashes" in {
    val config = BankFailureAblationExport.Config(seeds = 1, months = 2, runId = "test")
    val result = DiagnosticIo.unsafeRun:
      BankFailureAblationExport.computeSeedResult(
        config,
        BankFailureAblationExport.Scenarios.head,
        1L,
        ZStream.fail("synthetic crash"),
      )

    val seed = result.value
    seed.observedMonths shouldBe 0
    seed.crashed shouldBe 1
    seed.crashReason should include("synthetic crash")
    seed.terminalFailures shouldBe None
  }

  it should "treat crashes after the requested horizon as completed seeds" in {
    val config = BankFailureAblationExport.Config(seeds = 1, months = 2, runId = "test")
    val result = DiagnosticIo.unsafeRun:
      BankFailureAblationExport.computeSeedResult(
        config,
        BankFailureAblationExport.Scenarios.head,
        1L,
        ZStream.fromIterable(Vector(seedMonth(1), seedMonth(2, bankFailures = 7, totalCreditToGdp = MetricValue.fromDecimalDigits(42, 2)))) ++
          ZStream.fail("late crash"),
      )

    val seed = result.value
    seed.observedMonths shouldBe 2
    seed.crashed shouldBe 0
    seed.crashReason shouldBe ""
    seed.terminalFailures shouldBe Some(7)
    seed.terminalTotalCreditToGdp shouldBe Some(BigDecimal("0.42"))
  }

  it should "propagate interruption instead of rendering it as a crash seed" in {
    val config = BankFailureAblationExport.Config(seeds = 1, months = 2, runId = "test")
    val exit   = Unsafe.unsafe: unsafe =>
      given Unsafe = unsafe
      Runtime.default.unsafe
        .run:
          BankFailureAblationExport
            .computeSeedResult(config, BankFailureAblationExport.Scenarios.head, 1L, ZStream.fromZIO(ZIO.interrupt))
            .exit
        .getOrThrowFiberFailure()

    exit match
      case zio.Exit.Failure(cause) => cause.isInterrupted shouldBe true
      case zio.Exit.Success(seed)  => fail(s"expected interruption, got seed result $seed")
  }

  it should "propagate stream defects instead of rendering them as crash seeds" in {
    val config = BankFailureAblationExport.Config(seeds = 1, months = 2, runId = "test")
    val defect = RuntimeException("synthetic defect")
    val exit   = Unsafe.unsafe: unsafe =>
      given Unsafe = unsafe
      Runtime.default.unsafe
        .run:
          BankFailureAblationExport
            .computeSeedResult(config, BankFailureAblationExport.Scenarios.head, 1L, ZStream.fromZIO(ZIO.die(defect)))
            .exit
        .getOrThrowFiberFailure()

    exit match
      case zio.Exit.Failure(cause) => cause.defects should contain(defect)
      case zio.Exit.Success(seed)  => fail(s"expected defect, got seed result $seed")
  }

  private def seed(
      scenarioId: String,
      scenarioLabel: String,
      seed: Long,
      firstFailureMonth: Option[Int],
      terminalFailures: Option[Int],
      peakFailures: Int,
      observedMonths: Int = 12,
      crashed: Int = 0,
      crashReason: String = "",
      cumulativeRealizedCreditLoss: BigDecimal = BigDecimal(10),
  ): BankFailureAblationExport.SeedResult =
    BankFailureAblationExport.SeedResult(
      runId = "test",
      scenarioId = scenarioId,
      scenarioLabel = scenarioLabel,
      seed = seed,
      months = 12,
      observedMonths = observedMonths,
      crashed = crashed,
      crashReason = crashReason,
      firstFailureMonth = firstFailureMonth,
      firstFailureReasonCode = firstFailureMonth.fold(0)(_ => 1),
      firstFailureBankId = firstFailureMonth.fold(-1)(_ => 0),
      terminalFailures = terminalFailures,
      peakFailures = peakFailures,
      cumulativeNewFailures = terminalFailures.map(BigDecimal(_)).getOrElse(BigDecimal(0)),
      cumulativeRealizedCreditLoss = cumulativeRealizedCreditLoss,
      cumulativeInterbankContagionLoss = BigDecimal("0.5"),
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
      firstFailureInterbankContagionLoss = BigDecimal(0),
      firstFailureReconciliationResidual = BigDecimal(0),
      firstFailureDepositBailInLoss = BigDecimal(0),
      terminalTotalCreditToGdp = terminalFailures.map(_ => BigDecimal("0.2")),
      terminalUnemployment = terminalFailures.map(_ => BigDecimal("0.05")),
      terminalInflation = terminalFailures.map(_ => BigDecimal("0.03")),
      interpretation = "test",
    )

  private val metricColumnIndex: Map[String, Int] =
    McTimeseriesSchema.colNames.zipWithIndex.toMap

  private def seedMonth(
      month: Int,
      bankFailures: Int = 0,
      totalCreditToGdp: MetricValue = MetricValue.Zero,
  ): McSeedMonth =
    val row = Array.fill(McTimeseriesSchema.colNames.length)(MetricValue.Zero)
    putMetric(row, "Month", MetricValue(month))
    putMetric(row, "BankFailures", MetricValue(bankFailures))
    putMetric(row, "TotalCreditToGdp", totalCreditToGdp)
    McSeedMonth(ExecutionMonth(month), row, null, null, null, Vector.empty, Vector.empty)

  private def putMetric(row: Array[MetricValue], name: String, value: MetricValue): Unit =
    row(metricColumnIndex(name)) = value
