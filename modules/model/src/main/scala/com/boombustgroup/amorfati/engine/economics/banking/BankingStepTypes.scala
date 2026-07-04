package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.engine.*
import com.boombustgroup.amorfati.engine.diagnostics.banking.*
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
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

private[banking] final case class OpeningBankBooks(
    financialStocks: Vector[Banking.BankFinancialStocks],
    corpBondHoldings: Banking.BankCorpBondHoldings,
)

private[banking] object OpeningBankBooks:
  def from(ledgerFinancialState: LedgerFinancialState): OpeningBankBooks =
    OpeningBankBooks(
      financialStocks = ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks),
      corpBondHoldings = bankId => CorporateBondOwnership.bankHolderFor(ledgerFinancialState, bankId),
    )

private[banking] final case class GovJstResult(
    newGovWithYield: FiscalBudget.GovState,
    newGovBondOutstanding: PLN,
    newJst: Jst.State,
    jstCash: PLN,
    jstDepositChange: PLN,
    polishBankLevy: Banking.PerBankAmounts,
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

private[banking] final case class InterbankSettlementResult(
    banks: Vector[Banking.BankState],
    financialStocks: Vector[Banking.BankFinancialStocks],
    interbankRate: Rate,
    standingFacilityBackstop: PLN,
    htmRealizedLoss: PLN,
)

private[banking] final case class FailureDetectionResult(
    banks: Vector[Banking.BankState],
    financialStocks: Vector[Banking.BankFinancialStocks],
    primary: Banking.FailureCheckResult,
    secondary: Banking.FailureCheckResult,
    contagion: InterbankContagion.ContagionLossResult,
    failedBankIds: Set[BankId],
    anyFailed: Boolean,
    newFailures: Int,
    capitalDestruction: PLN,
    events: Vector[Banking.FailureEvent],
)

private[banking] final case class BailInStageResult(
    banks: Vector[Banking.BankState],
    financialStocks: Vector[Banking.BankFinancialStocks],
    eligibleBankIds: Set[BankId],
    loss: PLN,
)

private[banking] final case class BankResolutionStageResult(
    banks: Vector[Banking.BankState],
    financialStocks: Vector[Banking.BankFinancialStocks],
    bankCorpBondHoldings: Vector[PLN],
    absorberBankId: BankId,
    resolvedBankCount: Int,
    allFailedFallbackUsed: Boolean,
)

private[banking] final case class ReconciledResolutionResult(
    banks: Vector[Banking.BankState],
    financialStocks: Vector[Banking.BankFinancialStocks],
    bankCorpBondHoldings: Vector[PLN],
    bailInLoss: PLN,
    capitalDestruction: PLN,
    newFailures: Int,
    failureEvents: Vector[Banking.FailureEvent],
    resolvedBankCount: Int,
    allFailedFallbackUsed: Boolean,
    bankReconciliationDiagnostics: BankReconciliationDiagnostics,
    capitalResidualBreakdown: BankCapitalResidualBreakdown,
    capitalReconciliationResidual: PLN,
)

private[banking] final case class FailureResolutionPipelineResult(
    failureDetection: FailureDetectionResult,
    bailIn: BailInStageResult,
    bankResolution: BankResolutionStageResult,
    aggregateReconciliation: AggregateReconciliationResult,
    finalResolution: ReconciledResolutionResult,
    bankCapitalTerms: BankCapitalTerms,
)

private[banking] final case class PerBankHhFlows(
    incomeShare: PLN,
    consShare: PLN,
    mortgageInterest: PLN,
    depInterest: PLN,
    ccDebtService: PLN,
    ccPrincipal: PLN,
    ccOrigination: PLN,
    liquidityShortfallFinancing: PLN,
    ccDefault: PLN,
    ccLoanDefault: PLN,
    ccInsolvencyDefault: PLN,
    liquidityBridgeChargeOff: PLN,
)

private[banking] final case class SingleBankUpdate(
    bank: Banking.BankState,
    financialStocks: Banking.BankFinancialStocks,
    bankCapitalTerms: BankCapitalTerms = BankCapitalTerms.zero,
)

private[banking] final case class MultiBankResult(
    finalBanks: Vector[Banking.BankState],
    finalBankCorpBondHoldings: Vector[PLN],
    finalBankLedgerBalances: Vector[LedgerFinancialState.BankBalances],
    finalBankingMarket: Banking.MarketState,
    reassignedFirms: Vector[Firm.State],
    reassignedHouseholds: Vector[Household.State],
    bailInLoss: PLN,
    polishBankLevyTax: PLN,
    multiCapDestruction: PLN,
    interbankContagionLoss: PLN,
    newFailures: Int,
    capitalReconciliationResidual: PLN,
    capitalResidualBreakdown: BankCapitalResidualBreakdown,
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
    capitalResidualBreakdown: BankCapitalResidualBreakdown,
    resolvedBanksDelta: Int = 0,
)

private[banking] final case class ReconciliationPatchResult(
    banks: Vector[Banking.BankState],
    financialStocks: Vector[Banking.BankFinancialStocks],
    bankReconciliationDiagnostics: BankReconciliationDiagnostics,
)

private[banking] final case class LedgerClosingResult(
    ledgerFinancialState: LedgerFinancialState,
    monAgg: Option[Banking.MonetaryAggregates],
    bankCapitalDiagnostics: BankCapitalDiagnostics,
)

private[banking] final case class BankCapitalTerms(
    creditLosses: BankCreditLossAccounting.Breakdown,
    unrealizedBondLoss: PLN,
    eclProvisionChange: PLN,
    capitalGrossIncome: PLN,
    retainedIncome: PLN,
    bfgLevy: PLN = PLN.Zero,
    polishBankLevyTax: PLN = PLN.Zero,
):
  def firmNplLoss: PLN               = creditLosses.firm.netCapitalLoss
  def mortgageNplLoss: PLN           = creditLosses.mortgage.netCapitalLoss
  def consumerNplLoss: PLN           = creditLosses.consumer.netCapitalLoss
  def consumerLoanNplLoss: PLN       = creditLosses.consumerLoan.netCapitalLoss
  def consumerInsolvencyNplLoss: PLN = creditLosses.consumerInsolvency.netCapitalLoss
  def liquidityBridgeNplLoss: PLN    = creditLosses.liquidityBridge.netCapitalLoss
  def corpBondDefaultLoss: PLN       = creditLosses.corpBondDefaultLoss
  def realizedCreditLoss: PLN        = creditLosses.realizedCreditLoss

  def +(other: BankCapitalTerms): BankCapitalTerms =
    BankCapitalTerms(
      creditLosses = creditLosses + other.creditLosses,
      unrealizedBondLoss = unrealizedBondLoss + other.unrealizedBondLoss,
      eclProvisionChange = eclProvisionChange + other.eclProvisionChange,
      capitalGrossIncome = capitalGrossIncome + other.capitalGrossIncome,
      retainedIncome = retainedIncome + other.retainedIncome,
      bfgLevy = bfgLevy + other.bfgLevy,
      polishBankLevyTax = polishBankLevyTax + other.polishBankLevyTax,
    )

object BankCapitalTerms:
  val zero: BankCapitalTerms =
    BankCapitalTerms(
      creditLosses = BankCreditLossAccounting.Breakdown.zero,
      unrealizedBondLoss = PLN.Zero,
      eclProvisionChange = PLN.Zero,
      capitalGrossIncome = PLN.Zero,
      retainedIncome = PLN.Zero,
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
