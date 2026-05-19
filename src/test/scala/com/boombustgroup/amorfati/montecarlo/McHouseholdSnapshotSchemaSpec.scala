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
      "RunId;Seed;Month;HouseholdId;Status;Region;ContractType;BankId;Wage;Rent;MPC;Skill;HealthPenalty;FinancialDistressMonths;DemandDeposit;MortgageLoan;ConsumerLoan;Equity;PositiveDeposit;ImplicitOverdraft;NetLiquidPosition;NetFinancialPosition;OpeningDemandDeposit;OpeningConsumerLoan;ConsumerApprovedOrigination;LiquidityShortfallFinancing;ConsumerDebtService;ConsumerDefault;ConsumerPrincipal;ClosingConsumerLoan"
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
