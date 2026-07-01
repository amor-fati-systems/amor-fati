package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.agents.household.HouseholdStepTypes.*
import com.boombustgroup.amorfati.types.*

/** Exact mutable accumulator for one household monthly step. */
private[agents] object HouseholdStepAccumulator:

  /** Accumulates finalized household monthly results without intermediate
    * aggregate objects.
    */
  private[household] final class StepTotals:
    private var incomeAcc: PLN               = PLN.Zero // total household income
    private var benefitAcc: PLN              = PLN.Zero // unemployment benefits received
    private var debtSvcAcc: PLN              = PLN.Zero // total debt service paid
    private var mortgagePrincipalAcc: PLN    = PLN.Zero // mortgage principal repaid
    private var mortgageInterestAcc: PLN     = PLN.Zero // mortgage interest paid
    private var depIntAcc: PLN               = PLN.Zero // deposit interest received
    private var goodsConsAcc: PLN            = PLN.Zero // goods consumption paid
    private var rentAcc: PLN                 = PLN.Zero // rent paid
    private var remitAcc: PLN                = PLN.Zero // remittances paid
    private var pitAcc: PLN                  = PLN.Zero // PIT paid
    private var socialAcc: PLN               = PLN.Zero // social transfers received
    private var ccDebtSvcAcc: PLN            = PLN.Zero // consumer-credit debt service paid
    private var ccOrigAcc: PLN               = PLN.Zero // total consumer-credit origination
    private var ccApprovedOrigAcc: PLN       = PLN.Zero // underwritten consumer credit originated by DTI rule
    private var ccDemandAcc: PLN             = PLN.Zero // total consumer-credit demand
    private var ccRejectedAcc: PLN           = PLN.Zero // total rejected consumer-credit demand
    private var ccBankRejectedAcc: PLN       = PLN.Zero // bank-side rejected consumer-credit demand
    private var ccPortfolioRejectedAcc: PLN  = PLN.Zero // bank-side rejection due to portfolio preference
    private var liquidityShortfallAcc: PLN   = PLN.Zero // same-month bridge/write-off preventing negative deposits
    private var consumptionShortfallAcc: PLN = PLN.Zero // consumption shortfall component
    private var rentArrearsAcc: PLN          = PLN.Zero // rent arrears component
    private var mortgageArrearsAcc: PLN      = PLN.Zero // mortgage arrears component
    private var consumerDebtArrearsAcc: PLN  = PLN.Zero // consumer-debt arrears component
    private var temporaryOverdraftAcc: PLN   = PLN.Zero // temporary overdraft component
    private var ccDefaultAcc: PLN            = PLN.Zero // ordinary consumer-loan principal default
    private var ccPrincipalAcc: PLN          = PLN.Zero // consumer-loan principal repaid
    private var ccLoanDefaultAcc: PLN        = PLN.Zero // default of ordinary outstanding consumer-loan principal
    private var ccInsolvencyDefaultAcc: PLN  = PLN.Zero // subset of consumer-loan default from personal-insolvency filing
    private var bridgeChargeOffAcc: PLN      = PLN.Zero // same-month bridge charge-off, not ordinary consumer-loan default
    private var unmetBasicConsAcc: PLN       = PLN.Zero // unmet basic consumption
    private var discretionaryCutAcc: PLN     = PLN.Zero // discretionary consumption compression

    var retrainingAttempts: Int              = 0        // retraining attempts count
    var retrainingSuccesses: Int             = 0        // retraining successes count
    var voluntaryQuits: Int                  = 0        // voluntary quits count

    /** Adds one finalized household monthly result to the accumulator. */
    def add(r: HhMonthlyResult): Unit =
      incomeAcc = incomeAcc + r.income
      benefitAcc = benefitAcc + r.benefit
      debtSvcAcc = debtSvcAcc + r.debtService
      mortgagePrincipalAcc = mortgagePrincipalAcc + r.mortgagePrincipal
      mortgageInterestAcc = mortgageInterestAcc + r.mortgageInterest
      depIntAcc = depIntAcc + r.depositInterest
      goodsConsAcc = goodsConsAcc + r.consumption
      rentAcc = rentAcc + r.rent
      remitAcc = remitAcc + r.remittance
      pitAcc = pitAcc + r.pitTax
      socialAcc = socialAcc + r.socialTransfer
      ccDebtSvcAcc = ccDebtSvcAcc + r.credit.debtService
      ccOrigAcc = ccOrigAcc + r.credit.totalOrigination
      ccApprovedOrigAcc = ccApprovedOrigAcc + r.credit.newLoan
      ccDemandAcc = ccDemandAcc + r.credit.creditDemand
      ccRejectedAcc = ccRejectedAcc + r.credit.rejectedCreditDemand
      ccBankRejectedAcc = ccBankRejectedAcc + r.credit.bankRejectedCreditDemand
      if r.credit.bankApproval.flatMap(_.audit.rejectionReason).contains(Banking.CreditRejectionReason.PortfolioPreference) then
        ccPortfolioRejectedAcc = ccPortfolioRejectedAcc + r.credit.bankRejectedCreditDemand
      liquidityShortfallAcc = liquidityShortfallAcc + r.credit.liquidityShortfallFinancing
      consumptionShortfallAcc = consumptionShortfallAcc + r.credit.liquidityShortfall.consumptionShortfall
      rentArrearsAcc = rentArrearsAcc + r.credit.liquidityShortfall.rentArrears
      mortgageArrearsAcc = mortgageArrearsAcc + r.credit.liquidityShortfall.mortgageArrears
      consumerDebtArrearsAcc = consumerDebtArrearsAcc + r.credit.liquidityShortfall.consumerDebtArrears
      temporaryOverdraftAcc = temporaryOverdraftAcc + r.credit.liquidityShortfall.temporaryOverdraft
      ccDefaultAcc = ccDefaultAcc + r.credit.defaultAmt
      ccPrincipalAcc = ccPrincipalAcc + r.credit.principal
      ccLoanDefaultAcc = ccLoanDefaultAcc + r.credit.consumerLoanDefault
      ccInsolvencyDefaultAcc = ccInsolvencyDefaultAcc + r.credit.insolvencyDefaultAmt
      bridgeChargeOffAcc = bridgeChargeOffAcc + r.credit.liquidityBridgeChargeOff
      unmetBasicConsAcc = unmetBasicConsAcc + r.unmetBasicConsumption
      discretionaryCutAcc = discretionaryCutAcc + r.discretionaryConsumptionCompression
      retrainingAttempts += r.retrainingAttempt
      retrainingSuccesses += r.retrainingSuccess
      voluntaryQuits += r.voluntaryQuit

    def income: PLN                               = incomeAcc
    def unempBenefits: PLN                        = benefitAcc
    def debtService: PLN                          = debtSvcAcc
    def mortgagePrincipal: PLN                    = mortgagePrincipalAcc
    def mortgageInterest: PLN                     = mortgageInterestAcc
    def depositInterest: PLN                      = depIntAcc
    def goodsConsumption: PLN                     = goodsConsAcc
    def rent: PLN                                 = rentAcc
    def remittances: PLN                          = remitAcc
    def pit: PLN                                  = pitAcc
    def socialTransfers: PLN                      = socialAcc
    def consumerDebtService: PLN                  = ccDebtSvcAcc
    def consumerOrigination: PLN                  = ccOrigAcc
    def consumerApprovedOrigination: PLN          = ccApprovedOrigAcc
    def totalConsumerCreditDemand: PLN            = ccDemandAcc
    def totalConsumerRejectedOrigination: PLN     = ccRejectedAcc
    def totalConsumerBankRejectedOrigination: PLN = ccBankRejectedAcc
    def totalConsumerBankRejectedPortfolio: PLN   = ccPortfolioRejectedAcc
    def liquidityShortfallFinancing: PLN          = liquidityShortfallAcc
    def consumptionShortfall: PLN                 = consumptionShortfallAcc
    def rentArrears: PLN                          = rentArrearsAcc
    def mortgageArrears: PLN                      = mortgageArrearsAcc
    def consumerDebtArrears: PLN                  = consumerDebtArrearsAcc
    def temporaryOverdraft: PLN                   = temporaryOverdraftAcc
    def consumerDefault: PLN                      = ccDefaultAcc
    def consumerPrincipal: PLN                    = ccPrincipalAcc
    def consumerLoanDefault: PLN                  = ccLoanDefaultAcc
    def consumerInsolvencyDefault: PLN            = ccInsolvencyDefaultAcc
    def liquidityBridgeChargeOff: PLN             = bridgeChargeOffAcc
    def unmetBasicConsumption: PLN                = unmetBasicConsAcc
    def discretionaryConsumptionCut: PLN          = discretionaryCutAcc
