# ADR-0008: Explicit Reference Population and Representation Scale

Status: Proposed

Date: 2026-07-17

## Context

The current Amor Fati initialization derives the number of household agents
from realized firm worker slots, configured unemployment, and initial
immigration. `firmsCount`, `workersPerFirm`, and `gdpRatio` consequently serve
several different purposes: runtime population size, production normalization,
and translation between agent-scale and Polish macroeconomic values.

That coupling obscures what a simulation represents. Changing the number of
simulated rows can change economic meaning rather than only representation
resolution. Persons and households are also conflated, economically inactive
residents are absent as agents, retirement is partly aggregate, and firm
attributes do not yet reproduce a controlled joint empirical population.

Researchers need to distinguish the empirical economy from the number of
simulated units used to represent it. This distinction must be resolved before
the Research API can offer an intuitive population control and before the
data-oriented core fixes its population tables.

## Decision

A run represents a versioned **reference economy** with an explicit reference
population and opening institutional controls. Representation scale is a
separate run-level choice describing how many empirical units are represented
by each simulated unit or relationship.

The target population contract has these properties:

- the baseline identifies its geography, reference period, source vintages,
  statistical-unit definitions, classifications, and true-scale controls;
- persons and households are distinct units linked by explicit membership;
- labor-force status belongs to persons, while pooled consumption, housing,
  and most retail-finance relationships belong to households;
- ordinary enterprises are synthetic units constrained jointly by empirical
  sector, size, region, ownership, and employment controls;
- representation weights are explicit, typed, reported, and reconciled;
- named systemic institutions can remain represented at `1:1` while ordinary
  populations are weighted;
- population compilation jointly creates units and the opening relationships
  required by the selected baseline; and
- `gdpRatio` is not the target mechanism for expressing representation scale.

A researcher selects a baseline, a supported representation policy such as
`1:1000`, and a seed. The population compiler derives the compatible simulated
population and emits a manifest containing represented and simulated counts,
weights, controls, residuals, exceptions, and provenance. Researchers do not
coordinate independent firm, worker, household, and macro-scaling parameters
to approximate a population size.

Exact Polish baseline controls, weight-transition rules, compiler algorithms,
and storage encodings remain unresolved in RFC-0001. This ADR remains Proposed
until that RFC's acceptance criteria and first implementation scope are agreed.

## Consequences

- Baseline calibration and representation scale become different versioned
  concepts. A scale change does not silently select a different economy.
- Aggregate stocks and flows must reconcile to true-scale controls within
  declared tolerances across supported representation scales.
- Person, household, enterprise, membership, and employment populations require
  explicit identities and weight semantics.
- Economic inactivity, retirement, migration history, residency, and financial
  distress cannot remain one overloaded household status.
- Firm initialization must control a joint empirical population rather than
  independently sampling weakly related marginals.
- The Research API can expose one population representation choice and a
  population manifest instead of implementation-shaped count knobs.
- Data-oriented population tables are designed after the statistical units and
  dominant Research API queries are known.
- Existing calibrations and equations that depend on `workersPerFirm` or
  `gdpRatio` require classification as economic parameters, numerical
  normalization, or obsolete representation coupling.
- Dynamic entry, exit, births, deaths, household changes, and migration require
  weight-preserving lifecycle rules before they are implemented.

## Alternatives Considered

### Keep household-agent count implicit

Continue deriving household agents from firms and unemployment, then scale
macroeconomic values through `gdpRatio`. Rejected because the empirical economy,
statistical population, and computational resolution remain entangled.

### Make every represented family use one global weight

Apply a uniform `1:N` weight to persons, households, firms, banks, and public
institutions. Rejected because household composition, enterprise size,
relationship quantities, and named systemic institutions require different but
reconciled representation policies.

### Require `1:1` simulation

Represent every empirical person, household, and enterprise individually.
Rejected as the sole supported policy because scientific meaning should not
depend on one computational resolution. A `1:1` profile can remain a supported
special case when validated and operationally feasible.

## References

- [RFC-0001: Population and Representation](../rfc/0001-population-and-representation.md)
- [RFC-0002: Research API and Notebook Runtime](../rfc/0002-research-api-and-notebook-runtime.md)
- [RFC-0003: Model Ontology and State Architecture](../rfc/0003-model-ontology-and-state-architecture.md)
- [ADR-0006: Data-Oriented High-Cardinality State](0006-data-oriented-high-cardinality-state.md)
- [ADR-0007: Controlled Model-Core Replacement](0007-controlled-model-core-replacement.md)
- [ADR-0011: First-Target Model Ontology and Resolution Boundaries](0011-first-target-model-ontology-and-resolution-boundaries.md)
- [`PopulationConfig.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/config/PopulationConfig.scala)
- [`SimParams.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/config/SimParams.scala)
- [`WorldInit.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/init/WorldInit.scala)
