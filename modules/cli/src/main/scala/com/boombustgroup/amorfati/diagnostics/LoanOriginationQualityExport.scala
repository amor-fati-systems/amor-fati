package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.agents.{Banking, BankruptReason, Firm, HhFinancialDistressState, HhStatus, Household, TechState}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.montecarlo.core.McSeedMonth
import com.boombustgroup.amorfati.montecarlo.diagnostics.McDiagnosticRunner
import com.boombustgroup.amorfati.montecarlo.io.{McTsvFile, McTsvSchema}
import com.boombustgroup.amorfati.types.*
import zio.ZIO
import zio.stream.ZStream

import java.nio.file.Path
import scala.math.BigDecimal.RoundingMode
import scala.util.Try

/** Optional borrower-level credit diagnostics for issue #590.
  *
  * The export links origination-month underwriting quality to later
  * arrears/default outcomes. It is intentionally separate from the standard
  * Monte Carlo schema because it can emit many micro rows.
  */
object LoanOriginationQualityExport:

  final case class Config(
      seedStart: Long = 1L,
      seeds: Int = 5,
      months: Int = 60,
      outcomeWindow: Int = 12,
      runId: String = "loan-origination-quality",
      out: Path = Path.of("target/loan-origination-quality"),
  ):
    def seedRange: Vector[Long] = Vector.tabulate(seeds)(i => seedStart + i.toLong)
    def runRoot: Path           = out.resolve(runId)

  final case class HhOriginationRow(
      runId: String,
      seed: Long,
      originationMonth: Int,
      householdId: Int,
      bankId: Int,
      status: String,
      incomeDecile: String,
      openingFinancialDistressState: String,
      closingFinancialDistressState: String,
      openingDistressMonths: Int,
      closingDistressMonths: Int,
      wage: PLN,
      openingDemandDeposit: PLN,
      openingConsumerLoan: PLN,
      openingMortgageLoan: PLN,
      monthlyIncome: PLN,
      mortgageDebtService: PLN,
      consumerDebtService: PLN,
      totalDebtService: PLN,
      totalDsr: Option[BigDecimal],
      mortgageDsr: Option[BigDecimal],
      consumerDsr: Option[BigDecimal],
      creditCapacity: PLN,
      creditAccessEligible: Boolean,
      creditDemand: PLN,
      approvedPrincipal: PLN,
      rejectedPrincipal: PLN,
      bankRejectedPrincipal: PLN,
      bankApprovalProduct: String,
      bankRejectionReason: String,
      bankApprovalProbability: Option[Share],
      bankApprovalRoll: Option[Share],
      bankProjectedCar: Option[Multiplier],
      bankMinCar: Option[Multiplier],
      bankManagementCarTarget: Option[Multiplier],
      bankCapitalThrottle: Option[Share],
      bankApprovalLcr: Option[Multiplier],
      bankApprovalNsfr: Option[Multiplier],
      bankCar: BigDecimal,
      bankLcr: BigDecimal,
      bankNsfr: BigDecimal,
      sameMonthArrears: Boolean,
      sameMonthDefault: Boolean,
      futureArrearsWithinWindow: Boolean,
      futureDefaultWithinWindow: Boolean,
      futureBankruptcyWithinWindow: Boolean,
      firstFutureDefaultMonth: Option[Int],
      observedFutureMonths: Int,
      futureWriteOffAmount: PLN,
  )

  final case class FirmOriginationRow(
      runId: String,
      seed: Long,
      originationMonth: Int,
      firmId: Int,
      bankId: Int,
      sector: String,
      sizeClass: String,
      techState: String,
      decisionType: String,
      creditPurpose: String,
      bankApprovalObserved: Boolean,
      bankApproved: Boolean,
      bankRejected: Boolean,
      observedCreditNeed: PLN,
      approvedPrincipal: PLN,
      cashBefore: PLN,
      firmLoanBefore: PLN,
      realizedPostTaxProfit: PLN,
      grossInvestment: PLN,
      principalRepaid: PLN,
      monthlyDebtServiceProxy: PLN,
      debtToCash: Option[BigDecimal],
      dscrProxy: Option[BigDecimal],
      workersBefore: Int,
      workersAfter: Int,
      foreignOwned: Boolean,
      stateOwned: Boolean,
      bankCar: BigDecimal,
      bankLcr: BigDecimal,
      bankNsfr: BigDecimal,
      sameMonthBankrupt: Boolean,
      futureBankruptWithinWindow: Boolean,
      firstFutureBankruptcyMonth: Option[Int],
      observedFutureMonths: Int,
      bankruptcyReason: String,
  )

  final case class SummaryRow(
      runId: String,
      borrowerType: String,
      cohortType: String,
      cohortValue: String,
      rows: Int,
      approvedRows: Int,
      rejectedRows: Int,
      approvedPrincipal: PLN,
      futureBadRows: Int,
      futureBadRate: Option[BigDecimal],
      meanRiskRatio: Option[BigDecimal],
      meanBankCar: Option[BigDecimal],
      interpretation: String,
  )

  final case class ExportResult(
      paths: Vector[Path],
      summaryRows: Vector[SummaryRow],
  )

  private final case class ReportStats(
      householdRows: Int,
      householdBadRows: Int,
      firmRows: Int,
      firmBadRows: Int,
  ):
    def householdBadRate: Option[BigDecimal] =
      if householdRows > 0 then Some(BigDecimal(householdBadRows) / BigDecimal(householdRows)) else None

    def firmBadRate: Option[BigDecimal] =
      if firmRows > 0 then Some(BigDecimal(firmBadRows) / BigDecimal(firmRows)) else None

  private final case class HhMonthOutcome(
      month: Int,
      householdId: Int,
      consumerLoanDefault: PLN,
      liquidityBridgeChargeOff: PLN,
      liquidityShortfallFinancing: PLN,
      consumerDebtArrears: PLN,
      mortgageArrears: PLN,
      financialDistressState: HhFinancialDistressState,
      bankrupt: Boolean,
  ):
    def arrears: Boolean =
      consumerDebtArrears > PLN.Zero || mortgageArrears > PLN.Zero || financialDistressState == HhFinancialDistressState.Arrears

    def defaultOrWriteOff: Boolean =
      consumerLoanDefault > PLN.Zero || liquidityBridgeChargeOff > PLN.Zero ||
        financialDistressState == HhFinancialDistressState.Defaulted || financialDistressState == HhFinancialDistressState.Bankruptcy || bankrupt

  private final case class FirmMonthOutcome(
      month: Int,
      firmId: Int,
      bankrupt: Boolean,
      bankruptcyReason: Option[BankruptReason],
  )

  private final case class FirmCreditLeg(
      decisionType: String,
      creditPurpose: String,
      bankApproval: Option[Boolean],
      observedCreditNeed: PLN,
      approvedPrincipal: PLN,
  )

  private enum LoanRow:
    case Household(row: HhOriginationRow)
    case Firm(row: FirmOriginationRow)

  private final case class CohortKey(
      borrowerType: String,
      cohortType: String,
      cohortValue: String,
  )

  private final class CohortAccumulator:
    private var rowCount          = 0
    private var approvedRowCount  = 0
    private var rejectedRowCount  = 0
    private var approvedPrincipal = PLN.Zero
    private var futureBadRowCount = 0
    private var riskRatioSum      = BigDecimal(0)
    private var riskRatioCount    = 0
    private var bankCarSum        = BigDecimal(0)
    private var bankCarCount      = 0

    def add(
        approved: Boolean,
        rejected: Boolean,
        principal: PLN,
        futureBad: Boolean,
        riskRatio: Option[BigDecimal],
        bankCar: BigDecimal,
    ): Unit =
      rowCount += 1
      if approved then approvedRowCount += 1
      if rejected then rejectedRowCount += 1
      approvedPrincipal += principal
      if futureBad then futureBadRowCount += 1
      riskRatio.foreach: value =>
        riskRatioSum += value
        riskRatioCount += 1
      bankCarSum += bankCar
      bankCarCount += 1

    def toSummaryRow(runId: String, key: CohortKey): SummaryRow =
      SummaryRow(
        runId = runId,
        borrowerType = key.borrowerType,
        cohortType = key.cohortType,
        cohortValue = key.cohortValue,
        rows = rowCount,
        approvedRows = approvedRowCount,
        rejectedRows = rejectedRowCount,
        approvedPrincipal = approvedPrincipal,
        futureBadRows = futureBadRowCount,
        futureBadRate = if rowCount > 0 then Some(BigDecimal(futureBadRowCount) / BigDecimal(rowCount)) else None,
        meanRiskRatio = if riskRatioCount > 0 then Some((riskRatioSum / BigDecimal(riskRatioCount)).setScale(6, RoundingMode.HALF_UP)) else None,
        meanBankCar = if bankCarCount > 0 then Some((bankCarSum / BigDecimal(bankCarCount)).setScale(6, RoundingMode.HALF_UP)) else None,
        interpretation = key.borrowerType match
          case "Household" => "HH future bad rows mean later default/write-off or bankruptcy inside the configured observation window."
          case "Firm"      => "Firm future bad rows mean later bankruptcy inside the configured observation window; mean risk ratio is DSCR proxy."
          case other       => s"$other future bad rows are diagnostic cohort counts.",
      )

  private final class LoanSummaryAccumulator:
    private val cohorts = scala.collection.mutable.Map.empty[CohortKey, CohortAccumulator]
    private var hhRows  = 0
    private var hhBad   = 0
    private var fRows   = 0
    private var fBad    = 0

    def add(row: LoanRow): LoanSummaryAccumulator =
      row match
        case LoanRow.Household(hh) => addHousehold(hh)
        case LoanRow.Firm(firm)    => addFirm(firm)
      this

    def reportStats: ReportStats =
      ReportStats(hhRows, hhBad, fRows, fBad)

    def summaryRows(runId: String): Vector[SummaryRow] =
      HouseholdCohortTypes.flatMap(cohortRows(runId, "Household", _)) ++
        FirmCohortTypes.flatMap(cohortRows(runId, "Firm", _))

    private def addHousehold(row: HhOriginationRow): Unit =
      val futureBad = row.futureDefaultWithinWindow || row.futureBankruptcyWithinWindow
      hhRows += 1
      if futureBad then hhBad += 1
      householdCohorts(row).foreach: (cohortType, cohortValue) =>
        addCohort(
          CohortKey("Household", cohortType, cohortValue),
          approved = row.approvedPrincipal > PLN.Zero,
          rejected = row.rejectedPrincipal > PLN.Zero || row.bankRejectedPrincipal > PLN.Zero,
          approvedPrincipal = row.approvedPrincipal,
          futureBad = futureBad,
          riskRatio = row.totalDsr,
          bankCar = row.bankCar,
        )

    private def addFirm(row: FirmOriginationRow): Unit =
      val futureBad = row.futureBankruptWithinWindow || row.sameMonthBankrupt
      fRows += 1
      if futureBad then fBad += 1
      firmCohorts(row).foreach: (cohortType, cohortValue) =>
        addCohort(
          CohortKey("Firm", cohortType, cohortValue),
          approved = row.approvedPrincipal > PLN.Zero,
          rejected = row.bankRejected,
          approvedPrincipal = row.approvedPrincipal,
          futureBad = futureBad,
          riskRatio = row.dscrProxy,
          bankCar = row.bankCar,
        )

    private def addCohort(
        key: CohortKey,
        approved: Boolean,
        rejected: Boolean,
        approvedPrincipal: PLN,
        futureBad: Boolean,
        riskRatio: Option[BigDecimal],
        bankCar: BigDecimal,
    ): Unit =
      val cohort = cohorts.getOrElseUpdate(key, new CohortAccumulator)
      cohort.add(approved, rejected, approvedPrincipal, futureBad, riskRatio, bankCar)

    private def cohortRows(runId: String, borrowerType: String, cohortType: String): Vector[SummaryRow] =
      cohorts
        .collect:
          case (key, accumulator) if key.borrowerType == borrowerType && key.cohortType == cohortType =>
            accumulator.toSummaryRow(runId, key)
        .toVector
        .sortBy(_.cohortValue)

  private final class SeedRowWindow(config: Config, seed: Long)(using SimParams):
    private val rawHhEvents   = scala.collection.mutable.Map.empty[Int, scala.collection.mutable.ArrayBuffer[HhOriginationRow]]
    private val rawFirmEvents = scala.collection.mutable.Map.empty[Int, scala.collection.mutable.ArrayBuffer[FirmOriginationRow]]
    private val hhOutcomes    = scala.collection.mutable.Map.empty[(Int, Int), HhMonthOutcome]
    private val firmOutcomes  = scala.collection.mutable.Map.empty[(Int, Int), FirmMonthOutcome]

    def observe(month: McSeedMonth): Vector[LoanRow] =
      val monthHhEvents    = scala.collection.mutable.ArrayBuffer.empty[(HhOriginationRow, Int)]
      val monthFirmEvents  = scala.collection.mutable.ArrayBuffer.empty[(FirmOriginationRow, Int)]
      val monthHhOutcomes  = scala.collection.mutable.ArrayBuffer.empty[HhMonthOutcome]
      val monthFirmOutcome = scala.collection.mutable.ArrayBuffer.empty[FirmMonthOutcome]

      collectHouseholdMonth(config, seed, month, monthHhEvents, monthHhOutcomes)
      collectFirmMonth(config, seed, month, monthFirmEvents, monthFirmOutcome)

      monthHhEvents.foreach: (row, originationMonth) =>
        rawHhEvents.getOrElseUpdate(originationMonth, scala.collection.mutable.ArrayBuffer.empty) += row
      monthFirmEvents.foreach: (row, originationMonth) =>
        rawFirmEvents.getOrElseUpdate(originationMonth, scala.collection.mutable.ArrayBuffer.empty) += row
      monthHhOutcomes.foreach(outcome => hhOutcomes += ((outcome.householdId, outcome.month) -> outcome))
      monthFirmOutcome.foreach(outcome => firmOutcomes += ((outcome.firmId, outcome.month) -> outcome))

      flushReady(month.executionMonth.toInt, force = false)

    def finish(): Vector[LoanRow] =
      flushReady(config.months, force = true)

    private def flushReady(currentMonth: Int, force: Boolean): Vector[LoanRow] =
      val readyMonths = pendingOriginationMonths.filter(origin => force || origin + config.outcomeWindow <= currentMonth).sorted
      if readyMonths.isEmpty then Vector.empty
      else
        val hhByKey   = hhOutcomes.toMap
        val firmByKey = firmOutcomes.toMap
        val rows      = readyMonths.flatMap: originationMonth =>
          val hhRows   = rawHhEvents
            .remove(originationMonth)
            .fold(Vector.empty[HhOriginationRow])(_.toVector)
            .map(row => LoanRow.Household(enrichHouseholdOutcome(config, row, originationMonth, hhByKey)))
          val firmRows = rawFirmEvents
            .remove(originationMonth)
            .fold(Vector.empty[FirmOriginationRow])(_.toVector)
            .map(row => LoanRow.Firm(enrichFirmOutcome(config, row, originationMonth, firmByKey)))
          hhRows ++ firmRows
        evictExpiredOutcomes()
        rows.toVector

    private def pendingOriginationMonths: Vector[Int] =
      (rawHhEvents.keysIterator ++ rawFirmEvents.keysIterator).toVector.distinct

    private def evictExpiredOutcomes(): Unit =
      val retainFrom = pendingOriginationMonths.minOption.fold(Int.MaxValue)(_ + 1)
      hhOutcomes.keysIterator.filter { case (_, month) => month < retainFrom }.toVector.foreach(hhOutcomes.remove)
      firmOutcomes.keysIterator.filter { case (_, month) => month < retainFrom }.toVector.foreach(firmOutcomes.remove)

  private[diagnostics] val HouseholdHeader: String =
    Vector(
      "RunId",
      "Seed",
      "OriginationMonth",
      "HouseholdId",
      "BankId",
      "Status",
      "IncomeDecile",
      "OpeningFinancialDistressState",
      "ClosingFinancialDistressState",
      "OpeningDistressMonths",
      "ClosingDistressMonths",
      "Wage",
      "OpeningDemandDeposit",
      "OpeningConsumerLoan",
      "OpeningMortgageLoan",
      "MonthlyIncome",
      "MortgageDebtService",
      "ConsumerDebtService",
      "TotalDebtService",
      "TotalDsr",
      "MortgageDsr",
      "ConsumerDsr",
      "CreditCapacity",
      "CreditAccessEligible",
      "CreditDemand",
      "ApprovedPrincipal",
      "RejectedPrincipal",
      "BankRejectedPrincipal",
      "BankApprovalProduct",
      "BankRejectionReason",
      "BankApprovalProbability",
      "BankApprovalRoll",
      "BankProjectedCAR",
      "BankMinCAR",
      "BankManagementCAR",
      "BankCapitalThrottle",
      "BankApprovalLCR",
      "BankApprovalNSFR",
      "BankCar",
      "BankLcr",
      "BankNsfr",
      "SameMonthArrears",
      "SameMonthDefault",
      "FutureArrearsWithinWindow",
      "FutureDefaultWithinWindow",
      "FutureBankruptcyWithinWindow",
      "FirstFutureDefaultMonth",
      "ObservedFutureMonths",
      "FutureWriteOffAmount",
    ).mkString("\t")

  private[diagnostics] val FirmHeader: String =
    Vector(
      "RunId",
      "Seed",
      "OriginationMonth",
      "FirmId",
      "BankId",
      "Sector",
      "SizeClass",
      "TechState",
      "DecisionType",
      "CreditPurpose",
      "BankApprovalObserved",
      "BankApproved",
      "BankRejected",
      "ObservedCreditNeed",
      "ApprovedPrincipal",
      "CashBefore",
      "FirmLoanBefore",
      "RealizedPostTaxProfit",
      "GrossInvestment",
      "PrincipalRepaid",
      "MonthlyDebtServiceProxy",
      "DebtToCash",
      "DscrProxy",
      "WorkersBefore",
      "WorkersAfter",
      "ForeignOwned",
      "StateOwned",
      "BankCar",
      "BankLcr",
      "BankNsfr",
      "SameMonthBankrupt",
      "FutureBankruptWithinWindow",
      "FirstFutureBankruptcyMonth",
      "ObservedFutureMonths",
      "BankruptcyReason",
    ).mkString("\t")

  private[diagnostics] val SummaryHeader: String =
    McTsvSchema.header(
      "RunId",
      "BorrowerType",
      "CohortType",
      "CohortValue",
      "Rows",
      "ApprovedRows",
      "RejectedRows",
      "ApprovedPrincipal",
      "FutureBadRows",
      "FutureBadRate",
      "MeanRiskRatio",
      "MeanBankCar",
      "Interpretation",
    )

  private val HouseholdCohortTypes: Vector[String] =
    Vector("IncomeDecile", "OpeningDistress", "TotalDsrBand", "OpeningBankCarBand")

  private val FirmCohortTypes: Vector[String] =
    Vector("Sector", "SizeClass", "TechState", "OpeningBankCarBand")

  def main(args: Array[String]): Unit =
    parseArgs(args.toVector) match
      case Left(_) if args.contains("--help") =>
        Console.out.println(usage)
        sys.exit(0)
      case Left(err)                          =>
        Console.err.println(err)
        Console.err.println(usage)
        sys.exit(2)
      case Right(config)                      =>
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
        val hhTsvPath      = validConfig.runRoot.resolve("loan-origination-quality-households.tsv")
        val firmTsvPath    = validConfig.runRoot.resolve("loan-origination-quality-firms.tsv")
        val rowOutputPaths = Vector(hhTsvPath, firmTsvPath)
        for
          summaryAccumulator <- McTsvFile.writeSplitFold(
            hhTsvPath,
            firmTsvPath,
            loanRows(validConfig),
            HouseholdRowsTsvSchema,
            FirmRowsTsvSchema,
            new LoanSummaryAccumulator,
          ) {
            case LoanRow.Household(row) => Left(row)
            case LoanRow.Firm(row)      => Right(row)
          }((acc, row) => acc.add(row))(DiagnosticIo.outputFailure)
          summaryRows         = summaryAccumulator.summaryRows(validConfig.runId)
          paths              <- writeArtifactsZIO(validConfig, rowOutputPaths, summaryAccumulator.reportStats, summaryRows)
        yield ExportResult(paths, summaryRows)

  def parseArgs(args: Vector[String]): Either[String, Config] =
    def missingValue(flag: String): Left[String, Config] = Left(s"Missing value for $flag")

    def loop(rest: Seq[String], config: Config): Either[String, Config] =
      rest match
        case Seq()                                  => Right(config)
        case Seq("--help", _*)                      => Left(usage)
        case Seq(flag, tail*) if knownFlag(flag)    =>
          tail match
            case Seq()                                           => missingValue(flag)
            case Seq(value, _*) if value.startsWith("--")        => missingValue(flag)
            case Seq(value, next*) if flag == "--seed-start"     =>
              parseLong(value, flag).flatMap(seedStart => loop(next, config.copy(seedStart = seedStart)))
            case Seq(value, next*) if flag == "--seeds"          =>
              parseInt(value, flag).flatMap(seeds => loop(next, config.copy(seeds = seeds)))
            case Seq(value, next*) if flag == "--months"         =>
              parseInt(value, flag).flatMap(months => loop(next, config.copy(months = months)))
            case Seq(value, next*) if flag == "--outcome-window" =>
              parseInt(value, flag).flatMap(window => loop(next, config.copy(outcomeWindow = window)))
            case Seq(value, next*) if flag == "--run-id"         =>
              loop(next, config.copy(runId = value))
            case Seq(value, next*) if flag == "--out"            =>
              loop(next, config.copy(out = Path.of(value)))
            case Seq(_, _*)                                      => Left(s"Unknown argument: $flag")
        case Seq(flag, _*) if flag.startsWith("--") => Left(s"Unknown argument: $flag")
        case Seq(value, _*)                         => Left(s"Unexpected positional argument: $value")

    loop(args, Config())

  private[diagnostics] def validate(config: Config): Either[String, Config] =
    Either
      .cond(config.seeds > 0, config, "--seeds must be a positive integer")
      .flatMap(valid => Either.cond(valid.months > 0, valid, "--months must be a positive integer"))
      .flatMap(valid => Either.cond(valid.outcomeWindow >= 0, valid, "--outcome-window must be >= 0"))
      .flatMap(valid => Either.cond(valid.runId.trim.nonEmpty, valid, "--run-id must be non-empty"))

  private def loanRows(config: Config): ZStream[Any, String, LoanRow] =
    McDiagnosticRunner
      .runScenarioSeedStreams(
        Vector(config.runId -> SimParams.defaults),
        config.seedRange,
        config.months,
        _._1,
        _._2,
        traceFirmDecisions = true,
      )((_, seed, months) => computeSeedRows(config, seed, months))

  private def computeSeedRows(
      config: Config,
      seed: Long,
      months: ZStream[Any, String, McSeedMonth],
  ): ZStream[Any, String, LoanRow] =
    given SimParams = SimParams.defaults
    val window      = new SeedRowWindow(config, seed)

    months
      .mapZIO: month =>
        ZIO
          .attempt(window.observe(month))
          .mapError(err => s"Seed $seed failed loan origination diagnostics at month ${month.executionMonth.toInt}: ${err.getMessage}")
      .mapConcat(identity) ++
      ZStream
        .fromZIO(ZIO.attempt(window.finish()).mapError(err => s"Seed $seed failed loan origination diagnostics during final flush: ${err.getMessage}"))
        .mapConcat(identity)

  private def collectHouseholdMonth(
      config: Config,
      seed: Long,
      output: McSeedMonth,
      rows: scala.collection.mutable.ArrayBuffer[(HhOriginationRow, Int)],
      outcomes: scala.collection.mutable.ArrayBuffer[HhMonthOutcome],
  )(using p: SimParams): Unit =
    val month             = output.executionMonth.toInt
    val openingHouseholds = output.openingState.households
    val openingBalances   = output.openingState.ledgerFinancialState.households
    val closingHouseholds = output.householdSnapshotState.households
    val flows             = output.householdMonthlyFlows
    require(openingHouseholds.length == openingBalances.length, "Loan origination HH diagnostics require aligned opening household balances")
    require(openingHouseholds.length == closingHouseholds.length, "Loan origination HH diagnostics require aligned closing household rows")
    require(openingHouseholds.length == flows.length, "Loan origination HH diagnostics require aligned household flows")

    val incomeDeciles = incomeDecileByHousehold(flows)
    val nBanks        = output.openingState.banks.length
    val bankStocks    = output.openingState.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks)

    openingHouseholds
      .zip(openingBalances)
      .zip(closingHouseholds)
      .zip(flows)
      .foreach: nested =>
        val (((opening, openingStock), closing), flow) = nested
        require(opening.id == flow.householdId && closing.id == opening.id, "Loan origination HH diagnostics require household id alignment")
        val outcome                                    = HhMonthOutcome(
          month = month,
          householdId = opening.id.toInt,
          consumerLoanDefault = flow.consumerLoanDefault,
          liquidityBridgeChargeOff = flow.liquidityBridgeChargeOff,
          liquidityShortfallFinancing = flow.liquidityShortfallFinancing,
          consumerDebtArrears = flow.consumerDebtArrears,
          mortgageArrears = flow.mortgageArrears,
          financialDistressState = closing.financialDistressState,
          bankrupt = closing.financialDistressState == HhFinancialDistressState.Bankruptcy || closing.status == HhStatus.Bankrupt,
        )
        outcomes += outcome

        if householdCreditObserved(flow) then
          val bankId           = opening.bankId.toInt
          require(bankId >= 0 && bankId < nBanks, s"Household ${opening.id.toInt} references invalid bank id $bankId")
          val bank             = output.openingState.banks(bankId)
          val stocks           = bankStocks(bankId)
          val corpBondHoldings = CorporateBondOwnership.bankHolderFor(output.openingState.ledgerFinancialState, bank.id)
          val totalDebtService = flow.mortgageDebtService + flow.consumerDebtService
          rows += (
            HhOriginationRow(
              runId = config.runId,
              seed = seed,
              originationMonth = month,
              householdId = opening.id.toInt,
              bankId = bankId,
              status = householdStatus(opening.status),
              incomeDecile = incomeDeciles.getOrElse(opening.id.toInt, "D00"),
              openingFinancialDistressState = opening.financialDistressState.toString,
              closingFinancialDistressState = closing.financialDistressState.toString,
              openingDistressMonths = opening.financialDistressMonths,
              closingDistressMonths = closing.financialDistressMonths,
              wage = householdWage(opening.status),
              openingDemandDeposit = openingStock.demandDeposit,
              openingConsumerLoan = openingStock.consumerLoan,
              openingMortgageLoan = openingStock.mortgageLoan,
              monthlyIncome = flow.monthlyIncome,
              mortgageDebtService = flow.mortgageDebtService,
              consumerDebtService = flow.consumerDebtService,
              totalDebtService = totalDebtService,
              totalDsr = ratio(totalDebtService, flow.monthlyIncome),
              mortgageDsr = ratio(flow.mortgageDebtService, flow.monthlyIncome),
              consumerDsr = ratio(flow.consumerDebtService, flow.monthlyIncome),
              creditCapacity = flow.consumerCreditCapacity,
              creditAccessEligible = flow.consumerCreditAccessEligible,
              creditDemand = flow.consumerCreditDemand,
              approvedPrincipal = flow.consumerApprovedOrigination,
              rejectedPrincipal = flow.consumerRejectedOrigination,
              bankRejectedPrincipal = flow.consumerBankRejectedOrigination,
              bankApprovalProduct = flow.consumerBankApprovalProduct,
              bankRejectionReason = flow.consumerBankRejectionReason,
              bankApprovalProbability = flow.consumerBankApprovalProbability,
              bankApprovalRoll = flow.consumerBankApprovalRoll,
              bankProjectedCar = flow.consumerBankProjectedCar,
              bankMinCar = flow.consumerBankMinCar,
              bankManagementCarTarget = flow.consumerBankManagementCarTarget,
              bankCapitalThrottle = flow.consumerBankCapitalThrottle,
              bankApprovalLcr = flow.consumerBankLcr,
              bankApprovalNsfr = flow.consumerBankNsfr,
              bankCar = fixed(Banking.car(bank, stocks, corpBondHoldings)),
              bankLcr = fixed(Banking.lcr(stocks)),
              bankNsfr = fixed(Banking.nsfr(bank, stocks, corpBondHoldings)),
              sameMonthArrears = outcome.arrears,
              sameMonthDefault = outcome.defaultOrWriteOff,
              futureArrearsWithinWindow = false,
              futureDefaultWithinWindow = false,
              futureBankruptcyWithinWindow = false,
              firstFutureDefaultMonth = None,
              observedFutureMonths = 0,
              futureWriteOffAmount = PLN.Zero,
            ) -> month
          )

  private def collectFirmMonth(
      config: Config,
      seed: Long,
      output: McSeedMonth,
      rows: scala.collection.mutable.ArrayBuffer[(FirmOriginationRow, Int)],
      outcomes: scala.collection.mutable.ArrayBuffer[FirmMonthOutcome],
  )(using p: SimParams): Unit =
    val month           = output.executionMonth.toInt
    val openingFirms    = output.openingState.firms
    val openingBalances = output.openingState.ledgerFinancialState.firms
    val closingFirms    = output.state.firms
    val nBanks          = output.openingState.banks.length
    val bankStocks      = output.openingState.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks)
    require(openingFirms.length == openingBalances.length, "Loan origination firm diagnostics require aligned opening firm balances")

    closingFirms.foreach: firm =>
      outcomes += FirmMonthOutcome(
        month = month,
        firmId = firm.id.toInt,
        bankrupt = !Firm.isAlive(firm),
        bankruptcyReason = firm.tech match
          case TechState.Bankrupt(reason) => Some(reason)
          case _                          => None,
      )

    val openingById = openingFirms.iterator.map(firm => firm.id -> firm).toMap
    val balanceById = openingFirms.zip(openingBalances).map((firm, balances) => firm.id -> balances).toMap

    output.firmDecisionTraces.foreach: trace =>
      val creditLegs = firmCreditLegs(trace)
      if creditLegs.nonEmpty then
        val opening            = openingById.getOrElse(trace.firmId, throw IllegalStateException(s"Missing opening firm ${trace.firmId.toInt}"))
        val balances           = balanceById.getOrElse(trace.firmId, throw IllegalStateException(s"Missing opening firm balances ${trace.firmId.toInt}"))
        val bankId             = trace.bankId.toInt
        require(bankId >= 0 && bankId < nBanks, s"Firm ${trace.firmId.toInt} references invalid bank id $bankId")
        val bank               = output.openingState.banks(bankId)
        val stocks             = bankStocks(bankId)
        val corpBondHoldings   = CorporateBondOwnership.bankHolderFor(output.openingState.ledgerFinancialState, bank.id)
        val monthlyDebtService = trace.principalRepaid + trace.firmLoanBefore * trace.lendingRate.monthly
        creditLegs.foreach: leg =>
          val approved = leg.bankApproval.contains(true) || leg.approvedPrincipal > PLN.Zero
          val rejected = leg.bankApproval.contains(false)
          rows += (
            FirmOriginationRow(
              runId = config.runId,
              seed = seed,
              originationMonth = month,
              firmId = trace.firmId.toInt,
              bankId = bankId,
              sector = sectorName(opening),
              sizeClass = firmSizeClass(trace.workersBefore),
              techState = techState(trace.openingTech),
              decisionType = leg.decisionType,
              creditPurpose = leg.creditPurpose,
              bankApprovalObserved = leg.bankApproval.isDefined,
              bankApproved = approved,
              bankRejected = rejected,
              observedCreditNeed = leg.observedCreditNeed,
              approvedPrincipal = leg.approvedPrincipal.max(PLN.Zero),
              cashBefore = balances.cash,
              firmLoanBefore = balances.firmLoan,
              realizedPostTaxProfit = trace.signedRealizedPostTaxProfit,
              grossInvestment = trace.grossInvestment,
              principalRepaid = trace.principalRepaid,
              monthlyDebtServiceProxy = monthlyDebtService,
              debtToCash = ratio(balances.firmLoan, balances.cash.max(PLN(1))),
              dscrProxy = ratio(trace.signedRealizedPostTaxProfit, monthlyDebtService),
              workersBefore = trace.workersBefore,
              workersAfter = trace.workersAfter,
              foreignOwned = trace.foreignOwned,
              stateOwned = trace.stateOwned,
              bankCar = fixed(Banking.car(bank, stocks, corpBondHoldings)),
              bankLcr = fixed(Banking.lcr(stocks)),
              bankNsfr = fixed(Banking.nsfr(bank, stocks, corpBondHoldings)),
              sameMonthBankrupt = trace.closingTech match
                case _: TechState.Bankrupt => true
                case _                     => false,
              futureBankruptWithinWindow = false,
              firstFutureBankruptcyMonth = None,
              observedFutureMonths = 0,
              bankruptcyReason = trace.bankruptcyReason.map(bankruptcyReasonName).getOrElse(""),
            ) -> month
          )

  private def enrichHouseholdOutcome(
      config: Config,
      row: HhOriginationRow,
      originationMonth: Int,
      outcomes: Map[(Int, Int), HhMonthOutcome],
  ): HhOriginationRow =
    val future       = futureMonths(originationMonth, config).flatMap(month => outcomes.get(row.householdId -> month))
    val firstDefault = future.find(_.defaultOrWriteOff).map(_.month)
    row.copy(
      futureArrearsWithinWindow = future.exists(_.arrears),
      futureDefaultWithinWindow = firstDefault.isDefined,
      futureBankruptcyWithinWindow = future.exists(_.bankrupt),
      firstFutureDefaultMonth = firstDefault,
      observedFutureMonths = future.length,
      futureWriteOffAmount = future.foldLeft(PLN.Zero)((acc, outcome) => acc + outcome.consumerLoanDefault + outcome.liquidityBridgeChargeOff),
    )

  private def enrichFirmOutcome(
      config: Config,
      row: FirmOriginationRow,
      originationMonth: Int,
      outcomes: Map[(Int, Int), FirmMonthOutcome],
  ): FirmOriginationRow =
    val future          = futureMonths(originationMonth, config).flatMap(month => outcomes.get(row.firmId -> month))
    val firstBankruptcy = future.find(_.bankrupt)
    row.copy(
      futureBankruptWithinWindow = firstBankruptcy.isDefined,
      firstFutureBankruptcyMonth = firstBankruptcy.map(_.month),
      observedFutureMonths = future.length,
      bankruptcyReason =
        if row.bankruptcyReason.nonEmpty then row.bankruptcyReason else firstBankruptcy.flatMap(_.bankruptcyReason).map(bankruptcyReasonName).getOrElse(""),
    )

  private[diagnostics] def summarize(
      config: Config,
      householdRows: Vector[HhOriginationRow],
      firmRows: Vector[FirmOriginationRow],
  ): Vector[SummaryRow] =
    val accumulator = new LoanSummaryAccumulator
    householdRows.foreach(row => accumulator.add(LoanRow.Household(row)))
    firmRows.foreach(row => accumulator.add(LoanRow.Firm(row)))
    accumulator.summaryRows(config.runId)

  private def writeArtifactsZIO(
      config: Config,
      rowOutputPaths: Vector[Path],
      reportStats: ReportStats,
      summaryRows: Vector[SummaryRow],
  ): ZIO[Any, String, Vector[Path]] =
    val summaryTsvPath = config.runRoot.resolve("loan-origination-quality-summary.tsv")
    val reportMdPath   = config.runRoot.resolve("loan-origination-quality-report.md")
    for
      summaryPath <- McTsvFile.writeAll(summaryTsvPath, summaryRows, SummaryRowsTsvSchema)(DiagnosticIo.outputFailure)
      reportPath  <- DiagnosticIo.writeText(reportMdPath, renderReport(config, reportStats, summaryRows))
    yield rowOutputPaths ++ Vector(summaryPath, reportPath)

  private[diagnostics] val HouseholdRowsTsvSchema: McTsvSchema[HhOriginationRow] =
    McTsvSchema(
      header = HouseholdHeader,
      render = row =>
        Vector(
          text(row.runId),
          row.seed.toString,
          row.originationMonth.toString,
          row.householdId.toString,
          row.bankId.toString,
          text(row.status),
          row.incomeDecile,
          row.openingFinancialDistressState,
          row.closingFinancialDistressState,
          row.openingDistressMonths.toString,
          row.closingDistressMonths.toString,
          row.wage.format(2),
          row.openingDemandDeposit.format(2),
          row.openingConsumerLoan.format(2),
          row.openingMortgageLoan.format(2),
          row.monthlyIncome.format(2),
          row.mortgageDebtService.format(2),
          row.consumerDebtService.format(2),
          row.totalDebtService.format(2),
          row.totalDsr.map(renderDecimal).getOrElse(""),
          row.mortgageDsr.map(renderDecimal).getOrElse(""),
          row.consumerDsr.map(renderDecimal).getOrElse(""),
          row.creditCapacity.format(2),
          row.creditAccessEligible.toString,
          row.creditDemand.format(2),
          row.approvedPrincipal.format(2),
          row.rejectedPrincipal.format(2),
          row.bankRejectedPrincipal.format(2),
          text(row.bankApprovalProduct),
          text(row.bankRejectionReason),
          row.bankApprovalProbability.fold("")(_.format(6)),
          row.bankApprovalRoll.fold("")(_.format(6)),
          row.bankProjectedCar.fold("")(_.format(6)),
          row.bankMinCar.fold("")(_.format(6)),
          row.bankManagementCarTarget.fold("")(_.format(6)),
          row.bankCapitalThrottle.fold("")(_.format(6)),
          row.bankApprovalLcr.fold("")(_.format(6)),
          row.bankApprovalNsfr.fold("")(_.format(6)),
          renderDecimal(row.bankCar),
          renderDecimal(row.bankLcr),
          renderDecimal(row.bankNsfr),
          row.sameMonthArrears.toString,
          row.sameMonthDefault.toString,
          row.futureArrearsWithinWindow.toString,
          row.futureDefaultWithinWindow.toString,
          row.futureBankruptcyWithinWindow.toString,
          row.firstFutureDefaultMonth.fold("")(_.toString),
          row.observedFutureMonths.toString,
          row.futureWriteOffAmount.format(2),
        ).mkString("\t"),
    )

  private[diagnostics] val FirmRowsTsvSchema: McTsvSchema[FirmOriginationRow] =
    McTsvSchema(
      header = FirmHeader,
      render = row =>
        Vector(
          text(row.runId),
          row.seed.toString,
          row.originationMonth.toString,
          row.firmId.toString,
          row.bankId.toString,
          text(row.sector),
          row.sizeClass,
          row.techState,
          row.decisionType,
          row.creditPurpose,
          row.bankApprovalObserved.toString,
          row.bankApproved.toString,
          row.bankRejected.toString,
          row.observedCreditNeed.format(2),
          row.approvedPrincipal.format(2),
          row.cashBefore.format(2),
          row.firmLoanBefore.format(2),
          row.realizedPostTaxProfit.format(2),
          row.grossInvestment.format(2),
          row.principalRepaid.format(2),
          row.monthlyDebtServiceProxy.format(2),
          row.debtToCash.map(renderDecimal).getOrElse(""),
          row.dscrProxy.map(renderDecimal).getOrElse(""),
          row.workersBefore.toString,
          row.workersAfter.toString,
          row.foreignOwned.toString,
          row.stateOwned.toString,
          renderDecimal(row.bankCar),
          renderDecimal(row.bankLcr),
          renderDecimal(row.bankNsfr),
          row.sameMonthBankrupt.toString,
          row.futureBankruptWithinWindow.toString,
          row.firstFutureBankruptcyMonth.fold("")(_.toString),
          row.observedFutureMonths.toString,
          text(row.bankruptcyReason),
        ).mkString("\t"),
    )

  private[diagnostics] val SummaryRowsTsvSchema: McTsvSchema[SummaryRow] =
    McTsvSchema(
      header = SummaryHeader,
      render = row =>
        Vector(
          text(row.runId),
          row.borrowerType,
          row.cohortType,
          text(row.cohortValue),
          row.rows.toString,
          row.approvedRows.toString,
          row.rejectedRows.toString,
          row.approvedPrincipal.format(2),
          row.futureBadRows.toString,
          row.futureBadRate.map(renderDecimal).getOrElse(""),
          row.meanRiskRatio.map(renderDecimal).getOrElse(""),
          row.meanBankCar.map(renderDecimal).getOrElse(""),
          text(row.interpretation),
        ).mkString("\t"),
    )

  private[diagnostics] def renderHouseholdRows(rows: Vector[HhOriginationRow]): String =
    renderTsv(HouseholdRowsTsvSchema, rows)

  private[diagnostics] def renderFirmRows(rows: Vector[FirmOriginationRow]): String =
    renderTsv(FirmRowsTsvSchema, rows)

  private[diagnostics] def renderSummaryRows(rows: Vector[SummaryRow]): String =
    renderTsv(SummaryRowsTsvSchema, rows)

  private def renderTsv[A](schema: McTsvSchema[A], rows: Vector[A]): String =
    (schema.header +: rows.map(schema.render)).mkString("\n") + "\n"

  private def renderReport(
      config: Config,
      reportStats: ReportStats,
      summaryRows: Vector[SummaryRow],
  ): String =
    val hhBadRate   = reportStats.householdBadRate.map(renderDecimal).getOrElse("NA")
    val firmBadRate = reportStats.firmBadRate.map(renderDecimal).getOrElse("NA")
    val topRows     = summaryRows.sortBy(row => row.futureBadRate.getOrElse(BigDecimal(-1)))(using Ordering.BigDecimal.reverse).take(10)
    val table       =
      if topRows.isEmpty then "_No origination rows emitted in this fixture._"
      else
        val header = "| Borrower | Cohort | Value | Rows | Future bad rate |\n| --- | --- | --- | --- | --- |"
        val body   = topRows.map: row =>
          s"| ${row.borrowerType} | ${row.cohortType} | ${row.cohortValue} | ${row.rows} | ${row.futureBadRate.map(renderDecimal).getOrElse("NA")} |"
        (header +: body).mkString("\n")
    Vector(
      "# Loan Origination Quality Diagnostics",
      "",
      "Generated for issue #590.",
      "",
      s"- Run id: `${config.runId}`",
      s"- Seeds: `${config.seeds}` from `${config.seedStart}`",
      s"- Months: `${config.months}`",
      s"- Outcome window: `${config.outcomeWindow}` months after origination",
      s"""- Repeat command: `sbt "loanOriginationQuality --seed-start ${config.seedStart} --seeds ${config.seeds} --months ${config.months} --outcome-window ${config.outcomeWindow} --out ${config.out} --run-id ${config.runId}"`""",
      "",
      "## Outputs",
      "",
      "- `loan-origination-quality-households.tsv`: household consumer-credit demand/origination rows joined to later arrears/default/write-off outcomes.",
      "- `loan-origination-quality-firms.tsv`: firm credit-decision rows joined to later bankruptcy outcomes.",
      "- `loan-origination-quality-summary.tsv`: cohort-level default/bankruptcy rates.",
      "- `loan-origination-quality-report.md`: this summary.",
      "",
      "## Quick Read",
      "",
      s"- Household rows: `${reportStats.householdRows}`, future bad rate `${hhBadRate}`.",
      s"- Firm rows: `${reportStats.firmRows}`, future bad rate `${firmBadRate}`.",
      "",
      "## Highest Future-Bad Cohorts",
      "",
      table,
      "",
      "These are diagnostics, not parameter changes. They are meant to separate weak borrower underwriting from default/write-off calibration and bank-capital calibration.",
      "",
    ).mkString("\n")

  private def futureMonths(originationMonth: Int, config: Config): Vector[Int] =
    ((originationMonth + 1) to (originationMonth + config.outcomeWindow).min(config.months)).toVector

  private def householdCohorts(row: HhOriginationRow): Vector[(String, String)] =
    Vector(
      "IncomeDecile"       -> row.incomeDecile,
      "OpeningDistress"    -> row.openingFinancialDistressState,
      "TotalDsrBand"       -> dsrBand(row.totalDsr),
      "OpeningBankCarBand" -> carBand(row.bankCar),
    )

  private def firmCohorts(row: FirmOriginationRow): Vector[(String, String)] =
    Vector(
      "Sector"             -> row.sector,
      "SizeClass"          -> row.sizeClass,
      "TechState"          -> row.techState,
      "OpeningBankCarBand" -> carBand(row.bankCar),
    )

  private def householdCreditObserved(flow: Household.MonthlyFlow): Boolean =
    flow.consumerCreditDemand > PLN.Zero ||
      flow.consumerApprovedOrigination > PLN.Zero ||
      flow.consumerRejectedOrigination > PLN.Zero ||
      flow.consumerBankRejectedOrigination > PLN.Zero

  private def firmCreditLegs(trace: Firm.DecisionTrace): Vector[FirmCreditLeg] =
    Vector(techCreditLeg(trace), investmentCreditLeg(trace)).flatten

  private def techCreditLeg(trace: Firm.DecisionTrace): Option[FirmCreditLeg] =
    val need      = trace.techCreditNeed.max(trace.techCreditAmount)
    val principal = trace.techCreditAmount.max(PLN.Zero)
    if need <= PLN.Zero && principal <= PLN.Zero && trace.selectedBankApproval.isEmpty then None
    else
      Some(
        FirmCreditLeg(
          decisionType = trace.techCreditDecisionType.getOrElse(trace.decisionType).tsvValue,
          creditPurpose = techCreditPurpose(trace.techCreditDecisionType.getOrElse(trace.decisionType)),
          bankApproval = trace.selectedBankApproval,
          observedCreditNeed = need,
          approvedPrincipal = principal,
        ),
      )

  private def investmentCreditLeg(trace: Firm.DecisionTrace): Option[FirmCreditLeg] =
    val need      = trace.investmentCreditNeed.getOrElse(PLN.Zero)
    val principal = trace.investmentCreditAmount.getOrElse(PLN.Zero).max(PLN.Zero)
    if need <= PLN.Zero && principal <= PLN.Zero && trace.investmentBankApproval.isEmpty then None
    else
      Some(
        FirmCreditLeg(
          decisionType = trace.decisionType.tsvValue,
          creditPurpose = "physical-investment",
          bankApproval = trace.investmentBankApproval,
          observedCreditNeed = need.max(principal),
          approvedPrincipal = principal,
        ),
      )

  private def techCreditPurpose(decisionType: Firm.DecisionTrace.DecisionType): String =
    decisionType match
      case Firm.DecisionTrace.DecisionType.FullAiUpgrade => "full-ai-upgrade"
      case Firm.DecisionTrace.DecisionType.HybridUpgrade => "hybrid-upgrade"
      case Firm.DecisionTrace.DecisionType.UpgradeFailed => "failed-tech-upgrade"
      case _                                             => "firm-credit-decision"

  private def incomeDecileByHousehold(flows: Vector[Household.MonthlyFlow]): Map[Int, String] =
    if flows.isEmpty then Map.empty
    else
      flows
        .sortBy(flow => (flow.monthlyIncome.toLong, flow.householdId.toInt))
        .zipWithIndex
        .map: (flow, rank) =>
          val decile = math.min(10, (rank * 10) / flows.length + 1)
          flow.householdId.toInt -> f"D$decile%02d"
        .toMap

  private def householdStatus(status: HhStatus): String =
    status match
      case HhStatus.Employed(_, _, _)   => "Employed"
      case HhStatus.Unemployed(_)       => "Unemployed"
      case HhStatus.Retraining(_, _, _) => "Retraining"
      case HhStatus.Bankrupt            => "Bankrupt"

  private def householdWage(status: HhStatus): PLN =
    status match
      case HhStatus.Employed(_, _, wage) => wage
      case _                             => PLN.Zero

  private def sectorName(firm: Firm.State)(using p: SimParams): String =
    p.sectorDefs.lift(firm.sector.toInt).fold(s"sector-${firm.sector.toInt}")(_.name)

  private def techState(tech: TechState): String =
    tech match
      case TechState.Traditional(_) => "Traditional"
      case TechState.Hybrid(_, _)   => "Hybrid"
      case TechState.Automated(_)   => "Automated"
      case TechState.Bankrupt(_)    => "Bankrupt"

  private def firmSizeClass(workers: Int): String =
    workers match
      case size if size <= 9   => "Micro"
      case size if size <= 49  => "Small"
      case size if size <= 249 => "Medium"
      case _                   => "Large"

  private def dsrBand(value: Option[BigDecimal]): String =
    value match
      case None                              => "NA"
      case Some(v) if v < BigDecimal("0.20") => "lt20pct"
      case Some(v) if v < BigDecimal("0.40") => "20_40pct"
      case Some(v) if v < BigDecimal("0.60") => "40_60pct"
      case Some(v) if v < BigDecimal("1.00") => "60_100pct"
      case Some(_)                           => "100pct_plus"

  private def carBand(value: BigDecimal): String =
    if value < BigDecimal("0.08") then "lt8pct"
    else if value < BigDecimal("0.12") then "8_12pct"
    else if value < BigDecimal("0.16") then "12_16pct"
    else "16pct_plus"

  private def bankruptcyReasonName(reason: BankruptReason): String =
    reason match
      case BankruptReason.AiDebtTrap          => "AiDebtTrap"
      case BankruptReason.HybridInsolvency    => "HybridInsolvency"
      case BankruptReason.AiImplFailure       => "AiImplFailure"
      case BankruptReason.HybridImplFailure   => "HybridImplFailure"
      case BankruptReason.LaborCostInsolvency => "LaborCostInsolvency"
      case BankruptReason.Other(msg)          => s"Other(${text(msg)})"

  private def ratio(numerator: PLN, denominator: PLN): Option[BigDecimal] =
    if denominator <= PLN.Zero then None
    else Some((BigDecimal(numerator.toLong) / BigDecimal(denominator.toLong)).setScale(6, RoundingMode.HALF_UP))

  private def fixed(value: Multiplier): BigDecimal =
    (BigDecimal(value.toLong) / FixedPointBase.ScaleDecimal).setScale(6, RoundingMode.HALF_UP)

  private def renderDecimal(value: BigDecimal): String =
    value.setScale(6, RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros.toPlainString

  private def text(value: String): String =
    value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

  private def parseInt(value: String, flag: String): Either[String, Int] =
    Try(value.toInt).toOption.toRight(s"$flag must be an integer")

  private def parseLong(value: String, flag: String): Either[String, Long] =
    Try(value.toLong).toOption.toRight(s"$flag must be an integer")

  private def knownFlag(flag: String): Boolean =
    flag == "--seed-start" || flag == "--seeds" || flag == "--months" ||
      flag == "--outcome-window" || flag == "--run-id" || flag == "--out"

  private val usage =
    """Usage: LoanOriginationQualityExport [--seed-start <long>] [--seeds <int>] [--months <int>] [--outcome-window <int>] [--out <path>] [--run-id <id>]
      |
      |Runs an optional borrower-level diagnostic that links HH/Firm credit origination quality to later default outcomes.
      |""".stripMargin

end LoanOriginationQualityExport
