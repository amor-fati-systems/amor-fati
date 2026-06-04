# Amor Fati Model Specification

This is the canonical publication-facing entry point for the current Amor Fati
model. It consolidates the model's purpose, scope, state vector, monthly timing,
equation families, SFC/accounting contract, stochasticity, calibration surface,
validation evidence, and known limitation boundaries.

This document describes the implemented executable model. It is not a normative
target model, and it does not replace the detailed source documents listed
below.

## Source Map

| Source | Role in the model specification |
| --- | --- |
| [Model notation and state vector](model-notation-and-state-vector.md) | Canonical symbols, state vector, time indexing, quantity classes, stochastic notation, and implementation anchors. |
| [Model-spec completeness checklist](model-spec-completeness-checklist.md) | Review control surface for model-family coverage, detailed sources, visible gaps, and remaining publication-readiness work. |
| [Monthly transition function](monthly-transition-function.md) | Formal `X_t -> X_tau` month-step contract, including randomness, same-month economics, closed-month state, flow emission, runtime ledger execution, SFC validation, and next-pre boundary. |
| [ODD / ODD+D model documentation](odd-model-documentation.md) | ODD/ODD+D description of purpose, entities, scales, scheduling, initialization, inputs, submodels, observation surfaces, and decisions. |
| [Behavioral equations and decision rules](behavioral-equations-and-decision-rules.md) | Implemented equations and algorithmic decision rules by model family. |
| [SFC matrix evidence](sfc-matrix-evidence.md) | Balance Sheet Matrix, Transactions Flow Matrix, stock-flow reconciliation evidence, sign conventions, and generated matrix artifacts. |
| [Engine invariants and economic semantics](engine-invariants-and-semantics.md) | Hard invariants, normal-path expectations, stress semantics, known limitations, enforcement points, and coverage. |
| [Calibration register](calibration-register.md) | Parameter names, units, owners, empirical targets, transformations, provenance status, and searchable gaps. |
| [Data bridge to national and financial accounts](data-bridge-national-financial-accounts.md) | Official data sources and empirical bridges used for initialization, calibration, scenarios, and validation. |
| [Empirical validation report](empirical-validation-report.md) | Empirical-validation workflow and current snapshot artifacts. |
| [Validation matrix](validation-matrix.md) | Ownership boundary for CI, integration tests, generated outputs, nightly diagnostics, stress profiles, profiling, and observability. |

## Model Identity

Amor Fati is a stock-flow consistent agent-based macroeconomic model of the
Polish economy. It simulates heterogeneous households, heterogeneous firms,
multi-row banking-sector balance sheets, public-sector institutions, financial
markets, non-bank financial institutions, insurance, and the rest of world.

The model is designed for executable counterfactual analysis under strict
accounting discipline:

- behavioral rules are implemented as explicit agent, market, and institutional
  mechanisms;
- every supported monetary flow is routed through the runtime ledger and SFC
  validation surface;
- generated diagnostics and matrix artifacts expose whether model behavior is
  normal-path, stress, exploratory, benchmark, or performance evidence;
- calibration and empirical validation are documented as evidence surfaces, not
  as hidden assumptions.

The strongest model contract is accounting correctness. Macro paths can be
revised, calibrated, or rejected. Silent monetary drift is a model error.

## Scope And Scale

| Dimension | Current implementation |
| --- | --- |
| Economy | Poland with explicit rest-of-world sector |
| Time | Monthly discrete steps |
| Households | Individual heterogeneous household agents |
| Firms | Individual heterogeneous firm agents |
| Banks | Ten banking-sector rows: named bank archetypes plus residual Other banks |
| Production sectors | BPO/SSC, Manufacturing, Retail/Services, Healthcare, Public, Agriculture |
| Regions | Six NUTS-1 macroregions for regional labor and housing mechanics |
| Public sector | Central government, local government, ZUS, NFZ, PPK, FP, PFRON, FGSP, quasi-fiscal vehicles |
| Financial sector | Banks, NBP, insurers, investment funds/NBFI, corporate bonds, government bonds, listed equity |
| External sector | Trade, tourism, remittances, FDI, portfolio flows, FX reserves, NFA/current-account channels |
| Money domain | PLN-denominated fixed-point values unless explicitly marked otherwise |

The model is not a GIS model. Region is a market-segmentation and demographic
attribute, not a continuous spatial coordinate.

## Canonical State Vector

The complete month-boundary state is:

```text
X_t = (m_t, W_t, F_t, H_t, B_t, A^H_t, L_t)
```

