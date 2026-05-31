package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.{HhStatus, Household}
import com.boombustgroup.amorfati.agents.household.HouseholdParameters.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.mechanisms.SectoralMobility
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Voluntary search and retraining transitions applied after household budget
  * flows have been computed.
  */
private[agents] object HouseholdLaborTransitions:

  /** Attempts voluntary cross-sector search for an employed household. */
  def tryVoluntarySearch(
      financialStocks: Household.FinancialStocks,
      status: HhStatus.Employed,
      laborContext: HouseholdLaborTransitionContext,
      rng: RandomStream,
  )(using p: SimParams): (HhStatus, Int) =
    if !p.labor.voluntarySearchProb.sampleBelow(rng) then return (status, 0)
    val sectorWages       = laborContext.sectorWages
    val sectorVacancies   = laborContext.sectorVacancies
    val targetSector      =
      SectoralMobility.selectTargetSector(status.sectorIdx.toInt, sectorWages, sectorVacancies, p.labor.frictionMatrix, p.labor.vacancyWeight, rng)
    if sectorVacancies(targetSector) <= 0 then return (status, 0)
    val targetAvgWage     = sectorWages(targetSector)
    val friction          = p.labor.frictionMatrix(status.sectorIdx.toInt)(targetSector)
    val frictionNetWage   = targetAvgWage * SectoralMobility.crossSectorWagePenalty(friction)
    val wageThresholdMult = Multiplier.One + p.labor.voluntaryWageThreshold.toMultiplier
    if frictionNetWage <= status.wage * wageThresholdMult then return (status, 0)
    if friction <= p.labor.adjacentFrictionMax then
      val rp = SectoralMobility.frictionAdjustedParams(friction, p.labor.frictionDurationMult, p.labor.frictionCostMult)
      if financialStocks.demandDeposit > rp.cost then (HhStatus.Retraining(rp.duration, SectorIdx(targetSector), rp.cost), 1)
      else (status, 0)
    else (status, 0)

  /** Attempts retraining entry or advances an existing retraining spell. */
  def tryRetraining(
      hh: Household.State,
      financialStocks: Household.FinancialStocks,
      status: HhStatus,
      neighborDistress: Share,
      laborContext: Option[HouseholdLaborTransitionContext],
      rng: RandomStream,
  )(using p: SimParams): (HhStatus, Int, Int) =
    status match
      case HhStatus.Unemployed(months) if months > UnemploymentRetrainingThreshold && p.household.retrainingEnabled =>
        val retrainProb = p.household.retrainingProb +
          (if neighborDistress > NeighborDistressThreshold then NeighborDistressRetrainBoost else Share.Zero)
        if financialStocks.demandDeposit > p.household.retrainingCost && retrainProb.sampleBelow(rng) then
          laborContext match
            case Some(context) =>
              val sw           = context.sectorWages
              val sv           = context.sectorVacancies
              val fromSector   = if hh.lastSectorIdx.toInt >= 0 then hh.lastSectorIdx.toInt else 0
              val targetSector = SectoralMobility.selectTargetSector(fromSector, sw, sv, p.labor.frictionMatrix, p.labor.vacancyWeight, rng)
              val friction     = p.labor.frictionMatrix(fromSector)(targetSector)
              val rp           = SectoralMobility.frictionAdjustedParams(friction, p.labor.frictionDurationMult, p.labor.frictionCostMult)
              if financialStocks.demandDeposit > rp.cost then (HhStatus.Retraining(rp.duration, SectorIdx(targetSector), rp.cost), 1, 0)
              else (status, 0, 0)
            case None          =>
              val targetSector = rng.nextInt(p.sectorDefs.length)
              (HhStatus.Retraining(p.household.retrainingDuration, SectorIdx(targetSector), p.household.retrainingCost), 1, 0)
        else (status, 0, 0)

      case HhStatus.Retraining(monthsLeft, targetSector, cost) =>
        if monthsLeft <= 1 then
          val afterSkill      = HouseholdDistressMachine.applySkillDecay(hh, status)
          val afterHealth     = HouseholdDistressMachine.applyHealthScarring(hh, status)
          val skillHealthProb = p.household.retrainingBaseSuccess * afterSkill * (Share.One - afterHealth)
          val eduMult         = p.social.eduRetrainMultiplier(hh.education)
          val baseSuccessProb = (skillHealthProb * eduMult).toShare
          val successProb     =
            val fromSector = if hh.lastSectorIdx.toInt >= 0 then hh.lastSectorIdx.toInt else 0
            val friction   = p.labor.frictionMatrix(fromSector)(targetSector.toInt)
            baseSuccessProb * (Share.One - friction * SectoralMobility.FrictionSuccessDiscount)
          if successProb.sampleBelow(rng) then (HhStatus.Unemployed(0), 0, 1)
          else (HhStatus.Unemployed(PostFailedRetrainingMonths), 0, 0)
        else (HhStatus.Retraining(monthsLeft - 1, targetSector, cost), 0, 0)

      case _ => (status, 0, 0)
