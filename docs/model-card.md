# Amor Fati Model Card

## Model Identity

| Field | Value |
| --- | --- |
| Model | Amor Fati |
| Model class | Stock-flow consistent agent-based macroeconomic model (SFC-ABM) |
| Baseline | Poland, model-start data boundary 2026-04-30 |
| Frequency | Monthly |
| Development status | Research prototype; not an institutional production model |
| License | Apache License 2.0 for repository source code |
| Versioning | Unreleased development version; identify results by Git commit and run manifest |

Amor Fati is an executable model of the Polish economy with heterogeneous
households and firms, a ten-row banking-sector archetype system, public and
monetary institutions, an external sector, insurance, non-bank finance, and
quasi-fiscal vehicles. Its defining engineering constraint is that monetary
flows execute through a ledger and must satisfy explicit accounting contracts.

## Intended Use

Amor Fati is intended for:

- research on internally consistent macro-financial mechanisms;
- exploratory counterfactual and stress-scenario analysis;
- examination of heterogeneous household, firm, and bank outcomes;
- development and comparison of SFC-ABM mechanisms;
- reproducible computational experiments with explicit parameters and seeds;
- teaching and review of ledger-first macroeconomic simulation.

The model is most useful as a transparent research testbed: a place to state a
mechanism, execute it, inspect its balance-sheet consequences, and test whether
the resulting path is robust to seeds and parameter assumptions.

## Uses Outside Scope

Amor Fati must not be presented as:

- a point-forecasting system for GDP, inflation, unemployment, asset prices, or
  fiscal variables;
- a source of causal estimates without an external identification strategy;
- a welfare model that identifies an optimal or recommended policy;
- a substitute for official statistics, supervisory models, or institutional
  model-risk governance;
- a validated long-horizon projection model;
- a cross-country model or a calibrated representation of a country other than
  Poland;
- evidence that a policy will produce the simulated point estimate in the real
  economy.

Scenario output should be described as an internally consistent conditional
path under stated assumptions, not as a prediction.

## Implemented Scope

The default configuration contains 10,000 heterogeneous firms, a household
population derived from the firm-employment scale, six input-output-linked
production sectors, and ten banking-sector archetype rows. It represents public
and monetary institutions, housing and credit, financial markets and non-bank
finance, quasi-fiscal channels, and an external sector. Initialization and
monthly decisions use explicit random-stream contracts, and named scenarios can
be run over multiple Monte Carlo seeds.

The [model specification](model-specification.md) and
[ODD/ODD+D documentation](odd-model-documentation.md) define the authoritative
scientific scope and execution schedule.

## Accounting And Correctness Claims

The strongest claims made by the project concern execution correctness, not
empirical truth:

- monetary batches must conserve value through the runtime ledger;
- the engine checks 15 explicit SFC accounting identities;
- fixed-point domain numerics are used for accounting-critical quantities;
- identical parameters, boundary state, and seed contract reproduce the same
  transition;
- committed generated accounting artifacts are guarded against unreviewed
  drift.

The separately maintained `amor-fati-ledger` kernel is checked with Stainless
and Z3. This formal-verification boundary does **not** extend to all behavioral
equations, calibration choices, or empirical claims in Amor Fati. A balanced
ledger proves internal accounting consistency; it does not prove that the
modeled economy is behaviorally or empirically correct.

