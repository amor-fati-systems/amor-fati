package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.config.BankFailureAblationScenarios
import com.boombustgroup.amorfati.montecarlo.McTsvFile
import com.boombustgroup.amorfati.montecarlo.McTsvSchema
import com.boombustgroup.amorfati.montecarlo.McDiagnosticRunner
import com.boombustgroup.amorfati.montecarlo.McSeedMonth
import com.boombustgroup.amorfati.montecarlo.McTimeseriesSchema
import com.boombustgroup.amorfati.montecarlo.MetricValue
import com.boombustgroup.amorfati.montecarlo.MetricValue.*
import zio.{Cause, Ref, ZIO}
import zio.stream.ZStream

import java.nio.file.Path
import scala.math.BigDecimal.RoundingMode
import scala.util.Try

/** Controlled diagnostic ablations for #560.
  *
  * These scenarios are not production policies. They neutralize one channel at
  * a time so the resulting failure timing can identify which mechanism is doing
  * the work.
  */
object BankFailureAblationExport:

  final case class Config(
      seedStart: Long = 1L,
      seeds: Int = 5,
      months: Int = 60,
      runId: String = "bank-failure-ablations",
      out: Path = Path.of("target/bank-failure-ablations"),
  ):
    def seedRange: Vector[Long] = Vector.tabulate(seeds)(i => seedStart + i.toLong)
    def runRoot: Path           = out.resolve(runId)

  final case class SeedResult(
      runId: String,
      scenarioId: String,
      scenarioLabel: String,
      seed: Long,
      months: Int,
      observedMonths: Int,
      crashed: Int,
      crashReason: String,
      firstFailureMonth: Option[Int],
      firstFailureReasonCode: Int,
      firstFailureBankId: Int,
      terminalFailures: Int,
      peakFailures: Int,
      cumulativeNewFailures: BigDecimal,
      cumulativeRealizedCreditLoss: BigDecimal,
      cumulativeInterbankContagionLoss: BigDecimal,
      cumulativeEclProvisionChange: BigDecimal,
      cumulativeBailInLoss: BigDecimal,
      cumulativeCapitalDestruction: BigDecimal,
      cumulativeReconciliationResidualAbs: BigDecimal,
      firstFailureRealizedCreditLoss: BigDecimal,
      firstFailureEclProvisionChange: BigDecimal,
      firstFailureConsumerNplLoss: BigDecimal,
      firstFailureFirmNplLoss: BigDecimal,
      firstFailureMortgageNplLoss: BigDecimal,
      firstFailureCorpBondDefaultLoss: BigDecimal,
      firstFailureInterbankContagionLoss: BigDecimal,
      firstFailureReconciliationResidual: BigDecimal,
      firstFailureDepositBailInLoss: BigDecimal,
      terminalTotalCreditToGdp: BigDecimal,
      terminalUnemployment: BigDecimal,
      terminalInflation: BigDecimal,
      interpretation: String,
  )

  final case class SummaryResult(
      runId: String,
      scenarioId: String,
      scenarioLabel: String,
      seeds: Int,
      months: Int,
      crashedSeeds: Int,
      failedSeeds: Int,
      firstFailureMonthMean: Option[BigDecimal],
      firstFailureMonthMin: Option[Int],
      firstFailureMonthMax: Option[Int],
      terminalFailuresMean: BigDecimal,
      peakFailuresMean: BigDecimal,
      cumulativeNewFailuresMean: BigDecimal,
      cumulativeRealizedCreditLossMean: BigDecimal,
      cumulativeInterbankContagionLossMean: BigDecimal,
      cumulativeEclProvisionChangeMean: BigDecimal,
      cumulativeBailInLossMean: BigDecimal,
      cumulativeCapitalDestructionMean: BigDecimal,
      cumulativeReconciliationResidualAbsMean: BigDecimal,
      firstFailureRealizedCreditLossMean: BigDecimal,
      firstFailureEclProvisionChangeMean: BigDecimal,
      firstFailureConsumerNplLossMean: BigDecimal,
      firstFailureFirmNplLossMean: BigDecimal,
      firstFailureMortgageNplLossMean: BigDecimal,
      firstFailureCorpBondDefaultLossMean: BigDecimal,
      firstFailureInterbankContagionLossMean: BigDecimal,
      terminalTotalCreditToGdpMean: BigDecimal,
      terminalUnemploymentMean: BigDecimal,
      terminalInflationMean: BigDecimal,
      interpretation: String,
  )

  final case class ExportResult(paths: Vector[Path], seedResults: Vector[SeedResult], summary: Vector[SummaryResult])

  private val RequiredColumns: Set[String] = Set(
    "Month",
    "Inflation",
    "Unemployment",
    "TotalCreditToGdp",
    "BankFailures",
    "BankFailure_FirstNewReasonCode",
    "BankFailure_FirstNewBankId",
    "BankCapital_NewFailures",
    "BankCapital_RealizedCreditLoss",
    "BankCapital_FirmNplLoss",
    "BankCapital_MortgageNplLoss",
    "BankCapital_ConsumerNplLoss",
    "BankCapital_CorpBondDefaultLoss",
    "BankCapital_InterbankContagionLoss",
    "BankCapital_EclProvisionChange",
    "BankCapital_CapitalDestruction",
    "BankCapital_ReconciliationResidual",
    "BankCapital_DepositBailInLoss",
  )

  private val ColumnIndex: Map[String, Int] =
    McTimeseriesSchema.colNames.zipWithIndex.toMap

  private[diagnostics] val Scenarios: Vector[BankFailureAblationScenarios.Spec] =
    BankFailureAblationScenarios.all

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
      validConfig <- ZIO.fromEither(validate(config))
      seedResults <- McTsvFile
        .writeFold(
          validConfig.runRoot.resolve("bank-failure-ablation-seeds.tsv"),
          McDiagnosticRunner.runScenarioSeeds(
            Scenarios,
            validConfig.seedRange,
            validConfig.months,
            _.id,
            _.params,
          )((scenario, seed, months) => computeSeedResult(validConfig, scenario, seed, months)),
          SeedTsvSchema,
          Vector.newBuilder[SeedResult],
        )((builder, row) => builder += row)(DiagnosticIo.outputFailure)
        .map(_.result())
      summary      = summarize(validConfig, seedResults)
      paths       <- writeArtifactsZIO(validConfig, summary)
    yield ExportResult(paths, seedResults, summary)

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

  private[diagnostics] def validate(config: Config): Either[String, Config] =
    val missing = RequiredColumns.diff(ColumnIndex.keySet)
    Either
      .cond(config.seeds > 0, config, "--seeds must be a positive integer")
      .flatMap(valid => Either.cond(valid.months > 0, valid, "--months must be a positive integer"))
      .flatMap(valid => Either.cond(valid.runId.trim.nonEmpty, valid, "--run-id must be non-empty"))
      .flatMap(valid => Either.cond(missing.isEmpty, valid, s"Missing Monte Carlo columns: ${missing.toVector.sorted.mkString(", ")}"))

  private final case class FirstFailureSnapshot(
      month: Int,
      reasonCode: Int,
      bankId: Int,
      realizedCreditLoss: BigDecimal,
      eclProvisionChange: BigDecimal,
      consumerNplLoss: BigDecimal,
      firmNplLoss: BigDecimal,
      mortgageNplLoss: BigDecimal,
      corpBondDefaultLoss: BigDecimal,
      interbankContagionLoss: BigDecimal,
      reconciliationResidual: BigDecimal,
      depositBailInLoss: BigDecimal,
  )

  private object FirstFailureSnapshot:
    def from(row: Array[MetricValue]): FirstFailureSnapshot =
      FirstFailureSnapshot(
        month = colInt(row, "Month"),
        reasonCode = colInt(row, "BankFailure_FirstNewReasonCode"),
        bankId = colInt(row, "BankFailure_FirstNewBankId"),
        realizedCreditLoss = col(row, "BankCapital_RealizedCreditLoss"),
        eclProvisionChange = col(row, "BankCapital_EclProvisionChange"),
        consumerNplLoss = col(row, "BankCapital_ConsumerNplLoss"),
        firmNplLoss = col(row, "BankCapital_FirmNplLoss"),
        mortgageNplLoss = col(row, "BankCapital_MortgageNplLoss"),
        corpBondDefaultLoss = col(row, "BankCapital_CorpBondDefaultLoss"),
        interbankContagionLoss = col(row, "BankCapital_InterbankContagionLoss"),
        reconciliationResidual = col(row, "BankCapital_ReconciliationResidual"),
        depositBailInLoss = col(row, "BankCapital_DepositBailInLoss"),
      )

  private final case class SeedAccumulator(
      observedMonths: Int,
      firstFailure: Option[FirstFailureSnapshot],
      terminalRow: Option[Array[MetricValue]],
      peakFailures: Int,
      cumulativeNewFailures: BigDecimal,
      cumulativeRealizedCreditLoss: BigDecimal,
      cumulativeInterbankContagionLoss: BigDecimal,
      cumulativeEclProvisionChange: BigDecimal,
      cumulativeBailInLoss: BigDecimal,
      cumulativeCapitalDestruction: BigDecimal,
      cumulativeReconciliationResidualAbs: BigDecimal,
  ):
    def observe(row: Array[MetricValue]): SeedAccumulator =
      copy(
        observedMonths = observedMonths + 1,
        firstFailure = firstFailure.orElse(Option.when(col(row, "BankCapital_NewFailures") > BigDecimal(0))(FirstFailureSnapshot.from(row))),
        terminalRow = Some(row),
        peakFailures = scala.math.max(peakFailures, colInt(row, "BankFailures")),
        cumulativeNewFailures = cumulativeNewFailures + col(row, "BankCapital_NewFailures"),
        cumulativeRealizedCreditLoss = cumulativeRealizedCreditLoss + col(row, "BankCapital_RealizedCreditLoss"),
        cumulativeInterbankContagionLoss = cumulativeInterbankContagionLoss + col(row, "BankCapital_InterbankContagionLoss"),
        cumulativeEclProvisionChange = cumulativeEclProvisionChange + col(row, "BankCapital_EclProvisionChange"),
        cumulativeBailInLoss = cumulativeBailInLoss + col(row, "BankCapital_DepositBailInLoss"),
        cumulativeCapitalDestruction = cumulativeCapitalDestruction + col(row, "BankCapital_CapitalDestruction"),
        cumulativeReconciliationResidualAbs = cumulativeReconciliationResidualAbs + col(row, "BankCapital_ReconciliationResidual").abs,
      )

    def toSeedResult(config: Config, scenario: BankFailureAblationScenarios.Spec, seed: Long, crashReason: Option[String]): Either[String, SeedResult] =
      for
        _       <- Either.cond(
          crashReason.nonEmpty || observedMonths == config.months,
          (),
          s"bank-failure ablation expected ${config.months} monthly rows for scenario ${scenario.id} seed $seed, observed $observedMonths",
        )
        terminal = terminalRow
      yield SeedResult(
        runId = config.runId,
        scenarioId = scenario.id,
        scenarioLabel = scenario.label,
        seed = seed,
        months = config.months,
        observedMonths = observedMonths,
        crashed = if crashReason.nonEmpty then 1 else 0,
        crashReason = crashReason.getOrElse(""),
        firstFailureMonth = firstFailure.map(_.month),
        firstFailureReasonCode = firstFailure.map(_.reasonCode).getOrElse(0),
        firstFailureBankId = firstFailure.map(_.bankId).getOrElse(-1),
        terminalFailures = terminal.map(row => colInt(row, "BankFailures")).getOrElse(0),
        peakFailures = peakFailures,
        cumulativeNewFailures = cumulativeNewFailures,
        cumulativeRealizedCreditLoss = cumulativeRealizedCreditLoss,
        cumulativeInterbankContagionLoss = cumulativeInterbankContagionLoss,
        cumulativeEclProvisionChange = cumulativeEclProvisionChange,
        cumulativeBailInLoss = cumulativeBailInLoss,
        cumulativeCapitalDestruction = cumulativeCapitalDestruction,
        cumulativeReconciliationResidualAbs = cumulativeReconciliationResidualAbs,
        firstFailureRealizedCreditLoss = firstFailure.map(_.realizedCreditLoss).getOrElse(BigDecimal(0)),
        firstFailureEclProvisionChange = firstFailure.map(_.eclProvisionChange).getOrElse(BigDecimal(0)),
        firstFailureConsumerNplLoss = firstFailure.map(_.consumerNplLoss).getOrElse(BigDecimal(0)),
        firstFailureFirmNplLoss = firstFailure.map(_.firmNplLoss).getOrElse(BigDecimal(0)),
        firstFailureMortgageNplLoss = firstFailure.map(_.mortgageNplLoss).getOrElse(BigDecimal(0)),
        firstFailureCorpBondDefaultLoss = firstFailure.map(_.corpBondDefaultLoss).getOrElse(BigDecimal(0)),
        firstFailureInterbankContagionLoss = firstFailure.map(_.interbankContagionLoss).getOrElse(BigDecimal(0)),
        firstFailureReconciliationResidual = firstFailure.map(_.reconciliationResidual).getOrElse(BigDecimal(0)),
        firstFailureDepositBailInLoss = firstFailure.map(_.depositBailInLoss).getOrElse(BigDecimal(0)),
        terminalTotalCreditToGdp = terminal.map(row => col(row, "TotalCreditToGdp")).getOrElse(BigDecimal(0)),
        terminalUnemployment = terminal.map(row => col(row, "Unemployment")).getOrElse(BigDecimal(0)),
        terminalInflation = terminal.map(row => col(row, "Inflation")).getOrElse(BigDecimal(0)),
        interpretation = scenario.interpretation,
      )

  private object SeedAccumulator:
    val Empty: SeedAccumulator =
      SeedAccumulator(
        observedMonths = 0,
        firstFailure = None,
        terminalRow = None,
        peakFailures = 0,
        cumulativeNewFailures = BigDecimal(0),
        cumulativeRealizedCreditLoss = BigDecimal(0),
        cumulativeInterbankContagionLoss = BigDecimal(0),
        cumulativeEclProvisionChange = BigDecimal(0),
        cumulativeBailInLoss = BigDecimal(0),
        cumulativeCapitalDestruction = BigDecimal(0),
        cumulativeReconciliationResidualAbs = BigDecimal(0),
      )

  private[diagnostics] def computeSeedResult(
      config: Config,
      scenario: BankFailureAblationScenarios.Spec,
      seed: Long,
      months: ZStream[Any, String, McSeedMonth],
  ): ZIO[Any, String, SeedResult] =
    for
      ref     <- Ref.make(SeedAccumulator.Empty)
      outcome <- months
        .foreach(month => ref.update(_.observe(month.row)))
        .as(Right(()))
        .catchAllCause(cause => ZIO.succeed(Left(renderStreamCrash(cause))))
      acc     <- ref.get
      result  <- ZIO.fromEither(acc.toSeedResult(config, scenario, seed, outcome.left.toOption))
    yield result

  private[diagnostics] def summarize(config: Config, rows: Vector[SeedResult]): Vector[SummaryResult] =
    Scenarios.map: scenario =>
      val scenarioRows  = rows.filter(_.scenarioId == scenario.id)
      val failureMonths = scenarioRows.flatMap(_.firstFailureMonth)
      SummaryResult(
        runId = config.runId,
        scenarioId = scenario.id,
        scenarioLabel = scenario.label,
        seeds = scenarioRows.length,
        months = config.months,
        crashedSeeds = scenarioRows.count(_.crashed != 0),
        failedSeeds = failureMonths.length,
        firstFailureMonthMean = meanOption(failureMonths.map(month => BigDecimal(month))),
        firstFailureMonthMin = failureMonths.minOption,
        firstFailureMonthMax = failureMonths.maxOption,
        terminalFailuresMean = mean(scenarioRows.map(row => BigDecimal(row.terminalFailures))),
        peakFailuresMean = mean(scenarioRows.map(row => BigDecimal(row.peakFailures))),
        cumulativeNewFailuresMean = mean(scenarioRows.map(_.cumulativeNewFailures)),
        cumulativeRealizedCreditLossMean = mean(scenarioRows.map(_.cumulativeRealizedCreditLoss)),
        cumulativeInterbankContagionLossMean = mean(scenarioRows.map(_.cumulativeInterbankContagionLoss)),
        cumulativeEclProvisionChangeMean = mean(scenarioRows.map(_.cumulativeEclProvisionChange)),
        cumulativeBailInLossMean = mean(scenarioRows.map(_.cumulativeBailInLoss)),
        cumulativeCapitalDestructionMean = mean(scenarioRows.map(_.cumulativeCapitalDestruction)),
        cumulativeReconciliationResidualAbsMean = mean(scenarioRows.map(_.cumulativeReconciliationResidualAbs)),
        firstFailureRealizedCreditLossMean = meanFailedOnly(scenarioRows)(_.firstFailureRealizedCreditLoss),
        firstFailureEclProvisionChangeMean = meanFailedOnly(scenarioRows)(_.firstFailureEclProvisionChange),
        firstFailureConsumerNplLossMean = meanFailedOnly(scenarioRows)(_.firstFailureConsumerNplLoss),
        firstFailureFirmNplLossMean = meanFailedOnly(scenarioRows)(_.firstFailureFirmNplLoss),
        firstFailureMortgageNplLossMean = meanFailedOnly(scenarioRows)(_.firstFailureMortgageNplLoss),
        firstFailureCorpBondDefaultLossMean = meanFailedOnly(scenarioRows)(_.firstFailureCorpBondDefaultLoss),
        firstFailureInterbankContagionLossMean = meanFailedOnly(scenarioRows)(_.firstFailureInterbankContagionLoss),
        terminalTotalCreditToGdpMean = mean(scenarioRows.map(_.terminalTotalCreditToGdp)),
        terminalUnemploymentMean = mean(scenarioRows.map(_.terminalUnemployment)),
        terminalInflationMean = mean(scenarioRows.map(_.terminalInflation)),
        interpretation = scenario.interpretation,
      )

  private def writeArtifactsZIO(config: Config, summary: Vector[SummaryResult]): ZIO[Any, String, Vector[Path]] =
    val seedPath     = config.runRoot.resolve("bank-failure-ablation-seeds.tsv")
    val summaryPath  = config.runRoot.resolve("bank-failure-ablation-summary.tsv")
    val scenarioPath = config.runRoot.resolve("bank-failure-ablation-scenarios.tsv")
    val reportPath   = config.runRoot.resolve("bank-failure-ablation-report.md")
    for
      _ <- McTsvFile.writeAll(summaryPath, summary, SummaryTsvSchema)(DiagnosticIo.outputFailure)
      _ <- McTsvFile.writeAll(scenarioPath, Scenarios, ScenarioTsvSchema)(DiagnosticIo.outputFailure)
      _ <- DiagnosticIo.writeText(reportPath, renderReport(config, summary))
    yield Vector(seedPath, summaryPath, scenarioPath, reportPath)

  private[diagnostics] def renderSeedTsv(rows: Vector[SeedResult]): String =
    renderTsv(SeedTsvSchema, rows)

  private[diagnostics] def renderSummaryTsv(rows: Vector[SummaryResult]): String =
    renderTsv(SummaryTsvSchema, rows)

  private[diagnostics] def renderScenarioTsv(scenarios: Vector[BankFailureAblationScenarios.Spec]): String =
    renderTsv(ScenarioTsvSchema, scenarios)

  private val SeedTsvSchema: McTsvSchema[SeedResult] =
    val header = McTsvSchema.header(
      "RunId",
      "ScenarioId",
      "ScenarioLabel",
      "Seed",
      "Months",
      "ObservedMonths",
      "Crashed",
      "CrashReason",
      "FirstFailureMonth",
      "FirstFailureReasonCode",
      "FirstFailureBankId",
      "TerminalFailures",
      "PeakFailures",
      "CumulativeNewFailures",
      "CumulativeRealizedCreditLoss",
      "CumulativeInterbankContagionLoss",
      "CumulativeEclProvisionChange",
      "CumulativeBailInLoss",
      "CumulativeCapitalDestruction",
      "CumulativeReconciliationResidualAbs",
      "FirstFailureRealizedCreditLoss",
      "FirstFailureEclProvisionChange",
      "FirstFailureConsumerNplLoss",
      "FirstFailureFirmNplLoss",
      "FirstFailureMortgageNplLoss",
      "FirstFailureCorpBondDefaultLoss",
      "FirstFailureInterbankContagionLoss",
      "FirstFailureReconciliationResidual",
      "FirstFailureDepositBailInLoss",
      "TerminalTotalCreditToGdp",
      "TerminalUnemployment",
      "TerminalInflation",
      "Interpretation",
    )
    McTsvSchema(header, renderSeedRow)

  private val SummaryTsvSchema: McTsvSchema[SummaryResult] =
    val header = McTsvSchema.header(
      "RunId",
      "ScenarioId",
      "ScenarioLabel",
      "Seeds",
      "Months",
      "CrashedSeeds",
      "FailedSeeds",
      "FirstFailureMonthMean",
      "FirstFailureMonthMin",
      "FirstFailureMonthMax",
      "TerminalFailuresMean",
      "PeakFailuresMean",
      "CumulativeNewFailuresMean",
      "CumulativeRealizedCreditLossMean",
      "CumulativeInterbankContagionLossMean",
      "CumulativeEclProvisionChangeMean",
      "CumulativeBailInLossMean",
      "CumulativeCapitalDestructionMean",
      "CumulativeReconciliationResidualAbsMean",
      "FirstFailureRealizedCreditLossMean",
      "FirstFailureEclProvisionChangeMean",
      "FirstFailureConsumerNplLossMean",
      "FirstFailureFirmNplLossMean",
      "FirstFailureMortgageNplLossMean",
      "FirstFailureCorpBondDefaultLossMean",
      "FirstFailureInterbankContagionLossMean",
      "TerminalTotalCreditToGdpMean",
      "TerminalUnemploymentMean",
      "TerminalInflationMean",
      "Interpretation",
    )
    McTsvSchema(header, renderSummaryRow)

  private val ScenarioTsvSchema: McTsvSchema[BankFailureAblationScenarios.Spec] =
    McTsvSchema(
      header = McTsvSchema.header("ScenarioId", "ScenarioLabel", "Interpretation"),
      render = scenario => Vector(scenario.id, scenario.label, scenario.interpretation).map(tsv).mkString("\t"),
    )

  private def renderSeedRow(row: SeedResult): String =
    Vector(
      row.runId,
      row.scenarioId,
      row.scenarioLabel,
      row.seed.toString,
      row.months.toString,
      row.observedMonths.toString,
      row.crashed.toString,
      row.crashReason,
      row.firstFailureMonth.map(_.toString).getOrElse(""),
      row.firstFailureReasonCode.toString,
      row.firstFailureBankId.toString,
      row.terminalFailures.toString,
      row.peakFailures.toString,
      renderDecimal(row.cumulativeNewFailures),
      renderDecimal(row.cumulativeRealizedCreditLoss),
      renderDecimal(row.cumulativeInterbankContagionLoss),
      renderDecimal(row.cumulativeEclProvisionChange),
      renderDecimal(row.cumulativeBailInLoss),
      renderDecimal(row.cumulativeCapitalDestruction),
      renderDecimal(row.cumulativeReconciliationResidualAbs),
      renderDecimal(row.firstFailureRealizedCreditLoss),
      renderDecimal(row.firstFailureEclProvisionChange),
      renderDecimal(row.firstFailureConsumerNplLoss),
      renderDecimal(row.firstFailureFirmNplLoss),
      renderDecimal(row.firstFailureMortgageNplLoss),
      renderDecimal(row.firstFailureCorpBondDefaultLoss),
      renderDecimal(row.firstFailureInterbankContagionLoss),
      renderDecimal(row.firstFailureReconciliationResidual),
      renderDecimal(row.firstFailureDepositBailInLoss),
      renderDecimal(row.terminalTotalCreditToGdp),
      renderDecimal(row.terminalUnemployment),
      renderDecimal(row.terminalInflation),
      row.interpretation,
    ).map(tsv).mkString("\t")

  private def renderSummaryRow(row: SummaryResult): String =
    Vector(
      row.runId,
      row.scenarioId,
      row.scenarioLabel,
      row.seeds.toString,
      row.months.toString,
      row.crashedSeeds.toString,
      row.failedSeeds.toString,
      row.firstFailureMonthMean.map(renderDecimal).getOrElse(""),
      row.firstFailureMonthMin.map(_.toString).getOrElse(""),
      row.firstFailureMonthMax.map(_.toString).getOrElse(""),
      renderDecimal(row.terminalFailuresMean),
      renderDecimal(row.peakFailuresMean),
      renderDecimal(row.cumulativeNewFailuresMean),
      renderDecimal(row.cumulativeRealizedCreditLossMean),
      renderDecimal(row.cumulativeInterbankContagionLossMean),
      renderDecimal(row.cumulativeEclProvisionChangeMean),
      renderDecimal(row.cumulativeBailInLossMean),
      renderDecimal(row.cumulativeCapitalDestructionMean),
      renderDecimal(row.cumulativeReconciliationResidualAbsMean),
      renderDecimal(row.firstFailureRealizedCreditLossMean),
      renderDecimal(row.firstFailureEclProvisionChangeMean),
      renderDecimal(row.firstFailureConsumerNplLossMean),
      renderDecimal(row.firstFailureFirmNplLossMean),
      renderDecimal(row.firstFailureMortgageNplLossMean),
      renderDecimal(row.firstFailureCorpBondDefaultLossMean),
      renderDecimal(row.firstFailureInterbankContagionLossMean),
      renderDecimal(row.terminalTotalCreditToGdpMean),
      renderDecimal(row.terminalUnemploymentMean),
      renderDecimal(row.terminalInflationMean),
      row.interpretation,
    ).map(tsv).mkString("\t")

  private def renderTsv[A](schema: McTsvSchema[A], rows: Vector[A]): String =
    (schema.header +: rows.map(schema.render)).mkString("\n") + "\n"

  private[diagnostics] def renderReport(config: Config, summary: Vector[SummaryResult]): String =
    val command        =
      s"""sbt "bankFailureAblations --seed-start ${config.seedStart} --seeds ${config.seeds} --months ${config.months} --out ${config.out} --run-id ${config.runId}""""
    val rows           = summary.map: row =>
      markdownRow(
        Vector(
          row.scenarioId,
          row.crashedSeeds.toString,
          row.failedSeeds.toString,
          row.firstFailureMonthMean.map(renderDecimal).getOrElse("n/a"),
          renderDecimal(row.terminalFailuresMean),
          renderDecimal(row.cumulativeRealizedCreditLossMean),
          renderDecimal(row.cumulativeInterbankContagionLossMean),
          renderDecimal(row.cumulativeEclProvisionChangeMean),
          renderDecimal(row.cumulativeBailInLossMean),
          renderDecimal(row.cumulativeReconciliationResidualAbsMean),
        ),
      )
    val comparisonRows = baselineComparisonRows(summary)
    val lines          = Vector(
      "# Bank Failure Ablation Diagnostics",
      "",
      "Generated by `BankFailureAblationExport` for issue #560.",
      "",
      s"- Run id: `${config.runId}`",
      s"- Seeds: `${config.seeds}` from `${config.seedStart}`",
      s"- Months: `${config.months}`",
      s"- Repeat command: `$command`",
      "",
      "These scenarios are diagnostic probes, not production policy settings.",
      "They neutralize one channel at a time so failure timing can be compared with the baseline.",
      "",
      "## Scenarios",
      "",
    ) ++ Scenarios.flatMap(scenario => Vector(s"- `${scenario.id}`: ${scenario.interpretation}")) ++ Vector(
      "",
      "## Summary",
      "",
      markdownRow(
        Vector(
          "Scenario",
          "Crashed Seeds",
          "Failed Seeds",
          "Mean First Failure Month",
          "Mean Terminal Failures",
          "Mean Cum Realized Loss",
          "Mean Cum Interbank Loss",
          "Mean Cum ECL",
          "Mean Cum Bail-In",
          "Mean Abs Reconciliation Residual",
        ),
      ),
      markdownRow(Vector("---", "---", "---", "---", "---", "---", "---", "---", "---", "---")),
    ) ++ rows ++ Vector(
      "",
      "## Baseline Comparison",
      "",
      markdownRow(Vector("Scenario", "Failed Seeds Delta", "First Failure Month Delta", "Terminal Failures Delta", "Reading")),
      markdownRow(Vector("---", "---", "---", "---", "---")),
    ) ++ comparisonRows
    lines.mkString("\n") + "\n"

  private def baselineComparisonRows(summary: Vector[SummaryResult]): Vector[String] =
    summary
      .find(_.scenarioId == "baseline")
      .toVector
      .flatMap: baseline =>
        summary
          .filterNot(_.scenarioId == baseline.scenarioId)
          .map: row =>
            markdownRow(
              Vector(
                row.scenarioId,
                signed(row.failedSeeds - baseline.failedSeeds),
                firstFailureDelta(row.firstFailureMonthMean, baseline.firstFailureMonthMean),
                signedDecimal(row.terminalFailuresMean - baseline.terminalFailuresMean),
                comparisonReading(row, baseline),
              ),
            )

  private def firstFailureDelta(candidate: Option[BigDecimal], baseline: Option[BigDecimal]): String =
    (candidate, baseline) match
      case (Some(c), Some(b)) => signedDecimal(c - b)
      case (None, Some(_))    => "prevented in run"
      case (Some(_), None)    => "new failures"
      case (None, None)       => "no failures"

  private def comparisonReading(candidate: SummaryResult, baseline: SummaryResult): String =
    if candidate.failedSeeds < baseline.failedSeeds then "materially prevents failures in this fixture"
    else
      (candidate.firstFailureMonthMean, baseline.firstFailureMonthMean) match
        case (Some(c), Some(b)) if c > b => "materially delays first failure"
        case _                           => "does not delay first failure"

  private def col(row: Array[MetricValue], name: String): BigDecimal =
    decimal(row(ColumnIndex(name)).toLong)

  private def colInt(row: Array[MetricValue], name: String): Int =
    col(row, name).setScale(0, RoundingMode.HALF_UP).toInt

  private def mean(values: Vector[BigDecimal]): BigDecimal =
    if values.isEmpty then BigDecimal(0) else values.sum / BigDecimal(values.length)

  private def meanOption(values: Vector[BigDecimal]): Option[BigDecimal] =
    if values.isEmpty then None else Some(mean(values))

  private def meanFailedOnly(rows: Vector[SeedResult])(value: SeedResult => BigDecimal): BigDecimal =
    mean(rows.filter(_.firstFailureMonth.nonEmpty).map(value))

  private def decimal(raw: Long): BigDecimal =
    BigDecimal(raw) / BigDecimal(com.boombustgroup.amorfati.fp.FixedPointBase.Scale)

  private def renderDecimal(value: BigDecimal): String =
    value.setScale(6, RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros.toPlainString

  private def renderStreamCrash(cause: Cause[String]): String =
    cause.failureOption.getOrElse(cause.prettyPrint)

  private def signed(value: Int): String =
    if value > 0 then s"+$value" else value.toString

  private def signedDecimal(value: BigDecimal): String =
    val rendered = renderDecimal(value)
    if value > BigDecimal(0) then s"+$rendered" else rendered

  private def tsv(value: String): String =
    val escaped = value.replace("\"", "\"\"")
    if escaped.exists(ch => ch == '\t' || ch == '"' || ch == '\n' || ch == '\r') then s""""$escaped""""
    else escaped

  private def markdownRow(values: Vector[String]): String =
    values.map(_.replace("|", "\\|")).mkString("| ", " | ", " |")

  private def knownFlag(flag: String): Boolean =
    flag == "--seed-start" || flag == "--seeds" || flag == "--months" || flag == "--run-id" || flag == "--out"

  private def parseLong(value: String, name: String): Either[String, Long] =
    Try(value.toLong).toEither.left.map(_ => s"$name must be a long integer")

  private def parseInt(value: String, name: String): Either[String, Int] =
    Try(value.toInt).toEither.left.map(_ => s"$name must be an integer")

  private val usage: String =
    """Usage:
      |  bankFailureAblations [--seed-start N] [--seeds N] [--months N] [--out PATH] [--run-id ID]
      |
      |Runs baseline plus controlled bank-failure ablation scenarios and writes:
      |  bank-failure-ablation-seeds.tsv
      |  bank-failure-ablation-summary.tsv
      |  bank-failure-ablation-scenarios.tsv
      |  bank-failure-ablation-report.md
      |""".stripMargin
