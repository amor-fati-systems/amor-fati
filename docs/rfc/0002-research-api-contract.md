# RFC-0002 Companion: Research API Contract

Status: Draft for decision
Audit base: `main` at `5b17597e`
Owning RFC: [RFC-0002](0002-research-api-and-notebook-runtime.md)

## Purpose

This document turns RFC-0002's researcher-facing concepts into an explicit
contract before public Scala signatures or physical baseline storage are fixed.
Its first slice defines how a researcher discovers and selects a baseline, how
that baseline is kept separate from scenarios and run controls, and how
historical reconstruction differs from counterfactual analysis and real-time
vintage evaluation.

This is not a claim about current runtime behavior. It is a design companion to
RFC-0002 and remains non-canonical until the relevant decisions are accepted.
Exact Scala names and serialization formats are deliberately deferred where the
logical contract does not require them.

## Current Code Audit

The current configuration surface cannot yet satisfy this contract:

- `SimParams.defaults` is the one code-defined production parameterization and
  still describes the existing `2026-04-30` model-start calibration;
- `BaselineCatalog` now exposes that calibration through the exact legacy ID
  `pl-2026-04-30-legacy-v1`, verifies the compiled payload against a reviewed,
  pinned digest and required model contract, and resolves it into an internal
  `BaselineBundle`; that legacy bundle contains only the parameter payload,
  while provenance and validation are references and the population and
  institutional components remain absent. It is not yet a public Research API,
  persisted bundle loader, or `pl-2026q2-v1` implementation;
- the `SimConfigSpec` and `SimConfigPropertySpec` test names refer to
  `SimParams`; there is no separately loadable `SimConfig` baseline contract;
- `ScenarioRegistry` now selects typed scenario references and applies a typed
  patch only after resolving a compatible `BaselineBundle`; it currently
  supports only the legacy baseline and has no persisted scenario manifest,
  scenario digest, or calendar-indexed driver-path loader;
- scenario provenance can classify a change as a historical analogue, but the
  current registry does not load a historical opening economy or a complete
  calendar-indexed observed shock path; and
- CLI and diagnostic entry points frequently select `SimParams.defaults`
  directly, so a manifest cannot yet prove that an immutable baseline bundle
  was resolved and verified.

The existing defaults and scenarios are useful migration evidence. They must
not be relabeled as the unimplemented `pl-2026q2-v1` bundle or presented as
historical replay.

## Contract Principles

1. A researcher selects one immutable baseline identity. The API does not ask
   the researcher to coordinate source dates, stock valuation dates, parameter
   files, or population tables independently.
2. A baseline describes a reference economy and calibrated model inputs. It
   does not contain representation scale, seeds, horizon, evidence retention,
   or a research intervention.
3. A scenario is a declared transformation or time path applied to a compatible
   baseline. It is not a second full baseline hidden inside a `SimParams` copy.
4. Observed outcomes used for validation are separate from both baseline inputs
   and scenario drivers. The manifest makes this separation auditable.
5. Baseline assets are data, never executable Scala configuration. Loading them
   cannot run arbitrary code or deserialize implementation objects.
6. `SimParams` may remain an internal adapter for the current engine, but it is
   not a Research API input, a persisted baseline schema, or a compatibility
   promise for the target core.
7. Every resolved baseline, scenario, and validation dataset has an immutable
   identity, schema version, content digest, and provenance record.
8. Unsupported schema or model compatibility fails explicitly. The loader does
   not silently reinterpret, partially load, or substitute a baseline.

## Researcher-Facing Concepts

| Concept | Responsibility | Excludes |
| --- | --- | --- |
| `BaselineRef` | Select one catalogued reference-economy bundle by immutable ID. | Representation, run length, seed policy, research shock. |
| `ScenarioRef` | Select a catalogued intervention, counterfactual, or observed driver path. | Opening economy and validation outcomes. |
| `RepresentationSpec` | Select scale, weights, and declared weight-one exceptions under ADR-0008. | Calibration of the represented economy. |
| `ExecutionSpec` | Select horizon, seed schedule, resource profile, and cancellation/checkpoint policy. The opening boundary comes from the resolved baseline or checkpoint. | Economic assumptions and an independent baseline date. |
| `EvidenceSpec` | Select aggregate series, bounded snapshots, traces, and validation evidence to retain. | Changes to model behavior. |
| `ValidationDatasetRef` | Select observations against which outputs are evaluated. | Inputs supplied to the simulation unless a field is separately declared as a scenario driver. |

