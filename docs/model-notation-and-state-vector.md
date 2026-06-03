# Model Notation And State Vector

This document defines the canonical notation layer for publication-facing Amor
Fati model descriptions. It is a map from mathematical symbols to the current
executable engine, not a new model and not a replacement for detailed equation,
ODD, SFC, calibration, or validation documents.

Use this document when writing monthly-transition, sector-equation,
stochasticity, SFC-mapping, calibration, or paper-facing overview material.

## Source Documents

| Source | Role |
| --- | --- |
| [ODD / ODD+D model documentation](odd-model-documentation.md) | Purpose, entities, state variables, scales, scheduling, initialization, and decision-making context. |
| [Behavioral equations and decision rules](behavioral-equations-and-decision-rules.md) | Implemented household, firm, bank, fiscal, monetary, external, insurance, NBFI, quasi-fiscal, and JST rule surfaces. |
| [SFC matrix evidence](sfc-matrix-evidence.md) | Balance Sheet Matrix, Transactions Flow Matrix, stock-flow reconciliation evidence, sign conventions, and generated matrix artifacts. |
| [Engine invariants and economic semantics](engine-invariants-and-semantics.md) | Hard invariants, normal-path expectations, known limitations, enforcement points, and coverage. |
| [Validation matrix](validation-matrix.md) | CI, integration, generated-output, nightly, stress, profiling, and validation ownership boundaries. |
| [Calibration register](calibration-register.md) | Parameter names, units, implementation owners, empirical targets, and provenance status. |

Implementation anchors:

- [`engine/flows/FlowSimulation.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/flows/FlowSimulation.scala)
- [`engine/World.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/World.scala)
- [`engine/WorldStateSegments.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/WorldStateSegments.scala)
- [`engine/ledger/LedgerFinancialState.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/ledger/LedgerFinancialState.scala)
- [`agents/Household.scala`](../src/main/scala/com/boombustgroup/amorfati/agents/Household.scala)
- [`agents/Firm.scala`](../src/main/scala/com/boombustgroup/amorfati/agents/Firm.scala)
- [`agents/Banking.scala`](../src/main/scala/com/boombustgroup/amorfati/agents/Banking.scala)
- [`types.scala`](../src/main/scala/com/boombustgroup/amorfati/types.scala)
- [`engine/MonthRandomness.scala`](../src/main/scala/com/boombustgroup/amorfati/engine/MonthRandomness.scala)

## Notation Rules

The notation follows the runtime ownership model:

- boundary stocks carry a time subscript, e.g. `X_t`, `D^H_{h,t}`;
- monthly flows use the execution-month subscript, e.g. `c^H_{h,\tau}`;
- `\tau = t + 1` denotes the month executed from boundary state `X_t`;
- rates are annual unless explicitly marked as monthly;
- shares are dimensionless and bounded in `[0, 1]` unless the source rule
  states otherwise;
- all monetary quantities are PLN unless explicitly marked as foreign-currency
  or index values;
- ledger-owned financial stocks live in `LedgerFinancialState`;
- behavioral and diagnostic stocks that are not ledger-owned must be named as
  such rather than silently treated as transferable owner-resolved assets.

## Time And Transition Boundary

Let `X_t` denote the complete simulation boundary after month `t` has been
completed. In code this is `FlowSimulation.SimState` with
`completedMonth = t`.

One execution month is a transition:

```text
Phi_tau : (X_t, RND_tau, theta) -> (X_tau, E_tau)
tau = t + 1
```

where:

| Symbol | Meaning | Implementation anchor |
| --- | --- | --- |
| `X_t` | start boundary after completed month `t` | `FlowSimulation.SimState` |
| `RND_tau` | explicit randomness contract for month `tau` | `MonthRandomness.Contract` |
| `theta` | model parameters and scenario-adjusted configuration | `SimParams` and scenario registry |
| `Phi_tau` | executable one-month transition | `FlowSimulation.step` |
| `E_tau` | transition evidence: trace, emitted flows, SFC validation, deltas, diagnostics | `FlowSimulation.StepOutput`, `MonthTrace` |
| `X_tau` | next boundary after month `tau` closes | `StepOutput.nextState` |

