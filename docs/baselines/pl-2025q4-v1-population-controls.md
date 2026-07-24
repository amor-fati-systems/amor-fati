# PL-2025-Q4-v1 Population Controls

## Status

This is the acquisition and acceptance record for the population-control
component of the first planned Polish reference economy, `PL-2025-Q4-v1`.
It is not a baseline bundle, calibration, or evidence that a Polish population
has been compiled. No empirical control rows are committed yet.

The component remains planned until the source extracts below are acquired,
transformed deterministically, and accepted by `PopulationControlBundleLoader`.
It can then become an `Experimental` input to a future full baseline bundle;
the component alone is not a `Canonical` baseline.

## Fixed Scope

| Field | Value |
| --- | --- |
| Baseline identity | `PL-2025-Q4-v1` |
| Territory | Poland, usual residents |
| Reference-state boundary | End of `2025-12-31`, the Q4 closing boundary in source statistics and the opening state for the first simulated month, January 2026. The baseline does not simulate Q4. |
| Compilation mode | Retrospective. Each table records its actual observation period, release, access date, and transformation; an older structural source is never relabelled as a Q4 observation. |
| Territorial classification | The 16 TERYT voivodeships. This is the common axis for person, household, labour, and employment reconciliation; it is not the legacy runtime's seven regional markets or a proxy for NUTS 2. |
| Production classification | Preserve the source classification and declare every aggregation and mapping to the target production ontology. The six legacy runtime sectors are not control-table codes. |
| Representation scale | Not a baseline field. A researcher selects it per compilation through `PopulationRepresentation.RepresentationSpec`. |

The final age classification must retain `0-14`, the BAEL employment and
unemployment boundaries, `75-89`, and `90+`; it cannot be collapsed into the
legacy working-age split.

## Source And Transformation Register

Every committed TSV row requires a source-specific `tables.tsv` entry with its
observation period, release, access date, and transformation. The sources below
are acquisition targets, not pins: the exact downloaded file, table, or API
query and its SHA-256 must be recorded before an artifact is accepted.

| Output table | Authoritative target | Primary source | Required transformation | Current state |
| --- | --- | --- | --- | --- |
| `persons.tsv` | Usual residents by voivodeship, sex, age band, and private versus collective residence. | GUS population balance / BDL for `2025-12-31`. | Preserve source territorial and sex-age cells. Derive the private/collective split only through an explicit Census 2021 structural bridge, then reconcile both per-region totals to the Q4 population balance. | Planned: Q4 source extract is not pinned. |
| `households.tsv` | Private households by voivodeship, size, and composition. | Final Census 2021 household tables. | Use Census 2021 only as a structural prior. Rebase counts through documented constrained synthesis that preserves household size, composition, member capacity, and the Q4 private-household total for each voivodeship. | Planned: source and synthesis contract are not pinned. |
| `household-membership.tsv` | Private-household member positions by composition, role, and age band. | Final Census 2021 household and family tables. | Derive member positions from the same Census version as `households.tsv`; apply the same constrained synthesis. The output must match household capacity and the private-resident total. | Planned: source and synthesis contract are not pinned. |
| `demographic-labour.tsv` | Usual residents by sex, age band, and BAEL labour status. | GUS BAEL Q4 2025 detailed release. | Retain BAEL concepts: `NotApplicable` below 15; employed, unemployed, and inactive only in their supported age universe. Map every `90+` person to `non_bael_residual`, outside BAEL denominators but inside demographic and regional reconciliation. Do not substitute registered unemployment. | Planned: Q4 extract is not pinned. |
| `regional-labour.tsv` | Usual residents by voivodeship and BAEL labour status. | GUS BAEL Q4 2025 regional release or a documented compatible bridge. | Reconcile every regional total, including `non_bael_residual`, to `persons.tsv` and national status totals to `demographic-labour.tsv`. Any estimate, suppression treatment, or rounding allocation is a declared transformation with a tolerance. | Planned: source and regional bridge are not pinned. |
| `employment.tsv` | Primary employment assignments by worker-residence voivodeship, workplace voivodeship, and declared production sector. | GUS Q4 2025 employment data for level and activity; a separately reviewed structural commuting bridge only if current origin-destination evidence is unavailable. | Produce exactly one primary assignment for each represented employed resident. Exclude job positions, FTE, vacancies, and secondary jobs. A historical commuting matrix is a labelled structural bridge, not a current observation. | Planned: sources and bridge are not pinned. |

GUS is the primary source. Eurostat may provide definitions or completeness
cross-checks, but cannot silently replace a missing Polish regional table. A
source that does not support its declared universe remains an explicit gap.

## Acceptance Gates

The component can be committed only when all conditions hold:

1. Exact raw-source references, retrieval dates, licences, and SHA-256 hashes
   are recorded in the extraction record.
2. All six output tables use the fixed territory and one declared age and
   production classification.
3. `PopulationControlBundleLoader.load` accepts the bundle with no schema,
   integrity, or reconciliation errors.
4. Deterministic transforms have zero tolerance. Source rounding, disclosure
   suppression, or balancing residuals have a named, justified tolerance in
   the relevant `tables.tsv` row.
5. Household capacity equals private-household member positions, and those
   positions equal the private-resident population. Collective residents remain
   explicit rather than being reassigned to private households.
6. Demographic and regional labour-status margins agree by status, including
   `NonBaelResidual`. Represented employed residents equal primary employment
   assignments by residence region within the declared source tolerance. Both
   sides count people or jobholders, not FTE, vacancies, or job positions.
7. The component digest is computed after final TSV generation. Changing a
   source, bridge, classification, or control value creates a new baseline
   version rather than editing `PL-2025-Q4-v1` in place.

## Publication Sequence

1. Acquire and locally archive source extracts without committing raw files.
2. Implement deterministic extraction and balancing that write a candidate
   component to a temporary directory with its source record.
3. Review row-level controls and the reconciliation report.
4. Commit only the derived TSV component, immutable digest, and provenance
   record after review.
5. Register it in a full baseline only after parameters, institutional opening
   state, exogenous assumptions, provenance, and validation profile are jointly
   integrity-pinned.

The population compiler starts after this component exists. It must consume
accepted controls, not infer a Polish population from legacy `SimParams.defaults`
or the synthetic test fixture.

## Source References

- [GUS population balance and territorial structure, 2025](https://stat.gov.pl/en/topics/population/population/population-size-and-structure-and-vital-statistics-in-poland-by-territorial-division-in-2025-as-of-31-december%2C3%2C39.html)
- [GUS Local Data Bank: population series and methodology](https://bdl.stat.gov.pl/bdl/dane/podgrup/temat/3/7)
- [GUS BAEL and labour-market releases](https://stat.gov.pl/obszary-tematyczne/rynek-pracy/pracujacy-zatrudnieni-wynagrodzenia-koszty.html)
- [GUS PKD 2025 classification and transition rules](https://bip.stat.gov.pl/dzialalnosc-statystyki-publicznej/rejestr-regon/pkd-2025/)
- [GUS final Census 2021 results, including household and commuting tables](https://stat.gov.pl/spisy-powszechne/nsp-2021/nsp-2021-wyniki-ostateczne/)
