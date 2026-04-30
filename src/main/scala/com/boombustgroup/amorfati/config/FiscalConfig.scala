package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.types.*

/** Government fiscal policy: taxation, spending, transfers, bond market, and
  * local government (JST).
  *
  * Covers the full fiscal architecture of the Polish state:
  * CIT/VAT/PIT/excise/customs revenue, government consumption and investment,
  * EU fund absorption, minimum wage indexation, unemployment benefits,
  * government bond market, JST (local government) fiscal sharing, PIT brackets,
  * and 800+ social transfers.
  *
  * Stock values (`govBaseSpending`, `initGovDebt`) are in raw PLN — scaled by
  * `gdpRatio` in `SimParams.defaults`.
  *
  * @param citRate
  *   corporate income tax rate
  * @param vatRates
  *   per-sector effective VAT rates (6 sectors)
  * @param exciseRates
  *   per-sector effective excise rates (6 sectors)
  * @param customsDutyRate
  *   average customs duty rate on non-EU imports (EU CET/Eurostat TARIC: ~4%)
  * @param customsNonEuShare
  *   share of imports subject to customs duties (non-EU origin)
  * @param govBaseSpending
  *   monthly government spending plan in raw PLN (scaled by gdpRatio)
  * @param govFiscalRecyclingRate
  *   share of tax revenue recycled into spending (automatic stabilizer)
  * @param govAutoStabMult
  *   multiplier for automatic stabilizer response to output gap
  * @param govWageIndexShare
  *   share of the government-purchases cost index tied to aggregate wage
  *   growth; public services are labor-intensive, so CPI-only indexation
  *   mechanically shrinks real public demand when wages outpace consumer prices
  * @param govInvestShare
  *   share of government spending allocated to capital investment
  * @param govCapitalMultiplier
  *   fiscal multiplier for government capital spending (Ilzetzki, Mendoza &
  *   Vegh 2013)
  * @param govCurrentMultiplier
  *   fiscal multiplier for government current spending
  * @param govDepreciationRate
  *   annual depreciation rate of public capital stock (GUS F-01)
  * @param govInitCapital
  *   initial public capital stock (PLN, scaled by gdpRatio in
  *   SimParams.defaults). Existing infrastructure is embedded in baseline firm
  *   capacity; only net movement around this stock shifts production capacity.
  * @param euFundsTotalEur
  *   total EU/KPO-style investment allocation in EUR for the active absorption
  *   window
  * @param euFundsPeriodMonths
  *   programming period length in months (7 years = 84)
  * @param euFundsStartMonth
  *   simulation month when EU fund absorption begins
  * @param euFundsAlpha
  *   Beta distribution alpha for absorption curve shape
  * @param euFundsBeta
  *   Beta distribution beta for absorption curve shape
  * @param euCofinanceRate
  *   national co-financing rate (typically 15%)
  * @param euCapitalShare
  *   share of EU funds directed to capital investment (vs. current)
  * @param minWageAdjustMonths
  *   months between minimum wage adjustments (typically 12)
  * @param minWageInflationIndex
  *   whether to index minimum wage to inflation
  * @param minWageTargetRatio
  *   target ratio of minimum wage to average wage (Art. 5 Ustawa o min.
  *   wynagrodzeniu: 50%)
  * @param minWageConvergenceSpeed
  *   annual convergence speed toward target ratio
  * @param fofConsWeights
  *   Flow-of-Funds: household consumption weights by sector (6 sectors)
  * @param fofGovWeights
  *   Flow-of-Funds: government spending weights by sector (6 sectors)
  * @param fofExportShares
  *   Flow-of-Funds: export demand shares by sector (6 sectors)
  * @param fofInvestWeights
  *   Flow-of-Funds: investment demand weights by sector (6 sectors)
  * @param govBenefitM1to3
  *   monthly unemployment benefit for months 1-3 (PLN)
  * @param govBenefitM4to6
  *   monthly unemployment benefit for months 4-6 (PLN)
  * @param govBenefitDuration
  *   maximum benefit duration in months
  * @param govBenefitCoverage
  *   fraction of unemployed receiving benefits
  * @param govFiscalRiskBeta
  *   baseline sensitivity of bond spread to debt/GDP pressure above 40%
  * @param govTermPremium
  *   term/liquidity premium over the long-rate anchor (NBP/MF calibration)
  * @param govInitialWeightedCoupon
  *   opening weighted-average coupon on the outstanding Treasury debt stock (MF
  *   interest-cost calibration)
  * @param govAvgMaturityMonths
  *   average maturity of the total State Treasury debt portfolio in months.
  *   Controls yield pass-through speed: each month 1/avgMaturity of the
  *   portfolio matures and is refinanced at current yield. The weighted average
  *   coupon converges to market yield gradually, not instantly.
  * @param sgpCorrectionSpeed
  *   annual convergence speed of the SGP excessive-deficit correction. The
  *   model applies this to the excess over the 3% deficit path, scaled by the
  *   deficit overshoot, instead of a one-month hard cap.
  * @param baseForeignShare
  *   baseline foreign holding share of SPW
  * @param maxForeignShare
  *   ceiling on foreign share (structural limit, ~55%)
  * @param foreignYieldSensitivity
  *   elasticity of foreign demand to yield spread vs Bund
  * @param foreignErSensitivity
  *   elasticity of foreign demand to PLN depreciation (risk-off)
  * @param bundYield
  *   German 10Y Bund yield benchmark
  * @param bankBondAbsorptionShare
  *   fraction of bank deposits available for bond absorption
  * @param initGovDebt
  *   initial government debt in raw PLN (scaled by gdpRatio)
  * @param jstPitShare
  *   JST (local government) share of PIT revenue (Art. 4 Ustawa o dochodach
  *   JST: 38.46%)
  * @param jstCitShare
  *   JST share of CIT revenue (Art. 4: 6.71%)
  * @param jstPropertyTax
  *   annual property tax per household (PLN)
  * @param jstSubventionShare
  *   education subvention as share of central budget
  * @param jstDotacjeShare
  *   earmarked grants (dotacje celowe) as share of central budget
  * @param jstSpendingMult
  *   JST spending multiplier (slightly above 1 due to own revenue)
  * @param pitRate1
  *   PIT first bracket rate
  * @param pitRate2
  *   PIT second bracket rate
  * @param pitBracket1Annual
  *   annual income threshold for second PIT bracket (PLN)
  * @param pitTaxCreditAnnual
  *   annual tax credit / free amount (PLN, kwota wolna)
  * @param pitEffectiveRate
  *   effective average PIT rate applied in simplified monthly calculation
  * @param social800
  *   monthly 800+ benefit per child (PLN, Dz.U. 2023)
  * @param social800ChildrenPerHh
  *   average number of eligible children per household
  */
