package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.{BankRates, Banking, HhFinancialDistressState, HhStatus, Household}
import com.boombustgroup.amorfati.agents.household.HouseholdParameters.*
import com.boombustgroup.amorfati.agents.household.HouseholdStepTypes.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.World
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Consumer-credit underwriting, bank approval, and residual shortfall credit
  * demand.
  */
private[agents] object HouseholdConsumerCredit:

  /** Consumer-credit supply result after borrower eligibility and bank-side
    * approval have both been evaluated.
    */
  private final case class BankSupplyResult(
      approved: PLN,
      rejected: PLN,
      bankRejected: PLN,
      approval: Option[Banking.CreditApproval],
  )

  /** Computes principal capacity from affordable monthly payment headroom. */
  private def consumerLoanCapacity(monthlyPaymentHeadroom: PLN, paymentFactor: Rate): PLN =
    if monthlyPaymentHeadroom <= PLN.Zero || paymentFactor <= Rate.Zero then PLN.Zero
    else monthlyPaymentHeadroom / paymentFactor.toMultiplier

  /** Returns whether new unsecured credit is blocked by the household distress
    * state.
    */
  def blockedByDistress(state: HhFinancialDistressState): Boolean =
    state match
      case HhFinancialDistressState.Arrears | HhFinancialDistressState.Restructuring | HhFinancialDistressState.Defaulted |
          HhFinancialDistressState.Bankruptcy =>
        true
      case HhFinancialDistressState.Current | HhFinancialDistressState.LiquidityStress =>
        false

  /** Applies bank-side product-aware supply approval after household credit
    * eligibility has produced a positive demand.
    */
  private def applyBankCreditSupply(
      hh: Household.State,
      demand: PLN,
      householdEligible: Boolean,
      rng: RandomStream,
      bankCreditSupply: Option[Household.BankCreditSupply],
  ): BankSupplyResult =
    if demand <= PLN.Zero then BankSupplyResult(PLN.Zero, PLN.Zero, PLN.Zero, None)
    else if !householdEligible then BankSupplyResult(PLN.Zero, demand, PLN.Zero, None)
    else
      bankCreditSupply match
        case None         => BankSupplyResult(demand, PLN.Zero, PLN.Zero, None)
        case Some(supply) =>
          val approval = supply.approve(hh.bankId, Banking.CreditProduct.ConsumerLoan, demand, rng)
          require(
            approval.product == Banking.CreditProduct.ConsumerLoan,
            s"Household consumer-credit supply returned ${approval.product} approval for ConsumerLoan request",
          )
          if approval.approved then BankSupplyResult(demand, PLN.Zero, PLN.Zero, Some(approval))
          else BankSupplyResult(PLN.Zero, demand, demand, Some(approval))

  /** Computes ordinary consumer-credit debt service and possible new
    * underwritten origination.
    */
  def processConsumerCredit(
      hh: Household.State,
      financialStocks: Household.FinancialStocks,
      income: PLN,
      disposable: PLN,
      debtService: PLN,
      world: World,
      bankRates: Option[BankRates],
      rng: RandomStream,
      bankCreditSupply: Option[Household.BankCreditSupply],
  )(using p: SimParams): CreditResult =
    val consumerRate: Rate = bankRates match
      case Some(br) => br.lendingRates(hh.bankId.toInt) + p.household.ccSpread
      case None     => world.nbp.referenceRate + p.household.ccSpread
    val consumerPrin       = financialStocks.consumerLoan * p.household.ccAmortRate
    val consumerInterest   = financialStocks.consumerLoan * consumerRate.monthly
    val consumerDebtSvc    = consumerPrin + consumerInterest
    val paymentFactor      = p.household.ccAmortRate + consumerRate.monthly

    val (creditDemand, rejectedCreditDemand, bankRejectedCreditDemand, newConsumerLoan, creditCapacity, creditAccessEligible, bankApproval) = hh.status match
      case HhStatus.Employed(_, _, wage)                                             =>
        val stressed = disposable < wage * DisposableWageThreshold
        val eligible = stressed && !blockedByDistress(hh.financialDistressState) && p.household.ccEligRate.sampleBelow(rng)
        if !stressed then (PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, false, None)
        else
          val totalDbtSvc       = debtService + consumerDebtSvc
          val existingDti       = if income > PLN.Zero then (totalDbtSvc / income).toShare else Share.One
          val headroomShare     = (p.household.ccMaxDti - existingDti).max(Share.Zero)
          val paymentHeadroom   = income * headroomShare
          val principalCapacity = consumerLoanCapacity(paymentHeadroom, paymentFactor).min(p.household.ccMaxLoan)
          val stressGap         = (wage * DisposableWageThreshold - disposable).max(PLN.Zero)
          val essentialGap      = (p.household.basicConsumptionFloor + consumerDebtSvc - disposable).max(PLN.Zero)
          val desired           = stressGap.max(essentialGap).min(principalCapacity)
          val demand            = if desired > MinConsumerLoanSize then desired else PLN.Zero
          val bankSupply        = applyBankCreditSupply(hh, demand, eligible, rng, bankCreditSupply)
          (demand, bankSupply.rejected, bankSupply.bankRejected, bankSupply.approved, principalCapacity, eligible, bankSupply.approval)
      case HhStatus.Unemployed(_) | HhStatus.Retraining(_, _, _) | HhStatus.Bankrupt =>
        (PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, false, None)

    val updatedDebt = (financialStocks.consumerLoan + newConsumerLoan - consumerPrin).max(PLN.Zero)

    CreditResult(
      debtService = consumerDebtSvc,
      principal = consumerPrin,
      interest = consumerInterest,
      newLoan = newConsumerLoan,
      creditDemand = creditDemand,
      rejectedCreditDemand = rejectedCreditDemand,
      bankRejectedCreditDemand = bankRejectedCreditDemand,
      creditCapacity = creditCapacity,
      creditAccessEligible = creditAccessEligible,
      bankApproval = bankApproval,
      liquidityShortfall = Household.LiquidityShortfallComponents.Zero,
      liquidityBridgeChargeOff = PLN.Zero,
      defaultAmt = PLN.Zero,
      updatedDebt = updatedDebt,
    )

  /** Attempts to underwrite a remaining residual liquidity shortfall using any
    * unused consumer-credit capacity.
    */
  def underwriteResidualShortfall(f: MonthlyFlows, rng: RandomStream, bankCreditSupply: Option[Household.BankCreditSupply]): MonthlyFlows =
    val shortfall         = (PLN.Zero - f.newSavings).max(PLN.Zero)
    val remainingCapacity = (f.credit.creditCapacity - f.credit.newLoan).max(PLN.Zero)
    val desired           = shortfall.min(remainingCapacity)
    val demand            = if desired > MinConsumerLoanSize then desired else PLN.Zero
    if demand <= PLN.Zero then f
    else
      val bankSupply = applyBankCreditSupply(f.hh, demand, f.credit.creditAccessEligible, rng, bankCreditSupply)
      f.copy(
        credit = f.credit.copy(
          newLoan = f.credit.newLoan + bankSupply.approved,
          creditDemand = f.credit.creditDemand + demand,
          rejectedCreditDemand = f.credit.rejectedCreditDemand + bankSupply.rejected,
          bankRejectedCreditDemand = f.credit.bankRejectedCreditDemand + bankSupply.bankRejected,
          bankApproval = bankSupply.approval.orElse(f.credit.bankApproval),
          updatedDebt = f.credit.updatedDebt + bankSupply.approved,
        ),
        newSavings = f.newSavings + bankSupply.approved,
      )
