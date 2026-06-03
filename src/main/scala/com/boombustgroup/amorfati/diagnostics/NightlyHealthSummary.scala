package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.diagnostics.NightlyDiagnosticsProfileRunner.{DiagnosticClass, ManifestStep, RunContext}
import zio.{Clock, ZIO}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.format.DateTimeFormatter
import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Builds a compact nightly health verdict from artifacts already emitted by
  * the diagnostics profile.
  *
  * This layer is intentionally post-processing only: it reads the manifest
  * steps and baseline Monte Carlo seed CSVs, applies documented threshold
  * semantics, and writes machine/human summaries without launching more
  * simulations.
  */
private[diagnostics] object NightlyHealthSummary:

  private val HealthJsonName = "health-summary.json"
  private val HealthMdName   = "health-summary.md"

  private val BaselineStepId              = "baseline-monte-carlo"
  private val NormalCreditToGdpLowerBound = BigDecimal(0)
  private val NormalCreditToGdpUpperBound = BigDecimal(10)
  private val ResidualToGdpUpperBound     = BigDecimal("0.001")
  private val RatioLowerBound             = BigDecimal(0)
  private val RatioUpperBound             = BigDecimal(1)

  private val RequiredBaselineColumns: Vector[String] = Vector(
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
  )

  private val LossRateColumns: Vector[String] = Vector(
    "BankCreditLoss_FirmDefaultRate",
    "BankCreditLoss_MortgageDefaultRate",
    "BankCreditLoss_ConsumerLoanDefaultRate",
    "BankCreditLoss_CorpBondDefaultRate",
    "ConsumerCredit_NplRatioGross",
  )

  private[diagnostics] enum HealthStatus(val id: String):
    case Passed  extends HealthStatus("PASSED")
    case Warning extends HealthStatus("WARNING")
    case Failed  extends HealthStatus("FAILED")

  private[diagnostics] enum MetricStatus(val id: String):
    case Pass extends MetricStatus("PASS")
    case Warn extends MetricStatus("WARN")
    case Fail extends MetricStatus("FAIL")
    case Info extends MetricStatus("INFO")

  private[diagnostics] final case class Metric(
      id: String,
      label: String,
      classification: String,
      status: MetricStatus,
      hard: Boolean,
      observed: String,
      threshold: String,
      source: String,
      details: String,
  )

  private[diagnostics] final case class Summary(
      profile: String,
      runId: String,
      generatedAt: Instant,
      status: HealthStatus,
      hardFailureCount: Int,
      warningCount: Int,
      metrics: Vector[Metric],
  )

  private[diagnostics] final case class Result(summary: Summary, jsonPath: Path, markdownPath: Path):
    def status: HealthStatus       = summary.status
    def hardFailureCount: Int      = summary.hardFailureCount
    def hardFailureMessage: String =
      val failedIds = summary.metrics.filter(metric => metric.hard && metric.status == MetricStatus.Fail).map(_.id)
      val rendered  = failedIds.take(6).mkString(", ")
      val suffix    = if failedIds.length > 6 then s", +${failedIds.length - 6} more" else ""
      s"Nightly health summary failed ${summary.hardFailureCount} hard metric(s): $rendered$suffix"

  def write(ctx: RunContext, steps: Vector[ManifestStep]): ZIO[Any, String, Result] =
    for
      generated <- Clock.instant
      summary   <- ZIO.attemptBlocking(build(ctx, steps, generated)).mapError(err => s"Failed to build nightly health summary: ${message(err)}")
      jsonPath  <- DiagnosticIo.writeText(ctx.runRoot.resolve(HealthJsonName), renderJson(summary))
      mdPath    <- DiagnosticIo.writeText(ctx.runRoot.resolve(HealthMdName), renderMarkdown(summary))
    yield Result(summary, jsonPath, mdPath)

  private[diagnostics] def build(ctx: RunContext, steps: Vector[ManifestStep], generatedAt: Instant): Summary =
    val metrics          = completionMetrics(steps) ++ baselineMetrics(steps)
    val hardFailureCount = metrics.count(metric => metric.hard && metric.status == MetricStatus.Fail)
    val warningCount     = metrics.count(_.status == MetricStatus.Warn)
    val status           =
      if hardFailureCount > 0 then HealthStatus.Failed
      else if warningCount > 0 then HealthStatus.Warning
      else HealthStatus.Passed

    Summary(
      profile = ctx.profile.id,
      runId = ctx.runId,
      generatedAt = generatedAt,
      status = status,
      hardFailureCount = hardFailureCount,
      warningCount = warningCount,
      metrics = metrics,
    )

  private def completionMetrics(steps: Vector[ManifestStep]): Vector[Metric] =
    DiagnosticClass.values.toVector.flatMap: classification =>
      val classSteps = steps.filter(_.classification == classification)
      Option.when(classSteps.nonEmpty):
        val failed = classSteps.filter(step => step.status != "SUCCEEDED")
        val status = if failed.nonEmpty then MetricStatus.Fail else MetricStatus.Pass
        Metric(
          id = s"diagnostic_completion.${classification.id}",
          label = s"${classification.id} step completion",
          classification = classification.id,
          status = status,
          hard = true,
          observed =
            if failed.isEmpty then s"${classSteps.length}/${classSteps.length} succeeded"
            else failed.map(step => s"${step.id}:${step.status}").mkString("; "),
          threshold = "All configured steps must complete successfully.",
          source = "run-manifest.json",
          details = classification.failurePolicy,
        )

  private def baselineMetrics(steps: Vector[ManifestStep]): Vector[Metric] =
    steps.find(_.id == BaselineStepId) match
      case None       =>
        Vector(
          metric(
            id = "normal.baseline_artifacts",
            label = "Baseline Monte Carlo artifacts",
            status = MetricStatus.Fail,
            hard = true,
            observed = "baseline-monte-carlo step missing",
            threshold = "Baseline normal-validation evidence must be present.",
            source = "run-manifest.json",
            details = "The health summary cannot evaluate normal-path economic thresholds without baseline seed CSVs.",
          ),
        )
      case Some(step) =>
        loadBaseline(step) match
          case Left(err)     =>
            Vector(
              metric(
                id = "normal.baseline_artifacts",
                label = "Baseline Monte Carlo artifacts",
                status = MetricStatus.Fail,
                hard = true,
                observed = err,
                threshold = "Baseline seed CSVs must be present, well-formed, and contain required health columns.",
                source = step.outputDir.toString,
                details = "This is an artifact/readiness failure, not an economic finding.",
              ),
            )
          case Right(series) =>
            baselineArtifactMetric(series, step) +:
              Vector(
                bankFailureMetric(series),
                gdpMetric(series),
                creditToGdpMetric(series),
                lossRatesMetric(series),
                residualMetric(series),
                negativeStockMetric(series),
              )

  private def baselineArtifactMetric(series: BaselineSeries, step: ManifestStep): Metric =
    val expectedRows = for
      seeds  <- step.seeds
      months <- step.months
    yield seeds * months
    val rowCountOk   = expectedRows.forall(_ == series.rowCount)
    metric(
      id = "normal.baseline_artifacts",
      label = "Baseline Monte Carlo artifacts",
      status = if rowCountOk then MetricStatus.Pass else MetricStatus.Fail,
      hard = true,
      observed = s"${series.files.length} seed CSV(s), ${series.rowCount} monthly row(s)",
      threshold = expectedRows.fold("At least one non-empty baseline seed CSV with all required health columns.")(rows =>
        s"Exactly $rows monthly row(s), derived from baseline seeds=${step.seeds.getOrElse("?")} and months=${step.months.getOrElse("?")}.",
      ),
      source = series.files.map(_.getFileName.toString).mkString(", "),
      details = "Seed CSVs are reused from the baseline Monte Carlo step.",
    )

  private def bankFailureMetric(series: BaselineSeries): Metric =
    val maxFailures       = series.max("BankFailures")
    val cumulativeNew     = series.sum("BankResolution_NewFailures")
    val allFailedFallback = series.sum("BankResolution_AllFailedFallback")
    val invalidInvariant  = series.sum("BankResolution_InvalidActiveBankInvariant")
    val failed            = maxFailures > 0 || cumulativeNew > 0 || allFailedFallback > 0 || invalidInvariant > 0
    metric(
      id = "normal.bank_failures",
      label = "Normal-path bank failures",
      status = if failed then MetricStatus.Fail else MetricStatus.Pass,
      hard = true,
      observed =
        s"max_bank_failures=$maxFailures; cumulative_new_failures=$cumulativeNew; all_failed_fallback=$allFailedFallback; invalid_active_bank_invariant=$invalidInvariant",
      threshold = "All bank-failure and bank-resolution failure counters must remain 0 on normal baseline paths.",
      source = "baseline Monte Carlo seed CSVs",
      details = "Stress-channel bank failures are not evaluated here; this metric only reads the normal baseline Monte Carlo step.",
    )

  private def gdpMetric(series: BaselineSeries): Metric =
    val nonPositiveRows = series.countWhere("MonthlyGdpProxy")(_ <= 0)
    val terminalDrop    = series
      .seedSeries("MonthlyGdpProxy")
      .count: values =>
        values.nonEmpty && values.last < values.head
    val status          =
      if nonPositiveRows > 0 then MetricStatus.Fail
      else if terminalDrop > 0 then MetricStatus.Warn
      else MetricStatus.Pass
    metric(
      id = "normal.gdp_direction",
      label = "GDP proxy direction",
      status = status,
      hard = nonPositiveRows > 0,
      observed = s"min_monthly_gdp=${series.min("MonthlyGdpProxy")}; terminal_below_opening_seed_count=$terminalDrop/${series.files.length}",
      threshold = "MonthlyGdpProxy must stay positive; terminal-below-opening seeds are warnings until calibrated as hard trend thresholds.",
      source = "baseline Monte Carlo seed CSVs",
      details = "The five-year profile validates operational sanity, not long-horizon cycle claims.",
    )

  private def creditToGdpMetric(series: BaselineSeries): Metric =
    val minValue = series.min("TotalCreditToGdp")
    val maxValue = series.max("TotalCreditToGdp")
    val failed   = minValue < NormalCreditToGdpLowerBound || maxValue > NormalCreditToGdpUpperBound
    metric(
      id = "normal.total_credit_to_gdp",
      label = "Total credit to GDP bound",
      status = if failed then MetricStatus.Fail else MetricStatus.Pass,
      hard = true,
      observed = s"min=$minValue; max=$maxValue",
      threshold = s"$NormalCreditToGdpLowerBound <= TotalCreditToGdp <= $NormalCreditToGdpUpperBound",
      source = "baseline Monte Carlo seed CSVs",
      details = "This is a blow-up guard shared with the baseline invariant integration gate, not a calibration target.",
    )

  private def lossRatesMetric(series: BaselineSeries): Metric =
    val extrema = LossRateColumns.map(column => column -> (series.min(column), series.max(column)))
    val failed  = extrema.exists((_, bounds) => bounds._1 < RatioLowerBound || bounds._2 > RatioUpperBound)
    metric(
      id = "normal.default_loss_rates",
      label = "Default and loss-rate bounds",
      status = if failed then MetricStatus.Fail else MetricStatus.Pass,
      hard = true,
      observed = extrema.map((column, bounds) => s"$column=[${bounds._1},${bounds._2}]").mkString("; "),
      threshold = s"All monitored default/NPL ratios must satisfy $RatioLowerBound <= value <= $RatioUpperBound.",
      source = "baseline Monte Carlo seed CSVs",
      details = "Positive defaults are allowed; impossible negative or above-100% rates are not.",
    )

  private def residualMetric(series: BaselineSeries): Metric =
    val waterfallMax = series.maxAbs("BankCapital_WaterfallResidual")
    val reconMax     = series.maxAbs("BankCapital_ReconciliationResidual")
    val fofMax       = series.maxAbs("FofResidual")
    val waterfallGdp = series.maxAbsRatio("BankCapital_WaterfallResidual", "AnnualizedGdpProxy")
    val reconGdp     = series.maxAbsRatio("BankCapital_ReconciliationResidual", "AnnualizedGdpProxy")
    val fofGdp       = series.maxAbsRatio("FofResidual", "AnnualizedGdpProxy")
    val failed       = waterfallGdp > ResidualToGdpUpperBound || reconGdp > ResidualToGdpUpperBound || fofGdp > ResidualToGdpUpperBound
    metric(
      id = "normal.accounting_residuals",
      label = "SFC/accounting residual guard",
      status = if failed then MetricStatus.Fail else MetricStatus.Pass,
      hard = true,
      observed =
        s"bank_capital_waterfall_abs_max=$waterfallMax; bank_capital_waterfall_to_gdp_max=$waterfallGdp; bank_reconciliation_abs_max=$reconMax; bank_reconciliation_to_gdp_max=$reconGdp; fof_abs_max=$fofMax; fof_to_gdp_max=$fofGdp",
      threshold = s"Runtime SFC exactness must complete; exported residuals must remain <= $ResidualToGdpUpperBound of annualized GDP.",
      source = "baseline Monte Carlo seed CSVs and runtime SFC completion",
      details = "Runtime SFC exactness failures stop the baseline step; exported residual columns catch material accounting drift at macro scale.",
    )

  private def negativeStockMetric(series: BaselineSeries): Metric =
    val maxCount = series.max("HouseholdLiquidity_NegativeDepositCount")
    val maxShare = series.max("HouseholdLiquidity_NegativeDepositShare")
    metric(
      id = "normal.negative_stock_counts",
      label = "Negative stock diagnostics",
      status = if maxCount > 0 then MetricStatus.Warn else MetricStatus.Pass,
      hard = false,
      observed = s"household_negative_deposit_count_max=$maxCount; household_negative_deposit_share_max=$maxShare",
      threshold = "Reported as warning when household negative deposit diagnostics are non-zero.",
      source = "baseline Monte Carlo seed CSVs",
      details =
        "Bank/firm hard non-negative stock invariants are enforced by runtime and integration tests; household negative deposits remain a diagnostic signal.",
    )

  private def metric(
      id: String,
      label: String,
      status: MetricStatus,
      hard: Boolean,
      observed: String,
      threshold: String,
      source: String,
      details: String,
      classification: DiagnosticClass = DiagnosticClass.NormalValidation,
  ): Metric =
    Metric(
      id = id,
      label = label,
      classification = classification.id,
      status = status,
      hard = hard,
      observed = observed,
      threshold = threshold,
      source = source,
      details = details,
    )

  private final case class BaselineSeries(files: Vector[Path], rows: Vector[BaselineRow]):
    val rowCount: Int = rows.length

    def values(column: String): Vector[BigDecimal] =
      rows.map(_.decimal(column))

    def seedSeries(column: String): Vector[Vector[BigDecimal]] =
      rows
        .groupBy(_.file)
        .toVector
        .sortBy((file, _) => file.toString)
        .map((_, fileRows) => fileRows.sortBy(_.rowNumber).map(_.decimal(column)))

    def min(column: String): BigDecimal =
      values(column).min

    def max(column: String): BigDecimal =
      values(column).max

    def maxAbs(column: String): BigDecimal =
      values(column).map(_.abs).max

    def maxAbsRatio(numerator: String, denominator: String): BigDecimal =
      rows
        .map: row =>
          val den = row.decimal(denominator).abs
          if den > 0 then row.decimal(numerator).abs / den else BigDecimal(0)
        .max

    def sum(column: String): BigDecimal =
      values(column).foldLeft(BigDecimal(0))(_ + _)

    def countWhere(column: String)(predicate: BigDecimal => Boolean): Int =
      values(column).count(predicate)

  private final case class BaselineRow(file: Path, rowNumber: Int, values: Map[String, BigDecimal]):
    def decimal(column: String): BigDecimal =
      values.getOrElse(column, throw IllegalStateException(s"Missing parsed column '$column' in ${file.getFileName}:$rowNumber"))

  private def loadBaseline(step: ManifestStep): Either[String, BaselineSeries] =
    for
      files <- seedCsvFiles(step.outputDir)
      rows  <- readSeedRows(files)
    yield BaselineSeries(files, rows)

  private def seedCsvFiles(outputDir: Path): Either[String, Vector[Path]] =
    if !Files.isDirectory(outputDir) then Left(s"baseline output directory is missing: $outputDir")
    else
      Using.resource(Files.list(outputDir)): stream =>
        val files = stream
          .iterator()
          .asScala
          .filter(path => Files.isRegularFile(path))
          .filter(path => path.getFileName.toString.endsWith(".csv"))
          .filter(path => path.getFileName.toString.contains("_seed"))
          .toVector
          .sortBy(_.toString)
        Either.cond(files.nonEmpty, files, s"baseline seed CSV files are missing under $outputDir")

  private def readSeedRows(files: Vector[Path]): Either[String, Vector[BaselineRow]] =
    val parsed = files.foldLeft[Either[String, Vector[BaselineRow]]](Right(Vector.empty)): (acc, file) =>
      acc.flatMap(rows => readSeedRows(file).map(rows ++ _))
    parsed.flatMap: rows =>
      Either.cond(rows.nonEmpty, rows, s"baseline seed CSV files contain no monthly rows: ${files.mkString(", ")}")

  private def readSeedRows(file: Path): Either[String, Vector[BaselineRow]] =
    val lines = Files.readAllLines(file, StandardCharsets.UTF_8).asScala.toVector.filter(_.trim.nonEmpty)
    if lines.isEmpty then Left(s"empty seed CSV: $file")
    else
      val header  = lines.head.split(";", -1).toVector
      val missing = RequiredBaselineColumns.filterNot(header.contains)
      if missing.nonEmpty then Left(s"${file.getFileName} is missing required health columns: ${missing.mkString(", ")}")
      else
        val index = header.zipWithIndex.toMap
        lines.tail.zipWithIndex.foldLeft[Either[String, Vector[BaselineRow]]](Right(Vector.empty)): (acc, rowWithIndex) =>
          acc.flatMap: rows =>
            val (line, zeroBasedIndex) = rowWithIndex
            val rowNumber              = zeroBasedIndex + 2
            val parts                  = line.split(";", -1)
            if parts.length < header.length then Left(s"${file.getFileName}:$rowNumber has ${parts.length} cells, expected ${header.length}")
            else parseRequiredValues(file, rowNumber, parts, index).map(parsed => rows :+ BaselineRow(file, rowNumber, parsed))

  private def parseRequiredValues(file: Path, rowNumber: Int, parts: Array[String], index: Map[String, Int]): Either[String, Map[String, BigDecimal]] =
    RequiredBaselineColumns.foldLeft[Either[String, Map[String, BigDecimal]]](Right(Map.empty)): (acc, column) =>
      acc.flatMap: parsed =>
        parseDecimal(parts(index(column)))
          .map(value => parsed.updated(column, value))
          .left
          .map: err =>
            s"${file.getFileName}:$rowNumber column $column is not numeric: $err"

  private def parseDecimal(value: String): Either[String, BigDecimal] =
    val clean = value.trim.replace(',', '.')
    if clean.isEmpty then Left("empty value")
    else
      try Right(BigDecimal(clean))
      catch case err: NumberFormatException => Left(err.getMessage)

  private[diagnostics] def renderJson(summary: Summary): String =
    val fields = Vector(
      "profile"            -> json(summary.profile),
      "run_id"             -> json(summary.runId),
      "generated_at"       -> json(instant(summary.generatedAt)),
      "status"             -> json(summary.status.id),
      "hard_failure_count" -> summary.hardFailureCount.toString,
      "warning_count"      -> summary.warningCount.toString,
      "metrics"            -> renderArray(summary.metrics.map(renderMetricJson)),
    )
    renderObject(fields) + "\n"

  private def renderMetricJson(metric: Metric): String =
    renderObject(
      Vector(
        "id"             -> json(metric.id),
        "label"          -> json(metric.label),
        "classification" -> json(metric.classification),
        "status"         -> json(metric.status.id),
        "hard"           -> metric.hard.toString,
        "observed"       -> json(metric.observed),
        "threshold"      -> json(metric.threshold),
        "source"         -> json(metric.source),
        "details"        -> json(metric.details),
      ),
    )

  private[diagnostics] def renderMarkdown(summary: Summary): String =
    val rows = summary.metrics.map: metric =>
      Vector(
        metric.id,
        metric.classification,
        metric.status.id,
        if metric.hard then "yes" else "no",
        metric.observed,
        metric.threshold,
      ).map(markdownCell).mkString("| ", " | ", " |")

    (Vector(
      "# Nightly Health Summary",
      "",
      s"- Profile: `${summary.profile}`",
      s"- Run id: `${summary.runId}`",
      s"- Generated at: `${instant(summary.generatedAt)}`",
      s"- Status: `${summary.status.id}`",
      s"- Hard failures: `${summary.hardFailureCount}`",
      s"- Warnings: `${summary.warningCount}`",
      "",
      "| Metric | Classification | Status | Hard | Observed | Threshold |",
      "| --- | --- | --- | --- | --- | --- |",
    ) ++ rows).mkString("\n") + "\n"

  private def markdownCell(value: String): String =
    value.replace("|", "\\|").replace("\n", " ").trim

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

  private def message(err: Throwable): String =
    Option(err.getMessage).filter(_.trim.nonEmpty).getOrElse(err.getClass.getSimpleName)

end NightlyHealthSummary
