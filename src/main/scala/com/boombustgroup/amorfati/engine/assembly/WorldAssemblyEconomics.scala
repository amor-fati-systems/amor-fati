package com.boombustgroup.amorfati.engine.assembly

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.*
import com.boombustgroup.amorfati.engine.economics.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
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
      informal: WorldInformalEconomy.Result,
      fofResidual: PLN,
      observables: WorldObservables.Values,
  )

  private[assembly] object AssemblyContext:
    def from(step: StepInput)(using p: SimParams): AssemblyContext =
      AssemblyContext(
        step = step,
        informal = WorldInformalEconomy.compute(step),
        fofResidual = FlowOfFundsDiagnostics.residual(step),
        observables = WorldObservables.compute(step),
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
    val population     = PostMonthPopulationTransitions.run(context.step, assembledWorld, randomness)

    PostResult(
      world = population.world,
      firms = population.firms,
      households = population.households,
      banks = step.s9.banks,
      householdAggregates = population.householdAggregates,
      ledgerFinancialState = population.ledgerFinancialState,
      startupAbsorptionRate = population.startupAbsorptionRate,
    )
