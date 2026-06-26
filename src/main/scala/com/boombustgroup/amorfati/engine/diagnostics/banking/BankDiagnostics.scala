package com.boombustgroup.amorfati.engine.diagnostics.banking

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.types.*

/** Monthly banking-sector capital attribution used by Monte Carlo diagnostics.
  *
  * Amounts are aggregate sector PLN. Loss components are positive when they
  * reduce bank capital; retained income is positive when it increases capital.
  * Deposit bail-in is carried here as a resolution-adjacent diagnostic, but it
  * is not part of the bank-capital identity because it haircuts deposits rather
  * than equity capital.
  */
case class BankCapitalDiagnostics(
    openingCapital: PLN = PLN.Zero,           // aggregate bank capital at the start of the month
    closingCapital: PLN = PLN.Zero,           // aggregate bank capital after monthly banking settlement
    retainedIncome: PLN = PLN.Zero,           // retained ordinary bank income after profit-retention rule
    firmNplLoss: PLN = PLN.Zero,              // realized firm-loan capital loss net of recovery and ECL allowance draw
    mortgageNplLoss: PLN = PLN.Zero,          // realized mortgage capital loss net of recovery; product-level ECL draw is not modeled yet
    consumerNplLoss: PLN = PLN.Zero,          // realized consumer-loan capital loss net of recovery; product-level ECL draw is not modeled yet
    corpBondDefaultLoss: PLN = PLN.Zero,      // bank-held corporate-bond default loss
    firmNplAllowanceDraw: PLN = PLN.Zero,     // firm-loan default loss covered by existing/new ECL allowance instead of capital loss
    mortgageNplAllowanceDraw: PLN = PLN.Zero, // currently zero until mortgage product-level ECL staging is modeled
    consumerNplAllowanceDraw: PLN = PLN.Zero, // currently zero until consumer product-level ECL staging is modeled
    bfgLevy: PLN = PLN.Zero,                  // monthly BFG levy paid by active banks
    polishBankLevyTax: PLN = PLN.Zero,        // monthly Polish bank tax paid by active banks
    unrealizedBondLoss: PLN = PLN.Zero,       // AFS government-bond mark-to-market capital hit
    htmRealizedLoss: PLN = PLN.Zero,          // HTM forced-reclassification realized loss
    eclProvisionChange: PLN = PLN.Zero,       // IFRS 9 provision increase, positive when capital is hit
    capitalDestruction: PLN = PLN.Zero,       // shareholder capital wiped when banks newly fail
    interbankContagionLoss: PLN = PLN.Zero,   // failed-counterparty interbank exposure loss
    reconciliationResidual: PLN = PLN.Zero,   // aggregate exactness patch distributed across bank rows
    depositBailInLoss: PLN = PLN.Zero,        // depositor haircut from resolution, not equity-capital P&L
    newFailures: Int = 0,                     // banks newly marked failed during the month
):
  def delta: PLN = closingCapital - openingCapital

  def realizedCreditLoss: PLN =
    firmNplLoss + mortgageNplLoss + consumerNplLoss + corpBondDefaultLoss

  def creditLossAllowanceDraw: PLN =
    firmNplAllowanceDraw + mortgageNplAllowanceDraw + consumerNplAllowanceDraw

  def expectedDelta: PLN =
    retainedIncome - realizedCreditLoss - bfgLevy - polishBankLevyTax - unrealizedBondLoss -
      htmRealizedLoss - eclProvisionChange - interbankContagionLoss - capitalDestruction

  /** Unexplained capital delta after ordinary waterfall terms and the aggregate
    * exactness patch. Values away from zero indicate a missing diagnostic term.
    */
  def waterfallResidual: PLN =
    delta - expectedDelta - reconciliationResidual

object BankCapitalDiagnostics:
  val zero: BankCapitalDiagnostics = BankCapitalDiagnostics()

/** Monthly bank-failure trigger diagnostics.
  *
  * Reason-code mapping for `firstNewReasonCode`: 0 = none, 1 = negative
  * capital, 2 = CAR breach, 3 = LCR/liquidity breach, 4 = all-failed resolution
  * fallback (stable legacy diagnostic; current all-failed semantics fail fast),
  * 5 = invariant mismatch.
  */
