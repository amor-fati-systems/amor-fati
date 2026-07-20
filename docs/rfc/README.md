# Request for Comments Index

This directory contains proposed Amor Fati model and architecture contracts.
RFCs describe target semantics, unresolved design choices, and implementation
boundaries. They do not describe canonical runtime behavior until the relevant
decisions are accepted, implemented, and promoted into the canonical model and
architecture documentation.

## Active RFCs

| RFC | Status | Scope |
| --- | --- | --- |
| [RFC-0001: Population and representation](0001-population-and-representation.md) | Semantic decisions complete; implementation pending | Reference economy, population compiler, representation scale, population storage, migration, tourism, and opening relationships. |
| [RFC-0002: Research API and notebook runtime](0002-research-api-and-notebook-runtime.md) | Draft for decision | Public experiment lifecycle, result queries, reproducibility, committed notebooks, and the managed Almond/Jupyter environment. |
| [RFC-0003: Model ontology and state architecture](0003-model-ontology-and-state-architecture.md) | Draft for decision | Model-wide units, relationships, instruments, assets, state lifetimes, representation resolution, and target core architecture. |
| [RFC-0004: JVM runtime, JIT, and garbage collection policy](0004-jvm-runtime-jit-and-garbage-collection-policy.md) | Draft for decision | Supported JDK and distribution, process topology, JIT and GC profiles, heap policy, runtime provenance, and qualification evidence. |

## Decision and Evidence Order

The active RFCs have one evidence loop rather than a strictly linear resolution
order:

1. **Population semantics:** define the statistical population units,
   representation weights, and opening population relationships required by the
   first target slice.
2. **Model-wide semantic ontology:** classify units, relationships, contracts,
   instruments, assets, aggregate institutions, authoritative owners, state
   lifetimes, and first-target representation modes.
3. **Research API and notebook runtime:** define how researchers configure,
   execute, observe, compare, and reproduce experiments using the accepted
   ontology.
4. **JVM runtime provisional baseline:** qualify the JDK, process topology, and
   control runtime needed by the notebook pilot without selecting a final GC.
5. **Physical state architecture:** translate the accepted ontology and
   observed research access patterns into data-oriented storage, indexes, views,
   and migration boundaries.
6. **JVM runtime final qualification:** use the representative target-core DOD
   allocation and live-set profile to select any supported JIT and GC profiles.

The [RFC-0003 ontology audit matrix](0003-model-ontology-matrix.md) is the
working inventory for the model-wide semantic gate. It is a companion to the
RFC, not a separate decision record.

RFC numbers are stable document identities, following the repository's ADR
convention, not dependency or implementation positions. Once assigned, a number
is never changed; dependency changes and evidence loops are recorded in this
index rather than by renumbering files.

## Lifecycle

1. A draft RFC records proposed semantics and lists its open decisions.
2. Durable accepted decisions are captured in an ADR and linked from the RFC.
3. The RFC remains active while implementation and unresolved design work
   continue.
4. Once the target is implemented, canonical model, ODD, state-vector,
   architecture, validation, and model-card documentation is updated.
5. The RFC is then marked `Implemented` or `Superseded` and retained as design
   history rather than deleted.

An accepted ADR does not automatically make an entire RFC canonical. It accepts
only the decision within the ADR's stated scope.
