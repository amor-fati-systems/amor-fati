package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.economics.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.engine.{OperationalSignals, World}
import com.boombustgroup.amorfati.types.*

/** Firm-stage input boundary assembled by the public facade. */
private[firm] final case class StepInput(
    w: World,
    firms: Vector[Firm.State],
    households: Vector[Household.State],
    banks: Vector[Banking.BankState],
    ledgerFinancialState: LedgerFinancialState,
    fiscal: FiscalConstraintEconomics.StepOutput,
    labor: LaborEconomics.StepOutput,
    householdIncome: HouseholdIncomeEconomics.StepOutput,
    demand: DemandEconomics.StepOutput,
    traceDecisions: Boolean,
)

/** Full firm-stage output consumed by downstream monthly calculus stages. */
final case class StepOutput(
    ioFirms: Vector[Firm.State],
    households: Vector[Household.State],
    sumTax: PLN,
    sumCapex: PLN,
    sumTechImp: PLN,
    sumNewLoans: PLN,
    automationTechCapex: PLN,
    automationTechImports: PLN,
    automationTechLoans: PLN,
    automationUpgradeFailures: Int,
    automationAiDebtTrap: Int,
    automationNewFullAi: Int,
    automationNewHybrid: Int,
    sumEquityIssuance: PLN,
    sumGrossInvestment: PLN,
    sumBondIssuance: PLN,
    sumProfitShifting: PLN,
    sumFdiRepatriation: PLN,
    sumInventoryChange: PLN,
    sumCitEvasion: PLN,
    sumEnergyCost: PLN,
    sumGreenInvestment: PLN,
    totalIoPaid: PLN,
    nplNew: PLN,
    nplLoss: PLN,
    totalBondDefault: PLN,
    firmDeaths: Int,
    intIncome: PLN,
    corpBondAbsorption: Share,
    actualBondIssuance: PLN,
    netMigration: Int,
    perBankNewLoans: Vector[PLN],
    sumFirmPrincipal: PLN,
    perBankFirmPrincipal: Vector[PLN],
    perBankNplDebt: Vector[PLN],
    perBankIntIncome: Vector[PLN],
    perBankWorkers: Vector[Int],
    lendingRates: Vector[Rate],
    sumInvestmentCreditDemand: PLN,
    sumInvestmentCreditApproved: PLN,
    sumInvestmentCreditRejected: PLN,
    sumTechCreditDemand: PLN,
    sumTechCreditApproved: PLN,
    sumTechCreditRejected: PLN,
    sumTechSelectedCreditDemand: PLN,
    sumTechSelectedCreditApproved: PLN,
    sumTechSelectedCreditRejected: PLN,
    sumTechCandidateCreditDemand: PLN,
    sumTechCandidateCreditApproved: PLN,
    sumTechCandidateCreditRejected: PLN,
    sumCreditRejectedByReason: Firm.CreditRejectionBreakdown,
    postFirmCrossSectorHires: Int,
    postFirmHires: Int,
    postFirmHireCapacity: Int,
    markupInflation: Rate,
    sumRealizedPostTaxProfit: PLN,
    sumStateOwnedPostTaxProfit: PLN,
    decisionTraces: Vector[Firm.DecisionTrace],
    ledgerFinancialState: LedgerFinancialState,
)

/** Accumulated monetary flows from firm processing. */
private[firm] final case class FirmFlows(
    tax: PLN,
    capex: PLN,
    techImp: PLN,
    newLoans: PLN,
    equityIssuance: PLN,
    grossInvestment: PLN,
    bondIssuance: PLN,
    profitShifting: PLN,
    fdiRepatriation: PLN,
    inventoryChange: PLN,
    citEvasion: PLN,
    energyCost: PLN,
    greenInvestment: PLN,
    principalRepaid: PLN,
    investmentCreditDemand: PLN,
    investmentCreditApproved: PLN,
    investmentCreditRejected: PLN,
    techCreditDemand: PLN,
    techCreditApproved: PLN,
    techCreditRejected: PLN,
    techSelectedCreditDemand: PLN,
    techSelectedCreditApproved: PLN,
    techSelectedCreditRejected: PLN,
    techCandidateCreditDemand: PLN,
    techCandidateCreditApproved: PLN,
    techCandidateCreditRejected: PLN,
    creditRejectedByReason: Firm.CreditRejectionBreakdown,
):
  def +(o: FirmFlows): FirmFlows = FirmFlows(
    tax + o.tax,
    capex + o.capex,
    techImp + o.techImp,
    newLoans + o.newLoans,
    equityIssuance + o.equityIssuance,
    grossInvestment + o.grossInvestment,
    bondIssuance + o.bondIssuance,
    profitShifting + o.profitShifting,
    fdiRepatriation + o.fdiRepatriation,
    inventoryChange + o.inventoryChange,
    citEvasion + o.citEvasion,
    energyCost + o.energyCost,
    greenInvestment + o.greenInvestment,
    principalRepaid + o.principalRepaid,
    investmentCreditDemand + o.investmentCreditDemand,
    investmentCreditApproved + o.investmentCreditApproved,
    investmentCreditRejected + o.investmentCreditRejected,
    techCreditDemand + o.techCreditDemand,
    techCreditApproved + o.techCreditApproved,
    techCreditRejected + o.techCreditRejected,
    techSelectedCreditDemand + o.techSelectedCreditDemand,
    techSelectedCreditApproved + o.techSelectedCreditApproved,
    techSelectedCreditRejected + o.techSelectedCreditRejected,
    techCandidateCreditDemand + o.techCandidateCreditDemand,
    techCandidateCreditApproved + o.techCandidateCreditApproved,
    techCandidateCreditRejected + o.techCandidateCreditRejected,
    creditRejectedByReason + o.creditRejectedByReason,
  )

