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
| [![CI](https://github.com/boombustgroup/amor-fati/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/boombustgroup/amor-fati/actions/workflows/ci.yml) | [![Coverage](https://codecov.io/gh/boombustgroup/amor-fati/graph/badge.svg)](https://codecov.io/gh/boombustgroup/amor-fati) | [![Generated outputs guarded](https://img.shields.io/badge/generated_outputs-guarded-2ea44f.svg)](scripts/check-generated-outputs.sh) |

### Research and Accounting Contract

| Exact SFC identities | Verified ledger | Empirical validation |
| --- | --- | --- |
| [![15 exact SFC identities](https://img.shields.io/badge/exact_SFC_identities-15-orange.svg)](docs/sfc-matrix-evidence.md) | [![Verified ledger with Stainless and Z3](https://img.shields.io/badge/verified_ledger-Stainless_%2B_Z3-4B32C3.svg)](modules/ledger/docs/verification.md) | [![Empirical validation snapshot](https://img.shields.io/badge/empirical_validation-snapshot-0A7EA4.svg)](docs/empirical-validation-report.md) |

### Operational Diagnostics

| Diagnostics smoke | Diagnostics nightly | Diagnostics extended |
| --- | --- | --- |
| [![Diagnostics Smoke](https://github.com/boombustgroup/amor-fati/actions/workflows/diagnostics-smoke.yml/badge.svg?branch=main)](https://github.com/boombustgroup/amor-fati/actions/workflows/diagnostics-smoke.yml) | [![Diagnostics Nightly](https://github.com/boombustgroup/amor-fati/actions/workflows/diagnostics-nightly.yml/badge.svg?branch=main)](https://github.com/boombustgroup/amor-fati/actions/workflows/diagnostics-nightly.yml) | [![Diagnostics Extended](https://github.com/boombustgroup/amor-fati/actions/workflows/diagnostics-extended.yml/badge.svg?branch=main)](https://github.com/boombustgroup/amor-fati/actions/workflows/diagnostics-extended.yml) |

| Profiling smoke | Profiling nightly | Profiling extended |
| --- | --- | --- |
| [![Hot-Path Profiling Smoke](https://github.com/boombustgroup/amor-fati/actions/workflows/hot-path-profiling-smoke.yml/badge.svg?branch=main)](https://github.com/boombustgroup/amor-fati/actions/workflows/hot-path-profiling-smoke.yml) | [![Hot-Path Profiling Nightly](https://github.com/boombustgroup/amor-fati/actions/workflows/hot-path-profiling-nightly.yml/badge.svg?branch=main)](https://github.com/boombustgroup/amor-fati/actions/workflows/hot-path-profiling-nightly.yml) | [![Hot-Path Profiling Extended](https://github.com/boombustgroup/amor-fati/actions/workflows/hot-path-profiling-extended.yml/badge.svg?branch=main)](https://github.com/boombustgroup/amor-fati/actions/workflows/hot-path-profiling-extended.yml) |

---

A ledger-first SFC-ABM of the Polish economy where **every monetary flow is accounted for**. Firms produce, households consume, banks lend, the central bank sets policy, the government taxes and spends, the external sector trades and moves capital — and the books must balance. Always.

Amor Fati is a **stock-flow consistent** (SFC) **agent-based model** (ABM) that simulates the Polish economy at the level of individual households, heterogeneous firms, and a realistic multi-bank financial system. The engine enforces 15 exact accounting identities each month — if a single zloty goes missing, the simulation fails.

The key design principle is simple:

> **Macro stories can be wrong. The ledger cannot.**

This engine is built on top of the separately verified [amor-fati-ledger](https://github.com/boombustgroup/amor-fati-ledger) flow interpreter, checked out as a Git submodule under `modules/ledger`. The practical consequence is that the strongest invariant in the entire project is not "inflation should look smooth" or "GDP should converge nicely after 10 years". It is this:

> **The books must balance to the end of the universe.**

That is the hard floor under every experiment in the model. Behavioral rules, policy heuristics, and long-horizon dynamics can be revised, recalibrated, or replaced. The accounting layer cannot silently drift.

## Table of Contents

- [Quick Start](#quick-start)
- [Operational Documentation](#operational-documentation)
- [Why](#why)
- [What Is Technically Distinctive](#what-is-technically-distinctive)
- [Core Invariants](#core-invariants)
- [State Ontology](#state-ontology)
- [Verified Ledger](#verified-ledger)
- [Model Documentation](#model-documentation)
- [Ledger-Derived Matrix Artifacts](#ledger-derived-matrix-artifacts)
- [Tech Stack](#tech-stack)
- [License](#license)

## Quick Start

Amor Fati is currently operated as a source-first research engine: clone the
repo, run the tests, then run the model or a diagnostic from `sbt`.

Requirements:

- JDK 21 as the supported baseline, matching CI's Temurin 21 runtime
- sbt 1.11.6, pinned in `project/build.properties`

Alternatively, enter the optional Nix developer shell:

```bash
nix develop
```

The flake provides JDK 21, the nixpkgs sbt launcher, Python 3, Z3, Git,
standard shell utilities, and the same `SBT_OPTS` baseline used by CI. The sbt
launcher respects `project/build.properties`, so the root build still runs with
the pinned sbt version. `flake.lock` pins the nixpkgs revision used by both
local Nix shells and CI.

Clone the repository with its ledger submodule:

```bash
git clone --recurse-submodules https://github.com/boombustgroup/amor-fati.git
cd amor-fati
```

If the repository was cloned without submodules:

```bash
git submodule update --init --recursive
```

Validate the checkout:

```bash
sbt test
```

Run a one-seed, 12-month local smoke simulation:

```bash
sbt "runMain com.boombustgroup.amorfati.Main 1 local-smoke --duration 12 --run-id smoke"
```

This writes generated CSV outputs under `mc/`, which is intentionally ignored
by git.

## Operational Documentation

Day-to-day commands, test tiers, diagnostics, scenario runs, output locations,
and troubleshooting notes live in
[docs/operations.md](docs/operations.md).

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

Amor Fati now includes a research-readiness documentation spine for review,
replication, calibration, validation, and publication work:

| Artifact | Purpose |
| --- | --- |
| [Model specification](docs/model-specification.md) | Canonical publication-facing entry point: model identity, scope, state vector, monthly transition, equation families, SFC/accounting contract, stochasticity, calibration, validation, limitations, and reading order. |
| [Model-spec completeness checklist](docs/model-spec-completeness-checklist.md) | Review checklist for model-family coverage across notation, equations, implementation anchors, output columns, SFC/ledger mapping, calibration references, validation diagnostics, and visible gaps. |
| [Model notation and state vector](docs/model-notation-and-state-vector.md) | Canonical publication-facing notation for time, agents, sectors, stocks, flows, rates, shares, stochastic variables, the full model state vector, and runtime implementation anchors. |
| [Monthly transition function](docs/monthly-transition-function.md) | Mathematical month-step contract from `X_t` to `X_tau`: randomness, same-month economics, flow emission, runtime ledger execution, closed-month state, SFC validation, trace evidence, and next-pre boundary. |
| [Firm equations](docs/firm-equations.md) | Publication-facing firm-sector equations: firm state, production/capacity, labor, pricing, inventory, investment, technology adoption, financing, default/NPL, entry/exit, outputs, validation, and limitations. |
| [ODD / ODD+D model documentation](docs/odd-model-documentation.md) | ODD/ODD+D source document: purpose, entities, state variables, scales, scheduling, initialization, inputs, submodels, observation surfaces, and decision-making notes. |
| [Behavioral equations and decision rules](docs/behavioral-equations-and-decision-rules.md) | Household, firm, bank, fiscal, monetary, external, insurance, NBFI, quasi-fiscal, and JST rules linked to implementation modules and numeric output columns. |
| [Calibration register](docs/calibration-register.md) | Key parameter values, units, implementation owners, empirical targets, transformations, provenance status, and searchable gaps. |
| [Data bridge to national and financial accounts](docs/data-bridge-national-financial-accounts.md) | Official Polish, EU, and financial-account sources mapped to initialization stocks, calibrated parameters, scenario inputs, validation targets, transformations, and prioritized empirical gaps. |
| [Empirical validation report](docs/empirical-validation-report.md) | Workflow for the empirical-validation snapshot: the curated source manifest is the editable input, while generated baseline artifacts live under `docs/empirical-validation/`. |
| [Engine invariants and economic semantics](docs/engine-invariants-and-semantics.md) | Canonical reviewer-facing index of hard invariants, normal-path expectations, stress/exploratory diagnostics, calibration warnings, known limitations, enforcement points, and coverage. |
| [Validation matrix and ownership boundaries](docs/validation-matrix.md) | CI, integration-test, generated-output, nightly, stress, and profiling ownership rules so new validation work lands in the right layer. |
| [Performance regression budgets](docs/performance-regression-budgets.md) | Soft baseline comparisons for diagnostics and profiling telemetry, using existing run manifests without adding duplicate simulations. |
| [Sensitivity and robustness workflow](docs/sensitivity-robustness-workflow.md) | Seed envelopes and one-at-a-time parameter-sensitivity artifacts generated from the Monte Carlo runner. |
| [Reproducible scenario registry](docs/scenario-registry.md) | Named policy and shock scenarios with exact parameter deltas from baseline, expected channels, seed/run metadata, and the `scenarioRun` execution path. |

## Ledger-Derived Matrix Artifacts

The project includes committed Markdown snapshots of the paper-facing SFC
matrix artifacts:

| Artifact | Purpose |
| --- | --- |
| [Balance Sheet Matrix (BSM)](docs/sfc-matrix-artifacts/symbolic-bsm.md) | Symbolic stock matrix by instrument and sector, using SFC asset/liability signs and explicit row sums. |
| [Transactions Flow Matrix (TFM)](docs/sfc-matrix-artifacts/symbolic-tfm.md) | Symbolic monthly flow matrix by sector, including income, taxes, transfers, interest, trade, credit, bonds, and deposit changes. |
| [Stock-Flow Reconciliation and Revaluation Evidence](docs/sfc-matrix-artifacts/stock-flow-reconciliation.md) | Executed-run evidence comparing observed stock deltas or level identities with independent transaction, revaluation, default, write-off, and other-change channels. |
| [Symbolic-row to runtime mapping](docs/sfc-matrix-artifacts/matrix-mapping.md) | Traceability table linking each symbolic matrix row to runtime assets, mechanisms, ids, and coverage notes. |
| [Economic flow-channel semantics](docs/sfc-matrix-artifacts/flow-mechanism-semantics.md) | Reviewer-facing audit map for executed economic flow channels, including family, topology, asset class, SFC/reconciliation impact, ledger survivability, and test/diagnostic coverage. |

These snapshots are generated from an executed deterministic simulation step
and committed as versioned evidence. The regeneration commands, sign
conventions, coverage gaps, exact reconciliation rows, and review checklist are
documented in
[docs/sfc-matrix-evidence.md](docs/sfc-matrix-evidence.md).

## Tech Stack

![Scala](https://img.shields.io/badge/Scala_3-DC322F?logo=scala&logoColor=white)
![Stainless](https://img.shields.io/badge/Stainless-Formal%20Verification-4B5563)
![Z3](https://img.shields.io/badge/Z3-SMT%20Solver-1F6FEB)
![sbt](https://img.shields.io/badge/sbt-1.11.6-blue)

## License

[Apache 2.0](LICENSE) — Copyright 2026 [BoomBustGroup](https://www.boombustgroup.com/)
