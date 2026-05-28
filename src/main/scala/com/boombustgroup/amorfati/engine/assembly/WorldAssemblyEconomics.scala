package com.boombustgroup.amorfati.engine.assembly

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.*
import com.boombustgroup.amorfati.engine.economics.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.engine.mechanisms.{FirmEntry, InformalEconomy, PopulationLifecycleTransitions}
import com.boombustgroup.amorfati.types.*

/** Explicit post-month assembly boundary.
  *
  * Domain mechanisms and diagnostic mappers live in focused modules. This
  * object owns only the public StepInput/PostResult contract and the top-level
  * ordering of post-month assembly.
  */
object WorldAssemblyEconomics:

  case class StepInput(
      w: World,                                   // current world state
      s1: FiscalConstraintEconomics.StepOutput,   // fiscal constraint (month, reservation wage, lending base rate)
      s2: LaborEconomics.StepOutput,              // labor/demographics (wage, employment, ZUS, PPK)
      s3: HouseholdIncomeEconomics.StepOutput,    // household income (consumption, PIT, import propensity)
      s4: DemandEconomics.StepOutput,             // demand (sector multipliers, gov purchases)
      s5: FirmEconomics.StepOutput,               // firm processing (loans, NPL, tax, I-O, bond issuance)
      s6: HouseholdFinancialEconomics.StepOutput, // household financial flows (consumer credit, remittances, tourism)
      s7: PriceEquityEconomics.StepOutput,        // price/equity (inflation, GDP, equity, macropru)
      s8: OpenEconEconomics.StepOutput,           // open economy (monetary policy, forex, BOP, corp bonds)
      s9: BankingEconomics.StepOutput,            // bank update (balance sheets, tax revenue, housing flows)
  )

  private[assembly] final case class AssemblyContext(
      step: StepInput,
      informal: InformalEconomy.Result,
      fofResidual: PLN,
  )

  private[assembly] object AssemblyContext:
    def from(step: StepInput)(using p: SimParams): AssemblyContext =
      AssemblyContext(
        step = step,
        informal = InformalEconomy.compute(informalInput(step)),
        fofResidual = FlowOfFundsDiagnostics.residual(step),
      )

    private def informalInput(step: StepInput): InformalEconomy.Input =
      InformalEconomy.Input(
        citEvasion = step.s5.sumCitEvasion,
        vatBeforeEvasion = step.s9.vat,
        vatAfterEvasion = step.s9.vatAfterEvasion,
        pitBeforeEvasion = step.s3.pitRevenue,
        pitAfterEvasion = step.s9.pitAfterEvasion,
        exciseBeforeEvasion = step.s9.exciseRevenue,
        exciseAfterEvasion = step.s9.exciseAfterEvasion,
        realizedTaxShadowShare = step.s9.realizedTaxShadowShare,
        employed = step.s2.employed,
        workingAgePopulation = step.s2.newDemographics.workingAgePop,
        previousCyclicalAdjustment = step.w.mechanisms.informalCyclicalAdj,
      )

  /** Assembled month-`t` state before the next-month decision seed is applied.
    */
  case class PostResult(
      world: World,
      firms: Vector[Firm.State],
      households: Vector[Household.State],
      banks: Vector[Banking.BankState],
      householdAggregates: Household.Aggregates,
      ledgerFinancialState: LedgerFinancialState,
      startupAbsorptionRate: Share,
  )

  def computePostMonth(
      step: StepInput,
      randomness: MonthRandomness.AssemblyStreams,
  )(using p: SimParams): PostResult =
    val context        = AssemblyContext.from(step)
    val assembledWorld = WorldStateAssembler.assemble(context)
    val population     = PopulationLifecycleTransitions.run(
      populationLifecycleInput(step, assembledWorld),
      fdiMaRng = randomness.fdiMa,
      firmEntryRng = randomness.firmEntry,
      startupStaffingRng = randomness.startupStaffing,
      regionalMigrationRng = randomness.regionalMigration,
    )
    val finalWorld     = WorldStateAssembler.withPopulationLifecycle(assembledWorld, step, population)
    val finalLedger    = step.s9.ledgerFinancialState.copy(
      firms = LedgerFinancialState.refreshFirmPopulationBalances(
        population.firmFinancialStocks,
        step.s9.ledgerFinancialState.firms,
        population.newFirmIds,
      ),
    )

    PostResult(
      world = finalWorld,
      firms = population.firms,
      households = population.households,
      banks = step.s9.banks,
      householdAggregates = population.householdAggregates,
      ledgerFinancialState = finalLedger,
      startupAbsorptionRate = population.startupAbsorptionRate,
    )

  private def populationLifecycleInput(
      step: StepInput,
      assembledWorld: World,
  ): PopulationLifecycleTransitions.Input =
    PopulationLifecycleTransitions.Input(
      firms = step.s9.reassignedFirms,
      firmFinancialBalances = step.s9.ledgerFinancialState.firms,
      households = step.s9.reassignedHouseholds,
      householdFinancialBalances = step.s9.ledgerFinancialState.households,
      automationRatio = assembledWorld.real.automationRatio,
      hybridRatio = assembledWorld.real.hybridRatio,
      laggedEntrySignals = FirmEntry.LaggedEntrySignals.fromDecisionSignals(step.w.seedIn),
      marketWage = step.s2.newWage,
      reservationWage = step.s1.resWage,
      importAdjustment = step.s3.importAdj,
      regionalWages = step.s2.regionalWages,
      remainingHireCapacity = step.s5.postFirmHireCapacity - step.s5.postFirmHires,
      retrainingAttempts = step.s3.hhAgg.retrainingAttempts,
      retrainingSuccesses = step.s3.hhAgg.retrainingSuccesses,
      householdFlowTotals = step.s9.finalHhAgg,
    )
