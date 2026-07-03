# ADR-0003: Separate Verified Ledger Repository

Status: Accepted

Date: 2026-03-24

## Historical Provenance

This retroactive ADR's primary mainline anchor is `224bb12f` Integrate
amor-fati-ledger-poc as git submodule (#115), merged on 2026-03-24. Later
commits updated the submodule pointer and expanded engine-side usage, but this
commit established the separate ledger repository boundary.

## Context

The accounting kernel has a different stability profile from the macro model.
Macroeconomic behavior, calibration, scenarios, and agent heuristics are
expected to evolve. The ledger interpreter is the accounting substrate that
must remain small, testable, and formally constrained.

Keeping the ledger inside the main engine package or repository would blur this
boundary and make it harder to reason about which guarantees come from the
accounting kernel and which come from Amor Fati's model-specific usage of it.

## Decision

The verified accounting kernel lives in the separate
[amor-fati-ledger](https://github.com/boombustgroup/amor-fati-ledger)
repository. Amor Fati checks that repository out as a Git submodule under
`modules/ledger` and references it from the root build through an sbt
`ProjectRef`.

The root Amor Fati project depends on that submodule checkout and adapts it
through engine-side topology, ownership, flow emission, runtime execution, and
projection code.

The external ledger repository owns the generic double-entry execution model.
The Amor Fati engine owns:

- which owners and assets are supported persisted stocks;
- how runtime topology maps model sectors to ledger accounts;
- how economic mechanisms emit ledger batches;
- how executed deltas are projected into `LedgerFinancialState`;
- how SFC semantic validation interprets the result.

## Consequences

- Ledger verification and engine model validation remain separate concerns.
- The public ledger API can be wider than the engine's supported persisted
  stock slice.
- The submodule pointer is an explicit dependency boundary: changing the
  checked-out ledger revision is a lower-level accounting-kernel update, even
  when the local path is `modules/ledger`.
- Engine-side ownership contracts must document what the model actually
  persists and what is currently unsupported or metric-only.
- Changes to the `amor-fati-ledger` repository should be treated as lower-level
  accounting changes, not routine model calibration edits.

## References

- [State and ledger boundary](../architecture/state-and-ledger-boundary.md)
- [amor-fati-ledger repository](https://github.com/boombustgroup/amor-fati-ledger)
- [`AssetOwnershipContract.scala`](../../src/main/scala/com/boombustgroup/amorfati/engine/ledger/AssetOwnershipContract.scala)
- [`RuntimeFlowProjection.scala`](../../src/main/scala/com/boombustgroup/amorfati/engine/ledger/RuntimeFlowProjection.scala)
- [`build.sbt`](../../build.sbt)
