package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.{MonthExecution, MonthRandomness, MonthWorkflow, World}
import com.boombustgroup.amorfati.engine.economics.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

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

/** Household-income stage dependencies pinned to the opening labor/payroll
  * boundary.
  */
private[flows] final case class HouseholdIncomeStageInput(
    world: World,
    firms: Vector[Firm.State],
    households: Vector[Household.State],
    banks: Vector[Banking.BankState],
    ledger: LedgerFinancialState,
    lendingBaseRate: Rate,
    reservationWage: PLN,
    wage: PLN,
    pensionIncome: PLN,
    rng: RandomStream,
)

/** Firm stage dependencies before post-firm labor reconciliation. */
private[flows] final case class FirmStageInput(
    world: World,
    firms: Vector[Firm.State],
    households: Vector[Household.State],
    banks: Vector[Banking.BankState],
    ledger: LedgerFinancialState,
    fiscal: FiscalConstraintEconomics.StepOutput,
    openingLabor: LaborEconomics.StepOutput,
    householdIncome: HouseholdIncomeEconomics.StepOutput,
    demand: DemandEconomics.StepOutput,
    rng: RandomStream,
    traceDecisions: Boolean,
)

/** Social-fund dependencies that remain anchored to opening payroll while firm
  * bankruptcies affect earmarked funds.
  */
private[flows] final case class SocialFundStageInput(
    payroll: SocialSecurity.PayrollBase,
    zus: SocialSecurity.ZusState,
    workingAgePopulation: Int,
    retirees: Int,
    unemploymentBenefits: PLN,
    bankruptFirms: Int,
    averageFirmWorkers: Int,
)

/** Labor reconciliation dependencies after firm processing. */
private[flows] final case class LaborReconciliationStageInput(
    world: World,
    fiscal: FiscalConstraintEconomics.StepOutput,
    openingLabor: LaborEconomics.StepOutput,
    livingFirms: Vector[Firm.State],
    households: Vector[Household.State],
    socialFunds: SocialFundStages,
)

/** Household-financial stage dependencies after labor reconciliation. */
private[flows] final case class HouseholdFinancialStageInput(
    world: World,
    month: ExecutionMonth,
    employed: Int,
    householdAggregates: Household.Aggregates,
    rng: RandomStream,
)

/** Price/equity dependencies for market valuation and macro-price updates. */
private[flows] final case class PriceEquityStageInput(
    world: World,
    month: ExecutionMonth,
    wageGrowth: Coefficient,
    averageDemandMultiplier: Multiplier,
    sectorMultipliers: Vector[Multiplier],
    totalSystemLoans: PLN,
    firm: FirmEconomics.StepOutput,
    ledger: LedgerFinancialState,
)

/** Open-economy dependencies after domestic real/financial stages are known. */
private[flows] final case class OpenEconomyStageInput(
    world: World,
    ledger: LedgerFinancialState,
    fiscal: FiscalConstraintEconomics.StepOutput,
    labor: LaborEconomics.StepOutput,
    householdIncome: HouseholdIncomeEconomics.StepOutput,
    demand: DemandEconomics.StepOutput,
    firm: FirmEconomics.StepOutput,
    householdFinancial: HouseholdFinancialEconomics.StepOutput,
    priceEquity: PriceEquityEconomics.StepOutput,
    banks: Vector[Banking.BankState],
    rng: RandomStream,
)

/** Banking dependencies after fiscal, real-sector, household-financial,
  * price/equity, and open-economy stages have resolved.
  */
private[flows] final case class BankingStageInput(
    world: World,
    ledger: LedgerFinancialState,
    fiscal: FiscalConstraintEconomics.StepOutput,
    labor: LaborEconomics.StepOutput,
    householdIncome: HouseholdIncomeEconomics.StepOutput,
    demand: DemandEconomics.StepOutput,
    firm: FirmEconomics.StepOutput,
    householdFinancial: HouseholdFinancialEconomics.StepOutput,
    priceEquity: PriceEquityEconomics.StepOutput,
    openEconomy: OpenEconEconomics.StepOutput,
    banks: Vector[Banking.BankState],
    depositRng: RandomStream,
)

