# Simulation Engine

The engine package orchestrates the monthly simulation loop. The month
boundary is `FlowSimulation.SimState`, which carries `World` macro state,
agent populations, household aggregates, and `LedgerFinancialState`.
Domain logic is split across **economics** (same-month calculus pipeline),
**closedmonth** (closed-month state projection), **flows** (SFC-verified monetary
flow emission via ledger), **ledger** (financial ownership contracts and
projections), **markets** (clearing mechanisms), and **mechanisms** (domain
rules that modify agent state outside market clearing).

```
engine/
├── World.scala             # Immutable macro/runtime month-boundary root
├── WorldStateSegments.scala # Domain state segments carried by World
├── PipelineState.scala     # persisted pre-month decision signal surface
├── FlowState.scala         # single-step derived flow diagnostics
├── diagnostics/banking/    # bank failure/capital/resolution diagnostic contracts
├── economics/              # Same-month calculus pipeline, no monetary flow emission
├── closedmonth/            # Closed-month World/agent/ledger projection
├── flows/                  # SFC flow emission via verified ledger
├── ledger/                 # Ledger-owned financial state, ownership contracts, projections
├── markets/                # Market clearing & price formation
└── mechanisms/             # Domain rules outside market clearing
```

## Core files

| File | Responsibility |
|------|----------------|
| `World.scala` | Root month-boundary state: macro aggregates, domain state segments, pipeline signals, flow diagnostics, and regional wage cache. Financial ownership lives in `LedgerFinancialState`, not `World`. |
| `WorldStateSegments.scala` | Domain state segments carried by `World`: social security/local government, household market, financial markets, external sector, real economy, mechanisms, and monetary plumbing. |
| `PipelineState.scala` | Persisted pre-month decision signal surface plus `DecisionSignals` projection. Same-month operational consumers should use `OperationalSignals`. |
| `FlowState.scala` | Single-step derived flow outputs and diagnostics used by SFC identities, output columns, and characterization exports. |
| `diagnostics/banking/BankDiagnostics.scala` | Bank capital, failure-trigger, resolution, reconciliation, and ECL diagnostics carried through `FlowState`. |
| `ledger/LedgerFinancialState.scala` | Runtime source of truth for supported financial balances; exposes projection DTOs for agent/economics execution. |
| `ledger/AssetOwnershipContract.scala` | Audit contract for supported persisted owner/asset pairs, unsupported stock-like families, and non-persisted runtime shells. |
| `ledger/RuntimeMechanismSurvivability.scala` | Audit contract classifying each runtime-emitted `FlowMechanism` as round-trippable stock, execution-delta-only, or unsupported/metric-only. |
| `ledger/RuntimeFlowProjection.scala` | Typed projection from executed runtime `deltaLedger` into the currently materialized persisted ledger slice. |
| `ledger/BankReserveDiagnostics.scala` | Ledger-backed reserve diagnostics for active bank deposit-facility usage. |
| `MonthSemantics.scala` | Tiny typed phase markers for the internal month step: pre-seed, same-month operational state, closed-month state, and next pre-seed extraction. |
| `MonthExecution.scala` | Same-month result of the ordered economics pipeline; retained as an execution transcript and adapted into narrower downstream boundary views. |
| `MonthClosing.scala` | Explicit closing input/result contracts: narrowed closing-state view, derived mechanisms, diagnostics, agent lifecycle input, and realized month-`t` closing state. |
| `MonthWorkflow.scala` | Minimal identity-monad DSL used to express the deterministic month transition as a typed `for`-comprehension without adding runtime effects. |
| `MonthRandomness.scala` | Explicit month-step randomness contract: one root seed split into named stage and closing streams for deterministic replay and auditability. |
| `MonthDriver.scala` | Shared month-by-month unfold driver over the explicit `FlowSimulation.step` boundary. |
| `OperationalSignals.scala` | Explicit same-month signal surface for month-`t` operational execution, kept distinct from persisted start-of-month `DecisionSignals`. |
| `SignalExtraction.scala` | Explicit closed-to-next-pre boundary: derives next-month `DecisionSignals` and typed seed provenance from realized month-`t` outcomes. |
| `MonthTrace.scala` | Boundary-focused audit artifact with a stable month core (`boundary`, `seedTransition`, `randomness`, validations) plus extensible typed timing envelopes. |
| `closedmonth/MonthClosing.scala` | Explicit month-closing boundary. Consumes `MonthClosingInput` and closing randomness, then returns the realized month-`t` closed state before next seed extraction. |

## Month Step Boundary

The top-level engine shape is now explicit:

