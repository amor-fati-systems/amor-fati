package com.boombustgroup.amorfati.diagnostics

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues

class HhBankLeadLagDiagnosticsExportSpec extends AnyFlatSpec with Matchers with OptionValues:

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

  private def row(
      scenarioId: String = "baseline",
      scenarioLabel: String = "Baseline",
      seed: Long = 1L,
      month: Int,
      bankId: Int = 0,
      hhConsumerLoanDefault: BigDecimal,
      bankConsumerNplLoss: BigDecimal,
      bankNewFailure: Int = 0,
      bankFailed: Int = 0,
  ): HhBankLeadLagDiagnosticsExport.BankMonthRow =
    HhBankLeadLagDiagnosticsExport.BankMonthRow(
      runId = "test",
      scenarioId = scenarioId,
      scenarioLabel = scenarioLabel,
      seed = seed,
      month = month,
      bankId = bankId,
      bankName = s"Bank-$bankId",
      householdCount = 1,
      hhMonthlyIncome = BigDecimal(100),
      hhConsumerLoanDefault = hhConsumerLoanDefault,
      hhLiquidityBridgeChargeOff = BigDecimal(0),
      hhLiquidityShortfallFinancing = BigDecimal(0),
      hhConsumerDefault = hhConsumerLoanDefault,
      hhConsumerDebtService = BigDecimal(0),
      hhConsumerApprovedOrigination = BigDecimal(0),
      hhConsumerRejectedOrigination = BigDecimal(0),
      hhConsumerBankRejectedOrigination = BigDecimal(0),
      hhConsumerDebtArrears = BigDecimal(0),
      hhMortgageArrears = BigDecimal(0),
      bankConsumerLoanStock = BigDecimal(1000),
      bankConsumerNplStock = BigDecimal(0),
      bankConsumerNplLoss = bankConsumerNplLoss,
      bankCapital = BigDecimal(100),
      bankCapitalDelta = -bankConsumerNplLoss,
      bankCar = BigDecimal("0.1"),
      bankLcr = BigDecimal("1.0"),
      bankFailed = bankFailed,
      bankNewFailure = bankNewFailure,
      bankFailureReasonCode = if bankNewFailure == 1 then 2 else 0,
      unemployment = BigDecimal("0.05"),
      inflation = BigDecimal("0.02"),
      monthlyGdpProxy = BigDecimal(10000),
    )
