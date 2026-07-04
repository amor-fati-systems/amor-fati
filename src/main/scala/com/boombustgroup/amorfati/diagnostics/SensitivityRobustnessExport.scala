package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.config.RobustnessScenarios
import com.boombustgroup.amorfati.config.RobustnessScenarios.{Scenario, ScenarioSet}
import com.boombustgroup.amorfati.montecarlo.core.{McSeedMonth, MetricValue}
import com.boombustgroup.amorfati.montecarlo.core.MetricValue.*
import com.boombustgroup.amorfati.montecarlo.diagnostics.McDiagnosticRunner
import com.boombustgroup.amorfati.montecarlo.io.{McTsvFile, McTsvSchema}
import com.boombustgroup.amorfati.montecarlo.timeseries.McTimeseriesSchema
import zio.ZIO
import zio.stream.ZStream

import java.nio.file.Path
import scala.util.Try

object SensitivityRobustnessExport:

  final case class Config(
      seedStart: Long = 1L,
      seeds: Int = 2,
      months: Int = 24,
      out: Path = Path.of("target/robustness"),
      scenarioSet: ScenarioSet = ScenarioSet.default,
  ):
    def seedRange: Vector[Long] = Vector.range(seedStart, seedStart + seeds.toLong)

  final case class ExportResult(paths: Vector[Path])

  private final case class MetricDef(id: String, ordinal: Int, description: String)

  private final case class SeedMetric(
      scenarioId: String,
      seed: Long,
      metric: MetricDef,
      terminal: MetricValue,
      pathMin: MetricValue,
      pathMax: MetricValue,
      pathMean: MetricValue,
  )

  private final case class EnvelopeMetric(
      scenarioId: String,
      metric: MetricDef,
      terminalMean: MetricValue,
      terminalMin: MetricValue,
      terminalMax: MetricValue,
      pathMin: MetricValue,
      pathMax: MetricValue,
      pathMean: MetricValue,
  )

  private final case class SensitivityMetric(
      scenarioId: String,
      metric: MetricDef,
      baselineMean: MetricValue,
      scenarioMean: MetricValue,
      delta: MetricValue,
      deltaPct: Option[MetricValue],
  )

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
      validConfig       <- ZIO.fromEither(validate(config))
      scenarios          = RobustnessScenarios.scenarios(validConfig.scenarioSet)
      seedMetrics       <- McTsvFile
        .writeFold(
          validConfig.out.resolve("seed-metrics.tsv"),
          McDiagnosticRunner
            .runScenarioSeeds(scenarios, validConfig.seedRange, validConfig.months, _.id, _.params)(runSeed)
            .flatMap(rows => ZStream.fromIterable(rows)),
          SeedMetricsTsvSchema,
          Vector.newBuilder[SeedMetric],
        )((builder, row) => builder += row)(DiagnosticIo.outputFailure)
        .map(_.result())
      envelopeMetrics    = summarizeEnvelope(seedMetrics)
      sensitivityMetrics = summarizeSensitivity(envelopeMetrics)
      paths             <- writeArtifactsZIO(validConfig, scenarios, envelopeMetrics, sensitivityMetrics)
    yield ExportResult(paths)

  def parseArgs(args: Vector[String]): Either[String, Config] =
    def missingValue(flag: String): Left[String, Config] = Left(s"Missing value for $flag")

    def loop(rest: Seq[String], config: Config): Either[String, Config] =
      rest match
        case Seq()                                  => Right(config)
        case Seq("--help", _*)                      => Left(usage)
        case Seq(flag, tail*) if knownFlag(flag)    =>
          tail match
            case Seq()                                         => missingValue(flag)
            case Seq(value, _*) if value.startsWith("--")      => missingValue(flag)
            case Seq(value, next*) if flag == "--seed-start"   =>
              parseLong(value, flag).flatMap(seedStart => loop(next, config.copy(seedStart = seedStart)))
            case Seq(value, next*) if flag == "--seeds"        =>
              parseInt(value, flag).flatMap(seeds => loop(next, config.copy(seeds = seeds)))
            case Seq(value, next*) if flag == "--months"       =>
              parseInt(value, flag).flatMap(months => loop(next, config.copy(months = months)))
            case Seq(value, next*) if flag == "--out"          =>
              loop(next, config.copy(out = Path.of(value)))
            case Seq(value, next*) if flag == "--scenario-set" =>
              ScenarioSet.parse(value).flatMap(scenarioSet => loop(next, config.copy(scenarioSet = scenarioSet)))
            case Seq(_, _*)                                    => Left(s"Unknown argument: $flag")
        case Seq(flag, _*) if flag.startsWith("--") => Left(s"Unknown argument: $flag")
        case Seq(value, _*)                         => Left(s"Unexpected positional argument: $value")

    loop(args, Config())

  private def validate(config: Config): Either[String, Config] =
    Either
      .cond(config.seeds > 0, config, "--seeds must be a positive integer")
      .flatMap(valid => Either.cond(valid.months > 0, valid, "--months must be a positive integer"))

  private final case class MetricAccumulator(
      terminal: Option[MetricValue],
      pathMin: Option[MetricValue],
      pathMax: Option[MetricValue],
      pathSum: MetricValue,
      observations: Int,
  ):
    def observe(value: MetricValue): MetricAccumulator =
      MetricAccumulator(
        terminal = Some(value),
        pathMin = Some(pathMin.fold(value)(current => if value < current then value else current)),
        pathMax = Some(pathMax.fold(value)(current => if value > current then value else current)),
        pathSum = pathSum + value,
        observations = observations + 1,
      )

    def toSeedMetric(scenarioId: String, seed: Long, metric: MetricDef): Either[String, SeedMetric] =
      terminal
        .toRight(s"metric ${metric.id} has no observations")
        .map: terminalValue =>
          SeedMetric(
            scenarioId,
            seed,
            metric,
            terminal = terminalValue,
            pathMin = pathMin.getOrElse(terminalValue),
            pathMax = pathMax.getOrElse(terminalValue),
            pathMean = pathSum / observations,
          )

  private object MetricAccumulator:
    val Empty: MetricAccumulator =
      MetricAccumulator(None, None, None, MetricValue.Zero, 0)

  private def runSeed(scenario: Scenario, seed: Long, months: ZStream[Any, String, McSeedMonth]): ZIO[Any, String, Vector[SeedMetric]] =
    months
      .runFold(Vector.fill(Metrics.length)(MetricAccumulator.Empty)): (acc, month) =>
        acc
          .zip(Metrics)
          .map: (metricAcc, metric) =>
            metricAcc.observe(month.row(metric.ordinal))
      .flatMap: metricAccumulators =>
        ZIO.fromEither:
          metricAccumulators
            .zip(Metrics)
            .foldLeft[Either[String, Vector[SeedMetric]]](Right(Vector.empty)): (acc, item) =>
              val (metricAcc, metric) = item
              for
                rows <- acc
                row  <- metricAcc.toSeedMetric(scenario.id, seed, metric)
              yield rows :+ row

  private def summarizeEnvelope(seedMetrics: Vector[SeedMetric]): Vector[EnvelopeMetric] =
    seedMetrics
      .groupBy(metric => metric.scenarioId -> metric.metric.id)
      .toVector
      .sortBy { case ((scenarioId, metricId), _) => scenarioId -> metricId }
      .map { case ((scenarioId, _), metrics) =>
        val metric    = metrics.head.metric
        val terminals = metrics.map(_.terminal)
        EnvelopeMetric(
          scenarioId = scenarioId,
          metric = metric,
          terminalMean = sumMetricValues(terminals) / terminals.length,
          terminalMin = terminals.min,
          terminalMax = terminals.max,
          pathMin = metrics.map(_.pathMin).min,
          pathMax = metrics.map(_.pathMax).max,
          pathMean = sumMetricValues(metrics.map(_.pathMean)) / metrics.length,
        )
      }

  private def summarizeSensitivity(envelopeMetrics: Vector[EnvelopeMetric]): Vector[SensitivityMetric] =
    val baseline = envelopeMetrics.filter(_.scenarioId == "baseline").map(metric => metric.metric.id -> metric).toMap
    envelopeMetrics
      .filterNot(_.scenarioId == "baseline")
      .flatMap: metric =>
        baseline
          .get(metric.metric.id)
          .map: base =>
            val delta = metric.terminalMean - base.terminalMean
            SensitivityMetric(
              scenarioId = metric.scenarioId,
              metric = metric.metric,
              baselineMean = base.terminalMean,
              scenarioMean = metric.terminalMean,
              delta = delta,
              deltaPct = if base.terminalMean.toLong != 0L then Some(delta.ratioTo(base.terminalMean.abs)) else None,
            )

  private def writeArtifactsZIO(
      config: Config,
      scenarios: Vector[Scenario],
      envelopeMetrics: Vector[EnvelopeMetric],
      sensitivityMetrics: Vector[SensitivityMetric],
  ): ZIO[Any, String, Vector[Path]] =
    val seedMetricsPath = config.out.resolve("seed-metrics.tsv")
    val envelopePath    = config.out.resolve("envelope-summary.tsv")
    val sensitivityPath = config.out.resolve("sensitivity-summary.tsv")
    val reportPath      = config.out.resolve("robustness-report.md")

    for
      _ <- McTsvFile.writeAll(envelopePath, envelopeMetrics, EnvelopeTsvSchema)(DiagnosticIo.outputFailure)
      _ <- McTsvFile.writeAll(sensitivityPath, sensitivityMetrics, SensitivityTsvSchema)(DiagnosticIo.outputFailure)
      _ <- DiagnosticIo.writeText(reportPath, renderReport(config, scenarios, envelopeMetrics, sensitivityMetrics))
    yield Vector(seedMetricsPath, envelopePath, sensitivityPath, reportPath)

  private val SeedMetricsTsvSchema: McTsvSchema[SeedMetric] =
    McTsvSchema(
      header = McTsvSchema.header("Scenario", "Seed", "Metric", "Terminal", "PathMin", "PathMax", "PathMean"),
      render =
        row => Vector(row.scenarioId, row.seed.toString, row.metric.id, fmt(row.terminal), fmt(row.pathMin), fmt(row.pathMax), fmt(row.pathMean)).mkString("\t"),
    )

  private val EnvelopeTsvSchema: McTsvSchema[EnvelopeMetric] =
    McTsvSchema(
      header = McTsvSchema.header("Scenario", "Metric", "TerminalMean", "TerminalMin", "TerminalMax", "PathMin", "PathMax", "PathMean"),
      render = row =>
        Vector(
          row.scenarioId,
          row.metric.id,
          fmt(row.terminalMean),
          fmt(row.terminalMin),
          fmt(row.terminalMax),
          fmt(row.pathMin),
          fmt(row.pathMax),
          fmt(row.pathMean),
        ).mkString("\t"),
    )

  private val SensitivityTsvSchema: McTsvSchema[SensitivityMetric] =
    McTsvSchema(
      header = McTsvSchema.header("Scenario", "Metric", "BaselineTerminalMean", "ScenarioTerminalMean", "Delta", "DeltaPct"),
      render = row =>
        Vector(
          row.scenarioId,
          row.metric.id,
          fmt(row.baselineMean),
          fmt(row.scenarioMean),
          fmt(row.delta),
          row.deltaPct.map(fmt).getOrElse("NA"),
        ).mkString("\t"),
    )

  private def renderReport(
      config: Config,
      scenarios: Vector[Scenario],
      envelopeMetrics: Vector[EnvelopeMetric],
      sensitivityMetrics: Vector[SensitivityMetric],
  ): String =
    val baselineRows = envelopeMetrics
      .filter(_.scenarioId == "baseline")
      .map: row =>
        markdownRow(Vector(row.metric.id, fmt(row.terminalMean), fmt(row.terminalMin), fmt(row.terminalMax), fmt(row.pathMin), fmt(row.pathMax)))

    val sensitivityRows = sensitivityMetrics.map: row =>
      markdownRow(
        Vector(
          row.scenarioId,
          row.metric.id,
          fmt(row.baselineMean),
          fmt(row.scenarioMean),
          signed(row.delta),
          row.deltaPct.map(p => signed(p * 100) + "%").getOrElse("NA"),
        ),
      )

    val scenarioRows = scenarios.map: scenario =>
      markdownRow(Vector(scenario.id, scenario.label, scenario.category, scenario.variedParameter, scenario.variation, scenario.rationale))

    val metricRows = Metrics.map: metric =>
      markdownRow(Vector(metric.id, metric.description))

    val lines =
      Vector(
        "# Sensitivity And Robustness Report",
        "",
        "Generated by `SensitivityRobustnessExport`.",
        "",
        "## Run Configuration",
        "",
        s"- Scenario set: `${config.scenarioSet.cliName}`",
        s"- Seeds: `${config.seedRange.head}` to `${config.seedRange.last}` (${config.seeds} seeds)",
        s"- Months: `${config.months}`",
        s"- Output directory: `${config.out}`",
        "",
        "## Scenario Sweep",
        "",
        markdownRow(Vector("Scenario", "Label", "Category", "Varied parameter", "Variation", "Rationale")),
        markdownRow(Vector("---", "---", "---", "---", "---", "---")),
      ) ++ scenarioRows ++ Vector(
        "",
        "## Metrics",
        "",
        markdownRow(Vector("Metric", "Description")),
        markdownRow(Vector("---", "---")),
      ) ++ metricRows ++ Vector(
        "",
        "## Stochastic Uncertainty",
        "",
        "Baseline seed variation is summarized below. `TerminalMin` and",
        "`TerminalMax` are cross-seed terminal envelopes; `PathMin` and `PathMax`",
        "are the full within-run path envelope across all baseline seeds.",
        "",
        markdownRow(Vector("Metric", "TerminalMean", "TerminalMin", "TerminalMax", "PathMin", "PathMax")),
        markdownRow(Vector("---", "---", "---", "---", "---", "---")),
      ) ++ baselineRows ++ Vector(
        "",
        "## Parameter Sensitivity",
        "",
        "The table compares each non-baseline scenario's terminal cross-seed mean",
        "against the baseline terminal cross-seed mean. This is one-at-a-time",
        "sensitivity, not a full global sensitivity design.",
        "",
        markdownRow(Vector("Scenario", "Metric", "BaselineMean", "ScenarioMean", "Delta", "DeltaPct")),
        markdownRow(Vector("---", "---", "---", "---", "---", "---")),
      ) ++ sensitivityRows ++ Vector(
        "",
        "## Runtime Guidance",
        "",
        """- Smoke check: `sbt "robustnessReport --scenario-set smoke --seeds 1 --months 6 --out target/robustness-smoke"`""",
        """- Local review default: `sbt "robustnessReport --scenario-set core --seeds 2 --months 24 --out target/robustness"`""",
        "- Heavier review run: increase to 5-10 seeds and 60-120 months after checking runtime locally.",
        "",
        "## Output Files",
        "",
        "- `seed-metrics.tsv`: per scenario, seed, and metric terminal/path statistics.",
        "- `envelope-summary.tsv`: cross-seed envelopes by scenario and metric.",
        "- `sensitivity-summary.tsv`: terminal mean deltas versus baseline.",
        "- `robustness-report.md`: this human-readable summary.",
      )

    lines.mkString("\n") + "\n"

  private def knownFlag(flag: String): Boolean =
    flag == "--seed-start" || flag == "--seeds" || flag == "--months" || flag == "--out" || flag == "--scenario-set"

  private def parseLong(value: String, name: String): Either[String, Long] =
    Try(value.toLong).toEither.left.map(_ => s"$name must be a long integer")

  private def parseInt(value: String, name: String): Either[String, Int] =
    Try(value.toInt).toEither.left.map(_ => s"$name must be an integer")

  private def markdownRow(values: Vector[String]): String =
    values.map(escapeMarkdown).mkString("| ", " | ", " |")

  private def escapeMarkdown(value: String): String =
    value.replace("|", "\\|")

  private def sumMetricValues(values: IterableOnce[MetricValue]): MetricValue =
    values.iterator.foldLeft(MetricValue.Zero)(_ + _)

  private def fmt(value: MetricValue): String =
    value.format(8)

  private def signed(value: MetricValue): String =
    if value.toLong >= 0L then "+" + fmt(value) else fmt(value)

  private def metric(column: String, description: String): MetricDef =
    val ordinal = McTimeseriesSchema.colNames.indexOf(column)
    require(ordinal >= 0, s"Unknown Monte Carlo output column: $column")
    MetricDef(column, ordinal, description)

  private val Metrics: Vector[MetricDef] = Vector(
    metric("Inflation", "annualized inflation rate"),
    metric("Unemployment", "unemployment share"),
    metric("MarketWage", "aggregate market wage"),
    metric("MeanEmployedWage", "mean employed household wage"),
    metric("DebtToGdp", "government debt to annualized GDP"),
    metric("DeficitToGdp", "government deficit to annualized GDP"),
    metric("TotalCreditToGdp", "total credit stock to annualized GDP"),
    metric("CreditToGdpGap", "macroprudential credit-to-GDP gap"),
    metric("MinBankCAR", "minimum bank capital adequacy ratio"),
    metric("MinBankLCR", "minimum bank liquidity coverage ratio"),
    metric("CurrentAccount", "current account flow"),
    metric("CurrentAccountToGdp", "annualized current account to GDP ratio"),
    metric("ExRate", "PLN/EUR exchange rate"),
    metric("TotalAdoption", "automation plus hybrid adoption share"),
    metric("FirmDeaths", "monthly firm deaths"),
    metric("HouseholdBankruptcyRate", "household bankruptcy rate"),
    metric("BankFailures", "failed bank count"),
  )

  private val usage: String =
    "Usage: SensitivityRobustnessExport [--seed-start <long>] [--seeds <int>] [--months <int>] [--out <path>] [--scenario-set smoke|core]"

end SensitivityRobustnessExport