case class BankFailureDiagnostics(
    newNegativeCapital: Int = 0, // newly failed banks whose primary trigger was negative capital
    newCarBreach: Int = 0,       // newly failed banks whose primary trigger was the consecutive low-CAR rule
    newLiquidityBreach: Int = 0, // newly failed banks whose primary trigger was the LCR liquidity rule
    allFailedFallback: Int = 0,  // stable legacy column; should stay 0 because all-failed resolution now fails fast
    invariantViolation: Int = 0, // failure-event accounting mismatch, should remain zero
    firstNewReasonCode: Int = 0, // reason code for first new failure event in bank-id order, or 0 when none
    firstNewBankId: Int = -1,    // bank id for first new failure event, or -1 when none
)

object BankFailureDiagnostics:
  val zero: BankFailureDiagnostics = BankFailureDiagnostics()

  def fromEvents(events: Vector[Banking.FailureEvent], allFailedFallbackUsed: Boolean, invariantViolation: Int): BankFailureDiagnostics =
    val firstEvent = events.headOption
    BankFailureDiagnostics(
      newNegativeCapital = events.count(_.reason == Banking.BankFailureReason.NegativeCapital),
      newCarBreach = events.count(_.reason == Banking.BankFailureReason.CarBreach),
      newLiquidityBreach = events.count(_.reason == Banking.BankFailureReason.LiquidityBreach),
      allFailedFallback = if allFailedFallbackUsed then 1 else 0,
      invariantViolation = invariantViolation,
      firstNewReasonCode = firstEvent.map(_.reason.code).getOrElse(if allFailedFallbackUsed then Banking.BankFailureReason.AllFailedFallback.code else 0),
      firstNewBankId = firstEvent.map(_.bankId.toInt).getOrElse(-1),
    )

/** Monthly bank-resolution diagnostics for Monte Carlo timeseries.
  *
  * These fields are intentionally count-oriented and sit beside the richer
  * `BankFailure_*`, `BankCapital_*`, and `BankReconciliation_*` blocks so a run
  * can distinguish credit-demand weakness from bank-supply collapse.
  */
case class BankResolutionDiagnostics(
    activeBanks: Int = 0,                   // active bank rows after monthly failure/resolution
    failedBanks: Int = 0,                   // failed bank rows after monthly failure/resolution
    newFailures: Int = 0,                   // banks newly marked failed during the month
    bailInEvents: Int = 0,                  // distinct newly failed bank ids eligible for event-based bail-in
    resolvedBanks: Int = 0,                 // failed bank rows whose balance sheets were transferred by P&A
    allFailedFallback: Int = 0,             // stable legacy flag; current semantics fail fast before fallback
    bridgeRecapitalization: PLN = PLN.Zero, // explicit bridge/nationalization recap amount; zero until modeled
    invalidActiveBankInvariant: Int = 0,    // 1 when an active bank ends the month with negative capital
)

object BankResolutionDiagnostics:
  val zero: BankResolutionDiagnostics = BankResolutionDiagnostics()

  def fromState(
      banks: Vector[Banking.BankState],
      newFailures: Int,
      bailInEvents: Int,
      resolvedBanks: Int,
      allFailedFallbackUsed: Boolean,
      bridgeRecapitalization: PLN = PLN.Zero,
  ): BankResolutionDiagnostics =
    val activeBanks   = banks.count(bank => !bank.failed)
    val failedBanks   = banks.size - activeBanks
    val invalidActive = banks.exists(bank => !bank.failed && bank.capital < PLN.Zero)
    BankResolutionDiagnostics(
      activeBanks = activeBanks,
      failedBanks = failedBanks,
      newFailures = newFailures,
      bailInEvents = bailInEvents,
      resolvedBanks = resolvedBanks,
      allFailedFallback = if allFailedFallbackUsed then 1 else 0,
      bridgeRecapitalization = bridgeRecapitalization,
      invalidActiveBankInvariant = if invalidActive then 1 else 0,
    )

/** Diagnostics for the aggregate-exactness patch distributed across bank rows.
  *
  * `capitalResidual` is the amount allocated to `targetBankId`: positive adds
  * capital to that row, negative removes it. The sector-level residual remains
  * available through `BankCapitalDiagnostics.reconciliationResidual`.
  */