| Symbol | Runtime field | Meaning |
| --- | --- | --- |
| `m_t` | `FlowSimulation.SimState.completedMonth` | completed month index |
| `W_t` | `FlowSimulation.SimState.world` | macro, market, mechanism, signal, and diagnostic world state |
| `F_t` | `FlowSimulation.SimState.firms` | firm behavioral state vector |
| `H_t` | `FlowSimulation.SimState.households` | household behavioral state vector |
| `B_t` | `FlowSimulation.SimState.banks` | bank operational state vector |
| `A^H_t` | `FlowSimulation.SimState.householdAggregates` | household aggregate diagnostics and market aggregates |
| `L_t` | `FlowSimulation.SimState.ledgerFinancialState` | ledger-owned financial balances |

The state vector intentionally separates:

- behavioral agent state: household, firm, and bank decision-relevant state;
- macro and market state: prices, policy, external conditions, expectations,
  market memory, demand signals, regional wages, mechanism state, and flow
  diagnostics;
- ledger-owned financial state: supported financial balances in
  `LedgerFinancialState`, projected into runtime execution and SFC validation.

Detailed notation, stock/flow/rate/share conventions, stochastic notation, and
state-to-code mapping live in
[model-notation-and-state-vector.md](model-notation-and-state-vector.md).

## Monthly Transition Function

One model month is the transition:

```text
Phi_tau : (X_t, RND_tau, theta) -> (X_tau, E_tau)
tau = t + 1
```

where:

| Symbol | Meaning |
| --- | --- |
| `X_t` | month-boundary state after completed month `t` |
| `RND_tau` | explicit month randomness contract |
| `theta` | model parameter vector, including scenario-adjusted parameters |
| `Phi_tau` | executable one-month transition implemented by `FlowSimulation.step` |
| `X_tau` | next month-boundary state |
| `E_tau` | trace, emitted flows, runtime ledger evidence, SFC validation, diagnostics, and deltas |

The execution order is:

```text
pre boundary -> same-month economics -> same-month boundary views
-> semantic closed month and seed extraction -> flow emission
-> runtime ledger execution -> next-pre materialization -> SFC validation gate
```

Same-month economics calculates decisions, prices, rates, quantities, and
closing projections. The flow layer translates those quantities into typed
monetary mechanisms, executes them through the ledger topology, and validates
semantic stock-flow identities before the step result is accepted. Closed-month
and next-pre logic materialize the next boundary state and next-period decision
signals. The formal transition contract lives in
[monthly-transition-function.md](monthly-transition-function.md).

## Entity And Institution Families

