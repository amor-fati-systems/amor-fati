package com.boombustgroup.amorfati.engine.markets

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Fiscal rule constraints: Art. 216 Konstytucja RP (60% debt/GDP ceiling),
  * Art. 86 uFP (55% cautionary threshold), SRW Art. 112aa uFP (expenditure
  * growth rule), and SGP Pakt Stabilności i Wzrostu (3% deficit/GDP limit).
  *
  * Stateless — pure constraint logic applied to government spending decisions
  * before they flow into FiscalBudget.update.
  */
object FiscalRules:

  /** Inputs for fiscal rule evaluation. */
  case class Input(
      rawGovPurchases: PLN,  // unconstrained gov purchases from DemandStep formula
      prevGovSpend: PLN,     // previous month total gov spending (current + capital)
      cumulativeDebt: PLN,   // current government debt stock
      monthlyGdp: PLN,       // monthly GDP proxy (gdpProxy)
      prevRevenue: PLN,      // previous month total tax revenue
      prevDeficit: PLN,      // previous month budget deficit (single-month flow)
      inflation: Rate,       // current CPI YoY inflation
      outputGap: Coefficient, // (unemp − NAIRU) / NAIRU (positive = slack)
  )

  /** Result of fiscal rule application. */
  case class Output(
      constrainedGovPurchases: PLN, // final gov purchases after all rule constraints
      status: RuleStatus,           // which rules are binding + diagnostics
  )

  /** Rule compliance status for observability */
  case class RuleStatus(
      debtToGdp: Share,       // current debt/GDP ratio
      deficitToGdp: Share,    // current deficit/GDP ratio (annualized)
      srwCeiling: PLN,        // SRW expenditure ceiling this month
      bindingRule: Int,       // 0=none, 1=SRW, 2=SGP, 3=Art86_55, 4=Art216_60
      spendingCutRatio: Share, // fraction of raw spending that was cut (0 = no cut)
  )

  /** Apply fiscal rules in order of severity, taking the most restrictive. */
  def constrain(in: Input)(using p: SimParams): Output =
    val annualGdp    = in.monthlyGdp * Multiplier(12)
    val debtToGdp    = if annualGdp > PLN.Zero then in.cumulativeDebt.ratioTo(annualGdp).toShare else Share.Zero
    val deficitToGdp =
      if annualGdp > PLN.Zero then in.prevDeficit.ratioTo(in.monthlyGdp).toShare else Share.Zero // monthly deficit / monthly GDP = annualized ratio

    // 1. SRW: expenditure growth ceiling with convergence blending
    val srwCeiling = computeSrwCeiling(in)
    val afterSrw   = blendSrw(in.rawGovPurchases, srwCeiling)

    // 2. SGP: converge spending toward the 3% deficit path
    val afterSgp = applySgp(afterSrw, in, deficitToGdp)

    // 3. Art. 86 (55%): consolidation cut
    val afterArt86 = applyConsolidation55(afterSgp, debtToGdp)

    // 4. Art. 216 (60%): staged consolidation above the constitutional ceiling
    val afterArt216 = applyArt216(afterArt86, debtToGdp)

    // Determine which rule is binding (most restrictive wins)
    val constrained = afterArt216
    val bindingRule = determineBindingRule(in.rawGovPurchases, afterSrw, afterSgp, afterArt86, afterArt216)
    val cutRatio    =
      if in.rawGovPurchases > PLN.Zero then (in.rawGovPurchases - constrained).ratioTo(in.rawGovPurchases).toShare.max(Share.Zero)
      else Share.Zero

    Output(
      constrainedGovPurchases = constrained,
      status = RuleStatus(debtToGdp, deficitToGdp, srwCeiling, bindingRule, cutRatio),
    )

  /** SRW ceiling: previous spending × (1 + monthly inflation + monthly real cap
    * + cyclical slack allowance).
    */
  private def computeSrwCeiling(in: Input)(using p: SimParams): PLN =
    val monthlyInflation = in.inflation.monthly.toMultiplier
    val monthlyRealCap   = p.fiscal.srwRealGrowthCap.monthly.toMultiplier
    val slackAllowance   = ((in.outputGap * p.fiscal.srwOutputGapSensitivity).max(Coefficient.Zero).toScalar / 12).toMultiplier
    in.prevGovSpend * (Multiplier.One + monthlyInflation + monthlyRealCap + slackAllowance)

  /** Blend raw spending toward SRW ceiling at convergence speed. */
  private def blendSrw(raw: PLN, ceiling: PLN)(using p: SimParams): PLN =
    if ceiling >= raw then raw
    else
      val s = p.fiscal.srwCorrectionSpeed.monthly
      raw * (Share.One - s) + ceiling * s

  /** SGP: if annualized deficit/GDP > limit, converge gradually toward revenue
    * + allowable deficit. The EU excessive-deficit process is a multi-year
    * adjustment path, not an instantaneous monthly spending cap. The correction
    * speed scales with the size of the deficit overshoot, so a 10% deficit
    * closes faster than a 4% deficit without imposing a hard spending floor.
    */
  private def applySgp(spending: PLN, in: Input, deficitToGdp: Share)(using p: SimParams): PLN =
    if deficitToGdp > p.fiscal.sgpDeficitLimit then
      val prevTotalSpend        = in.prevRevenue + in.prevDeficit
      val prevNonPurchaseSpend  = (prevTotalSpend - in.prevGovSpend).max(PLN.Zero)
      val allowableDeficit      = in.monthlyGdp * p.fiscal.sgpDeficitLimit
      val maxDiscretionarySpend = (in.prevRevenue + allowableDeficit - prevNonPurchaseSpend).max(PLN.Zero)
      if spending > maxDiscretionarySpend then
        val excess         = spending - maxDiscretionarySpend
        val deficitGap     = (deficitToGdp - p.fiscal.sgpDeficitLimit).max(Share.Zero)
        val gapScale       =
          if p.fiscal.sgpDeficitLimit > Share.Zero then (Share.One.toScalar + deficitGap.ratioTo(p.fiscal.sgpDeficitLimit)).toMultiplier
          else Multiplier.One
        val correctionRate = (p.fiscal.sgpCorrectionSpeed.monthly * gapScale).toShare.min(Share.One)
        spending - excess * correctionRate
      else spending
    else spending

  /** Art. 86 uFP (55%): apply consolidation spending cut. */
  private def applyConsolidation55(spending: PLN, debtToGdp: Share)(using p: SimParams): PLN =
    if debtToGdp > p.fiscal.fiscalRuleCautionThreshold then spending * (Share.One - p.fiscal.fiscalConsolidationSpeed55.monthly)
    else spending

  /** Art. 216 (60%): staged consolidation above the constitutional ceiling.
    *
    * The monthly model applies the rule as a consolidation path rather than an
    * instantaneous cap to last month's revenue. A hard one-month balance cap is
    * too procyclical for baseline calibration because central-budget purchases
    * are decided before current-month revenues are known.
    */
  private def applyArt216(spending: PLN, debtToGdp: Share)(using p: SimParams): PLN =
    if debtToGdp > p.fiscal.fiscalRuleDebtCeiling then spending * (Share.One - p.fiscal.fiscalConsolidationSpeed60.monthly)
    else spending

  /** Identify which rule is most restrictive (highest severity binding). */
  private def determineBindingRule(
      raw: PLN,
      afterSrw: PLN,
      afterSgp: PLN,
      afterArt86: PLN,
      afterArt216: PLN,
  ): Int =
    if afterArt216 < afterArt86 then 4   // Art. 216 (60%) binding
    else if afterArt86 < afterSgp then 3 // Art. 86 (55%) binding
    else if afterSgp < afterSrw then 2 // SGP binding
    else if afterSrw < raw then 1 // SRW binding
    else 0 // no rule binding
