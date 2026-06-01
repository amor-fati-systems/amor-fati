package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.accounting.Sfc
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.{EngineFailure, MonthSemantics}
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.*

/** Projects executed runtime batches into the SFC semantic validation surface.
  *
  * Runtime execution is the cash-flow source of truth. This module translates
  * those executed batches plus the narrow same-month semantic view into
  * `Sfc.SemanticFlows`, then runs the SFC validation boundary.
  */
object SfcSemanticProjection:

  def semanticFlows(
      semanticProjection: MonthSemantics.SemanticProjection,
      batches: Vector[BatchedFlow],
      fofResidual: PLN,
  )(using p: SimParams): Sfc.SemanticFlows =
    val firms    = semanticProjection.firms
    val openEcon = semanticProjection.openEcon
    val banking  = semanticProjection.banking
    val evidence = ExecutedFlowEvidence.from(batches)
    // Runtime-covered legs are sourced from executed flow evidence. Remaining
    // month-semantics reads are diagnostics or stock projections without a
    // first-class emitted mechanism yet.
    Sfc.SemanticFlows(
      govSpending = evidence.govSpending,
      govRevenue = evidence.sum(GovBudgetFlows.CentralGovernmentRevenueMechanisms*),
      nplLoss = evidence.amount(FlowMechanism.BankNplLoss),
      interestIncome = evidence.amount(FlowMechanism.BankFirmInterest),
      totalIncome = evidence.amount(FlowMechanism.HhTotalIncome),
      totalConsumption = evidence.amount(FlowMechanism.HhConsumption),
      newLoans = evidence.amount(FlowMechanism.FirmNewLoan),
      nplRecovery = firms.nplNew * p.banking.loanRecovery,
      currentAccount = openEcon.external.newBop.currentAccount,
      valuationEffect = openEcon.external.oeValuationEffect,
      bankBondIncome = evidence.amount(FlowMechanism.BankGovBondIncome),
      qePurchase = evidence.amount(FlowMechanism.NbpQeGovBondPurchase),
      newBondIssuance = banking.actualBondChange,
      depositInterestPaid = evidence.amount(FlowMechanism.HhDepositInterest),
      reserveInterest = evidence.amount(FlowMechanism.BankReserveInterest),
      standingFacilityIncome = evidence.signedAmount(FlowMechanism.BankStandingFacility),
      interbankInterest = evidence.signedAmount(FlowMechanism.BankInterbankInterest),
      jstDepositChange = evidence.jstDepositChange,
      jstSpending = evidence.amount(FlowMechanism.JstSpending),
      jstRevenue = evidence.jstRevenue,
      zusContributions = evidence.amount(FlowMechanism.ZusContribution),
      zusPensionPayments = evidence.amount(FlowMechanism.ZusPension),
      zusGovSubvention = evidence.amount(FlowMechanism.ZusGovSubvention),
      nfzContributions = evidence.amount(FlowMechanism.NfzContribution),
      nfzSpending = evidence.amount(FlowMechanism.NfzSpending),
      nfzGovSubvention = evidence.amount(FlowMechanism.NfzGovSubvention),
      dividendIncome = evidence.amount(FlowMechanism.EquityDomDividend),
      foreignDividendOutflow = evidence.amount(FlowMechanism.EquityForDividend),
      dividendTax = evidence.amount(FlowMechanism.EquityDividendTax),
      mortgageInterestIncome = evidence.amount(FlowMechanism.MortgageInterest),
      mortgageNplLoss = evidence.amount(FlowMechanism.BankMortgageNplLoss),
      mortgageOrigination = evidence.amount(FlowMechanism.MortgageOrigination),
      mortgagePrincipalRepaid = evidence.amount(FlowMechanism.MortgageRepayment),
      mortgageDefaultAmount = evidence.amount(FlowMechanism.MortgageDefault),
      remittanceOutflow = evidence.amount(FlowMechanism.HhRemittance),
      fofResidual = fofResidual,
      consumerDebtService = evidence.amount(FlowMechanism.HhCcDebtService) + evidence.amount(FlowMechanism.HhCcInterest),
      consumerNplLoss = evidence.amount(FlowMechanism.BankCcNplLoss),
      consumerOrigination = evidence.amount(FlowMechanism.HhCcOrigination) + evidence.amount(FlowMechanism.HhLiquidityShortfallFinancing),
      consumerLiquidityShortfallFinancing = evidence.amount(FlowMechanism.HhLiquidityShortfallFinancing),
      consumerPrincipalRepaid = evidence.amount(FlowMechanism.HhCcDebtService),
      consumerDefaultAmount = evidence.amount(FlowMechanism.HhCcDefault),
      corpBondCouponIncome = evidence.amount(FlowMechanism.BankCorpBondCoupon),
      corpBondDefaultLoss = evidence.amount(FlowMechanism.BankCorpBondLoss),
      corpBondIssuance = evidence.amount(FlowMechanism.CorpBondIssuance),
      corpBondAmortization = evidence.amount(FlowMechanism.CorpBondAmortization),
      corpBondDefaultAmount = evidence.amount(FlowMechanism.CorpBondDefault),
      insNetDepositChange = evidence.insuranceNetDepositChange,
      nbfiDepositDrain = evidence.nbfiDepositDrain,
      nbfiOrigination = evidence.amount(FlowMechanism.NbfiOrigination),
      nbfiRepayment = evidence.amount(FlowMechanism.NbfiRepayment),
      nbfiDefaultAmount = evidence.amount(FlowMechanism.NbfiDefault),
      fdiProfitShifting = evidence.amount(FlowMechanism.FirmProfitShifting),
      fdiRepatriation = evidence.amount(FlowMechanism.FirmFdiRepatriation),
      diasporaInflow = evidence.amount(FlowMechanism.DiasporaInflow),
      tourismExport = evidence.amount(FlowMechanism.TourismExport),
      tourismImport = evidence.amount(FlowMechanism.TourismImport),
      bfgLevy = evidence.amount(FlowMechanism.BankBfgLevy),
      bailInLoss = evidence.amount(FlowMechanism.BankBailIn),
      bankCapitalDestruction = banking.multiCapDestruction,
      interbankContagionLoss = banking.interbankContagionLoss,
      investNetDepositFlow = evidence.investNetDepositFlow,
      firmPrincipalRepaid = evidence.amount(FlowMechanism.FirmLoanRepayment),
      unrealizedBondLoss = evidence.amount(FlowMechanism.BankUnrealizedLoss),
      htmRealizedLoss = banking.htmRealizedLoss,
      eclProvisionChange = banking.eclProvisionChange,
      quasiFiscalBondIssuance = evidence.quasiFiscalBondIssuance,
      quasiFiscalBondAmortization = evidence.quasiFiscalBondAmortization,
      quasiFiscalNbpBondAmortization = evidence.quasiFiscalNbpBondAmortization,
      quasiFiscalNbpAbsorption = evidence.quasiFiscalNbpAbsorption,
      quasiFiscalLending = evidence.quasiFiscalLending,
      quasiFiscalRepayment = evidence.quasiFiscalRepayment,
      quasiFiscalDepositChange = evidence.quasiFiscalDepositChange,
    )

  def validate(
      stateIn: FlowSimulation.SimState,
      nextState: FlowSimulation.SimState,
      flows: Sfc.SemanticFlows,
      batches: Vector[BatchedFlow],
      execution: RuntimeFlowExecutor.Result,
  )(using SimParams): Sfc.SfcResult =
    Sfc.validate(
      prev = runtimeState(stateIn),
      curr = runtimeState(nextState),
      flows = flows,
      batches = batches,
      executionDeltaLedger = Sfc.ExecutionDeltaLedger.fromRaw(execution.deltaLedger),
      deltaLedgerNet = execution.netDelta,
    )

  def runtimeState(state: FlowSimulation.SimState): Sfc.RuntimeState =
    Sfc.RuntimeState(state.world, state.firms, state.households, state.banks, state.ledgerFinancialState)

  case class ExecutedFlowEvidence(
      totals: Map[MechanismId, Long],
      signedTotals: Map[MechanismId, Long],
  ):
    def amount(mechanism: MechanismId): PLN =
      PLN.fromRaw(totals.getOrElse(mechanism, 0L))

    def signedAmount(mechanism: MechanismId): PLN =
      PLN.fromRaw(signedTotals.getOrElse(mechanism, 0L))

    def sum(mechanisms: MechanismId*): PLN =
      PLN.fromRaw(mechanisms.iterator.map(m => totals.getOrElse(m, 0L)).sum)

    def sumAll(mechanisms: Iterable[MechanismId]): PLN =
      PLN.fromRaw(mechanisms.iterator.map(m => totals.getOrElse(m, 0L)).sum)

    def govSpending: PLN =
      sumAll(ExecutedFlowEvidence.CentralGovernmentSpendingMechanisms) +
        sumAll(ExecutedFlowEvidence.SocialFundGovSubventionMechanisms)

    def jstRevenue: PLN =
      sumAll(ExecutedFlowEvidence.JstRevenueMechanisms)

    def jstDepositChange: PLN =
      jstRevenue - amount(FlowMechanism.JstSpending)

    def insuranceNetDepositChange: PLN =
      sum(FlowMechanism.InsLifeClaim, FlowMechanism.InsNonLifeClaim) -
        sum(FlowMechanism.InsLifePremium, FlowMechanism.InsNonLifePremium)

    def investNetDepositFlow: PLN =
      signedAmount(FlowMechanism.InvestmentDepositSettlement)

    def nbfiDepositDrain: PLN =
      signedAmount(FlowMechanism.TfiDepositDrain)

    /** Total BGK/PFR bond issuance. This intentionally includes both the
      * commercial-bank leg and the separately addressable NBP absorption leg.
      */
    def quasiFiscalBondIssuance: PLN =
      amount(FlowMechanism.QuasiFiscalBondIssuance) + amount(FlowMechanism.QuasiFiscalNbpAbsorption)

    /** Total BGK/PFR bond amortization across bank and NBP holders. */
    def quasiFiscalBondAmortization: PLN =
      amount(FlowMechanism.QuasiFiscalBondAmortization) + amount(FlowMechanism.QuasiFiscalNbpBondAmortization)

    /** NBP's purchase share of BGK/PFR issuance. Callers that already use
      * `quasiFiscalBondIssuance` must not add this again.
      */
    def quasiFiscalNbpAbsorption: PLN =
      amount(FlowMechanism.QuasiFiscalNbpAbsorption)

    /** NBP holder leg of BGK/PFR bond amortization. Callers that already use
      * `quasiFiscalBondAmortization` must not add this again.
      */
    def quasiFiscalNbpBondAmortization: PLN =
      amount(FlowMechanism.QuasiFiscalNbpBondAmortization)

    def quasiFiscalLending: PLN =
      amount(FlowMechanism.QuasiFiscalLending)

    def quasiFiscalRepayment: PLN =
      amount(FlowMechanism.QuasiFiscalRepayment)

    def quasiFiscalDepositChange: PLN =
      signedAmount(FlowMechanism.QuasiFiscalLendingDeposit) +
        signedAmount(FlowMechanism.QuasiFiscalRepaymentDeposit)

  object ExecutedFlowEvidence:
    val CentralGovernmentSpendingMechanisms: Vector[MechanismId] =
      Vector(
        FlowMechanism.GovPurchases,
        FlowMechanism.GovDebtService,
        FlowMechanism.GovUnempBenefit,
        FlowMechanism.GovSocialTransfer,
        FlowMechanism.GovEuCofin,
        FlowMechanism.GovCapitalInvestment,
        FlowMechanism.JstGovSubvention,
      )

    val SocialFundGovSubventionMechanisms: Vector[MechanismId] =
      Vector(
        FlowMechanism.ZusGovSubvention,
        FlowMechanism.NfzGovSubvention,
        FlowMechanism.FpGovSubvention,
        FlowMechanism.PfronGovSubvention,
        FlowMechanism.FgspGovSubvention,
      )

    val JstRevenueMechanisms: Vector[MechanismId] =
      Vector(FlowMechanism.JstRevenue, FlowMechanism.JstGovSubvention)

    def from(batches: Vector[BatchedFlow]): ExecutedFlowEvidence =
      val (totals, signedTotals): (Map[MechanismId, Long], Map[MechanismId, Long]) =
        batches.foldLeft(
          Map.empty[MechanismId, Long].withDefaultValue(0L),
          Map.empty[MechanismId, Long].withDefaultValue(0L),
        ):
          case ((totalsAcc, signedAcc), batch) =>
            val amount       = RuntimeLedgerTopology.totalTransferred(batch)
            val signedAmount =
              batch.mechanism match
                case mechanism @ (FlowMechanism.BankInterbankInterest | FlowMechanism.BankStandingFacility) =>
                  (batch.from, batch.to) match
                    case (EntitySector.NBP, EntitySector.Banks) => amount
                    case (EntitySector.Banks, EntitySector.NBP) => -amount
                    case _                                      =>
                      throw unsupportedDirection(bankIncomeMechanismLabel(mechanism), batch)
                case FlowMechanism.InvestmentDepositSettlement                                              =>
                  (batch.from, batch.to) match
                    case (EntitySector.Banks, EntitySector.Firms) => amount
                    case (EntitySector.Firms, EntitySector.Banks) => -amount
                    case _                                        => throw unsupportedDirection("InvestmentDepositSettlement", batch)
                case FlowMechanism.TfiDepositDrain                                                          =>
                  (batch.from, batch.to) match
                    case (EntitySector.Banks, EntitySector.Households) => amount
                    case (EntitySector.Households, EntitySector.Banks) => -amount
                    case _                                             => throw unsupportedDirection("TfiDepositDrain", batch)
                case FlowMechanism.QuasiFiscalLendingDeposit                                                =>
                  (batch.from, batch.to) match
                    case (EntitySector.Banks, EntitySector.Firms) => amount
                    case _                                        => throw unsupportedDirection("QuasiFiscalLendingDeposit", batch)
                case FlowMechanism.QuasiFiscalRepaymentDeposit                                              =>
                  (batch.from, batch.to) match
                    case (EntitySector.Firms, EntitySector.Banks) => -amount
                    case _                                        => throw unsupportedDirection("QuasiFiscalRepaymentDeposit", batch)
                case _                                                                                      => amount

            (
              totalsAcc.updated(batch.mechanism, totalsAcc(batch.mechanism) + amount),
              signedAcc.updated(batch.mechanism, signedAcc(batch.mechanism) + signedAmount),
            )

      ExecutedFlowEvidence(totals, signedTotals)

    private def bankIncomeMechanismLabel(mechanism: MechanismId): String =
      if mechanism == FlowMechanism.BankInterbankInterest then "FlowMechanism.BankInterbankInterest"
      else "FlowMechanism.BankStandingFacility"

    private def unsupportedDirection(mechanismLabel: String, batch: BatchedFlow): EngineFailure =
      EngineFailure.unsupportedTopology(
        "SfcSemanticProjection.ExecutedFlowEvidence.from",
        s"$mechanismLabel batch has unsupported direction ${batch.from}->${batch.to}",
      )
