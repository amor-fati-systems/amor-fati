package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.diagnostics.banking.BankCapitalDiagnostics
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.types.PLN

/** Builds the ledger-backed month boundary after all explicit banking,
  * government-bond, housing, insurance, fund, and resolution movements.
  */
private[banking] object BankingLedgerClosing:

  def close(
      in: StepInput,
      govJst: GovJstResult,
      housing: HousingResult,
      quasiFiscalStep: QuasiFiscal.StepResult,
      multi: MultiBankResult,
      prevBankAgg: Banking.Aggregate,
      bfgLevy: PLN,
  )(using p: SimParams): LedgerClosingResult =
    val issuerSettledFirmBalances =
      CorporateBondOwnership.applyAmortization(in.firm.ledgerFinancialState.firms, multi.reassignedFirms, in.openEconomy.corpBonds.corpBondAmort)

    val rawLedgerFinancialState =
      in.firm.ledgerFinancialState.copy(
        households = LedgerFinancialState.settleHouseholdMortgageStock(
          in.firm.ledgerFinancialState.households,
          housing.housingAfterFlows.mortgageStock,
          housing.housingAfterFlows.lastOrigination,
        ),
        firms = issuerSettledFirmBalances,
        banks = multi.finalBankLedgerBalances,
        government = LedgerFinancialState.GovernmentBalances(govBondOutstanding = govJst.newGovBondOutstanding),
        foreign = LedgerFinancialState.ForeignBalances(
          govBondHoldings = multi.foreignBondHoldings,
          equityHoldings = in.priceEquity.foreignEquityHoldings,
        ),
        nbp = LedgerFinancialState.nbpBalances(
          multi.finalNbpFinancialStocks,
          reserveLiability = LedgerFinancialState.nbpReserveLiabilityFromBanks(multi.finalBankLedgerBalances),
        ),
        insurance = LedgerFinancialState.insuranceBalances(multi.finalInsuranceBalances, in.openEconomy.corpBonds.closingCorpBondProjection.insuranceHoldings),
        funds = LedgerFinancialState.fundBalances(
          zusCash = SocialSecurity.zusCashAfter(in.ledgerFinancialState.funds.zusCash, in.labor.newZus),
          nfzCash = SocialSecurity.nfzCashAfter(in.ledgerFinancialState.funds.nfzCash, in.labor.newNfz),
          fpCash = EarmarkedFunds.fpCashAfter(in.ledgerFinancialState.funds.fpCash, in.labor.newEarmarked),
          pfronCash = EarmarkedFunds.pfronCashAfter(in.ledgerFinancialState.funds.pfronCash, in.labor.newEarmarked),
          fgspCash = EarmarkedFunds.fgspCashAfter(in.ledgerFinancialState.funds.fgspCash, in.labor.newEarmarked),
          jstCash = govJst.jstCash,
          ppkGovBondHoldings = multi.finalPpkGovBondHoldings,
          corporateBonds = in.openEconomy.corpBonds.closingCorpBondProjection,
          nbfi = multi.finalNbfiBalances,
          quasiFiscal = quasiFiscalStep.stock,
        ),
      )
    val ledgerFinancialState    =
      LedgerFinancialState.withBankMortgageAssets(
        LedgerFinancialState.withHouseholdInsuranceReserveAssets(rawLedgerFinancialState),
      )
    val monAgg                  = computeMonetaryAggregates(multi.finalBanks, ledgerFinancialState)
    val bankCapitalTerms        = multi.bankCapitalTerms
    val bankCapitalDiagnostics  = BankCapitalDiagnostics(
      openingCapital = prevBankAgg.capital,
      closingCapital = multi.resolvedBank.capital,
      retainedIncome = bankCapitalTerms.retainedIncome,
      firmNplLoss = in.firm.nplLoss,
      mortgageNplLoss = housing.mortgageFlows.defaultLoss,
      consumerNplLoss = in.householdFinancial.consumerNplLoss,
      corpBondDefaultLoss = in.openEconomy.corpBonds.corpBondBankDefaultLoss,
      bfgLevy = bfgLevy,
      unrealizedBondLoss = bankCapitalTerms.unrealizedBondLoss,
      htmRealizedLoss = multi.htmRealizedLoss,
      eclProvisionChange = bankCapitalTerms.eclProvisionChange,
      capitalDestruction = multi.multiCapDestruction,
      interbankContagionLoss = multi.interbankContagionLoss,
      reconciliationResidual = multi.capitalReconciliationResidual,
      depositBailInLoss = multi.bailInLoss,
      newFailures = multi.newFailures,
    )

    LedgerClosingResult(
      ledgerFinancialState = ledgerFinancialState,
      monAgg = monAgg,
      bankCapitalDiagnostics = bankCapitalDiagnostics,
    )

  /** Monetary aggregates (M0/M1/M2/M3) when credit diagnostics enabled. */
  private def computeMonetaryAggregates(
      finalBanks: Vector[Banking.BankState],
      ledgerFinancialState: LedgerFinancialState,
  ): Option[Banking.MonetaryAggregates] =
    Some(
      Banking.MonetaryAggregates.computeFromBankStocks(
        finalBanks,
        ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks),
        ledgerFinancialState.funds.nbfi.tfiUnit,
        CorporateBondOwnership.issuerOutstanding(ledgerFinancialState),
      ),
    )
