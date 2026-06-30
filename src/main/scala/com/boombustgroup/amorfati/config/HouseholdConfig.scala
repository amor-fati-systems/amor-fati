package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.types.*

/** Household agent parameters: wages, consumption, savings, debt, and consumer
  * credit.
  *
  * Each household is an individual agent with heterogeneous MPC
  * (Beta-distributed), log-normal savings and debt, skill level subject to
  * decay and scarring, and access to consumer credit. Rent is drawn from a
  * truncated normal.
  *
  * @param baseWage
  *   mean monthly gross wage (PLN, GUS March 2026 enterprise-sector wage:
  *   ~9,652 PLN)
  * @param baseReservationWage
  *   minimum acceptable wage — anchored to the statutory 2026 minimum wage
  * @param mpc
  *   mean marginal propensity to consume (aggregate target)
  * @param laborSupplySteepness
  *   slope of labor supply response to wage gap
  * @param wageAdjSpeed
  *   monthly wage Phillips-curve adjustment speed
  * @param count
  *   number of household agents (set to totalPopulation in SimParams.defaults)
  * @param savingsMu
  *   log-normal mean of initial savings distribution (ln PLN)
  * @param savingsSigma
  *   log-normal std dev of initial savings distribution
  * @param debtFraction
  *   fraction of households initialized with positive debt (BIK bridge prior:
  *   ~40%)
  * @param debtMu
  *   log-normal mean of initial debt distribution (ln PLN)
  * @param debtSigma
  *   log-normal std dev of initial debt distribution
  * @param rentMean
  *   mean monthly rent (PLN, Otodom March 2026 provincial-city asking-rent
  *   bridge)
  * @param rentStd
  *   std dev of rent (PLN)
  * @param rentFloor
  *   minimum rent (PLN)
  * @param mpcAlpha
  *   Beta distribution alpha parameter for heterogeneous MPC
  * @param mpcBeta
  *   Beta distribution beta parameter for heterogeneous MPC
  * @param basicConsumptionFloor
  *   monthly non-discretionary consumption floor protected before discretionary
  *   consumption is allowed (PLN)
  * @param initialUnemployedMaxMonths
  *   maximum opening unemployment spell assigned to initial unemployed workers
  * @param initialUnemployedRunwayMonths
  *   minimum opening cashflow runway for initial unemployed workers, measured
  *   against benefits, transfers, rent, basic consumption, and debt service
  * @param skillDecayRate
  *   monthly skill depreciation rate while unemployed
  * @param scarringRate
  *   additional monthly skill loss after `scarringOnset` months of unemployment
  * @param scarringCap
  *   maximum cumulative scarring penalty
  * @param scarringOnset
  *   months of unemployment before scarring begins
  * @param retrainingCost
  *   cost of retraining program (PLN)
  * @param retrainingDuration
  *   duration of retraining in months
  * @param retrainingBaseSuccess
  *   base probability of successful retraining (education-adjusted)
  * @param retrainingProb
  *   monthly probability of enrolling in retraining while unemployed
  * @param retrainingEnabled
  *   whether retraining mechanism is active
  * @param bankruptcyThreshold
  *   savings threshold (in multiples of monthly obligations) below which a
  *   household accumulates financial distress
  * @param bankruptcyDistressMonths
  *   consecutive distressed months before the household enters default
  * @param personalInsolvencyDistressMonths
  *   distress-duration horizon where personal-insolvency duration risk reaches
  *   its configured maximum; this is not a deterministic filing trigger
  * @param personalInsolvencyMinDistressMonths
  *   minimum consecutive distressed months before personal-insolvency hazard
  *   can activate
  * @param personalInsolvencyBaseHazard
  *   monthly personal-insolvency filing hazard at activation
  * @param personalInsolvencyMaxHazard
  *   maximum monthly personal-insolvency filing hazard
  * @param personalInsolvencyBurdenHazardWeight
  *   maximum hazard add-on from arrears and consumer-debt-service burden
  * @param ccRestructuringDefaultDebtServiceMonths
  *   monthly consumer-loan workout default cap as a multiple of debt service
  * @param ccRestructuringDefaultOutstandingShare
  *   monthly consumer-loan workout default cap as a share of outstanding
  *   principal
  * @param ccBankruptcyDefaultDebtServiceMonths
  *   personal-insolvency consumer-loan default cap as a multiple of debt
  *   service
  * @param ccBankruptcyDefaultOutstandingShare
  *   personal-insolvency consumer-loan default cap as a share of outstanding
  *   principal
  * @param socialK
  *   Watts-Strogatz degree for household social network
  * @param socialP
  *   Watts-Strogatz rewiring probability for household network
  * @param depositSpread
  *   spread below policy rate for household deposit remuneration
  * @param ccSpread
  *   consumer credit spread over policy rate (NBP MIR bridge prior)
  * @param ccMaxDti
  *   maximum debt-to-income ratio for consumer credit eligibility (KNF
  *   Recommendation T)
  * @param ccMaxLoan
  *   maximum consumer loan size (PLN)
  * @param ccAmortRate
  *   monthly amortization rate on consumer loans
  * @param ccNplRecovery
  *   recovery rate on defaulted consumer loans (BIK bridge prior)
  * @param ccEligRate
  *   fraction of employed households eligible for consumer credit each month
  */
