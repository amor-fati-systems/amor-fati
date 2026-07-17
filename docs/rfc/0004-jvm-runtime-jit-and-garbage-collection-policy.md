# RFC-0004: JVM Runtime, JIT, and Garbage Collection Policy

Status: Draft for decision

Scope: Supported JDK and distribution, process topology, JIT compiler, garbage
collector profiles, heap policy, runtime provenance, and qualification evidence

Performance: Selection methodology is in scope; no candidate is selected by
uncontrolled timing evidence

## Purpose

Amor Fati is a JVM system, but its current runtime policy is implicit. The Nix
development shell and CI use JDK 21 and configure G1 with an 8 GiB maximum heap.
The repository-level `.jvmopts` also selects G1 but uses a 4 GiB maximum heap.
Runs outside the managed shell can use whichever `java` executable happens to
be installed. Build, test, assembled-CLI, diagnostics, and future notebook
processes therefore do not yet share one explicit runtime contract.

The target population and data-oriented core will materially change the heap:
many immutable object rows and copied vectors will become fewer long-lived
primitive columns, current/next buffers, indexed relationships, and reusable
workspaces. The managed Almond environment will also introduce a long-lived
compiler kernel with different allocation and responsiveness requirements from
a batch simulation. A garbage collector selected from current-engine timing or
from generic reputation would not be a defensible system decision.

This RFC defines how Amor Fati selects, launches, observes, qualifies, and
records its JVM runtime. It separates the JDK, VM, JIT compiler, garbage
collector, process topology, and heap policy. It names candidates and a
provisional control configuration, but it does not accept a final JDK, JIT, or
collector before compatibility and target-core evidence exist.

## Relationship to Other Decisions

This RFC is deliberately opened before the target state layout is implemented
and closed after a representative target-core slice exists:

1. [RFC-0001](0001-population-and-representation.md) defines supported
   representation scales and the population workloads that runtime profiles
   must execute.
2. [RFC-0002](0002-research-api-and-notebook-runtime.md) defines the managed
   notebook environment, experiment lifecycle, cancellation, and result
   contract. This RFC owns the JVM process and runtime policy beneath it.
3. This RFC provisionally qualifies a JDK, control collector, and process
   topology for the notebook pilot.
4. [RFC-0003](0003-model-ontology-and-state-architecture.md) implements the thin
   target-core month and exposes the representative DOD allocation and live-set
   profile.
5. This RFC then compares collectors and JITs on that target workload before
   proposing any runtime ADR.

[ADR-0006](../adr/0006-data-oriented-high-cardinality-state.md) decides the
encapsulation and storage principle, not the garbage collector. Collector
results may select an encoding within the ADR boundary but cannot redefine
economic ontology, expose raw columns, or restore object-row state merely to
improve a benchmark.

[ADR-0007](../adr/0007-controlled-model-core-replacement.md) permits a current
engine oracle and a target core during development. Runtime comparison may
execute both, but the old engine is not the workload on which the final
collector is selected.

## Terminology

| Term | Meaning in this RFC |
| --- | --- |
| JDK | Java development and runtime distribution, including version, vendor build, VM, tools, and security-update line. |
| JVM or VM | Managed runtime executing bytecode. The primary candidate family is OpenJDK HotSpot. |
| JIT | Runtime compiler translating hot bytecode to machine code, such as HotSpot C2 or Graal JIT. |
| GC | Garbage collector and its memory-management barriers, threads, pause behavior, and heap ergonomics. |
| Native Image | GraalVM ahead-of-time native executable and runtime. It is not interchangeable with Graal JIT on HotSpot. |
| Kernel JVM | Long-lived process hosting Almond, Scala compilation, notebook state, and notebook adapter code. |
| Worker JVM | Process owning one simulation execution context, heap, core state, and result publication lifecycle. |
| Runtime profile | Amor Fati-owned, versioned selection of JDK, VM, JIT, GC, heap, and operational flags for a declared workload. |
| Control configuration | Stable comparison runtime used to determine whether another candidate improves an explicit objective. |

## Goals

1. Make every supported run use an identifiable and reproducible JVM runtime.
2. Separate notebook-kernel requirements from simulation-worker requirements.
3. Select runtime defaults from Amor Fati workloads rather than generic JVM
   recommendations.
4. Optimize the primary research objective, time to a valid result, while
   retaining explicit memory, pause, startup, and interactive-responsiveness
   constraints.