private[firm] object FirmFlows:
  val zero: FirmFlows = FirmFlows(
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    PLN.Zero,
    Firm.CreditRejectionBreakdown.zero,
  )

/** Per-bank lending rates and credit approval shared across stages. */
private[firm] final case class LendingConditions(
    firmWorld: World,
    executionMonth: ExecutionMonth,
    operationalSignals: OperationalSignals,
    rates: Vector[Rate],
    bankCreditDecision: (Int, PLN) => Firm.CreditDecision,
    nBanks: Int,
)

/** Per-firm processing outcome before aggregate assembly. */
private[firm] final case class FirmOutcome(
    firm: Firm.State,
    financialStocks: Firm.FinancialStocks,
    flows: FirmFlows,
    realizedPostTaxProfit: PLN,
    bankId: BankId,
    finalLoan: PLN,
    bondAmt: PLN,
    techBankLoan: PLN,
    techBondAmt: PLN,
    automationUpgradeFailure: Boolean,
    automationAiDebtTrap: Boolean,
    automationNewFullAi: Boolean,
    automationNewHybrid: Boolean,
    principalRepaid: PLN,
    decisionTrace: Option[Firm.DecisionTrace],
)

private[firm] final case class FirmProcessingResult(
    outcomes: Vector[FirmOutcome],
    flows: FirmFlows,
    firmBondAmounts: Map[FirmId, PLN],
    traceDecisions: Boolean,
)

private[firm] final case class TraceLoanComponents(techCreditAmount: PLN, investmentCreditAmount: Option[PLN])

private[firm] final case class BondAbsorptionResult(
    firms: Vector[Firm.State],
    financialStocks: Vector[Firm.FinancialStocks],
    ledgerFinancialState: LedgerFinancialState,
    sumNewLoans: PLN,
    corpBondAbsorption: Share,
    actualBondIssuance: PLN,
    bondReversionByFirm: Map[FirmId, PLN],
)

private[amorfati] final case class FinancingChannelAmounts(
    bankLoan: PLN,
    equity: PLN,
    bonds: PLN,
    techBankLoan: PLN,
    techBonds: PLN,
)

private[firm] final case class FinancingSplit(
    bankLoan: PLN,
    equity: PLN,
    bonds: PLN,
    techBankLoan: PLN,
    techBonds: PLN,
    firm: Firm.State,
    financialStocks: Firm.FinancialStocks,
)

private[firm] final case class IntermediateResult(
    firms: Vector[Firm.State],
    financialStocks: Vector[Firm.FinancialStocks],
    totalPaid: PLN,
)

private[firm] final case class PricingResult(
    firms: Vector[Firm.State],
    markupInflation: Rate,
)

private[firm] final case class LaborMarketResult(
    households: Vector[Household.State],
    crossSectorHires: Int,
    hires: Int,
    hireCapacity: Int,
    newHouseholdFinancialStocksById: Map[HhId, Household.FinancialStocks],
)

private[firm] final case class NplResult(
    nplNew: PLN,
    nplLoss: PLN,
    totalBondDefault: PLN,
    firmDeaths: Int,
    intIncome: PLN,
    perBankNplDebt: Vector[PLN],
    perBankIntIncome: Vector[PLN],
    perBankWorkers: Vector[Int],
    defaultedBondFirmIds: Set[FirmId],
)
