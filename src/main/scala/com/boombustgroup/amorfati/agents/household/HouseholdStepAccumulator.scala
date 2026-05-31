package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.household.HouseholdStepTypes.*
import com.boombustgroup.amorfati.types.*

/** Exact mutable accumulator for one household monthly step. */
private[agents] object HouseholdStepAccumulator:

  /** Accumulates finalized household monthly results without intermediate
    * aggregate objects.
    */
  private[household] final class StepTotals:
    private var incomeAcc: PLN               = PLN.Zero
    private var benefitAcc: PLN              = PLN.Zero
    private var debtSvcAcc: PLN              = PLN.Zero
    private var mortgagePrincipalAcc: PLN    = PLN.Zero
    private var mortgageInterestAcc: PLN     = PLN.Zero
    private var depIntAcc: PLN               = PLN.Zero
    private var goodsConsAcc: PLN            = PLN.Zero
    private var rentAcc: PLN                 = PLN.Zero
    private var remitAcc: PLN                = PLN.Zero
    private var pitAcc: PLN                  = PLN.Zero
    private var socialAcc: PLN               = PLN.Zero
    private var ccDebtSvcAcc: PLN            = PLN.Zero
    private var ccOrigAcc: PLN               = PLN.Zero
    private var ccApprovedOrigAcc: PLN       = PLN.Zero
    private var ccDemandAcc: PLN             = PLN.Zero
    private var ccRejectedAcc: PLN           = PLN.Zero
    private var ccBankRejectedAcc: PLN       = PLN.Zero
    private var liquidityShortfallAcc: PLN   = PLN.Zero
    private var consumptionShortfallAcc: PLN = PLN.Zero
    private var rentArrearsAcc: PLN          = PLN.Zero
    private var mortgageArrearsAcc: PLN      = PLN.Zero
    private var consumerDebtArrearsAcc: PLN  = PLN.Zero
    private var temporaryOverdraftAcc: PLN   = PLN.Zero
    private var ccDefaultAcc: PLN            = PLN.Zero
    private var ccPrincipalAcc: PLN          = PLN.Zero
    private var ccLoanDefaultAcc: PLN        = PLN.Zero
    private var bridgeChargeOffAcc: PLN      = PLN.Zero
    private var unmetBasicConsAcc: PLN       = PLN.Zero
    private var discretionaryCutAcc: PLN     = PLN.Zero
    var retrainingAttempts: Int              = 0
    var retrainingSuccesses: Int             = 0
    var voluntaryQuits: Int                  = 0

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
      liquidityShortfallAcc = liquidityShortfallAcc + r.credit.liquidityShortfallFinancing
      consumptionShortfallAcc = consumptionShortfallAcc + r.credit.liquidityShortfall.consumptionShortfall
      rentArrearsAcc = rentArrearsAcc + r.credit.liquidityShortfall.rentArrears
      mortgageArrearsAcc = mortgageArrearsAcc + r.credit.liquidityShortfall.mortgageArrears
      consumerDebtArrearsAcc = consumerDebtArrearsAcc + r.credit.liquidityShortfall.consumerDebtArrears
      temporaryOverdraftAcc = temporaryOverdraftAcc + r.credit.liquidityShortfall.temporaryOverdraft
      ccDefaultAcc = ccDefaultAcc + r.credit.defaultAmt
      ccPrincipalAcc = ccPrincipalAcc + r.credit.principal
      ccLoanDefaultAcc = ccLoanDefaultAcc + r.credit.consumerLoanDefault
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
    def liquidityShortfallFinancing: PLN          = liquidityShortfallAcc
    def consumptionShortfall: PLN                 = consumptionShortfallAcc
    def rentArrears: PLN                          = rentArrearsAcc
    def mortgageArrears: PLN                      = mortgageArrearsAcc
    def consumerDebtArrears: PLN                  = consumerDebtArrearsAcc
    def temporaryOverdraft: PLN                   = temporaryOverdraftAcc
    def consumerDefault: PLN                      = ccDefaultAcc
    def consumerPrincipal: PLN                    = ccPrincipalAcc
    def consumerLoanDefault: PLN                  = ccLoanDefaultAcc
    def liquidityBridgeChargeOff: PLN             = bridgeChargeOffAcc
    def unmetBasicConsumption: PLN                = unmetBasicConsAcc
    def discretionaryConsumptionCut: PLN          = discretionaryCutAcc
