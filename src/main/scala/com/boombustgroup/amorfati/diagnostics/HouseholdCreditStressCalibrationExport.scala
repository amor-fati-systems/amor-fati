package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.montecarlo.{McRunner, McTimeseriesSchema, MetricValue, RunResult}
import com.boombustgroup.amorfati.montecarlo.MetricValue.*
import com.boombustgroup.amorfati.montecarlo.TimeSeries.*
import com.boombustgroup.amorfati.types.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
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
      target: TargetBand,
      value: ObservedValue,
      status: Status,
  )

  final case class SummaryMetric(
      runId: String,
      months: Int,
      seeds: Int,
      target: TargetBand,
      mean: ObservedValue,
      min: Option[BigDecimal],
      max: Option[BigDecimal],
      status: Status,
  )

  final case class ExportResult(paths: Vector[Path], seedMetrics: Vector[SeedMetric], summary: Vector[SummaryMetric])

  private final case class SeedContext(result: RunResult):
    val terminalRow: Array[MetricValue] = result.timeSeries.lastMonth
    val householdCount: Int             = result.terminalState.households.size

    def col(name: String): BigDecimal =
      decimal(McTimeseriesSchema.colNames.indexOf(name) match
        case -1      => throw new IllegalArgumentException(s"Missing Monte Carlo column: $name")
        case ordinal => terminalRow(ordinal).toLong)

    def ratioColumn(numerator: String, denominator: String): ObservedValue =
      ratio(col(numerator), col(denominator))

    def householdRatio(numerator: PLN, denominator: PLN): ObservedValue =
      ratioRaw(BigDecimal(numerator.toLong), BigDecimal(denominator.toLong))

    def householdRatio(numerator: PLN, denominatorRaw: BigDecimal): ObservedValue =
      ratioRaw(BigDecimal(numerator.toLong), denominatorRaw)

  private final case class MetricDef(target: TargetBand, compute: SeedContext => ObservedValue)

  private val RequiredMetricIds: Set[String] = Set(
    "ConsumerLoansToGdp",
    "MortgageLoansToGdp",
    "ConsumerDebtServiceToIncome",
    "MortgageDebtServiceToIncome",
    "MortgagePrincipalToIncome",
    "MortgageInterestToIncome",
    "ConsumerDefaultToConsumerLoans",
    "LiquidityBridgeChargeOffToConsumerLoans",
    "LiquidityBridgeChargeOffShareOfConsumerDefault",
    "MortgageDefaultToMortgageLoans",
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
      id = "LiquidityBridgeChargeOffShareOfConsumerDefault",
      label = "Liquidity-bridge charge-offs / combined consumer default flow",
      unit = "share",
      guardrailClass = GuardrailClass.ExploratoryDiagnostic,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = Some(BigDecimal("1.00")),
      sourceNote = "Issue #536 attribution check over the combined ConsumerDefault SFC flow.",
      interpretation = "Shows the share of combined consumer-default flow that is actually same-month liquidity bridge write-off.",
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
      interpretation = "Shows whether normal credit origination is suppressed by access/underwriting denial rather than by lack of borrower demand.",
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
      ctx.householdRatio(ctx.result.terminalState.householdAggregates.totalConsumerDebtService, ctx.result.terminalState.householdAggregates.totalIncome),
    ),
    metric("MortgageDebtServiceToIncome")(ctx =>
      ctx.householdRatio(ctx.result.terminalState.householdAggregates.totalDebtService, ctx.result.terminalState.householdAggregates.totalIncome),
    ),
    metric("MortgagePrincipalToIncome")(ctx =>
      ctx.householdRatio(ctx.result.terminalState.householdAggregates.totalMortgagePrincipal, ctx.result.terminalState.householdAggregates.totalIncome),
    ),
    metric("MortgageInterestToIncome")(ctx =>
      ctx.householdRatio(ctx.result.terminalState.householdAggregates.totalMortgageInterest, ctx.result.terminalState.householdAggregates.totalIncome),
    ),
    metric("ConsumerDefaultToConsumerLoans")(ctx => ctx.ratioColumn("ConsumerLoanDefault", "ConsumerLoans")),
    metric("LiquidityBridgeChargeOffToConsumerLoans")(ctx => ctx.ratioColumn("LiquidityBridgeChargeOff", "ConsumerLoans")),
    metric("LiquidityBridgeChargeOffShareOfConsumerDefault")(ctx => ctx.ratioColumn("LiquidityBridgeChargeOff", "ConsumerDefault")),
    metric("MortgageDefaultToMortgageLoans")(ctx => ctx.ratioColumn("MortgageDefault", "MortgageStock")),
    metric("PositiveDepositsToMonthlyIncome")(ctx =>
      ctx.householdRatio(
        ctx.result.terminalState.ledgerFinancialState.households.map(h => h.demandDeposit).sumPln,
        ctx.result.terminalState.householdAggregates.totalIncome,
      ),
    ),
    metric("MedianDepositToMeanMonthlyIncome")(ctx =>
      val agg = ctx.result.terminalState.householdAggregates
      if ctx.householdCount <= 0 then ObservedValue.NotAvailable
      else ctx.householdRatio(agg.medianSavings, BigDecimal(agg.totalIncome.toLong) / BigDecimal(ctx.householdCount)),
    ),
    metric("NegativeDepositShare")(ctx => ObservedValue.Finite(ctx.col("HouseholdLiquidity_NegativeDepositShare"))),
    metric("DebtArrearsToShortfall")(ctx =>
      val agg = ctx.result.terminalState.householdAggregates
      ctx.householdRatio(
        agg.totalRentArrears + agg.totalMortgageArrears + agg.totalConsumerDebtArrears,
        agg.totalLiquidityShortfallFinancing,
      ),
    ),
    metric("UnmetBasicConsumptionToIncome")(ctx =>
      val agg = ctx.result.terminalState.householdAggregates
      ctx.householdRatio(agg.totalUnmetBasicConsumption, agg.totalIncome),
    ),
    metric("DiscretionaryConsumptionCompressionToIncome")(ctx =>
      val agg = ctx.result.terminalState.householdAggregates
      ctx.householdRatio(agg.totalDiscretionaryConsumptionCompression, agg.totalIncome),
    ),
    metric("ShortfallToIncome")(ctx =>
      val agg = ctx.result.terminalState.householdAggregates
      ctx.householdRatio(agg.totalLiquidityShortfallFinancing, agg.totalIncome),
    ),
    metric("ShortfallToApprovedOrigination")(ctx =>
      val agg = ctx.result.terminalState.householdAggregates
      ctx.householdRatio(agg.totalLiquidityShortfallFinancing, agg.totalConsumerApprovedOrigination),
    ),
    metric("RejectedConsumerCreditDemandToApprovedOrigination")(ctx =>
      val agg = ctx.result.terminalState.householdAggregates
      ctx.householdRatio(agg.totalConsumerRejectedOrigination, agg.totalConsumerApprovedOrigination),
    ),
    metric("RejectedConsumerCreditDemandToShortfall")(ctx =>
      val agg = ctx.result.terminalState.householdAggregates
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
    validate(config).flatMap: validConfig =>
      given SimParams = SimParams.defaults

      val attempts = validConfig.seedRange.map: seed =>
        Try(McRunner.runSingle(seed, validConfig.months)).toEither.left
          .map(ex => s"Seed $seed crashed: ${ex.getMessage}")
          .flatMap:
            _.left
              .map(err => s"Seed $seed failed: $err")
              .map(result => computeSeedMetrics(validConfig, seed, result))

      attempts.collectFirst { case Left(err) => err } match
        case Some(err) => Left(err)
        case None      =>
          val seedMetrics = attempts.collect { case Right(rows) => rows }.flatten
          val summary     = summarize(validConfig, seedMetrics)
          val paths       = writeArtifacts(validConfig, seedMetrics, summary)
          Right(ExportResult(paths, seedMetrics, summary))

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

  private def computeSeedMetrics(config: Config, seed: Long, result: RunResult): Vector[SeedMetric] =
    val context = SeedContext(result)
    Metrics.map: metric =>
      val value = metric.compute(context)
      SeedMetric(config.runId, seed, config.months, metric.target, value, evaluate(value, metric.target))

  private[diagnostics] def summarize(config: Config, rows: Vector[SeedMetric]): Vector[SummaryMetric] =
    Targets.map: target =>
      val metricRows = rows.filter(_.target.id == target.id)
      val finite     = metricRows.flatMap(_.value.finite)
      val mean       =
        if metricRows.exists(_.value == ObservedValue.PositiveInfinity) then ObservedValue.PositiveInfinity
        else if finite.nonEmpty then ObservedValue.Finite(finite.sum / BigDecimal(finite.size))
        else ObservedValue.NotAvailable
      SummaryMetric(
        runId = config.runId,
        months = config.months,
        seeds = config.seeds,
        target = target,
        mean = mean,
        min = if finite.nonEmpty then Some(finite.min) else None,
        max = if finite.nonEmpty then Some(finite.max) else None,
        status = evaluate(mean, target),
      )

  private def writeArtifacts(config: Config, seedMetrics: Vector[SeedMetric], summary: Vector[SummaryMetric]): Vector[Path] =
    Files.createDirectories(config.runRoot)
    val seedMetricsPath = config.runRoot.resolve("household-credit-stress-seed-metrics.csv")
    val summaryPath     = config.runRoot.resolve("household-credit-stress-summary.csv")
    val targetPath      = config.runRoot.resolve("household-credit-stress-targets.csv")
    val reportPath      = config.runRoot.resolve("household-credit-stress-report.md")
    Files.writeString(seedMetricsPath, renderSeedMetricsCsv(seedMetrics), StandardCharsets.UTF_8)
    Files.writeString(summaryPath, renderSummaryCsv(summary), StandardCharsets.UTF_8)
    Files.writeString(targetPath, renderTargetsCsv(Targets), StandardCharsets.UTF_8)
    Files.writeString(reportPath, renderReport(config, summary), StandardCharsets.UTF_8)
    Vector(seedMetricsPath, summaryPath, targetPath, reportPath)

  private[diagnostics] def renderSeedMetricsCsv(rows: Vector[SeedMetric]): String =
    val header = "RunId;Seed;Months;Metric;Label;Value;Unit;GuardrailClass;Vintage;Lower;Upper;Status;SourceNote;Interpretation"
    val body   = rows.map: row =>
      Vector(
        row.runId,
        row.seed.toString,
        row.months.toString,
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
      ).map(csv).mkString(";")
    (header +: body).mkString("\n") + "\n"

  private[diagnostics] def renderSummaryCsv(rows: Vector[SummaryMetric]): String =
    val header = "RunId;Months;Seeds;Metric;Label;Mean;Min;Max;Unit;GuardrailClass;Vintage;Lower;Upper;Status;SourceNote;Interpretation"
    val body   = rows.map: row =>
      Vector(
        row.runId,
        row.months.toString,
        row.seeds.toString,
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
      ).map(csv).mkString(";")
    (header +: body).mkString("\n") + "\n"

  private[diagnostics] def renderTargetsCsv(targets: Vector[TargetBand]): String =
    val header = "Metric;Label;Unit;GuardrailClass;Vintage;Lower;Upper;SourceNote;Interpretation"
    val body   = targets.map: target =>
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
      ).map(csv).mkString(";")
    (header +: body).mkString("\n") + "\n"

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
        markdownRow(Vector("Metric", "Mean", "Min", "Max", "Status")),
        markdownRow(Vector("---", "---", "---", "---", "---")),
      ) ++ summaryRows
    lines.mkString("\n") + "\n"

  private def metric(id: String)(compute: SeedContext => ObservedValue): MetricDef =
    val target = Targets.find(_.id == id).getOrElse(throw new IllegalArgumentException(s"Missing target band: $id"))
    MetricDef(target, compute)

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

  private def csv(value: String): String =
    val escaped = value.replace("\"", "\"\"")
    if escaped.exists(ch => ch == ';' || ch == '"' || ch == '\n' || ch == '\r') then s""""$escaped""""
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
