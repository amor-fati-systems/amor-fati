package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.config.{HhBankLeadLagScenarios, SimParams}
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.montecarlo.{McCsvFile, McCsvSchema, McDiagnosticRunner, McSeedMonth}
import com.boombustgroup.amorfati.types.*
import zio.ZIO
import zio.stream.ZStream

import java.nio.file.Path
import scala.math.BigDecimal.RoundingMode

/** HH-to-bank lead-lag diagnostics for #584.
  *
  * The export is intentionally separate from the standard Monte Carlo schema:
  * it is a heavier per-bank/month drilldown used to connect household stress to
  * bank capital, CAR, and failure outcomes.
  */
object HhBankLeadLagDiagnosticsExport:

  final case class Config(
      seedStart: Long = 1L,
      seeds: Int = 5,
      months: Int = 60,
      lagMax: Int = 6,
      runId: String = "hh-bank-lead-lag",
      out: Path = Path.of("target/hh-bank-lead-lag"),
  ):
    def seedRange: Vector[Long] = Vector.tabulate(seeds)(i => seedStart + i.toLong)
    def runRoot: Path           = out.resolve(runId)

  final case class BankMonthRow(
      runId: String,
      scenarioId: String,
      scenarioLabel: String,
      seed: Long,
      month: Int,
      bankId: Int,
      bankName: String,
      householdCount: Int,
      hhMonthlyIncome: BigDecimal,
      hhConsumerLoanDefault: BigDecimal,
      hhLiquidityBridgeChargeOff: BigDecimal,
      hhLiquidityShortfallFinancing: BigDecimal,
      hhConsumerDefault: BigDecimal,
      hhConsumerDebtService: BigDecimal,
      hhConsumerApprovedOrigination: BigDecimal,
      hhConsumerRejectedOrigination: BigDecimal,
      hhConsumerDebtArrears: BigDecimal,
      hhMortgageArrears: BigDecimal,
      bankConsumerLoanStock: BigDecimal,
      bankConsumerNplStock: BigDecimal,
      bankConsumerNplLoss: BigDecimal,
      bankCapital: BigDecimal,
      bankCapitalDelta: BigDecimal,
      bankCar: BigDecimal,
      bankLcr: BigDecimal,
      bankFailed: Int,
      bankNewFailure: Int,
      bankFailureReasonCode: Int,
      unemployment: BigDecimal,
      inflation: BigDecimal,
      monthlyGdpProxy: BigDecimal,
  )

  final case class CorrelationResult(
      runId: String,
      scenarioId: String,
      scenarioLabel: String,
      lagMonths: Int,
      hhMetric: String,
      bankMetric: String,
      observations: Int,
      correlation: Option[BigDecimal],
      interpretation: String,
  )

  final case class CounterfactualResult(
      runId: String,
      scenarioId: String,
      scenarioLabel: String,
      seeds: Int,
      months: Int,
      failedSeeds: Int,
      firstFailureMonthMean: Option[BigDecimal],
      terminalFailuresMean: BigDecimal,
      cumulativeNewFailuresMean: BigDecimal,
      cumulativeConsumerNplLossMean: BigDecimal,
      cumulativeHhConsumerLoanDefaultMean: BigDecimal,
      cumulativeLiquidityBridgeChargeOffMean: BigDecimal,
      interpretation: String,
  )

  final case class ExportResult(
      paths: Vector[Path],
      bankMonthRows: Vector[BankMonthRow],
      correlations: Vector[CorrelationResult],
      counterfactuals: Vector[CounterfactualResult],
  )

  private final class HhBankTotals:
    var householdCount: Int              = 0
    var monthlyIncome: PLN               = PLN.Zero
    var consumerLoanDefault: PLN         = PLN.Zero
    var liquidityBridgeChargeOff: PLN    = PLN.Zero
    var liquidityShortfallFinancing: PLN = PLN.Zero
    var consumerDefault: PLN             = PLN.Zero
    var consumerDebtService: PLN         = PLN.Zero
    var consumerApprovedOrigination: PLN = PLN.Zero
    var consumerRejectedOrigination: PLN = PLN.Zero
    var consumerDebtArrears: PLN         = PLN.Zero
    var mortgageArrears: PLN             = PLN.Zero

    def add(flow: com.boombustgroup.amorfati.agents.Household.MonthlyFlow): Unit =
      householdCount += 1
      monthlyIncome = monthlyIncome + flow.monthlyIncome
      consumerLoanDefault = consumerLoanDefault + flow.consumerLoanDefault
      liquidityBridgeChargeOff = liquidityBridgeChargeOff + flow.liquidityBridgeChargeOff
      liquidityShortfallFinancing = liquidityShortfallFinancing + flow.liquidityShortfallFinancing
      consumerDefault = consumerDefault + flow.consumerDefault
      consumerDebtService = consumerDebtService + flow.consumerDebtService
      consumerApprovedOrigination = consumerApprovedOrigination + flow.consumerApprovedOrigination
      consumerRejectedOrigination = consumerRejectedOrigination + flow.consumerRejectedOrigination
      consumerDebtArrears = consumerDebtArrears + flow.consumerDebtArrears
      mortgageArrears = mortgageArrears + flow.mortgageArrears

  private[diagnostics] val Scenarios: Vector[HhBankLeadLagScenarios.Spec] =
    HhBankLeadLagScenarios.all

  private val HhMetricAccessors: Vector[(String, BankMonthRow => BigDecimal)] = Vector(
    "HhConsumerLoanDefault"         -> (_.hhConsumerLoanDefault),
    "HhLiquidityBridgeChargeOff"    -> (_.hhLiquidityBridgeChargeOff),
    "HhLiquidityShortfallFinancing" -> (_.hhLiquidityShortfallFinancing),
    "HhConsumerDebtArrears"         -> (_.hhConsumerDebtArrears),
    "HhMortgageArrears"             -> (_.hhMortgageArrears),
    "HhConsumerDebtService"         -> (_.hhConsumerDebtService),
  )

  private val BankMetricAccessors: Vector[(String, BankMonthRow => BigDecimal)] = Vector(
    "BankConsumerNplLoss" -> (_.bankConsumerNplLoss),
    "BankCapitalDelta"    -> (_.bankCapitalDelta),
    "BankCar"             -> (_.bankCar),
    "BankLcr"             -> (_.bankLcr),
    "BankNewFailure"      -> (row => BigDecimal(row.bankNewFailure)),
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
      validConfig    <- ZIO.fromEither(validate(config))
      bankRows       <- McCsvFile
        .writeFold(
          validConfig.runRoot.resolve("hh-bank-lead-lag-bank-months.csv"),
          McDiagnosticRunner
            .runScenarioSeedStreams(
              Scenarios,
              validConfig.seedRange,
              validConfig.months,
              _.id,
              _.params,
            )((scenario, seed, months) => computeBankRows(validConfig, scenario, seed, months)),
          BankMonthCsvSchema,
          Vector.newBuilder[BankMonthRow],
        )((builder, row) => builder += row)(DiagnosticIo.outputFailure)
        .map(_.result())
      correlations    = computeCorrelations(validConfig, bankRows)
      counterfactuals = summarizeCounterfactuals(validConfig, bankRows)
      paths          <- writeArtifactsZIO(validConfig, correlations, counterfactuals)
    yield ExportResult(paths, bankRows, correlations, counterfactuals)

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
            case Seq(value, next*) if flag == "--lag-max"    =>
              parseInt(value, flag).flatMap(lagMax => loop(next, config.copy(lagMax = lagMax)))
            case Seq(value, next*) if flag == "--run-id"     =>
              loop(next, config.copy(runId = value))
            case Seq(value, next*) if flag == "--out"        =>
              loop(next, config.copy(out = Path.of(value)))
            case Seq(_, _*)                                  => Left(s"Unknown argument: $flag")
        case Seq(flag, _*) if flag.startsWith("--") => Left(s"Unknown argument: $flag")
        case Seq(value, _*)                         => Left(s"Unexpected positional argument: $value")

    loop(args, Config())

  private[diagnostics] def validate(config: Config): Either[String, Config] =
    Either
      .cond(config.seeds > 0, config, "--seeds must be a positive integer")
      .flatMap(valid => Either.cond(valid.months > 0, valid, "--months must be a positive integer"))
      .flatMap(valid => Either.cond(valid.lagMax >= 0, valid, "--lag-max must be >= 0"))
      .flatMap(valid => Either.cond(valid.runId.trim.nonEmpty, valid, "--run-id must be non-empty"))

  private[diagnostics] def computeBankRows(
      config: Config,
      scenario: HhBankLeadLagScenarios.Spec,
      seed: Long,
      months: ZStream[Any, String, McSeedMonth],
  ): ZStream[Any, String, BankMonthRow] =
    months
      .mapZIO: month =>
        ZIO
          .attempt(bankMonthRows(config, scenario, seed, month)(using scenario.params))
          .mapError(err =>
            s"Scenario ${scenario.id} seed $seed failed HH-bank row construction at month ${month.executionMonth.toInt}: ${Option(err.getMessage).getOrElse(err.getClass.getSimpleName)}",
          )
      .mapConcat(identity)

  private def bankMonthRows(
      config: Config,
      scenario: HhBankLeadLagScenarios.Spec,
      seed: Long,
      seedMonth: McSeedMonth,
  )(using p: SimParams): Vector[BankMonthRow] =
    val nBanks = seedMonth.state.banks.length
    require(
      seedMonth.householdSnapshotState.households.length == seedMonth.householdMonthlyFlows.length,
      s"HH-bank diagnostics require aligned household rows and flow rows, got ${seedMonth.householdSnapshotState.households.length} households and ${seedMonth.householdMonthlyFlows.length} flows",
    )
    require(
      seedMonth.state.ledgerFinancialState.banks.length == nBanks,
      s"HH-bank diagnostics require aligned bank rows and ledger rows, got $nBanks banks and ${seedMonth.state.ledgerFinancialState.banks.length} ledger rows",
    )
    require(
      seedMonth.openingState.banks.length == nBanks,
      s"HH-bank diagnostics require aligned opening and closing bank rows, got ${seedMonth.openingState.banks.length} opening banks and $nBanks closing banks",
    )

    val hhTotals = Array.tabulate(nBanks)(_ => HhBankTotals())
    seedMonth.householdSnapshotState.households
      .zip(seedMonth.householdMonthlyFlows)
      .foreach: (household, flow) =>
        require(
          household.id == flow.householdId,
          s"HH-bank diagnostics require positional household-flow alignment, got household ${household.id.toInt} and flow ${flow.householdId.toInt}",
        )
        val bankId = household.bankId.toInt
        require(bankId >= 0 && bankId < nBanks, s"Household ${household.id.toInt} references invalid bank id $bankId for $nBanks banks")
        hhTotals(bankId).add(flow)

    val bankStocks        = seedMonth.state.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks)
    val failureDiagnostic = seedMonth.state.world.flows.bankFailure
    val unemployment      = fixed(seedMonth.state.world.unemploymentRate(seedMonth.state.householdAggregates.employed).toLong)
    val inflation         = fixed(seedMonth.state.world.inflation.toLong)
    val monthlyGdp        = pln(seedMonth.state.world.cachedMonthlyGdpProxy)

    seedMonth.state.banks.zipWithIndex.map: (bank, idx) =>
      val stocks            = bankStocks(idx)
      val openingBank       = seedMonth.openingState.banks(idx)
      val corpBondHoldings  = CorporateBondOwnership.bankHolderFor(seedMonth.state.ledgerFinancialState, bank.id)
      val totals            = hhTotals(idx)
      val consumerNplLoss   = totals.consumerLoanDefault * (Share.One - p.household.ccNplRecovery)
      val newFailure        = if !openingBank.failed && bank.failed then 1 else 0
      val failureReasonCode =
        if newFailure == 1 && failureDiagnostic.firstNewBankId == bank.id.toInt then failureDiagnostic.firstNewReasonCode
        else 0
      BankMonthRow(
        runId = config.runId,
        scenarioId = scenario.id,
        scenarioLabel = scenario.label,
        seed = seed,
        month = seedMonth.executionMonth.toInt,
        bankId = bank.id.toInt,
        bankName = bankName(idx),
        householdCount = totals.householdCount,
        hhMonthlyIncome = pln(totals.monthlyIncome),
        hhConsumerLoanDefault = pln(totals.consumerLoanDefault),
        hhLiquidityBridgeChargeOff = pln(totals.liquidityBridgeChargeOff),
        hhLiquidityShortfallFinancing = pln(totals.liquidityShortfallFinancing),
        hhConsumerDefault = pln(totals.consumerDefault),
        hhConsumerDebtService = pln(totals.consumerDebtService),
        hhConsumerApprovedOrigination = pln(totals.consumerApprovedOrigination),
        hhConsumerRejectedOrigination = pln(totals.consumerRejectedOrigination),
        hhConsumerDebtArrears = pln(totals.consumerDebtArrears),
        hhMortgageArrears = pln(totals.mortgageArrears),
        bankConsumerLoanStock = pln(stocks.consumerLoan),
        bankConsumerNplStock = pln(bank.consumerNpl),
        bankConsumerNplLoss = pln(consumerNplLoss),
        bankCapital = pln(bank.capital),
        bankCapitalDelta = pln(bank.capital - openingBank.capital),
        bankCar = fixed(Banking.car(bank, stocks, corpBondHoldings).toLong),
        bankLcr = fixed(Banking.lcr(stocks).toLong),
        bankFailed = if bank.failed then 1 else 0,
        bankNewFailure = newFailure,
        bankFailureReasonCode = failureReasonCode,
        unemployment = unemployment,
        inflation = inflation,
        monthlyGdpProxy = monthlyGdp,
      )

  private[diagnostics] def computeCorrelations(config: Config, rows: Vector[BankMonthRow]): Vector[CorrelationResult] =
    Scenarios.flatMap: scenario =>
      val scenarioRows = rows.filter(_.scenarioId == scenario.id)
      val bySeedBank   = scenarioRows.groupBy(row => (row.seed, row.bankId)).view.mapValues(_.sortBy(_.month).map(row => row.month -> row).toMap).toMap
      for
        lag                  <- (0 to config.lagMax).toVector
        (hhMetric, hhValue)  <- HhMetricAccessors
        (bankMetric, target) <- BankMetricAccessors
      yield
        val pairs       = bySeedBank.valuesIterator.flatMap: byMonth =>
          byMonth.valuesIterator.flatMap: current =>
            byMonth.get(current.month - lag).map(previous => hhValue(previous) -> target(current))
        val vectorPairs = pairs.toVector
        CorrelationResult(
          runId = config.runId,
          scenarioId = scenario.id,
          scenarioLabel = scenario.label,
          lagMonths = lag,
          hhMetric = hhMetric,
          bankMetric = bankMetric,
          observations = vectorPairs.length,
          correlation = pearson(vectorPairs),
          interpretation = s"$hhMetric at t-$lag versus $bankMetric at t; correlation is descriptive, causal evidence comes from counterfactual rows.",
        )

  private[diagnostics] def summarizeCounterfactuals(config: Config, rows: Vector[BankMonthRow]): Vector[CounterfactualResult] =
    Scenarios.map: scenario =>
      val scenarioRows  = rows.filter(_.scenarioId == scenario.id)
      val bySeed        = scenarioRows.groupBy(_.seed)
      val seedSummaries = config.seedRange.map: seed =>
        val seedRows       = bySeed.getOrElse(seed, Vector.empty)
        val firstFailure   = seedRows.filter(_.bankNewFailure > 0).map(_.month).minOption
        val terminalMonth  = seedRows.map(_.month).maxOption.getOrElse(0)
        val terminalFailed = seedRows.filter(_.month == terminalMonth).map(_.bankFailed).sum
        (
          firstFailure,
          terminalFailed,
          seedRows.map(_.bankNewFailure).sum,
          seedRows.map(_.bankConsumerNplLoss).sum,
          seedRows.map(_.hhConsumerLoanDefault).sum,
          seedRows.map(_.hhLiquidityBridgeChargeOff).sum,
        )
      val failureMonths = seedSummaries.flatMap(_._1)
      CounterfactualResult(
        runId = config.runId,
        scenarioId = scenario.id,
        scenarioLabel = scenario.label,
        seeds = seedSummaries.length,
        months = config.months,
        failedSeeds = seedSummaries.count(_._1.isDefined),
        firstFailureMonthMean = mean(failureMonths.map(BigDecimal(_))),
        terminalFailuresMean = meanOrZero(seedSummaries.map(row => BigDecimal(row._2))),
        cumulativeNewFailuresMean = meanOrZero(seedSummaries.map(row => BigDecimal(row._3))),
        cumulativeConsumerNplLossMean = meanOrZero(seedSummaries.map(_._4)),
        cumulativeHhConsumerLoanDefaultMean = meanOrZero(seedSummaries.map(_._5)),
        cumulativeLiquidityBridgeChargeOffMean = meanOrZero(seedSummaries.map(_._6)),
        interpretation = scenario.interpretation,
      )

  private[diagnostics] def pearson(pairs: Vector[(BigDecimal, BigDecimal)]): Option[BigDecimal] =
    if pairs.length < 2 then None
    else
      val xs    = pairs.map(_._1.toDouble)
      val ys    = pairs.map(_._2.toDouble)
      val meanX = xs.sum / xs.length
      val meanY = ys.sum / ys.length
      val dx    = xs.map(_ - meanX)
      val dy    = ys.map(_ - meanY)
      val sx    = math.sqrt(dx.map(v => v * v).sum)
      val sy    = math.sqrt(dy.map(v => v * v).sum)
      if sx == 0.0 || sy == 0.0 then None
      else Some(BigDecimal((dx zip dy).map((x, y) => x * y).sum / (sx * sy)).setScale(6, RoundingMode.HALF_UP))

  private def writeArtifactsZIO(
      config: Config,
      correlations: Vector[CorrelationResult],
      counterfactuals: Vector[CounterfactualResult],
  ): ZIO[Any, String, Vector[Path]] =
    val bankRowsPath        = config.runRoot.resolve("hh-bank-lead-lag-bank-months.csv")
    val correlationsPath    = config.runRoot.resolve("hh-bank-lead-lag-correlations.csv")
    val counterfactualsPath = config.runRoot.resolve("hh-bank-lead-lag-counterfactuals.csv")
    val reportPath          = config.runRoot.resolve("hh-bank-lead-lag-report.md")
    for
      _ <- McCsvFile.writeAll(correlationsPath, correlations, CorrelationCsvSchema)(DiagnosticIo.outputFailure)
      _ <- McCsvFile.writeAll(counterfactualsPath, counterfactuals, CounterfactualCsvSchema)(DiagnosticIo.outputFailure)
      _ <- DiagnosticIo.writeText(reportPath, renderReport(config, correlations, counterfactuals))
    yield Vector(bankRowsPath, correlationsPath, counterfactualsPath, reportPath)

  private val BankMonthCsvSchema: McCsvSchema[BankMonthRow] =
    val header =
      "RunId;ScenarioId;ScenarioLabel;Seed;Month;BankId;BankName;HouseholdCount;HhMonthlyIncome;HhConsumerLoanDefault;HhLiquidityBridgeChargeOff;HhLiquidityShortfallFinancing;HhConsumerDefault;HhConsumerDebtService;HhConsumerApprovedOrigination;HhConsumerRejectedOrigination;HhConsumerDebtArrears;HhMortgageArrears;BankConsumerLoanStock;BankConsumerNplStock;BankConsumerNplLoss;BankCapital;BankCapitalDelta;BankCar;BankLcr;BankFailed;BankNewFailure;BankFailureReasonCode;Unemployment;Inflation;MonthlyGdpProxy"
    McCsvSchema(header, renderBankMonthRow)

  private val CorrelationCsvSchema: McCsvSchema[CorrelationResult] =
    val header = "RunId;ScenarioId;ScenarioLabel;LagMonths;HhMetric;BankMetric;Observations;Correlation;Interpretation"
    McCsvSchema(
      header,
      row =>
        Vector(
          row.runId,
          row.scenarioId,
          row.scenarioLabel,
          row.lagMonths.toString,
          row.hhMetric,
          row.bankMetric,
          row.observations.toString,
          row.correlation.map(renderDecimal).getOrElse("NA"),
          row.interpretation,
        ).map(csv).mkString(";"),
    )

  private val CounterfactualCsvSchema: McCsvSchema[CounterfactualResult] =
    val header =
      "RunId;ScenarioId;ScenarioLabel;Seeds;Months;FailedSeeds;FirstFailureMonthMean;TerminalFailuresMean;CumulativeNewFailuresMean;CumulativeConsumerNplLossMean;CumulativeHhConsumerLoanDefaultMean;CumulativeLiquidityBridgeChargeOffMean;Interpretation"
    McCsvSchema(
      header,
      row =>
        Vector(
          row.runId,
          row.scenarioId,
          row.scenarioLabel,
          row.seeds.toString,
          row.months.toString,
          row.failedSeeds.toString,
          row.firstFailureMonthMean.map(renderDecimal).getOrElse("NA"),
          renderDecimal(row.terminalFailuresMean),
          renderDecimal(row.cumulativeNewFailuresMean),
          renderDecimal(row.cumulativeConsumerNplLossMean),
          renderDecimal(row.cumulativeHhConsumerLoanDefaultMean),
          renderDecimal(row.cumulativeLiquidityBridgeChargeOffMean),
          row.interpretation,
        ).map(csv).mkString(";"),
    )

  private def renderBankMonthRow(row: BankMonthRow): String =
    Vector(
      row.runId,
      row.scenarioId,
      row.scenarioLabel,
      row.seed.toString,
      row.month.toString,
      row.bankId.toString,
      row.bankName,
      row.householdCount.toString,
      renderDecimal(row.hhMonthlyIncome),
      renderDecimal(row.hhConsumerLoanDefault),
      renderDecimal(row.hhLiquidityBridgeChargeOff),
      renderDecimal(row.hhLiquidityShortfallFinancing),
      renderDecimal(row.hhConsumerDefault),
      renderDecimal(row.hhConsumerDebtService),
      renderDecimal(row.hhConsumerApprovedOrigination),
      renderDecimal(row.hhConsumerRejectedOrigination),
      renderDecimal(row.hhConsumerDebtArrears),
      renderDecimal(row.hhMortgageArrears),
      renderDecimal(row.bankConsumerLoanStock),
      renderDecimal(row.bankConsumerNplStock),
      renderDecimal(row.bankConsumerNplLoss),
      renderDecimal(row.bankCapital),
      renderDecimal(row.bankCapitalDelta),
      renderDecimal(row.bankCar),
      renderDecimal(row.bankLcr),
      row.bankFailed.toString,
      row.bankNewFailure.toString,
      row.bankFailureReasonCode.toString,
      renderDecimal(row.unemployment),
      renderDecimal(row.inflation),
      renderDecimal(row.monthlyGdpProxy),
    ).map(csv).mkString(";")

  private def renderReport(config: Config, correlations: Vector[CorrelationResult], counterfactuals: Vector[CounterfactualResult]): String =
    val baseline = counterfactuals.find(_.scenarioId == "baseline")
    val noHit    = counterfactuals.find(_.scenarioId == "no-consumer-npl-capital-hit")
    val topCorr  = correlations
      .filter(row => row.scenarioId == "baseline" && row.lagMonths > 0 && row.correlation.isDefined)
      .sortBy(row => -row.correlation.get.abs)
      .take(10)
    val command  =
      s"""sbt "hhBankLeadLagDiagnostics --seed-start ${config.seedStart} --seeds ${config.seeds} --months ${config.months} --lag-max ${config.lagMax} --out ${config.out} --run-id ${config.runId}""""
    Vector(
      "# HH-to-Bank Lead-Lag Diagnostics",
      "",
      "Generated for issue #584.",
      "",
      s"- Run id: `${config.runId}`",
      s"- Seeds: `${config.seeds}` from `${config.seedStart}`",
      s"- Months: `${config.months}`",
      s"- Lag window: `0..${config.lagMax}` months",
      s"- Repeat command: `$command`",
      "",
      "The bank-month export joins household stress flows by routed `BankId` to",
      "bank consumer-credit loss, capital, CAR, LCR, and failure outcomes. The",
      "correlation table is descriptive lead-lag evidence; the counterfactual table",
      "tests whether the direct consumer NPL capital-hit channel is necessary in this fixture.",
      "",
      "## Counterfactual Summary",
      "",
      counterfactualTable(counterfactuals),
      "",
      "## Counterfactual Reading",
      "",
      causalReading(baseline, noHit),
      "",
      "## Strongest Positive-Lag Baseline Correlations",
      "",
      correlationTable(topCorr),
      "",
    ).mkString("\n")

  private def counterfactualTable(rows: Vector[CounterfactualResult]): String =
    val header = "| Scenario | Failed Seeds | Mean First Failure Month | Mean Terminal Failures | Mean Cum Consumer NPL Loss |"
    val sep    = "| --- | --- | --- | --- | --- |"
    val body   = rows.map: row =>
      s"| ${row.scenarioId} | ${row.failedSeeds}/${row.seeds} | ${row.firstFailureMonthMean.map(renderDecimal).getOrElse("n/a")} | ${renderDecimal(row.terminalFailuresMean)} | ${renderDecimal(row.cumulativeConsumerNplLossMean)} |"
    (header +: sep +: body).mkString("\n")

  private def correlationTable(rows: Vector[CorrelationResult]): String =
    if rows.isEmpty then "No finite baseline correlations."
    else
      val header = "| Lag | HH Metric | Bank Metric | Observations | Correlation |"
      val sep    = "| --- | --- | --- | --- | --- |"
      val body   = rows.map: row =>
        s"| ${row.lagMonths} | ${row.hhMetric} | ${row.bankMetric} | ${row.observations} | ${row.correlation.map(renderDecimal).getOrElse("NA")} |"
      (header +: sep +: body).mkString("\n")

  private def causalReading(baseline: Option[CounterfactualResult], noHit: Option[CounterfactualResult]): String =
    (baseline, noHit) match
      case (Some(base), Some(counter)) =>
        val failureDelta  = counter.failedSeeds - base.failedSeeds
        val terminalDelta = counter.terminalFailuresMean - base.terminalFailuresMean
        s"Neutralizing the consumer NPL capital hit changes failed seed count by $failureDelta and mean terminal failures by ${renderDecimal(terminalDelta)} versus baseline. If failures fall materially while HH default flows remain visible, the direct consumer NPL capital-hit channel is necessary for bank failure timing in this fixture. This is a controlled model intervention, not full macro-causal identification."
      case _                           =>
        "Counterfactual rows are incomplete; counterfactual reading unavailable."

  private def pln(value: PLN)(using p: SimParams): BigDecimal =
    fixed(polandScale(value).toLong)

  private def polandScale(value: PLN)(using p: SimParams): PLN =
    if p.gdpRatio > Scalar.Zero then value / p.gdpRatio.toMultiplier else value

  private def fixed(raw: Long): BigDecimal =
    BigDecimal(raw) / BigDecimal(FixedPointBase.Scale)

  private def renderDecimal(value: BigDecimal): String =
    value.setScale(6, RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros.toPlainString

  private def csv(value: String): String =
    val escaped = value.replace("\"", "\"\"")
    if escaped.exists(ch => ch == ';' || ch == '"' || ch == '\n' || ch == '\r') then s""""$escaped""""
    else escaped

  private def mean(values: Vector[BigDecimal]): Option[BigDecimal] =
    if values.isEmpty then None else Some(values.sum / BigDecimal(values.length))

  private def meanOrZero(values: Vector[BigDecimal]): BigDecimal =
    mean(values).getOrElse(BigDecimal(0))

  private def bankName(index: Int): String =
    Banking.DefaultConfigs.lift(index).map(_.name).getOrElse(s"Bank-$index")

  private def knownFlag(flag: String): Boolean =
    Set("--seed-start", "--seeds", "--months", "--lag-max", "--run-id", "--out").contains(flag)

  private def parseLong(value: String, flag: String): Either[String, Long] =
    value.toLongOption.toRight(s"Invalid long for $flag: $value")

  private def parseInt(value: String, flag: String): Either[String, Int] =
    value.toIntOption.toRight(s"Invalid integer for $flag: $value")

  private val usage: String =
    """Usage: hhBankLeadLagDiagnostics [--seed-start N] [--seeds N] [--months N] [--lag-max N] [--out DIR] [--run-id ID]
      |
      |Writes:
      |- hh-bank-lead-lag-bank-months.csv
      |- hh-bank-lead-lag-correlations.csv
      |- hh-bank-lead-lag-counterfactuals.csv
      |- hh-bank-lead-lag-report.md
      |""".stripMargin
