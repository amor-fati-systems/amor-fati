package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.config.{BaselineCatalog, BaselineId, BaselineManifest, BaselineRef, PreparedScenario, ScenarioRef, ScenarioRegistry}
import com.boombustgroup.amorfati.config.ScenarioRegistry.{DeltaProvenance, ParameterDelta, ScenarioSpec}
import com.boombustgroup.amorfati.montecarlo.core.{McSeedMonth, MetricValue}
import com.boombustgroup.amorfati.montecarlo.core.MetricValue.*
import com.boombustgroup.amorfati.montecarlo.diagnostics.McDiagnosticRunner
import com.boombustgroup.amorfati.montecarlo.io.{McTsvFile, McTsvSchema}
import com.boombustgroup.amorfati.montecarlo.timeseries.McTimeseriesSchema
import zio.ZIO

import java.nio.file.Path
import scala.util.Try

object ScenarioRunExport:

  final case class Config(
      baseline: BaselineRef = BaselineRef(BaselineCatalog.LegacyDefaultsId),
      scenarios: Vector[ScenarioSpec] = ScenarioRegistry.defaultScenarioIds.flatMap(id => ScenarioRegistry.get(ScenarioRef(id)).toOption),
      seedStart: Long = 1L,
      seeds: Int = 1,
      months: Int = 12,
      runId: String = "scenario-registry",
      out: Path = Path.of("target/scenarios"),
  ):
    def seedRange: Vector[Long] = Vector.range(seedStart, seedStart + seeds.toLong)
    def runRoot: Path           = out.resolve(runId)

  final case class ExportResult(paths: Vector[Path])

  private final case class MetricDef(id: String, ordinal: Int)

  private final case class TerminalMetric(
      scenarioId: String,
      seed: Long,
      metric: MetricDef,
      value: MetricValue,
  )

  private final case class ScenarioDeltaRow(scenarioId: String, delta: ParameterDelta)

  private final case class SeedRunOutput(paths: Vector[Path], metrics: Vector[TerminalMetric])

  def main(args: Array[String]): Unit =
    parseArgs(args.toVector) match
      case Left(err)     =>
        Console.err.println(err)
        Console.err.println(usage)
        sys.exit(2)
      case Right(config) =>
        run(config) match
          case Left(err)     =>
            Console.err.println(err)
            sys.exit(1)
          case Right(result) =>
            result.paths.foreach(path => println(path.toString))

  def run(config: Config): Either[String, ExportResult] =
    DiagnosticIo.unsafeRun(runZIO(config))

  def runZIO(config: Config): ZIO[Any, String, ExportResult] =
    for
      validConfig   <- ZIO.fromEither(validate(config))
      baseline      <- ZIO.fromEither(BaselineCatalog.legacy.resolve(validConfig.baseline).left.map(_.toString))
      runs          <- ZIO.fromEither(ScenarioRegistry.prepare(baseline, validConfig.scenarios).left.map(_.toString))
      registryPath  <- DiagnosticIo.writeText(validConfig.runRoot.resolve("scenario-registry.md"), renderRegistry(baseline.manifest, validConfig.scenarios))
      deltasPath     = validConfig.runRoot.resolve("scenario-deltas.tsv")
      deltaRows      = validConfig.scenarios.flatMap(scenario => scenario.deltas.map(delta => ScenarioDeltaRow(scenario.id.value, delta)))
      _             <- McTsvFile.writeAll(deltasPath, deltaRows, DeltasTsvSchema)(DiagnosticIo.outputFailure)
      metadataPaths <- ZIO.foreach(runs): run =>
        DiagnosticIo.writeText(validConfig.runRoot.resolve(run.id).resolve("metadata.md"), renderRunMetadata(validConfig, baseline.manifest, run))
      outputs       <- McDiagnosticRunner
        .runScenarioSeeds(runs, validConfig.seedRange, validConfig.months, _.id, _.params)((run, seed, months) => runSeed(validConfig, run, seed, months))
        .runCollect
        .map(_.toVector)
      paths          = Vector(registryPath, deltasPath) ++ metadataPaths ++ outputs.flatMap(_.paths)
      metrics        = outputs.flatMap(_.metrics)
      summaryPath    = validConfig.runRoot.resolve("run-summary.tsv")
      _             <- McTsvFile.writeAll(summaryPath, metrics, RunSummaryTsvSchema)(DiagnosticIo.outputFailure)
    yield ExportResult(paths :+ summaryPath)

  def parseArgs(args: Vector[String]): Either[String, Config] =
    def missingValue(flag: String): Left[String, Config] = Left(s"Missing value for $flag")

    def loop(rest: Seq[String], config: Config): Either[String, Config] =
      rest match
        case Seq()                                  => Right(config)
        case Seq("--help", _*)                      => Left(usage)
        case Seq(flag, tail*) if knownFlag(flag)    =>
          tail match
            case Seq()                                       => missingValue(flag)
            case Seq(value, _*) if value.startsWith("--")    => missingValue(flag)
            case Seq(value, next*) if flag == "--scenarios"  =>
              ScenarioRegistry.select(value).flatMap(scenarios => loop(next, config.copy(scenarios = scenarios)))
            case Seq(value, next*) if flag == "--baseline"   =>
              BaselineId.from(value).left.map(reason => s"--baseline $reason").flatMap(id => loop(next, config.copy(baseline = BaselineRef(id))))
            case Seq(value, next*) if flag == "--seed-start" =>
              parseLong(value, flag).flatMap(seedStart => loop(next, config.copy(seedStart = seedStart)))
            case Seq(value, next*) if flag == "--seeds"      =>
              parseInt(value, flag).flatMap(seeds => loop(next, config.copy(seeds = seeds)))
            case Seq(value, next*) if flag == "--months"     =>
              parseInt(value, flag).flatMap(months => loop(next, config.copy(months = months)))
            case Seq(value, next*) if flag == "--run-id"     =>
              loop(next, config.copy(runId = value))
            case Seq(value, next*) if flag == "--out"        =>
              loop(next, config.copy(out = Path.of(value)))
            case Seq(_, _*)                                  => Left(s"Unknown argument: $flag")
        case Seq(flag, _*) if flag.startsWith("--") => Left(s"Unknown argument: $flag")
        case Seq(value, _*)                         => Left(s"Unexpected positional argument: $value")

    loop(args, Config())

  private def validate(config: Config): Either[String, Config] =
    Either
      .cond(config.seeds > 0, config, "--seeds must be a positive integer")
      .flatMap(valid => Either.cond(valid.months > 0, valid, "--months must be a positive integer"))
      .flatMap(valid => Either.cond(valid.runId.trim.nonEmpty, valid, "--run-id must be non-empty"))

  private def runSeed(
      config: Config,
      run: PreparedScenario,
      seed: Long,
      months: zio.stream.ZStream[Any, String, McSeedMonth],
  ): ZIO[Any, String, SeedRunOutput] =
    val tsvPath = config.runRoot.resolve(run.id).resolve(f"${config.runId}_${run.id}_${config.months}m_seed$seed%03d.tsv")
    McTsvFile
      .writeStreaming(
        tsvPath,
        months,
        TimeSeriesTsvSchema,
        s"Run ${run.id} seed $seed produced no monthly rows",
      )(DiagnosticIo.outputFailure)
      .map: terminal =>
        val metrics = Metrics.map(metric => TerminalMetric(run.id, seed, metric, terminal.row(metric.ordinal)))
        SeedRunOutput(Vector(tsvPath), metrics)

  private[diagnostics] def renderRegistry(baseline: BaselineManifest, scenarios: Vector[ScenarioSpec]): String =
    val rows = scenarios.map: scenario =>
      markdownRow(
        Vector(
          scenario.id.value,
          scenario.label,
          scenario.category,
          provenanceClassifications(scenario),
          sourceProviders(scenario),
          vintages(scenario),
          scenario.recommendedMonths.toString,
          scenario.seedPolicy,
          scenario.outputFolder,
        ),
      )

    val lines = Vector(
      "# Scenario Registry",
      "",
      "Generated by `ScenarioRunExport` from `ScenarioRegistry`.",
      "",
      s"- Resolved baseline: `${baseline.id}`",
      "- The baseline run has no scenario patch.",
      "",
      markdownRow(Vector("Scenario", "Label", "Category", "Provenance", "Source / provider", "Vintage", "Recommended months", "Seed policy", "Output folder")),
      markdownRow(Vector("---", "---", "---", "---", "---", "---", "---", "---", "---")),
    ) ++ rows

    lines.mkString("\n") + "\n"

  private[diagnostics] def renderDeltas(scenarios: Vector[ScenarioSpec]): String =
    val rows = scenarios.flatMap(scenario => scenario.deltas.map(delta => ScenarioDeltaRow(scenario.id.value, delta)))
    renderTsv(DeltasTsvSchema, rows)

  private[diagnostics] def renderRunMetadata(config: Config, baseline: BaselineManifest, run: PreparedScenario): String =
    val scenario    = run.scenario
    val channelRows = scenario.toVector.flatMap(_.expectedChannels).map(channel => s"- `$channel`")
    val deltaRows   = scenario.toVector
      .flatMap(_.deltas)
      .map: delta =>
        val provenance = delta.provenance
        markdownRow(
          Vector(
            delta.parameter,
            delta.baseline,
            delta.scenario,
            delta.note,
            provenance.classification.label,
            provenance.sourceProvider.filter(_.trim.nonEmpty).getOrElse("n/a"),
            provenance.vintage.filter(_.trim.nonEmpty).getOrElse("n/a"),
            transformationNotes(provenance),
          ),
        )
    val lines       =
      Vector(
        s"# ${run.label}",
        "",
        s"- Baseline id: `${baseline.id}`",
        s"- Scenario id: `${scenario.map(_.id.value).getOrElse("none")}`",
        s"- Category: `${scenario.map(_.category).getOrElse("baseline")}`",
        s"- Purpose: ${scenario.map(_.purpose).getOrElse("Unpatched resolved baseline run.")}",
        s"- Provenance classification: ${scenario.map(provenanceClassifications).getOrElse("n/a")}",
        s"- Source/provider: ${scenario.map(sourceProviders).getOrElse("n/a")}",
        s"- Vintage: ${scenario.map(vintages).getOrElse("n/a")}",
        s"- Run id: `${config.runId}`",
        s"- Months in this run: `${config.months}`",
        s"- Recommended months: `${scenario.map(_.recommendedMonths).getOrElse(config.months)}`",
        s"- Seed policy: ${scenario.map(_.seedPolicy).getOrElse("Use the same seed band as every scenario patch.")}",
        s"- Output folder: `${config.runRoot.resolve(run.id)}`",
        "",
        "## Expected Channels",
        "",
      ) ++ channelRows ++ Vector(
        "",
        "## Parameter Deltas",
        "",
        markdownRow(Vector("Parameter", "Baseline", "Scenario", "Note", "Provenance", "Source / provider", "Vintage", "Transformation notes")),
        markdownRow(Vector("---", "---", "---", "---", "---", "---", "---", "---")),
      ) ++ deltaRows

    lines.mkString("\n") + "\n"

  private val DeltasTsvSchema: McTsvSchema[ScenarioDeltaRow] =
    McTsvSchema(
      header = McTsvSchema.header(
        "Scenario",
        "Parameter",
        "Baseline",
        "ScenarioValue",
        "Note",
        "ProvenanceClassification",
        "SourceProvider",
        "Vintage",
        "TransformationNotes",
      ),
      render = row =>
        val provenance = row.delta.provenance
        Vector(
          row.scenarioId,
          row.delta.parameter,
          row.delta.baseline,
          row.delta.scenario,
          row.delta.note,
          provenance.classification.id,
          provenance.sourceProvider.getOrElse(""),
          provenance.vintage.getOrElse(""),
          provenance.transformationNotes.getOrElse(""),
        ).map(tsv).mkString("\t"),
    )

  private val RunSummaryTsvSchema: McTsvSchema[TerminalMetric] =
    McTsvSchema(
      header = McTsvSchema.header("Scenario", "Seed", "Metric", "TerminalValue"),
      render = row => Vector(row.scenarioId, row.seed.toString, row.metric.id, fmt(row.value)).mkString("\t"),
    )

  private val TimeSeriesTsvSchema: McTsvSchema[McSeedMonth] =
    McTimeseriesSchema.tsvSchema.contramap(month => (month.executionMonth, month.row))

  private def renderTsv[A](schema: McTsvSchema[A], rows: Vector[A]): String =
    (schema.header +: rows.map(schema.render)).mkString("\n") + "\n"

  private def knownFlag(flag: String): Boolean =
    flag == "--baseline" || flag == "--scenarios" || flag == "--seed-start" || flag == "--seeds" || flag == "--months" || flag == "--run-id" || flag == "--out"

  private def parseLong(value: String, name: String): Either[String, Long] =
    Try(value.toLong).toEither.left.map(_ => s"$name must be a long integer")

  private def parseInt(value: String, name: String): Either[String, Int] =
    Try(value.toInt).toEither.left.map(_ => s"$name must be an integer")

  private def markdownRow(values: Vector[String]): String =
    values.map(_.replace("|", "\\|")).mkString("| ", " | ", " |")

  private def provenanceClassifications(scenario: ScenarioSpec): String =
    aggregate(scenario.deltas.map(_.provenance.classification.label))

  private def sourceProviders(scenario: ScenarioSpec): String =
    aggregate(scenario.deltas.flatMap(_.provenance.sourceProvider))

  private def vintages(scenario: ScenarioSpec): String =
    aggregate(scenario.deltas.flatMap(_.provenance.vintage))

  private def transformationNotes(provenance: DeltaProvenance): String =
    provenance.transformationNotes.filter(_.trim.nonEmpty).getOrElse("n/a")

  private def aggregate(values: Iterable[String]): String =
    val distinct = values.iterator.map(_.trim).filter(_.nonEmpty).toVector.distinct
    if distinct.isEmpty then "n/a" else distinct.mkString(", ")

  private def tsv(value: String): String =
    if value.exists(ch => ch == '\t' || ch == '"' || ch == '\n' || ch == '\r') then "\"" + value.replace("\"", "\"\"") + "\""
    else value

  private def fmt(value: MetricValue): String =
    value.format(8)

  private def metric(column: String): MetricDef =
    val ordinal = McTimeseriesSchema.colNames.indexOf(column)
    require(ordinal >= 0, s"Unknown Monte Carlo output column: $column")
    MetricDef(column, ordinal)

  private val Metrics: Vector[MetricDef] = Vector(
    metric("Inflation"),
    metric("Unemployment"),
    metric("MarketWage"),
    metric("MeanEmployedWage"),
    metric("DebtToGdp"),
    metric("DeficitToGdp"),
    metric("CurrentAccount"),
    metric("CurrentAccountToGdp"),
    metric("ExRate"),
    metric("TotalCreditToGdp"),
    metric("CreditToGdpGap"),
    metric("MinBankCAR"),
    metric("MinBankLCR"),
    metric("BankFailures"),
    metric("FirmDeaths"),
    metric("HouseholdBankruptcies"),
    metric("HouseholdBankruptcyRate"),
  )

  private val usage: String =
    "Usage: ScenarioRunExport [--baseline <id>] [--scenarios monetary-tightening,fiscal-expansion|all|none] [--seed-start <long>] [--seeds <int>] [--months <int>] [--run-id <id>] [--out <path>]"

end ScenarioRunExport
