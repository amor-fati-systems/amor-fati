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
    MonthClosingInput(
      execution = execution,
      mechanisms = MonthClosingMechanisms(
        informalEconomy = InformalEconomy.compute(informalInput(execution)),
      ),
      diagnostics = MonthClosingDiagnostics(
        flowOfFundsResidual = FlowOfFundsDiagnostics.residual(execution),
      ),
      agentLifecycle = populationLifecycleInput(execution),
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
    val execution   = closingInput.execution
    val lifecycle   = PopulationLifecycleTransitions.run(
      closingInput.agentLifecycle,
      fdiMaRng = randomness.fdiMa,
      firmEntryRng = randomness.firmEntry,
      startupStaffingRng = randomness.startupStaffing,
      regionalMigrationRng = randomness.regionalMigration,
    )
    val finalWorld  = WorldStateAssembler.assemble(closingInput, lifecycle)
    val finalLedger = execution.banking.ledgerFinancialState.copy(
      firms = LedgerFinancialState.refreshFirmPopulationBalances(
        lifecycle.firmFinancialStocks,
        execution.banking.ledgerFinancialState.firms,
        lifecycle.newFirmIds,
      ),
    )

    MonthClosingResult(
      world = finalWorld,
      firms = lifecycle.firms,
      households = lifecycle.households,
      banks = execution.banking.banks,
      householdAggregates = lifecycle.householdAggregates,
      ledgerFinancialState = finalLedger,
      startupAbsorptionRate = lifecycle.startupAbsorptionRate,
    )

  private def informalInput(execution: MonthExecution): InformalEconomy.Input =
    InformalEconomy.Input(
      citEvasion = execution.firm.sumCitEvasion,
      vatBeforeEvasion = execution.banking.vat,
      vatAfterEvasion = execution.banking.vatAfterEvasion,
      pitBeforeEvasion = execution.householdIncome.pitRevenue,
      pitAfterEvasion = execution.banking.pitAfterEvasion,
      exciseBeforeEvasion = execution.banking.exciseRevenue,
      exciseAfterEvasion = execution.banking.exciseAfterEvasion,
      realizedTaxShadowShare = execution.banking.realizedTaxShadowShare,
      employed = execution.labor.employed,
      workingAgePopulation = execution.labor.newDemographics.workingAgePop,
      previousCyclicalAdjustment = execution.openingWorld.mechanisms.informalCyclicalAdj,
    )

  private def populationLifecycleInput(execution: MonthExecution): PopulationLifecycleTransitions.Input =
    PopulationLifecycleTransitions.Input(
      stocks = PopulationLifecycleTransitions.AgentStocks(
        firms = execution.banking.reassignedFirms,
        firmFinancialBalances = execution.banking.ledgerFinancialState.firms,
        households = execution.banking.reassignedHouseholds,
        householdFinancialBalances = execution.banking.ledgerFinancialState.households,
      ),
      entry = PopulationLifecycleTransitions.EntryContext(
        automationRatio = execution.priceEquity.autoR,
        hybridRatio = execution.priceEquity.hybR,
        laggedEntrySignals = FirmEntry.LaggedEntrySignals.fromDecisionSignals(execution.openingWorld.seedIn),
      ),
      startupStaffing = PopulationLifecycleTransitions.StartupStaffingContext(
        marketWage = execution.labor.newWage,
        reservationWage = execution.fiscal.resWage,
        importAdjustment = execution.householdIncome.importAdj,
        regionalWages = execution.labor.regionalWages,
        remainingHireCapacity = execution.firm.postFirmHireCapacity - execution.firm.postFirmHires,
        retrainingAttempts = execution.householdIncome.hhAgg.retrainingAttempts,
        retrainingSuccesses = execution.householdIncome.hhAgg.retrainingSuccesses,
        householdFlowTotals = execution.banking.finalHhAgg,
      ),
      regionalMigration = PopulationLifecycleTransitions.RegionalMigrationContext(
        regionalWages = execution.labor.regionalWages,
      ),
    )
