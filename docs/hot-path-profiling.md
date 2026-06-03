# Hot-Path Profiling

This document defines the manual and weekly profiling workflow for Amor Fati's
main engine paths. It is an observability workflow, not a replacement for CI,
integration tests, nightly diagnostics, or nightly health summaries.

## Purpose

Correctness checks answer whether the engine still respects its contracts.
Profiling answers where the engine spends time, allocates memory, blocks,
compiles hot methods, or triggers GC while running representative workloads.

The workflow exists to produce reproducible artifacts after large refactors and
before introducing performance budgets. Initial results are report-only. Hard
performance gates belong to a later baseline and regression-budget workflow.

## Workflow

GitHub Actions workflow:

```text
.github/workflows/hot-path-profiling.yml
```

Triggers:

- `workflow_dispatch`: maintainers can profile `smoke`, `nightly`, or
  `extended`.
- `schedule`: runs weekly against `HEAD` of `main`.

The workflow is intentionally not attached to `pull_request`. It should not
slow down PR feedback and should not duplicate correctness checks already owned
by CI or nightly diagnostics.

## Workload

The workflow builds the assembled jar under Nix:

```bash
nix develop --command sbt assembly
```

It then profiles the selected diagnostics workload from that jar:

```bash
nix develop --command java \
  -XX:StartFlightRecording=filename=<run-root>/profiling/amor-fati-<run-id>.jfr,settings=profile,dumponexit=true,disk=true \
  -cp target/scala-3.8.2/amor-fati.jar \
  com.boombustgroup.amorfati.diagnostics.NightlyDiagnosticsProfileRunner \
  --profile <profile> \
  --out target/hot-path-profiling \
  --run-id <run-id> \
  --jar-path target/scala-3.8.2/amor-fati.jar \
  --require-main
```

Using `NightlyDiagnosticsProfileRunner` as the workload is deliberate: the
profile steps exercise representative long-running paths rather than unit-test
micro cases, including:

- `FlowSimulation` and month-calculus execution
- runtime ledger execution
- SFC semantic projection
- banking economics
- firm economics
- household economics
- diagnostics CSV export paths

The workflow's purpose is profiling those paths. The health summary produced by
the runner remains useful context, but the profiling workflow is not the owner
of nightly normal-path verdicts.

## Artifacts

Outputs are written under:

```text
target/hot-path-profiling/<profile>/<run-id>/
```

The GitHub artifact is named:

```text
amor-fati-hot-path-profiling-<run-id>
```

The uploaded artifact contains the profile run directory, including:

- `profiling/amor-fati-<run-id>.jfr`: raw Java Flight Recorder recording
- `profiling/jfr-summary.txt`: compact JFR event summary
- `profiling/jfr-hot-methods.txt`: sampled hot methods
- `profiling/jfr-allocation-by-class.txt`: allocation pressure by class
- `profiling/jfr-allocation-by-site.txt`: allocation pressure by stack/site
- `profiling/jfr-gc.txt` and `profiling/jfr-gc-pauses.txt`: GC observations
- `profiling/jfr-jvm-flags.txt`: JVM flag evidence
- `profiling/jfr-thread-cpu-load.txt`: thread CPU observations
- `profiling/jfr-file-reads-by-path.txt` and
  `profiling/jfr-file-writes-by-path.txt`: filesystem activity
- `profiling/build.log`: assembled-jar build log
- `profiling/profiling.log`: profiled workload stdout/stderr
- `profiling/profiling-metadata.json`: commit, command, profile, runtime, and
  artifact metadata
- `run-manifest.json`, `health-summary.json`, and `health-summary.md` when the
  diagnostics runner reaches the point where it can write them

Failed runs still upload partial artifacts whenever possible. The final
workflow failure step runs after artifact upload, so build logs, JFR files, and
partial manifests remain available for review.

## Retention

Artifact retention is profile-specific:

| Profile | Retention |
| --- | --- |
| `smoke` | 14 days |
| `nightly` | 30 days |
| `extended` | 60 days |

## Interpretation

Profiling evidence is initially report-only:

- A red workflow means the profiling workload, build, or JFR capture failed.
- A green workflow means artifacts were produced for the selected workload.
- It does not mean performance is good.
- It does not mean the economic path is calibrated.

Performance budgets should be introduced only after several comparable runs
exist for the same profile, commit metadata, JVM, and machine class.

## Relationship To Other Validation Layers

- PR CI owns fast correctness feedback.
- Integration tests own short end-to-end regression checks.
- Nightly diagnostics own long validation artifacts and health summaries.
- Nightly per-step telemetry owns lightweight step-level runtime visibility.
- Hot-path profiling owns low-frequency JFR evidence for runtime and allocation
  investigation.
