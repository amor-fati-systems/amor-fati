# ADR-0004: Ledger-Owned Financial State

Status: Accepted

Date: 2026-07-03

## Context

Agent-based models naturally accumulate state on agents: household deposits,
firm loans, bank assets, reserves, securities, and sector balances. In Amor
Fati, that pattern would create multiple sources of truth for accounting
state, because the same financial stock would also need to participate in
ledger execution, SFC validation, and matrix evidence.

The model still needs rich behavioral state on agents and market memory in
`World`, but accounting-critical financial ownership needs a stricter boundary.

## Decision

Supported ledger-backed financial stocks live in `LedgerFinancialState`.
Agent states and `World` may carry behavioral state, operational state,
diagnostic state, market memory, and explicitly unsupported legacy metrics, but
they should not become parallel sources of truth for supported ledger-owned
stocks.

Projection from `LedgerFinancialState` into agent or economics DTOs is allowed
for calculation. Those DTOs are execution inputs, not persisted stock owners.

## Consequences

- Adding a supported financial stock requires an explicit ledger-state and
  ownership-contract change.
- Runtime flow mechanisms must be classified by survivability before auditors
  can treat them as round-trippable persisted stock evidence.
- Unsupported or partially supported stock families must remain visible as
  known limitations.
- Initialization may construct financial-stock DTOs, but `WorldInit` must fold
  supported opening balances into `LedgerFinancialState`.
- Package docs should distinguish behavioral state from ledger-owned financial
  state whenever an agent holds monetary balances.

## References

- [State and ledger boundary](../architecture/state-and-ledger-boundary.md)
- [Engine invariants and semantics](../engine-invariants-and-semantics.md)
- [`LedgerFinancialState.scala`](../../src/main/scala/com/boombustgroup/amorfati/engine/ledger/LedgerFinancialState.scala)
- [`World.scala`](../../src/main/scala/com/boombustgroup/amorfati/engine/World.scala)
- [`RuntimeMechanismSurvivability.scala`](../../src/main/scala/com/boombustgroup/amorfati/engine/ledger/RuntimeMechanismSurvivability.scala)
- [`WorldInit.scala`](../../src/main/scala/com/boombustgroup/amorfati/init/WorldInit.scala)
