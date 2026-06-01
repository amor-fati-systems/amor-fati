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

  /** CES aggregator for physical-capital and labor/technology productivity. */
  def cesOutput(alpha: Share, k: Multiplier, l: Multiplier, sigma: Sigma): Multiplier =
    if !(sigma > Sigma.decimal(1001, 3)) then k.pow(alpha.toScalar) * l.pow((Share.One - alpha).toScalar)
    else
      val rho   = (sigma.toScalar - Scalar.One).ratioTo(sigma.toScalar)
      val kTerm = alpha * k.pow(rho)
      val lTerm = (Share.One - alpha) * l.pow(rho)
      (kTerm + lTerm).pow(rho.reciprocal)
