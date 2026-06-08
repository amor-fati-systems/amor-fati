package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, OptionValues}

class LoanOriginationQualityExportSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  "LoanOriginationQualityExport" should "parse CLI run controls" in {
    val parsed = LoanOriginationQualityExport.parseArgs(
      Vector(
        "--seed-start",
        "3",
        "--seeds",
        "2",
        "--months",
        "18",
        "--outcome-window",
        "6",
        "--out",
        "target/custom-loan-quality",
        "--run-id",
        "custom",
      ),
    )

    parsed.value shouldBe LoanOriginationQualityExport.Config(
      seedStart = 3L,
      seeds = 2,
      months = 18,
      outcomeWindow = 6,
      runId = "custom",
      out = java.nio.file.Path.of("target/custom-loan-quality"),
    )
  }

  it should "keep diagnostic output headers stable" in {
    LoanOriginationQualityExport.HouseholdHeader should include("IncomeDecile")
    LoanOriginationQualityExport.HouseholdHeader should include("CreditCapacity")
    LoanOriginationQualityExport.HouseholdHeader should include("BankApprovalProduct")
    LoanOriginationQualityExport.HouseholdHeader should include("BankProjectedCAR")
    LoanOriginationQualityExport.HouseholdHeader should include("FutureDefaultWithinWindow")
    LoanOriginationQualityExport.FirmHeader should include("DscrProxy")
    LoanOriginationQualityExport.FirmHeader should include("FutureBankruptWithinWindow")
    LoanOriginationQualityExport.SummaryHeader shouldBe
      "RunId;BorrowerType;CohortType;CohortValue;Rows;ApprovedRows;RejectedRows;ApprovedPrincipal;FutureBadRows;FutureBadRate;MeanRiskRatio;MeanBankCar;Interpretation"
  }

  it should "summarize future bad outcomes by borrower cohort" in {
    val hhBad   = hhRow(incomeDecile = "D01", futureDefault = true)
    val hhOk    = hhRow(incomeDecile = "D10", futureDefault = false)
    val firmBad = firmRow(sizeClass = "Micro", futureBankrupt = true)
    val firmOk  = firmRow(sizeClass = "Large", futureBankrupt = false)

    val summary = LoanOriginationQualityExport.summarize(
      LoanOriginationQualityExport.Config(runId = "fixture"),
      Vector(hhBad, hhOk),
      Vector(firmBad, firmOk),
    )

    val hhD01 = summary.find(row => row.borrowerType == "Household" && row.cohortType == "IncomeDecile" && row.cohortValue == "D01").value
    hhD01.rows shouldBe 1
    hhD01.approvedRows shouldBe 1
    hhD01.futureBadRows shouldBe 1
    hhD01.futureBadRate.value shouldBe BigDecimal("1.000000")

    val hhD10 = summary.find(row => row.borrowerType == "Household" && row.cohortType == "IncomeDecile" && row.cohortValue == "D10").value
    hhD10.rows shouldBe 1
    hhD10.futureBadRows shouldBe 0
    hhD10.futureBadRate.value shouldBe BigDecimal("0.000000")

    val firmMicro = summary.find(row => row.borrowerType == "Firm" && row.cohortType == "SizeClass" && row.cohortValue == "Micro").value
    firmMicro.rows shouldBe 1
    firmMicro.futureBadRows shouldBe 1
    firmMicro.futureBadRate.value shouldBe BigDecimal("1.000000")

    val firmLarge = summary.find(row => row.borrowerType == "Firm" && row.cohortType == "SizeClass" && row.cohortValue == "Large").value
    firmLarge.rows shouldBe 1
    firmLarge.futureBadRows shouldBe 0
    firmLarge.futureBadRate.value shouldBe BigDecimal("0.000000")
  }

  private def hhRow(incomeDecile: String, futureDefault: Boolean): LoanOriginationQualityExport.HhOriginationRow =
    LoanOriginationQualityExport.HhOriginationRow(
      runId = "fixture",
      seed = 1L,
      originationMonth = 1,
      householdId = if futureDefault then 1 else 2,
      bankId = 0,
      status = "Employed",
      incomeDecile = incomeDecile,
      openingFinancialDistressState = "Current",
      closingFinancialDistressState = "LiquidityStress",
      openingDistressMonths = 0,
      closingDistressMonths = if futureDefault then 1 else 0,
      wage = PLN(5000),
      openingDemandDeposit = PLN(1000),
      openingConsumerLoan = PLN(10000),
      openingMortgageLoan = PLN.Zero,
      monthlyIncome = PLN(5000),
      mortgageDebtService = PLN.Zero,
      consumerDebtService = PLN(1500),
      totalDebtService = PLN(1500),
      totalDsr = Some(BigDecimal("0.300000")),
      mortgageDsr = Some(BigDecimal("0.000000")),
      consumerDsr = Some(BigDecimal("0.300000")),
      creditCapacity = PLN(12000),
      creditAccessEligible = true,
      creditDemand = PLN(3000),
      approvedPrincipal = PLN(3000),
      rejectedPrincipal = PLN.Zero,
      bankRejectedPrincipal = PLN.Zero,
      bankApprovalProduct = "consumer-loan",
      bankRejectionReason = "",
      bankApprovalProbability = Some(Share.decimal(75, 2)),
      bankApprovalRoll = Some(Share.decimal(50, 2)),
      bankProjectedCar = Some(Multiplier.decimal(11, 2)),
      bankMinCar = Some(Multiplier.decimal(10, 2)),
      bankApprovalLcr = Some(Multiplier.decimal(12, 1)),
      bankApprovalNsfr = Some(Multiplier.decimal(11, 1)),
      bankCar = BigDecimal("0.110000"),
      bankLcr = BigDecimal("1.200000"),
      bankNsfr = BigDecimal("1.100000"),
      sameMonthArrears = false,
      sameMonthDefault = false,
      futureArrearsWithinWindow = futureDefault,
      futureDefaultWithinWindow = futureDefault,
      futureBankruptcyWithinWindow = false,
      firstFutureDefaultMonth = Option.when(futureDefault)(3),
      observedFutureMonths = 6,
      futureWriteOffAmount = if futureDefault then PLN(1000) else PLN.Zero,
    )

  private def firmRow(sizeClass: String, futureBankrupt: Boolean): LoanOriginationQualityExport.FirmOriginationRow =
    LoanOriginationQualityExport.FirmOriginationRow(
      runId = "fixture",
      seed = 1L,
      originationMonth = 1,
      firmId = if futureBankrupt then 10 else 11,
      bankId = 0,
      sector = "Manufacturing",
      sizeClass = sizeClass,
      techState = "Traditional",
      decisionType = "survive",
      creditPurpose = "physical-investment",
      bankApprovalObserved = true,
      bankApproved = true,
      bankRejected = false,
      observedCreditNeed = PLN(20000),
      approvedPrincipal = PLN(20000),
      cashBefore = PLN(5000),
      firmLoanBefore = PLN(30000),
      realizedPostTaxProfit = PLN(2500),
      grossInvestment = PLN(20000),
      principalRepaid = PLN(500),
      monthlyDebtServiceProxy = PLN(700),
      debtToCash = Some(BigDecimal("6.000000")),
      dscrProxy = Some(BigDecimal("3.571429")),
      workersBefore = if sizeClass == "Micro" then 8 else 300,
      workersAfter = if sizeClass == "Micro" then 8 else 300,
      foreignOwned = false,
      stateOwned = false,
      bankCar = BigDecimal("0.130000"),
      bankLcr = BigDecimal("1.100000"),
      bankNsfr = BigDecimal("1.050000"),
      sameMonthBankrupt = false,
      futureBankruptWithinWindow = futureBankrupt,
      firstFutureBankruptcyMonth = Option.when(futureBankrupt)(4),
      observedFutureMonths = 6,
      bankruptcyReason = "",
    )
