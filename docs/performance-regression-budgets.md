# Performance Regression Budgets

This document defines the first performance-baseline layer for Amor Fati's
engine diagnostics and hot-path profiling workflows.

The layer is intentionally soft. It produces reviewer-facing regression reports
from existing run manifests; it does not launch extra simulations and it does
not fail workflows on performance drift.

## Source Artifacts

Performance comparisons reuse `run-manifest.json` files already produced by
`NightlyDiagnosticsProfileRunner`.

The comparison scripts run inside:

```text
.github/workflows/diagnostics-reusable.yml
.github/workflows/hot-path-profiling-reusable.yml
```

Each workflow downloads the newest non-expired artifact for the same profile on
`main`, extracts the baseline `run-manifest.json`, and compares it to the
current manifest. Missing permissions, missing artifacts, expired artifacts, or
missing manifests produce a `NO_BASELINE` report rather than a failed job.

No noisy baseline files are committed to the repository.

## Output Artifacts

Each diagnostics or profiling run writes:

```text
<run-root>/performance/performance-regression-report.json
<run-root>/performance/performance-regression-report.md
```

The Markdown report is also appended to the GitHub Actions step summary. The
JSON report contains the current run id, baseline run id, commit metadata,
budget policy, warning count, and per-step metric comparisons.

## Compared Metrics

The initial metrics are deliberately coarse and manifest-derived:

| Metric | Direction | Soft warning threshold |
| --- | --- | --- |
| Step duration (`duration_ms`) | lower is better | current > 1.25 x baseline and +30s |
| Seed-month throughput (`seed_months_per_second`) | higher is better | current < 0.75 x baseline |
| Post-step heap used (`heap_used_bytes_after`) | lower is better | current > 1.40 x baseline and +64 MiB |
| GC time (`gc_time_millis_delta`) | lower is better | current > 1.50 x baseline and +10s |

These thresholds are soft warnings. They are meant to make regressions visible,
not to encode a claim that the engine is too slow.

## Profile Eligibility

`smoke` and `nightly` are eligible for soft budget reports because they are the
most comparable normal-validation profiles.

`extended` remains report-only for performance budgets until the all-failed
bank stress semantics in #718 are resolved. The extended profile currently
includes stress paths that can terminate before later steps run, which makes
step-to-step performance comparisons misleading.

## Promotion Policy

A metric can become a hard gate only after:

- the profile has several comparable successful `main` runs;
- the metric is low-noise on GitHub-hosted runners;
- the failure message explains the affected step and metric;
- stress-profile failures cannot be mistaken for normal-path performance
  regressions;
- the threshold rationale is documented here.

Until then, performance reports are observability evidence. They should guide
review and refactoring, not block correctness work.
