# RFC-0001: Population and Representation

Status: Draft for decision
Scope: Reference population, representation scale, population storage profile,
firm population, migration, tourism, and opening population relationships
Performance: Deliberately out of scope for this design pass

## Purpose

Amor Fati currently derives its household population from firm employment and
then maps agent-scale monetary flows to Polish macro aggregates through
`gdpRatio`. This makes a run possible, but it leaves three different concepts
entangled:

1. the empirical economy being represented;
2. the statistical units and relationships present in that economy; and
3. the number of simulated agents used to represent those units.

The target design separates those concepts. A researcher should select a
versioned reference economy, a representation scale such as `1:1000`, and a
seed. The population compiler should derive a balanced synthetic population
and report exactly what it created. Researchers should not need to coordinate
independent `firmsCount`, `workersPerFirm`, and household-count parameters.

This RFC is the first decision stage for the target core. It owns the
statistical population, its compiler, and the population-facing part of the
storage profile. Its accepted semantics become inputs to the
[Research API and notebook runtime RFC](0002-research-api-and-notebook-runtime.md)
and the later model-wide
[Model ontology and state architecture RFC](0003-model-ontology-and-state-architecture.md).
It does not define the full ontology of public and financial institutions, real
assets, securities, insurance, interbank exposures, markets, policy state, or
monthly execution evidence.

This RFC records the intended population model, not the current implementation.
Its durable separation of reference population from representation scale is
extracted as proposed
[ADR-0008](../adr/0008-explicit-reference-population-and-representation-scale.md).
That ADR can become accepted, and canonical model documentation can change,
only after this RFC's open decisions are resolved. The narrower runtime-storage
boundary is already accepted in
[ADR-0006](../adr/0006-data-oriented-high-cardinality-state.md); the population
ontology and compiler details remain under review here.

## Proposed Decisions

1. A run represents a versioned **reference economy**. Representation scale is
   a property of the simulation, not a calibration of the economy itself.
2. `Person` and `Household` become distinct units. Labor supply belongs to
   persons; consumption, pooled finances, housing, and most bank relationships
   belong to households.
3. Employment, unemployment, economic inactivity, retirement, and financial
   bankruptcy are not interchangeable statuses.
4. Immigration is a residency transition and migration history attached to a
   person. It is not a separate kind of household.
5. Inbound tourists are temporary non-residents and do not enter the resident
   population. Tourism remains aggregate by default, with an optional visitor
   cohort module when a research question needs micro-level tourism.
6. Ordinary firms are synthetic but empirically constrained jointly by sector,
   size, region, ownership, and employment. Named systemic institutions may be
   represented at `1:1`.
7. Employment and opening financial relationships become explicit links or
   contracts. A single `bankId` must not stand for every account and credit
   product. The model-wide RFC owns the final contract and instrument schemas.
8. `gdpRatio` is not the target representation mechanism. Agent weights and a
   true-scale reference-economy balance sheet should make the mapping from
   simulated units to aggregates explicit.
9. High-cardinality population state uses data-oriented, columnar storage.
   Scala `case class` values remain appropriate for configuration, manifests,
   aggregate state, validated commands, results, and diagnostic views. The
   model-wide RFC owns the storage boundary outside the population domain.

## Terminology

The following terms are normative for this design:

| Term | Meaning |
| --- | --- |
| Resident | A person included in the reference economy's usual-residence population. Citizenship and country of birth are separate attributes. |
| Immigrant | A resident with an inward residency transition or relevant migration history. This does not imply current foreign citizenship, permanent wage discount, or permanent remittance behavior. |
| Emigrant | A person whose usual residence moves out of the reference economy. Temporary outbound tourism is not emigration. |
| Labor force | Employed plus unemployed persons under the baseline's statistical definition. |
| Unemployed | A person in the labor force without employment and available for or seeking work under the selected definition. |
| Economically inactive | A person outside the labor force. Reasons include education, retirement, care, disability or long-term sickness, and other inactivity. |
| Pension recipient | A person receiving a pension. This is not identical to economic inactivity because pension recipients may work. |
| Household | A co-resident economic unit that pools at least part of consumption, housing, income, assets, or liabilities. |
| Firm | The modeled enterprise unit. Establishments and local units require an explicit extension rather than being silently treated as enterprises. |
| Tourist or visitor | A temporary non-resident visitor, or a resident temporarily travelling abroad. Neither event changes usual residence. |
| Representation weight | The number of empirical units represented by a simulated unit or relationship. |