case class BankReconciliationDiagnostics(
    targetBankId: Int = -1,                        // most impacted bank id, or -1 when no patch was applied
    capitalResidual: PLN = PLN.Zero,               // target-bank capital exactness patch allocation
    targetCapitalBefore: PLN = PLN.Zero,           // most-impacted-bank capital before the patch
    targetCapitalAfter: PLN = PLN.Zero,            // most-impacted-bank capital after the patch
    targetCarBefore: Multiplier = Multiplier.Zero, // most-impacted-bank CAR before the patch
    targetCarAfter: Multiplier = Multiplier.Zero,  // most-impacted-bank CAR after the patch
    residualToTargetCapital: Scalar = Scalar.Zero, // |target allocation| / max(|targetCapitalBefore|, PLN 1)
    materialResidual: Int = 0,                     // 1 when residualToTargetCapital is at least 1 bp
    crossedFailureThreshold: Int = 0,              // 1 when the patch alone moves any bank into a failure trigger
    postResidualReasonCode: Int = 0,               // most-impacted-bank failure reason after the patch, or 0 when none
)

object BankReconciliationDiagnostics:
  private val MaterialResidualRatio: Scalar = Scalar.decimal(1, 4)

  val zero: BankReconciliationDiagnostics = BankReconciliationDiagnostics()

  def fromPatch(
      targetBankId: BankId,
      capitalResidual: PLN,
      targetCapitalBefore: PLN,
      targetCapitalAfter: PLN,
      targetCarBefore: Multiplier,
      targetCarAfter: Multiplier,
      reasonBefore: Option[Banking.BankFailureReason],
      reasonAfter: Option[Banking.BankFailureReason],
  ): BankReconciliationDiagnostics =
    val ratio    = capitalResidual.abs.ratioTo(targetCapitalBefore.abs.max(PLN(1)))
    val crossed  = reasonBefore.isEmpty && reasonAfter.nonEmpty
    val material = ratio >= MaterialResidualRatio
    BankReconciliationDiagnostics(
      targetBankId = targetBankId.toInt,
      capitalResidual = capitalResidual,
      targetCapitalBefore = targetCapitalBefore,
      targetCapitalAfter = targetCapitalAfter,
      targetCarBefore = targetCarBefore,
      targetCarAfter = targetCarAfter,
      residualToTargetCapital = ratio,
      materialResidual = if material then 1 else 0,
      crossedFailureThreshold = if crossed then 1 else 0,
      postResidualReasonCode = reasonAfter.map(_.code).getOrElse(0),
    )

  def fromDistributedPatch(
      targetBankId: BankId,
      targetCapitalAllocation: PLN,
      targetCapitalBefore: PLN,
      targetCapitalAfter: PLN,
      targetCarBefore: Multiplier,
      targetCarAfter: Multiplier,
      crossedFailureThreshold: Boolean,
      targetReasonAfter: Option[Banking.BankFailureReason],
  ): BankReconciliationDiagnostics =
    val ratio    = targetCapitalAllocation.abs.ratioTo(targetCapitalBefore.abs.max(PLN(1)))
    val material = ratio >= MaterialResidualRatio
    BankReconciliationDiagnostics(
      targetBankId = targetBankId.toInt,
      capitalResidual = targetCapitalAllocation,
      targetCapitalBefore = targetCapitalBefore,
      targetCapitalAfter = targetCapitalAfter,
      targetCarBefore = targetCarBefore,
      targetCarAfter = targetCarAfter,
      residualToTargetCapital = ratio,
      materialResidual = if material then 1 else 0,
      crossedFailureThreshold = if crossedFailureThreshold then 1 else 0,
      postResidualReasonCode = targetReasonAfter.map(_.code).getOrElse(0),
    )

/** Monthly IFRS 9 / ECL provisioning diagnostics.
  *
  * Allowances are accounting provisions implied by the Stage 1/2/3 staging
  * stock. `excessAllowance` is the amount above an all-performing Stage-1
  * baseline.
  */
case class BankEclDiagnostics(
    openingAllowance: PLN = PLN.Zero,                // ECL allowance implied by opening bank staging
    closingAllowance: PLN = PLN.Zero,                // ECL allowance implied by closing bank staging
    baselineStage1Allowance: PLN = PLN.Zero,         // closing staged ECL book if all stayed at Stage 1
    excessAllowance: PLN = PLN.Zero,                 // closing allowance above the all-Stage-1 baseline
    migrationRate: Share = Share.Zero,               // macro-driven Stage 1 to Stage 2 migration rate used this month
    gdpGrowthMonthly: Coefficient = Coefficient.Zero, // month-on-month GDP growth used by ECL staging
)

object BankEclDiagnostics:
  val zero: BankEclDiagnostics = BankEclDiagnostics()
