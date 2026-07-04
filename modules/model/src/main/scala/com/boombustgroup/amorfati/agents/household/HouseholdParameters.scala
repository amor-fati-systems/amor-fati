package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.types.*

/** Shared household-agent constants used by initialization, monthly execution,
  * and aggregate diagnostics.
  */
private[agents] object HouseholdParameters:

  /** MPC sampling lower bound. */
  val MpcFloor: Share = Share.decimal(5, 1)

  /** MPC sampling upper bound. */
  val MpcCeiling: Share = Share.decimal(98, 2)

  /** Social-neighbor distress share that triggers precautionary behavior. */
  val NeighborDistressThreshold: Share = Share.decimal(30, 2)

  /** Consumption multiplier under social-neighbor distress. */
  val NeighborDistressConsAdj: Share = Share.decimal(90, 2)

  /** Retraining-probability boost under social-neighbor distress. */
  val NeighborDistressRetrainBoost: Share = Share.decimal(5, 2)

  /** Unemployment spell length before retraining becomes available. */
  val UnemploymentRetrainingThreshold: Int = 6

  /** Failed retraining maps to this unemployment duration. */
  val PostFailedRetrainingMonths: Int = 7

  /** Disposable-wage ratio below which employed households may seek credit. */
  val DisposableWageThreshold: Share = Share.decimal(3, 1)

  /** Minimum underwritten consumer-loan request. */
  val MinConsumerLoanSize: PLN = PLN(100)

  /** Initial consumer debt as a fraction of mortgage draw. */
  val ConsumerDebtInitFrac: Share = Share.decimal(3, 1)

  /** Consumption multiplier after first-month liquidity stress. */
  val LiquidityStressConsumptionMultiplier: Share = Share.decimal(95, 2)

  /** Consumption multiplier under arrears. */
  val ArrearsConsumptionMultiplier: Share = Share.decimal(90, 2)

  /** Consumption multiplier during restructuring. */
  val RestructuringConsumptionMultiplier: Share = Share.decimal(85, 2)

  /** Consumption multiplier after default or bankruptcy. */
  val DefaultedConsumptionMultiplier: Share = Share.decimal(80, 2)

  /** Initial GPW-equity share of household savings. */
  val GpwEquityInitFrac: Share = Share.decimal(5, 2)

  /** Sector skill bonus coefficient from sector complexity. */
  val SectorSkillBonusCoeff: Scalar = Scalar.decimal(2, 2)

  /** Sector skill bonus cap. */
  val SectorSkillBonusMax: Scalar = Scalar.decimal(1, 1)

  /** Import-consumption share cap. */
  val ImportRatioCap: Share = Share.decimal(65, 2)

  /** Relative poverty line at 50% median income. */
  val PovertyRate50Pct: Share = Share.decimal(50, 2)

  /** Deep-poverty line at 30% median income. */
  val PovertyRate30Pct: Share = Share.decimal(30, 2)

  /** Consumption P10 percentile index. */
  val ConsumptionP10: Share = Share.decimal(10, 2)

  /** Consumption P90 percentile index. */
  val ConsumptionP90: Share = Share.decimal(90, 2)
