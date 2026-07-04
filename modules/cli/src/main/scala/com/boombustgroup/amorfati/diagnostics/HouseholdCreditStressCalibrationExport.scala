package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.montecarlo.core.{McSeedMonth, MetricValue}
import com.boombustgroup.amorfati.montecarlo.core.MetricValue.*
import com.boombustgroup.amorfati.montecarlo.diagnostics.McDiagnosticRunner
import com.boombustgroup.amorfati.montecarlo.io.{McTsvFile, McTsvSchema}
import com.boombustgroup.amorfati.montecarlo.timeseries.McTimeseriesSchema
import com.boombustgroup.amorfati.types.*
import zio.ZIO
import zio.stream.ZStream

import java.nio.file.Path
import scala.math.BigDecimal.RoundingMode
import scala.util.Try

object HouseholdCreditStressCalibrationExport:

  private val BaselineVintage = "2026-04-30 model-start baseline"

  final case class Config(
      seedStart: Long = 1L,
      seeds: Int = 5,
      months: Int = 60,
      runId: String = "household-credit-stress",
      out: Path = Path.of("target/household-credit-stress"),
  ):
    def seedRange: Vector[Long] = Vector.range(seedStart, seedStart + seeds.toLong)
    def runRoot: Path           = out.resolve(runId)

  enum GuardrailClass(val token: String):
    case HardInvariant          extends GuardrailClass("HARD_INVARIANT")
    case SoftCalibrationWarning extends GuardrailClass("SOFT_CALIBRATION_WARNING")
    case ExploratoryDiagnostic  extends GuardrailClass("EXPLORATORY_DIAGNOSTIC")

  enum Status(val token: String):
    case Pass extends Status("PASS")
    case Warn extends Status("WARN")
    case Fail extends Status("FAIL")
    case Info extends Status("INFO")

  enum ObservationWindow(val token: String):
    case Terminal     extends ObservationWindow("TERMINAL")
    case PeakMonthly  extends ObservationWindow("PEAK_MONTHLY")
    case PeakRolling3 extends ObservationWindow("PEAK_ROLLING_3")

  enum ObservedValue:
    case Finite(value: BigDecimal)
    case PositiveInfinity
    case NotAvailable

    def finite: Option[BigDecimal] =
      this match
        case Finite(value) => Some(value)
        case _             => None

  final case class TargetBand(
      id: String,
      label: String,
      unit: String,
      guardrailClass: GuardrailClass,
      vintage: String,
      lower: Option[BigDecimal],
      upper: Option[BigDecimal],
      sourceNote: String,
      interpretation: String,
  )

  final case class SeedMetric(
      runId: String,
      seed: Long,
      months: Int,
      observationWindow: ObservationWindow,
      observationMonth: Int,
      target: TargetBand,
      value: ObservedValue,
      status: Status,
  )

  final case class SummaryMetric(
      runId: String,
      months: Int,
      seeds: Int,
      observationWindow: ObservationWindow,
      target: TargetBand,
      mean: ObservedValue,
      min: Option[BigDecimal],
      max: Option[BigDecimal],
      status: Status,
  )

  final case class ExportResult(paths: Vector[Path], seedMetrics: Vector[SeedMetric], summary: Vector[SummaryMetric])

  private final case class SeedContext(month: McSeedMonth):
    val monthlyRow: Array[MetricValue] = month.row
    val monthlyState                   = month.state
    val householdCount: Int            = monthlyState.households.size
    val monthNumber: Int               = month.executionMonth.toInt

    def col(name: String): BigDecimal =
      decimal(McTimeseriesSchema.colNames.indexOf(name) match
        case -1      => throw new IllegalArgumentException(s"Missing Monte Carlo column: $name")
        case ordinal => monthlyRow(ordinal).toLong)

    def ratioColumn(numerator: String, denominator: String): ObservedValue =
      ratio(col(numerator), col(denominator))

    def householdRatio(numerator: PLN, denominator: PLN): ObservedValue =
      ratioRaw(BigDecimal(numerator.toLong), BigDecimal(denominator.toLong))

    def householdRatio(numerator: PLN, denominatorRaw: BigDecimal): ObservedValue =
      ratioRaw(BigDecimal(numerator.toLong), denominatorRaw)

  private final case class MetricDef(target: TargetBand, compute: SeedContext => ObservedValue)
  private[diagnostics] final case class MetricObservation(month: Int, value: ObservedValue)
  private[diagnostics] final case class MetricAccumulator(
      terminal: Option[MetricObservation],
      peakMonthly: Option[MetricObservation],
      rollingWindow: Vector[MetricObservation],
      peakRolling: Option[MetricObservation],
      observationCount: Int,
  ):
    def observe(observation: MetricObservation, windowSize: Int): MetricAccumulator =
      val nextWindow           = (rollingWindow :+ observation).takeRight(windowSize)
      val nextObservationCount = observationCount + 1
      val rollingCandidate     =
        Option.when(nextObservationCount >= windowSize):
          MetricObservation(nextWindow.last.month, rollingValue(nextWindow.map(_.value)))
      MetricAccumulator(
        terminal = Some(observation),
        peakMonthly = Some(bestObservation(peakMonthly, observation)),
        rollingWindow = nextWindow,
        peakRolling = rollingCandidate.fold(peakRolling)(candidate => Some(bestObservation(peakRolling, candidate))),
        observationCount = nextObservationCount,
      )

    def toSeedMetrics(config: Config, seed: Long, target: TargetBand, windowSize: Int): Either[String, Vector[SeedMetric]] =
      terminal match
        case None       => Left(s"metric ${target.id} has no observations")
        case Some(last) =>
          val rolling =
            if observationCount >= windowSize then peakRolling.getOrElse(last)
            else MetricObservation(last.month, rollingValue(rollingWindow.map(_.value)))
          Right(
            Vector(
              seedMetric(config, seed, target, ObservationWindow.Terminal, last),
              seedMetric(config, seed, target, ObservationWindow.PeakMonthly, peakMonthly.getOrElse(last)),
              seedMetric(config, seed, target, ObservationWindow.PeakRolling3, rolling),
            ),
          )

  private[diagnostics] object MetricAccumulator:
    val Empty: MetricAccumulator =
      MetricAccumulator(None, None, Vector.empty, None, 0)

  private val RequiredMetricIds: Set[String] = Set(
    "ConsumerLoansToGdp",
    "MortgageLoansToGdp",
    "ConsumerDebtServiceToIncome",
    "MortgageDebtServiceToIncome",
    "MortgagePrincipalToIncome",
    "MortgageInterestToIncome",
    "ConsumerDefaultToConsumerLoans",
    "LiquidityBridgeChargeOffToConsumerLoans",
    "LiquidityBridgeChargeOffShareOfHouseholdCreditWriteOff",
    "MortgageDefaultToMortgageLoans",
    "HouseholdBankruptcyShare",
    "PositiveDepositsToMonthlyIncome",
    "MedianDepositToMeanMonthlyIncome",
    "NegativeDepositShare",
    "DebtArrearsToShortfall",
    "UnmetBasicConsumptionToIncome",
    "DiscretionaryConsumptionCompressionToIncome",
    "ShortfallToIncome",
    "ShortfallToApprovedOrigination",
    "RejectedConsumerCreditDemandToApprovedOrigination",
    "RejectedConsumerCreditDemandToShortfall",
  )

  private[diagnostics] val Targets: Vector[TargetBand] = Vector(
    TargetBand(
      id = "ConsumerLoansToGdp",
      label = "Consumer loan stock / annual GDP",
      unit = "ratio",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.03")),
      upper = Some(BigDecimal("0.08")),
      sourceNote = "Stylized-Poland 2026-04-30 household consumer-credit stock band; source extraction should later replace this with NBP/KNF series.",
      interpretation = "Consumer credit should be material, but far below mortgage credit as a share of GDP.",
    ),
    TargetBand(
      id = "MortgageLoansToGdp",
      label = "Mortgage stock / annual GDP",
      unit = "ratio",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.09")),
      upper = Some(BigDecimal("0.16")),
      sourceNote =
        "Anchored to the 2026-04-30 production baseline and the existing validation manifest bridge: KNF housing loans near 12% of model GDP, with a broad tolerance.",
      interpretation = "Mortgage stock should sit near the Polish housing-credit scale, not EU high-mortgage economies.",
    ),
    TargetBand(
      id = "ConsumerDebtServiceToIncome",
      label = "Consumer debt service / monthly household income",
      unit = "ratio",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = Some(BigDecimal("0.08")),
      sourceNote =
        "Stylized 2026-04-30 household cash-flow guardrail; formal calibration needs DSR microdata or bank loan-payment aggregates. The numerator is the household instalment burden, while only the interest component is bank income.",
      interpretation = "Consumer instalments, principal plus interest, should not dominate regular household income in the baseline.",
    ),
    TargetBand(
      id = "MortgageDebtServiceToIncome",
      label = "Mortgage debt service / monthly household income",
      unit = "ratio",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = Some(BigDecimal("0.12")),
      sourceNote = "Stylized 2026-04-30 household cash-flow guardrail for secured debt service.",
      interpretation = "Mortgage payments can be larger than consumer instalments, but should remain a minority of monthly income.",
    ),
    TargetBand(
      id = "MortgagePrincipalToIncome",
      label = "Mortgage principal service / monthly household income",
      unit = "ratio",
      guardrailClass = GuardrailClass.ExploratoryDiagnostic,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = None,
      sourceNote = "Issue #533 mortgage DSR decomposition; principal follows the housing mortgage-maturity schedule.",
      interpretation = "Separates amortization pressure from interest-rate pass-through in the secured debt-service burden.",
    ),
    TargetBand(
      id = "MortgageInterestToIncome",
      label = "Mortgage interest service / monthly household income",
      unit = "ratio",
      guardrailClass = GuardrailClass.ExploratoryDiagnostic,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = None,
      sourceNote = "Issue #533 mortgage DSR decomposition; interest follows the household bank lending-rate channel.",
      interpretation = "Shows the rate-sensitive part of mortgage debt service separately from scheduled principal repayment.",
    ),
    TargetBand(
      id = "ConsumerDefaultToConsumerLoans",
      label = "Consumer-loan defaults / consumer-loan stock",
      unit = "monthly ratio",
      guardrailClass = GuardrailClass.ExploratoryDiagnostic,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = Some(BigDecimal("0.03")),
      sourceNote = "Exploratory flow/stock stress ratio over ordinary outstanding consumer-loan principal defaults only; not an official NPL rate.",
      interpretation = "Separates true consumer-loan default from same-month liquidity-bridge write-offs.",
    ),
    TargetBand(
      id = "LiquidityBridgeChargeOffToConsumerLoans",
      label = "Liquidity-bridge charge-offs / consumer-loan stock",
      unit = "monthly ratio",
      guardrailClass = GuardrailClass.ExploratoryDiagnostic,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = None,
      sourceNote = "Issue #536 split of same-month liquidity bridge write-offs from ordinary consumer-loan defaults.",
      interpretation = "Identifies whether the old combined consumer-default stress ratio was driven by non-underwritten bridge settlements.",
    ),
    TargetBand(
      id = "LiquidityBridgeChargeOffShareOfHouseholdCreditWriteOff",
      label = "Liquidity-bridge charge-offs / household credit write-offs",
      unit = "share",
      guardrailClass = GuardrailClass.ExploratoryDiagnostic,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = Some(BigDecimal("1.00")),
      sourceNote = "Issue #867 attribution check after ConsumerDefault was narrowed to ordinary consumer-loan principal default.",
      interpretation =
        "Shows the share of household credit write-offs that is same-month liquidity bridge charge-off rather than ordinary consumer-loan default.",
    ),
    TargetBand(
      id = "MortgageDefaultToMortgageLoans",
      label = "Mortgage arrears/default flow / mortgage stock",
      unit = "monthly ratio",
      guardrailClass = GuardrailClass.ExploratoryDiagnostic,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = Some(BigDecimal("0.01")),
      sourceNote = "Exploratory flow/stock stress ratio; should later be mapped to arrears/default definitions.",
      interpretation = "Mortgage stress should be rarer than unsecured consumer-credit stress in the baseline.",
    ),
    TargetBand(
      id = "HouseholdBankruptcyShare",
      label = "Personal insolvencies / households",
      unit = "monthly share",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = Some(BigDecimal("0.01")),
      sourceNote =
        "Issue #871 stylized baseline path cap for monthly household personal-insolvency incidence after replacing the deterministic insolvency cliff.",
      interpretation = "Baseline personal insolvency can occur, but should not synchronize into monthly waves across a material household share.",
    ),
    TargetBand(
      id = "PositiveDepositsToMonthlyIncome",
      label = "Positive demand deposits / monthly household income",
      unit = "months of income",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("1.00")),
      upper = Some(BigDecimal("8.00")),
      sourceNote = "Stylized 2026-04-30 liquid-buffer band for household deposits relative to one month of income.",
      interpretation = "Aggregate liquid buffers should be neither exhausted nor implausibly huge relative to household income.",
    ),
    TargetBand(
      id = "MedianDepositToMeanMonthlyIncome",
      label = "Median demand deposit / mean monthly household income",
      unit = "months of mean income",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.20")),
      upper = Some(BigDecimal("6.00")),
      sourceNote = "Stylized 2026-04-30 distributional liquidity guardrail; later source bridge should use household wealth or deposit microdata.",
      interpretation = "The median household should have some liquidity, but not years of income in demand deposits.",
    ),
    TargetBand(
      id = "NegativeDepositShare",
      label = "Share of households with negative demand deposits",
      unit = "share",
      guardrailClass = GuardrailClass.HardInvariant,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = Some(BigDecimal("0.00")),
      sourceNote = "Runtime accounting invariant after liquidity shortfalls are externalized from bank deposits.",
      interpretation = "Demand deposits are non-negative bank liabilities; stress must appear in explicit shortfall/default channels.",
    ),
    TargetBand(
      id = "DebtArrearsToShortfall",
      label = "Rent, mortgage, and consumer-debt arrears / liquidity shortfall",
      unit = "share",
      guardrailClass = GuardrailClass.ExploratoryDiagnostic,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = Some(BigDecimal("1.00")),
      sourceNote = "Internal attribution check over shortfall components.",
      interpretation = "Shows whether shortfall pressure comes mainly from debt/rent service or from consumption/liquidity residuals.",
    ),
    TargetBand(
      id = "UnmetBasicConsumptionToIncome",
      label = "Unmet basic consumption / monthly household income",
      unit = "ratio",
      guardrailClass = GuardrailClass.ExploratoryDiagnostic,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = None,
      sourceNote = "Issue #528 budget-waterfall diagnostic over non-discretionary consumption needs.",
      interpretation = "Shows deprivation created before bridge/default settlement; it is not treated as financeable household debt.",
    ),
    TargetBand(
      id = "DiscretionaryConsumptionCompressionToIncome",
      label = "Compressed discretionary consumption / monthly household income",
      unit = "ratio",
      guardrailClass = GuardrailClass.ExploratoryDiagnostic,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = None,
      sourceNote = "Issue #528 budget-waterfall diagnostic over consumption cuts before shortfall financing.",
      interpretation = "Shows how much spending pressure is absorbed by reducing discretionary consumption before creating bridge charge-offs.",
    ),
    TargetBand(
      id = "ShortfallToIncome",
      label = "Liquidity shortfall financing / monthly household income",
      unit = "ratio",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = Some(BigDecimal("0.05")),
      sourceNote = "Stylized 2026-04-30 red-flag threshold for monthly emergency bridge/write-off flow.",
      interpretation = "Shortfall financing should be a stress channel, not a large routine substitute for income.",
    ),
    TargetBand(
      id = "ShortfallToApprovedOrigination",
      label = "Liquidity shortfall financing / approved consumer-credit origination",
      unit = "ratio",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = Some(BigDecimal("2.00")),
      sourceNote = "Stylized 2026-04-30 guardrail added after the diagnostic run where shortfalls dwarfed approved credit.",
      interpretation = "The non-underwritten liquidity bridge should not structurally dominate normal approved consumer credit.",
    ),
    TargetBand(
      id = "RejectedConsumerCreditDemandToApprovedOrigination",
      label = "Rejected consumer-credit demand / approved consumer-credit origination",
      unit = "ratio",
      guardrailClass = GuardrailClass.ExploratoryDiagnostic,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = None,
      sourceNote = "Issue #534 diagnostic over stressed households with positive DTI-based consumer-credit demand.",
      interpretation =
        "Shows whether normal credit origination is suppressed by borrower-side denial or bank-side supply rejection rather than by lack of borrower demand.",
    ),
    TargetBand(
      id = "RejectedConsumerCreditDemandToShortfall",
      label = "Rejected consumer-credit demand / liquidity shortfall financing",
      unit = "ratio",
      guardrailClass = GuardrailClass.ExploratoryDiagnostic,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = None,
      sourceNote = "Issue #534 diagnostic comparing denied normal-credit demand with residual emergency bridge/write-off flow.",
      interpretation = "Shows whether emergency shortfall is plausibly linked to denied underwritten consumer-credit demand.",
    ),
  )

  private val Metrics: Vector[MetricDef] = Vector(
    metric("ConsumerLoansToGdp")(ctx => ObservedValue.Finite(ctx.col("ConsumerLoansToGdp"))),
    metric("MortgageLoansToGdp")(ctx => ObservedValue.Finite(ctx.col("MortgageToGdp"))),
    metric("ConsumerDebtServiceToIncome")(ctx =>
      ctx.householdRatio(ctx.monthlyState.householdAggregates.totalConsumerDebtService, ctx.monthlyState.householdAggregates.totalIncome),
    ),
    metric("MortgageDebtServiceToIncome")(ctx =>
      ctx.householdRatio(ctx.monthlyState.householdAggregates.totalDebtService, ctx.monthlyState.householdAggregates.totalIncome),
    ),
    metric("MortgagePrincipalToIncome")(ctx =>
      ctx.householdRatio(ctx.monthlyState.householdAggregates.totalMortgagePrincipal, ctx.monthlyState.householdAggregates.totalIncome),
    ),
    metric("MortgageInterestToIncome")(ctx =>
      ctx.householdRatio(ctx.monthlyState.householdAggregates.totalMortgageInterest, ctx.monthlyState.householdAggregates.totalIncome),
    ),
    metric("ConsumerDefaultToConsumerLoans")(ctx => ctx.ratioColumn("ConsumerLoanDefault", "ConsumerLoans")),
    metric("LiquidityBridgeChargeOffToConsumerLoans")(ctx => ctx.ratioColumn("LiquidityBridgeChargeOff", "ConsumerLoans")),
    metric("LiquidityBridgeChargeOffShareOfHouseholdCreditWriteOff")(ctx =>
      val bridge = ctx.col("LiquidityBridgeChargeOff")
      ratioRaw(bridge, bridge + ctx.col("ConsumerLoanDefault")),
    ),
    metric("MortgageDefaultToMortgageLoans")(ctx => ctx.ratioColumn("MortgageDefault", "MortgageStock")),
    metric("HouseholdBankruptcyShare")(ctx => ObservedValue.Finite(ctx.col("HouseholdDistress_BankruptcyShare"))),
    metric("PositiveDepositsToMonthlyIncome")(ctx =>
      ctx.householdRatio(
        ctx.monthlyState.ledgerFinancialState.households.map(h => h.demandDeposit).sumPln,
        ctx.monthlyState.householdAggregates.totalIncome,
      ),
    ),
    metric("MedianDepositToMeanMonthlyIncome")(ctx =>
      val agg = ctx.monthlyState.householdAggregates
      if ctx.householdCount <= 0 then ObservedValue.NotAvailable
      else ctx.householdRatio(agg.medianSavings, BigDecimal(agg.totalIncome.toLong) / BigDecimal(ctx.householdCount)),
    ),
    metric("NegativeDepositShare")(ctx => ObservedValue.Finite(ctx.col("HouseholdLiquidity_NegativeDepositShare"))),
    metric("DebtArrearsToShortfall")(ctx =>
      val agg = ctx.monthlyState.householdAggregates
      ctx.householdRatio(
        agg.totalRentArrears + agg.totalMortgageArrears + agg.totalConsumerDebtArrears,
        agg.totalLiquidityShortfallFinancing,
      ),
    ),
    metric("UnmetBasicConsumptionToIncome")(ctx =>
      val agg = ctx.monthlyState.householdAggregates
      ctx.householdRatio(agg.totalUnmetBasicConsumption, agg.totalIncome),
    ),
    metric("DiscretionaryConsumptionCompressionToIncome")(ctx =>
      val agg = ctx.monthlyState.householdAggregates
      ctx.householdRatio(agg.totalDiscretionaryConsumptionCompression, agg.totalIncome),
    ),
    metric("ShortfallToIncome")(ctx =>
      val agg = ctx.monthlyState.householdAggregates
      ctx.householdRatio(agg.totalLiquidityShortfallFinancing, agg.totalIncome),
    ),
    metric("ShortfallToApprovedOrigination")(ctx =>
      val agg = ctx.monthlyState.householdAggregates
      ctx.householdRatio(agg.totalLiquidityShortfallFinancing, agg.totalConsumerApprovedOrigination),
    ),
    metric("RejectedConsumerCreditDemandToApprovedOrigination")(ctx =>
      val agg = ctx.monthlyState.householdAggregates
      ctx.householdRatio(agg.totalConsumerRejectedOrigination, agg.totalConsumerApprovedOrigination),
    ),
    metric("RejectedConsumerCreditDemandToShortfall")(ctx =>
      val agg = ctx.monthlyState.householdAggregates
      ctx.householdRatio(agg.totalConsumerRejectedOrigination, agg.totalLiquidityShortfallFinancing),
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
      validConfig <- ZIO.fromEither(validate(config))
      seedMetrics <- McTsvFile
        .writeFold(
          validConfig.runRoot.resolve("household-credit-stress-seed-metrics.tsv"),
          McDiagnosticRunner
            .runSeeds(validConfig.seedRange, validConfig.months, SimParams.defaults)((seed, months) => computeSeedMetricsZIO(validConfig, seed, months))
            .flatMap(rows => ZStream.fromIterable(rows)),
          SeedMetricsTsvSchema,
          Vector.newBuilder[SeedMetric],
        )((builder, row) => builder += row)(DiagnosticIo.outputFailure)
        .map(_.result())
      summary      = summarize(validConfig, seedMetrics)
      paths       <- writeArtifactsZIO(validConfig, summary)
    yield ExportResult(paths, seedMetrics, summary)

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
    Either
      .cond(config.seeds > 0, config, "--seeds must be a positive integer")
      .flatMap(valid => Either.cond(valid.months > 0, valid, "--months must be a positive integer"))
      .flatMap(valid => Either.cond(valid.runId.trim.nonEmpty, valid, "--run-id must be non-empty"))
      .flatMap(valid => Either.cond(Metrics.map(_.target.id).toSet == RequiredMetricIds, valid, "Household credit stress metric coverage is incomplete"))

  private[diagnostics] def evaluate(value: ObservedValue, target: TargetBand): Status =
    value match
      case ObservedValue.Finite(v)        =>
        val outside = target.lower.exists(v < _) || target.upper.exists(v > _)
        target.guardrailClass match
          case GuardrailClass.HardInvariant if outside          => Status.Fail
          case GuardrailClass.HardInvariant                     => Status.Pass
          case GuardrailClass.SoftCalibrationWarning if outside => Status.Warn
          case GuardrailClass.SoftCalibrationWarning            => Status.Pass
          case GuardrailClass.ExploratoryDiagnostic if outside  => Status.Warn
          case GuardrailClass.ExploratoryDiagnostic             => Status.Info
      case ObservedValue.PositiveInfinity =>
        target.guardrailClass match
          case GuardrailClass.HardInvariant          => Status.Fail
          case GuardrailClass.SoftCalibrationWarning => Status.Warn
          case GuardrailClass.ExploratoryDiagnostic  => Status.Warn
      case ObservedValue.NotAvailable     =>
        target.guardrailClass match
          case GuardrailClass.HardInvariant          => Status.Fail
          case GuardrailClass.SoftCalibrationWarning => Status.Warn
          case GuardrailClass.ExploratoryDiagnostic  => Status.Warn

  private def computeSeedMetricsZIO(config: Config, seed: Long, months: ZStream[Any, String, McSeedMonth]): ZIO[Any, String, Vector[SeedMetric]] =
    val windowSize = 3
    months
      .runFold(Vector.fill(Metrics.length)(MetricAccumulator.Empty)): (accumulators, month) =>
        val context = SeedContext(month)
        accumulators
          .zip(Metrics)
          .map: (accumulator, metric) =>
            accumulator.observe(MetricObservation(context.monthNumber, metric.compute(context)), windowSize)
      .flatMap: accumulators =>
        ZIO.fromEither(computeSeedMetrics(config, seed, accumulators, windowSize))

  private def computeSeedMetrics(config: Config, seed: Long, accumulators: Vector[MetricAccumulator], windowSize: Int): Either[String, Vector[SeedMetric]] =
    accumulators
      .zip(Metrics)
      .foldLeft[Either[String, Vector[SeedMetric]]](Right(Vector.empty)): (acc, item) =>
        val (accumulator, metric) = item
        for
          rows       <- acc
          metricRows <- accumulator.toSeedMetrics(config, seed, metric.target, windowSize)
        yield rows ++ metricRows

  private def seedMetric(
      config: Config,
      seed: Long,
      target: TargetBand,
      window: ObservationWindow,
      observation: MetricObservation,
  ): SeedMetric =
    SeedMetric(
      runId = config.runId,
      seed = seed,
      months = config.months,
      observationWindow = window,
      observationMonth = observation.month,
      target = target,
      value = observation.value,
      status = evaluate(observation.value, target),
    )

  private[diagnostics] def summarize(config: Config, rows: Vector[SeedMetric]): Vector[SummaryMetric] =
    Targets.flatMap: target =>
      ObservationWindow.values.toVector.map: window =>
        val metricRows = rows.filter(row => row.target.id == target.id && row.observationWindow == window)
        val finite     = metricRows.flatMap(_.value.finite)
        val mean       =
          if metricRows.exists(_.value == ObservedValue.PositiveInfinity) then ObservedValue.PositiveInfinity
          else if finite.nonEmpty then ObservedValue.Finite(finite.sum / BigDecimal(finite.size))
          else ObservedValue.NotAvailable
        SummaryMetric(
          runId = config.runId,
          months = config.months,
          seeds = config.seeds,
          observationWindow = window,
          target = target,
          mean = mean,
          min = if finite.nonEmpty then Some(finite.min) else None,
          max = if finite.nonEmpty then Some(finite.max) else None,
          status = worstStatus(evaluate(mean, target) +: metricRows.map(_.status)),
        )

  private def writeArtifactsZIO(config: Config, summary: Vector[SummaryMetric]): ZIO[Any, String, Vector[Path]] =
    val seedMetricsPath = config.runRoot.resolve("household-credit-stress-seed-metrics.tsv")
    val summaryPath     = config.runRoot.resolve("household-credit-stress-summary.tsv")
    val targetPath      = config.runRoot.resolve("household-credit-stress-targets.tsv")
    val reportPath      = config.runRoot.resolve("household-credit-stress-report.md")
    for
      _ <- McTsvFile.writeAll(summaryPath, summary, SummaryTsvSchema)(DiagnosticIo.outputFailure)
      _ <- McTsvFile.writeAll(targetPath, Targets, TargetsTsvSchema)(DiagnosticIo.outputFailure)
      _ <- DiagnosticIo.writeText(reportPath, renderReport(config, summary))
    yield Vector(seedMetricsPath, summaryPath, targetPath, reportPath)

  private[diagnostics] def renderSeedMetricsTsv(rows: Vector[SeedMetric]): String =
    renderTsv(SeedMetricsTsvSchema, rows)

  private[diagnostics] def renderSummaryTsv(rows: Vector[SummaryMetric]): String =
    renderTsv(SummaryTsvSchema, rows)

  private[diagnostics] def renderTargetsTsv(targets: Vector[TargetBand]): String =
    renderTsv(TargetsTsvSchema, targets)

  private val SeedMetricsTsvSchema: McTsvSchema[SeedMetric] =
    McTsvSchema(
      header = McTsvSchema.header(
        "RunId",
        "Seed",
        "Months",
        "ObservationWindow",
        "ObservationMonth",
        "Metric",
        "Label",
        "Value",
        "Unit",
        "GuardrailClass",
        "Vintage",
        "Lower",
        "Upper",
        "Status",
        "SourceNote",
        "Interpretation",
      ),
      render = row =>
        Vector(
          row.runId,
          row.seed.toString,
          row.months.toString,
          row.observationWindow.token,
          row.observationMonth.toString,
          row.target.id,
          row.target.label,
          renderValue(row.value),
          row.target.unit,
          row.target.guardrailClass.token,
          row.target.vintage,
          row.target.lower.map(renderDecimal).getOrElse(""),
          row.target.upper.map(renderDecimal).getOrElse(""),
          row.status.token,
          row.target.sourceNote,
          row.target.interpretation,
        ).map(tsv).mkString("\t"),
    )

  private val SummaryTsvSchema: McTsvSchema[SummaryMetric] =
    McTsvSchema(
      header = McTsvSchema.header(
        "RunId",
        "Months",
        "Seeds",
        "ObservationWindow",
        "Metric",
        "Label",
        "Mean",
        "Min",
        "Max",
        "Unit",
        "GuardrailClass",
        "Vintage",
        "Lower",
        "Upper",
        "Status",
        "SourceNote",
        "Interpretation",
      ),
      render = row =>
        Vector(
          row.runId,
          row.months.toString,
          row.seeds.toString,
          row.observationWindow.token,
          row.target.id,
          row.target.label,
          renderValue(row.mean),
          row.min.map(renderDecimal).getOrElse(""),
          row.max.map(renderDecimal).getOrElse(""),
          row.target.unit,
          row.target.guardrailClass.token,
          row.target.vintage,
          row.target.lower.map(renderDecimal).getOrElse(""),
          row.target.upper.map(renderDecimal).getOrElse(""),
          row.status.token,
          row.target.sourceNote,
          row.target.interpretation,
        ).map(tsv).mkString("\t"),
    )

  private val TargetsTsvSchema: McTsvSchema[TargetBand] =
    McTsvSchema(
      header = McTsvSchema.header("Metric", "Label", "Unit", "GuardrailClass", "Vintage", "Lower", "Upper", "SourceNote", "Interpretation"),
      render = target =>
        Vector(
          target.id,
          target.label,
          target.unit,
          target.guardrailClass.token,
          target.vintage,
          target.lower.map(renderDecimal).getOrElse(""),
          target.upper.map(renderDecimal).getOrElse(""),
          target.sourceNote,
          target.interpretation,
        ).map(tsv).mkString("\t"),
    )

  private def renderTsv[A](schema: McTsvSchema[A], rows: Vector[A]): String =
    (schema.header +: rows.map(schema.render)).mkString("\n") + "\n"

  private[diagnostics] def renderReport(config: Config, summary: Vector[SummaryMetric]): String =
    val command     =
      s"""sbt "householdCreditStressCalibration --seed-start ${config.seedStart} --seeds ${config.seeds} --months ${config.months} --out ${config.out} --run-id ${config.runId}""""
    val targetRows  = Targets.map: target =>
      markdownRow(
        Vector(
          target.id,
          target.guardrailClass.token,
          target.vintage,
          bound(target.lower),
          bound(target.upper),
          target.unit,
          target.interpretation,
        ),
      )
    val summaryRows = summary.map: row =>
      markdownRow(
        Vector(
          row.target.id,
          row.observationWindow.token,
          renderValue(row.mean),
          row.min.map(renderDecimal).getOrElse("n/a"),
          row.max.map(renderDecimal).getOrElse("n/a"),
          row.status.token,
        ),
      )
    val lines       =
      Vector(
        "# Household Credit Stress Calibration",
        "",
        "Generated by `HouseholdCreditStressCalibrationExport`.",
        "",
        s"- Run id: `${config.runId}`",
        s"- Seeds: `${config.seeds}` from `${config.seedStart}`",
        s"- Months: `${config.months}`",
        s"- Repeat command: `$command`",
        "",
        "## Guardrail Classes",
        "",
        "- `HARD_INVARIANT`: accounting or reporting boundary that should never fail.",
        "- `SOFT_CALIBRATION_WARNING`: Poland-relevant calibration band; violations are warnings, not runtime failures.",
        "- `EXPLORATORY_DIAGNOSTIC`: useful stress decomposition without a settled empirical acceptance band.",
        "",
        "## Target Bands",
        "",
        markdownRow(Vector("Metric", "Class", "Vintage", "Lower", "Upper", "Unit", "Interpretation")),
        markdownRow(Vector("---", "---", "---", "---", "---", "---", "---")),
      ) ++ targetRows ++ Vector(
        "",
        "## Summary",
        "",
        markdownRow(Vector("Metric", "Window", "Mean", "Min", "Max", "Status")),
        markdownRow(Vector("---", "---", "---", "---", "---", "---")),
      ) ++ summaryRows
    lines.mkString("\n") + "\n"

  private def metric(id: String)(compute: SeedContext => ObservedValue): MetricDef =
    val target = Targets.find(_.id == id).getOrElse(throw new IllegalArgumentException(s"Missing target band: $id"))
    MetricDef(target, compute)

  private def bestObservation(current: Option[MetricObservation], candidate: MetricObservation): MetricObservation =
    current match
      case Some(existing) if summon[Ordering[(Int, BigDecimal, Int)]].gteq(observationKey(existing), observationKey(candidate)) => existing
      case _                                                                                                                    => candidate

  private def observationKey(observation: MetricObservation): (Int, BigDecimal, Int) =
    val (rank, value) = valueRank(observation.value)
    (rank, value, -observation.month)

  private def rollingValue(values: Vector[ObservedValue]): ObservedValue =
    if values.exists(_ == ObservedValue.PositiveInfinity) then ObservedValue.PositiveInfinity
    else
      val finite = values.flatMap(_.finite)
      if finite.length == values.length then ObservedValue.Finite(finite.sum / BigDecimal(finite.length))
      else ObservedValue.NotAvailable

  private def valueRank(value: ObservedValue): (Int, BigDecimal) =
    value match
      case ObservedValue.PositiveInfinity => 2 -> BigDecimal(0)
      case ObservedValue.Finite(v)        => 1 -> v
      case ObservedValue.NotAvailable     => 0 -> BigDecimal(0)

  private def worstStatus(statuses: Vector[Status]): Status =
    statuses.maxBy:
      case Status.Fail => 3
      case Status.Warn => 2
      case Status.Pass => 1
      case Status.Info => 0

  private def ratio(numerator: BigDecimal, denominator: BigDecimal): ObservedValue =
    ratioRaw(numerator, denominator)

  private def ratioRaw(numerator: BigDecimal, denominator: BigDecimal): ObservedValue =
    if denominator == BigDecimal(0) then
      if numerator == BigDecimal(0) then ObservedValue.Finite(BigDecimal(0))
      else ObservedValue.PositiveInfinity
    else ObservedValue.Finite(numerator / denominator)

  private def decimal(raw: Long): BigDecimal =
    BigDecimal(raw) / BigDecimal(com.boombustgroup.amorfati.fp.FixedPointBase.Scale)

  private def renderValue(value: ObservedValue): String =
    value match
      case ObservedValue.Finite(v)        => renderDecimal(v)
      case ObservedValue.PositiveInfinity => "INF"
      case ObservedValue.NotAvailable     => "NA"

  private def renderDecimal(value: BigDecimal): String =
    value.setScale(6, RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros.toPlainString

  private def bound(value: Option[BigDecimal]): String =
    value.map(renderDecimal).getOrElse("n/a")

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

  private def usage: String =
    """Usage: HouseholdCreditStressCalibrationExport [options]
      |
      |Options:
      |  --seed-start <long>  First Monte Carlo seed, default 1
      |  --seeds <int>        Number of seeds, default 5
      |  --months <int>       Number of simulated months, default 60
      |  --run-id <string>    Output run id, default household-credit-stress
      |  --out <path>         Output root, default target/household-credit-stress
      |""".stripMargin

end HouseholdCreditStressCalibrationExport
