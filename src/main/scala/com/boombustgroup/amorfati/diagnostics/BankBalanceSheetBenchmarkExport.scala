package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.agents.{Banking, EclStaging}
import com.boombustgroup.amorfati.agents.banking.PolishBankLevyAssetPerimeter
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.engine.mechanisms.Macroprudential
import com.boombustgroup.amorfati.init.{InitRandomness, WorldInit}
import com.boombustgroup.amorfati.montecarlo.{DelimitedTextFormat, McTsvFile, McTsvSchema}
import com.boombustgroup.amorfati.types.*
import zio.ZIO

import java.nio.file.Path
import scala.math.BigDecimal.RoundingMode
import scala.util.Try

object BankBalanceSheetBenchmarkExport:

  private val BaselineVintage                         = "2026-04-30 model-start baseline"
  private[diagnostics] val BankCapitalSourceStatement =
    "Capital source: `BankState.capital`. It is a persisted regulatory/accounting bank-capital buffer seeded by bank calibration and updated by the bank P&L/loss waterfall. `LedgerFinancialState.BankBalances` intentionally has no capital field; this diagnostic must not infer holder-resolved ledger-owned bank equity."

  final case class Config(
      seedStart: Long = 1L,
      seeds: Int = 10,
      runId: String = "bank-balance-sheet-benchmark",
      out: Path = Path.of("target/bank-balance-sheet-benchmark"),
  ):
    def seedRange: Vector[Long] = Vector.tabulate(seeds)(i => seedStart + i.toLong)
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
      target: TargetBand,
      value: ObservedValue,
      status: Status,
  )

  final case class SummaryMetric(
      runId: String,
      seeds: Int,
      target: TargetBand,
      mean: ObservedValue,
      min: Option[BigDecimal],
      max: Option[BigDecimal],
      status: Status,
  )

  final case class BankRow(
      runId: String,
      seed: Long,
      bankId: Int,
      bankName: String,
      capital: PLN,
      assets: PLN,
      deposits: PLN,
      totalCredit: PLN,
      govBondHoldings: PLN,
      govBondShareOfAssets: Share,
      polishBankLevyTaxableAssets: PLN,
      polishBankLevyTaxableAssetsShare: Share,
      capitalAdequacyRatio: BigDecimal,
      effectiveMinCar: BigDecimal,
      carBuffer: BigDecimal,
      lcr: BigDecimal,
      nsfr: BigDecimal,
      creditShare: BigDecimal,
      depositShare: BigDecimal,
      assetShare: BigDecimal,
  )

  final case class ExportResult(
      paths: Vector[Path],
      seedMetrics: Vector[SeedMetric],
      summary: Vector[SummaryMetric],
      bankRows: Vector[BankRow],
  )

  private final case class MetricDef(target: TargetBand, compute: SeedContext => ObservedValue)

  private final case class SeedContext(seed: Long, init: WorldInit.InitResult)(using p: SimParams):
    val params: SimParams                                       = p
    val bankBalances: Vector[LedgerFinancialState.BankBalances] = init.ledgerFinancialState.banks
    val bankStocks: Vector[Banking.BankFinancialStocks]         = bankBalances.map(LedgerFinancialState.projectBankFinancialStocks)
    val corpBondHoldings: Banking.BankCorpBondHoldings          =
      Banking.bankCorpBondHoldingsFromVector(bankBalances.map(_.corpBond))
    val aggregate: Banking.Aggregate                            =
      Banking.aggregateFromBankStocks(init.banks, bankStocks, corpBondHoldings)

    val annualGdp: PLN        = init.world.flows.monthlyGdpProxy * 12
    val firmLoans: PLN        = aggregate.totalLoans
    val consumerLoans: PLN    = aggregate.consumerLoans
    val mortgageLoans: PLN    = LedgerFinancialState.bankMortgageStock(init.ledgerFinancialState)
    val totalCredit: PLN      = firmLoans + consumerLoans + mortgageLoans
    val deposits: PLN         = aggregate.deposits
    val capital: PLN          = aggregate.capital
    val govBonds: PLN         = aggregate.govBondHoldings
    val reserves: PLN         = bankStocks.map(_.reserve).sumPln
    val demandDeposits: PLN   = bankStocks.map(_.demandDeposit).sumPln
    val termDeposits: PLN     = bankStocks.map(_.termDeposit).sumPln
    val liquidAssets: PLN     = govBonds + reserves
    val eclAllowance: PLN     = init.banks.map(bank => EclStaging.allowance(bank.eclStaging)).sumPln
    val eclCoveredLoans: PLN  = firmLoans + consumerLoans
    val eclStagedLoans: PLN   = init.banks.map(bank => bank.eclStaging.stage1 + bank.eclStaging.stage2 + bank.eclStaging.stage3).sumPln
    val nplStock: PLN         = aggregate.nplAmount + aggregate.consumerNpl
    val assets: PLN           = bankAssets(bankBalances)
    val rows: Vector[BankRow] = bankRows()

    def ratio(numerator: PLN, denominator: PLN): ObservedValue =
      ratioRaw(BigDecimal(numerator.toLong), BigDecimal(denominator.toLong))

    def finiteRatio(numerator: PLN, denominator: PLN): BigDecimal =
      ratio(numerator, denominator) match
        case ObservedValue.Finite(value)    => value
        case ObservedValue.PositiveInfinity => BigDecimal(0)
        case ObservedValue.NotAvailable     => BigDecimal(0)

    def finiteShare(numerator: PLN, denominator: PLN): Share =
      if denominator <= PLN.Zero then Share.Zero
      else (numerator / denominator).clampToShare

    private def bankRows(): Vector[BankRow] =
      if init.banks.size != bankBalances.size then
        val bankIds = init.banks.map(_.id.toInt).mkString("[", ",", "]")
        throw new IllegalStateException(
          s"Bank balance-sheet benchmark requires aligned bank and ledger rows, got banks=${init.banks.size} bankBalances=${bankBalances.size} bankIds=$bankIds",
        )
      init.banks
        .zip(bankBalances)
        .map: (bank, balances) =>
          val stocks     = LedgerFinancialState.projectBankFinancialStocks(balances)
          val corpBond   = corpBondHoldings(bank.id)
          val credit     = balances.firmLoan + balances.consumerLoan + balances.mortgageLoan
          val govBonds   = balances.govBondAfs + balances.govBondHtm
          val bankAssets = singleBankAssets(balances)
          val levyBase   =
            Banking.computePolishBankLevyTaxableAssets(bank, PolishBankLevyAssetPerimeter.fromBankStocks(stocks, corpBond))
          val car        = rawDecimal(Banking.car(bank, stocks, corpBond).toLong)
          val minCar     = rawDecimal(Macroprudential.effectiveMinCar(bank.id.toInt, init.world.mechanisms.macropru.ccyb).toLong)
          BankRow(
            runId = "",
            seed = seed,
            bankId = bank.id.toInt,
            bankName = Banking.DefaultConfigs.lift(bank.id.toInt).map(_.name).getOrElse(s"Bank ${bank.id.toInt}"),
            capital = bank.capital,
            assets = bankAssets,
            deposits = balances.totalDeposits,
            totalCredit = credit,
            govBondHoldings = govBonds,
            govBondShareOfAssets = finiteShare(govBonds, bankAssets),
            polishBankLevyTaxableAssets = levyBase,
            polishBankLevyTaxableAssetsShare = finiteShare(levyBase, bankAssets),
            capitalAdequacyRatio = car,
            effectiveMinCar = minCar,
            carBuffer = car - minCar,
            lcr = rawDecimal(Banking.lcr(stocks).toLong),
            nsfr = rawDecimal(Banking.nsfr(bank, stocks, corpBond).toLong),
            creditShare = finiteRatio(credit, totalCredit),
            depositShare = finiteRatio(balances.totalDeposits, deposits),
            assetShare = finiteRatio(bankAssets, assets),
          )

  private val RequiredMetricIds: Set[String] = Set(
    "CapitalToAssets",
    "AggregateCar",
    "MinimumCar",
    "MinimumEffectiveCarBuffer",
    "BanksBelowEffectiveCar",
    "FirmLoansToGdp",
    "ConsumerLoansToGdp",
    "MortgageLoansToGdp",
    "TotalBankLoansToGdp",
    "DepositsToLoans",
    "LiquidAssetsToDeposits",
    "ReserveToDeposits",
    "DepositSplitCoverage",
    "DemandDepositShare",
    "InitialEclAllowanceToLoans",
    "EclStagedShareOfCoveredLoans",
    "AggregateNplRatio",
    "LargestBankCreditShare",
    "BankCreditHhi",
  )

  private[diagnostics] val Targets: Vector[TargetBand] = Vector(
    TargetBand(
      id = "CapitalToAssets",
      label = "Bank capital / banking-sector assets",
      unit = "ratio",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.06")),
      upper = Some(BigDecimal("0.12")),
      sourceNote =
        "Stylized Poland 2026 opening balance-sheet guardrail using BankState.capital, the regulatory/accounting bank-capital buffer, relative to simplified banking-sector assets.",
      interpretation = "Checks whether the opening balance sheet is thinly capitalised before any macro shock.",
    ),
    TargetBand(
      id = "AggregateCar",
      label = "Aggregate capital adequacy ratio",
      unit = "ratio",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.191")),
      upper = Some(BigDecimal("0.231")),
      sourceNote =
        "KNF February 2026 total-capital ratio anchor near 21.1%, mapped from BankState.capital to the model's explicit regulatory RWA perimeter with +/-2pp tolerance.",
      interpretation = "The sector should not start close to regulatory capital failure.",
    ),
    TargetBand(
      id = "MinimumCar",
      label = "Weakest-bank capital adequacy ratio",
      unit = "ratio",
      guardrailClass = GuardrailClass.HardInvariant,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.08")),
      upper = None,
      sourceNote = "Basel III CRR floor applied to BankState.capital; model banks should not start below the base 8% capital requirement.",
      interpretation = "A bank below the base capital floor at t=0 means the failure process begins from an invalid initial state.",
    ),
    TargetBand(
      id = "MinimumEffectiveCarBuffer",
      label = "Weakest-bank buffer above effective minimum CAR",
      unit = "ratio",
      guardrailClass = GuardrailClass.HardInvariant,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = None,
      sourceNote = "Effective minimum CAR compares BankState.capital with the base requirement, opening CCyB, O-SII buffer and P2R add-ons.",
      interpretation = "Negative opening buffer means at least one bank starts already in supervisory breach.",
    ),
    TargetBand(
      id = "BanksBelowEffectiveCar",
      label = "Banks below effective minimum CAR",
      unit = "count",
      guardrailClass = GuardrailClass.HardInvariant,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0")),
      upper = Some(BigDecimal("0")),
      sourceNote = "Direct count of opening bank rows whose BankState.capital-based CAR is below their effective minimum.",
      interpretation = "No bank should require resolution before the first simulated month.",
    ),
    TargetBand(
      id = "FirmLoansToGdp",
      label = "Corporate/nonfinancial business loans / annual GDP",
      unit = "ratio",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.10")),
      upper = Some(BigDecimal("0.18")),
      sourceNote = "KNF February 2026 business-loan stock bridge used by banking.initLoans.",
      interpretation = "Corporate loans should be visible but not dominate the Polish credit stock.",
    ),
    TargetBand(
      id = "ConsumerLoansToGdp",
      label = "Consumer loans / annual GDP",
      unit = "ratio",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.03")),
      upper = Some(BigDecimal("0.08")),
      sourceNote = "Same 2026-04-30 consumer-credit band used by household credit-stress calibration.",
      interpretation = "Unsecured household credit should be material but much smaller than GDP.",
    ),
    TargetBand(
      id = "MortgageLoansToGdp",
      label = "Mortgage loans / annual GDP",
      unit = "ratio",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.09")),
      upper = Some(BigDecimal("0.16")),
      sourceNote = "Same mortgage-stock bridge used by household credit-stress calibration and housing.initMortgage.",
      interpretation = "Mortgage assets mirrored onto banks should sit near the Polish housing-credit scale.",
    ),
    TargetBand(
      id = "TotalBankLoansToGdp",
      label = "Firm, consumer, and mortgage loans / annual GDP",
      unit = "ratio",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.25")),
      upper = Some(BigDecimal("0.40")),
      sourceNote = "Composite opening credit-stock guardrail combining the model's bank-owned firm, consumer and mortgage loan books.",
      interpretation = "The banking-sector loan book should not start in the old 80%+ credit-to-GDP red-flag regime.",
    ),
    TargetBand(
      id = "DepositsToLoans",
      label = "Customer deposits / bank loan book",
      unit = "ratio",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("1.50")),
      upper = Some(BigDecimal("3.00")),
      sourceNote = "Stylized funding guardrail for the high-deposit Polish banking sector.",
      interpretation = "Deposits should comfortably fund credit, but an extreme value would signal an unrealistic liability/asset mix.",
    ),
    TargetBand(
      id = "LiquidAssetsToDeposits",
      label = "Government bonds and reserves / deposits",
      unit = "ratio",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.10")),
      upper = Some(BigDecimal("0.30")),
      sourceNote = "Opening liquidity-stock guardrail using modeled reserves plus government-bond holdings as liquid assets.",
      interpretation = "The sector should have visible liquid assets against deposits before stress starts.",
    ),
    TargetBand(
      id = "ReserveToDeposits",
      label = "NBP reserves / deposits",
      unit = "ratio",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.02")),
      upper = Some(BigDecimal("0.06")),
      sourceNote = "NBP required-reserve bridge around the configured 3.5% reserve requirement.",
      interpretation = "A zero opening reserve stock means the reserve-requirement channel is not seeded in the bank ledger.",
    ),
    TargetBand(
      id = "DepositSplitCoverage",
      label = "Demand plus term deposits / total deposits",
      unit = "ratio",
      guardrailClass = GuardrailClass.HardInvariant,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("1.00")),
      upper = Some(BigDecimal("1.00")),
      sourceNote = "Ledger consistency check for deposit composition used by M1/M2, LCR and NSFR diagnostics.",
      interpretation = "Total deposits should be decomposed into demand and term buckets, not left as an unclassified liability.",
    ),
    TargetBand(
      id = "DemandDepositShare",
      label = "Demand deposits / total deposits",
      unit = "share",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.55")),
      upper = Some(BigDecimal("0.65")),
      sourceNote = "Configured termDepositFrac is 40%, implying a 60% demand-deposit opening share.",
      interpretation = "This guards the deposit split used by monetary aggregates and liquidity outflow assumptions.",
    ),
    TargetBand(
      id = "InitialEclAllowanceToLoans",
      label = "Opening ECL allowance / ECL-covered loans",
      unit = "ratio",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.005")),
      upper = Some(BigDecimal("0.03")),
      sourceNote = "IFRS 9 Stage 1 opening allowance should be seeded rather than created as a first-month catch-up.",
      interpretation = "A zero allowance suggests the first ECL step may book an artificial initialization hit.",
    ),
    TargetBand(
      id = "EclStagedShareOfCoveredLoans",
      label = "ECL staged book / ECL-covered loans",
      unit = "share",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.95")),
      upper = Some(BigDecimal("1.05")),
      sourceNote = "The current ECL engine covers firm plus consumer loans; mortgage ECL is not in this perimeter yet.",
      interpretation = "The covered loan book should already be assigned to ECL stages at model start.",
    ),
    TargetBand(
      id = "AggregateNplRatio",
      label = "Opening NPL stock / covered loans",
      unit = "ratio",
      guardrailClass = GuardrailClass.ExploratoryDiagnostic,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = Some(BigDecimal("0.06")),
      sourceNote = "Exploratory opening credit-quality check across corporate and consumer NPL stocks.",
      interpretation = "Zero may be acceptable as a clean start, but high opening NPLs would explain early capital pressure.",
    ),
    TargetBand(
      id = "LargestBankCreditShare",
      label = "Largest bank credit share",
      unit = "share",
      guardrailClass = GuardrailClass.SoftCalibrationWarning,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = Some(BigDecimal("0.25")),
      sourceNote = "Model concentration guardrail aligned with the macroprudential single-name concentration limit.",
      interpretation = "No single bank row should dominate the opening credit book.",
    ),
    TargetBand(
      id = "BankCreditHhi",
      label = "Bank credit HHI",
      unit = "index",
      guardrailClass = GuardrailClass.ExploratoryDiagnostic,
      vintage = BaselineVintage,
      lower = Some(BigDecimal("0.00")),
      upper = Some(BigDecimal("0.20")),
      sourceNote = "Herfindahl-Hirschman index over opening bank credit shares.",
      interpretation = "A compact concentration diagnostic for the default banking-sector rows.",
    ),
  )

  private val Metrics: Vector[MetricDef] = Vector(
    metric("CapitalToAssets")(ctx => ctx.ratio(ctx.capital, ctx.assets)),
    metric("AggregateCar")(ctx => ObservedValue.Finite(rawDecimal(ctx.aggregate.car(using ctx.params).toLong))),
    metric("MinimumCar")(ctx => finiteMinimum(ctx.rows.map(_.capitalAdequacyRatio))),
    metric("MinimumEffectiveCarBuffer")(ctx => finiteMinimum(ctx.rows.map(_.carBuffer))),
    metric("BanksBelowEffectiveCar")(ctx => ObservedValue.Finite(BigDecimal(ctx.rows.count(_.carBuffer < BigDecimal(0))))),
    metric("FirmLoansToGdp")(ctx => ctx.ratio(ctx.firmLoans, ctx.annualGdp)),
    metric("ConsumerLoansToGdp")(ctx => ctx.ratio(ctx.consumerLoans, ctx.annualGdp)),
    metric("MortgageLoansToGdp")(ctx => ctx.ratio(ctx.mortgageLoans, ctx.annualGdp)),
    metric("TotalBankLoansToGdp")(ctx => ctx.ratio(ctx.totalCredit, ctx.annualGdp)),
    metric("DepositsToLoans")(ctx => ctx.ratio(ctx.deposits, ctx.totalCredit)),
    metric("LiquidAssetsToDeposits")(ctx => ctx.ratio(ctx.liquidAssets, ctx.deposits)),
    metric("ReserveToDeposits")(ctx => ctx.ratio(ctx.reserves, ctx.deposits)),
    metric("DepositSplitCoverage")(ctx => ctx.ratio(ctx.demandDeposits + ctx.termDeposits, ctx.deposits)),
    metric("DemandDepositShare")(ctx => ctx.ratio(ctx.demandDeposits, ctx.deposits)),
    metric("InitialEclAllowanceToLoans")(ctx => ctx.ratio(ctx.eclAllowance, ctx.eclCoveredLoans)),
    metric("EclStagedShareOfCoveredLoans")(ctx => ctx.ratio(ctx.eclStagedLoans, ctx.eclCoveredLoans)),
    metric("AggregateNplRatio")(ctx => ctx.ratio(ctx.nplStock, ctx.eclCoveredLoans)),
    metric("LargestBankCreditShare")(ctx => finiteMaximum(ctx.rows.map(_.creditShare))),
    metric("BankCreditHhi")(ctx => ObservedValue.Finite(ctx.rows.map(row => row.creditShare * row.creditShare).sum)),
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
    ZIO
      .fromEither(validate(config))
      .flatMap: validConfig =>
        given SimParams = SimParams.defaults

        for
          results    <- ZIO.foreach(validConfig.seedRange): seed =>
            ZIO
              .attempt:
                val init = WorldInit.initialize(InitRandomness.Contract.fromSeed(seed))
                computeSeed(validConfig, seed, init)
              .mapError(ex => s"Seed $seed crashed during initialization or benchmark computation: ${ex.getMessage}")
          seedMetrics = results.flatMap(_._1)
          bankRows    = results.flatMap(_._2)
          summary     = summarize(validConfig, seedMetrics)
          paths      <- writeArtifactsZIO(validConfig, seedMetrics, summary, bankRows)
        yield ExportResult(paths, seedMetrics, summary, bankRows)

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
      .flatMap(valid => Either.cond(valid.runId.trim.nonEmpty, valid, "--run-id must be non-empty"))
      .flatMap(valid => Either.cond(Metrics.map(_.target.id).toSet == RequiredMetricIds, valid, "Bank balance-sheet metric coverage is incomplete"))

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

  private[diagnostics] def summarize(config: Config, rows: Vector[SeedMetric]): Vector[SummaryMetric] =
    Targets.map: target =>
      val metricRows = rows.filter(_.target.id == target.id)
      val finite     = metricRows.flatMap(_.value.finite)
      val mean       =
        if metricRows.exists(_.value == ObservedValue.PositiveInfinity) then ObservedValue.PositiveInfinity
        else if finite.nonEmpty then ObservedValue.Finite(finite.sum / BigDecimal(finite.size))
        else ObservedValue.NotAvailable
      val status     =
        if metricRows.nonEmpty then worstStatus(metricRows.map(row => worstStatus(Vector(row.status, evaluate(row.value, target)))))
        else evaluate(mean, target)
      SummaryMetric(
        runId = config.runId,
        seeds = config.seeds,
        target = target,
        mean = mean,
        min = if finite.nonEmpty then Some(finite.min) else None,
        max = if finite.nonEmpty then Some(finite.max) else None,
        status = status,
      )

  private[diagnostics] def computeSeed(config: Config, seed: Long, init: WorldInit.InitResult)(using SimParams): (Vector[SeedMetric], Vector[BankRow]) =
    val context = SeedContext(seed, init)
    val metrics = Metrics.map: metric =>
      val value = metric.compute(context)
      SeedMetric(config.runId, seed, metric.target, value, evaluate(value, metric.target))
    val rows    = context.rows.map(_.copy(runId = config.runId))
    (metrics, rows)

  private def writeArtifactsZIO(
      config: Config,
      seedMetrics: Vector[SeedMetric],
      summary: Vector[SummaryMetric],
      bankRows: Vector[BankRow],
  ): ZIO[Any, String, Vector[Path]] =
    val seedMetricsPath = config.runRoot.resolve("bank-balance-sheet-seed-metrics.tsv")
    val summaryTsvPath  = config.runRoot.resolve("bank-balance-sheet-summary.tsv")
    val targetTsvPath   = config.runRoot.resolve("bank-balance-sheet-targets.tsv")
    val bankRowsPath    = config.runRoot.resolve("bank-balance-sheet-bank-rows.tsv")
    val reportMdPath    = config.runRoot.resolve("bank-balance-sheet-report.md")
    for
      seedPath    <- McTsvFile.writeAll(seedMetricsPath, seedMetrics, SeedMetricsTsvSchema)(DiagnosticIo.outputFailure)
      summaryPath <- McTsvFile.writeAll(summaryTsvPath, summary, SummaryTsvSchema)(DiagnosticIo.outputFailure)
      targetPath  <- McTsvFile.writeAll(targetTsvPath, Targets, TargetsTsvSchema)(DiagnosticIo.outputFailure)
      bankPath    <- McTsvFile.writeAll(bankRowsPath, bankRows, BankRowsTsvSchema)(DiagnosticIo.outputFailure)
      reportPath  <- DiagnosticIo.writeText(reportMdPath, renderReport(config, summary))
    yield Vector(seedPath, summaryPath, targetPath, bankPath, reportPath)

  private[diagnostics] val SeedMetricsTsvSchema: McTsvSchema[SeedMetric] =
    McTsvSchema(
      header = McTsvSchema.header(
        "RunId",
        "Seed",
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
        DelimitedTextFormat.Tsv.join(
          Vector(
            row.runId,
            row.seed.toString,
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
          ),
        ),
    )

  private[diagnostics] val SummaryTsvSchema: McTsvSchema[SummaryMetric] =
    McTsvSchema(
      header = McTsvSchema.header(
        "RunId",
        "Seeds",
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
        DelimitedTextFormat.Tsv.join(
          Vector(
            row.runId,
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
          ),
        ),
    )

  private[diagnostics] val TargetsTsvSchema: McTsvSchema[TargetBand] =
    McTsvSchema(
      header = McTsvSchema.header("Metric", "Label", "Unit", "GuardrailClass", "Vintage", "Lower", "Upper", "SourceNote", "Interpretation"),
      render = target =>
        DelimitedTextFormat.Tsv.join(
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
          ),
        ),
    )

  private[diagnostics] val BankRowsTsvSchema: McTsvSchema[BankRow] =
    McTsvSchema(
      header = McTsvSchema.header(
        "RunId",
        "Seed",
        "BankId",
        "BankName",
        "Capital",
        "Assets",
        "Deposits",
        "TotalCredit",
        "GovBondHoldings",
        "GovBondShareOfAssets",
        "PolishBankLevyTaxableAssets",
        "PolishBankLevyTaxableAssetsShare",
        "CapitalAdequacyRatio",
        "EffectiveMinCar",
        "CarBuffer",
        "Lcr",
        "Nsfr",
        "CreditShare",
        "DepositShare",
        "AssetShare",
      ),
      render = row =>
        DelimitedTextFormat.Tsv.join(
          Vector(
            row.runId,
            row.seed.toString,
            row.bankId.toString,
            row.bankName,
            renderPln(row.capital),
            renderPln(row.assets),
            renderPln(row.deposits),
            renderPln(row.totalCredit),
            renderPln(row.govBondHoldings),
            row.govBondShareOfAssets.format(6),
            renderPln(row.polishBankLevyTaxableAssets),
            row.polishBankLevyTaxableAssetsShare.format(6),
            renderDecimal(row.capitalAdequacyRatio),
            renderDecimal(row.effectiveMinCar),
            renderDecimal(row.carBuffer),
            renderDecimal(row.lcr),
            renderDecimal(row.nsfr),
            renderDecimal(row.creditShare),
            renderDecimal(row.depositShare),
            renderDecimal(row.assetShare),
          ),
        ),
    )

  private[diagnostics] def renderSeedMetricsTsv(rows: Vector[SeedMetric]): String =
    renderTsv(SeedMetricsTsvSchema, rows)

  private[diagnostics] def renderSummaryTsv(rows: Vector[SummaryMetric]): String =
    renderTsv(SummaryTsvSchema, rows)

  private[diagnostics] def renderTargetsTsv(targets: Vector[TargetBand]): String =
    renderTsv(TargetsTsvSchema, targets)

  private[diagnostics] def renderBankRowsTsv(rows: Vector[BankRow]): String =
    renderTsv(BankRowsTsvSchema, rows)

  private def renderTsv[A](schema: McTsvSchema[A], rows: Vector[A]): String =
    (schema.header +: rows.map(schema.render)).mkString("\n") + "\n"

  private[diagnostics] def renderReport(config: Config, summary: Vector[SummaryMetric]): String =
    val command     =
      s"""sbt "bankBalanceSheetBenchmark --seed-start ${config.seedStart} --seeds ${config.seeds} --out ${config.out} --run-id ${config.runId}""""
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
    val issueRows   = summary
      .filter(row => row.status == Status.Warn || row.status == Status.Fail)
      .map: row =>
        markdownRow(Vector(row.target.id, row.status.token, row.target.interpretation))
    val issueBlock  =
      if issueRows.nonEmpty then
        Vector(
          "",
          "## Interpretation",
          "",
          "The rows below identify opening balance-sheet channels that need follow-up before the bank-failure mechanism is interpreted as a macro shock result.",
          "",
          markdownRow(Vector("Metric", "Status", "Why it matters")),
          markdownRow(Vector("---", "---", "---")),
        ) ++ issueRows
      else
        Vector(
          "",
          "## Interpretation",
          "",
          "No opening balance-sheet guardrail emitted WARN or FAIL; banks do not appear to start close to failure before shocks.",
        )
    val lines       =
      Vector(
        "# Bank Balance-Sheet Benchmark",
        "",
        "Generated by `BankBalanceSheetBenchmarkExport`.",
        "",
        s"- Run id: `${config.runId}`",
        s"- Seeds: `${config.seeds}` from `${config.seedStart}`",
        s"- Repeat command: `$command`",
        "",
        "## Guardrail Classes",
        "",
        "- `HARD_INVARIANT`: opening accounting or prudential boundary that should not fail.",
        "- `SOFT_CALIBRATION_WARNING`: Poland-relevant opening balance-sheet band; violations are warnings, not runtime failures.",
        "- `EXPLORATORY_DIAGNOSTIC`: useful bank-sector diagnostic without a settled empirical acceptance band.",
        "",
        "## Bank Capital Semantics",
        "",
        BankCapitalSourceStatement,
        "",
        "`AssetType.Capital` remains an unsupported persisted diagnostic stock: SFC-validatable, but outside the supported transferable ledger-owned stock slice.",
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
      ) ++ summaryRows ++ issueBlock
    lines.mkString("\n") + "\n"

  private def metric(id: String)(compute: SeedContext => ObservedValue): MetricDef =
    val target = Targets.find(_.id == id).getOrElse(throw new IllegalArgumentException(s"Missing target band: $id"))
    MetricDef(target, compute)

  private def finiteMinimum(values: Vector[BigDecimal]): ObservedValue =
    if values.nonEmpty then ObservedValue.Finite(values.min) else ObservedValue.NotAvailable

  private def finiteMaximum(values: Vector[BigDecimal]): ObservedValue =
    if values.nonEmpty then ObservedValue.Finite(values.max) else ObservedValue.NotAvailable

  private def worstStatus(statuses: Vector[Status]): Status =
    statuses.maxBy(statusSeverity)

  private def statusSeverity(status: Status): Int =
    status match
      case Status.Fail => 3
      case Status.Warn => 2
      case Status.Info => 1
      case Status.Pass => 0

  private def bankAssets(rows: Vector[LedgerFinancialState.BankBalances]): PLN =
    rows.map(singleBankAssets).sumPln

  private def singleBankAssets(row: LedgerFinancialState.BankBalances): PLN =
    row.firmLoan + row.consumerLoan + row.mortgageLoan + row.govBondAfs + row.govBondHtm + row.corpBond + row.reserve + row.interbankLoan.max(PLN.Zero)

  private def ratioRaw(numerator: BigDecimal, denominator: BigDecimal): ObservedValue =
    if denominator == BigDecimal(0) then
      if numerator == BigDecimal(0) then ObservedValue.Finite(BigDecimal(0))
      else ObservedValue.PositiveInfinity
    else ObservedValue.Finite(numerator / denominator)

  private def rawDecimal(raw: Long): BigDecimal =
    BigDecimal(raw) / BigDecimal(com.boombustgroup.amorfati.fp.FixedPointBase.Scale)

  private def renderValue(value: ObservedValue): String =
    value match
      case ObservedValue.Finite(v)        => renderDecimal(v)
      case ObservedValue.PositiveInfinity => "INF"
      case ObservedValue.NotAvailable     => "NA"

  private def renderPln(value: PLN): String =
    renderDecimal(rawDecimal(value.toLong))

  private def renderDecimal(value: BigDecimal): String =
    value.setScale(6, RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros.toPlainString

  private def bound(value: Option[BigDecimal]): String =
    value.map(renderDecimal).getOrElse("n/a")

  private def markdownRow(values: Vector[String]): String =
    values.map(_.replace("|", "\\|")).mkString("| ", " | ", " |")

  private def knownFlag(flag: String): Boolean =
    flag == "--seed-start" || flag == "--seeds" || flag == "--run-id" || flag == "--out"

  private def parseLong(value: String, name: String): Either[String, Long] =
    Try(value.toLong).toEither.left.map(_ => s"$name must be a long integer")

  private def parseInt(value: String, name: String): Either[String, Int] =
    Try(value.toInt).toEither.left.map(_ => s"$name must be an integer")

  private def usage: String =
    """Usage: BankBalanceSheetBenchmarkExport [options]
      |
      |Options:
      |  --seed-start <long>  First initialization seed, default 1
      |  --seeds <int>        Number of seeds, default 10
      |  --run-id <string>    Output run id, default bank-balance-sheet-benchmark
      |  --out <path>         Output root, default target/bank-balance-sheet-benchmark
      |""".stripMargin

end BankBalanceSheetBenchmarkExport
