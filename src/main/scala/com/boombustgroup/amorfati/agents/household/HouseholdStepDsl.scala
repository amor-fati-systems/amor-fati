package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.{HhFinancialDistressState, HhStatus, Household}
import com.boombustgroup.amorfati.agents.household.HouseholdStepAccumulator.StepTotals
import com.boombustgroup.amorfati.agents.household.HouseholdStepSemantics.*
import com.boombustgroup.amorfati.agents.household.HouseholdStepTypes.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.MonthWorkflow
import com.boombustgroup.amorfati.engine.MonthWorkflow.Program
import com.boombustgroup.amorfati.types.*

/** Identity-DSL for the household monthly batch step.
  *
  * Each method names one temporal boundary around the existing data-oriented
  * implementation: validate opening rows, execute households, aggregate public
  * totals, validate diagnostics, and close into the public result.
  */
private[household] object HouseholdStepDsl:

  /** Opens one household batch from the public `Household.step` argument
    * surface.
    */
  def open(input: Input): Program[OpeningInput] =
    MonthWorkflow.pure(HouseholdStepSemantics.opening(input))

  /** Validates aligned opening rows and derives shared month context. */
  def prepare(opening: OpeningInput): Program[PreparedInput] =
    val input           = opening.input
    val households      = input.opening.households
    val financialStocks = input.opening.financialStocks
    require(
      households.length == financialStocks.length,
      s"Household.step requires aligned households and financialStocks, got ${households.length} households and ${financialStocks.length} stock rows",
    )
    var stockCheck      = 0
    while stockCheck < financialStocks.length do
      require(
        financialStocks(stockCheck).demandDeposit >= PLN.Zero,
        "Household.step requires non-negative opening demandDeposit balances; liquidity shortfalls must enter as consumer-loan stocks",
      )
      stockCheck += 1

    MonthWorkflow.pure(
      HouseholdStepSemantics.preparedMonth(
        PreparedMonth(
          input = input,
          distressedIds = HouseholdDistressMachine.buildDistressedSet(households),
          laborContext = HouseholdLaborTransitionContext.fromOptions(input.market.sectorWages, input.market.sectorVacancies),
        ),
      ),
    )

  /** Executes every household row while preserving array-backed hot paths. */
  def process(prepared: PreparedInput)(using SimParams): Program[ProcessedHouseholds] =
    val context         = prepared.month
    val input           = context.input
    val households      = input.opening.households
    val financialStocks = input.opening.financialStocks
    val updatedRows     = new Array[Household.State](households.length)
    val stockRows       = new Array[Household.FinancialStocks](financialStocks.length)
    val monthlyRows     = new Array[Household.MonthlyFlow](households.length)
    val flowBuilder     = Vector.newBuilder[BankedMonthlyResult]
    val totals          = StepTotals()

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
          input.opening.world,
          input.stochastic.rng,
          input.credit.bankRates,
          input.market.equityIndexReturn,
          context.laborContext,
          context.distressedIds,
          input.credit.consumerCreditGate,
        )
        flowBuilder += ((hh.bankId, result))
        totals.add(result)
        updatedRows(i) = result.newState
        stockRows(i) = result.financialStocks
        monthlyRows(i) = HouseholdMonthlyFlowConstruction.monthlyFlow(hh, stocks, result)
      i += 1

    MonthWorkflow.pure(
      HouseholdStepSemantics.processedHouseholds(
        ProcessedRows(
          input = input,
          updatedRows = updatedRows,
          stockRows = stockRows,
          monthlyRows = monthlyRows,
          bankedFlows = flowBuilder.result(),
          totals = totals,
        ),
      ),
    )

  /** Converts row buffers into immutable public aggregates and per-bank flows.
    */
  def aggregate(processed: ProcessedHouseholds)(using SimParams): Program[AggregatedHouseholds] =
    val rows    = processed.rows
    val input   = rows.input
    val market  = input.market
    val credit  = input.credit
    val updated = rows.updatedRows.toVector
    val stocks  = rows.stockRows.toVector
    val monthly = rows.monthlyRows.toVector
    val agg     =
      HouseholdAggregateComputation.computeAggregates(updated, stocks, market.marketWage, market.reservationWage, market.importAdj, rows.totals)
    val pbf     =
      if credit.bankRates.isDefined then Some(HouseholdPerBankFlowAggregation.buildPerBankFlows(rows.bankedFlows, credit.nBanks))
      else None

    MonthWorkflow.pure(
      HouseholdStepSemantics.aggregatedHouseholds(
        AggregatedRows(
          updated = updated,
          stocks = stocks,
          monthly = monthly,
          aggregates = agg,
          perBankFlows = pbf,
        ),
      ),
    )

  /** Checks monthly diagnostics against aggregate accounting before closing. */
  def validate(aggregated: AggregatedHouseholds): Program[ValidatedHouseholds] =
    val rows             = aggregated.rows
    val monthlyRows      = rows.monthly
    val agg              = rows.aggregates
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
    MonthWorkflow.pure(HouseholdStepSemantics.validatedHouseholds(rows))

  /** Closes the household batch into the public `Household.StepResult`. */
  def close(validated: ValidatedHouseholds): Program[ClosedResult] =
    val rows = validated.validatedRows
    MonthWorkflow.pure(
      HouseholdStepSemantics.closedResult(
        Household.StepResult(
          households = rows.updated,
          aggregates = rows.aggregates,
          perBankFlows = rows.perBankFlows,
          financialStocks = rows.stocks,
          monthlyFlows = rows.monthly,
        ),
      ),
    )
