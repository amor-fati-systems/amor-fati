# Monthly Transition Function

This document formalizes one Amor Fati model month as a transition from the
completed month boundary `X_t` to the next completed month boundary `X_tau`,
where `tau = t + 1`.

It describes the implemented engine contract. It does not introduce new model
behavior and does not replace the detailed economics, flow, SFC, calibration,
or validation documents.

## Source Anchors

| Source | Role |
| --- | --- |
| [Model specification](model-specification.md) | Publication-facing model overview. |
| [Model notation and state vector](model-notation-and-state-vector.md) | Canonical symbols, state vector, time indices, stocks, flows, rates, shares, and stochastic notation. |
| [Stochastic processes and replay](stochastic-processes-and-replay.md) | Detailed seed policy, initialization and month stream maps, stochastic decision surfaces, Monte Carlo replay, and validation coverage. |
| [Behavioral equations and decision rules](behavioral-equations-and-decision-rules.md) | Detailed rule families used by the same-month economics stages. |
| [SFC matrix evidence](sfc-matrix-evidence.md) | Accounting matrix evidence, sign conventions, generated artifacts, and reconciliation surface. |
| [Engine invariants and semantics](engine-invariants-and-semantics.md) | Hard invariants, failure semantics, known limitations, and validation routing. |
| [`FlowSimulation.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/flows/FlowSimulation.scala) | Public one-month `step` boundary. |
| [`MonthCalculusRunner.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/flows/MonthCalculusRunner.scala) | Ordered same-month economics workflow. |
| [`MonthSemantics.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/MonthSemantics.scala) | Zero-cost phase tags for `Pre`, `SameMonth`, `Post`, and `NextPre`. |
| [`MonthRandomness.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/MonthRandomness.scala) | Explicit per-month randomness contract. |
| [`MonthFlowEmitter.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/flows/MonthFlowEmitter.scala) | Translation from same-month quantities into runtime ledger batches. |
| [`RuntimeFlowExecutor.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/flows/RuntimeFlowExecutor.scala) | Runtime ledger batch execution. |
| [`SfcSemanticProjection.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/flows/SfcSemanticProjection.scala) | Projection from executed batches and same-month semantic evidence into SFC validation flows. |
| [`closedmonth/MonthClosing.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/closedmonth/MonthClosing.scala) | Realized closed-month semantic state. |
| [`NextStateAdvancer.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/flows/NextStateAdvancer.scala) | Closed-month to next-pre state materialization. |

## Boundary State

The month-boundary state is the canonical state vector from
[model-notation-and-state-vector.md](model-notation-and-state-vector.md):

```text
X_t = (m_t, W_t, F_t, H_t, B_t, A^H_t, L_t)
```

where:

| Symbol | Meaning |
| --- | --- |
| `m_t` | completed month index |
| `W_t` | macro, market, mechanism, signal, and diagnostic world state |
| `F_t` | firm behavioral state vector |
| `H_t` | household behavioral state vector |
| `B_t` | bank operational state vector |
| `A^H_t` | household aggregates |
| `L_t` | ledger-owned financial state |

The public one-month engine contract is:

```text
Phi_tau : (X_t, RND_tau, theta) -> (X_tau, E_tau)
tau = t + 1
```

where:

| Symbol | Meaning |
| --- | --- |
| `theta` | model parameter vector, including scenario-adjusted parameters |
| `RND_tau` | explicit month randomness contract |
| `X_tau` | next completed month boundary state |
| `E_tau` | evidence emitted by the transition: flow plan, batches, ledger execution, semantic SFC flows, validation result, traces, and diagnostics |

The transition is deterministic conditional on `(X_t, RND_tau, theta)`.

## Randomness Contract

`RND_tau` is an explicit `MonthRandomness.Contract`, not ambient global
randomness. It is derived from one `rootSeed` and split into independent named
streams:

| Stream group | Streams |
| --- | --- |
| Same-month economics | household income economics, firm economics, household financial economics, open-economy economics, banking economics |
| Closed-month mechanisms | FDI M&A, firm entry, startup staffing, regional migration |

Fixing `X_t`, `theta`, and `RND_tau.rootSeed` fixes all named streams and
therefore fixes the one-month replay.

The full production seed policy and stochastic surface map are documented in
[stochastic-processes-and-replay.md](stochastic-processes-and-replay.md).

## Phase Timeline

The engine uses four coarse temporal phases:

| Phase | Meaning | Runtime anchor |
| --- | --- | --- |
| `Pre` | Start-of-month boundary, including persisted decision signals from `X_t` | `FlowSimulation.StepInput`, `MonthSemantics.SeedIn` |
| `SameMonth` | Ordered economics, operational signals, flow plan, semantic projection payload, and step evidence for month `tau` | `MonthCalculusRunner`, `MonthSemantics.FlowPlan`, `MonthSemantics.SignalView` |
| `Post` | Realized semantic closed-month state before it becomes the next public boundary | `MonthClosing.close`, `MonthSemantics.ClosedMonth` |
| `NextPre` | Extracted decision-signal seed and exposed `X_tau` boundary | `SignalExtraction`, `NextStateAdvancer` |

The publication-level dependency order is:

```text
pre boundary
-> same-month economics
-> same-month boundary views
-> semantic closed month and seed extraction
-> flow emission
-> runtime ledger execution
-> next-pre materialization
-> SFC validation gate and trace evidence
```

Implementation note: `MonthClosing.close` is pure and uses only same-month
closing inputs plus closing randomness. The public boundary `X_tau` is not
exposed until runtime ledger deltas are materialized by `NextStateAdvancer` and
SFC validation has been evaluated for the transition.

## Formal Stages

### 1. Pre Boundary

The engine constructs the execution month and opening snapshot:

```text
tau = next(m_t)
S_t = seedIn(W_t)
Omega_t = topology(X_t)
M_t = snapshot(X_t)
```

where:

| Symbol | Meaning |
| --- | --- |
| `S_t` | persisted start-of-month decision surface |
| `Omega_t` | runtime ledger topology derived from the opening state |
| `M_t` | audit snapshot of the opening month boundary |

The lineage invariant is:

```text
tau = next(m_t)
```

The next state must complete exactly this execution month; skipping or
rewriting the month index is an engine invariant violation.

### 2. Same-Month Economics

Same-month economics is the deterministic ordered workflow:

```text
C_tau = Gamma_tau(X_t, S_t, RND_tau^stage, theta)
```

`Gamma_tau` runs the same-month stages in this order:

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
-> price/equity
-> open economy
-> banking
-> month execution assembly
```

The same-month output is then narrowed into boundary views:

| View | Meaning | Runtime type |
| --- | --- | --- |
| `K_tau` | flow translation plan | `MonthlyCalculus` / `MonthSemantics.FlowPlan` |
| `V_tau` | same-month labor and demand signal view | `MonthSemantics.SignalView` |
| `Z_tau` | closed-month input | `MonthSemantics.ClosingInput` |
| `U_tau` | narrow semantic projection payload for SFC | `MonthSemantics.SemanticProjection` |
| `D_tau` | diagnostics and export evidence | `MonthSemantics.StepEvidence` |

Same-month economics does not execute runtime ledger batches. It computes
quantities, rates, decisions, closing projections, and the narrow evidence
needed by later boundaries.

### 3. Closed-Month Semantic State

The semantic closed month is computed from the same-month closing input and
closing randomness:

```text
C_tau^close = close(Z_tau, RND_tau^close, theta)
```

This stage applies closed-month mechanisms and diagnostics, including:

- informal-economy adjustment;
- flow-of-funds diagnostic residual;
- population lifecycle transitions;
- firm entry and startup staffing;
- FDI M&A ownership transition;
- regional migration;
- final world, agent, bank, household-aggregate, and semantic ledger closing
  projection.

The closed-month world still carries the old `SeedIn`. The next decision signal
surface is extracted separately.

### 4. Signal Extraction

The next pre-month decision surface is derived from realized month outcomes:

```text
S_tau = extractSeed(V_tau, C_tau^close)
```

`S_tau` becomes the persisted decision-signal surface for the next public
boundary. This keeps same-month operational signals distinct from lagged
signals used by the following month.

### 5. Flow Emission

The flow plan is translated into runtime ledger batches:

```text
B_tau = emit(K_tau, Omega_t, theta)
```

`B_tau` is a vector of typed runtime batches. Each batch has a mechanism,
source sector, destination sector, asset class, amount, and topology-specific
legs.

Flow emission is a translation boundary. It does not decide new economics and
does not mutate the month state.

### 6. Runtime Ledger Execution

Runtime batches are executed through the ledger interpreter:

```text
R_tau = execute(B_tau, Omega_t)
R_tau = (Delta L_tau^run, netDelta_tau)
```

where:

| Symbol | Meaning |
| --- | --- |
| `Delta L_tau^run` | executed runtime delta ledger |
| `netDelta_tau` | conservation check over the runtime delta space |

The runtime ledger must conserve value. Execution failure is a hard engine
failure. The executed delta ledger is not by itself the final model state; it
is materialized into the supported persisted ledger slice at the next boundary.

### 7. Next-Pre Materialization

The final next boundary is:

```text
X_tau = advance(X_t, tau, C_tau^close, S_t, S_tau, R_tau)
```

`NextStateAdvancer` performs two jobs:

1. It applies `S_tau` to the next boundary world.
2. It materializes supported runtime ledger deltas from `R_tau` into
   `LedgerFinancialState`.

The exposed state has:

```text
m_tau = tau
W_tau.seedIn = S_tau
```

and must satisfy the month-lineage and seed-boundary invariants checked by
`NextStateAdvancer`.

### 8. SFC Semantic Validation

The semantic SFC validation surface is built from:

```text
Q_tau = project(U_tau, B_tau, X_tau.world.plumbing.fofResidual, theta)
```

The validation checks:

```text
validate(X_t, X_tau, Q_tau, B_tau, R_tau) = pass
```

SFC validation compares the previous runtime state, final next runtime state,
semantic monthly flows, emitted batches, and runtime execution deltas. A failed
exact SFC identity is a hard model error.

## Evidence Surface

The transition evidence is:

```text
E_tau =
  (K_tau, V_tau, Z_tau, U_tau, D_tau,
   B_tau, R_tau, Q_tau, SFC_tau, Trace_tau)
```

where:

| Evidence | Meaning |
| --- | --- |
| `K_tau` | flow plan used for batch emission |
| `V_tau` | same-month signal view |
| `Z_tau` | closing input |
| `U_tau` | semantic projection input |
| `D_tau` | diagnostic/export evidence |
| `B_tau` | emitted runtime batches |
| `R_tau` | runtime ledger execution result |
| `Q_tau` | projected SFC semantic flows |
| `SFC_tau` | exact SFC validation result |
| `Trace_tau` | month trace with boundary snapshots, seed transition, timing, executed flows, and validations |

This evidence is what diagnostics, Monte Carlo exports, generated SFC
artifacts, and validation workflows consume.

## Invariants

Every valid month transition must satisfy:

1. Month lineage: `tau = next(m_t)` and `m_tau = tau`.
2. Deterministic replay: fixed `(X_t, RND_tau, theta)` fixes `X_tau` and
   `E_tau`.
3. Phase separation: same-month economics computes quantities and projections;
   runtime ledger execution books emitted batches; next-pre materialization
   exposes the final boundary.
4. Seed separation: `SeedIn` is consumed during month `tau`; `SeedOut` is
   applied only at `X_tau`.
5. Runtime ledger conservation: emitted batches execute without losing or
   creating PLN in the runtime delta ledger.
6. Exact SFC validation: semantic stock-flow identities close for
   `(X_t, X_tau, Q_tau, B_tau, R_tau)`.
7. Traceability: `Trace_tau` records boundary, seed, timing, executed-flow, and
   validation evidence for review.

## Reading Guidance

For publication writing, use this document for the transition structure and
[model-notation-and-state-vector.md](model-notation-and-state-vector.md) for
symbols. Use
[behavioral-equations-and-decision-rules.md](behavioral-equations-and-decision-rules.md)
for family-level equations and
[sfc-matrix-evidence.md](sfc-matrix-evidence.md) for accounting evidence.
