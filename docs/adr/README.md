# Architecture Decision Records

This directory stores durable architecture decision records. ADRs preserve
accepted trade-offs and their consequences. They are not living tutorials; the
current architecture narrative lives in [docs/architecture](../architecture/).

ADR-0001 through ADR-0005 are retroactive records for decisions already
embedded in the codebase before this ADR series was started. Later ADRs should
normally be written when a new durable decision is made or an existing one is
revised.

Each retroactive ADR includes a `Historical Provenance` section with mainline
commit anchors found from file history and symbol-level searches. These anchors
are evidence of when the decision entered `main`; for decisions that evolved
over multiple PRs, the ADR records both the primary anchor and relevant
supporting commits.

## Records

| ADR | Decision |
| --- | --- |
| [ADR-0001](0001-ledger-first-runtime.md) | Runtime monetary flows execute through the ledger-first path. |
| [ADR-0002](0002-explicit-month-boundary.md) | `FlowSimulation.SimState` and explicit month randomness define the public month boundary. |
| [ADR-0003](0003-separate-verified-ledger-module.md) | The verified accounting kernel lives in the separate `modules/ledger` sbt module. |
| [ADR-0004](0004-ledger-owned-financial-state.md) | Supported ledger-backed financial stocks live in `LedgerFinancialState`. |
| [ADR-0005](0005-fixed-point-domain-numerics.md) | Domain numerics use Long-backed fixed-point opaque types instead of untyped floating-point values. |

## Format

New ADRs should use this shape:

```text
# ADR-NNNN: Title

Status: Proposed | Accepted | Superseded

Date: YYYY-MM-DD

## Context
## Decision
## Consequences
## References
```

When an ADR supersedes another ADR, keep both files and mark the old record as
superseded with a link to the replacement.