The word `inactive` should describe labor-force status only. It should not be
used as a synonym for a household skipped by an execution stage, a bankrupt
borrower, an unemployed person, or a retired person.

## Current Amor Fati State

The current implementation already contains useful pieces, but it does not yet
implement the ontology above.

| Area | Implemented state | Consequence |
| --- | --- | --- |
| Person and household | `Household.State` is simultaneously a worker, a consumption unit, and a financial owner. | Individual labor status and multi-person household finances cannot be represented separately. |
| Employment | `HhStatus` contains `Employed`, `Unemployed`, `Retraining`, and `Bankrupt`. | Retraining and bankruptcy are mixed into an employment/activity status even though they belong to program and financial-distress dimensions. |
| Initial population | Firm worker slots create employed household agents; initial unemployment adds agents outside those slots. | Actual household-agent count depends on realized firm sizes and unemployment, not on one authoritative resident-population control. |
| Economic inactivity | No resident inactive-person population exists. | Students, carers, disabled or long-term sick people, and other inactive residents are absent as agents. |
| Retirement | `DemographicsState` carries an aggregate retiree stock used by ZUS and NFZ. Household agents have neither age nor a retired status. | Retirement is currently a fiscal/demographic proxy, not an agent transition. Growing the retiree stock does not remove or reclassify a represented worker. |
| Immigration | Initial and monthly immigrants are created as household agents with `isImmigrant = true`; return migrants are removed; remittances are modeled. | Migration is substantially more developed than retirement, but a permanent Boolean conflates residency history, integration, citizenship, and remittance propensity. |
| Tourism | Inbound receipts and outbound expenditure are aggregate GDP-linked flows with seasonality, exchange-rate response, trend, and shocks. | Tourism exists, but there are no visitor agents, stays, origin markets, destinations, or tourism-firm capacity constraints. |
| Firms | Sector counts are controlled; size, region, and ownership attributes are then sampled in separate passes, with some ownership probabilities conditional on sector. | The population reflects selected empirical marginals but not a controlled joint distribution of sector, size, region, ownership, and employment. |
| Banks | A household or firm has one `bankId`; household deposits, mortgages, consumer credit, interest, and most flows use it. | Aggregate multi-bank routing is explicit, but product-level bilateral contracts are not. |
| Scale | Defaults declare 10,000 firms and use `workersPerFirm` as a population and GDP normalizer. `gdpRatio` scales many macro stocks. | Population representation, firm production, and monetary calibration are coupled. A scale change is not a transparent `1:N` sampling decision. |
| Runtime data layout | Agent state and persistent financial state are primarily `Vector` collections of per-entity case classes. The ledger library executes batches against columnar `Array[Long]` stores, but Amor Fati currently creates an empty execution state for monthly deltas and snapshots the result to a `Map`. | The ledger execution kernel is data-oriented, but the persistent simulation state is not. Population passes still allocate and copy object rows, and the ledger layout is not yet the single persistent layout of financial state. |

Two opening stocks expose the population mismatch directly. Initial
`workingAgePop` is the length of the population actually created from realized
firm job slots, unemployment, and initial immigration. Initial retirees are
instead configured as `firmsCount * workersPerFirm / 3`. Monthly retirement
then increases the external retiree stock without reclassifying or removing a
household agent. The resulting values can support fiscal-flow experiments, but
they do not form a closed demographic accounting system.

Relevant implementation anchors include:

- [`Household.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/agents/Household.scala)
  for household state and `HhStatus`;
- [`SocialSecurity.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/agents/SocialSecurity.scala)
  for the external retiree stock;
- [`Immigration.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/agents/Immigration.scala)
  and [`ImmigrantInit.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/init/ImmigrantInit.scala)
  for migrant creation and removal;
- [`HouseholdFinancialEconomics.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/engine/economics/HouseholdFinancialEconomics.scala)
  and [`TourismConfig.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/config/TourismConfig.scala)
  for aggregate tourism;
- [`FirmInit.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/init/FirmInit.scala)
  for the current firm generator; and
- [`PopulationConfig.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/config/PopulationConfig.scala)
  and [`SimParams.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/config/SimParams.scala)
  for current population and `gdpRatio` coupling;
- [`LedgerFinancialState.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/engine/ledger/LedgerFinancialState.scala)
  for the current object-row persistent financial state; and
