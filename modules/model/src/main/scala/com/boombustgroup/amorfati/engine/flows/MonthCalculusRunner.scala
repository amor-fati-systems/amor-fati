package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.{MonthClosingStateInput, MonthExecution, MonthWorkflow}
import com.boombustgroup.amorfati.engine.closedmonth.MonthClosing
import com.boombustgroup.amorfati.types.*

/** Runs the deterministic same-month economics boundary.
  *
  * This is the one insertion point for the ordered same-month economics
  * boundary. It returns only month-`t` views: flow plan, operational signals,
  * closing input, SFC semantic projection, and step evidence. Closed-month
  * state and next-pre seed extraction remain outside this runner.
  */
private[flows] object MonthCalculusRunner:
  import FlowSimulation.{SameMonthBoundaryViews, SemanticFlowInputs, SignalBoundaryInputs, StepEvidenceInputs, StepInput}

  def run(input: StepInput)(using p: SimParams): SameMonthBoundaryViews =
    val flowPlanSource = runEconomicsStages(input)
    val flowPlan       = MonthFlowPlanBuilder.build(flowPlanSource)
    buildBoundaryViews(flowPlanSource.execution, flowPlan)

  private def runEconomicsStages(input: StepInput)(using p: SimParams): SameMonthFlowPlanSource =
    MonthWorkflow.run:
      for
        pre                 <- SameMonthEconomicsDsl.pre(input)
        fiscal              <- SameMonthEconomicsDsl.fiscal(pre)
        laborPre            <- SameMonthEconomicsDsl.laborPre(pre, fiscal)
        payroll             <- SameMonthEconomicsDsl.openingPayroll(pre, laborPre)
        householdIncomeIn    = HouseholdIncomeStageInput(
          world = pre.world,
          firms = pre.firms,
          households = pre.households,
          banks = pre.banks,
          ledger = pre.ledger,
          lendingBaseRate = fiscal.lendingBaseRate,
          reservationWage = fiscal.resWage,
          wage = laborPre.newWage,
          pensionIncome = payroll.zus.pensionPayments,
          rng = pre.randomness.householdIncomeEconomics.newStream(),
        )
        householdIncome     <- SameMonthEconomicsDsl.householdIncome(householdIncomeIn)
        demand              <- SameMonthEconomicsDsl.demand(pre, laborPre, householdIncome)
        firmIn               = FirmStageInput(
          world = pre.world,
          firms = pre.firms,
          households = pre.households,
          banks = pre.banks,
          ledger = pre.ledger,
          fiscal = fiscal,
          openingLabor = laborPre,
          householdIncome = householdIncome,
          demand = demand,
          rng = pre.randomness.firmEconomics.newStream(),
          traceDecisions = pre.input.traceFirmDecisions,
        )
        firm                <- SameMonthEconomicsDsl.firm(firmIn)
        postFirm            <- SameMonthEconomicsDsl.postFirm(laborPre, firm)
        socialFundsIn        = SocialFundStageInput(
          payroll = payroll.payroll,
          zus = payroll.zus,
          workingAgePopulation = laborPre.newDemographics.workingAgePop,
          retirees = laborPre.newDemographics.retirees,
          unemploymentBenefits = householdIncome.hhAgg.totalUnempBenefits,
          bankruptFirms = postFirm.nBankruptFirms,
          averageFirmWorkers = postFirm.avgFirmWorkers,
        )
        socialFunds         <- SameMonthEconomicsDsl.socialFunds(socialFundsIn)
        laborIn              = LaborReconciliationStageInput(
          world = pre.world,
          fiscal = fiscal,
          openingLabor = laborPre,
          livingFirms = postFirm.livingFirms,
          households = postFirm.firm.households,
          socialFunds = socialFunds,
        )
        labor               <- SameMonthEconomicsDsl.labor(laborIn)
        householdFinancialIn = HouseholdFinancialStageInput(
          world = pre.world,
          month = fiscal.m,
          employed = labor.employed,
          householdAggregates = householdIncome.hhAgg,
          rng = pre.randomness.householdFinancialEconomics.newStream(),
        )
        householdFinancial  <- SameMonthEconomicsDsl.householdFinancial(householdFinancialIn)
        priceEquityIn        = PriceEquityStageInput(
          world = pre.world,
          month = fiscal.m,
          wageGrowth = labor.wageGrowth,
          averageDemandMultiplier = demand.avgDemandMult,
          sectorMultipliers = demand.sectorMults,
          totalSystemLoans = pre.ledger.banks.map(_.firmLoan).sumPln,
          firm = firm,
          ledger = pre.ledger,
        )
        priceEquity         <- SameMonthEconomicsDsl.priceEquity(priceEquityIn)
        openEconomyIn        = OpenEconomyStageInput(
          world = pre.world,
          ledger = pre.ledger,
          fiscal = fiscal,
          labor = labor,
          householdIncome = householdIncome,
          demand = demand,
          firm = firm,
          householdFinancial = householdFinancial,
          priceEquity = priceEquity,
          banks = pre.banks,
          rng = pre.randomness.openEconEconomics.newStream(),
        )
        openEconomy         <- SameMonthEconomicsDsl.openEconomy(openEconomyIn)
        bankingIn            = BankingStageInput(
          world = pre.world,
          ledger = pre.ledger,
          fiscal = fiscal,
          labor = labor,
          householdIncome = householdIncome,
          demand = demand,
          firm = firm,
          householdFinancial = householdFinancial,
          priceEquity = priceEquity,
          openEconomy = openEconomy,
          banks = pre.banks,
          depositRng = pre.randomness.bankingEconomics.newStream(),
        )
        banking             <- SameMonthEconomicsDsl.banking(bankingIn)
        executionIn          = MonthExecutionAssemblyInput(
          openingWorld = pre.world,
          fiscal = fiscal,
          labor = labor,
          householdIncome = householdIncome,
          demand = demand,
          firm = firm,
          householdFinancial = householdFinancial,
          priceEquity = priceEquity,
          openEconomy = openEconomy,
          banking = banking,
        )
        execution           <- SameMonthEconomicsDsl.execution(executionIn)
      yield SameMonthFlowPlanSource(
        openingFinancial = SameMonthFlowPlanSource.OpeningFinancialBoundary(
          ledger = pre.ledger,
          banks = pre.banks,
        ),
        execution = execution,
        laborOpening = SameMonthFlowPlanSource.LaborOpeningBoundary(
          payroll = payroll.payroll,
          retirees = laborPre.newDemographics.retirees,
          workingAgePopulation = laborPre.newDemographics.workingAgePop,
        ),
        firmDemography = SameMonthFlowPlanSource.FirmDemographyBoundary(
          bankruptFirms = postFirm.nBankruptFirms,
          averageFirmWorkers = postFirm.avgFirmWorkers,
        ),
      )

  private def buildBoundaryViews(execution: MonthExecution, flowPlan: MonthlyCalculus)(using p: SimParams): SameMonthBoundaryViews =
    val closingState = MonthClosingStateInput.fromExecution(execution)
    SameMonthBoundaryViews(
      flowPlan = flowPlan,
      signals = SignalBoundaryInputs(
        labor = execution.labor,
        demand = execution.demand,
      ),
      closing = MonthClosing.prepareInput(closingState),
      semanticProjection = SemanticFlowInputs.fromExecution(execution),
      stepEvidence = StepEvidenceInputs.fromExecution(execution),
    )
