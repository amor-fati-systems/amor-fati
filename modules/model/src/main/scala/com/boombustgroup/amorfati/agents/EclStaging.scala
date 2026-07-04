package com.boombustgroup.amorfati.agents

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** IFRS 9 Expected Credit Loss (ECL) staging for bank loan portfolios.
  *
  * Three-stage model replacing instantaneous NPL capital hit:
  *
  *   - '''Stage 1''' (performing, 12-month ECL): loans with no significant
  *     increase in credit risk. Provision = portfolio × eclRate1 (~1%).
  *   - '''Stage 2''' (watch, lifetime ECL): loans with significant increase in
  *     credit risk (GDP decline, unemployment spike). Provision = portfolio ×
  *     eclRate2 (~5-10%). Macro trigger shifts loans from Stage 1 to Stage 2 en
  *     masse.
  *   - '''Stage 3''' (default): non-performing loans. Provision = portfolio ×
  *     eclRate3 (= 1 − recovery rate). This is the current NPL treatment.
  *
  * Pro-cyclical amplification: GDP downturn → mass Stage 1 to Stage 2 migration
  * → provisioning cliff → capital hit → lending restriction → deeper downturn.
  * Forward-looking: provisions are booked BEFORE defaults materialize.
  *
  * Pure functions — no mutable state. Per-bank staging computed from macro
  * signals (unemployment, GDP growth) and bank-level loan quality.
  *
  * Calibration: KNF IFRS 9 implementation guidelines, NBP Financial Stability
  * Report, EBA stress test methodology.
  */
object EclStaging:

  /** Per-bank ECL staging state. */
  case class State(
      stage1: PLN, // performing loans (12-month ECL)
      stage2: PLN, // watch loans (lifetime ECL)
      stage3: PLN, // defaulted loans (full provision)
  )
  object State:
    val zero: State = State(PLN.Zero, PLN.Zero, PLN.Zero)

    /** Opening all-performing book used until a calibrated Stage 2/3 split is
      * introduced.
      */
    def allStage1(coveredLoans: PLN): State =
      require(coveredLoans >= PLN.Zero, s"ECL opening covered loans must be non-negative, got $coveredLoans")
      State(coveredLoans, PLN.Zero, PLN.Zero)

  /** ECL staging result: updated stages + total provision change. */
  case class StepResult(
      newStaging: State,
      provisionChange: PLN, // Δ provision this month (positive = additional provision → capital hit)
  )

  def allowance(state: State)(using p: SimParams): PLN =
    state.stage1 * p.banking.eclRate1 + state.stage2 * p.banking.eclRate2 + state.stage3 * p.banking.eclRate3

  /** Compute macro-driven Stage 1 to Stage 2 migration rate.
    *
    * When unemployment deteriorates relative to the carried reference level or
    * GDP contracts, a fraction of performing loans migrates to Stage 2
    * (significant credit risk increase). The reference level is usually the
    * previous month's unemployment rate, bootstrapped at model start from the
    * opening macro baseline, so an opening unemployment level above NAIRU does
    * not by itself create recurring stress migration.
    *
    * migrationRate = sensitivity × max(0, unemployment − reference) +
    * gdpSensitivity × max(0, −gdpGrowth) Clamped to [0, maxMigration].
    */
  private[amorfati] def migrationRate(unemployment: Share, referenceUnemployment: Share, gdpGrowthMonthly: Coefficient)(using p: SimParams): Share =
    val unempDeterioration: Share   = (unemployment - referenceUnemployment).max(Share.Zero)
    val gdpContraction: Coefficient = (-gdpGrowthMonthly).max(Coefficient.Zero)
    val rawMigration: Coefficient   = p.banking.eclMigrationSensitivity * unempDeterioration + p.banking.eclGdpSensitivity * gdpContraction
    rawMigration.max(Coefficient.Zero).min(p.banking.eclMaxMigration.toCoefficient).toShare

  /** Monthly ECL staging step for a single bank.
    *
    * @param prev
    *   previous staging state
    * @param totalLoans
    *   total loan book (corporate + consumer)
    * @param nplNew
    *   new defaults this month (→ Stage 3)
    * @param unemployment
    *   current unemployment rate
    * @param referenceUnemployment
    *   carried baseline/lagged unemployment used to measure deterioration
    * @param gdpGrowthMonthly
    *   month-on-month GDP growth
    */
  def step(
      prev: State,
      totalLoans: PLN,
      nplNew: PLN,
      unemployment: Share,
      referenceUnemployment: Share,
      gdpGrowthMonthly: Coefficient,
  )(using p: SimParams): StepResult =
    val migration: Share = migrationRate(unemployment, referenceUnemployment, gdpGrowthMonthly)

    // Stage transitions
    val stage1ToStage2: PLN = prev.stage1 * migration             // macro-driven migration
    val stage2ToStage3: PLN = nplNew                              // actual defaults enter Stage 3
    val stage3Cure: PLN     = prev.stage3 * p.banking.eclCureRate // some Stage 3 loans recover

    // Updated stages
    val newStage3: PLN = (prev.stage3 + stage2ToStage3 - stage3Cure).max(PLN.Zero)
    val newStage2: PLN = (prev.stage2 + stage1ToStage2 - stage2ToStage3 + stage3Cure).max(PLN.Zero)
    val newStage1: PLN = (totalLoans - newStage2 - newStage3).max(PLN.Zero)

    val newStaging: State = State(newStage1, newStage2, newStage3)

    // Provision = Σ(stage × eclRate) — change vs previous month
    val prevProvision: PLN   = allowance(prev)
    val newProvision: PLN    = allowance(newStaging)
    val provisionChange: PLN = newProvision - prevProvision

    StepResult(newStaging, provisionChange)
