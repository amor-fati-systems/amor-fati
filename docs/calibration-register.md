# Calibration Register

This register records the current calibration surface of Amor Fati. It is a
paper-facing inventory of key parameters, their implementation owner, value,
unit, provenance, empirical target, transformation, and calibration status.

The register is intentionally useful before every value has a final data
source. Missing or weak provenance is marked explicitly with searchable tokens:
`UNKNOWN_SOURCE`, `TUNED_NEEDS_VALIDATION`, `ASSUMED`, `PLACEHOLDER`, or
`POLICY_SCENARIO`.

## Status Taxonomy

| Status | Meaning |
| --- | --- |
| `EMPIRICAL` | Direct statutory, institutional, or data-note target is documented in code comments. |
| `CODE_NOTE_EMPIRICAL` | Code comments name source institution/year, but no source table or URL is attached yet. |
| `ASSUMED` | Structural modeling assumption or stylized calibration. |
| `TUNED_NEEDS_VALIDATION` | Behavioral/dynamic coefficient chosen for model behavior; needs sensitivity and validation work. |
| `POLICY_SCENARIO` | Scenario/shock switch or policy parameter, not a baseline empirical estimate. |
| `PLACEHOLDER` | Explicit placeholder or simplified value awaiting data bridge. |
| `UNKNOWN_SOURCE` | Parameter is active in the model but final provenance is not yet documented. |

## Transformation Rules

- Raw PLN stock values in config classes are scaled by `SimParams.gdpRatio` in
  `SimParams.defaults` when they represent macro stocks or monthly macro flows.
- Agent-level monetary values such as wages, rents, firm costs, and per-worker
  values are not scaled by `gdpRatio`.
- Rates are annual unless the consuming rule applies `.monthly`.
- Vector parameters generally follow the six-sector order:
  `BPO/SSC`, `Manufacturing`, `Retail/Services`, `Healthcare`, `Public`,
  `Agriculture`.
- Runtime numeric evidence comes from Monte Carlo output columns in
  `McTimeseriesSchema`; this register documents parameter provenance, not run
  results. Empirical validation targets and output mappings are documented in
  `docs/empirical-validation-report.md`.
- External source selection, unit/frequency conversion, sector/instrument
  crosswalks, source vintages, license/reuse notes, and prioritized empirical
  gaps are documented in
  `docs/data-bridge-national-financial-accounts.md`.

## Core Scale And Sectors

