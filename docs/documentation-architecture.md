# Documentation Architecture

This inventory is the documentation ownership map for Amor Fati. It exists to
keep the research-facing documentation navigable after the model specification,
SFC evidence, diagnostics, calibration, and performance work.

The inventory covers `README.md` and every committed artifact under `docs/`.
This document does not define model behavior. Instead, it classifies ownership,
reader role, and source-of-truth status so future cleanup can reduce confusion
without deleting evidence.

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
| Deprecated or merge candidate | Still present, but likely to be merged, archived, or replaced after review. Do not remove silently. |

## First-Pass Reviewer Path

The canonical first-pass reviewer path is maintained in
[model-specification.md#reviewer-reading-path](model-specification.md#reviewer-reading-path).
README links to that path as the public front door; this inventory exists to
classify the remaining documentation surfaces and prevent secondary documents
from creating competing top-level reading orders.

That canonical path separates:

1. model specification and executable model contract;
2. sector and behavior detail;
3. SFC evidence boundary;
4. calibration evidence;
5. validation evidence;
6. operational appendices.

Operational, diagnostic, calibration, profiling, and scenario documents remain
appendices unless the model specification explicitly promotes them into the
first-pass path.

## Inventory

| Artifact | Owner class | Purpose |
| --- | --- | --- |
| [README.md](../README.md) | Canonical reviewer spine | Repository front door, status overview, model identity, and top-level documentation entry. |
| [docs/adr/0001-bank-capital-sfc-semantics.md](adr/0001-bank-capital-sfc-semantics.md) | ADR or decision record | Accepted decision defining bank capital as SFC-validated regulatory state rather than holder-resolved equity. |
| [docs/bank-balance-sheet-benchmark.md](bank-balance-sheet-benchmark.md) | Diagnostics or profiling appendix | Bank balance-sheet benchmark diagnostic and bank-capital source interpretation. |
| [docs/bank-failure-ablations.md](bank-failure-ablations.md) | Diagnostics or profiling appendix | Bank-failure ablation diagnostic methodology and interpretation. |
| [docs/banking-and-financial-sector-equations.md](banking-and-financial-sector-equations.md) | Canonical reviewer spine | Paper-facing banking and financial-sector equations, timing, SFC mapping, diagnostics, and limitations. |
| [docs/behavioral-equations-and-decision-rules.md](behavioral-equations-and-decision-rules.md) | Canonical reviewer spine | Detailed rule catalog for household, firm, bank, fiscal, monetary, external, and non-bank behavior. |
| [docs/calibration-register.md](calibration-register.md) | Generated evidence | Generated parameter register from `calibrationRegister`; records parameters, units, owners, provenance, and gaps. |
| [docs/data-bridge-national-financial-accounts.md](data-bridge-national-financial-accounts.md) | Calibration or empirical evidence | Empirical source bridge from Polish, EU, and financial-account data to initialization, calibration, scenarios, and validation. |
| [docs/documentation-architecture.md](documentation-architecture.md) | Operational appendix | Documentation ownership inventory, reviewer reading path, generated-evidence boundary, and cleanup source of truth. |
| [docs/empirical-validation-report.md](empirical-validation-report.md) | Calibration or empirical evidence | Empirical-validation workflow and interpretation for committed validation snapshots. |
| [docs/empirical-validation-source-manifest.csv](empirical-validation-source-manifest.csv) | Calibration or empirical evidence | Editable source manifest used by empirical-validation export. |
| [docs/empirical-validation/README.md](empirical-validation/README.md) | Hand-maintained companion to generated evidence | Explains the empirical-validation snapshot bundle and its generated CSVs. |
| [docs/empirical-validation/baseline-validation-snapshot.csv](empirical-validation/baseline-validation-snapshot.csv) | Generated evidence | Generated empirical-validation baseline snapshot. |
| [docs/empirical-validation/model-run-manifest.csv](empirical-validation/model-run-manifest.csv) | Generated evidence | Generated model-run manifest for the empirical-validation snapshot. |
| [docs/empirical-validation/source-manifest.csv](empirical-validation/source-manifest.csv) | Generated evidence | Generated copy of the empirical-validation source manifest captured with the snapshot. |
| [docs/engine-invariants-and-semantics.md](engine-invariants-and-semantics.md) | Canonical reviewer spine | Reviewer-facing index of hard invariants, normal-path expectations, limitations, enforcement points, and coverage. |
| [docs/external-sector-baseline-calibration.md](external-sector-baseline-calibration.md) | Calibration or empirical evidence | External-sector baseline calibration note and evidence from the related diagnostic ticket. |
| [docs/firm-equations.md](firm-equations.md) | Canonical reviewer spine | Paper-facing firm-sector equations, implementation anchors, SFC mapping, outputs, validation, and limitations. |
| [docs/hh-bank-lead-lag-diagnostics.md](hh-bank-lead-lag-diagnostics.md) | Diagnostics or profiling appendix | Household-to-bank lead-lag diagnostic methodology and interpretation. |
| [docs/hot-path-profiling.md](hot-path-profiling.md) | Diagnostics or profiling appendix | Profiling workflow for hot execution paths and performance evidence. |
| [docs/household-credit-stress-calibration.md](household-credit-stress-calibration.md) | Calibration or empirical evidence | Household credit stress calibration note and diagnostic evidence. |
| [docs/household-equations.md](household-equations.md) | Canonical reviewer spine | Paper-facing household-sector equations, implementation anchors, SFC mapping, outputs, validation, and limitations. |
| [docs/institutional-sector-equations.md](institutional-sector-equations.md) | Canonical reviewer spine | Paper-facing public, monetary, external, insurance, NBFI, TFI, quasi-fiscal, and JST equations. |
| [docs/loan-origination-quality-diagnostics.md](loan-origination-quality-diagnostics.md) | Diagnostics or profiling appendix | Loan-origination quality diagnostic methodology and interpretation. |
| [docs/model-equations-to-sfc-map.md](model-equations-to-sfc-map.md) | Hand-maintained companion to generated evidence | Human-maintained bridge from equation families to generated SFC rows, identities, mechanisms, and known accounting limits. |
| [docs/model-notation-and-state-vector.md](model-notation-and-state-vector.md) | Canonical reviewer spine | Canonical notation, state-vector definitions, symbols, ownership boundaries, and implementation anchors. |
| [docs/model-spec-completeness-checklist.md](model-spec-completeness-checklist.md) | Canonical reviewer spine | Publication-readiness QA checklist for coverage across notation, equations, implementation anchors, outputs, SFC mapping, calibration, and validation. |
| [docs/model-specification.md](model-specification.md) | Canonical reviewer spine | Canonical publication-facing model specification, reviewer reading path, and first scientific entry point. |
| [docs/monthly-transition-function.md](monthly-transition-function.md) | Canonical reviewer spine | Formal monthly transition contract from pre-boundary state through same-month economics, ledger execution, SFC validation, and next-pre materialization. |
| [docs/nightly-baseline-comparison.md](nightly-baseline-comparison.md) | Operational appendix | Nightly diagnostic baseline comparison policy and report semantics. |
| [docs/nightly-diagnostics.md](nightly-diagnostics.md) | Operational appendix | Diagnostic profile taxonomy, nightly/extended execution semantics, and manifest policy. |
| [docs/odd-model-documentation.md](odd-model-documentation.md) | Canonical reviewer spine | ODD/ODD+D model documentation for ABM structure, scheduling, entities, observations, and decision-making. |
| [docs/operations.md](operations.md) | Operational appendix | Command-oriented runbook for tests, diagnostics, generated outputs, and operational workflows. |
| [docs/performance-regression-budgets.md](performance-regression-budgets.md) | Operational appendix | Performance regression budget policy and promotion criteria for profiling/diagnostic telemetry. |
| [docs/private-credit-renewal-calibration.md](private-credit-renewal-calibration.md) | Calibration or empirical evidence | Private-credit renewal calibration note and baseline diagnostic evidence. |
| [docs/scenario-registry.md](scenario-registry.md) | Operational appendix | Named policy and shock scenario registry with reproducible scenario-run metadata. |
| [docs/sensitivity-robustness-workflow.md](sensitivity-robustness-workflow.md) | Operational appendix | Seed uncertainty and one-at-a-time robustness workflow for scenario and publication prep. |
| [docs/sfc-matrix-artifacts/flow-mechanism-semantics.md](sfc-matrix-artifacts/flow-mechanism-semantics.md) | Generated evidence | Generated audit table for every runtime `FlowMechanism`, including topology, asset class, SFC impact, survivability, and coverage. |
| [docs/sfc-matrix-artifacts/matrix-mapping.md](sfc-matrix-artifacts/matrix-mapping.md) | Generated evidence | Generated mapping from symbolic BSM/TFM rows to runtime assets, mechanisms, and coverage notes. |
| [docs/sfc-matrix-artifacts/stock-flow-reconciliation.md](sfc-matrix-artifacts/stock-flow-reconciliation.md) | Generated evidence | Generated executed-run evidence for exact SFC identities and stock-flow reconciliation. |
| [docs/sfc-matrix-artifacts/symbolic-bsm.md](sfc-matrix-artifacts/symbolic-bsm.md) | Generated evidence | Generated symbolic Balance Sheet Matrix snapshot. |
| [docs/sfc-matrix-artifacts/symbolic-tfm.md](sfc-matrix-artifacts/symbolic-tfm.md) | Generated evidence | Generated symbolic Transactions Flow Matrix snapshot. |
| [docs/sfc-matrix-evidence.md](sfc-matrix-evidence.md) | Hand-maintained companion to generated evidence | Workflow and source-contract document for ledger-derived SFC matrix artifacts and generated SFC evidence. |
| [docs/stochastic-processes-and-replay.md](stochastic-processes-and-replay.md) | Canonical reviewer spine | Publication-facing randomness, seed, stream, replay, validation, and stochastic-limitation contract. |
| [docs/validation-matrix.md](validation-matrix.md) | Operational appendix | Ownership map for CI, integration tests, generated-output validation, nightly diagnostics, stress, and profiling. |

## Generated Evidence Boundary

Generated evidence must be refreshed through the owning exporter rather than
edited directly. Current generated surfaces are:

| Generated surface | Owning path |
| --- | --- |
| `docs/calibration-register.md` | `sbt "calibrationRegister --out docs/calibration-register.md"` |
| `docs/sfc-matrix-artifacts/*` | `sbt "sfcMatrices --seed 1 --months 12 --out docs/sfc-matrix-artifacts --format md --commit committed-snapshot"` |
| `docs/empirical-validation/*.csv` | `sbt "empiricalValidation ... --out docs/empirical-validation"` |

The existing `scripts/check-generated-outputs.sh` guard covers
`docs/calibration-register.md` and `docs/sfc-matrix-artifacts/`. Empirical
validation snapshots are intentionally separate because they are refreshed only
when the validation snapshot is intentionally updated.

Hand-maintained companion documents must say that they are not generated and
must identify the generated artifacts they track:

| Companion surface | Generated artifacts it tracks |
| --- | --- |
| `docs/sfc-matrix-evidence.md` | `docs/sfc-matrix-artifacts/*` |
| `docs/model-equations-to-sfc-map.md` | `docs/sfc-matrix-artifacts/*` |
| `docs/empirical-validation-report.md` | `docs/empirical-validation/*.csv` |
| `docs/empirical-validation/README.md` | `docs/empirical-validation/*.csv` |

## Deprecated And Merge Candidates

No documentation artifact is deprecated as of this inventory. The following are
merge or consolidation candidates for later cleanup tickets:

| Candidate group | Current files | Proposed direction |
| --- | --- | --- |
| Operational validation appendix | `operations.md`, `validation-matrix.md`, `nightly-diagnostics.md`, `nightly-baseline-comparison.md`, `performance-regression-budgets.md` | Use `operations.md#operational-appendix-index` as the operational entry point and keep detailed policy pages cross-linked from it. |
| Diagnostic-method appendix | `bank-balance-sheet-benchmark.md`, `bank-failure-ablations.md`, `hh-bank-lead-lag-diagnostics.md`, `loan-origination-quality-diagnostics.md`, `hot-path-profiling.md` | Route diagnostic/profiling discovery through `operations.md#operational-appendix-index` so README and model spec do not enumerate each diagnostic document. |
| Calibration evidence notes | `household-credit-stress-calibration.md`, `external-sector-baseline-calibration.md`, `private-credit-renewal-calibration.md` | Keep as evidence notes, but route them through calibration governance and data-bridge indexes. |
| Publication QA checklist | `model-spec-completeness-checklist.md` | Keep as QA appendix unless #739 proves it duplicates the documentation architecture inventory. |

## Review Rules

When adding or changing documentation:

1. Assign exactly one owner class in this inventory.
2. Decide whether the file belongs on the canonical first-pass reviewer path in
   [model-specification.md](model-specification.md) or an appendix path.
3. If the file is generated, document the owning generator and keep it under
   generated-output checks where practical.
4. If the file is hand-maintained but coupled to generated evidence, name the
   generated artifacts it must track.
5. Do not delete or merge documentation without listing the replacement path and
   preserving generated evidence.
6. Run `nix develop --command python3 scripts/check-docs.py` before review when
   changing local links, headings, or committed `docs/` artifacts.
