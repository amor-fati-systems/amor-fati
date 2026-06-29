package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.Household
import com.boombustgroup.amorfati.agents.household.HouseholdStepTypes.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Consumption priority, liquidity shortfall attribution, and non-negative
  * demand-deposit settlement.
  */
private[agents] object HouseholdLiquidityWaterfall:

  /** Pays basic consumption first, then obligations, then affordable
    * discretionary consumption.
    */
  def applyConsumptionWaterfall(
      openingDemandDeposit: PLN,
      income: PLN,
      approvedConsumerLoan: PLN,
      obligations: PLN,
      desiredConsumption: PLN,
  )(using p: SimParams): ConsumptionWaterfall =
    if desiredConsumption <= PLN.Zero then ConsumptionWaterfall(desiredConsumption, desiredConsumption, PLN.Zero, PLN.Zero)
    else
      val desiredPositive      = desiredConsumption.max(PLN.Zero)
      val basicNeed            = p.household.basicConsumptionFloor.min(desiredPositive)
      val availableCash        = (openingDemandDeposit + income + approvedConsumerLoan).max(PLN.Zero)
      val paidBasic            = basicNeed.min(availableCash)
      val availableAfterBasic  = (availableCash - paidBasic).max(PLN.Zero)
      val availableAfterBills  = (availableAfterBasic - obligations.max(PLN.Zero)).max(PLN.Zero)
      val desiredDiscretionary = (desiredPositive - basicNeed).max(PLN.Zero)
      val paidDiscretionary    = desiredDiscretionary.min(availableAfterBills)
      ConsumptionWaterfall(
        desiredConsumption = desiredConsumption,
        actualConsumption = paidBasic + paidDiscretionary,
        unmetBasicConsumption = basicNeed - paidBasic,
        discretionaryConsumptionCompression = desiredDiscretionary - paidDiscretionary,
      )

  /** Attributes a negative raw demand-deposit settlement to diagnostic
    * shortfall buckets in a stable cash-priority order.
    */
  def attributeLiquidityShortfall(f: MonthlyFlows, rawDemandDeposit: PLN, temporaryOutflow: PLN): Household.LiquidityShortfallComponents =
    if rawDemandDeposit >= PLN.Zero then Household.LiquidityShortfallComponents.Zero
    else
      var available = f.financialStocks.demandDeposit + f.income + f.credit.newLoan

      def gap(outflow: PLN): PLN =
        if outflow <= PLN.Zero then
          available = available - outflow
          PLN.Zero
        else if available >= outflow then
          available = available - outflow
          PLN.Zero
        else
          val shortfall = outflow - available.max(PLN.Zero)
          available = PLN.Zero
          shortfall

      val components = Household.LiquidityShortfallComponents(
        consumptionShortfall = gap(f.consumption),
        rentArrears = gap(f.hh.monthlyRent),
        mortgageArrears = gap(f.debtService),
        consumerDebtArrears = gap(f.credit.debtService),
        temporaryOverdraft = gap(f.remittance + temporaryOutflow),
      )
      val expected   = PLN.Zero - rawDemandDeposit
      require(
        components.total == expected,
        s"Household liquidity shortfall components must sum to settlement shortfall, got ${components.total} and $expected",
      )
      components

  /** Converts any residual negative demand-deposit settlement into explicit
    * same-month bridge charge-off diagnostics without treating it as ordinary
    * consumer-loan principal default.
    */
  def settleLiquidityShortfall(rawDemandDeposit: PLN, credit: CreditResult, components: Household.LiquidityShortfallComponents): (PLN, CreditResult) =
    if rawDemandDeposit >= PLN.Zero then (rawDemandDeposit, credit)
    else
      val shortfall = PLN.Zero - rawDemandDeposit
      require(
        components.total == shortfall,
        s"Household liquidity shortfall settlement requires components to match total, got ${components.total} and $shortfall",
      )
      (
        PLN.Zero,
        credit.copy(
          liquidityShortfall = credit.liquidityShortfall + components,
        ),
      )
