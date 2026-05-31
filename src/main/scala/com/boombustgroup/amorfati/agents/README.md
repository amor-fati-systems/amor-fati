# Autonomous Economic Agents

The agents package contains every autonomous agent in the SFC-ABM model.
Each agent is an `object` with a nested `case class State` and pure functions
that transform state. No mutable fields — state transitions produce new immutable instances.

All agents that modify monetary flows participate in the SFC accounting check.
Ledger-contracted financial stocks live in `LedgerFinancialState`; agent states
carry behavioral state, operational diagnostics, and legacy unsupported metrics.

## Agents

| File | Agent | State | Key SFC identities |
|------|-------|-------|-------------------|
| `Banking.scala` | 10 banking-sector rows: named bank archetypes plus residual Other banks (Poland 2026-04-30 baseline) | Public banking facade and domain types; implementation is split under `banking/` by credit, interbank, resolution, bond portfolio, capital, defaults, and regulatory metrics | BankCapital, BankDeposits, BondClearing, InterbankNetting |
| `Firm.scala` | Heterogeneous firms (6 sectors) | Public firm facade and domain types; implementation is split under `firm/` by decision engine, execution, post-processing, P&L, credit audit, and trace construction | BankCapital (NPL), FlowOfFunds, CorpBondStock |
| `Household.scala` | Individual households | Public household facade and domain types; implementation is split under `household/` by income, credit, liquidity, distress, labor transitions, monthly flow construction, and aggregate computation | BankDeposits, ConsumerCredit |
| `Immigration.scala` | Immigrant workers | Stock, monthly inflow/outflow, remittance outflow | BankDeposits (remittance → deposit outflow), Nfa |
| `Insurance.scala` | Life + non-life sector | Premium, claim, and investment-income diagnostics; reserves and securities holdings are ledger projections | BankDeposits (premium/claims), BondClearing |
| `Jst.scala` | Local government (JST) | Revenue, spending, deficit, unsupported debt metric; cash is ledger-owned | BankDeposits (JST deposits), JstDebt |
| `Nbfi.scala` | TFI funds + NBFI credit | Origination, default, and deposit-drain diagnostics; AUM, bond/equity holdings, cash, and loan stock are ledger projections | BankDeposits (deposit drain), BondClearing (TFI bonds), NbfiCredit |
| `Nbp.scala` | National Bank of Poland | Reference rate, QE policy metrics, monthly FX operations; gov bond holdings and FX reserves are ledger-owned | BankCapital (reserve interest), Nfa (FX intervention), BondClearing (QE bonds) |
| `DepositMobility.scala` | Deposit flight (Diamond-Dybvig) | Boundary `bankId` reassignment, health-based flight, panic contagion; delayed routing only, no same-month balance-sheet transfer | Future BankDeposits routing via reassigned households |
| `EarmarkedFunds.scala` | FP, PFRON, FGŚP | Payroll-funded statutory funds, bankruptcy payouts, ALMP | GovDebt (gov subvention) |
| `EclStaging.scala` | IFRS 9 ECL provisioning | S1/S2/S3 staging, macro-driven migration, forward-looking provisions | BankCapital (provision) |
| `InterbankContagion.scala` | Interbank contagion (Lehman channel) | 7×7 bilateral exposure matrix, counterparty losses, liquidity hoarding | InterbankNetting |
| `QuasiFiscal.scala` | BGK + PFR (consolidated) | Monthly issuance and lending diagnostics; bonds, holder split, and loan portfolio are ledger projections | BondClearing (quasi-fiscal bonds) |
| `SocialSecurity.scala` | ZUS, NFZ, PPK, demographics | Contribution/pension/health flows, retirees, working-age pop; fund cash and PPK bond holdings are ledger-owned | BondClearing (PPK bonds), FusBalance, NfzBalance |

## Supporting types

| File | Kind | Description |
|------|------|-------------|
| `ContractType.scala` | Enum | `Permanent`, `Zlecenie`, `B2B` — contract-specific ZUS employer rates, FP rates, firing priority, AI vulnerability, sector mix |
| `Region.scala` | Enum | 6 NUTS-1 regions (Central, South, East, Northwest, Southwest, North) — wage multipliers, base unemployment, housing cost, population share, friction matrix, migration probabilities |
| `RegionalMigration.scala` | Module | Inter-regional household relocation: wage-gap–driven migration probability, friction-weighted target selection |
| `StateOwned.scala` | Module | SOE behavioral modifiers: dividend multiplier, firing reduction, investment multiplier, energy passthrough, per-sector SOE share (GUS) |

## Banking Modules