The temporal order is:

```text
pre boundary -> same-month economics -> flow plan -> runtime ledger execution
-> SFC validation -> closed month -> next-pre boundary
```

This order is described operationally in
`src/main/scala/com/boombustgroup/amorfati/engine/README.md` and is the target
surface for the monthly-transition ticket.

## Full State Vector

The canonical boundary state is:

```text
X_t = (m_t, W_t, F_t, H_t, B_t, A^H_t, L_t)
```

| Symbol | Runtime field | Meaning |
| --- | --- | --- |
| `m_t` | `SimState.completedMonth` | completed month index |
| `W_t` | `SimState.world` | macro, market, mechanism, signal, and diagnostic world state |
| `F_t` | `SimState.firms` | vector of firm behavioral states |
| `H_t` | `SimState.households` | vector of household behavioral states |
| `B_t` | `SimState.banks` | vector of bank operational states |
| `A^H_t` | `SimState.householdAggregates` | household aggregate diagnostics and market aggregates |
| `L_t` | `SimState.ledgerFinancialState` | ledger-owned financial balances |

`X_t` is not a single undifferentiated object. It is a typed boundary between
three state surfaces:

| Surface | Symbol family | Runtime source | Publication meaning |
| --- | --- | --- | --- |
| Behavioral agent state | `H_t`, `F_t`, `B_t` | household, firm, and bank state vectors | heterogeneous decision state and operational status |
| Macro and market state | `W_t`, `A^H_t` | `World`, `Household.Aggregates` | prices, policies, market memory, signals, diagnostics, and aggregates |
| Ledger-owned financial state | `L_t` | `LedgerFinancialState` | supported financial owner/issuer balances used by runtime execution and SFC validation |

## Entity And Index Sets

| Symbol | Meaning | Runtime anchor |
| --- | --- | --- |
| `h in H_t` | household agent | `Household.State.id: HhId` |
| `f in F_t` | firm agent | `Firm.State.id: FirmId` |
| `b in B` | bank row | `Banking.BankState.id: BankId` |
| `s in S` | production sector | `SectorIdx`, `SimParams.sectorDefs` |
| `r in R` | NUTS-1 macroregion | `Region` |
| `q in Q` | ledger-facing entity sector | `EntitySector` |
| `k in K_R` | named randomness stream | `MonthRandomness.StreamKey` |

The bank set `B` is a fixed vector of banking-sector rows: named bank
archetypes plus the residual Other banks row. A bank row is not a full legal
entity model of a single commercial bank.

## Behavioral Agent State

### Households

For household `h`, write behavioral state as:

```text
a^H_{h,t}
```

This is the non-ledger household state in `Household.State`: employment or
activity status, rent, skill, health penalty, MPC, social-neighbor ids, bank
routing id, last sector, immigrant flag, dependent children, education, task
routineness, wage scar, contract type, region, and financial-distress state.

Ledger-backed household balances are not part of `a^H_{h,t}`. They are:

```text
l^H_{h,t} in L_t.households
```

with the main stock families:

| Symbol | Runtime field | Type | Meaning |
| --- | --- | --- | --- |
| `D^H_{h,t}` | `HouseholdBalances.demandDeposit` | stock, PLN | household demand-deposit asset |
| `M^H_{h,t}` | `HouseholdBalances.mortgageLoan` | stock, PLN | household mortgage principal liability |
| `CL^H_{h,t}` | `HouseholdBalances.consumerLoan` | stock, PLN | household consumer-loan principal liability |
| `E^H_{h,t}` | `HouseholdBalances.equity` | stock, PLN | listed-equity asset |
| `IR^{life}_{h,t}` | `HouseholdBalances.lifeReserveAsset` | stock, PLN | life-insurance reserve asset |
| `IR^{nonlife}_{h,t}` | `HouseholdBalances.nonLifeReserveAsset` | stock, PLN | non-life insurance reserve asset |

### Firms

For firm `f`, write behavioral state as:

```text
a^F_{f,t}
```

This is the non-ledger firm state exposed through `Firm.State`: sector,
technology regime, worker/capacity logic, productivity, capital/inventory
structure, digital readiness, operational status, hiring signal state, and
decision-relevant attributes.

Ledger-backed firm balances are:

```text
l^F_{f,t} in L_t.firms
```

with the main stock families:

| Symbol | Runtime field | Type | Meaning |
| --- | --- | --- | --- |
| `Cash^F_{f,t}` | `FirmBalances.cash` | stock, PLN | firm liquidity |
| `L^F_{f,t}` | `FirmBalances.firmLoan` | stock, PLN | outstanding bank-loan principal |
| `CB^F_{f,t}` | `FirmBalances.corpBond` | stock, PLN | corporate bonds issued by the firm |
| `Eq^F_{f,t}` | `FirmBalances.equity` | stock, PLN | listed equity issued by the firm |

### Banks

For bank row `b`, write operational state as:

```text
a^B_{b,t}
```

This is `Banking.BankState`: id, regulatory capital, NPL stock diagnostics,
HTM book yield, active/failed status, corporate-loan maturity buckets,
consumer NPL stock, and IFRS 9 ECL staging.

Bank financial ownership balances are:

```text
l^B_{b,t} in L_t.banks
```

with the main stock families:

| Symbol | Runtime field | Type | Meaning |
| --- | --- | --- | --- |
| `D^B_{b,t}` | `BankBalances.totalDeposits` | stock, PLN | total customer deposit liability |
| `D^{B,demand}_{b,t}` | `BankBalances.demandDeposit` | stock, PLN | demand-deposit liability |
| `D^{B,term}_{b,t}` | `BankBalances.termDeposit` | stock, PLN | term-deposit liability |
| `L^{B,F}_{b,t}` | `BankBalances.firmLoan` | stock, PLN | firm-loan asset |
| `L^{B,H}_{b,t}` | `BankBalances.consumerLoan` | stock, PLN | consumer-loan asset |
| `M^B_{b,t}` | `BankBalances.mortgageLoan` | stock, PLN | mortgage-loan asset mirror used for BSM evidence |
| `GB^{AFS}_{b,t}` | `BankBalances.govBondAfs` | stock, PLN | available-for-sale government-bond asset |
| `GB^{HTM}_{b,t}` | `BankBalances.govBondHtm` | stock, PLN | held-to-maturity government-bond asset |
| `Res^B_{b,t}` | `BankBalances.reserve` | stock, PLN | reserve asset at NBP |
| `IB_{b,t}` | `BankBalances.interbankLoan` | stock, PLN | net interbank position |
| `CB^B_{b,t}` | `BankBalances.corpBond` | stock, PLN | corporate-bond asset |
| `K^B_{b,t}` | `BankState.capital` | diagnostic stock, PLN | regulatory/accounting bank-capital buffer, not holder-resolved ledger equity |

`K^B_{b,t}` is intentionally outside `LedgerFinancialState.BankBalances`.
ADR 0001 defines it as persisted bank regulatory/accounting state validated by
SFC, not transferable holder-resolved bank equity.

## Macro And Market State

Write the world state as:

```text
W_t = (P_t, pi_t, sigma_t, W^gov_t, W^{nbp}_t, W^{bank}_t,
       W^{fx}_t, W^{bop}_t, W^{hhm}_t, W^{soc}_t, W^{fin}_t,
       W^{ext}_t, W^{real}_t, W^{mech}_t, W^{plumb}_t,
       W^{pipe}_t, W^{flow}_t, W^{regwage}_t)
```