- [`RuntimeFlowExecutor.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/engine/flows/RuntimeFlowExecutor.scala)
  for the current monthly delta execution boundary.

## Target Entity Model

### Person

A person is the demographic and labor-market unit. The minimum target state is:

- `personId` and `householdId`;
- age or age cohort;
- region of usual residence;
- residency status and migration history;
- labor-force status: employed, unemployed, or inactive with a reason;
- optional employment-contract link;
- education, skill, and health or work-capacity attributes;
- pension eligibility and receipt, separate from labor-force status; and
- labor-market program state such as retraining, separate from employment.

Children and other dependants are persons even when they have no independent
economic decision rule. Collective-household residents may be represented by a
dedicated household type rather than dropped from the population.

### Household

A household is the primary consumption and retail-finance unit. It contains:

- member links and household type;
- dwelling tenure, region, housing cost, and dependent composition;
- pooled disposable income and consumption rules;
- deposit accounts and securities holdings;
- mortgage and consumer-credit contracts; and
- household-level distress and insolvency state.

An employment loss changes a person's labor state and then the income of their
household. Personal insolvency changes household finances; it does not make a
person economically inactive.

### Firm

A firm is an enterprise or explicit enterprise archetype. It contains sector,
size class, region, ownership class, legal form where relevant, production and
capital state, and financial contracts. Employment is linked through explicit
person-to-firm relationships or weighted job allocations.

The baseline must declare whether a row represents an enterprise, local unit,
or establishment. Mixing those universes produces invalid firm counts and
employment ratios.

### Financial Institution

Banks, the central bank, funds, insurers, and other financial institutions keep
their institutional identity. Because the number of systemically relevant
institutions is small and their heterogeneity matters, named banks can remain
`1:1` even when household and ordinary-firm populations are sampled.

This section defines only how population compilation references those
institutions when assigning accounts, credit, or employment. Their complete
ontology, consolidation, contracts, instruments, positions, and runtime state
belong to the model-wide ontology RFC.

### Visitor Cohort

The default model has no visitor agents. If tourism microstructure is activated,
it should create temporary `VisitorCohort` units with origin, destination,
arrival month, nights, party size, and expenditure basket. Their representation
weight contributes to visitor nights and expenditure, never to resident
population or labor-force denominators.

## Population Storage Profile

The population ontology and its storage layout should be designed together.
Amor Fati should not first introduce millions of `Person`, `Household`,
`Enterprise`, membership, employment, and opening-relationship objects and then
replace them with a columnar representation in a second migration.

Data-Oriented Design in this RFC means arranging high-cardinality population
state by the fields consumed together by model kernels. It does not mean
removing every `case class`, exposing mutable arrays throughout the codebase,
or introducing a generic entity-component-system framework.

### Storage boundary

The compiler-owned population slice has the following conceptual shape. It is
embedded in, but does not redefine, the full `SimulationState` described by the
model-wide ontology RFC:

```text
CompiledPopulation
|-- PersonTable                       columnar person state
|-- HouseholdTable                    columnar household state
|-- EnterpriseTable                   columnar ordinary-enterprise state
|-- HouseholdMembershipTable          person-to-household relationships
|-- EmploymentTable                   person-to-enterprise relationships
|-- PopulationNetworkStore            compact social and production graphs
|-- OpeningRelationshipAssignments    typed account, credit, tenure, and ownership inputs
`-- PopulationManifest                weights, controls, residuals, and provenance
```

`OpeningRelationshipAssignments` is compiler output, not a generic persistent
contract table. The target deposit-account, credit-contract, security-position,
enterprise-ownership, insurance, pension, and interbank schemas are defined in
the model-wide RFC. The compiler materializes only the families enabled by the
selected baseline and hands their balances to the authoritative ledger
initialization boundary.

The ownership rule is:

| State family | Default representation |
| --- | --- |
| Persons, households, ordinary enterprises | Dedicated structure-of-arrays tables backed by primitive arrays or equally compact typed columns. |
| Employment and membership | Dedicated relationship tables with indexed party columns and represented quantities. |
| Opening account, credit, tenure, and ownership assignments | Typed compiler output validated against the model-wide relationship and contract schemas. |
| Social and production networks | Compact adjacency storage such as offsets plus neighbor indices, not one neighbor-array object per agent. |
| Financial balances | Persistent columnar ledger storage owned outside this population slice; compiler DTOs must not become a second owner. |
| Named institutions referenced by the population | Stable typed IDs and immutable profiles supplied by the selected baseline. |
| Configuration, population manifests, aggregates, commands, and results | Immutable typed values and case classes. |
| Tests, diagnostics, and exports | On-demand immutable row views or snapshots derived from the columnar source. |

The ledger library already demonstrates the intended split. Its production
`MutableWorldState` stores one `Array[Long]` per entity-sector and asset pair,
while typed batches and pure reference semantics remain object-based. Amor Fati
should reuse this architectural principle without treating the current ledger
implementation as a complete population or world-state design.

In this example, `Array[Long]` is raw backing storage for fixed-point monetary
values, not an untyped integer domain model. Amor Fati accessors and projections
must expose `PLN` or the project's equivalent opaque fixed-point monetary type;
the raw representation remains encapsulated inside the storage boundary.

### Column schemas

An entity table stores one column per state dimension. For example, a person
labor state should not be stored as an allocated enum payload such as
`Employed(firmId, sectorIdx, wage)`. Its runtime representation may use:

```text
labourStatus       Array[Byte]
employerIndex      Array[Int]
sectorIndex        Array[Byte]
wageRaw            Array[Long]  // fixed-point PLN backing units; exposed as PLN
unemployedMonths   Array[Int]
retrainingProgram  Array[Int]
representationWeight Array[Long]
```

The exact primitive encoding is an implementation decision, but raw columns
must remain package-private. Domain transitions such as `setEmployed`,
`setUnemployed`, `retire`, `immigrate`, and `emigrate` update every dependent
column together and enforce the same invariants that an algebraic data type
would otherwise make visible.

Columnar storage is not permission to replace domain types with unlabelled
integers at API boundaries. Opaque IDs, typed column accessors, validated
commands, and explicit transition methods should preserve Scala's type safety.

### Stable identity and dense indices

A stable entity ID is not an array position. Every high-cardinality entity
family needs:

- a stable typed ID used by manifests, diagnostics, replay, and external
  references;
- a dense runtime row index used for array access;
- an ID-to-row resolution boundary where dynamic lookup is required; and
- generation or liveness protection so a removed row cannot be mistaken for a
  newly allocated entity.

Births, deaths, immigration, emigration, household formation, firm entry, and
firm exit change table membership through a controlled allocator. Slot reuse,
compaction, and relationship rewrites may occur only at a declared transition
boundary and must preserve replay determinism.

### Month boundary and mutation

The current explicit monthly transition remains a semantic requirement. DOD
must not turn the model into globally mutable state with order-dependent reads.

Static columns are shared across months. Dynamic columns use explicit current
and next buffers, a change set, or another mechanism that guarantees that a
stage reads the declared opening state and that the completed month becomes
visible atomically. Scratch columns for income, consumption, matching, or flow
amounts may be reused within a month but are not persistent state.

The preferred default for entity-resolved state is double buffering of the
columns whose opening and closing values must coexist. The runtime swaps those
buffers only after validation and ledger reconciliation. Full immutable object
graphs should be materialized only for requested snapshots, not for every
month.

### Relationship and contract tables

Population relationships are data, not fields overloaded with several
meanings:

- a person-to-household membership column handles the common one-household
  relation;
- employment links connect person rows to firm rows and carry represented job
  quantities where weights require them;
- the compiler emits validated opening assignments for accounts, loans, tenure,
  and ownership using the model-wide schemas; and
- variable-degree relationships use indexed adjacency or relationship tables
  rather than nested collections.

Financial principal must have one persistent owner. A contract schema may own
parties and contractual terms while the ledger owns the balance, but Amor Fati
must not keep independently mutable principal columns in both places. Deposit,
credit, security, ownership, insurance, pension, and interbank schemas remain
separate as specified by the model-wide ontology RFC.

### Research and diagnostic interface

Researchers should not need to understand the column encoding. Public model
configuration uses the baseline, representation policy, seed, and scenarios.
Diagnostics and exporters receive stable typed read views, iterators, aggregate
queries, or explicit snapshots.

This preserves a readable research interface while allowing the engine to
process large populations without making per-agent case classes the permanent
storage format.

## Reference Economy

A reference-economy bundle is a versioned, immutable input to population
compilation. It should contain:

- country and territorial coverage;
- reference period and source-vintage metadata;
- resident population controls by age, region, household type, and relevant
  labor-force dimensions;
- household controls for size, composition, tenure, income, and financial
  products;
- enterprise controls by sector, size, region, ownership, and employment;
- an explicit sector-classification bridge into Amor Fati sectors;
- named or archetypal financial institutions and their opening profiles;
- national-accounts and financial-accounts totals; and
- migration, births, deaths, pension receipt, and tourism control series.

The first target bundle represents Poland in `2026-Q1` using information
available by `2026-04-30`. These are different temporal concepts:

| Field | Initial target meaning |
| --- | --- |
| `referencePeriod` | `2026-Q1`: the principal completed-quarter economic state represented by the baseline. |
| `valuationDate` | `2026-04-30`: the point-in-time date for market prices and stocks where a valid observation is available. |
| `informationCutoff` | `2026-04-30`: no source release published after this date belongs to this baseline vintage. |
| `sourceObservationPeriod` | The actual date, month, quarter, or year observed by each individual source series. |
| `sourceReleaseDate` | The publication date of the exact source vintage used. |

April 30 is in the second calendar quarter; it must not be used as evidence
that the reference period is `2026-Q2`. Conversely, calling the bundle
`2026-Q1` does not make every input a Q1 observation. Older annual, monthly, or
quarterly inputs remain admissible only when their dates and transformations
are explicit.

The bundle may combine sources observed on different dates, but every series
must retain its observation date, release vintage, transformation, and
reconciliation rule. A proposed identifier such as
`pl-2026q1-cutoff-2026-04-30-v1` identifies the compiled bundle, not an
unsupported claim that every source describes the same day.

The existing Amor Fati calibration is the starting evidence for this bundle,
not yet proof of a fully reconciled reference economy. Aggregate GDP, public,
banking, external, and financial stocks may be calibrated while the joint
population of persons, households, and firms remains only partially empirical.
The baseline compiler must preserve valid existing bridges and make the sector,
population, and ownership gaps explicit rather than treating all current
defaults as equally calibrated.

## Representation Scale

### Researcher-facing meaning

`1:N` means that one ordinary simulated resident unit represents approximately
`N` empirical residents in the same controlled stratum. At `1:1`, Amor Fati
would create a synthetic unit for each in-scope empirical unit; it would not
claim to reconstruct real identities.

The primary user setting should therefore be residents per agent, not raw agent
counts:

```yaml
baseline: pl-2026q1-cutoff-2026-04-30-v1
representation:
  residentsPerAgent: 1000
  enterprisePolicy: stratified
  systemicInstitutions: census
  rareStrata: preserve
seed: 42
```

The population compiler derives household, person, firm, and relationship
counts. A scenario may change empirical controls; a scale setting may not.

### Weights and exceptions

A single scale label does not require every entity type to have the same
weight:

- synthetic households and their member records normally carry a household
  representation weight close to the requested population scale;
- ordinary firms carry stratum-specific enterprise weights so weighted firm
  counts and employment both reconcile;
- rare strata may use smaller weights to avoid disappearance;
- named banks and systemic or otherwise indispensable institutions may have
  weight one; and
- every non-default weight and preservation rule is reported in the manifest.

Agent count is never used as an economic count without applying weights.

### Money and the ledger

Rates, prices, propensities, and per-person or per-household decision variables
are intensive values and are not multiplied by representation scale. Monetary
stocks and flows posted to the economy-wide ledger are extensive values and
must equal the represented amount.

Every monetary value and account balance, whether intensive or extensive, must
use `PLN` or an equivalent opaque fixed-point domain type at domain boundaries.
`Double` and `Float` are not valid monetary or accounting representations.

For a weighted household cohort, underwriting may use per-household income,
debt, and debt-service values, while the ledger contract carries the weighted
exposure. Both sides remain fixed-point monetary values, and weighting must use
a checked domain operation with explicit rounding and overflow behavior. The
implementation must make that projection explicit. It must not silently apply
`gdpRatio` to some stocks while treating other agent values as already scaled.

Changing `residentsPerAgent` should preserve controlled aggregate opening
stocks and flows within documented rounding tolerances. It may change finite
population variance and network realization; those effects belong in scale
robustness evidence.

## Population Compiler

Population initialization becomes a deterministic compiler from a baseline,
representation policy, and random seed:

1. Load and schema-validate the reference-economy bundle.
2. Reconcile source universes, dates, classifications, and accounting totals.
3. Construct joint control tables rather than a collection of unrelated
   marginal probabilities.
4. Generate weighted synthetic households and their person members using
   constrained synthesis that satisfies both household-level and person-level
   controls.
5. Generate the firm population from joint sector, size, region, ownership,
   and employment controls.
6. Match employed persons to weighted firm job positions; keep unemployed and
   inactive populations distinct.
7. Assign deposit accounts, mortgages, consumer loans, firm loans, and other
   financial contracts using product-specific empirical controls.
8. Reconcile the micro holdings to national and financial accounts and create
   ledger opening balances.
9. Validate hard invariants and emit the compiled population plus a manifest.

The random seed selects a reproducible micro-realization inside the empirical
constraints. It must not alter controlled totals such as resident population,
labor force, sector employment, or opening deposit stock beyond declared
rounding tolerance.

## Firm Population Policy

The correct default is neither an arbitrary random firm population nor an
attempt to reproduce every actual Polish company.

Ordinary firms should be synthetic and disclosure-safe, but the weighted joint
population must reproduce the reference economy. Size should be conditional on
sector and region; ownership should be conditional where the data supports it;
and weighted job capacity must reconcile with employment. Independent draws
from national sector, size, and region marginals are insufficient because they
can create combinations that do not resemble the empirical economy.

Named firms should be included only when their individual identity is necessary
for the research question and supported by reliable public data. Named or
explicit archetypal banks are a stronger default because bank concentration,
balance sheets, and failure propagation are central mechanisms and the
institution count is small.

## Bilateral Relationships

The population compiler replaces overloaded routing fields with explicit
opening relations:

```text
Person --member-of--> Household
Person --employment-contract--> Firm
Household --deposit-account--> Bank
Household --mortgage-contract--> Bank
Household --consumer-loan-contract--> Bank
Firm --deposit-account--> Bank
Firm --loan-contract--> Bank
```

A household may use different banks for deposits, mortgages, and consumer
credit. A firm may likewise have multiple banking relationships. Each contract
has an owner or borrower, a financial institution, a product, an outstanding
balance, contractual terms, and a representation weight or represented amount.

These examples do not exhaust the model-wide relationship ontology. Enterprise
ownership, securities, insurance, pensions, interbank exposures, tenancy,
collateral, and supply networks are governed by the model-wide RFC. This RFC is
responsible for compiling only the population-linked opening rows requested by
the selected baseline.

Banks do not need a separate copy of household state. They observe the
information allowed by an application or servicing relationship and obtain
their exposures by aggregating the same ledger contracts. This creates a
bilateral micro link while preserving one accounting source of truth.

## Demographic and External Transitions

### Inactivity and retirement

At minimum, inactivity reasons should distinguish retirement, education, care,
disability or long-term sickness, and other inactivity. Pension receipt remains
a separate flag or benefit contract so working pension recipients are valid.

A retirement event must update the relevant person's activity, employment, and
pension states and the household's income. It may not only increment an
external retiree counter. The aggregate retiree count becomes a derived
validation output rather than an independently evolving population.

### Migration

Immigration adds residents and emigration removes residents according to the
baseline's usual-residence convention. The transition updates person,
household, labor, housing, and financial relations together. Migration history
may influence labor matching and remittances, but those effects should decay or
depend on explicit attributes rather than a permanent universal discount.

Amor Fati's existing immigrant spawning, return migration, and remittance logic
is a useful behavioral starting point. It should be re-homed under the common
population compiler and demographic-transition contract rather than discarded.

### Tourism

Tourism is already present as aggregate inbound receipts and outbound resident
expenditure. That is the correct default granularity for monetary-policy,
fiscal, banking, and general macro scenarios unless visitor composition,
regional destinations, accommodation capacity, or tourism-specific shocks are
causal mechanisms in the experiment.

If micro tourism is enabled, inbound visitor cohorts transact with domestic
firms and the foreign sector but never join domestic households. Outbound trips
belong to resident households and create import expenditure without changing
their residency.

## Required Manifest

Every run should persist a machine-readable population manifest containing at
least:

- reference-economy ID, digest, and source vintages;
- population-compiler version;
- requested representation scale and seed;
- simulated and weighted counts by entity type;
- weight distribution and preserved rare strata;
- controlled totals and achieved residuals;
- person and household counts by age, region, activity, employment, and
  migration dimensions;
- firm counts and employment by sector, size, region, and ownership;
- bank and product relationship counts and opening balances;
- resident migration flows and visitor/tourism flows separately; and
- all reconciliation tolerances and warnings.

The manifest, not a comment in `PopulationConfig`, is the authority for what a
specific run represented.

## Hard Invariants

The compiler and monthly transition must enforce:

1. Labor force equals employed plus unemployed represented persons.
2. Economically inactive persons are excluded from the labor-force denominator.
3. Pension receipt does not by itself imply inactivity.
4. Each resident person belongs to exactly one household or declared collective
   household at a month boundary.
5. Weighted employed persons equal weighted filled job positions within an
   explicit tolerance.
6. Resident population evolves through births, deaths, immigration, and
   emigration; tourism never changes it.
7. Every employment or financial contract references live entities and a valid
   represented amount.
8. Product-level household and firm positions reconcile to bank and
   economy-wide ledger positions.
9. Firm and household joint controls meet their declared tolerances.
10. Changing representation scale does not change reference-economy controls or
    true-scale opening balance sheets.
11. Seed changes alter only the admissible micro-realization, not hard controls.
12. No aggregate retiree, migrant, or inactive stock evolves independently of
    the represented entities from which it is defined.
13. All columns in an entity table have the same logical row count, and every
    live stable ID resolves to exactly one live row.
14. Current-month kernels cannot observe partially written next-month entity
    state.
15. Persistent financial principal has one authoritative storage owner.

## Implementation Boundary

The target population is implemented natively inside the controlled model-core
replacement accepted by
[ADR-0007](../adr/0007-controlled-model-core-replacement.md). The current runtime
remains a behavioral and accounting comparison oracle, not the production host
for the new population. Test-only projections may create matched fixtures, but
the target runtime must not translate persistent state through
`Household.State`, `FirmState`, or a second financial-state owner.

A practical sequence is:

1. Define the baseline schema, representation weights, compiler manifest,
   population-table schemas, stable-ID policy, and invariants consistently with
   the model-wide ontology RFC.
2. Build the constrained firm and household/person compiler so its native
   output is the target columnar population, then validate it offline against
   the baseline.
3. Build the new core's month-boundary state directly from the compiled tables
   and opening ledger balances.
4. Implement a thin end-to-end month over native population tables, covering
   labor, household income and consumption, enterprise activity, financial
   flows, ledger execution, SFC validation, and atomic closing.
5. Port retirement, migration, population lifecycle, and active behavioral
   mechanisms without preserving the old agent API as a compatibility target.
6. Replace single-bank routing with model-wide product-level account and credit
   contracts backed by the ledger for every currently active product family.
7. Remove population meaning from `gdpRatio`; retain any independent numerical
   unit normalization only if it is explicit and economically neutral.
8. Add optional visitor cohorts only when a validated research requirement
   justifies them.

The data-oriented storage boundary is recorded in
[ADR-0006](../adr/0006-data-oriented-high-cardinality-state.md). The full state
taxonomy and non-population stores are proposed in the
[model-wide ontology RFC](0003-model-ontology-and-state-architecture.md). Their
individual column encodings may evolve without changing model semantics or
superseding the ADR.

Runtime optimization follows the accepted semantics. It must not decide the
statistical units, delete inactive residents, or collapse product-level
relationships before the reference model is defined.

The next design stage is the
[Research API and notebook runtime RFC](0002-research-api-and-notebook-runtime.md).
It turns the population semantics accepted here into researcher-facing
experiment, query, and evidence requirements. The model-wide ontology and state
RFC then uses both documents to finalize physical state, indexes, views, and
the data-oriented implementation boundary.

## Open Decisions

The following decisions remain before this RFC can become an ADR:

1. Exact baseline IDs, reference periods, and source-vintage policy.
2. The statistical definition and age range used for labor force and
   inactivity controls.
3. The minimum household/person joint control table supported in the first
   compiler version.
4. Whether rare and systemic non-bank firms are preserved automatically or
   only by baseline declaration.
5. The representation-weight policy for firm entry, firm death, household
   splitting, births, and deaths during a run.
6. The first population-linked account and credit families emitted by the
   compiler; the model-wide promotion order remains an ontology-RFC decision.
7. Whether aggregate tourism remains the only supported mode in the first
   population redesign release.
8. The concrete stable-ID, slot-allocation, and compaction policy for dynamic
   populations.
9. Which dynamic columns require double buffering and which may use validated
   in-place transitions or change sets.
