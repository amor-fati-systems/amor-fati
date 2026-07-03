# ADR-0001: Ledger-First Runtime

Status: Accepted

Date: 2026-04-02

## Historical Provenance

This retroactive ADR records a contract that entered `main` in stages:

| Anchor | Main commit |
| --- | --- |
| First explicit flow-simulation skeleton | `8785299e` FlowSimulation skeleton: Contract-First MonthlyCalculus -> emitAllFlows (#157), 2026-03-25 |
| Primary ledger-execution anchor | `7dd07232` Execute runtime flow batches via imperative ledger runtime (#224), 2026-04-02 |
| SFC validation tied to runtime state and ledger execution | `fa942061` Redesign SFC validation around runtime state and ledger execution (#235), 2026-04-02 |

## Context

Amor Fati is an SFC-ABM where rich behavioral rules can change frequently, but
executed monetary plumbing must not silently create or destroy value. A
traditional ABM implementation could update agent balances directly and treat
accounting as a later diagnostic check. That would make model iteration easier
in the short term, but it would also allow implementation drift to hide inside
state updates.

The project already depends on the verified `amor-fati-ledger` repository and
uses exact SFC validation as a hard research and engineering contract.

## Decision

Runtime monetary flows are emitted as named ledger batches and executed through
the ledger interpreter before the next public month boundary is returned.

Same-month economics may decide quantities, prices, rates, defaults, issuance,
and policy responses. It must not become the accounting execution layer. The
runtime flow path is:

```text
same-month economics
-> MonthlyCalculus
-> FlowMechanism batches
-> ledger execution
-> supported next-state materialization
-> SFC semantic validation
```

## Consequences

- Flow emission must stay explicit and named by `FlowMechanism`.
- Ledger conservation failures are engine failures, not calibration issues.
- SFC projection remains a semantic validation oracle over executed evidence
  and currently non-runtime semantic terms.
- Some stock families can remain unsupported or execution-delta-only, but they
  must be classified visibly instead of hidden behind residual balancing.
- Adding a monetary mechanism usually requires code changes in flow emission,
  survivability classification, SFC projection, and tests.

## References

- [Runtime loop](../architecture/runtime-loop.md)
- [State and ledger boundary](../architecture/state-and-ledger-boundary.md)
- [SFC matrix evidence](../sfc-matrix-evidence.md)
- [`FlowSimulation.scala`](../../src/main/scala/com/boombustgroup/amorfati/engine/flows/FlowSimulation.scala)
- [`MonthFlowEmitter.scala`](../../src/main/scala/com/boombustgroup/amorfati/engine/flows/MonthFlowEmitter.scala)
- [`RuntimeFlowExecutor.scala`](../../src/main/scala/com/boombustgroup/amorfati/engine/flows/RuntimeFlowExecutor.scala)
- [amor-fati-ledger repository](https://github.com/boombustgroup/amor-fati-ledger)
