package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.{MonthExecution, MonthRandomness, MonthWorkflow, World}
import com.boombustgroup.amorfati.engine.economics.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.types.*

private[flows] final case class SameMonthStageRun(
    openingLedger: LedgerFinancialState,
    openingBanks: Vector[Banking.BankState],
    execution: MonthExecution,
    laborPre: LaborEconomics.StepOutput,
    payroll: SocialSecurity.PayrollBase,
    nBankruptFirms: Int,
    avgFirmWorkers: Int,
)

private[flows] final case class SameMonthEconomicsPre(
    input: FlowSimulation.StepInput,
    randomness: MonthRandomness.StageSeeds,
    ledger: LedgerFinancialState,
    world: World,
    firms: Vector[Firm.State],
    households: Vector[Household.State],
    banks: Vector[Banking.BankState],
)

private[flows] final case class OpeningPayroll(
    payroll: SocialSecurity.PayrollBase,
    zus: SocialSecurity.ZusState,
)

private[flows] final case class PostFirmBoundary(
    firm: FirmEconomics.StepOutput,
    livingFirms: Vector[Firm.State],
    nBankruptFirms: Int,
    avgFirmWorkers: Int,
)

private[flows] final case class SocialFundStages(
    zus: SocialSecurity.ZusState,
    nfz: SocialSecurity.NfzState,
    ppk: SocialSecurity.PpkState,
    earmarked: EarmarkedFunds.State,
)

/** Identity-monad DSL for ordered same-month economics.
  *
  * Each method advances one named economics boundary. The `for`-comprehension
  * in [[MonthCalculusRunner]] is therefore the authoritative execution order,
  * while this module owns the stage wiring details.
  */
