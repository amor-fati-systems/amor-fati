# Extension Points

This document gives code-facing recipes for common changes. It is a routing
guide, not a substitute for reading the package README beside the code.

## Add A New Flow Mechanism

Use this path when a same-month economic quantity must become executable
monetary plumbing.

1. Add or reuse a field in
   [`MonthlyCalculus`](../../src/main/scala/com/boombustgroup/amorfati/engine/flows/MonthlyCalculus.scala).
2. Populate it from same-month execution in
   [`MonthFlowPlanBuilder`](../../src/main/scala/com/boombustgroup/amorfati/engine/flows/MonthFlowPlanBuilder.scala).
3. Add the mechanism to
   [`FlowMechanism`](../../src/main/scala/com/boombustgroup/amorfati/engine/flows/FlowMechanism.scala)
   if it is a new named economic flow.
4. Emit batches in the appropriate `*Flows.scala` module, or create a new one
   under `engine/flows/` if the ownership boundary is new.
5. Wire the emitter through
   [`MonthFlowEmitter`](../../src/main/scala/com/boombustgroup/amorfati/engine/flows/MonthFlowEmitter.scala).
6. Classify the runtime survivability in
   [`RuntimeMechanismSurvivability`](../../src/main/scala/com/boombustgroup/amorfati/engine/ledger/RuntimeMechanismSurvivability.scala).
7. If the flow affects supported persisted stocks, update
   [`AssetOwnershipContract`](../../src/main/scala/com/boombustgroup/amorfati/engine/ledger/AssetOwnershipContract.scala)
   and
   [`RuntimeFlowProjection`](../../src/main/scala/com/boombustgroup/amorfati/engine/ledger/RuntimeFlowProjection.scala)
   as needed.
8. Update
   [`SfcSemanticProjection`](../../src/main/scala/com/boombustgroup/amorfati/engine/flows/SfcSemanticProjection.scala)
   and SFC tests if the flow enters exact identities.
9. Add focused tests for the emitter and the owning accounting identity.
10. Update generated SFC matrix evidence if the mechanism changes matrix
    coverage.

Do not compute new behavior inside a `*Flows.scala` emitter. The emitter should
translate already-decided quantities into batches.

## Add A New Same-Month Economics Stage

Use this path when a new decision or market calculation must run inside the
month before flow emission.

1. Add the domain module under `engine/economics/`, `engine/markets/`, or
   `engine/mechanisms/` depending on whether it is same-month calculus, market
   clearing, or persistent mechanism state.
2. Define a named input boundary and a `StepOutput` or result type. Prefer
   named boundary case classes over long positional argument lists.
3. Insert the stage in
   [`MonthCalculusRunner`](../../src/main/scala/com/boombustgroup/amorfati/engine/flows/MonthCalculusRunner.scala)
   through the existing `MonthWorkflow` order.
4. Add fields to `MonthExecution` or a narrower stage output only where a
   downstream stage actually needs them.
5. Project any flow-relevant quantities through `MonthFlowPlanBuilder`.
6. Add unit/property tests for the stage and a runtime test if the new stage
   changes the shared month boundary.

Changing the stage order is an architectural change. Update
[runtime-loop.md](runtime-loop.md) and consider an ADR.

## Add A New Agent Or Sector

Use this path when the model gains a new autonomous population or institutional
sector.

1. Start with the [agents package README](../../src/main/scala/com/boombustgroup/amorfati/agents/README.md)
   to pick the local structure: facade, state, submodules, and tests.
2. Add initialization under `init/` and wire it through `WorldInit` if the
   sector exists at month zero.
3. Decide where persistent state belongs:
   - behavioral state in an agent `State`;
   - macro or market memory in a `World` segment;
   - ledger-backed financial stock in `LedgerFinancialState`.
4. Add same-month execution in the appropriate economics or mechanism stage.
5. Add flow mechanisms and emitters for monetary flows.
6. Add SFC semantic projection and matrix evidence for accounting identities.
7. Add output columns or diagnostics only after the state and flow contracts are
   stable enough to expose.

