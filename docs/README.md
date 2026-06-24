# Documentation Index

This is the index for Amor Fati documentation. It keeps the research-facing
reading path, operational appendices, generated evidence, and complete document
inventory in one place.

This document does not define model behavior. The documentation hygiene check
requires every committed artifact under `docs/` to appear in the complete
inventory below, so new documentation cannot silently become orphaned.

## Start Here

| Need | Start here |
| --- | --- |
| First scientific review | [Model specification reviewer reading path](model-specification.md#reviewer-reading-path) |
| ABM structure and scheduling | [ODD / ODD+D model documentation](odd-model-documentation.md) |
| Sector equations and decision rules | [Model specification source map](model-specification.md#source-map) |
| SFC matrix and ledger-derived evidence | [SFC matrix evidence](sfc-matrix-evidence.md) and [model equations to SFC map](model-equations-to-sfc-map.md) |
| Calibration and empirical validation | [Calibration register](calibration-register.md), [data bridge](data-bridge-national-financial-accounts.md), and [empirical validation report](empirical-validation-report.md) |
| Commands, CI, diagnostics, scenarios, and local outputs | [Operations appendix index](operations.md#operational-appendix-index) |

## Operational Docs

Operational, diagnostic, calibration, profiling, and scenario documents remain
appendices unless the model specification explicitly promotes them into the
first-pass reviewer path. Use [operations.md](operations.md#operational-appendix-index)
as the operational entry point.

## Owner Classes

| Owner class | Meaning |
| --- | --- |
| Canonical reviewer spine | Paper-facing model contract or primary reviewer reference. These files define how the implemented model should be read. |
| Generated evidence | Committed output produced by a task or exporter. Do not edit by hand; regenerate through the owning command. |
| Hand-maintained companion to generated evidence | Human-written bridge, index, or workflow note that explains generated evidence and must stay aligned with it. |
| Calibration or empirical evidence | Parameter, source, calibration, or empirical-validation evidence. These files do not define parameter governance policy; deeper governance belongs to the [Calibration Governance milestone](https://github.com/boombustgroup/amor-fati/milestone/27). |
| Operational appendix | Commands, CI, validation ownership, scenarios, robustness, and runbook material. Useful, but not the first scientific reading path. |
| Diagnostics or profiling appendix | Specific diagnostic/profiling methodology, exporter interpretation, or investigation evidence. |
| ADR or decision record | Durable architectural or semantic decision record. Preserve history unless superseded explicitly. |

## Complete Inventory

| Artifact | Owner class | Purpose |
| --- | --- | --- |
| [docs/data-bridge-national-financial-accounts.md](data-bridge-national-financial-accounts.md) | Calibration or empirical evidence | Empirical source bridge from Polish, EU, and financial-account data to initialization, calibration, scenarios, and validation. |
| [docs/empirical-validation-report.md](empirical-validation-report.md) | Calibration or empirical evidence | Interpretation contract for empirical-validation artifacts, status taxonomy, snapshot bundle, and source-manifest expectations. |
| [docs/empirical-validation-source-manifest.tsv](empirical-validation-source-manifest.tsv) | Calibration or empirical evidence | Editable source manifest used by empirical-validation export. |
| [docs/external-sector-baseline-calibration.md](external-sector-baseline-calibration.md) | Calibration or empirical evidence | External-sector baseline calibration note and evidence from the related diagnostic ticket. |
| [docs/household-credit-stress-calibration.md](household-credit-stress-calibration.md) | Calibration or empirical evidence | Household credit stress calibration note and diagnostic evidence. |
| [docs/private-credit-renewal-calibration.md](private-credit-renewal-calibration.md) | Calibration or empirical evidence | Private-credit renewal calibration note and baseline diagnostic evidence. |
| [docs/banking-and-financial-sector-equations.md](banking-and-financial-sector-equations.md) | Canonical reviewer spine | Paper-facing banking and financial-sector equations, timing, SFC mapping, diagnostics, and limitations. |
| [docs/behavioral-equations-and-decision-rules.md](behavioral-equations-and-decision-rules.md) | Canonical reviewer spine | Detailed rule catalog for household, firm, bank, fiscal, monetary, external, and non-bank behavior. |
| [docs/engine-invariants-and-semantics.md](engine-invariants-and-semantics.md) | Canonical reviewer spine | Reviewer-facing index of hard invariants, normal-path expectations, limitations, enforcement points, and coverage. |
| [docs/firm-equations.md](firm-equations.md) | Canonical reviewer spine | Paper-facing firm-sector equations, implementation anchors, SFC mapping, outputs, validation, and limitations. |
| [docs/household-equations.md](household-equations.md) | Canonical reviewer spine | Paper-facing household-sector equations, implementation anchors, SFC mapping, outputs, validation, and limitations. |
| [docs/institutional-sector-equations.md](institutional-sector-equations.md) | Canonical reviewer spine | Paper-facing public, monetary, external, insurance, NBFI, TFI, quasi-fiscal, and JST equations. |
| [docs/model-notation-and-state-vector.md](model-notation-and-state-vector.md) | Canonical reviewer spine | Canonical notation, state-vector definitions, symbols, ownership boundaries, and implementation anchors. |
| [docs/model-specification.md](model-specification.md) | Canonical reviewer spine | Canonical publication-facing model specification, reviewer reading path, and first scientific entry point. |
| [docs/monthly-transition-function.md](monthly-transition-function.md) | Canonical reviewer spine | Formal monthly transition contract from pre-boundary state through same-month economics, ledger execution, SFC validation, and next-pre materialization. |
| [docs/odd-model-documentation.md](odd-model-documentation.md) | Canonical reviewer spine | ODD/ODD+D model documentation for ABM structure, scheduling, entities, observations, and decision-making. |
| [docs/stochastic-processes-and-replay.md](stochastic-processes-and-replay.md) | Canonical reviewer spine | Publication-facing randomness, seed, stream, replay, validation, and stochastic-limitation contract. |
| [README.md](../README.md) | Canonical reviewer spine | Repository front door, status overview, model identity, and top-level documentation entry. |
| [docs/bank-balance-sheet-benchmark.md](bank-balance-sheet-benchmark.md) | Diagnostics or profiling appendix | Bank balance-sheet benchmark diagnostic and bank-capital source interpretation. |
| [docs/bank-failure-ablations.md](bank-failure-ablations.md) | Diagnostics or profiling appendix | Bank-failure ablation diagnostic methodology and interpretation. |
| [docs/hh-bank-lead-lag-diagnostics.md](hh-bank-lead-lag-diagnostics.md) | Diagnostics or profiling appendix | Household-to-bank lead-lag diagnostic methodology and interpretation. |
| [docs/hot-path-profiling.md](hot-path-profiling.md) | Diagnostics or profiling appendix | Profiling workflow for hot execution paths and performance evidence. |
| [docs/loan-origination-quality-diagnostics.md](loan-origination-quality-diagnostics.md) | Diagnostics or profiling appendix | Loan-origination quality diagnostic methodology and interpretation. |
| [docs/calibration-register.md](calibration-register.md) | Generated evidence | Generated parameter register from `calibrationRegister`; records parameters, units, owners, provenance, and gaps. |
| [docs/empirical-source-extracts/production-sector-gva-shares.tsv](empirical-source-extracts/production-sector-gva-shares.tsv) | Generated evidence | Generated GUS-primary and Eurostat-allocation source extract for six-sector production GVA shares. |
| [docs/empirical-source-extracts/production-sector-labor-bridges.tsv](empirical-source-extracts/production-sector-labor-bridges.tsv) | Generated evidence | Generated GUS/Eurostat source extract for sector firm-population, employment, and wage-ratio bridges. |
| [docs/empirical-validation/baseline-validation-snapshot.tsv](empirical-validation/baseline-validation-snapshot.tsv) | Generated evidence | Generated empirical-validation baseline snapshot. |
| [docs/empirical-validation/model-run-manifest.tsv](empirical-validation/model-run-manifest.tsv) | Generated evidence | Generated model-run manifest for the empirical-validation snapshot. |
| [docs/empirical-validation/source-manifest.tsv](empirical-validation/source-manifest.tsv) | Generated evidence | Generated copy of the empirical-validation source manifest captured with the snapshot. |
| [docs/sfc-matrix-artifacts/flow-mechanism-semantics.md](sfc-matrix-artifacts/flow-mechanism-semantics.md) | Generated evidence | Generated audit table for every runtime `FlowMechanism`, including topology, asset class, SFC impact, survivability, and coverage. |
| [docs/sfc-matrix-artifacts/matrix-mapping.md](sfc-matrix-artifacts/matrix-mapping.md) | Generated evidence | Generated mapping from symbolic BSM/TFM rows to runtime assets, mechanisms, and coverage notes. |
| [docs/sfc-matrix-artifacts/stock-flow-reconciliation.md](sfc-matrix-artifacts/stock-flow-reconciliation.md) | Generated evidence | Generated executed-run evidence for exact SFC identities and stock-flow reconciliation. |
| [docs/sfc-matrix-artifacts/symbolic-bsm.md](sfc-matrix-artifacts/symbolic-bsm.md) | Generated evidence | Generated symbolic Balance Sheet Matrix snapshot. |
| [docs/sfc-matrix-artifacts/symbolic-tfm.md](sfc-matrix-artifacts/symbolic-tfm.md) | Generated evidence | Generated symbolic Transactions Flow Matrix snapshot. |
| [docs/empirical-validation/README.md](empirical-validation/README.md) | Hand-maintained companion to generated evidence | Source contract for the generated empirical-validation snapshot TSVs. |
| [docs/model-equations-to-sfc-map.md](model-equations-to-sfc-map.md) | Hand-maintained companion to generated evidence | Human-maintained bridge from equation families to generated SFC rows, identities, mechanisms, and known accounting limits. |
| [docs/sfc-matrix-evidence.md](sfc-matrix-evidence.md) | Hand-maintained companion to generated evidence | Workflow and source-contract document for ledger-derived SFC matrix artifacts and generated SFC evidence. |
| [docs/nightly-baseline-comparison.md](nightly-baseline-comparison.md) | Operational appendix | Nightly diagnostic baseline comparison policy and report semantics. |
| [docs/nightly-diagnostics.md](nightly-diagnostics.md) | Operational appendix | Diagnostic profile taxonomy, nightly/extended execution semantics, and manifest policy. |
| [docs/operations.md](operations.md) | Operational appendix | Command-oriented guide for local setup, model runs, tests, diagnostics, scenario runs, output locations, and guards. |
| [docs/performance-regression-budgets.md](performance-regression-budgets.md) | Operational appendix | Performance regression budget policy and promotion criteria for profiling/diagnostic telemetry. |
| [docs/README.md](README.md) | Operational appendix | Documentation index and ownership inventory. |
| [docs/scenario-registry.md](scenario-registry.md) | Operational appendix | Named policy and shock scenario registry with reproducible scenario-run metadata. |
| [docs/sensitivity-robustness-workflow.md](sensitivity-robustness-workflow.md) | Operational appendix | Seed uncertainty and one-at-a-time robustness workflow for scenario and publication prep. |
| [docs/validation-matrix.md](validation-matrix.md) | Operational appendix | Ownership map for CI, integration tests, generated-output validation, nightly diagnostics, stress, and profiling. |
