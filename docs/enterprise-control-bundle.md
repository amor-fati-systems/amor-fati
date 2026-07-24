# Enterprise-Control Bundle Format

## Status And Scope

`EnterpriseControlBundleLoader` reads a data-only registry-control component
from UTF-8 TSV files. It constructs the typed
`EnterpriseControlSchema.Bundle`, verifies the component digest from one
immutable byte snapshot, and checks that ordinary strata plus explicit source
residuals preserve each published national total by expected-workers band.

This is not a full `BaselineBundle`, an XLSX extractor, a firm compiler, a
public Research API, or a workforce bridge. It does not load `SimParams`,
execute Scala configuration, initialize runtime firms, or treat a REGON
expected-workers band as actual employment. The committed `synthetic-v1`
fixture is a test artifact only; it is not an empirical baseline.

The target evidence and remaining enterprise work are defined by the
[PL-2025-Q4-v1 enterprise-control acquisition record](baselines/pl-2025q4-v1-enterprise-controls.md)
and [RFC-0001](rfc/0001-population-and-representation.md). The relationship to
the future full baseline bundle and Research API is defined by
[RFC-0002's contract](rfc/0002-research-api-contract.md).

## Bundle Layout

Each component root must provide these files:

```text
manifest.tsv
registered-seat-regions.tsv
pkd2007-sections.tsv
expected-workers-bands.tsv
enterprise-strata.tsv
source-residuals.tsv
source-totals.tsv
```

All files are UTF-8, tab-separated, and include one header row. Header names
and table values are part of the schema. Malformed UTF-8, unknown
classifications, duplicate cells, invalid counts, or a failed reconciliation
reject the component. The current loader ignores additional root entries; the
future full baseline bundle will own its complete artifact inventory.

## Manifest And Integrity

`manifest.tsv` has exactly one data row:

| Column | Meaning |
| --- | --- |
| `schema_version` | Must equal the supported `EnterpriseControlSchema` version. |
| `baseline_id` | Immutable identity of the baseline that will contain this component. |
| `enterprise_controls_digest` | Canonical SHA-256 digest for this component. |
| `registered_seat_region_classification_id`, `registered_seat_region_classification_version` | Registered-seat territorial classification identity. |
| `pkd2007_section_classification_id`, `pkd2007_section_classification_version` | Source PKD 2007 section classification identity. |
| `expected_workers_band_classification_id`, `expected_workers_band_classification_version` | REGON expected-workers band classification identity. |
| `source_provider`, `source_location`, `source_sha256` | Producer, stable location, and SHA-256 of the raw source artifact. |
| `source_observation_period`, `source_release`, `source_accessed_at` | Source period, release label, and ISO-8601 retrieval date. |

The component digest covers the schema version, baseline ID, all declared
classification references, all declared source-artifact fields, and the raw
bytes of every required non-manifest TSV. The digest field itself is excluded,
so it does not self-reference. A changed source declaration, classification, or
derived row therefore requires a newly computed digest and a new immutable
baseline version under baseline governance.

The component digest proves the integrity of the normalized payload relative to
the declared raw source hash. It does not itself fetch or verify the raw XLSX.
The deterministic extraction stage must verify that raw hash before it writes
the normalized TSVs. A future full baseline digest must additionally cover
parameters, opening institutional state, provenance, validation evidence, and
its complete artifact inventory.

## Classifications And Tables

The classification files declare the only codes accepted by dependent tables:

| File | Required columns | Unit and meaning |
| --- | --- | --- |
| `registered-seat-regions.tsv` | `code` | Registered-seat territorial code. For the Polish target, this will be a TERYT voivodeship code. |
| `pkd2007-sections.tsv` | `code` | PKD 2007 section code, `A` through `U`. |
| `expected-workers-bands.tsv` | `code` | Published REGON expected-number-of-workers band. It is a label, not a numeric workforce estimate. |
| `enterprise-strata.tsv` | `registered_seat_region`, `pkd2007_section`, `expected_workers_band`, `count` | Count of ordinary source cells with all three dimensions known. |
| `source-residuals.tsv` | `source_residual_category`, `registered_seat_region`, `pkd2007_section`, `expected_workers_band`, `count` | Count of an explicit source residual family with at least one of registered seat or PKD section missing. |
| `source-totals.tsv` | `expected_workers_band`, `count` | Published national total by expected-workers band before the source cells are partitioned. |

`count` is a non-negative `Long` count of registry entities. It is neither a
simulated-enterprise count nor a representation weight.

An `enterprise-strata.tsv` row must name declared region, PKD section, and
expected-workers band. A `source-residuals.tsv` row must leave at least one of
`registered_seat_region` and `pkd2007_section` empty. Its non-empty
`source_residual_category` identifies either a separately published source
family or a declared partition of published residual margins; it is not an
ordinary axis classification or an inference from absent normal rows. The
other axis remains populated whenever the source reports it. When both axes
are absent, the category keeps distinct source residual families or partition
cells from collapsing into one row. A derived partition must be documented in
the source recipe and preserve the published total through a checked
reconciliation.

The loader requires exact per-band reconciliation:

```text
source total = sum(enterprise strata) + sum(source residual cells)
```

Residual cells cannot be sampled as an ordinary registered-seat geography or
production activity. Any handling in a future compiler must be declared and
validated rather than silently dropped.

## Measurement Boundary

The first target component preserves the selected source table's registered-seat
voivodeship, PKD section, and expected-workers-band dimensions. It does not
establish actual employment, FTE, job positions, vacancies, ownership, legal
form, a PKD-to-Amor-Fati-sector crosswalk, financial institutions, or systemic
enterprises.

In particular, population `employment.tsv` counts primary employment
assignments for employed usual residents, while REGON expected-workers bands
are registry classifications. A reproducible workforce bridge with its source,
target measures, residual, and tolerance is required before either measure can
constrain the other.

## Current Use

The format is exercised by the synthetic fixture in
`modules/model/src/test/resources/enterprise-control-bundles/synthetic-v1` and
its schema and loader specifications. No empirical source-native component is
committed yet. When one exists, it is generated and versioned in
[`amor-fati-economies`](https://github.com/amor-fati-systems/amor-fati-economies)
while the raw source remains outside both repositories. This is an internal
target-core boundary, deliberately separate from the legacy `BaselineCatalog`
provider over `SimParams.defaults`.

The next implementation steps are a reviewed PKD crosswalk, remaining
enterprise dimensions, and an explicit workforce bridge. The source-native
component must not be treated as a full baseline compilation until those
separate artifacts are accepted.
