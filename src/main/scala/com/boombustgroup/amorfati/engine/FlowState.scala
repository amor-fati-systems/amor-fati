package com.boombustgroup.amorfati.engine

import com.boombustgroup.amorfati.agents.Firm.CreditRejectionBreakdown
import com.boombustgroup.amorfati.engine.diagnostics.banking.*
import com.boombustgroup.amorfati.types.*

/** Single-step derived flow outputs recomputed each step for SFC identities and
  * output columns.
  */
case class FlowState(
    monthlyGdpProxy: PLN = PLN.Zero,                                                        // cached monthly GDP proxy for diagnostics / output ratios
    sectorOutputs: Vector[PLN] = Vector.empty,                                              // nominal monthly output by schema sector
    ioFlows: PLN = PLN.Zero,                                                                // I-O intermediate payments between sectors
    fdiProfitShifting: PLN = PLN.Zero,                                                      // intangible imports booked abroad (profit shifting)
    fdiRepatriation: PLN = PLN.Zero,                                                        // dividend repatriation by foreign-owned firms
    fdiCitLoss: PLN = PLN.Zero,                                                             // CIT lost to profit shifting
    diasporaRemittanceInflow: PLN = PLN.Zero,                                               // diaspora remittance inflow
    tourismExport: PLN = PLN.Zero,                                                          // inbound tourism services export
    tourismImport: PLN = PLN.Zero,                                                          // outbound tourism services import
    aggInventoryStock: PLN = PLN.Zero,                                                      // aggregate firm inventory stock
    aggInventoryChange: PLN = PLN.Zero,                                                     // change in inventories entering GDP
    aggEnergyCost: PLN = PLN.Zero,                                                          // aggregate energy and CO2 costs
    automationTechCapex: PLN = PLN.Zero,                                                    // technology CAPEX for automation/hybrid upgrades
    automationTechImports: PLN = PLN.Zero,                                                  // import content of technology CAPEX
    automationTechLoans: PLN = PLN.Zero,                                                    // bank-credit component of technology financing
    automationUpgradeFailures: Int = 0,                                                     // implementation failures causing firm bankruptcy
    automationAiDebtTrap: Int = 0,                                                          // AI debt-trap bankruptcies
    automationNewFullAi: Int = 0,                                                           // new full-AI adopters
    automationNewHybrid: Int = 0,                                                           // new hybrid adopters
    firmNewLoans: PLN = PLN.Zero,                                                           // aggregate firm bank-loan origination, including bond reversion
    firmPrincipalRepaid: PLN = PLN.Zero,                                                    // aggregate scheduled firm-loan principal repayment
    firmGrossDefault: PLN = PLN.Zero,                                                       // gross firm-loan default volume from newly bankrupt firms
    firmNplLoss: PLN = PLN.Zero,                                                            // net firm-loan credit loss after recovery
    firmInvestmentCreditDemand: PLN = PLN.Zero,                                             // physical-investment bank credit requested
    firmInvestmentCreditApproved: PLN = PLN.Zero,                                           // physical-investment bank credit approved
    firmInvestmentCreditRejected: PLN = PLN.Zero,                                           // physical-investment bank credit rejected by bank supply
    firmTechCreditDemand: PLN = PLN.Zero,                                                   // technology-upgrade bank credit requested or bank-rejected
    firmTechCreditApproved: PLN = PLN.Zero,                                                 // technology-upgrade bank credit approved
    firmTechCreditRejected: PLN = PLN.Zero,                                                 // technology-upgrade bank credit rejected by bank supply
    firmTechSelectedCreditDemand: PLN = PLN.Zero,                                           // actual selected technology-upgrade bank credit requested
    firmTechSelectedCreditApproved: PLN = PLN.Zero,                                         // actual selected technology-upgrade bank credit approved
    firmTechSelectedCreditRejected: PLN = PLN.Zero,                                         // actual selected technology-upgrade bank credit rejected by bank supply
    firmTechCandidateCreditDemand: PLN = PLN.Zero,                                          // otherwise feasible technology-upgrade candidate bank credit requested
    firmTechCandidateCreditApproved: PLN = PLN.Zero,                                        // otherwise feasible technology-upgrade candidate bank credit approved
    firmTechCandidateCreditRejected: PLN = PLN.Zero,                                        // otherwise feasible technology-upgrade candidate rejected by bank supply
    firmCreditRejectedByReason: CreditRejectionBreakdown = CreditRejectionBreakdown.zero,   // firm bank-credit rejections by primary reason
    firmBirths: Int = 0,                                                                    // new firms (recycled + net new)
    firmDeaths: Int = 0,                                                                    // firms bankrupt this step
    netFirmBirths: Int = 0,                                                                 // net new firms appended to vector
    taxEvasionLoss: PLN = PLN.Zero,                                                         // tax lost to 4-channel evasion (CIT+VAT+PIT+excise)
    realizedTaxShadowShare: Share = Share.Zero,                                             // current-period realized aggregate tax-side shadow share
    bailInLoss: PLN = PLN.Zero,                                                             // bail-in deposit haircut imposed on bank creditors
    bfgLevyTotal: PLN = PLN.Zero,                                                           // BFG resolution levy from all banks
    polishBankLevyTaxTotal: PLN = PLN.Zero,                                                 // Polish tax on selected financial institutions paid by banks
    bankCapital: BankCapitalDiagnostics = BankCapitalDiagnostics.zero,                      // monthly bank-capital waterfall diagnostics
    bankFailure: BankFailureDiagnostics = BankFailureDiagnostics.zero,                      // monthly bank-failure trigger diagnostics
    bankResolution: BankResolutionDiagnostics = BankResolutionDiagnostics.zero,             // monthly bank-resolution count diagnostics
    bankReconciliation: BankReconciliationDiagnostics = BankReconciliationDiagnostics.zero, // distributed exactness-patch impact on bank capital/CAR
    bankEcl: BankEclDiagnostics = BankEclDiagnostics.zero,                                  // IFRS 9 ECL allowance and staging diagnostics
)
object FlowState:
  val zero: FlowState = FlowState()
