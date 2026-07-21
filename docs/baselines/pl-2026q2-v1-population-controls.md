# pl-2026q2-v1 Population Controls

## Status

This is the acquisition and acceptance record for the population-control
component of the retrospective `pl-2026q2-v1` baseline. It is not a baseline
bundle, a calibration, or evidence that the target population has been
compiled. No empirical control rows are committed yet.

The component remains blocked until the source extracts below are obtained,
transformed reproducibly, and accepted by `PopulationControlBundleLoader`.
When that happens, the component will be an `Experimental` input to the future
full baseline bundle, not a `Canonical` baseline by itself.

## Fixed Scope

| Field | Value |
| --- | --- |
| Baseline identity | `pl-2026q2-v1` |
| Territory | Poland, usual residents |
| Reference-state boundary | End of 2026-06-30. It is the Q2 closing boundary in source statistics and the opening state for the first simulated month, July 2026. The baseline does not simulate Q2. |
| Compilation mode | Retrospective; source observation periods and release dates may differ from the reference-state boundary and must be recorded per table. |
| Territorial classification | The 16 voivodeships, identified by TERYT codes. This is the common, stable level for the GUS population, household, labour, and employment extracts. It is not the legacy runtime's seven regional markets and is not a proxy for NUTS 2. |
| Production classification | PKD 2025 sections or published aggregates, with every aggregation and later mapping to the target production ontology declared explicitly. A Census 2021 or other legacy PKD 2007 input may inform a structural bridge but must not become an undeclared output classification. The six legacy runtime sectors are not control-table codes. |
| Representation scale | Not a baseline field. The researcher selects it per compilation through `PopulationRepresentation.RepresentationSpec`. |

The final age classification is selected only after confirming the common cells
available in the Q2 BAEL and population extracts. It must retain `0-14`, the
BAEL employment and unemployment boundaries, `75-89`, and `90+`; it cannot be
silently collapsed to the legacy working-age split.

## Source And Transformation Register

Every committed TSV row must have a source-specific entry in `tables.tsv` with
the observation period, release, access date, and transformation. URLs below
identify the acquisition surface; the final record must pin the exact downloaded
file, table, or API query and its SHA-256 hash outside the control-component
digest.

| Output table | Authoritative target | Primary source | Required transformation | Current state |
| --- | --- | --- | --- | --- |
| `persons.tsv` | Usual residents by voivodeship, sex, age band, and private versus collective residence. | GUS population balance / BDL, based on Census 2021; use the release covering the chosen 2026 observation point. | Preserve source territorial and sex-age cells. Derive the private/collective split only from an explicit Census 2021 structural bridge, then reconcile both per-region totals to the 2026 population balance. | Blocked: the definitive 2026-Q2 population structure extract is not yet pinned. |
| `households.tsv` | Private households by voivodeship, size, and composition. | Final 2021 Census household tables. | Use Census 2021 as the structural distribution. Rebase counts only through a documented constrained synthesis that preserves household size, composition, member-capacity, and the `persons.tsv` private-household total for each voivodeship. | Ready for extraction; not yet transformed or reconciled. |
| `household-membership.tsv` | Private-household member positions by composition, role, and age band. | Final 2021 Census household and family tables. | Derive member positions from the same Census version used for `households.tsv`; apply the same constrained synthesis. The output must exactly match household capacity and the private-resident person total. | Ready for extraction; not yet transformed or reconciled. |
| `demographic-labour.tsv` | Usual residents by sex, age band, and BAEL labour status. | GUS BAEL Q2 2026 detailed release. | Retain the BAEL concepts: `NotApplicable` below 15; employed, unemployed, and inactive only in their supported age universe. Map every `90+` person to `non_bael_residual`: a model-specific status outside BAEL denominators that remains in demographic and regional population reconciliation but is excluded from employment assignment. Do not substitute registered unemployment. | Blocked: Q2 2026 detailed BAEL cells are not published as of 2026-07-21. |
| `regional-labour.tsv` | Usual residents by voivodeship and BAEL labour status. | GUS BAEL Q2 2026 regional release. | Reconcile every regional total, including `non_bael_residual`, to `persons.tsv` and national status totals to `demographic-labour.tsv`. Any regional estimate, suppression treatment, or rounding allocation is a declared transformation with a tolerance, never an implicit remainder. | Blocked: Q2 2026 regional BAEL extract is not pinned. |
| `employment.tsv` | Primary employment assignments by worker-residence voivodeship, workplace voivodeship, and PKD 2025 section or declared aggregate. Each assignment represents one employed resident's main job. | GUS Q2 employment data for level and activity; Census 2021 commuting matrix for the residence-to-workplace allocation where no current origin-destination series exists. | Use BAEL employed residents as the person-level target and produce exactly one primary employment assignment for each. Exclude job positions, FTE, vacancies, and secondary jobs from this first component. Apply the Census commuting matrix only as a clearly labelled structural bridge, balanced to current PKD 2025 workplace-sector margins with a reproducible integer procedure. | Blocked: Q2 2026 activity and regional employment inputs are not pinned. |