5. Keep scientific results invariant across qualified runtime profiles.
6. Preserve JFR, GC logging, crash diagnostics, and operational observability.
7. Support the repository's declared operating systems and architectures
   without pretending that every experimental collector is portable.
8. Permit later enterprise runtime profiles without making current research
   users configure raw JVM flags.

## Non-Goals

This RFC does not:

- tune individual economic kernels or replace the DOD design;
- promise that one GC is best for batch, notebook, and future service workloads;
- make low pause time the primary scientific objective by default;
- expose JVM flags as part of the economic experiment specification;
- use GitHub-hosted runner timings as decisive cross-collector evidence;
- select GraalVM Native Image as the primary notebook or simulation runtime;
- define remote worker scheduling, multi-tenancy, or distributed Monte Carlo;
- require every evaluated JIT and collector to become a supported profile; or
- create an ADR before the qualification gates in this RFC pass.

## Current Runtime Contract

The implemented runtime has the following pieces:

| Surface | Current behavior | Gap |
| --- | --- | --- |
| Nix development shell | `pkgs.jdk21`, `JAVA_HOME`, `SBT_OPTS=-Xmx8G -XX:+UseG1GC`, and an `amor-fati-java` wrapper with the same application options. | JDK major and GC are pinned, but vendor identity and rationale are not a run-level contract. |
| GitHub Actions | Enters the Nix shell and checks that `java -version` reports major version 21. | It validates the major version, not the complete runtime profile. |
| Repository `.jvmopts` | `-Xmx4G -XX:+UseG1GC`. | Heap differs from the Nix/CI profile, and the file configures sbt rather than a separately owned worker. |
| Assembled diagnostics | Can use `amor-fati-java` and `AMOR_FATI_JAVA_OPTS`. | Ordinary commands do not yet have one mandatory system launcher. |
| Profiling | JFR, unified GC logs, allocation views, GC pause views, runtime metadata, and coarse MXBean GC counters exist. | The comparison policy lacks collector, JIT, allocation-rate, RSS, pause-distribution, and canonical-hardware qualification. |
| Run evidence | Diagnostics report JVM metadata and performance telemetry. | Every scientific result bundle does not yet record the complete runtime and effective flags. |
| Notebook runtime | Not implemented. | Kernel and simulation memory ownership have not been separated. |

The operations documentation currently describes the CI baseline as Temurin,
while CI actually consumes the JDK supplied by the pinned Nix package set. The
qualification harness must record the resolved vendor, build, VM, and flags
rather than infer them from a documentation label.

## Candidate Runtime Families

### OpenJDK 21 control

JDK 21 with HotSpot C2 and G1 is the implemented control. It remains the
compatibility reference until another JDK passes the full build, test, ledger,
profiling, CLI, and notebook gates. Existing evidence is not a final endorsement
because it was not produced as a controlled runtime comparison.

### OpenJDK 25 candidate baseline

JDK 25 is the candidate supported baseline for the target research platform:

- it is the current post-JDK-21 LTS line from most vendors;
- Scala 3.8 supports compilation and execution on JDK 25;
- non-generational ZGC has been removed, leaving generational ZGC;
- Generational Shenandoah is a product feature; and
- its JFR and runtime capabilities are appropriate for a new long-lived
  platform baseline.

Candidate status is not acceptance. The exact vendor distribution, Nix package,
security-update policy, supported operating systems, Almond compatibility,
Stainless workflow, and ledger verification must be tested. A JDK 25 build that
improves execution but breaks the reproducible development or verification
toolchain is not qualified.

### GraalVM on HotSpot

GraalVM running JVM bytecode with Graal JIT is a JIT candidate, not a synonym
for Native Image and not automatically a different GC decision. It may improve
hot Scala code through inlining, escape analysis, and allocation elimination,
but it also changes compilation warmup, CPU consumption, code cache, failure
diagnostics, distribution, and support requirements.

Graal JIT is evaluated only after a stable HotSpot C2 control exists. The first
comparison uses the same collector and heap policy where the distributions
support that comparison. The RFC does not require a full Cartesian product of
every JIT and collector.

### GraalVM Native Image

Native Image uses a different ahead-of-time build and runtime model. It is
outside the initial supported notebook and simulation-worker candidates because:

- Almond and interactive Scala compilation require a normal JVM process;
- Scala and dependencies can require closed-world reflection and resource
  configuration;
