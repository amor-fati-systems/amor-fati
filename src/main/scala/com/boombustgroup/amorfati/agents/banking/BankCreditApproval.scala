package com.boombustgroup.amorfati.agents.banking

import com.boombustgroup.amorfati.agents.Banking.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.mechanisms.Macroprudential
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Encapsulates bank-side credit routing, pricing, and loan approval gates.
  *
  * The module owns borrower-bank assignment, rate formation, and the audited
  * regulatory approval decision for new product-specific credit exposures.
  */
private[agents] object BankCreditApproval:

  private val FailedBankSpread: Rate = Rate.decimal(50, 2)
  private val NplSpreadCap: Rate     = Rate.decimal(15, 2)

  /** Returns the internal management CAR target used for pricing and
    * risk-weighted credit-supply throttling.
    */
  def managementCarTarget(bankId: Int, ccyb: Multiplier)(using p: SimParams): Multiplier =
    Macroprudential.effectiveMinCar(bankId, ccyb) + p.banking.creditManagementCarBuffer

  /** Converts the distance above the hard CAR floor into a smooth approval
    * throttle. At the hard floor the bank stops discretionary risk-weighted
    * origination; at the management target the throttle reaches one.
    */
  def capitalThrottle(projectedCar: Multiplier, effectiveMinCar: Multiplier)(using p: SimParams): Share =
    val gap = projectedCar - effectiveMinCar
    if gap <= Multiplier.Zero then Share.Zero
    else if gap >= p.banking.creditManagementCarBuffer then Share.One
    else gap.ratioTo(p.banking.creditManagementCarBuffer).clampToShare

  /** Chooses the bank relationship for a firm using sector affinity and
    * relationship weights.
    */
  def assignBank(firmSector: SectorIdx, configs: Vector[Config], rng: RandomStream): BankId =
    val weights = configs.map(c => c.sectorAffinity(firmSector.toInt) * c.relationshipWeight)
    val total   = weights.map(_.toLong).sum
    if total <= 0L then BankId(0)
    else
      val r      = rng.between(0L, total)
      val cumul  = weights.map(_.toLong).scanLeft(0L)(_ + _).tail
      val picked = cumul.indexWhere(_ > r)
      BankId(if picked >= 0 then picked else weights.length - 1)

  /** Computes the household deposit rate offered by banks at the current
    * monetary-policy reference rate.
    */
  def hhDepositRate(refRate: Rate)(using p: SimParams): Rate =
    (refRate - p.household.depositSpread).max(Rate.Zero)

  /** Prices bank lending from monetary stance, bank-specific spread, product
    * credit risk, capital pressure, and the portfolio-choice wedge.
    */
  def lendingRate(
      bank: BankState,
      stocks: BankFinancialStocks,
      cfg: Config,
      refRate: Rate,
      govBondMarketYield: Rate,
      corpBondHoldings: PLN,
      ccyb: Multiplier,
      product: CreditProduct,
  )(using p: SimParams): Rate =
    if bank.failed then refRate + FailedBankSpread
    else
      val prePortfolioRate = lendingRateBeforePortfolio(bank, stocks, cfg, refRate, corpBondHoldings, ccyb, product)
      val portfolioAudit   = BankPortfolioChoice.compute(
        bank = bank,
        stocks = stocks,
        product = product,
        amount = PLN.Zero,
        loanRateBeforePortfolio = prePortfolioRate,
        govBondMarketYield = govBondMarketYield,
        corpBondHoldings = corpBondHoldings,
        ccyb = ccyb,
      )
      prePortfolioRate + BankPortfolioChoice.pricePremium(portfolioAudit)

  /** Returns the boolean approval decision for callers that do not need audit
    * fields.
    */
  def canLend(
      context: CreditApprovalContext,
      stocks: BankFinancialStocks,
      product: CreditProduct,
      amount: PLN,
      rng: RandomStream,
  )(using p: SimParams): Boolean =
    creditApproval(context, stocks, product, amount, rng).approved

  /** Evaluates the full audited bank-credit approval decision.
    *
    * Hard regulatory gates cover failure status, projected CAR, LCR, and NSFR.
    * Banks above the hard CAR floor but below their management target ration
    * new risk-weighted credit smoothly. NPL pressure is routed through lending
    * spreads, ECL provisioning, and the resulting capital path rather than
    * through a second direct approval penalty.
    */
  def creditApproval(
      context: CreditApprovalContext,
      stocks: BankFinancialStocks,
      product: CreditProduct,
      amount: PLN,
      rng: RandomStream,
  )(using p: SimParams): CreditApproval =
    val bank = context.bank
    if bank.failed then
      CreditApproval(
        product = product,
        amount = amount,
        approved = false,
        approvalProbability = None,
        approvalRoll = None,
        audit = CreditApprovalAudit(rejectionReason = Some(CreditRejectionReason.FailedBank)),
      )
    else
      val projectedExposure                                                 =
        BankRiskWeightedAssets.exposure(bank, stocks, context.corpBondHoldings).withAdditionalCredit(product, amount)
      val projectedCar                                                      = BankRegulatoryMetrics.capitalAdequacyRatio(bank.capital, projectedExposure)
      val minCar                                                            = Macroprudential.effectiveMinCar(bank.id.toInt, context.ccyb)
      val managementTarget                                                  = managementCarTarget(bank.id.toInt, context.ccyb)
      val throttle                                                          = capitalThrottle(projectedCar, minCar)
      val carOk                                                             = projectedCar >= minCar
      val currentLcr                                                        = BankRegulatoryMetrics.lcr(stocks)
      val lcrOk                                                             = currentLcr >= p.banking.lcrMin
      val currentNsfr                                                       = BankRegulatoryMetrics.nsfr(bank, stocks, context.corpBondHoldings)
      val nsfrOk                                                            = currentNsfr >= p.banking.nsfrMin
      val portfolioAudit                                                    = BankPortfolioChoice.compute(
        bank = bank,
        stocks = stocks,
        product = product,
        amount = amount,
        loanRateBeforePortfolio = lendingRateBeforePortfolio(
          bank,
          stocks,
          context.portfolio.config,
          context.portfolio.refRate,
          context.corpBondHoldings,
          context.ccyb,
          product,
        ),
        govBondMarketYield = context.portfolio.govBondMarketYield,
        corpBondHoldings = context.corpBondHoldings,
        ccyb = context.ccyb,
      )
      val portfolioThrottle                                                 = BankPortfolioChoice.supplyThrottle(portfolioAudit)
      val approvalP                                                         = throttle * portfolioThrottle
      def audit(reason: Option[CreditRejectionReason]): CreditApprovalAudit =
        CreditApprovalAudit(
          rejectionReason = reason,
          projectedCar = Some(projectedCar),
          minCar = Some(minCar),
          managementCarTarget = Some(managementTarget),
          capitalThrottle = Some(throttle),
          lcr = Some(currentLcr),
          lcrMin = Some(p.banking.lcrMin),
          nsfr = Some(currentNsfr),
          nsfrMin = Some(p.banking.nsfrMin),
          portfolioChoice = Some(portfolioAudit),
        )
      if carOk && lcrOk && nsfrOk then
        val roll     = Share.random(rng)
        val approved = approvalP == Share.One || roll < approvalP
        CreditApproval(
          product = product,
          amount = amount,
          approved = approved,
          approvalProbability = Some(approvalP),
          approvalRoll = Some(roll),
          audit = audit(if approved then None else Some(discretionaryRejectionReason(throttle, portfolioThrottle))),
        )
      else
        val reason =
          if !carOk then CreditRejectionReason.CapitalAdequacy
          else if !lcrOk then CreditRejectionReason.LiquidityCoverage
          else CreditRejectionReason.StableFunding
        CreditApproval(
          product = product,
          amount = amount,
          approved = false,
          approvalProbability = Some(approvalP),
          approvalRoll = None,
          audit = audit(Some(reason)),
        )

  private def lendingRateBeforePortfolio(
      bank: BankState,
      stocks: BankFinancialStocks,
      cfg: Config,
      refRate: Rate,
      corpBondHoldings: PLN,
      ccyb: Multiplier,
      product: CreditProduct,
  )(using p: SimParams): Rate =
    val nplSpread  = (BankRegulatoryMetrics.nplRatio(bank, stocks, product) * p.banking.nplSpreadFactor).toRate.min(NplSpreadCap)
    val carTarget  = managementCarTarget(bank.id.toInt, ccyb)
    val bankCar    = BankRegulatoryMetrics.car(bank, stocks, corpBondHoldings)
    val carPenalty =
      if bankCar < carTarget then ((carTarget - bankCar) * p.banking.creditCarShortfallPenaltyScale).toRate
      else Rate.Zero
    refRate + p.banking.baseSpread + cfg.lendingSpread + nplSpread + carPenalty

  private def discretionaryRejectionReason(capitalThrottle: Share, portfolioThrottle: Share): CreditRejectionReason =
    if portfolioThrottle < Share.One && portfolioThrottle <= capitalThrottle then CreditRejectionReason.PortfolioPreference
    else CreditRejectionReason.CapitalBuffer
