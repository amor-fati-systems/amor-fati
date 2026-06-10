package com.boombustgroup.amorfati.montecarlo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class McFirmDecisionTraceSchemaSpec extends AnyFlatSpec with Matchers:

  "McFirmDecisionTraceSchema" should "keep the firm decision trace header stable" in {
    val portfolioFields = Vector(
      "PortfolioRiskAdjustedLoanReturn",
      "PortfolioRiskAdjustedBondReturn",
      "PortfolioWedge",
      "PortfolioExpectedLossComponent",
      "PortfolioCapitalComponent",
      "PortfolioLevyComponent",
      "PortfolioFundingComponent",
      "PortfolioPriceShare",
      "PortfolioPriceContribution",
      "PortfolioQuantityThrottle",
    )
    val portfolioHeader = Vector("SelectedBank", "FullAiBank", "HybridBank", "InvestmentBank")
      .flatMap(prefix => portfolioFields.map(field => s"$prefix$field"))
      .mkString(";")
    McFirmDecisionTraceSchema.header shouldBe
      s"RunId;Seed;Month;FirmId;OpeningTechState;ClosingTechState;DecisionType;BankruptcyReason;CashBefore;CashAfter;FirmLoanBefore;FirmLoanAfter;DigitalReadinessBefore;DigitalReadinessAfter;WorkersBefore;WorkersAfter;Capex;NewLoan;TechCreditDecisionType;TechCreditSource;TechCreditNeed;TechCreditAmount;DownPayment;BankId;LendingRate;SelectedBankApproval;SelectedBankApprovalProbability;SelectedBankApprovalRoll;SelectedBankRejectionReason;SelectedBankProjectedCar;SelectedBankMinCar;SelectedBankManagementCar;SelectedBankCapitalThrottle;SelectedBankLcr;SelectedBankLcrMin;SelectedBankNsfr;SelectedBankNsfrMin;FullAiFeasible;HybridFeasible;FullAiAdoptionProbability;HybridAdoptionProbability;AdoptionRoll;FullAiBankApproval;FullAiBankApprovalProbability;FullAiBankApprovalRoll;FullAiBankRejectionReason;FullAiBankProjectedCar;FullAiBankMinCar;FullAiBankManagementCar;FullAiBankCapitalThrottle;FullAiBankLcr;FullAiBankLcrMin;FullAiBankNsfr;FullAiBankNsfrMin;HybridBankApproval;HybridBankApprovalProbability;HybridBankApprovalRoll;HybridBankRejectionReason;HybridBankProjectedCar;HybridBankMinCar;HybridBankManagementCar;HybridBankCapitalThrottle;HybridBankLcr;HybridBankLcrMin;HybridBankNsfr;HybridBankNsfrMin;ImplementationFailureProbability;ImplementationRoll;UpgradeEfficiencyDraw;UpgradeEfficiencyMultiplier;InvestmentCreditNeed;InvestmentCreditAmount;InvestmentBankApproval;InvestmentBankApprovalProbability;InvestmentBankApprovalRoll;InvestmentBankRejectionReason;InvestmentBankProjectedCar;InvestmentBankMinCar;InvestmentBankManagementCar;InvestmentBankCapitalThrottle;InvestmentBankLcr;InvestmentBankLcrMin;InvestmentBankNsfr;InvestmentBankNsfrMin;DigitalInvestProbability;DigitalInvestRoll;LaborAdjustmentResidualProbability;LaborAdjustmentResidualRoll;$portfolioHeader"
  }
