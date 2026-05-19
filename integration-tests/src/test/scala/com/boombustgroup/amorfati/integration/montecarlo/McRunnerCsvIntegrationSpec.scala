package com.boombustgroup.amorfati.integration.montecarlo

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.agents.HhStatus
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.montecarlo.{McFirmSnapshotSchedule, McHouseholdSnapshotSchedule, McRunConfig, McRunner, McTimeseriesSchema, RunResult, SimError}
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.{Runtime, Unsafe, ZIO}

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

class McRunnerCsvIntegrationSpec extends AnyFlatSpec with Matchers:

  given SimParams = SimParams.defaults

  private val DurationMonths     = 3
  private val Seeds              = Vector(1L, 2L)
  private val OutputPrefix       = "mc-it"
  private val RunId              = "csvspec"
  private val HhLiquidityHeader  =
    "HouseholdLiquidity_NetDemandDeposit;HouseholdLiquidity_PositiveDemandDeposits;HouseholdLiquidity_ImplicitOverdraft;HouseholdLiquidity_NegativeDepositCount;HouseholdLiquidity_NegativeDepositShare;HouseholdLiquidity_MinDemandDeposit;HouseholdLiquidity_DepositP01;HouseholdLiquidity_DepositP05;HouseholdLiquidity_DepositP10;HouseholdLiquidity_DepositP25;HouseholdLiquidity_DepositP50;HouseholdLiquidity_DepositP75;HouseholdLiquidity_DepositP90;HouseholdLiquidity_DepositP95;HouseholdLiquidity_DepositP99"
  private val ExpectedHhHeader   =
    "Seed;HH_Employed;HH_Unemployed;HH_Retraining;HH_Bankrupt;MeanMonthlyIncome;MeanEmployedWage;WageP10;WageP50;WageP90;MeanSavings;MedianSavings;Gini_Individual;Gini_Wealth;MeanSkill;MeanHealthPenalty;RetrainingAttempts;RetrainingSuccesses;ConsumptionP10;ConsumptionP50;ConsumptionP90;BankruptcyRate;MeanMonthsToRuin;PovertyRate_50pct;PovertyRate_30pct;" +
      HhLiquidityHeader
  private val ExpectedBankHeader =
    "Seed;BankId;Deposits;Loans;Capital;NPL;CAR;GovBonds;InterbankNet;Failed"
  private val ExpectedFirmHeader =
    "Seed;Firm_Living;FirmSize_Micro;FirmSize_Small;FirmSize_Medium;FirmSize_Large;FirmSize_MicroShare;FirmSize_SmallShare;FirmSize_MediumShare;FirmSize_LargeShare"
  private val ExpectedFirmSnapshotHeader =
    "RunId;Seed;Month;FirmId;Sector;Region;SizeClass;Workers;TechState;BankruptcyReason;DigitalReadiness;Cash;FirmLoan;Equity;BankId;RiskProfile;InitialSize;CapitalStock;Inventory;GreenCapital;ForeignOwned;StateOwned"
  private val ExpectedHouseholdSnapshotHeader =
    "RunId;Seed;Month;HouseholdId;Status;Region;ContractType;BankId;Wage;Rent;MPC;Skill;HealthPenalty;FinancialDistressMonths;DemandDeposit;MortgageLoan;ConsumerLoan;Equity;PositiveDeposit;ImplicitOverdraft;NetLiquidPosition;NetFinancialPosition;OpeningDemandDeposit;OpeningConsumerLoan;MonthlyIncome;Consumption;RentPaid;MortgageDebtService;ConsumerApprovedOrigination;LiquidityShortfallFinancing;ConsumerDebtService;ConsumerDefault;ConsumerPrincipal;ClosingConsumerLoan"
  private val ExpectedHouseholdShortfallCohortHeader =
    "RunId;Seed;Month;Dimension;Cohort;HouseholdCount;ShortfallHouseholdCount;ShortfallHouseholdShare;LiquidityShortfallFinancing;ShortfallShareOfMonth;ConsumerApprovedOrigination;ConsumerDebtService;ConsumerDefault;ConsumerPrincipal;OpeningDemandDeposit;ClosingDemandDeposit;OpeningConsumerLoan;ClosingConsumerLoan;MonthlyIncome;Consumption;Rent;MortgageDebtService;RentToIncome;MortgageDebtServiceToIncome;ConsumerDebtServiceToIncome;ClosingConsumerLoanToIncome"

  private def rc =
    McRunConfig(
      nSeeds = Seeds.length,
      outputPrefix = OutputPrefix,
      runDurationMonths = DurationMonths,
      runId = RunId,
    )

  private def filePrefix(rc: McRunConfig) =
    s"${rc.outputPrefix}_${rc.runId}_${rc.runDurationMonths}m"

  private def seedFileName(seed: Long, rc: McRunConfig) =
    f"${filePrefix(rc)}_seed${seed}%03d.csv"

  private def unsafeRun[A](zio: ZIO[Any, SimError, A]): A =
    Unsafe.unsafe:
      implicit unsafe =>
        Runtime.default.unsafe.run(zio.either).getOrThrowFiberFailure() match
          case Right(value) => value
          case Left(err)    => fail(err.toString)

  private def unsafeRunEither[A](zio: ZIO[Any, SimError, A]): Either[SimError, A] =
    Unsafe.unsafe:
      implicit unsafe =>
        Runtime.default.unsafe.run(zio.either).getOrThrowFiberFailure()

  private def withTempDir[A](f: Path => A): A =
    val dir = Files.createTempDirectory("mc-runner-it-")
    try f(dir)
    finally deleteRecursively(dir)

  private def deleteRecursively(dir: Path): Unit =
    if Files.exists(dir) then
      Using.resource(Files.walk(dir)): stream =>
        stream.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)

  private def readLines(path: Path): Vector[String] =
    Files.readAllLines(path).asScala.toVector

  private def parseCsvRow(line: String): Vector[BigDecimal] =
    line.split(';').toVector.map(value => BigDecimal(value.replace(',', '.')))

  private def expectedRun(seed: Long): RunResult =
    McRunner.runSingle(seed, DurationMonths).fold(err => fail(err.toString), identity)

  private def expectedHhRow(seed: Long, result: RunResult): String =
    val a             = result.terminalState.householdAggregates
    val households    = result.terminalState.households
    val employedWages = households
      .flatMap: household =>
        household.status match
          case HhStatus.Employed(_, _, wage) => Some(wage)
          case _                             => None
      .sorted
    val meanMonthlyIncome = if households.nonEmpty then a.totalIncome.divideBy(households.length) else PLN.Zero
    val meanEmployedWage  = if employedWages.nonEmpty then employedWages.sumPln.divideBy(employedWages.length) else PLN.Zero
    s"$seed;${a.employed};${a.unemployed};${a.retraining};${a.bankrupt};" +
      s"${meanMonthlyIncome.format(2)};${meanEmployedWage.format(2)};" +
      s"${percentile(employedWages, Share.decimal(10, 2)).format(2)};" +
      s"${percentile(employedWages, Share.decimal(50, 2)).format(2)};" +
      s"${percentile(employedWages, Share.decimal(90, 2)).format(2)};" +
      s"${a.meanSavings.format(2)};${a.medianSavings.format(2)};" +
      s"${a.giniIndividual.format(6)};${a.giniWealth.format(6)};" +
      s"${a.meanSkill.format(6)};${a.meanHealthPenalty.format(6)};" +
      s"${a.retrainingAttempts};${a.retrainingSuccesses};" +
      s"${a.consumptionP10.format(2)};${a.consumptionP50.format(2)};${a.consumptionP90.format(2)};" +
      s"${a.bankruptcyRate.format(6)};" +
      s"${a.meanMonthsToRuin.format(2)};" +
      s"${a.povertyRate50.format(6)};${a.povertyRate30.format(6)};" +
      expectedHhLiquiditySuffix(result)

  private def expectedHhLiquiditySuffix(result: RunResult): String =
    val deposits      = result.terminalState.ledgerFinancialState.households.map(_.demandDeposit).sorted
    val net           = deposits.sumPln
    val positive      = deposits.filter(_ > PLN.Zero).sumPln
    val overdraft     = deposits.filter(_ < PLN.Zero).foldLeft(PLN.Zero)((acc, deposit) => acc - deposit)
    val negativeCount = deposits.count(_ < PLN.Zero)
    val negativeShare = if deposits.nonEmpty then Share.fraction(negativeCount, deposits.length) else Share.Zero
    val minDeposit    = deposits.headOption.getOrElse(PLN.Zero)

    Vector(
      net.format(2),
      positive.format(2),
      overdraft.format(2),
      negativeCount.toString,
      negativeShare.format(6),
      minDeposit.format(2),
      percentile(deposits, Share.decimal(1, 2)).format(2),
      percentile(deposits, Share.decimal(5, 2)).format(2),
      percentile(deposits, Share.decimal(10, 2)).format(2),
      percentile(deposits, Share.decimal(25, 2)).format(2),
      percentile(deposits, Share.decimal(50, 2)).format(2),
      percentile(deposits, Share.decimal(75, 2)).format(2),
      percentile(deposits, Share.decimal(90, 2)).format(2),
      percentile(deposits, Share.decimal(95, 2)).format(2),
      percentile(deposits, Share.decimal(99, 2)).format(2),
    ).mkString(";")

  private def percentile(values: Vector[PLN], p: Share): PLN =
    if values.isEmpty then PLN.Zero
    else
      val idx = Math.min(values.length - 1, (values.length.toLong * p.toLong / com.boombustgroup.amorfati.fp.FixedPointBase.Scale).toInt)
      values(idx)

  private def expectedBankRows(seed: Long, result: RunResult): Vector[String] =
    result.terminalState.banks.map: bank =>
      val balances = result.terminalState.ledgerFinancialState.banks(bank.id.toInt)
      val stocks   = LedgerFinancialState.projectBankFinancialStocks(balances)
      s"$seed;${bank.id};" +
        s"${stocks.totalDeposits.format(2)};${stocks.firmLoan.format(2)};${bank.capital.format(2)};" +
        s"${Banking.nplRatio(bank, stocks).format(6)};${Banking.car(bank, stocks, balances.corpBond).format(6)};" +
        s"${Banking.govBondHoldings(stocks).format(2)};${stocks.interbankLoan.format(2)};" +
        s"${bank.failed}"

  "runZIO".should("write deterministic per-seed and summary CSV files").in {
    withTempDir { outputDir =>
      unsafeRun(McRunner.runZIO(rc, outputDir.toFile))

      val expectedRuns      = Seeds.map(seed => seed -> expectedRun(seed)).toMap
      val expectedFileNames = (
        Seeds.map(seed => seedFileName(seed, rc)) :+
          s"${filePrefix(rc)}_hh.csv" :+
          s"${filePrefix(rc)}_banks.csv" :+
          s"${filePrefix(rc)}_firms.csv"
      ).toSet

      Using.resource(Files.list(outputDir)): stream =>
        stream.iterator.asScala.map(_.getFileName.toString).toSet.shouldBe(expectedFileNames)

      for seed <- Seeds do
        val path   = outputDir.resolve(seedFileName(seed, rc))
        val lines  = readLines(path)
        val result = expectedRuns(seed)

        lines.head.shouldBe(McTimeseriesSchema.colNames.mkString(";"))
        lines.length.shouldBe(DurationMonths + 1)

        for (line, monthIndex) <- lines.tail.zipWithIndex do
          val actual   = parseCsvRow(line)
          val month    = ExecutionMonth.First.advanceBy(monthIndex)
          val expected = result.timeSeries.monthRow(month)

          actual.length.shouldBe(McTimeseriesSchema.nCols)

          for col <- 0 until McTimeseriesSchema.nCols do
            withClue(s"seed=$seed month=${month.toInt} col=$col: ") {
              actual(col).shouldBe(decimal(expected(col)) +- BigDecimal("1e-6"))
            }

      val hhLines = readLines(outputDir.resolve(s"${filePrefix(rc)}_hh.csv"))
      hhLines.head.shouldBe(ExpectedHhHeader)
      hhLines.length.shouldBe(Seeds.length + 1)
      hhLines.tail.shouldBe(Seeds.map(seed => expectedHhRow(seed, expectedRuns(seed))))

      val bankLines = readLines(outputDir.resolve(s"${filePrefix(rc)}_banks.csv"))
      bankLines.head.shouldBe(ExpectedBankHeader)
      bankLines.length.shouldBe(1 + expectedRuns.valuesIterator.map(_.terminalState.banks.length).sum)
      bankLines.tail.shouldBe(Seeds.flatMap(seed => expectedBankRows(seed, expectedRuns(seed))))

      val firmLines = readLines(outputDir.resolve(s"${filePrefix(rc)}_firms.csv"))
      firmLines.head.shouldBe(ExpectedFirmHeader)
      firmLines.length.shouldBe(Seeds.length + 1)
      firmLines.tail.zip(Seeds).foreach: (line, seed) =>
        withClue(s"seed=$seed firms summary: ") {
          val fields = line.split(';').toVector
          fields.length.shouldBe(ExpectedFirmHeader.split(';').length)
          fields.head.shouldBe(seed.toString)
        }
    }
  }

  it.should("write terminal firm snapshots aligned with terminal firm-size counts when enabled").in {
    withTempDir { outputDir =>
      val snapshotRc = McRunConfig(
        nSeeds = 1,
        outputPrefix = "mc-it-snap",
        runDurationMonths = DurationMonths,
        runId = "snap",
        firmSnapshotSchedule = McFirmSnapshotSchedule.TerminalOnly,
      )

      unsafeRun(McRunner.runZIO(snapshotRc, outputDir.toFile))

      val expectedRun = McRunner.runSingle(1L, DurationMonths).fold(err => fail(err.toString), identity)
      val snapshotLines = readLines(outputDir.resolve(s"${filePrefix(snapshotRc)}_firm_snapshots.csv"))
      val snapshotHeader = snapshotLines.head.split(';').toVector
      snapshotLines.head.shouldBe(ExpectedFirmSnapshotHeader)
      snapshotLines.tail.size.shouldBe(expectedRun.terminalState.firms.length)

      val monthIdx = snapshotHeader.indexOf("Month")
      monthIdx should be >= 0
      snapshotLines.tail.map(_.split(';')(monthIdx)).toSet.shouldBe(Set(DurationMonths.toString))

      val sizeClassIdx = snapshotHeader.indexOf("SizeClass")
      val techStateIdx = snapshotHeader.indexOf("TechState")
      sizeClassIdx should be >= 0
      techStateIdx should be >= 0
      val snapshotCounts = snapshotLines.tail
        .map(_.split(';').toVector)
        .filter(row => row(techStateIdx) != "Bankrupt")
        .groupBy(row => row(sizeClassIdx))
        .view
        .mapValues(_.size)
        .toMap

      val firmSummaryLine = readLines(outputDir.resolve(s"${filePrefix(snapshotRc)}_firms.csv"))(1)
      val summaryFields   = ExpectedFirmHeader.split(';').toVector.zip(firmSummaryLine.split(';').toVector).toMap

      snapshotCounts.getOrElse("Micro", 0).shouldBe(summaryFields("FirmSize_Micro").toInt)
      snapshotCounts.getOrElse("Small", 0).shouldBe(summaryFields("FirmSize_Small").toInt)
      snapshotCounts.getOrElse("Medium", 0).shouldBe(summaryFields("FirmSize_Medium").toInt)
      snapshotCounts.getOrElse("Large", 0).shouldBe(summaryFields("FirmSize_Large").toInt)
      snapshotCounts.values.sum.shouldBe(summaryFields("Firm_Living").toInt)
    }
  }

  it.should("write terminal household snapshots with inspectable demand deposits when enabled").in {
    withTempDir { outputDir =>
      val snapshotRc = McRunConfig(
        nSeeds = 1,
        outputPrefix = "mc-it-hh-snap",
        runDurationMonths = DurationMonths,
        runId = "hhsnap",
        householdSnapshotSchedule = McHouseholdSnapshotSchedule.TerminalOnly,
      )

      unsafeRun(McRunner.runZIO(snapshotRc, outputDir.toFile))

      val snapshotLines = readLines(outputDir.resolve(s"${filePrefix(snapshotRc)}_household_snapshots.csv"))
      val header        = snapshotLines.head.split(';').toVector
      snapshotLines.head.shouldBe(ExpectedHouseholdSnapshotHeader)
      snapshotLines.tail should not be empty

      val monthIdx   = header.indexOf("Month")
      val hhIdIdx    = header.indexOf("HouseholdId")
      val depositIdx = header.indexOf("DemandDeposit")
      monthIdx should be >= 0
      hhIdIdx should be >= 0
      depositIdx should be >= 0

      snapshotLines.tail.map(_.split(';')(monthIdx)).toSet.shouldBe(Set(DurationMonths.toString))

      snapshotLines.tail.foreach: line =>
        val fields = line.split(';').toVector
        fields.length.shouldBe(ExpectedHouseholdSnapshotHeader.split(';').length)
        fields(hhIdIdx).toInt should be >= 0
        BigDecimal(fields(depositIdx)) should be >= BigDecimal(0)

      val cohortLines = readLines(outputDir.resolve(s"${filePrefix(snapshotRc)}_household_shortfall_cohorts.csv"))
      val cohortHeader = cohortLines.head.split(';').toVector
      cohortLines.head.shouldBe(ExpectedHouseholdShortfallCohortHeader)
      cohortLines.tail should not be empty

      val dimensionIdx = cohortHeader.indexOf("Dimension")
      val cohortIdx = cohortHeader.indexOf("Cohort")
      val countIdx = cohortHeader.indexOf("HouseholdCount")
      val shareIdx = cohortHeader.indexOf("ShortfallShareOfMonth")
      dimensionIdx should be >= 0
      cohortIdx should be >= 0
      countIdx should be >= 0
      shareIdx should be >= 0

      val allRows = cohortLines.tail.map(_.split(';').toVector).filter(row => row(dimensionIdx) == "All")
      allRows.map(row => row(cohortIdx)).toSet.shouldBe(Set("All"))
      allRows.foreach: row =>
        row(countIdx).toInt should be > 0
        BigDecimal(row(shareIdx)) should be >= BigDecimal(0)
        BigDecimal(row(shareIdx)) should be <= BigDecimal(1)
    }
  }

  it.should("surface a typed error when the output location is not a directory").in {
    withTempDir { tempDir =>
      val outputFile = tempDir.resolve("not-a-directory")
      Files.writeString(outputFile, "occupied")

      unsafeRunEither(McRunner.runZIO(rc, outputFile.toFile)) match
        case Left(SimError.OutputFailure(operation, path, details)) =>
          operation.shouldBe("prepare output directory")
          path.shouldBe(outputFile.toFile.getPath)
          details.should(include("not a directory"))
        case other                                                 =>
          fail(s"expected typed output failure, got: $other")
    }
  }