See [SFC matrix evidence](sfc-matrix-evidence.md),
[engine invariants and semantics](engine-invariants-and-semantics.md), and the
[ledger verification record](https://github.com/boombustgroup/amor-fati-ledger/blob/main/docs/verification.md).

## Calibration And Data

The active parameter surface is a Poland baseline with a 2026-04-30 model-start
boundary. Parameter provenance is typed in code and rendered into the
[calibration register](calibration-register.md). External source selection,
vintages, transformations, and remaining bridges are documented separately
from model mechanics.

The generated register currently contains 37 direct empirical rows, 19
empirically transformed rows, 62 rows with source information still held at
code-note level, 39 assumptions, 88 tuned rows needing validation, seven policy
scenario controls, and one explicit placeholder. These labels describe
provenance readiness, not statistical uncertainty or independent validation.

Raw external data are not automatically redistributable under the repository's
Apache license. Users remain responsible for the terms of the cited source
datasets.

## Empirical Validation Status

Accounting validation and empirical validation are separate. The committed
empirical-validation bundle records a 60-month, five-seed baseline run from
commit `1187b057`. It currently contains 67 target rows:

| Result status | Count | Meaning |
| --- | ---: | --- |
| `PASS_BASELINE` | 9 | Model value meets the documented baseline criterion |
| `PARTIAL` | 52 | Output exists, but source, mapping, normalization, or criterion remains incomplete |
| `MISSING_DATA_BRIDGE` | 6 | Model output exists, but a publication-grade empirical bridge is missing |

`PARTIAL` is not a failed model result, and `PASS_BASELINE` is not evidence of
forecasting power. The snapshot is a visible evidence inventory whose run
commit may differ from the repository's current `HEAD`. Any publication must
cite the exact model-run manifest and regenerate or explicitly retain the
relevant snapshot.

See the [empirical validation report](empirical-validation-report.md) and
[snapshot manifest](empirical-validation/model-run-manifest.tsv).

## Known Limitations

Material current limitations include:

- incomplete empirical bridges and validation evidence for many behavioral
  parameters;
- the model has not yet undergone time-split or rolling-origin historical
  validation on observations excluded from calibration; consequently, its
  predictive performance and the empirical reliability of simulated magnitudes
  have not been established;
- no completed independent external reproduction of a reference experiment;
- although parameter provenance is registered, the project does not yet define
  a formal approval, freezing, and versioning policy for canonical calibration
  baselines and their underlying data vintages;
- empirical validation currently covers a 60-month horizon and does not yet
  establish whether endogenous balance-sheet dynamics remain empirically
  plausible over complete business and financial cycles or across materially
  different inflation, monetary-policy, and financial-stress environments;
- each bank's accounting and regulatory capital is persisted and its monthly
  waterfall is SFC-validated, but the model does not represent bank shares or
  shareholders; bank equity therefore cannot be attributed to specific owners,
  and the unretained share of bank gross income has no modeled dividend
  receiver;
- listed-equity holdings are represented on the holder side but are not matched
  to individual issuing firms, so the model does not maintain firm- or
  bank-level cap tables; corporate bonds are the principal security family
  resolved by both issuer and holder, while several other financial-market
  measures remain aggregate or diagnostic-only;
- performance budgets are currently warnings rather than hard release gates;
- the model is Poland-specific and contains Poland-shaped institutions;
- institutional model-risk review, operational certification, and production
  support processes are outside the present project scope.

Detailed, mechanism-specific limitations remain authoritative in the model
specification and sector documents.

## Reproducibility

Research use should record the Git commit and dirty state, parameters and
scenario definition, seeds, horizon, output schema, calibration/data vintage,
and local modifications. The repository provides a Nix environment,
deterministic random-stream derivation, scenario exports, generated-output
checks, and nightly manifests. Commands and artifact locations are documented
in [operations](operations.md) and
[stochastic processes and replay](stochastic-processes-and-replay.md).

## Responsible Interpretation

Researchers publishing Amor Fati results should:

- report distributions or uncertainty bands across seeds instead of relying on
  a single path;
- report sensitivity to influential calibrated parameters;
- distinguish accounting closure from empirical validation;
- disclose missing data bridges, tuned parameters, and structural assumptions;
- avoid unsupported causal, predictive, optimality, or policy-recommendation
  language;
- publish enough run metadata and artifacts for independent reproduction;
- avoid excessive decimal precision that implies unsupported certainty;
- state when a result is conditional on omitted behavioral responses.

Policy scenarios can be politically sensitive. A model result should never be
detached from its scenario definition, calibration vintage, uncertainty, and
known limitations.

## Contact And Review

Issues concerning model behavior, documentation, calibration evidence, or
reproduction should be filed in the public repository with the relevant commit,
command, seed, and artifact. Scientific review should begin with the
[model specification reviewer path](model-specification.md#reviewer-reading-path).
