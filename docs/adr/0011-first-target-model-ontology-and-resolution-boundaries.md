# ADR-0011: First-Target Model Ontology and Resolution Boundaries

Status: Accepted

Date: 2026-07-20

## Context

The current model core contains economically meaningful mechanisms, but its
runtime types do not form a consistent model ontology. `Household.State`
combines person, household, employment, finance, migration, and network state.
`FirmState` combines an enterprise with bank routing, ownership flags, and
network links. Product-level financial relationships are often inferred from
one `bankId`, while several institutions and security families exist only as
aggregate buckets.

These conflations make it impossible to define a stable Research API or target
data-oriented core without first deciding what the model represents. Greater
granularity is not automatically more scientific: a resolved population needs
an empirical bridge, lifecycle semantics, reconciliation, and validation.
Conversely, an aggregate must not be named or exposed as if it were an
individual economic unit or contract.

RFC-0003 and its code audit identified the units, relationships, contracts,
assets, institutions, and resolution choices required for the first target.
This ADR records the accepted semantic boundary. It does not select physical
column layouts, mutation algorithms, snapshot cadence, or public API shapes.

## Decision

Amor Fati adopts one explicit model ontology. Every modeled family is classified
as an economic unit, relationship, contract or instrument, real asset,
market/policy state, event or flow, observation/evidence, or storage concern.
Scala classes, package names, ledger sectors, and runtime settlement shells do
not define ontological identity.

Persistent units, relationships, contracts, instruments, and positions use
stable family-specific typed IDs. Dense row indices are private runtime
locators. A tagged heterogeneous party reference is used only at boundaries
that intentionally accept several party families.

### Core economic units

- `Person` owns demographic, residency, education, labor-supply, and
  pension-eligibility state.
- `Household` is the co-resident consumption, pooled-finance, housing, and
  retail-credit unit. Person-to-household membership is explicit.
- `Enterprise` follows the Eurostat statistical-enterprise concept: an
  autonomous producer may rest on one or more legal units and operate at one
  or more locations. It is also the first-target consolidated synthetic
  financial counterparty. Ordinary enterprises are weighted; baseline-declared
  systemic enterprises may use weight one.
- Legal units, enterprise groups, establishments, and local units remain
  distinct ontology levels but are not separately resolved in the first
  target.
- Commercial banks remain named units with weight one.

Employment, household membership, banking, ownership, tenure, collateral, and
variable-degree networks are explicit relations or contracts rather than
overloaded entity fields. The current household network is a household
distress-peer network. The current firm network is an enterprise
technology-adoption peer network, not a supply-chain representation.

### Financial contracts and ownership

The first contract-resolved financial families are demand-deposit accounts,
household mortgages, household consumer credit, enterprise bank credit,
named-bank reserve accounts at NBP, and bilateral interbank exposure.

A resolved retail or enterprise account/facility is bilateral and keyed by
holder or borrower, bank, and product. It carries represented quantity where
relationship coverage differs from entity weight. Per-unit fixed-point values
may drive behavior; the ledger owns the checked extensive fixed-point balance.
The contract owns counterparties and terms and references the ledger balance
key. It does not duplicate monetary principal.

One represented facility is not a reconstruction of every empirical agreement
or tranche. Material heterogeneity is preserved through population strata,
product/cohort classification, or additional represented relationships. Term
deposits and unresolved NBFI loan books remain declared aggregate residuals or
cohorts until evidence justifies promotion.

Enterprise ownership uses a mixed policy:

- ordinary domestic-private enterprises carry a declared ownership/control
  class without a private-owner cap table;
- publicly controlled enterprises link to a public owner or owner cohort;
- foreign-controlled enterprises link to a rest-of-world owner cohort; and
- mixed stakes may coexist, but a declared control rule derives one validated
  control classification.

Enterprise control, transferable listed equity, accounting net worth, and bank
regulatory capital remain separate concepts.

### Securities and real assets

The first target uses security instrument cohorts rather than a complete ISIN
book. Government, corporate, and quasi-fiscal cohorts preserve issuer or issuer
class, currency, coupon or indexation, maturity, seniority, and risk where
relevant. Holder positions separately own quantity or represented holding,
valuation, and accounting classification; AFS and HTM are position attributes.

BGK and PFR issuance remains distinguishable. Listed equity uses preserved
systemic issuers where declared by the baseline and issuer cohorts otherwise.
Positions reconcile to issued amounts and valuation state, but Amor Fati does
not create an individual shareholder register.

