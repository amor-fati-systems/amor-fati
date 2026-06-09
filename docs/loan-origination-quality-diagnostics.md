# Loan Origination Quality Diagnostics

Issue #590 adds an optional diagnostic export that links household and firm
credit-origination quality to later arrears, default, write-off, or bankruptcy
outcomes. The export does not change production behavior.

Operational appendix entry point:
[operations.md#operational-appendix-index](operations.md#operational-appendix-index).
Use this page for loan-origination diagnostic details after starting from the
operations index.

Run the standard review fixture with:

```bash
sbt "loanOriginationQuality --seeds 2 --months 24 --outcome-window 12 --out target/loan-origination-quality --run-id loan-origination-quality"
```

The task writes:

- `loan-origination-quality-households.csv`: household consumer-credit demand,
  underwriting capacity, approved/rejected principal, debt-service ratios,
  opening distress state, bank-side approval product/reason/probability/roll,
  bank gate CAR/LCR/NSFR audit ratios, bank health at origination, and later
  arrears/default/write-off outcomes.
- `loan-origination-quality-firms.csv`: firm credit-decision rows, approved
  principal, sector, size, technology state, cash/debt/profit diagnostics, bank
  health at origination, and later bankruptcy outcomes.
- `loan-origination-quality-summary.csv`: cohort-level future-bad rates by
  household income decile, household distress state, DSR band, firm sector,
  firm size, technology state, and opening bank-CAR band.
- `loan-origination-quality-report.md`: short human-readable summary.

Use larger `--seeds` and `--months` values for research runs. The
`--outcome-window` value controls how many months after origination are checked
for bad outcomes.

## Economic Reading

The diagnostic answers whether bank credit losses are mostly explained by:

- weak borrower underwriting at origination,
- default/write-off mechanics after origination,
- or insufficient bank capital / management-buffer headroom for otherwise
  plausible credit losses.

For households, a future-bad row means later consumer-loan default, liquidity
bridge write-off, or personal insolvency inside the observation window.

For firms, a future-bad row means later bankruptcy inside the observation
window. The firm `DscrProxy` is a narrow monthly diagnostic: realized post-tax
profit divided by the proxy debt-service flow visible in the firm trace.

Household origination rows carry bank approval audit fields including projected
CAR, effective minimum CAR, management CAR target, capital-buffer throttle, LCR,
and NSFR. These columns distinguish hard regulatory rejection from soft
management-buffer quantity rationing.

The export is intentionally micro-level and optional. It should be used for
root-cause investigation, not as a routine production Monte Carlo output.