| Parameter | Value | Unit | Source / provenance | Empirical target | Transformation | Owner module | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `pop.firmsCount` | `10000` | agents | Simulation design choice | Tractable heterogeneous firm population | Direct | `PopulationConfig` | `ASSUMED` |
| `pop.workersPerFirm` | `10` | workers/firm normalizer | Simulation design choice | Normalizer for population and `gdpRatio` | Direct | `PopulationConfig` | `ASSUMED` |
| `household.count` | `firmsCount * workersPerFirm = 100000` | agents | Derived from simulation scale | Household population tied to firm scale | Derived in `SimParams.defaults` | `SimParams` | `ASSUMED` |
| `pop.firmSizeDist` | `Gus` | enum | Code note: GUS CEIDG/KRS 2024 | Polish firm-size distribution mode | Direct | `PopulationConfig` | `CODE_NOTE_EMPIRICAL` |
| `pop.firmSizeMicroShare` | `0.962` | share | Code note: GUS CEIDG 2024 | Micro enterprise share | Direct | `PopulationConfig` | `CODE_NOTE_EMPIRICAL` |
| `pop.firmSizeSmallShare` | `0.028` | share | Code note: GUS | Small firm share | Direct | `PopulationConfig` | `CODE_NOTE_EMPIRICAL` |
| `pop.firmSizeMediumShare` | `0.008` | share | Code note: GUS residual | Medium firm share | Direct | `PopulationConfig` | `CODE_NOTE_EMPIRICAL` |
| `pop.firmSizeLargeShare` | `0.002` | share | Code note: GUS | Large firm share | Direct | `PopulationConfig` | `CODE_NOTE_EMPIRICAL` |
| `pop.realGdp` | `3500e9` | PLN/year | Code note: GUS 2024 | Polish GDP scale | Feeds `gdpRatio` | `PopulationConfig` | `CODE_NOTE_EMPIRICAL` |
| `gdpRatio` | `computeGdpRatio(pop, firm.baseRevenue)` | scalar | Derived from agent flow scale and real GDP | Map agent flows to Polish macro scale | Derived | `SimParams` | `ASSUMED` |
| `topology` | `Watts-Strogatz` | enum | Network modeling convention | Small-world interaction topology | Direct | `SimParams` | `ASSUMED` |
| `sectorDefs` | 6 sectors | vector | Code note: GUS 2024 | Polish sector composition | Direct | `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `sectorDefs.share` | `[0.03, 0.16, 0.45, 0.06, 0.22, 0.08]` | share by sector | Code note: GUS 2024 | Firm/employment sector weights | Direct | `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `sectorDefs.sigma` | `[50, 10, 5, 2, 1, 3]` | CES elasticity | Structural automation/substitution assumption | Sectoral substitutability ranking | Direct | `SimParams` | `TUNED_NEEDS_VALIDATION` |
| `sectorDefs.wageMultiplier` | `[1.35, 0.94, 0.79, 0.97, 0.91, 0.67]` | multiplier | Code note: GUS 2024 | Relative sector wages | Direct | `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `sectorDefs.revenueMultiplier` | `[1.50, 1.05, 0.91, 1.10, 1.08, 0.80]` | multiplier | UNKNOWN_SOURCE | Relative sector revenue/productivity | Direct | `SimParams` | `UNKNOWN_SOURCE` |

## Household And Labor

| Parameter | Value | Unit | Source / provenance | Empirical target | Transformation | Owner module | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `household.baseWage` | `8266` | PLN/month | Code note: GUS 2024 | Mean monthly gross wage | Direct | `HouseholdConfig` | `CODE_NOTE_EMPIRICAL` |
| `household.baseReservationWage` | `4666` | PLN/month | Code note: 2025 minimum wage / legal act | Minimum acceptable wage | Direct | `HouseholdConfig` | `EMPIRICAL` |
| `household.mpc` | `0.92` | share | #461 demand-side calibration | Aggregate mean MPC supporting Poland 2026 consumption-led growth | Direct | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.mpcAlpha`, `household.mpcBeta` | `9.2`, `0.8` | beta params | #461 demand-side calibration | Heterogeneous MPC distribution centered on stronger private-consumption channel | Beta draw | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.savingsMu`, `household.savingsSigma` | `9.6`, `1.2` | log PLN params | UNKNOWN_SOURCE | Initial savings distribution | Lognormal draw | `HouseholdConfig` | `UNKNOWN_SOURCE` |
| `household.debtFraction` | `0.40` | share | Code note: BIK 2024 | Household positive debt share | Bernoulli init | `HouseholdConfig` | `CODE_NOTE_EMPIRICAL` |
| `household.debtMu`, `household.debtSigma` | `10.5`, `1.5` | log PLN params | UNKNOWN_SOURCE | Initial debt distribution | Lognormal draw | `HouseholdConfig` | `UNKNOWN_SOURCE` |
| `household.rentMean`, `rentStd`, `rentFloor` | `1800`, `400`, `800` | PLN/month | Code note: Otodom/NBP 2024 | Rent distribution | Truncated normal draw | `HouseholdConfig` | `CODE_NOTE_EMPIRICAL` |
| `household.bufferTargetMonths` | `6.0` | months | Carroll-style buffer-stock model | Target liquid buffer | Direct | `HouseholdConfig` | `ASSUMED` |
| `household.laborSupplySteepness` | `4.0` | coefficient | #461 labor-market calibration | Labor-supply response steepness; calibrated so wage clearing supports strong 2026 growth without forcing persistent labor shedding | Direct | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.bufferSensitivity` | `0.2` | coefficient | #461 calibration | MPC response to buffer gap | Direct | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.mpcUnemployedBoost` | `0.10` | share | UNKNOWN_SOURCE | Extra MPC while unemployed | Direct | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.skillDecayRate` | `0.02` | monthly share | UNKNOWN_SOURCE | Skill decay under unemployment | Direct | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.scarringRate`, `scarringCap`, `scarringOnset` | `0.02`, `0.50`, `3` | share/months | Literature note in code | Long-run unemployment scarring | Direct | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.wageScarRate`, `wageScarCap`, `wageScarDecay` | `0.025`, `0.30`, `0.005` | share/month | Code note: Jacobson et al.; Davis and von Wachter | Wage scar accumulation and recovery | Direct | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.retrainingCost`, `retrainingDuration` | `5000`, `6` | PLN/months | UNKNOWN_SOURCE | Retraining cost and duration | Direct | `HouseholdConfig` | `UNKNOWN_SOURCE` |
| `household.retrainingBaseSuccess`, `retrainingProb` | `0.60`, `0.15` | share | UNKNOWN_SOURCE | Retraining success/enrollment | Direct | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.bankruptcyDistressMonths` | `3` | months | ASSUMED | Distress persistence before bankruptcy | Direct | `HouseholdConfig` | `ASSUMED` |
| `household.depositSpread` | `0.02` | annual rate | UNKNOWN_SOURCE | Deposit rate below policy rate | Direct | `HouseholdConfig` | `UNKNOWN_SOURCE` |
| `household.ccSpread` | `0.04` | annual rate | Code note: NBP MIR 2024 | Consumer credit spread | Direct | `HouseholdConfig` | `CODE_NOTE_EMPIRICAL` |
| `household.ccMaxDti` | `0.40` | share | Code note: KNF Recommendation T | Consumer credit DTI cap | Direct | `HouseholdConfig` | `CODE_NOTE_EMPIRICAL` |
| `household.ccMaxLoan` | `50000` | PLN | UNKNOWN_SOURCE | Maximum unsecured consumer loan | Direct | `HouseholdConfig` | `UNKNOWN_SOURCE` |
| `household.ccNplRecovery` | `0.15` | share | Code note: BIK 2024 | Consumer loan recovery | Direct | `HouseholdConfig` | `CODE_NOTE_EMPIRICAL` |
| `labor.frictionMatrix` | `DefaultFrictionMatrix` | 6x6 share | Code note: GUS LFS 2024, Shimer 2005 | Cross-sector mobility friction | Direct | `LaborConfig` | `CODE_NOTE_EMPIRICAL` |
| `labor.voluntarySearchProb` | `0.02` | monthly share | UNKNOWN_SOURCE | Employed voluntary sector search | Direct | `LaborConfig` | `TUNED_NEEDS_VALIDATION` |
| `labor.voluntaryWageThreshold` | `0.20` | share | UNKNOWN_SOURCE | Required wage gain for voluntary search | Direct | `LaborConfig` | `TUNED_NEEDS_VALIDATION` |
| `labor.unionDensity` | `[0.02, 0.15, 0.03, 0.12, 0.30, 0.04]` | share by sector | Code note: GUS 2024 | Union membership density | Direct | `LaborConfig` | `CODE_NOTE_EMPIRICAL` |
| `labor.unionWagePremium` | `0.08` | share | Code note: empirical approx. | Union wage premium | Direct | `LaborConfig` | `CODE_NOTE_EMPIRICAL` |
| `labor.unionRigidity` | `0.50` | share | UNKNOWN_SOURCE | Downward nominal wage rigidity | Direct | `LaborConfig` | `TUNED_NEEDS_VALIDATION` |
| `labor.expLambda` | `0.70` | coefficient | Code note: Carroll 2003 | Adaptive expectations speed | Direct | `LaborConfig` | `TUNED_NEEDS_VALIDATION` |
| `labor.expCredibilityInit` | `0.80` | share | UNKNOWN_SOURCE | Initial NBP credibility | Direct | `LaborConfig` | `TUNED_NEEDS_VALIDATION` |
| `labor.expWagePassthrough`, `tightLaborWageSensitivity` | `0.75`, `0.06` | coefficient | #461 GDP-growth calibration | Nominal wage-setting pass-through from anchored expected inflation, plus a separately calibrated wage-pressure response when observed unemployment is below NAIRU; split after 48m runs showed the earlier shared-speed floor created a wage-cost spiral and later labor shedding | Monthly transform plus unemployment-below-NAIRU wage pressure | `LaborConfig`, `LaborEconomics` | `TUNED_NEEDS_VALIDATION` |
| `social.zusContribRate`, `zusEmployeeRate` | `0.1952`, `0.1371` | annual rate/share | Code note: Social insurance law | ZUS payroll contribution rates | Direct | `SocialConfig` | `EMPIRICAL` |
| `social.zusBasePension` | `3500` | PLN/month | Code note: ZUS 2024 | Average pension payment | Direct | `SocialConfig` | `CODE_NOTE_EMPIRICAL` |
| `social.nfzContribRate` | `0.09` | rate | Code note: health-care law | NFZ contribution rate | Direct | `SocialConfig` | `EMPIRICAL` |
| `social.nfzPerCapitaCost` | `500` | PLN/month | #461 pension-stock recalibration | Health spending per effective capita after activating initial retirees; avoids double-counting retiree health demand in NFZ subvention | Direct, with `nfzAgingElasticity` | `SocialConfig` | `TUNED_NEEDS_VALIDATION` |
| `social.ppkEmployeeRate`, `ppkEmployerRate` | `0.02`, `0.015` | rate | Code note: PPK law | PPK contribution rates | Direct | `SocialConfig` | `EMPIRICAL` |
| `social.eduShares` | `[0.08, 0.25, 0.30, 0.37]` | share | Code note: GUS LFS 2024 | Education composition | CDF draw | `SocialConfig` | `CODE_NOTE_EMPIRICAL` |
| `social.demInitialRetirees` | `pop.firmsCount * pop.workersPerFirm / 3` | agents | #461 pension-consumption/GDP calibration | Effective initial retiree stock; pension payments feed aggregate household consumption so the Poland demand channel includes retiree income | Derived in `SimParams.defaults`; consumed by `HouseholdIncomeEconomics` via ZUS pension flow | `SocialConfig`, `SimParams`, `HouseholdIncomeEconomics` | `TUNED_NEEDS_VALIDATION` |

## Firm Production, Entry, Capital, And Climate

| Parameter | Value | Unit | Source / provenance | Empirical target | Transformation | Owner module | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `firm.baseRevenue` | `180000` | PLN/month/worker | Code note: GUS F-01 2024 | Revenue per worker before demand shocks | Direct, unscaled | `FirmConfig` | `CODE_NOTE_EMPIRICAL` |
| `firm.productivityGrowth` | `0.085` | annual rate | #461 calibration | Baseline real productivity/catch-up trend needed to keep output-based GDP in Poland 2026 growth band after labor-market staffing reconciliation | `.monthly` in use | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.otherCosts` | `16667` | PLN/month/worker | UNKNOWN_SOURCE | Fixed non-wage operating cost | Direct, unscaled | `FirmConfig` | `UNKNOWN_SOURCE` |
| `firm.aiCapex` | `1200000` | PLN/firm | UNKNOWN_SOURCE | Full automation capex | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.hybridCapex` | `350000` | PLN/firm | UNKNOWN_SOURCE | Hybrid automation capex | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.aiOpex`, `firm.hybridOpex` | `30000`, `12000` | PLN/month/firm | UNKNOWN_SOURCE | AI/hybrid operating cost | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.hybridReadinessMin`, `fullAiReadinessMin` | `0.20`, `0.55` | share | #461 calibration | Digital readiness adoption thresholds | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.entryRate` | `0.02` | monthly share | Code note: GUS CEIDG 2024 | Base vacant-slot entry rate | Direct | `FirmConfig` | `CODE_NOTE_EMPIRICAL` |
| `firm.entrySectorBarriers` | `[0.8, 0.6, 1.2, 0.5, 0.1, 0.7]` | coefficient by sector | Code note: GUS CEIDG/KRS 2024 | Entry barriers | Direct | `FirmConfig` | `CODE_NOTE_EMPIRICAL` |
| `firm.entryAiThreshold`, `entryAiProb` | `0.15`, `0.20` | share | UNKNOWN_SOURCE | AI-native entrant trigger/probability | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.entryStartupCash` | `50000` | PLN | UNKNOWN_SOURCE | Entrant liquidity | Direct | `FirmConfig` | `UNKNOWN_SOURCE` |
| `firm.replacementEntryRate` | `0.35` | monthly share | UNKNOWN_SOURCE | Replacement of dead firm slots | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.netEntryRate`, `netEntryMaxMonthly` | `0.12`, `175` | monthly share / firms | #461 labor/GDP calibration | Expansionary net births under cyclical entry signal; raised after 48m runs showed unemployment persisted because the firm-birth channel was too weak to absorb labor-market slack in years 3-4 | Direct | `FirmConfig`, `FirmEntry` | `TUNED_NEEDS_VALIDATION` |
| `firm.laborAdjustSpeed`, `hiringWorkingCapitalMonths`, `startupHiringWorkingCapitalMonths` | `0.15`, `3`, `4` | monthly share / wage-months | #461 GDP-growth calibration | Firm hiring absorption and payroll working-capital runway; avoids under-absorbing labor supply when order books are positive | Direct in firm workforce decision | `FirmConfig`, `Firm` | `TUNED_NEEDS_VALIDATION` |
| `firm.digiDrift` | `0.001` | monthly share | UNKNOWN_SOURCE | Exogenous digital readiness drift | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.digiInvestCost`, `digiInvestBoost` | `50000`, `0.05` | PLN/share | UNKNOWN_SOURCE | Discretionary digital investment | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.networkK`, `networkRewireP` | `6`, `0.10` | degree/share | Network design assumption | Watts-Strogatz firm network | Direct | `FirmConfig` | `ASSUMED` |
| `firm.demoEffectThresh`, `demoEffectBoost` | `0.40`, `0.15` | share | UNKNOWN_SOURCE | Peer adoption demonstration effect | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.adoptionRampMonths` | `60` | months | #461 calibration | Adoption willingness ramp | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.sigmaLambda` | `0.0` | coefficient | Scenario switch | Arthur-style sigma learning off by default | Direct | `FirmConfig` | `POLICY_SCENARIO` |
| `capital.klRatios` | `[180000, 375000, 120000, 300000, 225000, 270000]` | PLN/worker | GUS F-01 2024 note + #461 K/GDP calibration | Sector capital-labor ratios; lifted after fiscal-investment audit showed private capital stock around 60-66% of GDP and total GFCF below Poland 2024 reference range; investment targets use structural firm scale so temporary headcount cuts do not immediately erase planned capital intensity | Direct via `Firm.capitalPlanningWorkers` | `CapitalConfig`, `Firm` | `TUNED_NEEDS_VALIDATION` |
| `capital.depRates` | `[0.15, 0.08, 0.10, 0.07, 0.05, 0.08]` | annual rate | Code note: GUS F-01 2024 | Sector depreciation rates | `.monthly` in use | `CapitalConfig` | `CODE_NOTE_EMPIRICAL` |
| `capital.importShare` | `0.18` | share | #461 GDP-growth calibration | Import share of investment; lowered after runs showed excessive domestic-demand leakage and a widening trade deficit during the 2026-2027 investment window | Direct | `CapitalConfig` | `TUNED_NEEDS_VALIDATION` |
| `capital.adjustSpeed` | `0.18` | monthly coefficient | #461 fiscal-investment/GDP calibration | Capital partial-adjustment speed; raised after GDP-growth audit showed private GFCF was not offsetting fiscal consolidation | Direct | `CapitalConfig` | `TUNED_NEEDS_VALIDATION` |
| `capital.demandExpansionSensitivity` | `0.40` | coefficient | #461 GDP-growth calibration | Target-capital uplift under persistent excess demand; raises private investment response in bottleneck sectors during the Poland investment recovery window | Direct | `CapitalConfig` | `TUNED_NEEDS_VALIDATION` |
| `capital.investmentCreditShare` | `1.0` | share | #461 calibration | Credit-financed share of cash-unfunded desired investment | Direct | `CapitalConfig` | `TUNED_NEEDS_VALIDATION` |
| `capital.inventoryTargetRatios` | `[0.05, 0.25, 0.15, 0.10, 0.02, 0.30]` | share by sector | Code note: GUS 2024 | Inventory/revenue targets | Direct | `CapitalConfig` | `CODE_NOTE_EMPIRICAL` |
| `climate.energyCostShares` | `[0.02, 0.10, 0.04, 0.05, 0.03, 0.06]` | share of revenue | Code note: Eurostat/GUS 2023 | Energy burden by sector | Direct | `ClimateConfig` | `CODE_NOTE_EMPIRICAL` |
| `climate.etsBasePrice` | `80` | EUR/tCO2 | Code note: KOBiZE 2024 | EU ETS starting price | Direct | `ClimateConfig` | `CODE_NOTE_EMPIRICAL` |
| `climate.etsPriceDrift` | `0.03` | annual rate | Code note: EC Fit for 55 trajectory | ETS trend | `.monthly` in use | `ClimateConfig` | `CODE_NOTE_EMPIRICAL` |
| `climate.greenBudgetShare` | `0.20` | share | UNKNOWN_SOURCE | Green investment budget share | Direct | `ClimateConfig` | `TUNED_NEEDS_VALIDATION` |
| `climate.greenImportShare` | `0.35` | share | UNKNOWN_SOURCE | Import share of green capex | Direct | `ClimateConfig` | `UNKNOWN_SOURCE` |

