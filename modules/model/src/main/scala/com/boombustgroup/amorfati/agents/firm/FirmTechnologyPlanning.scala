package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.agents.TechState
import com.boombustgroup.amorfati.agents.Firm.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.amorfati.agents.firm.FirmCalibration.*

/** Technology-investment cost and adoption-neighborhood calculations. */
private[agents] object FirmTechnologyPlanning:

  /** Effective AI CAPEX after sector, size, readiness, and firm-cost effects.
    */
  def computeAiCapex(f: State)(using p: SimParams): PLN =
    val sizeFactor   = Scalar.fraction(f.initialSize, p.pop.workersPerFirm).pow(CapexSizeExponent).toMultiplier
    val digiDiscount = Share.One - p.firm.digiCapexDiscount * f.digitalReadiness
    p.firm.aiCapex * p.sectorDefs(f.sector.toInt).aiCapexMultiplier * digiDiscount * (f.innovationCostFactor * sizeFactor)

  /** Effective hybrid CAPEX after sector, size, readiness, and firm-cost
    * effects.
    */
  def computeHybridCapex(f: State)(using p: SimParams): PLN =
    val sizeFactor   = Scalar.fraction(f.initialSize, p.pop.workersPerFirm).pow(CapexSizeExponent).toMultiplier
    val digiDiscount = Share.One - p.firm.digiCapexDiscount * f.digitalReadiness
    p.firm.hybridCapex * p.sectorDefs(f.sector.toInt).hybridCapexMultiplier * digiDiscount * (f.innovationCostFactor * sizeFactor)

  /** Digital-readiness investment cost for a firm of this size. */
  def computeDigiInvestCost(f: State)(using p: SimParams): PLN =
    val sizeFactor = Scalar.fraction(f.initialSize, p.pop.workersPerFirm).pow(OpexSizeExponent).toMultiplier
    p.firm.digiInvestCost * sizeFactor

  /** Share of network neighbors already in automated or hybrid regimes. */
  def computeLocalAutoRatio(firm: State, firms: Vector[State]): Share =
    val neighbors = firm.neighbors
    if neighbors.isEmpty then return Share.Zero
    val autoCount = neighbors.count: nid =>
      val nf = firms(nid.toInt)
      nf.tech.isInstanceOf[TechState.Automated] || nf.tech.isInstanceOf[TechState.Hybrid]
    Share.fraction(autoCount, neighbors.length)

  /** Sigma-based profitability threshold modifier for technology adoption. */
  def sigmaThreshold(sigma: Sigma): Multiplier =
    (SigmaThreshBase + SigmaThreshScale * sigma.toScalar.log10.toMultiplier).min(Multiplier.One)
