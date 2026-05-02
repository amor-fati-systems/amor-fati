package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.types.*

/** Commercial banking system: balance sheets, credit risk, LCR/NSFR,
  * macroprudential, and KNF/BFG supervision.
  *
  * Models a multi-bank system (7 banks by default, calibrated to the Poland
  * 2026-04-30 production baseline) with heterogeneous balance sheets, credit
  * spreads, NPL dynamics, capital adequacy (Basel III CRR), liquidity coverage
  * (LCR/NSFR), macroprudential buffers (CCyB, O-SII), KNF BION/SREP P2R
  * add-ons, BFG resolution levy and bail-in, and interbank market.
  *
  * Stock values (`initCapital`, `initDeposits`, etc.) are in raw PLN — scaled
  * by `gdpRatio` in `SimParams.defaults`.
  *
  * @param initCapital
  *   initial aggregate regulatory-capital proxy; calibrated to KNF TCR 21.1% on
  *   the model's simplified RWA perimeter, not book equity
  * @param initDeposits
  *   initial aggregate deposits (KNF monthly banking data, February 2026:
  *   ~2,542.3 bn PLN)
  * @param initLoans
  *   initial aggregate corporate/nonfinancial business loans (KNF monthly
  *   banking data, February 2026: ~557.4 bn PLN)
  * @param initGovBonds
  *   initial commercial bank government bond holdings (NBP bridge prior: ~400
  *   mld PLN)
  * @param initNbpGovBonds
  *   initial NBP government bond holdings (NBP bridge prior: ~300 mld PLN)
  * @param initConsumerLoans
  *   initial consumer loan stock (KNF monthly banking data, February 2026:
  *   ~225.2 bn PLN)
  * @param baseSpread
  *   base lending spread over policy rate
  * @param nplSpreadFactor
  *   spread increase per unit NPL ratio
  * @param minCar
  *   minimum capital adequacy ratio (Basel III CRR: 8%)
  * @param loanRecovery
  *   loss-given-default recovery rate on corporate loans
  * @param profitRetention
  *   fraction of bank profits retained as capital
  * @param reserveReq
  *   required reserve ratio (NBP bridge prior: 3.5%)
  * @param stressThreshold
  *   CAR threshold below which bank enters stress mode
  * @param lcrMin
  *   minimum Liquidity Coverage Ratio (Basel III: 100%)
  * @param nsfrMin
  *   minimum Net Stable Funding Ratio (Basel III: 100%)
  * @param demandDepositRunoff
  *   LCR assumption: fraction of demand deposits that may run off in 30 days
  * @param termDepositFrac
  *   fraction of deposits that are term (stable for NSFR purposes)
  * @param p2rAddons
  *   per-bank BION/SREP P2R capital add-ons (KNF bridge prior, 7 banks)
  * @param bfgLevyRate
  *   annual BFG resolution fund levy as fraction of deposits (BFG bridge prior)
  * @param bailInDepositHaircut
  *   fraction of uninsured deposits bailed-in during resolution
  * @param bfgDepositGuarantee
  *   BFG deposit guarantee limit per depositor, converted from EUR 100,000 at
  *   the model-start PLN/EUR rate
  * @param ccybMax
  *   maximum countercyclical capital buffer (KNF bridge prior: 2.5%)
  * @param ccybActivationGap
  *   credit/GDP gap threshold to activate CCyB
  * @param ccybReleaseGap
  *   credit/GDP gap threshold to release CCyB
  * @param osiiBuffers
  *   O-SII buffers by default bank id (KNF decisions announced in November 2025
  *   and active in the 2026 baseline)
  * @param concentrationLimit
  *   single-name concentration limit as fraction of capital (Art. 395 CRR: 25%)
  * @param htmShare
  *   fraction of gov bond portfolio classified Held-to-Maturity (NBP bridge
  *   prior: ~60%)
  * @param htmForcedSaleThreshold
  *   LCR threshold (as fraction of lcrMin) below which HTM bonds are forcibly
  *   reclassified to AFS, realizing hidden mark-to-market losses (interest rate
  *   risk channel)
  * @param htmForcedSaleRate
  *   fraction of HTM portfolio reclassified to AFS per month under LCR stress
  * @param initHtmBookYield
  *   weighted-average acquisition yield on initial HTM portfolio (Polish 10Y at
  *   model start, MF bridge prior)
  * @param depositFlightSensitivity
  *   sensitivity of deposit switching to CAR shortfall below threshold
  * @param depositFlightCarThreshold
  *   CAR level below which depositors start leaving (KNF stress: ~10%)
  * @param depositPanicRate
  *   fraction of depositors who panic-switch when any bank fails (Diamond &
  *   Dybvig 1983)
  * @param maxDepositSwitchRate
  *   maximum fraction of HH that can switch banks per month (structural cap)
  * @param interbankRecoveryRate
  *   recovery rate on interbank exposures when counterparty fails (NBP FSR:
  *   ~40%, secured/unsecured mix)
  * @param hoardingNplThreshold
  *   system NPL ratio above which banks start hoarding liquidity (reducing
  *   interbank lending)
  * @param hoardingSensitivity
  *   speed of hoarding onset: factor = 1 − sensitivity × (NPL − threshold). At
  *   10.0, a 10pp NPL overshoot → full freeze.
  * @param eclRate1
  *   Stage 1 (performing) ECL provision rate (12-month ECL, KNF: ~1%)
  * @param eclRate2
  *   Stage 2 (watch) ECL provision rate (lifetime ECL, KNF: ~8%)
  * @param eclRate3
  *   Stage 3 (default) ECL provision rate (1 − recovery, KNF: ~50%)
  * @param eclMigrationSensitivity
  *   sensitivity of S1→S2 migration to unemployment excess above NAIRU
  * @param eclGdpSensitivity
  *   sensitivity of S1→S2 migration to GDP contraction
  * @param eclMaxMigration
  *   maximum monthly S1→S2 migration rate (structural cap)
  * @param eclCureRate
  *   monthly cure rate: fraction of S3 loans returning to S2 (restructuring)
  */
