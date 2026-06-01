package com.boombustgroup.amorfati.engine

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.flows.FlowSimulation

/** Shared unfold-style driver over the explicit monthly step boundary.
  *
  * This is orchestration glue at the engine level: callers own the schedule of
  * month-level randomness contracts, while [[FlowSimulation.step]] remains the
  * narrow one-month transition.
  */
object MonthDriver:

  type SimState                     = FlowSimulation.SimState
  type StepOutput                   = FlowSimulation.StepOutput
  type StepResult                   = Either[EngineFailure, StepOutput]
  private[engine] type StepBoundary = (SimState, MonthRandomness.Contract, Boolean) => StepResult

  /** Caller-owned month schedule for the engine unfold.
    *
    * Returning `Some(contract)` executes one monthly step from the provided
    * state boundary. Returning `None` closes the unfold.
    */
  type RandomnessSchedule = SimState => Option[MonthRandomness.Contract]

  def unfoldSteps(initialState: SimState, traceFirmDecisions: Boolean = false)(schedule: RandomnessSchedule)(using p: SimParams): Iterator[StepOutput] =
    unfoldStepResults(initialState, traceFirmDecisions = traceFirmDecisions)(schedule).map:
      case Right(output) => output
      case Left(failure) => throw failure

  /** Engine unfold that keeps central month-step failures in their structured
    * form for diagnostics and Monte Carlo orchestration.
    */
  def unfoldStepResults(initialState: SimState, traceFirmDecisions: Boolean = false)(schedule: RandomnessSchedule)(using
      p: SimParams,
  ): Iterator[StepResult] =
    unfoldStepResultsWithBoundary(initialState, traceFirmDecisions = traceFirmDecisions)(schedule): (state, randomness, trace) =>
      FlowSimulation.stepEither(state, randomness, traceFirmDecisions = trace)

  private[engine] def unfoldStepResultsWithBoundary(initialState: SimState, traceFirmDecisions: Boolean = false)(schedule: RandomnessSchedule)(
      stepBoundary: StepBoundary,
  ): Iterator[StepResult] =
    Iterator.unfold((initialState, false)):
      case (_, true)      =>
        None
      case (state, false) =>
        schedule(state).map: randomness =>
          stepBoundary(state, randomness, traceFirmDecisions) match
            case Right(output) => (Right(output), (output.nextState, false))
            case Left(failure) => (Left(failure), (state, true))