- Native Image exposes a different collector set and memory behavior;
- it weakens direct comparability with existing JFR and HotSpot diagnostics; and
- fast startup is not by itself the primary objective of a multi-minute or
  long-horizon scientific run.

A future small CLI launcher or isolated deployment worker may justify a separate
Native Image RFC. It is not selected indirectly by choosing to benchmark Graal
JIT.

## Garbage Collector Candidates

### G1

G1 is the control collector. It targets a balance of throughput, bounded pause
goals, and large-heap operation and is HotSpot's default. Amor Fati will first
run G1 with minimal tuning: an explicit maximum heap and diagnostic flags, not
a collection of inherited folklore flags. Further tuning requires a traceable
observation such as evacuation failure, humongous-object pressure, or a missed
pause objective.

The target DOD design creates large primitive arrays. G1 classifies sufficiently
large allocations as humongous objects relative to its region size, so repeated
buffer allocation, resizing, and retention must be visible in the evidence.
This is not a reason to avoid DOD or G1; it is a reason to benchmark the actual
table lifecycle.

### Parallel GC

Parallel GC is a first-class candidate for unattended research batch runs. Its
stop-the-world collections can produce long pauses, but total time to a valid
result may be better when latency is irrelevant and sufficient heap headroom is
available. Excluding it would silently optimize a batch simulator for a service
latency objective it does not yet have.

Parallel GC cannot become the notebook default solely by winning throughput. It
must also satisfy kernel or worker isolation, cancellation, memory, and maximum
pause constraints for the profile in which it is used.

### Generational ZGC

ZGC is a candidate for large heaps, strict pause constraints, and any in-process
interactive execution that cannot isolate simulation pauses from the kernel.
Its concurrent work can trade throughput and CPU for very small pauses. A ZGC
profile must therefore improve a declared pause or responsiveness objective
without unacceptable time-to-result, RSS, or CPU cost.

On the JDK 25 candidate line, `-XX:+UseZGC` means generational ZGC; the removed
non-generational mode is not a separate candidate.

### Generational Shenandoah

Generational Shenandoah is an evaluation candidate on JDK 25. The generational
mode is a product feature but is not the default Shenandoah mode in JDK 25, so
the profile must request and record it explicitly. Availability and behavior
must be verified on each supported vendor, operating system, and architecture.

Shenandoah is promoted only if it offers a material advantage over G1 or ZGC
for an Amor Fati profile. Collector diversity is not itself a product feature.

## Proposed Runtime Architecture

The target system separates control-plane processes from the memory-intensive
simulation worker:

```text
                           +--------------------+
                           | CLI or scheduler   |
                           +---------+----------+
                                     |
+-------------------+      +---------v----------+
| Almond kernel JVM +------> Research API       |
| Scala + renderers |      | local coordinator |
+-------------------+      +---------+----------+
                                     |
                           +---------v----------+
                           | simulation worker  |
                           | JVM + target core  |
                           +---------+----------+
                                     |
                           +---------v----------+
                           | atomic result      |
                           | bundle/checkpoint  |
                           +--------------------+
```

The CLI and Almond adapter may use the same local coordinator. The simulation
worker owns its heap, runtime profile, active model state, month transition,
ledger execution, and atomic result publication. The kernel owns interactive
Scala state and bounded result views, not the complete mutable simulation heap.

This process boundary provides:

- a collector and heap policy chosen for the simulation rather than compiler
  allocations;
- hard process termination after cooperative cancellation fails;
- OOM isolation from the research notebook;
- explicit lifecycle and cleanup for repeated experiments;
- identical worker execution from CLI and notebooks; and
- a future local-to-remote boundary without changing scientific API semantics.

The notebook pilot may use an in-process backend temporarily only if the
Research API abstracts execution ownership and the pilot records the limitation.
An in-process shortcut cannot become the public result or state contract.

## Runtime Profiles

Researchers select an Amor Fati execution intent or accept a documented
default. They do not assemble JVM flags. The provisional profile vocabulary is:

| Profile | Objective | Initial candidate |
| --- | --- | --- |
| `control` | Reproducible compatibility and comparison | HotSpot C2, G1, explicit fixed heap |
| `research-batch` | Minimum time and resource cost to a valid result | Compare G1 and Parallel GC first |
| `large-population` | Complete a large live-set run with bounded pauses and sufficient headroom | Compare G1, ZGC, and Generational Shenandoah |
| `notebook-kernel` | Interactive compilation, rendering, and coordinator responsiveness | Separate non-worker profile; G1 control initially |
| `jit-evaluation` | Measure Graal JIT against C2 without conflating collector changes | Same qualified GC where supported |

