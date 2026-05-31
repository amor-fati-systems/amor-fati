package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.{HhFinancialDistressState, HhStatus, Household}
import com.boombustgroup.amorfati.agents.household.HouseholdParameters.*
import com.boombustgroup.amorfati.agents.household.HouseholdStepTypes.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Financial-distress, scarring, MPC, and social-neighbor state machine for
  * household monthly execution.
  */
private[agents] object HouseholdDistressMachine:

  /** Returns the consumption multiplier implied by the financial-distress
    * lifecycle state.
    */
  def financialDistressConsumptionMultiplier(state: HhFinancialDistressState): Share =
    state match
      case HhFinancialDistressState.Current         => Share.One
      case HhFinancialDistressState.LiquidityStress => LiquidityStressConsumptionMultiplier
      case HhFinancialDistressState.Arrears         => ArrearsConsumptionMultiplier
      case HhFinancialDistressState.Restructuring   => RestructuringConsumptionMultiplier
      case HhFinancialDistressState.Defaulted       => DefaultedConsumptionMultiplier
      case HhFinancialDistressState.Bankruptcy      => DefaultedConsumptionMultiplier

  /** Applies distress-driven consumption discipline while preserving the
    * configured basic-consumption floor.
    */
  def applyFinancialDistressConsumptionAdjustment(consumption: PLN, state: HhFinancialDistressState)(using p: SimParams): PLN =
    val multiplier = financialDistressConsumptionMultiplier(state)
    if multiplier >= Share.One || consumption <= PLN.Zero then consumption
    else (consumption * multiplier).max(p.household.basicConsumptionFloor.min(consumption))

  /** Advances a recovered household through the staged recovery branch. */
  def recoveryDistressState(previous: HhFinancialDistressState): HhFinancialDistressState =
    previous match
      case HhFinancialDistressState.Arrears | HhFinancialDistressState.Defaulted | HhFinancialDistressState.Bankruptcy          =>
        HhFinancialDistressState.Restructuring
      case HhFinancialDistressState.Current | HhFinancialDistressState.LiquidityStress | HhFinancialDistressState.Restructuring =>
        HhFinancialDistressState.Current

  /** Advances the financial-distress lifecycle from liquidity stress through
    * arrears, default, and personal insolvency.
    */
  def advanceFinancialDistressState(
      previous: HhFinancialDistressState,
      status: HhStatus,
      distressMonths: Int,
      personalInsolvency: Boolean,
  )(using p: SimParams): HhFinancialDistressState =
    if status == HhStatus.Bankrupt || personalInsolvency then HhFinancialDistressState.Bankruptcy
    else if distressMonths <= 0 then recoveryDistressState(previous)
    else if distressMonths >= p.household.bankruptcyDistressMonths then HhFinancialDistressState.Defaulted
    else if distressMonths >= 2 then HhFinancialDistressState.Arrears
    else HhFinancialDistressState.LiquidityStress

  /** Detects whether this month pushes the household into financial distress.
    */
  def financialDistressTriggered(f: MonthlyFlows)(using p: SimParams): Boolean =
    val essentialOutflows = (f.hh.monthlyRent + f.debtService + f.credit.debtService).max(f.hh.monthlyRent)
    val configuredFloor   = essentialOutflows * p.household.bankruptcyThreshold.toMultiplier
    f.newSavings < PLN.Zero || f.unmetBasicConsumption > PLN.Zero || f.newSavings < configuredFloor

  /** Builds an O(1) lookup set of households whose state can affect social
    * neighbor distress.
    */
  def buildDistressedSet(households: Vector[Household.State]): java.util.BitSet =
    val bits = new java.util.BitSet(households.length)
    var i    = 0
    while i < households.length do
      val hh = households(i)
      hh.status match
        case HhStatus.Bankrupt | HhStatus.Unemployed(_)                         => bits.set(i)
        case _ if hh.financialDistressState != HhFinancialDistressState.Current =>
          bits.set(i)
        case _                                                                  =>
      i += 1
    bits

  /** Computes the distressed-neighbor share for one household. */
  def neighborDistressRatioFast(hh: Household.State, distressedIds: java.util.BitSet): Share =
    if hh.socialNeighbors.isEmpty then Share.Zero
    else
      var count = 0
      var i     = 0
      while i < hh.socialNeighbors.length do
        if distressedIds.get(hh.socialNeighbors(i).toInt) then count += 1
        i += 1
      Share.fraction(count, hh.socialNeighbors.length)

  /** Applies long-term-unemployment skill decay. */
  def applySkillDecay(hh: Household.State, status: HhStatus)(using p: SimParams): Share =
    status match
      case HhStatus.Unemployed(months) if months >= p.household.scarringOnset =>
        hh.skill * (Share.One - p.household.skillDecayRate)
      case _                                                                  => hh.skill

  /** Applies cumulative health scarring from long-term unemployment. */
  def applyHealthScarring(hh: Household.State, status: HhStatus)(using p: SimParams): Share =
    status match
      case HhStatus.Unemployed(months) if months >= p.household.scarringOnset =>
        (hh.healthPenalty + p.household.scarringRate).min(p.household.scarringCap)
      case _                                                                  => hh.healthPenalty

  /** Applies wage scarring while unemployed and gradual recovery after
    * re-employment.
    */
  def applyWageScar(hh: Household.State, status: HhStatus)(using p: SimParams): Share =
    status match
      case HhStatus.Unemployed(months) if months >= p.household.scarringOnset =>
        (hh.wageScar + p.household.wageScarRate).min(p.household.wageScarCap)
      case _: HhStatus.Employed                                               =>
        (hh.wageScar - p.household.wageScarDecay).max(Share.Zero)
      case _                                                                  => hh.wageScar

  /** Updates marginal propensity to consume using the buffer-stock rule. */
  def updateMpc(hh: Household.State, financialStocks: Household.FinancialStocks, income: PLN, status: HhStatus)(using p: SimParams): Share =
    val baseMpc = hh.mpc
    if income <= PLN.Zero then baseMpc
    else
      val targetSavings = income * p.household.bufferTargetMonths
      val bufferRatio   = financialStocks.demandDeposit.ratioTo(targetSavings).toMultiplier
      val deviation     = bufferRatio - Multiplier.One
      val adjustment    = p.household.bufferSensitivity * deviation
      val bufferAdj     = (Share.One - adjustment).clamp(Share.Zero, Share.One)
      val unemployedAdj = status match
        case _: HhStatus.Unemployed => Share.One + p.household.mpcUnemployedBoost
        case _                      => Share.One
      (baseMpc * bufferAdj * unemployedAdj).clamp(MpcFloor, MpcCeiling)

  /** Computes the controlled drawdown from liquid savings buffers. */
  def computeSavingsDrawdown(
      financialStocks: Household.FinancialStocks,
      cashOnHand: PLN,
      status: HhStatus,
  )(using p: SimParams): PLN =
    val targetIncome  = cashOnHand.max(p.household.baseReservationWage)
    val targetSavings = targetIncome * p.household.bufferTargetMonths
    val protectedBuff = targetSavings * p.household.bufferProtectedShare
    status match
      case _: HhStatus.Unemployed | _: HhStatus.Retraining =>
        (financialStocks.demandDeposit - protectedBuff).max(PLN.Zero) * p.household.bufferStressDrawdownRate
      case _: HhStatus.Employed | HhStatus.Bankrupt        =>
        (financialStocks.demandDeposit - targetSavings).max(PLN.Zero) * p.household.bufferExcessDrawdownRate
