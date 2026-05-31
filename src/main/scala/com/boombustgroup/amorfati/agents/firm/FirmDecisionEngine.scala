package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.agents.{BankruptReason, TechState}
import com.boombustgroup.amorfati.agents.Firm.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.{OperationalSignals, World}
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Firm decision dispatch for one agent month.
  *
  * The engine routes by current technology state and keeps the simple automated
  * path local. Technology-adoption paths live in [[FirmTechnologyAdoption]];
  * operating fallback lives in [[FirmOperatingDecision]].
  */
private[agents] object FirmDecisionEngine:

  /** Dispatch to tech-specific decision logic. Contains all RandomStream calls.
    */
  private[agents] def decide(
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
        FirmTechnologyAdoption.decideHybrid(
          firm,
          financialStocks,
          w,
          executionMonth,
          operationalSignals,
          lendRate,
          bankCreditDecision,
          wkrs,
          aiEff,
          rng,
          corpBondDebt,
        )
      case TechState.Traditional(wkrs)   =>
        FirmTechnologyAdoption.decideTraditional(
          firm,
          financialStocks,
          w,
          executionMonth,
          operationalSignals,
          lendRate,
          bankCreditDecision,
          allFirms,
          wkrs,
          rng,
          corpBondDebt,
        )

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
