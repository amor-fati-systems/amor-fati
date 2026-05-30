package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.engine.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.engine.markets.{FiscalBudget, HousingMarket}
import com.boombustgroup.amorfati.engine.mechanisms.TaxRevenue
import com.boombustgroup.amorfati.types.*

final case class GovBondRuntimeMovements(
    primaryByBank: Vector[PLN],
    foreignPurchaseByBank: Vector[PLN],
    nbpQePurchaseByBank: Vector[PLN],
    ppkPurchaseByBank: Vector[PLN],
    insurancePurchaseByBank: Vector[PLN],
    tfiPurchaseByBank: Vector[PLN],
)

private[banking] final case class GovJstResult(
    newGovWithYield: FiscalBudget.GovState,
    newGovBondOutstanding: PLN,
    newJst: Jst.State,
    jstCash: PLN,
    jstDepositChange: PLN,
    tax: TaxRevenue.Output,
)

private[banking] final case class HousingResult(
    housingAfterFlows: HousingMarket.State,
    mortgageFlows: HousingMarket.MortgageFlows,
)

/** Inputs for the bond waterfall -- raw requests, not final allocations. */
private[banking] final case class BondWaterfallInputs(
    actualBondChange: PLN,
    qeRequested: PLN,
    ppkRequested: PLN,
    insRequested: PLN,
    tfiRequested: PLN,
    erChange: Coefficient,
)

private[banking] final case class BondWaterfallResult(
    banks: Vector[Banking.BankState],
    financialStocks: Vector[Banking.BankFinancialStocks],
    finalNbp: Nbp.State,
    finalNbpFinancialStocks: Nbp.FinancialStocks,
    finalPpk: SocialSecurity.PpkState,
    finalPpkGovBondHoldings: PLN,
    finalInsurance: Insurance.State,
    finalInsuranceBalances: Insurance.ClosingBalances,
    finalNbfi: Nbfi.State,
    finalNbfiBalances: Nbfi.ClosingBalances,
    foreignBondHoldings: PLN,
    bidToCover: Multiplier,
    govBondRuntimeMovements: GovBondRuntimeMovements,
)

private[banking] final case class PerBankHhFlows(
    incomeShare: PLN,
    consShare: PLN,
    mortgageInterest: PLN,
    depInterest: PLN,
    ccDebtService: PLN,
    ccPrincipal: PLN,
    ccOrigination: PLN,
    ccDefault: PLN,
    ccLoanDefault: PLN,
)

private[banking] final case class SingleBankUpdate(
    bank: Banking.BankState,
    financialStocks: Banking.BankFinancialStocks,
)

private[banking] final case class MultiBankResult(
    finalBanks: Vector[Banking.BankState],
    finalBankCorpBondHoldings: Vector[PLN],
    finalBankLedgerBalances: Vector[LedgerFinancialState.BankBalances],
    finalBankingMarket: Banking.MarketState,
    reassignedFirms: Vector[Firm.State],
    reassignedHouseholds: Vector[Household.State],
    bailInLoss: PLN,
    multiCapDestruction: PLN,
    interbankContagionLoss: PLN,
    newFailures: Int,
    capitalReconciliationResidual: PLN,
    bankCapitalTerms: BankCapitalTerms,
    bankFailureDiagnostics: BankFailureDiagnostics,
    bankResolutionDiagnostics: BankResolutionDiagnostics,
    bankReconciliationDiagnostics: BankReconciliationDiagnostics,
    bankEclDiagnostics: BankEclDiagnostics,
    resolvedBank: Banking.Aggregate,
    htmRealizedLoss: PLN,
    finalNbp: Nbp.State,
    finalNbpFinancialStocks: Nbp.FinancialStocks,
    finalPpk: SocialSecurity.PpkState,
    finalPpkGovBondHoldings: PLN,
    finalInsurance: Insurance.State,
    finalInsuranceBalances: Insurance.ClosingBalances,
    finalNbfi: Nbfi.State,
    finalNbfiBalances: Nbfi.ClosingBalances,
    actualBondChange: PLN,
    standingFacilityBackstop: PLN,
    foreignBondHoldings: PLN,
    bidToCover: Multiplier,
    govBondRuntimeMovements: GovBondRuntimeMovements,
)

private[banking] final case class AggregateReconciliation(
    depositsResidual: PLN,
    capitalResidual: PLN,
)

private[banking] final case class AggregateReconciliationResult(
    banks: Vector[Banking.BankState],
    financialStocks: Vector[Banking.BankFinancialStocks],
    bankCorpBondHoldings: Vector[PLN],
    depositResidual: PLN,
    capitalResidual: PLN,
    bailInLossDelta: PLN,
    capitalDestructionDelta: PLN,
    newFailuresDelta: Int,
    failureEvents: Vector[Banking.FailureEvent],
    allFailedFallbackUsed: Boolean,
    bankReconciliationDiagnostics: BankReconciliationDiagnostics,
    resolvedBanksDelta: Int = 0,
)

private[banking] final case class LedgerClosingResult(
    ledgerFinancialState: LedgerFinancialState,
    monAgg: Option[Banking.MonetaryAggregates],
    bankCapitalDiagnostics: BankCapitalDiagnostics,
)

private[banking] final case class BankCapitalTerms(
    unrealizedBondLoss: PLN,
    eclProvisionChange: PLN,
    capitalGrossIncome: PLN,
    retainedIncome: PLN,
)

private[amorfati] final case class ReserveSettlementResult(
    banks: Vector[Banking.BankState],
    financialStocks: Vector[Banking.BankFinancialStocks],
    standingFacilityBackstop: PLN,
    residual: PLN,
)

private[banking] final case class FxSettlementAllocation(
    allocations: Vector[PLN],
    residual: PLN,
)
