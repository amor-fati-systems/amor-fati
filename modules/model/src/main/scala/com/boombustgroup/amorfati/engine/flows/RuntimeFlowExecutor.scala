package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.engine.EngineFailure
import com.boombustgroup.ledger.*

/** Executes runtime ledger batches against the explicit month topology.
  *
  * Flow emission decides what should be booked; this module owns the runtime
  * interpreter call, delta capture, and execution-failure mapping.
  */
object RuntimeFlowExecutor:

  /** Executed aggregate batch deltas on top of an empty runtime ledger shell.
    *
    * This is not a closing stock snapshot. `deltaLedger` stores the net monthly
    * delta per synthetic runtime slot, and `netDelta` is the conservation check
    * over that delta space.
    */
  case class Result(
      topology: RuntimeLedgerTopology,
      deltaLedger: Map[(EntitySector, AssetType, Int), Long],
      netDelta: Long,
  )

  def execute(flows: Vector[BatchedFlow])(using topology: RuntimeLedgerTopology): Either[EngineFailure, Result] =
    val state = topology.emptyExecutionState()
    ImperativeInterpreter
      .planAndApplyAll(state, flows)
      .left
      .map(executionFailure)
      .map: _ =>
        val deltaLedger = state.snapshot
        Result(
          topology = topology,
          deltaLedger = deltaLedger,
          netDelta = topology.netDelta(deltaLedger),
        )

  def executeOrThrow(flows: Vector[BatchedFlow])(using RuntimeLedgerTopology): Result =
    execute(flows).fold(err => throw err, identity)

  def executionFailure(error: String): EngineFailure =
    EngineFailure.runtimeLedgerExecution("RuntimeFlowExecutor.execute", s"Ledger batch execution failed: $error")
