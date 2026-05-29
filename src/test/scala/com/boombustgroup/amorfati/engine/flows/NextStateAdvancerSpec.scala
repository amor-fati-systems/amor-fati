package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.{
  DecisionSignals,
  MonthBoundarySnapshot,
  MonthClosingResult,
  MonthSemantics,
  MonthTimingTrace,
  SignalExtraction,
  SimulationMonth,
  World,
}
import com.boombustgroup.amorfati.types.{PLN, Share}
import com.boombustgroup.ledger.{AssetType, BatchedFlow, EntitySector}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NextStateAdvancerSpec extends AnyFlatSpec with Matchers:
  import RuntimeFlowsTestSupport.*

  private given SimParams = SimParams.defaults

  "NextStateAdvancer" should "apply SeedOut at the next-pre boundary and materialize runtime ledger deltas" in {
    val state     = stateFromSeed()
    val nextSeed  = state.world.seedIn.copy(laggedHiringSlack = Share.decimal(42, 2))
    val execution = executeNfzTransfer(state, PLN(12345))

    val nextState = NextStateAdvancer.advance(input(state, nextSeed, execution = Some(execution)))

    nextState.completedMonth.shouldBe(state.completedMonth.next.completed)
    nextState.world.seedIn.shouldBe(nextSeed)
    nextState.firms.shouldBe(state.firms)
    nextState.households.shouldBe(state.households)
    nextState.banks.shouldBe(state.banks)
    nextState.ledgerFinancialState.funds.nfzCash.shouldBe(state.ledgerFinancialState.funds.nfzCash + PLN(12345))
  }

  it should "reject a step input seed that does not match the opening state" in {
    val state    = stateFromSeed()
    val nextSeed = state.world.seedIn.copy(laggedHiringSlack = Share.decimal(42, 2))
    val badSeed  = state.world.seedIn.copy(laggedHiringSlack = Share.Zero)

    val err = intercept[IllegalArgumentException]:
      NextStateAdvancer.advance(input(state, nextSeed, seedIn = Some(MonthSemantics.seedIn(badSeed))))

    err.getMessage.should(include("StepInput seedIn"))
  }

  it should "reject an execution month that does not follow the opening completed month" in {
    val state    = stateFromSeed()
    val nextSeed = state.world.seedIn.copy(laggedHiringSlack = Share.decimal(42, 2))

    val err = intercept[IllegalArgumentException]:
      NextStateAdvancer.advance(input(state, nextSeed, executionMonth = Some(state.completedMonth.next.next)))

    err.getMessage.should(include("input.executionMonth"))
    err.getMessage.should(include("input.stateIn.completedMonth.next"))
    err.getMessage.should(include("FlowSimulation.SimState"))
  }

  it should "reject a closed month that already applied the next seed" in {
    val state       = stateFromSeed()
    val nextSeed    = state.world.seedIn.copy(laggedHiringSlack = Share.decimal(42, 2))
    val seededWorld = state.world.copy(pipeline = state.world.pipeline.withDecisionSignals(nextSeed))

    val err = intercept[IllegalArgumentException]:
      NextStateAdvancer.advance(input(state, nextSeed, closed = Some(closedMonth(state, world = Some(seededWorld)))))

    err.getMessage.should(include("ClosedMonth world must remain on the pre-step seed"))
  }

  private def input(
      state: FlowSimulation.SimState,
      nextSeed: DecisionSignals,
      executionMonth: Option[SimulationMonth.ExecutionMonth] = None,
      seedIn: Option[MonthSemantics.SeedIn] = None,
      closed: Option[MonthSemantics.ClosedMonth] = None,
      execution: Option[RuntimeFlowExecutor.Result] = None,
  ): NextStateAdvancer.Input =
    NextStateAdvancer.Input(
      stateIn = state,
      executionMonth = executionMonth.getOrElse(state.completedMonth.next),
      seedIn = seedIn.getOrElse(MonthSemantics.seedIn(state.world.seedIn)),
      closed = closed.getOrElse(closedMonth(state)),
      seedOut = seedOut(nextSeed),
      execution = execution.getOrElse(emptyExecution(state)),
    )

  private def closedMonth(state: FlowSimulation.SimState, world: Option[World] = None): MonthSemantics.ClosedMonth =
    val closingWorld = world.getOrElse(state.world)
    val closing      = MonthClosingResult(
      world = closingWorld,
      firms = state.firms,
      households = state.households,
      banks = state.banks,
      householdAggregates = state.householdAggregates,
      ledgerFinancialState = state.ledgerFinancialState,
      startupAbsorptionRate = state.world.seedIn.startupAbsorptionRate,
    )

    MonthSemantics.closedMonth(
      FlowSimulation.ClosedMonthBoundary(
        closing = closing,
        boundaryOut = MonthBoundarySnapshot.capture(closingWorld, state.firms, state.households, state.banks, state.ledgerFinancialState),
        timing = MonthTimingTrace(Vector.empty),
      ),
    )

  private def seedOut(signals: DecisionSignals): MonthSemantics.SeedOut =
    MonthSemantics.seedOut(
      SignalExtraction.compute(
        SignalExtraction.Input(
          labor = SignalExtraction.LaborOutcomes(
            unemploymentRate = signals.unemploymentRate,
            laggedHiringSlack = signals.laggedHiringSlack,
            startupAbsorptionRate = signals.startupAbsorptionRate,
          ),
          nominal = SignalExtraction.NominalOutcomes(
            inflation = signals.inflation,
            expectedInflation = signals.expectedInflation,
          ),
          demand = SignalExtraction.DemandOutcomes(
            sectorDemandMult = signals.sectorDemandMult,
            sectorDemandPressure = signals.sectorDemandPressure,
            sectorHiringSignal = signals.sectorHiringSignal,
          ),
        ),
      ),
    )

  private def emptyExecution(state: FlowSimulation.SimState): RuntimeFlowExecutor.Result =
    given RuntimeLedgerTopology = RuntimeLedgerTopology.fromState(state)
    RuntimeFlowExecutor.executeOrThrow(Vector.empty)

  private def executeNfzTransfer(state: FlowSimulation.SimState, amount: PLN): RuntimeFlowExecutor.Result =
    given topology: RuntimeLedgerTopology = RuntimeLedgerTopology.fromState(state)
    RuntimeFlowExecutor.executeOrThrow(
      Vector(
        BatchedFlow.Broadcast(
          from = EntitySector.Government,
          fromIndex = topology.government.sovereignIssuer,
          to = EntitySector.Funds,
          amounts = Array(amount.toLong),
          targetIndices = Array(topology.funds.nfz),
          asset = AssetType.Cash,
          mechanism = FlowMechanism.GovPurchases,
        ),
      ),
    )
