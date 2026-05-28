package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.accounting.Sfc
import com.boombustgroup.amorfati.engine.*
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth

/** Builds the month trace from explicit month-step boundaries.
  *
  * FlowSimulation owns the transition itself; this module owns the audit
  * artifact assembled from that transition.
  */
object MonthTraceBuilder:

  /** Creates the timing-envelope trace from same-month signals and realized
    * closed-month values.
    */
  def timingTrace(
      signalView: MonthSemantics.SignalView,
      closing: MonthClosingResult,
  ): MonthTimingTrace =
    MonthTimingTrace.fromInputs(
      MonthTimingInputs(
        labor = MonthTimingPayload.LaborSignals(
          operationalHiringSlack = signalView.labor.operationalHiringSlack,
        ),
        demand = MonthTimingPayload.DemandSignals(
          sectorDemandMult = signalView.demand.sectorMults,
          sectorDemandPressure = signalView.demand.sectorDemandPressure,
          sectorHiringSignal = signalView.demand.sectorHiringSignal,
        ),
        nominal = MonthTimingPayload.NominalSignals(
          realizedInflation = closing.world.inflation,
          expectedInflation = closing.world.mechanisms.expectations.expectedInflation,
        ),
        firmDynamics = MonthTimingPayload.FirmDynamics(
          startupAbsorptionRate = closing.startupAbsorptionRate,
          firmBirths = closing.world.flows.firmBirths,
          firmDeaths = closing.world.flows.firmDeaths,
          netFirmBirths = closing.world.flows.netFirmBirths,
        ),
      ),
    )

  /** Builds the reusable trace core at the explicit pre -> closed-month
    * boundary, before SFC execution details are attached.
    */
  def core(
      pre: FlowSimulation.StepInput,
      closed: MonthSemantics.ClosedMonth,
      seedOut: MonthSemantics.SeedOut,
  ): MonthTraceCore =
    MonthTraceCore(
      boundary = MonthBoundaryTrace.from(pre.boundaryIn, closed.boundaryOut),
      seedTransition = SeedTransitionTrace.from(pre.seedIn, seedOut),
      timing = closed.timing,
    )

  /** Finalizes the full month trace with the projected end snapshot, executed
    * semantic flows, and validation results.
    */
  def build(
      executionMonth: ExecutionMonth,
      randomness: MonthRandomness.Contract,
      core: MonthTraceCore,
      endState: FlowSimulation.SimState,
      executedFlows: Sfc.SemanticFlows,
      sfcResult: Sfc.SfcResult,
  ): MonthTrace =
    val projectedBoundary = core.boundary.copy(
      endSnapshot = MonthBoundarySnapshot.capture(
        endState.world,
        endState.firms,
        endState.households,
        endState.banks,
        endState.ledgerFinancialState,
      ),
    )
    val projectedCore     = core.copy(boundary = projectedBoundary)

    MonthTrace.fromCore(
      executionMonth = executionMonth,
      randomness = randomness,
      core = projectedCore,
      executedFlows = executedFlows,
      validations = Vector(MonthValidation.fromSfcResult(sfcResult)),
    )
