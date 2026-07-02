# AMOR FATI

*Stock-Flow Consistent Agent-Based Macroeconomic Engine*

> *Ducunt volentem fata, nolentem trahunt.*<br>
> The fates lead the willing; they drag the unwilling.<br>
> — Cleanthes, quoted by Seneca, *Epistulae Morales* 107.11

---

## Status

### Quality Gates

| CI | Coverage | Generated outputs |
| --- | --- | --- |
| [![CI](https://github.com/boombustgroup/amor-fati/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/boombustgroup/amor-fati/actions/workflows/ci.yml) | [![Coverage](https://codecov.io/gh/boombustgroup/amor-fati/graph/badge.svg)](https://codecov.io/gh/boombustgroup/amor-fati) | [![Generated outputs guarded](https://img.shields.io/badge/generated_outputs-guarded-2ea44f.svg)](docs/operations.md#generated-output-guard) |

### Research and Accounting Contract

| Exact SFC identities | Verified ledger | Empirical validation |
| --- | --- | --- |
| [![15 exact SFC identities](https://img.shields.io/badge/exact_SFC_identities-15-orange.svg)](docs/sfc-matrix-evidence.md) | [![Verified ledger with Stainless and Z3](https://img.shields.io/badge/verified_ledger-Stainless_%2B_Z3-4B32C3.svg)](https://github.com/boombustgroup/amor-fati-ledger/blob/main/docs/verification.md) | [![Empirical validation snapshot](https://img.shields.io/badge/empirical_validation-snapshot-0A7EA4.svg)](docs/empirical-validation-report.md) |

### Operational Diagnostics

| Diagnostics smoke | Diagnostics nightly | Diagnostics extended |
| --- | --- | --- |
| [![Diagnostics Smoke](https://github.com/boombustgroup/amor-fati/actions/workflows/diagnostics-smoke.yml/badge.svg?branch=main)](https://github.com/boombustgroup/amor-fati/actions/workflows/diagnostics-smoke.yml) | [![Diagnostics Nightly](https://github.com/boombustgroup/amor-fati/actions/workflows/diagnostics-nightly.yml/badge.svg?branch=main)](https://github.com/boombustgroup/amor-fati/actions/workflows/diagnostics-nightly.yml) | [![Diagnostics Extended](https://github.com/boombustgroup/amor-fati/actions/workflows/diagnostics-extended.yml/badge.svg?branch=main)](https://github.com/boombustgroup/amor-fati/actions/workflows/diagnostics-extended.yml) |

| Profiling smoke | Profiling nightly | Profiling extended |
| --- | --- | --- |
| [![Hot-Path Profiling Smoke](https://github.com/boombustgroup/amor-fati/actions/workflows/hot-path-profiling-smoke.yml/badge.svg?branch=main)](https://github.com/boombustgroup/amor-fati/actions/workflows/hot-path-profiling-smoke.yml) | [![Hot-Path Profiling Nightly](https://github.com/boombustgroup/amor-fati/actions/workflows/hot-path-profiling-nightly.yml/badge.svg?branch=main)](https://github.com/boombustgroup/amor-fati/actions/workflows/hot-path-profiling-nightly.yml) | [![Hot-Path Profiling Extended](https://github.com/boombustgroup/amor-fati/actions/workflows/hot-path-profiling-extended.yml/badge.svg?branch=main)](https://github.com/boombustgroup/amor-fati/actions/workflows/hot-path-profiling-extended.yml) |

---

A ledger-first SFC-ABM of the Polish economy where **every monetary flow is accounted for**. Firms produce, households consume, banks lend, the central bank sets policy, the government taxes and spends, the external sector trades and moves capital — and the books must balance. Always.

Amor Fati is a **stock-flow consistent** (SFC) **agent-based model** (ABM) that simulates the Polish economy at the level of individual households, heterogeneous firms, and a ten-row banking-sector archetype system. The engine enforces 15 exact accounting identities each month — if a single zloty goes missing, the simulation fails.

The key design principle is simple:

> **Macro stories can be wrong. The ledger cannot.**

This engine is built on top of the separately verified [amor-fati-ledger](https://github.com/boombustgroup/amor-fati-ledger) flow interpreter, checked out as a Git submodule under `modules/ledger`. The practical consequence is that the strongest invariant in the entire project is not "inflation should look smooth" or "GDP should converge nicely after 10 years". It is this:

> **The books must balance to the end of the universe.**

That is the hard floor under every experiment in the model. Behavioral rules, policy heuristics, and long-horizon dynamics can be revised, recalibrated, or replaced. The accounting layer cannot silently drift.

## Table of Contents

1. [Why](#why)
2. [What Is Technically Distinctive](#what-is-technically-distinctive)
3. [Core Invariants](#core-invariants)
4. [State Ontology](#state-ontology)
5. [Verified Ledger](#verified-ledger)
6. [Model Documentation](#model-documentation)
7. [Ledger-Derived Matrix Artifacts](#ledger-derived-matrix-artifacts)

## Why

Standard macro models (DSGE) assume representative agents and rational expectations. Reality has neither. Amor Fati models the economy from the bottom up: thousands of heterogeneous agents making bounded decisions, interacting through markets, generating emergent macro dynamics.

**Counterfactual analysis through code.** Want to test a policy hypothesis? Fork the repo, modify the mechanism, run the simulation, compare. The model is the experiment.

## What Is Technically Distinctive

Amor Fati is not just an ABM with accounting checks bolted on afterwards. The project combines several layers that are rarely pushed into one executable research engine:

- heterodox macroeconomics and SFC discipline
- heterogeneous-agent ABM microstructure
- a runtime ledger execution layer backed by formal verification work
- a data-oriented mutable execution substrate for performance
- explicit state ontology separating behavioral state, macro state, and financial state

The result is a model where macro dynamics, institutional behavior, and monetary plumbing are all first-class parts of the implementation rather than separate narratives.

## Core Invariants

The project is built around a few non-negotiable invariants:

- executed financial flows must conserve value exactly at runtime
- SFC validation must preserve 15 exact semantic accounting identities
- financial execution must not depend on ad hoc mutable bookkeeping outside the ledger path
- behavioral rules may change, but accounting consistency must remain hard-constrained

This is the core philosophy of the engine: theories may evolve, but broken plumbing is not an acceptable macro result.

## State Ontology

Amor Fati uses a hybrid runtime ontology rather than a single undifferentiated world object:

- behavioral populations
  - households, firms, and banks carry heterogeneous agent state and decision logic
- macro and market state
  - prices, policy, external conditions, and inter-step signals live in the macro runtime layer
- ledger-owned financial state
  - supported financial balances are kept in an accounting-controlled stock layer and projected into runtime execution and validation

This split is intentional. It keeps rich agent behavior where object-level modeling is useful, while moving accounting-critical execution into a stricter ledger substrate.

## Verified Ledger

Most macro models treat accounting consistency as a secondary validation step. Amor Fati does not.

The simulation pipeline is anchored to the verified [amor-fati-ledger](https://github.com/boombustgroup/amor-fati-ledger) layer that enforces the project’s stock-flow constraints at runtime. In other words:

- macro behavior is experimental
- agent rules are revisable
- accounting identities are non-negotiable

This is why Amor Fati is useful even when the long-run path is still being calibrated. If a branch generates a bad macro regime, that may be a modeling problem. If the ledger breaks, the simulation itself is wrong.

That distinction matters. A nonlinear ABM can explore unstable, surprising, even pathological futures. But it should never "lose money" in the plumbing.

## Model Documentation

Start with the [model specification](docs/model-specification.md). Its
[Reviewer Reading Path](docs/model-specification.md#reviewer-reading-path) is
the canonical first-pass path for scientific review. For the complete
documentation index, use [docs/README.md](docs/README.md):

| Step | Entry point | Boundary |
| --- | --- | --- |
| 1. Model specification and ODD | [Model specification](docs/model-specification.md) and [ODD / ODD+D model documentation](docs/odd-model-documentation.md) | Model identity, scope, entities, scheduling, state vector, month timing, equation families, stochasticity, limitations, and pointers to detailed sector documents. |
| 2. SFC evidence boundary | [SFC matrix evidence](docs/sfc-matrix-evidence.md) and [model equations to SFC map](docs/model-equations-to-sfc-map.md) | Hand-maintained entry points for generated BSM/TFM snapshots, exact identities, runtime mechanism mapping, and stock-flow reconciliation evidence. |
| 3. Calibration evidence | [Calibration register](docs/calibration-register.md) and [data bridge](docs/data-bridge-national-financial-accounts.md) | Parameter provenance, empirical sources, transformations, assumptions, and visible calibration gaps. |
| 4. Validation evidence | [Empirical validation report](docs/empirical-validation-report.md) and [engine invariants](docs/engine-invariants-and-semantics.md) | Empirical snapshot workflow, normal-path expectations, hard invariants, warnings, and known limitation surfaces. |
| 5. Operational appendices | [Operations](docs/operations.md) | Entry point for commands, CI ownership, diagnostics/profiling routing, generated-output guards, scenarios, robustness, and local artifacts. |

## Ledger-Derived Matrix Artifacts

Generated SFC snapshots live under
[docs/sfc-matrix-artifacts/](docs/sfc-matrix-artifacts/), but they are not a
separate README reading path:

| Step | Entry point | Boundary |
| --- | --- | --- |
| 1. Generated matrix snapshots | [SFC matrix artifacts](docs/sfc-matrix-artifacts/) | Generated BSM/TFM snapshots, matrix mapping, flow-mechanism semantics, and stock-flow reconciliation artifacts. |
| 2. Evidence and regeneration | [SFC matrix evidence](docs/sfc-matrix-evidence.md) | Regeneration commands, sign conventions, exact reconciliation rows, and coverage gaps. |
| 3. Equation mapping | [Model equations to SFC map](docs/model-equations-to-sfc-map.md) | Hand-maintained bridge from model equation families to generated rows, identities, and runtime evidence. |

## Tech Stack

![Scala](https://img.shields.io/badge/Scala_3-DC322F?logo=scala&logoColor=white)
![Stainless](https://img.shields.io/badge/Stainless-Formal%20Verification-4B5563)
![Z3](https://img.shields.io/badge/Z3-SMT%20Solver-1F6FEB)
![sbt](https://img.shields.io/badge/sbt-1.11.6-blue)

## License

[Apache 2.0](LICENSE) — Copyright 2026 [BoomBustGroup](https://www.boombustgroup.com/)
