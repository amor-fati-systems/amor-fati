package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues

class HhBankLeadLagDiagnosticsExportSpec extends AnyFlatSpec with Matchers with OptionValues:

  private given SimParams = SimParams.defaults

  "HhBankLeadLagDiagnosticsExport.parseArgs" should "parse run controls" in {
    val parsed = HhBankLeadLagDiagnosticsExport.parseArgs(
      Vector("--seed-start", "3", "--seeds", "4", "--months", "24", "--lag-max", "3", "--out", "target/x", "--run-id", "review"),
    )

    parsed.map(config => (config.seedStart, config.seeds, config.months, config.lagMax, config.out.toString, config.runId)) shouldBe
      Right((3L, 4, 24, 3, "target/x", "review"))
  }

  it should "reject negative lag windows" in {
    HhBankLeadLagDiagnosticsExport.validate(HhBankLeadLagDiagnosticsExport.Config(lagMax = -1)).left.toOption.value should include("--lag-max")
  }

  "HhBankLeadLagDiagnosticsExport.pearson" should "return a finite correlation when both sides vary" in {
    HhBankLeadLagDiagnosticsExport.pearson(Vector(BigDecimal(1) -> BigDecimal(2), BigDecimal(2) -> BigDecimal(4), BigDecimal(3) -> BigDecimal(6))) shouldBe
      Some(BigDecimal("1.000000"))
  }

  it should "return None when a side is constant" in {
    HhBankLeadLagDiagnosticsExport.pearson(Vector(BigDecimal(1) -> BigDecimal(2), BigDecimal(1) -> BigDecimal(4))) shouldBe None
  }

  "HhBankLeadLagDiagnosticsExport.consumerNplLoss" should "weight ordinary defaults and insolvencies with distinct recoveries" in {
    HhBankLeadLagDiagnosticsExport.consumerNplLoss(
      consumerLoanDefault = PLN(120),
      consumerInsolvencyDefault = PLN(20),
    ) shouldBe PLN(104)
  }

  "HhBankLeadLagDiagnosticsExport.computeCorrelations" should "join household metrics at t-lag to bank outcomes at t" in {
    val rows = Vector(
      row(month = 1, hhConsumerLoanDefault = BigDecimal(10), bankConsumerNplLoss = BigDecimal(0)),
      row(month = 2, hhConsumerLoanDefault = BigDecimal(20), bankConsumerNplLoss = BigDecimal(10)),
      row(month = 3, hhConsumerLoanDefault = BigDecimal(30), bankConsumerNplLoss = BigDecimal(20)),
    )

    val correlations = HhBankLeadLagDiagnosticsExport.computeCorrelations(HhBankLeadLagDiagnosticsExport.Config(lagMax = 1), rows)
    val lagOne       = correlations
      .find(row =>
        row.scenarioId == "baseline" && row.lagMonths == 1 &&
          row.hhMetric == "HhConsumerLoanDefault" && row.bankMetric == "BankConsumerNplLoss",
      )
      .value

    lagOne.observations shouldBe 2
    lagOne.correlation shouldBe Some(BigDecimal("1.000000"))
  }

  "HhBankLeadLagDiagnosticsExport.summarizeCounterfactuals" should "separate baseline failures from consumer-loss counterfactual failures" in {
    val rows = Vector(
      row(month = 1, hhConsumerLoanDefault = BigDecimal(10), bankConsumerNplLoss = BigDecimal(8)),
      row(month = 2, hhConsumerLoanDefault = BigDecimal(20), bankConsumerNplLoss = BigDecimal(16), bankNewFailure = 1, bankFailed = 1),
      row("no-consumer-npl-capital-hit", "No consumer NPL capital hit", month = 1, hhConsumerLoanDefault = BigDecimal(10), bankConsumerNplLoss = BigDecimal(0)),
      row("no-consumer-npl-capital-hit", "No consumer NPL capital hit", month = 2, hhConsumerLoanDefault = BigDecimal(20), bankConsumerNplLoss = BigDecimal(0)),
    )

    val summary = HhBankLeadLagDiagnosticsExport.summarizeCounterfactuals(HhBankLeadLagDiagnosticsExport.Config(seeds = 1, months = 2), rows)

    summary.find(_.scenarioId == "baseline").value.failedSeeds shouldBe 1
    summary.find(_.scenarioId == "no-consumer-npl-capital-hit").value.failedSeeds shouldBe 0
  }

  "HhBankLeadLagDiagnosticsExport.summarizeConcentrationPeaks" should "locate peak per-bank household default-flow concentration" in {
    val rows = Vector(
      row(
        month = 1,
        bankId = 0,
        bankCount = 2,
        hhConsumerLoanDefault = BigDecimal(10),
        hhConsumerLoanDefaultShare = BigDecimal("0.25"),
        bankConsumerNplLoss = BigDecimal(8),
      ),
      row(
        month = 1,
        bankId = 1,
        bankCount = 2,
        hhConsumerLoanDefault = BigDecimal(30),
        hhConsumerLoanDefaultShare = BigDecimal("0.75"),
        bankConsumerNplLoss = BigDecimal(24),
      ),
      row(
        month = 2,
        bankId = 0,
        bankCount = 2,
        hhConsumerLoanDefault = BigDecimal(45),
        hhConsumerLoanDefaultShare = BigDecimal("0.90"),
        bankConsumerNplLoss = BigDecimal(36),
      ),
      row(
        month = 2,
        bankId = 1,
        bankCount = 2,
        hhConsumerLoanDefault = BigDecimal(5),
        hhConsumerLoanDefaultShare = BigDecimal("0.10"),
        bankConsumerNplLoss = BigDecimal(4),
      ),
    )

    val peaks = HhBankLeadLagDiagnosticsExport.summarizeConcentrationPeaks(HhBankLeadLagDiagnosticsExport.Config(seeds = 1, months = 2), rows)
    val peak  = peaks.find(_.metric == "ConsumerLoanDefaultFlowShare").value

    peak.peakMonth shouldBe 2
    peak.bankId shouldBe 0
    peak.topShare shouldBe BigDecimal("0.90")
    peak.amount shouldBe BigDecimal(45)
    peak.denominator shouldBe BigDecimal(50)
  }

  it should "skip zero-denominator months and use deterministic concentration tie-breaks" in {
    val rows = Vector(
      row(
        month = 1,
        bankId = 0,
        bankCount = 2,
        hhConsumerLoanDefault = BigDecimal(0),
        hhConsumerLoanDefaultShare = BigDecimal(1),
        bankConsumerNplLoss = BigDecimal(0),
        bankDeposits = BigDecimal(0),
        bankDepositShare = Some(BigDecimal(0)),
      ),
      row(
        month = 1,
        bankId = 1,
        bankCount = 2,
        hhConsumerLoanDefault = BigDecimal(0),
        hhConsumerLoanDefaultShare = BigDecimal(1),
        bankConsumerNplLoss = BigDecimal(0),
        bankDeposits = BigDecimal(0),
        bankDepositShare = Some(BigDecimal(0)),
      ),
      row(
        month = 2,
        bankId = 0,
        bankCount = 2,
        hhConsumerLoanDefault = BigDecimal(50),
        hhConsumerLoanDefaultShare = BigDecimal("0.50"),
        bankConsumerNplLoss = BigDecimal(40),
        bankDeposits = BigDecimal(10),
        bankDepositShare = Some(BigDecimal("0.25")),
      ),
      row(
        month = 2,
        bankId = 1,
        bankCount = 2,
        hhConsumerLoanDefault = BigDecimal(50),
        hhConsumerLoanDefaultShare = BigDecimal("0.50"),
        bankConsumerNplLoss = BigDecimal(40),
        bankDeposits = BigDecimal(30),
        bankDepositShare = Some(BigDecimal("0.75")),
      ),
      row(
        month = 4,
        bankId = 0,
        bankCount = 2,
        hhConsumerLoanDefault = BigDecimal(75),
        hhConsumerLoanDefaultShare = BigDecimal("0.50"),
        bankConsumerNplLoss = BigDecimal(60),
        bankDeposits = BigDecimal(20),
        bankDepositShare = Some(BigDecimal("0.50")),
        bankCapitalBuffer = BigDecimal(30),
      ),
      row(
        month = 4,
        bankId = 1,
        bankCount = 2,
        hhConsumerLoanDefault = BigDecimal(75),
        hhConsumerLoanDefaultShare = BigDecimal("0.50"),
        bankConsumerNplLoss = BigDecimal(60),
        bankDeposits = BigDecimal(20),
        bankDepositShare = Some(BigDecimal("0.50")),
        bankCapitalBuffer = BigDecimal(10),
      ),
      row(
        month = 5,
        bankId = 0,
        bankCount = 2,
        hhConsumerLoanDefault = BigDecimal(75),
        hhConsumerLoanDefaultShare = BigDecimal("0.50"),
        bankConsumerNplLoss = BigDecimal(60),
        bankDeposits = BigDecimal(20),
        bankDepositShare = Some(BigDecimal("0.50")),
        bankCapitalBuffer = BigDecimal(20),
      ),
      row(
        month = 5,
        bankId = 1,
        bankCount = 2,
        hhConsumerLoanDefault = BigDecimal(75),
        hhConsumerLoanDefaultShare = BigDecimal("0.50"),
        bankConsumerNplLoss = BigDecimal(60),
        bankDeposits = BigDecimal(20),
        bankDepositShare = Some(BigDecimal("0.50")),
        bankCapitalBuffer = BigDecimal(20),
      ),
    )

    val peaks       = HhBankLeadLagDiagnosticsExport.summarizeConcentrationPeaks(HhBankLeadLagDiagnosticsExport.Config(seeds = 1, months = 5), rows)
    val defaultPeak = peaks.find(_.metric == "ConsumerLoanDefaultFlowShare").value

    defaultPeak.peakMonth shouldBe 4
    defaultPeak.bankId shouldBe 0
    defaultPeak.topShare shouldBe BigDecimal("0.50")
    defaultPeak.amount shouldBe BigDecimal(75)
    defaultPeak.denominator shouldBe BigDecimal(150)

    val depositPeak = peaks.find(_.metric == "DepositShare").value
    depositPeak.peakMonth shouldBe 2
    depositPeak.bankId shouldBe 1
    depositPeak.topShare shouldBe BigDecimal("0.75")
    depositPeak.amount shouldBe BigDecimal(30)
    depositPeak.denominator shouldBe BigDecimal(40)

    val bufferPeak = peaks.find(_.metric == "CapitalBufferShare").value
    bufferPeak.peakMonth shouldBe 4
    bufferPeak.bankId shouldBe 0
    bufferPeak.topShare shouldBe BigDecimal("0.75")
    bufferPeak.amount shouldBe BigDecimal(30)
    bufferPeak.denominator shouldBe BigDecimal(40)
  }

  private def row(
      scenarioId: String = "baseline",
      scenarioLabel: String = "Baseline",
      seed: Long = 1L,
      month: Int,
      bankId: Int = 0,
      bankCount: Int = 1,
      hhConsumerLoanDefault: BigDecimal,
      hhConsumerLoanDefaultShare: BigDecimal = BigDecimal(1),
      bankConsumerNplLoss: BigDecimal,
      bankNewFailure: Int = 0,
      bankFailed: Int = 0,
      householdCount: Int = 1,
      closingHouseholdCount: Int = 1,
      householdShare: Option[BigDecimal] = None,
      closingHouseholdShare: Option[BigDecimal] = None,
      bankConsumerLoanStock: BigDecimal = BigDecimal(1000),
      bankConsumerLoanShare: Option[BigDecimal] = None,
      bankDeposits: BigDecimal = BigDecimal(1000),
      bankDepositShare: Option[BigDecimal] = None,
      bankRwa: BigDecimal = BigDecimal(1000),
      bankRwaShare: Option[BigDecimal] = None,
      bankCapital: BigDecimal = BigDecimal(100),
      bankCapitalShare: Option[BigDecimal] = None,
      bankCapitalBuffer: BigDecimal = BigDecimal(20),
  ): HhBankLeadLagDiagnosticsExport.BankMonthRow =
    require(bankCount > 0, "bankCount must be positive")
    val equalBankShare     = BigDecimal(1) / BigDecimal(bankCount)
    val capitalBufferToRwa = if bankRwa > BigDecimal(0) then bankCapitalBuffer / bankRwa else BigDecimal(0)
    HhBankLeadLagDiagnosticsExport.BankMonthRow(
      runId = "test",
      scenarioId = scenarioId,
      scenarioLabel = scenarioLabel,
      seed = seed,
      month = month,
      bankId = bankId,
      bankName = s"Bank-$bankId",
      householdCount = householdCount,
      householdShare = householdShare.getOrElse(equalBankShare),
      closingHouseholdCount = closingHouseholdCount,
      closingHouseholdShare = closingHouseholdShare.getOrElse(equalBankShare),
      hhMonthlyIncome = BigDecimal(100),
      hhConsumerLoanDefault = hhConsumerLoanDefault,
      hhConsumerInsolvencyDefault = BigDecimal(0),
      hhLiquidityBridgeChargeOff = BigDecimal(0),
      hhLiquidityShortfallFinancing = BigDecimal(0),
      hhConsumerDefault = hhConsumerLoanDefault,
      hhConsumerDebtService = BigDecimal(0),
      hhConsumerApprovedOrigination = BigDecimal(0),
      hhConsumerRejectedOrigination = BigDecimal(0),
      hhConsumerBankRejectedOrigination = BigDecimal(0),
      hhConsumerDebtArrears = BigDecimal(0),
      hhMortgageArrears = BigDecimal(0),
      hhConsumerLoanDefaultShare = hhConsumerLoanDefaultShare,
      hhConsumerInsolvencyDefaultShare = BigDecimal(0),
      hhLiquidityBridgeChargeOffShare = BigDecimal(0),
      hhLiquidityShortfallFinancingShare = BigDecimal(0),
      bankConsumerLoanStock = bankConsumerLoanStock,
      bankConsumerLoanShare = bankConsumerLoanShare.getOrElse(equalBankShare),
      bankMortgageLoanStock = BigDecimal(0),
      bankMortgageLoanShare = BigDecimal(0),
      bankDeposits = bankDeposits,
      bankDepositShare = bankDepositShare.getOrElse(equalBankShare),
      bankConsumerNplStock = BigDecimal(0),
      bankConsumerNplLoss = bankConsumerNplLoss,
      bankRwa = bankRwa,
      bankRwaShare = bankRwaShare.getOrElse(equalBankShare),
      bankCapital = bankCapital,
      bankCapitalShare = bankCapitalShare.getOrElse(equalBankShare),
      bankCapitalDelta = -bankConsumerNplLoss,
      bankEffectiveMinCar = BigDecimal("0.08"),
      bankCar = BigDecimal("0.1"),
      bankCarBuffer = BigDecimal("0.02"),
      bankCapitalBuffer = bankCapitalBuffer,
      bankCapitalBufferToRwa = capitalBufferToRwa,
      bankLcr = BigDecimal("1.0"),
      bankFailed = bankFailed,
      bankNewFailure = bankNewFailure,
      bankFailureReasonCode = if bankNewFailure == 1 then 2 else 0,
      unemployment = BigDecimal("0.05"),
      inflation = BigDecimal("0.02"),
      monthlyGdpProxy = BigDecimal(10000),
    )
