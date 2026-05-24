package com.boombustgroup.amorfati.montecarlo

import com.boombustgroup.amorfati.agents.Household
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.flows.FlowSimulation

/** Public-in-module monthly seed output shared by production Monte Carlo and
  * diagnostics. It exposes the canonical monthly row plus the month-boundary
  * state and lightweight monthly tap payloads needed by diagnostics.
  */
private[amorfati] final case class McSeedMonth(
    executionMonth: ExecutionMonth,
    row: Array[MetricValue],
    openingState: FlowSimulation.SimState,
    state: FlowSimulation.SimState,
    householdSnapshotState: FlowSimulation.HouseholdSnapshotState,
    householdMonthlyFlows: Vector[Household.MonthlyFlow],
)
