package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.config.RobustnessScenarios.ScenarioSet
import com.boombustgroup.amorfati.config.{ScenarioRegistry, SimParams}
import com.boombustgroup.amorfati.montecarlo.{McRunConfig, McRunner}
import com.boombustgroup.amorfati.util.BuildInfo
import zio.{Clock, Ref, ZIO}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import java.util.Locale
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** Jar-runnable profile orchestrator for the nightly diagnostics milestone.
  *
  * The individual diagnostics keep owning their domain logic. This runner only
  * standardizes profile selection, jar/Nix invocation metadata, clean-ref
  * validation, output layout, and the machine-readable run manifest.
  */
object NightlyDiagnosticsProfileRunner:

  final case class Config(
      profile: String = "smoke",
      out: Path = Path.of("target/nightly-diagnostics"),
      runId: Option[String] = None,
      jarPath: Path = Path.of("target/scala-3.8.2/amor-fati.jar"),
      dryRun: Boolean = false,
      allowDirty: Boolean = false,
      requireMain: Boolean = false,
  )

  final case class RunResult(manifestPath: Path, runRoot: Path, status: String)

  private[diagnostics] final case class RuntimeMetadata(
      commit: String,
      shortCommit: String,
      branch: String,
      dirty: Boolean,
      gitAvailable: Boolean,
      jarPath: Path,
      jarHash: Option[String],
      commandLine: String,
      javaVersion: String,
      scalaVersion: String,
      nixVersion: Option[String],
      sbtVersion: Option[String],
      buildCommit: String,
  )

  private[diagnostics] final case class RunContext(
      config: Config,
      profile: ProfileSpec,
      runId: String,
      runRoot: Path,
      metadata: RuntimeMetadata,
      startedAt: Instant,
  )

  private[diagnostics] final case class ManifestStep(
      id: String,
      label: String,
      entrypoint: String,
      seedStart: Option[Long],
      seeds: Option[Int],
      months: Option[Int],
      outputDir: Path,
      details: Vector[(String, String)],
      status: String,
      startedAt: Option[Instant],
      finishedAt: Option[Instant],
      artifacts: Vector[Path],
      error: Option[String],
  )

  private[diagnostics] final case class Manifest(
      profileId: String,
      runId: String,
      status: String,
      dryRun: Boolean,
      startedAt: Instant,
      finishedAt: Option[Instant],
      runRoot: Path,
      outRoot: Path,
      metadata: RuntimeMetadata,
      steps: Vector[ManifestStep],
      error: Option[String],
  )

  private[diagnostics] final case class ProfileSpec(
      id: String,
      label: String,
      seedStart: Long,
      steps: RunContext => Vector[DiagnosticStep],
  )

  private[diagnostics] final case class DiagnosticStep(
      id: String,
      label: String,
      entrypoint: String,
      seedStart: Option[Long],
      seeds: Option[Int],
      months: Option[Int],
      outputDir: RunContext => Path,
      details: Vector[(String, String)],
      run: RunContext => ZIO[Any, String, Vector[Path]],
  ):
    def planned(ctx: RunContext): ManifestStep =
      ManifestStep(
        id = id,
        label = label,
        entrypoint = entrypoint,
        seedStart = seedStart,
        seeds = seeds,
        months = months,
        outputDir = outputDir(ctx),
        details = details,
        status = "PLANNED",
        startedAt = None,
        finishedAt = None,
        artifacts = Vector.empty,
        error = None,
      )

    def completed(ctx: RunContext, startedAt: Instant, finishedAt: Instant, artifacts: Vector[Path]): ManifestStep =
      planned(ctx).copy(status = "SUCCEEDED", startedAt = Some(startedAt), finishedAt = Some(finishedAt), artifacts = artifacts)

    def failed(ctx: RunContext, startedAt: Instant, finishedAt: Instant, error: String): ManifestStep =
      planned(ctx).copy(status = "FAILED", startedAt = Some(startedAt), finishedAt = Some(finishedAt), error = Some(error))

  def main(args: Array[String]): Unit =
    parseArgs(args.toVector) match
      case Left(message) if args.contains("--help") =>
        println(usage)
        if message != usage then println(message)
      case Left(message)                            =>
        Console.err.println(message)
        Console.err.println(usage)
        sys.exit(2)
      case Right(config)                            =>
        DiagnosticIo.unsafeRun(run(config, args.toVector)) match
          case Left(err)     =>
            Console.err.println(err)
            sys.exit(1)
          case Right(result) =>
            println(result.manifestPath.toString)

  def run(config: Config, rawArgs: Vector[String] = Vector.empty): ZIO[Any, String, RunResult] =
    for
      profile  <- ZIO.fromEither(profileById(config.profile))
      started  <- Clock.instant
      metadata <- captureRuntimeMetadata(config.jarPath, rawArgs)
      runId     = config.runId.getOrElse(autoRunId(profile.id, metadata.shortCommit, started))
      runRoot   = config.out.resolve(profile.id).resolve(runId)
      ctx       = RunContext(config, profile, runId, runRoot, metadata, started)
      steps     = profile.steps(ctx)
      _        <- ZIO
        .fromEither(validateRun(config, metadata))
        .catchAll: err =>
          for
            finished <- Clock.instant
            _        <- writeManifest(ctx, steps.map(_.planned(ctx)), "FAILED", Some(finished), Some(err))
            _        <- ZIO.fail(err)
          yield ()
      result   <- if config.dryRun then dryRun(ctx, steps) else execute(ctx, steps)
    yield result

  def parseArgs(args: Vector[String]): Either[String, Config] =
    def missingValue(flag: String): Left[String, Config] = Left(s"Missing value for $flag")

    def loop(rest: Seq[String], config: Config): Either[String, Config] =
      rest match
        case Seq()                                  => Right(config)
        case Seq("--help", _*)                      => Left(usage)
        case Seq("--dry-run", next*)                => loop(next, config.copy(dryRun = true))
        case Seq("--allow-dirty", next*)            => loop(next, config.copy(allowDirty = true))
        case Seq("--require-main", next*)           => loop(next, config.copy(requireMain = true))
        case Seq(flag, tail*) if knownFlag(flag)    =>
          tail match
            case Seq()                                     => missingValue(flag)
            case Seq(value, _*) if value.startsWith("--")  => missingValue(flag)
            case Seq(value, next*) if flag == "--profile"  => loop(next, config.copy(profile = value))
            case Seq(value, next*) if flag == "--out"      => loop(next, config.copy(out = Path.of(value)))
            case Seq(value, next*) if flag == "--run-id"   => loop(next, config.copy(runId = Some(value)))
            case Seq(value, next*) if flag == "--jar-path" => loop(next, config.copy(jarPath = Path.of(value)))
            case Seq(value, next*) if flag == "--jar"      => loop(next, config.copy(jarPath = Path.of(value)))
            case Seq(_, _*)                                => Left(s"Unknown argument: $flag")
        case Seq(flag, _*) if flag.startsWith("--") =>
          Left(s"Unknown argument: $flag")
        case Seq(value, _*)                         =>
          Left(s"Unexpected positional argument: $value")

    loop(args, Config()).flatMap: config =>
      Either.cond(config.runId.forall(_.trim.nonEmpty), config, "--run-id must be non-empty")

  private def execute(ctx: RunContext, steps: Vector[DiagnosticStep]): ZIO[Any, String, RunResult] =
    for
      completedRef <- Ref.make(Vector.empty[ManifestStep])
      failedRef    <- Ref.make(Option.empty[ManifestStep])
      _            <- writeManifest(ctx, steps.map(_.planned(ctx)), "RUNNING", None, None)
      stepResult   <- ZIO
        .foreachDiscard(steps): step =>
          for
            started  <- Clock.instant
            paths    <- step
              .run(ctx)
              .tapError: err =>
                Clock.instant.flatMap(finished => failedRef.set(Some(step.failed(ctx, started, finished, err))))
            finished <- Clock.instant
            done      = step.completed(ctx, started, finished, paths)
            _        <- completedRef.update(_ :+ done)
            current  <- completedRef.get
            _        <- writeManifest(ctx, mergeSteps(steps, current, None, ctx), "RUNNING", None, None)
          yield ()
        .foldZIO(
          err =>
            for
              finished  <- Clock.instant
              completed <- completedRef.get
              failed    <- failedRef.get
              manifest  <- writeManifest(ctx, mergeSteps(steps, completed, failed, ctx), "FAILED", Some(finished), Some(err))
              _         <- ZIO.fail(err)
            yield RunResult(manifest, ctx.runRoot, "FAILED"),
          _ =>
            for
              finished  <- Clock.instant
              completed <- completedRef.get
              manifest  <- writeManifest(ctx, mergeSteps(steps, completed, None, ctx), "SUCCEEDED", Some(finished), None)
            yield RunResult(manifest, ctx.runRoot, "SUCCEEDED"),
        )
    yield stepResult

  private def dryRun(ctx: RunContext, steps: Vector[DiagnosticStep]): ZIO[Any, String, RunResult] =
    for
      finished <- Clock.instant
      manifest <- writeManifest(ctx, steps.map(_.planned(ctx)), "DRY_RUN", Some(finished), None)
    yield RunResult(manifest, ctx.runRoot, "DRY_RUN")

  private def writeManifest(
      ctx: RunContext,
      steps: Vector[ManifestStep],
      status: String,
      finishedAt: Option[Instant],
      error: Option[String],
  ): ZIO[Any, String, Path] =
    val path = ctx.runRoot.resolve("run-manifest.json")
    DiagnosticIo.writeText(
      path,
      renderManifest(
        Manifest(
          profileId = ctx.profile.id,
          runId = ctx.runId,
          status = status,
          dryRun = ctx.config.dryRun,
          startedAt = ctx.startedAt,
          finishedAt = finishedAt,
          runRoot = ctx.runRoot,
          outRoot = ctx.config.out,
          metadata = ctx.metadata,
          steps = steps,
          error = error,
        ),
      ),
    )

  private def validateRun(config: Config, metadata: RuntimeMetadata): Either[String, Unit] =
    Either
      .cond(profileById(config.profile).isRight, (), s"--profile must be one of: ${Profiles.map(_.id).mkString(", ")}")
      .flatMap(_ => Either.cond(metadata.gitAvailable, (), "Cannot validate run ref because git metadata is unavailable"))
      .flatMap(_ =>
        Either
          .cond(config.allowDirty || !metadata.dirty, (), "Ref validation failed: working tree is dirty; pass --allow-dirty only for local non-nightly probes"),
      )
      .flatMap(_ =>
        Either.cond(
          !config.requireMain || metadata.branch == "main",
          (),
          s"Ref validation failed: expected branch/ref main, observed ${metadata.branch}",
        ),
      )

  private def mergeSteps(
      planned: Vector[DiagnosticStep],
      completed: Vector[ManifestStep],
      failed: Option[ManifestStep],
      ctx: RunContext,
  ): Vector[ManifestStep] =
    val byId     = completed.map(step => step.id -> step).toMap ++ failed.map(step => step.id -> step).toMap
    val failedId = failed.map(_.id)
    planned.map: step =>
      byId.getOrElse(
        step.id,
        step.planned(ctx).copy(status = failedId.fold("PLANNED")(_ => "SKIPPED")),
      )

  private[diagnostics] def profileById(id: String): Either[String, ProfileSpec] =
    Profiles.find(_.id == id.trim.toLowerCase(Locale.ROOT)).toRight(s"--profile must be one of: ${Profiles.map(_.id).mkString(", ")}")

  private[diagnostics] val Profiles: Vector[ProfileSpec] =
    Vector(
      ProfileSpec(
        id = "smoke",
        label = "Smoke",
        seedStart = 1L,
        steps = _ =>
          Vector(
            baselineMonteCarlo(seeds = 1, months = 12),
            sfcMatrix(seed = 1L, months = 12),
            scenarioRun(selection = "baseline,monetary-tightening,fiscal-expansion", seeds = 1, months = 12),
            robustnessReport(scenarioSet = ScenarioSet.Smoke, seeds = 1, months = 6),
            bankBalanceSheetBenchmark(seeds = 2),
            householdCreditStress(seeds = 1, months = 12),
            bankFailureAblations(seeds = 1, months = 12),
          ),
      ),
      ProfileSpec(
        id = "nightly",
        label = "Nightly",
        seedStart = 1L,
        steps = _ =>
          Vector(
            baselineMonteCarlo(seeds = 5, months = 60),
            empiricalValidation(baselineSeeds = 5, baselineMonths = 60),
            scenarioRun(selection = "default", seeds = 5, months = 60),
            robustnessReport(scenarioSet = ScenarioSet.Core, seeds = 2, months = 24),
            bankBalanceSheetBenchmark(seeds = 10),
            householdCreditStress(seeds = 5, months = 60),
            bankFailureAblations(seeds = 5, months = 60),
            hhBankLeadLag(seeds = 5, months = 60, lagMax = 6),
            loanOriginationQuality(seeds = 2, months = 60, outcomeWindow = 12),
          ),
      ),
      ProfileSpec(
        id = "extended",
        label = "Extended",
        seedStart = 1L,
        steps = _ =>
          Vector(
            baselineMonteCarlo(seeds = 10, months = 60),
            scenarioRun(selection = "all", seeds = 5, months = 60),
            robustnessReport(scenarioSet = ScenarioSet.Core, seeds = 5, months = 60),
            bankBalanceSheetBenchmark(seeds = 10),
            householdCreditStress(seeds = 10, months = 60),
            bankFailureAblations(seeds = 10, months = 60),
            hhBankLeadLag(seeds = 10, months = 60, lagMax = 12),
            loanOriginationQuality(seeds = 5, months = 60, outcomeWindow = 12),
          ),
      ),
    )

  private def baselineMonteCarlo(seeds: Int, months: Int): DiagnosticStep =
    DiagnosticStep(
      id = "baseline-monte-carlo",
      label = "Baseline Monte Carlo",
      entrypoint = "com.boombustgroup.amorfati.montecarlo.McRunner",
      seedStart = Some(1L),
      seeds = Some(seeds),
      months = Some(months),
      outputDir = baselineMonteCarloDir,
      details = Vector("output_prefix" -> "baseline", "snapshots" -> "disabled", "decision_traces" -> "disabled"),
      run = ctx =>
        val rc          = McRunConfig(nSeeds = seeds, outputPrefix = "baseline", runDurationMonths = months, runId = ctx.runId)
        given SimParams = SimParams.defaults
        McRunner.runZIO(rc, baselineMonteCarloDir(ctx).toFile).as(Vector(baselineMonteCarloDir(ctx))).mapError(_.toString),
    )

  private def sfcMatrix(seed: Long, months: Int): DiagnosticStep =
    DiagnosticStep(
      id = "sfc-matrix",
      label = "SFC Matrix Evidence",
      entrypoint = "com.boombustgroup.amorfati.diagnostics.SfcMatrixExport",
      seedStart = Some(seed),
      seeds = Some(1),
      months = Some(months),
      outputDir = ctx => ctx.runRoot.resolve("sfc-matrix"),
      details = Vector("formats" -> "md,tex"),
      run = ctx =>
        ZIO
          .fromEither(SfcMatrixExport.run(SfcMatrixExport.Config(seed = seed, months = months, out = ctx.runRoot.resolve("sfc-matrix"))))
          .map(_.paths),
    )

  private def scenarioRun(selection: String, seeds: Int, months: Int): DiagnosticStep =
    DiagnosticStep(
      id = "scenario-run",
      label = "Scenario Registry Run",
      entrypoint = "com.boombustgroup.amorfati.diagnostics.ScenarioRunExport",
      seedStart = Some(1L),
      seeds = Some(seeds),
      months = Some(months),
      outputDir = ctx => ctx.runRoot.resolve("scenario-run").resolve(ctx.runId),
      details = Vector("scenario_selection" -> selection),
      run = ctx =>
        for
          scenarios <- ZIO.fromEither(resolveScenarios(selection))
          result    <- ScenarioRunExport.runZIO(
            ScenarioRunExport.Config(
              scenarios = scenarios,
              seedStart = 1L,
              seeds = seeds,
              months = months,
              runId = ctx.runId,
              out = ctx.runRoot.resolve("scenario-run"),
            ),
          )
        yield result.paths,
    )

  private def robustnessReport(scenarioSet: ScenarioSet, seeds: Int, months: Int): DiagnosticStep =
    DiagnosticStep(
      id = "robustness-report",
      label = "Sensitivity Robustness Report",
      entrypoint = "com.boombustgroup.amorfati.diagnostics.SensitivityRobustnessExport",
      seedStart = Some(1L),
      seeds = Some(seeds),
      months = Some(months),
      outputDir = ctx => ctx.runRoot.resolve("robustness-report"),
      details = Vector("scenario_set" -> scenarioSet.cliName),
      run = ctx =>
        SensitivityRobustnessExport
          .runZIO(
            SensitivityRobustnessExport.Config(
              seedStart = 1L,
              seeds = seeds,
              months = months,
              out = ctx.runRoot.resolve("robustness-report"),
              scenarioSet = scenarioSet,
            ),
          )
          .map(_.paths),
    )

  private def empiricalValidation(baselineSeeds: Int, baselineMonths: Int): DiagnosticStep =
    DiagnosticStep(
      id = "empirical-validation",
      label = "Empirical Validation Snapshot",
      entrypoint = "com.boombustgroup.amorfati.diagnostics.EmpiricalValidationExport",
      seedStart = Some(1L),
      seeds = Some(baselineSeeds),
      months = Some(baselineMonths),
      outputDir = ctx => ctx.runRoot.resolve("empirical-validation"),
      details = Vector("source" -> "baseline-monte-carlo", "output_prefix" -> "baseline"),
      run = ctx =>
        ZIO
          .fromEither(
            EmpiricalValidationExport.run(
              EmpiricalValidationExport.Config(
                mcDir = baselineMonteCarloDir(ctx),
                out = ctx.runRoot.resolve("empirical-validation"),
                runId = ctx.runId,
                outputPrefix = "baseline",
                durationMonths = baselineMonths,
                seeds = baselineSeeds,
                commit = ctx.metadata.commit,
                parameterBranch = ctx.metadata.branch,
              ),
            ),
          )
          .map(_.paths),
    )

  private def bankBalanceSheetBenchmark(seeds: Int): DiagnosticStep =
    DiagnosticStep(
      id = "bank-balance-sheet-benchmark",
      label = "Bank Balance-Sheet Benchmark",
      entrypoint = "com.boombustgroup.amorfati.diagnostics.BankBalanceSheetBenchmarkExport",
      seedStart = Some(1L),
      seeds = Some(seeds),
      months = None,
      outputDir = ctx => ctx.runRoot.resolve("bank-balance-sheet-benchmark").resolve(ctx.runId),
      details = Vector("horizon" -> "opening_balance_sheet"),
      run = ctx =>
        ZIO
          .fromEither(
            BankBalanceSheetBenchmarkExport.run(
              BankBalanceSheetBenchmarkExport
                .Config(seedStart = 1L, seeds = seeds, runId = ctx.runId, out = ctx.runRoot.resolve("bank-balance-sheet-benchmark")),
            ),
          )
          .map(_.paths),
    )

  private def householdCreditStress(seeds: Int, months: Int): DiagnosticStep =
    DiagnosticStep(
      id = "household-credit-stress",
      label = "Household Credit-Stress Calibration",
      entrypoint = "com.boombustgroup.amorfati.diagnostics.HouseholdCreditStressCalibrationExport",
      seedStart = Some(1L),
      seeds = Some(seeds),
      months = Some(months),
      outputDir = ctx => ctx.runRoot.resolve("household-credit-stress").resolve(ctx.runId),
      details = Vector.empty,
      run = ctx =>
        HouseholdCreditStressCalibrationExport
          .runZIO(
            HouseholdCreditStressCalibrationExport.Config(
              seedStart = 1L,
              seeds = seeds,
              months = months,
              runId = ctx.runId,
              out = ctx.runRoot.resolve("household-credit-stress"),
            ),
          )
          .map(_.paths),
    )

  private def bankFailureAblations(seeds: Int, months: Int): DiagnosticStep =
    DiagnosticStep(
      id = "bank-failure-ablations",
      label = "Bank Failure Ablations",
      entrypoint = "com.boombustgroup.amorfati.diagnostics.BankFailureAblationExport",
      seedStart = Some(1L),
      seeds = Some(seeds),
      months = Some(months),
      outputDir = ctx => ctx.runRoot.resolve("bank-failure-ablations").resolve(ctx.runId),
      details = Vector.empty,
      run = ctx =>
        BankFailureAblationExport
          .runZIO(
            BankFailureAblationExport.Config(
              seedStart = 1L,
              seeds = seeds,
              months = months,
              runId = ctx.runId,
              out = ctx.runRoot.resolve("bank-failure-ablations"),
            ),
          )
          .map(_.paths),
    )

  private def hhBankLeadLag(seeds: Int, months: Int, lagMax: Int): DiagnosticStep =
    DiagnosticStep(
      id = "hh-bank-lead-lag",
      label = "HH-Bank Lead-Lag Diagnostics",
      entrypoint = "com.boombustgroup.amorfati.diagnostics.HhBankLeadLagDiagnosticsExport",
      seedStart = Some(1L),
      seeds = Some(seeds),
      months = Some(months),
      outputDir = ctx => ctx.runRoot.resolve("hh-bank-lead-lag").resolve(ctx.runId),
      details = Vector("lag_max" -> lagMax.toString),
      run = ctx =>
        HhBankLeadLagDiagnosticsExport
          .runZIO(
            HhBankLeadLagDiagnosticsExport.Config(
              seedStart = 1L,
              seeds = seeds,
              months = months,
              lagMax = lagMax,
              runId = ctx.runId,
              out = ctx.runRoot.resolve("hh-bank-lead-lag"),
            ),
          )
          .map(_.paths),
    )

  private def loanOriginationQuality(seeds: Int, months: Int, outcomeWindow: Int): DiagnosticStep =
    DiagnosticStep(
      id = "loan-origination-quality",
      label = "Loan Origination Quality",
      entrypoint = "com.boombustgroup.amorfati.diagnostics.LoanOriginationQualityExport",
      seedStart = Some(1L),
      seeds = Some(seeds),
      months = Some(months),
      outputDir = ctx => ctx.runRoot.resolve("loan-origination-quality").resolve(ctx.runId),
      details = Vector("outcome_window" -> outcomeWindow.toString),
      run = ctx =>
        ZIO
          .fromEither(
            LoanOriginationQualityExport.run(
              LoanOriginationQualityExport.Config(
                seedStart = 1L,
                seeds = seeds,
                months = months,
                outcomeWindow = outcomeWindow,
                runId = ctx.runId,
                out = ctx.runRoot.resolve("loan-origination-quality"),
              ),
            ),
          )
          .map(_.paths),
    )

  private def baselineMonteCarloDir(ctx: RunContext): Path =
    ctx.runRoot.resolve("baseline-monte-carlo").resolve(ctx.runId)

  private def resolveScenarios(selection: String): Either[String, Vector[ScenarioRegistry.ScenarioSpec]] =
    selection match
      case "default" => Right(ScenarioRegistry.defaultScenarioIds.flatMap(id => ScenarioRegistry.get(id).toOption))
      case "all"     => Right(ScenarioRegistry.all)
      case other     => ScenarioRegistry.select(other)

  private def captureRuntimeMetadata(jarPath: Path, rawArgs: Vector[String]): ZIO[Any, String, RuntimeMetadata] =
    ZIO
      .attemptBlocking:
        val gitCommit = command(Vector("git", "rev-parse", "HEAD"))
        val branch    = sys.env
          .get("GITHUB_REF_NAME")
          .filter(_.trim.nonEmpty)
          .orElse(command(Vector("git", "branch", "--show-current")).filter(_.trim.nonEmpty))
          .orElse(command(Vector("git", "rev-parse", "--abbrev-ref", "HEAD")).filter(_.trim.nonEmpty))
          .getOrElse("unknown")
        val commit    = sys.env.get("GITHUB_SHA").filter(_.trim.nonEmpty).orElse(gitCommit).getOrElse(BuildInfo.gitCommit)
        val dirty     = command(Vector("git", "status", "--porcelain")).exists(_.trim.nonEmpty)
        RuntimeMetadata(
          commit = commit,
          shortCommit = shortenCommit(commit),
          branch = branch,
          dirty = dirty,
          gitAvailable = gitCommit.isDefined,
          jarPath = jarPath,
          jarHash = fileSha256(jarPath),
          commandLine = commandLine(rawArgs),
          javaVersion = System.getProperty("java.version", "unknown"),
          scalaVersion = scala.util.Properties.versionNumberString,
          nixVersion = command(Vector("nix", "--version")),
          sbtVersion = command(Vector("sbt", "--script-version")),
          buildCommit = BuildInfo.gitCommit,
        )
      .mapError(err => s"Failed to capture runtime metadata: ${message(err)}")

  private def command(args: Vector[String]): Option[String] =
    Try:
      val process = ProcessBuilder(args.asJava).redirectErrorStream(true).start()
      val output  = String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      val code    = process.waitFor()
      Option.when(code == 0 && output.nonEmpty)(output.linesIterator.next().trim)
    .toOption.flatten

  private def fileSha256(path: Path): Option[String] =
    Try:
      if !Files.isRegularFile(path) then None
      else
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes  = digest.digest(Files.readAllBytes(path))
        Some(bytes.map("%02x".format(_)).mkString)
    .toOption.flatten

  private def commandLine(rawArgs: Vector[String]): String =
    Option(System.getProperty("sun.java.command"))
      .filter(_.trim.nonEmpty)
      .getOrElse:
        ("com.boombustgroup.amorfati.diagnostics.NightlyDiagnosticsProfileRunner" +: rawArgs).mkString(" ")

  private[diagnostics] def autoRunId(profile: String, shortCommit: String, at: Instant): String =
    val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC).format(at)
    s"$profile-manual-$timestamp-$shortCommit"

  private def shortenCommit(commit: String): String =
    val clean = commit.trim.stripSuffix("-dirty")
    if clean.length >= 7 then clean.take(7) else clean

  private[diagnostics] def renderManifest(manifest: Manifest): String =
    val metadata = manifest.metadata
    val fields   = Vector(
      "profile"       -> json(manifest.profileId),
      "run_id"        -> json(manifest.runId),
      "status"        -> json(manifest.status),
      "dry_run"       -> manifest.dryRun.toString,
      "started_at"    -> json(instant(manifest.startedAt)),
      "finished_at"   -> manifest.finishedAt.fold("null")(value => json(instant(value))),
      "run_root"      -> json(manifest.runRoot.toString),
      "out_root"      -> json(manifest.outRoot.toString),
      "git"           -> renderObject(
        Vector(
          "commit"        -> json(metadata.commit),
          "short_commit"  -> json(metadata.shortCommit),
          "branch"        -> json(metadata.branch),
          "dirty"         -> metadata.dirty.toString,
          "git_available" -> metadata.gitAvailable.toString,
          "build_commit"  -> json(metadata.buildCommit),
        ),
      ),
      "jar"           -> renderObject(
        Vector(
          "path"   -> json(metadata.jarPath.toString),
          "sha256" -> metadata.jarHash.fold("null")(json),
        ),
      ),
      "command_line"  -> json(metadata.commandLine),
      "tool_versions" -> renderObject(
        Vector(
          "java"  -> json(metadata.javaVersion),
          "scala" -> json(metadata.scalaVersion),
          "nix"   -> metadata.nixVersion.fold("null")(json),
          "sbt"   -> metadata.sbtVersion.fold("null")(json),
        ),
      ),
      "steps"         -> renderArray(manifest.steps.map(renderStep)),
      "error"         -> manifest.error.fold("null")(json),
    )
    renderObject(fields) + "\n"

  private def renderStep(step: ManifestStep): String =
    renderObject(
      Vector(
        "id"          -> json(step.id),
        "label"       -> json(step.label),
        "entrypoint"  -> json(step.entrypoint),
        "seed_start"  -> step.seedStart.fold("null")(_.toString),
        "seeds"       -> step.seeds.fold("null")(_.toString),
        "months"      -> step.months.fold("null")(_.toString),
        "output_dir"  -> json(step.outputDir.toString),
        "details"     -> renderObject(step.details.map((key, value) => key -> json(value))),
        "status"      -> json(step.status),
        "started_at"  -> step.startedAt.fold("null")(value => json(instant(value))),
        "finished_at" -> step.finishedAt.fold("null")(value => json(instant(value))),
        "artifacts"   -> renderArray(step.artifacts.map(path => json(path.toString))),
        "error"       -> step.error.fold("null")(json),
      ),
    )

  private def renderObject(fields: Vector[(String, String)]): String =
    fields.map((key, value) => s"${json(key)}:$value").mkString("{", ",", "}")

  private def renderArray(values: Vector[String]): String =
    values.mkString("[", ",", "]")

  private def json(value: String): String =
    value
      .flatMap:
        case '"'                => "\\\""
        case '\\'               => "\\\\"
        case '\b'               => "\\b"
        case '\f'               => "\\f"
        case '\n'               => "\\n"
        case '\r'               => "\\r"
        case '\t'               => "\\t"
        case ch if ch.isControl => "\\u%04x".format(ch.toInt)
        case ch                 => ch.toString
      .prepended('"')
      .appended('"')

  private def instant(value: Instant): String =
    DateTimeFormatter.ISO_INSTANT.format(value)

  private def knownFlag(flag: String): Boolean =
    flag == "--profile" || flag == "--out" || flag == "--run-id" || flag == "--jar-path" || flag == "--jar"

  private def message(err: Throwable): String =
    Option(err.getMessage).filter(_.trim.nonEmpty).getOrElse(err.getClass.getSimpleName)

  private val usage: String =
    """Usage: NightlyDiagnosticsProfileRunner [--profile smoke|nightly|extended] [--out DIR] [--run-id ID] [--jar-path PATH] [--dry-run] [--allow-dirty] [--require-main]
      |
      |Runs the documented nightly diagnostic profile from the assembled jar path.
      |Scheduled main-branch runs should pass --require-main and should not pass --allow-dirty.
      |""".stripMargin

end NightlyDiagnosticsProfileRunner
