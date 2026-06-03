package com.boombustgroup.amorfati.diagnostics

import org.scalatest.EitherValues.*
import org.scalatest.OptionValues.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path
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
    rendered should include(""""steps":[""")
    rendered should include(""""id":"baseline-monte-carlo"""")
    rendered should include(""""classification":"normal_validation"""")
    rendered should include(
      """"failure_policy":"Hard accounting/runtime failures fail; economic failures are interpreted as normal-path engine or calibration alarms."""",
    )
  }

  it should "derive manual UTC run ids from profile and short commit" in {
    NightlyDiagnosticsProfileRunner.autoRunId("nightly", "abcdef0", Instant.parse("2026-05-26T12:34:00Z")) shouldBe
      "nightly-manual-20260526-1234-abcdef0"
  }

  private def context(profileId: String): NightlyDiagnosticsProfileRunner.RunContext =
    val profile = NightlyDiagnosticsProfileRunner.profileById(profileId).value
    val config  = NightlyDiagnosticsProfileRunner.Config(profile = profileId, out = Path.of("target/nightly-diagnostics"), runId = Some("run-1"))
    NightlyDiagnosticsProfileRunner.RunContext(
      config = config,
      profile = profile,
      runId = "run-1",
      runRoot = config.out.resolve(profileId).resolve("run-1"),
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
      ),
      startedAt = Instant.parse("2026-05-26T00:00:00Z"),
    )
