# ADR-0002: Explicit Month Boundary

Status: Accepted

Date: 2026-04-11

## Historical Provenance

This retroactive ADR records a boundary that entered `main` across several
closely related commits:

| Anchor | Main commit |
| --- | --- |
| Typed `MonthOutcome` inside `FlowSimulation` | `7da02898` Extract typed MonthOutcome boundary inside FlowSimulation (#324), 2026-04-10 |
| Explicit month randomness contract | `4336acd0` Unify randomness contracts across init and month step (#325), 2026-04-11 |
| Shared unfold driver | `3e1f5afc` Expose a first-class unfold driver over the monthly step (#326), 2026-04-11 |
| Typed simulation month boundaries | `918c362d` Introduce typed simulation month boundaries (#327), 2026-04-11 |
| Extracted next-state advancement boundary | `f6be2e03` Extract NextStateAdvancer from FlowSimulation (#659), 2026-05-29 |

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
- [`FlowSimulation.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/engine/flows/FlowSimulation.scala)
- [`MonthDriver.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/engine/MonthDriver.scala)
- [`MonthRandomness.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/engine/MonthRandomness.scala)
- [`NextStateAdvancer.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/engine/flows/NextStateAdvancer.scala)
