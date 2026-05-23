package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.accounting.{InitCheck, Sfc}
import com.boombustgroup.amorfati.agents.{Banking, BankruptReason, Firm, HhFinancialDistressState, HhStatus, Household, TechState}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.flows.FlowSimulation
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.engine.{MonthDriver, MonthRandomness}
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.init.{InitRandomness, WorldInit}
import com.boombustgroup.amorfati.types.*

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
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
      householdRows: Vector[HhOriginationRow],
      firmRows: Vector[FirmOriginationRow],
      summaryRows: Vector[SummaryRow],
  )

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

  private final case class SeedRawRows(
      householdRows: Vector[HhOriginationRow],
      firmRows: Vector[FirmOriginationRow],
  )

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
    ).mkString(";")

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
    ).mkString(";")

  private[diagnostics] val SummaryHeader: String =
    "RunId;BorrowerType;CohortType;CohortValue;Rows;ApprovedRows;RejectedRows;ApprovedPrincipal;FutureBadRows;FutureBadRate;MeanRiskRatio;MeanBankCar;Interpretation"

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
      val attempts = validConfig.seedRange.map(seed => runSeed(validConfig, seed))
      attempts.collectFirst { case Left(err) => err } match
        case Some(err) => Left(err)
        case None      =>
          val rawRows       = attempts.collect { case Right(rows) => rows }
          val householdRows = rawRows.flatMap(_.householdRows)
          val firmRows      = rawRows.flatMap(_.firmRows)
          val summaryRows   = summarize(validConfig, householdRows, firmRows)
          val paths         = writeArtifacts(validConfig, householdRows, firmRows, summaryRows)
          Right(ExportResult(paths, householdRows, firmRows, summaryRows))

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

  private def runSeed(config: Config, seed: Long): Either[String, SeedRawRows] =
    given SimParams = SimParams.defaults
    val init        = WorldInit.initialize(InitRandomness.Contract.fromSeed(seed))
    val initState   = FlowSimulation.SimState.fromInit(init)
    val runtime     = Sfc.RuntimeState(initState.world, initState.firms, initState.households, initState.banks, initState.ledgerFinancialState)
    val initErrors  = InitCheck.validate(runtime)
    if initErrors.nonEmpty then Left(s"Seed $seed failed init checks: ${initErrors.mkString("; ")}")
    else
      val rawHhEvents             = scala.collection.mutable.ArrayBuffer.empty[(HhOriginationRow, Int)]
      val rawFirmEvents           = scala.collection.mutable.ArrayBuffer.empty[(FirmOriginationRow, Int)]
      val hhOutcomes              = scala.collection.mutable.ArrayBuffer.empty[HhMonthOutcome]
      val firmOutcomes            = scala.collection.mutable.ArrayBuffer.empty[FirmMonthOutcome]
      val steps                   = MonthDriver
        .unfoldSteps(initState, traceFirmDecisions = true): state =>
          Some(MonthRandomness.Contract.fromSeed(runtimeRootSeed(seed, state)))
        .take(config.months)
      var failure: Option[String] = None

      while steps.hasNext && failure.isEmpty do
        val output = steps.next()
        output.sfcResult match
          case Left(errors) =>
            failure = Some(s"Seed $seed failed SFC at month ${output.executionMonth.toInt}: ${errors.mkString("; ")}")
          case Right(())    =>
            collectHouseholdMonth(config, seed, output, rawHhEvents, hhOutcomes)
            collectFirmMonth(config, seed, output, rawFirmEvents, firmOutcomes)

      failure match
        case Some(err) => Left(err)
        case None      =>
          val hhByKey   = hhOutcomes.toVector.groupBy(outcome => (outcome.householdId, outcome.month)).view.mapValues(_.head).toMap
          val firmByKey = firmOutcomes.toVector.groupBy(outcome => (outcome.firmId, outcome.month)).view.mapValues(_.head).toMap
          val hhRows    = rawHhEvents.toVector.map((row, month) => enrichHouseholdOutcome(config, row, month, hhByKey))
          val firmRows  = rawFirmEvents.toVector.map((row, month) => enrichFirmOutcome(config, row, month, firmByKey))
          Right(SeedRawRows(hhRows, firmRows))

  private def collectHouseholdMonth(
      config: Config,
      seed: Long,
      output: FlowSimulation.StepOutput,
      rows: scala.collection.mutable.ArrayBuffer[(HhOriginationRow, Int)],
      outcomes: scala.collection.mutable.ArrayBuffer[HhMonthOutcome],
  )(using p: SimParams): Unit =
    val month             = output.executionMonth.toInt
    val openingHouseholds = output.stateIn.households
    val openingBalances   = output.stateIn.ledgerFinancialState.households
    val closingHouseholds = output.householdSnapshotState.households
    val flows             = output.householdMonthlyFlows
    require(openingHouseholds.length == openingBalances.length, "Loan origination HH diagnostics require aligned opening household balances")
    require(openingHouseholds.length == closingHouseholds.length, "Loan origination HH diagnostics require aligned closing household rows")
    require(openingHouseholds.length == flows.length, "Loan origination HH diagnostics require aligned household flows")

    val incomeDeciles = incomeDecileByHousehold(flows)
    val nBanks        = output.stateIn.banks.length
    val bankStocks    = output.stateIn.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks)

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
          bankrupt = closing.status == HhStatus.Bankrupt,
        )
        outcomes += outcome

        if householdCreditObserved(flow) then
          val bankId           = opening.bankId.toInt
          require(bankId >= 0 && bankId < nBanks, s"Household ${opening.id.toInt} references invalid bank id $bankId")
          val bank             = output.stateIn.banks(bankId)
          val stocks           = bankStocks(bankId)
          val corpBondHoldings = CorporateBondOwnership.bankHolderFor(output.stateIn.ledgerFinancialState, bank.id)
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
      output: FlowSimulation.StepOutput,
      rows: scala.collection.mutable.ArrayBuffer[(FirmOriginationRow, Int)],
      outcomes: scala.collection.mutable.ArrayBuffer[FirmMonthOutcome],
  )(using p: SimParams): Unit =
    val month           = output.executionMonth.toInt
    val openingFirms    = output.stateIn.firms
    val openingBalances = output.stateIn.ledgerFinancialState.firms
    val closingFirms    = output.nextState.firms
    val nBanks          = output.stateIn.banks.length
    val bankStocks      = output.stateIn.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks)
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

    output.firmDecisionTraces
      .filter(firmCreditObserved)
      .foreach: trace =>
        val opening            = openingById.getOrElse(trace.firmId, throw IllegalStateException(s"Missing opening firm ${trace.firmId.toInt}"))
        val balances           = balanceById.getOrElse(trace.firmId, throw IllegalStateException(s"Missing opening firm balances ${trace.firmId.toInt}"))
        val bankId             = trace.bankId.toInt
        require(bankId >= 0 && bankId < nBanks, s"Firm ${trace.firmId.toInt} references invalid bank id $bankId")
        val bank               = output.stateIn.banks(bankId)
        val stocks             = bankStocks(bankId)
        val corpBondHoldings   = CorporateBondOwnership.bankHolderFor(output.stateIn.ledgerFinancialState, bank.id)
        val approvals          = firmApprovals(trace)
        val approved           = trace.newLoan > PLN.Zero || approvals.exists(identity)
        val rejected           = approvals.contains(false) || observedCreditNeed(trace) > trace.newLoan
        val monthlyDebtService = trace.principalRepaid + trace.firmLoanBefore * trace.lendingRate.monthly
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
            decisionType = trace.decisionType.csvValue,
            creditPurpose = creditPurpose(trace),
            bankApprovalObserved = approvals.nonEmpty,
            bankApproved = approved,
            bankRejected = rejected,
            observedCreditNeed = observedCreditNeed(trace),
            approvedPrincipal = trace.newLoan.max(PLN.Zero),
            cashBefore = balances.cash,
            firmLoanBefore = balances.firmLoan,
            realizedPostTaxProfit = trace.realizedPostTaxProfit,
            grossInvestment = trace.grossInvestment,
            principalRepaid = trace.principalRepaid,
            monthlyDebtServiceProxy = monthlyDebtService,
            debtToCash = ratio(balances.firmLoan, balances.cash.max(PLN(1))),
            dscrProxy = ratio(trace.realizedPostTaxProfit, monthlyDebtService),
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
    val hhGroups   = Vector(
      "IncomeDecile"       -> ((row: HhOriginationRow) => row.incomeDecile),
      "OpeningDistress"    -> ((row: HhOriginationRow) => row.openingFinancialDistressState),
      "TotalDsrBand"       -> ((row: HhOriginationRow) => dsrBand(row.totalDsr)),
      "OpeningBankCarBand" -> ((row: HhOriginationRow) => carBand(row.bankCar)),
    )
    val firmGroups = Vector(
      "Sector"             -> ((row: FirmOriginationRow) => row.sector),
      "SizeClass"          -> ((row: FirmOriginationRow) => row.sizeClass),
      "TechState"          -> ((row: FirmOriginationRow) => row.techState),
      "OpeningBankCarBand" -> ((row: FirmOriginationRow) => carBand(row.bankCar)),
    )
    hhGroups.flatMap((name, key) => summarizeHouseholds(config.runId, name, householdRows, key)) ++
      firmGroups.flatMap((name, key) => summarizeFirms(config.runId, name, firmRows, key))

  private def summarizeHouseholds(
      runId: String,
      cohortType: String,
      rows: Vector[HhOriginationRow],
      key: HhOriginationRow => String,
  ): Vector[SummaryRow] =
    rows
      .groupBy(key)
      .toVector
      .sortBy(_._1)
      .map: (cohortValue, cohortRows) =>
        val badRows = cohortRows.count(row => row.futureDefaultWithinWindow || row.futureBankruptcyWithinWindow)
        SummaryRow(
          runId = runId,
          borrowerType = "Household",
          cohortType = cohortType,
          cohortValue = cohortValue,
          rows = cohortRows.length,
          approvedRows = cohortRows.count(_.approvedPrincipal > PLN.Zero),
          rejectedRows = cohortRows.count(row => row.rejectedPrincipal > PLN.Zero || row.bankRejectedPrincipal > PLN.Zero),
          approvedPrincipal = cohortRows.foldLeft(PLN.Zero)(_ + _.approvedPrincipal),
          futureBadRows = badRows,
          futureBadRate = if cohortRows.nonEmpty then Some(BigDecimal(badRows) / BigDecimal(cohortRows.length)) else None,
          meanRiskRatio = mean(cohortRows.flatMap(_.totalDsr)),
          meanBankCar = mean(cohortRows.map(_.bankCar)),
          interpretation = "HH future bad rows mean later default/write-off or bankruptcy inside the configured observation window.",
        )

  private def summarizeFirms(
      runId: String,
      cohortType: String,
      rows: Vector[FirmOriginationRow],
      key: FirmOriginationRow => String,
  ): Vector[SummaryRow] =
    rows
      .groupBy(key)
      .toVector
      .sortBy(_._1)
      .map: (cohortValue, cohortRows) =>
        val badRows = cohortRows.count(row => row.futureBankruptWithinWindow || row.sameMonthBankrupt)
        SummaryRow(
          runId = runId,
          borrowerType = "Firm",
          cohortType = cohortType,
          cohortValue = cohortValue,
          rows = cohortRows.length,
          approvedRows = cohortRows.count(_.approvedPrincipal > PLN.Zero),
          rejectedRows = cohortRows.count(_.bankRejected),
          approvedPrincipal = cohortRows.foldLeft(PLN.Zero)(_ + _.approvedPrincipal),
          futureBadRows = badRows,
          futureBadRate = if cohortRows.nonEmpty then Some(BigDecimal(badRows) / BigDecimal(cohortRows.length)) else None,
          meanRiskRatio = mean(cohortRows.flatMap(_.dscrProxy)),
          meanBankCar = mean(cohortRows.map(_.bankCar)),
          interpretation = "Firm future bad rows mean later bankruptcy inside the configured observation window; mean risk ratio is DSCR proxy.",
        )

  private def writeArtifacts(
      config: Config,
      householdRows: Vector[HhOriginationRow],
      firmRows: Vector[FirmOriginationRow],
      summaryRows: Vector[SummaryRow],
  ): Vector[Path] =
    Files.createDirectories(config.runRoot)
    val hhPath      = config.runRoot.resolve("loan-origination-quality-households.csv")
    val firmPath    = config.runRoot.resolve("loan-origination-quality-firms.csv")
    val summaryPath = config.runRoot.resolve("loan-origination-quality-summary.csv")
    val reportPath  = config.runRoot.resolve("loan-origination-quality-report.md")
    Files.writeString(hhPath, renderHouseholdRows(householdRows), StandardCharsets.UTF_8)
    Files.writeString(firmPath, renderFirmRows(firmRows), StandardCharsets.UTF_8)
    Files.writeString(summaryPath, renderSummaryRows(summaryRows), StandardCharsets.UTF_8)
    Files.writeString(reportPath, renderReport(config, householdRows, firmRows, summaryRows), StandardCharsets.UTF_8)
    Vector(hhPath, firmPath, summaryPath, reportPath)

  private[diagnostics] def renderHouseholdRows(rows: Vector[HhOriginationRow]): String =
    val body = rows.map: row =>
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
      ).mkString(";")
    (HouseholdHeader +: body).mkString("", "\n", "\n")

  private[diagnostics] def renderFirmRows(rows: Vector[FirmOriginationRow]): String =
    val body = rows.map: row =>
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
      ).mkString(";")
    (FirmHeader +: body).mkString("", "\n", "\n")

  private[diagnostics] def renderSummaryRows(rows: Vector[SummaryRow]): String =
    val body = rows.map: row =>
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
      ).mkString(";")
    (SummaryHeader +: body).mkString("", "\n", "\n")

  private def renderReport(
      config: Config,
      householdRows: Vector[HhOriginationRow],
      firmRows: Vector[FirmOriginationRow],
      summaryRows: Vector[SummaryRow],
  ): String =
    val hhBad       = householdRows.count(row => row.futureDefaultWithinWindow || row.futureBankruptcyWithinWindow)
    val firmBad     = firmRows.count(row => row.futureBankruptWithinWindow || row.sameMonthBankrupt)
    val hhBadRate   = if householdRows.nonEmpty then renderDecimal(BigDecimal(hhBad) / BigDecimal(householdRows.length)) else "NA"
    val firmBadRate = if firmRows.nonEmpty then renderDecimal(BigDecimal(firmBad) / BigDecimal(firmRows.length)) else "NA"
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
      "- `loan-origination-quality-households.csv`: household consumer-credit demand/origination rows joined to later arrears/default/write-off outcomes.",
      "- `loan-origination-quality-firms.csv`: firm credit-decision rows joined to later bankruptcy outcomes.",
      "- `loan-origination-quality-summary.csv`: cohort-level default/bankruptcy rates.",
      "- `loan-origination-quality-report.md`: this summary.",
      "",
      "## Quick Read",
      "",
      s"- Household rows: `${householdRows.length}`, future bad rate `${hhBadRate}`.",
      s"- Firm rows: `${firmRows.length}`, future bad rate `${firmBadRate}`.",
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

  private def householdCreditObserved(flow: Household.MonthlyFlow): Boolean =
    flow.consumerCreditDemand > PLN.Zero ||
      flow.consumerApprovedOrigination > PLN.Zero ||
      flow.consumerRejectedOrigination > PLN.Zero ||
      flow.consumerBankRejectedOrigination > PLN.Zero

  private def firmCreditObserved(trace: Firm.DecisionTrace): Boolean =
    trace.newLoan > PLN.Zero ||
      trace.investmentCreditNeed.exists(_ > PLN.Zero) ||
      firmApprovals(trace).nonEmpty

  private def firmApprovals(trace: Firm.DecisionTrace): Vector[Boolean] =
    Vector(trace.selectedBankApproval, trace.fullAiBankApproval, trace.hybridBankApproval, trace.investmentBankApproval).flatten

  private def observedCreditNeed(trace: Firm.DecisionTrace): PLN =
    val investmentNeed = trace.investmentCreditNeed.getOrElse(PLN.Zero)
    investmentNeed.max(trace.newLoan)

  private def creditPurpose(trace: Firm.DecisionTrace): String =
    if trace.investmentCreditNeed.exists(_ > PLN.Zero) then "physical-investment"
    else
      trace.decisionType match
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

  private def mean(values: Vector[BigDecimal]): Option[BigDecimal] =
    if values.isEmpty then None
    else Some((values.sum / BigDecimal(values.length)).setScale(6, RoundingMode.HALF_UP))

  private def renderDecimal(value: BigDecimal): String =
    value.setScale(6, RoundingMode.HALF_UP).bigDecimal.stripTrailingZeros.toPlainString

  private def text(value: String): String =
    value.replace(';', ',').replace('\n', ' ').replace('\r', ' ')

  private def parseInt(value: String, flag: String): Either[String, Int] =
    Try(value.toInt).toOption.toRight(s"$flag must be an integer")

  private def parseLong(value: String, flag: String): Either[String, Long] =
    Try(value.toLong).toOption.toRight(s"$flag must be an integer")

  private def knownFlag(flag: String): Boolean =
    flag == "--seed-start" || flag == "--seeds" || flag == "--months" ||
      flag == "--outcome-window" || flag == "--run-id" || flag == "--out"

  private def runtimeRootSeed(seed: Long, state: FlowSimulation.SimState): Long =
    seed * 10000L + state.completedMonth.toLong

  private val usage =
    """Usage: LoanOriginationQualityExport [--seed-start <long>] [--seeds <int>] [--months <int>] [--outcome-window <int>] [--out <path>] [--run-id <id>]
      |
      |Runs an optional borrower-level diagnostic that links HH/Firm credit origination quality to later default outcomes.
      |""".stripMargin

end LoanOriginationQualityExport