Names and membership remain open until implementation. A profile is versioned
system configuration, not a behavioral model parameter. Changing a runtime
profile must not change the simulated economy or stochastic schedule.

Unsupported raw JVM overrides may remain available for developers and runtime
experiments, but they mark a result as non-qualified and record every effective
flag. A researcher should never need an override for a documented normal run.

## Heap and Resource Policy

One global `-Xmx` value is not sufficient for build, kernel, smoke, batch, and
large-population workloads. Each qualified profile declares:

- maximum and optional initial Java heap;
- expected non-heap and native-memory headroom;
- minimum host or container memory;
- CPU availability and collector-thread policy where constrained;
- maximum representation scale, seed concurrency, trace volume, and snapshot
  cadence validated for that resource envelope;
- behavior when required memory is unavailable; and
- whether multiple workers may execute concurrently.

The launcher validates obvious mismatches before population compilation. An 8
GiB heap does not imply that an 8 GiB container or host is sufficient, because
JIT code cache, thread stacks, direct buffers, memory-mapped artifacts, native
libraries, and collector metadata consume memory outside the Java heap.

Fixed heaps improve benchmark comparability. Percentage-based sizing may be
appropriate for deployment profiles, but the resolved byte sizes and container
limits must enter the manifest. The system must not silently increase scale to
fill available memory.

## Scientific Determinism

A qualified change of JDK, vendor build, JIT, collector, heap, worker count, or
operating system must preserve the scientific contract:

- identical baseline, experiment specification, seed, and requested evidence
  produce identical canonical result hashes where bitwise identity is promised;
- ledger conservation, SFC reconciliation, lifecycle invariants, and validation
  status remain identical;
- seed assignment and result ordering do not depend on thread scheduling or
  worker completion order;
- cancellation and failure never publish a successful or partially closed
  month; and
- any intentionally non-bitwise metric has a documented numerical tolerance and
  reason independent of performance.

If a runtime candidate changes results, it fails qualification until the source
is understood. A faster runtime does not justify accepting unexplained
scientific divergence.

## Runtime Manifest

Every supported result bundle records at least:

- runtime-profile ID and version;
- JDK vendor, distribution, feature version, security version, and complete
  build string;
- VM name, version, mode, and architecture;
- JIT identity and relevant compiler configuration;
- GC identity and generational mode;
- effective JVM arguments and environment-owned overrides;
- initial, maximum, and observed heap;
- host/container CPU and memory limits visible to the process;
- operating system and architecture;
- worker process count and seed-concurrency policy;
- JFR/GC-log availability and artifact hashes when captured; and
- qualified, experimental, or unsupported runtime status.

The manifest captures resolved runtime facts from the executing worker, not
only requested launcher settings.

## Qualification Workloads

Runtime candidates execute the same versioned workload bundle:

1. **Cold start:** launcher to validated no-op or prepared experiment.
2. **Notebook control:** fresh kernel startup, imports, baseline inspection, and
   one bounded scenario execution.
3. **Short simulation:** one seed over a CI-scale horizon.
4. **Canonical research run:** five seeds over 60 months.
5. **Long horizon:** enough months to expose promotion, retained state, leaks,
   checkpoint, and compaction behavior.
6. **Scale ladder:** identical economics across multiple supported
   representation scales.
7. **Evidence pressure:** controlled aggregate-only, snapshot, event, and trace
   retention profiles.
8. **Failure paths:** cancellation, worker termination, OOM boundary, invalid
   configuration, and artifact-write failure.

The current engine runs establish tooling and migration controls. The decisive
collector and JIT comparison uses the target population compiler, persistent
DOD state, representative relationships, ledger, full month closing, and result
publication. A synthetic allocation benchmark or isolated arithmetic loop is
diagnostic evidence only.

## Measurement Protocol

### Environment control

Decisive comparisons run on named, dedicated hardware with fixed CPU governor,
core allocation, memory, operating system, JDK build, workload inputs, output
filesystem, and background-load policy. GitHub-hosted runners provide
compatibility and regression signals, not final runtime selection evidence.