case class HouseholdConfig(
    baseWage: PLN = PLN(9652),
    baseReservationWage: PLN = PLN(4806),
    mpc: Share = Share.decimal(92, 2),
    laborSupplySteepness: Coefficient = Coefficient(4),
    wageAdjSpeed: Coefficient = Coefficient.decimal(12, 2),
    // Household count (defaults to totalPopulation — set in SimParams.defaults)
    count: Int = 100000,
    // Savings distribution
    savingsMu: Coefficient = Coefficient.decimal(96, 1),
    savingsSigma: Coefficient = Coefficient.decimal(12, 1),
    // Debt
    debtFraction: Share = Share.decimal(40, 2),
    debtMu: Coefficient = Coefficient.decimal(105, 1),
    debtSigma: Coefficient = Coefficient.decimal(15, 1),
    // Rent
    rentMean: PLN = PLN(3500),
    rentStd: PLN = PLN(800),
    rentFloor: PLN = PLN(1200),
    // MPC distribution
    mpcAlpha: Coefficient = Coefficient.decimal(92, 1),
    mpcBeta: Coefficient = Coefficient.decimal(8, 1),
    // State-dependent MPC (Carroll 1997 buffer-stock)
    bufferTargetMonths: Multiplier = Multiplier(6),             // target savings = 6 months of income
    bufferSensitivity: Coefficient = Coefficient.decimal(2, 1), // MPC adjustment strength (0 = static, 1 = fully responsive)
    mpcUnemployedBoost: Share = Share.decimal(10, 2),           // MPC uplift when unemployed (desperate spending)
    bufferProtectedShare: Share = Share.decimal(50, 2),         // protected share of target buffer under stress
    bufferExcessDrawdownRate: Share = Share.decimal(20, 2),     // monthly drawdown rate for savings above target buffer
    bufferStressDrawdownRate: Share = Share.decimal(35, 2),     // monthly drawdown rate for savings above protected buffer under stress
    basicConsumptionFloor: PLN = PLN(1500),                     // non-discretionary monthly consumption floor
    // Opening unemployed calibration
    initialUnemployedMaxMonths: Int = 6,
    initialUnemployedRunwayMonths: Int = 2,
    // Skill decay & scarring
    skillDecayRate: Share = Share.decimal(2, 2),
    scarringRate: Share = Share.decimal(2, 2),
    scarringCap: Share = Share.decimal(50, 2),
    scarringOnset: Int = 3,
    // Post-reemployment wage scarring (Jacobson, LaLonde & Sullivan 1993)
    wageScarRate: Share = Share.decimal(25, 3),                 // monthly wage scar accumulation during long-term unemployment
    wageScarCap: Share = Share.decimal(30, 2),                  // max 30% permanent wage loss
    wageScarDecay: Share = Share.decimal(5, 3),                 // monthly recovery once reemployed (~0.5%/mo → ~10 year half-life)
    // Retraining
    retrainingCost: PLN = PLN(5000),
    retrainingDuration: Int = 6,
    retrainingBaseSuccess: Share = Share.decimal(60, 2),
    retrainingProb: Share = Share.decimal(15, 2),
    retrainingEnabled: Boolean = true,
    // Bankruptcy
    bankruptcyThreshold: Coefficient = Coefficient(-3),
    bankruptcyDistressMonths: Int = 3,
    personalInsolvencyDistressMonths: Int = 12,
    personalInsolvencyMinDistressMonths: Int = 6,
    personalInsolvencyBaseHazard: Share = Share.decimal(1, 2),
    personalInsolvencyMaxHazard: Share = Share.decimal(20, 2),
    personalInsolvencyBurdenHazardWeight: Share = Share.decimal(10, 2),
    ccRestructuringDefaultDebtServiceMonths: Int = 3,
    ccRestructuringDefaultOutstandingShare: Share = Share.decimal(8, 2),
    ccBankruptcyDefaultDebtServiceMonths: Int = 6,
    ccBankruptcyDefaultOutstandingShare: Share = Share.decimal(25, 2),
    // Social network
    socialK: Int = 10,
    socialP: Share = Share.decimal(15, 2),
    // Household deposit rates
    depositSpread: Rate = Rate.decimal(2, 2),
    // Consumer credit
    ccSpread: Rate = Rate.decimal(4, 2),
    ccMaxDti: Share = Share.decimal(40, 2),
    ccMaxLoan: PLN = PLN(50000),
    ccAmortRate: Rate = Rate.decimal(25, 3),
    ccNplRecovery: Share = Share.decimal(15, 2),
    ccEligRate: Share = Share.decimal(85, 2),
):
  require(initialUnemployedMaxMonths >= 0, s"initialUnemployedMaxMonths must be non-negative: $initialUnemployedMaxMonths")
  require(initialUnemployedRunwayMonths >= 0, s"initialUnemployedRunwayMonths must be non-negative: $initialUnemployedRunwayMonths")
  require(bankruptcyDistressMonths > 0, s"bankruptcyDistressMonths must be positive: $bankruptcyDistressMonths")
  require(
    personalInsolvencyMinDistressMonths > bankruptcyDistressMonths,
    s"personalInsolvencyMinDistressMonths must exceed bankruptcyDistressMonths: $personalInsolvencyMinDistressMonths <= $bankruptcyDistressMonths",
  )
  require(
    personalInsolvencyDistressMonths >= personalInsolvencyMinDistressMonths,
    s"personalInsolvencyDistressMonths must be at least personalInsolvencyMinDistressMonths: $personalInsolvencyDistressMonths < $personalInsolvencyMinDistressMonths",
  )
  require(
    personalInsolvencyBaseHazard >= Share.Zero && personalInsolvencyBaseHazard <= personalInsolvencyMaxHazard,
    s"personalInsolvencyBaseHazard must be in [0, maxHazard], got $personalInsolvencyBaseHazard and $personalInsolvencyMaxHazard",
  )
  require(
    personalInsolvencyMaxHazard <= Share.One,
    s"personalInsolvencyMaxHazard must be <= 1, got $personalInsolvencyMaxHazard",
  )
  require(
    personalInsolvencyBurdenHazardWeight >= Share.Zero && personalInsolvencyBurdenHazardWeight <= Share.One,
    s"personalInsolvencyBurdenHazardWeight must be in [0, 1], got $personalInsolvencyBurdenHazardWeight",
  )
  require(
    ccRestructuringDefaultDebtServiceMonths >= 0,
    s"ccRestructuringDefaultDebtServiceMonths must be non-negative: $ccRestructuringDefaultDebtServiceMonths",
  )
  require(
    ccBankruptcyDefaultDebtServiceMonths >= 0,
    s"ccBankruptcyDefaultDebtServiceMonths must be non-negative: $ccBankruptcyDefaultDebtServiceMonths",
  )
  require(
    ccBankruptcyDefaultDebtServiceMonths >= ccRestructuringDefaultDebtServiceMonths,
    s"ccBankruptcyDefaultDebtServiceMonths must be >= ccRestructuringDefaultDebtServiceMonths, got $ccBankruptcyDefaultDebtServiceMonths < $ccRestructuringDefaultDebtServiceMonths",
  )
  require(
    ccRestructuringDefaultOutstandingShare >= Share.Zero && ccRestructuringDefaultOutstandingShare <= Share.One,
    s"ccRestructuringDefaultOutstandingShare must be in [0, 1], got $ccRestructuringDefaultOutstandingShare",
  )
  require(
    ccBankruptcyDefaultOutstandingShare >= ccRestructuringDefaultOutstandingShare && ccBankruptcyDefaultOutstandingShare <= Share.One,
    s"ccBankruptcyDefaultOutstandingShare must be in [ccRestructuringDefaultOutstandingShare, 1], got $ccBankruptcyDefaultOutstandingShare",
  )
