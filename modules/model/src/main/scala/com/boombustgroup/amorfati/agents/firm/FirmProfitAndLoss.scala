package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.agents.TechState
import com.boombustgroup.amorfati.agents.Firm.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.mechanisms.ClimatePolicy
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.amorfati.agents.firm.FirmCalibration.*
import com.boombustgroup.amorfati.agents.firm.FirmStartupLifecycle.startupCostMultiplier

/** Computes the monthly firm profit-and-loss statement.
  *
  * The P&L module owns revenue, operating-cost, ETS, interest, profit-shifting,
  * and CIT loss-carryforward accounting. It does not choose decisions or mutate
  * firm state.
  */
private[agents] object FirmProfitAndLoss:
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
    val carbonSurcharge      = ClimatePolicy.carbonSurcharge(firm.sector, month.previousCompleted.toInt)
    val greenDiscount: Share = if firm.greenCapital > PLN.Zero then
      val targetGK = capitalPlanningWorkers(firm) * p.climate.greenKLRatios(firm.sector.toInt)
      if targetGK > PLN.Zero then p.climate.greenMaxDiscount * firm.greenCapital.ratioTo(targetGK).toShare.clamp(Share.Zero, Share.One)
      else Share.Zero
    else Share.Zero
    val discountedEnergy     = commodityPrice * (baseEnergy * (Share.One - greenDiscount))
    discountedEnergy * (Multiplier.One + carbonSurcharge)

  /** Monthly P&L: revenue minus all cost categories, CIT on positive profit. */
  private[agents] def computePnL(
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

  private[agents] def realizedPostTaxProfit(
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
