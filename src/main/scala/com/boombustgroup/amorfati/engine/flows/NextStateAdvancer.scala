package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.engine.{MonthSemantics, SimulationMonth}
import com.boombustgroup.amorfati.engine.ledger.RuntimeFlowProjection

/** Advances a realized closed month into the next pre-month simulation state.
  *
  * Month closing still belongs to month `t`; this module is the explicit
  * boundary where the extracted `SeedOut` becomes the persisted month `t + 1`
  * decision surface and runtime-supported ledger deltas are materialized.
  */
object NextStateAdvancer:

  /** Explicit input for the closed-month -> next-pre boundary. */
  case class Input(
      stateIn: FlowSimulation.SimState,
      executionMonth: SimulationMonth.ExecutionMonth,
      seedIn: MonthSemantics.SeedIn,
      closed: MonthSemantics.ClosedMonth,
      seedOut: MonthSemantics.SeedOut,
      execution: RuntimeFlowExecutor.Result,
  )

  def advance(input: Input): FlowSimulation.SimState =
    val semanticState     = advanceSemanticState(input)
    val runtimeProjection = RuntimeFlowProjection.materializeSupportedState(
      opening = input.stateIn.ledgerFinancialState,
      semanticClosing = semanticState.ledgerFinancialState,
      deltaLedger = input.execution.deltaLedger,
      topology = input.execution.topology,
    )

    semanticState.copy(ledgerFinancialState = runtimeProjection.ledgerFinancialState)

  private def advanceSemanticState(input: Input): FlowSimulation.SimState =
    val closing     = input.closed.closing
    val nextSeed    = input.seedOut.nextSeed
    val nextWorld   = closing.world.copy(pipeline = closing.world.pipeline.withDecisionSignals(nextSeed))
    val currentSeed = input.seedIn.decisionSignals

    val expectedExecutionMonth = input.stateIn.completedMonth.next

    // This is the only legal closed-month -> next-pre transition: the closed
    // month still sees the old seed, while the next boundary applies SeedOut.
    require(
      input.executionMonth == expectedExecutionMonth,
      s"NextStateAdvancer input.executionMonth ${input.executionMonth.toInt} must equal input.stateIn.completedMonth.next ${expectedExecutionMonth.toInt} before constructing FlowSimulation.SimState",
    )
    require(currentSeed == input.stateIn.world.seedIn, "StepInput seedIn must match stateIn.world.seedIn")
    require(closing.world.seedIn == currentSeed, "ClosedMonth world must remain on the pre-step seed until NextStateAdvancer runs")
    require(nextWorld.seedIn == nextSeed, "NextStateAdvancer must be the transition that applies SeedOut to the next boundary")

    FlowSimulation.SimState(
      completedMonth = input.executionMonth.completed,
      world = nextWorld,
      firms = closing.firms,
      households = closing.households,
      banks = closing.banks,
      householdAggregates = closing.householdAggregates,
      ledgerFinancialState = closing.ledgerFinancialState,
    )
