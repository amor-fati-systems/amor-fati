# Nightly Baseline Comparison

This document defines how nightly diagnostic outputs should be compared against
baseline evidence. It is the comparison contract for the nightly validation
milestone. It intentionally comes before the jar runner and scheduled workflow
so automation does not encode unclear regression semantics.

Operational appendix entry point:
[operations.md#operational-appendix-index](operations.md#operational-appendix-index).
Use this page for nightly comparison policy after starting from the operations
index.

The comparison layer has two jobs:

- fail fast on accounting and output invariants that must never drift;
- make research drift visible without pretending that every economic movement
  is already a calibrated failure.

## Inputs

The comparison layer should consume these inputs when available:

| Input | Producer | Role |
| --- | --- | --- |
| Run manifest | Future diagnostics profile runner | Identifies profile, commit, seed policy, horizon, toolchain, and artifact paths |
| Profile definition | [nightly-diagnostics.md](nightly-diagnostics.md) | Defines `smoke`, `nightly`, and `extended` horizons and diagnostic steps |
| Characterization evidence | #623 | Initial deterministic baseline evidence for behavior-preserving refactors |
| Empirical validation snapshot | `EmpiricalValidationExport` | Compares model outputs with external empirical targets |
| Diagnostic target bands | Bank/household diagnostic exports | Existing hard, soft, and exploratory guardrails |
| Scenario and robustness summaries | `ScenarioRunExport`, `SensitivityRobustnessExport` | Within-run scenario deltas versus baseline |
| Timeseries and terminal summaries | `Main`/`McRunner` | Core macro, credit, bank, household, and firm metrics |

Baseline inputs must be tied to an explicit commit, profile, seed policy, month
horizon, and config assumption. A baseline without those fields is not
actionable evidence.

## Status Model

Use one shared status vocabulary in generated comparison reports:

| Status | CI effect | Meaning |
| --- | --- | --- |
| `PASS` | success | Check is inside its accepted invariant or threshold |
| `INFO` | success | Metric is reported for visibility, with no accepted threshold |
| `WARN` | success at first | Metric crossed a soft band or changed materially but is not a hard invariant |
| `FAIL` | failure | Hard invariant failed or a threshold has been explicitly promoted to fail |
| `MISSING` | failure for required artifacts, warning for optional artifacts | Required input or metric was not produced |

The first implementation should exit non-zero only when at least one `FAIL`
exists, or when a required artifact is `MISSING`. `WARN` rows should be visible
in reports but should not fail CI until a later issue intentionally promotes a
specific threshold.

## Existing Guardrails

Do not introduce a second interpretation for existing diagnostic guardrails.
Map them directly:

| Existing class/status | Comparison status |
| --- | --- |
| `HARD_INVARIANT` with `FAIL` | `FAIL` |
| `HARD_INVARIANT` with `PASS` | `PASS` |
| `SOFT_CALIBRATION_WARNING` with `WARN` | `WARN` |
| `SOFT_CALIBRATION_WARNING` with `PASS` | `PASS` |
| `EXPLORATORY_DIAGNOSTIC` with `INFO` | `INFO` |
| `EXPLORATORY_DIAGNOSTIC` with `WARN` | `WARN` |
| `PASS_BASELINE` from empirical validation | `PASS` |
| `FAIL_BASELINE` from empirical validation | `WARN` by default |
| `PARTIAL` or bridge statuses from empirical validation | `INFO` |
| `MISSING_OUTPUT` for required profile artifacts | `MISSING` |

`FAIL_BASELINE` in empirical validation is calibration evidence, not an
accounting failure, unless a metric is later promoted to a hard validation
threshold with a written rationale.

## Hard Invariants

These checks should fail in every profile:

| Check | Failure condition |
| --- | --- |
| SFC exactness | Any month returns an SFC validation error |
| Ledger conservation | Any ledger conservation or survivability check fails |
| Month completeness | A seed emits fewer monthly rows than the profile horizon |
| TSV shape | Required TSV is missing, empty, malformed, or lacks required columns |
| Numeric validity | Required numeric field is non-finite or unparsable |
| Impossible accounting state | Negative stock or ratio appears where the model contract says it is impossible |
| Active bank validity | Invalid active-bank invariant is raised |
| Diagnostic hard target | Existing `HARD_INVARIANT` target emits `FAIL` |

Missing optional artifacts should be `WARN` only when the profile explicitly
marks them optional, for example terminal micro snapshots in `extended`.

## Research Metrics

The first comparison version should report these as `INFO` or `WARN`, not
`FAIL`, unless a separate issue promotes an explicit threshold:

- GDP and GDP growth proxies
- inflation and unemployment
- total credit to GDP
- debt to GDP
- current account to GDP
- bank failures and failure reasons
- realized credit losses and ECL provision changes
- household bankruptcy and liquidity shortfall rates
- consumer-credit approval and rejection rates
- firm-credit approval and rejection rates
- loan-origination bad-outcome cohorts
- scenario deltas versus baseline

For these metrics, the comparison report should show observed value, baseline
value when available, absolute delta, relative delta when meaningful, and the
status chosen by the rule.

## Profile Policy

### `smoke`

`smoke` is a fast sanity profile. It should:

- fail on hard invariants;
- fail on malformed or missing required outputs;
- report research metrics as `INFO`;
- avoid soft economic thresholds unless they are cheap and already produced by
  a diagnostic target band.

### `nightly`

`nightly` is the daily validation profile. It should:

- fail on hard invariants;
- warn on existing soft calibration guardrails;
- warn on material drift in selected research metrics once a baseline exists;
- include empirical validation status counts;
- include scenario and robustness deltas versus baseline scenario runs.

### `extended`

`extended` runs the same currently interpretable 60-month horizon with a wider
seed, scenario, and diagnostic surface. It should:

- use the same hard-fail policy as `nightly`;
- report wider seed-envelope statistics, such as min, max, mean, and selected
  quantiles when available;
- avoid long-horizon cycle or regime interpretation;
- keep 120m+ metrics out of this milestone.

## Threshold Definition Contract

Later implementation should keep threshold definitions in a versioned,
reviewable artifact. A TSV or typed Scala registry is acceptable, but it must
expose the same logical fields:

| Field | Meaning |
| --- | --- |
| `Profile` | `smoke`, `nightly`, `extended`, or `all` |
| `MetricId` | Stable metric id used in reports |
| `SourceArtifact` | Artifact family, for example `timeseries`, `bank-benchmark`, `hh-stress`, `empirical-validation` |
| `Column` | Source column or diagnostic metric id |
| `Statistic` | `terminal`, `mean`, `min`, `max`, `sum`, `status`, or profile-specific statistic |
| `Severity` | `hard`, `soft`, or `info` |
| `BaselineSource` | `characterization`, `empirical`, `within-run-baseline`, `target-band`, or `none` |
| `Lower` / `Upper` | Optional absolute accepted band |
| `WarnAbsDelta` / `FailAbsDelta` | Optional absolute drift thresholds |
| `WarnRelDelta` / `FailRelDelta` | Optional relative drift thresholds |
| `Rationale` | Human-readable reason and source for the threshold |

Any threshold that can fail CI must include `Rationale`. Threshold changes
should be reviewed like calibration changes, not treated as formatting churn.

## Report Contract

Each comparison run should emit:

```text
comparison-summary.md
comparison-summary.tsv
```

The TSV should contain at least:

| Column | Meaning |
| --- | --- |
| `Profile` | Profile id |
| `RunId` | Run id from the manifest |
| `Commit` | Compared commit |
| `MetricId` | Stable metric id |
| `SourceArtifact` | Artifact family |
| `Observed` | Observed value or status |
| `Baseline` | Baseline value or blank |
| `AbsDelta` | Absolute delta when numeric |
| `RelDelta` | Relative delta when numeric and meaningful |
| `Status` | `PASS`, `INFO`, `WARN`, `FAIL`, or `MISSING` |
| `Severity` | `hard`, `soft`, or `info` |
| `Rationale` | Reason for the status |

The Markdown summary should lead with counts by status, then list all `FAIL`
rows, then `WARN` rows, then a compact research-metric section.

## Relationship To #623

#623 provides deterministic characterization evidence before architecture
refactors. This comparison contract defines how that evidence can be consumed:

- as initial baseline values for selected metrics;
- as invariant checks that must remain true;
- as a seed and horizon convention for behavior-preserving refactors.

Characterization evidence should not silently become a hard economic threshold.
Promotion from characterization baseline to failing threshold requires a
separate rationale.

## Update Policy

Changing a hard invariant requires a code or model-contract explanation.

Changing a soft research threshold requires:

- the old threshold;
- the proposed threshold;
- the observed drift that motivated the change;
- the economic or empirical rationale;
- the profile affected.

Adding a new `INFO` metric is low risk, but it should still use a stable
`MetricId` so historical reports remain comparable.
