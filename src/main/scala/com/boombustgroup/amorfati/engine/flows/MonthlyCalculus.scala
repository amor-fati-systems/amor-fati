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
    fiscal: MonthlyCalculus.FiscalBoundary,
    labor: MonthlyCalculus.LaborAndSocial,
    household: MonthlyCalculus.HouseholdFlows,
    government: MonthlyCalculus.GovernmentBudgetFlows,
    firm: MonthlyCalculus.FirmFlows,
    priceEquity: MonthlyCalculus.PriceEquityFlows,
    external: MonthlyCalculus.ExternalFlows,
    corpBonds: MonthlyCalculus.CorpBondFlowsPlan,
    housing: MonthlyCalculus.HousingFlows,
    banking: MonthlyCalculus.BankingFlowsPlan,
    nonBank: MonthlyCalculus.NonBankFlows,
    quasiFiscal: MonthlyCalculus.QuasiFiscalFlowsPlan,
    insurance: MonthlyCalculus.InsuranceOpeningStocks,
):
  export fiscal.{baseMinWage, lendingBaseRate, minWagePriceLevel, month, resWage}
  export labor.{
    avgFirmWorkers,
    earmarked,
    employed,
    laborDemand,
    livingFirms,
    nBankruptFirms,
    nfz,
    payroll,
    ppk,
    retirees,
    unemploymentRate,
    wage,
    workingAgePop,
    zus,
  }
  export household.{
    approvedCcOrigination,
    consumption,
    domesticConsumption,
    importConsumption,
    liquidityShortfallFinancing,
    pitRevenue,
    totalCcDebtService,
    totalCcDefault,
    totalCcOrigination,
    totalCcPrincipal,
    totalDepositInterest,
    totalIncome,
    totalRemittances,
    totalRent,
    totalSocialTransfers,
    totalUnempBenefits,
  }
  export government.{
    govBondRuntimeMovements,
    govCapitalSpend,
    govCurrentSpend,
    govCustomsDutyRevenue,
    govDebtService,
    govDebtServiceRecipients,
    govEuCofinancing,
    govExciseRevenue,
    govVatRevenue,
  }
  export firm.{
    firmCapex,
    firmEquityIssuance,
    firmFdiRepatriation,
    firmGrossInvestment,
    firmInterestIncome,
    firmIoPayments,
    firmNewLoans,
    firmNplLoss,
    firmPrincipal,
    firmProfitShifting,
    firmTax,
    investNetDepositFlow,
  }
  export priceEquity.{equityDivTax, equityDomDividends, equityForDividends, equityGovDividends, equityReturn, equityRevaluation, gdp, inflation}
  export external.{
    capitalFlightOutflow,
    carryTradeFlow,
    diasporaInflow,
    euFunds,
    exports,
    fdi,
    portfolioFlows,
    primaryIncome,
    totalImports,
    tourismExport,
    tourismImport,
  }
  export corpBonds.{
    corpBondAmortization,
    corpBondAmortizationRecipients,
    corpBondCoupon,
    corpBondCouponRecipients,
    corpBondDefaultAmount,
    corpBondDefaultRecipients,
    corpBondIssuance,
    corpBondIssuanceRecipients,
  }
  export housing.{mortgageDefault, mortgageInterest, mortgageOrigination, mortgageRepayment}
  export banking.{
    bankBailIn,
    bankBfgLevy,
    bankCorpBondCoupon,
    bankCorpBondLoss,
    bankFxReserveSettlement,
    bankGovBondIncome,
    bankInterbankInterest,
    bankNbpRemittance,
    bankReserveInterest,
    bankStandingFacility,
    bankStandingFacilityBackstop,
    bankUnrealizedLoss,
  }
  export nonBank.{nbfiDefaultAmount, nbfiDepositDrain, nbfiOrigination, nbfiRepayment}
  export quasiFiscal.{qfBankBondAmortization, qfBankBondIssuance, qfLending, qfNbpBondAbsorption, qfNbpBondAmortization, qfRepayment}
  export insurance.{
    corpBondYield,
    govBondYield,
    insuranceCorpBondDefaultLoss,
    insuranceCurrentLifeReserves,
    insuranceCurrentNonLifeReserves,
    insurancePrevCorpBonds,
    insurancePrevEquity,
    insurancePrevGovBonds,
  }