The source hierarchy is GUS first. Eurostat may be used only as a harmonized
definition or completeness cross-check, not to silently replace a missing Polish
regional table. A source that cannot support its statistical universe or its
declared transformation remains a gap rather than becoming a plausible value.

## Acceptance Gates

The component can be committed only when all conditions hold:

1. The exact raw-source references, retrieval dates, licenses, and SHA-256
   hashes are recorded in the extraction record.
2. All six output tables use the fixed territory and one declared age and
   production classification.
3. `PopulationControlBundleLoader.load` accepts the bundle with no schema,
   integrity, or reconciliation errors.
4. The bundle has zero tolerance for deterministic transforms. Any source
   rounding, disclosure suppression, or balancing residual has a named,
   justified tolerance in the relevant `tables.tsv` row.
5. Household capacity equals private-household member positions, and those
   positions equal the private-resident population. Collective residents remain
   explicit rather than being reassigned to private households.
6. Demographic and regional BAEL margins agree by status, and represented
   employed residents equal primary employment assignments by residence region
   within the declared source tolerance. Both sides count people/jobholders;
   job positions, FTE, vacancies, and secondary jobs are outside this component.
7. The component digest is computed after final TSV generation; changing one
   source, bridge, classification, or control value requires a new baseline
   version rather than editing `pl-2026q2-v1` in place.

## Publication Sequence

1. Obtain and locally archive the six source extracts without committing raw
   source files.
2. Implement a deterministic extraction and balancing command that writes a
   candidate component to a temporary directory and emits its source record.
3. Review the candidate's row-level controls and reconciliation report.
4. Commit only the derived TSV component, its immutable digest, and the
   source/provenance record after review.
5. Register it in a full baseline bundle only after parameters, institutional
   opening state, exogenous assumptions, provenance, and validation profile are
   present and jointly integrity-pinned.

The population compiler starts after this component exists. It must consume the
accepted controls rather than infer a Polish population from legacy
`SimParams.defaults` or the synthetic test fixture.

## Source References

- [GUS population balance and territorial structure, 2025](https://stat.gov.pl/en/topics/population/population/population-size-and-structure-and-vital-statistics-in-poland-by-territorial-division-in-2025-as-of-31-december%2C3%2C39.html)
- [GUS Local Data Bank: population series and methodology](https://bdl.stat.gov.pl/bdl/dane/podgrup/temat/3/7)
- [GUS BAEL and labour-market releases](https://stat.gov.pl/obszary-tematyczne/rynek-pracy/pracujacy-zatrudnieni-wynagrodzenia-koszty.html)
- [GUS PKD 2025 classification and transition rules](https://bip.stat.gov.pl/dzialalnosc-statystyki-publicznej/rejestr-regon/pkd-2025/)
- [GUS final Census 2021 results, including household and commuting tables](https://stat.gov.pl/spisy-powszechne/nsp-2021/nsp-2021-wyniki-ostateczne/)
- [GUS Census 2021 household-size and composition tables](https://stat.gov.pl/spisy-powszechne/nsp-2021/nsp-2021-wyniki-ostateczne/gospodarstwa-domowe-oraz-rodziny-wedlug-rejonow-statystycznych-i-obwodow-spisowych%2C13%2C1.html)