```scala
case class SimState(...)
case class MonthOutcome(...)
case class StepOutput(
  stateIn: SimState,
  operationalSignals: OperationalSignals,
  signalExtraction: SignalExtraction.Output,
  randomness: MonthRandomness.Contract,
  semanticFlows: Sfc.SemanticFlows,
  sfcResult: Sfc.SfcResult,
  trace: MonthTrace,
  nextState: SimState,
  ...
)

def step(state: SimState, randomness: MonthRandomness.Contract): StepOutput
object MonthDriver:
  type RandomnessSchedule = SimState => Option[MonthRandomness.Contract]
  def unfoldSteps(initialState: SimState)(schedule: RandomnessSchedule): Iterator[StepOutput]
```

Read it as a month transition:

- `stateIn.world.seedIn` is the persisted `pre` input surface.
- `randomness` is the explicit month-level randomness surface; fixing `stateIn` and `randomness.rootSeed` fixes replay for one step.
- `MonthOutcome` is built through the `MonthWorkflow` identity DSL as `pre -> same-month boundary views -> closed month -> seedOut/next-pre`; the same-month views are `SignalView`, `FlowPlan`, `ClosingInput`, `SemanticProjection`, and `StepEvidence`.
- `operationalSignals` is the explicit same-month surface created inside the step.
- `signalExtraction` is the dedicated `closed -> next-pre` boundary.
- `trace` is the emitted audit artifact for month `t`.
- `nextState` is the typed month `t+1` boundary state. Supported public-fund cash balances are materialized from executed runtime deltas before this boundary is exposed; remaining ledger-backed families still use explicit economics-stage closing state until their runtime emissions become holder-resolved closing-stock sources.
- `MonthDriver.unfoldSteps` is the first-class month driver: callers own the explicit randomness schedule, while the engine owns the `stateIn -> step -> nextState` unfold.

## economics/

The same-month calculus pipeline is executed in fixed order each month. Each
module is a pure function producing quantities, rates, and decisions without
emitting monetary flows. `MonthCalculusRunner` wires them together through the
same `MonthWorkflow` identity DSL, so the operational order is visible as a
typed `for`-comprehension rather than an implicit block of local values.

| File | Domain |
|------|--------|
| `FiscalConstraintEconomics.scala` | Minimum wage indexation, reservation wage, lending base rate |
| `LaborEconomics.scala` | Phillips curve + expectations + union rigidity wages, employment, demographics, immigration |
| `HouseholdIncomeEconomics.scala` | Individual HH income, consumption, saving, portfolio; labor separations, wage updates, bank-specific rates, equity returns, sectoral mobility |
| `DemandEconomics.scala` | Sector demand allocation: HH consumption, government purchases, investment, exports; capacity constraints and spillover |
| `FirmEconomics.scala` / `firm/*` | Firm facade plus staged runner: lending surface, firm decisions, financing splits, bond absorption, I-O market, pricing, labor matching, NPL/default settlement, and output assembly |
| `HouseholdFinancialEconomics.scala` | Mortgage debt service, deposit interest, diaspora remittances, tourism, consumer credit aggregation |
| `PriceEquityEconomics.scala` | Inflation, GPW equity, sigma dynamics, GDP, macroprudential, EU funds |
| `OpenEconEconomics.scala` | BoP/forex, GVC trade, Taylor rule, bond yields, interbank, corporate bonds, insurance, NBFI |
| `BankingEconomics.scala` / `banking/BankingStepRunner.scala` | Bank P&L, provisioning, CAR, multi-bank resolution, bail-in, interbank, BFG levy, monetary aggregates (M1/M2/M3) |

Each module exposes a `StepOutput` boundary type. Modules that historically
named their payload `Output` keep `type Output = StepOutput` aliases for
transitional type references, but new engine code should refer to `StepOutput`.

### economics/firm/

The firm sector is intentionally split like the banking sector: the public
`FirmEconomics.scala` facade delegates to a small `FirmStepRunner`, while
package-local stage modules own the actual same-month mechanics.

