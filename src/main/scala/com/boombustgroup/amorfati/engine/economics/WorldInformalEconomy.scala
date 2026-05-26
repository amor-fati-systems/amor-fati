package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.mechanisms.InformalEconomy
import com.boombustgroup.amorfati.types.*

/** Informal-economy tax evasion and shadow-share dynamics. */
object WorldInformalEconomy:

  final case class Result(
      taxEvasionLoss: PLN,
      realizedTaxShadowShare: Share,
      cyclicalAdj: Share,
      nextTaxShadowShare: Share,
  )

  /** Four-channel tax evasion (CIT, VAT, PIT, excise), estimated informal
    * employment, and smoothed cyclical adjustment for the counter-cyclical
    * shadow economy share.
    */
  def compute(in: WorldAssemblyEconomics.StepInput)(using p: SimParams): Result =
    val taxEvasionLoss =
      in.s5.sumCitEvasion + (in.s9.vat - in.s9.vatAfterEvasion) +
        (in.s3.pitRevenue - in.s9.pitAfterEvasion) +
        (in.s9.exciseRevenue - in.s9.exciseAfterEvasion)

    val realizedTaxShadowShare = in.s9.realizedTaxShadowShare

    val laborPopulation = in.s2.newDemographics.workingAgePop.max(1)
    val unemp           = Share.One - Share.fraction(in.s2.employed, laborPopulation)
    val target          = ((unemp - p.informal.unempThreshold.toScalar.toShare).max(Share.Zero) * p.informal.cyclicalSens).toShare
    val smoothing       = p.informal.smoothing.toShare
    val cyclicalAdj     = ((in.w.mechanisms.informalCyclicalAdj * smoothing) +
      (target * (Share.One - smoothing))).clamp(Share.Zero, Share.One)

    val nextTaxShadowShare = InformalEconomy.aggregateTaxShadowShare(cyclicalAdj)

    Result(taxEvasionLoss, realizedTaxShadowShare, cyclicalAdj, nextTaxShadowShare)