## Fiscal, Monetary, And Banking

| Parameter | Value | Unit | Source / provenance | Empirical target | Transformation | Owner module | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `fiscal.citRate` | `0.19` | rate | Code note: MF 2024 / CIT law | Corporate income tax rate | Direct | `FiscalConfig` | `EMPIRICAL` |
| `fiscal.vatRates` | `[0.23, 0.19, 0.12, 0.06, 0.10, 0.07]` | rate by sector | Code note: MF 2024 effective rates | Sector VAT rates | Direct | `FiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.exciseRates` | `[0.01, 0.04, 0.03, 0.005, 0.002, 0.02]` | rate by sector | Code note: MF 2024 aggregate | Effective excise rates | Direct | `FiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.customsDutyRate` | `0.04` | rate | Code note: EU CET/Eurostat TARIC | Average non-EU customs duty | Direct | `FiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.govBaseSpending` | `58.3e9` | raw PLN/month | Code note: MF 2024 | Government base spending | Scaled by `gdpRatio` | `FiscalConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.govWageIndexShare` | `0.75` | share | #461 GDP-growth calibration | Labor-cost indexation of government purchases so public-service demand is not mechanically deflated when wages outpace CPI | CPI/wage blended cost index in `DemandEconomics.computeGovPurchases` | `FiscalConfig`, `DemandEconomics` | `TUNED_NEEDS_VALIDATION` |
| `fiscal.fofConsWeights`, `fofGovWeights` | `[0.02, 0.18, 0.59, 0.06, 0.07, 0.08]`, `[0.04, 0.08, 0.08, 0.20, 0.58, 0.02]` | sector shares | #461 demand-allocation calibration | Flow-of-funds allocation of household consumption and government purchases; shifted toward slack domestic services/public-health sectors after probe showed excess demand was overallocated to already constrained manufacturing/agriculture | Direct sector allocation | `FiscalConfig`, `DemandEconomics` | `TUNED_NEEDS_VALIDATION` |
| `fiscal.govInvestShare` | `0.20` | share | Code note: MF 2024 | Capital share of government spending | Direct | `FiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.govCapitalMultiplier`, `govCurrentMultiplier` | `1.5`, `0.8` | multiplier | Code note: Ilzetzki, Mendoza and Vegh 2013 | Fiscal multipliers | Direct | `FiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.govInitCapital` | `0` | PLN | Explicit startup simplification | Initial public capital stock | Built from investment flows | `FiscalConfig` | `PLACEHOLDER` |
| `fiscal.euFundsTotalEur`, `euFundsAlpha`, `euFundsBeta` | `110e9`, `2`, `3` | EUR / beta shape | #461 GDP-growth calibration | EU cohesion plus KPO-style investment absorption window, with peak shifted toward the 2026-2027 investment cycle | Beta absorption path | `FiscalConfig`, `EuFunds` | `TUNED_NEEDS_VALIDATION` |
| `fiscal.euCofinanceRate`, `euCapitalShare` | `0.15`, `0.60` | share | Code note: MFiPR / EU funds | National cofinance and capex split | Direct | `FiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.minWageTargetRatio` | `0.50` | share | Code note: minimum wage act | Target minimum/average wage ratio | Annual adjustment | `FiscalConfig` | `EMPIRICAL` |
| `fiscal.govBenefitM1to3`, `govBenefitM4to6` | `1500`, `1200` | PLN/month | Code note: GUS 2024 | Unemployment benefit amounts | Direct | `FiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.govFiscalRiskBeta`, `fiscalRiskBeta55`, `fiscalRiskBeta60` | `0.03`, `0.04`, `0.08` | coefficient | #461 Poland debt-service calibration | Bond-yield sensitivity to public-debt pressure without a 60% debt cliff | Direct | `FiscalConfig`, `Nbp.bondYield` | `TUNED_NEEDS_VALIDATION` |
| `fiscal.govInitialWeightedCoupon` | `0.04` | annual rate | #461 calibration | Opening weighted coupon on Treasury debt stock | Direct | `FiscalConfig`, `WorldInit` | `TUNED_NEEDS_VALIDATION` |
| `fiscal.govAvgMaturityMonths` | `69` | months | MF monthly State Treasury debt data, Dec 2025 | Total State Treasury debt average maturity; domestic-only maturity is shorter | WAM coupon update | `FiscalConfig`, `OpenEconEconomics` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.baseForeignShare`, `maxForeignShare` | `0.35`, `0.55` | share | Code note: NBP SPW holder structure 2024 | Foreign government-bond holdings | Direct | `FiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.fiscalRuleDebtCeiling` | `0.60` | debt/GDP share | Code note: Polish constitution Art. 216 | Constitutional debt ceiling | Direct | `FiscalConfig` | `EMPIRICAL` |
| `fiscal.fiscalRuleCautionThreshold` | `0.55` | debt/GDP share | Code note: public finance act Art. 86 | Caution threshold | Direct | `FiscalConfig` | `EMPIRICAL` |
| `fiscal.sgpDeficitLimit` | `0.03` | deficit/GDP share | Maastricht / SGP | Deficit limit | Direct | `FiscalConfig` | `EMPIRICAL` |
| `fiscal.sgpCorrectionSpeed` | `0.85` | annual share | #461 Poland EDP/GDP calibration | Gradual excessive-deficit correction speed applied to discretionary purchases after prior non-purchase outlays; monthly speed scales with the deficit overshoot versus the 3% path | Direct | `FiscalConfig`, `FiscalRules` | `TUNED_NEEDS_VALIDATION` |
| `fiscal.fiscalConsolidationSpeed55`, `fiscalConsolidationSpeed60` | `0.18`, `0.45` | annual share | #461 Poland EDP/GDP calibration | Debt-threshold discretionary-spending consolidation path after 55%/60% debt-to-GDP; softened to avoid excessive GDP drag while retaining a debt-feedback channel | Direct | `FiscalConfig`, `FiscalRules` | `TUNED_NEEDS_VALIDATION` |
| `fiscal.initGovDebt` | `1600e9` | raw PLN | Code note: MF 2024 | Initial government debt | Scaled by `gdpRatio` | `FiscalConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.jstPitShare`, `jstCitShare` | `0.3846`, `0.0671` | share | Code note: JST revenue act | Local-government tax shares | Direct | `FiscalConfig` | `EMPIRICAL` |
| `fiscal.pitRate1`, `pitRate2`, `pitBracket1Annual` | `0.12`, `0.32`, `120000` | rate/PLN/year | Code note: PIT law 2024 | PIT brackets | Annualized monthly PIT | `FiscalConfig` | `EMPIRICAL` |
| `fiscal.social800` | `800` | PLN/month/child | Code note: legal act 2023 | 800+ benefit | Direct | `FiscalConfig` | `EMPIRICAL` |
| `monetary.initialRate` | `0.0575` | annual rate | Code note: NBP 2024 | NBP reference rate | Direct | `MonetaryConfig` | `CODE_NOTE_EMPIRICAL` |
| `monetary.targetInfl` | `0.025` | annual rate | NBP inflation target | Inflation target | Direct | `MonetaryConfig` | `EMPIRICAL` |
| `monetary.neutralRate` | `0.03` | annual rate | #461 calibration | Long-run neutral policy-rate anchor | Direct | `MonetaryConfig` | `TUNED_NEEDS_VALIDATION` |
| `monetary.taylorAlpha`, `taylorBeta`, `taylorDelta` | `1.2`, `0.8`, `0.5` | coefficients | #461 calibration / Taylor-rule convention | Policy reaction coefficients | Direct | `MonetaryConfig` | `TUNED_NEEDS_VALIDATION` |
| `monetary.taylorInertia` | `0.70` | share | UNKNOWN_SOURCE | Policy-rate smoothing | Direct | `MonetaryConfig` | `TUNED_NEEDS_VALIDATION` |
| `monetary.rateFloor`, `rateCeiling` | `0.001`, `0.15` | annual rate | Structural lower/upper bounds | Policy-rate corridor bounds | Direct | `MonetaryConfig` | `ASSUMED` |
| `monetary.maxRateChange` | `0.0025` | monthly annual-rate step | #461 calibration | Monthly policy-rate adjustment cap | Direct | `MonetaryConfig` | `TUNED_NEEDS_VALIDATION` |
| `monetary.nairu` | `0.05` | share | Code note: estimated | NAIRU | Direct | `MonetaryConfig` | `TUNED_NEEDS_VALIDATION` |
| `monetary.reserveRateMult` | `0.5` | share | Code note: NBP 2024 | Reserve remuneration fraction | Direct | `MonetaryConfig` | `CODE_NOTE_EMPIRICAL` |
| `monetary.depositFacilitySpread`, `lombardSpread` | `0.01`, `0.01` | annual rate | Code note: NBP corridor | Corridor +/- 100 bp | Direct | `MonetaryConfig` | `EMPIRICAL` |
| `monetary.qePace` | `5e9` | raw PLN/month | UNKNOWN_SOURCE | QE monthly purchase pace | Scaled by `gdpRatio` | `MonetaryConfig`, `SimParams` | `POLICY_SCENARIO` |
| `monetary.qeMaxGdpShare` | `0.30` | GDP share | UNKNOWN_SOURCE | QE stock ceiling | Direct | `MonetaryConfig` | `POLICY_SCENARIO` |
| `monetary.fxReserves` | `185e9` | raw PLN | Code note: NBP 2024 | Initial FX reserves | Scaled by `gdpRatio` | `MonetaryConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `banking.initCapital` | `270e9` | raw PLN | Code note: KNF 2024 | Aggregate bank equity | Scaled by `gdpRatio` | `BankingConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `banking.initDeposits` | `1900e9` | raw PLN | Code note: NBP M3 2024 | Aggregate deposits | Scaled by `gdpRatio` | `BankingConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `banking.initLoans` | `700e9` | raw PLN | Code note: NBP 2024 | Corporate loans | Scaled by `gdpRatio` | `BankingConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `banking.initGovBonds`, `initNbpGovBonds` | `400e9`, `300e9` | raw PLN | Code note: NBP 2024 | Bank/NBP government-bond holdings | Scaled by `gdpRatio` | `BankingConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `banking.initConsumerLoans` | `200e9` | raw PLN | Code note: BIK 2024 | Consumer loan stock | Scaled by `gdpRatio` | `BankingConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `banking.baseSpread` | `0.015` | annual rate | UNKNOWN_SOURCE | Base firm-loan spread | Direct | `BankingConfig` | `UNKNOWN_SOURCE` |
| `banking.minCar` | `0.08` | multiplier/share | Code note: Basel III CRR | Minimum capital adequacy | Direct | `BankingConfig` | `EMPIRICAL` |
| `banking.loanRecovery` | `0.30` | share | UNKNOWN_SOURCE | Corporate loan recovery | Direct | `BankingConfig` | `UNKNOWN_SOURCE` |
| `banking.firmLoanAmortRate` | `1/60` | monthly rate | Code note: NBP 2024 maturity | Five-year average loan maturity | Direct | `BankingConfig` | `CODE_NOTE_EMPIRICAL` |
| `banking.reserveReq` | `0.035` | share | Code note: NBP 2024 | Required reserve ratio | Direct | `BankingConfig` | `EMPIRICAL` |
| `banking.lcrMin`, `nsfrMin` | `1.0`, `1.0` | multiplier | Basel III | Minimum LCR/NSFR | Direct | `BankingConfig` | `EMPIRICAL` |
| `banking.p2rAddons` | `[0.015, 0.010, 0.030, 0.015, 0.020, 0.025, 0.020]` | multiplier by bank | Code note: KNF 2024 | SREP/P2R add-ons | Direct | `BankingConfig` | `CODE_NOTE_EMPIRICAL` |
| `banking.bfgLevyRate` | `0.0024` | annual rate | Code note: BFG 2024 | Resolution levy | `.monthly` in use | `BankingConfig` | `CODE_NOTE_EMPIRICAL` |
| `banking.bfgDepositGuarantee` | `400000` | PLN/depositor | Code note: BFG guarantee | Deposit guarantee threshold | Direct | `BankingConfig` | `EMPIRICAL` |
| `banking.ccybMax` | `0.025` | multiplier | Code note: KNF 2024 | Max CCyB | Direct | `BankingConfig` | `CODE_NOTE_EMPIRICAL` |
| `banking.htmShare` | `0.60` | share | Code note: NBP 2024 | HTM share of gov bond portfolio | Direct | `BankingConfig` | `CODE_NOTE_EMPIRICAL` |
| `banking.depositPanicRate` | `0.03` | monthly share | Code note: Diamond-Dybvig mechanism | Panic switching after failure | Direct | `BankingConfig` | `TUNED_NEEDS_VALIDATION` |
| `banking.eclRate1`, `eclRate2`, `eclRate3` | `0.01`, `0.08`, `0.50` | share | Code note: KNF IFRS 9 | ECL provision rates | Direct | `BankingConfig` | `CODE_NOTE_EMPIRICAL` |

## External Sector And Financial Markets

| Parameter | Value | Unit | Source / provenance | Empirical target | Transformation | Owner module | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `forex.baseExRate` | `4.33` | PLN/EUR | Code note: NBP 2024 | Starting exchange rate | Direct | `ForexConfig` | `CODE_NOTE_EMPIRICAL` |
| `forex.foreignRate` | `0.04` | annual rate | UNKNOWN_SOURCE | Foreign reference rate | Direct | `ForexConfig` | `UNKNOWN_SOURCE` |
| `forex.importPropensity` | `0.22` | GDP share | Code note: GUS/NBP 2024 | Aggregate import-to-GDP ratio | Direct | `ForexConfig` | `CODE_NOTE_EMPIRICAL` |
| `forex.techImportShare` | `0.40` | share | UNKNOWN_SOURCE | Technology/capital goods share of imports | Direct | `ForexConfig` | `UNKNOWN_SOURCE` |
| `forex.irpSensitivity`, `exRateAdjSpeed` | `0.15`, `0.02` | coefficient | IRP / FX adjustment model | Exchange-rate response speed | Direct | `ForexConfig` | `TUNED_NEEDS_VALIDATION` |
| `forex.riskOffShockMonth` | `0` | month | Scenario switch | No baseline risk-off shock | Direct | `ForexConfig` | `POLICY_SCENARIO` |
| `openEcon.importContent` | `[0.15, 0.50, 0.20, 0.15, 0.05, 0.12]` | share by sector | Code note: GUS supply-use 2024 | Import content of production | Direct | `OpenEconConfig` | `CODE_NOTE_EMPIRICAL` |
| `priceLevel.importPush` | `FX depreciation + GVC import-cost pressure` | monthly coefficient | #461 inflation audit | Imported inflation pass-through to CPI | Scaled by `forex.importPropensity` and capped by `openEcon.importPushCap` | `PriceLevel`, `PriceEquityEconomics` | `TUNED_NEEDS_VALIDATION` |
| `openEcon.exportBase` | `138.5e9` | raw PLN/month | Code note: NBP BoP 2024 | Monthly export base | Scaled by `gdpRatio` | `OpenEconConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `openEcon.foreignGdpGrowth` | `0.015` | annual rate | Code note: ECB/IMF projections | Foreign GDP growth | `.monthly` in export rule | `OpenEconConfig` | `CODE_NOTE_EMPIRICAL` |
| `openEcon.exportPriceElasticity`, `importPriceElasticity` | `0.8`, `0.6` | coefficient | Code note: Marshall-Lerner / Campa-Goldberg | Trade price elasticities | Direct | `OpenEconConfig` | `CODE_NOTE_EMPIRICAL` |
| `openEcon.erElasticity` | `0.5` | coefficient | UNKNOWN_SOURCE | Exchange-rate elasticity of trade | Direct | `OpenEconConfig` | `TUNED_NEEDS_VALIDATION` |
| `openEcon.euTransfers` | `1.458e9` | raw PLN/month | Code note: MFiPR 2024 | EU transfer monthly flow | Scaled by `gdpRatio` | `OpenEconConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `openEcon.fdiBase` | `583.1e6` | raw PLN/month | Code note: NBP IIP 2024 | Monthly FDI base flow | Scaled by `gdpRatio` | `OpenEconConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `openEcon.portfolioSensitivity`, `riskPremiumSensitivity` | `0.20`, `0.10` | coefficient | UNKNOWN_SOURCE | Portfolio/risk premium response | Direct | `OpenEconConfig` | `TUNED_NEEDS_VALIDATION` |
| `openEcon.pppSpeed` | `0.10` | annual coefficient | Code note: Rogoff 1996 | PPP convergence speed | `.monthly` in FX rule | `OpenEconConfig` | `CODE_NOTE_EMPIRICAL` |
| `fdi.foreignShares` | `[0.15, 0.30, 0.10, 0.03, 0.00, 0.05]` | share by sector | Code note: NBP IIP 2024 and GUS 2024 | Foreign-owned firm share by sector | Direct | `FdiConfig` | `CODE_NOTE_EMPIRICAL` |
| `fdi.profitShiftRate`, `repatriationRate` | `0.15`, `0.70` | share | Code note: FDI outflow calibration | Profit shifting and dividend repatriation | Direct | `FdiConfig` | `TUNED_NEEDS_VALIDATION` |
| `fdi.maProb`, `maSizeMin` | `0.001`, `50` | monthly share / employees | UNKNOWN_SOURCE | Domestic firm acquisition probability and eligibility | Direct | `FdiConfig` | `TUNED_NEEDS_VALIDATION` |
| `gvc.euTradeShare` | `0.70` | share | Code note: GUS/NBP 2024 | EU share of total trade | Direct | `GvcConfig` | `CODE_NOTE_EMPIRICAL` |
| `gvc.exportShares` | `[0.05, 0.55, 0.15, 0.03, 0.02, 0.20]` | share by sector | Code note: GUS 2024 | Sector export shares | Direct | `GvcConfig` | `CODE_NOTE_EMPIRICAL` |
| `gvc.depth` | `[0.35, 0.75, 0.30, 0.40, 0.10, 0.45]` | share by sector | Code note: WIOD/OECD ICIO | GVC backward linkage | Direct | `GvcConfig` | `CODE_NOTE_EMPIRICAL` |
| `gvc.foreignInflation`, `foreignGdpGrowth` | `0.02`, `0.015` | annual rate | Code note: ECB/IMF | Foreign inflation/growth | `.monthly` where used | `GvcConfig` | `CODE_NOTE_EMPIRICAL` |
| `gvc.commodityVolatility`, `commodityMeanReversion` | `0.015`, `0.08` | monthly sigma / share | #461 GDP-growth calibration | No-shock baseline commodity path; explicit `energy-shock` scenario carries crisis dynamics | Mean-reverting stochastic process plus scenario shock | `GvcConfig`, `GvcTrade` | `TUNED_NEEDS_VALIDATION` |
| `gvc.demandShockMonth`, `commodityShockMonth` | `0`, `0` | month | Scenario switches | No baseline external shocks | Direct | `GvcConfig` | `POLICY_SCENARIO` |
| `immigration.monthlyRate` | `0.0015` | monthly share | #461 labor/GDP calibration | Base labor-immigration rate; raised to give the Poland baseline a more elastic migrant labor-supply channel under wage pull | Direct | `ImmigrationConfig` | `TUNED_NEEDS_VALIDATION` |
| `immigration.wageElasticity` | `2.0` | coefficient | Code note: NBP 2023 survey | Wage differential migration response | Direct | `ImmigrationConfig` | `CODE_NOTE_EMPIRICAL` |
| `immigration.remitRate` | `0.15` | income share | Code note: NBP 2023 | Immigrant remittance outflow | Direct | `ImmigrationConfig` | `CODE_NOTE_EMPIRICAL` |
| `immigration.returnRate`, `returnUnempThreshold`, `returnUnempSensitivity` | `0.005`, `0.20`, `0.10` | monthly share / coefficient | #461 labor-market calibration | Baseline and unemployment-sensitive return migration | Direct | `ImmigrationConfig`, `Immigration` | `TUNED_NEEDS_VALIDATION` |
| `immigration.sectorShares` | `[0.05, 0.35, 0.25, 0.05, 0.05, 0.25]` | share by sector | Code note: GUS LFS 2024 | Immigrant sector allocation | CDF draw | `ImmigrationConfig` | `CODE_NOTE_EMPIRICAL` |
| `immigration.skillMean` | `0.55` | share | #461 labor-market calibration | New immigrant productivity distribution used by job matching | Gaussian draw clamped by education tier | `ImmigrationConfig`, `Immigration` | `TUNED_NEEDS_VALIDATION` |
| `immigration.initStock` | `0` | agents | Explicit startup simplification | Initial immigrant stock | Immigration accumulates from monthly flows | `ImmigrationConfig` | `PLACEHOLDER` |
| `remittance.perCapita` | `40` | PLN/person/month | Code note: NBP BoP 2024 | Diaspora remittance inflow | Direct | `RemittanceConfig` | `CODE_NOTE_EMPIRICAL` |
| `tourism.inboundShare`, `outboundShare` | `0.05`, `0.03` | GDP share | Code note: GUS TSA 2023 / NBP BoP 2023 | Tourism exports/imports | GDP-proportional | `TourismConfig` | `CODE_NOTE_EMPIRICAL` |
| `tourism.seasonality`, `peakMonth` | `0.40`, `7` | share/month | Code note: GUS TSA | Tourism seasonality | Cosine seasonal factor | `TourismConfig` | `CODE_NOTE_EMPIRICAL` |
| `equity.initIndex`, `initMcap` | `2400`, `1.4e12` | index/raw PLN | Code note: GPW 2024 | WIG index and market cap | `initMcap` scaled by `gdpRatio` | `EquityConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `equity.peMean`, `divYield` | `10.0`, `0.057` | scalar/annual rate | Code note: GPW 2024 | Long-run P/E and dividend yield | Direct | `EquityConfig` | `CODE_NOTE_EMPIRICAL` |
| `equity.foreignShare` | `0.67` | share | Code note: KNF/KDPW 2024 | Foreign ownership share | Direct | `EquityConfig` | `CODE_NOTE_EMPIRICAL` |
| `equity.listedProfitShare` | `0.10` | share | #461 calibration | Listed-company slice of aggregate modeled firm profits | Direct | `EquityConfig`, `EquityMarket` | `TUNED_NEEDS_VALIDATION` |
| `corpBond.spread` | `0.025` | annual rate | Code note: RRRF 2024 BBB | Corporate bond spread | Direct | `CorpBondConfig` | `CODE_NOTE_EMPIRICAL` |
| `corpBond.initStock` | `90e9` | raw PLN | Code note: KNF 2024 | Corporate bonds outstanding | Scaled by `gdpRatio` | `CorpBondConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `corpBond.recovery` | `0.30` | share | UNKNOWN_SOURCE | Corporate bond recovery | Direct | `CorpBondConfig` | `UNKNOWN_SOURCE` |

