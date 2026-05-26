package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.engine.markets.LaborMarket
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Startup hiring and state synchronization for newly entered firms. */
object StartupStaffing:

  final case class Result(
      firms: Vector[Firm.State],
      households: Vector[Household.State],
      hhAgg: Household.Aggregates,
      crossSectorHires: Int,
      startupAbsorptionRate: Share,
  )

  def assign(
      in: WorldAssemblyEconomics.StepInput,
      firms: Vector[Firm.State],
      households: Vector[Household.State],
      rng: RandomStream,
  )(using p: SimParams): Result =
    val startupIds = firms.filter(f => Firm.isAlive(f) && Firm.isInStartup(f)).map(_.id).toSet
    if startupIds.isEmpty then Result(sync(firms, households), households, in.s9.finalHhAgg, 0, Share.One)
    else
      val startupOpeningsBefore = firms
        .filter(f => Firm.isAlive(f) && Firm.isInStartup(f))
        .map(f => Math.max(0, f.startupTargetWorkers - f.startupFilledWorkers))
        .sum
      val startupFilledBefore   = firms
        .filter(f => Firm.isAlive(f) && Firm.isInStartup(f))
        .map(_.startupFilledWorkers)
        .sum
      val maxHires              = Math.max(0, in.s5.postFirmHireCapacity - in.s5.postFirmHires)
      val searchResult          = LaborMarket.jobSearch(households, firms, in.s2.newWage, rng, in.s2.regionalWages, startupIds, Some(maxHires))
      val postWages             = LaborMarket.updateWages(searchResult.households, firms, in.s2.newWage)
      val staffedFirms          = sync(firms, postWages)
      val startupFilled         = staffedFirms.filter(f => Firm.isAlive(f) && Firm.isInStartup(f)).map(_.startupFilledWorkers).sum
      val startupHires          = Math.max(0, startupFilled - startupFilledBefore)
      val startupAbsorptionRate =
        if startupOpeningsBefore > 0 then Share.fraction(startupHires, startupOpeningsBefore)
        else Share.One
      val hhAgg                 = Household
        .computeAggregates(
          postWages,
          in.s9.ledgerFinancialState.households.map(LedgerFinancialState.projectHouseholdFinancialStocks),
          in.s2.newWage,
          in.s1.resWage,
          in.s3.importAdj,
          in.s3.hhAgg.retrainingAttempts,
          in.s3.hhAgg.retrainingSuccesses,
        )
        .withFlowTotalsFrom(in.s9.finalHhAgg)
      Result(staffedFirms, postWages, hhAgg, searchResult.crossSectorHires, startupAbsorptionRate)

  def sync(
      firms: Vector[Firm.State],
      households: Vector[Household.State],
  ): Vector[Firm.State] =
    val staffedCounts = households
      .flatMap: hh =>
        hh.status match
          case HhStatus.Employed(fid, _, _) => Some(fid)
          case _                            => None
      .groupMapReduce(identity)(_ => 1)(_ + _)
    firms.map: firm =>
      if Firm.isInStartup(firm) then
        val filled     = staffedCounts.getOrElse(firm.id, 0).min(firm.startupTargetWorkers)
        val syncedTech = firm.tech match
          case TechState.Traditional(_) => TechState.Traditional(filled)
          case TechState.Hybrid(_, eff) => TechState.Hybrid(filled, eff)
          case other                    => other
        firm.copy(startupFilledWorkers = filled, tech = syncedTech)
      else firm
