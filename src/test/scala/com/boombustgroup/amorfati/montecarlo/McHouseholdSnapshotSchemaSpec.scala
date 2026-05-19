package com.boombustgroup.amorfati.montecarlo

import com.boombustgroup.amorfati.agents.Household
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.flows.FlowSimulation
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.init.{InitRandomness, WorldInit}
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class McHouseholdSnapshotSchemaSpec extends AnyFlatSpec with Matchers:

  private given SimParams = SimParams.defaults

  "McHouseholdSnapshotSchema" should "keep the household snapshot header stable" in {
    McHouseholdSnapshotSchema.header shouldBe
      "RunId;Seed;Month;HouseholdId;Status;Region;ContractType;BankId;Wage;Rent;MPC;Skill;HealthPenalty;FinancialDistressMonths;DemandDeposit;MortgageLoan;ConsumerLoan;Equity;PositiveDeposit;ImplicitOverdraft;NetLiquidPosition;NetFinancialPosition;OpeningDemandDeposit;OpeningConsumerLoan;MonthlyIncome;Consumption;RentPaid;MortgageDebtService;ConsumerApprovedOrigination;LiquidityShortfallFinancing;ConsumerDebtService;ConsumerDefault;ConsumerPrincipal;ClosingConsumerLoan"
  }

  "McHouseholdShortfallCohortSchema" should "keep the household shortfall cohort header stable" in {
    McHouseholdShortfallCohortSchema.header shouldBe
      "RunId;Seed;Month;Dimension;Cohort;HouseholdCount;ShortfallHouseholdCount;ShortfallHouseholdShare;LiquidityShortfallFinancing;ShortfallShareOfMonth;ConsumerApprovedOrigination;ConsumerDebtService;ConsumerDefault;ConsumerPrincipal;OpeningDemandDeposit;ClosingDemandDeposit;OpeningConsumerLoan;ClosingConsumerLoan;MonthlyIncome;Consumption;Rent;MortgageDebtService;RentToIncome;MortgageDebtServiceToIncome;ConsumerDebtServiceToIncome;ClosingConsumerLoanToIncome"
  }

  it should "apply the liquidity-shortfall selector to rendered rows" in {
    val state        = FlowSimulation.SimState.fromInit(WorldInit.initialize(InitRandomness.Contract.fromSeed(42L)))
    val boundary     = FlowSimulation.HouseholdSnapshotState(state.households, state.ledgerFinancialState)
    val household    = state.households.head
    val monthlyFlows = state.households
      .zip(state.ledgerFinancialState.households)
      .zipWithIndex
      .map:
        case ((hh, balances), idx) =>
          val stocks = LedgerFinancialState.projectHouseholdFinancialStocks(balances)
          val flow   = Household.MonthlyFlow.inactive(hh.id, stocks)
          if idx == 0 then flow.copy(liquidityShortfallFinancing = PLN(123)) else flow

    val rows = McHouseholdSnapshotSchema.rows(
      runId = "run",
      seed = 1L,
      month = ExecutionMonth.First,
      state = boundary,
      monthlyFlows = monthlyFlows,
      selection = McHouseholdSnapshotSelection.LiquidityShortfall,
    )

    rows.map(_.household.id) shouldBe Vector(household.id)
    rows.head.monthlyFlow.liquidityShortfallFinancing shouldBe PLN(123)
  }

  it should "aggregate household shortfall cohorts from the full snapshot boundary" in {
    val state        = FlowSimulation.SimState.fromInit(WorldInit.initialize(InitRandomness.Contract.fromSeed(42L)))
    val boundary     = FlowSimulation.HouseholdSnapshotState(state.households, state.ledgerFinancialState)
    val monthlyFlows = state.households
      .zip(state.ledgerFinancialState.households)
      .zipWithIndex
      .map:
        case ((hh, balances), idx) =>
          val stocks = LedgerFinancialState.projectHouseholdFinancialStocks(balances)
          val flow   = Household.MonthlyFlow.inactive(hh.id, stocks)
          if idx == 0 then
            flow.copy(
              monthlyIncome = PLN(1000),
              consumption = PLN(400),
              rent = PLN(500),
              mortgageDebtService = PLN(100),
              liquidityShortfallFinancing = PLN(123),
            )
          else flow

    val rows = McHouseholdShortfallCohortSchema.rows(
      runId = "run",
      seed = 1L,
      month = ExecutionMonth.First,
      state = boundary,
      monthlyFlows = monthlyFlows,
    )

    val all = rows.find(row => row.dimension == "All" && row.cohort == "All").getOrElse(fail("missing All cohort row"))
    all.householdCount shouldBe state.households.length
    all.shortfallHouseholdCount shouldBe 1
    all.liquidityShortfallFinancing shouldBe PLN(123)
    all.shortfallShareOfMonth shouldBe Share.One

    val rentBurden = rows.find(row => row.dimension == "RentBurden" && row.cohort == "40_60pct").getOrElse(fail("missing rent-burden cohort row"))
    rentBurden.shortfallHouseholdCount shouldBe 1
    rentBurden.liquidityShortfallFinancing shouldBe PLN(123)
    rentBurden.rentToIncome shouldBe Scalar.decimal(5, 1)
  }
