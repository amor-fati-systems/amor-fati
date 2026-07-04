# State And Ledger Boundary

Amor Fati separates behavioral state, macro/runtime state, and ledger-owned
financial stock state. This split is the main architectural reason the engine
can support rich agent behavior while keeping accounting-critical state
auditable.

## Boundary State

The public month boundary is `FlowSimulation.SimState`:

| Field | Owner | Role |
| --- | --- | --- |
| `completedMonth` | Engine | Month lineage. The next execution month is always `completedMonth.next`. |
| `world` | Engine | Macro, market, mechanism, signal, and diagnostic state at the month boundary. |
| `firms` | Agents | Firm behavioral state vector. |
| `households` | Agents | Household behavioral state vector. |
| `banks` | Agents | Bank operational state vector, including regulatory and resolution state. |
| `householdAggregates` | Agents/engine boundary | Derived aggregate household state used by later stages and output schemas. |
| `ledgerFinancialState` | Engine ledger boundary | Source of truth for supported ledger-backed financial stocks. |

`World` is the macro/runtime root. It owns domain state segments, pipeline
signals, flow diagnostics, market memory, and mechanism state. It should not
become a dumping ground for supported financial ownership.

## LedgerFinancialState

[`LedgerFinancialState`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/engine/ledger/LedgerFinancialState.scala)
is narrower than `World`. It stores ledger-backed stock balances for households,
firms, banks, government, foreign sector, NBP, insurance, and funds.

The important contract is:

```text
ledger-contracted financial stock -> LedgerFinancialState
behavioral or operational state -> agent state or World segment
single-month diagnostics -> FlowState, MonthTrace, or exporter-specific rows
execution-only shells -> runtime topology, not persisted owners
```

Boundary code may project ledger balances into agent or economics DTOs for
calculation. Those DTOs are not additional persisted owners.

## Ownership Contract

[`AssetOwnershipContract`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/engine/ledger/AssetOwnershipContract.scala)
declares how the engine treats public ledger asset types:

| Status | Meaning |
| --- | --- |
| `SupportedPersistedStock` | The asset has an engine-owned persisted stock contract and participates in the current supported ledger slice. |
| `UnsupportedPersistedStock` | The engine has real persisted state for the asset family, but it is intentionally outside the supported ledger-owned slice for now. |
| `PublicAssetWithoutEngineContract` | The public ledger API exposes the asset, but the engine has no persisted ownership contract for it. |

The public `amor-fati-ledger` API is wider than the engine's current supported
persisted slice. Do not infer that a ledger asset type is a supported engine
stock merely because the external ledger API can represent it.

## Runtime Flow Survivability

[`RuntimeMechanismSurvivability`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/engine/ledger/RuntimeMechanismSurvivability.scala)
classifies emitted `FlowMechanism` batches:

| Classification | Meaning |
| --- | --- |
| `RoundTrippableStock` | Observed batch sides are supported persisted stock owners for the concrete runtime topology. |
| `ExecutionDeltaOnly` | The asset family may be supported, but at least one side is an aggregate execution or settlement shell. |
| `UnsupportedOrMetricOnly` | The mechanism uses an unsupported persisted family or a public metric/backstop asset without an engine persisted-stock contract. |

This matrix is an audit contract. It keeps runtime flow evidence honest about
which mechanisms can round-trip into persisted stock owners today.

## Runtime Delta Materialization

The ledger interpreter returns month deltas over concrete
`(EntitySector, AssetType, index)` accounts. Not every delta becomes the next
boundary stock.

[`RuntimeFlowProjection`](../../modules/model/src/main/scala/com/boombustgroup/amorfati/engine/ledger/RuntimeFlowProjection.scala)
materializes only the supported persisted slice that has a one-to-one owner in
`LedgerFinancialState`. At the time of this architecture record, public-fund
cash slots and quasi-fiscal bond/loan stocks are materialized from executed
runtime deltas. Other families continue to come from semantic closing state
until their holder-resolved runtime emissions are precise enough to own the
closing stock.

This is intentional. Unsupported or partially supported families must remain
visible instead of being balanced by residuals.

## Initialization Boundary

`WorldInit` assembles both behavioral state and the initial
`LedgerFinancialState`. Agent financial-stock DTOs created during initialization
are inputs to the ledger state. After initialization, supported financial stocks
should be read from `LedgerFinancialState` or explicit projections from it.

See the [init package README](../../modules/model/src/main/scala/com/boombustgroup/amorfati/init/README.md).

## Change Rules

When adding or moving a financial stock:

1. Decide whether it is behavioral state, macro/runtime state, diagnostic state,
   unsupported persisted state, or supported ledger-owned stock.
2. If it is supported ledger-owned stock, add it to `LedgerFinancialState` and
   `AssetOwnershipContract`.
3. If runtime batches emit it, classify the mechanisms in
   `RuntimeMechanismSurvivability`.
4. If executed deltas should own the next boundary value, extend
   `RuntimeFlowProjection`.
5. Update SFC semantic projection, matrix evidence, and invariants where the
   stock participates in accounting identities.
6. Add tests at the lowest useful level: ownership contract, runtime projection,
   flow emission, SFC identity, and integration path if the boundary is shared.

Avoid adding ad hoc monetary balances to `World` or agent state when they are
really ledger-owned stocks. That creates a second source of truth and weakens
the engine's accounting contract.