If the sector owns monetary stocks, read
[state-and-ledger-boundary.md](state-and-ledger-boundary.md) before choosing a
state location.

## Add A Scenario

Named scenarios are configuration and diagnostics contracts, not hidden
runtime branches.

1. Add or update scenario configuration in `config/ScenarioRegistry.scala`.
2. Add tests under `src/test/scala/com/boombustgroup/amorfati/config/`.
3. Update [scenario-registry.md](../scenario-registry.md).
4. If the scenario should produce committed evidence, use the existing
   `scenarioRun` sbt task and generated-output workflow.
5. If it changes hard invariants, update
   [engine-invariants-and-semantics.md](../engine-invariants-and-semantics.md).

## Add A Diagnostic Or Generated Evidence Export

Use this path when producing new TSV, Markdown, or report artifacts.

1. Add the exporter under `diagnostics/`.
2. Add an sbt `inputKey` in `build.sbt` if it should be runnable as a named
   repository task.
3. Add focused exporter tests under `src/test/scala/com/boombustgroup/amorfati/diagnostics/`.
4. Document the artifact in `docs/` and classify it in
   [docs/README.md](../README.md).
5. If committed output must stay fresh, extend
   `scripts/check-generated-outputs.sh`.
6. If it feeds nightly or profiling workflows, update
   [validation-matrix.md](../validation-matrix.md) and the relevant operations
   appendix.

## Add A Monte Carlo Output Column

1. Add the source value to the month runtime evidence or terminal state. Avoid
   recomputing hidden economic logic inside output code.
2. Add the column definition in `McTimeseriesSchema` or the appropriate
   snapshot/summary schema.
3. Add or update schema tests under `src/test/scala/com/boombustgroup/amorfati/montecarlo/`.
4. If the column becomes a documented invariant or health signal, update
   [engine-invariants-and-semantics.md](../engine-invariants-and-semantics.md)
   and the relevant diagnostic docs.

## Add A Test

Choose the smallest test layer that protects the contract:

| Contract | Preferred test layer |
| --- | --- |
| Pure rule, market helper, or bounded arithmetic | Unit or property test in `src/test`. |
| Flow batch shape or mechanism coverage | `engine/flows/*Spec` plus survivability/ownership tests if needed. |
| Supported ledger stock projection | `engine/ledger/*Spec` and runtime projection tests. |
| Full one-month boundary | `FlowSimulation*Spec`, `NextStateAdvancerSpec`, or `MonthDriverSpec`. |
| Short normal-path runtime health | `integration-tests` project. |
| TSV/output contract | Monte Carlo or diagnostics schema/export specs. |
| Generated evidence freshness | `scripts/check-generated-outputs.sh` and docs inventory. |

Tests should protect the boundary being changed. Avoid broad integration tests
for narrow pure functions unless the change also affects the shared runtime
path.

## Add A Numeric Domain Type

Use this path only when an existing semantic type cannot describe the value.
Most new model parameters should use an existing type such as `PLN`, `Rate`,
`Share`, `Scalar`, `Multiplier`, `Coefficient`, `PriceIndex`, `Sigma`, or
`ExchangeRate`.

1. Add a provider under `src/main/scala/com/boombustgroup/amorfati/fp/` using
   the shared `FixedPointBase` scale and rounding helpers.
2. Export the type and companion from
   [`types.scala`](../../src/main/scala/com/boombustgroup/amorfati/types.scala).
3. Define cross-type operations centrally in `types.scala` when the operation
   is meaningful across domains.
4. Add focused coverage in
   [`OpaqueTypesSpec`](../../src/test/scala/com/boombustgroup/amorfati/OpaqueTypesSpec.scala)
   or an equivalent numeric-contract spec.
5. Avoid introducing raw `Double` into core economics, runtime flow emission,
   ledger-boundary, or SFC code. Use `BigDecimal` at parsing/test/reporting
   boundaries only when needed, then convert into typed fixed-point values.

This path is governed by
[ADR-0005](../adr/0005-fixed-point-domain-numerics.md).