| File | Boundary |
|------|----------|
| `banking/BankCreditApproval.scala` | Firm-bank assignment, lending/deposit pricing, and auditable credit approval gates |
| `banking/BankInterbankMarket.scala` | Interbank rate/clearing plus reserve, standing-facility, and interbank-interest plumbing |
| `banking/BankFailureResolution.scala` | Failure triggers, BFG levy, bail-in, P&A resolution, healthiest-survivor routing |
| `banking/BankBondPortfolio.scala` | Government-bond issuance/redemption allocation, buyer sales, and HTM forced-sale reclassification |
| `banking/BankCapitalWaterfall.scala` | Per-bank retained-income and loss waterfall for regulatory capital |
| `banking/BankRegulatoryMetrics.scala` | Aggregate bank balance sheet, CAR, NPL, HQLA, LCR, NSFR, monetary aggregate metrics |
| `banking/BankDefaultConfigs.scala` | Default Poland-facing bank archetype configuration |
| `banking/BankRows.scala` | Internal validated alignment boundary for bank operational rows and ledger-owned stock rows |

## Firm Modules

| File | Boundary |
|------|----------|
| `firm/FirmModel.scala` | Public firm model case classes re-exported through the `Firm` facade |
| `firm/FirmCalibration.scala` | Internal firm-model constants that are not yet experiment parameters |
| `firm/FirmProduction.scala` | Technology-state liveness, headcount, capacity, CES output, and capital-planning scale |
| `firm/FirmLaborPlanning.scala` | Labor-demand planning, hiring headroom, smooth adjustment, and labor diagnostics |
| `firm/FirmTechnologyPlanning.scala` | AI/hybrid CAPEX, digital-investment cost, automation-neighborhood metrics, and sigma threshold |
| `firm/FirmDecisionEngine.scala` | Stochastic operating and technology-upgrade decisions without state mutation |
| `firm/FirmOperatingDecision.scala` | Survival fallback decisions: labor resizing, working-capital grace, startup runway, and digital investment |
| `firm/FirmResultExecution.scala` | Pure application of selected decisions into closing firm state and primary flows |
| `firm/FirmPostProcessing.scala` | Deterministic post-decision monthly adjustments: amortization, investment, inventory, FDI, and informal CIT evasion |
| `firm/FirmProfitAndLoss.scala` | Revenue, operating costs, ETS, interest, profit shifting, and CIT loss-carryforward accounting |
| `firm/FirmStartupLifecycle.scala` | Startup runway, operating-cost ramp, and one-month lifecycle advancement helpers |
| `firm/FirmCreditAudit.scala` | Candidate-level and selected-credit audit aggregation, preserving trace fields independently from merge order |
| `firm/FirmDecisionTraceBuilder.scala` | Side-effect-free projection of opening state, decision audit, and closing result into export traces |
| `firm/FirmProcessPipeline.scala` | Month-step orchestration preserving the public `Firm.process` facade |

## Household Modules

| File | Boundary |
|------|----------|
| `household/HouseholdIncomeConstruction.scala` | Income, PIT, social transfers, and labor-status income effects |
| `household/HouseholdConsumerCredit.scala` | Consumer-credit underwriting, approval gates, and residual shortfall demand |
| `household/HouseholdLiquidityWaterfall.scala` | Consumption priority, liquidity shortfall attribution, and non-negative deposit settlement |
| `household/HouseholdDistressMachine.scala` | Financial-distress lifecycle, unemployment scarring, MPC, and social-neighbor distress |
| `household/HouseholdLaborTransitionContext.scala` | Aligned labor-market context for voluntary search and retraining |
| `household/HouseholdLaborTransitions.scala` | Voluntary cross-sector search and retraining transitions |
| `household/HouseholdMonthlyFlowConstruction.scala` | Per-household month pipeline from budget flows to finalized state and stocks |
| `household/HouseholdStepAccumulator.scala` | Exact monthly flow accumulator for finalized household results |
| `household/HouseholdPerBankFlowAggregation.scala` | Per-bank household-flow aggregation |
| `household/HouseholdDistributionStats.scala` | Distribution statistics, Gini, poverty-search helper, and sector-mobility metric |
| `household/HouseholdAggregateComputation.scala` | Household aggregate accounting from state, stocks, and monthly flow totals |
| `household/HouseholdStepRunner.scala` | Month-step orchestration preserving the public `Household.step` facade |
| `household/HouseholdMortgageSchedule.scala` | Mortgage amortization helpers for household financial stocks |
| `household/HouseholdParameters.scala` | Shared household constants used across init, monthly execution, and aggregates |

## How to extend

**Adding a new agent** (e.g., pension fund, development bank):
1. Create `agents/NewAgent.scala` with `object NewAgent` + `case class State`.
2. Add `zero` and `initial` factory methods.
3. Add `step(prev: State, ...)(using p: SimParams): State` for monthly logic.
4. Add state field to `World.scala`.
5. Wire `step` call into the appropriate economics stage.
6. If the agent emits monetary flows — add `FlowMechanism` entries and a `*Flows.scala`.
7. If flows affect monetary stocks — add to the SFC semantic flow projection and verify
   the relevant SFC identity passes.