| Symbol | Runtime field | Meaning |
| --- | --- | --- |
| `pi_t` | `World.inflation` | CPI YoY inflation rate |
| `P_t` | `World.priceLevel` | cumulative CPI price index |
| `sigma_t` | `World.currentSigmas` | per-sector production sigma values |
| `W^gov_t` | `World.gov` | central-government budget and debt state |
| `W^{nbp}_t` | `World.nbp` | NBP policy, QE, FX-operation state |
| `W^{bank}_t` | `World.bankingSector` | banking macro state, bank configs, interbank conditions, rate term structure |
| `W^{fx}_t` | `World.forex` | exchange rate, exports, imports, trade balance state |
| `W^{bop}_t` | `World.bop` | balance-of-payments state: NFA, current account, capital account, FDI |
| `W^{hhm}_t` | `World.householdMarket` | household market wage and reservation wage |
| `W^{soc}_t` | `World.social` | JST, ZUS, NFZ, PPK, demographics, earmarked funds |
| `W^{fin}_t` | `World.financialMarkets` | equity, corporate-bond, insurance, NBFI, quasi-fiscal market memory |
| `W^{ext}_t` | `World.external` | GVC, immigration, tourism state |
| `W^{real}_t` | `World.real` | housing, sectoral mobility, investment, energy, productivity, automation/hybrid shares |
| `W^{mech}_t` | `World.mechanisms` | macroprudential, expectations, BFG fund, informal-economy adjustment |
| `W^{plumb}_t` | `World.plumbing` | reserve interest, standing facility, interbank interest, deposit facility, flow-of-funds residual |
| `W^{pipe}_t` | `World.pipeline` | persisted next-month decision-signal surface |
| `W^{flow}_t` | `World.flows` | single-step derived flow diagnostics used by SFC identities and outputs |
| `W^{regwage}_t` | `World.regionalWages` | regional wage cache |

`World` does not own supported financial balances. It can store market memory,
diagnostic flows, unsupported transition fields, and signal surfaces; supported
financial ownership belongs in `L_t`.

## Ledger-Owned Financial State

Write the ledger-owned financial state as:

```text
L_t = (L^H_t, L^F_t, L^B_t, L^G_t, L^{ROW}_t,
       L^{NBP}_t, L^{INS}_t, L^{FUNDS}_t)
```

| Symbol | Runtime field | Meaning |
| --- | --- | --- |
| `L^H_t` | `LedgerFinancialState.households` | household financial balances |
| `L^F_t` | `LedgerFinancialState.firms` | firm financial balances |
| `L^B_t` | `LedgerFinancialState.banks` | bank financial balances |
| `L^G_t` | `LedgerFinancialState.government` | central-government issued bond stock |
| `L^{ROW}_t` | `LedgerFinancialState.foreign` | foreign-sector government-bond, foreign-asset, and equity holdings |
| `L^{NBP}_t` | `LedgerFinancialState.nbp` | NBP government-bond holdings, foreign assets, and reserve liability |
| `L^{INS}_t` | `LedgerFinancialState.insurance` | insurance reserves, bond holdings, equity holdings |
| `L^{FUNDS}_t` | `LedgerFinancialState.funds` | ZUS/NFZ/PPK/FP/PFRON/FGSP/JST cash, fund holdings, NBFI, quasi-fiscal balances |

Ledger rows are the canonical source for supported stock ownership. Agent and
economics modules may receive projections such as `Household.FinancialStocks`,
`Firm.FinancialStocks`, or `Banking.BankFinancialStocks`, but those projections
are execution DTOs, not independent persisted owners.

## Quantity Classes

| Class | Canonical notation | Runtime type | Interpretation |
| --- | --- | --- | --- |
| Monetary stock | uppercase with `t`, e.g. `D^H_{h,t}` | `PLN` | boundary level owned, owed, or diagnostically persisted |
| Monetary flow | lowercase or named flow with `tau`, e.g. `tax_{h,\tau}` | `PLN` | amount accumulated during one execution month |
| Rate | `r_{\tau}` or named rate | `Rate` | annual by default; use `.monthly` for monthly application |
| Share | `s_{\tau}`, `q_{\tau}`, `omega_{\tau}` | `Share` | bounded ratio, usually `[0, 1]` |
| Multiplier | `mu_{\tau}` | `Multiplier` | gross multiplier or index-like factor |
| Price index | `P_t` or named index | `PriceIndex`, `Multiplier` | cumulative price/index level |
| Elasticity or production curvature | `sigma_{s,t}` | `Sigma` | sector or production-function parameter |
| Count or index | `N_t`, `h`, `f`, `b`, `s` | `Int`, typed ids | population count or identifier |
| Stochastic draw | `epsilon^k_{\tau}` | `RandomStream` draw from `MonthRandomness.StreamKey` | seeded random variation from named stream `k` |

