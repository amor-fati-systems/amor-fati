# PL-2025-Q4-v1 Enterprise Controls

## Status

This is the acquisition and acceptance record for the enterprise-control
component of the first planned Polish reference economy, `PL-2025-Q4-v1`.
It is not a baseline bundle, calibration, or evidence that Amor Fati has
compiled Polish enterprises. No empirical enterprise-control artifact is
committed.

The component remains planned until a Q4 source is integrity-pinned, a
deterministic transformation produces a data-only component, and
`EnterpriseControlBundleLoader` accepts its reconciliation evidence. It may
then become an `Experimental` input to a full baseline; it is not a
`Canonical` baseline by itself.

## Fixed Scope

| Field | Value |
| --- | --- |
| Baseline identity | `PL-2025-Q4-v1` |
| Reference-state boundary | End of `2025-12-31`, the Q4 closing boundary in source statistics and the opening state for the first simulated month, January 2026. |
| Target unit | A register-declared-operating entity represented as one model enterprise. It is a legal entity or organisational unit, not an establishment or local unit. The registry universe must state its agricultural exclusions explicitly. |
| Enterprise geography | The 16 TERYT voivodeships. Registry counts use registered-seat geography; that is not workplace geography and cannot substitute for population employment controls. |
| Production classification | Preserve the source PKD classification and version. Every mapping to the target production ontology is an explicit, versioned crosswalk. |
| Size classification | A REGON expected-number-of-workers band is a registry attribute, not realised employment, FTE, vacancies, or BAEL jobholders. |
| Financial and systemic institutions | Financial and insurance activities and named institutions remain outside ordinary-enterprise controls until separately declared. Size, ownership label, or sector never infer systemic status. |
| Representation scale | Not a baseline field. The compiler retains each nonzero hard-control stratum under `PopulationRepresentation.RepresentationSpec`. |

The first target represents an enterprise as one production unit in one
registered-seat region. A later extension may introduce local units or
multi-region enterprise relationships, but may not relabel a registered seat
as a workplace observation.

## Source And Transformation Register

Every committed table must record its exact source file, observation period,
release, access date, licence, and SHA-256 hash. The entries below are source
requirements, not pins.

| Output control | Authoritative target | Primary source | Required transformation | Current state |
| --- | --- | --- | --- | --- |
| Enterprise stock and size stratum | Register-declared-operating entities by registered-seat TERYT voivodeship, source PKD section, and expected-workers band. | GUS quarterly REGON workbook for the Q4 2025 state. | Preserve the source universe, exclusions, bands, and explicit missing-region or missing-PKD margins. If margins overlap, partition them with a checked inclusion-exclusion calculation before reconciliation. A PKD-to-target-sector crosswalk is a separate artifact. | Planned: no source is pinned. |
| Legal-form margin | Entity counts by published source legal-form classification. | The same Q4 REGON release or separately pinned source. | Preserve source codes and unknown category. Map to ownership or governance only through a named bridge. | Planned: no source is pinned. |
| Ownership margin | Entity counts by published source ownership classification. | The same Q4 REGON release or separately pinned detailed ownership source. | Preserve reported classes and missing values. This is registry ownership, not a cap table or beneficial-ownership proof. | Planned: no source is pinned. |
| Workforce bridge | A declared relation from population primary-employment assignments to enterprise strata. | The [population employment control](pl-2025-q4-v1-population-controls.md) plus a separately reviewed structural source where needed. | Keep BAEL jobholders, workplace-region employment, and REGON expected workers distinct. Reconcile only through an explicit bridge that records source, target measure, residual, and tolerance. | Planned: no employment component or workforce bridge is pinned. |
| Agricultural coverage bridge | Production units outside the selected registry universe. | GUS agricultural statistics or a separately reviewed agricultural-register source. | Add only a declared agricultural bridge; do not inflate registry counts or draw an unexplained residual from legacy initialization. | Planned: source and target representation are not selected. |

GUS registry records can lack activity, location, ownership, or
expected-worker information. Such records must remain explicit unknown or
residual categories; they cannot be discarded to force margins to sum.

## Measurement Boundaries

`enterprise-count` and `employment` are different quantities:

- REGON's expected-number-of-workers band is a registry classification;
- population `employment.tsv` counts one primary assignment for each employed
  usual resident by residence, workplace, and production sector;
- job positions, FTE, vacancies, secondary jobs, and workers outside the
  usual-resident population require separately declared measures; and
- registered seat and workplace are distinct geographic concepts.

The component has hard registry-count controls only where the source supports
them. Size, legal-form, and ownership are calibrated margins unless a
source-backed joint table supports a stricter reconciliation. A workforce
bridge is validation evidence, not permission to rewrite either source measure.

## Acceptance Gates

1. Raw-source references, retrieval dates, licences, and hashes are recorded
   for every source and bridge.
2. Every enterprise-count row identifies its statistical universe,
   registered-seat territory, source PKD version, and expected-worker band.
3. The 16 TERYT voivodeships are the common registry axis; workplace controls
   are explicitly labelled and never silently merged with registered-seat data.
4. Missing region and activity margins remain explicit, quantified,
   non-overlapping residual cells.
5. Every source PKD section is mapped exactly once to an ordinary production
   target, named institutional component, or explicit exclusion.
6. No expected-worker band is treated as actual employment. Any workforce
   allocation records its two measures, bridge source, residual, and tolerance.
7. A named systemic enterprise has an evidence-backed baseline declaration.
8. The component digest is computed after final TSV generation. Changing a
   source, classification, crosswalk, bridge, or control value creates a new
   baseline version rather than editing `PL-2025-Q4-v1` in place.

## Publication Sequence

1. Acquire and locally archive Q4 source files without committing raw files.
2. Record source metadata and classify every missing source dimension before
   producing derived controls.
3. Implement deterministic extraction that verifies raw hashes and emits the
   data-only schema with its digest and validations.
4. Review source-native component residuals and per-band reconciliation.
5. Commit only derived TSVs, immutable component digest, and provenance after
   review. A sector crosswalk remains a separate artifact.
6. Combine the component with accepted population controls only through the
   declared workforce bridge, then register both alongside parameters,
   institutions, and validation evidence.

Until then, `FirmInit` sampling remains legacy migration evidence. It must not
be presented as a calibrated enterprise population.

## Source References

- [GUS quarterly REGON information](https://stat.gov.pl/obszary-tematyczne/podmioty-gospodarcze-wyniki-finansowe/zmiany-strukturalne-grup-podmiotow/kwartalna-informacja-o-podmiotach-gospodarki-narodowej-w-rejestrze-regon/)
- [GUS Local Data Bank: REGON entities by legal form](https://bdl.stat.gov.pl/bdl/dane/podgrup/temat/25/203/3256?lang=en)
- [GUS annual REGON structural changes](https://stat.gov.pl/en/topics/economic-activities-finances/structural-changes-of-groups-of-entities-of-the-national-economy/)
