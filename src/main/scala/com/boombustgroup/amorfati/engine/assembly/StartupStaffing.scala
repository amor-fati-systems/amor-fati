package com.boombustgroup.amorfati.engine.assembly

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

  private final case class StartupScan(
      eligibleIds: Set[FirmId],
      allStartupIndices: Vector[Int],
      eligibleStartupIndices: Vector[Int],
      openingsBefore: Int,
      filledBefore: Int,
  )

  private final case class SyncResult(
      firms: Vector[Firm.State],
      filled: Int,
  )

  def assign(
      in: WorldAssemblyEconomics.StepInput,
      firms: Vector[Firm.State],
      households: Vector[Household.State],
      rng: RandomStream,
  )(using p: SimParams): Result =
    val startupScan = scanStartups(firms)
    if startupScan.eligibleIds.isEmpty then
      val synced = syncWithStartupIndices(firms, households, startupScan.allStartupIndices, startupScan.allStartupIndices)
      Result(synced.firms, households, in.s9.finalHhAgg, 0, Share.One)
    else
      val maxHires              = Math.max(0, in.s5.postFirmHireCapacity - in.s5.postFirmHires)
      val searchResult          = LaborMarket.jobSearch(households, firms, in.s2.newWage, rng, in.s2.regionalWages, startupScan.eligibleIds, Some(maxHires))
      val postWages             = LaborMarket.updateWages(searchResult.households, firms, in.s2.newWage)
      val synced                = syncWithStartupIndices(firms, postWages, startupScan.allStartupIndices, startupScan.eligibleStartupIndices)
      val startupHires          = Math.max(0, synced.filled - startupScan.filledBefore)
      val startupAbsorptionRate =
        if startupScan.openingsBefore > 0 then Share.fraction(startupHires, startupScan.openingsBefore)
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
      Result(synced.firms, postWages, hhAgg, searchResult.crossSectorHires, startupAbsorptionRate)

  def sync(
      firms: Vector[Firm.State],
      households: Vector[Household.State],
  ): Vector[Firm.State] =
    val startupScan = scanStartups(firms)
    syncWithStartupIndices(firms, households, startupScan.allStartupIndices, startupScan.allStartupIndices).firms

  private def scanStartups(firms: Vector[Firm.State]): StartupScan =
    val eligibleIds            = Set.newBuilder[FirmId]
    val allStartupIndices      = Vector.newBuilder[Int]
    val eligibleStartupIndices = Vector.newBuilder[Int]
    var openingsBefore         = 0
    var filledBefore           = 0
    var i                      = 0

    while i < firms.length do
      val firm = firms(i)
      if Firm.isInStartup(firm) then
        allStartupIndices += i
        if Firm.isAlive(firm) then
          eligibleIds += firm.id
          eligibleStartupIndices += i
          openingsBefore += Math.max(0, firm.startupTargetWorkers - firm.startupFilledWorkers)
          filledBefore += firm.startupFilledWorkers
      i += 1

    StartupScan(
      eligibleIds.result(),
      allStartupIndices.result(),
      eligibleStartupIndices.result(),
      openingsBefore,
      filledBefore,
    )

  private def syncWithStartupIndices(
      firms: Vector[Firm.State],
      households: Vector[Household.State],
      startupIndices: Vector[Int],
      filledCountIndices: Vector[Int],
  ): SyncResult =
    if startupIndices.isEmpty then SyncResult(firms, 0)
    else
      val staffedCounts = scala.collection.mutable.HashMap.empty[FirmId, Int]
      var hhIndex       = 0
      while hhIndex < households.length do
        households(hhIndex).status match
          case HhStatus.Employed(fid, _, _) =>
            staffedCounts.update(fid, staffedCounts.getOrElse(fid, 0) + 1)
          case _                            =>
        hhIndex += 1

      val synced            = Vector.newBuilder[Firm.State]
      synced.sizeHint(firms.length)
      var startupCursor     = 0
      var filledCountCursor = 0
      var filledTotal       = 0
      var firmIndex         = 0
      while firmIndex < firms.length do
        if startupCursor < startupIndices.length && startupIndices(startupCursor) == firmIndex then
          val firm       = firms(firmIndex)
          val filled     = staffedCounts.getOrElse(firm.id, 0).min(firm.startupTargetWorkers)
          val syncedTech = firm.tech match
            case TechState.Traditional(_) => TechState.Traditional(filled)
            case TechState.Hybrid(_, eff) => TechState.Hybrid(filled, eff)
            case other                    => other
          synced += firm.copy(startupFilledWorkers = filled, tech = syncedTech)
          if filledCountCursor < filledCountIndices.length && filledCountIndices(filledCountCursor) == firmIndex then
            filledTotal += filled
            filledCountCursor += 1
          startupCursor += 1
        else synced += firms(firmIndex)
        firmIndex += 1

      SyncResult(synced.result(), filledTotal)
