package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.{Banking, QuasiFiscal}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState

/** Banking sector economics runner.
  *
  * The runner owns only the ordered monthly workflow. Public finance, housing,
  * household book alignment, bond waterfall, interbank settlement, multi-bank
  * processing, failure resolution, aggregate reconciliation, and ledger close
  * live in package-local stage modules.
  */
object BankingStepRunner:
  type StepInput = com.boombustgroup.amorfati.engine.economics.banking.StepInput
  val StepInput: com.boombustgroup.amorfati.engine.economics.banking.StepInput.type =
    com.boombustgroup.amorfati.engine.economics.banking.StepInput

  type StepOutput = com.boombustgroup.amorfati.engine.economics.banking.StepOutput
  val StepOutput: com.boombustgroup.amorfati.engine.economics.banking.StepOutput.type =
    com.boombustgroup.amorfati.engine.economics.banking.StepOutput

  def runStep(rawIn: StepInput)(using p: SimParams): StepOutput =
    val in                       = rawIn
    val openingBankBooks         = OpeningBankBooks.from(in.ledgerFinancialState)
    val openingBankStocks        = openingBankBooks.financialStocks
    val prevBankAgg              =
      Banking.aggregateFromBankStocks(
        in.banks,
        openingBankStocks,
        openingBankBooks.corpBondHoldings,
      )
    val govJst                   = BankingPublicFinanceStage.compute(in)
    val housing                  = BankingHousingStage.compute(in)
    val bfgLevy                  = Banking.computeBfgLevy(in.banks, openingBankStocks).total
    val investNetDepositFlow     = BankingHouseholdBooks.investmentTimingDepositFlow(in)
    val finalHhAgg               = BankingHouseholdBooks.computeFinalAggregates(in)
    val quasiFiscalStep          =
      QuasiFiscal.step(
        LedgerFinancialState.quasiFiscalStock(in.ledgerFinancialState),
        govJst.newGovWithYield.govCapitalSpend,
        govJst.newGovWithYield.euCofinancing,
        in.world.nbp.qeActive,
      )
    val newQuasiFiscal           = quasiFiscalStep.state
    val quasiFiscalDepositChange = newQuasiFiscal.monthlyLending - newQuasiFiscal.monthlyLoanRepayment
    val wf                       = BankBondWaterfall.inputs(in, govJst.newGovBondOutstanding)
    val multi                    = BankMultiBankStage.process(
      in,
      govJst.jstDepositChange,
      investNetDepositFlow,
      quasiFiscalDepositChange,
      housing.mortgageFlows,
      wf,
      govJst.polishBankLevy,
    )
    val ledgerClosing            = BankingLedgerClosing.close(
      in = in,
      govJst = govJst,
      housing = housing,
      quasiFiscalStep = quasiFiscalStep,
      multi = multi,
      prevBankAgg = prevBankAgg,
      bfgLevy = bfgLevy,
      polishBankLevyTax = multi.polishBankLevyTax,
    )
    val bankCapitalTerms         = multi.bankCapitalTerms

    StepOutput(
      resolvedBank = multi.resolvedBank,
      banks = multi.finalBanks,
      bankingMarket = multi.finalBankingMarket,
      reassignedFirms = multi.reassignedFirms,
      reassignedHouseholds = multi.reassignedHouseholds,
      finalNbp = multi.finalNbp,
      finalPpk = multi.finalPpk,
      finalInsurance = multi.finalInsurance,
      finalInsuranceBalances = multi.finalInsuranceBalances,
      finalNbfi = multi.finalNbfi,
      finalNbfiBalances = multi.finalNbfiBalances,
      newGovWithYield = govJst.newGovWithYield,
      newJst = govJst.newJst,
      housingAfterFlows = housing.housingAfterFlows,
      bfgLevy = bfgLevy,
      polishBankLevyTax = multi.polishBankLevyTax,
      bailInLoss = multi.bailInLoss,
      multiCapDestruction = multi.multiCapDestruction,
      interbankContagionLoss = multi.interbankContagionLoss,
      bankCapitalDiagnostics = ledgerClosing.bankCapitalDiagnostics,
      bankFailureDiagnostics = multi.bankFailureDiagnostics,
      bankResolutionDiagnostics = multi.bankResolutionDiagnostics,
      bankReconciliationDiagnostics = multi.bankReconciliationDiagnostics,
      bankEclDiagnostics = multi.bankEclDiagnostics,
      monAgg = ledgerClosing.monAgg,
      finalHhAgg = finalHhAgg,
      vat = govJst.tax.vat,
      vatAfterEvasion = govJst.tax.vatAfterEvasion,
      pitAfterEvasion = govJst.tax.pitAfterEvasion,
      exciseRevenue = govJst.tax.exciseRevenue,
      exciseAfterEvasion = govJst.tax.exciseAfterEvasion,
      customsDutyRevenue = govJst.tax.customsDutyRevenue,
      realizedTaxShadowShare = govJst.tax.realizedTaxShadowShare,
      mortgageInterestIncome = housing.mortgageFlows.interest,
      mortgagePrincipal = housing.mortgageFlows.principal,
      mortgageDefaultLoss = housing.mortgageFlows.defaultLoss,
      mortgageDefaultAmount = housing.mortgageFlows.defaultAmount,
      jstDepositChange = govJst.jstDepositChange,
      investNetDepositFlow = investNetDepositFlow,
      actualBondChange = multi.actualBondChange,
      standingFacilityBackstop = multi.standingFacilityBackstop,
      unrealizedBondLoss = bankCapitalTerms.unrealizedBondLoss,
      htmRealizedLoss = multi.htmRealizedLoss,
      eclProvisionChange = bankCapitalTerms.eclProvisionChange,
      newQuasiFiscal = newQuasiFiscal,
      govBondRuntimeMovements = multi.govBondRuntimeMovements,
      ledgerFinancialState = ledgerClosing.ledgerFinancialState,
    )
