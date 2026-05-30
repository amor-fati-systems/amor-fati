# ADR 0001: Bank Capital SFC Semantics

Status: accepted

Date: 2026-05-24

## Context

The engine stores bank capital on each `Banking.BankState` row as
`BankState.capital`. `LedgerFinancialState.BankBalances` intentionally contains
ledger-owned financial stocks such as deposits, loans, reserves, government
bonds, corporate bonds and mortgage assets; it does not contain a bank-capital
ownership slot.

`AssetOwnershipContract` therefore classifies `AssetType.Capital` as
`UnsupportedPersistedStock`: the stock exists in engine state and is validated
by the SFC exactness layer, but it is not part of the supported transferable
ledger-owned stock slice. The SFC matrix keeps a bank-capital row so monthly
capital changes remain auditable.

The open design choice is whether bank capital should now become full
SFC-owned equity with explicit holders, or remain regulatory/accounting state
with explicit diagnostics.

## Decision

Bank capital remains a regulatory/accounting bank buffer, not a supported
ledger-owned equity stock.

The authoritative runtime field is `Banking.BankState.capital`. It is persisted
operational state used by CAR, liquidity/failure logic, resolution diagnostics,
SFC exactness checks and bank diagnostics. It must not be interpreted as
holder-resolved household, fund, insurer, government or foreign equity.

Opening bank capital comes from bank calibration and `BankInit`, allocated
across model bank rows by the configured bank market shares. It does not come
from household, fund, insurer, government or foreign portfolio allocation.

Monthly bank-capital movements are validated as a P&L and loss-recognition
waterfall:

- retained bank income increases the buffer;
- credit losses, corporate-bond losses, interbank contagion losses, bond
  valuation losses, BFG levy, provision changes and failed-bank capital
  destruction reduce it;
- bail-in is a depositor haircut and deposit-resolution channel, reported near
  bank-capital diagnostics but not treated as bank-equity ownership transfer.

The unretained `(1 - profitRetention)` share of bank gross income has no
modeled owner-side distribution today. That is consistent with this decision:
there is no holder-resolved bank equity receiver. The #576 inventory should
record it as a known unowned income distribution rather than a missing flow to
route into bank capital.

Resolution ordering is explicit: failure marking first wipes
`BankState.capital` for newly failed banks, current-event bail-in then haircuts
eligible depositor claims, and purchase-and-assumption resolution transfers the
remaining failed-bank balance-sheet rows. Capital destruction and depositor
bail-in are SFC-validated through separate identities, `BankCapital` and
`BankDeposits`, not through holder-resolved equity transfer.

The SFC counterpart for those movements is the explicit semantic-flow and
stock-flow reconciliation evidence, not final-beneficiary equity ownership.
`AssetType.Equity` remains the supported listed-equity instrument; it must not
be overloaded to mean bank regulatory capital.

## Consequences

Diagnostics may read `BankState.capital` when they explicitly label it as the
regulatory/accounting bank-capital buffer. CAR and failure diagnostics remain
valid uses.

`AssetType.Capital` remains outside `AssetOwnershipContract.supportedPairs`.
It stays visible as an unsupported persisted diagnostic family so gaps are
intentional and auditable.

Issues that add holder-resolved bank equity are not needed under this decision:
#574 (bank-equity ledger contract) and #575 (opening ownership allocation) are
unnecessary unless the decision is revisited.

#576 reduces to an inventory and guardrail: enumerate every site that writes
`BankState.capital`, classify each site against the waterfall categories above,
and add a regression check that fails when a new writer appears without a
category. The live inventory is
`engine.economics.banking.BankCapitalSemantics.writeSites`.

#577 reduces to diagnostic wording: keep `BankBalanceSheetBenchmarkExport`
reading `BankState.capital`, and keep
`docs/bank-balance-sheet-benchmark.md` section "Bank Capital Semantics" as the
reviewer-facing statement of the capital source.

Bank-equity work should only be reopened if the model introduces explicit bank
share issuance, dividends, public recapitalisation, nationalisation, or other
ownership-changing bank-equity mechanisms, including a bank acquisition or
purchase-and-assumption variant that transfers bank equity rather than only
assets and liabilities.

Any new mechanism that changes `BankState.capital` must update the SFC
validation projection and the bank-capital diagnostics, even though bank
capital is not a supported ledger-owned stock.

## Code References

- `Banking.BankState.capital`
- `Banking.Aggregate.capital`
- `LedgerFinancialState.BankBalances`
- `AssetOwnershipContract.publicAsset(AssetType.Capital)`
- `AssetOwnershipContract.UnsupportedFamilyId.BankCapital`
- `Sfc.MonthlyFlows.bankCapitalDestruction`
- `Sfc.SfcIdentity.BankCapital`
- `engine.economics.banking.FailureDetectionResult.capitalDestruction`
- `engine.economics.banking.ReconciledResolutionResult.capitalDestruction`
- `SfcSemanticProjection.bankCapitalDestruction`
- `engine.economics.banking.BankCapitalSemantics.writeSites`
- `BankBalanceSheetBenchmarkExport`
