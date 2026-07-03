# Runtime Loop

This document describes the code architecture of one executed month. For the
formal model-facing transition contract, read
[monthly-transition-function.md](../monthly-transition-function.md). This page
answers where the implementation boundaries live.

## Public Entry Points

| Runtime object | Role |
| --- | --- |
| [`WorldInit.initialize`](../../src/main/scala/com/boombustgroup/amorfati/init/WorldInit.scala) | Builds the initial `World`, agent populations, aggregates, and `LedgerFinancialState`. |
| [`FlowSimulation.SimState`](../../src/main/scala/com/boombustgroup/amorfati/engine/flows/FlowSimulation.scala) | Single public month-boundary input state. |
| [`MonthRandomness.Contract`](../../src/main/scala/com/boombustgroup/amorfati/engine/MonthRandomness.scala) | Explicit per-month randomness surface derived from one root seed. |
| [`FlowSimulation.step`](../../src/main/scala/com/boombustgroup/amorfati/engine/flows/FlowSimulation.scala) | Narrow one-month transition used by tests, replay, diagnostics, and drivers. |
| [`MonthDriver.unfoldSteps`](../../src/main/scala/com/boombustgroup/amorfati/engine/MonthDriver.scala) | Shared month-by-month unfold driver. Callers own the randomness schedule. |
| [`FlowSimulation.StepOutput`](../../src/main/scala/com/boombustgroup/amorfati/engine/flows/FlowSimulation.scala) | Execution evidence: calculus, operational signals, emitted batches, ledger result, SFC result, trace, and next state. |

## One-Month Path

At a high level, one runtime month follows this path:

```text
SimState + MonthRandomness.Contract
-> StepInput
-> same-month economics
-> same-month boundary views
-> closed-month semantic state and seed extraction
-> runtime flow emission
-> ledger execution
-> next-state materialization
-> SFC semantic projection and validation result
-> MonthTrace and StepOutput
```

The strongest architectural split is between decisions and monetary execution:

| Stage | Code owner | Boundary |
| --- | --- | --- |
| Pre boundary | `FlowSimulation.stepInput` | Derives execution month, opening snapshot, topology, and seed input from `SimState`. |
| Same-month economics | `MonthCalculusRunner` and `SameMonthEconomicsDsl` | Runs ordered economic stages and returns narrowed views. No runtime ledger batches are emitted here. |
| Flow plan | `MonthFlowPlanBuilder` | Projects execution outputs and opening financial state into `MonthlyCalculus`. |
| Flow emission | `MonthFlowEmitter` and `*Flows.scala` emitters | Converts `MonthlyCalculus` into named ledger batches. No new economics should be decided here. |
| Ledger execution | `RuntimeFlowExecutor` and `modules/ledger` | Executes batches and captures conservation evidence plus `deltaLedger`. |
| Closed-month to next-pre | `NextStateAdvancer` | Applies extracted `SeedOut` and materializes supported runtime deltas into `LedgerFinancialState`. |
| SFC validation | `SfcSemanticProjection` and `accounting/Sfc.scala` | Converts execution evidence and semantic payloads into exact SFC identity checks. |
| Trace | `MonthTraceBuilder` | Emits boundary, seed, timing, flow, and validation evidence for the month. |

## Same-Month Economics Order

`MonthCalculusRunner` is the single insertion point for same-month economic
calculus. The current order is:

```text
fiscal constraint
-> labor pre-clearing
-> opening payroll
-> household income
-> demand
-> firm processing
-> post-firm labor context
-> social funds
-> labor reconciliation
-> household financial flows
-> price and equity
-> open economy
-> banking
-> month execution assembly
```

The output is deliberately narrowed into views rather than exposing one large
mutable month object:

| View | Consumer |
| --- | --- |
| `MonthlyCalculus` | `MonthFlowEmitter` |
| `SignalBoundaryInputs` | `OperationalSignals` and seed extraction |
| `MonthClosingInput` | `closedmonth.MonthClosing` |
| `SemanticFlowInputs` | `SfcSemanticProjection` |
| `StepEvidenceInputs` | traces, snapshots, and exports |

## Randomness Contract

Randomness is not ambient. `MonthRandomness.Contract.fromSeed(rootSeed)` splits
one root seed into named stage and closing streams:

| Group | Streams |
| --- | --- |
| Same-month economics | household income, firm, household financial, open-economy, and banking economics. |
| Closing mechanisms | FDI M&A, firm entry, startup staffing, and regional migration. |

Fixing `SimState`, `SimParams`, and `MonthRandomness.Contract.rootSeed` fixes
the one-month replay. Monte Carlo, diagnostics, and tests should schedule
contracts explicitly rather than introducing hidden RNG state.

## Driver Ownership

`FlowSimulation.step` owns one transition. `MonthDriver` owns repeated
execution:

```text
caller supplies: SimState => Option[MonthRandomness.Contract]
MonthDriver supplies: Iterator[StepOutput] or Iterator[Either[EngineFailure, StepOutput]]
```

The engine stops after the first structured failure in
`unfoldStepResultsWithBoundary`, preserving the failing boundary for diagnostics
instead of silently advancing from a broken state.

## What Not To Change Casually

The following are architecture boundaries, not incidental wiring:

- `FlowSimulation.SimState` is the public month boundary.
- `MonthRandomness.Contract` is the month-level replay contract.
- `MonthCalculusRunner` is the only ordered same-month economics insertion
  point.
- `MonthFlowEmitter` is translation glue, not a second economics layer.
- `RuntimeFlowExecutor` is the runtime accounting execution boundary.
- `NextStateAdvancer` is the only closed-month to next-pre transition.
- `SfcSemanticProjection` is the semantic accounting validation boundary.

Changes to any of these should update this document, the nearest package README,
and, when they change a durable trade-off, an ADR.
