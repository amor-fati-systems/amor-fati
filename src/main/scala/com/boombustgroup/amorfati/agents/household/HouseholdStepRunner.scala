package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.{BankRates, HhFinancialDistressState, HhStatus, Household, PerBankFlow}
import com.boombustgroup.amorfati.agents.household.HouseholdStepAccumulator.StepTotals
import com.boombustgroup.amorfati.agents.household.HouseholdStepTypes.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.World
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Orchestrates one household-agent month while delegating domain logic to
  * income, credit, liquidity, distress, labor, and aggregate modules.
  */
private[agents] object HouseholdStepRunner:

  /** Executes the household month and returns updated household state, stocks,
    * aggregates, per-bank flows, and micro diagnostics.
    */
  def step(
      households: Vector[Household.State],
      financialStocks: Vector[Household.FinancialStocks],
      world: World,
      marketWage: PLN,
      reservationWage: PLN,
      importAdj: Share,
      rng: RandomStream,
      nBanks: Int = 1,
      bankRates: Option[BankRates] = None,
      equityIndexReturn: Rate = Rate.Zero,
      sectorWages: Option[Vector[PLN]] = None,
      sectorVacancies: Option[Vector[Int]] = None,
      consumerCreditGate: Option[Household.ConsumerCreditGate] = None,
  )(using p: SimParams): Household.StepResult =
    require(
      households.length == financialStocks.length,
      s"Household.step requires aligned households and financialStocks, got ${households.length} households and ${financialStocks.length} stock rows",
    )
    var stockCheck = 0
    while stockCheck < financialStocks.length do
      require(
        financialStocks(stockCheck).demandDeposit >= PLN.Zero,
        "Household.step requires non-negative opening demandDeposit balances; liquidity shortfalls must enter as consumer-loan stocks",
      )
      stockCheck += 1

    val distressedIds = HouseholdDistressMachine.buildDistressedSet(households)
    val laborContext  = HouseholdLaborTransitionContext.fromOptions(sectorWages, sectorVacancies)
    val updatedRows   = new Array[Household.State](households.length)
    val stockRows     = new Array[Household.FinancialStocks](financialStocks.length)
    val monthlyRows   = new Array[Household.MonthlyFlow](households.length)
    val flowBuilder   = Vector.newBuilder[BankedMonthlyResult]
    val totals        = StepTotals()

    var i = 0
    while i < households.length do
      val hh     = households(i)
      val stocks = financialStocks(i)
      if hh.status == HhStatus.Bankrupt then
        val bankruptHh = hh.copy(financialDistressState = HhFinancialDistressState.Bankruptcy)
        updatedRows(i) = bankruptHh
        stockRows(i) = stocks
        monthlyRows(i) = Household.MonthlyFlow.inactive(hh.id, stocks)
      else
        val result = HouseholdMonthlyFlowConstruction.processHousehold(
          hh,
          stocks,
          world,
          rng,
          bankRates,
          equityIndexReturn,
          laborContext,
          distressedIds,
          consumerCreditGate,
        )
        flowBuilder += ((hh.bankId, result))
        totals.add(result)
        updatedRows(i) = result.newState
        stockRows(i) = result.financialStocks
        monthlyRows(i) = HouseholdMonthlyFlowConstruction.monthlyFlow(hh, stocks, result)
      i += 1

    val updated                          = updatedRows.toVector
    val stocks                           = stockRows.toVector
    val flows                            = flowBuilder.result()
    val monthly                          = monthlyRows.toVector
    val agg                              = HouseholdAggregateComputation.computeAggregates(updated, stocks, marketWage, reservationWage, importAdj, totals)
    val pbf: Option[Vector[PerBankFlow]] =
      if bankRates.isDefined then Some(HouseholdPerBankFlowAggregation.buildPerBankFlows(flows, nBanks)) else None

    var monthlyLiquidity = PLN.Zero
    var j                = 0
    while j < monthlyRows.length do
      monthlyLiquidity = monthlyLiquidity + monthlyRows(j).liquidityShortfallFinancing
      j += 1
    require(
      monthlyLiquidity == agg.totalLiquidityShortfallFinancing,
      "Household.step monthly flow diagnostics must reconcile to aggregate liquidity shortfall financing",
    )
    require(
      agg.totalLiquidityShortfallComponents == agg.totalLiquidityShortfallFinancing,
      "Household.step shortfall components must reconcile to aggregate liquidity shortfall financing",
    )
    require(
      agg.totalConsumerLoanDefault + agg.totalLiquidityBridgeChargeOff == agg.totalConsumerDefault,
      "Household.step consumer default components must reconcile to aggregate consumer default",
    )
    Household.StepResult(updated, agg, pbf, stocks, monthly)
