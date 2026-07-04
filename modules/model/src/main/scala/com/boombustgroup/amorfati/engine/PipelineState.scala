package com.boombustgroup.amorfati.engine

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Explicit informational surface available at the start of a month.
  *
  * This is the persisted `pre` side of the timing contract. Same-month
  * operational artifacts belong on [[OperationalSignals]], not here.
  */
case class DecisionSignals(
    unemploymentRate: Share,
    inflation: Rate,
    expectedInflation: Rate,
    laggedHiringSlack: Share,
    startupAbsorptionRate: Share,
    sectorDemandMult: Vector[Multiplier],
    sectorDemandPressure: Vector[Multiplier],
    sectorHiringSignal: Vector[Multiplier],
)
object DecisionSignals:
  def zero(sectorCount: Int): DecisionSignals =
    DecisionSignals(
      unemploymentRate = Share.Zero,
      inflation = Rate.Zero,
      expectedInflation = Rate.Zero,
      laggedHiringSlack = Share.One,
      startupAbsorptionRate = Share.One,
      sectorDemandMult = Vector.fill(sectorCount)(Multiplier.One),
      sectorDemandPressure = Vector.fill(sectorCount)(Multiplier.One),
      sectorHiringSignal = Vector.fill(sectorCount)(Multiplier.One),
    )

/** Inter-step pipeline signals carried into the next month.
  *
  * This remains the persisted substrate, while [[World.seedIn]] exposes the
  * narrower decision-oriented surface consumed by timing-sensitive blocks.
  * Same-month operational consumers should prefer [[OperationalSignals]]
  * instead of reading directly from this structure.
  */
case class PipelineState(
    sectorDemandMult: Vector[Multiplier],       // per-sector demand multipliers from S4
    sectorDemandPressure: Vector[Multiplier],   // persistent sector demand/capacity pressure signal
    sectorHiringSignal: Vector[Multiplier],     // smoothed sector hiring signal used by firm labor planning
    fiscalRuleSeverity: Int = 0,                // 0=none, 1=SRW, 2=SGP, 3=Art86_55, 4=Art216_60
    govSpendingCutRatio: Share = Share.Zero,    // fraction of raw spending cut by fiscal rules
    laggedHiringSlack: Share = Share.One,       // month t labor-tightness signal carried into month t+1 macro decisions
    operationalHiringSlack: Share = Share.One,  // persisted snapshot of month-t labor compression for observability / compatibility
    startupAbsorptionRate: Share = Share.One,   // share of startup hiring targets filled across active startup firms
    laggedUnemploymentRate: Share = Share.Zero, // end-of-month unemployment extracted for next-month decisions
    laggedInflation: Rate = Rate.Zero,          // realized inflation lagged into next month
    laggedExpectedInflation: Rate = Rate.Zero,  // expected inflation lagged into next month
):
  def seedIn: DecisionSignals =
    DecisionSignals(
      unemploymentRate = laggedUnemploymentRate,
      inflation = laggedInflation,
      expectedInflation = laggedExpectedInflation,
      laggedHiringSlack = laggedHiringSlack,
      startupAbsorptionRate = startupAbsorptionRate,
      sectorDemandMult = sectorDemandMult,
      sectorDemandPressure = sectorDemandPressure,
      sectorHiringSignal = sectorHiringSignal,
    )

  def withDecisionSignals(signals: DecisionSignals): PipelineState =
    copy(
      sectorDemandMult = signals.sectorDemandMult,
      sectorDemandPressure = signals.sectorDemandPressure,
      sectorHiringSignal = signals.sectorHiringSignal,
      laggedHiringSlack = signals.laggedHiringSlack,
      startupAbsorptionRate = signals.startupAbsorptionRate,
      laggedUnemploymentRate = signals.unemploymentRate,
      laggedInflation = signals.inflation,
      laggedExpectedInflation = signals.expectedInflation,
    )

  def withSameMonthDiagnostics(
      operationalHiringSlack: Share,
      fiscalRuleSeverity: Int,
      govSpendingCutRatio: Share,
  ): PipelineState =
    copy(
      operationalHiringSlack = operationalHiringSlack,
      fiscalRuleSeverity = fiscalRuleSeverity,
      govSpendingCutRatio = govSpendingCutRatio,
    )

object PipelineState:
  def zero(sectorCount: Int): PipelineState =
    PipelineState(
      sectorDemandMult = Vector.fill(sectorCount)(Multiplier.One),
      sectorDemandPressure = Vector.fill(sectorCount)(Multiplier.One),
      sectorHiringSignal = Vector.fill(sectorCount)(Multiplier.One),
    )

  def bootstrap(
      sectorCount: Int,
      unemploymentRate: Share,
      inflation: Rate,
      expectedInflation: Rate,
  ): PipelineState =
    zero(sectorCount).copy(
      laggedUnemploymentRate = unemploymentRate,
      laggedInflation = inflation,
      laggedExpectedInflation = expectedInflation,
    )

  def zero(using p: SimParams): PipelineState = zero(p.sectorDefs.length)