At least one canonical Linux deployment machine owns performance qualification.
Every supported Nix operating-system and architecture combination runs
functional and determinism gates. A local Apple Silicon notebook profile may
have separate performance evidence without redefining canonical batch claims.

### Repetition and warmup

- Cold-start and steady-state measurements are reported separately.
- Each candidate receives equivalent warmup or starts from an explicitly cold
  process according to the workload contract.
- Candidate order is randomized or rotated to reduce thermal and temporal bias.
- Enough repetitions are collected to report median, dispersion, and tail
  behavior; one fastest run is never evidence.
- JFR overhead is measured and either held constant or reported separately.
- Heap size, worker concurrency, and evidence policy remain fixed within a
  collector comparison.

### Required metrics

| Category | Required observations |
| --- | --- |
| Correctness | Result hashes, validation verdicts, ledger/SFC identities, deterministic replay. |
| Time | Cold startup, preparation, wall time, CPU time, seed-month throughput, checkpoint and export time. |
| Allocation | Allocated bytes, allocation rate, top classes/sites, promotion behavior, large-array lifecycle. |
| Heap | Live set after closing boundaries, peak used/committed heap, headroom, full-collection behavior. |
| Process memory | Peak and steady RSS, non-heap, direct/native memory where observable. |
| GC | Collection count, concurrent CPU, total GC time, pause median/p95/p99/max, allocation stalls and failure modes. |
| JIT | Compilation time, compiler CPU, code-cache use, warmup trajectory, deoptimizations and failures. |
| Operations | Cancellation latency, worker cleanup, artifact atomicity, diagnostics quality, platform availability. |

Primary selection uses time to a successfully validated canonical result under
the profile's resource and pause constraints. A candidate cannot win by omitting
evidence, reducing scale, changing seed concurrency, or consuming unbounded
memory.

## Candidate Promotion Rules

A runtime candidate becomes a supported default only when:

1. build, format, unit, heavy, integration, ledger, CLI, notebook, and
   profiling workflows pass on its exact distribution;
2. canonical outputs satisfy the determinism and accounting gates;
3. its improvement is repeatable on controlled hardware and material for the
   declared profile objective;
4. peak RSS, heap headroom, pause behavior, CPU, startup, and diagnostics remain
   within documented limits;
5. all required target operating systems have either the same supported profile
   or an explicit compatible fallback;
6. security updates and patch upgrades have a tested maintenance path;
7. the launcher, manifests, operations documentation, and failure messages
   expose the resolved runtime clearly; and
8. an ADR records the selected baseline or profile and the evidence bundle that
   justified it.

Failure to beat the control is a valid result. Amor Fati does not support extra
collectors or JITs merely because they were benchmarked.

## Implementation and Evidence Sequence

### Phase 0: make the current runtime observable

1. Reconcile `.jvmopts`, Nix, CI, assembled-runner, and documentation claims.
2. Capture complete JDK, VM, JIT, GC, heap, container, OS, and architecture
   metadata in profiling artifacts.
3. Add allocation rate, process RSS, pause distribution, and JIT observations to
   the controlled evidence where current tooling lacks them.
4. Freeze a current-engine workload bundle to validate the harness.

### Phase 1: qualify the notebook-pilot baseline

1. Test JDK 25 with Scala, sbt, ZIO, Almond, Stainless, ledger verification,
   assembly, tests, and all Nix systems.
2. Use HotSpot C2 and G1 as the initial control unless evidence exposes a
   blocker.
3. Implement the Amor Fati launcher and explicit kernel/worker runtime metadata.
4. Decide whether the pilot worker is out-of-process or a documented temporary
   in-process backend behind the Research API.

### Phase 2: establish the target allocation profile

Run the target population compiler and thin end-to-end DOD month with realistic
weights, table capacities, current/next state, relationships, workspaces,
ledger execution, validation, and bounded results. Remove accidental allocations
identified by profiling before comparing collectors.

### Phase 3: compare collectors and JITs

1. Compare G1 and Parallel GC for the canonical research-batch objective.
2. Compare G1, ZGC, and Generational Shenandoah for large-population and
   pause-constrained objectives.
3. Compare Graal JIT with HotSpot C2 using a qualified collector and identical
   workload.
4. Re-run finalists across the scale, horizon, evidence, cancellation, and
   supported-platform gates.

### Phase 4: record accepted runtime decisions

