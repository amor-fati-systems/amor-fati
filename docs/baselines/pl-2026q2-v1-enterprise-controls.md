# pl-2026q2-v1 Enterprise Controls

## Status

This is the acquisition and acceptance record for the enterprise-control
component of the retrospective `pl-2026q2-v1` baseline. It is not a baseline
bundle, a calibration, or evidence that Amor Fati has compiled Polish
enterprises. No empirical control rows are committed yet.

The component remains blocked until the source extracts below are obtained,
transformed reproducibly, and accepted by its future data-only loader. It will
be an `Experimental` input to the future full baseline bundle, not a
`Canonical` baseline by itself.

It complements the [population-control component](pl-2026q2-v1-population-controls.md).
The two components have different statistical units and source cadences, so
they must not be merged into one artificial table.

## Fixed Scope

| Field | Value |
| --- | --- |
| Baseline identity | `pl-2026q2-v1` |
| Reference-state boundary | End of 2026-06-30: the Q2 closing boundary in source statistics and the opening state for the first simulated month, July 2026. The baseline does not simulate Q2. |
| Target unit | A register-declared-operating entity is the first control unit and is represented as one modeled enterprise. It is a legal entity or organisational unit, not an establishment or local unit. The REGON universe excludes persons operating only individual agricultural holdings; agriculture therefore needs an explicit supplementary bridge rather than an invented registry count. |
| Enterprise geography | The 16 voivodeships identified by TERYT codes. Registry enterprise counts use the registered-seat voivodeship. This is not a workplace geography and cannot be silently substituted for the workplace axis of population employment controls. |
| Production classification | Preserve the source PKD classification and version. The current quarterly REGON release is published in PKD 2007, so any transition to PKD 2025 and then to Amor Fati production sectors must be an explicit, versioned crosswalk. Do not label PKD 2007 rows as PKD 2025. |
| Size classification | The REGON declared or expected-number-of-workers bands are registry attributes, not realised employment, FTE, vacancies, or BAEL jobholders. |
| Financial and systemic institutions | Financial and insurance activities and named institutions remain outside the ordinary-enterprise component. A named non-bank enterprise may receive weight-one treatment only through an explicit, evidence-backed baseline declaration; neither size nor a REGON form infers systemic status. |
| Representation scale | Not a baseline field. The researcher selects ordinary-enterprise representation through `PopulationRepresentation.RepresentationSpec`; the compiler must retain every nonzero hard-control stratum. |

The first target represents an enterprise as one production unit in one
registered-seat region. A later extension may introduce local units or
multi-region enterprise relationships, but it must not relabel a registered
seat as a workplace observation in the interim.

## Source And Transformation Register

Every committed control table must record its exact source file, observation
period, release, access date, licence, and SHA-256 hash. The sources below name
the acquisition surface only; they are not yet pinned source artifacts.

| Output control | Authoritative target | Primary source | Required transformation | Current state |
| --- | --- | --- | --- | --- |
| Enterprise stock | Register-declared-operating entities by registered-seat TERYT voivodeship and source PKD section or declared aggregate. | GUS quarterly REGON tables for the 30 June 2026 state. | Preserve the REGON universe, including its stated exclusions. Classify every source section through a versioned PKD-to-target-production crosswalk; route sections outside the ordinary production perimeter to their declared institutional component rather than dropping them. | Blocked: the Q2 2026 quarterly REGON extract is not pinned. |
| Enterprise size margin | Entity counts by source activity and REGON expected-number-of-workers band, with the source's missing-value category retained. | The same quarterly REGON tables. | Retain source bands and missing attributes. A band midpoint or fabricated worker total is forbidden. A later compiler may use the margin only with a declared allocation rule. | Blocked: the Q2 2026 quarterly REGON extract is not pinned. |
| Legal-form margin | Entity counts by source legal-form classification where published. | The same quarterly REGON tables. | Preserve the source legal-form code and unknown category. Map it to a model ownership or governance feature only through a named bridge, never by a default Boolean draw. | Blocked: the Q2 2026 quarterly REGON extract is not pinned. |
| Ownership margin | Entity counts by source ownership-form classification where published. | The same quarterly REGON tables. | Preserve all reported ownership classes and the source missing-value category. This is a registry ownership classification, not a cap table and not proof of a beneficial owner. | Blocked: the Q2 2026 quarterly REGON extract is not pinned. |
| Workforce bridge | A declared relation from primary employment assignments in the population component to enterprise strata. | The [population employment control](pl-2026q2-v1-population-controls.md) plus an evidence-backed structural source where needed. | Keep BAEL jobholders, workplace-region employment, and REGON expected workers as distinct measures. Reconcile only through an explicit bridge that records its source, target measure, residual, and tolerance. | Blocked: no Q2 employment component and no reviewed firm-workforce bridge are pinned. |
| Agricultural coverage bridge | Production units absent from the REGON target universe because they are persons operating only individual agricultural holdings. | GUS agricultural statistics or a separately reviewed agricultural register source. | Add only a declared agricultural bridge; do not inflate REGON enterprise counts or use a residual drawn from `FirmInit`. | Blocked: source and target representation are not yet selected. |

