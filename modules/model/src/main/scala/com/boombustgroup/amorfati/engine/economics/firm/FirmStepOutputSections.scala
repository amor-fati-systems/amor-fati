package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.{Firm, Household}
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.types.*

/** State-bearing portion of the public firm-stage output. */
private[firm] final case class FirmOutputState(
    ioFirms: Vector[Firm.State],
    households: Vector[Household.State],
    decisionTraces: Vector[Firm.DecisionTrace],
    ledgerFinancialState: LedgerFinancialState,
)

/** Firm monetary-flow totals before they are flattened into StepOutput. */
private[firm] final case class FirmOutputFlowTotals(
    sumTax: PLN,
    sumCapex: PLN,
    sumTechImp: PLN,
    sumNewLoans: PLN,
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
    sumFirmPrincipal: PLN,
)

/** Automation adoption and technology-credit diagnostics. */
private[firm] final case class FirmOutputAutomationTotals(
    techCapex: PLN,
    techImports: PLN,
    techLoans: PLN,
    upgradeFailures: Int,
    aiDebtTrap: Int,
    newFullAi: Int,
    newHybrid: Int,
)

/** Bank-facing firm-sector balances and loss diagnostics. */
private[firm] final case class FirmOutputBankingTotals(
    nplNew: PLN,
    nplLoss: PLN,
    totalBondDefault: PLN,
    firmDeaths: Int,
    intIncome: PLN,
    corpBondAbsorption: Share,
    actualBondIssuance: PLN,
    perBankNewLoans: Vector[PLN],
    perBankFirmPrincipal: Vector[PLN],
    perBankNplDebt: Vector[PLN],
    perBankIntIncome: Vector[PLN],
    perBankWorkers: Vector[Int],
    lendingRates: Vector[Rate],
)

/** Credit demand/approval/rejection diagnostics for investment and technology.
  */
private[firm] final case class FirmOutputCreditTotals(
    investmentDemand: PLN,
    investmentApproved: PLN,
    investmentRejected: PLN,
    techDemand: PLN,
    techApproved: PLN,
    techRejected: PLN,
    techSelectedDemand: PLN,
    techSelectedApproved: PLN,
    techSelectedRejected: PLN,
    techCandidateDemand: PLN,
    techCandidateApproved: PLN,
    techCandidateRejected: PLN,
    rejectedByReason: Firm.CreditRejectionBreakdown,
)

/** Labor-market quantities emitted by the firm stage. */
private[firm] final case class FirmOutputLaborTotals(
    netMigration: Int,
    crossSectorHires: Int,
    hires: Int,
    hireCapacity: Int,
)

/** Profitability and markup aggregates exposed to later month stages. */
private[firm] final case class FirmOutputProfitability(
    markupInflation: Rate,
    realizedPostTaxProfit: PLN,
    stateOwnedPostTaxProfit: PLN,
)