case class BankingConfig(
    // Initial balance sheet (raw — scaled by gdpRatio in SimParams.defaults)
    initCapital: PLN = PLN(168000000000L),
    initDeposits: PLN = PLN(2542300000000L),
    initLoans: PLN = PLN(557400000000L),
    initGovBonds: PLN = PLN(400000000000L),
    initNbpGovBonds: PLN = PLN(300000000000L),
    initConsumerLoans: PLN = PLN(225200000000L),
    // Spreads & risk
    baseSpread: Rate = Rate.decimal(15, 3),
    nplSpreadFactor: Multiplier = Multiplier(5),
    minCar: Multiplier = Multiplier.decimal(8, 2),
    loanRecovery: Share = Share.decimal(30, 2),
    firmLoanAmortRate: Rate = Rate.fraction(1, 60),          // monthly: 1/60 ≈ 5-year avg maturity (NBP bridge prior)
    profitRetention: Share = Share.decimal(30, 2),
    govBondDuration: Multiplier = Multiplier.decimal(45, 1), // avg modified duration of Polish gov bond portfolio (years, MF bridge prior)
    reserveReq: Share = Share.decimal(35, 3),
    stressThreshold: Share = Share.decimal(5, 2),
    // LCR/NSFR (Basel III)
    lcrMin: Multiplier = Multiplier(1),
    nsfrMin: Multiplier = Multiplier(1),
    demandDepositRunoff: Share = Share.decimal(10, 2),
    termDepositFrac: Share = Share.decimal(40, 2),
    // KNF/BFG
    p2rAddons: Vector[Multiplier] = Vector(
      Multiplier.decimal(15, 3),
      Multiplier.decimal(10, 3),
      Multiplier.decimal(30, 3),
      Multiplier.decimal(15, 3),
      Multiplier.decimal(20, 3),
      Multiplier.decimal(25, 3),
      Multiplier.decimal(20, 3),
    ),
    bfgLevyRate: Rate = Rate.decimal(24, 4),
    bailInDepositHaircut: Share = Share.decimal(8, 2),
    bfgDepositGuarantee: PLN = PLN(425370),
    // Macroprudential (KNF O-SII decisions active in the 2026 baseline)
    ccybMax: Multiplier = Multiplier.decimal(25, 3),
    ccybActivationGap: Coefficient = Coefficient.decimal(2, 2),
    ccybReleaseGap: Coefficient = Coefficient.decimal(-2, 2),
    osiiBuffers: Vector[Multiplier] = Vector(
      Multiplier.decimal(2, 2),  // PKO BP: 2.00%
      Multiplier.decimal(1, 2),  // Pekao: 1.00%
      Multiplier.decimal(5, 3),  // mBank: 0.50%
      Multiplier.decimal(1, 2),  // ING BSK: 1.00%
      Multiplier.decimal(15, 3), // Santander: 1.50%
      Multiplier.decimal(25, 4), // BPS/Coop: 0.25%
      Multiplier.decimal(25, 4), // Other O-SII banks aggregated into Others: 0.25%
    ),
    concentrationLimit: Share = Share.decimal(25, 2),
    // AFS/HTM bond portfolio split (interest rate risk channel)
    htmShare: Share = Share.decimal(60, 2),
    htmForcedSaleThreshold: Share = Share.decimal(75, 2),
    htmForcedSaleRate: Share = Share.decimal(10, 2),
    initHtmBookYield: Rate = Rate.decimal(55, 3),
    // Deposit mobility (Diamond & Dybvig 1983)
    depositFlightSensitivity: Coefficient = Coefficient(5),
    depositFlightCarThreshold: Multiplier = Multiplier.decimal(10, 2),
    depositPanicRate: Share = Share.decimal(3, 2),
    maxDepositSwitchRate: Share = Share.decimal(10, 2),
    // Interbank contagion
    interbankRecoveryRate: Share = Share.decimal(40, 2),
    hoardingNplThreshold: Share = Share.decimal(5, 2),
    hoardingSensitivity: Multiplier = Multiplier(10),
    // IFRS 9 ECL staging
    eclRate1: Share = Share.decimal(1, 2),
    eclRate2: Share = Share.decimal(8, 2),
    eclRate3: Share = Share.decimal(50, 2),
    eclMigrationSensitivity: Coefficient = Coefficient(3),
    eclGdpSensitivity: Coefficient = Coefficient(5),
    eclMaxMigration: Share = Share.decimal(20, 2),
    eclCureRate: Share = Share.decimal(2, 2),
):
  require(minCar > Multiplier.Zero && minCar < Multiplier.One, s"minCar must be in (0,1): $minCar")
  require(initCapital >= PLN.Zero, s"initCapital must be non-negative: $initCapital")
  require(initDeposits >= PLN.Zero, s"initDeposits must be non-negative: $initDeposits")
  require(p2rAddons.nonEmpty, "p2rAddons must be non-empty")
  require(
    osiiBuffers.length == p2rAddons.length,
    s"osiiBuffers must have the same length as p2rAddons: expected ${p2rAddons.length}, actual ${osiiBuffers.length}",
  )
  p2rAddons.zipWithIndex.foreach: (addon, idx) =>
    require(
      addon >= Multiplier.Zero && addon <= Multiplier.One,
      s"p2rAddons[$idx] must be in [0,1]: $addon",
    )
  osiiBuffers.zipWithIndex.foreach: (buffer, idx) =>
    require(
      buffer >= Multiplier.Zero && buffer <= Multiplier.One,
      s"osiiBuffers[$idx] must be in [0,1]: $buffer",
    )
  require(lcrMin > Multiplier.Zero, s"lcrMin must be positive: $lcrMin")
  require(nsfrMin > Multiplier.Zero, s"nsfrMin must be positive: $nsfrMin")
