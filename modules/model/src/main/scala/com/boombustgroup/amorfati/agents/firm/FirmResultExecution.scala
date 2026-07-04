package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.agents.TechState
import com.boombustgroup.amorfati.agents.Firm.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Executes a selected firm decision into closing state and primary flows.
  *
  * This module is pure decision application: no random draws and no secondary
  * monthly adjustments such as investment, inventories, or informal-economy tax
  * effects.
  */
private[agents] object FirmResultExecution:
  // ---- Execute (pure dispatch, zero RandomStream calls) ----

  /** Pure dispatch: map `Decision` → `Result`. No RandomStream calls, no side
    * effects.
    */
  private[agents] def execute(firm: State, financialStocks: FinancialStocks, d: Decision)(using p: SimParams): Result =
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
      firm = FirmStartupLifecycle.advanceStartupLifecycle(firm.copy(accumulatedLoss = pnl.newAccumulatedLoss)),
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