| File | Responsibility |
|------|----------------|
| `FirmStepRunner.scala` | Ordered firm-stage workflow expressed through `MonthWorkflow`: lending, processing, bond absorption, market stages, default settlement, output assembly |
| `FirmEconomicsStepDsl.scala` | Typed step composition helpers for the firm workflow |
| `FirmStepInput.scala` | Same-month input boundary from earlier economics stages, world state, agents, ledger state, and trace settings |
| `FirmStepOutput.scala` | Public flattened `StepOutput` consumed by downstream economics, flow emission, diagnostics, and snapshots |
| `FirmStepOutputSections.scala` | Internal grouped sections used to assemble the public `StepOutput` without treating the flattened contract as the domain model |
| `FirmCreditSurface.scala` | Bank-facing credit surface and financing-channel boundary types |
| `FirmLendingStage.scala` | Per-bank lending rates, approval functions, and current-month operational signals for firm decisions |
| `FirmOutcomeProcessor.scala` | Per-firm decision execution, financing split, automation diagnostics, and optional decision trace enrichment |
| `FirmProcessingStage.scala` | Indexed incumbent-firm processing loop with single-pass flow and bond-demand aggregation |
| `FirmFlowTotals.scala` | Firm monetary-flow and credit-audit totals accumulated during processing |
| `FirmFinancing.scala` | Equity, corporate-bond, and residual bank-loan split logic |
| `FirmBondAbsorption.scala` | Catalyst absorption constraint and unsold-bond reversion back into bank loans |
| `FirmMarketStages.scala` | I-O market, Calvo pricing, labor matching, firm default/NPL aggregation, and issuer-debt settlement |
| `FirmOutputAggregation.scala` | Single-pass derived totals for per-bank lending, automation diagnostics, and profitability |
| `FirmStepOutputAssembler.scala` | Final projection from stage internals to the public `StepOutput` |
| `FirmProcessingTypes.scala` / `FirmStageResults.scala` | Package-local boundary types for per-firm outcomes and stage results |

## closedmonth/

Closed-month state projection. This package is intentionally separate from
`economics`: it does not decide market behavior or emit monetary flows. It
takes the already-computed stage outputs, invokes domain transition mechanisms,
and materializes the realized month-`t` state consumed by next-month seed extraction.

| File | Responsibility |
|------|----------------|
| `MonthClosing.scala` | Month-closing contract and top-level ordering for assembling the realized month-`t` world before next-month seed extraction. |
| `WorldStateAssembler.scala` | Builds the closed month-`t` `World` from explicit closing input, domain-mechanism projections, lifecycle results, ledger diagnostics, and flow-of-funds diagnostics. |
| `FlowStateAssembler.scala` | Maps closing input and lifecycle results into `FlowState`, the diagnostic flow surface persisted on `World`. |
| `FlowOfFundsDiagnostics.scala` | Computes the flow-of-funds residual from realized firm revenue and adjusted demand. |

## flows/

SFC-verified monetary flow emission. Every PLN flow is recorded via the
`amor-fati-ledger` (formally verified with Stainless/Z3) and checked by the
exact SFC check: 15 accounting identities, including quasi-fiscal holder
exactness, each month.

