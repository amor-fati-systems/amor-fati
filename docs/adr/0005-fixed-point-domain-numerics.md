# ADR-0005: Fixed-Point Domain Numerics

Status: Accepted

Date: 2026-07-03

## Context

Amor Fati executes monetary flows, stock-flow identities, and long multi-month
simulation paths. Floating-point arithmetic would make exact accounting,
repeatable replay, and stable SFC residual interpretation harder: small binary
rounding differences can accumulate and can make a bookkeeping error look like
numeric noise.

The engine also has several semantically different numeric domains that should
not be freely interchangeable: money, rates, shares, multipliers,
coefficients, price indices, exchange rates, and generic scalars.

## Decision

Domain numerics use Long-backed fixed-point opaque types at scale `10^4`.
The shared implementation lives in `fp/`, and the public engine imports these
types through `types.scala`.

Core domain code should use semantic types such as `PLN`, `Rate`, `Share`,
`Scalar`, `Multiplier`, `Coefficient`, `PriceIndex`, `Sigma`, `ExchangeRate`,
and `ExchangeRateShock` instead of raw `Double`, raw `BigDecimal`, or untyped
`Long` values.

Cross-type operations are defined centrally where the opaque types remain
opaque, so the compiler can prevent nonsensical arithmetic such as adding money
to a rate. Rounding is explicit and shared through `FixedPointBase`, using
banker's rounding for fixed-point intermediate results.

## Consequences

- Runtime monetary and SFC paths can compare exact raw values instead of
  treating accounting residuals as floating-point tolerance questions.
- Numeric intent is visible in type signatures.
- New economics code must choose the correct semantic numeric type rather than
  falling back to `Double`.
- Boundary adapters, tests, and external data bridges may use `BigDecimal` for
  parsing, expectations, or comparison, but should convert into typed
  fixed-point values before entering domain execution.
- Adding a new numeric domain requires a provider in `fp/`, exports from
  `types.scala`, and coverage in `OpaqueTypesSpec` or a similarly focused test.

## References

- [Architecture overview](../architecture/overview.md)
- [Extension points](../architecture/extension-points.md)
- [`types.scala`](../../src/main/scala/com/boombustgroup/amorfati/types.scala)
- [`FixedPointBase.scala`](../../src/main/scala/com/boombustgroup/amorfati/fp/FixedPointBase.scala)
- [`PLNProvider.scala`](../../src/main/scala/com/boombustgroup/amorfati/fp/PLNProvider.scala)
- [`RateProvider.scala`](../../src/main/scala/com/boombustgroup/amorfati/fp/RateProvider.scala)
- [`ScalarProvider.scala`](../../src/main/scala/com/boombustgroup/amorfati/fp/ScalarProvider.scala)
- [`OpaqueTypesSpec.scala`](../../src/test/scala/com/boombustgroup/amorfati/OpaqueTypesSpec.scala)
