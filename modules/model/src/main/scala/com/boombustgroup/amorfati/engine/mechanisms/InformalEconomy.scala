package com.boombustgroup.amorfati.engine.mechanisms

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Shared helpers for aggregate tax-side informal-economy semantics. */
object InformalEconomy:

  final case class Input(
      citEvasion: PLN,
      vatBeforeEvasion: PLN,
      vatAfterEvasion: PLN,
      pitBeforeEvasion: PLN,
      pitAfterEvasion: PLN,
      exciseBeforeEvasion: PLN,
      exciseAfterEvasion: PLN,
      realizedTaxShadowShare: Share,
      employed: Int,
      workingAgePopulation: Int,
      previousCyclicalAdjustment: Share,
  )

  final case class Result(
      taxEvasionLoss: PLN,
      realizedTaxShadowShare: Share,
      cyclicalAdj: Share,
      nextTaxShadowShare: Share,
  )

  /** Four-channel tax evasion and smoothed cyclical adjustment for the
    * counter-cyclical shadow economy share.
    */
  def compute(in: Input)(using p: SimParams): Result =
    val taxEvasionLoss =
      in.citEvasion +
        (in.vatBeforeEvasion - in.vatAfterEvasion).max(PLN.Zero) +
        (in.pitBeforeEvasion - in.pitAfterEvasion).max(PLN.Zero) +
        (in.exciseBeforeEvasion - in.exciseAfterEvasion).max(PLN.Zero)

    val cyclicalAdj = computeCyclicalAdjustment(
      employed = in.employed,
      workingAgePopulation = in.workingAgePopulation,
      previousCyclicalAdjustment = in.previousCyclicalAdjustment,
    )

    Result(
      taxEvasionLoss = taxEvasionLoss,
      realizedTaxShadowShare = in.realizedTaxShadowShare,
      cyclicalAdj = cyclicalAdj,
      nextTaxShadowShare = aggregateTaxShadowShare(cyclicalAdj),
    )

  /** Consumption-weighted aggregate shadow-economy share used by the current
    * aggregate tax channels (VAT, PIT, excise).
    */
  def aggregateTaxShadowShare(cyclicalAdj: Share)(using p: SimParams): Share =
    p.fiscal.fofConsWeights
      .zip(p.informal.sectorShares)
      .map((cw, ss) => cw * (ss + cyclicalAdj).min(Share.One))
      .foldLeft(Share.Zero)(_ + _)

  private def computeCyclicalAdjustment(
      employed: Int,
      workingAgePopulation: Int,
      previousCyclicalAdjustment: Share,
  )(using p: SimParams): Share =
    val laborPopulation = workingAgePopulation.max(1)
    val unemployment    = Share.One - Share.fraction(employed, laborPopulation)
    val target          = ((unemployment - p.informal.unempThreshold.toScalar.toShare).max(Share.Zero) * p.informal.cyclicalSens).toShare
    val smoothing       = p.informal.smoothing.toShare
    ((previousCyclicalAdjustment * smoothing) + (target * (Share.One - smoothing))).clamp(Share.Zero, Share.One)