case class FiscalConfig(
    // Tax rates
    citRate: Rate = Rate.decimal(19, 2),
    citCarryforwardMaxShare: Share = Share.decimal(50, 2),             // max 50% of profit offset per year (Art. 7 ustawy o CIT)
    citCarryforwardDecay: Rate = Rate.fraction(1, 60),                 // monthly decay ≈ 5-year expiry horizon
    vatRates: Vector[Rate] = Vector(Rate.decimal(23, 2), Rate.decimal(19, 2), Rate.decimal(12, 2), Rate.decimal(6, 2), Rate.decimal(10, 2), Rate.decimal(7, 2)),
    exciseRates: Vector[Rate] = Vector(Rate.decimal(1, 2), Rate.decimal(4, 2), Rate.decimal(3, 2), Rate.decimal(5, 3), Rate.decimal(2, 3), Rate.decimal(2, 2)),
    customsDutyRate: Rate = Rate.decimal(4, 2),
    customsNonEuShare: Share = Share.decimal(30, 2),
    // Government spending (raw — scaled by gdpRatio in SimParams.defaults)
    govBaseSpending: PLN = PLN(76575000000L),
    govFiscalRecyclingRate: Share = Share.decimal(85, 2),
    govAutoStabMult: Coefficient = Coefficient(3),
    govWageIndexShare: Share = Share.decimal(75, 2),
    // Government investment
    govInvestShare: Share = Share.decimal(20, 2),
    govCapitalMultiplier: Multiplier = Multiplier.decimal(15, 1),
    govCurrentMultiplier: Multiplier = Multiplier.decimal(8, 1),
    govDepreciationRate: Rate = Rate.decimal(6, 2),
    govInitCapital: PLN = PLN(0),
    // EU Funds
    euFundsTotalEur: Multiplier = Multiplier(110000000000L),
    euFundsPeriodMonths: Int = 84,
    euFundsStartMonth: Int = 1,
    euFundsAlpha: Scalar = Scalar(2),
    euFundsBeta: Scalar = Scalar(3),
    euCofinanceRate: Share = Share.decimal(15, 2),
    euCapitalShare: Share = Share.decimal(60, 2),
    // Minimum wage
    minWageAdjustMonths: Int = 12,
    minWageInflationIndex: Boolean = true,
    minWageTargetRatio: Share = Share.decimal(50, 2),
    minWageConvergenceSpeed: Share = Share.decimal(33, 2),
    // Flow-of-Funds weights (6 sectors)
    fofConsWeights: Vector[Share] =
      Vector(Share.decimal(2, 2), Share.decimal(18, 2), Share.decimal(59, 2), Share.decimal(6, 2), Share.decimal(7, 2), Share.decimal(8, 2)),
    fofGovWeights: Vector[Share] =
      Vector(Share.decimal(4, 2), Share.decimal(8, 2), Share.decimal(8, 2), Share.decimal(20, 2), Share.decimal(58, 2), Share.decimal(2, 2)),
    fofExportShares: Vector[Share] =
      Vector(Share.decimal(7, 2), Share.decimal(52, 2), Share.decimal(12, 2), Share.decimal(2, 2), Share.decimal(3, 2), Share.decimal(24, 2)),
    fofInvestWeights: Vector[Share] =
      Vector(Share.decimal(10, 2), Share.decimal(40, 2), Share.decimal(15, 2), Share.decimal(5, 2), Share.decimal(20, 2), Share.decimal(10, 2)),
    // Unemployment benefits
    govBenefitM1to3: PLN = PLN(1500),
    govBenefitM4to6: PLN = PLN(1200),
    govBenefitDuration: Int = 6,
    govBenefitCoverage: Share = Share.decimal(15, 2),
    // Bond market
    govFiscalRiskBeta: Coefficient = Coefficient.decimal(3, 2),
    govTermPremium: Rate = Rate.decimal(5, 3),
    govInitialWeightedCoupon: Rate = Rate.decimal(4, 2),
    govAvgMaturityMonths: Int = 69,
    // Bond auction — foreign demand
    baseForeignShare: Share = Share.decimal(35, 2),
    maxForeignShare: Share = Share.decimal(55, 2),
    foreignYieldSensitivity: Coefficient = Coefficient(8),
    foreignErSensitivity: Coefficient = Coefficient(4),
    bundYield: Rate = Rate.decimal(25, 3),
    bankBondAbsorptionShare: Share = Share.decimal(30, 2),
    // Fiscal rules (Art. 216 Konstytucja RP, SRW Art. 112aa uFP, SGP)
    fiscalRuleDebtCeiling: Share = Share.decimal(60, 2),               // Art. 216: constitutional 60% debt/GDP ceiling
    fiscalRuleCautionThreshold: Share = Share.decimal(55, 2),          // Art. 86 uFP: cautionary 55% debt/GDP threshold
    srwRealGrowthCap: Rate = Rate.decimal(15, 3),                      // SRW: max real growth allowance (CPI + 1.5pp)
    srwCorrectionSpeed: Share = Share.decimal(33, 2),                  // SRW: annual convergence speed toward ceiling
    srwOutputGapSensitivity: Coefficient = Coefficient.decimal(50, 2), // SRW: correction term sensitivity to output gap
    sgpCorrectionSpeed: Share = Share.decimal(85, 2),                  // SGP: annual convergence speed toward 3% deficit path
    fiscalConsolidationSpeed55: Share = Share.decimal(18, 2),          // annual spending cut rate at 55% threshold
    fiscalConsolidationSpeed60: Share = Share.decimal(45, 2),          // annual spending cut rate at 60% threshold
    sgpDeficitLimit: Share = Share.decimal(3, 2),                      // SGP: 3% deficit/GDP Maastricht limit
    fiscalRiskBeta55: Coefficient = Coefficient.decimal(4, 2),         // bond yield sensitivity above 55% debt/GDP
    fiscalRiskBeta60: Coefficient = Coefficient.decimal(8, 2),         // bond yield sensitivity above 60% debt/GDP
    // Government debt (raw — scaled by gdpRatio in SimParams.defaults)
    initGovDebt: PLN = PLN(2235000000000L),
    // JST (local government, Art. 4 Ustawa o dochodach JST)
    jstPitShare: Share = Share.decimal(3846, 4),
    jstCitShare: Share = Share.decimal(671, 4),
    jstPropertyTax: PLN = PLN(5000),
    jstSubventionShare: Share = Share.decimal(3, 2),
    jstDotacjeShare: Share = Share.decimal(1, 2),
    jstSpendingMult: Multiplier = Multiplier.decimal(102, 2),
    // PIT
    pitRate1: Rate = Rate.decimal(12, 2),
    pitRate2: Rate = Rate.decimal(32, 2),
    pitBracket1Annual: PLN = PLN(120000),
    pitTaxCreditAnnual: PLN = PLN(3600),
    pitEffectiveRate: Rate = Rate.decimal(9, 2),
    // Social 800+ (Dz.U. 2023)
    social800: PLN = PLN(800),
    social800ChildrenPerHh: Scalar = Scalar.decimal(35, 2),
):
  require(citRate >= Rate.Zero && citRate <= Rate(1), s"citRate must be in [0,1]: $citRate")
  require(govBaseSpending >= PLN.Zero, s"govBaseSpending must be non-negative: $govBaseSpending")
  require(govInitialWeightedCoupon >= Rate.Zero, s"govInitialWeightedCoupon must be non-negative: $govInitialWeightedCoupon")
  require(sgpCorrectionSpeed >= Share.Zero && sgpCorrectionSpeed <= Share.One, s"sgpCorrectionSpeed must be in [0,1]: $sgpCorrectionSpeed")
  require(initGovDebt >= PLN.Zero, s"initGovDebt must be non-negative: $initGovDebt")
  require(vatRates.length == 6, s"vatRates must have 6 sectors: ${vatRates.length}")
  require(exciseRates.length == 6, s"exciseRates must have 6 sectors: ${exciseRates.length}")
  require(fofConsWeights.length == 6, s"fofConsWeights must have 6 sectors: ${fofConsWeights.length}")
  require(fofGovWeights.length == 6, s"fofGovWeights must have 6 sectors: ${fofGovWeights.length}")
  require(fofExportShares.length == 6, s"fofExportShares must have 6 sectors: ${fofExportShares.length}")
  require(fofInvestWeights.length == 6, s"fofInvestWeights must have 6 sectors: ${fofInvestWeights.length}")
