# ADR-0002: Explicit Month Boundary

Status: Accepted

Date: 2026-07-03

## Context

The model executes in monthly steps. The implementation needs a boundary that
is narrow enough for replay, diagnostics, Monte Carlo, and tests, while still
carrying heterogeneous agents, macro state, household aggregates, and
ledger-owned financial stocks.

Earlier implicit month logic would make it easy to skip seed timing, mix
same-month operational signals with next-month decision signals, or advance
state without a clear audit artifact.

## Decision

`FlowSimulation.SimState` is the public month-boundary state, and
`FlowSimulation.step(state, randomness)` is the public one-month transition.
Repeated execution goes through `MonthDriver`, where callers supply an explicit
`SimState => Option[MonthRandomness.Contract]` schedule.

The month is structured as:

```text
pre boundary
-> same-month economics
-> narrowed same-month boundary views
-> closed-month semantic state
-> seed extraction
-> next-pre materialization
```

`NextStateAdvancer` is the only legal closed-month to next-pre transition.

## Consequences

- Tests and diagnostics can replay one month by fixing `SimState`,
  `SimParams`, and `MonthRandomness.Contract`.
- Signal timing is explicit: same-month operational signals and next-month
  decision signals have different boundaries.
- The engine can return structured `EngineFailure` results through
  `MonthDriver.unfoldStepResults`.
- Adding a new same-month stage must go through `MonthCalculusRunner` so the
  order remains inspectable.
- Any durable change to the public boundary or month order must update the
  runtime documentation.

## References

- [Runtime loop](../architecture/runtime-loop.md)
- [Monthly transition function](../monthly-transition-function.md)
- [`FlowSimulation.scala`](../../src/main/scala/com/boombustgroup/amorfati/engine/flows/FlowSimulation.scala)
- [`MonthDriver.scala`](../../src/main/scala/com/boombustgroup/amorfati/engine/MonthDriver.scala)
- [`MonthRandomness.scala`](../../src/main/scala/com/boombustgroup/amorfati/engine/MonthRandomness.scala)
- [`NextStateAdvancer.scala`](../../src/main/scala/com/boombustgroup/amorfati/engine/flows/NextStateAdvancer.scala)
