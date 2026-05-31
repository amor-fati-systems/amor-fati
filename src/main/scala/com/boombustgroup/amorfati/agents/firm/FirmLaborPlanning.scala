package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.agents.{Firm, StateOwned, TechState}
import com.boombustgroup.amorfati.agents.Firm.{FinancialStocks, PnL, State}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.{OperationalSignals, World}
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.amorfati.agents.firm.FirmCalibration.*
import com.boombustgroup.amorfati.agents.firm.FirmProduction.*
import com.boombustgroup.amorfati.agents.firm.FirmStartupLifecycle.startupRunwayLimit

/** Labor-demand planning and smooth workforce adjustment helpers. */
private[agents] object FirmLaborPlanning:

  /** Desired worker count from a one-period marginal-revenue comparison. */
  def desiredWorkers(f: State, w: World, operationalSignals: OperationalSignals)(using p: SimParams): Int =
    if isInStartup(f) then return Math.max(workerCount(f), f.startupTargetWorkers)
    def withWorkers(workers: Int): State =
      f.tech match
        case TechState.Traditional(_) => f.copy(tech = TechState.Traditional(workers))
        case TechState.Hybrid(_, eff) => f.copy(tech = TechState.Hybrid(workers, eff))
        case _                        => f
    val sectorDemand                     = operationalSignals.sectorDemandMult(f.sector.toInt)
    val hiringSignal                     = operationalSignals.sectorHiringSignal(f.sector.toInt)
    val demandMult                       = sectorDemand + (hiringSignal - sectorDemand).max(Multiplier.Zero) * HiringPressureBlend
    val price                            = w.priceLevel.toMultiplier
    val wage                             = w.householdMarket.marketWage * effectiveWageMult(f.sector)
    val workers                          = workerCount(f)
    val minW                             = p.firm.minWorkersRetained
    val maxW                             = f.initialSize * 3

    var lo = minW
    var hi = maxW
    while lo < hi do
      val mid     = (lo + hi + 1) / 2
      val capMid  = computeEffectiveCapacity(withWorkers(mid), w.real.productivityIndex)
      val capPrev = computeEffectiveCapacity(withWorkers(mid - 1), w.real.productivityIndex)
      val mr      = (capMid - capPrev) * (demandMult * price)
      if mr > wage then lo = mid else hi = mid - 1
    applyOperationalHiringSlack(workers, lo, minW, operationalSignals.operationalHiringSlack.toMultiplier)

  /** Maximum planned gross hires for a firm in one month. */
  def monthlyHiringHeadroom(workers: Int): Int =
    if workers <= 5 then 1
    else if workers <= 10 then SmallFirmDesiredAddCap
    else if workers <= 25 then MidFirmDesiredAddCap
    else Math.max(MidFirmDesiredAddCap, LargeFirmDesiredGrowthShare.ceilApplyTo(workers))

  /** Minimum worker gap required before the firm changes headcount. */
  def hiringAdjustmentThreshold(workers: Int): Int =
    if workers <= 5 then MicroFirmHiringThreshold
    else Math.min(monthlyHiringHeadroom(workers), Math.max(2, workers / 10))

  /** Number of positive demand-signal months required before hiring. */
  def requiredHiringSignalMonths(workers: Int): Int =
    if workers <= 5 then 1 else HiringSignalPersistenceMonths

  /** Updates persistent positive hiring signal state. */
  def nextHiringSignalMonths(firm: State, desired: Int, workers: Int): Int =
    if desired > workers then firm.hiringSignalMonths + 1 else 0

  /** Converts fractional workforce adjustment into an integer draw. */
  def stochasticAdjustmentMagnitude(absGap: Int, adjustFrac: Share, rng: RandomStream): AdjustmentMagnitude =
    if absGap <= 0 then AdjustmentMagnitude(0, None, None)
    else
      val expectedRaw  = BigInt(adjustFrac.toLong) * BigInt(absGap)
      val whole        = (expectedRaw / BigInt(FixedPointBase.Scale)).toInt
      val residualRaw  = (expectedRaw % BigInt(FixedPointBase.Scale)).toLong
      val residualP    = if residualRaw > 0L then Some(Share.fromRaw(residualRaw)) else None
      val residualRoll = residualP.map(_ => Share.random(rng))
      val residual     = if residualP.exists(p => residualRoll.exists(_ < p)) then 1 else 0
      AdjustmentMagnitude(Math.min(absGap, whole + residual), residualP, residualRoll)

  /** Hiring adjustment speed bounded to a probability-like share. */
  def hiringAdjustFrac(using p: SimParams): Share =
    p.firm.laborAdjustSpeed.toShare.clamp(Share.Zero, Share.One)

  /** Firing adjustment speed, softened for state-owned firms. */
  def firingAdjustFrac(firm: State)(using p: SimParams): Share =
    if firm.stateOwned then FiringAdjustFrac * StateOwned.firingReduction else FiringAdjustFrac

  /** Near-term feasible workforce after persistence and liquidity constraints.
    */
  def feasibleWorkers(
      firm: State,
      workers: Int,
      desired: Int,
      pnl: PnL,
      cashAfterDecision: PLN,
  )(using p: SimParams): Int =
    if desired <= workers then desired
    else
      val signalMonths  = nextHiringSignalMonths(firm, desired, workers)
      val persistenceOk = signalMonths >= requiredHiringSignalMonths(workers) || isInStartup(firm)
      if !persistenceOk then workers
      else
        val headroom                = monthlyHiringHeadroom(workers)
        val structurallyConstrained = Math.min(desired, workers + headroom)
        val liquidityConstrained    =
          if firm.stateOwned || cashAfterDecision >= PLN.Zero then structurallyConstrained
          else if isInStartup(firm) && cashAfterDecision.abs <= startupRunwayLimit(firm) then structurallyConstrained
          else if pnl.netAfterTax > PLN.Zero then workers + Math.max(1, NegativeCashHiringPenalty.floorApplyTo(headroom))
          else workers
        Math.max(workers, liquidityConstrained)

  /** Compresses positive hiring plans when aggregate labor slack is binding. */
  def applyOperationalHiringSlack(currentWorkers: Int, rawTarget: Int, minWorkers: Int, slackFactor: Multiplier): Int =
    if rawTarget <= currentWorkers then Math.max(minWorkers, rawTarget)
    else
      val positiveGap = rawTarget - currentWorkers
      val scaledGap   = FixedPointBase.multiplyRaw(positiveGap.toLong, slackFactor.clamp(Multiplier.Zero, Multiplier.One).toLong).toInt
      Math.max(minWorkers, currentWorkers + scaledGap)

  /** Snapshot of the current labor-planning surface for diagnostics. */
  def hiringDiagnostics(
      firm: State,
      financialStocks: FinancialStocks,
      w: World,
      operationalSignals: OperationalSignals,
  )(using p: SimParams): Firm.HiringDiagnostics =
    val workers         = workerCount(firm)
    val desiredW        = desiredWorkers(firm, w, operationalSignals)
    val nc              = financialStocks.cash
    val feasibleW       = feasibleWorkers(firm, workers, desiredW, PnL.zero, nc)
    val desiredGap      = desiredW - workers
    val feasibleGap     = feasibleW - workers
    val hiringThresh    = hiringAdjustmentThreshold(workers)
    val firingThresh    = Math.max(2, workers / 10)
    val shouldAdjust    = if feasibleGap > 0 then feasibleGap >= hiringThresh else -feasibleGap >= firingThresh
    val proposedAdjust  =
      if shouldAdjust then
        val adjustFrac = if feasibleGap > 0 then hiringAdjustFrac else firingAdjustFrac(firm)
        Math.max(1, adjustFrac.floorApplyTo(Math.abs(feasibleGap))) * (if feasibleGap > 0 then 1 else -1)
      else 0
    val proposedWorkers = (workers + proposedAdjust).max(p.firm.minWorkersRetained)
    Firm.HiringDiagnostics(
      workers = workers,
      desiredWorkers = desiredW,
      feasibleWorkers = feasibleW,
      desiredGap = desiredGap,
      feasibleGap = feasibleGap,
      hiringThreshold = hiringThresh,
      firingThreshold = firingThresh,
      shouldAdjust = shouldAdjust,
      proposedAdjustment = proposedAdjust,
      proposedWorkers = proposedWorkers,
      signalMonths = nextHiringSignalMonths(firm, desiredW, workers),
      requiredSignalMonths = requiredHiringSignalMonths(workers),
    )