The experiment specification composes these references without exposing their
internal storage:

```text
ExperimentSpec
|-- BaselineRef
|-- RepresentationSpec
|-- ScenarioSelection
|-- ExecutionSpec
|-- EvidenceSpec
`-- ValidationSelection
```

Illustrative notebook code may eventually read as follows:

```scala
val baseline = AmorFati.baselines.require("pl-2019q4-v1")

val experiment = AmorFati.experiment(
  baseline = baseline.ref,
  representation = Representation.oneTo(1000),
  scenario = ScenarioRef("covid-pl-observed-v1"),
  validation = ValidationDatasetRef("pl-2020-2022-outcomes-v1"),
  seed = Seed(42)
)
```

These names and historical asset IDs are illustrative. Except for the accepted
target identity `pl-2026q2-v1`, they do not claim that a corresponding bundle
currently exists. The accepted API must preserve the separation of concepts
even if its final syntax differs.

## Baseline Catalog

The Research API owns a read-only catalog through which a researcher can:

- list baselines installed for the current Amor Fati release;
- filter by jurisdiction, opening boundary, qualification, or compatibility;
- inspect a baseline without compiling a population or starting a run;
- resolve an exact immutable ID and verify its digest;
- see whether it is retrospective or a real-time information vintage;
- inspect source, transformation, validation, and known-gap summaries; and
- receive a structured incompatibility or integrity error.

Selection is exact. Aliases such as `latest`, `poland`, or `default` may be
offered for interactive discovery, but an experiment must resolve them to an
immutable baseline ID and digest before validation. The resolved identity, not
the alias, enters the run manifest.

Canonical baselines are read-only. Researcher changes are expressed as a
scenario or typed parameter patch and receive their own provenance. A changed
canonical input creates a new baseline version through baseline governance; it
does not mutate an existing bundle in place.

Every catalog entry exposes one qualification status:

| Status | Meaning |
| --- | --- |
| Canonical | Released for scientific use for its declared scope, with completed required reconciliation and validation evidence. Known limitations remain explicit. |
| Experimental | Structurally loadable but not yet qualified for canonical scientific workflows or claims. |
| Legacy | Migration-only provider over an older code-defined or incomplete configuration surface. It is available for compatibility or API ergonomics, not as a canonical baseline. |
| Superseded | An immutable formerly canonical version retained for reproduction after a later version replaced it for new work. |

Qualification is not inferred from a version-shaped ID. Catalog display and
experiment preparation keep the status visible, and canonical notebooks must
not silently select an experimental or legacy baseline.

## Immutable Baseline Bundle

The first-target `pl-2026q2-v1` bundle is the reference-economy input accepted
by RFC-0001. Logically, a bundle contains:

| Component | Required content |
| --- | --- |
| Identity | Immutable baseline ID, jurisdiction, territorial coverage, opening boundary, qualification status, and human-readable description. |
| Compatibility | Baseline schema version, supported model-ontology version or range, population-compiler contract version, and any explicit feature requirements. |
| Parameters | Typed calibrated model parameters with units, domains, and provenance references. |
| Population controls | Reconciled person, household, labor, enterprise, migration, and other controls required by the selected ontology and compiler. |
| Institutional opening state | Named institutions, balance-sheet controls, financial-account totals, classifications, and crosswalks required to initialize the economy. |
| Exogenous baseline assumptions | Explicit no-shock or expected paths that belong to the baseline rather than a research scenario. |
| Provenance | Per-source observation period or date, release, access date, transformation, reconciliation rule, and licensing or redistribution constraint. |
| Validation profile | Compilation invariants, opening reconciliation results, empirical targets, known gaps, and baseline qualification status. |
| Integrity | Canonical file inventory, per-artifact hashes, and one content digest for the resolved bundle. |

Serialization may use different structured encodings for manifests, parameters,
and large control tables. Those encodings must have explicit schemas and
deterministic integrity rules. The public contract does not require one giant
configuration file and must not serialize `SimParams` or target-core storage
objects directly.

The researcher-facing identity remains `pl-2026q2-v1`. Detailed source periods,
valuation dates, access dates, and transformations remain inside its manifest,
as required by RFC-0001. Improving a source bridge or reconciliation for the
same opening quarter produces `pl-2026q2-v2`; it never rewrites v1.

## Resolution and Compilation

Baseline selection and baseline compilation are separate operations:

```text
BaselineRef
    |
    v
