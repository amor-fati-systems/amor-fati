package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.{HhStatus, Household}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Builds household income, taxes, transfers, and labor-status income effects.
  */
private[agents] object HouseholdIncomeConstruction:

  /** Computes monthly PIT using the model's progressive Polish tax brackets. */
  def computeMonthlyPit(monthlyIncome: PLN)(using p: SimParams): PLN =
    if monthlyIncome <= PLN.Zero then PLN.Zero
    else
      val afterZus   = monthlyIncome - monthlyIncome * p.social.zusEmployeeRate
      val annualized = afterZus * Multiplier(12)
      val grossTax   =
        if annualized <= p.fiscal.pitBracket1Annual then annualized * p.fiscal.pitRate1
        else
          p.fiscal.pitBracket1Annual * p.fiscal.pitRate1 +
            (annualized - p.fiscal.pitBracket1Annual) * p.fiscal.pitRate2
      (grossTax - p.fiscal.pitTaxCreditAnnual).max(PLN.Zero) / 12L

  /** Computes the household social transfer for dependent children. */
  def computeSocialTransfer(numChildren: Int)(using p: SimParams): PLN =
    if numChildren <= 0 then PLN.Zero
    else numChildren * p.fiscal.social800

  /** Computes unemployment benefit from spell duration. */
  def computeBenefit(monthsUnemployed: Int)(using p: SimParams): PLN =
    if monthsUnemployed <= p.fiscal.govBenefitDuration / 2 then p.fiscal.govBenefitM1to3
    else if monthsUnemployed <= p.fiscal.govBenefitDuration then p.fiscal.govBenefitM4to6
    else PLN.Zero

  /** Resolves base income, benefit attribution, and the next labor-income
    * status before credit and consumption decisions.
    */
  private[household] def computeIncome(hh: Household.State)(using SimParams): (PLN, PLN, HhStatus) =
    hh.status match
      case HhStatus.Employed(_, _, wage) =>
        (wage, PLN.Zero, hh.status)
      case HhStatus.Unemployed(months)   =>
        val benefit = computeBenefit(months)
        (benefit, benefit, HhStatus.Unemployed(months + 1))
      case HhStatus.Retraining(_, _, _)  =>
        (PLN.Zero, PLN.Zero, hh.status)
      case HhStatus.Bankrupt             =>
        (PLN.Zero, PLN.Zero, HhStatus.Bankrupt)
