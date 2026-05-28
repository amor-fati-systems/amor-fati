package com.boombustgroup.amorfati.engine.mechanisms

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.agents.RegionalMigration
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Post-month agent-population lifecycle: FDI ownership changes, firm entry,
  * startup staffing, and regional household migration.
  */
object PopulationLifecycleTransitions:

  final case class AgentStocks(
      firms: Vector[Firm.State],
      firmFinancialBalances: Vector[LedgerFinancialState.FirmBalances],
      households: Vector[Household.State],
      householdFinancialBalances: Vector[LedgerFinancialState.HouseholdBalances],
  )

  final case class EntryContext(
      automationRatio: Share,
      hybridRatio: Share,
      laggedEntrySignals: FirmEntry.LaggedEntrySignals,
  )

  final case class StartupStaffingContext(
      marketWage: PLN,
      reservationWage: PLN,
      importAdjustment: Share,
      regionalWages: Map[Region, PLN],
      remainingHireCapacity: Int,
      retrainingAttempts: Int,
      retrainingSuccesses: Int,
      householdFlowTotals: Household.Aggregates,
  )

  final case class RegionalMigrationContext(
      regionalWages: Map[Region, PLN],
  )

  final case class Input(
      stocks: AgentStocks,
      entry: EntryContext,
      startupStaffing: StartupStaffingContext,
      regionalMigration: RegionalMigrationContext,
  )

  final case class Result(
      firms: Vector[Firm.State],
      households: Vector[Household.State],
      householdAggregates: Household.Aggregates,
      firmFinancialStocks: Vector[Firm.FinancialStocks],
      newFirmIds: Set[FirmId],
      births: Int,
      netBirths: Int,
      automationTransitions: FirmEntry.AutomationEntryTransitions,
      crossSectorHires: Int,
      startupAbsorptionRate: Share,
  )

  def run(
      in: Input,
      fdiMaRng: RandomStream,
      firmEntryRng: RandomStream,
      startupStaffingRng: RandomStream,
      regionalMigrationRng: RandomStream,
  )(using p: SimParams): Result =
    val postFdiFirms               = FdiOwnershipTransitions(in.stocks.firms, fdiMaRng)
    val postFdiFirmFinancialStocks = in.stocks.firmFinancialBalances.map(LedgerFinancialState.projectFirmFinancialStocks)
    val entryStep                  = FirmEntry.process(
      postFdiFirms,
      postFdiFirmFinancialStocks,
      in.entry.automationRatio,
      in.entry.hybridRatio,
      in.entry.laggedEntrySignals,
      firmEntryRng,
    )

    val startupStaffing = StartupStaffing.assign(
      StartupStaffing.Input(
        firms = entryStep.firms,
        households = in.stocks.households,
        householdFinancialStocks = in.stocks.householdFinancialBalances.map(LedgerFinancialState.projectHouseholdFinancialStocks),
        marketWage = in.startupStaffing.marketWage,
        reservationWage = in.startupStaffing.reservationWage,
        importAdjustment = in.startupStaffing.importAdjustment,
        regionalWages = in.startupStaffing.regionalWages,
        remainingHireCapacity = in.startupStaffing.remainingHireCapacity,
        retrainingAttempts = in.startupStaffing.retrainingAttempts,
        retrainingSuccesses = in.startupStaffing.retrainingSuccesses,
        householdFlowTotals = in.startupStaffing.householdFlowTotals,
      ),
      startupStaffingRng,
    )

    val postMigrationHouseholds = RegionalMigration(startupStaffing.households, in.regionalMigration.regionalWages, regionalMigrationRng).households
    val finalFirms              = StartupStaffing.sync(startupStaffing.firms, postMigrationHouseholds)

    Result(
      firms = finalFirms,
      households = postMigrationHouseholds,
      householdAggregates = startupStaffing.hhAgg,
      firmFinancialStocks = entryStep.financialStocks,
      newFirmIds = entryStep.newFirmIds,
      births = entryStep.births,
      netBirths = entryStep.netBirths,
      automationTransitions = entryStep.automationTransitions,
      crossSectorHires = startupStaffing.crossSectorHires,
      startupAbsorptionRate = startupStaffing.startupAbsorptionRate,
    )
