package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.{HhFinancialDistressState, HhStatus, Household}
import com.boombustgroup.amorfati.agents.household.HouseholdParameters.*
import com.boombustgroup.amorfati.agents.household.HouseholdStepAccumulator.StepTotals
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.types.*

/** Household aggregate accounting from aligned household states, financial
  * stocks, and exact monthly flow totals.
  */
private[agents] object HouseholdAggregateComputation:

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
      giniIndividual = HouseholdDistributionStats.giniSorted(incomes),
      giniWealth = HouseholdDistributionStats.giniSorted(savingsArr),
      meanSavings = if n > 0 then PLN.fromRaw(sumSavings / n) else PLN.Zero,
      medianSavings = if n > 0 then PLN.fromRaw(savingsArr(n / 2)) else PLN.Zero,
      povertyRate50 =
        if n > 0 && medianIncomeRaw > 0L then
          Share.fraction(HouseholdDistributionStats.lowerBound(incomes, (PLN.fromRaw(medianIncomeRaw) * PovertyRate50Pct).toLong), n)
        else Share.Zero,
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
        if n > 0 && medianIncomeRaw > 0L then
          Share.fraction(HouseholdDistributionStats.lowerBound(incomes, (PLN.fromRaw(medianIncomeRaw) * PovertyRate30Pct).toLong), n)
        else Share.Zero,
      totalRent = t.rent,
      totalDebtService = t.debtService,
      totalUnempBenefits = t.unempBenefits,
      totalDepositInterest = t.depositInterest,
      crossSectorHires = 0,
      voluntaryQuits = t.voluntaryQuits,
      sectorMobilityRate = HouseholdDistributionStats.sectorMobilityRate(households),
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
      totalConsumerBankPortfolioRejected = t.totalConsumerBankRejectedPortfolio,
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