## Housing, Prices, Regions, IO, Informal, And SOE

| Parameter | Value | Unit | Source / provenance | Empirical target | Transformation | Owner module | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `housing.initHpi` | `100` | index | Base-index convention | National HPI starting point | Direct | `HousingConfig` | `ASSUMED` |
| `housing.initValue` | `3.0e12` | raw PLN | Code note: NBP residential price survey 2024 | Aggregate housing stock value | Scaled by `gdpRatio` | `HousingConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `housing.initMortgage` | `485e9` | raw PLN | Code note: NBP 2024 | Aggregate mortgage stock | Scaled by `gdpRatio` | `HousingConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `housing.priceIncomeElast`, `priceRateElast`, `priceReversion` | `1.2`, `-0.8`, `0.05` | coefficients | UNKNOWN_SOURCE | HPI response to income, rates, and fundamentals | Direct | `HousingConfig` | `TUNED_NEEDS_VALIDATION` |
| `housing.mortgageSpread` | `0.025` | annual rate | Code note: NBP 2024 | Mortgage spread over policy rate | Direct | `HousingConfig` | `CODE_NOTE_EMPIRICAL` |
| `housing.mortgageMaturity`, `ltvMax` | `300`, `0.80` | months/share | Code note: KNF Recommendation S | Mortgage maturity and LTV cap | Direct | `HousingConfig` | `EMPIRICAL` |
| `housing.originationRate`, `defaultBase`, `defaultUnempSens` | `0.003`, `0.001`, `0.05` | share/coefficient | UNKNOWN_SOURCE | Mortgage origination and default dynamics | Direct | `HousingConfig` | `TUNED_NEEDS_VALIDATION` |
| `housing.mortgageRecovery` | `0.70` | share | UNKNOWN_SOURCE | Defaulted mortgage recovery | Direct | `HousingConfig` | `UNKNOWN_SOURCE` |
| `housing.wealthMpc`, `rentalYield` | `0.05`, `0.045` | share/annual rate | Code note: Case, Quigley and Shiller 2005; Otodom/NBP | Housing wealth consumption effect and rental yield | Direct | `HousingConfig` | `CODE_NOTE_EMPIRICAL` |
| `housing.regionalMarkets` | 7 regional rows | vector | Code note: NBP/GUS 2024 | Regional HPI, value share, mortgage share, income multipliers | Direct | `HousingConfig` | `CODE_NOTE_EMPIRICAL` |
| `regional.baseMigrationRate` | `0.005` | monthly share | Code note: GUS 2024 | Internal migration probability for unemployed workers | Direct | `RegionalConfig` | `CODE_NOTE_EMPIRICAL` |
| `regional.housingBarrierThreshold` | `0.7` | share | UNKNOWN_SOURCE | Housing-cost migration barrier | Direct | `RegionalConfig` | `TUNED_NEEDS_VALIDATION` |
| `pricing.calvoTheta` | `0.15` | monthly share | Code note: Alvarez et al. 2006 | Average EU price duration around 6.7 months | Direct | `PricingConfig` | `CODE_NOTE_EMPIRICAL` |
| `pricing.baseMarkup` | `1.15` | multiplier | Code note: Polish microdata approximation | Steady-state markup over marginal cost | Direct | `PricingConfig` | `CODE_NOTE_EMPIRICAL` |
| `pricing.demandSensitivity`, `costPassthrough` | `0.10`, `0.4` | coefficients | #461 calibration | Markup response to demand and cost shocks | Direct | `PricingConfig` | `TUNED_NEEDS_VALIDATION` |
| `pricing.minMarkup`, `maxMarkup` | `0.95`, `1.50` | multiplier | Structural bounds | Markup floor and ceiling | Direct | `PricingConfig` | `ASSUMED` |
| `io.matrix` | 6x6 matrix | technical coefficients | Code note: GUS supply-use tables 2024 | Inter-sector intermediate demand | Direct | `IoConfig` | `CODE_NOTE_EMPIRICAL` |
| `io.scale` | `1.0` | multiplier | Sensitivity switch | Full-strength I-O flows by default | Direct | `IoConfig` | `POLICY_SCENARIO` |
| `informal.sectorShares` | `[0.05, 0.15, 0.30, 0.20, 0.02, 0.35]` | share by sector | Code note: Schneider 2023 | Shadow-economy sector shares | Direct | `InformalConfig` | `CODE_NOTE_EMPIRICAL` |
| `informal.citEvasion`, `vatEvasion`, `pitEvasion`, `exciseEvasion` | `0.50`, `0.30`, `0.40`, `0.30` | share | #461 calibration | Tax evasion rates by tax channel | Direct | `InformalConfig` | `TUNED_NEEDS_VALIDATION` |
| `informal.unempThreshold`, `cyclicalSens`, `smoothing` | `0.05`, `0.50`, `0.92` | rate/coefficient | UNKNOWN_SOURCE | Counter-cyclical informal-sector response | Direct | `InformalConfig` | `TUNED_NEEDS_VALIDATION` |
| `soe.baseDividendMultiplier` | `1.3` | multiplier | Code note: MF | Baseline SOE dividend payout versus private firms | Direct | `SoeConfig` | `CODE_NOTE_EMPIRICAL` |
| `soe.dividendFiscalThreshold`, `dividendFiscalSensitivity` | `0.03`, `5.0` | share/coefficient | UNKNOWN_SOURCE | Fiscal-pressure dividend response | Direct | `SoeConfig` | `TUNED_NEEDS_VALIDATION` |
| `soe.firingReduction`, `investmentMultiplier`, `energyPassthrough` | `0.70`, `1.2`, `0.60` | share/multiplier | UNKNOWN_SOURCE | SOE labor buffer, directed investment, energy pass-through | Firing buffer applies to standard workforce adjustment and insolvency downsizing | `SoeConfig`, `Firm` | `TUNED_NEEDS_VALIDATION` |

