package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.{HhFinancialDistressState, HhStatus, Household, PerBankFlow}
import com.boombustgroup.amorfati.agents.household.HouseholdParameters.*
import com.boombustgroup.amorfati.agents.household.HouseholdStepTypes.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.types.*

/** Aggregate household accounting and distribution statistics, separated from
  * the per-household transition pipeline.
  */
private[agents] object HouseholdAggregateComputation:

  private inline def scaledDivRaw(numerator: BigInt, denominator: BigInt): Long =
    if denominator == 0 then 0L
    else
      val scaled         = numerator * BigInt(FixedPointBase.Scale)
      val quotient       = scaled / denominator
      val remainder      = (scaled % denominator).abs
      val denominatorAbs = denominator.abs
      val twiceRemainder = remainder * 2
      if twiceRemainder < denominatorAbs then quotient.toLong
      else
        val resultSign = scaled.signum * denominator.signum
        if twiceRemainder > denominatorAbs then (quotient + resultSign).toLong
        else if quotient % 2 == 0 then quotient.toLong
        else (quotient + resultSign).toLong

  /** Mutable exact accumulator for one household monthly step. */
  private[household] final class StepTotals:
    private var incomeAcc: PLN               = PLN.Zero
    private var benefitAcc: PLN              = PLN.Zero
    private var debtSvcAcc: PLN              = PLN.Zero
    private var mortgagePrincipalAcc: PLN    = PLN.Zero
    private var mortgageInterestAcc: PLN     = PLN.Zero
    private var depIntAcc: PLN               = PLN.Zero
    private var goodsConsAcc: PLN            = PLN.Zero
    private var rentAcc: PLN                 = PLN.Zero
    private var remitAcc: PLN                = PLN.Zero
    private var pitAcc: PLN                  = PLN.Zero
    private var socialAcc: PLN               = PLN.Zero
    private var ccDebtSvcAcc: PLN            = PLN.Zero
    private var ccOrigAcc: PLN               = PLN.Zero
    private var ccApprovedOrigAcc: PLN       = PLN.Zero
    private var ccDemandAcc: PLN             = PLN.Zero
    private var ccRejectedAcc: PLN           = PLN.Zero
    private var ccBankRejectedAcc: PLN       = PLN.Zero
    private var liquidityShortfallAcc: PLN   = PLN.Zero
    private var consumptionShortfallAcc: PLN = PLN.Zero
    private var rentArrearsAcc: PLN          = PLN.Zero
    private var mortgageArrearsAcc: PLN      = PLN.Zero
    private var consumerDebtArrearsAcc: PLN  = PLN.Zero
    private var temporaryOverdraftAcc: PLN   = PLN.Zero
    private var ccDefaultAcc: PLN            = PLN.Zero
    private var ccPrincipalAcc: PLN          = PLN.Zero
    private var ccLoanDefaultAcc: PLN        = PLN.Zero
    private var bridgeChargeOffAcc: PLN      = PLN.Zero
    private var unmetBasicConsAcc: PLN       = PLN.Zero
    private var discretionaryCutAcc: PLN     = PLN.Zero
    var retrainingAttempts: Int              = 0
    var retrainingSuccesses: Int             = 0
    var voluntaryQuits: Int                  = 0

    /** Adds one finalized household monthly result to the accumulator. */
    def add(r: HhMonthlyResult): Unit =
      incomeAcc = incomeAcc + r.income
      benefitAcc = benefitAcc + r.benefit
      debtSvcAcc = debtSvcAcc + r.debtService
      mortgagePrincipalAcc = mortgagePrincipalAcc + r.mortgagePrincipal
      mortgageInterestAcc = mortgageInterestAcc + r.mortgageInterest
      depIntAcc = depIntAcc + r.depositInterest
      goodsConsAcc = goodsConsAcc + r.consumption
      rentAcc = rentAcc + r.rent
      remitAcc = remitAcc + r.remittance
      pitAcc = pitAcc + r.pitTax
      socialAcc = socialAcc + r.socialTransfer
      ccDebtSvcAcc = ccDebtSvcAcc + r.credit.debtService
      ccOrigAcc = ccOrigAcc + r.credit.totalOrigination
      ccApprovedOrigAcc = ccApprovedOrigAcc + r.credit.newLoan
      ccDemandAcc = ccDemandAcc + r.credit.creditDemand
      ccRejectedAcc = ccRejectedAcc + r.credit.rejectedCreditDemand
      ccBankRejectedAcc = ccBankRejectedAcc + r.credit.bankRejectedCreditDemand
      liquidityShortfallAcc = liquidityShortfallAcc + r.credit.liquidityShortfallFinancing
      consumptionShortfallAcc = consumptionShortfallAcc + r.credit.liquidityShortfall.consumptionShortfall
      rentArrearsAcc = rentArrearsAcc + r.credit.liquidityShortfall.rentArrears
      mortgageArrearsAcc = mortgageArrearsAcc + r.credit.liquidityShortfall.mortgageArrears
      consumerDebtArrearsAcc = consumerDebtArrearsAcc + r.credit.liquidityShortfall.consumerDebtArrears
      temporaryOverdraftAcc = temporaryOverdraftAcc + r.credit.liquidityShortfall.temporaryOverdraft
      ccDefaultAcc = ccDefaultAcc + r.credit.defaultAmt
      ccPrincipalAcc = ccPrincipalAcc + r.credit.principal
      ccLoanDefaultAcc = ccLoanDefaultAcc + r.credit.consumerLoanDefault
      bridgeChargeOffAcc = bridgeChargeOffAcc + r.credit.liquidityBridgeChargeOff
      unmetBasicConsAcc = unmetBasicConsAcc + r.unmetBasicConsumption
      discretionaryCutAcc = discretionaryCutAcc + r.discretionaryConsumptionCompression
      retrainingAttempts += r.retrainingAttempt
      retrainingSuccesses += r.retrainingSuccess
      voluntaryQuits += r.voluntaryQuit

    def income: PLN                               = incomeAcc
    def unempBenefits: PLN                        = benefitAcc
    def debtService: PLN                          = debtSvcAcc
    def mortgagePrincipal: PLN                    = mortgagePrincipalAcc
    def mortgageInterest: PLN                     = mortgageInterestAcc
    def depositInterest: PLN                      = depIntAcc
    def goodsConsumption: PLN                     = goodsConsAcc
    def rent: PLN                                 = rentAcc
    def remittances: PLN                          = remitAcc
    def pit: PLN                                  = pitAcc
    def socialTransfers: PLN                      = socialAcc
    def consumerDebtService: PLN                  = ccDebtSvcAcc
    def consumerOrigination: PLN                  = ccOrigAcc
    def consumerApprovedOrigination: PLN          = ccApprovedOrigAcc
    def totalConsumerCreditDemand: PLN            = ccDemandAcc
    def totalConsumerRejectedOrigination: PLN     = ccRejectedAcc
    def totalConsumerBankRejectedOrigination: PLN = ccBankRejectedAcc
    def liquidityShortfallFinancing: PLN          = liquidityShortfallAcc
    def consumptionShortfall: PLN                 = consumptionShortfallAcc
    def rentArrears: PLN                          = rentArrearsAcc
    def mortgageArrears: PLN                      = mortgageArrearsAcc
    def consumerDebtArrears: PLN                  = consumerDebtArrearsAcc
    def temporaryOverdraft: PLN                   = temporaryOverdraftAcc
    def consumerDefault: PLN                      = ccDefaultAcc
    def consumerPrincipal: PLN                    = ccPrincipalAcc
    def consumerLoanDefault: PLN                  = ccLoanDefaultAcc
    def liquidityBridgeChargeOff: PLN             = bridgeChargeOffAcc
    def unmetBasicConsumption: PLN                = unmetBasicConsAcc
    def discretionaryConsumptionCut: PLN          = discretionaryCutAcc

  /** Builds per-bank household-flow totals from finalized per-household
    * results.
    */
  def buildPerBankFlows(flows: Vector[BankedMonthlyResult], nBanks: Int): Vector[PerBankFlow] =
    val acc = Array.fill(nBanks)(PerBankFlow.zero)
    var i   = 0
    while i < flows.length do
      val (bankId, r) = flows(i)
      val b           = bankId.toInt
      val cur         = acc(b)
      acc(b) = PerBankFlow(
        income = cur.income + r.income,
        consumption = cur.consumption + r.consumption + r.rent,
        debtService = cur.debtService + r.debtService,
        mortgageInterest = cur.mortgageInterest + r.mortgageInterest,
        depositInterest = cur.depositInterest + r.depositInterest,
        consumerDebtService = cur.consumerDebtService + r.credit.debtService,
        consumerOrigination = cur.consumerOrigination + r.credit.totalOrigination,
        consumerApprovedOrigination = cur.consumerApprovedOrigination + r.credit.newLoan,
        consumerBankRejectedOrigination = cur.consumerBankRejectedOrigination + r.credit.bankRejectedCreditDemand,
        liquidityShortfallFinancing = cur.liquidityShortfallFinancing + r.credit.liquidityShortfallFinancing,
        consumerDefault = cur.consumerDefault + r.credit.defaultAmt,
        consumerLoanDefault = cur.consumerLoanDefault + r.credit.consumerLoanDefault,
        consumerPrincipal = cur.consumerPrincipal + r.credit.principal,
      )
      i += 1
    acc.toVector

  /** Computes public aggregate stats when explicit monthly-flow totals are not
    * available.
    */
  def computeAggregates(
      households: Vector[Household.State],
      financialStocks: Vector[Household.FinancialStocks],
      marketWage: PLN,
      reservationWage: PLN,
      importAdj: Share,
      retrainingAttempts: Int,
      retrainingSuccesses: Int,
  )(using SimParams): Household.Aggregates =
    computeAggregates(
      households,
      financialStocks,
      marketWage,
      reservationWage,
      importAdj, {
        val t = StepTotals()
        t.retrainingAttempts = retrainingAttempts
        t.retrainingSuccesses = retrainingSuccesses
        t
      },
    )

  /** Computes distribution statistics and merges them with exact monthly-flow
    * totals from the step accumulator.
    */
  private[household] def computeAggregates(
      households: Vector[Household.State],
      financialStocks: Vector[Household.FinancialStocks],
      marketWage: PLN,
      reservationWage: PLN,
      importAdj: Share,
      t: StepTotals,
  )(using p: SimParams): Household.Aggregates =
    require(
      households.length == financialStocks.length,
      s"Household.computeAggregates requires aligned households and financialStocks, got ${households.length} households and ${financialStocks.length} stock rows",
    )
    val n = households.length

    var nEmployed          = 0
    var nUnemployed        = 0
    var nRetraining        = 0
    var nBankrupt          = 0
    var nDistCurrent       = 0
    var nDistStress        = 0
    var nDistArrears       = 0
    var nDistRestructuring = 0
    var nDistDefaulted     = 0
    var nDistBankruptcy    = 0
    var sumSkill           = 0L
    var sumHealth          = 0L
    var sumSavings         = 0L
    val incomes            = new Array[Long](n)
    val consumptions       = new Array[Long](n)
    val savingsArr         = new Array[Long](n)

    var i = 0
    while i < n do
      val hh     = households(i)
      val stocks = financialStocks(i)
      hh.status match
        case HhStatus.Employed(_, _, wage) =>
          nEmployed += 1
          incomes(i) = wage.toLong
          sumSkill += hh.skill.toLong
          sumHealth += hh.healthPenalty.toLong
        case HhStatus.Unemployed(months)   =>
          nUnemployed += 1
          incomes(i) = HouseholdIncomeConstruction.computeBenefit(months).toLong
          sumSkill += hh.skill.toLong
          sumHealth += hh.healthPenalty.toLong
        case HhStatus.Retraining(_, _, _)  =>
          nRetraining += 1
          incomes(i) = 0L
          sumSkill += hh.skill.toLong
          sumHealth += hh.healthPenalty.toLong
        case HhStatus.Bankrupt             =>
          nBankrupt += 1
          incomes(i) = 0L

      val distressState =
        if hh.status == HhStatus.Bankrupt then HhFinancialDistressState.Bankruptcy else hh.financialDistressState
      distressState match
        case HhFinancialDistressState.Current         => nDistCurrent += 1
        case HhFinancialDistressState.LiquidityStress => nDistStress += 1
        case HhFinancialDistressState.Arrears         => nDistArrears += 1
        case HhFinancialDistressState.Restructuring   => nDistRestructuring += 1
        case HhFinancialDistressState.Defaulted       => nDistDefaulted += 1
        case HhFinancialDistressState.Bankruptcy      => nDistBankruptcy += 1

      val rentRaw       = hh.monthlyRent.toLong
      val mortgageRate  = p.monetary.initialRate + p.housing.mortgageSpread
      val debtSvcRaw    = (HouseholdMortgageSchedule.scheduledMortgagePrincipal(stocks) + (stocks.mortgageLoan * mortgageRate.monthly)).toLong
      val disposableRaw = math.max(0L, incomes(i) - rentRaw - debtSvcRaw)
      consumptions(i) = FixedPointBase.multiplyRaw(disposableRaw, hh.mpc.toLong)
      savingsArr(i) = stocks.demandDeposit.toLong
      sumSavings += savingsArr(i)
      i += 1

    val nAlive = n - nBankrupt

    java.util.Arrays.sort(incomes)
    java.util.Arrays.sort(savingsArr)
    java.util.Arrays.sort(consumptions)

    val totalConsumption = t.goodsConsumption + t.rent
    val importCons       = t.goodsConsumption * importAdj.min(ImportRatioCap)
    val domesticCons     = totalConsumption - importCons
    val medianIncomeRaw  = if n > 0 then incomes(n / 2) else 0L

    Household.Aggregates(
      employed = nEmployed,
      unemployed = nUnemployed,
      retraining = nRetraining,
      bankrupt = nBankrupt,
      totalIncome = t.income,
      consumption = totalConsumption,
      domesticConsumption = domesticCons,
      importConsumption = importCons,
      marketWage = marketWage,
      reservationWage = reservationWage,
      giniIndividual = giniSorted(incomes),
      giniWealth = giniSorted(savingsArr),
      meanSavings = if n > 0 then PLN.fromRaw(sumSavings / n) else PLN.Zero,
      medianSavings = if n > 0 then PLN.fromRaw(savingsArr(n / 2)) else PLN.Zero,
      povertyRate50 =
        if n > 0 && medianIncomeRaw > 0L then Share.fraction(lowerBound(incomes, (PLN.fromRaw(medianIncomeRaw) * PovertyRate50Pct).toLong), n) else Share.Zero,
      bankruptcyRate = if n > 0 then Share.fraction(nBankrupt, n) else Share.Zero,
      meanSkill = if nAlive > 0 then Share.fromRaw(sumSkill / nAlive) else Share.Zero,
      meanHealthPenalty = if nAlive > 0 then Share.fromRaw(sumHealth / nAlive) else Share.Zero,
      retrainingAttempts = t.retrainingAttempts,
      retrainingSuccesses = t.retrainingSuccesses,
      consumptionP10 =
        if n > 0 then PLN.fromRaw(consumptions((n * ConsumptionP10.toLong / FixedPointBase.Scale).toInt)) else PLN.Zero,
      consumptionP50 = if n > 0 then PLN.fromRaw(consumptions(n / 2)) else PLN.Zero,
      consumptionP90 =
        if n > 0 then PLN.fromRaw(consumptions(Math.min(n - 1, (n * ConsumptionP90.toLong / FixedPointBase.Scale).toInt)))
        else PLN.Zero,
      meanMonthsToRuin = Scalar.Zero,
      povertyRate30 =
        if n > 0 && medianIncomeRaw > 0L then Share.fraction(lowerBound(incomes, (PLN.fromRaw(medianIncomeRaw) * PovertyRate30Pct).toLong), n) else Share.Zero,
      totalRent = t.rent,
      totalDebtService = t.debtService,
      totalUnempBenefits = t.unempBenefits,
      totalDepositInterest = t.depositInterest,
      crossSectorHires = 0,
      voluntaryQuits = t.voluntaryQuits,
      sectorMobilityRate = sectorMobilityRate(households),
      totalRemittances = t.remittances,
      totalPit = t.pit,
      totalSocialTransfers = t.socialTransfers,
      totalConsumerDebtService = t.consumerDebtService,
      totalConsumerOrigination = t.consumerOrigination,
      totalConsumerApprovedOrigination = t.consumerApprovedOrigination,
      totalLiquidityShortfallFinancing = t.liquidityShortfallFinancing,
      totalConsumerDefault = t.consumerDefault,
      totalConsumerPrincipal = t.consumerPrincipal,
      totalConsumerCreditDemand = t.totalConsumerCreditDemand,
      totalConsumerRejectedOrigination = t.totalConsumerRejectedOrigination,
      totalConsumerBankRejectedOrigination = t.totalConsumerBankRejectedOrigination,
      totalConsumerLoanDefault = t.consumerLoanDefault,
      totalLiquidityBridgeChargeOff = t.liquidityBridgeChargeOff,
      totalUnmetBasicConsumption = t.unmetBasicConsumption,
      totalDiscretionaryConsumptionCompression = t.discretionaryConsumptionCut,
      totalConsumptionShortfall = t.consumptionShortfall,
      totalRentArrears = t.rentArrears,
      totalMortgageArrears = t.mortgageArrears,
      totalConsumerDebtArrears = t.consumerDebtArrears,
      totalTemporaryOverdraft = t.temporaryOverdraft,
      totalMortgagePrincipal = t.mortgagePrincipal,
      totalMortgageInterest = t.mortgageInterest,
      distressCurrent = nDistCurrent,
      distressLiquidityStress = nDistStress,
      distressArrears = nDistArrears,
      distressRestructuring = nDistRestructuring,
      distressDefaulted = nDistDefaulted,
      distressBankruptcy = nDistBankruptcy,
    )

  /** Computes a Gini coefficient for a pre-sorted array, shifting negatives if
    * needed.
    */
  def giniSorted(sorted: Array[Long]): Share =
    try giniSortedLong(sorted)
    catch case _: ArithmeticException => giniSortedBig(sorted)

  private def giniSortedLong(sorted: Array[Long]): Share =
    val n           = sorted.length
    if n <= 1 then return Share.Zero
    val minVal      = sorted(0)
    if minVal == Long.MinValue then throw new ArithmeticException("gini shift overflows Long")
    val shift       = if minVal < 0L then -minVal else 0L
    var total       = 0L
    var weightedSum = 0L
    var i           = 0
    while i < n do
      val v      = java.lang.Math.addExact(sorted(i), shift)
      total = java.lang.Math.addExact(total, v)
      val weight = 2L * (i.toLong + 1L) - n.toLong - 1L
      weightedSum = java.lang.Math.addExact(weightedSum, java.lang.Math.multiplyExact(weight, v))
      i += 1
    if total <= 0L then Share.Zero
    else Share.fromRaw(FixedPointBase.ratioRaw(weightedSum, java.lang.Math.multiplyExact(n.toLong, total)))

  private def giniSortedBig(sorted: Array[Long]): Share =
    val n           = sorted.length
    if n <= 1 then return Share.Zero
    val minVal      = sorted(0)
    val shift       = if minVal < 0L then BigInt(minVal).abs else BigInt(0)
    var total       = BigInt(0)
    var weightedSum = BigInt(0)
    var i           = 0
    while i < n do
      val v = BigInt(sorted(i)) + shift
      total += v
      weightedSum += BigInt(2L * (i.toLong + 1L) - n.toLong - 1L) * v
      i += 1
    if total <= 0 then Share.Zero else Share.fromRaw(scaledDivRaw(weightedSum, BigInt(n) * total))

  /** Counts sorted elements strictly below the threshold. */
  private def lowerBound(sorted: Array[Long], threshold: Long): Int =
    var lo = 0
    var hi = sorted.length
    while lo < hi do
      val mid = (lo + hi) >>> 1
      if sorted(mid) < threshold then lo = mid + 1
      else hi = mid
    lo

  /** Computes the share of employed households whose sector changed. */
  private def sectorMobilityRate(updated: Vector[Household.State]): Share =
    var employed    = 0
    var crossSector = 0
    var i           = 0
    while i < updated.length do
      val hh = updated(i)
      hh.status match
        case HhStatus.Employed(_, sec, _) =>
          employed += 1
          if hh.lastSectorIdx.toInt >= 0 && hh.lastSectorIdx != sec then crossSector += 1
        case _                            =>
      i += 1
    if employed == 0 then Share.Zero else Share.fraction(crossSector, employed)
