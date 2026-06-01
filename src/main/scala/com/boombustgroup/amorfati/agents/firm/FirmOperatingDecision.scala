package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.agents.{BankruptReason, StateOwned, TechState}
import com.boombustgroup.amorfati.agents.Firm.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.{OperationalSignals, World}
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.amorfati.agents.firm.FirmCalibration.*
import com.boombustgroup.amorfati.agents.firm.FirmStartupLifecycle.startupRunwayLimit

/** Survival and operating fallback decisions for a firm month.
  *
  * This module handles labor resizing, working-capital grace, digital-readiness
  * investment, and startup runway behavior after the technology-upgrade path
  * has either been skipped or rejected.
  */
private[agents] object FirmOperatingDecision:

  /** Smooth labor adjustment: Δworkers = λ × (target − current), with severance
    * costs. Target = break-even headcount from P&L. If adjustment insufficient
    * to restore solvency, escalates to bankruptcy.
    */
  private[amorfati] def attemptDownsize(
      firm: State,
      pnl: PnL,
      nc: PLN,
      workers: Int,
      newTech: Int => TechState,
      wage: PLN,
      reason: BankruptReason,
      drUpdate: Option[Share] = None,
  )(using p: SimParams): Decision =
    val minRetained         = p.firm.minWorkersRetained
    if workers <= minRetained then
      return if hasWorkingCapitalGrace(firm, pnl, nc) then Decision.Survive(pnl, nc, drUpdate = drUpdate) else Decision.GoBankrupt(pnl, nc, reason)
    val laborPerWorker: PLN = wage * effectiveWageMult(firm.sector)
    // Target headcount: workers needed for revenue to cover non-labor costs
    val nonLaborCost: PLN   = (pnl.costs - workers * laborPerWorker).max(PLN.Zero)
    val revenuePerWorker    = if workers > 0 then pnl.revenue / workers else PLN.Zero
    val contributionMargin  = (revenuePerWorker - laborPerWorker).max(PLN.Zero)
    val targetWorkers       =
      if contributionMargin > PLN.Zero then (nonLaborCost / contributionMargin).ceilToInt
      else minRetained
    // Smooth adjustment: cut λ of the gap, not the entire excess
    val gap                 = workers - Math.max(minRetained, targetWorkers)
    val cutSpeedRaw         =
      if firm.stateOwned then (p.firm.laborAdjustSpeed * StateOwned.firingReduction).toLong
      else if isInStartup(firm) then (p.firm.laborAdjustSpeed * StartupDownsizeSpeedMultiplier).toLong
      else p.firm.laborAdjustSpeed.toLong
    val cut                 =
      if gap <= 0 then 0
      else Math.max(1, FixedPointBase.multiplyRaw(gap.toLong, cutSpeedRaw).toInt)
    val newWkrs             = Math.max(minRetained, workers - cut)
    // Severance cost = fired workers × wage × severanceMonths
    val fired               = workers - newWkrs
    val severancePay: PLN   = fired * laborPerWorker * p.firm.severanceMonths
    val laborSaved: PLN     = fired * laborPerWorker
    val revRatio: Share     = Share.fraction(newWkrs, workers).sqrt
    val revLost: PLN        = pnl.revenue * (Share.One - revRatio)
    val adjustedNc          = nc + laborSaved - revLost - severancePay
    if adjustedNc >= PLN.Zero || hasWorkingCapitalGrace(firm, pnl, adjustedNc) then
      Decision.Downsize(pnl, newWkrs, adjustedNc, newTech(newWkrs), drUpdate = drUpdate)
    else Decision.GoBankrupt(pnl, nc, reason)

  /** Whether a negative cash position can survive this month without
    * bankruptcy.
    */
  private[amorfati] def hasWorkingCapitalGrace(firm: State, pnl: PnL, cashAfterDecision: PLN)(using p: SimParams): Boolean =
    firm.stateOwned ||
      (cashAfterDecision < PLN.Zero &&
        pnl.netAfterTax > PLN.Zero &&
        cashAfterDecision.abs <= pnl.netAfterTax * WorkingCapitalGraceMonths) ||
      (isInStartup(firm) &&
        cashAfterDecision < PLN.Zero &&
        cashAfterDecision.abs <= startupRunwayLimit(firm))

  /** Try upsize, digital readiness investment, downsize, or survive when no
    * technology upgrade is selected.
    */
  private[agents] def fallbackDecision(
      firm: State,
      financialStocks: FinancialStocks,
      pnl: PnL,
      w: World,
      operationalSignals: OperationalSignals,
      workers: Int,
      rng: RandomStream,
      nextTech: Int => TechState,
      drUpdate: Option[Share] = None,
      allowDigiInvest: Boolean = true,
  )(using p: SimParams): DecisionWithAudit =
    val nc            = financialStocks.cash + pnl.netAfterTax
    var audit         = DecisionAudit()
    val desiredW      = desiredWorkers(firm, w, operationalSignals)
    val feasibleW     = feasibleWorkers(firm, workers, desiredW, pnl, nc)
    val gap           = feasibleW - workers
    val hiringThresh  = hiringAdjustmentThreshold(workers)
    val firingThresh  = Math.max(2, workers / 10)
    val shouldAdjust  = if gap > 0 then gap >= hiringThresh else -gap >= firingThresh
    if shouldAdjust then
      val adjustFrac = if gap > 0 then hiringAdjustFrac else firingAdjustFrac(firm)
      val magnitude  = stochasticAdjustmentMagnitude(Math.abs(gap), adjustFrac, rng)
      audit = audit.copy(
        laborAdjustmentResidualProbability = magnitude.residualProbability,
        laborAdjustmentResidualRoll = magnitude.residualRoll,
      )
      if magnitude.value > 0 then
        val adj     = magnitude.value * (if gap > 0 then 1 else -1)
        val newWkrs = (workers + adj).max(p.firm.minWorkersRetained)
        if newWkrs > workers && canFundUpsize(firm, pnl, nc, newWkrs - workers, w.householdMarket.marketWage) then
          return DecisionWithAudit(Decision.Upsize(pnl, newWkrs, nc, nextTech(newWkrs), drUpdate = drUpdate), audit)
        else if newWkrs < workers then return DecisionWithAudit(Decision.Downsize(pnl, newWkrs, nc, nextTech(newWkrs), drUpdate = drUpdate), audit)
    val digiCost: PLN = computeDigiInvestCost(firm)
    val canAfford     = nc > digiCost * DigiInvestCashMult
    val competitive   = w.real.automationRatio + w.real.hybridRatio * Share.decimal(5, 1)
    val diminishing   = Share.One - firm.digitalReadiness
    val digiProb      = (p.firm.digiInvestBaseProb * firm.riskProfile * diminishing * (Share.decimal(5, 1) + competitive)).min(Share.One)
    val digiRoll      = if allowDigiInvest && canAfford then Some(Share.random(rng)) else None
    audit = audit.merge(
      DecisionAudit(
        digitalInvestProbability = if allowDigiInvest then Some(digiProb) else None,
        digitalInvestRoll = digiRoll,
      ),
    )
    if allowDigiInvest && canAfford && digiRoll.exists(_ < digiProb) then
      val boost = p.firm.digiInvestBoost * diminishing
      val newDR = (firm.digitalReadiness + boost).min(Share.One)
      DecisionWithAudit(Decision.DigiInvest(pnl, digiCost, newDR), audit)
    else if nc < PLN.Zero then
      DecisionWithAudit(
        attemptDownsize(firm, pnl, nc, workers, nextTech, w.householdMarket.marketWage, BankruptReason.LaborCostInsolvency, drUpdate = drUpdate),
        audit,
      )
    else DecisionWithAudit(Decision.Survive(pnl, nc, drUpdate = drUpdate), audit)

  /** Startup-specific one-worker ramp and runway-aware fallback. */
  private[agents] def startupFallbackDecision(
      firm: State,
      financialStocks: FinancialStocks,
      pnl: PnL,
      currentWorkers: Int,
      nextTech: Int => TechState,
      wage: PLN,
  )(using p: SimParams): Decision =
    val nc            = financialStocks.cash + pnl.netAfterTax
    val targetWorkers = Math.max(currentWorkers, firm.startupTargetWorkers)
    if currentWorkers < targetWorkers && canFundUpsize(firm, pnl, nc, 1, wage) then Decision.Upsize(pnl, currentWorkers + 1, nc, nextTech(currentWorkers + 1))
    else if nc < PLN.Zero then
      if hasWorkingCapitalGrace(firm, pnl, nc) then Decision.Survive(pnl, nc)
      else attemptDownsize(firm, pnl, nc, currentWorkers, nextTech, wage, BankruptReason.LaborCostInsolvency)
    else Decision.Survive(pnl, nc)

  /** Whether an intended workforce expansion can be funded without immediate
    * bankruptcy.
    */
  private[amorfati] def canFundUpsize(
      firm: State,
      pnl: PnL,
      cashAfterDecision: PLN,
      addedWorkers: Int,
      marketWage: PLN,
  )(using p: SimParams): Boolean =
    cashAfterDecision >= PLN.Zero ||
      firm.stateOwned ||
      (isInStartup(firm) &&
        cashAfterDecision.abs <= startupRunwayLimit(firm) +
        addedWorkers * (marketWage * effectiveWageMult(firm.sector)) * p.firm.startupHiringWorkingCapitalMonths) ||
      (pnl.netAfterTax >= PLN.Zero &&
        cashAfterDecision.abs <= addedWorkers * (marketWage * effectiveWageMult(firm.sector)) * p.firm.hiringWorkingCapitalMonths)
