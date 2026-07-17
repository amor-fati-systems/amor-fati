# ADR-0010: Managed Almond/Jupyter Runtime and Committed Notebooks

Status: Proposed

Date: 2026-07-17

## Context

Amor Fati aims to become a research system used and cited by scientists, but
its current entry paths assume familiarity with the repository, sbt tasks, CLI
exports, and internal model structure. Scala gives researchers direct access to
the system's typed domain, yet a generic Scala kernel would still require them
to assemble compatible compiler, JVM, classpath, baseline, and dependency
versions.

Amor Fati is distributed as a complete system rather than as a library that
researchers install into arbitrary applications. The notebook experience must
preserve that model while lowering the cost of exploratory work. It must also
exercise the Research API without coupling the simulation core to Jupyter.

Almond provides a Scala kernel for Jupyter, configurable kernel identity,
launcher command, arguments, and predef behavior, plus APIs for rich notebook
display. Notebook execution remains arbitrary local code execution and is not a
security boundary.

## Decision

Amor Fati supplies a managed Almond-based Jupyter kernel and a small set of
committed, editable Scala notebooks as the first interactive scientific
environment for the Research API.

The system-owned environment:

- exposes a dedicated kernel identity, provisionally `Amor Fati (Scala)`;
- pins and tests one compatible Amor Fati, Scala, JVM, Almond, Jupyter, model,
  baseline, and notebook-adapter combination per supported release;
- launches through an Amor Fati-owned command or environment entry point;
- provides the compiled internal classpath and a minimal predef for Research
  API imports and renderers;
- validates version compatibility at startup and reports the resolved
  environment;
- does not require publishing Amor Fati as a Maven package, `$ivy` resolution,
  or a researcher-managed classpath; and
- keeps Almond and Jupyter dependencies outside the simulation core and
  Research API.

Canonical notebooks are committed as executable research workflows. They run
from a fresh kernel in top-to-bottom order, use bounded CI configurations,
contain no machine-specific paths or credentials, and store clean outputs by
default. Run evidence is persisted through Research API result bundles and
manifests rather than relying on mutable notebook state or visible cell output.

The initial pilot contains at least baseline inspection and scenario comparison
against the pre-release Research API before DOD layout is finalized. The
notebooks record API friction and dominant queries that inform the target state
design. The environment is promoted as supported only after the target core,
deterministic replay, cancellation, resource cleanup, manifests, and canonical
notebook suite pass together.

The managed local kernel is trusted code execution. Remote, shared, or
multi-tenant hosting requires a separate decision covering authentication,
isolation, network policy, quotas, secrets, audit, and tenancy.

## Consequences

- Researchers receive an editable, typed environment without assembling Amor
  Fati as a package dependency.
- Canonical notebooks become executable acceptance tests for Research API
  ergonomics and release compatibility.
- Scala, JVM, or Almond upgrades require the notebook suite to pass against a
  newly pinned compatibility matrix.
- Notebook state, hidden cell order, and rendered output are not sufficient run
  provenance; manifests and result bundles remain authoritative.
- Large outputs, checkpoints, generated plots, and data extracts are not
  committed beside notebook source by default.
- The adapter owns rich display, progress integration, and kernel-specific
  cancellation while core results retain useful non-Jupyter representations.
- Cooperative cancellation must preserve the explicit month boundary and may
  initially limit a kernel to one active run.
- Replacing Almond later can supersede this ADR without changing ADR-0009 or
  the Research API contract.
- Hosted enterprise notebooks are explicitly outside the initial security and
  operational scope.

## Alternatives Considered

### Require researchers to use sbt and CLI exports

Keep the existing operational entry points as the only supported workflow.
Rejected because they impose avoidable repository and build-tool knowledge and
do not provide an interactive typed research surface.

### Publish Amor Fati as a notebook dependency

Ask notebooks to resolve a published or local Amor Fati artifact. Rejected
because it contradicts the system distribution model and makes researchers
responsible for assembling compatible runtime, baseline, and dependency
versions.

### Use uncommitted example notebooks

Treat notebooks as local demonstrations outside version control and CI.
Rejected because they would neither define a reproducible entry path nor test
the Research API against releases.

### Couple the Research API to Almond

Expose Jupyter display and kernel lifecycle types from the scientific API.
Rejected because CLI, batch, and future service adapters must remain independent
of the selected notebook technology.

## References

- [RFC-0002: Research API and Notebook Runtime](../rfc/0002-research-api-and-notebook-runtime.md)
- [ADR-0009: Research API as the Supported Scientific Interface](0009-research-api-as-supported-scientific-interface.md)
- [ADR-0002: Explicit Month Boundary](0002-explicit-month-boundary.md)
- [ADR-0007: Controlled Model-Core Replacement](0007-controlled-model-core-replacement.md)
- [Almond installation options](https://almond.sh/docs/install-options)
- [Almond advanced installation](https://almond.sh/docs/next/install-advanced)
- [Almond Jupyter API](https://almond.sh/docs/api-jupyter)
