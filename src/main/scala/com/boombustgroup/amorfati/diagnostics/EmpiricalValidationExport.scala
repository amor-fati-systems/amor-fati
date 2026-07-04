package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.montecarlo.io.{DelimitedTextFile, DelimitedTextFormat, DelimitedTextRows, DelimitedTextSchema}
import com.boombustgroup.amorfati.util.BuildInfo
import zio.ZIO

import java.nio.file.{Files, Path}
import java.time.LocalDate
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

object EmpiricalValidationExport:

  final case class Config(
      sourceManifest: Path = Path.of("docs/empirical-validation-source-manifest.tsv"),
      runManifest: Option[Path] = None,
      mcDir: Path = Path.of("mc"),
      out: Path = Path.of("target/empirical-validation"),
      runId: String = "validation-baseline",
      outputPrefix: String = "validation-baseline",
      durationMonths: Int = 120,
      seeds: Int = 0,
      commit: String = BuildInfo.gitCommit,
      parameterBranch: String = "unknown",
  )

  enum CliCommand:
    case Export(config: Config)
    case Help

  final case class ExportResult(paths: Vector[Path], rows: Vector[SnapshotRow])

  private type TsvRow = DelimitedTextRows.Row

  extension (row: TsvRow)
    private def decimal(column: String): Either[String, BigDecimal] =
      row.required(column).flatMap(parseDecimal(_, column))

  final case class ModelRunManifest(
      runId: String,
      outputPrefix: String,
      durationMonths: Int,
      seedCount: Int,
      commit: String,
      parameterBranch: String,
      outputDir: Path,
  ):
    def filePrefix: String = s"${outputPrefix}_${runId}_${durationMonths}m"
    def label: String      =
      val stableRef =
        if parameterBranch.trim.nonEmpty && parameterBranch != "unknown" then s"$parameterBranch@$commit"
        else commit
      s"$stableRef, $seedCount seeds, ${durationMonths}m, run-id $runId"

  enum SourceStatus(val token: String):
    case Ready               extends SourceStatus("READY")
    case Partial             extends SourceStatus("PARTIAL")
    case MissingOutput       extends SourceStatus("MISSING_OUTPUT")
    case MissingDataBridge   extends SourceStatus("MISSING_DATA_BRIDGE")
    case MissingSourceDetail extends SourceStatus("MISSING_SOURCE_DETAIL")
    case BridgeAssumption    extends SourceStatus("BRIDGE_ASSUMPTION")

  object SourceStatus:
    def parse(value: String): Either[String, SourceStatus] =
      normalize(value) match
        case "READY" | "READY_FOR_BASELINE"      => Right(Ready)
        case "PARTIAL" | "PARTIAL_OUTPUT"        => Right(Partial)
        case "MISSING_OUTPUT"                    => Right(MissingOutput)
        case "MISSING_DATA_BRIDGE"               => Right(MissingDataBridge)
        case "MISSING_SOURCE_DETAIL"             => Right(MissingSourceDetail)
        case "BRIDGE_ASSUMPTION"                 => Right(BridgeAssumption)
        case "NOT_RUN"                           => Right(Partial)
        case "PASS_BASELINE_PROBE" | "FOLLOW_UP" => Right(Partial)
        case other                               => Left(s"Unknown manifest status: $other")

  enum SnapshotStatus(val token: String):
    case PassBaseline      extends SnapshotStatus("PASS_BASELINE")
    case FailBaseline      extends SnapshotStatus("FAIL_BASELINE")
    case Partial           extends SnapshotStatus("PARTIAL")
    case MissingOutput     extends SnapshotStatus("MISSING_OUTPUT")
    case MissingDataBridge extends SnapshotStatus("MISSING_DATA_BRIDGE")

  /** Statistic applied to a model output surface before it is compared with an
    * empirical source-manifest row.
    */
  enum ModelStatistic(val token: String):
    /** Last observed value in the selected output surface. */
    case Terminal extends ModelStatistic("terminal")

    /** First observed value in the selected output surface. */
    case First extends ModelStatistic("first")

    /** Arithmetic mean over all selected observations. */
    case Mean extends ModelStatistic("mean")

    /** Minimum selected observation. */
    case Min extends ModelStatistic("min")

    /** Maximum selected observation. */
    case Max extends ModelStatistic("max")

    /** Sum of all selected observations. */
    case Sum extends ModelStatistic("sum")

    /** Per-seed first-to-last level change, computed as last - first. */
    case Delta extends ModelStatistic("delta")

    /** Per-seed first-to-last stock growth, computed as last / first - 1. */
    case PctChange extends ModelStatistic("pct_change")

  object ModelStatistic:
    def parse(value: String): Either[String, ModelStatistic] =
      normalize(value) match
        case "TERMINAL" | "LAST"                                          => Right(Terminal)
        case "FIRST"                                                      => Right(First)
        case "MEAN" | "AVG"                                               => Right(Mean)
        case "MIN"                                                        => Right(Min)
        case "MAX"                                                        => Right(Max)
        case "SUM"                                                        => Right(Sum)
        case "DELTA" | "DIFF" | "CHANGE" | "FIRST_TO_LAST_DELTA"          =>
          Right(Delta)
        case "PCT_CHANGE" | "PCTCHANGE" | "PCT-CHANGE" | "PERCENT_CHANGE" =>
          Right(PctChange)
        case other                                                        => Left(s"Unknown model statistic: $other")

  enum ModelSurface(val token: String):
    case TimeSeries        extends ModelSurface("timeseries")
    case TerminalHousehold extends ModelSurface("terminal_hh")
    case TerminalBanks     extends ModelSurface("terminal_banks")
    case TerminalFirms     extends ModelSurface("terminal_firms")

  object ModelSurface:
    def parse(value: String): Either[String, ModelSurface] =
      normalize(value) match
        case "TIMESERIES" | "TIME_SERIES" | "SERIES" => Right(TimeSeries)
        case "TERMINAL_HH" | "HH" | "HOUSEHOLD"      => Right(TerminalHousehold)
        case "TERMINAL_BANKS" | "BANKS" | "BANK"     => Right(TerminalBanks)
        case "TERMINAL_FIRMS" | "FIRMS" | "FIRM"     => Right(TerminalFirms)
        case other                                   => Left(s"Unknown model target surface: $other")

  final case class ModelTarget(surface: ModelSurface, column: String, statistic: ModelStatistic):
    def render: String = s"${surface.token}:$column:${statistic.token}"

  object ModelTarget:
    def parse(value: String): Either[String, ModelTarget] =
      value.split(":", -1).toVector match
        case Vector(surface, column, statistic) if column.trim.nonEmpty =>
          for
            parsedSurface   <- ModelSurface.parse(surface)
            parsedStatistic <- ModelStatistic.parse(statistic)
          yield ModelTarget(parsedSurface, column.trim, parsedStatistic)
        case _                                                          =>
          Left("model_target must use <timeseries|terminal_hh|terminal_banks|terminal_firms>:<column>:<terminal|first|mean|min|max|sum|delta|pct_change>")

  final case class SourceManifestRow(
      target: String,
      sourceProvider: String,
      sourceUrl: String,
      datasetCode: String,
      vintage: String,
      accessedAt: Option[LocalDate],
      licenseOrReuseNote: String,
      frequency: String,
      unit: String,
      transformation: String,
      modelTarget: ModelTarget,
      status: SourceStatus,
      empiricalValue: Option[BigDecimal],
      tolerance: Option[BigDecimal],
      criterion: String,
      notes: String,
  )

  final case class SnapshotRow(
      target: String,
      sourceProvider: String,
      sourceUrl: String,
      datasetCode: String,
      vintage: String,
      accessedAt: Option[LocalDate],
      frequency: String,
      unit: String,
      transformation: String,
      modelRun: String,
      modelTarget: String,
      empiricalValue: Option[BigDecimal],
      modelValue: Option[BigDecimal],
      tolerance: Option[BigDecimal],
      criterion: String,
      status: SnapshotStatus,
      notes: String,
  )

  def main(args: Array[String]): Unit =
    parseArgs(args.toVector) match
      case Left(err)                        =>
        Console.err.println(err)
        Console.err.println(usage)
        sys.exit(2)
      case Right(CliCommand.Help)           =>
        println(usage)
      case Right(CliCommand.Export(config)) =>
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
      validConfig <- ZIO.fromEither(validate(config))
      sourceRows  <- ZIO.fromEither(readSourceManifest(validConfig.sourceManifest))
      baseRun     <- ZIO.fromEither(readOrBuildRunManifest(validConfig))
      modelRun    <- ZIO.fromEither(inferSeedCount(baseRun))
      output      <- ZIO.fromEither(MonteCarloOutput.load(modelRun))
      snapshot     = buildSnapshot(sourceRows, modelRun, output)
      paths       <- writeArtifactsZIO(validConfig.out, sourceRows, modelRun, snapshot)
    yield ExportResult(paths, snapshot)

  def parseArgs(args: Vector[String]): Either[String, CliCommand] =
    def missingValue(flag: String): Left[String, Config] = Left(s"Missing value for $flag")

    def loop(rest: Seq[String], config: Config): Either[String, Config] =
      rest match
        case Seq()                                  => Right(config)
        case Seq(flag, tail*) if knownFlag(flag)    =>
          tail match
            case Seq()                                             => missingValue(flag)
            case Seq(value, _*) if value.startsWith("--")          => missingValue(flag)
            case Seq(value, next*) if flag == "--source-manifest"  =>
              loop(next, config.copy(sourceManifest = Path.of(value)))
            case Seq(value, next*) if flag == "--run-manifest"     =>
              loop(next, config.copy(runManifest = Some(Path.of(value))))
            case Seq(value, next*) if flag == "--mc-dir"           =>
              loop(next, config.copy(mcDir = Path.of(value)))
            case Seq(value, next*) if flag == "--out"              =>
              loop(next, config.copy(out = Path.of(value)))
            case Seq(value, next*) if flag == "--run-id"           =>
              loop(next, config.copy(runId = value))
            case Seq(value, next*) if flag == "--output-prefix"    =>
              loop(next, config.copy(outputPrefix = value))
            case Seq(value, next*) if flag == "--duration"         =>
              parseInt(value, flag).flatMap(duration => loop(next, config.copy(durationMonths = duration)))
            case Seq(value, next*) if flag == "--seeds"            =>
              parseInt(value, flag).flatMap(seeds => loop(next, config.copy(seeds = seeds)))
            case Seq(value, next*) if flag == "--commit"           =>
              loop(next, config.copy(commit = value))
            case Seq(value, next*) if flag == "--parameter-branch" =>
              loop(next, config.copy(parameterBranch = value))
            case Seq(_, _*)                                        => Left(s"Unknown argument: $flag")
        case Seq(flag, _*) if flag.startsWith("--") => Left(s"Unknown argument: $flag")
        case Seq(value, _*)                         => Left(s"Unexpected positional argument: $value")

    args match
      case Vector("--help") => Right(CliCommand.Help)
      case _                => loop(args, Config()).map(CliCommand.Export.apply)

  private def validate(config: Config): Either[String, Config] =
    Either
      .cond(Files.exists(config.sourceManifest), config, s"Source manifest does not exist: ${config.sourceManifest}")
      .flatMap(valid => Either.cond(valid.durationMonths > 0, valid, "--duration must be a positive integer"))
      .flatMap(valid => Either.cond(valid.seeds >= 0, valid, "--seeds must be zero or a positive integer"))
      .flatMap(valid => Either.cond(valid.runId.trim.nonEmpty, valid, "--run-id must be non-empty"))
      .flatMap(valid => Either.cond(valid.outputPrefix.trim.nonEmpty, valid, "--output-prefix must be non-empty"))
      .flatMap(valid =>
        valid.runManifest match
          case Some(path) => Either.cond(Files.exists(path), valid, s"Run manifest does not exist: $path")
          case None       => Right(valid),
      )

  private def readOrBuildRunManifest(config: Config): Either[String, ModelRunManifest] =
    config.runManifest match
      case Some(path) => readModelRunManifest(path, config.mcDir)
      case None       =>
        Right(
          ModelRunManifest(
            runId = config.runId,
            outputPrefix = config.outputPrefix,
            durationMonths = config.durationMonths,
            seedCount = config.seeds,
            commit = config.commit,
            parameterBranch = config.parameterBranch,
            outputDir = config.mcDir,
          ),
        )

  private def inferSeedCount(manifest: ModelRunManifest): Either[String, ModelRunManifest] =
    if manifest.seedCount > 0 then Right(manifest)
    else
      matchingSeedFiles(manifest).map: paths =>
        manifest.copy(seedCount = paths.length)

  private def readSourceManifest(path: Path): Either[String, Vector[SourceManifestRow]] =
    DelimitedTextRows
      .readRows(path, DelimitedTextFormat.Tsv, SourceManifestHeader)
      .flatMap: rows =>
        sequence(rows.zipWithIndex.map((row, idx) => parseSourceRow(row).left.map(err => s"$path row ${idx + 2}: $err")))

  private def parseSourceRow(row: TsvRow): Either[String, SourceManifestRow] =
    val parsed =
      for
        target      <- row.required("target")
        modelTarget <- row.required("model_target").flatMap(ModelTarget.parse)
        status      <- row.required("status").flatMap(SourceStatus.parse)
        accessedAt  <- parseOptionalDate(row.optional("accessed_at"), "accessed_at")
        empirical   <- parseOptionalDecimal(row.optional("empirical_value"), "empirical_value")
        tolerance   <- parseOptionalDecimal(row.optional("tolerance"), "tolerance")
      yield SourceManifestRow(
        target = target,
        sourceProvider = row.optional("source_provider").getOrElse(""),
        sourceUrl = row.optional("source_url").getOrElse(""),
        datasetCode = row.optional("dataset_code").getOrElse(""),
        vintage = row.optional("vintage").getOrElse(""),
        accessedAt = accessedAt,
        licenseOrReuseNote = row.optional("license_or_reuse_note").getOrElse(""),
        frequency = row.optional("frequency").getOrElse(""),
        unit = row.optional("unit").getOrElse(""),
        transformation = row.optional("transformation").getOrElse(""),
        modelTarget = modelTarget,
        status = status,
        empiricalValue = empirical,
        tolerance = tolerance,
        criterion = row.optional("criterion").getOrElse(""),
        notes = row.optional("notes").getOrElse(""),
      )
    parsed.flatMap(validateSourceRowContract)

  private def validateSourceRowContract(row: SourceManifestRow): Either[String, SourceManifestRow] =
    row.status match
      case SourceStatus.Ready =>
        val errors = Vector(
          Option.when(row.empiricalValue.isEmpty)("READY row must have numeric empirical_value"),
          Option.when(row.tolerance.isEmpty)("READY row must have numeric tolerance"),
          Option.when(row.criterion.trim.isEmpty)("READY row must have criterion"),
        ).flatten
        Either.cond(errors.isEmpty, row, s"${row.target}: ${errors.mkString("; ")}")
      case _                  =>
        Right(row)

  private def readModelRunManifest(path: Path, fallbackOutputDir: Path): Either[String, ModelRunManifest] =
    DelimitedTextRows
      .readRows(path, DelimitedTextFormat.Tsv, ModelRunManifestHeader)
      .flatMap:
        case Vector(row) => parseModelRunManifest(row, fallbackOutputDir).left.map(err => s"$path row 2: $err")
        case rows        => Left(s"$path must contain exactly one model run row, got ${rows.length}")

  private def parseModelRunManifest(row: TsvRow, fallbackOutputDir: Path): Either[String, ModelRunManifest] =
    for
      runId          <- row.required("run_id")
      outputPrefix   <- row.required("output_prefix")
      durationMonths <- row.required("duration_months").flatMap(parsePositiveInt(_, "duration_months"))
      seedCount      <- row.required("seed_count").flatMap(parseNonNegativeInt(_, "seed_count"))
    yield ModelRunManifest(
      runId = runId,
      outputPrefix = outputPrefix,
      durationMonths = durationMonths,
      seedCount = seedCount,
      commit = row.optional("commit").getOrElse("unknown"),
      parameterBranch = row.optional("parameter_branch").getOrElse("unknown"),
      outputDir = row.optional("output_dir").map(Path.of(_)).getOrElse(fallbackOutputDir),
    )

  private def buildSnapshot(
      sourceRows: Vector[SourceManifestRow],
      modelRun: ModelRunManifest,
      output: MonteCarloOutput,
  ): Vector[SnapshotRow] =
    sourceRows.map: row =>
      val modelAttempt = output.evaluate(row.modelTarget)
      val modelValue   = modelAttempt.toOption
      val status       = snapshotStatus(row, modelAttempt)
      val notes        = snapshotNotes(row, modelAttempt)
      SnapshotRow(
        target = row.target,
        sourceProvider = row.sourceProvider,
        sourceUrl = row.sourceUrl,
        datasetCode = row.datasetCode,
        vintage = row.vintage,
        accessedAt = row.accessedAt,
        frequency = row.frequency,
        unit = row.unit,
        transformation = row.transformation,
        modelRun = modelRun.label,
        modelTarget = row.modelTarget.render,
        empiricalValue = row.empiricalValue,
        modelValue = modelValue,
        tolerance = row.tolerance,
        criterion = row.criterion,
        status = status,
        notes = notes,
      )

  private def snapshotStatus(row: SourceManifestRow, modelAttempt: Either[String, BigDecimal]): SnapshotStatus =
    row.status match
      case SourceStatus.MissingOutput                                        =>
        SnapshotStatus.MissingOutput
      case SourceStatus.MissingDataBridge | SourceStatus.MissingSourceDetail =>
        modelAttempt.fold(_ => SnapshotStatus.MissingOutput, _ => SnapshotStatus.MissingDataBridge)
      case SourceStatus.Partial                                              =>
        modelAttempt.fold(_ => SnapshotStatus.MissingOutput, _ => SnapshotStatus.Partial)
      case SourceStatus.Ready | SourceStatus.BridgeAssumption                =>
        modelAttempt match
          case Left(_)      => SnapshotStatus.MissingOutput
          case Right(model) =>
            (row.empiricalValue, row.tolerance) match
              case (None, _)            => SnapshotStatus.MissingDataBridge
              case (Some(_), None)      => SnapshotStatus.Partial
              case (Some(emp), Some(t)) =>
                if (model - emp).abs <= t.abs then SnapshotStatus.PassBaseline
                else SnapshotStatus.FailBaseline

  private def snapshotNotes(row: SourceManifestRow, modelAttempt: Either[String, BigDecimal]): String =
    val base    = Vector(row.notes).filter(_.nonEmpty)
    val derived = modelAttempt.left.toOption match
      case Some(err) => base :+ s"Model output unavailable: $err"
      case None      =>
        row.status match
          case SourceStatus.MissingDataBridge | SourceStatus.MissingSourceDetail =>
            base :+ s"Source status: ${row.status.token}"
          case SourceStatus.BridgeAssumption                                     =>
            base :+ "Source uses a documented bridge assumption"
          case _                                                                 =>
            base
    derived.mkString(" ")

  private def writeArtifactsZIO(
      out: Path,
      sourceRows: Vector[SourceManifestRow],
      modelRun: ModelRunManifest,
      snapshot: Vector[SnapshotRow],
  ): ZIO[Any, String, Vector[Path]] =
    val sourceManifestPath = out.resolve("source-manifest.tsv")
    val runManifestPath    = out.resolve("model-run-manifest.tsv")
    val snapshotPath       = out.resolve("baseline-validation-snapshot.tsv")
    for
      sourcePath      <- DelimitedTextFile.writeAll(sourceManifestPath, sourceRows, SourceManifestTsvSchema, DelimitedTextFormat.Tsv)(DiagnosticIo.outputFailure)
      runPath         <- DelimitedTextFile.writeAll(runManifestPath, Vector(modelRun), ModelRunManifestTsvSchema, DelimitedTextFormat.Tsv)(DiagnosticIo.outputFailure)
      snapshotTsvPath <- DelimitedTextFile.writeAll(snapshotPath, snapshot, SnapshotTsvSchema, DelimitedTextFormat.Tsv)(DiagnosticIo.outputFailure)
    yield Vector(sourcePath, runPath, snapshotTsvPath)

  private final case class MonteCarloOutput(
      timeSeries: Vector[Vector[TsvRow]],
      household: Option[Vector[TsvRow]],
      banks: Option[Vector[TsvRow]],
      firms: Option[Vector[TsvRow]],
  ):
    def evaluate(target: ModelTarget): Either[String, BigDecimal] =
      target.surface match
        case ModelSurface.TimeSeries        => evaluateTimeSeries(target.column, target.statistic)
        case ModelSurface.TerminalHousehold => evaluateTerminal("household", household, target.column, target.statistic)
        case ModelSurface.TerminalBanks     => evaluateTerminal("banks", banks, target.column, target.statistic)
        case ModelSurface.TerminalFirms     => evaluateTerminal("firms", firms, target.column, target.statistic)

    private def evaluateTimeSeries(column: String, statistic: ModelStatistic): Either[String, BigDecimal] =
      if timeSeries.isEmpty then Left("no per-seed timeseries TSV files found")
      else
        statistic match
          case ModelStatistic.Terminal  =>
            sequence(timeSeries.map(rows => terminalValue(rows, column, "last"))).map(mean)
          case ModelStatistic.First     =>
            sequence(timeSeries.map(rows => firstValue(rows, column))).map(mean)
          case ModelStatistic.Mean      =>
            aggregate(allTimeSeriesValues(column), statistic)
          case ModelStatistic.Min       =>
            aggregate(allTimeSeriesValues(column), statistic)
          case ModelStatistic.Max       =>
            aggregate(allTimeSeriesValues(column), statistic)
          case ModelStatistic.Sum       =>
            aggregate(allTimeSeriesValues(column), statistic)
          case ModelStatistic.Delta     =>
            sequence(timeSeries.map(rows => delta(rows, column))).map(mean)
          case ModelStatistic.PctChange =>
            sequence(timeSeries.map(rows => pctChange(rows, column))).map(mean)

    private def evaluateTerminal(
        label: String,
        rowsOpt: Option[Vector[TsvRow]],
        column: String,
        statistic: ModelStatistic,
    ): Either[String, BigDecimal] =
      rowsOpt match
        case None       => Left(s"terminal $label summary TSV not found")
        case Some(rows) =>
          val values = sequence(rows.map(_.decimal(column)))
          statistic match
            case ModelStatistic.Terminal | ModelStatistic.Mean => aggregate(values, ModelStatistic.Mean)
            case ModelStatistic.First                          => firstValue(rows, column)
            case ModelStatistic.Min                            => aggregate(values, ModelStatistic.Min)
            case ModelStatistic.Max                            => aggregate(values, ModelStatistic.Max)
            case ModelStatistic.Sum                            => aggregate(values, ModelStatistic.Sum)
            case ModelStatistic.Delta                          => Left("delta is only supported for timeseries model targets")
            case ModelStatistic.PctChange                      => Left("pct_change is only supported for timeseries model targets")

    private def allTimeSeriesValues(column: String): Either[String, Vector[BigDecimal]] =
      sequence(timeSeries.flatMap(rows => rows.map(_.decimal(column))))

    private def terminalValue(rows: Vector[TsvRow], column: String, position: String): Either[String, BigDecimal] =
      rows.lastOption.toRight(s"seed timeseries has no $position row").flatMap(_.decimal(column))

    private def firstValue(rows: Vector[TsvRow], column: String): Either[String, BigDecimal] =
      rows.headOption.toRight("TSV has no first row").flatMap(_.decimal(column))

    private def pctChange(rows: Vector[TsvRow], column: String): Either[String, BigDecimal] =
      for
        first <- firstValue(rows, column)
        last  <- terminalValue(rows, column, "last")
        value <- Either.cond(first != BigDecimal(0), last / first - BigDecimal(1), s"cannot compute pct_change for $column because first value is zero")
      yield value

    private def delta(rows: Vector[TsvRow], column: String): Either[String, BigDecimal] =
      for
        first <- firstValue(rows, column)
        last  <- terminalValue(rows, column, "last")
      yield last - first

    private def aggregate(valuesAttempt: Either[String, Vector[BigDecimal]], statistic: ModelStatistic): Either[String, BigDecimal] =
      valuesAttempt.flatMap: values =>
        if values.isEmpty then Left("no numeric values found")
        else
          statistic match
            case ModelStatistic.Mean | ModelStatistic.Terminal => Right(mean(values))
            case ModelStatistic.Min                            => Right(values.min)
            case ModelStatistic.Max                            => Right(values.max)
            case ModelStatistic.Sum                            => Right(values.sum)
            case ModelStatistic.First                          => Right(values.head)
            case ModelStatistic.Delta                          => Left("delta requires per-seed first and last timeseries values")
            case ModelStatistic.PctChange                      => Left("pct_change requires per-seed first and last timeseries values")

  private object MonteCarloOutput:
    def load(manifest: ModelRunManifest): Either[String, MonteCarloOutput] =
      if !Files.isDirectory(manifest.outputDir) then Left(s"Monte Carlo output directory does not exist: ${manifest.outputDir}")
      else
        for
          seedFiles <- matchingSeedFiles(manifest)
          series    <- sequence(seedFiles.map(readTsv))
          household <- readOptionalTsv(manifest.outputDir.resolve(s"${manifest.filePrefix}_hh.tsv"))
          banks     <- readOptionalTsv(manifest.outputDir.resolve(s"${manifest.filePrefix}_banks.tsv"))
          firms     <- readOptionalTsv(manifest.outputDir.resolve(s"${manifest.filePrefix}_firms.tsv"))
        yield MonteCarloOutput(series, household, banks, firms)

  private def matchingSeedFiles(manifest: ModelRunManifest): Either[String, Vector[Path]] =
    if !Files.isDirectory(manifest.outputDir) then Left(s"Monte Carlo output directory does not exist: ${manifest.outputDir}")
    else if manifest.seedCount > 0 then
      val expected = (1 to manifest.seedCount).map(seed => manifest.outputDir.resolve(f"${manifest.filePrefix}_seed$seed%03d.tsv")).toVector
      val existing = expected.filter(Files.exists(_))
      if existing.length == expected.length then Right(existing)
      else
        val missing = expected.filterNot(Files.exists(_)).map(_.getFileName.toString)
        Left(s"Missing expected seed TSV files: ${missing.mkString(", ")}")
    else
      listDirectory(manifest.outputDir).map: paths =>
        paths
          .filter(path => path.getFileName.toString.matches(java.util.regex.Pattern.quote(manifest.filePrefix) + "_seed[0-9]+\\.tsv"))
          .sortBy(_.getFileName.toString)

  private def readOptionalTsv(path: Path): Either[String, Option[Vector[TsvRow]]] =
    if Files.exists(path) then readTsv(path).map(Some(_))
    else Right(None)

  private def readTsv(path: Path): Either[String, Vector[TsvRow]] =
    DelimitedTextRows.readRows(path, DelimitedTextFormat.Tsv)

  private[diagnostics] lazy val SourceManifestTsvSchema: DelimitedTextSchema[SourceManifestRow] =
    DelimitedTextSchema.fromCells(SourceManifestHeader, DelimitedTextFormat.Tsv): row =>
      Vector(
        row.target,
        row.sourceProvider,
        row.sourceUrl,
        row.datasetCode,
        row.vintage,
        row.accessedAt.map(_.toString).getOrElse(""),
        row.licenseOrReuseNote,
        row.frequency,
        row.unit,
        row.transformation,
        row.modelTarget.render,
        row.status.token,
        row.empiricalValue.map(formatDecimal).getOrElse(""),
        row.tolerance.map(formatDecimal).getOrElse(""),
        row.criterion,
        row.notes,
      )

  private[diagnostics] lazy val ModelRunManifestTsvSchema: DelimitedTextSchema[ModelRunManifest] =
    DelimitedTextSchema.fromCells(ModelRunManifestHeader, DelimitedTextFormat.Tsv): manifest =>
      Vector(
        manifest.runId,
        manifest.outputPrefix,
        manifest.durationMonths.toString,
        manifest.seedCount.toString,
        manifest.commit,
        manifest.parameterBranch,
        manifest.outputDir.toString,
      )

  private[diagnostics] lazy val SnapshotTsvSchema: DelimitedTextSchema[SnapshotRow] =
    DelimitedTextSchema.fromCells(SnapshotHeader, DelimitedTextFormat.Tsv): row =>
      Vector(
        row.target,
        row.sourceProvider,
        row.sourceUrl,
        row.datasetCode,
        row.vintage,
        row.accessedAt.map(_.toString).getOrElse(""),
        row.frequency,
        row.unit,
        row.transformation,
        row.modelRun,
        row.modelTarget,
        row.empiricalValue.map(formatSnapshotDecimal).getOrElse(""),
        row.modelValue.map(formatSnapshotDecimal).getOrElse(""),
        row.tolerance.map(formatSnapshotDecimal).getOrElse(""),
        row.criterion,
        row.status.token,
        row.notes,
      )

  private def listDirectory(path: Path): Either[String, Vector[Path]] =
    Try(Using.resource(Files.list(path))(_.iterator().asScala.toVector)).toEither.left.map(err => s"Failed to list $path: ${err.getMessage}")

  private def parseOptionalDate(value: Option[String], field: String): Either[String, Option[LocalDate]] =
    value match
      case None        => Right(None)
      case Some(input) => Try(LocalDate.parse(input)).toEither.left.map(_ => s"$field must use ISO date format yyyy-mm-dd").map(Some(_))

  private def parseOptionalDecimal(value: Option[String], field: String): Either[String, Option[BigDecimal]] =
    value match
      case None        => Right(None)
      case Some(input) => parseDecimal(input, field).map(Some(_))

  private def parseDecimal(value: String, field: String): Either[String, BigDecimal] =
    val trimmed = value.trim
    val percent = trimmed.endsWith("%")
    val raw     = if percent then trimmed.dropRight(1).trim else trimmed
    Try(BigDecimal(raw)).toEither.left.map(_ => s"$field must be numeric").map(parsed => if percent then parsed / 100 else parsed)

  private def parsePositiveInt(value: String, field: String): Either[String, Int] =
    parseInt(value, field).flatMap(parsed => Either.cond(parsed > 0, parsed, s"$field must be a positive integer"))

  private def parseNonNegativeInt(value: String, field: String): Either[String, Int] =
    parseInt(value, field).flatMap(parsed => Either.cond(parsed >= 0, parsed, s"$field must be zero or a positive integer"))

  private def parseInt(value: String, field: String): Either[String, Int] =
    Try(value.toInt).toEither.left.map(_ => s"$field must be an integer")

  private def mean(values: Vector[BigDecimal]): BigDecimal =
    values.sum / BigDecimal(values.length)

  private def formatDecimal(value: BigDecimal): String =
    value.bigDecimal.stripTrailingZeros.toPlainString

  private def formatSnapshotDecimal(value: BigDecimal): String =
    val absValue = value.abs
    val scale    =
      if absValue >= BigDecimal(100) then 2
      else if absValue >= BigDecimal(1) then 4
      else 6
    value.setScale(scale, BigDecimal.RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros.toPlainString

  private def normalize(value: String): String =
    value.trim.replace('-', '_').replace(' ', '_').toUpperCase

  private def knownFlag(flag: String): Boolean =
    flag == "--source-manifest" ||
      flag == "--run-manifest" ||
      flag == "--mc-dir" ||
      flag == "--out" ||
      flag == "--run-id" ||
      flag == "--output-prefix" ||
      flag == "--duration" ||
      flag == "--seeds" ||
      flag == "--commit" ||
      flag == "--parameter-branch"

  private def sequence[A](values: Vector[Either[String, A]]): Either[String, Vector[A]] =
    values.collectFirst { case Left(err) => err } match
      case Some(err) => Left(err)
      case None      => Right(values.collect { case Right(value) => value })

  private val SourceManifestHeader: Vector[String] = Vector(
    "target",
    "source_provider",
    "source_url",
    "dataset_code",
    "vintage",
    "accessed_at",
    "license_or_reuse_note",
    "frequency",
    "unit",
    "transformation",
    "model_target",
    "status",
    "empirical_value",
    "tolerance",
    "criterion",
    "notes",
  )

  private val ModelRunManifestHeader: Vector[String] = Vector(
    "run_id",
    "output_prefix",
    "duration_months",
    "seed_count",
    "commit",
    "parameter_branch",
    "output_dir",
  )

  private val SnapshotHeader: Vector[String] = Vector(
    "Target",
    "SourceProvider",
    "SourceUrl",
    "DatasetCode",
    "Vintage",
    "AccessedAt",
    "Frequency",
    "Unit",
    "Transformation",
    "ModelRun",
    "ModelTarget",
    "EmpiricalValue",
    "ModelValue",
    "Tolerance",
    "Criterion",
    "Status",
    "Notes",
  )

  private val usage: String =
    """Usage: EmpiricalValidationExport [--help] [--source-manifest <path>] [--run-manifest <path>] [--mc-dir <path>] [--out <path>] [--run-id <id>] [--output-prefix <prefix>] [--duration <months>] [--seeds <int>] [--commit <hash>] [--parameter-branch <name>]
      |
      |Options:
      |  --help      Show this help message""".stripMargin

end EmpiricalValidationExport
