# ADR-0003: Separate Verified Ledger Module

Status: Accepted

Date: 2026-07-03

## Context

The accounting kernel has a different stability profile from the macro model.
Macroeconomic behavior, calibration, scenarios, and agent heuristics are
expected to evolve. The ledger interpreter is the accounting substrate that
must remain small, testable, and formally constrained.

Keeping the ledger inside the main engine package would blur this boundary and
make it harder to reason about which guarantees come from the accounting kernel
and which come from Amor Fati's model-specific usage of it.

## Decision

The verified accounting kernel lives in `modules/ledger` as a separate sbt
module. The root Amor Fati project depends on it and adapts it through
engine-side topology, ownership, flow emission, runtime execution, and
projection code.

The ledger module owns the generic double-entry execution model. The Amor Fati
engine owns:

- which owners and assets are supported persisted stocks;
- how runtime topology maps model sectors to ledger accounts;
- how economic mechanisms emit ledger batches;
- how executed deltas are projected into `LedgerFinancialState`;
- how SFC semantic validation interprets the result.

## Consequences

- Ledger verification and engine model validation remain separate concerns.
- The public ledger API can be wider than the engine's supported persisted
  stock slice.
- Engine-side ownership contracts must document what the model actually
  persists and what is currently unsupported or metric-only.
- Changes to `modules/ledger` should be treated as lower-level accounting
  changes, not routine model calibration edits.

## References

- [State and ledger boundary](../architecture/state-and-ledger-boundary.md)
- [Ledger module README](../../modules/ledger/README.md)
- [`AssetOwnershipContract.scala`](../../src/main/scala/com/boombustgroup/amorfati/engine/ledger/AssetOwnershipContract.scala)
- [`RuntimeFlowProjection.scala`](../../src/main/scala/com/boombustgroup/amorfati/engine/ledger/RuntimeFlowProjection.scala)
- [`build.sbt`](../../build.sbt)
