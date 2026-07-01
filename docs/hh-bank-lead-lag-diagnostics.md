# HH-to-Bank Lead-Lag Diagnostics

This diagnostic export supports issue #584. It connects household stress flows
to bank outcomes by `BankId` and month, then separates descriptive lead-lag
correlation from controlled counterfactual evidence.

Operational appendix entry point:
[operations.md#operational-appendix-index](operations.md#operational-appendix-index).
Use this page for HH-bank lead-lag diagnostic details after starting from the
operations index.

Run:

```bash
sbt "hhBankLeadLagDiagnostics --seeds 2 --months 24 --lag-max 6 --out target/hh-bank-lead-lag --run-id hh-bank-lead-lag"
```

The export uses the shared Monte Carlo diagnostic runner. Scenario/seed jobs
run with bounded `mapZIOPar` parallelism on the same monthly seed stream as
`Main`; months inside a seed remain sequential.

The task writes:

```text
<out>/<run-id>/hh-bank-lead-lag-bank-months.tsv
<out>/<run-id>/hh-bank-lead-lag-correlations.tsv
<out>/<run-id>/hh-bank-lead-lag-counterfactuals.tsv
<out>/<run-id>/hh-bank-lead-lag-report.md
```

## Bank-Month Rows

`hh-bank-lead-lag-bank-months.tsv` is the join surface. Each row is one
`RunId` / scenario / seed / month / bank. Household flows are assigned to the
bank referenced by the household during the household stage of that month.

Key household-side columns:

- `HhConsumerLoanDefault`: ordinary unsecured consumer-loan default.
- `HhConsumerInsolvencyDefault`: subset of consumer-loan default produced by
  personal-insolvency filing/workout.
- `HhLiquidityBridgeChargeOff`: same-month liquidity bridge/write-off,
  separate from ordinary consumer-loan default.
- `HhLiquidityShortfallFinancing`: residual monthly liquidity gap closed by the
  bridge mechanism.
- `HhConsumerApprovedOrigination`, `HhConsumerRejectedOrigination`, and
  `HhConsumerBankRejectedOrigination`: underwritten household consumer-credit
  origination, total rejected demand, and the bank-side rejected subset.
- `HhConsumerDebtArrears` and `HhMortgageArrears`: shortfall components.

Key bank-side columns:

- `BankConsumerNplLoss`: aggregate realized household-credit capital loss net of
  recovery and any product allowance draw for ordinary consumer-loan and
  personal-insolvency channels. Liquidity-bridge charge-off is exported as a
  separate HH metric and as `BankCreditLoss_*` product diagnostics, but the
  same-month bridge settlement does not add a capital loss by default.
- `BankConsumerNplStock`: closing consumer NPL stock.
- `BankCapital`, `BankCapitalDelta`, `BankCar`, `BankLcr`: closing bank stress
  state.
- `BankNewFailure`: 1 when the bank moves from not failed to failed in that
  month.
- `BankFailureReasonCode`: populated for the first new failure event when the
  aggregate failure diagnostic identifies that bank.

## Correlation Versus Causality

`hh-bank-lead-lag-correlations.tsv` computes Pearson correlations over lag
windows `0..--lag-max`. For lag `k`, household metric values at `t-k` are
paired with bank outcomes at `t` for the same scenario, seed, and bank.

These correlations answer: "does HH stress tend to move before this bank
outcome?" They do not prove causality because a common macro shock can move
households and banks together.

`hh-bank-lead-lag-counterfactuals.tsv` is the controlled intervention check. It
compares:

- `baseline`: production configuration.
- `no-consumer-npl-capital-hit`: household consumer defaults remain visible,
  but unsecured consumer-credit recovery is set to 100%, removing the direct
  consumer NPL capital hit.

If failures materially fall in the counterfactual while household default flows
remain visible, the direct consumer NPL capital-hit channel is necessary for
failure timing in that fixture. This is model-internal intervention evidence,
not full macro-causal identification.
