package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.{BankRates, Household}
import com.boombustgroup.amorfati.agents.household.HouseholdStepSemantics.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.{MonthWorkflow, World}
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Orchestrates one household-agent month while delegating domain logic to
  * income, credit, liquidity, distress, labor, and aggregate modules.
  */
private[agents] object HouseholdStepRunner:

  /** Executes the household month and returns updated household state, stocks,
    * aggregates, per-bank flows, and micro diagnostics.
    */
  def step(
      households: Vector[Household.State],
      financialStocks: Vector[Household.FinancialStocks],
      world: World,
      marketWage: PLN,
      reservationWage: PLN,
      importAdj: Share,
      rng: RandomStream,
      nBanks: Int = 1,
      bankRates: Option[BankRates] = None,
      equityIndexReturn: Rate = Rate.Zero,
      sectorWages: Option[Vector[PLN]] = None,
      sectorVacancies: Option[Vector[Int]] = None,
      consumerCreditGate: Option[Household.ConsumerCreditGate] = None,
  )(using p: SimParams): Household.StepResult =
    val stepInput = Input(
      opening = OpeningState(
        households = households,
        financialStocks = financialStocks,
        world = world,
      ),
      market = MarketSurface(
        marketWage = marketWage,
        reservationWage = reservationWage,
        importAdj = importAdj,
        equityIndexReturn = equityIndexReturn,
        sectorWages = sectorWages,
        sectorVacancies = sectorVacancies,
      ),
      credit = CreditSurface(
        nBanks = nBanks,
        bankRates = bankRates,
        consumerCreditGate = consumerCreditGate,
      ),
      stochastic = StochasticSurface(rng),
    )
    MonthWorkflow.run:
      for
        opening    <- HouseholdStepDsl.open(stepInput)
        prepared   <- HouseholdStepDsl.prepare(opening)
        processed  <- HouseholdStepDsl.process(prepared)
        aggregated <- HouseholdStepDsl.aggregate(processed)
        validated  <- HouseholdStepDsl.validate(aggregated)
        closed     <- HouseholdStepDsl.close(validated)
      yield closed.result
