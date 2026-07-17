# Architecture Documentation

This directory is the code-facing architecture entry point for Amor Fati. It
explains how the implementation is organized, where architectural boundaries
live in code, and which local package maps to read next.

It does not define economic behavior. Economic semantics remain in the model,
equation, SFC, calibration, validation, and diagnostics documents linked from
[docs/README.md](../README.md).

## Reading Path

| Need | Start here |
| --- | --- |
| System map and package responsibilities | [Architecture overview](overview.md) |
| One-month runtime execution path | [Runtime loop](runtime-loop.md) |
| State ownership and ledger-backed financial stocks | [State and ledger boundary](state-and-ledger-boundary.md) |
| Proposed population compiler and representation scale | [Population and representation RFC](../rfc/0001-population-and-representation.md) |
| Proposed public Research API and notebook runtime | [Research API and notebook runtime RFC](../rfc/0002-research-api-and-notebook-runtime.md) |
| Proposed model-wide ontology and target state architecture | [Model ontology and state architecture RFC](../rfc/0003-model-ontology-and-state-architecture.md) |
| Proposed JVM, JIT, GC, and worker-runtime policy | [JVM runtime, JIT, and garbage collection policy RFC](../rfc/0004-jvm-runtime-jit-and-garbage-collection-policy.md) |
| How to add mechanisms, sectors, scenarios, diagnostics, or output columns | [Extension points](extension-points.md) |
| Durable architectural decisions | [ADR index](../adr/) |

## Source Anchors

The architecture docs summarize cross-package contracts. When editing a package,
read the nearby package README first, then return here for the system boundary:

| Local map | Boundary |
| --- | --- |
| [engine README](../../modules/model/src/main/scala/com/boombustgroup/amorfati/engine/README.md) | Month loop, same-month economics, flow emission, ledger projection, and closed-month state. |
| [agents README](../../modules/model/src/main/scala/com/boombustgroup/amorfati/agents/README.md) | Autonomous agents, behavioral state, and agent-local module splits. |
| [init README](../../modules/model/src/main/scala/com/boombustgroup/amorfati/init/README.md) | Initial world construction and initialization randomness. |
| [montecarlo README](../../modules/montecarlo/src/main/scala/com/boombustgroup/amorfati/montecarlo/README.md) | Production Monte Carlo runner, TSV contracts, and seed/month output path. |
| [amor-fati-ledger repository](https://github.com/amor-fati-systems/amor-fati-ledger) | Separately verified ledger repository checked out locally under `modules/ledger`; owns the public ledger API. |

## Architecture Scope

The current architecture spine covers four contracts:

1. Package layering: which package owns orchestration, behavior, accounting,
   initialization, Monte Carlo, diagnostics, and generated evidence.
2. Runtime loop: how one explicit month boundary becomes the next boundary.
3. State ownership: which state is behavioral, which state is macro/runtime,
   and which financial stocks are ledger-owned.
4. Extension workflow: where to wire new mechanisms and which tests or docs
   must move with the code.

The population, Research API and notebook, model-wide ontology, and JVM runtime
RFCs describe proposed target boundaries, not current implementation. They
remain design proposals until their decisions are accepted, implemented, and
promoted into the canonical architecture spine.

Architectural history belongs in [docs/adr](../adr/). A proposed ADR records a
candidate decision while its owning RFC remains under review. Only accepted
ADRs enter the canonical architecture history as durable decisions. ADRs are
intentionally short decision records, not living design tutorials.

## Maintenance Rule

When an implementation change moves a boundary between packages, changes the
month-step order, promotes an unsupported stock family into the ledger-owned
slice, or adds a new top-level extension path:

1. Update the package README nearest the code.
2. Update the relevant architecture document in this directory.
3. Add or update an ADR if the decision changes a durable trade-off.
4. Update [docs/README.md](../README.md) so the documentation hygiene check
   continues to classify every committed `docs/` artifact.