The Q1 2026 quarterly REGON publication confirms the required source dimensions
but is not a substitute for Q2: it reports entities declaring activity by PKD
2007, voivodeship, selected legal form, ownership form, and expected-number-of-
workers bands. It also warns that registry records can lack activity, location,
ownership, or expected-worker information. Such records must remain explicit
unknown or residual categories in this component.

Annual GUS structural-business and non-financial-enterprise releases may inform
lagged size, employment, financial, or foreign-capital bridges. They cannot be
silently treated as 2026-Q2 stock controls. Each use is a separately declared
transformation with its actual observation period and release.

## Measurement Boundaries

`enterprise-count` and `employment` are not interchangeable quantities:

- REGON's expected-number-of-workers band is a registry classification, not a
  realised number of workers;
- population `employment.tsv` counts one primary assignment for each employed
  usual resident, by residence, workplace, and production sector;
- job positions, FTE, vacancies, secondary jobs, and workers who are not usual
  residents require their own declared measures; and
- enterprise registered seat and employment workplace are distinct geographic
  concepts, particularly for multi-location organisations.

Consequently, the first component has hard registry-count controls only where
the source supports them. Size, legal-form, and ownership controls are
calibrated margins unless a source-backed joint table supports a stricter
reconciliation. The workforce bridge is validation evidence, not permission to
rewrite either source measure until its reconciliation rule is accepted.

## Acceptance Gates

The component can be committed only when all conditions hold:

1. Exact raw-source references, retrieval dates, licences, and SHA-256 hashes
   are recorded for every table and bridge.
2. Every enterprise-count row identifies the source statistical universe,
   registered-seat territory, PKD version, and classification crosswalk.
3. The 16 TERYT voivodeships are the common regional axis for registry controls;
   a workplace control is labelled as such and never merged with registered-seat
   counts by an implicit assumption.
4. Missing REGON activity, region, ownership, legal-form, and expected-worker
   attributes remain represented by declared categories or a quantified,
   justified residual. They are not discarded to make margins sum.
5. Every source PKD section is mapped exactly once to an ordinary production
   target, a named institutional component, or an explicit exclusion. The
   mapping records whether it is direct, residual, or a bridge assumption.
6. No source expected-worker band is treated as realised employment, and no
   primary-employment-assignment total is forced to equal it. Any workforce
   allocation records the two measures, bridge source, residual, and tolerance.
7. A named systemic enterprise has a baseline declaration and evidence. No
   heuristic based on size, ownership label, or sector can promote one.
8. The component digest is computed after final TSV generation. Changing a
   source, classification, crosswalk, bridge, or control value creates a new
   baseline version rather than editing `pl-2026q2-v1` in place.

## Publication Sequence

1. Obtain and locally archive the Q2 quarterly REGON tables and any selected
   agricultural or structural-business source without committing raw files.
2. Record exact source metadata and classify every missing or unsupported
   source dimension before producing derived controls.
3. Implement the data-only enterprise-control schema and loader around the
   accepted source tables, including its component digest and validations.
4. Generate a candidate component with a deterministic extraction and
   crosswalk command; review row-level controls and all residuals.
5. Commit only derived TSV data, immutable component digest, crosswalk, and
   provenance record after review.
6. Combine it with the accepted population component only through the declared
   workforce bridge, then register both in a full baseline bundle alongside
   parameters, institutions, and validation evidence.

Until this component is accepted, the existing `FirmInit` sampling remains
legacy migration evidence. It must not be relabelled as a calibrated enterprise
population for `pl-2026q2-v1`.

## Source References

- [GUS quarterly REGON information, 2026](https://stat.gov.pl/obszary-tematyczne/podmioty-gospodarcze-wyniki-finansowe/zmiany-strukturalne-grup-podmiotow/kwartalna-informacja-o-podmiotach-gospodarki-narodowej-w-rejestrze-regon-rok-2026%2C7%2C16.html)
- [GUS annual REGON structural changes, 2025](https://stat.gov.pl/en/topics/economic-activities-finances/structural-changes-of-groups-of-entities-of-the-national-economy/structural-changes-of-groups-of-the-national-economy-entities-in-the-regon-register-2025%2C2%2C10.html)
- [GUS Local Data Bank: REGON entities by legal form](https://bdl.stat.gov.pl/bdl/dane/podgrup/temat/25/203/3256?lang=en)
- [GUS financial results of non-financial enterprises, 2024](https://stat.gov.pl/en/topics/economic-activities-finances/activity-of-enterprises-activity-of-companies/financial-results-of-non-financial-enterprises-in-2024-balance-sheet%2C3%2C19.html)
- [GUS activity of enterprises with up to 9 persons employed, 2023](https://stat.gov.pl/files/gfx/portalinformacyjny/en/defaultaktualnosci/3317/8/11/1/activity_of_enterprises_with_up_to_9_persons_employed_in_2023.pdf)