BaselineCatalog.resolve
    |  identity, availability, schema, compatibility, digest
    v
VerifiedBaselineBundle
    |
    v
BaselineCompiler
    |  controls, classifications, parameters, opening reconciliation
    v
CompiledBaseline
    |
    v
Population compiler + institutional initialization
    |
    v
PreparedExperiment
```

`VerifiedBaselineBundle` and `CompiledBaseline` are internal handles. They do
not expose filesystem layout, parsed configuration DTOs, `SimParams`, or
mutable population state through the Research API.

Preparation fails before execution when:

- the requested ID is unavailable;
- an artifact or bundle digest does not match;
- a schema or ontology version is unsupported;
- a required component, unit, classification, or crosswalk is absent;
- population or financial controls fail declared reconciliation;
- a scenario is incompatible with the baseline; or
- the requested representation cannot satisfy required strata or weight-one
  units.

The prepared experiment records the exact resolved identities and digests. A
catalog update after preparation cannot change a running experiment.

## Baseline, Scenario, and Patch Boundary

A canonical baseline represents the economy before the research intervention.
Scenarios are applied only after the baseline is resolved and verified.

The scenario contract supports three semantic forms:

| Form | Meaning | Example |
| --- | --- | --- |
| Typed parameter patch | A validated change to a named model parameter relative to the resolved baseline. | Higher capital requirement or alternative behavioral coefficient. |
| Calendar-indexed driver path | A time series of declared exogenous values or policy settings. Every value declares its source vintage or availability stage and its permitted decision-stage binding. | Observed energy-price, tourism-demand, policy-rate, or migration path. |
| Timed intervention | A discrete command applied at a defined pre-decision or month-boundary stage. | Policy introduction, transfer program, or one-off balance-sheet operation. |

Each scenario declares:

- immutable ID, schema version, digest, and provenance;
- classification such as observed historical driver, historical analogue,
  policy counterfactual, or explicit assumption;
- compatible baseline IDs, opening-boundary constraints, or required features;
- typed targets, units, calendar timing, and composition rules;
- source vintage or availability stage and permitted decision-stage binding for
  every calendar-indexed driver value;
- whether values are absolute, relative, additive, or replacing;
- conflict behavior when multiple scenarios affect the same target; and
- validation and expected-channel metadata.

For execution month `T`, a decision may consume a driver only as an inherited
pre-signal or as explicitly permitted same-month information available before
that decision. It must never consume a `T` end-of-month aggregate, a downstream
Stage C outcome, or a value first realized or released after the decision. This
uses the Stage A/B/C timing contract in RFC-0003: realized outcomes become
decision input only at `T + 1` through the next boundary. A run that supplies a
decision with a future realized driver is a conditional forecast, not a genuine
out-of-sample evaluation; the manifest and validation report must state that
classification.

The current registry has removed `ScenarioSpec.params: SimParams`: its typed
`ScenarioPatch` applies only after a compatible baseline resolves, and the
unpatched baseline run has no scenario identity. The internal engine adapter
still materializes that composition into `SimParams`. Persisted scenario
manifests, independently pinned scenario digests, and driver-path loading
remain required before this becomes the target contract.

## Historical Research Modes

The Research API must distinguish three claims that are currently easy to
conflate.

### Historical reconstruction

Run the current model from a historical opening economy using an observed or
curated driver path, then compare with a separate observed-outcome dataset.
Revised historical data may be used. This is a hindcast or reconstruction, not
automatically an out-of-sample forecast. When a path supplies a decision with a
driver that was first available after that decision, the run is a conditional
forecast or reconstruction and must be labelled accordingly.

```text
pl-2019q4-v1
+ covid-pl-observed-v1
+ pl-2020-2022-outcomes-v1
```

### Real-time vintage evaluation

Run from a baseline compiled only from information available at the declared
historical cutoff. Later outcomes remain held out from calibration and scenario
construction. Every driver must have a declared vintage or availability stage
and be usable at its Stage A or permitted Stage B decision point. A future
realized driver may be supplied for a conditional forecast, but disqualifies
the run from a genuine out-of-sample forecasting claim. Only this mode, with
that timing condition satisfied, can support such a claim, subject to the model
and experiment's full evidence.

The researcher still selects one baseline identity. The bundle manifest records
its real-time information policy and source releases instead of exposing a set
of independent cutoff settings in every experiment.

### Counterfactual experiment

Apply an alternative policy, behavioral assumption, or shock to a baseline and
compare it with a matched no-intervention run. Historical resemblance does not
make the scenario an observed historical replay.

Existing `energy-shock`, `tourism-shock`, and other
`HistoricalAnalogue` scenarios remain analogues until they are paired with an
appropriate historical baseline and complete, source-backed driver paths.

Loading `pl-2020q2-v1` would describe an economy at its Q2 2020 opening
boundary. It would not by itself reproduce the preceding COVID shock. A replay
must start at a suitable pre-shock boundary and declare the shock path.

## Driver and Outcome Separation

Historical experiments require a field-level distinction between:

- baseline inputs used to construct the opening economy;
- observed drivers deliberately supplied to the simulation;
- parameter values estimated or calibrated from historical data; and
- outcomes retained only for validation.

A datum cannot silently move between these roles. The run manifest records the
baseline, scenario, and validation dataset digests plus their declared roles.
Validation reports identify any target contaminated by calibration or by use as
an input and must not label it out-of-sample. Driver evidence records the source
vintage or availability stage for every value used at a decision point; a
future realized value makes the run conditional rather than out-of-sample.

The same source dataset may contribute different fields to different roles only
when the bundle manifests record the field-level partition. A single opaque
"historical data" attachment is insufficient evidence.

## Version Compatibility and Reproduction

Baseline identity, baseline schema, model release, model ontology, Research API,
scenario schema, validation dataset, and result-bundle schema are separate
versions.

The current Amor Fati release is not required to load every historical baseline
schema forever. It must instead do one of the following explicitly:

1. load the schema directly under a declared compatibility range;
2. apply a versioned, tested, evidence-producing migration; or
3. reject the bundle and identify a compatible Amor Fati release.

Exact reproduction of an old published run may therefore require the original
Amor Fati release and its pinned baseline bundle. Re-running an old baseline on
a newer model is a new experiment and must receive a new manifest even when the
baseline bytes are identical.

## Initial Implementation Boundary

The first Research API pilot should implement the contract without pretending
that historical bundles already exist:

1. introduce catalog, reference, manifest, integrity, and structured error
   boundaries independent of Almond;
2. expose the existing `2026-04-30` defaults through an accurately named,
   explicitly legacy and non-canonical provider for API ergonomics tests;
3. adapt the current engine by materializing the resolved provider plus an
   optional typed scenario patch into an internal `SimParams`;
4. stop new Research API and notebook code from selecting
   `SimParams.defaults` directly;
5. replace the legacy provider with the real `pl-2026q2-v1` bundle only after
   Q2 compilation, calibration, opening reconciliation, and validation exist;
   and
6. add historical baselines, driver paths, and validation datasets as separate
   evidence-bearing work, not as prerequisites for testing the API shape.

The legacy provider must not be advertised as a canonical scientific baseline.
Its ID and manifest must make its migration-only status visible.

The first two boundaries now exist as the internal `BaselineCatalog` kernel.
It verifies the legacy `SimParams.defaults` payload against a reviewed pinned
digest and model-contract marker before preparation, then resolves it into a
logical `BaselineBundle` with explicit component availability. It has no public
Research API facade, filesystem bundle format, or real Q2 baseline. The
remaining steps above remain required.

## Decision Register

| ID | Decision | Proposed resolution | State |
| --- | --- | --- | --- |
| R-01 | Researcher baseline selection | Select one immutable `BaselineRef`; resolve aliases before experiment validation and record the exact ID and digest. | Proposed for RFC-0002 |
| R-02 | Baseline contents | Use an immutable structured bundle containing parameters, controls, opening state inputs, provenance, validation, compatibility, and integrity metadata. | Proposed for RFC-0002 |
| R-03 | Runtime configuration boundary | Keep `SimParams` internal; compile a verified bundle into the current engine adapter and never expose it as the public or persisted baseline schema. | Proposed for RFC-0002 |
| R-04 | Scenario representation | Store typed patches, calendar driver paths, or timed interventions rather than a full copied baseline configuration. | Proposed for RFC-0002 |
| R-05 | Historical claims | Distinguish reconstruction, real-time vintage evaluation, and counterfactual analysis in experiment and result metadata. | Proposed for RFC-0002 |
| R-06 | Validation leakage | Keep observed outcomes separate and record field-level input, calibration, driver, and validation roles. | Proposed for RFC-0002 |
| R-07 | Compatibility | Direct load, explicit migration, or explicit rejection; never silent baseline reinterpretation. | Proposed for RFC-0002 |
| R-08 | Pilot baseline | Use an accurately named legacy provider until `pl-2026q2-v1` is genuinely compiled and validated. | Proposed for RFC-0002 |
| R-09 | Serialization | Choose schema formats and canonical hashing after the logical DTOs and expected control-table sizes are fixed. | Open physical design |
| R-10 | Distribution | Decide which assets ship with a release and which use a verified artifact cache after size and redistribution constraints are inventoried. | Open operational design |
| R-11 | Custom researcher baselines | Defer import of non-canonical bundles until canonical loading, validation, trust status, and failure evidence are implemented. | Deferred extension |

## Evidence Required to Close This Slice

Before these proposed resolutions enter ADR-0009's accepted Research API
boundary, the design needs:

1. logical DTO sketches for baseline identity, catalog description, bundle
   manifest, scenario metadata, and structured load failures;
2. a current-code mapping showing which `SimParams` fields, empirical source
   tables, initialization inputs, and scenario deltas belong to each bundle
   component;
3. one legacy-provider fixture that proves exact resolution, digest failure,
   compatibility failure, and immutable preparation behavior;
4. one illustrative calendar-indexed historical scenario fixture that proves
   driver/outcome separation without claiming scientific calibration; and
5. a manifest example showing baseline, scenario, validation dataset, model,
   API, and result-schema identities together.

## Implementation Anchors

- [`SimParams.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/config/SimParams.scala)
  for the current code-defined parameter root;
- [`BaselineCatalog.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/config/BaselineCatalog.scala)
  for the current internal catalog, legacy provider, and preparation checks;
- [`ScenarioRegistry.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/config/ScenarioRegistry.scala)
  for current typed scenario patches, compatible-baseline declarations, and
  provenance;
- [`WorldInit.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/init/WorldInit.scala)
  for current initialization inputs and opening-state construction;
- [`CalibrationProvenance.scala`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/config/CalibrationProvenance.scala)
  for current parameter-source and calibration-status evidence;
- [`McRunConfig.scala`](../../modules/montecarlo/src/main/scala/com/boombustgroup/amorfati/montecarlo/core/McRunConfig.scala)
  for current runtime controls that must remain outside the baseline; and
- [RFC-0001 reference-economy contract](0001-population-and-representation.md#reference-economy)
  for accepted baseline identity and source-provenance semantics.