Only after the evidence is reviewable, extract separate ADRs where independence
matters:

- supported JDK/distribution and update line;
- kernel and simulation-worker process topology;
- default and optional GC profiles; and
- Graal JIT adoption, if it independently qualifies.

Native Image requires a later RFC rather than an incidental ADR from this
comparison.

## Acceptance Criteria

This RFC can close only when:

1. the system launcher, not ambient `java`, owns supported runtime selection;
2. build, kernel, and worker JVM responsibilities are explicit;
3. a JDK/distribution passes the complete compatibility matrix;
4. the target-core benchmark bundle represents real population, DOD, ledger,
   evidence, and month-closing behavior;
5. collector and JIT comparisons are reproducible on controlled hardware;
6. scientific outputs and invariants are unchanged across qualified profiles;
7. one default research-batch profile and one notebook-kernel profile are
   selected, with any large-population profile justified separately;
8. effective runtime facts appear in every result manifest;
9. resource validation, cancellation, OOM isolation, and artifact atomicity are
   tested; and
10. accepted choices are extracted into one or more ADRs with evidence links.

## Open Decisions

1. The exact JDK major, vendor distribution, Nix attribute, and patch-update
   policy.
2. Whether JDK 21 remains a supported fallback after JDK 25 qualification.
3. Whether the notebook pilot starts with an out-of-process worker or a
   temporary in-process backend.
4. The local worker protocol and checkpoint/result transport boundary.
5. Canonical Linux qualification hardware and operating-system image.
6. Supported performance evidence for Apple Silicon and other Nix systems.
7. Initial heap and host-memory envelopes for kernel, CI, research batch, and
   scale-ladder profiles.
8. The exact canonical horizons, scales, seed counts, and evidence policies.
9. Statistical repetition count and material-improvement thresholds.
10. Whether G1, Parallel, ZGC, or Generational Shenandoah qualifies for each
    supported worker profile.
11. Whether Graal JIT provides a material target-core advantage over C2.
12. Which runtime overrides mark a run experimental versus unsupported.
13. Runtime-profile compatibility and deprecation rules across releases.
14. Which JFR and native-memory observations are always on versus profiling-only.

## Implementation Anchors

- [`flake.nix`](../../flake.nix) for the current JDK 21, G1, heap, and
  `amor-fati-java` wrapper;
- [`.jvmopts`](../../.jvmopts) for the current local sbt heap and G1 selection;
- [CI workflow](../../.github/workflows/ci.yml) for the current major-version
  check and Nix-owned build/test environment;
- [Operations](../operations.md#requirements) for the implemented toolchain
  contract;
- [Hot-path profiling](../hot-path-profiling.md) for JFR, allocation, GC, and
  JVM-flag artifacts;
- [Performance regression budgets](../performance-regression-budgets.md) for
  current soft duration, throughput, heap, and GC-time comparisons;
- [`profile-jvm-process.sh`](../../scripts/profile-jvm-process.sh) for the
  assembled-process JFR and unified-GC-log harness; and
- [`NightlyDiagnosticsProfileRunner.scala`](../../modules/cli/src/main/scala/com/boombustgroup/amorfati/diagnostics/NightlyDiagnosticsProfileRunner.scala)
  for current JVM, heap, GC, and step-throughput telemetry.

External capability references:

- [OpenJDK 25](https://openjdk.org/projects/jdk/25/) and
  [JEPs integrated since JDK 21](https://openjdk.org/projects/jdk/25/jeps-since-jdk-21)
  for the candidate JDK line and collector changes;
- [Scala JDK compatibility](https://docs.scala-lang.org/overviews/jdk-compatibility/overview.html)
  for Scala 3.8 support on JDK 21 and JDK 25;
- [JDK 25 HotSpot GC tuning guide](https://docs.oracle.com/en/java/javase/25/gctuning/)
  for G1, Parallel, and ZGC behavior and tuning guidance;
- [JEP 521: Generational Shenandoah](https://openjdk.org/jeps/521) for its JDK 25
  product status and explicit mode behavior;
- [GraalVM as a JVM](https://www.graalvm.org/jdk25/reference-manual/java/) and
  [Graal JIT operations](https://www.graalvm.org/jdk25/reference-manual/compiler/operations/)
  for the JVM/JIT distinction; and
- [GraalVM Native Image memory management](https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/MemoryManagement/)
  for its distinct runtime and collector model.
