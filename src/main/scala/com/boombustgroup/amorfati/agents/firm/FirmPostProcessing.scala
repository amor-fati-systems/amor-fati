package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.agents.{Banking, StateOwned}
import com.boombustgroup.amorfati.agents.Firm.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.{OperationalSignals, World}
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.amorfati.agents.firm.FirmCalibration.*

/** Applies deterministic month-end firm adjustments after decision execution.
  *
  * These stages settle amortization, capital investment, digital drift,
  * inventories, FDI cash flows, and informal-economy CIT leakage without
  * changing the stochastic decision that was already selected.
  */
private[agents] object FirmPostProcessing:
  // ---- Post-processing pipeline ----

  /** Persists demand pressure as the next month's hiring-signal state. */
  private[agents] def updateHiringSignalState(
      result: Result,
      prior: State,
      w: World,
      operationalSignals: OperationalSignals,
      cachedDesiredWorkers: Option[Int] = None,
  )(using p: SimParams): Result =
    if !isAlive(result.firm) then result
    else
      val currentWorkers = workerCount(result.firm)
      val desired        = cachedDesiredWorkers.getOrElse(desiredWorkers(prior, w, operationalSignals))
      val nextSignal     = nextHiringSignalMonths(prior, desired, currentWorkers)
      result.copy(firm = result.firm.copy(hiringSignalMonths = nextSignal))

  /** Scheduled loan principal repayment: debt × amortRate per month. Reduces
    * the firm-loan and cash stocks; reports flow for SFC accounting. Bankrupt
    * firms and firms with zero debt skip.
    */
  private[agents] def applyLoanAmortization(r: Result)(using p: SimParams): Result =
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
  private[agents] def applyDigitalDrift(r: Result)(using p: SimParams): Result =
    if p.firm.digiDrift <= Share.Zero then return r
    val f     = r.firm
    if !isAlive(f) then return r
    val newDR = (f.digitalReadiness + p.firm.digiDrift).min(Share.One)
    r.copy(firm = f.copy(digitalReadiness = newDR))

  /** Apply physical capital investment after firm decision. Depreciation,
    * replacement + expansion investment, cash-constrained.
    */
  private[agents] def applyInvestment(r: Result, sectorDemandPressure: Multiplier, bankCreditDecision: PLN => CreditDecision)(using p: SimParams): Result =
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
    val availableCash      = stocks.cash.max(PLN.Zero)
    val targetDebtNeed     = desiredInv * p.capital.investmentDebtTargetShare
    val shortfallDebtNeed  = (desiredInv - availableCash).max(PLN.Zero) * p.capital.investmentCreditShare
    val creditNeed         = targetDebtNeed.max(shortfallDebtNeed).min(desiredInv)
    val creditDecision     = if creditNeed > PLN.Zero then Some(bankCreditDecision(creditNeed)) else None
    val creditInv          = if creditDecision.exists(_.approved) then creditNeed else PLN.Zero
    val rejectedCredit     = (creditNeed - creditInv).max(PLN.Zero)
    val rejectionBreakdown =
      creditDecision.fold(CreditRejectionBreakdown.zero)(credit => CreditRejectionBreakdown.from(credit.audit.rejectionReason, rejectedCredit))
    val cashInv            = (desiredInv - creditInv).max(PLN.Zero).min(availableCash)
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

  /** Apply green capital investment — separate cash pool. Firms earmark
    * GreenBudgetShare of cash for green investment; physical capital
    * (applyInvestment) uses the remainder.
    */
  private[agents] def applyGreenInvestment(r: Result)(using p: SimParams): Result =
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
  private[agents] def applyInventory(r: Result, sectorDemandMult: Multiplier)(using p: SimParams): Result =
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
    (p.informal.sectorShares(sector.toInt) + carriedInformalAdj).max(Share.Zero).min(Share.One)

  /** CIT evasion fraction for a sector — shadow share × CIT evasion rate. */
  private def citEvasionFrac(sector: SectorIdx, carriedInformalAdj: Share)(using p: SimParams): Share =
    effectiveShadowShare(sector, carriedInformalAdj) * p.informal.citEvasion

  /** Apply informal CIT evasion using the carried current-step shadow-economy
    * adjustment from world state.
    */
  private[agents] def applyInformalCitEvasion(r: Result, carriedInformalAdj: Share)(using p: SimParams): Result =
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
  private[agents] def applyFdiFlows(r: Result)(using p: SimParams): Result =
    if !r.firm.foreignOwned || !isAlive(r.firm) then return r
    val afterTaxProfit: PLN = r.signedRealizedPostTaxProfit.max(PLN.Zero)
    val repatriation: PLN   =
      (afterTaxProfit * p.fdi.repatriationRate).min(r.financialStocks.cash.max(PLN.Zero))
    if repatriation <= PLN.Zero then return r
    r.copy(financialStocks = r.financialStocks.copy(cash = r.financialStocks.cash - repatriation), fdiRepatriation = repatriation)
