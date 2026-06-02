package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.BatchedFlow

/** Translates monthly calculus into runtime ledger batches.
  *
  * Economics decides quantities and rates before this boundary. This module
  * only maps those month-level quantities into named flow mechanisms.
  */
object MonthFlowEmitter:

  def emitAllBatches(c: MonthlyCalculus)(using p: SimParams, topology: RuntimeLedgerTopology): Vector[BatchedFlow] =
    Vector.concat(
      // Tier 1: Social funds
      ZusFlows.emitBatches(ZusFlows.ZusInput(c.zus)),
      NfzFlows.emitBatches(NfzFlows.NfzInput(c.nfz)),
      PpkFlows.emitBatches(PpkFlows.PpkInput(c.ppk)),
      GovBondFlows.emitBatches(c.govBondRuntimeMovements),
      EarmarkedFlows.emitBatches(EarmarkedFlows.Input(c.earmarked)),
      JstFlows.emitBatches(JstFlows.Input(c.firmTax, c.totalIncome, c.gdp, c.livingFirms, c.pitRevenue)),
      // Tier 2: Agents
      HouseholdFlows.emitBatches(
        HouseholdFlows.Input(
          c.consumption,
          c.totalRent,
          c.pitRevenue,
          c.totalDepositInterest,
          c.totalRemittances,
          c.approvedCcOrigination,
          c.liquidityShortfallFinancing,
          c.totalCcPrincipal,
          (c.totalCcDebtService - c.totalCcPrincipal).max(PLN.Zero),
          c.totalCcDefault,
        ),
      ),
      FirmFlows.emitBatches(
        FirmFlows.Input(
          c.totalIncome,
          c.firmTax,
          c.firmPrincipal,
          c.firmNewLoans,
          c.firmInterestIncome,
          c.firmCapex,
          c.firmEquityIssuance,
          c.firmIoPayments,
          c.firmNplLoss,
          c.firmProfitShifting,
          c.firmFdiRepatriation,
          c.firmGrossInvestment,
        ),
      ),
      InvestmentDepositSettlementFlows.emitBatches(InvestmentDepositSettlementFlows.Input(c.investNetDepositFlow)),
      GovBudgetFlows.emitBatches(
        GovBudgetFlows.Input(
          vatRevenue = c.govVatRevenue,
          exciseRevenue = c.govExciseRevenue,
          customsDutyRevenue = c.govCustomsDutyRevenue,
          govCurrentSpend = c.govCurrentSpend,
          debtService = c.govDebtService,
          unempBenefitSpend = c.totalUnempBenefits,
          socialTransferSpend = c.totalSocialTransfers,
          euCofinancing = c.govEuCofinancing,
          govCapitalSpend = c.govCapitalSpend,
          debtServiceRecipients = Some(c.govDebtServiceRecipients),
        ),
      ),
      InsuranceFlows.emitBatches(
        InsuranceFlows.Input(
          employed = c.employed,
          wage = c.wage,
          unempRate = c.unemploymentRate,
          currentLifeReserves = c.insuranceCurrentLifeReserves,
          currentNonLifeReserves = c.insuranceCurrentNonLifeReserves,
          prevGovBondHoldings = c.insurancePrevGovBonds,
          prevCorpBondHoldings = c.insurancePrevCorpBonds,
          corpBondDefaultLoss = c.insuranceCorpBondDefaultLoss,
          prevEquityHoldings = c.insurancePrevEquity,
          govBondYield = c.govBondYield,
          corpBondYield = c.corpBondYield,
          equityReturn = c.equityReturn,
        ),
      ),
      // Tier 3: Financial markets
      EquityFlows.emitBatches(
        EquityFlows.Input(
          c.equityDomDividends,
          c.equityForDividends,
          c.equityDivTax,
          c.equityGovDividends,
        ),
      ),
      EquityFlows.emitRevaluationBatches(c.equityRevaluation),
      CorpBondFlows.emitBatches(
        CorpBondFlows.Input(
          coupon = c.corpBondCoupon,
          defaultAmount = c.corpBondDefaultAmount,
          issuance = c.corpBondIssuance,
          amortization = c.corpBondAmortization,
          couponRecipients = Some(c.corpBondCouponRecipients),
          defaultRecipients = Some(c.corpBondDefaultRecipients),
          issuanceRecipients = Some(c.corpBondIssuanceRecipients),
          amortizationRecipients = Some(c.corpBondAmortizationRecipients),
        ),
      ),
      MortgageFlows.emitBatches(MortgageFlows.Input(c.mortgageOrigination, c.mortgageRepayment, c.mortgageInterest, c.mortgageDefault)),
      OpenEconFlows.emitBatches(
        OpenEconFlows.Input(
          exports = c.exports,
          imports = c.totalImports,
          tourismExport = c.tourismExport,
          tourismImport = c.tourismImport,
          fdi = c.fdi,
          portfolioFlows = c.portfolioFlows,
          carryTradeFlow = c.carryTradeFlow,
          primaryIncome = c.primaryIncome,
          euFunds = c.euFunds,
          diasporaInflow = c.diasporaInflow,
          capitalFlightOutflow = c.capitalFlightOutflow,
        ),
      ),
      BankingFlows.emitBatches(
        BankingFlows.Input(
          firmInterestIncome = c.firmInterestIncome,
          firmNplLoss = c.firmNplLoss,
          mortgageNplLoss = c.mortgageDefault * (Share.One - p.housing.mortgageRecovery),
          consumerNplLoss = (c.totalCcDefault - c.liquidityShortfallFinancing).max(PLN.Zero) * (Share.One - p.household.ccNplRecovery),
          govBondIncome = c.bankGovBondIncome,
          reserveInterest = c.bankReserveInterest,
          standingFacilityIncome = c.bankStandingFacility,
          interbankInterest = c.bankInterbankInterest,
          corpBondCoupon = c.bankCorpBondCoupon,
          corpBondDefaultLoss = c.bankCorpBondLoss,
          bfgLevy = c.bankBfgLevy,
          unrealizedBondLoss = c.bankUnrealizedLoss,
          bailInLoss = c.bankBailIn,
          nbpRemittance = c.bankNbpRemittance,
          fxReserveSettlement = c.bankFxReserveSettlement,
          standingFacilityBackstop = c.bankStandingFacilityBackstop,
        ),
      ),
      NbfiFlows.emitBatches(NbfiFlows.Input(c.nbfiDepositDrain, c.nbfiOrigination, c.nbfiRepayment, c.nbfiDefaultAmount)),
      QuasiFiscalFlows.emitBatches(
        QuasiFiscalFlows.Input(
          bankBondIssuance = c.qfBankBondIssuance,
          nbpBondAbsorption = c.qfNbpBondAbsorption,
          bankBondAmortization = c.qfBankBondAmortization,
          nbpBondAmortization = c.qfNbpBondAmortization,
          lending = c.qfLending,
          repayment = c.qfRepayment,
        ),
      ),
    )
