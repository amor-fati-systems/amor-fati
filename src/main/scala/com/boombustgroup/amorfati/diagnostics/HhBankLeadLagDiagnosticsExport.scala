package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.config.{HhBankLeadLagScenarios, SimParams}
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.engine.mechanisms.Macroprudential
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.montecarlo.core.McSeedMonth
import com.boombustgroup.amorfati.montecarlo.diagnostics.McDiagnosticRunner
import com.boombustgroup.amorfati.montecarlo.io.{McTsvFile, McTsvSchema}
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
      householdShare: BigDecimal,
      closingHouseholdCount: Int,
      closingHouseholdShare: BigDecimal,
      hhMonthlyIncome: BigDecimal,
      hhConsumerLoanDefault: BigDecimal,
      hhConsumerInsolvencyDefault: BigDecimal,
      hhLiquidityBridgeChargeOff: BigDecimal,
      hhLiquidityShortfallFinancing: BigDecimal,
      hhConsumerDefault: BigDecimal,
      hhConsumerDebtService: BigDecimal,
      hhConsumerApprovedOrigination: BigDecimal,
      hhConsumerRejectedOrigination: BigDecimal,
      hhConsumerBankRejectedOrigination: BigDecimal,
      hhConsumerDebtArrears: BigDecimal,
      hhMortgageArrears: BigDecimal,
      hhConsumerLoanDefaultShare: BigDecimal,
      hhConsumerInsolvencyDefaultShare: BigDecimal,
      hhLiquidityBridgeChargeOffShare: BigDecimal,
      hhLiquidityShortfallFinancingShare: BigDecimal,
      bankConsumerLoanStock: BigDecimal,
      bankConsumerLoanShare: BigDecimal,
      bankMortgageLoanStock: BigDecimal,
      bankMortgageLoanShare: BigDecimal,
      bankDeposits: BigDecimal,
      bankDepositShare: BigDecimal,
      bankConsumerNplStock: BigDecimal,
      bankConsumerNplLoss: BigDecimal,
      bankRwa: BigDecimal,
      bankRwaShare: BigDecimal,
      bankCapital: BigDecimal,
      bankCapitalShare: BigDecimal,
      bankCapitalDelta: BigDecimal,
      bankEffectiveMinCar: BigDecimal,
      bankCar: BigDecimal,
      bankCarBuffer: BigDecimal,
      bankCapitalBuffer: BigDecimal,
      bankCapitalBufferToRwa: BigDecimal,
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

  final case class ConcentrationPeakResult(
      runId: String,
      scenarioId: String,
      scenarioLabel: String,
      seed: Long,
      metric: String,
      peakMonth: Int,
      bankId: Int,
      bankName: String,
      topShare: BigDecimal,
      amount: BigDecimal,
      denominator: BigDecimal,
      bankConsumerLoanShare: BigDecimal,
      householdShare: BigDecimal,
      bankRwaShare: BigDecimal,
      bankCapitalBufferToRwa: BigDecimal,
      bankCar: BigDecimal,
      bankEffectiveMinCar: BigDecimal,
      bankNewFailure: Int,
      bankFailed: Int,
      interpretation: String,
  )

  final case class ExportResult(
      paths: Vector[Path],
      bankMonthRows: Vector[BankMonthRow],
      correlations: Vector[CorrelationResult],
      counterfactuals: Vector[CounterfactualResult],
      concentrationPeaks: Vector[ConcentrationPeakResult],
  )

  private final class HhBankTotals:
    var householdCount: Int                  = 0
    var monthlyIncome: PLN                   = PLN.Zero
    var consumerLoanDefault: PLN             = PLN.Zero
    var consumerInsolvencyDefault: PLN       = PLN.Zero
    var liquidityBridgeChargeOff: PLN        = PLN.Zero
    var liquidityShortfallFinancing: PLN     = PLN.Zero
    var consumerDefault: PLN                 = PLN.Zero
    var consumerDebtService: PLN             = PLN.Zero
    var consumerApprovedOrigination: PLN     = PLN.Zero
    var consumerRejectedOrigination: PLN     = PLN.Zero
    var consumerBankRejectedOrigination: PLN = PLN.Zero
    var consumerDebtArrears: PLN             = PLN.Zero
    var mortgageArrears: PLN                 = PLN.Zero

    def add(flow: com.boombustgroup.amorfati.agents.Household.MonthlyFlow): Unit =
      householdCount += 1
      monthlyIncome = monthlyIncome + flow.monthlyIncome
      consumerLoanDefault = consumerLoanDefault + flow.consumerLoanDefault
      consumerInsolvencyDefault = consumerInsolvencyDefault + flow.consumerInsolvencyDefault
      liquidityBridgeChargeOff = liquidityBridgeChargeOff + flow.liquidityBridgeChargeOff
      liquidityShortfallFinancing = liquidityShortfallFinancing + flow.liquidityShortfallFinancing
      consumerDefault = consumerDefault + flow.consumerDefault
      consumerDebtService = consumerDebtService + flow.consumerDebtService
      consumerApprovedOrigination = consumerApprovedOrigination + flow.consumerApprovedOrigination
      consumerRejectedOrigination = consumerRejectedOrigination + flow.consumerRejectedOrigination
      consumerBankRejectedOrigination = consumerBankRejectedOrigination + flow.consumerBankRejectedOrigination
      consumerDebtArrears = consumerDebtArrears + flow.consumerDebtArrears
      mortgageArrears = mortgageArrears + flow.mortgageArrears

  private[diagnostics] val Scenarios: Vector[HhBankLeadLagScenarios.Spec] =
    HhBankLeadLagScenarios.all

  private val HhMetricAccessors: Vector[(String, BankMonthRow => BigDecimal)] = Vector(
    "HhConsumerLoanDefault"             -> (_.hhConsumerLoanDefault),
    "HhConsumerInsolvencyDefault"       -> (_.hhConsumerInsolvencyDefault),
    "HhLiquidityBridgeChargeOff"        -> (_.hhLiquidityBridgeChargeOff),
    "HhLiquidityShortfallFinancing"     -> (_.hhLiquidityShortfallFinancing),
    "HhConsumerDebtArrears"             -> (_.hhConsumerDebtArrears),
    "HhMortgageArrears"                 -> (_.hhMortgageArrears),
    "HhConsumerDebtService"             -> (_.hhConsumerDebtService),
    "HhConsumerBankRejectedOrigination" -> (_.hhConsumerBankRejectedOrigination),
  )

  private val BankMetricAccessors: Vector[(String, BankMonthRow => BigDecimal)] = Vector(
    "BankConsumerNplLoss" -> (_.bankConsumerNplLoss),
    "BankCapitalDelta"    -> (_.bankCapitalDelta),
    "BankCar"             -> (_.bankCar),
    "BankLcr"             -> (_.bankLcr),
    "BankNewFailure"      -> (row => BigDecimal(row.bankNewFailure)),
  )

  private final case class ConcentrationMetric(
      id: String,
      amount: BankMonthRow => BigDecimal,
      share: (BankMonthRow, BigDecimal) => BigDecimal,
      interpretation: String,
  )

  private def observedShare(accessor: BankMonthRow => BigDecimal): (BankMonthRow, BigDecimal) => BigDecimal =
    (row, _) => accessor(row)

  private def denominatorShare(amount: BankMonthRow => BigDecimal): (BankMonthRow, BigDecimal) => BigDecimal =
    (row, denominator) => if denominator > BigDecimal(0) then amount(row) / denominator else BigDecimal(0)

  private val ConcentrationMetrics: Vector[ConcentrationMetric] = Vector(
    ConcentrationMetric(
      id = "HouseholdRoutingShare",
      amount = row => BigDecimal(row.householdCount),
      share = observedShare(_.householdShare),
      interpretation = "Share of household-stage routed households assigned to the top bank.",
    ),
    ConcentrationMetric(
      id = "ClosingHouseholdRoutingShare",
      amount = row => BigDecimal(row.closingHouseholdCount),
      share = observedShare(_.closingHouseholdShare),
      interpretation = "Share of post-banking closing household assignments carried by the top bank.",
    ),
    ConcentrationMetric(
      id = "ConsumerLoanExposureShare",
      amount = _.bankConsumerLoanStock,
      share = observedShare(_.bankConsumerLoanShare),
      interpretation = "Share of closing unsecured consumer-loan stock held by the top bank.",
    ),
    ConcentrationMetric(
      id = "MortgageExposureShare",
      amount = _.bankMortgageLoanStock,
      share = observedShare(_.bankMortgageLoanShare),
      interpretation = "Share of closing mortgage-loan stock mirrored to the top bank.",
    ),
    ConcentrationMetric(
      id = "DepositShare",
      amount = _.bankDeposits,
      share = observedShare(_.bankDepositShare),
      interpretation = "Share of closing customer deposits assigned to the top bank.",
    ),
    ConcentrationMetric(
      id = "ConsumerLoanDefaultFlowShare",
      amount = _.hhConsumerLoanDefault,
      share = observedShare(_.hhConsumerLoanDefaultShare),
      interpretation = "Share of ordinary consumer-loan default flow routed to the top bank.",
    ),
    ConcentrationMetric(
      id = "ConsumerInsolvencyDefaultFlowShare",
      amount = _.hhConsumerInsolvencyDefault,
      share = observedShare(_.hhConsumerInsolvencyDefaultShare),
      interpretation = "Share of personal-insolvency consumer default flow routed to the top bank.",
    ),
    ConcentrationMetric(
      id = "LiquidityBridgeChargeOffFlowShare",
      amount = _.hhLiquidityBridgeChargeOff,
      share = observedShare(_.hhLiquidityBridgeChargeOffShare),
      interpretation = "Share of same-month liquidity-bridge charge-off flow routed to the top bank.",
    ),
    ConcentrationMetric(
      id = "LiquidityShortfallFinancingFlowShare",
      amount = _.hhLiquidityShortfallFinancing,
      share = observedShare(_.hhLiquidityShortfallFinancingShare),
      interpretation = "Share of same-month liquidity shortfall financing routed to the top bank.",
    ),
    ConcentrationMetric(
      id = "CapitalShare",
      amount = _.bankCapital,
      share = observedShare(_.bankCapitalShare),
      interpretation = "Share of closing bank capital carried by the top bank.",
    ),
    ConcentrationMetric(
      id = "CapitalBufferShare",
      amount = _.bankCapitalBuffer,
      share = denominatorShare(_.bankCapitalBuffer),
      interpretation = "Share of aggregate capital buffer carried by the top bank.",
    ),
    ConcentrationMetric(
      id = "RwaShare",
      amount = _.bankRwa,
      share = observedShare(_.bankRwaShare),
      interpretation = "Share of inferred bank RWA denominator carried by the top bank.",
    ),
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
      bankRows       <- McTsvFile
        .writeFold(
          validConfig.runRoot.resolve("hh-bank-lead-lag-bank-months.tsv"),
          McDiagnosticRunner
            .runScenarioSeedStreams(
              Scenarios,
              validConfig.seedRange,
              validConfig.months,
              _.id,
              _.params,
            )((scenario, seed, months) => computeBankRows(validConfig, scenario, seed, months)),
          BankMonthTsvSchema,
          Vector.newBuilder[BankMonthRow],
        )((builder, row) => builder += row)(DiagnosticIo.outputFailure)
        .map(_.result())
      correlations    = computeCorrelations(validConfig, bankRows)
      counterfactuals = summarizeCounterfactuals(validConfig, bankRows)
      peaks           = summarizeConcentrationPeaks(validConfig, bankRows)
      paths          <- writeArtifactsZIO(validConfig, bankRows, correlations, counterfactuals, peaks)
    yield ExportResult(paths, bankRows, correlations, counterfactuals, peaks)

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

  private[diagnostics] def consumerNplLoss(
      consumerLoanDefault: PLN,
      consumerInsolvencyDefault: PLN,
  )(using p: SimParams): PLN =
    val ordinaryDefault = (consumerLoanDefault - consumerInsolvencyDefault).max(PLN.Zero)
    ordinaryDefault * (Share.One - p.household.ccNplRecovery) +
      consumerInsolvencyDefault * (Share.One - p.household.ccInsolvencyRecovery)

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

    val hhTotals               = Array.tabulate(nBanks)(_ => HhBankTotals())
    val closingHouseholdCounts = Array.fill(nBanks)(0)
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

    seedMonth.state.households.foreach: household =>
      val bankId = household.bankId.toInt
      require(bankId >= 0 && bankId < nBanks, s"Closing household ${household.id.toInt} references invalid bank id $bankId for $nBanks banks")
      closingHouseholdCounts(bankId) += 1

    val bankStocks                       = seedMonth.state.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks)
    val corpBondHoldings                 = seedMonth.state.banks.map(bank => CorporateBondOwnership.bankHolderFor(seedMonth.state.ledgerFinancialState, bank.id))
    val bankRwas                         = seedMonth.state.banks
      .zip(bankStocks)
      .zip(corpBondHoldings)
      .map { case ((bank, stocks), corpBond) => inferredRwa(bank, stocks, corpBond) }
    val totalHouseholds                  = seedMonth.householdSnapshotState.households.length
    val totalClosingHh                   = seedMonth.state.households.length
    val totalConsumerLoanDefault         = hhTotals.iterator.map(_.consumerLoanDefault).sumPln
    val totalConsumerInsolvencyDefault   = hhTotals.iterator.map(_.consumerInsolvencyDefault).sumPln
    val totalLiquidityBridgeChargeOff    = hhTotals.iterator.map(_.liquidityBridgeChargeOff).sumPln
    val totalLiquidityShortfallFinancing = hhTotals.iterator.map(_.liquidityShortfallFinancing).sumPln
    val totalConsumerLoans               = bankStocks.map(_.consumerLoan).sumPln
    val totalMortgageLoans               = bankStocks.map(_.mortgageLoan).sumPln
    val totalDeposits                    = bankStocks.map(_.totalDeposits).sumPln
    val totalBankRwa                     = bankRwas.sumPln
    val totalBankCapital                 = seedMonth.state.banks.map(_.capital).sumPln
    val failureDiagnostic                = seedMonth.state.world.flows.bankFailure
    val unemployment                     = fixed(seedMonth.state.world.unemploymentRate(seedMonth.state.householdAggregates.employed).toLong)
    val inflation                        = fixed(seedMonth.state.world.inflation.toLong)
    val monthlyGdp                       = pln(seedMonth.state.world.cachedMonthlyGdpProxy)

    seedMonth.state.banks.zipWithIndex.map: (bank, idx) =>
      val stocks            = bankStocks(idx)
      val openingBank       = seedMonth.openingState.banks(idx)
      val totals            = hhTotals(idx)
      val bankConsumerLoss  = consumerNplLoss(totals.consumerLoanDefault, totals.consumerInsolvencyDefault)
      val newFailure        = if !openingBank.failed && bank.failed then 1 else 0
      val failureReasonCode =
        if newFailure == 1 && failureDiagnostic.firstNewBankId == bank.id.toInt then failureDiagnostic.firstNewReasonCode
        else 0
      val corpBond          = corpBondHoldings(idx)
      val bankRwa           = bankRwas(idx)
      val bankCar           = Banking.car(bank, stocks, corpBond)
      val effectiveMinCar   = Macroprudential.effectiveMinCar(bank.id.toInt, seedMonth.state.world.mechanisms.macropru.ccyb)
      val capitalBuffer     = bank.capital - (bankRwa * effectiveMinCar)
      BankMonthRow(
        runId = config.runId,
        scenarioId = scenario.id,
        scenarioLabel = scenario.label,
        seed = seed,
        month = seedMonth.executionMonth.toInt,
        bankId = bank.id.toInt,
        bankName = bankName(idx),
        householdCount = totals.householdCount,
        householdShare = countRatio(totals.householdCount, totalHouseholds),
        closingHouseholdCount = closingHouseholdCounts(idx),
        closingHouseholdShare = countRatio(closingHouseholdCounts(idx), totalClosingHh),
        hhMonthlyIncome = pln(totals.monthlyIncome),
        hhConsumerLoanDefault = pln(totals.consumerLoanDefault),
        hhConsumerInsolvencyDefault = pln(totals.consumerInsolvencyDefault),
        hhLiquidityBridgeChargeOff = pln(totals.liquidityBridgeChargeOff),
        hhLiquidityShortfallFinancing = pln(totals.liquidityShortfallFinancing),
        hhConsumerDefault = pln(totals.consumerDefault),
        hhConsumerDebtService = pln(totals.consumerDebtService),
        hhConsumerApprovedOrigination = pln(totals.consumerApprovedOrigination),
        hhConsumerRejectedOrigination = pln(totals.consumerRejectedOrigination),
        hhConsumerBankRejectedOrigination = pln(totals.consumerBankRejectedOrigination),
        hhConsumerDebtArrears = pln(totals.consumerDebtArrears),
        hhMortgageArrears = pln(totals.mortgageArrears),
        hhConsumerLoanDefaultShare = finiteRatio(totals.consumerLoanDefault, totalConsumerLoanDefault),
        hhConsumerInsolvencyDefaultShare = finiteRatio(totals.consumerInsolvencyDefault, totalConsumerInsolvencyDefault),
        hhLiquidityBridgeChargeOffShare = finiteRatio(totals.liquidityBridgeChargeOff, totalLiquidityBridgeChargeOff),
        hhLiquidityShortfallFinancingShare = finiteRatio(totals.liquidityShortfallFinancing, totalLiquidityShortfallFinancing),
        bankConsumerLoanStock = pln(stocks.consumerLoan),
        bankConsumerLoanShare = finiteRatio(stocks.consumerLoan, totalConsumerLoans),
        bankMortgageLoanStock = pln(stocks.mortgageLoan),
        bankMortgageLoanShare = finiteRatio(stocks.mortgageLoan, totalMortgageLoans),
        bankDeposits = pln(stocks.totalDeposits),
        bankDepositShare = finiteRatio(stocks.totalDeposits, totalDeposits),
        bankConsumerNplStock = pln(bank.consumerNpl),
        bankConsumerNplLoss = pln(bankConsumerLoss),
        bankRwa = pln(bankRwa),
        bankRwaShare = finiteRatio(bankRwa, totalBankRwa),
        bankCapital = pln(bank.capital),
        bankCapitalShare = finiteRatio(bank.capital, totalBankCapital),
        bankCapitalDelta = pln(bank.capital - openingBank.capital),
        bankEffectiveMinCar = fixed(effectiveMinCar.toLong),
        bankCar = fixed(bankCar.toLong),
        bankCarBuffer = fixed((bankCar - effectiveMinCar).toLong),
        bankCapitalBuffer = pln(capitalBuffer),
        bankCapitalBufferToRwa = finiteRatio(capitalBuffer, bankRwa),
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

  private[diagnostics] def summarizeConcentrationPeaks(config: Config, rows: Vector[BankMonthRow]): Vector[ConcentrationPeakResult] =
    val byScenarioSeed = rows.groupBy(row => (row.scenarioId, row.scenarioLabel, row.seed))
    byScenarioSeed.toVector
      .sortBy { case ((scenarioId, _, seed), _) => (scenarioId, seed) }
      .flatMap: (key, seedRows) =>
        val (scenarioId, scenarioLabel, seed) = key
        ConcentrationMetrics.flatMap: metric =>
          peakForMetric(seedRows, metric).map: peak =>
            val (row, amount, denominator) = peak
            ConcentrationPeakResult(
              runId = config.runId,
              scenarioId = scenarioId,
              scenarioLabel = scenarioLabel,
              seed = seed,
              metric = metric.id,
              peakMonth = row.month,
              bankId = row.bankId,
              bankName = row.bankName,
              topShare = metric.share(row, denominator),
              amount = amount,
              denominator = denominator,
              bankConsumerLoanShare = row.bankConsumerLoanShare,
              householdShare = row.householdShare,
              bankRwaShare = row.bankRwaShare,
              bankCapitalBufferToRwa = row.bankCapitalBufferToRwa,
              bankCar = row.bankCar,
              bankEffectiveMinCar = row.bankEffectiveMinCar,
              bankNewFailure = row.bankNewFailure,
              bankFailed = row.bankFailed,
              interpretation = metric.interpretation,
            )

  private def peakForMetric(
      rows: Vector[BankMonthRow],
      metric: ConcentrationMetric,
  ): Option[(BankMonthRow, BigDecimal, BigDecimal)] =
    val candidates = rows
      .groupBy(_.month)
      .toVector
      .flatMap: (_, monthRows) =>
        val denominator = monthRows.map(metric.amount).sum
        if denominator > BigDecimal(0) then monthRows.map(row => (row, metric.amount(row), denominator))
        else Vector.empty

    candidates.maxByOption: (row, amount, denominator) =>
      (metric.share(row, denominator), amount, -row.month, -row.bankId)

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
      bankRows: Vector[BankMonthRow],
      correlations: Vector[CorrelationResult],
      counterfactuals: Vector[CounterfactualResult],
      concentrationPeaks: Vector[ConcentrationPeakResult],
  ): ZIO[Any, String, Vector[Path]] =
    val bankRowsPath        = config.runRoot.resolve("hh-bank-lead-lag-bank-months.tsv")
    val correlationsPath    = config.runRoot.resolve("hh-bank-lead-lag-correlations.tsv")
    val counterfactualsPath = config.runRoot.resolve("hh-bank-lead-lag-counterfactuals.tsv")
    val concentrationPath   = config.runRoot.resolve("hh-bank-lead-lag-concentration-peaks.tsv")
    val reportPath          = config.runRoot.resolve("hh-bank-lead-lag-report.md")
    for
      _ <- McTsvFile.writeAll(correlationsPath, correlations, CorrelationTsvSchema)(DiagnosticIo.outputFailure)
      _ <- McTsvFile.writeAll(counterfactualsPath, counterfactuals, CounterfactualTsvSchema)(DiagnosticIo.outputFailure)
      _ <- McTsvFile.writeAll(concentrationPath, concentrationPeaks, ConcentrationPeakTsvSchema)(DiagnosticIo.outputFailure)
      _ <- DiagnosticIo.writeText(reportPath, renderReport(config, bankRows, correlations, counterfactuals, concentrationPeaks))
    yield Vector(bankRowsPath, correlationsPath, counterfactualsPath, concentrationPath, reportPath)

  private val BankMonthTsvSchema: McTsvSchema[BankMonthRow] =
    val header = McTsvSchema.header(
      "RunId",
      "ScenarioId",
      "ScenarioLabel",
      "Seed",
      "Month",
      "BankId",
      "BankName",
      "HouseholdCount",
      "HouseholdShare",
      "ClosingHouseholdCount",
      "ClosingHouseholdShare",
      "HhMonthlyIncome",
      "HhConsumerLoanDefault",
      "HhConsumerInsolvencyDefault",
      "HhLiquidityBridgeChargeOff",
      "HhLiquidityShortfallFinancing",
      "HhConsumerDefault",
      "HhConsumerDebtService",
      "HhConsumerApprovedOrigination",
      "HhConsumerRejectedOrigination",
      "HhConsumerBankRejectedOrigination",
      "HhConsumerDebtArrears",
      "HhMortgageArrears",
      "HhConsumerLoanDefaultShare",
      "HhConsumerInsolvencyDefaultShare",
      "HhLiquidityBridgeChargeOffShare",
      "HhLiquidityShortfallFinancingShare",
      "BankConsumerLoanStock",
      "BankConsumerLoanShare",
      "BankMortgageLoanStock",
      "BankMortgageLoanShare",
      "BankDeposits",
      "BankDepositShare",
      "BankConsumerNplStock",
      "BankConsumerNplLoss",
      "BankRwa",
      "BankRwaShare",
      "BankCapital",
      "BankCapitalShare",
      "BankCapitalDelta",
      "BankEffectiveMinCar",
      "BankCar",
      "BankCarBuffer",
      "BankCapitalBuffer",
      "BankCapitalBufferToRwa",
      "BankLcr",
      "BankFailed",
      "BankNewFailure",
      "BankFailureReasonCode",
      "Unemployment",
      "Inflation",
      "MonthlyGdpProxy",
    )
    McTsvSchema(header, renderBankMonthRow)

  private val CorrelationTsvSchema: McTsvSchema[CorrelationResult] =
    val header =
      McTsvSchema.header("RunId", "ScenarioId", "ScenarioLabel", "LagMonths", "HhMetric", "BankMetric", "Observations", "Correlation", "Interpretation")
    McTsvSchema(
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
        ).map(tsv).mkString("\t"),
    )

  private val CounterfactualTsvSchema: McTsvSchema[CounterfactualResult] =
    val header = McTsvSchema.header(
      "RunId",
      "ScenarioId",
      "ScenarioLabel",
      "Seeds",
      "Months",
      "FailedSeeds",
      "FirstFailureMonthMean",
      "TerminalFailuresMean",
      "CumulativeNewFailuresMean",
      "CumulativeConsumerNplLossMean",
      "CumulativeHhConsumerLoanDefaultMean",
      "CumulativeLiquidityBridgeChargeOffMean",
      "Interpretation",
    )
    McTsvSchema(
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
        ).map(tsv).mkString("\t"),
    )

  private val ConcentrationPeakTsvSchema: McTsvSchema[ConcentrationPeakResult] =
    val header = McTsvSchema.header(
      "RunId",
      "ScenarioId",
      "ScenarioLabel",
      "Seed",
      "Metric",
      "PeakMonth",
      "BankId",
      "BankName",
      "TopShare",
      "Amount",
      "Denominator",
      "BankConsumerLoanShare",
      "HouseholdShare",
      "BankRwaShare",
      "BankCapitalBufferToRwa",
      "BankCar",
      "BankEffectiveMinCar",
      "BankNewFailure",
      "BankFailed",
      "Interpretation",
    )
    McTsvSchema(
      header,
      row =>
        Vector(
          row.runId,
          row.scenarioId,
          row.scenarioLabel,
          row.seed.toString,
          row.metric,
          row.peakMonth.toString,
          row.bankId.toString,
          row.bankName,
          renderDecimal(row.topShare),
          renderDecimal(row.amount),
          renderDecimal(row.denominator),
          renderDecimal(row.bankConsumerLoanShare),
          renderDecimal(row.householdShare),
          renderDecimal(row.bankRwaShare),
          renderDecimal(row.bankCapitalBufferToRwa),
          renderDecimal(row.bankCar),
          renderDecimal(row.bankEffectiveMinCar),
          row.bankNewFailure.toString,
          row.bankFailed.toString,
          row.interpretation,
        ).map(tsv).mkString("\t"),
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
      renderDecimal(row.householdShare),
      row.closingHouseholdCount.toString,
      renderDecimal(row.closingHouseholdShare),
      renderDecimal(row.hhMonthlyIncome),
      renderDecimal(row.hhConsumerLoanDefault),
      renderDecimal(row.hhConsumerInsolvencyDefault),
      renderDecimal(row.hhLiquidityBridgeChargeOff),
      renderDecimal(row.hhLiquidityShortfallFinancing),
      renderDecimal(row.hhConsumerDefault),
      renderDecimal(row.hhConsumerDebtService),
      renderDecimal(row.hhConsumerApprovedOrigination),
      renderDecimal(row.hhConsumerRejectedOrigination),
      renderDecimal(row.hhConsumerBankRejectedOrigination),
      renderDecimal(row.hhConsumerDebtArrears),
      renderDecimal(row.hhMortgageArrears),
      renderDecimal(row.hhConsumerLoanDefaultShare),
      renderDecimal(row.hhConsumerInsolvencyDefaultShare),
      renderDecimal(row.hhLiquidityBridgeChargeOffShare),
      renderDecimal(row.hhLiquidityShortfallFinancingShare),
      renderDecimal(row.bankConsumerLoanStock),
      renderDecimal(row.bankConsumerLoanShare),
      renderDecimal(row.bankMortgageLoanStock),
      renderDecimal(row.bankMortgageLoanShare),
      renderDecimal(row.bankDeposits),
      renderDecimal(row.bankDepositShare),
      renderDecimal(row.bankConsumerNplStock),
      renderDecimal(row.bankConsumerNplLoss),
      renderDecimal(row.bankRwa),
      renderDecimal(row.bankRwaShare),
      renderDecimal(row.bankCapital),
      renderDecimal(row.bankCapitalShare),
      renderDecimal(row.bankCapitalDelta),
      renderDecimal(row.bankEffectiveMinCar),
      renderDecimal(row.bankCar),
      renderDecimal(row.bankCarBuffer),
      renderDecimal(row.bankCapitalBuffer),
      renderDecimal(row.bankCapitalBufferToRwa),
      renderDecimal(row.bankLcr),
      row.bankFailed.toString,
      row.bankNewFailure.toString,
      row.bankFailureReasonCode.toString,
      renderDecimal(row.unemployment),
      renderDecimal(row.inflation),
      renderDecimal(row.monthlyGdpProxy),
    ).map(tsv).mkString("\t")

  private def renderReport(
      config: Config,
      bankRows: Vector[BankMonthRow],
      correlations: Vector[CorrelationResult],
      counterfactuals: Vector[CounterfactualResult],
      concentrationPeaks: Vector[ConcentrationPeakResult],
  ): String =
    val baseline      = counterfactuals.find(_.scenarioId == "baseline")
    val noHit         = counterfactuals.find(_.scenarioId == "no-consumer-npl-capital-hit")
    val topCorr       = correlations
      .filter(row => row.scenarioId == "baseline" && row.lagMonths > 0 && row.correlation.isDefined)
      .sortBy(row => -row.correlation.get.abs)
      .take(10)
    val baselinePeaks = concentrationPeaks
      .filter(_.scenarioId == "baseline")
      .sortBy(row => (row.seed, row.metric))
    val firstFailures = bankRows
      .filter(row => row.scenarioId == "baseline" && row.bankNewFailure > 0)
      .sortBy(row => (row.seed, row.month, row.bankId))
    val command       =
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
      "Concentration shares make the household-to-bank routing and product-specific",
      "consumer-credit flow concentration directly visible before failures occur.",
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
      "## Baseline Concentration Peaks",
      "",
      concentrationTable(baselinePeaks),
      "",
      "## Baseline First Failure Rows",
      "",
      firstFailureTable(firstFailures),
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

  private def concentrationTable(rows: Vector[ConcentrationPeakResult]): String =
    if rows.isEmpty then "No baseline concentration peak rows."
    else
      val header = "| Seed | Metric | Month | Bank | Top Share | Amount | Denominator | Consumer Loan Share | RWA Share | Capital Buffer / RWA |"
      val sep    = "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"
      val body   = rows.map: row =>
        s"| ${row.seed} | ${row.metric} | ${row.peakMonth} | ${row.bankId} ${row.bankName} | ${renderDecimal(row.topShare)} | ${renderDecimal(row.amount)} | ${renderDecimal(row.denominator)} | ${renderDecimal(row.bankConsumerLoanShare)} | ${renderDecimal(row.bankRwaShare)} | ${renderDecimal(row.bankCapitalBufferToRwa)} |"
      (header +: sep +: body).mkString("\n")

  private def firstFailureTable(rows: Vector[BankMonthRow]): String =
    if rows.isEmpty then "No baseline first-failure rows in the observed horizon."
    else
      val header =
        "| Seed | Month | Bank | Reason | HhConsumerLoanDefaultShare | Bridge Charge-Off Share | Consumer Loan Share | RWA Share | CAR | Min CAR | Capital Buffer / RWA |"
      val sep    = "| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |"
      val body   = rows.map: row =>
        s"| ${row.seed} | ${row.month} | ${row.bankId} ${row.bankName} | ${row.bankFailureReasonCode} | ${renderDecimal(row.hhConsumerLoanDefaultShare)} | ${renderDecimal(row.hhLiquidityBridgeChargeOffShare)} | ${renderDecimal(row.bankConsumerLoanShare)} | ${renderDecimal(row.bankRwaShare)} | ${renderDecimal(row.bankCar)} | ${renderDecimal(row.bankEffectiveMinCar)} | ${renderDecimal(row.bankCapitalBufferToRwa)} |"
      (header +: sep +: body).mkString("\n")

  private def causalReading(baseline: Option[CounterfactualResult], noHit: Option[CounterfactualResult]): String =
    (baseline, noHit) match
      case (Some(base), Some(counter)) =>
        val failureDelta  = counter.failedSeeds - base.failedSeeds
        val terminalDelta = counter.terminalFailuresMean - base.terminalFailuresMean
        s"Neutralizing the consumer NPL capital hit changes failed seed count by $failureDelta and mean terminal failures by ${renderDecimal(terminalDelta)} versus baseline. If failures fall materially while HH default flows remain visible, the direct consumer NPL capital-hit channel is necessary for bank failure timing in this fixture. This is a controlled model intervention, not full macro-causal identification."
      case _                           =>
        "Counterfactual rows are incomplete; counterfactual reading unavailable."

  private def inferredRwa(bank: Banking.BankState, stocks: Banking.BankFinancialStocks, corpBondHoldings: PLN)(using p: SimParams): PLN =
    val car      = Banking.car(bank, stocks, corpBondHoldings)
    val baseRwa  = Banking.riskWeightedAssets(stocks, corpBondHoldings)
    val ratioRwa =
      if car > Multiplier.Zero && bank.capital > PLN.Zero then bank.capital / car
      else PLN.Zero
    ratioRwa.max(baseRwa)

  private def pln(value: PLN)(using p: SimParams): BigDecimal =
    fixed(polandScale(value).toLong)

  private def polandScale(value: PLN)(using p: SimParams): PLN =
    if p.gdpRatio > Scalar.Zero then value / p.gdpRatio.toMultiplier else value

  private def finiteRatio(numerator: PLN, denominator: PLN): BigDecimal =
    if denominator > PLN.Zero then fixed(numerator.ratioTo(denominator).toLong) else BigDecimal(0)

  private def countRatio(numerator: Int, denominator: Int): BigDecimal =
    if denominator > 0 then BigDecimal(numerator) / BigDecimal(denominator) else BigDecimal(0)

  private def fixed(raw: Long): BigDecimal =
    BigDecimal(raw) / BigDecimal(FixedPointBase.Scale)

  private def renderDecimal(value: BigDecimal): String =
    value.setScale(6, RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros.toPlainString

  private def tsv(value: String): String =
    val escaped = value.replace("\"", "\"\"")
    if escaped.exists(ch => ch == '\t' || ch == '"' || ch == '\n' || ch == '\r') then s""""$escaped""""
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
      |- hh-bank-lead-lag-bank-months.tsv
      |- hh-bank-lead-lag-correlations.tsv
      |- hh-bank-lead-lag-counterfactuals.tsv
      |- hh-bank-lead-lag-concentration-peaks.tsv
      |- hh-bank-lead-lag-report.md
      |""".stripMargin