| File | Responsibility |
|------|----------------|
| `FlowSimulation.scala` | Sole pipeline entry point for one month. `step(state, randomness)` is the explicit month boundary: it assembles `MonthOutcome`, delegates same-month calculus, flow emission, runtime execution, SFC projection, next-state advancement, and trace construction, and returns typed `nextState` for month `t+1`. |
| `MonthlyCalculus.scala` | Same-month flow-plan contract consumed by `MonthFlowEmitter`; economics outputs are projected here before runtime ledger batches are emitted. |
| `MonthCalculusRunner.scala` | Orchestrates ordered same-month economics through `MonthWorkflow` and returns the month-`t` boundary views consumed by flow emission, signal timing, month closing, and SFC projection. |
| `SameMonthEconomicsDsl.scala` | Identity-monad DSL for the same-month economics order: fiscal -> labor-pre -> household income -> demand -> firm -> labor reconciliation -> household finance -> prices -> open economy -> banking. |
| `SameMonthFlowPlanSource.scala` | Named source surfaces for flow-plan translation: opening financial state, execution transcript, labor/payroll opening facts, and firm-demography facts. |
| `MonthFlowPlanBuilder.scala` | Projects `SameMonthFlowPlanSource` into `MonthlyCalculus`; this is translation glue, not economics execution. |
| `MonthFlowEmitter.scala` | Translates `MonthlyCalculus` into named runtime ledger batches without doing economics or validation. |
| `SfcSemanticProjection.scala` | Converts executed runtime batches plus narrow semantic views into `Sfc.SemanticFlows` and runs the SFC validation boundary. |
| `MonthTraceBuilder.scala` | Builds the month audit trace from explicit step boundaries: start/end snapshots, seed transition, timing envelopes, executed SFC flows, and validation results. |
| `RuntimeFlowExecutor.scala` | Executes emitted runtime batches through the ledger interpreter, captures delta-ledger evidence, and maps execution failures consistently. |
| `NextStateAdvancer.scala` | Owns the closed-month -> next-pre transition: applies extracted `SeedOut`, enforces seed timing invariants, and materializes runtime-supported ledger deltas into the next `SimState`. |
| `FlowMechanism.scala` | Enum of ~80 named flow mechanisms (e.g. `HhTotalIncome`, `HhConsumption`, `BankBfgLevy`). Each flow in the system maps to exactly one mechanism. |
| `ZusFlows.scala` | ZUS/FUS pensions: contributions (HH → FUS), pensions (FUS → HH), gov subvention covering deficit |
| `NfzFlows.scala` | NFZ (National Health Fund): 9% składka zdrowotna, healthcare spending, gov subvention |
| `PpkFlows.scala` | PPK (Pracownicze Plany Kapitałowe): employee + employer contributions |
| `GovBondFlows.scala` | Holder-resolved SPW circuits: primary issuance/redemption and actual waterfall purchases by foreign, NBP, PPK, insurance, and TFI holders |
| `EarmarkedFlows.scala` | Earmarked funds (FP, PFRON, FGSP): contributions, spending, gov subvention covering deficit |
| `HouseholdFlows.scala` | HH aggregate flows: consumption, rent, PIT, debt service, deposits, remittances |
| `FirmFlows.scala` | Firm aggregate flows: household income carrier, CIT, loans, investment, I-O, NPL, FDI |
| `GovBudgetFlows.scala` | Government budget: tax revenue, purchases, benefits, transfers, debt service, capital investment |
| `BankingFlows.scala` | Bank P&L flows: gov bond income, reserve/standing facility/interbank interest, BFG levy, unrealized losses, bail-in, NBP remittance |
| `EquityFlows.scala` | GPW: dividends (domestic net of Belka tax, foreign), equity issuance |
| `CorpBondFlows.scala` | Catalyst: holder-class coupon, default, issuance, and amortization evidence |
| `MortgageFlows.scala` | Housing: origination, principal repayment, interest, default |
| `InsuranceFlows.scala` | Insurance: life + non-life reserve deltas for premiums, claims, and investment income |
| `NbfiFlows.scala` | NBFI/TFI: TFI deposit drain and NBFI credit stock movement evidence |
| `QuasiFiscalFlows.scala` | BGK/PFR: quasi-fiscal bond issuance/amortization, NBP absorption, subsidized lending/repayment, and explicit deposit creation/destruction legs |
| `JstFlows.scala` | JST (local government): PIT/CIT shares, property tax, subventions, spending |
| `OpenEconFlows.scala` | BoP: trade, FDI, portfolio, carry trade, primary income (NFA), secondary income (EU funds, diaspora), tourism, capital flight |

## ledger/

The ledger package defines the engine-side boundary between durable financial
stocks and same-month execution plumbing.

| File | Responsibility |
|------|----------------|
| `LedgerFinancialState.scala` | Persisted financial stock surface owned by the ledger-backed engine slice. |
| `AssetOwnershipContract.scala` | Declares which `(EntitySector, AssetType)` owner pairs are supported persisted stock, which stock-like families remain unsupported, and which runtime nodes are non-persisted execution or settlement shells. Topology-aware checks must be used for concrete emitted batches so aggregate shell indices are not mistaken for persisted owners. |
| `MortgageRuntimeContract.scala` | Names the household-sector mortgage principal settlement shell used to keep runtime `MortgageLoan` flow evidence aggregate while persisted bank-side mortgage stock is mirrored at the month boundary. |
| `RuntimeMechanismSurvivability.scala` | Declares the survivability class for every runtime-emitted `FlowMechanism`. It separates mechanisms whose emitted legs can round-trip through persisted stock owners from mechanisms that are execution-delta-only or intentionally outside the supported persisted stock slice. |
| `RuntimeFlowProjection.scala` | Applies executed runtime ledger deltas to the materialized persisted slice. Today this owns ZUS, NFZ, FP, PFRON, FGSP, and JST cash slots plus quasi-fiscal bond/loan stocks; unsupported/manual slices remain in the stage-produced `LedgerFinancialState` explicitly. |

## markets/

Stateless (or thin-state) market-clearing modules. Each computes
equilibrium prices, quantities, or flows given current state.

