# Empirical Validation Report

This hand-maintained report defines how to read Amor Fati's empirical
validation artifacts. It is not a calibration-governance document, not a
runbook, and not a backlog.

## Purpose

Empirical validation asks whether simulated paths reproduce selected Polish,
EU, macro, meso, financial, and micro stylized facts. It is evidence for model
calibration and interpretation. It does not define model mechanics and it does
not relax accounting constraints.

## Validation Boundary

Accounting validation and empirical validation answer different questions:

| Surface | Question | Artifact |
| --- | --- | --- |
| Runtime ledger checks | Did emitted monetary flows conserve value through the ledger? | Engine runtime checks and SFC identity validation |
| Paper-facing accounting structure | Are BSM, TFM, and stock-flow reconciliation rows traceable to runtime mechanisms? | [sfc-matrix-evidence.md](sfc-matrix-evidence.md) |
| Empirical validation | Do simulated paths match selected empirical comparators or stylized facts? | This report and the [empirical-validation snapshot bundle](#snapshot-bundle) |

A model run can match an empirical target and still be invalid if ledger or SFC
checks fail. Conversely, an empirically weak row is calibration evidence, not
an accounting failure.

## Status Taxonomy

The source manifest uses readiness statuses:

| Status | Meaning |
| --- | --- |
| `READY` | Output columns exist and comparator metadata is sufficient for the current validation criterion. |
| `PARTIAL` | Output exists, but normalization, aggregation, source extraction, or bridge detail remains incomplete. |
| `MISSING_DATA_BRIDGE` | Model output exists, but external source extraction or transformation is not documented yet. |
| `MISSING_OUTPUT` | The target is known, but the Monte Carlo output schema does not expose enough model state. |
| `MISSING_SOURCE_DETAIL` | The source family is identified, but concrete table, vintage, or access path is missing. |
| `BRIDGE_ASSUMPTION` | The row depends on an explicit empirical-to-model bridge assumption. |

The generated baseline snapshot uses run-result statuses:

| Status | Meaning |
| --- | --- |
| `PASS_BASELINE` | Model value is within the stated criterion for the committed baseline run. |
| `FAIL_BASELINE` | Model value is outside the stated criterion for the committed baseline run. |
| `NOT_RUN` | The row exists but no generated baseline result is available. |

Manifest blockers can coexist with run-result statuses. For example, a row can
carry a model value while still needing a source bridge before it becomes a
publication-grade empirical claim.

`FAIL_BASELINE` marks calibration, comparator, or interpretation work; it is
not an accounting defect. `PARTIAL` and `MISSING_DATA_BRIDGE` rows remain visible
until source vintage, transformation, model aggregation, empirical value, and
criterion are recorded.

## Snapshot Bundle

The committed empirical-validation bundle is:

| Artifact | Path | Role |
| --- | --- | --- |
| Curated source manifest | [empirical-validation-source-manifest.tsv](empirical-validation-source-manifest.tsv) | Editable source, mapping, comparator, tolerance, and readiness metadata. |
| Baseline snapshot | [empirical-validation/baseline-validation-snapshot.tsv](empirical-validation/baseline-validation-snapshot.tsv) | Generated per-row model values and baseline pass/fail statuses. |
| Model run manifest | [empirical-validation/model-run-manifest.tsv](empirical-validation/model-run-manifest.tsv) | Generated run id, seed count, horizon, commit, branch, and output metadata. |
| Source manifest snapshot | [empirical-validation/source-manifest.tsv](empirical-validation/source-manifest.tsv) | Generated copy of the source manifest captured with the baseline snapshot. |

Do not edit generated TSV files under [empirical-validation/](empirical-validation/)
directly. Edit
[empirical-validation-source-manifest.tsv](empirical-validation-source-manifest.tsv),
regenerate the snapshot with
[operations.md](operations.md#empirical-validation-snapshot), and commit the
generated bundle intentionally.

Paper-facing claims must cite
[empirical-validation/model-run-manifest.tsv](empirical-validation/model-run-manifest.tsv):
commit, branch, seed count, horizon, run id, and statistic.

## Source Manifest Contract

The source manifest is metadata-first. It records source provider, URL,
dataset or release code, vintage, access date, reuse note, frequency, unit,
transformation, model target, empirical value, tolerance or criterion, status,
and notes.

Tolerances require a concrete source, transformation, and rationale; do not
infer them from seed count alone. Time-series macro PLN aggregates emitted by
Monte Carlo outputs are already in Poland scale, so validation TSV values should
not be divided by `gdpRatio`.

Survey or proxy rows become empirical claims only after the source manifest
records source vintage, transformation, model aggregation, empirical value, and
criterion. Directional survey evidence needs an explicit bridge assumption
before it can be used as a direct model-level comparator.

Raw external data should not be vendored into the repository unless there is a
small, clearly licensed fixture with an explicit reason. Source selection,
vintage rules, units, sector and instrument crosswalks, and prioritized source
gaps belong in [data-bridge-national-financial-accounts.md](data-bridge-national-financial-accounts.md).

The authoritative per-row model mapping is the `model_target` column in the
source manifest. Family-level schema detail is owned by
[montecarlo/README.md](../src/main/scala/com/boombustgroup/amorfati/montecarlo/README.md)
and the schema definitions it indexes, especially
[McTimeseriesSchema.scala](../src/main/scala/com/boombustgroup/amorfati/montecarlo/McTimeseriesSchema.scala).
Diagnostics routing starts from the [operations appendix index](operations.md#operational-appendix-index).