The Scala fixed-point type layer prevents accidental cross-type arithmetic:
`PLN`, `Rate`, `Share`, `Multiplier`, `Coefficient`, `PriceIndex`, `Sigma`,
`ExchangeRate`, and ids are distinct runtime domains.

## Major Flow Families

Monthly flow notation should follow the economic channel, not the runtime file
name. Detailed equations live in `behavioral-equations-and-decision-rules.md`;
the table below only fixes symbol families for publication work.

| Symbol family | Meaning | Main anchors |
| --- | --- | --- |
| `Y_{\tau}` | output, GDP proxy, or revenue, with scope specified by subscript | `DemandEconomics`, `FirmEconomics`, `GdpAccounting`, `McTimeseriesSchema` |
| `C^H_{\tau}` | household consumption | `HouseholdIncomeEconomics`, `HouseholdFlows` |
| `I^F_{\tau}` | firm investment and capex | `FirmEconomics`, `FirmFlows` |
| `G_{\tau}` | government purchases and spending | `FiscalBudget`, `GovBudgetFlows` |
| `X_{\tau}`, `IM_{\tau}` | exports and imports | `OpenEconEconomics`, `OpenEconFlows` |
| `Wage_{\tau}` | wage payments and market wage outcomes | `LaborEconomics`, `LaborMarket`, `HouseholdIncomeEconomics` |
| `T_{\tau}` | taxes by type: PIT, CIT, VAT, excise, levies | tax flow emitters, `Sfc` identities |
| `TR_{\tau}` | transfers and benefits | household/public-sector flow emitters |
| `Int_{\tau}` | interest by instrument and counterparty | banking, household, NBP, bond, and external flow emitters |
| `Orig_{\tau}` | new credit origination | household, firm, mortgage, NBFI credit modules |
| `Prin_{\tau}` | scheduled principal repayment | credit and mortgage flow modules |
| `Def_{\tau}` | default or write-off branch | household, firm, mortgage, NBFI, bond, and bank loss modules |
| `Iss_{\tau}` | bond or equity issuance | corporate-bond, government-bond, quasi-fiscal, and equity modules |
| `Val_{\tau}` | revaluation or valuation effect | equity, bond, FX, and SFC semantic projection paths |

When a symbol is ambiguous, add sector and instrument superscripts. For example,
`Def^{H,cons}_{\tau}` is household consumer-loan default, while
`Def^{F,loan}_{\tau}` is firm bank-loan default.

## Rates, Shares, And Policy Variables

Use rates for interest, inflation, growth, and default-rate surfaces:

| Symbol | Meaning | Typical runtime source |
| --- | --- | --- |
| `r^{NBP}_{\tau}` | NBP reference rate | `Nbp.State`, `OpenEconEconomics` |
| `r^{loan}_{b,\tau}` | bank lending rate | `Banking`, `BankingStepRunner` |
| `r^{dep}_{b,\tau}` | bank deposit rate | `Banking`, household income/financial economics |
| `pi_{\tau}` | inflation | `World.inflation`, `PriceEquityEconomics` |
| `u_{\tau}` | unemployment rate | `World.unemploymentRate`, household aggregates |
| `CAR_{b,\tau}` | capital adequacy ratio | banking regulatory metrics |
| `NPL_{b,\tau}` | non-performing-loan ratio or stock, with unit specified | banking metrics and diagnostics |
| `LCR_{b,\tau}` | liquidity coverage ratio | banking regulatory metrics |
| `NSFR_{b,\tau}` | net stable funding ratio | banking regulatory metrics |
| `ccyb_{\tau}` | countercyclical capital buffer | macroprudential mechanism |
| `theta` | parameter vector | `SimParams` |

