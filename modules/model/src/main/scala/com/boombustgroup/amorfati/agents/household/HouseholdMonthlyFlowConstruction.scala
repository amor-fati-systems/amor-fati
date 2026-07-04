package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.{BankRates, HhStatus, Household}
import com.boombustgroup.amorfati.agents.household.HouseholdParameters.*
import com.boombustgroup.amorfati.agents.household.HouseholdStepTypes.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.World
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Month-level household budget pipeline: income, credit, consumption,
  * liquidity settlement, labor transition, and final stock construction.
  */
private[agents] object HouseholdMonthlyFlowConstruction:

  /** Computes all pre-branch budget flows for one household. */
  private def computeMonthlyFlows(
      hh: Household.State,
      financialStocks: Household.FinancialStocks,
      world: World,
      rng: RandomStream,
      bankRates: Option[BankRates],
      equityIndexReturn: Rate,
      distressedIds: java.util.BitSet,
      bankCreditSupply: Option[Household.BankCreditSupply],
  )(using p: SimParams): MonthlyFlows =
    val (baseIncome, benefit, newStatus) = HouseholdIncomeConstruction.computeIncome(hh)

    val mortgageRate: Rate = bankRates match
      case Some(br) => br.lendingRates(hh.bankId.toInt)
      case None     => world.nbp.referenceRate + p.housing.mortgageSpread

    val depInterest: PLN = bankRates match
      case Some(br) => financialStocks.demandDeposit * br.depositRates(hh.bankId.toInt).monthly
      case None     => PLN.Zero

    val grossIncome       = baseIncome + depInterest.max(PLN.Zero)
    val pitTax            = HouseholdIncomeConstruction.computeMonthlyPit(grossIncome)
    val socialTransfer    = HouseholdIncomeConstruction.computeSocialTransfer(hh.numDependentChildren)
    val income            = grossIncome - pitTax + socialTransfer
    val mortgagePrincipal = HouseholdMortgageSchedule.scheduledMortgagePrincipal(financialStocks)
    val mortgageInterest  = financialStocks.mortgageLoan * mortgageRate.max(Rate.Zero).monthly
    val thisDebtService   = mortgagePrincipal + mortgageInterest
    val newMortgageLoan   = (financialStocks.mortgageLoan - mortgagePrincipal).max(PLN.Zero)

    val remittance =
      if hh.isImmigrant then income * p.immigration.remitRate
      else PLN.Zero

    val obligations         = hh.monthlyRent + thisDebtService + remittance
    val disposablePreCredit = (income - obligations).max(PLN.Zero)
    val credit              =
      HouseholdConsumerCredit.processConsumerCredit(
        hh,
        financialStocks,
        income,
        disposablePreCredit,
        thisDebtService,
        world,
        bankRates,
        rng,
        bankCreditSupply,
      )
    val fullObligations     = obligations + credit.debtService
    val disposable          = (income - fullObligations).max(PLN.Zero)
    val savingsDrawdown     = HouseholdDistressMachine.computeSavingsDrawdown(financialStocks, income, newStatus)
    val consumptionBudget   = disposable + credit.newLoan + savingsDrawdown
    val consumption         = consumptionBudget * hh.mpc

    val neighborDistress            = HouseholdDistressMachine.neighborDistressRatioFast(hh, distressedIds)
    val consumptionAdj              =
      if neighborDistress > NeighborDistressThreshold then consumption * NeighborDistressConsAdj else consumption
    val distressAdjustedConsumption = HouseholdDistressMachine.applyFinancialDistressConsumptionAdjustment(consumptionAdj, hh.financialDistressState)

    val newEquityWealth       = (financialStocks.equity * (Multiplier.One + equityIndexReturn.toMultiplier)).max(PLN.Zero)
    val equityGain            = newEquityWealth - financialStocks.equity
    val equityBoost           =
      if equityGain > PLN.Zero then equityGain * p.equity.wealthEffectMpc
      else PLN.Zero
    val housingBoost          = world.real.housing.lastWealthEffect / world.derivedTotalPopulation.toLong.max(1L)
    val consumptionWithWealth = distressAdjustedConsumption + equityBoost + housingBoost
    val consumptionWaterfall  =
      HouseholdLiquidityWaterfall.applyConsumptionWaterfall(financialStocks.demandDeposit, income, credit.newLoan, fullObligations, consumptionWithWealth)

    val initialFlows = MonthlyFlows(
      hh = hh,
      financialStocks = financialStocks,
      income = income,
      benefit = benefit,
      newStatus = newStatus,
      debtService = thisDebtService,
      mortgagePrincipal = mortgagePrincipal,
      mortgageInterest = mortgageInterest,
      depositInterest = depInterest.max(PLN.Zero),
      remittance = remittance,
      pitTax = pitTax,
      socialTransfer = socialTransfer,
      credit = credit,
      desiredConsumption = consumptionWaterfall.desiredConsumption,
      consumption = consumptionWaterfall.actualConsumption,
      unmetBasicConsumption = consumptionWaterfall.unmetBasicConsumption,
      discretionaryConsumptionCompression = consumptionWaterfall.discretionaryConsumptionCompression,
      newEquityWealth = newEquityWealth,
      newSavings = financialStocks.demandDeposit + income - fullObligations + credit.newLoan - consumptionWaterfall.actualConsumption,
      newDebt = newMortgageLoan,
      newMortgageRemainingMonths = HouseholdMortgageSchedule.remainingMortgageMonthsAfterPayment(financialStocks, newMortgageLoan),
      neighborDistress = neighborDistress,
    )
    HouseholdConsumerCredit.underwriteResidualShortfall(initialFlows, rng, bankCreditSupply)

  /** Runs one household through the monthly survival/default branch. */
  def processHousehold(
      hh: Household.State,
      financialStocks: Household.FinancialStocks,
      world: World,
      rng: RandomStream,
      bankRates: Option[BankRates],
      equityIndexReturn: Rate,
      laborContext: Option[HouseholdLaborTransitionContext],
      distressedIds: java.util.BitSet,
      bankCreditSupply: Option[Household.BankCreditSupply],
  )(using p: SimParams): HhMonthlyResult =
    val f               = computeMonthlyFlows(hh, financialStocks, world, rng, bankRates, equityIndexReturn, distressedIds, bankCreditSupply)
    val distressMonths  =
      if HouseholdDistressMachine.financialDistressTriggered(f) then hh.financialDistressMonths + 1 else 0
    val filingShortfall =
      if distressMonths > 0 then HouseholdLiquidityWaterfall.attributeLiquidityShortfall(f, f.newSavings, temporaryOutflow = PLN.Zero)
      else Household.LiquidityShortfallComponents.Zero
    if HouseholdDistressMachine.personalInsolvencyFires(f, distressMonths, filingShortfall, rng) then resolveBankruptcy(f, distressMonths, filingShortfall)
    else resolveSurvival(f, laborContext, rng, distressMonths)

  /** Builds the public monthly diagnostic row from a finalized household
    * result.
    */
  def monthlyFlow(hh: Household.State, stocks: Household.FinancialStocks, result: HhMonthlyResult): Household.MonthlyFlow =
    val bankApproval = result.credit.bankApproval
    val portfolio    = bankApproval.flatMap(_.audit.portfolioChoice)
    Household.MonthlyFlow(
      householdId = hh.id,
      openingDemandDeposit = stocks.demandDeposit,
      openingConsumerLoan = stocks.consumerLoan,
      monthlyIncome = result.income,
      consumption = result.consumption,
      rent = result.rent,
      mortgageDebtService = result.debtService,
      consumerApprovedOrigination = result.credit.newLoan,
      consumerCreditDemand = result.credit.creditDemand,
      consumerRejectedOrigination = result.credit.rejectedCreditDemand,
      consumerBankRejectedOrigination = result.credit.bankRejectedCreditDemand,
      consumerCreditCapacity = result.credit.creditCapacity,
      consumerCreditAccessEligible = result.credit.creditAccessEligible,
      liquidityShortfallFinancing = result.credit.liquidityShortfallFinancing,
      consumerDebtService = result.credit.debtService,
      consumerDefault = result.credit.defaultAmt,
      consumerPrincipal = result.credit.principal,
      closingConsumerLoan = result.financialStocks.consumerLoan,
      consumerLoanDefault = result.credit.consumerLoanDefault,
      consumerInsolvencyDefault = result.credit.insolvencyDefaultAmt,
      liquidityBridgeChargeOff = result.credit.liquidityBridgeChargeOff,
      unmetBasicConsumption = result.unmetBasicConsumption,
      discretionaryConsumptionCompression = result.discretionaryConsumptionCompression,
      consumptionShortfall = result.credit.liquidityShortfall.consumptionShortfall,
      rentArrears = result.credit.liquidityShortfall.rentArrears,
      mortgageArrears = result.credit.liquidityShortfall.mortgageArrears,
      consumerDebtArrears = result.credit.liquidityShortfall.consumerDebtArrears,
      temporaryOverdraft = result.credit.liquidityShortfall.temporaryOverdraft,
      consumerBankApprovalProduct = bankApproval.map(_.product.diagnosticCode).getOrElse(""),
      consumerBankRejectionReason = bankApproval.flatMap(_.audit.rejectionReason).map(_.diagnosticCode).getOrElse(""),
      consumerBankApprovalProbability = bankApproval.flatMap(_.approvalProbability),
      consumerBankApprovalRoll = bankApproval.flatMap(_.approvalRoll),
      consumerBankProjectedCar = bankApproval.flatMap(_.audit.projectedCar),
      consumerBankMinCar = bankApproval.flatMap(_.audit.minCar),
      consumerBankManagementCarTarget = bankApproval.flatMap(_.audit.managementCarTarget),
      consumerBankCapitalThrottle = bankApproval.flatMap(_.audit.capitalThrottle),
      consumerBankLcr = bankApproval.flatMap(_.audit.lcr),
      consumerBankNsfr = bankApproval.flatMap(_.audit.nsfr),
      consumerBankPortfolioChoice = portfolio,
    )

  /** Resolves a personal-insolvency filing through bounded unsecured default
    * and equity write-off while leaving remaining consumer debt in workout.
    */
  private def resolveBankruptcy(
      f: MonthlyFlows,
      distressMonths: Int,
      liquidityShortfall: Household.LiquidityShortfallComponents,
  )(using p: SimParams): HhMonthlyResult =
    val (finalDemandDeposit, settledCredit) = HouseholdLiquidityWaterfall.settleLiquidityShortfall(f.newSavings, f.credit, liquidityShortfall)
    val ccDefaultAmt                        =
      HouseholdDistressMachine.boundedConsumerLoanDefault(settledCredit, liquidityShortfall, distressMonths, personalInsolvency = true)
    val creditWithDef                       =
      settledCredit.copy(
        defaultAmt = settledCredit.defaultAmt + ccDefaultAmt,
        insolvencyDefaultAmt = settledCredit.insolvencyDefaultAmt + ccDefaultAmt,
        updatedDebt = (settledCredit.updatedDebt - ccDefaultAmt).max(PLN.Zero),
      )
    val financial                           = Household.FinancialStocks(
      demandDeposit = finalDemandDeposit,
      mortgageLoan = f.newDebt,
      consumerLoan = creditWithDef.updatedDebt,
      equity = PLN.Zero,
      mortgageRemainingMonths = f.newMortgageRemainingMonths,
    )
    HhMonthlyResult(
      newState = f.hh.copy(
        status = f.newStatus,
        financialDistressMonths = 0,
        financialDistressState =
          HouseholdDistressMachine.advanceFinancialDistressState(f.hh.financialDistressState, f.newStatus, distressMonths, personalInsolvency = true),
      ),
      income = f.income,
      benefit = f.benefit,
      consumption = f.consumption,
      debtService = f.debtService,
      mortgagePrincipal = f.mortgagePrincipal,
      mortgageInterest = f.mortgageInterest,
      depositInterest = f.depositInterest,
      remittance = f.remittance,
      pitTax = f.pitTax,
      socialTransfer = f.socialTransfer,
      credit = creditWithDef,
      voluntaryQuit = 0,
      retrainingAttempt = 0,
      retrainingSuccess = 0,
      equityWealth = PLN.Zero,
      rent = f.hh.monthlyRent,
      financialStocks = financial,
      unmetBasicConsumption = f.unmetBasicConsumption,
      discretionaryConsumptionCompression = f.discretionaryConsumptionCompression,
    )

  /** Resolves normal survival, including scarring, labor transitions, and
    * non-negative demand-deposit settlement.
    */
  private def resolveSurvival(
      f: MonthlyFlows,
      laborContext: Option[HouseholdLaborTransitionContext],
      rng: RandomStream,
      distressMonths: Int,
  )(using p: SimParams): HhMonthlyResult =
    val afterSkill    = HouseholdDistressMachine.applySkillDecay(f.hh, f.newStatus)
    val afterHealth   = HouseholdDistressMachine.applyHealthScarring(f.hh, f.newStatus)
    val afterWageScar = HouseholdDistressMachine.applyWageScar(f.hh, f.newStatus)
    val afterMpc      = HouseholdDistressMachine.updateMpc(f.hh, f.financialStocks, f.income, f.newStatus)

    val (afterVoluntary, vQuit) = f.newStatus match
      case emp: HhStatus.Employed =>
        laborContext match
          case Some(context) => HouseholdLaborTransitions.tryVoluntarySearch(f.financialStocks, emp, context, rng)
          case None          => (f.newStatus, 0)
      case _                      => (f.newStatus, 0)

    val (finalStatus, rAttempt, rSuccess) =
      HouseholdLaborTransitions.tryRetraining(f.hh, f.financialStocks, afterVoluntary, f.neighborDistress, laborContext, rng)
    val nextFinancialDistressState        =
      HouseholdDistressMachine.advanceFinancialDistressState(f.hh.financialDistressState, finalStatus, distressMonths, personalInsolvency = false)

    val retrainingCostThisMonth             = finalStatus match
      case HhStatus.Retraining(ml, _, cost) if ml == p.household.retrainingDuration - 1 => cost
      case _                                                                            => PLN.Zero
    val rawFinalDemandDeposit               = f.newSavings - retrainingCostThisMonth
    val liquidityShortfall                  = HouseholdLiquidityWaterfall.attributeLiquidityShortfall(f, rawFinalDemandDeposit, temporaryOutflow = retrainingCostThisMonth)
    val (finalDemandDeposit, settledCredit) =
      HouseholdLiquidityWaterfall.settleLiquidityShortfall(rawFinalDemandDeposit, f.credit, liquidityShortfall)
    val stagedDefault                       =
      HouseholdDistressMachine.boundedConsumerLoanDefault(settledCredit, liquidityShortfall, distressMonths, personalInsolvency = false)
    val creditAfterWorkout                  =
      settledCredit.copy(defaultAmt = settledCredit.defaultAmt + stagedDefault, updatedDebt = (settledCredit.updatedDebt - stagedDefault).max(PLN.Zero))
    val financial                           = Household.FinancialStocks(
      demandDeposit = finalDemandDeposit,
      mortgageLoan = f.newDebt,
      consumerLoan = creditAfterWorkout.updatedDebt,
      equity = f.newEquityWealth,
      mortgageRemainingMonths = f.newMortgageRemainingMonths,
    )

    HhMonthlyResult(
      newState = f.hh.copy(
        skill = afterSkill,
        healthPenalty = afterHealth,
        wageScar = afterWageScar,
        mpc = afterMpc,
        status = finalStatus,
        financialDistressMonths = distressMonths,
        financialDistressState = nextFinancialDistressState,
      ),
      income = f.income,
      benefit = f.benefit,
      consumption = f.consumption,
      debtService = f.debtService,
      mortgagePrincipal = f.mortgagePrincipal,
      mortgageInterest = f.mortgageInterest,
      depositInterest = f.depositInterest,
      remittance = f.remittance,
      pitTax = f.pitTax,
      socialTransfer = f.socialTransfer,
      credit = creditAfterWorkout,
      voluntaryQuit = vQuit,
      retrainingAttempt = rAttempt,
      retrainingSuccess = rSuccess,
      equityWealth = f.newEquityWealth,
      rent = f.hh.monthlyRent,
      financialStocks = financial,
      unmetBasicConsumption = f.unmetBasicConsumption,
      discretionaryConsumptionCompression = f.discretionaryConsumptionCompression,
    )
