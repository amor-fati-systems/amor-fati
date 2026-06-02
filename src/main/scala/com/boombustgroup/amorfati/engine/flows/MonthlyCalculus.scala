package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.agents.{EarmarkedFunds, SocialSecurity}
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.economics.BankingEconomics
import com.boombustgroup.amorfati.types.*

/** Same-month flow plan consumed by [[MonthFlowEmitter]].
  *
  * Economics stages produce rich domain outputs. `MonthlyCalculus` is the
  * narrower month-`t` translation contract: quantities here are exactly the
  * values needed to emit runtime ledger batches and SFC evidence.
  */
case class MonthlyCalculus(
    // Stage 1: Fiscal constraint
    month: ExecutionMonth,
    resWage: PLN,
    lendingBaseRate: Rate,
    baseMinWage: PLN,
    minWagePriceLevel: PriceIndex,
    // Stage 2: Labor market
    wage: PLN,
    employed: Int,
    payroll: SocialSecurity.PayrollBase,
    zus: SocialSecurity.ZusState,
    ppk: SocialSecurity.PpkState,
    earmarked: EarmarkedFunds.State,
    unemploymentRate: Share,
    laborDemand: Int,
    livingFirms: Int,
    retirees: Int,
    workingAgePop: Int,
    nfz: SocialSecurity.NfzState,
    nBankruptFirms: Int,
    avgFirmWorkers: Int,
    // Stage 3: HH income (aggregates)
    totalIncome: PLN,
    consumption: PLN,
    domesticConsumption: PLN,
    importConsumption: PLN,
    totalRent: PLN,
    pitRevenue: PLN,
    totalDepositInterest: PLN,
    totalRemittances: PLN,
    totalUnempBenefits: PLN,
    totalSocialTransfers: PLN,
    totalCcOrigination: PLN,
    approvedCcOrigination: PLN,
    liquidityShortfallFinancing: PLN,
    totalCcDebtService: PLN,
    totalCcPrincipal: PLN,
    totalCcDefault: PLN,
    // Stage 9: Gov budget
    govCurrentSpend: PLN,
    // Stage 5: Firm
    firmTax: PLN,
    firmNewLoans: PLN,
    firmPrincipal: PLN,
    firmInterestIncome: PLN,
    firmCapex: PLN,
    firmEquityIssuance: PLN,
    firmIoPayments: PLN,
    firmNplLoss: PLN,
    firmProfitShifting: PLN,
    firmFdiRepatriation: PLN,
    firmGrossInvestment: PLN,
    investNetDepositFlow: PLN,
    // Stage 7: Price / Equity
    gdp: PLN,
    inflation: Rate,
    equityDomDividends: PLN,
    equityForDividends: PLN,
    equityDivTax: PLN,
    equityGovDividends: PLN,
    equityReturn: Rate,
    // Stage 8: Open economy
    exports: PLN,
    totalImports: PLN,
    tourismExport: PLN,
    tourismImport: PLN,
    fdi: PLN,
    portfolioFlows: PLN,
    carryTradeFlow: PLN,
    capitalFlightOutflow: PLN,
    primaryIncome: PLN,
    euFunds: PLN,
    diasporaInflow: PLN,
    // Stage 8: Corp bonds
    corpBondCoupon: PLN,
    corpBondDefaultAmount: PLN,
    corpBondIssuance: PLN,
    corpBondAmortization: PLN,
    corpBondCouponRecipients: CorpBondFlows.HolderBreakdown,
    corpBondDefaultRecipients: CorpBondFlows.HolderBreakdown,
    corpBondIssuanceRecipients: CorpBondFlows.HolderBreakdown,
    corpBondAmortizationRecipients: CorpBondFlows.HolderBreakdown,
    // Stage 8: Mortgage
    mortgageOrigination: PLN,
    mortgageRepayment: PLN,
    mortgageInterest: PLN,
    mortgageDefault: PLN,
    // Stage 9: Banking
    bankGovBondIncome: PLN,
    bankReserveInterest: PLN,
    bankStandingFacility: PLN,
    bankStandingFacilityBackstop: PLN,
    bankInterbankInterest: PLN,
    bankCorpBondCoupon: PLN,
    bankCorpBondLoss: PLN,
    bankFxReserveSettlement: PLN,
    bankBfgLevy: PLN,
    bankUnrealizedLoss: PLN,
    bankBailIn: PLN,
    bankNbpRemittance: PLN,
    // Stage 9: holder stock deltas after BankingEconomics.runStep
    equityRevaluation: EquityFlows.RevaluationInput,
    // Stage 8/9: NBFI / TFI monetary channels
    nbfiDepositDrain: PLN,
    nbfiOrigination: PLN,
    nbfiRepayment: PLN,
    nbfiDefaultAmount: PLN,
    // Stage 9: quasi-fiscal monetary channels
    qfBankBondIssuance: PLN,
    qfNbpBondAbsorption: PLN,
    qfBankBondAmortization: PLN,
    qfNbpBondAmortization: PLN,
    qfLending: PLN,
    qfRepayment: PLN,
    // Stage 8: Gov budget
    govVatRevenue: PLN,
    govExciseRevenue: PLN,
    govCustomsDutyRevenue: PLN,
    govDebtService: PLN,
    govDebtServiceRecipients: GovBudgetFlows.DebtServiceRecipients,
    govBondRuntimeMovements: BankingEconomics.GovBondRuntimeMovements,
    govEuCofinancing: PLN,
    govCapitalSpend: PLN,
    // Insurance
    insuranceCurrentLifeReserves: PLN,
    insuranceCurrentNonLifeReserves: PLN,
    insurancePrevGovBonds: PLN,
    insurancePrevCorpBonds: PLN,
    insuranceCorpBondDefaultLoss: PLN,
    insurancePrevEquity: PLN,
    govBondYield: Rate,
    corpBondYield: Rate,
)
