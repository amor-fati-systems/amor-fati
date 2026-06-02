package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.engine.*
import com.boombustgroup.amorfati.engine.diagnostics.banking.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.engine.markets.{FiscalBudget, HousingMarket}
import com.boombustgroup.amorfati.types.*

/** Flattened public banking-stage output consumed by downstream month closing,
  * flow execution, diagnostics, and Monte Carlo exports.
  */
final case class StepOutput(
    resolvedBank: Banking.Aggregate,                              // aggregate bank balance sheet after resolution
    banks: Vector[Banking.BankState],                             // explicit post-step bank population
    bankingMarket: Banking.MarketState,                           // banking market wrapper after interbank clearing
    reassignedFirms: Vector[Firm.State],                          // firms with bankId reassigned after bank failure
    reassignedHouseholds: Vector[Household.State],                // HH with bankId reassigned after bank failure
    finalNbp: Nbp.State,                                          // NBP policy/QE state after bond-waterfall settlement
    finalPpk: SocialSecurity.PpkState,                            // PPK monthly contribution state
    finalInsurance: Insurance.State,                              // insurance monthly state
    finalInsuranceBalances: Insurance.ClosingBalances,            // insurance non-corporate-bond closing balances
    finalNbfi: Nbfi.State,                                        // NBFI/TFI monthly state
    finalNbfiBalances: Nbfi.ClosingBalances,                      // NBFI/TFI non-corporate-bond closing balances
    newGovWithYield: FiscalBudget.GovState,                       // gov state with updated bond yield
    newJst: Jst.State,                                            // local government state
    housingAfterFlows: HousingMarket.State,                       // housing market after mortgage flows
    bfgLevy: PLN,                                                 // BFG resolution fund levy (aggregate)
    bailInLoss: PLN,                                              // bail-in deposit destruction (aggregate)
    multiCapDestruction: PLN,                                     // capital wiped when banks fail
    interbankContagionLoss: PLN,                                  // counterparty losses from failed-bank interbank exposures
    bankCapitalDiagnostics: BankCapitalDiagnostics,               // aggregate monthly bank-capital waterfall diagnostics
    bankFailureDiagnostics: BankFailureDiagnostics,               // monthly bank-failure trigger diagnostics
    bankResolutionDiagnostics: BankResolutionDiagnostics,         // monthly bank-resolution count diagnostics
    bankReconciliationDiagnostics: BankReconciliationDiagnostics, // distributed exactness-patch impact on bank capital/CAR
    bankEclDiagnostics: BankEclDiagnostics,                       // IFRS 9 ECL allowance and migration diagnostics
    monAgg: Option[Banking.MonetaryAggregates],                   // M0/M1/M2/M3 (when credit diagnostics on)
    finalHhAgg: Household.Aggregates,                             // recomputed HH aggregates
    vat: PLN,                                                     // gross VAT revenue
    vatAfterEvasion: PLN,                                         // VAT after informal evasion
    pitAfterEvasion: PLN,                                         // PIT after informal evasion
    exciseRevenue: PLN,                                           // gross excise revenue
    exciseAfterEvasion: PLN,                                      // excise after informal evasion
    customsDutyRevenue: PLN,                                      // customs duty revenue
    realizedTaxShadowShare: Share,                                // current-period realized aggregate tax-side shadow share
    mortgageInterestIncome: PLN,                                  // mortgage interest income (bank share)
    mortgagePrincipal: PLN,                                       // mortgage principal repaid
    mortgageDefaultLoss: PLN,                                     // mortgage default loss (bank share)
    mortgageDefaultAmount: PLN,                                   // gross mortgage default amount
    jstDepositChange: PLN,                                        // JST deposit flow (Identity 2)
    investNetDepositFlow: PLN,                                    // investment timing deposit settlement
    actualBondChange: PLN,                                        // net change in gov bonds outstanding
    standingFacilityBackstop: PLN,                                // reserve shortfall funded by explicit NBP standing-facility backstop
    unrealizedBondLoss: PLN,                                      // mark-to-market loss on gov bond portfolio (interest rate risk channel)
    htmRealizedLoss: PLN,                                         // realized loss from HTM forced reclassification
    eclProvisionChange: PLN,                                      // aggregate IFRS 9 ECL provision change
    newQuasiFiscal: QuasiFiscal.State,                            // BGK/PFR market memory after issuance and lending
    govBondRuntimeMovements: GovBondRuntimeMovements,             // holder-resolved SPW runtime movements from the bond waterfall
    ledgerFinancialState: LedgerFinancialState,                   // ledger-backed financial state at the banking stage boundary
)
