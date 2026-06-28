package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.agents.TechState
import com.boombustgroup.amorfati.agents.Firm.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.amorfati.agents.firm.FirmCalibration.*

/** Production, capacity, and structural labor-scale calculations for firms. */
private[agents] object FirmProduction:

  /** True unless the firm is in `Bankrupt` tech state. */
  def isAlive(f: State): Boolean = f.tech match
    case _: TechState.Bankrupt => false
    case _                     => true

  /** Headcount implied by the current technology regime. */
  def workerCount(f: State)(using SimParams): Int = f.tech match
    case TechState.Traditional(w) => w
    case TechState.Hybrid(w, _)   => w
    case _: TechState.Automated   => skeletonCrew(f)
    case _: TechState.Bankrupt    => 0

  /** Skeleton crew for automated firms, scaled by initial firm size. */
  def skeletonCrew(f: State)(using p: SimParams): Int =
    Math.max(p.firm.autoSkeletonCrew, SkeletonCrewFrac.floorApplyTo(f.initialSize))

  /** Whether the firm is still in its startup ramp-up window. */
  def isInStartup(f: State): Boolean =
    f.startupMonthsLeft > 0

  /** Structural operating scale used for capital planning. */
  def capitalPlanningWorkers(f: State)(using p: SimParams): Int =
    if !isAlive(f) then 0
    else
      val workers = workerCount(f)
      if f.startupTargetWorkers > 0 && workers < f.initialSize then Math.max(workers, f.startupTargetWorkers)
      else Math.max(workers, f.initialSize)

  /** Effective wage multiplier including union wage premium. */
  def effectiveWageMult(sectorIdx: SectorIdx)(using p: SimParams): Multiplier =
    val base = p.sectorDefs(sectorIdx.toInt).wageMultiplier
    base + base * (p.labor.unionWagePremium * p.labor.unionDensity(sectorIdx.toInt))

  /** Monthly production capacity in PLN before runtime productivity scaling. */
  def computeCapacity(f: State)(using p: SimParams): PLN =
    val sec       = p.sectorDefs(f.sector.toInt)
    val sizeScale = Scalar.fraction(f.initialSize, p.pop.workersPerFirm).toMultiplier
    val laborEff  = f.tech match
      case TechState.Traditional(w) => Scalar.fraction(w, f.initialSize).toMultiplier
      case TechState.Hybrid(w, eff) =>
        HybridLaborCapShare.toMultiplier * Scalar.fraction(w, f.initialSize).toMultiplier + HybridAiCapShare.toMultiplier * eff
      case TechState.Automated(eff) => eff
      case _: TechState.Bankrupt    => Multiplier.Zero
    val tfp       = sizeScale * sec.revenueMultiplier
    if f.capitalStock > PLN.Zero && laborEff > Multiplier.Zero then
      val targetK: PLN  = capitalPlanningWorkers(f) * p.capital.klRatios(f.sector.toInt)
      val k: Multiplier =
        (if targetK > PLN.Zero then f.capitalStock.ratioTo(targetK).toMultiplier else Multiplier.One).clamp(Multiplier.decimal(1, 1), Multiplier(2))
      val alpha: Share  = p.capital.prodElast
      p.firm.baseRevenue * tfp * cesOutput(alpha, k, laborEff, sec.sigma)
    else p.firm.baseRevenue * tfp * laborEff

  /** Monthly production capacity after runtime productivity scaling. */
  def computeEffectiveCapacity(f: State, productivityIndex: Multiplier)(using p: SimParams): PLN =
    computeCapacity(f) * productivityIndex

  /** Capacity evaluator for repeated hypothetical worker-count probes.
    *
    * Labor planning uses this during binary search. It keeps the public
    * `computeCapacity` semantics, but avoids rebuilding synthetic firm states
    * for every midpoint and reuses firm-level production constants.
    */
  final class CapacityEvaluator private[firm] (f: State, productivityIndex: Multiplier, p: SimParams):
    private val sec                         = p.sectorDefs(f.sector.toInt)
    private val sizeScale                   = Scalar.fraction(f.initialSize, p.pop.workersPerFirm).toMultiplier
    private val tfp                         = sizeScale * sec.revenueMultiplier
    private val baseRevenue                 = p.firm.baseRevenue * tfp
    private val hasCapital                  = f.capitalStock > PLN.Zero
    private val sectorKlRatio               = p.capital.klRatios(f.sector.toInt)
    private val alpha                       = p.capital.prodElast
    private val beta                        = Share.One - alpha
    private val alphaScalar                 = alpha.toScalar
    private val betaScalar                  = beta.toScalar
    private val useCobbDouglas              = !(sec.sigma > Sigma.decimal(1001, 3))
    private val supportsWorkerOverride      = f.tech match
      case _: TechState.Traditional | _: TechState.Hybrid => true
      case _                                              => false
    private lazy val fixedEffectiveCapacity = computeEffectiveCapacity(f, productivityIndex)(using p)

    private lazy val rho           = (sec.sigma.toScalar - Scalar.One).ratioTo(sec.sigma.toScalar)
    private lazy val rhoReciprocal = rho.reciprocal

    def capacityAtWorkers(workers: Int): PLN =
      if supportsWorkerOverride then capacityFor(laborEffAt(workers), capitalPlanningWorkersAt(workers))
      else computeCapacity(f)(using p)

    def effectiveCapacityAtWorkers(workers: Int): PLN =
      if supportsWorkerOverride then capacityAtWorkers(workers) * productivityIndex
      else fixedEffectiveCapacity

    def marginalEffectiveCapacityAtWorkers(workers: Int): PLN =
      if !supportsWorkerOverride then PLN.Zero
      else
        val prevWorkers = workers - 1
        val laborEff    = laborEffAt(workers)
        val prevLabor   = laborEffAt(prevWorkers)
        val planning    = capitalPlanningWorkersAt(workers)
        val prevPlan    = capitalPlanningWorkersAt(prevWorkers)
        val (cap, prev) =
          if hasCapital && planning == prevPlan then capacitiesWithSharedCapital(laborEff, prevLabor, planning)
          else (capacityFor(laborEff, planning), capacityFor(prevLabor, prevPlan))
        cap * productivityIndex - prev * productivityIndex

    private def laborEffAt(workers: Int): Multiplier =
      f.tech match
        case TechState.Traditional(_) => Scalar.fraction(workers, f.initialSize).toMultiplier
        case TechState.Hybrid(_, eff) =>
          HybridLaborCapShare.toMultiplier * Scalar.fraction(workers, f.initialSize).toMultiplier + HybridAiCapShare.toMultiplier * eff
        case TechState.Automated(eff) => eff
        case _: TechState.Bankrupt    => Multiplier.Zero

    private def capitalPlanningWorkersAt(workers: Int): Int =
      if !isAlive(f) then 0
      else if f.startupTargetWorkers > 0 && workers < f.initialSize then Math.max(workers, f.startupTargetWorkers)
      else Math.max(workers, f.initialSize)

    private def capitalMultiplier(planningWorkers: Int): Multiplier =
      val targetK: PLN = planningWorkers * sectorKlRatio
      (if targetK > PLN.Zero then f.capitalStock.ratioTo(targetK).toMultiplier else Multiplier.One).clamp(Multiplier.decimal(1, 1), Multiplier(2))

    private def capacityFor(laborEff: Multiplier, planningWorkers: Int): PLN =
      if hasCapital && laborEff > Multiplier.Zero then baseRevenue * cesOutput(alpha, capitalMultiplier(planningWorkers), laborEff, sec.sigma)
      else baseRevenue * laborEff

    private def capacitiesWithSharedCapital(laborEff: Multiplier, prevLaborEff: Multiplier, planningWorkers: Int): (PLN, PLN) =
      val k = capitalMultiplier(planningWorkers)
      if useCobbDouglas then
        val kTerm = k.pow(alphaScalar)
        (capacityForSharedCobbDouglas(laborEff, kTerm), capacityForSharedCobbDouglas(prevLaborEff, kTerm))
      else
        val kTerm = alpha * k.pow(rho)
        (capacityForSharedCes(laborEff, kTerm), capacityForSharedCes(prevLaborEff, kTerm))

    private def capacityForSharedCobbDouglas(laborEff: Multiplier, kTerm: Multiplier): PLN =
      if laborEff > Multiplier.Zero then baseRevenue * (kTerm * laborEff.pow(betaScalar))
      else baseRevenue * laborEff

    private def capacityForSharedCes(laborEff: Multiplier, kTerm: Multiplier): PLN =
      if laborEff > Multiplier.Zero then
        val lTerm = beta * laborEff.pow(rho)
        baseRevenue * (kTerm + lTerm).pow(rhoReciprocal)
      else baseRevenue * laborEff

  def capacityEvaluator(f: State, productivityIndex: Multiplier)(using p: SimParams): CapacityEvaluator =
    new CapacityEvaluator(f, productivityIndex, p)

  def computeCapacityAtWorkers(f: State, workers: Int)(using p: SimParams): PLN =
    capacityEvaluator(f, Multiplier.One).capacityAtWorkers(workers)

  def computeMarginalEffectiveCapacityAtWorkers(f: State, workers: Int, productivityIndex: Multiplier)(using p: SimParams): PLN =
    capacityEvaluator(f, productivityIndex).marginalEffectiveCapacityAtWorkers(workers)

  /** CES aggregator for physical-capital and labor/technology productivity. */
  def cesOutput(alpha: Share, k: Multiplier, l: Multiplier, sigma: Sigma): Multiplier =
    if !(sigma > Sigma.decimal(1001, 3)) then k.pow(alpha.toScalar) * l.pow((Share.One - alpha).toScalar)
    else
      val rho   = (sigma.toScalar - Scalar.One).ratioTo(sigma.toScalar)
      val kTerm = alpha * k.pow(rho)
      val lTerm = (Share.One - alpha) * l.pow(rho)
      (kTerm + lTerm).pow(rho.reciprocal)