/** Final same-month transcript assembly dependencies. */
private[flows] final case class MonthExecutionAssemblyInput(
    openingWorld: World,
    fiscal: FiscalConstraintEconomics.StepOutput,
    labor: LaborEconomics.StepOutput,
    householdIncome: HouseholdIncomeEconomics.StepOutput,
    demand: DemandEconomics.StepOutput,
    firm: FirmEconomics.StepOutput,
    householdFinancial: HouseholdFinancialEconomics.StepOutput,
    priceEquity: PriceEquityEconomics.StepOutput,
    openEconomy: OpenEconEconomics.StepOutput,
    banking: BankingEconomics.StepOutput,
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

  def householdIncome(in: HouseholdIncomeStageInput)(using SimParams): Program[HouseholdIncomeEconomics.StepOutput] =
    MonthWorkflow.pure(
      HouseholdIncomeEconomics.compute(
        in.world,
        in.firms,
        in.households,
        in.banks,
        in.ledger,
        in.lendingBaseRate,
        in.reservationWage,
        in.wage,
        in.rng,
        pensionIncome = in.pensionIncome,
      ),
    )

  def demand(
      pre: SameMonthEconomicsPre,
      laborPre: LaborEconomics.StepOutput,
      householdIncome: HouseholdIncomeEconomics.StepOutput,
  )(using SimParams): Program[DemandEconomics.StepOutput] =
    MonthWorkflow.pure(DemandEconomics.compute(pre.world, laborPre.employed, laborPre.living, householdIncome.domesticCons))

  def firm(in: FirmStageInput)(using SimParams): Program[FirmEconomics.StepOutput] =
    MonthWorkflow.pure(
      FirmEconomics.runStep(
        in.world,
        in.firms,
        in.households,
        in.banks,
        in.ledger,
        in.fiscal,
        in.openingLabor,
        in.householdIncome,
        in.demand,
        in.rng,
        traceDecisions = in.traceDecisions,
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

  def socialFunds(in: SocialFundStageInput)(using SimParams): Program[SocialFundStages] =
    MonthWorkflow.pure(
      SocialFundStages(
        zus = in.zus,
        nfz = SocialSecurity.nfzStep(in.payroll, in.workingAgePopulation, in.retirees),
        ppk = SocialSecurity.ppkStep(in.payroll),
        earmarked = EarmarkedFunds.step(in.payroll, in.unemploymentBenefits, in.bankruptFirms, in.averageFirmWorkers),
      ),
    )

  def labor(in: LaborReconciliationStageInput)(using SimParams): Program[LaborEconomics.StepOutput] =
    val reconciled = LaborEconomics.reconcilePostFirmStep(in.world, in.fiscal, in.openingLabor, in.livingFirms, in.households)
    // Labor reconciliation refreshes employment/wage state after the firm step,
    // but social funds are pinned to the opening-boundary payroll for month t.
    MonthWorkflow.pure(
      reconciled.copy(
        newZus = in.socialFunds.zus,
        newNfz = in.socialFunds.nfz,
        newPpk = in.socialFunds.ppk,
        newEarmarked = in.socialFunds.earmarked,
      ),
    )

  def householdFinancial(in: HouseholdFinancialStageInput)(using SimParams): Program[HouseholdFinancialEconomics.StepOutput] =
    MonthWorkflow.pure(
      HouseholdFinancialEconomics.compute(
        in.world,
        in.month,
        in.employed,
        in.householdAggregates,
        in.rng,
      ),
    )

  def priceEquity(in: PriceEquityStageInput)(using SimParams): Program[PriceEquityEconomics.StepOutput] =
    MonthWorkflow.pure(
      PriceEquityEconomics.compute(
        w = in.world,
        month = in.month,
        wageGrowth = in.wageGrowth,
        avgDemandMult = in.averageDemandMultiplier,
        sectorMults = in.sectorMultipliers,
        totalSystemLoans = in.totalSystemLoans,
        firmStep = in.firm,
        ledgerFinancialState = in.ledger,
      ),
    )

  def openEconomy(in: OpenEconomyStageInput)(using SimParams): Program[OpenEconEconomics.StepOutput] =
    MonthWorkflow.pure(
      OpenEconEconomics.runStep(
        OpenEconEconomics.StepInput(
          in.world,
          in.ledger,
          in.fiscal,
          in.labor,
          in.householdIncome,
          in.demand,
          in.firm,
          in.householdFinancial,
          in.priceEquity,
          in.banks,
          in.rng,
        ),
      ),
    )

  def banking(in: BankingStageInput)(using SimParams): Program[BankingEconomics.StepOutput] =
    MonthWorkflow.pure(
      BankingEconomics.runStep(
        BankingEconomics.StepInput(
          world = in.world,
          ledgerFinancialState = in.ledger,
          fiscal = in.fiscal,
          labor = in.labor,
          householdIncome = in.householdIncome,
          demand = in.demand,
          firm = in.firm,
          householdFinancial = in.householdFinancial,
          priceEquity = in.priceEquity,
          openEconomy = in.openEconomy,
          banks = in.banks,
          depositRng = in.depositRng,
        ),
      ),
    )

  def execution(in: MonthExecutionAssemblyInput): Program[MonthExecution] =
    MonthWorkflow.pure(
      MonthExecution(
        openingWorld = in.openingWorld,
        fiscal = in.fiscal,
        labor = in.labor,
        householdIncome = in.householdIncome,
        demand = in.demand,
        firm = in.firm,
        householdFinancial = in.householdFinancial,
        priceEquity = in.priceEquity,
        openEconomy = in.openEconomy,
        banking = in.banking,
      ),
    )
