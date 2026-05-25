package com.boombustgroup.amorfati.agents

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.{OperationalSignals, World}
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.types.*

import com.boombustgroup.amorfati.random.RandomStream

// ---- Domain types ----

/** Reason a firm exited the simulation — carried in `TechState.Bankrupt` and
  * `Decision.UpgradeFailed`.
  */
sealed trait BankruptReason
object BankruptReason:
  case object AiDebtTrap          extends BankruptReason
  case object HybridInsolvency    extends BankruptReason
  case object AiImplFailure       extends BankruptReason
  case object HybridImplFailure   extends BankruptReason
  case object LaborCostInsolvency extends BankruptReason
  case class Other(msg: String)   extends BankruptReason

/** Technology regime of a firm. Determines worker count, capacity, and cost
  * structure.
  */
sealed trait TechState
object TechState:
  case class Traditional(workers: Int)                      extends TechState
  case class Hybrid(workers: Int, aiEfficiency: Multiplier) extends TechState
  case class Automated(efficiency: Multiplier)              extends TechState
  case class Bankrupt(reason: BankruptReason)               extends TechState

/** Firm agent: stateless functions operating on `State`. Entry point:
  * `process`.
  */
object Firm:

  final case class HiringDiagnostics(
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

  // ---- Calibration constants ----
  // Named here rather than inline. Candidates for SimParams if they need
  // to vary across experiments.

  // Financing splits: fraction of CAPEX financed by loan vs down payment
  private val FullAiLoanShare: Share      = Share.decimal(85, 2) // full-AI: 85% loan, 15% down
  private val FullAiDownShare: Share      = Share.decimal(15, 2)
  private val HybridLoanShare: Share      = Share.decimal(80, 2) // hybrid: 80% loan, 20% down
  private val HybridDownShare: Share      = Share.decimal(20, 2)
  private val HybridToFullCapexMul: Share = Share.decimal(6, 1)  // hybrid→full-AI upgrade costs 60% of greenfield

  // Profitability thresholds: upgrade must be this much cheaper than status quo
  private val FullAiProfitMargin: Multiplier = Multiplier.decimal(11, 1)  // full-AI must save ≥10%
  private val HybridProfitMargin: Multiplier = Multiplier.decimal(105, 2) // hybrid must save ≥5%

  // Adoption probability weights
  private val RiskWeightFullAi: Share     = Share.decimal(1, 1)  // risk profile weight in full-AI adoption
  private val RiskWeightHybrid: Share     = Share.decimal(4, 2)  // risk profile weight in hybrid adoption
  private val RiskWeightHybUpgrade: Share = Share.decimal(15, 2) // risk profile weight when hybrid→full-AI
  private val AutoRatioWeight: Share      = Share.decimal(3, 1)  // automation ratio weight when hybrid→full-AI
  private val LocalPanicWeight: Share     = Share.decimal(4, 1)  // local neighbor automation pressure
  private val GlobalPanicWeight: Share    = Share.decimal(4, 1)  // global automation pressure
  private val HybridPanicDiscount: Share  = Share.decimal(5, 1)  // hybrid counts 50% in global panic
  private val DesperationBonus: Share     = Share.decimal(2, 1)  // bonus prob when firm is loss-making
  private val StrategicAdoptBase: Share   = Share.decimal(5, 3)  // strategic adoption when AI not yet profitable

  // Baseline willingness ramp for automation adoption.
  private val UncertaintyBase: Share  = Share.decimal(8, 2) // initial willingness multiplier
  private val UncertaintySlope: Share = Share.decimal(7, 2) // additional willingness after ramp saturation

  // Implementation failure rates
  private val FullAiBaseFailRate: Share   = Share.decimal(5, 2)  // base failure prob for full-AI upgrade
  private val FullAiFailDrSens: Share     = Share.decimal(10, 2) // additional failure from low digital readiness
  private val HybridBaseFailRate: Share   = Share.decimal(3, 2)  // base failure prob for hybrid upgrade
  private val HybridFailDrSens: Share     = Share.decimal(7, 2)  // additional failure from low digital readiness
  private val CatastrophicFailFrac: Share = Share.decimal(4, 1)  // fraction of failures that are catastrophic (vs partial)

  // Partial cost on failed upgrade: firm absorbs fraction of planned CAPEX/loan/down
  private val FailCapexFrac: Share = Share.decimal(5, 1)
  private val FailLoanFrac: Share  = Share.decimal(3, 1)
  private val FailDownFrac: Share  = Share.decimal(5, 1)

  // Efficiency draw ranges on successful upgrade
  private val HybToFullEffMin: Scalar      = Scalar.decimal(2, 1)      // hybrid→full-AI efficiency range
  private val HybToFullEffMax: Scalar      = Scalar.decimal(6, 1)
  private val TradToFullEffMin: Scalar     = Scalar.decimal(5, 2)      // traditional→full-AI efficiency range
  private val TradToFullEffMax: Scalar     = Scalar.decimal(6, 1)
  private val BadHybridEffBase: Multiplier = Multiplier.decimal(85, 2) // partial-failure hybrid efficiency base
  private val BadHybridEffRange: Scalar    = Scalar.decimal(20, 2)     // partial-failure hybrid efficiency range
  private val GoodHybridEffBase: Scalar    = Scalar.decimal(5, 2)      // good hybrid efficiency base boost
  private val GoodHybridEffRange: Scalar   = Scalar.decimal(15, 2)     // good hybrid efficiency range
  private val GoodHybridDrBlend: Share     = Share.decimal(5, 1)       // DR contribution weight in good hybrid efficiency

  // Labor adjustment: firm converges toward MR=MC optimal headcount
  private val FiringAdjustFrac: Share                    = Share.decimal(5, 2)       // slower firing captures employment protection and labor hoarding
  private val InvestmentSignalRampMonths: Int            = 24                        // expansion capex requires a persistent order-book signal
  private val WorkingCapitalGraceMonths: Multiplier      = Multiplier.decimal(15, 1) // tolerate short-lived cash gaps for otherwise profitable firms
  private val StartupRunwayCashShare: Share              = Share.decimal(35, 2)      // entrants may burn part of startup cash during the startup window
  private val StartupDownsizeSpeedMultiplier: Multiplier = Multiplier.decimal(5, 1)  // startups should cut headcount more cautiously than incumbents
  private val StartupRunwayMonths                        = 4                         // should match entrant startup ramp-up window
  private val StartupCostFloor: Multiplier               = Multiplier.decimal(50, 2) // entrants ramp operating overhead in gradually
  private val MicroFirmHiringThreshold                   = 1                         // micro firms should react to a +1 worker gap
  private val HiringSignalPersistenceMonths              = 2                         // non-micro firms require sustained demand before hiring
  private val SmallFirmDesiredAddCap                     = 1                         // firms up to 10 workers plan at most +1 worker per month
  private val MidFirmDesiredAddCap                       = 2                         // firms up to 25 workers plan at most +2 workers per month
  private val LargeFirmDesiredGrowthShare: Share         = Share.decimal(5, 2)       // larger firms expand plans gradually rather than jumping to MR=MC headcount
  private val HiringPressureBlend: Share                 = Share.decimal(35, 2)      // uncapped sector pressure is informative but should not dominate planning
  private val NegativeCashHiringPenalty: Share           = Share.decimal(5, 1)       // cash-negative but profitable firms scale back desired hiring materially

  // Capacity blend: hybrid production = labor share + AI share
  private val HybridLaborCapShare: Share = Share.decimal(4, 1)
  private val HybridAiCapShare: Share    = Share.decimal(6, 1)

  // Opex domestic/import split: 60% domestic (price-sensitive), 40% imported
  private val OpexDomesticShare: Share = Share.decimal(60, 2)
  private val OpexImportShare: Share   = Share.decimal(40, 2)

  // CAPEX/opex size scaling exponents
  private val CapexSizeExponent: Scalar = Scalar.decimal(6, 1) // CAPEX scales sublinearly (economies of scale)
  private val OpexSizeExponent: Scalar  = Scalar.decimal(5, 1) // opex/digi-invest scales sublinearly
  private val SkeletonCrewFrac: Share   = Share.decimal(2, 2)  // automated firm retains 2% of initial headcount

  // Digital readiness
  private val HybridMonthlyDrDrift: Share    = Share.decimal(5, 3) // hybrid firms gain DR passively each month
  private val DigiInvestCashMult: Multiplier = Multiplier(2)       // must have 2× digi-invest cost in cash to afford

  // sigma threshold formula (sigmaThreshold function)
  private val SigmaThreshBase: Multiplier  = Multiplier.decimal(88, 2)
  private val SigmaThreshScale: Multiplier = Multiplier.decimal(75, 3)

  // ---- Data types ----

  /** Ledger-contracted firm financial stocks passed into firm execution by the
    * ledger boundary.
    *
    * Corporate bonds stay in `LedgerFinancialState` because issuance,
    * absorption, and default settlement happen outside individual
    * `Firm.process`.
    */
  case class FinancialStocks(
      cash: PLN,     // cash or deposit-like liquidity owned by the firm
      firmLoan: PLN, // outstanding bank-loan principal owed by the firm
      equity: PLN,   // listed equity issued by the firm
  )
  object FinancialStocks:
    val zero: FinancialStocks = FinancialStocks(PLN.Zero, PLN.Zero, PLN.Zero)

  /** Operational state of a single firm, carried across simulation months.
    * Financial ownership lives in `LedgerFinancialState` and enters firm
    * execution explicitly as `FinancialStocks`.
    */
  case class State(
      id: FirmId,                          // Unique firm identifier (index into firms vector)
      tech: TechState,                     // Current technology regime
      riskProfile: Share,                  // Propensity to invest / adopt technology [0,1]
      innovationCostFactor: Multiplier,    // Firm-specific CAPEX multiplier (drawn at creation)
      digitalReadiness: Share,             // Digital readiness score [0,1], gates tech upgrades
      sector: SectorIdx,                   // Index into p.sectorDefs
      neighbors: Vector[FirmId],           // Network adjacency (firm IDs)
      bankId: BankId,                      // Multi-bank: index into the explicit bank vector
      initialSize: Int,                    // Firm size at creation (heterogeneous when FIRM_SIZE_DIST=gus)
      capitalStock: PLN,                   // Physical capital stock (PLN)
      foreignOwned: Boolean,               // FDI: subject to profit shifting & repatriation
      stateOwned: Boolean = false,         // SOE: Skarb Państwa ownership (dividend/employment/investment policy)
      inventory: PLN,                      // Inventory stock (PLN)
      greenCapital: PLN,                   // Green capital stock (PLN)
      accumulatedLoss: PLN,                // CIT loss carryforward stock (Art. 7 ustawy o CIT)
      markup: Multiplier = Multiplier.One, // Calvo pricing: firm-specific markup over marginal cost
      region: Region = Region.Central,     // NUTS-1 macroregion
      startupMonthsLeft: Int = 0,          // startup grace/ramp-up window for new entrants
      startupTargetWorkers: Int = 0,       // small team size targeted during startup phase
      startupFilledWorkers: Int = 0,       // employed workers currently filling the startup team
      hiringSignalMonths: Int = 0,         // consecutive months with positive desired hiring gap
  )

  /** Output of `process` for one firm in one month — updated state + flow
    * variables.
    */
  case class Result(
      firm: State,                                // Updated firm state after this month
      financialStocks: FinancialStocks,           // Closing ledger-contracted financial stocks
      taxPaid: PLN,                               // CIT actually paid (after informal evasion)
      realizedPostTaxProfit: PLN,                 // realized monthly profit after tax, floored at zero for payout logic
      signedRealizedPostTaxProfit: PLN,           // signed net-after-tax profit before payout floor, for diagnostics
      capexSpent: PLN,                            // Technology upgrade CAPEX (AI or hybrid)
      techImports: PLN,                           // Import content of CAPEX (forex demand)
      newLoan: PLN,                               // New bank loan requested before financing-channel split
      techNewLoan: PLN,                           // Portion of newLoan tied to technology CAPEX
      equityIssuance: PLN,                        // GPW equity raised this month (filled by S4)
      grossInvestment: PLN,                       // Physical capital investment this month
      bondIssuance: PLN,                          // Corporate bond issuance (filled by S4)
      profitShiftCost: PLN,                       // FDI profit shifting outflow
      fdiRepatriation: PLN,                       // FDI dividend repatriation outflow
      inventoryChange: PLN,                       // Net inventory change (+ accumulation, - drawdown)
      citEvasion: PLN,                            // CIT evaded via informal economy
      energyCost: PLN,                            // Total energy + ETS cost this month
      greenInvestment: PLN,                       // Green capital investment this month
      principalRepaid: PLN,                       // Monthly firm loan principal repayment
      investmentCreditDemand: PLN = PLN.Zero,     // Physical-investment bank credit requested
      investmentCreditApproved: PLN = PLN.Zero,   // Physical-investment bank credit approved
      investmentCreditRejected: PLN = PLN.Zero,   // Physical-investment bank credit rejected by bank supply
      techCreditDemand: PLN = PLN.Zero,           // Technology-upgrade bank credit requested or bank-rejected
      techCreditApproved: PLN = PLN.Zero,         // Technology-upgrade bank credit approved
      techCreditRejected: PLN = PLN.Zero,         // Technology-upgrade bank credit rejected by bank supply
      investmentCreditRejectionBreakdown: CreditRejectionBreakdown = CreditRejectionBreakdown.zero,
      techCreditRejectionBreakdown: CreditRejectionBreakdown = CreditRejectionBreakdown.zero,
      decisionTrace: Option[DecisionTrace] = None, // Auditable decision surface for optional exports
  )
  object Result:
    /** Convenience factory for tests — all flow fields set to `PLN.Zero`. */
    def zero(firm: State, financialStocks: FinancialStocks = FinancialStocks.zero): Result =
      Result(
        firm,
        financialStocks,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
      )

  /** Auditable per-firm decision record. It is computed from values already
    * consumed by the decision path; constructing it must not draw from RNG.
    */
  case class DecisionTrace(
      firmId: FirmId,
      openingTech: TechState,
      closingTech: TechState,
      decisionType: DecisionTrace.DecisionType,
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
      techCreditDecisionType: Option[DecisionTrace.DecisionType],
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
  object DecisionTrace:
    enum DecisionType(val csvValue: String):
      case Survive       extends DecisionType("survive")
      case Upsize        extends DecisionType("upsize")
      case Downsize      extends DecisionType("downsize")
      case DigiInvest    extends DecisionType("digi-invest")
      case FullAiUpgrade extends DecisionType("full-AI-upgrade")
      case HybridUpgrade extends DecisionType("hybrid-upgrade")
      case UpgradeFailed extends DecisionType("upgrade-failed")
      case Bankrupt      extends DecisionType("bankrupt")

  /** Monthly profit-and-loss breakdown, computed by `computePnL`. */
  case class PnL(
      revenue: PLN,           // Gross revenue (capacity × demand × price)
      costs: PLN,             // Total costs including profit shifting
      tax: PLN,               // CIT after loss carryforward offset
      netAfterTax: PLN,       // Profit minus tax (can be negative)
      profitShiftCost: PLN,   // FDI profit shifting cost (zero if not foreign-owned)
      energyCost: PLN,        // Energy + ETS carbon surcharge
      newAccumulatedLoss: PLN, // Updated loss carryforward stock for next month
  )
  object PnL:
    val zero: PnL = PnL(PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero)

  /** Intermediate ADT from `decide` → `execute`. Separates stochastic choices
    * from state mutation.
    */
  sealed trait Decision
  object Decision:
    case object StayBankrupt                                                                                                  extends Decision
    case class Survive(pnl: PnL, newCash: PLN, drUpdate: Option[Share] = None)                                                extends Decision
    case class GoBankrupt(pnl: PnL, cash: PLN, reason: BankruptReason)                                                        extends Decision
    case class Upgrade(pnl: PnL, newTech: TechState, capex: PLN, loan: PLN, downPayment: PLN, drUpdate: Option[Share] = None) extends Decision
    case class UpgradeFailed(pnl: PnL, reason: BankruptReason, capex: PLN, loan: PLN, down: PLN)                              extends Decision
    case class Downsize(pnl: PnL, newWorkers: Int, adjustedCash: PLN, newTech: TechState, drUpdate: Option[Share] = None)     extends Decision
    case class Upsize(pnl: PnL, newWorkers: Int, newCash: PLN, newTech: TechState, drUpdate: Option[Share] = None)            extends Decision
    case class DigiInvest(pnl: PnL, cost: PLN, newDR: Share)                                                                  extends Decision

  case class CreditDecision(
      approved: Boolean,
      approvalProbability: Option[Share] = None,
      approvalRoll: Option[Share] = None,
      audit: Banking.CreditApprovalAudit = Banking.CreditApprovalAudit.empty,
  )
  object CreditDecision:
    def fromBoolean(approved: Boolean): CreditDecision =
      CreditDecision(approved)

    def fromApproval(approval: Banking.CreditApproval): CreditDecision =
      CreditDecision(
        approved = approval.approved,
        approvalProbability = approval.approvalProbability,
        approvalRoll = approval.approvalRoll,
        audit = approval.audit,
      )

  case class CreditRejectionBreakdown(
      failedBank: PLN = PLN.Zero,
      carGate: PLN = PLN.Zero,
      lcrGate: PLN = PLN.Zero,
      nsfrGate: PLN = PLN.Zero,
      stochastic: PLN = PLN.Zero,
      unclassified: PLN = PLN.Zero,
  ):
    def +(other: CreditRejectionBreakdown): CreditRejectionBreakdown =
      CreditRejectionBreakdown(
        failedBank = failedBank + other.failedBank,
        carGate = carGate + other.carGate,
        lcrGate = lcrGate + other.lcrGate,
        nsfrGate = nsfrGate + other.nsfrGate,
        stochastic = stochastic + other.stochastic,
        unclassified = unclassified + other.unclassified,
      )

  object CreditRejectionBreakdown:
    val zero: CreditRejectionBreakdown = CreditRejectionBreakdown()

    def from(reason: Option[Banking.CreditRejectionReason], amount: PLN): CreditRejectionBreakdown =
      if amount <= PLN.Zero then zero
      else
        reason match
          case Some(Banking.CreditRejectionReason.FailedBank)        => CreditRejectionBreakdown(failedBank = amount)
          case Some(Banking.CreditRejectionReason.CapitalAdequacy)   => CreditRejectionBreakdown(carGate = amount)
          case Some(Banking.CreditRejectionReason.LiquidityCoverage) => CreditRejectionBreakdown(lcrGate = amount)
          case Some(Banking.CreditRejectionReason.StableFunding)     => CreditRejectionBreakdown(nsfrGate = amount)
          case Some(Banking.CreditRejectionReason.Stochastic)        => CreditRejectionBreakdown(stochastic = amount)
          case None                                                  => CreditRejectionBreakdown(unclassified = amount)

  private case class DecisionAudit(
      selectedBankApproval: Option[Boolean] = None,
      selectedBankApprovalProbability: Option[Share] = None,
      selectedBankApprovalRoll: Option[Share] = None,
      selectedBankApprovalAudit: Banking.CreditApprovalAudit = Banking.CreditApprovalAudit.empty,
      fullAiFeasible: Option[Boolean] = None,
      hybridFeasible: Option[Boolean] = None,
      fullAiAdoptionProbability: Option[Share] = None,
      hybridAdoptionProbability: Option[Share] = None,
      adoptionRoll: Option[Share] = None,
      fullAiBankApproval: Option[Boolean] = None,
      fullAiBankApprovalProbability: Option[Share] = None,
      fullAiBankApprovalRoll: Option[Share] = None,
      fullAiBankApprovalAudit: Banking.CreditApprovalAudit = Banking.CreditApprovalAudit.empty,
      hybridBankApproval: Option[Boolean] = None,
      hybridBankApprovalProbability: Option[Share] = None,
      hybridBankApprovalRoll: Option[Share] = None,
      hybridBankApprovalAudit: Banking.CreditApprovalAudit = Banking.CreditApprovalAudit.empty,
      techCreditDecisionType: Option[DecisionTrace.DecisionType] = None,
      techCreditNeed: Option[PLN] = None,
      implementationFailureProbability: Option[Share] = None,
      implementationRoll: Option[Share] = None,
      upgradeEfficiencyDraw: Option[Scalar] = None,
      upgradeEfficiencyMultiplier: Option[Multiplier] = None,
      digitalInvestProbability: Option[Share] = None,
      digitalInvestRoll: Option[Share] = None,
      laborAdjustmentResidualProbability: Option[Share] = None,
      laborAdjustmentResidualRoll: Option[Share] = None,
  ):
    def merge(next: DecisionAudit): DecisionAudit =
      DecisionAudit(
        selectedBankApproval = next.selectedBankApproval.orElse(selectedBankApproval),
        selectedBankApprovalProbability = next.selectedBankApprovalProbability.orElse(selectedBankApprovalProbability),
        selectedBankApprovalRoll = next.selectedBankApprovalRoll.orElse(selectedBankApprovalRoll),
        selectedBankApprovalAudit = next.selectedBankApprovalAudit.orElse(selectedBankApprovalAudit),
        fullAiFeasible = next.fullAiFeasible.orElse(fullAiFeasible),
        hybridFeasible = next.hybridFeasible.orElse(hybridFeasible),
        fullAiAdoptionProbability = next.fullAiAdoptionProbability.orElse(fullAiAdoptionProbability),
        hybridAdoptionProbability = next.hybridAdoptionProbability.orElse(hybridAdoptionProbability),
        adoptionRoll = next.adoptionRoll.orElse(adoptionRoll),
        fullAiBankApproval = next.fullAiBankApproval.orElse(fullAiBankApproval),
        fullAiBankApprovalProbability = next.fullAiBankApprovalProbability.orElse(fullAiBankApprovalProbability),
        fullAiBankApprovalRoll = next.fullAiBankApprovalRoll.orElse(fullAiBankApprovalRoll),
        fullAiBankApprovalAudit = next.fullAiBankApprovalAudit.orElse(fullAiBankApprovalAudit),
        hybridBankApproval = next.hybridBankApproval.orElse(hybridBankApproval),
        hybridBankApprovalProbability = next.hybridBankApprovalProbability.orElse(hybridBankApprovalProbability),
        hybridBankApprovalRoll = next.hybridBankApprovalRoll.orElse(hybridBankApprovalRoll),
        hybridBankApprovalAudit = next.hybridBankApprovalAudit.orElse(hybridBankApprovalAudit),
        techCreditDecisionType = next.techCreditDecisionType.orElse(techCreditDecisionType),
        techCreditNeed = next.techCreditNeed.orElse(techCreditNeed),
        implementationFailureProbability = next.implementationFailureProbability.orElse(implementationFailureProbability),
        implementationRoll = next.implementationRoll.orElse(implementationRoll),
        upgradeEfficiencyDraw = next.upgradeEfficiencyDraw.orElse(upgradeEfficiencyDraw),
        upgradeEfficiencyMultiplier = next.upgradeEfficiencyMultiplier.orElse(upgradeEfficiencyMultiplier),
        digitalInvestProbability = next.digitalInvestProbability.orElse(digitalInvestProbability),
        digitalInvestRoll = next.digitalInvestRoll.orElse(digitalInvestRoll),
        laborAdjustmentResidualProbability = next.laborAdjustmentResidualProbability.orElse(laborAdjustmentResidualProbability),
        laborAdjustmentResidualRoll = next.laborAdjustmentResidualRoll.orElse(laborAdjustmentResidualRoll),
      )

  private case class DecisionWithAudit(decision: Decision, audit: DecisionAudit)

  private case class AdjustmentMagnitude(
      value: Int,
      residualProbability: Option[Share],
      residualRoll: Option[Share],
  )

  private def selectedBankAudit(credit: CreditDecision): DecisionAudit =
    DecisionAudit(
      selectedBankApproval = Some(credit.approved),
      selectedBankApprovalProbability = credit.approvalProbability,
      selectedBankApprovalRoll = credit.approvalRoll,
      selectedBankApprovalAudit = credit.audit,
    )

  private def selectedTechBankAudit(credit: CreditDecision, need: PLN, decisionType: DecisionTrace.DecisionType): DecisionAudit =
    selectedBankAudit(credit).copy(
      techCreditDecisionType = Some(decisionType),
      techCreditNeed = Some(need),
    )

  private def bankRejectedTechDemandAudit(candidate: UpgradeCandidate, decisionType: DecisionTrace.DecisionType): DecisionAudit =
    if !candidate.bankOk && candidate.profitable && candidate.canPay && candidate.ready then
      selectedTechBankAudit(candidate.credit, candidate.loan, decisionType)
    else DecisionAudit()

  private def fullAiBankAudit(credit: CreditDecision): DecisionAudit =
    DecisionAudit(
      fullAiBankApproval = Some(credit.approved),
      fullAiBankApprovalProbability = credit.approvalProbability,
      fullAiBankApprovalRoll = credit.approvalRoll,
      fullAiBankApprovalAudit = credit.audit,
    )

  private def hybridBankAudit(credit: CreditDecision): DecisionAudit =
    DecisionAudit(
      hybridBankApproval = Some(credit.approved),
      hybridBankApprovalProbability = credit.approvalProbability,
      hybridBankApprovalRoll = credit.approvalRoll,
      hybridBankApprovalAudit = credit.audit,
    )

  // ---- Queries ----

  /** True unless the firm is in `Bankrupt` tech state. */
  def isAlive(f: State): Boolean = f.tech match
    case _: TechState.Bankrupt => false
    case _                     => true

  /** Headcount: workers for Traditional/Hybrid, skeleton crew for Automated, 0
    * for Bankrupt.
    */
  def workerCount(f: State)(using SimParams): Int = f.tech match
    case TechState.Traditional(w) => w
    case TechState.Hybrid(w, _)   => w
    case _: TechState.Automated   => skeletonCrew(f)
    case _: TechState.Bankrupt    => 0

  /** Skeleton crew for automated firms — scales with firm size. */
  def skeletonCrew(f: State)(using p: SimParams): Int =
    Math.max(p.firm.autoSkeletonCrew, SkeletonCrewFrac.floorApplyTo(f.initialSize))

  def isInStartup(f: State): Boolean = f.startupMonthsLeft > 0

  /** Structural operating scale used for capital planning. Physical and green
    * capital adjust slowly, so temporary staffing cuts should not immediately
    * erase a firm's capital target.
    */
  private[amorfati] def capitalPlanningWorkers(f: State)(using p: SimParams): Int =
    if !isAlive(f) then 0
    else
      val workers = workerCount(f)
      if f.startupTargetWorkers > 0 && workers < f.initialSize then Math.max(workers, f.startupTargetWorkers)
      else Math.max(workers, f.initialSize)

  /** Effective wage multiplier including union wage premium. */
  def effectiveWageMult(sectorIdx: SectorIdx)(using p: SimParams): Multiplier =
    val base = p.sectorDefs(sectorIdx.toInt).wageMultiplier
    base + base * (p.labor.unionWagePremium * p.labor.unionDensity(sectorIdx.toInt))

  /** Monthly production capacity in PLN. Scales with tech, sector, firm size;
    * augmented by physical capital via CES production function when enabled.
    *
    * CES: Y = A × [α·K^ρ + (1-α)·L^ρ]^(1/ρ) where ρ = (σ-1)/σ. High-σ sectors
    * (BPO=50) substitute K/L easily; low-σ (Public=1) resist.
    */
  def computeCapacity(f: State)(using p: SimParams): PLN =
    val sec       = p.sectorDefs(f.sector.toInt)
    val sizeScale = Scalar.fraction(f.initialSize, p.pop.workersPerFirm).toMultiplier
    val laborEff  = f.tech match
      case TechState.Traditional(w) => Scalar.fraction(w, f.initialSize).toMultiplier
      case TechState.Hybrid(w, eff) =>
        HybridLaborCapShare.toMultiplier * Scalar.fraction(w, f.initialSize).toMultiplier + HybridAiCapShare.toMultiplier * eff
      case TechState.Automated(eff) => eff
      case _: TechState.Bankrupt    => Multiplier.Zero
    val tfp       = sizeScale * sec.revenueMultiplier
    if f.capitalStock > PLN.Zero && laborEff > Multiplier.Zero then
      val targetK: PLN  = capitalPlanningWorkers(f) * p.capital.klRatios(f.sector.toInt)
      val k: Multiplier =
        (if targetK > PLN.Zero then f.capitalStock.ratioTo(targetK).toMultiplier else Multiplier.One).clamp(Multiplier.decimal(1, 1), Multiplier(2))
      val alpha: Share  = p.capital.prodElast
      p.firm.baseRevenue * tfp * cesOutput(alpha, k, laborEff, sec.sigma)
    else p.firm.baseRevenue * tfp * laborEff

  def computeEffectiveCapacity(f: State, productivityIndex: Multiplier)(using p: SimParams): PLN =
    computeCapacity(f) * productivityIndex

  /** CES aggregator: [α·K^ρ + (1-α)·L^ρ]^(1/ρ). Degrades gracefully: σ→1 ≈
    * Cobb-Douglas, σ→∞ ≈ linear (perfect substitutes).
    */
  private[amorfati] def cesOutput(alpha: Share, k: Multiplier, l: Multiplier, sigma: Sigma): Multiplier =
    if !(sigma > Sigma.decimal(1001, 3)) then // near-Leontief/Cobb-Douglas boundary — use Cobb-Douglas
      k.pow(alpha.toScalar) * l.pow((Share.One - alpha).toScalar)
    else
      val rho   = (sigma.toScalar - Scalar.One).ratioTo(sigma.toScalar)
      val kTerm = alpha * k.pow(rho)
      val lTerm = (Share.One - alpha) * l.pow(rho)
      (kTerm + lTerm).pow(rho.reciprocal)

  /** Desired worker count from a one-period MR≈MC comparison.
    *
    * Numerically searches for the headcount where adding one more worker would
    * cost more (wage) than the revenue gained (∂capacity × demand × price).
    * Bounded by [minWorkersRetained, 3 × initialSize], then compressed by an
    * economy-wide labor-slack factor when aggregate plans exceed available
    * labor supply.
    */
  private def desiredWorkers(f: State, w: World, operationalSignals: OperationalSignals)(using p: SimParams): Int =
    if isInStartup(f) then return Math.max(workerCount(f), f.startupTargetWorkers)
    def withWorkers(workers: Int): State =
      f.tech match
        case TechState.Traditional(_) => f.copy(tech = TechState.Traditional(workers))
        case TechState.Hybrid(_, eff) => f.copy(tech = TechState.Hybrid(workers, eff))
        case _                        => f
    val sectorDemand                     = operationalSignals.sectorDemandMult(f.sector.toInt)
    val hiringSignal                     = operationalSignals.sectorHiringSignal(f.sector.toInt)
    val demandMult                       = sectorDemand + (hiringSignal - sectorDemand).max(Multiplier.Zero) * HiringPressureBlend
    val price                            = w.priceLevel.toMultiplier
    val wage                             = w.householdMarket.marketWage * effectiveWageMult(f.sector)
    val workers                          = workerCount(f)
    val minW                             = p.firm.minWorkersRetained
    val maxW                             = f.initialSize * 3

    // Binary search: find largest w where marginal revenue > marginal cost
    var lo = minW; var hi = maxW
    while lo < hi do
      val mid     = (lo + hi + 1) / 2
      val capMid  = computeEffectiveCapacity(withWorkers(mid), w.real.productivityIndex)
      val capPrev = computeEffectiveCapacity(withWorkers(mid - 1), w.real.productivityIndex)
      val mr      = (capMid - capPrev) * (demandMult * price)
      if mr > wage then lo = mid else hi = mid - 1
    applyOperationalHiringSlack(workers, lo, minW, operationalSignals.operationalHiringSlack.toMultiplier)

  private[amorfati] def monthlyHiringHeadroom(workers: Int): Int =
    if workers <= 5 then 1
    else if workers <= 10 then SmallFirmDesiredAddCap
    else if workers <= 25 then MidFirmDesiredAddCap
    else Math.max(MidFirmDesiredAddCap, LargeFirmDesiredGrowthShare.ceilApplyTo(workers))

  private def hiringAdjustmentThreshold(workers: Int): Int =
    if workers <= 5 then MicroFirmHiringThreshold
    else Math.min(monthlyHiringHeadroom(workers), Math.max(2, workers / 10))

  private def requiredHiringSignalMonths(workers: Int): Int =
    if workers <= 5 then 1 else HiringSignalPersistenceMonths

  private def nextHiringSignalMonths(firm: State, desired: Int, workers: Int): Int =
    if desired > workers then firm.hiringSignalMonths + 1 else 0

  private def stochasticAdjustmentMagnitude(absGap: Int, adjustFrac: Share, rng: RandomStream): AdjustmentMagnitude =
    if absGap <= 0 then AdjustmentMagnitude(0, None, None)
    else
      val expectedRaw  = BigInt(adjustFrac.toLong) * BigInt(absGap)
      val whole        = (expectedRaw / BigInt(FixedPointBase.Scale)).toInt
      val residualRaw  = (expectedRaw % BigInt(FixedPointBase.Scale)).toLong
      val residualP    = if residualRaw > 0L then Some(Share.fromRaw(residualRaw)) else None
      val residualRoll = residualP.map(_ => Share.random(rng))
      val residual     = if residualP.exists(p => residualRoll.exists(_ < p)) then 1 else 0
      AdjustmentMagnitude(Math.min(absGap, whole + residual), residualP, residualRoll)

  private def hiringAdjustFrac(using p: SimParams): Share =
    p.firm.laborAdjustSpeed.toShare.clamp(Share.Zero, Share.One)

  private def firingAdjustFrac(firm: State)(using p: SimParams): Share =
    if firm.stateOwned then FiringAdjustFrac * StateOwned.firingReduction else FiringAdjustFrac

  private def feasibleWorkers(
      firm: State,
      workers: Int,
      desired: Int,
      pnl: PnL,
      cashAfterDecision: PLN,
  )(using p: SimParams): Int =
    if desired <= workers then desired
    else
      val signalMonths  = nextHiringSignalMonths(firm, desired, workers)
      val persistenceOk = signalMonths >= requiredHiringSignalMonths(workers) || isInStartup(firm)
      if !persistenceOk then workers
      else
        val headroom                = monthlyHiringHeadroom(workers)
        val structurallyConstrained = Math.min(desired, workers + headroom)
        val liquidityConstrained    =
          if firm.stateOwned || cashAfterDecision >= PLN.Zero then structurallyConstrained
          else if isInStartup(firm) && cashAfterDecision.abs <= startupRunwayLimit(firm) then structurallyConstrained
          else if pnl.netAfterTax > PLN.Zero then workers + Math.max(1, NegativeCashHiringPenalty.floorApplyTo(headroom))
          else workers
        Math.max(workers, liquidityConstrained)

  private[amorfati] def applyOperationalHiringSlack(currentWorkers: Int, rawTarget: Int, minWorkers: Int, slackFactor: Multiplier): Int =
    if rawTarget <= currentWorkers then Math.max(minWorkers, rawTarget)
    else
      val positiveGap = rawTarget - currentWorkers
      val scaledGap   = FixedPointBase.multiplyRaw(positiveGap.toLong, slackFactor.clamp(Multiplier.Zero, Multiplier.One).toLong).toInt
      Math.max(minWorkers, currentWorkers + scaledGap)

  private[amorfati] def hiringDiagnostics(
      firm: State,
      financialStocks: FinancialStocks,
      w: World,
      operationalSignals: OperationalSignals,
  )(using p: SimParams): HiringDiagnostics =
    val workers         = workerCount(firm)
    val desiredW        = desiredWorkers(firm, w, operationalSignals)
    val nc              = financialStocks.cash
    val feasibleW       = feasibleWorkers(firm, workers, desiredW, PnL.zero, nc)
    val desiredGap      = desiredW - workers
    val feasibleGap     = feasibleW - workers
    val hiringThresh    = hiringAdjustmentThreshold(workers)
    val firingThresh    = Math.max(2, workers / 10)
    val shouldAdjust    = if feasibleGap > 0 then feasibleGap >= hiringThresh else -feasibleGap >= firingThresh
    val proposedAdjust  =
      if shouldAdjust then
        val adjustFrac = if feasibleGap > 0 then hiringAdjustFrac else firingAdjustFrac(firm)
        Math.max(1, adjustFrac.floorApplyTo(Math.abs(feasibleGap))) * (if feasibleGap > 0 then 1 else -1)
      else 0
    val proposedWorkers = (workers + proposedAdjust).max(p.firm.minWorkersRetained)
    HiringDiagnostics(
      workers = workers,
      desiredWorkers = desiredW,
      feasibleWorkers = feasibleW,
      desiredGap = desiredGap,
      feasibleGap = feasibleGap,
      hiringThreshold = hiringThresh,
      firingThreshold = firingThresh,
      shouldAdjust = shouldAdjust,
      proposedAdjustment = proposedAdjust,
      proposedWorkers = proposedWorkers,
      signalMonths = nextHiringSignalMonths(firm, desiredW, workers),
      requiredSignalMonths = requiredHiringSignalMonths(workers),
    )

  /** Effective AI CAPEX for sector — sublinear in firm size (exponent 0.6),
    * digital readiness discount.
    */
  def computeAiCapex(f: State)(using p: SimParams): PLN =
    val sizeFactor   = Scalar.fraction(f.initialSize, p.pop.workersPerFirm).pow(CapexSizeExponent).toMultiplier
    val digiDiscount = Share.One - p.firm.digiCapexDiscount * f.digitalReadiness
    p.firm.aiCapex * p.sectorDefs(f.sector.toInt).aiCapexMultiplier * digiDiscount * (f.innovationCostFactor * sizeFactor)

  /** Hybrid upgrade CAPEX — same scaling as AI CAPEX but using hybrid
    * multipliers.
    */
  def computeHybridCapex(f: State)(using p: SimParams): PLN =
    val sizeFactor   = Scalar.fraction(f.initialSize, p.pop.workersPerFirm).pow(CapexSizeExponent).toMultiplier
    val digiDiscount = Share.One - p.firm.digiCapexDiscount * f.digitalReadiness
    p.firm.hybridCapex * p.sectorDefs(f.sector.toInt).hybridCapexMultiplier * digiDiscount * (f.innovationCostFactor * sizeFactor)

  /** Digital investment cost — sublinear in firm size (exponent 0.5). */
  def computeDigiInvestCost(f: State)(using p: SimParams): PLN =
    val sizeFactor = Scalar.fraction(f.initialSize, p.pop.workersPerFirm).pow(OpexSizeExponent).toMultiplier
    p.firm.digiInvestCost * sizeFactor

  /** Fraction of a firm's network neighbors that have adopted automation
    * (Automated or Hybrid tech).
    *
    * Used in technology adoption decisions: firms with more automated neighbors
    * face stronger competitive pressure to digitalize (network externality /
    * peer effect). Returns 0.0 for firms with no neighbors (isolates).
    */
  def computeLocalAutoRatio(firm: Firm.State, firms: Vector[Firm.State]): Share =
    val neighbors = firm.neighbors
    if neighbors.isEmpty then return Share.Zero
    val autoCount = neighbors.count: nid =>
      val nf = firms(nid.toInt)
      nf.tech.isInstanceOf[TechState.Automated] || nf.tech.isInstanceOf[TechState.Hybrid]
    Share.fraction(autoCount, neighbors.length)

  /** sigma-based threshold modifier: high sigma sectors find automation
    * profitable at lower cost gap. Only used for profitability threshold, NOT
    * for probability multiplier. Mapping: sigma=2->0.91, sigma=5->0.95,
    * sigma=10->0.98, sigma=50->1.00 At equilibrium P~1.1: Manufacturing
    * marginal, Healthcare blocked.
    */
  def sigmaThreshold(sigma: Sigma): Multiplier =
    (SigmaThreshBase + SigmaThreshScale * sigma.toScalar.log10.toMultiplier).min(Multiplier.One)

  // ---- Entry point ----

  /** Monthly entry point. Pipeline: decide → execute → greenInvest → invest →
    * digiDrift → inventory → FDI → informal evasion.
    */
  def process(
      firm: State,
      financialStocks: FinancialStocks,
      w: World,
      executionMonth: ExecutionMonth,
      operationalSignals: OperationalSignals,
      lendRate: Rate,
      bankCanLend: PLN => Boolean,
      allFirms: Vector[State],
      rng: RandomStream,
      corpBondDebt: PLN,
  )(using p: SimParams): Result =
    processWithCreditAudit(
      firm,
      financialStocks,
      w,
      executionMonth,
      operationalSignals,
      lendRate,
      amount => CreditDecision.fromBoolean(bankCanLend(amount)),
      allFirms,
      rng,
      corpBondDebt,
      traceDecision = false,
    )

  private[amorfati] def processWithCreditAudit(
      firm: State,
      financialStocks: FinancialStocks,
      w: World,
      executionMonth: ExecutionMonth,
      operationalSignals: OperationalSignals,
      lendRate: Rate,
      bankCreditDecision: PLN => CreditDecision,
      allFirms: Vector[State],
      rng: RandomStream,
      corpBondDebt: PLN,
      traceDecision: Boolean,
  )(using p: SimParams): Result =
    val decision = decide(firm, financialStocks, w, executionMonth, operationalSignals, lendRate, bankCreditDecision, allFirms, rng, corpBondDebt)
    val r0       = updateHiringSignalState(execute(firm, financialStocks, decision.decision), firm, w, operationalSignals)
    val r0a      = applyLoanAmortization(r0)
    val tracedR0 = if traceDecision then r0a.copy(decisionTrace = Some(buildDecisionTrace(firm, financialStocks, lendRate, decision, r0a))) else r0a
    val r1       = applyGreenInvestment(tracedR0)
    val r2       = applyInvestment(r1, operationalSignals.sectorDemandPressure(firm.sector.toInt), bankCreditDecision)
    val r3       = applyDigitalDrift(r2)
    val r4       = applyInventory(r3, sectorDemandMult = operationalSignals.sectorDemandMult(firm.sector.toInt))
    val r5       = applyFdiFlows(r4)
    val finalR   = applyInformalCitEvasion(r5, w.mechanisms.informalCyclicalAdj)
    val auditedR = applyTechCreditDiagnostics(finalR, decision)
    if traceDecision then auditedR.copy(decisionTrace = auditedR.decisionTrace.map(refreshDecisionTraceClosing(_, auditedR)))
    else auditedR

  // ---- Decide (all match logic + RandomStream rolls) ----

  /** Dispatch to tech-specific decision logic. Contains all RandomStream calls.
    */
  private def decide(
      firm: State,
      financialStocks: FinancialStocks,
      w: World,
      executionMonth: ExecutionMonth,
      operationalSignals: OperationalSignals,
      lendRate: Rate,
      bankCreditDecision: PLN => CreditDecision,
      allFirms: Vector[State],
      rng: RandomStream,
      corpBondDebt: PLN,
  )(using p: SimParams): DecisionWithAudit =
    firm.tech match
      case _: TechState.Bankrupt         => DecisionWithAudit(Decision.StayBankrupt, DecisionAudit())
      case _: TechState.Automated        => decideAutomated(firm, financialStocks, w, executionMonth, operationalSignals, lendRate, corpBondDebt)
      case TechState.Hybrid(wkrs, aiEff) =>
        decideHybrid(firm, financialStocks, w, executionMonth, operationalSignals, lendRate, bankCreditDecision, wkrs, aiEff, rng, corpBondDebt)
      case TechState.Traditional(wkrs)   =>
        decideTraditional(firm, financialStocks, w, executionMonth, operationalSignals, lendRate, bankCreditDecision, allFirms, wkrs, rng, corpBondDebt)

  /** Smooth labor adjustment: Δworkers = λ × (target − current), with severance
    * costs. Target = break-even headcount from P&L. If adjustment insufficient
    * to restore solvency, escalates to bankruptcy.
    */
  private[amorfati] def attemptDownsize(
      firm: State,
      pnl: PnL,
      nc: PLN,
      workers: Int,
      newTech: Int => TechState,
      wage: PLN,
      reason: BankruptReason,
      drUpdate: Option[Share] = None,
  )(using p: SimParams): Decision =
    val minRetained         = p.firm.minWorkersRetained
    if workers <= minRetained then
      return if hasWorkingCapitalGrace(firm, pnl, nc) then Decision.Survive(pnl, nc, drUpdate = drUpdate) else Decision.GoBankrupt(pnl, nc, reason)
    val laborPerWorker: PLN = wage * effectiveWageMult(firm.sector)
    // Target headcount: workers needed for revenue to cover non-labor costs
    val nonLaborCost: PLN   = (pnl.costs - workers * laborPerWorker).max(PLN.Zero)
    val revenuePerWorker    = if workers > 0 then pnl.revenue / workers else PLN.Zero
    val contributionMargin  = (revenuePerWorker - laborPerWorker).max(PLN.Zero)
    val targetWorkers       =
      if contributionMargin > PLN.Zero then (nonLaborCost / contributionMargin).ceilToInt
      else minRetained
    // Smooth adjustment: cut λ of the gap, not the entire excess
    val gap                 = workers - Math.max(minRetained, targetWorkers)
    val cutSpeedRaw         =
      if firm.stateOwned then (p.firm.laborAdjustSpeed * StateOwned.firingReduction).toLong
      else if isInStartup(firm) then (p.firm.laborAdjustSpeed * StartupDownsizeSpeedMultiplier).toLong
      else p.firm.laborAdjustSpeed.toLong
    val cut                 = Math.max(1, FixedPointBase.multiplyRaw(gap.toLong, cutSpeedRaw).toInt)
    val newWkrs             = Math.max(minRetained, workers - cut)
    // Severance cost = fired workers × wage × severanceMonths
    val fired               = workers - newWkrs
    val severancePay: PLN   = fired * laborPerWorker * p.firm.severanceMonths
    val laborSaved: PLN     = fired * laborPerWorker
    val revRatio: Share     = Share.fraction(newWkrs, workers).sqrt
    val revLost: PLN        = pnl.revenue * (Share.One - revRatio)
    val adjustedNc          = nc + laborSaved - revLost - severancePay
    if adjustedNc >= PLN.Zero || hasWorkingCapitalGrace(firm, pnl, adjustedNc) then
      Decision.Downsize(pnl, newWkrs, adjustedNc, newTech(newWkrs), drUpdate = drUpdate)
    else Decision.GoBankrupt(pnl, nc, reason)

  private[amorfati] def startupRunwayLimit(firm: State)(using p: SimParams): PLN =
    if !isInStartup(firm) then PLN.Zero
    else
      val remainingShare = Share.fraction(firm.startupMonthsLeft, StartupRunwayMonths).clamp(Share.Zero, Share.One)
      p.firm.entryStartupCash * (StartupRunwayCashShare * remainingShare)

  private def startupProgress(firm: State): Share =
    if !isInStartup(firm) then Share.One
    else Share.One - Share.fraction(firm.startupMonthsLeft, StartupRunwayMonths).clamp(Share.Zero, Share.One)

  private def startupCostMultiplier(firm: State): Multiplier =
    if !isInStartup(firm) then Multiplier.One
    else StartupCostFloor + (Multiplier.One - StartupCostFloor) * startupProgress(firm).toMultiplier

  private[amorfati] def hasWorkingCapitalGrace(firm: State, pnl: PnL, cashAfterDecision: PLN)(using p: SimParams): Boolean =
    firm.stateOwned ||
      (cashAfterDecision < PLN.Zero &&
        pnl.netAfterTax > PLN.Zero &&
        cashAfterDecision.abs <= pnl.netAfterTax * WorkingCapitalGraceMonths) ||
      (isInStartup(firm) &&
        cashAfterDecision < PLN.Zero &&
        cashAfterDecision.abs <= startupRunwayLimit(firm))

  /** Estimate monthly operating cost for a hypothetical tech configuration.
    * Used by `decideHybrid` and `decideTraditional` to compare current costs
    * against upgrade costs.
    */
  private def estimateMonthlyCost(
      firm: State,
      financialStocks: FinancialStocks,
      opex: PLN,
      laborWorkers: Int,
      additionalDebt: PLN,
      wage: PLN,
      lendRate: Rate,
      domesticPrice: PriceIndex,
      importPrice: PriceIndex,
  )(using p: SimParams): PLN =
    val opexSizeFactor  = Scalar.fraction(firm.initialSize, p.pop.workersPerFirm).pow(OpexSizeExponent).toMultiplier
    val otherSizeFactor = Scalar.fraction(firm.initialSize, p.pop.workersPerFirm).toMultiplier
    val wMult           = effectiveWageMult(firm.sector)
    opex * ((domesticPrice.toMultiplier * OpexDomesticShare + importPrice.toMultiplier * OpexImportShare) * opexSizeFactor) +
      (financialStocks.firmLoan + additionalDebt) * lendRate.monthly +
      laborWorkers * (wage * wMult) +
      p.firm.otherCosts * (domesticPrice.toMultiplier * otherSizeFactor)

  /** Automated firm: compute PnL, survive or go bankrupt (AI debt trap). */
  private def decideAutomated(
      firm: State,
      financialStocks: FinancialStocks,
      w: World,
      executionMonth: ExecutionMonth,
      operationalSignals: OperationalSignals,
      lendRate: Rate,
      corpBondDebt: PLN,
  )(using p: SimParams): DecisionWithAudit =
    val pnl = computePnL(
      firm,
      financialStocks,
      w.householdMarket.marketWage,
      operationalSignals.sectorDemandMult(firm.sector.toInt),
      w.priceLevel,
      w.external.gvc.importCostIndex,
      w.external.gvc.commodityPriceIndex,
      lendRate,
      executionMonth,
      corpBondDebt,
      w.real.productivityIndex,
    )
    val nc  = financialStocks.cash + pnl.netAfterTax
    if nc < PLN.Zero then DecisionWithAudit(Decision.GoBankrupt(pnl, nc, BankruptReason.AiDebtTrap), DecisionAudit())
    else DecisionWithAudit(Decision.Survive(pnl, nc), DecisionAudit())

  /** Hybrid firm: attempt full-AI upgrade, else survive/downsize/bankrupt. */
  private def decideHybrid(
      firm: State,
      financialStocks: FinancialStocks,
      w: World,
      executionMonth: ExecutionMonth,
      operationalSignals: OperationalSignals,
      lendRate: Rate,
      bankCreditDecision: PLN => CreditDecision,
      workers: Int,
      aiEff: Multiplier,
      rng: RandomStream,
      corpBondDebt: PLN,
  )(using p: SimParams): DecisionWithAudit =
    val pnl    = computePnL(
      firm,
      financialStocks,
      w.householdMarket.marketWage,
      operationalSignals.sectorDemandMult(firm.sector.toInt),
      w.priceLevel,
      w.external.gvc.importCostIndex,
      w.external.gvc.commodityPriceIndex,
      lendRate,
      executionMonth,
      corpBondDebt,
      w.real.productivityIndex,
    )
    val ready2 = (firm.digitalReadiness + HybridMonthlyDrDrift).min(Share.One)

    if isInStartup(firm) then
      return DecisionWithAudit(
        startupFallbackDecision(firm, financialStocks, pnl, workers, w => TechState.Hybrid(w, aiEff), w.householdMarket.marketWage),
        DecisionAudit(),
      )

    val upCapex    = computeAiCapex(firm) * HybridToFullCapexMul
    val upLoan     = upCapex * FullAiLoanShare
    val upDown     = upCapex * FullAiDownShare
    val upCost     = estimateMonthlyCost(
      firm,
      financialStocks,
      p.firm.aiOpex,
      skeletonCrew(firm),
      upLoan,
      w.householdMarket.marketWage,
      lendRate,
      w.priceLevel,
      w.external.gvc.importCostIndex,
    )
    val profitable = pnl.costs > upCost * FullAiProfitMargin
    val canPay     = financialStocks.cash > upDown
    val ready      = firm.digitalReadiness >= p.firm.fullAiReadinessMin
    val bankCredit = bankCreditDecision(upLoan)
    val bankOk     = bankCredit.approved

    val prob: Share =
      if profitable && canPay && ready && bankOk then
        ((firm.riskProfile * RiskWeightHybUpgrade) + (w.real.automationRatio * AutoRatioWeight)) * firm.digitalReadiness
      else Share.Zero
    val audit       = DecisionAudit(
      fullAiFeasible = Some(profitable && canPay && ready && bankOk),
      fullAiAdoptionProbability = Some(prob.min(Share.One)),
    ).merge(fullAiBankAudit(bankCredit))
      .merge(
        if !bankOk && profitable && canPay && ready then selectedTechBankAudit(bankCredit, upLoan, DecisionTrace.DecisionType.FullAiUpgrade)
        else DecisionAudit(),
      )
    val roll        = Share.random(rng)

    if roll < prob then
      val effDraw = Scalar.randomBetween(HybToFullEffMin, HybToFullEffMax, rng)
      val eff     = Multiplier.One + (effDraw * firm.digitalReadiness.toScalar).toMultiplier
      DecisionWithAudit(
        Decision.Upgrade(pnl, TechState.Automated(eff), upCapex, upLoan, upDown, drUpdate = Some(ready2)),
        audit
          .merge(selectedTechBankAudit(bankCredit, upLoan, DecisionTrace.DecisionType.FullAiUpgrade))
          .copy(adoptionRoll = Some(roll), upgradeEfficiencyDraw = Some(effDraw), upgradeEfficiencyMultiplier = Some(eff)),
      )
    else
      val fallback = fallbackDecision(
        firm,
        financialStocks,
        pnl,
        w,
        operationalSignals,
        workers,
        rng,
        nextTech = workers => TechState.Hybrid(workers, aiEff),
        drUpdate = Some(ready2),
        allowDigiInvest = false,
      )
      DecisionWithAudit(fallback.decision, audit.copy(adoptionRoll = Some(roll)).merge(fallback.audit))

  /** Upgrade feasibility for one tech path (full-AI or hybrid). */
  private case class UpgradeCandidate(
      capex: PLN,
      loan: PLN,
      down: PLN,
      profitable: Boolean,
      canPay: Boolean,
      ready: Boolean,
      credit: CreditDecision,
  ):
    def bankOk: Boolean   = credit.approved
    def feasible: Boolean = profitable && canPay && ready && bankOk

  /** Evaluate full-AI upgrade feasibility for a traditional firm. */
  private def evaluateFullAi(
      firm: State,
      financialStocks: FinancialStocks,
      pnl: PnL,
      w: World,
      lendRate: Rate,
      bankCreditDecision: PLN => CreditDecision,
  )(using p: SimParams): UpgradeCandidate =
    val capex = computeAiCapex(firm)
    val loan  = capex * FullAiLoanShare
    val down  = capex * FullAiDownShare
    val cost  =
      estimateMonthlyCost(
        firm,
        financialStocks,
        p.firm.aiOpex,
        skeletonCrew(firm),
        loan,
        w.householdMarket.marketWage,
        lendRate,
        w.priceLevel,
        w.external.gvc.importCostIndex,
      )
    UpgradeCandidate(
      capex,
      loan,
      down,
      profitable = pnl.costs > (cost * FullAiProfitMargin) / sigmaThreshold(w.currentSigmas(firm.sector.toInt)),
      canPay = financialStocks.cash > down,
      ready = firm.digitalReadiness >= p.firm.fullAiReadinessMin,
      credit = bankCreditDecision(loan),
    )

  /** Evaluate hybrid upgrade feasibility for a traditional firm. */
  private def evaluateHybrid(
      firm: State,
      financialStocks: FinancialStocks,
      pnl: PnL,
      workers: Int,
      w: World,
      lendRate: Rate,
      bankCreditDecision: PLN => CreditDecision,
  )(using p: SimParams): (UpgradeCandidate, Int) =
    val capex = computeHybridCapex(firm)
    val loan  = capex * HybridLoanShare
    val down  = capex * HybridDownShare
    val hWkrs = Math.max(3, p.sectorDefs(firm.sector.toInt).hybridRetainFrac.applyTo(workers))
    val cost  = estimateMonthlyCost(
      firm,
      financialStocks,
      p.firm.hybridOpex,
      hWkrs,
      loan,
      w.householdMarket.marketWage,
      lendRate,
      w.priceLevel,
      w.external.gvc.importCostIndex,
    )
    val cand  = UpgradeCandidate(
      capex,
      loan,
      down,
      profitable = pnl.costs > (cost * HybridProfitMargin) / sigmaThreshold(w.currentSigmas(firm.sector.toInt)),
      canPay = financialStocks.cash > down,
      ready = firm.digitalReadiness >= p.firm.hybridReadinessMin,
      credit = bankCreditDecision(loan),
    )
    (cand, hWkrs)

  /** Compute adoption probabilities for full-AI and hybrid upgrades. Blends
    * network mimetic pressure, desperation, and uncertainty discount.
    */
  private def adoptionProbabilities(
      firm: State,
      pnl: PnL,
      fullAi: UpgradeCandidate,
      hybrid: UpgradeCandidate,
      executionMonth: ExecutionMonth,
      w: World,
      allFirms: Vector[State],
  )(using p: SimParams): (Share, Share) =
    val localAuto   = computeLocalAutoRatio(firm, allFirms)
    val globalPanic = (w.real.automationRatio + w.real.hybridRatio * HybridPanicDiscount) * HybridPanicDiscount
    val panic       = localAuto * LocalPanicWeight + globalPanic * GlobalPanicWeight
    val desper      = if pnl.netAfterTax < PLN.Zero then DesperationBonus else Share.Zero
    val strat       =
      if !fullAi.profitable && fullAi.canPay && fullAi.ready && fullAi.bankOk then firm.riskProfile * firm.digitalReadiness * StrategicAdoptBase
      else Share.Zero

    val willingnessMultiplier = adoptionWillingnessMultiplier(executionMonth, localAuto)

    val rawFull = willingnessMultiplier *
      (if fullAi.feasible then (firm.riskProfile * RiskWeightFullAi + panic + desper) * firm.digitalReadiness
       else strat)
    val rawHyb  = willingnessMultiplier *
      (if hybrid.feasible then (firm.riskProfile * RiskWeightHybrid + panic * HybridPanicDiscount + desper * HybridPanicDiscount) * firm.digitalReadiness
       else Share.Zero)

    val pFull = rawFull.min(Share.One)
    val pHyb  = rawHyb.min(Share.One - pFull)
    (pFull, pHyb)

  private[amorfati] def adoptionWillingnessMultiplier(month: ExecutionMonth, localAuto: Share)(using p: SimParams): Share =
    val elapsedMonths = month.toInt - 1
    val rampFrac      = Scalar.fraction(elapsedMonths, p.firm.adoptionRampMonths).clamp(Scalar.Zero, Scalar.One).toShare
    val baseLevel     = UncertaintyBase + UncertaintySlope * rampFrac
    val demoBoost     =
      if localAuto > p.firm.demoEffectThresh then p.firm.demoEffectBoost * (localAuto - p.firm.demoEffectThresh)
      else Share.Zero
    (baseLevel + demoBoost).min(Share.One)

  /** Roll for full-AI upgrade: success (with random efficiency) or
    * implementation failure.
    */
  private def rollFullAiUpgrade(firm: State, pnl: PnL, ai: UpgradeCandidate, rng: RandomStream): DecisionWithAudit =
    val failRate = FullAiBaseFailRate + (Share.One - firm.digitalReadiness) * FullAiFailDrSens
    val roll     = Share.random(rng)
    val audit    = DecisionAudit(
      implementationFailureProbability = Some(failRate.min(Share.One)),
      implementationRoll = Some(roll),
    )
    if roll < failRate then
      DecisionWithAudit(
        Decision.UpgradeFailed(pnl, BankruptReason.AiImplFailure, ai.capex * FailCapexFrac, ai.loan * FailLoanFrac, ai.down * FailDownFrac),
        audit,
      )
    else
      val effDraw = Scalar.randomBetween(TradToFullEffMin, TradToFullEffMax, rng)
      val eff     = Multiplier.One + (effDraw * firm.digitalReadiness.toScalar).toMultiplier
      DecisionWithAudit(
        Decision.Upgrade(pnl, TechState.Automated(eff), ai.capex, ai.loan, ai.down),
        audit.copy(upgradeEfficiencyDraw = Some(effDraw), upgradeEfficiencyMultiplier = Some(eff)),
      )

  /** Roll for hybrid upgrade: catastrophic failure, partial failure (bad
    * efficiency), or success (good efficiency).
    */
  private def rollHybridUpgrade(firm: State, pnl: PnL, hyb: UpgradeCandidate, hWkrs: Int, rng: RandomStream): DecisionWithAudit =
    val failRate = HybridBaseFailRate + (Share.One - firm.digitalReadiness) * HybridFailDrSens
    val draw     = Share.random(rng)
    val audit    = DecisionAudit(
      implementationFailureProbability = Some(failRate.min(Share.One)),
      implementationRoll = Some(draw),
    )
    if draw < failRate * CatastrophicFailFrac then
      DecisionWithAudit(
        Decision.UpgradeFailed(
          pnl,
          BankruptReason.HybridImplFailure,
          hyb.capex * FailCapexFrac,
          hyb.loan * FailLoanFrac,
          hyb.down * FailDownFrac,
        ),
        audit,
      )
    else if draw < failRate then
      val effDraw = Scalar.randomBetween(Scalar.Zero, BadHybridEffRange, rng)
      val badEff  = BadHybridEffBase + effDraw.toMultiplier
      DecisionWithAudit(
        Decision.Upgrade(pnl, TechState.Hybrid(hWkrs, badEff), hyb.capex, hyb.loan, hyb.down),
        audit.copy(upgradeEfficiencyDraw = Some(effDraw), upgradeEfficiencyMultiplier = Some(badEff)),
      )
    else
      val effDraw = Scalar.randomBetween(Scalar.Zero, GoodHybridEffRange, rng)
      val goodEff = Multiplier.One +
        (GoodHybridEffBase + effDraw) *
        (GoodHybridDrBlend + firm.digitalReadiness * GoodHybridDrBlend).toScalar.toMultiplier
      DecisionWithAudit(
        Decision.Upgrade(pnl, TechState.Hybrid(hWkrs, goodEff), hyb.capex, hyb.loan, hyb.down),
        audit.copy(upgradeEfficiencyDraw = Some(effDraw), upgradeEfficiencyMultiplier = Some(goodEff)),
      )

  /** Try upsize, digital readiness investment, downsize, or survive — fallback
    * when neither full-AI nor hybrid upgrade was chosen.
    */
  private def fallbackDecision(
      firm: State,
      financialStocks: FinancialStocks,
      pnl: PnL,
      w: World,
      operationalSignals: OperationalSignals,
      workers: Int,
      rng: RandomStream,
      nextTech: Int => TechState,
      drUpdate: Option[Share] = None,
      allowDigiInvest: Boolean = true,
  )(using p: SimParams): DecisionWithAudit =
    val nc            = financialStocks.cash + pnl.netAfterTax
    var audit         = DecisionAudit()
    // Firms now distinguish between a one-period desired workforce target,
    // a feasible near-term target, and the actual monthly adjustment.
    val desiredW      = desiredWorkers(firm, w, operationalSignals)
    val feasibleW     = feasibleWorkers(firm, workers, desiredW, pnl, nc)
    val gap           = feasibleW - workers
    val hiringThresh  = hiringAdjustmentThreshold(workers)
    val firingThresh  = Math.max(2, workers / 10)
    val shouldAdjust  = if gap > 0 then gap >= hiringThresh else -gap >= firingThresh
    if shouldAdjust then
      val adjustFrac = if gap > 0 then hiringAdjustFrac else firingAdjustFrac(firm)
      val magnitude  = stochasticAdjustmentMagnitude(Math.abs(gap), adjustFrac, rng)
      audit = audit.copy(
        laborAdjustmentResidualProbability = magnitude.residualProbability,
        laborAdjustmentResidualRoll = magnitude.residualRoll,
      )
      if magnitude.value > 0 then
        val adj     = magnitude.value * (if gap > 0 then 1 else -1)
        val newWkrs = (workers + adj).max(p.firm.minWorkersRetained)
        if newWkrs > workers && canFundUpsize(firm, pnl, nc, newWkrs - workers, w.householdMarket.marketWage) then
          return DecisionWithAudit(Decision.Upsize(pnl, newWkrs, nc, nextTech(newWkrs), drUpdate = drUpdate), audit)
        else if newWkrs < workers then return DecisionWithAudit(Decision.Downsize(pnl, newWkrs, nc, nextTech(newWkrs), drUpdate = drUpdate), audit)
    val digiCost: PLN = computeDigiInvestCost(firm)
    val canAfford     = nc > digiCost * DigiInvestCashMult
    val competitive   = w.real.automationRatio + w.real.hybridRatio * Share.decimal(5, 1)
    val diminishing   = Share.One - firm.digitalReadiness
    val digiProb      = (p.firm.digiInvestBaseProb * firm.riskProfile * diminishing * (Share.decimal(5, 1) + competitive)).min(Share.One)
    val digiRoll      = if allowDigiInvest && canAfford then Some(Share.random(rng)) else None
    audit = audit.merge(
      DecisionAudit(
        digitalInvestProbability = if allowDigiInvest then Some(digiProb) else None,
        digitalInvestRoll = digiRoll,
      ),
    )
    if allowDigiInvest && canAfford && digiRoll.exists(_ < digiProb) then
      val boost = p.firm.digiInvestBoost * diminishing
      val newDR = (firm.digitalReadiness + boost).min(Share.One)
      DecisionWithAudit(Decision.DigiInvest(pnl, digiCost, newDR), audit)
    else if nc < PLN.Zero then
      DecisionWithAudit(
        attemptDownsize(firm, pnl, nc, workers, nextTech, w.householdMarket.marketWage, BankruptReason.LaborCostInsolvency, drUpdate = drUpdate),
        audit,
      )
    else DecisionWithAudit(Decision.Survive(pnl, nc, drUpdate = drUpdate), audit)

  private def startupFallbackDecision(
      firm: State,
      financialStocks: FinancialStocks,
      pnl: PnL,
      currentWorkers: Int,
      nextTech: Int => TechState,
      wage: PLN,
  )(using p: SimParams): Decision =
    val nc            = financialStocks.cash + pnl.netAfterTax
    val targetWorkers = Math.max(currentWorkers, firm.startupTargetWorkers)
    if currentWorkers < targetWorkers && canFundUpsize(firm, pnl, nc, 1, wage) then Decision.Upsize(pnl, currentWorkers + 1, nc, nextTech(currentWorkers + 1))
    else if nc < PLN.Zero then
      if hasWorkingCapitalGrace(firm, pnl, nc) then Decision.Survive(pnl, nc)
      else attemptDownsize(firm, pnl, nc, currentWorkers, nextTech, wage, BankruptReason.LaborCostInsolvency)
    else Decision.Survive(pnl, nc)

  private[amorfati] def canFundUpsize(
      firm: State,
      pnl: PnL,
      cashAfterDecision: PLN,
      addedWorkers: Int,
      marketWage: PLN,
  )(using p: SimParams): Boolean =
    cashAfterDecision >= PLN.Zero ||
      firm.stateOwned ||
      (isInStartup(firm) &&
        cashAfterDecision.abs <= startupRunwayLimit(firm) +
        addedWorkers * (marketWage * effectiveWageMult(firm.sector)) * p.firm.startupHiringWorkingCapitalMonths) ||
      (pnl.netAfterTax >= PLN.Zero &&
        cashAfterDecision.abs <= addedWorkers * (marketWage * effectiveWageMult(firm.sector)) * p.firm.hiringWorkingCapitalMonths)

  /** Traditional firm: evaluate full-AI, hybrid, downsize, digital invest, or
    * survive/bankrupt. Dispatches to sub-evaluators.
    */
  private def decideTraditional(
      firm: State,
      financialStocks: FinancialStocks,
      w: World,
      executionMonth: ExecutionMonth,
      operationalSignals: OperationalSignals,
      lendRate: Rate,
      bankCreditDecision: PLN => CreditDecision,
      allFirms: Vector[State],
      workers: Int,
      rng: RandomStream,
      corpBondDebt: PLN,
  )(using p: SimParams): DecisionWithAudit =
    val pnl           = computePnL(
      firm,
      financialStocks,
      w.householdMarket.marketWage,
      operationalSignals.sectorDemandMult(firm.sector.toInt),
      w.priceLevel,
      w.external.gvc.importCostIndex,
      w.external.gvc.commodityPriceIndex,
      lendRate,
      executionMonth,
      corpBondDebt,
      w.real.productivityIndex,
    )
    if isInStartup(firm) then
      return DecisionWithAudit(
        startupFallbackDecision(firm, financialStocks, pnl, workers, TechState.Traditional(_), w.householdMarket.marketWage),
        DecisionAudit(),
      )
    val ai            = evaluateFullAi(firm, financialStocks, pnl, w, lendRate, bankCreditDecision)
    val (hyb, hWkrs)  = evaluateHybrid(firm, financialStocks, pnl, workers, w, lendRate, bankCreditDecision)
    val (pFull, pHyb) = adoptionProbabilities(firm, pnl, ai, hyb, executionMonth, w, allFirms)
    val roll          = Share.random(rng)
    val baseAudit     = DecisionAudit(
      fullAiFeasible = Some(ai.feasible),
      hybridFeasible = Some(hyb.feasible),
      fullAiAdoptionProbability = Some(pFull),
      hybridAdoptionProbability = Some(pHyb),
      adoptionRoll = Some(roll),
    ).merge(fullAiBankAudit(ai.credit))
      .merge(hybridBankAudit(hyb.credit))
      .merge(bankRejectedTechDemandAudit(ai, DecisionTrace.DecisionType.FullAiUpgrade))
      .merge(bankRejectedTechDemandAudit(hyb, DecisionTrace.DecisionType.HybridUpgrade))

    if roll < pFull then
      val upgrade = rollFullAiUpgrade(firm, pnl, ai, rng)
      DecisionWithAudit(
        upgrade.decision,
        baseAudit.merge(selectedTechBankAudit(ai.credit, ai.loan, DecisionTrace.DecisionType.FullAiUpgrade)).merge(upgrade.audit),
      )
    else if roll < pFull + pHyb then
      val upgrade = rollHybridUpgrade(firm, pnl, hyb, hWkrs, rng)
      DecisionWithAudit(
        upgrade.decision,
        baseAudit.merge(selectedTechBankAudit(hyb.credit, hyb.loan, DecisionTrace.DecisionType.HybridUpgrade)).merge(upgrade.audit),
      )
    else
      val fallback = fallbackDecision(firm, financialStocks, pnl, w, operationalSignals, workers, rng, nextTech = TechState.Traditional(_))
      DecisionWithAudit(fallback.decision, baseAudit.merge(fallback.audit))

  private def buildDecisionTrace(
      openingFirm: State,
      openingStocks: FinancialStocks,
      lendingRate: Rate,
      decision: DecisionWithAudit,
      result: Result,
  )(using p: SimParams): DecisionTrace =
    val d = decision.decision
    DecisionTrace(
      firmId = openingFirm.id,
      openingTech = openingFirm.tech,
      closingTech = result.firm.tech,
      decisionType = decisionType(d),
      bankruptcyReason = bankruptcyReason(d).orElse(bankruptcyReason(result.firm.tech)),
      sector = openingFirm.sector,
      foreignOwned = openingFirm.foreignOwned,
      stateOwned = openingFirm.stateOwned,
      cashBefore = openingStocks.cash,
      cashAfter = result.financialStocks.cash,
      firmLoanBefore = openingStocks.firmLoan,
      firmLoanAfter = result.financialStocks.firmLoan,
      realizedPostTaxProfit = result.realizedPostTaxProfit,
      signedRealizedPostTaxProfit = result.signedRealizedPostTaxProfit,
      grossInvestment = result.grossInvestment,
      principalRepaid = result.principalRepaid,
      digitalReadinessBefore = openingFirm.digitalReadiness,
      digitalReadinessAfter = result.firm.digitalReadiness,
      workersBefore = workerCount(openingFirm),
      workersAfter = workerCount(result.firm),
      capex = result.capexSpent,
      newLoan = result.newLoan,
      techCreditDecisionType = decision.audit.techCreditDecisionType,
      techCreditNeed = decision.audit.techCreditNeed.getOrElse(decisionCreditNeed(d)),
      techCreditAmount = result.techNewLoan,
      downPayment = downPayment(d),
      bankId = openingFirm.bankId,
      lendingRate = lendingRate,
      selectedBankApproval = decision.audit.selectedBankApproval,
      selectedBankApprovalProbability = decision.audit.selectedBankApprovalProbability,
      selectedBankApprovalRoll = decision.audit.selectedBankApprovalRoll,
      selectedBankApprovalAudit = decision.audit.selectedBankApprovalAudit,
      fullAiFeasible = decision.audit.fullAiFeasible,
      hybridFeasible = decision.audit.hybridFeasible,
      fullAiAdoptionProbability = decision.audit.fullAiAdoptionProbability,
      hybridAdoptionProbability = decision.audit.hybridAdoptionProbability,
      adoptionRoll = decision.audit.adoptionRoll,
      fullAiBankApproval = decision.audit.fullAiBankApproval,
      fullAiBankApprovalProbability = decision.audit.fullAiBankApprovalProbability,
      fullAiBankApprovalRoll = decision.audit.fullAiBankApprovalRoll,
      fullAiBankApprovalAudit = decision.audit.fullAiBankApprovalAudit,
      hybridBankApproval = decision.audit.hybridBankApproval,
      hybridBankApprovalProbability = decision.audit.hybridBankApprovalProbability,
      hybridBankApprovalRoll = decision.audit.hybridBankApprovalRoll,
      hybridBankApprovalAudit = decision.audit.hybridBankApprovalAudit,
      implementationFailureProbability = decision.audit.implementationFailureProbability,
      implementationRoll = decision.audit.implementationRoll,
      upgradeEfficiencyDraw = decision.audit.upgradeEfficiencyDraw,
      upgradeEfficiencyMultiplier = decision.audit.upgradeEfficiencyMultiplier,
      investmentCreditNeed = None,
      investmentCreditAmount = None,
      investmentBankApproval = None,
      investmentBankApprovalProbability = None,
      investmentBankApprovalRoll = None,
      investmentBankApprovalAudit = Banking.CreditApprovalAudit.empty,
      digitalInvestProbability = decision.audit.digitalInvestProbability,
      digitalInvestRoll = decision.audit.digitalInvestRoll,
      laborAdjustmentResidualProbability = decision.audit.laborAdjustmentResidualProbability,
      laborAdjustmentResidualRoll = decision.audit.laborAdjustmentResidualRoll,
    )

  private def refreshDecisionTraceClosing(trace: DecisionTrace, result: Result)(using p: SimParams): DecisionTrace =
    trace.copy(
      closingTech = result.firm.tech,
      cashAfter = result.financialStocks.cash,
      firmLoanAfter = result.financialStocks.firmLoan,
      realizedPostTaxProfit = result.realizedPostTaxProfit,
      signedRealizedPostTaxProfit = result.signedRealizedPostTaxProfit,
      grossInvestment = result.grossInvestment,
      principalRepaid = result.principalRepaid,
      digitalReadinessAfter = result.firm.digitalReadiness,
      workersAfter = workerCount(result.firm),
      capex = result.capexSpent,
      newLoan = result.newLoan,
      techCreditAmount = result.techNewLoan,
    )

  private def decisionType(decision: Decision): DecisionTrace.DecisionType =
    decision match
      case Decision.StayBankrupt                    => DecisionTrace.DecisionType.Bankrupt
      case Decision.Survive(_, _, _)                => DecisionTrace.DecisionType.Survive
      case Decision.GoBankrupt(_, _, _)             => DecisionTrace.DecisionType.Bankrupt
      case Decision.Upgrade(_, newTech, _, _, _, _) =>
        newTech match
          case _: TechState.Automated => DecisionTrace.DecisionType.FullAiUpgrade
          case _: TechState.Hybrid    => DecisionTrace.DecisionType.HybridUpgrade
          case _                      => DecisionTrace.DecisionType.Survive
      case Decision.UpgradeFailed(_, _, _, _, _)    => DecisionTrace.DecisionType.UpgradeFailed
      case Decision.Downsize(_, _, _, _, _)         => DecisionTrace.DecisionType.Downsize
      case Decision.Upsize(_, _, _, _, _)           => DecisionTrace.DecisionType.Upsize
      case Decision.DigiInvest(_, _, _)             => DecisionTrace.DecisionType.DigiInvest

  private def bankruptcyReason(decision: Decision): Option[BankruptReason] =
    decision match
      case Decision.GoBankrupt(_, _, reason)          => Some(reason)
      case Decision.UpgradeFailed(_, reason, _, _, _) => Some(reason)
      case _                                          => None

  private def bankruptcyReason(tech: TechState): Option[BankruptReason] =
    tech match
      case TechState.Bankrupt(reason) => Some(reason)
      case _                          => None

  private def downPayment(decision: Decision): Option[PLN] =
    decision match
      case Decision.Upgrade(_, _, _, _, downPayment, _) => Some(downPayment)
      case Decision.UpgradeFailed(_, _, _, _, down)     => Some(down)
      case _                                            => None

  private def decisionCreditNeed(decision: Decision): PLN =
    decision match
      case Decision.Upgrade(_, _, _, loan, _, _)    => loan
      case Decision.UpgradeFailed(_, _, _, loan, _) => loan
      case _                                        => PLN.Zero

  private def applyTechCreditDiagnostics(result: Result, decision: DecisionWithAudit): Result =
    val demand   = decision.audit.techCreditNeed.getOrElse(decisionCreditNeed(decision.decision)).max(result.techNewLoan)
    val approved = result.techNewLoan.min(demand)
    val rejected = (demand - approved).max(PLN.Zero)
    result.copy(
      techCreditDemand = demand,
      techCreditApproved = approved,
      techCreditRejected = rejected,
      techCreditRejectionBreakdown = CreditRejectionBreakdown.from(decision.audit.selectedBankApprovalAudit.rejectionReason, rejected),
    )

  // ---- Execute (pure dispatch, zero RandomStream calls) ----

  /** Pure dispatch: map `Decision` → `Result`. No RandomStream calls, no side
    * effects.
    */
  private def execute(firm: State, financialStocks: FinancialStocks, d: Decision)(using p: SimParams): Result =
    d match
      case Decision.StayBankrupt =>
        buildResult(firm, financialStocks, PnL.zero)

      case Decision.Survive(pnl, newCash, drUpdate) =>
        val stocks = financialStocks.copy(cash = newCash)
        buildResult(drUpdate.fold(firm)(dr => firm.copy(digitalReadiness = dr)), stocks, pnl)

      case Decision.GoBankrupt(pnl, cash, reason) =>
        buildResult(firm.copy(tech = TechState.Bankrupt(reason)), financialStocks.copy(cash = cash), pnl)

      case Decision.Upgrade(pnl, newTech, capex, loan, downPayment, drUpdate) =>
        val tImp   = capex * p.forex.techImportShare
        val f      = firm.copy(tech = newTech)
        val stocks = financialStocks.copy(
          firmLoan = financialStocks.firmLoan + loan,
          cash = financialStocks.cash + pnl.netAfterTax + loan - downPayment,
        )
        buildResult(
          drUpdate.fold(f)(dr => f.copy(digitalReadiness = dr)),
          stocks,
          pnl,
          capex = capex,
          techImports = tImp,
          newLoan = loan,
          techNewLoan = loan,
        )

      case Decision.UpgradeFailed(pnl, reason, capex, loan, down) =>
        val tImp = capex * p.forex.techImportShare
        buildResult(
          firm.copy(tech = TechState.Bankrupt(reason)),
          financialStocks.copy(
            cash = financialStocks.cash + pnl.netAfterTax + loan - down,
            firmLoan = financialStocks.firmLoan + loan,
          ),
          pnl,
          capex = capex,
          techImports = tImp,
          newLoan = loan,
          techNewLoan = loan,
        )

      case Decision.Downsize(pnl, _, adjustedCash, newTech, drUpdate) =>
        val f = firm.copy(tech = newTech)
        buildResult(drUpdate.fold(f)(dr => f.copy(digitalReadiness = dr)), financialStocks.copy(cash = adjustedCash), pnl)

      case Decision.Upsize(pnl, _, newCash, newTech, drUpdate) =>
        val f = firm.copy(tech = newTech)
        buildResult(drUpdate.fold(f)(dr => f.copy(digitalReadiness = dr)), financialStocks.copy(cash = newCash), pnl)

      case Decision.DigiInvest(pnl, cost, newDR) =>
        val nc = financialStocks.cash + pnl.netAfterTax
        buildResult(firm.copy(digitalReadiness = newDR), financialStocks.copy(cash = nc - cost), pnl)

  /** Assemble `Result` from updated `State` and `PnL`. Flow fields not set by
    * the decision (equity, investment, inventory, FDI, evasion) default to zero
    * — filled by post-processing steps.
    */
  private def buildResult(
      firm: State,
      financialStocks: FinancialStocks,
      pnl: PnL,
      capex: PLN = PLN.Zero,
      techImports: PLN = PLN.Zero,
      newLoan: PLN = PLN.Zero,
      techNewLoan: PLN = PLN.Zero,
  ): Result =
    Result(
      firm = advanceStartupLifecycle(firm.copy(accumulatedLoss = pnl.newAccumulatedLoss)),
      financialStocks = financialStocks,
      taxPaid = pnl.tax,
      realizedPostTaxProfit = pnl.netAfterTax.max(PLN.Zero),
      signedRealizedPostTaxProfit = pnl.netAfterTax,
      capexSpent = capex,
      techImports = techImports,
      newLoan = newLoan,
      techNewLoan = techNewLoan,
      equityIssuance = PLN.Zero,
      grossInvestment = PLN.Zero,
      bondIssuance = PLN.Zero,
      profitShiftCost = pnl.profitShiftCost,
      fdiRepatriation = PLN.Zero,
      inventoryChange = PLN.Zero,
      citEvasion = PLN.Zero,
      energyCost = pnl.energyCost,
      greenInvestment = PLN.Zero,
      principalRepaid = PLN.Zero,
    )

  private def advanceStartupLifecycle(firm: State): State =
    if isAlive(firm) && firm.startupMonthsLeft > 0 then firm.copy(startupMonthsLeft = firm.startupMonthsLeft - 1)
    else firm

  private def updateHiringSignalState(result: Result, prior: State, w: World, operationalSignals: OperationalSignals)(using p: SimParams): Result =
    if !isAlive(result.firm) then result
    else
      val currentWorkers = workerCount(prior)
      val desired        = desiredWorkers(prior, w, operationalSignals)
      val nextSignal     = nextHiringSignalMonths(prior, desired, currentWorkers)
      result.copy(firm = result.firm.copy(hiringSignalMonths = nextSignal))

  // ---- Post-processing pipeline ----

  /** Scheduled loan principal repayment: debt × amortRate per month. Reduces
    * the firm-loan and cash stocks; reports flow for SFC accounting. Bankrupt
    * firms and firms with zero debt skip.
    */
  private def applyLoanAmortization(r: Result)(using p: SimParams): Result =
    val f         = r.firm
    val stocks    = r.financialStocks
    if !isAlive(f) || stocks.firmLoan <= PLN.Zero then return r
    val principal = stocks.firmLoan * p.banking.firmLoanAmortRate
    val paid      = principal.min(stocks.cash.max(PLN.Zero))
    r.copy(
      financialStocks = stocks.copy(firmLoan = stocks.firmLoan - paid, cash = stocks.cash - paid),
      principalRepaid = paid,
    )

  /** Apply natural digital drift to all living firms (always-on). */
  private def applyDigitalDrift(r: Result)(using p: SimParams): Result =
    if p.firm.digiDrift <= Share.Zero then return r
    val f     = r.firm
    if !isAlive(f) then return r
    val newDR = (f.digitalReadiness + p.firm.digiDrift).min(Share.One)
    r.copy(firm = f.copy(digitalReadiness = newDR))

  /** Apply physical capital investment after firm decision. Depreciation,
    * replacement + expansion investment, cash-constrained.
    */
  private def applyInvestment(r: Result, sectorDemandPressure: Multiplier, bankCreditDecision: PLN => CreditDecision)(using p: SimParams): Result =
    val f                  = r.firm
    if !isAlive(f) then return r.copy(firm = f.copy(capitalStock = PLN.Zero))
    val stocks             = r.financialStocks
    val depRate            = p.capital.depRates(f.sector.toInt).monthly
    val depn: PLN          = f.capitalStock * depRate
    val postDepK           = f.capitalStock - depn
    val baseTargetK        = capitalPlanningWorkers(f) * p.capital.klRatios(f.sector.toInt)
    val invMult            = if f.stateOwned then StateOwned.directedInvestmentMultiplier(f.sector.toInt) else Multiplier.One
    val pressure           = sectorDemandPressure.deviationFromOne.clamp(Coefficient.Zero, Coefficient.One)
    val persistence        = Scalar.fraction(f.hiringSignalMonths.min(InvestmentSignalRampMonths), InvestmentSignalRampMonths)
    val demandTargetBoost  =
      ((pressure.toScalar * persistence) * p.capital.demandExpansionSensitivity).toMultiplier
    val targetK            = baseTargetK * (Multiplier.One + demandTargetBoost)
    val gap                = (targetK - postDepK).max(PLN.Zero)
    val desiredInv         = depn + (gap * p.capital.adjustSpeed * invMult)
    val cashInv            = desiredInv.min(stocks.cash.max(PLN.Zero))
    val creditNeed         = (desiredInv - cashInv).max(PLN.Zero) * p.capital.investmentCreditShare
    val creditDecision     = if creditNeed > PLN.Zero then Some(bankCreditDecision(creditNeed)) else None
    val creditInv          = if creditDecision.exists(_.approved) then creditNeed else PLN.Zero
    val rejectedCredit     = (creditNeed - creditInv).max(PLN.Zero)
    val rejectionBreakdown =
      creditDecision.fold(CreditRejectionBreakdown.zero)(credit => CreditRejectionBreakdown.from(credit.audit.rejectionReason, rejectedCredit))
    val actualInv          = (cashInv + creditInv).min(desiredInv)
    val newK               = postDepK + actualInv
    val investmentTrace    = r.decisionTrace.map: trace =>
      trace.copy(
        investmentCreditNeed = if creditNeed > PLN.Zero then Some(creditNeed) else None,
        investmentCreditAmount = if creditNeed > PLN.Zero then Some(creditInv) else None,
        investmentBankApproval = creditDecision.map(_.approved),
        investmentBankApprovalProbability = creditDecision.flatMap(_.approvalProbability),
        investmentBankApprovalRoll = creditDecision.flatMap(_.approvalRoll),
        investmentBankApprovalAudit = creditDecision.map(_.audit).getOrElse(Banking.CreditApprovalAudit.empty),
      )
    r.copy(
      firm = f.copy(capitalStock = newK),
      financialStocks = stocks.copy(
        cash = stocks.cash + creditInv - actualInv,
        firmLoan = stocks.firmLoan + creditInv,
      ),
      newLoan = r.newLoan + creditInv,
      grossInvestment = actualInv,
      investmentCreditDemand = creditNeed,
      investmentCreditApproved = creditInv,
      investmentCreditRejected = rejectedCredit,
      investmentCreditRejectionBreakdown = rejectionBreakdown,
      decisionTrace = investmentTrace,
    )

  // ---- PnL computation ----

  /** Residual "other" operating costs — base scaled by price and firm size,
    * reduced when physical capital, energy, or inventory costs are explicit.
    */
  private def otherCosts(firm: State, domesticPrice: PriceIndex)(using p: SimParams): PLN =
    val sizeFactor = Scalar.fraction(firm.initialSize, p.pop.workersPerFirm).toMultiplier
    val raw: PLN   = (domesticPrice * p.firm.otherCosts) * sizeFactor
    val afterCap   = raw * (Share.One - p.capital.costReplace)
    val afterEnerg = afterCap * (Share.One - p.climate.energyCostReplace)
    val adjusted   = afterEnerg * (Share.One - p.capital.inventoryCostReplace)
    adjusted * startupCostMultiplier(firm)

  /** AI/hybrid maintenance opex — domestic + imported split, sublinear in firm
    * size.
    */
  private def aiMaintenanceCost(firm: State, domesticPrice: PriceIndex, importPrice: PriceIndex)(using p: SimParams): PLN =
    val opexSizeFactor = Scalar.fraction(firm.initialSize, p.pop.workersPerFirm).pow(OpexSizeExponent).toMultiplier
    val priceFactor    = domesticPrice.toMultiplier * OpexDomesticShare + importPrice.toMultiplier * OpexImportShare
    firm.tech match
      case _: TechState.Automated => p.firm.aiOpex * priceFactor * opexSizeFactor * startupCostMultiplier(firm)
      case _: TechState.Hybrid    => p.firm.hybridOpex * priceFactor * opexSizeFactor * startupCostMultiplier(firm)
      case _                      => PLN.Zero

  /** Energy cost including EU ETS carbon surcharge, net of green capital
    * discount.
    */
  private def energyAndEtsCost(firm: State, revenue: PLN, month: ExecutionMonth, commodityPrice: PriceIndex)(using p: SimParams): PLN =
    val baseEnergy: PLN      = revenue * p.climate.energyCostShares(firm.sector.toInt)
    val monthsElapsed        = month.previousCompleted
    val etsGrowth            = (Scalar.One + p.climate.etsPriceDrift.monthly.toScalar).pow(monthsElapsed.toInt)
    val carbonSurcharge      = p.climate.carbonIntensity(firm.sector.toInt) * (etsGrowth - Scalar.One)
    val greenDiscount: Share = if firm.greenCapital > PLN.Zero then
      val targetGK = capitalPlanningWorkers(firm) * p.climate.greenKLRatios(firm.sector.toInt)
      if targetGK > PLN.Zero then p.climate.greenMaxDiscount * firm.greenCapital.ratioTo(targetGK).toShare.clamp(Share.Zero, Share.One)
      else Share.Zero
    else Share.Zero
    val discountedEnergy     = commodityPrice * (baseEnergy * (Share.One - greenDiscount))
    discountedEnergy * (Multiplier.One + carbonSurcharge.max(Scalar.Zero).toMultiplier)

  /** Monthly P&L: revenue minus all cost categories, CIT on positive profit. */
  private[amorfati] def computePnL(
      firm: State,
      financialStocks: FinancialStocks,
      wage: PLN,
      sectorDemandMult: Multiplier,
      domesticPrice: PriceIndex,
      importPrice: PriceIndex,
      commodityPrice: PriceIndex,
      lendRate: Rate,
      month: ExecutionMonth,
      corpBondDebt: PLN = PLN.Zero,
      productivityIndex: Multiplier = Multiplier.One,
  )(using p: SimParams): PnL =
    val revenue: PLN         = (domesticPrice * computeEffectiveCapacity(firm, productivityIndex)) * sectorDemandMult
    val labor: PLN           = workerCount(firm) * (wage * effectiveWageMult(firm.sector))
    val depnCost: PLN        = firm.capitalStock * p.capital.depRates(firm.sector.toInt).monthly
    val interest: PLN        = (financialStocks.firmLoan + corpBondDebt) * lendRate.monthly
    val inventoryCost: PLN   = firm.inventory * p.capital.inventoryCarryingCost.monthly
    val energyCost: PLN      = energyAndEtsCost(firm, revenue, month, commodityPrice)
    val prePsCosts           =
      labor + otherCosts(firm, domesticPrice) + depnCost + aiMaintenanceCost(firm, domesticPrice, importPrice) + interest + inventoryCost + energyCost
    val grossProfit          = revenue - prePsCosts
    val profitShiftCost: PLN =
      if firm.foreignOwned then grossProfit.max(PLN.Zero) * p.fdi.profitShiftRate
      else PLN.Zero
    val costs                = prePsCosts + profitShiftCost
    val profit               = revenue - costs

    // CIT with loss carryforward (Art. 7 ustawy o CIT):
    // - Losses accumulate when profit < 0
    // - When profitable: offset up to 50% of profit from accumulated losses
    // - Losses expire gradually (~5 year horizon via monthly decay)
    val (tax, newAccLoss) =
      if profit <= PLN.Zero then (PLN.Zero, firm.accumulatedLoss + profit.abs)
      else
        val maxOffset = profit * p.fiscal.citCarryforwardMaxShare
        val offset    = maxOffset.min(firm.accumulatedLoss)
        val taxable   = profit - offset
        val remaining = (firm.accumulatedLoss - offset) * (Multiplier.One - p.fiscal.citCarryforwardDecay.toMultiplier)
        (taxable * p.fiscal.citRate, remaining.max(PLN.Zero))

    PnL(revenue, costs, tax, profit - tax, profitShiftCost, energyCost, newAccLoss)

  private[amorfati] def realizedPostTaxProfit(
      firm: State,
      financialStocks: FinancialStocks,
      wage: PLN,
      sectorDemandMult: Multiplier,
      domesticPrice: PriceIndex,
      importPrice: PriceIndex,
      commodityPrice: PriceIndex,
      lendRate: Rate,
      month: ExecutionMonth,
      corpBondDebt: PLN = PLN.Zero,
  )(using p: SimParams): PLN =
    computePnL(
      firm,
      financialStocks,
      wage,
      sectorDemandMult,
      domesticPrice,
      importPrice,
      commodityPrice,
      lendRate,
      month,
      corpBondDebt,
      Multiplier.One,
    ).netAfterTax.max(PLN.Zero)

  /** Apply green capital investment — separate cash pool. Firms earmark
    * GreenBudgetShare of cash for green investment; physical capital
    * (applyInvestment) uses the remainder.
    */
  private def applyGreenInvestment(r: Result)(using p: SimParams): Result =
    val f           = r.firm
    if !isAlive(f) then return r.copy(firm = f.copy(greenCapital = PLN.Zero))
    val stocks      = r.financialStocks
    val depRate     = p.climate.greenDepRate.monthly
    val depn: PLN   = f.greenCapital * depRate
    val postDepGK   = f.greenCapital - depn
    val targetGK    = capitalPlanningWorkers(f) * p.climate.greenKLRatios(f.sector.toInt)
    val gap         = (targetGK - postDepGK).max(PLN.Zero)
    val invMult     = if f.stateOwned then StateOwned.directedInvestmentMultiplier(f.sector.toInt) else Multiplier.One
    val desiredInv  = depn + (gap * p.climate.greenAdjustSpeed * invMult)
    val greenBudget = stocks.cash.max(PLN.Zero) * p.climate.greenBudgetShare
    val actualInv   = desiredInv.min(greenBudget)
    val newGK       = postDepGK + actualInv
    r.copy(
      firm = f.copy(greenCapital = newGK),
      financialStocks = stocks.copy(cash = stocks.cash - actualInv),
      greenInvestment = actualInv,
    )

  /** Apply inventory accumulation/drawdown after firm decision. Inventories
    * converge toward a sector target based on realized sales; persistent demand
    * shortfalls lower the target instead of creating a free-standing unsold
    * capacity flow every month. Includes sector-specific spoilage and stress
    * liquidation at a discount when the firm is cash-negative.
    */
  private def applyInventory(r: Result, sectorDemandMult: Multiplier)(using p: SimParams): Result =
    val f                     = r.firm
    if !isAlive(f) then return r.copy(firm = f.copy(inventory = PLN.Zero))
    val stocks                = r.financialStocks
    val cap                   = computeCapacity(f)
    val realizedDemand        = sectorDemandMult.toShare.clamp(Share.Zero, Share.One)
    val realizedRevenue       = cap * realizedDemand
    // Spoilage
    val spoilRate             = p.capital.inventorySpoilageRates(f.sector.toInt).monthly
    val postSpoilage          = f.inventory - f.inventory * spoilRate
    // Target-based adjustment
    val targetInv             = realizedRevenue * p.capital.inventoryTargetRatios(f.sector.toInt)
    val desired               = (targetInv - postSpoilage) * p.capital.inventoryAdjustSpeed
    val replenishmentBudget   = realizedRevenue * p.capital.inventoryCostFraction
    val rawChange             = if desired > PLN.Zero then desired.min(replenishmentBudget) else desired
    // Can't draw down more than available
    val invChange             = rawChange.max(-postSpoilage)
    val newInv                = (postSpoilage + invChange).max(PLN.Zero)
    // Stress liquidation: if cash < 0, sell inventory at discount
    val (finalInv, cashBoost) = if stocks.cash < PLN.Zero && newInv > PLN.Zero then
      val liquidate = newInv.min(stocks.cash.abs / p.capital.inventoryLiquidationDisc)
      (newInv - liquidate, liquidate * p.capital.inventoryLiquidationDisc)
    else (newInv, PLN.Zero)
    val actualChange          = finalInv - f.inventory
    r.copy(
      firm = f.copy(inventory = finalInv),
      financialStocks = stocks.copy(cash = stocks.cash + cashBoost),
      inventoryChange = actualChange,
    )

  /** Effective shadow share for a sector — base share + cyclical adjustment,
    * clamped to [0, 1].
    */
  private def effectiveShadowShare(sector: SectorIdx, carriedInformalAdj: Share)(using p: SimParams): Share =
    (p.informal.sectorShares(sector.toInt) + carriedInformalAdj).min(Share.One)

  /** CIT evasion fraction for a sector — shadow share × CIT evasion rate. */
  private def citEvasionFrac(sector: SectorIdx, carriedInformalAdj: Share)(using p: SimParams): Share =
    effectiveShadowShare(sector, carriedInformalAdj) * p.informal.citEvasion

  /** Apply informal CIT evasion using the carried current-step shadow-economy
    * adjustment from world state.
    */
  private def applyInformalCitEvasion(r: Result, carriedInformalAdj: Share)(using p: SimParams): Result =
    if !isAlive(r.firm) || r.taxPaid <= PLN.Zero then return r
    val evaded = r.taxPaid * citEvasionFrac(r.firm.sector, carriedInformalAdj)
    r.copy(
      financialStocks = r.financialStocks.copy(cash = r.financialStocks.cash + evaded),
      taxPaid = r.taxPaid - evaded,
      citEvasion = evaded,
    )

  /** Apply FDI dividend repatriation for foreign-owned firms (post-tax,
    * cash-constrained).
    */
  private def applyFdiFlows(r: Result)(using p: SimParams): Result =
    if !r.firm.foreignOwned || !isAlive(r.firm) then return r
    val afterTaxProfit: PLN =
      if p.fiscal.citRate > Rate.Zero && r.taxPaid > PLN.Zero then r.taxPaid * ((Rate(1) / p.fiscal.citRate) - Scalar.One).toMultiplier
      else PLN.Zero
    val repatriation: PLN   =
      (afterTaxProfit.max(PLN.Zero) * p.fdi.repatriationRate).min(r.financialStocks.cash.max(PLN.Zero))
    if repatriation <= PLN.Zero then return r
    r.copy(financialStocks = r.financialStocks.copy(cash = r.financialStocks.cash - repatriation), fdiRepatriation = repatriation)
