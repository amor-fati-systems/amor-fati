package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.types.*

/** Physical capital and inventory accumulation at the firm level.
  *
  * Two mechanisms: (1) physical capital with per-sector capital-labor ratios,
  * Cobb-Douglas production, and vintage depreciation; (2) firm-level
  * inventories with per-sector target ratios, spoilage, carrying costs, and
  * stress liquidation. GDP accounting includes inventory investment (SNA 2008:
  * GDP += delta-inventories).
  *
  * Calibrated to GUS F-01 bridge prior (capital) and GUS bridge prior industry
  * data (inventories).
  *
  * @param klRatios
  *   per-sector capital-labor ratio in PLN per worker (6 sectors, GUS F-01
  *   bridge prior)
  * @param depRates
  *   per-sector annual capital depreciation rate (6 sectors)
  * @param importShare
  *   share of capital investment that is imported (import leakage)
  * @param adjustSpeed
  *   monthly speed of capital stock adjustment toward target
  * @param demandExpansionSensitivity
  *   target-capital uplift per unit of persistent excess demand pressure
  * @param investmentCreditShare
  *   share of cash-unfunded desired physical investment eligible for bank
  *   credit
  * @param investmentDebtTargetShare
  *   target share of desired physical investment requested as bank debt before
  *   firms fall back to internal cash
  * @param prodElast
  *   capital share α in CES production function (also Cobb-Douglas when σ≈1)
  * @param costReplace
  *   replacement cost of capital as fraction of original value
  * @param inventoryTargetRatios
  *   per-sector target inventory-to-revenue ratio (6 sectors, GUS bridge prior)
  * @param inventoryAdjustSpeed
  *   monthly speed of inventory adjustment toward target
  * @param inventoryCarryingCost
  *   annual carrying cost as fraction of inventory value
  * @param inventorySpoilageRates
  *   per-sector monthly spoilage rate (6 sectors, highest for Agriculture)
  * @param inventoryCostFraction
  *   fraction of revenue allocated to inventory replenishment
  * @param inventoryLiquidationDisc
  *   discount applied during stress inventory liquidation
  * @param inventoryInitRatio
  *   initial inventory as fraction of target (0-1)
  * @param inventoryCostReplace
  *   cost of replacing spoiled inventory as fraction of revenue
  */
case class CapitalConfig(
    // Physical capital (GUS F-01 bridge prior; #461 K/GDP calibration)
    klRatios: Vector[PLN] = Vector(PLN(180000), PLN(375000), PLN(120000), PLN(300000), PLN(225000), PLN(270000)),
    depRates: Vector[Rate] = Vector(Rate.decimal(15, 2), Rate.decimal(8, 2), Rate.decimal(10, 2), Rate.decimal(7, 2), Rate.decimal(5, 2), Rate.decimal(8, 2)),
    importShare: Share = Share.decimal(18, 2),
    adjustSpeed: Coefficient = Coefficient.decimal(10, 2),
    demandExpansionSensitivity: Coefficient = Coefficient.decimal(30, 2),
    investmentCreditShare: Share = Share.One,
    investmentDebtTargetShare: Share = Share.decimal(7, 2),
    prodElast: Share = Share.decimal(30, 2),
    costReplace: Share = Share.decimal(50, 2),
    // Inventories (GUS bridge prior)
    inventoryTargetRatios: Vector[Share] =
      Vector(Share.decimal(5, 2), Share.decimal(25, 2), Share.decimal(15, 2), Share.decimal(10, 2), Share.decimal(2, 2), Share.decimal(30, 2)),
    inventoryAdjustSpeed: Coefficient = Coefficient.decimal(10, 2),
    inventoryCarryingCost: Rate = Rate.decimal(6, 2),
    inventorySpoilageRates: Vector[Rate] = Vector(Rate(0), Rate.decimal(2, 2), Rate.decimal(5, 2), Rate.decimal(3, 2), Rate(0), Rate.decimal(10, 2)),
    inventoryCostFraction: Share = Share.decimal(50, 2),
    inventoryLiquidationDisc: Share = Share.decimal(50, 2),
    inventoryInitRatio: Share = Share.decimal(80, 2),
    inventoryCostReplace: Share = Share.decimal(10, 2),
):
  require(klRatios.length == 6, s"klRatios must have 6 sectors: ${klRatios.length}")
  require(depRates.length == 6, s"depRates must have 6 sectors: ${depRates.length}")
  require(
    inventoryTargetRatios.length == 6,
    s"inventoryTargetRatios must have 6 sectors: ${inventoryTargetRatios.length}",
  )
  require(
    inventorySpoilageRates.length == 6,
    s"inventorySpoilageRates must have 6 sectors: ${inventorySpoilageRates.length}",
  )
  require(
    investmentCreditShare >= Share.Zero && investmentCreditShare <= Share.One,
    s"investmentCreditShare must be in [0,1]: $investmentCreditShare",
  )
  require(
    investmentDebtTargetShare >= Share.Zero && investmentDebtTargetShare <= Share.One,
    s"investmentDebtTargetShare must be in [0,1]: $investmentDebtTargetShare",
  )
