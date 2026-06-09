package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.agents.{Banking, BankruptReason, Region, TechState}
import com.boombustgroup.amorfati.types.*

/** Labor-planning diagnostic snapshot for one firm before monthly execution. */
final case class FirmHiringDiagnostics(
    workers: Int,
    desiredWorkers: Int,
    feasibleWorkers: Int,
    desiredGap: Int,
    feasibleGap: Int,
    hiringThreshold: Int,
    firingThreshold: Int,
    shouldAdjust: Boolean,
    proposedAdjustment: Int,
    proposedWorkers: Int,
    signalMonths: Int,
    requiredSignalMonths: Int,
)

/** Ledger-contracted firm financial stocks passed into firm execution. */
case class FirmFinancialStocks(
    cash: PLN,
    firmLoan: PLN,
    equity: PLN,
)
object FirmFinancialStocks:
  val zero: FirmFinancialStocks = FirmFinancialStocks(PLN.Zero, PLN.Zero, PLN.Zero)

/** Operational state of a single firm, carried across simulation months. */
case class FirmState(
    id: FirmId,
    tech: TechState,
    riskProfile: Share,
    innovationCostFactor: Multiplier,
    digitalReadiness: Share,
    sector: SectorIdx,
    neighbors: Vector[FirmId],
    bankId: BankId,
    initialSize: Int,
    capitalStock: PLN,
    foreignOwned: Boolean,
    stateOwned: Boolean = false,
    inventory: PLN,
    greenCapital: PLN,
    accumulatedLoss: PLN,
    markup: Multiplier = Multiplier.One,
    region: Region = Region.Central,
    startupMonthsLeft: Int = 0,
    startupTargetWorkers: Int = 0,
    startupFilledWorkers: Int = 0,
    hiringSignalMonths: Int = 0,
)

/** Output of firm processing for one firm in one month. */
case class FirmResult(
    firm: FirmState,
    financialStocks: FirmFinancialStocks,
    taxPaid: PLN,
    realizedPostTaxProfit: PLN,
    signedRealizedPostTaxProfit: PLN,
    capexSpent: PLN,
    techImports: PLN,
    newLoan: PLN,
    techNewLoan: PLN,
    equityIssuance: PLN,
    grossInvestment: PLN,
    bondIssuance: PLN,
    profitShiftCost: PLN,
    fdiRepatriation: PLN,
    inventoryChange: PLN,
    citEvasion: PLN,
    energyCost: PLN,
    greenInvestment: PLN,
    principalRepaid: PLN,
    investmentCreditDemand: PLN = PLN.Zero,
    investmentCreditApproved: PLN = PLN.Zero,
    investmentCreditRejected: PLN = PLN.Zero,
    techCreditDemand: PLN = PLN.Zero,
    techCreditApproved: PLN = PLN.Zero,
    techCreditRejected: PLN = PLN.Zero,
    techSelectedCreditDemand: PLN = PLN.Zero,
    techSelectedCreditApproved: PLN = PLN.Zero,
    techSelectedCreditRejected: PLN = PLN.Zero,
    techCandidateCreditDemand: PLN = PLN.Zero,
    techCandidateCreditApproved: PLN = PLN.Zero,
    techCandidateCreditRejected: PLN = PLN.Zero,
    investmentCreditRejectionBreakdown: FirmCreditRejectionBreakdown = FirmCreditRejectionBreakdown.zero,
    techCreditRejectionBreakdown: FirmCreditRejectionBreakdown = FirmCreditRejectionBreakdown.zero,
    decisionTrace: Option[FirmDecisionTrace] = None,
)
object FirmResult:
  /** Convenience factory for tests, with all flow fields set to `PLN.Zero`. */
  def zero(firm: FirmState, financialStocks: FirmFinancialStocks = FirmFinancialStocks.zero): FirmResult =
    FirmResult(
      firm = firm,
      financialStocks = financialStocks,
      taxPaid = PLN.Zero,
      realizedPostTaxProfit = PLN.Zero,
      signedRealizedPostTaxProfit = PLN.Zero,
      capexSpent = PLN.Zero,
      techImports = PLN.Zero,
      newLoan = PLN.Zero,
      techNewLoan = PLN.Zero,
      equityIssuance = PLN.Zero,
      grossInvestment = PLN.Zero,
      bondIssuance = PLN.Zero,
      profitShiftCost = PLN.Zero,
      fdiRepatriation = PLN.Zero,
      inventoryChange = PLN.Zero,
      citEvasion = PLN.Zero,
      energyCost = PLN.Zero,
      greenInvestment = PLN.Zero,
      principalRepaid = PLN.Zero,
    )

