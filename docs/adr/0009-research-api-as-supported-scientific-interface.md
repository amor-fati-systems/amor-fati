# ADR-0009: Research API as the Supported Scientific Interface

Status: Proposed

Date: 2026-07-17

## Context

Amor Fati currently exposes useful capabilities through internal initialization
and month-step methods, Monte Carlo runners, CLI commands, and specialized
diagnostic exporters. These entry points reflect implementation ownership rather
than one coherent scientific workflow. A researcher cannot yet construct,
validate, execute, inspect, compare, and reproduce experiments through a stable
programmatic contract.

The target core will replace current high-cardinality state and internal agent
APIs. Exposing `FlowSimulation.SimState`, current agent case classes, or future
primitive data-oriented columns would couple scientific work to a storage shape
that is intentionally changing. Conversely, designing storage without first
observing real research queries risks optimizing the wrong access patterns.

Notebook integration provides the first concrete external-style consumer, but
the scientific interface must survive replacement of Almond, Jupyter, the CLI,
and the internal simulation core.

## Decision

Amor Fati provides a versioned **Research API** as the supported programmatic
interface for scientific experiment construction, execution, observation,
comparison, and reproducibility.

The Research API:

- accepts immutable, typed, and validated experiment specifications;
- makes baseline identity, representation policy, scenario, seed, horizon,
  requested evidence, and resource profile explicit;
- owns lifecycle operations such as prepare, start, step, run, cancel,
  checkpoint, fork, seed ensemble, and close;
- publishes immutable results, manifests, validation reports, aggregate time
  series, controlled snapshots, and bounded micro views;
- uses economic domain names and stable selectors rather than storage indices;
- preserves the explicit month boundary and cannot publish a partially closed
  month;
- records enough configuration and software provenance to reproduce a run; and
- remains independent of Almond, Jupyter, terminal rendering, charting, and
  future transport protocols.

CLI experiment commands, committed notebooks, and future service adapters use
the same application-level experiment services. They may provide different
presentation and operational behavior, but they do not implement separate model
execution semantics.

The public contract does not expose current internal state, primitive DOD
columns, allocator slots, buffer swaps, or ledger implementation indices.
Researcher-facing row values and result tables may use immutable typed objects
because they are bounded observations, not authoritative persistent state.

The first API remains explicitly pre-release until RFC-0002's acceptance
criteria are met against the target core. A pilot facade may execute the current
engine to validate workflows, but current internal signatures are not a
compatibility requirement for the replacement core.

## Consequences

- Notebook and CLI work become consumers of one contract rather than parallel
  wrappers around internal methods.
- The target DOD layout can change without rewriting scientific notebooks when
  public semantics remain stable.
- Result queries and evidence requests must declare retention and resource cost;
  aggregate series cannot require unconditional full microstate snapshots.
- Every completed, cancelled, or failed run requires a machine-readable manifest
  and explicit validation status independent of visible notebook output.
- Checkpoint compatibility, result-schema evolution, cancellation, and API
  versioning become supported system concerns.
- Current CLI and Monte Carlo paths require convergence on Research API
  application services where they represent the same experiment lifecycle.
- Internal package APIs remain free to change under ADR-0007 until the Research
  API is promoted.
- Future enterprise adapters can build on the same boundary without making the
  notebook adapter an enterprise architecture dependency.

## Alternatives Considered

### Treat CLI commands as the public interface

Keep scientific workflows as command invocations plus TSV parsing. Rejected
because interactive composition, typed validation, checkpoint control, bounded
queries, and in-process result inspection would remain fragmented or require a
second semantic layer.

### Expose the simulation state directly

Let researchers import `SimState` or target core tables. Rejected because it
freezes internal storage, permits invariant-breaking mutation, and makes
notebooks depend on object-row or primitive-column details.

### Make the notebook adapter the API

Put experiment lifecycle and result semantics directly into Almond-specific
helpers. Rejected because CLI, unattended runs, and future service adapters
would either depend on Jupyter or reimplement the same behavior.

## References

- [RFC-0002: Research API and Notebook Runtime](../rfc/0002-research-api-and-notebook-runtime.md)
- [RFC-0001: Population and Representation](../rfc/0001-population-and-representation.md)
- [RFC-0003: Model Ontology and State Architecture](../rfc/0003-model-ontology-and-state-architecture.md)
- [ADR-0006: Data-Oriented High-Cardinality State](0006-data-oriented-high-cardinality-state.md)
- [ADR-0007: Controlled Model-Core Replacement](0007-controlled-model-core-replacement.md)
- [ADR-0010: Managed Almond/Jupyter Runtime and Committed Notebooks](0010-managed-almond-jupyter-runtime-and-committed-notebooks.md)
- [`FlowSimulation.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/engine/flows/FlowSimulation.scala)
- [`McRunner.scala`](../../modules/montecarlo/src/main/scala/com/boombustgroup/amorfati/montecarlo/runner/McRunner.scala)
- [`ScenarioRunExport.scala`](../../modules/cli/src/main/scala/com/boombustgroup/amorfati/diagnostics/ScenarioRunExport.scala)
