package com.boombustgroup.amorfati.agents.banking

import com.boombustgroup.amorfati.agents.Banking.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.mechanisms.Macroprudential
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Encapsulates bank-side credit routing, pricing, and loan approval gates.
  *
  * The module owns borrower-bank assignment, rate formation, and the audited
  * stochastic approval decision for new firm credit.
  */
private[agents] object BankCreditApproval:

  private val FailedBankSpread: Rate             = Rate.decimal(50, 2)
  private val NplSpreadCap: Rate                 = Rate.decimal(15, 2)
  private val CarPenaltyThreshMult: Multiplier   = Multiplier.decimal(15, 1)
  private val CarPenaltyScale: Multiplier        = Multiplier(2)
  private val CrowdingOutSensitivity: Multiplier = Multiplier.decimal(30, 2)

  /** Chooses the bank relationship for a firm using sector affinity and opening
    * market-share weights.
    */
  def assignBank(firmSector: SectorIdx, configs: Vector[Config], rng: RandomStream): BankId =
    val weights = configs.map(c => c.sectorAffinity(firmSector.toInt) * c.initMarketShare)
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

  /** Prices firm lending from monetary stance, bank-specific spread, risk
    * stress, capital pressure, and government-bond crowding-out.
    */
  def lendingRate(bank: BankState, stocks: BankFinancialStocks, cfg: Config, refRate: Rate, bondYield: Rate, corpBondHoldings: PLN)(using
      p: SimParams,
  ): Rate =
    if bank.failed then refRate + FailedBankSpread
    else
      val nplSpread   = (BankRegulatoryMetrics.nplRatio(bank, stocks) * p.banking.nplSpreadFactor).toRate.min(NplSpreadCap)
      val carThresh   = p.banking.minCar * CarPenaltyThreshMult
      val bankCar     = BankRegulatoryMetrics.car(bank, stocks, corpBondHoldings)
      val carPenalty  =
        if bankCar < carThresh then ((carThresh - bankCar) * CarPenaltyScale).toRate
        else Rate.Zero
      val crowdingOut = (bondYield - refRate - p.banking.baseSpread).max(Rate.Zero) * CrowdingOutSensitivity
      refRate + p.banking.baseSpread + cfg.lendingSpread + nplSpread + carPenalty + crowdingOut

  /** Returns the boolean approval decision used by legacy call sites. */
  def canLend(bank: BankState, stocks: BankFinancialStocks, amount: PLN, rng: RandomStream, ccyb: Multiplier, corpBondHoldings: PLN)(using
      p: SimParams,
  ): Boolean =
    creditApproval(bank, stocks, amount, rng, ccyb, corpBondHoldings).approved

  /** Evaluates the full audited firm-credit approval decision.
    *
    * Hard regulatory gates cover failure status, projected CAR, LCR, and NSFR.
    * Banks that pass those gates still face a stochastic approval draw
    * penalized by NPL pressure.
    */
  def creditApproval(bank: BankState, stocks: BankFinancialStocks, amount: PLN, rng: RandomStream, ccyb: Multiplier, corpBondHoldings: PLN)(using
      p: SimParams,
  ): CreditApproval =
    if bank.failed then
      CreditApproval(
        approved = false,
        approvalProbability = None,
        approvalRoll = None,
        audit = CreditApprovalAudit(rejectionReason = Some(CreditRejectionReason.FailedBank)),
      )
    else
      val projectedExposure                                                 =
        BankRiskWeightedAssets.exposure(bank, stocks, corpBondHoldings).withAdditionalFirmLoan(amount)
      val projectedCar                                                      = BankRegulatoryMetrics.capitalAdequacyRatio(bank.capital, projectedExposure)
      val minCar                                                            = Macroprudential.effectiveMinCar(bank.id.toInt, ccyb)
      val carOk                                                             = projectedCar >= minCar
      val currentLcr                                                        = BankRegulatoryMetrics.lcr(stocks)
      val lcrOk                                                             = currentLcr >= p.banking.lcrMin
      val currentNsfr                                                       = BankRegulatoryMetrics.nsfr(bank, stocks, corpBondHoldings)
      val nsfrOk                                                            = currentNsfr >= p.banking.nsfrMin
      val nplPenalty                                                        = BankRegulatoryMetrics.nplRatio(bank, stocks) * p.banking.firmCreditNplApprovalPenalty
      val approvalP                                                         = (Share.One - nplPenalty.toShare).max(p.banking.firmCreditMinApprovalProb)
      def audit(reason: Option[CreditRejectionReason]): CreditApprovalAudit =
        CreditApprovalAudit(
          rejectionReason = reason,
          projectedCar = Some(projectedCar),
          minCar = Some(minCar),
          lcr = Some(currentLcr),
          lcrMin = Some(p.banking.lcrMin),
          nsfr = Some(currentNsfr),
          nsfrMin = Some(p.banking.nsfrMin),
        )
      if carOk && lcrOk && nsfrOk then
        val roll     = Share.random(rng)
        val approved = roll < approvalP
        CreditApproval(
          approved = approved,
          approvalProbability = Some(approvalP),
          approvalRoll = Some(roll),
          audit = audit(if approved then None else Some(CreditRejectionReason.Stochastic)),
        )
      else
        val reason =
          if !carOk then CreditRejectionReason.CapitalAdequacy
          else if !lcrOk then CreditRejectionReason.LiquidityCoverage
          else CreditRejectionReason.StableFunding
        CreditApproval(
          approved = false,
          approvalProbability = Some(approvalP),
          approvalRoll = None,
          audit = audit(Some(reason)),
        )