The first housing target uses regional housing cohorts. Explicit household
tenure and collateral relations carry represented quantity or value and connect
housing status, costs, wealth, and mortgages without assigning identity to an
individual dwelling.

### Institutional resolution

- NBP, central government, ZUS/FUS, NFZ, PPK, FP, PFRON, FGSP, and BFG are
  named aggregate singletons.
- JST is one consolidated local-government sector.
- Insurance is one aggregate institutional sector.
- TFI/investment funds and credit NBFIs are two distinct aggregate
  institutions.
- BGK and PFR are distinct named low-cardinality units.
- Rest-of-world owners and investors remain aggregate sectors or cohorts;
  current GVC partner-sector rows are not foreign enterprises.

Institutional consolidation is declared per accounting view. Sharing current
storage or settlement plumbing does not make two institutions one owner.

### Resolution and evidence rule

An aggregate family is promoted to resolved units, relationships, contracts,
positions, or assets only when supported by a research question, empirical
controls, reconciliation rules, lifecycle semantics, and validation evidence.
Architectural possibility alone is insufficient.

Lifecycle events are applied atomically whether or not detailed event rows are
retained. Mandatory audit aggregates and reconciliation evidence survive;
detailed persistence follows the run evidence policy.

## Consequences

- Research API vocabulary must use these economic concepts rather than current
  case classes, vector positions, aggregate routing fields, or settlement
  shells.
- The target core must split person from household and extract employment,
  membership, ownership, accounts, credit, tenure, collateral, and networks
  from overloaded entity rows.
- `foreignOwned`, `stateOwned`, and `bankId` are migration inputs, not target
  ontology fields. Current flags may overlap without shares or a control rule
  and therefore cannot be carried forward as authoritative ownership state.
- Financial stocks retain one ledger owner while contracts and positions add
  counterparty and term semantics.
- First-target resolution is deliberately mixed: weighted synthetic ordinary
  populations, named systemic units, cohorts, aggregate singletons, and
  intentionally absent families coexist and are reported in manifests.
- Individual dwellings, private cap tables, complete shareholder registries,
  individual insurers, individual JST units, and security-by-security books are
  not prerequisites for the target core.
- BGK/PFR and TFI/credit-NBFI conflations in the current runtime require target
  separation even if their first implementations remain low-cardinality.
- Physical table schemas, allocators, buffering, compaction, query indexes, and
  snapshot cadence remain downstream decisions. ADR-0006 still governs the
  data-oriented boundary.
- Exact reference-population controls and dynamic weight rules remain under
  RFC-0001 and proposed ADR-0008. This ADR fixes ontology, not a baseline
  vintage or calibration dataset.

## Alternatives Considered

### Preserve current agent classes as the ontology

Rejected because current types conflate statistical units, relationships,
contracts, routing, observations, and storage details.

### Resolve every empirical unit, contract, security, and dwelling

Rejected because unsupported micro-detail would create false precision and a
large validation burden without a corresponding empirical bridge or research
requirement.

### Keep all finance and institutions aggregate

Rejected because bilateral bank relationships, borrower heterogeneity,
interbank propagation, ownership behavior, and institutional reconciliation
require explicit counterparties at the accepted resolution.

### Let each baseline define unrelated concepts

Rejected because baselines may select resolution modes and populations, but
must implement one stable ontology so results, evidence, and Research API
selectors remain comparable.

## References

- [RFC-0003: Model Ontology and State Architecture](../rfc/0003-model-ontology-and-state-architecture.md)
- [RFC-0003 Ontology Audit Matrix](../rfc/0003-model-ontology-matrix.md)
- [RFC-0001: Population and Representation](../rfc/0001-population-and-representation.md)
- [ADR-0004: Ledger-Owned Financial State](0004-ledger-owned-financial-state.md)
- [ADR-0005: Fixed-Point Domain Numerics](0005-fixed-point-domain-numerics.md)
- [ADR-0006: Data-Oriented High-Cardinality State](0006-data-oriented-high-cardinality-state.md)
- [ADR-0007: Controlled Model-Core Replacement](0007-controlled-model-core-replacement.md)
- [ADR-0008: Explicit Reference Population and Representation Scale](0008-explicit-reference-population-and-representation-scale.md)
- [Council Regulation (EEC) No 696/93](https://eur-lex.europa.eu/eli/reg/1993/696/oj/eng)
