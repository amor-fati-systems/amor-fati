package com.boombustgroup.amorfati.engine.closedmonth

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.engine.mechanisms.{FirmEntry, InformalEconomy, PopulationLifecycleTransitions}

/** Explicit month-closing boundary.
  *
  * Domain mechanisms and diagnostic mappers live in focused modules. This
  * object owns only the month-closing contract and the top-level ordering of
  * month closing.
  */
object MonthClosing:

  def prepareInput(execution: MonthExecution)(using p: SimParams): MonthClosingInput =
    prepareInput(MonthClosingStateInput.fromExecution(execution))

  def prepareInput(state: MonthClosingStateInput)(using p: SimParams): MonthClosingInput =
    MonthClosingInput(
      closingState = state,
      mechanisms = MonthClosingMechanisms(
        informalEconomy = InformalEconomy.compute(informalInput(state)),
      ),
      diagnostics = MonthClosingDiagnostics(
        flowOfFundsResidual = FlowOfFundsDiagnostics.residual(FlowOfFundsDiagnostics.Input.fromClosingState(state)),
      ),
      agentLifecycle = populationLifecycleInput(state),
    )

  def closeExecution(
      execution: MonthExecution,
      randomness: MonthRandomness.ClosingStreams,
  )(using p: SimParams): MonthClosingResult =
    close(prepareInput(execution), randomness)

  def close(
      closingInput: MonthClosingInput,
      randomness: MonthRandomness.ClosingStreams,
  )(using p: SimParams): MonthClosingResult =
    val state       = closingInput.closingState
    val lifecycle   = PopulationLifecycleTransitions.run(
      closingInput.agentLifecycle,
      fdiMaRng = randomness.fdiMa,
      firmEntryRng = randomness.firmEntry,
      startupStaffingRng = randomness.startupStaffing,
      regionalMigrationRng = randomness.regionalMigration,
    )
    val finalWorld  = WorldStateAssembler.assemble(closingInput, lifecycle)
    val finalLedger = state.banking.ledgerFinancialState.copy(
      firms = LedgerFinancialState.refreshFirmPopulationBalances(
        lifecycle.firmFinancialStocks,
        state.banking.ledgerFinancialState.firms,
        lifecycle.newFirmIds,
      ),
    )

    MonthClosingResult(
      world = finalWorld,
      firms = lifecycle.firms,
      households = lifecycle.households,
      banks = state.banking.banks,
      householdAggregates = lifecycle.householdAggregates,
      ledgerFinancialState = finalLedger,
      startupAbsorptionRate = lifecycle.startupAbsorptionRate,
    )

  private def informalInput(state: MonthClosingStateInput): InformalEconomy.Input =
    InformalEconomy.Input(
      citEvasion = state.firm.sumCitEvasion,
      vatBeforeEvasion = state.banking.vat,
      vatAfterEvasion = state.banking.vatAfterEvasion,
      pitBeforeEvasion = state.householdIncome.pitRevenue,
      pitAfterEvasion = state.banking.pitAfterEvasion,
      exciseBeforeEvasion = state.banking.exciseRevenue,
      exciseAfterEvasion = state.banking.exciseAfterEvasion,
      realizedTaxShadowShare = state.banking.realizedTaxShadowShare,
      employed = state.labor.employed,
      workingAgePopulation = state.labor.newDemographics.workingAgePop,
      previousCyclicalAdjustment = state.openingWorld.mechanisms.informalCyclicalAdj,
    )

  private def populationLifecycleInput(state: MonthClosingStateInput): PopulationLifecycleTransitions.Input =
    PopulationLifecycleTransitions.Input(
      stocks = PopulationLifecycleTransitions.AgentStocks(
        firms = state.banking.reassignedFirms,
        firmFinancialBalances = state.banking.ledgerFinancialState.firms,
        households = state.banking.reassignedHouseholds,
        householdFinancialBalances = state.banking.ledgerFinancialState.households,
      ),
      entry = PopulationLifecycleTransitions.EntryContext(
        automationRatio = state.priceEquity.autoR,
        hybridRatio = state.priceEquity.hybR,
        laggedEntrySignals = FirmEntry.LaggedEntrySignals.fromDecisionSignals(state.openingWorld.seedIn),
      ),
      startupStaffing = PopulationLifecycleTransitions.StartupStaffingContext(
        marketWage = state.labor.newWage,
        reservationWage = state.fiscal.resWage,
        importAdjustment = state.householdIncome.importAdj,
        regionalWages = state.labor.regionalWages,
        remainingHireCapacity = state.firm.postFirmHireCapacity - state.firm.postFirmHires,
        retrainingAttempts = state.householdIncome.hhAgg.retrainingAttempts,
        retrainingSuccesses = state.householdIncome.hhAgg.retrainingSuccesses,
        householdFlowTotals = state.banking.finalHhAgg,
      ),
      regionalMigration = PopulationLifecycleTransitions.RegionalMigrationContext(
        regionalWages = state.labor.regionalWages,
      ),
      bankRouting = PopulationLifecycleTransitions.BankRoutingContext(
        banks = state.banking.banks,
        bankFinancialBalances = state.banking.ledgerFinancialState.banks,
      ),
    )