object MonthlyCalculus:

  /** Fiscal-policy boundary values that anchor this execution month. */
  case class FiscalBoundary(
      month: ExecutionMonth,
      resWage: PLN,
      lendingBaseRate: Rate,
      baseMinWage: PLN,
      minWagePriceLevel: PriceIndex,
  )

  /** Labor-market, social-security, and population aggregates used by flow
    * emission.
    */
  case class LaborAndSocial(
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
  )

  /** Household income, spending, credit, and tax aggregates for the month. */
  case class HouseholdFlows(
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
  )

  /** Government budget flows and bond-market movements emitted for the current
    * month.
    */
  case class GovernmentBudgetFlows(
      govCurrentSpend: PLN,
      govVatRevenue: PLN,
      govExciseRevenue: PLN,
      govCustomsDutyRevenue: PLN,
      govDebtService: PLN,
      govDebtServiceRecipients: GovBudgetFlows.DebtServiceRecipients,
      govBondRuntimeMovements: BankingEconomics.GovBondRuntimeMovements,
      govEuCofinancing: PLN,
      govCapitalSpend: PLN,
  )

  /** Firm-sector taxes, financing, investment, and cross-firm payment flows. */
  case class FirmFlows(
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
  )

  /** Price, GDP, equity-income, and holder revaluation signals. */
  case class PriceEquityFlows(
      gdp: PLN,
      inflation: Rate,
      equityDomDividends: PLN,
      equityForDividends: PLN,
      equityDivTax: PLN,
      equityGovDividends: PLN,
      equityReturn: Rate,
      equityRevaluation: EquityFlows.RevaluationInput,
  )

  /** External-sector real, financial, and household cross-border flows. */
  case class ExternalFlows(
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
  )

  /** Corporate-bond cash flows plus their holder-side allocation plans. */
  case class CorpBondFlowsPlan(
      corpBondCoupon: PLN,
      corpBondDefaultAmount: PLN,
      corpBondIssuance: PLN,
      corpBondAmortization: PLN,
      corpBondCouponRecipients: CorpBondFlows.HolderBreakdown,
      corpBondDefaultRecipients: CorpBondFlows.HolderBreakdown,
      corpBondIssuanceRecipients: CorpBondFlows.HolderBreakdown,
      corpBondAmortizationRecipients: CorpBondFlows.HolderBreakdown,
  )

  /** Housing-credit origination, repayment, interest, and default flows. */
  case class HousingFlows(
      mortgageOrigination: PLN,
      mortgageRepayment: PLN,
      mortgageInterest: PLN,
      mortgageDefault: PLN,
  )

  /** Bank income, loss, levy, bail-in, and central-bank settlement flows. */
  case class BankingFlowsPlan(
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
  )

  /** Non-bank financial-intermediary credit and deposit-channel flows. */
  case class NonBankFlows(
      nbfiDepositDrain: PLN,
      nbfiOrigination: PLN,
      nbfiRepayment: PLN,
      nbfiDefaultAmount: PLN,
  )

  /** Quasi-fiscal monetary balance-sheet operations for bank and NBP holders.
    */
  case class QuasiFiscalFlowsPlan(
      qfBankBondIssuance: PLN,
      qfNbpBondAbsorption: PLN,
      qfBankBondAmortization: PLN,
      qfNbpBondAmortization: PLN,
      qfLending: PLN,
      qfRepayment: PLN,
  )

  /** Insurance opening stocks needed to emit current-month investment income.
    */
  case class InsuranceOpeningStocks(
      insuranceCurrentLifeReserves: PLN,
      insuranceCurrentNonLifeReserves: PLN,
      insurancePrevGovBonds: PLN,
      insurancePrevCorpBonds: PLN,
      insuranceCorpBondDefaultLoss: PLN,
      insurancePrevEquity: PLN,
      govBondYield: Rate,
      corpBondYield: Rate,
  )
