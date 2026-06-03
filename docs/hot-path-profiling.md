# Hot-Path Profiling

This document defines the manual and weekly profiling workflow for Amor Fati's
main engine paths. It is an observability workflow, not a replacement for CI,
integration tests, nightly diagnostics, or nightly health summaries.

## Purpose

Correctness checks answer whether the engine still respects its contracts.
Profiling answers where the engine spends time, allocates memory, blocks,
compiles hot methods, or triggers GC while running representative workloads.

The workflow exists to produce reproducible artifacts after large refactors and
to feed the soft [performance regression budget](performance-regression-budgets.md)
reports. Initial results remain report-only. Hard performance gates require a
documented promotion step.

## Workflow

GitHub Actions workflows:

```text
.github/workflows/hot-path-profiling-smoke.yml
.github/workflows/hot-path-profiling-nightly.yml
.github/workflows/hot-path-profiling-extended.yml
```

Triggers:

- `workflow_dispatch`: maintainers can profile the selected workflow's fixed
  profile with `profile` or `default` JFR settings. Dispatching with `--ref`
  checks out that branch so profiling workflow changes can be validated before
  merge.
- `schedule`: each profile runs weekly against `HEAD` of `main`, staggered by
  profile.

The visible workflows delegate to:

```text
.github/workflows/hot-path-profiling-reusable.yml
```

This keeps the Actions UI and README badges profile-specific without copying
the JFR orchestration logic.

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
- `performance/performance-regression-report.json` and
  `performance/performance-regression-report.md`: soft comparison against the
  newest same-profile baseline artifact on `main`, when available
- `run-manifest.json`, `health-summary.json`, and `health-summary.md` when the
  diagnostics runner reaches the point where it can write them

Failed runs still upload partial artifacts whenever possible. The final
workflow failure step runs after artifact upload, so build logs, JFR files, and
partial manifests remain available for review.

## Retention

Artifact retention is profile-specific:

| Profile | Workflow | Schedule | Retention |
| --- | --- | --- | --- |
| `smoke` | `hot-path-profiling-smoke.yml` | weekly, Sunday `00:00` UTC | 14 days |
| `nightly` | `hot-path-profiling-nightly.yml` | weekly, Sunday `02:00` UTC | 30 days |
| `extended` | `hot-path-profiling-extended.yml` | weekly, Sunday `04:00` UTC | 60 days |

GitHub cron is UTC. During CEST, these correspond to 02:00, 04:00, and
06:00 Europe/Warsaw respectively. GitHub cron does not track local daylight
saving time.

## Interpretation

Profiling evidence is initially report-only:

- A red workflow means the profiling workload, build, or JFR capture failed.
- A green workflow means artifacts were produced for the selected workload.
- It does not mean performance is good.
- It does not mean the economic path is calibrated.

Soft performance-budget reports compare the current manifest to the newest
same-profile baseline artifact on `main`. They warn about large step-level
changes in duration, seed-month throughput, post-step heap use, and GC time.
They do not fail the workflow. Hard gates can be introduced only after several
comparable runs exist for the same profile, commit metadata, JVM, and machine
class.

## Relationship To Other Validation Layers

- PR CI owns fast correctness feedback.
- Integration tests own short end-to-end regression checks.
- Nightly diagnostics own long validation artifacts and health summaries.
- Nightly per-step telemetry owns lightweight step-level runtime visibility.
- Hot-path profiling owns low-frequency JFR evidence for runtime and allocation
  investigation.
- [Performance regression budgets](performance-regression-budgets.md) own the
  soft manifest-to-manifest comparison policy and the criteria for promoting a
  metric to a hard gate.
