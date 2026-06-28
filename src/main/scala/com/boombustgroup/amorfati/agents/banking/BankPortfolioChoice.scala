package com.boombustgroup.amorfati.agents.banking

import com.boombustgroup.amorfati.agents.Banking.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.mechanisms.Macroprudential
import com.boombustgroup.amorfati.types.*

/** Auditable loan-versus-sovereign portfolio-choice surface for one bank credit
  * request.
  */
final case class BankPortfolioChoiceAudit(
    riskAdjustedLoanReturn: Rate,
    riskAdjustedBondReturn: Rate,
    wedge: Rate,
    wedgeExpectedLossComponent: Rate,
    wedgeCapitalComponent: Rate,
    wedgeLevyComponent: Rate,
    wedgeFundingComponent: Rate,
    priceShareOfWedge: Share,
    wedgePriceContribution: Rate,
    wedgeQuantityThrottle: Share,
)

/** Computes the marginal private-credit return wedge against Polish sovereign
  * securities.
  *
  * The module is deliberately return-based: sovereign holdings are not
  * subtracted from credit capacity. They affect credit supply only through the
  * explicit price and quantity channels derived from the same wedge.
  */
private[agents] object BankPortfolioChoice:

  /** Computes the portfolio-choice audit used by pricing and approval. */
  def compute(
      bank: BankState,
      stocks: BankFinancialStocks,
      product: CreditProduct,
      amount: PLN,
      loanRateBeforePortfolio: Rate,
      govBondMarketYield: Rate,
      corpBondHoldings: PLN,
      ccyb: Multiplier,
  )(using p: SimParams): BankPortfolioChoiceAudit =
    val expectedLoss           = expectedLossRate(bank, stocks, product)
    val capitalCharge          = capitalCostRate(bank.id.toInt, product, ccyb)
    val levyCost               = marginalPolishBankLevyRate(bank, stocks, product, amount, corpBondHoldings)
    val riskAdjustedLoanReturn = loanRateBeforePortfolio - expectedLoss - capitalCharge - levyCost
    val riskAdjustedBondReturn = govBondMarketYield
    val wedge                  = riskAdjustedLoanReturn - riskAdjustedBondReturn
    val negativeWedge          = (Rate.Zero - wedge).max(Rate.Zero)
    val priceShare             = p.banking.portfolioWedgePriceShare
    val priceContribution      = negativeWedge * priceShare
    val quantityWedge          = negativeWedge * priceShare.complement
    val quantityPenalty        = (quantityWedge * p.banking.portfolioWedgeQuantitySensitivity).toScalar.clampToShare
    BankPortfolioChoiceAudit(
      riskAdjustedLoanReturn = riskAdjustedLoanReturn,
      riskAdjustedBondReturn = riskAdjustedBondReturn,
      wedge = wedge,
      wedgeExpectedLossComponent = expectedLoss,
      wedgeCapitalComponent = capitalCharge,
      wedgeLevyComponent = levyCost,
      // Loan and sovereign alternatives share the same generic funding anchor
      // at this abstraction level, so funding does not move the wedge.
      wedgeFundingComponent = Rate.Zero,
      priceShareOfWedge = priceShare,
      wedgePriceContribution = priceContribution,
      wedgeQuantityThrottle = Share.One - quantityPenalty,
    )

  /** Returns the price component of the negative wedge. */
  def pricePremium(audit: BankPortfolioChoiceAudit): Rate =
    audit.wedgePriceContribution

  /** Returns the quantity component of the negative wedge. */
  def supplyThrottle(audit: BankPortfolioChoiceAudit): Share =
    audit.wedgeQuantityThrottle

  private def expectedLossRate(bank: BankState, stocks: BankFinancialStocks, product: CreditProduct)(using p: SimParams): Rate =
    val nplRatio         = BankRegulatoryMetrics.nplRatio(bank, stocks, product)
    val lossGivenDefault = product match
      case CreditProduct.FirmLoan     => Share.One - p.banking.loanRecovery
      case CreditProduct.ConsumerLoan => Share.One - p.household.ccNplRecovery
      case CreditProduct.MortgageLoan => Share.One - p.housing.mortgageRecovery
    (nplRatio * lossGivenDefault).toRate

  private def capitalCostRate(bankId: Int, product: CreditProduct, ccyb: Multiplier)(using p: SimParams): Rate =
    val effectiveRequirement = (Macroprudential.effectiveMinCar(bankId, ccyb) + p.banking.creditManagementCarBuffer).toShare.clamp(Share.Zero, Share.One)
    val riskWeight           = productRiskWeight(product)
    p.banking.portfolioCapitalHurdleRate * (effectiveRequirement * riskWeight)

  private def productRiskWeight(product: CreditProduct)(using p: SimParams): Share =
    product match
      case CreditProduct.FirmLoan     => p.banking.firmLoanRiskWeight
      case CreditProduct.ConsumerLoan => p.banking.consumerLoanRiskWeight
      case CreditProduct.MortgageLoan => p.banking.mortgageLoanRiskWeight

  private def marginalPolishBankLevyRate(
      bank: BankState,
      stocks: BankFinancialStocks,
      product: CreditProduct,
      amount: PLN,
      corpBondHoldings: PLN,
  )(using p: SimParams): Rate =
    val basePerimeter  = PolishBankLevyAssetPerimeter.fromBankStocks(stocks, corpBondHoldings)
    val baseTaxable    = BankTaxation.computePolishBankLevyTaxableAssets(bank, basePerimeter)
    val addedAmount    = amount.max(PLN.Zero)
    val addedStocks    =
      product match
        case CreditProduct.FirmLoan     => stocks.copy(firmLoan = stocks.firmLoan + addedAmount)
        case CreditProduct.ConsumerLoan => stocks.copy(consumerLoan = stocks.consumerLoan + addedAmount)
        case CreditProduct.MortgageLoan => stocks.copy(mortgageLoan = stocks.mortgageLoan + addedAmount)
    val addedPerimeter = PolishBankLevyAssetPerimeter.fromBankStocks(addedStocks, corpBondHoldings)
    val addedTaxable   = BankTaxation.computePolishBankLevyTaxableAssets(bank, addedPerimeter)
    val marginalBase   = (addedTaxable - baseTaxable).max(PLN.Zero)
    val monthlyRate    =
      if addedAmount > BankRegulatoryMetrics.MinBalanceThreshold then p.banking.polishBankLevyMonthlyRate * marginalBase.ratioTo(addedAmount).clampToShare
      else if baseTaxable > PLN.Zero then p.banking.polishBankLevyMonthlyRate
      else Rate.Zero
    monthlyRate.annualize
