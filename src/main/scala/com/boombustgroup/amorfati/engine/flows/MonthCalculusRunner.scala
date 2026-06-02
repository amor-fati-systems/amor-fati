package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.{MonthExecution, MonthWorkflow}
import com.boombustgroup.amorfati.engine.closedmonth.MonthClosing

/** Runs the deterministic same-month economics boundary.
  *
  * This is the one insertion point for the ordered same-month economics
  * boundary. It returns only month-`t` views: flow plan, operational signals,
  * closing input, and SFC semantic projection. Closed-month state and next-pre
  * seed extraction remain outside this runner.
  */
private[flows] object MonthCalculusRunner:
  import FlowSimulation.{SameMonthBoundaryViews, SemanticFlowInputs, SignalBoundaryInputs, StepInput}

  def run(input: StepInput)(using p: SimParams): SameMonthBoundaryViews =
    val stages   = runEconomicsStages(input)
    val flowPlan = MonthFlowPlanBuilder.build(stages)
    buildBoundaryViews(stages.execution, flowPlan)

  private def runEconomicsStages(input: StepInput)(using p: SimParams): SameMonthStageRun =
    MonthWorkflow.run:
      for
        pre                <- SameMonthEconomicsDsl.pre(input)
        fiscal             <- SameMonthEconomicsDsl.fiscal(pre)
        laborPre           <- SameMonthEconomicsDsl.laborPre(pre, fiscal)
        payroll            <- SameMonthEconomicsDsl.openingPayroll(pre, laborPre)
        householdIncome    <- SameMonthEconomicsDsl.householdIncome(pre, fiscal, laborPre, payroll)
        demand             <- SameMonthEconomicsDsl.demand(pre, laborPre, householdIncome)
        firm               <- SameMonthEconomicsDsl.firm(pre, fiscal, laborPre, householdIncome, demand)
        postFirm           <- SameMonthEconomicsDsl.postFirm(laborPre, firm)
        socialFunds        <- SameMonthEconomicsDsl.socialFunds(payroll, laborPre, householdIncome, postFirm)
        labor              <- SameMonthEconomicsDsl.labor(pre, fiscal, laborPre, postFirm, socialFunds)
        householdFinancial <- SameMonthEconomicsDsl.householdFinancial(pre, fiscal, labor, householdIncome)
        priceEquity        <- SameMonthEconomicsDsl.priceEquity(pre, fiscal, labor, demand, firm)
        openEconomy        <- SameMonthEconomicsDsl.openEconomy(pre, fiscal, labor, householdIncome, demand, firm, householdFinancial, priceEquity)
        banking            <- SameMonthEconomicsDsl.banking(pre, fiscal, labor, householdIncome, demand, firm, householdFinancial, priceEquity, openEconomy)
        execution          <- SameMonthEconomicsDsl.execution(pre, fiscal, labor, householdIncome, demand, firm, householdFinancial, priceEquity, openEconomy, banking)
      yield SameMonthStageRun(
        openingLedger = pre.ledger,
        openingBanks = pre.banks,
        execution = execution,
        laborPre = laborPre,
        payroll = payroll.payroll,
        nBankruptFirms = postFirm.nBankruptFirms,
        avgFirmWorkers = postFirm.avgFirmWorkers,
      )

  private def buildBoundaryViews(execution: MonthExecution, flowPlan: MonthlyCalculus)(using p: SimParams): SameMonthBoundaryViews =
    SameMonthBoundaryViews(
      flowPlan = flowPlan,
      signals = SignalBoundaryInputs(
        labor = execution.labor,
        demand = execution.demand,
      ),
      closing = MonthClosing.prepareInput(execution),
      semanticProjection = SemanticFlowInputs(
        labor = execution.labor,
        hhIncome = execution.householdIncome,
        firms = execution.firm,
        hhFinancial = execution.householdFinancial,
        prices = execution.priceEquity,
        openEcon = execution.openEconomy,
        banking = execution.banking,
      ),
    )
