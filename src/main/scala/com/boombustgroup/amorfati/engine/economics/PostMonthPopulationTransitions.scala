package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.agents.RegionalMigration
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.{MonthRandomness, World}
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.engine.mechanisms.{FirmEntry, SectoralMobility}
import com.boombustgroup.amorfati.types.Share

/** Post-month population transitions that complete firm/household state after
  * the World value has been assembled from stage outputs.
  */
object PostMonthPopulationTransitions:

  final case class Result(
      world: World,
      firms: Vector[Firm.State],
      households: Vector[Household.State],
      householdAggregates: Household.Aggregates,
      ledgerFinancialState: LedgerFinancialState,
      startupAbsorptionRate: Share,
  )

  def run(
      in: WorldAssemblyEconomics.StepInput,
      world: World,
      randomness: MonthRandomness.AssemblyStreams,
  )(using p: SimParams): Result =
    val postFdiFirms               = FdiOwnershipTransitions(in.s9.reassignedFirms, randomness.fdiMa)
    val postFdiFirmFinancialStocks = in.s9.ledgerFinancialState.firms.map(LedgerFinancialState.projectFirmFinancialStocks)
    val entryStep                  = FirmEntry.process(
      postFdiFirms,
      postFdiFirmFinancialStocks,
      world.real.automationRatio,
      world.real.hybridRatio,
      FirmEntry.LaggedEntrySignals.fromDecisionSignals(in.w.seedIn),
      randomness.firmEntry,
    )
    val entryAutomationTransitions = FirmEntryTransitions.automationEntryTransitions(entryStep.firms, entryStep.newFirmIds)

    val startupStaffing = StartupStaffing.assign(in, entryStep.firms, in.s9.reassignedHouseholds, randomness.startupStaffing)

    val postMigHh             = RegionalMigration(startupStaffing.households, in.s2.regionalWages, randomness.regionalMigration).households
    val finalFirms            = StartupStaffing.sync(startupStaffing.firms, postMigHh)
    val finalFlows            = world.flows.copy(
      firmBirths = entryStep.births,
      firmDeaths = in.s5.firmDeaths,
      netFirmBirths = entryStep.netBirths,
      automationNewFullAi = world.flows.automationNewFullAi + entryAutomationTransitions.newFullAi,
      automationNewHybrid = world.flows.automationNewHybrid + entryAutomationTransitions.newHybrid,
    )
    val finalCrossSectorHires = world.real.sectoralMobility.crossSectorHires + startupStaffing.crossSectorHires
    val finalReal             = world.real.copy(
      sectoralMobility = world.real.sectoralMobility.copy(
        crossSectorHires = finalCrossSectorHires,
        sectorMobilityRate = SectoralMobility.mobilityRate(finalCrossSectorHires, startupStaffing.hhAgg.employed),
      ),
    )
    val finalWorld            = world.copy(
      pipeline = PostMonthPipelineState.build(in),
      flows = finalFlows,
      real = finalReal,
      regionalWages = in.s2.regionalWages,
    )
    val finalLedger           = in.s9.ledgerFinancialState.copy(
      firms = LedgerFinancialState.refreshFirmPopulationBalances(entryStep.financialStocks, in.s9.ledgerFinancialState.firms, entryStep.newFirmIds),
    )
    Result(
      finalWorld,
      finalFirms,
      postMigHh,
      startupStaffing.hhAgg,
      finalLedger,
      startupStaffing.startupAbsorptionRate,
    )