/** Auditable per-firm decision record. */
case class FirmDecisionTrace(
    firmId: FirmId,
    openingTech: TechState,
    closingTech: TechState,
    decisionType: FirmDecisionTrace.DecisionType,
    bankruptcyReason: Option[BankruptReason],
    sector: SectorIdx,
    foreignOwned: Boolean,
    stateOwned: Boolean,
    cashBefore: PLN,
    cashAfter: PLN,
    firmLoanBefore: PLN,
    firmLoanAfter: PLN,
    realizedPostTaxProfit: PLN,
    signedRealizedPostTaxProfit: PLN,
    grossInvestment: PLN,
    principalRepaid: PLN,
    digitalReadinessBefore: Share,
    digitalReadinessAfter: Share,
    workersBefore: Int,
    workersAfter: Int,
    capex: PLN,
    newLoan: PLN,
    techCreditDecisionType: Option[FirmDecisionTrace.DecisionType],
    techCreditSource: Option[FirmDecisionTrace.TechCreditSource],
    techCreditNeed: PLN,
    techCreditAmount: PLN,
    downPayment: Option[PLN],
    bankId: BankId,
    lendingRate: Rate,
    selectedBankApproval: Option[Boolean],
    selectedBankApprovalProbability: Option[Share],
    selectedBankApprovalRoll: Option[Share],
    selectedBankApprovalAudit: Banking.CreditApprovalAudit,
    fullAiFeasible: Option[Boolean],
    hybridFeasible: Option[Boolean],
    fullAiAdoptionProbability: Option[Share],
    hybridAdoptionProbability: Option[Share],
    adoptionRoll: Option[Share],
    fullAiBankApproval: Option[Boolean],
    fullAiBankApprovalProbability: Option[Share],
    fullAiBankApprovalRoll: Option[Share],
    fullAiBankApprovalAudit: Banking.CreditApprovalAudit,
    hybridBankApproval: Option[Boolean],
    hybridBankApprovalProbability: Option[Share],
    hybridBankApprovalRoll: Option[Share],
    hybridBankApprovalAudit: Banking.CreditApprovalAudit,
    implementationFailureProbability: Option[Share],
    implementationRoll: Option[Share],
    upgradeEfficiencyDraw: Option[Scalar],
    upgradeEfficiencyMultiplier: Option[Multiplier],
    investmentCreditNeed: Option[PLN],
    investmentCreditAmount: Option[PLN],
    investmentBankApproval: Option[Boolean],
    investmentBankApprovalProbability: Option[Share],
    investmentBankApprovalRoll: Option[Share],
    investmentBankApprovalAudit: Banking.CreditApprovalAudit,
    digitalInvestProbability: Option[Share],
    digitalInvestRoll: Option[Share],
    laborAdjustmentResidualProbability: Option[Share],
    laborAdjustmentResidualRoll: Option[Share],
)
object FirmDecisionTrace:
  enum DecisionType(val csvValue: String):
    case Survive       extends DecisionType("survive")
    case Upsize        extends DecisionType("upsize")
    case Downsize      extends DecisionType("downsize")
    case DigiInvest    extends DecisionType("digi-invest")
    case FullAiUpgrade extends DecisionType("full-AI-upgrade")
    case HybridUpgrade extends DecisionType("hybrid-upgrade")
    case UpgradeFailed extends DecisionType("upgrade-failed")
    case Bankrupt      extends DecisionType("bankrupt")

  enum TechCreditSource(val csvValue: String):
    case SelectedUpgrade       extends TechCreditSource("selected-upgrade")
    case BankRejectedCandidate extends TechCreditSource("bank-rejected-candidate")