| File | Domain |
|------|--------|
| `LaborMarket.scala` | Wage Phillips curve, worker separations, job search with sectoral priority |
| `PriceLevel.scala` | Inflation: demand-pull + cost-push + import pass-through, soft floor |
| `CalvoPricing.scala` | Calvo staggered pricing: per-firm markup lottery (θ=15%), endogenous markup, sticky prices |
| `OpenEconomy.scala` | BoP, floating exchange rate, trade balance, capital account, NFA |
| `FiscalBudget.scala` | Government budget: revenue (CIT/VAT/excise/customs), spending, deficit, bond issuance |
| `FiscalRules.scala` | Polish fiscal rules: SRW (stabilizing expenditure rule), SGP 3% deficit, Art. 216 debt brake, consolidation 55% |
| `EquityMarket.scala` | GPW: WIG index, market cap, dividend yield, foreign-ownership share, issuance |
| `HousingMarket.scala` | House price index (aggregate + 7 regions), mortgage origination/default/amortization |
| `CorporateBondMarket.scala` | Catalyst: corporate bond issuance, coupon, default, demand-side absorption |
| `BondAuction.scala` | SPW primary market: foreign demand f(yield spread vs Bund, ER), absorption constraint |
| `CapitalFlows.scala` | Capital flight: risk-off shock, carry trade (accumulation/unwind), auction confidence signal |
| `GvcTrade.scala` | GVC deep external sector: foreign firm partners, sector-level trade, disruption shocks |
| `IntermediateMarket.scala` | I-O intermediate goods: inter-sector purchases via input-output matrix |
| `RegionalClearing.scala` | Regional labor markets: 6 independent Phillips curves (NUTS-1), population-weighted national wage (Kahan sum) |

## mechanisms/

Domain mechanisms that modify agent behavior or state outside the main
economics-stage market-clearing pipeline.

| File | Domain |
|------|--------|
| `ClimatePolicy.scala` | EU ETS price path and carbon surcharge helpers shared by firm costs and world diagnostics. |
| `EuFunds.scala` | EU structural funds: Beta-curve absorption timing, co-financing, capital investment |
| `Expectations.scala` | Inflation expectations: adaptive-anchoring hybrid, central bank credibility |
| `FdiOwnershipTransitions.scala` | Stochastic FDI M&A mechanism: eligible domestic firms may become foreign-owned at the month-closing transition boundary. |
| `FirmEntry.scala` | Endogenous firm entry: profit-weighted sector choice, regulatory barriers, AI-native startups, and entrant technology diagnostics |
| `InformalEconomy.scala` | Shadow-economy tax evasion diagnostics and counter-cyclical informal-sector state dynamics. |
| `Macroprudential.scala` | CCyB (countercyclical capital buffer), credit-to-GDP gap, O-SII buffers |
| `PopulationLifecycleTransitions.scala` | Post-month agent-population lifecycle: FDI M&A, firm entry, startup staffing, regional migration, and lifecycle diagnostics. |
| `SectoralMobility.scala` | Cross-sector labor transitions: friction matrix, voluntary quits, wage penalties |
| `StartupStaffing.scala` | Startup lifecycle mechanism: assigns workers to newly entered firms and synchronizes startup filled-worker counts with household employment. |
| `TaxRevenue.scala` | Fiscal revenue: VAT, excise, customs, informal-economy evasion adjustments |
| `TourismSeasonality.scala` | 12-month tourism seasonal profile used consistently by tourism flows and world state. |
| `YieldCurve.scala` | Interbank term structure: WIRON overnight → WIBOR 1M/3M/6M with term premia |

## How to extend

**Adding a new market** (e.g., derivatives, crypto):
1. Create `markets/NewMarket.scala` with a `step(...)` or `update(...)` function.
2. Add state to `World.scala` if the market carries state across months.
3. Wire the call into the appropriate economics boundary, usually firm, price/equity, or open-economy execution.
4. If flows affect bank capital, deposits, or government — add a `FlowMechanism` entry and corresponding `*Flows.scala`.

**Adding a new mechanism** (e.g., carbon tax, capital controls):
1. Create `mechanisms/NewMechanism.scala` — pure function, no World dependency.
2. Call it from the relevant economics stage. Mechanisms are typically stateless or
   carry minimal state on `World`.

**Adding a new flow:**
1. Add a case to the `FlowMechanism` enum in `FlowMechanism.scala`.
2. Add the mechanism to `FlowMechanism.emittedRuntimeMechanisms`.
3. Create or extend the appropriate `*Flows.scala` to emit the flow.
4. Wire the batch emission call in `MonthFlowEmitter.emitAllBatches(...)` or the relevant aggregation point.
5. Update `RuntimeMechanismSurvivability.scala` with the mechanism's audit class and ensure representative branch coverage exercises it.
6. Update `SfcSemanticProjection.scala` so exact stock-flow identities cover the new flow.

**SFC rule:** Any flow that modifies bank capital, deposits, government
debt, NFA, bond holdings, or interbank positions **must** be reflected in
the SFC validation projection. The exact SFC check runs every month and will
fail at runtime if the accounting is broken.