Use shares for bounded composition variables such as MPC, import propensity,
foreign ownership, automation ratio, hybrid ratio, tax-shadow share, and
sectoral weights.

## Stochastic Variables

The model is deterministic conditional on:

```text
(X_t, RND_tau, theta)
```

Random variables should be written as:

```text
epsilon^k_{\tau}
```

where `k` names a stream from `MonthRandomness.StreamKey`.

| Stream key | Publication role | Runtime field |
| --- | --- | --- |
| `HouseholdIncomeEconomics` | household income, consumption, labor-transition randomness used in the household-income stage | `stages.householdIncomeEconomics` |
| `FirmEconomics` | firm decision, labor adjustment, pricing, credit and production-stage randomness | `stages.firmEconomics` |
| `HouseholdFinancialEconomics` | household financial-stage randomness | `stages.householdFinancialEconomics` |
| `OpenEconEconomics` | external, commodity, market, and open-economy stage randomness | `stages.openEconEconomics` |
| `BankingEconomics` | banking-stage credit, interbank, and resolution randomness | `stages.bankingEconomics` |
| `FdiMa` | closed-month FDI M&A ownership transition | `closing.fdiMa` |
| `FirmEntry` | closed-month firm-entry transition | `closing.firmEntry` |
| `StartupStaffing` | closed-month startup staffing transition | `closing.startupStaffing` |
| `RegionalMigration` | closed-month regional migration transition | `closing.regionalMigration` |

Initialization randomness is separate and belongs to `InitRandomness.Contract`.
Monthly stochasticity must not be described as ambient global randomness; it is
an explicit seed contract.

## Notation-To-Code Map

| Publication object | Code anchor | Notes |
| --- | --- | --- |
| Full boundary state `X_t` | `FlowSimulation.SimState` | single input to one monthly step |
| Month transition `Phi_tau` | `FlowSimulation.step`, `MonthDriver` | returns `StepOutput` and `nextState` |
| Macro/market state `W_t` | `World`, `WorldStateSegments` | excludes supported financial ownership |
| Household behavioral state `a^H_{h,t}` | `Household.State` | projected ledger balances carried separately |
| Firm behavioral state `a^F_{f,t}` | `Firm.State` | `Firm.FinancialStocks` is a projection DTO |
| Bank operational state `a^B_{b,t}` | `Banking.BankState` | includes regulatory capital diagnostic stock |
| Ledger financial state `L_t` | `LedgerFinancialState` | source of truth for supported financial ownership |
| Household aggregate state `A^H_t` | `Household.Aggregates` | aggregate diagnostics and market aggregates |
| Runtime monetary flow | `FlowMechanism`, `MonthFlowEmitter`, `RuntimeFlowExecutor` | executed through verified ledger topology |
| SFC semantic validation | `Sfc`, `SfcSemanticProjection` | exact SFC identities and semantic stock-flow terms |
| Randomness contract `RND_tau` | `MonthRandomness.Contract` | named deterministic seed streams |
| Parameter vector `theta` | `SimParams`, scenario deltas | calibrated defaults plus explicit scenario mutations |

## Usage Rules For Future Specification Tickets

1. Use `X_t` for the full boundary state and `Phi_tau` for the one-month
   transition.
2. Use `L_t` only for ledger-owned financial balances.
3. Do not put bank regulatory capital into holder-resolved ledger equity.
4. Distinguish stocks from flows by time indexing and wording.
5. State whether a monetary variable is a boundary stock, monthly flow,
   diagnostic stock, valuation effect, or known unsupported gap.
6. Link detailed equations to the behavioral-equations document instead of
   duplicating long rule prose.
7. Link accounting claims to SFC matrix evidence and engine invariants.
8. Use `epsilon^k_{\tau}` only when the source stream `k` is identified.

## Known Scope Boundaries

- This document does not define new equations.
- This document does not settle calibration governance; that is tracked by
  milestone #27.
- This document does not replace generated SFC matrix artifacts.
- This document intentionally mirrors the current implementation. Future model
  changes should update this notation only when the runtime state boundary or
  symbol families change.