private[flows] object SameMonthEconomicsDsl:
  import MonthWorkflow.Program

  def pre(input: FlowSimulation.StepInput): Program[SameMonthEconomicsPre] =
    val stateIn = input.stateIn
    MonthWorkflow.pure(
      SameMonthEconomicsPre(
        input = input,
        randomness = input.randomness.stages,
        ledger = stateIn.ledgerFinancialState,
        world = stateIn.world,
        firms = stateIn.firms,
        households = stateIn.households,
        banks = stateIn.banks,
      ),
    )

  def fiscal(pre: SameMonthEconomicsPre)(using SimParams): Program[FiscalConstraintEconomics.StepOutput] =
    MonthWorkflow.pure(FiscalConstraintEconomics.compute(pre.world, pre.banks, pre.ledger, pre.input.executionMonth))

  def laborPre(pre: SameMonthEconomicsPre, fiscal: FiscalConstraintEconomics.StepOutput)(using SimParams): Program[LaborEconomics.StepOutput] =
    MonthWorkflow.pure(LaborEconomics.compute(pre.world, pre.firms, pre.households, fiscal))

  def openingPayroll(pre: SameMonthEconomicsPre, laborPre: LaborEconomics.StepOutput)(using SimParams): Program[OpeningPayroll] =
    val payroll = SocialSecurity.payrollBase(pre.households)
    MonthWorkflow.pure(
      OpeningPayroll(
        payroll = payroll,
        zus = SocialSecurity.zusStep(payroll, laborPre.newDemographics.retirees),
      ),
    )

  def householdIncome(
      pre: SameMonthEconomicsPre,
      fiscal: FiscalConstraintEconomics.StepOutput,
      laborPre: LaborEconomics.StepOutput,
      payroll: OpeningPayroll,
  )(using SimParams): Program[HouseholdIncomeEconomics.StepOutput] =
    MonthWorkflow.pure(
      HouseholdIncomeEconomics.compute(
        pre.world,
        pre.firms,
        pre.households,
        pre.banks,
        pre.ledger,
        fiscal.lendingBaseRate,
        fiscal.resWage,
        laborPre.newWage,
        pre.randomness.householdIncomeEconomics.newStream(),
        pensionIncome = payroll.zus.pensionPayments,
      ),
    )

  def demand(
      pre: SameMonthEconomicsPre,
      laborPre: LaborEconomics.StepOutput,
      householdIncome: HouseholdIncomeEconomics.StepOutput,
  )(using SimParams): Program[DemandEconomics.StepOutput] =
    MonthWorkflow.pure(DemandEconomics.compute(pre.world, laborPre.employed, laborPre.living, householdIncome.domesticCons))

  def firm(
      pre: SameMonthEconomicsPre,
      fiscal: FiscalConstraintEconomics.StepOutput,
      laborPre: LaborEconomics.StepOutput,
      householdIncome: HouseholdIncomeEconomics.StepOutput,
      demand: DemandEconomics.StepOutput,
  )(using SimParams): Program[FirmEconomics.StepOutput] =
    MonthWorkflow.pure(
      FirmEconomics.runStep(
        pre.world,
        pre.firms,
        pre.households,
        pre.banks,
        pre.ledger,
        fiscal,
        laborPre,
        householdIncome,
        demand,
        pre.randomness.firmEconomics.newStream(),
        traceDecisions = pre.input.traceFirmDecisions,
      ),
    )

  def postFirm(laborPre: LaborEconomics.StepOutput, firm: FirmEconomics.StepOutput): Program[PostFirmBoundary] =
    MonthWorkflow.pure(
      PostFirmBoundary(
        firm = firm,
        livingFirms = firm.ioFirms.filter(Firm.isAlive),
        nBankruptFirms = firm.firmDeaths,
        avgFirmWorkers = if laborPre.living.nonEmpty then laborPre.employed / laborPre.living.length else 0,
      ),
    )

  def socialFunds(
      payroll: OpeningPayroll,
      laborPre: LaborEconomics.StepOutput,
      householdIncome: HouseholdIncomeEconomics.StepOutput,
      postFirm: PostFirmBoundary,
  )(using SimParams): Program[SocialFundStages] =
    MonthWorkflow.pure(
      SocialFundStages(
        zus = payroll.zus,
        nfz = SocialSecurity.nfzStep(payroll.payroll, laborPre.newDemographics.workingAgePop, laborPre.newDemographics.retirees),
        ppk = SocialSecurity.ppkStep(payroll.payroll),
        earmarked = EarmarkedFunds.step(payroll.payroll, householdIncome.hhAgg.totalUnempBenefits, postFirm.nBankruptFirms, postFirm.avgFirmWorkers),
      ),
    )

  def labor(
      pre: SameMonthEconomicsPre,
      fiscal: FiscalConstraintEconomics.StepOutput,
      laborPre: LaborEconomics.StepOutput,
      postFirm: PostFirmBoundary,
      socialFunds: SocialFundStages,
  )(using SimParams): Program[LaborEconomics.StepOutput] =
    val reconciled = LaborEconomics.reconcilePostFirmStep(pre.world, fiscal, laborPre, postFirm.livingFirms, postFirm.firm.households)
    // Labor reconciliation refreshes employment/wage state after the firm step,
    // but social funds are pinned to the opening-boundary payroll for month t.
    MonthWorkflow.pure(
      reconciled.copy(
        newZus = socialFunds.zus,
        newNfz = socialFunds.nfz,
        newPpk = socialFunds.ppk,
        newEarmarked = socialFunds.earmarked,
      ),
    )

  def householdFinancial(
      pre: SameMonthEconomicsPre,
      fiscal: FiscalConstraintEconomics.StepOutput,
      labor: LaborEconomics.StepOutput,
      householdIncome: HouseholdIncomeEconomics.StepOutput,
  )(using SimParams): Program[HouseholdFinancialEconomics.StepOutput] =
    MonthWorkflow.pure(
      HouseholdFinancialEconomics.compute(
        pre.world,
        fiscal.m,
        labor.employed,
        householdIncome.hhAgg,
        pre.randomness.householdFinancialEconomics.newStream(),
      ),
    )

  def priceEquity(
      pre: SameMonthEconomicsPre,
      fiscal: FiscalConstraintEconomics.StepOutput,
      labor: LaborEconomics.StepOutput,
      demand: DemandEconomics.StepOutput,
      firm: FirmEconomics.StepOutput,
  )(using SimParams): Program[PriceEquityEconomics.StepOutput] =
    MonthWorkflow.pure(
      PriceEquityEconomics.compute(
        w = pre.world,
        month = fiscal.m,
        wageGrowth = labor.wageGrowth,
        avgDemandMult = demand.avgDemandMult,
        sectorMults = demand.sectorMults,
        totalSystemLoans = pre.ledger.banks.map(_.firmLoan).sumPln,
        firmStep = firm,
        ledgerFinancialState = pre.ledger,
      ),
    )

  def openEconomy(
      pre: SameMonthEconomicsPre,
      fiscal: FiscalConstraintEconomics.StepOutput,
      labor: LaborEconomics.StepOutput,
      householdIncome: HouseholdIncomeEconomics.StepOutput,
      demand: DemandEconomics.StepOutput,
      firm: FirmEconomics.StepOutput,
      householdFinancial: HouseholdFinancialEconomics.StepOutput,
      priceEquity: PriceEquityEconomics.StepOutput,
  )(using SimParams): Program[OpenEconEconomics.StepOutput] =
    MonthWorkflow.pure(
      OpenEconEconomics.runStep(
        OpenEconEconomics.StepInput(
          pre.world,
          pre.ledger,
          fiscal,
          labor,
          householdIncome,
          demand,
          firm,
          householdFinancial,
          priceEquity,
          pre.banks,
          pre.randomness.openEconEconomics.newStream(),
        ),
      ),
    )

  def banking(
      pre: SameMonthEconomicsPre,
      fiscal: FiscalConstraintEconomics.StepOutput,
      labor: LaborEconomics.StepOutput,
      householdIncome: HouseholdIncomeEconomics.StepOutput,
      demand: DemandEconomics.StepOutput,
      firm: FirmEconomics.StepOutput,
      householdFinancial: HouseholdFinancialEconomics.StepOutput,
      priceEquity: PriceEquityEconomics.StepOutput,
      openEconomy: OpenEconEconomics.StepOutput,
  )(using SimParams): Program[BankingEconomics.StepOutput] =
    MonthWorkflow.pure(
      BankingEconomics.runStep(
        BankingEconomics.StepInput(
          world = pre.world,
          ledgerFinancialState = pre.ledger,
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
        ),
      ),
    )

  def execution(
      pre: SameMonthEconomicsPre,
      fiscal: FiscalConstraintEconomics.StepOutput,
      labor: LaborEconomics.StepOutput,
      householdIncome: HouseholdIncomeEconomics.StepOutput,
      demand: DemandEconomics.StepOutput,
      firm: FirmEconomics.StepOutput,
      householdFinancial: HouseholdFinancialEconomics.StepOutput,
      priceEquity: PriceEquityEconomics.StepOutput,
      openEconomy: OpenEconEconomics.StepOutput,
      banking: BankingEconomics.StepOutput,
  ): Program[MonthExecution] =
    MonthWorkflow.pure(
      MonthExecution(
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
      ),
    )