/** Monthly profit-and-loss breakdown. */
case class FirmPnL(
    revenue: PLN,
    costs: PLN,
    tax: PLN,
    netAfterTax: PLN,
    profitShiftCost: PLN,
    energyCost: PLN,
    newAccumulatedLoss: PLN,
)
object FirmPnL:
  val zero: FirmPnL = FirmPnL(PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero)

/** Decision selected by the stochastic decision engine before execution. */
sealed trait FirmDecision
object FirmDecision:
  case object StayBankrupt                                                                                                      extends FirmDecision
  case class Survive(pnl: FirmPnL, newCash: PLN, drUpdate: Option[Share] = None)                                                extends FirmDecision
  case class GoBankrupt(pnl: FirmPnL, cash: PLN, reason: BankruptReason)                                                        extends FirmDecision
  case class Upgrade(pnl: FirmPnL, newTech: TechState, capex: PLN, loan: PLN, downPayment: PLN, drUpdate: Option[Share] = None) extends FirmDecision
  case class UpgradeFailed(pnl: FirmPnL, reason: BankruptReason, capex: PLN, loan: PLN, down: PLN)                              extends FirmDecision
  case class Downsize(pnl: FirmPnL, newWorkers: Int, adjustedCash: PLN, newTech: TechState, drUpdate: Option[Share] = None)     extends FirmDecision
  case class Upsize(pnl: FirmPnL, newWorkers: Int, newCash: PLN, newTech: TechState, drUpdate: Option[Share] = None)            extends FirmDecision
  case class DigiInvest(pnl: FirmPnL, cost: PLN, newDR: Share)                                                                  extends FirmDecision

/** Bank-credit decision attached to a firm request, including audit metadata
  * when the lending bank exposes it.
  */
case class FirmCreditDecision(
    approved: Boolean,
    approvalProbability: Option[Share] = None,
    approvalRoll: Option[Share] = None,
    audit: Banking.CreditApprovalAudit = Banking.CreditApprovalAudit.empty,
)
object FirmCreditDecision:
  def fromBoolean(approved: Boolean): FirmCreditDecision =
    FirmCreditDecision(approved)

  def fromApproval(approval: Banking.CreditApproval): FirmCreditDecision =
    FirmCreditDecision(
      approved = approval.approved,
      approvalProbability = approval.approvalProbability,
      approvalRoll = approval.approvalRoll,
      audit = approval.audit,
    )

/** Attribution of rejected firm credit by banking gate. */
case class FirmCreditRejectionBreakdown(
    failedBank: PLN = PLN.Zero,
    carGate: PLN = PLN.Zero,
    lcrGate: PLN = PLN.Zero,
    nsfrGate: PLN = PLN.Zero,
    unclassified: PLN = PLN.Zero,
):
  def +(other: FirmCreditRejectionBreakdown): FirmCreditRejectionBreakdown =
    FirmCreditRejectionBreakdown(
      failedBank = failedBank + other.failedBank,
      carGate = carGate + other.carGate,
      lcrGate = lcrGate + other.lcrGate,
      nsfrGate = nsfrGate + other.nsfrGate,
      unclassified = unclassified + other.unclassified,
    )

object FirmCreditRejectionBreakdown:
  val zero: FirmCreditRejectionBreakdown = FirmCreditRejectionBreakdown()

  def from(reason: Option[Banking.CreditRejectionReason], amount: PLN): FirmCreditRejectionBreakdown =
    if amount <= PLN.Zero then zero
    else
      reason match
        case Some(Banking.CreditRejectionReason.FailedBank)        => FirmCreditRejectionBreakdown(failedBank = amount)
        case Some(Banking.CreditRejectionReason.CapitalAdequacy)   => FirmCreditRejectionBreakdown(carGate = amount)
        case Some(Banking.CreditRejectionReason.LiquidityCoverage) => FirmCreditRejectionBreakdown(lcrGate = amount)
        case Some(Banking.CreditRejectionReason.StableFunding)     => FirmCreditRejectionBreakdown(nsfrGate = amount)
        case None                                                  => FirmCreditRejectionBreakdown(unclassified = amount)