| Family | Implemented role | Detailed source |
| --- | --- | --- |
| Households | Labor supply, income, consumption, savings, rent, mortgages, consumer credit, remittances, retraining, distress, bankruptcy, social-neighbor effects | [ODD](odd-model-documentation.md), [behavioral equations](behavioral-equations-and-decision-rules.md#household-rules) |
| Firms | Production, capacity, hiring/firing, inventory, investment, technology adoption, credit demand, bond/equity financing, default, entry/exit | [ODD](odd-model-documentation.md), [behavioral equations](behavioral-equations-and-decision-rules.md#firm-rules) |
| Banks | Lending, deposits, interest margins, CAR/NPL/LCR/NSFR, ECL staging, interbank, bond portfolio, failures, resolution, bail-in, BFG levy | [behavioral equations](behavioral-equations-and-decision-rules.md#banking-rules), [ADR 0001](adr/0001-bank-capital-sfc-semantics.md) |
| Central government | Taxes, spending, transfers, fiscal-rule constraints, bond issuance, public debt and deficit metrics | [behavioral equations](behavioral-equations-and-decision-rules.md#government-budget-and-debt) |
| NBP | Reference rate, monetary policy, reserves, standing facilities, QE, FX operations, monetary aggregates | [behavioral equations](behavioral-equations-and-decision-rules.md#nbp-policy-bond-yield-qe-fx) |
| External sector | Exports, imports, current account, capital account, FDI, remittances, tourism, foreign holdings, NFA | [external-sector calibration](external-sector-baseline-calibration.md), [behavioral equations](behavioral-equations-and-decision-rules.md#external-sector) |
| Insurance | Premiums, claims, reserves, investment income, reserve assets/liabilities | [behavioral equations](behavioral-equations-and-decision-rules.md#insurance-nbfi-quasi-fiscal-and-jst-rules) |
| NBFI/funds | TFI/NBFI assets, fund units, non-bank credit renewal, deposit drain, PPK, quasi-fiscal lending and bonds | [private-credit calibration](private-credit-renewal-calibration.md), [behavioral equations](behavioral-equations-and-decision-rules.md#insurance-nbfi-quasi-fiscal-and-jst-rules) |

## Equation And Rule Families

This specification treats detailed equations as source-linked rule families.
The canonical detailed rule source remains
[behavioral-equations-and-decision-rules.md](behavioral-equations-and-decision-rules.md).

| Rule family | Model role | Current source of truth |
| --- | --- | --- |
| Household income, tax, transfers, consumption, saving, credit, distress | Maps employment and financial state into disposable income, consumption, debt service, defaults, liquidity stress, and household aggregates | `Household.scala`, `HouseholdIncomeEconomics.scala`, `HouseholdFinancialEconomics.scala`, household sections in behavioral equations |
| Labor, wages, demographics, social funds | Determines market wage, employment, immigration, retirements, ZUS/NFZ/PPK and earmarked fund flows | `LaborEconomics.scala`, `LaborMarket.scala`, `SocialSecurity.scala`, `EarmarkedFunds.scala` |
| Demand, GDP, prices, equity, macroprudential | Allocates demand, computes GDP proxy, inflation, price index, equity market updates, and credit-gap policy state | `DemandEconomics.scala`, `PriceEquityEconomics.scala`, `GdpAccounting.scala`, macroprudential mechanisms |
| Firm production, investment, technology, financing, default, entry | Computes production/capacity, pricing, labor adjustment, investment, financing mix, credit rejection, default/NPL, births/deaths | `agents/firm/*`, `FirmEconomics.scala`, `engine/economics/firm/*` |
| Banking and monetary plumbing | Updates bank P&L, capital, provisioning, credit approval, rates, interbank, bond waterfall, failures/resolution, monetary aggregates | `agents/banking/*`, `BankingEconomics.scala`, `engine/economics/banking/*` |
| Housing and mortgages | Updates housing prices, mortgage stock, origination, repayment, default, and mortgage-to-GDP outputs | `HousingMarket.scala`, banking housing stage, mortgage flow modules |
| Fiscal, NBP, bonds, external sector | Computes public budget, public debt, rates, QE, bond yields, BoP/forex, GVC, trade, and current-account closure | fiscal, NBP, open-economy, bond-market, and external-sector modules |
| Insurance, NBFI, quasi-fiscal, JST | Computes premiums, claims, reserves, NBFI credit, fund AUM, PPK holdings, quasi-fiscal issuance/lending, and local-government flows | insurance, NBFI, quasi-fiscal, PPK, JST modules |
| Scenario, robustness, diagnostics | Defines executable counterfactuals, sensitivity envelopes, health summaries, and profiling evidence | [scenario registry](scenario-registry.md), [sensitivity workflow](sensitivity-robustness-workflow.md), [nightly diagnostics](nightly-diagnostics.md), [hot-path profiling](hot-path-profiling.md) |

When writing publication equations, use the notation in
[model-notation-and-state-vector.md](model-notation-and-state-vector.md) and
link back to these rule-family sources rather than duplicating implementation
prose.

## SFC And Accounting Contract

Amor Fati's accounting contract has three layers:

1. Runtime ledger execution: supported monetary flows are emitted as typed
   mechanisms and executed through the verified ledger topology.
2. Ledger-owned stock projection: supported financial balances are materialized
   in `LedgerFinancialState` and projected into agent/economics execution DTOs.
3. Semantic SFC validation: exact SFC identities validate the economic stock-flow
   interpretation of the month.

The project maintains generated SFC evidence:

| Artifact | Purpose |
| --- | --- |
| [Symbolic BSM](sfc-matrix-artifacts/symbolic-bsm.md) | Paper-facing stock matrix by instrument and sector |
| [Symbolic TFM](sfc-matrix-artifacts/symbolic-tfm.md) | Paper-facing monthly transaction matrix |
| [Matrix mapping](sfc-matrix-artifacts/matrix-mapping.md) | Symbolic row to runtime asset/mechanism/coverage mapping |
| [Flow-channel semantics](sfc-matrix-artifacts/flow-mechanism-semantics.md) | Economic meaning of runtime flow mechanisms |
| [Stock-flow reconciliation](sfc-matrix-artifacts/stock-flow-reconciliation.md) | Executed-run evidence for stock deltas, levels, revaluation, defaults, write-offs, and other changes |

Known unsupported, diagnostic-only, or non-holder-resolved stock families must
remain explicit. In particular, bank capital is a persisted bank regulatory and
accounting buffer validated by SFC, not holder-resolved bank equity.

## Stochasticity And Replay

The model is deterministic conditional on:

```text
(X_t, RND_tau, theta)
```

`RND_tau` is an explicit `MonthRandomness.Contract`, not ambient global
randomness. It derives named streams for household income, firm economics,
household financial economics, open-economy economics, banking economics, FDI
M&A, firm entry, startup staffing, and regional migration.

Monte Carlo output is distributional across seeds. Within one seed and one
month boundary, replay requires the same state, same parameter vector, and same
randomness contract.

## Calibration And Empirical Evidence

Calibration is currently documented through:

- [calibration-register.md](calibration-register.md): parameter-level register,
  units, owners, empirical targets, transformations, and provenance status;
- [data-bridge-national-financial-accounts.md](data-bridge-national-financial-accounts.md):
  source mapping from official national and financial accounts into model
  initialization, calibration, scenario, and validation surfaces;
- [empirical-validation-report.md](empirical-validation-report.md): current
  empirical-validation workflow and generated validation snapshot;
- targeted calibration notes for external sector, household credit stress, and
  private credit renewal.

Calibration governance is intentionally treated as a separate design problem.
This model specification references current calibration artifacts but does not
declare a new parameter source-of-truth policy.

## Validation And Diagnostics

Validation is layered:

| Layer | Role |
| --- | --- |
| Unit/property tests | Local mechanism, algebraic, schema, parser, and invariant checks |
| Integration tests | Short end-to-end engine health and deterministic CSV checks |
| Generated-output guard | Ensures committed generated docs/resources match source generators |
| Diagnostics profiles | Long validation and research diagnostics from assembled jar under Nix |
| Nightly health summary | Compact thresholded verdict over existing diagnostics artifacts |
| Performance telemetry | Step runtime, throughput, memory, GC, and soft regression-budget evidence |
| Hot-path profiling | JFR-backed runtime and allocation evidence |

Failure semantics are not uniform. Accounting, ledger conservation, malformed
outputs, missing required artifacts, impossible stock states, and exact SFC
breaks are hard failures. Calibration metrics, stress outcomes, exploratory
diagnostics, and performance budgets start as warning/report evidence unless a
written threshold rationale promotes them.

The routing contract lives in [validation-matrix.md](validation-matrix.md).

## Observation Surfaces

The primary numeric model output is the Monte Carlo time-series schema, backed
by CSV outputs and diagnostics artifacts. Representative surfaces include:

- macro variables: GDP proxy, inflation, unemployment, wages, public debt,
  fiscal balance, current account, prices, monetary aggregates;
- private-sector variables: household consumption, deposits, credit, distress,
  firm production, investment, technology adoption, credit demand, defaults,
  entry/exit;
- financial-stability variables: bank capital, NPLs, CAR, LCR, NSFR, interbank,
  BFG levy, bail-in, failures, resolution, bank reconciliation residuals;
- SFC/accounting artifacts: BSM, TFM, stock-flow reconciliation, flow-mechanism
  semantics, SFC identity diagnostics;
- validation artifacts: health summaries, empirical validation snapshots,
  scenario outputs, robustness envelopes, loan-origination diagnostics,
  HH-bank lead-lag diagnostics, profiling artifacts.

Output-column details belong in the detailed diagnostics and schema documents,
not in this overview.

## Implemented Model, Limitations, And Future Extensions

This specification distinguishes current implementation from future research
extensions:

| Category | Current status |
| --- | --- |
| Implemented model | Monthly SFC-ABM with heterogeneous households/firms, multi-row banks, public sector, NBP, external sector, insurance, NBFI/funds, quasi-fiscal vehicles, executable scenarios, diagnostics, SFC evidence, and validation/profile workflows |
| Known limitations | Bank capital is not holder-resolved equity; some symbolic matrix rows intentionally expose unsupported or diagnostic-only coverage; calibration governance is not yet settled; several empirical bridges remain incomplete; performance budgets are soft warnings |
| Future research extensions | Long-horizon cycle/regime validation, richer calibration governance, deeper holder-resolved ownership where supported by data, refined empirical validation, and publication-grade sector equation consolidation |

The implemented model should be read as an executable scientific object:
behavioral mechanisms can be revised, calibration can be improved, and
extensions can be added, but the accounting and validation surfaces must remain
auditable.

## Reviewer Reading Order

For a first academic review:

1. Read this document for the model spine.
2. Skim [model-spec-completeness-checklist.md](model-spec-completeness-checklist.md)
   for current coverage and visible publication-readiness gaps.
3. Read [model-notation-and-state-vector.md](model-notation-and-state-vector.md)
   for notation and state ownership.
4. Read [monthly-transition-function.md](monthly-transition-function.md) for
   the formal month-step contract.
5. Read [odd-model-documentation.md](odd-model-documentation.md) for ODD/ODD+D
   structure and agent/entity description.
6. Read [behavioral-equations-and-decision-rules.md](behavioral-equations-and-decision-rules.md)
   for implemented rule families.
7. Read [sfc-matrix-evidence.md](sfc-matrix-evidence.md) and generated matrix
   artifacts for the accounting contract.
8. Read [calibration-register.md](calibration-register.md),
   [data-bridge-national-financial-accounts.md](data-bridge-national-financial-accounts.md),
   and [empirical-validation-report.md](empirical-validation-report.md) for
   calibration and validation evidence.
9. Read [engine-invariants-and-semantics.md](engine-invariants-and-semantics.md)
   and [validation-matrix.md](validation-matrix.md) for failure semantics and
   review routing.
