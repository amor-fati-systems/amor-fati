package com.boombustgroup.amorfati.diagnostics

import org.scalatest.EitherValues.*
import org.scalatest.OptionValues.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.Cause

import java.nio.file.{Files, Path}
import java.time.Instant

class NightlyDiagnosticsProfileRunnerSpec extends AnyFlatSpec with Matchers:

  "NightlyDiagnosticsProfileRunner.parseArgs" should "parse jar profile controls" in {
    val parsed = NightlyDiagnosticsProfileRunner.parseArgs(
      Vector(
        "--profile",
        "nightly",
        "--out",
        "target/nightly",
        "--run-id",
        "nightly-20260526-abcdef0",
        "--jar-path",
        "target/scala-3.8.2/amor-fati.jar",
        "--dry-run",
        "--require-main",
      ),
    )

    parsed.value shouldBe NightlyDiagnosticsProfileRunner.Config(
      profile = "nightly",
      out = Path.of("target/nightly"),
      runId = Some("nightly-20260526-abcdef0"),
      jarPath = Path.of("target/scala-3.8.2/amor-fati.jar"),
      dryRun = true,
      requireMain = true,
    )
  }

  it should "reject unknown profiles through profile lookup" in {
    NightlyDiagnosticsProfileRunner.profileById("unknown").left.value should include("--profile")
  }

  "NightlyDiagnosticsProfileRunner profiles" should "encode the documented smoke profile" in {
    val ctx   = context("smoke")
    val steps = ctx.profile.steps(ctx)

    steps.map(_.id) shouldBe Vector(
      "baseline-monte-carlo",
      "sfc-matrix",
      "scenario-run",
      "robustness-report",
      "bank-balance-sheet-benchmark",
      "household-credit-stress",
    )
    steps.find(_.id == "baseline-monte-carlo").value.seeds shouldBe Some(1)
    steps.find(_.id == "baseline-monte-carlo").value.months shouldBe Some(12)
    steps.find(_.id == "robustness-report").value.months shouldBe Some(6)
    steps.map(_.classification) should not contain NightlyDiagnosticsProfileRunner.DiagnosticClass.StressValidation
  }

  it should "encode the five-year nightly profile without 120 month diagnostics" in {
    val ctx   = context("nightly")
    val steps = ctx.profile.steps(ctx)

    steps.map(_.id) should contain theSameElementsInOrderAs Vector(
      "baseline-monte-carlo",
      "empirical-validation",
      "scenario-run",
      "robustness-report",
      "bank-balance-sheet-benchmark",
      "household-credit-stress",
      "hh-bank-lead-lag",
      "loan-origination-quality",
    )
    steps.flatMap(_.months).max shouldBe 60
    steps.find(_.id == "hh-bank-lead-lag").value.details should contain("lag_max" -> "6")
    steps.find(_.id == "loan-origination-quality").value.details should contain("outcome_window" -> "12")
    steps.map(_.classification) should not contain NightlyDiagnosticsProfileRunner.DiagnosticClass.StressValidation
  }

  it should "encode the extended profile lag window and all-scenario selector" in {
    val ctx   = context("extended")
    val steps = ctx.profile.steps(ctx)

    steps.find(_.id == "scenario-run").value.details should contain("scenario_selection" -> "all")
    steps.find(_.id == "scenario-run").value.classification shouldBe NightlyDiagnosticsProfileRunner.DiagnosticClass.StressValidation
    steps.find(_.id == "bank-failure-ablations").value.classification shouldBe NightlyDiagnosticsProfileRunner.DiagnosticClass.StressValidation
    steps.find(_.id == "bank-failure-ablations").value.details should contain("parallelism" -> "2")
    steps.find(_.id == "hh-bank-lead-lag").value.details should contain("lag_max" -> "12")
    steps.find(_.id == "loan-origination-quality").value.seeds shouldBe Some(5)
    steps.flatMap(_.months).max shouldBe 60
  }

  it should "classify baseline evidence, benchmarks, and research diagnostics explicitly" in {
    val ctx   = context("nightly")
    val steps = ctx.profile.steps(ctx)

    steps.find(_.id == "baseline-monte-carlo").value.classification shouldBe NightlyDiagnosticsProfileRunner.DiagnosticClass.NormalValidation
    steps.find(_.id == "empirical-validation").value.classification shouldBe NightlyDiagnosticsProfileRunner.DiagnosticClass.NormalValidation
    steps.find(_.id == "bank-balance-sheet-benchmark").value.classification shouldBe NightlyDiagnosticsProfileRunner.DiagnosticClass.Benchmark
    steps.find(_.id == "household-credit-stress").value.classification shouldBe NightlyDiagnosticsProfileRunner.DiagnosticClass.Exploratory
    steps.find(_.id == "hh-bank-lead-lag").value.classification shouldBe NightlyDiagnosticsProfileRunner.DiagnosticClass.Exploratory
    steps.find(_.id == "loan-origination-quality").value.classification shouldBe NightlyDiagnosticsProfileRunner.DiagnosticClass.Exploratory
  }

  "NightlyDiagnosticsProfileRunner.renderManifest" should "emit machine-readable run metadata" in {
    val ctx      = context("smoke")
    val manifest = NightlyDiagnosticsProfileRunner.Manifest(
      profileId = "smoke",
      runId = "smoke-manual-20260526-abcdef0",
      status = "DRY_RUN",
      dryRun = true,
      startedAt = Instant.parse("2026-05-26T00:00:00Z"),
      finishedAt = Some(Instant.parse("2026-05-26T00:00:01Z")),
      runRoot = ctx.runRoot,
      outRoot = ctx.config.out,
      metadata = ctx.metadata,
      steps = ctx.profile.steps(ctx).map(_.planned(ctx)),
      error = None,
    )

    val rendered = NightlyDiagnosticsProfileRunner.renderManifest(manifest)

    rendered should include(""""profile":"smoke"""")
    rendered should include(""""status":"DRY_RUN"""")
    rendered should include(""""jar":{"path":"target/scala-3.8.2/amor-fati.jar"""")
    rendered should include(""""runtime":{"available_processors":8""")
    rendered should include(""""steps":[""")
    rendered should include(""""id":"baseline-monte-carlo"""")
    rendered should include(""""classification":"normal_validation"""")
    rendered should include(""""telemetry":null""")
    rendered should include(
      """"failure_policy":"Hard accounting/runtime failures fail; economic failures are interpreted as normal-path engine or calibration alarms."""",
    )
  }

  it should "render per-step performance telemetry" in {
    val ctx      = context("smoke")
    val step     = ctx.profile
      .steps(ctx)
      .head
      .completed(
        ctx,
        startedAt = Instant.parse("2026-05-26T00:00:00Z"),
        finishedAt = Instant.parse("2026-05-26T00:00:01Z"),
        artifacts = Vector(ctx.runRoot.resolve("baseline-monte-carlo")),
        telemetry = Some(sampleStepTelemetry),
      )
    val manifest = NightlyDiagnosticsProfileRunner.Manifest(
      profileId = "smoke",
      runId = "smoke-manual-20260526-abcdef0",
      status = "RUNNING",
      dryRun = false,
      startedAt = Instant.parse("2026-05-26T00:00:00Z"),
      finishedAt = None,
      runRoot = ctx.runRoot,
      outRoot = ctx.config.out,
      metadata = ctx.metadata,
      steps = Vector(step),
      error = None,
    )
    val rendered = NightlyDiagnosticsProfileRunner.renderManifest(manifest)

    rendered should include(""""telemetry":{""")
    rendered should include(""""duration_ms":1500""")
    rendered should include(""""duration_seconds":1.5""")
    rendered should include(""""seed_months":12""")
    rendered should include(""""seed_months_per_second":8""")
    rendered should include(""""tsv_row_count":36""")
    rendered should include(""""heap_used_bytes":1000""")
    rendered should include(""""collection_count_delta":2""")
    rendered should include(""""collection_error":null""")
  }

  it should "derive manual UTC run ids from profile and short commit" in {
    NightlyDiagnosticsProfileRunner.autoRunId("nightly", "abcdef0", Instant.parse("2026-05-26T12:34:00Z")) shouldBe
      "nightly-manual-20260526-1234-abcdef0"
  }

  it should "render typed and defect causes for manifest failure fields" in {
    NightlyDiagnosticsProfileRunner.renderCause("step-x", Cause.fail("typed failure")) shouldBe "typed failure"
    NightlyDiagnosticsProfileRunner.renderCause("step-x", Cause.die(RuntimeException("boom"))) should include("step-x crashed")
  }

  "NightlyHealthSummary" should "pass when baseline TSVs satisfy normal-path thresholds" in {
    val ctx = context("smoke", tempOut("health-pass"), runId = "health-pass")
    writeBaselineSeedTsv(ctx, seed = 1, rows = healthyRows(months = 12))

    val result = DiagnosticIo.unsafeRun(NightlyHealthSummary.write(ctx, succeededSteps(ctx))).value

    result.status shouldBe NightlyHealthSummary.HealthStatus.Passed
    result.hardFailureCount shouldBe 0
    Files.readString(result.jsonPath) should include(""""id":"normal.bank_failures"""")
    Files.readString(result.markdownPath) should include("Nightly Health Summary")
  }

  it should "fail on normal-path bank failures from baseline TSVs" in {
    val ctx = context("smoke", tempOut("health-bank-fail"), runId = "health-bank-fail")
    writeBaselineSeedTsv(ctx, seed = 1, rows = healthyRows(months = 12).updated(5, healthyRow(month = 6, bankFailures = 1, newFailures = 1)))

    val result = DiagnosticIo.unsafeRun(NightlyHealthSummary.write(ctx, succeededSteps(ctx))).value

    result.status shouldBe NightlyHealthSummary.HealthStatus.Failed
    result.hardFailureCount should be > 0
    Files.readString(result.jsonPath) should include(""""id":"normal.bank_failures"""")
    Files.readString(result.jsonPath) should include(""""status":"FAIL"""")
  }

  it should "warn without hard-failing on household negative-deposit diagnostics" in {
    val ctx = context("smoke", tempOut("health-warning"), runId = "health-warning")
    writeBaselineSeedTsv(
      ctx,
      seed = 1,
      rows = healthyRows(months = 12).updated(2, healthyRow(month = 3, negativeDepositCount = 2, negativeDepositShare = "0.004")),
    )

    val result = DiagnosticIo.unsafeRun(NightlyHealthSummary.write(ctx, succeededSteps(ctx))).value

    result.status shouldBe NightlyHealthSummary.HealthStatus.Warning
    result.hardFailureCount shouldBe 0
    Files.readString(result.jsonPath) should include(""""id":"normal.negative_stock_counts"""")
    Files.readString(result.jsonPath) should include(""""status":"WARN"""")
  }

  private def context(
      profileId: String,
      out: Path = Path.of("target/nightly-diagnostics"),
      runId: String = "run-1",
  ): NightlyDiagnosticsProfileRunner.RunContext =
    val profile = NightlyDiagnosticsProfileRunner.profileById(profileId).value
    val config  = NightlyDiagnosticsProfileRunner.Config(profile = profileId, out = out, runId = Some(runId))
    NightlyDiagnosticsProfileRunner.RunContext(
      config = config,
      profile = profile,
      runId = runId,
      runRoot = config.out.resolve(profileId).resolve(runId),
      metadata = NightlyDiagnosticsProfileRunner.RuntimeMetadata(
        commit = "abcdef0123456789",
        shortCommit = "abcdef0",
        branch = "main",
        dirty = false,
        gitAvailable = true,
        jarPath = Path.of("target/scala-3.8.2/amor-fati.jar"),
        jarHash = Some("hash"),
        commandLine = "java -cp target/scala-3.8.2/amor-fati.jar com.boombustgroup.amorfati.diagnostics.NightlyDiagnosticsProfileRunner --profile smoke",
        javaVersion = "test-java",
        scalaVersion = "test-scala",
        nixVersion = Some("nix test"),
        sbtVersion = Some("sbt test"),
        buildCommit = "abcdef0",
        runtime = NightlyDiagnosticsProfileRunner.RuntimeTelemetry(
          availableProcessors = 8,
          maxMemoryBytes = 4096,
          totalMemoryBytes = 2048,
          freeMemoryBytes = 1024,
          usedMemoryBytes = 1024,
          jvmName = "test-vm",
          jvmVendor = "test-vendor",
          jvmVersion = "test-vm-version",
          osName = "test-os",
          osArch = "test-arch",
          osVersion = "test-os-version",
        ),
      ),
      startedAt = Instant.parse("2026-05-26T00:00:00Z"),
    )

  private def sampleStepTelemetry: NightlyDiagnosticsProfileRunner.StepTelemetry =
    NightlyDiagnosticsProfileRunner.StepTelemetry(
      durationMillis = 1500L,
      seedMonths = Some(12L),
      seedMonthsPerSecond = Some(BigDecimal(8)),
      artifacts = NightlyDiagnosticsProfileRunner.ArtifactTelemetry(
        fileCount = 4L,
        bytes = 8192L,
        tsvFileCount = 3L,
        tsvRowCount = 36L,
      ),
      memoryBefore = NightlyDiagnosticsProfileRunner.MemoryTelemetry(
        heapUsedBytes = 1000L,
        heapCommittedBytes = 2000L,
        heapMaxBytes = 4000L,
        nonHeapUsedBytes = 300L,
        nonHeapCommittedBytes = 600L,
        nonHeapMaxBytes = 900L,
      ),
      memoryAfter = NightlyDiagnosticsProfileRunner.MemoryTelemetry(
        heapUsedBytes = 1200L,
        heapCommittedBytes = 2200L,
        heapMaxBytes = 4000L,
        nonHeapUsedBytes = 350L,
        nonHeapCommittedBytes = 650L,
        nonHeapMaxBytes = 900L,
      ),
      gc = Vector(NightlyDiagnosticsProfileRunner.GcTelemetry("test-gc", collectionCountDelta = Some(2L), collectionTimeMillisDelta = Some(11L))),
      collectionError = None,
    )

  private def succeededSteps(ctx: NightlyDiagnosticsProfileRunner.RunContext): Vector[NightlyDiagnosticsProfileRunner.ManifestStep] =
    ctx.profile
      .steps(ctx)
      .map: step =>
        step.completed(
          ctx,
          startedAt = Instant.parse("2026-05-26T00:00:01Z"),
          finishedAt = Instant.parse("2026-05-26T00:00:02Z"),
          artifacts = Vector(step.outputDir(ctx)),
        )

  private def tempOut(name: String): Path =
    val path = Path.of("target", "nightly-health-summary-spec", s"$name-${System.nanoTime()}")
    Files.createDirectories(path)
    path

  private def writeBaselineSeedTsv(ctx: NightlyDiagnosticsProfileRunner.RunContext, seed: Int, rows: Vector[String]): Path =
    val baselineStep = ctx.profile.steps(ctx).find(_.id == "baseline-monte-carlo").value
    val months       = baselineStep.months.value
    val dir          = baselineStep.outputDir(ctx)
    Files.createDirectories(dir)
    val path         = dir.resolve(f"baseline_${ctx.runId}_${months}m_seed$seed%03d.tsv")
    Files.writeString(path, (HealthTsvHeader +: rows).mkString("\n") + "\n")
    path

  private def healthyRows(months: Int): Vector[String] =
    (1 to months).toVector.map(month => healthyRow(month))

  private def healthyRow(
      month: Int,
      bankFailures: Int = 0,
      newFailures: Int = 0,
      negativeDepositCount: Int = 0,
      negativeDepositShare: String = "0",
  ): String =
    Vector(
      month.toString,
      (100 + month).toString,
      ((100 + month) * 12).toString,
      "0.55",
      bankFailures.toString,
      newFailures.toString,
      "0",
      "0",
      "0",
      "0",
      "0",
      "0.01",
      "0.02",
      "0.03",
      "0.04",
      "0.05",
      negativeDepositCount.toString,
      negativeDepositShare,
    ).mkString("\t")

  private val HealthTsvHeader: String =
    Vector(
      "Month",
      "MonthlyGdpProxy",
      "AnnualizedGdpProxy",
      "TotalCreditToGdp",
      "BankFailures",
      "BankResolution_NewFailures",
      "BankResolution_AllFailedFallback",
      "BankResolution_InvalidActiveBankInvariant",
      "BankCapital_WaterfallResidual",
      "BankCapital_ReconciliationResidual",
      "FofResidual",
      "BankCreditLoss_FirmDefaultRate",
      "BankCreditLoss_MortgageDefaultRate",
      "BankCreditLoss_ConsumerLoanDefaultRate",
      "BankCreditLoss_CorpBondDefaultRate",
      "ConsumerCredit_NplRatioGross",
      "HouseholdLiquidity_NegativeDepositCount",
      "HouseholdLiquidity_NegativeDepositShare",
    ).mkString("\t")
