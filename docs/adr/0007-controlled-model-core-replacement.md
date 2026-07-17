# ADR-0007: Controlled Model-Core Replacement

Status: Accepted

Date: 2026-07-15

## Context

The current Amor Fati model core encodes several foundational concepts in a
state shape that conflicts with the target ontology:

- `Household.State` is simultaneously a worker, household consumption unit,
  financial owner, bank customer, migrant marker, and social-network node;
- `FirmState` embeds ownership classification, bank routing, production-network
  links, operational state, and real assets in one copied row;
- employment, household membership, enterprise ownership, accounts, and credit
  are fields or aggregates rather than independently identified relationships;
- persistent micro and financial state is primarily stored as immutable vectors
  of object rows; and
- same-month economics passes updated populations through large result DTOs.

Correcting these boundaries in place would require the old and new
representations to coexist across much of the month pipeline. Production
adapters would need to translate persons back into household-worker rows,
contracts back into `bankId`, tables back into copied vectors, and ledger-backed
balances back into object-row state. That transitional architecture would be
large, would constrain the target design, and would create persistent risk of
dual state ownership.

Amor Fati has no external users or compatibility commitments that justify
preserving the current agent-state API. It does, however, have substantial
assets that must not be discarded: the verified ledger, fixed-point domain
types, explicit month semantics, random-stream ownership, economic equations,
flow vocabulary, SFC validation, calibration evidence, scenarios, diagnostics,
and tests.

## Decision

Amor Fati will replace its stateful model core with a new implementation built
natively on the accepted model ontology and data-oriented storage boundary.
This is a controlled core replacement, not a clean-room rewrite of the system.

The replacement is developed in the existing repository. It preserves and
reuses components whose semantics remain valid, including:

- the `amor-fati-ledger` library and ledger-first accounting path;
- fixed-point domain numerics and semantically valid typed IDs;
- explicit month-boundary and random-stream contracts;
- pure economic equations and mechanisms after dependency review;
- aggregate market, policy, fiscal, monetary, and external state whose ontology
  remains valid;
- flow, SFC, reconciliation, scenario, diagnostic, and evidence infrastructure;
  and
- existing tests and fixed-seed runs after classifying them as behavioral,
  accounting, scientific, or obsolete storage-shape evidence.

The replacement may break the internal API of the current model core. It will
replace:

- population initialization and scaling;
- persistent person, household, enterprise, relationship, contract, network,
  and high-cardinality financial storage;
- agent kernels whose behavior is coupled to whole-row copying or conflated
  state;
- current root ownership of high-cardinality month-boundary state; and
- same-month DTOs that duplicate complete persistent populations.

The current engine is frozen as a behavioral and accounting oracle during
development. It is not a production compatibility layer. Test-only fixtures,
projections, and differential harnesses may compare old and new mechanisms on
controlled inputs. A production month must never translate persistent state
through both cores or allow both cores to own the same balance or entity state.

The new core must first complete a thin end-to-end month using its native state:

```text
baseline and compiled population
        -> labor and wages
        -> household income and consumption
        -> enterprise production and decisions
        -> active financial flows
        -> ledger execution and SFC validation
        -> atomic next-month state
```

The new core becomes the default runtime only after satisfying the cutover gates
defined by the model ontology RFC. These include population and opening-balance
reconciliation, accounting identities, deterministic replay, controlled-input
mechanism evidence, lifecycle integrity, multi-month stability, scenario
directionality, evidence-schema availability, and single runtime ownership.

Full time-series equality with the old engine is not required where corrected
population, relationship, contract, or scaling semantics intentionally change
the modeled economy. Every intentional behavioral difference must instead be
identified, justified, and validated.

## Consequences

- The target architecture is not constrained by compatibility with
  `Household.State`, `FirmState`, current object-row financial state, copied
  population vectors, or overloaded `bankId` routing.
- Development temporarily contains two implementations, but only in branch and
  test contexts. There is no supported dual-engine production mode.
- Existing mechanisms are reviewed and selectively reused rather than copied
  indiscriminately or discarded wholesale.
- Test effort shifts from preserving object shape to proving equations,
  accounting, replay, invariants, empirical reconciliation, and scenario
  behavior.
- The first end-to-end slice must include ledger and SFC execution. A population
  prototype disconnected from accounting is insufficient evidence for the
  replacement.
- Optional ontology resolution, such as individual dwellings, insurers, bank
  shareholders, JST units, or foreign enterprises, remains outside the
  foundational replacement unless separately promoted by research and evidence
  requirements.
- The old core is removed after cutover. It is not retained as a permanent
  fallback, compatibility mode, or alternate scientific model.
- A pre-release Research API and notebook pilot may execute the current engine
  to validate scientific workflows before DOD implementation. This does not
  make current internal types a compatibility target or the old engine a
  production adapter after target-core cutover.
- Canonical model and architecture documentation changes only as new slices
  become implemented and authoritative; draft RFCs continue to distinguish
  target design from current behavior.

## Alternatives Considered

### In-place vertical migration

Introduce target tables one family at a time under the current runtime and
transfer ownership gradually. Rejected because the current conflations cross
population, economics, banking, ledger projection, and month-closing boundaries.
The required compatibility and dual-representation code would be extensive and
would shape the new architecture around temporary constraints.

### Clean-room system rewrite

Reimplement the complete model, accounting, orchestration, diagnostics, and
scientific infrastructure without treating current code as reusable. Rejected
because the absence of external users removes API compatibility pressure, not
the value of verified accounting, domain types, equations, calibration,
scenarios, and evidence.

### Preserve the current core

Add population weights and incremental fields to the current household and firm
rows. Rejected because it would preserve the person-household conflation,
overloaded relationships, opaque scale mapping, object-row storage, and
aggregate financial routing that motivated the ontology redesign.

## References

- [Model ontology and state architecture RFC](../rfc/0003-model-ontology-and-state-architecture.md#core-migration-strategy)
- [Population and representation RFC](../rfc/0001-population-and-representation.md#implementation-boundary)
- [ADR-0008: Explicit Reference Population and Representation Scale](0008-explicit-reference-population-and-representation-scale.md)
- [ADR-0009: Research API as the Supported Scientific Interface](0009-research-api-as-supported-scientific-interface.md)
- [ADR-0010: Managed Almond/Jupyter Runtime and Committed Notebooks](0010-managed-almond-jupyter-runtime-and-committed-notebooks.md)
- [ADR-0002: Explicit Month Boundary](0002-explicit-month-boundary.md)
- [ADR-0004: Ledger-Owned Financial State](0004-ledger-owned-financial-state.md)
- [ADR-0005: Fixed-Point Domain Numerics](0005-fixed-point-domain-numerics.md)
- [ADR-0006: Data-Oriented High-Cardinality State](0006-data-oriented-high-cardinality-state.md)
- [State and ledger boundary](../architecture/state-and-ledger-boundary.md)