## Non-Bank Financials And Public Funds

| Parameter | Value | Unit | Source / provenance | Empirical target | Transformation | Owner module | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `ins.lifeReserves`, `nonLifeReserves` | `110e9`, `90e9` | raw PLN | Code note: KNF 2024 | Insurance reserve pools | Scaled by `gdpRatio` | `InsuranceConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `ins.govBondShare`, `corpBondShare`, `equityShare` | `0.35`, `0.08`, `0.12` | share | UNKNOWN_SOURCE | Insurance asset allocation | Portfolio rebalance target | `InsuranceConfig` | `UNKNOWN_SOURCE` |
| `ins.lifePremiumRate`, `nonLifePremiumRate` | `0.003`, `0.0025` | wage-bill share | UNKNOWN_SOURCE | Insurance premium flow | Direct | `InsuranceConfig` | `UNKNOWN_SOURCE` |
| `ins.lifeLossRatio`, `nonLifeLossRatio` | `0.85`, `0.70` | share | UNKNOWN_SOURCE | Insurance claims/premiums | Direct | `InsuranceConfig` | `UNKNOWN_SOURCE` |
| `nbfi.tfiInitAum` | `380e9` | raw PLN | Code note: KNF/IZFiA 2024 | TFI AUM | Scaled by `gdpRatio` | `NbfiConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `nbfi.creditInitStock` | `231e9` | raw PLN | Code note: KNF 2024 | NBFI credit stock | Scaled by `gdpRatio` | `NbfiConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `nbfi.tfiGovBondShare`, `tfiCorpBondShare`, `tfiEquityShare` | `0.40`, `0.10`, `0.10` | share | UNKNOWN_SOURCE | TFI portfolio allocation | Portfolio rebalance target | `NbfiConfig` | `UNKNOWN_SOURCE` |
| `nbfi.creditBaseRate` | `0.005` | monthly share | UNKNOWN_SOURCE | NBFI credit origination rate | Direct | `NbfiConfig` | `TUNED_NEEDS_VALIDATION` |
| `nbfi.creditRate` | `0.10` | annual rate | UNKNOWN_SOURCE | NBFI loan rate | `.monthly` in income | `NbfiConfig` | `UNKNOWN_SOURCE` |
| `nbfi.defaultBase`, `defaultUnempSens` | `0.002`, `3.0` | share/coefficient | UNKNOWN_SOURCE | NBFI default dynamics | Direct | `NbfiConfig` | `TUNED_NEEDS_VALIDATION` |
| `quasiFiscal.issuanceShare` | `0.40` | share | Code note: NIK 2024 | BGK/PFR share of capital programs | Direct | `QuasiFiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `quasiFiscal.avgMaturityMonths` | `72` | months | Code note: NIK/BGK/PFR | BGK/PFR bond maturity | Amortization `1/maturity` | `QuasiFiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `quasiFiscal.nbpAbsorptionShare` | `0.70` | share | Code note: COVID quasi-QE | NBP absorption under QE | Active when NBP QE active | `QuasiFiscalConfig` | `POLICY_SCENARIO` |
| `quasiFiscal.lendingShare` | `0.50` | share | Code note: BGK subsidized lending | Lending share of issuance | Direct | `QuasiFiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `earmarked.fpRate` | `0.0245` | payroll rate | Code note: employment promotion law | Fundusz Pracy levy | Direct | `EarmarkedConfig` | `EMPIRICAL` |
| `earmarked.pfronMonthlyRevenue`, `pfronMonthlySpending` | `460e6`, `420e6` | PLN/month | Code note: PFRON 2024 | PFRON revenue/spending | Direct, currently unscaled | `EarmarkedConfig` | `CODE_NOTE_EMPIRICAL` |
| `earmarked.fgspRate` | `0.001` | payroll rate | Code note: employee claims law | FGSP levy | Direct | `EarmarkedConfig` | `EMPIRICAL` |
| `earmarked.fgspPayoutPerWorker` | `10000` | PLN/worker | UNKNOWN_SOURCE | Bankruptcy wage payout | Direct | `EarmarkedConfig` | `UNKNOWN_SOURCE` |

## Searchable Gaps

Use the following commands to audit open provenance gaps:

```bash
rg "UNKNOWN_SOURCE|TUNED_NEEDS_VALIDATION|PLACEHOLDER|POLICY_SCENARIO" docs/calibration-register.md
```

Priority gaps before publication:

- Replace `UNKNOWN_SOURCE` rows with source table, year, and transformation
  notes.
- Split `CODE_NOTE_EMPIRICAL` rows into source-specific evidence links once
  the data bridge identifies the target source family and extraction rule.
- Validate `TUNED_NEEDS_VALIDATION` coefficients with historical fit,
  stylized-fact matching, or sensitivity ranges.
- Decide whether currently unscaled institutional monthly flows, such as PFRON
  monthly revenue/spending, should remain agent-scale values or be moved into
  the `gdpRatio`-scaled macro-stock convention.
- Keep scenario deltas in `docs/scenario-registry.md`; this register remains
  the baseline parameter-provenance surface.
