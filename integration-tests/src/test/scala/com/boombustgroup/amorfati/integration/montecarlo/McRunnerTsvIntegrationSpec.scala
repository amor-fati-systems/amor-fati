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

class McRunnerTsvIntegrationSpec extends AnyFlatSpec with Matchers:

  given SimParams = SimParams.defaults

  private val DurationMonths     = 3
  private val Seeds              = Vector(1L, 2L)
  private val OutputPrefix       = "mc-it"
  private val RunId              = "tsvspec"
  private val HhLiquidityColumns = columns(
    """
      HouseholdLiquidity_NetDemandDeposit
      HouseholdLiquidity_PositiveDemandDeposits
      HouseholdLiquidity_ImplicitOverdraft
      HouseholdLiquidity_NegativeDepositCount
      HouseholdLiquidity_NegativeDepositShare
      HouseholdLiquidity_MinDemandDeposit
      HouseholdLiquidity_DepositP01
      HouseholdLiquidity_DepositP05
      HouseholdLiquidity_DepositP10
      HouseholdLiquidity_DepositP25
      HouseholdLiquidity_DepositP50
      HouseholdLiquidity_DepositP75
      HouseholdLiquidity_DepositP90
      HouseholdLiquidity_DepositP95
      HouseholdLiquidity_DepositP99
    """,
  )
  private val HhDistressColumns  = columns(
    """
      HH_Distress_Current
      HH_Distress_LiquidityStress
      HH_Distress_Arrears
      HH_Distress_Restructuring
      HH_Distress_Defaulted
      HH_Distress_Bankruptcy
      HH_Distress_ActiveShare
    """,
  )
  private val ExpectedHhHeader   = tsv(
    columns(
      """
        Seed
        HH_Employed
        HH_Unemployed
        HH_Retraining
        HH_Bankrupt
      """,
    ) ++ HhDistressColumns ++ columns(
      """
        MeanMonthlyIncome
        MeanEmployedWage
        WageP10
        WageP50
        WageP90
        MeanSavings
        MedianSavings
        Gini_Individual
        Gini_Wealth
        MeanSkill
        MeanHealthPenalty
        RetrainingAttempts
        RetrainingSuccesses
        ConsumptionP10
        ConsumptionP50
        ConsumptionP90
        BankruptcyRate
        MeanMonthsToRuin
        PovertyRate_50pct
        PovertyRate_30pct
      """,
    ) ++ HhLiquidityColumns,
  )
  private val ExpectedBankHeader = tsv(
    columns(
      """
        Seed
        BankId
        Deposits
        Loans
        Capital
        NPL
        CAR
        GovBonds
        InterbankNet
        Failed
      """,
    ),
  )
  private val ExpectedFirmHeader = tsv(
    columns(
      """
        Seed
        Firm_Living
        FirmSize_Micro
        FirmSize_Small
        FirmSize_Medium
        FirmSize_Large
        FirmSize_MicroShare
        FirmSize_SmallShare
        FirmSize_MediumShare
        FirmSize_LargeShare
      """,
    ),
  )
  private val ExpectedFirmSnapshotHeader = tsvHeader(
    """
      RunId
      Seed
      Month
      FirmId
      Sector
      Region
      SizeClass
      Workers
      TechState
      BankruptcyReason
      DigitalReadiness
      Cash
      FirmLoan
      Equity
      BankId
      RiskProfile
      InitialSize
      CapitalStock
      Inventory
      GreenCapital
      ForeignOwned
      StateOwned
    """,
  )
  private val ConsumerBankPortfolioColumns = columns(
    """
      ConsumerBankPortfolioRiskAdjustedLoanReturn
      ConsumerBankPortfolioRiskAdjustedBondReturn
      ConsumerBankPortfolioWedge
      ConsumerBankPortfolioExpectedLossComponent
      ConsumerBankPortfolioCapitalComponent
      ConsumerBankPortfolioLevyComponent
      ConsumerBankPortfolioFundingComponent
      ConsumerBankPortfolioPriceShare
      ConsumerBankPortfolioPriceContribution
      ConsumerBankPortfolioQuantityThrottle
    """,
  )
  private val ExpectedHouseholdSnapshotHeader = tsv(
    columns(
      """
        RunId
        Seed
        Month
        HouseholdId
        Status
        Region
        ContractType
        BankId
        Wage
        Rent
        MPC
        Skill
        HealthPenalty
        FinancialDistressMonths
        FinancialDistressState
        DemandDeposit
        MortgageLoan
        ConsumerLoan
        Equity
        PositiveDeposit
        ImplicitOverdraft
        NetLiquidPosition
        NetFinancialPosition
        OpeningDemandDeposit
        OpeningConsumerLoan
        MonthlyIncome
        Consumption
        UnmetBasicConsumption
        DiscretionaryConsumptionCompression
        RentPaid
        MortgageDebtService
        ConsumerApprovedOrigination
        ConsumerCreditDemand
        ConsumerRejectedOrigination
        ConsumerBankRejectedOrigination
        ConsumerBankApprovalProduct
        ConsumerBankRejectionReason
        ConsumerBankApprovalProbability
        ConsumerBankApprovalRoll
        ConsumerBankProjectedCAR
        ConsumerBankMinCAR
        ConsumerBankManagementCAR
        ConsumerBankCapitalThrottle
        ConsumerBankLCR
        ConsumerBankNSFR
      """,
    ) ++ ConsumerBankPortfolioColumns ++ columns(
      """
        LiquidityShortfallFinancing
        ConsumptionShortfall
        RentArrears
        MortgageArrears
        ConsumerDebtArrears
        TemporaryOverdraft
        ConsumerDebtService
        ConsumerDefault
        ConsumerLoanDefault
        LiquidityBridgeChargeOff
        ConsumerPrincipal
        ClosingConsumerLoan
      """,
    ),
  )
  private val ExpectedHouseholdShortfallCohortHeader = tsvHeader(
    """
      RunId
      Seed
      Month
      Dimension
      Cohort
      HouseholdCount
      ShortfallHouseholdCount
      ShortfallHouseholdShare
      LiquidityShortfallFinancing
      ShortfallShareOfMonth
      ConsumptionShortfall
      RentArrears
      MortgageArrears
      ConsumerDebtArrears
      TemporaryOverdraft
      ConsumerApprovedOrigination
      ConsumerCreditDemand
      ConsumerRejectedOrigination
      ConsumerBankRejectedOrigination
      ConsumerDebtService
      ConsumerDefault
      ConsumerLoanDefault
      LiquidityBridgeChargeOff
      ConsumerPrincipal
      OpeningDemandDeposit
      ClosingDemandDeposit
      OpeningConsumerLoan
      ClosingConsumerLoan
      MonthlyIncome
      Consumption
      UnmetBasicConsumption
      DiscretionaryConsumptionCompression
      Rent
      MortgageDebtService
      RentToIncome
      MortgageDebtServiceToIncome
      ConsumerDebtServiceToIncome
      ClosingConsumerLoanToIncome
    """,
  )

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
    f"${filePrefix(rc)}_seed${seed}%03d.tsv"

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

  private def columns(raw: String): Vector[String] =
    raw.linesIterator.map(_.trim).filter(_.nonEmpty).toVector

  private def tsv(values: Iterable[String]): String =
    values.mkString("\t")

  private def tsvHeader(raw: String): String =
    tsv(columns(raw))

  private def splitTsv(line: String): Vector[String] =
    line.split("\t", -1).toVector

  private def parseTsvRow(line: String): Vector[BigDecimal] =
    splitTsv(line).map(value => BigDecimal(value.replace(',', '.')))

  private def columnIndex(header: Vector[String], name: String): Int =
    val idx = header.indexOf(name)
    idx should be >= 0
    idx

  private def assertBankCapitalWaterfall(header: Vector[String], row: Vector[BigDecimal]): Unit =
    val openingIdx       = columnIndex(header, "BankCapital_Opening")
    val closingIdx       = columnIndex(header, "BankCapital_Closing")
    val deltaIdx         = columnIndex(header, "BankCapital_Delta")
    val retainedIdx      = columnIndex(header, "BankCapital_RetainedIncome")
    val realizedIdx      = columnIndex(header, "BankCapital_RealizedCreditLoss")
    val firmLossIdx      = columnIndex(header, "BankCapital_FirmNplLoss")
    val mortgageLossIdx  = columnIndex(header, "BankCapital_MortgageNplLoss")
    val consumerLossIdx  = columnIndex(header, "BankCapital_ConsumerNplLoss")
    val corpBondLossIdx  = columnIndex(header, "BankCapital_CorpBondDefaultLoss")
    val interbankLossIdx = columnIndex(header, "BankCapital_InterbankContagionLoss")
    val bfgLevyIdx       = columnIndex(header, "BankCapital_BfgLevy")
    val polishLevyIdx    = columnIndex(header, "BankCapital_PolishBankLevyTax")
    val unrealizedIdx    = columnIndex(header, "BankCapital_UnrealizedBondLoss")
    val htmIdx           = columnIndex(header, "BankCapital_HtmRealizedLoss")
    val eclIdx           = columnIndex(header, "BankCapital_EclProvisionChange")
    val eclOpeningIdx    = columnIndex(header, "BankEcl_OpeningAllowance")
    val eclClosingIdx    = columnIndex(header, "BankEcl_ClosingAllowance")
    val eclBaselineIdx   = columnIndex(header, "BankEcl_BaselineStage1Allowance")
    val eclExcessIdx     = columnIndex(header, "BankEcl_ExcessAllowance")
    val eclExcessShareIdx = columnIndex(header, "BankEcl_ExcessAllowanceShare")
    val eclStage2ShareIdx = columnIndex(header, "BankEcl_Stage2Share")
    val eclStage3ShareIdx = columnIndex(header, "BankEcl_Stage3Share")
    val eclMigrationIdx  = columnIndex(header, "BankEcl_MigrationRate")
    val destructionIdx   = columnIndex(header, "BankCapital_CapitalDestruction")
    val reconcileIdx     = columnIndex(header, "BankCapital_ReconciliationResidual")
    val residualIdx      = columnIndex(header, "BankCapital_WaterfallResidual")
    val bailInIdx        = columnIndex(header, "BailInLoss")
    val bankBailInIdx    = columnIndex(header, "BankCapital_DepositBailInLoss")
    val newFailuresIdx   = columnIndex(header, "BankCapital_NewFailures")
    val negCapitalIdx    = columnIndex(header, "BankFailure_NewNegativeCapital")
    val carBreachIdx     = columnIndex(header, "BankFailure_NewCarBreach")
    val liquidityIdx     = columnIndex(header, "BankFailure_NewLiquidityBreach")
    val fallbackIdx      = columnIndex(header, "BankFailure_AllFailedFallback")
    val invariantIdx     = columnIndex(header, "BankFailure_InvariantViolation")
    val firstReasonIdx   = columnIndex(header, "BankFailure_FirstNewReasonCode")
    val firstBankIdx     = columnIndex(header, "BankFailure_FirstNewBankId")
    val reconTargetIdx   = columnIndex(header, "BankReconciliation_TargetBankId")
    val reconCapIdx      = columnIndex(header, "BankReconciliation_CapitalResidual")
    val reconBeforeIdx   = columnIndex(header, "BankReconciliation_TargetCapitalBefore")
    val reconAfterIdx    = columnIndex(header, "BankReconciliation_TargetCapitalAfter")
    val reconRatioIdx    = columnIndex(header, "BankReconciliation_ResidualToTargetCapital")
    val reconMaterialIdx = columnIndex(header, "BankReconciliation_MaterialResidual")
    val reconCrossedIdx  = columnIndex(header, "BankReconciliation_CrossedFailureThreshold")
    val reconReasonIdx   = columnIndex(header, "BankReconciliation_PostResidualReasonCode")
    val realizedCapitalIdx = columnIndex(header, "BankCreditLoss_RealizedToOpeningCapital")
    val firmDefaultRateIdx = columnIndex(header, "BankCreditLoss_FirmDefaultRate")
    val firmLossRateIdx = columnIndex(header, "BankCreditLoss_FirmLossRate")
    val mortgageDefaultRateIdx = columnIndex(header, "BankCreditLoss_MortgageDefaultRate")
    val mortgageLossRateIdx = columnIndex(header, "BankCreditLoss_MortgageLossRate")
    val consumerDefaultRateIdx = columnIndex(header, "BankCreditLoss_ConsumerLoanDefaultRate")
    val bridgeChargeOffRateIdx = columnIndex(header, "BankCreditLoss_LiquidityBridgeChargeOffRate")
    val consumerLossRateIdx = columnIndex(header, "BankCreditLoss_ConsumerLossRate")
    val corpBondDefaultRateIdx = columnIndex(header, "BankCreditLoss_CorpBondDefaultRate")
    val corpBondLossRateIdx = columnIndex(header, "BankCreditLoss_CorpBondLossRate")
    val realizedCredit   =
      row(firmLossIdx) + row(mortgageLossIdx) + row(consumerLossIdx) + row(corpBondLossIdx)
    val expectedDelta    =
      row(retainedIdx) - realizedCredit - row(interbankLossIdx) - row(bfgLevyIdx) - row(polishLevyIdx) -
        row(unrealizedIdx) - row(htmIdx) - row(eclIdx) - row(destructionIdx)
    val observedDelta    = row(closingIdx) - row(openingIdx)
    val expectedResidual = row(deltaIdx) - expectedDelta - row(reconcileIdx)

    row(deltaIdx) shouldBe observedDelta +- BigDecimal("0.05")
    row(realizedIdx) shouldBe realizedCredit +- BigDecimal("0.05")
    row(residualIdx) shouldBe expectedResidual +- BigDecimal("0.05")
    row(bankBailInIdx) shouldBe row(bailInIdx) +- BigDecimal("0.05")
    row(eclOpeningIdx) should be >= BigDecimal(0)
    row(eclClosingIdx) should be >= BigDecimal(0)
    row(eclBaselineIdx) should be >= BigDecimal(0)
    row(eclExcessIdx) should be >= BigDecimal(0)
    row(eclClosingIdx) - row(eclOpeningIdx) shouldBe row(eclIdx) +- BigDecimal("0.05")
    row(eclExcessShareIdx) should (be >= BigDecimal(0) and be <= BigDecimal(1))
    row(eclStage2ShareIdx) should (be >= BigDecimal(0) and be <= BigDecimal(1))
    row(eclStage3ShareIdx) should (be >= BigDecimal(0) and be <= BigDecimal(1))
    row(eclMigrationIdx) should (be >= BigDecimal(0) and be <= BigDecimal(1))
    row(newFailuresIdx) should be >= BigDecimal(0)
    row(negCapitalIdx) should be >= BigDecimal(0)
    row(carBreachIdx) should be >= BigDecimal(0)
    row(liquidityIdx) should be >= BigDecimal(0)
    row(fallbackIdx) should (be >= BigDecimal(0) and be <= BigDecimal(1))
    row(invariantIdx) should be >= BigDecimal(0)
    row(firstReasonIdx) should (be >= BigDecimal(0) and be <= BigDecimal(5))
    row(firstBankIdx) should be >= BigDecimal(-1)
    row(reconTargetIdx) should be >= BigDecimal(-1)
    row(reconAfterIdx) shouldBe row(reconBeforeIdx) + row(reconCapIdx) +- BigDecimal("0.05")
    row(reconRatioIdx) should be >= BigDecimal(0)
    row(reconMaterialIdx) should (be >= BigDecimal(0) and be <= BigDecimal(1))
    row(reconCrossedIdx) should (be >= BigDecimal(0) and be <= BigDecimal(1))
    row(reconReasonIdx) should (be >= BigDecimal(0) and be <= BigDecimal(5))
    if row(reconCrossedIdx) == BigDecimal(1) then row(reconReasonIdx) should be > BigDecimal(0)
    Vector(
      realizedCapitalIdx,
      firmDefaultRateIdx,
      firmLossRateIdx,
      mortgageDefaultRateIdx,
      mortgageLossRateIdx,
      consumerDefaultRateIdx,
      bridgeChargeOffRateIdx,
      consumerLossRateIdx,
      corpBondDefaultRateIdx,
      corpBondLossRateIdx,
    ).foreach(idx => row(idx) should be >= BigDecimal(0))

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
    tsv(
      Vector(
        seed.toString,
        a.employed.toString,
        a.unemployed.toString,
        a.retraining.toString,
        a.bankrupt.toString,
        a.distressCurrent.toString,
        a.distressLiquidityStress.toString,
        a.distressArrears.toString,
        a.distressRestructuring.toString,
        a.distressDefaulted.toString,
        a.distressBankruptcy.toString,
        a.distressActiveShare(households.length).format(6),
        meanMonthlyIncome.format(2),
        meanEmployedWage.format(2),
        percentile(employedWages, Share.decimal(10, 2)).format(2),
        percentile(employedWages, Share.decimal(50, 2)).format(2),
        percentile(employedWages, Share.decimal(90, 2)).format(2),
        a.meanSavings.format(2),
        a.medianSavings.format(2),
        a.giniIndividual.format(6),
        a.giniWealth.format(6),
        a.meanSkill.format(6),
        a.meanHealthPenalty.format(6),
        a.retrainingAttempts.toString,
        a.retrainingSuccesses.toString,
        a.consumptionP10.format(2),
        a.consumptionP50.format(2),
        a.consumptionP90.format(2),
        a.bankruptcyRate.format(6),
        a.meanMonthsToRuin.format(2),
        a.povertyRate50.format(6),
        a.povertyRate30.format(6),
      ) ++ expectedHhLiquidityFields(result),
    )

  private def expectedHhLiquidityFields(result: RunResult): Vector[String] =
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
    )

  private def percentile(values: Vector[PLN], p: Share): PLN =
    if values.isEmpty then PLN.Zero
    else
      val idx = Math.min(values.length - 1, (values.length.toLong * p.toLong / com.boombustgroup.amorfati.fp.FixedPointBase.Scale).toInt)
      values(idx)

  private def expectedBankRows(seed: Long, result: RunResult): Vector[String] =
    result.terminalState.banks.map: bank =>
      val balances = result.terminalState.ledgerFinancialState.banks(bank.id.toInt)
      val stocks   = LedgerFinancialState.projectBankFinancialStocks(balances)
      tsv(
        Vector(
          seed.toString,
          bank.id.toString,
          stocks.totalDeposits.format(2),
          stocks.firmLoan.format(2),
          bank.capital.format(2),
          Banking.nplRatio(bank, stocks).format(6),
          Banking.car(bank, stocks, balances.corpBond).format(6),
          Banking.govBondHoldings(stocks).format(2),
          stocks.interbankLoan.format(2),
          bank.failed.toString,
        ),
      )

  "runZIO".should("write deterministic per-seed and summary TSV files").in {
    withTempDir { outputDir =>
      unsafeRun(McRunner.runZIO(rc, outputDir.toFile))

      val expectedRuns      = Seeds.map(seed => seed -> expectedRun(seed)).toMap
      val expectedFileNames = (
        Seeds.map(seed => seedFileName(seed, rc)) :+
          s"${filePrefix(rc)}_hh.tsv" :+
          s"${filePrefix(rc)}_banks.tsv" :+
          s"${filePrefix(rc)}_firms.tsv"
      ).toSet

      Using.resource(Files.list(outputDir)): stream =>
        stream.iterator.asScala.map(_.getFileName.toString).toSet.shouldBe(expectedFileNames)

      for seed <- Seeds do
        val path   = outputDir.resolve(seedFileName(seed, rc))
        val lines  = readLines(path)
        val result = expectedRuns(seed)
        val header = splitTsv(lines.head)

        lines.head.shouldBe(McTimeseriesSchema.colNames.mkString("\t"))
        lines.length.shouldBe(DurationMonths + 1)

        for (line, monthIndex) <- lines.tail.zipWithIndex do
          val actual   = parseTsvRow(line)
          val month    = ExecutionMonth.First.advanceBy(monthIndex)
          val expected = result.timeSeries.monthRow(month)

          actual.length.shouldBe(McTimeseriesSchema.nCols)

          for col <- 0 until McTimeseriesSchema.nCols do
            withClue(s"seed=$seed month=${month.toInt} col=$col: ") {
              actual(col).shouldBe(decimal(expected(col)) +- BigDecimal("1e-6"))
            }
          withClue(s"seed=$seed month=${month.toInt} bank capital waterfall: ") {
            assertBankCapitalWaterfall(header, actual)
          }

      val hhLines = readLines(outputDir.resolve(s"${filePrefix(rc)}_hh.tsv"))
      hhLines.head.shouldBe(ExpectedHhHeader)
      hhLines.length.shouldBe(Seeds.length + 1)
      hhLines.tail.shouldBe(Seeds.map(seed => expectedHhRow(seed, expectedRuns(seed))))

      val bankLines = readLines(outputDir.resolve(s"${filePrefix(rc)}_banks.tsv"))
      bankLines.head.shouldBe(ExpectedBankHeader)
      bankLines.length.shouldBe(1 + expectedRuns.valuesIterator.map(_.terminalState.banks.length).sum)
      bankLines.tail.shouldBe(Seeds.flatMap(seed => expectedBankRows(seed, expectedRuns(seed))))

      val firmLines = readLines(outputDir.resolve(s"${filePrefix(rc)}_firms.tsv"))
      firmLines.head.shouldBe(ExpectedFirmHeader)
      firmLines.length.shouldBe(Seeds.length + 1)
      firmLines.tail.zip(Seeds).foreach: (line, seed) =>
        withClue(s"seed=$seed firms summary: ") {
          val fields = splitTsv(line)
          fields.length.shouldBe(splitTsv(ExpectedFirmHeader).length)
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
      val snapshotLines = readLines(outputDir.resolve(s"${filePrefix(snapshotRc)}_firm_snapshots.tsv"))
      val snapshotHeader = splitTsv(snapshotLines.head)
      snapshotLines.head.shouldBe(ExpectedFirmSnapshotHeader)
      snapshotLines.tail.size.shouldBe(expectedRun.terminalState.firms.length)

      val monthIdx = snapshotHeader.indexOf("Month")
      monthIdx should be >= 0
      snapshotLines.tail.map(line => splitTsv(line)(monthIdx)).toSet.shouldBe(Set(DurationMonths.toString))

      val sizeClassIdx = snapshotHeader.indexOf("SizeClass")
      val techStateIdx = snapshotHeader.indexOf("TechState")
      sizeClassIdx should be >= 0
      techStateIdx should be >= 0
      val snapshotCounts = snapshotLines.tail
        .map(splitTsv)
        .filter(row => row(techStateIdx) != "Bankrupt")
        .groupBy(row => row(sizeClassIdx))
        .view
        .mapValues(_.size)
        .toMap

      val firmSummaryLine = readLines(outputDir.resolve(s"${filePrefix(snapshotRc)}_firms.tsv"))(1)
      val summaryFields   = splitTsv(ExpectedFirmHeader).zip(splitTsv(firmSummaryLine)).toMap

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

      val snapshotLines = readLines(outputDir.resolve(s"${filePrefix(snapshotRc)}_household_snapshots.tsv"))
      val header        = splitTsv(snapshotLines.head)
      snapshotLines.head.shouldBe(ExpectedHouseholdSnapshotHeader)
      snapshotLines.tail should not be empty

      val monthIdx   = header.indexOf("Month")
      val hhIdIdx    = header.indexOf("HouseholdId")
      val depositIdx = header.indexOf("DemandDeposit")
      monthIdx should be >= 0
      hhIdIdx should be >= 0
      depositIdx should be >= 0

      snapshotLines.tail.map(line => splitTsv(line)(monthIdx)).toSet.shouldBe(Set(DurationMonths.toString))

      snapshotLines.tail.foreach: line =>
        val fields = splitTsv(line)
        fields.length.shouldBe(splitTsv(ExpectedHouseholdSnapshotHeader).length)
        fields(hhIdIdx).toInt should be >= 0
        BigDecimal(fields(depositIdx)) should be >= BigDecimal(0)

      val cohortLines = readLines(outputDir.resolve(s"${filePrefix(snapshotRc)}_household_shortfall_cohorts.tsv"))
      val cohortHeader = splitTsv(cohortLines.head)
      cohortLines.head.shouldBe(ExpectedHouseholdShortfallCohortHeader)
      cohortLines.tail should not be empty

      val dimensionIdx = cohortHeader.indexOf("Dimension")
      val cohortIdx = cohortHeader.indexOf("Cohort")
      val countIdx = cohortHeader.indexOf("HouseholdCount")
      val shareIdx = cohortHeader.indexOf("ShortfallShareOfMonth")
      val householdShortfallIdx = cohortHeader.indexOf("LiquidityShortfallFinancing")
      val consumptionShortfallIdx = cohortHeader.indexOf("ConsumptionShortfall")
      val rentArrearsIdx = cohortHeader.indexOf("RentArrears")
      val mortgageArrearsIdx = cohortHeader.indexOf("MortgageArrears")
      val consumerDebtArrearsIdx = cohortHeader.indexOf("ConsumerDebtArrears")
      val temporaryOverdraftIdx = cohortHeader.indexOf("TemporaryOverdraft")
      val consumerCreditDemandIdx = cohortHeader.indexOf("ConsumerCreditDemand")
      val consumerRejectedOriginationIdx = cohortHeader.indexOf("ConsumerRejectedOrigination")
      val consumerBankRejectedOriginationIdx = cohortHeader.indexOf("ConsumerBankRejectedOrigination")
      val unmetBasicConsumptionIdx = cohortHeader.indexOf("UnmetBasicConsumption")
      val discretionaryCompressionIdx = cohortHeader.indexOf("DiscretionaryConsumptionCompression")
      val consumerDefaultIdx = cohortHeader.indexOf("ConsumerDefault")
      val consumerLoanDefaultIdx = cohortHeader.indexOf("ConsumerLoanDefault")
      val liquidityBridgeChargeOffIdx = cohortHeader.indexOf("LiquidityBridgeChargeOff")
      dimensionIdx should be >= 0
      cohortIdx should be >= 0
      countIdx should be >= 0
      shareIdx should be >= 0
      householdShortfallIdx should be >= 0
      consumptionShortfallIdx should be >= 0
      rentArrearsIdx should be >= 0
      mortgageArrearsIdx should be >= 0
      consumerDebtArrearsIdx should be >= 0
      temporaryOverdraftIdx should be >= 0
      consumerCreditDemandIdx should be >= 0
      consumerRejectedOriginationIdx should be >= 0
      consumerBankRejectedOriginationIdx should be >= 0
      unmetBasicConsumptionIdx should be >= 0
      discretionaryCompressionIdx should be >= 0
      consumerDefaultIdx should be >= 0
      consumerLoanDefaultIdx should be >= 0
      liquidityBridgeChargeOffIdx should be >= 0

      val allRows = cohortLines.tail.map(splitTsv).filter(row => row(dimensionIdx) == "All")
      allRows.map(row => row(cohortIdx)).toSet.shouldBe(Set("All"))
      allRows.foreach: row =>
        row(countIdx).toInt should be > 0
        BigDecimal(row(shareIdx)) should be >= BigDecimal(0)
        BigDecimal(row(shareIdx)) should be <= BigDecimal(1)
        BigDecimal(row(consumptionShortfallIdx)) should be >= BigDecimal(0)
        BigDecimal(row(rentArrearsIdx)) should be >= BigDecimal(0)
        BigDecimal(row(mortgageArrearsIdx)) should be >= BigDecimal(0)
        BigDecimal(row(consumerDebtArrearsIdx)) should be >= BigDecimal(0)
        BigDecimal(row(temporaryOverdraftIdx)) should be >= BigDecimal(0)
        BigDecimal(row(consumerCreditDemandIdx)) should be >= BigDecimal(0)
        BigDecimal(row(consumerRejectedOriginationIdx)) should be >= BigDecimal(0)
        BigDecimal(row(consumerBankRejectedOriginationIdx)) should be >= BigDecimal(0)
        BigDecimal(row(consumerBankRejectedOriginationIdx)) should be <= BigDecimal(row(consumerRejectedOriginationIdx))
        BigDecimal(row(unmetBasicConsumptionIdx)) should be >= BigDecimal(0)
        BigDecimal(row(discretionaryCompressionIdx)) should be >= BigDecimal(0)
        BigDecimal(row(consumerDefaultIdx)) shouldBe
          BigDecimal(row(consumerLoanDefaultIdx)) + BigDecimal(row(liquidityBridgeChargeOffIdx)) +- BigDecimal("0.05")
        val componentSum =
          BigDecimal(row(consumptionShortfallIdx)) +
            BigDecimal(row(rentArrearsIdx)) +
            BigDecimal(row(mortgageArrearsIdx)) +
            BigDecimal(row(consumerDebtArrearsIdx)) +
            BigDecimal(row(temporaryOverdraftIdx))
        BigDecimal(row(householdShortfallIdx)) shouldBe componentSum +- BigDecimal("0.05")
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
