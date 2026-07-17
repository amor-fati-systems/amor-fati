# ADR-0006: Data-Oriented High-Cardinality State

Status: Accepted

Date: 2026-07-15

## Context

Amor Fati currently stores household, firm, and persistent financial rows
primarily as immutable vectors of per-entity case classes. This representation
is readable and type-safe, but population passes allocate and copy complete
objects even when a mechanism consumes only a few fields.

The model ontology redesign introduces distinct persons, households,
enterprises, institutions, relationships, contracts, instruments, positions,
networks, real assets, and representation weights at different resolutions.
The population redesign is the first high-cardinality specialization. Creating
those families first as a larger immutable object graph would establish a
storage boundary that would need another structural migration before supporting
substantially different representation scales or more resolved contracts.

The verified ledger library already demonstrates a narrower data-oriented
boundary: its production state stores one primitive balance array for each
entity-sector and asset pair, while commands and reference semantics remain
typed values. Amor Fati should use the same separation of dense runtime data
from control-plane objects without treating the current monthly ledger delta
shell as a complete persistent simulation-state design.

## Decision

High-cardinality persistent runtime state is stored in dedicated
data-oriented tables. Persons, households, ordinary enterprises,
high-cardinality relationships, networks, financial contracts, positions, real
assets when resolved, and persisted financial balances use structure-of-arrays
storage backed by primitive arrays or equally compact typed columns.

This decision does not prohibit case classes. Immutable typed values remain the
default for configuration, reference-economy manifests, scenarios, validated
commands, aggregate and low-cardinality state, public results, diagnostic
views, and pure reference semantics.

The data-oriented boundary has the following contracts:

- Raw mutable columns remain encapsulated inside their owning package.
- Domain transitions update related columns together and enforce cross-column
  invariants.
- Stable typed entity IDs are distinct from dense runtime row indices.
- Dynamic membership uses a controlled allocator with liveness protection and
  deterministic transition-boundary behavior.
- The explicit monthly boundary remains authoritative. Current-month kernels
  cannot observe partially written next-month state; dynamic columns use
  current/next buffers, validated change sets, or an equivalent mechanism.
- Variable-degree relationships use compact indexed relationship or adjacency
  tables rather than nested per-agent collections.
- Persistent financial principal has one authoritative owner. Contract terms
  and ledger balances must not become independently mutable copies.
- Row views and immutable snapshots are materialized on demand for tests,
  diagnostics, export, and research-facing APIs, not as the default monthly
  storage representation.
- Large transient per-entity workspaces may use the same columnar and indexed
  techniques, but remain monthly workspace rather than persistent economic
  state.
- Amor Fati uses dedicated domain tables rather than introducing a generic
  entity-component-system framework.

Exact column encodings, slot-allocation algorithms, compaction policy, and the
set of double-buffered columns are implementation details governed by the
model-wide ontology RFC and its population specialization. They may evolve
without superseding this ADR as long as the boundary above remains intact.

## Consequences

- The population compiler should emit the target columnar population directly,
  rather than building `Vector[Person]` or `Vector[Household]` as persistent
  runtime state.
- New high-cardinality state should not extend the existing per-agent case-class
  representation.
- Existing household, firm, labor-market, relationship, and financial-state
  code is replaced or selectively reused through the controlled core strategy
  recorded by ADR-0007.
- Existing monthly economics outputs that copy complete entity vectors should
  be replaced by table views, workspaces, change sets, or requested snapshots
  according to their state lifetime.
- Domain APIs must compensate for the weaker structural guarantees of separate
  columns by centralizing transitions and validating table-wide invariants.
- Tests should cover row alignment, ID resolution, liveness, month-boundary
  isolation, deterministic replay, and equivalence between row views and
  columnar state.
- Diagnostics and scientific exports retain readable typed schemas even though
  the runtime storage is columnar.
- The proposed Research API defines the supported observation boundary and
  informs indexes and projections without exposing their physical layout.
- Low-cardinality and control-plane code does not need a data-oriented rewrite
  merely for consistency of style.
- The storage layout supports larger and differently weighted populations, but
  performance claims still require profiling and scale-robustness evidence.

## References

- [Model ontology and state architecture RFC](../rfc/0003-model-ontology-and-state-architecture.md)
- [Population and representation RFC](../rfc/0001-population-and-representation.md#population-storage-profile)
- [ADR-0009: Research API as the Supported Scientific Interface](0009-research-api-as-supported-scientific-interface.md)
- [ADR-0007: Controlled Model-Core Replacement](0007-controlled-model-core-replacement.md)
- [ADR-0002: Explicit Month Boundary](0002-explicit-month-boundary.md)
- [ADR-0004: Ledger-Owned Financial State](0004-ledger-owned-financial-state.md)
- [State and ledger boundary](../architecture/state-and-ledger-boundary.md)
- [`Household.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/agents/Household.scala)
- [`LedgerFinancialState.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/engine/ledger/LedgerFinancialState.scala)
- [`RuntimeFlowExecutor.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/engine/flows/RuntimeFlowExecutor.scala)
