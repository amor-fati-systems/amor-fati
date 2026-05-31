package com.boombustgroup.amorfati.agents

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.{OperationalSignals, World}
import com.boombustgroup.amorfati.agents.firm.*
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

  type HiringDiagnostics        = FirmHiringDiagnostics
  type FinancialStocks          = FirmFinancialStocks
  type State                    = FirmState
  type Result                   = FirmResult
  type DecisionTrace            = FirmDecisionTrace
  type PnL                      = FirmPnL
  type Decision                 = FirmDecision
  type CreditDecision           = FirmCreditDecision
  type CreditRejectionBreakdown = FirmCreditRejectionBreakdown

  val HiringDiagnostics: FirmHiringDiagnostics.type               = FirmHiringDiagnostics
  val FinancialStocks: FirmFinancialStocks.type                   = FirmFinancialStocks
  val State: FirmState.type                                       = FirmState
  val Result: FirmResult.type                                     = FirmResult
  val DecisionTrace: FirmDecisionTrace.type                       = FirmDecisionTrace
  val PnL: FirmPnL.type                                           = FirmPnL
  val Decision: FirmDecision.type                                 = FirmDecision
  val CreditDecision: FirmCreditDecision.type                     = FirmCreditDecision
  val CreditRejectionBreakdown: FirmCreditRejectionBreakdown.type = FirmCreditRejectionBreakdown

  // ---- Facade delegates ----

  def isAlive(f: State): Boolean =
    FirmProduction.isAlive(f)

  def workerCount(f: State)(using SimParams): Int =
    FirmProduction.workerCount(f)

  def skeletonCrew(f: State)(using p: SimParams): Int =
    FirmProduction.skeletonCrew(f)

  def isInStartup(f: State): Boolean =
    FirmProduction.isInStartup(f)

  private[amorfati] def capitalPlanningWorkers(f: State)(using p: SimParams): Int =
    FirmProduction.capitalPlanningWorkers(f)

  def effectiveWageMult(sectorIdx: SectorIdx)(using p: SimParams): Multiplier =
    FirmProduction.effectiveWageMult(sectorIdx)

  def computeCapacity(f: State)(using p: SimParams): PLN =
    FirmProduction.computeCapacity(f)

  def computeEffectiveCapacity(f: State, productivityIndex: Multiplier)(using p: SimParams): PLN =
    FirmProduction.computeEffectiveCapacity(f, productivityIndex)

  private[amorfati] def cesOutput(alpha: Share, k: Multiplier, l: Multiplier, sigma: Sigma): Multiplier =
    FirmProduction.cesOutput(alpha, k, l, sigma)

  private[agents] def desiredWorkers(f: State, w: World, operationalSignals: OperationalSignals)(using p: SimParams): Int =
    FirmLaborPlanning.desiredWorkers(f, w, operationalSignals)

  private[amorfati] def monthlyHiringHeadroom(workers: Int): Int =
    FirmLaborPlanning.monthlyHiringHeadroom(workers)

  private[agents] def hiringAdjustmentThreshold(workers: Int): Int =
    FirmLaborPlanning.hiringAdjustmentThreshold(workers)

  private[agents] def requiredHiringSignalMonths(workers: Int): Int =
    FirmLaborPlanning.requiredHiringSignalMonths(workers)

  private[agents] def nextHiringSignalMonths(firm: State, desired: Int, workers: Int): Int =
    FirmLaborPlanning.nextHiringSignalMonths(firm, desired, workers)

  private[agents] def stochasticAdjustmentMagnitude(absGap: Int, adjustFrac: Share, rng: RandomStream): AdjustmentMagnitude =
    FirmLaborPlanning.stochasticAdjustmentMagnitude(absGap, adjustFrac, rng)

  private[agents] def hiringAdjustFrac(using p: SimParams): Share =
    FirmLaborPlanning.hiringAdjustFrac

  private[agents] def firingAdjustFrac(firm: State)(using p: SimParams): Share =
    FirmLaborPlanning.firingAdjustFrac(firm)

  private[agents] def feasibleWorkers(
      firm: State,
      workers: Int,
      desired: Int,
      pnl: PnL,
      cashAfterDecision: PLN,
  )(using p: SimParams): Int =
    FirmLaborPlanning.feasibleWorkers(firm, workers, desired, pnl, cashAfterDecision)

  private[amorfati] def applyOperationalHiringSlack(currentWorkers: Int, rawTarget: Int, minWorkers: Int, slackFactor: Multiplier): Int =
    FirmLaborPlanning.applyOperationalHiringSlack(currentWorkers, rawTarget, minWorkers, slackFactor)

  private[amorfati] def hiringDiagnostics(
      firm: State,
      financialStocks: FinancialStocks,
      w: World,
      operationalSignals: OperationalSignals,
  )(using p: SimParams): HiringDiagnostics =
    FirmLaborPlanning.hiringDiagnostics(firm, financialStocks, w, operationalSignals)

  def computeAiCapex(f: State)(using p: SimParams): PLN =
    FirmTechnologyPlanning.computeAiCapex(f)

  def computeHybridCapex(f: State)(using p: SimParams): PLN =
    FirmTechnologyPlanning.computeHybridCapex(f)

  def computeDigiInvestCost(f: State)(using p: SimParams): PLN =
    FirmTechnologyPlanning.computeDigiInvestCost(f)

  def computeLocalAutoRatio(firm: State, firms: Vector[State]): Share =
    FirmTechnologyPlanning.computeLocalAutoRatio(firm, firms)

  def sigmaThreshold(sigma: Sigma): Multiplier =
    FirmTechnologyPlanning.sigmaThreshold(sigma)

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
    FirmProcessPipeline.processWithCreditAudit(
      firm,
      financialStocks,
      w,
      executionMonth,
      operationalSignals,
      lendRate,
      bankCreditDecision,
      allFirms,
      rng,
      corpBondDebt,
      traceDecision,
    )

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
    FirmOperatingDecision.attemptDownsize(firm, pnl, nc, workers, newTech, wage, reason, drUpdate)

  private[amorfati] def startupRunwayLimit(firm: State)(using p: SimParams): PLN =
    FirmStartupLifecycle.startupRunwayLimit(firm)

  private[amorfati] def hasWorkingCapitalGrace(firm: State, pnl: PnL, cashAfterDecision: PLN)(using p: SimParams): Boolean =
    FirmOperatingDecision.hasWorkingCapitalGrace(firm, pnl, cashAfterDecision)

  private[amorfati] def adoptionWillingnessMultiplier(month: ExecutionMonth, localAuto: Share)(using p: SimParams): Share =
    FirmTechnologyAdoption.adoptionWillingnessMultiplier(month, localAuto)

  private[amorfati] def canFundUpsize(
      firm: State,
      pnl: PnL,
      cashAfterDecision: PLN,
      addedWorkers: Int,
      marketWage: PLN,
  )(using p: SimParams): Boolean =
    FirmOperatingDecision.canFundUpsize(firm, pnl, cashAfterDecision, addedWorkers, marketWage)

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
    FirmProfitAndLoss.computePnL(
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
      productivityIndex,
    )

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
    FirmProfitAndLoss.realizedPostTaxProfit(
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
    )
