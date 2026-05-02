package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.types.*

/** Firm size distribution strategy used to compute average workers per firm and
  * gdpRatio.
  *   - `Uniform`: all firms have `workersPerFirm` employees (analytical
  *     baseline)
  *   - `Gus`: realistic micro/small/medium/large enterprise split
  */
enum FirmSizeDist:
  case Uniform, Gus

/** Agent population and firm size distribution.
  *
  * Controls the number of heterogeneous firms and the mapping from firm count
  * to total employment, which in turn determines `gdpRatio` — the scaling
  * factor that maps agent-level flows to the 2026-04-30 Poland baseline GDP
  * scale.
  *
  * @param firmsCount
  *   number of firm agents in the simulation
  * @param workersPerFirm
  *   workers per firm under `Uniform` distribution (also used as normalizing
  *   base for `Gus`)
  * @param firmSizeDist
  *   size distribution strategy: `Uniform` (equal sizes) or `Gus`
  *   (micro/small/medium/large split)
  * @param firmSizeMicroShare
  *   share of micro-enterprises (1-9 employees) under `Gus` distribution
  * @param firmSizeSmallShare
  *   share of small firms (10-49 employees) under `Gus`
  * @param firmSizeMediumShare
  *   share of medium firms (50-249 employees) — derived as residual
  * @param firmSizeLargeShare
  *   share of large firms (250+ employees) under `Gus`
  * @param firmSizeLargeMax
  *   upper bound on headcount draw for large firms
  * @param realGdp
  *   annual current-price GDP scale in PLN used to compute `gdpRatio`
  * @param initialUnemploymentRate
  *   unemployment-rate stock at simulation start
  */
case class PopulationConfig(
    firmsCount: Int = 10000,
    workersPerFirm: Int = 10,
    firmSizeDist: FirmSizeDist = FirmSizeDist.Gus,
    firmSizeMicroShare: Share = Share.decimal(962, 3),
    firmSizeSmallShare: Share = Share.decimal(28, 3),
    firmSizeMediumShare: Share = Share.decimal(8, 3),
    firmSizeLargeShare: Share = Share.decimal(2, 3),
    firmSizeLargeMax: Int = 1000,
    realGdp: PLN = PLN(4160000000000L),
    initialUnemploymentRate: Share = Share.decimal(61, 3),
):
  require(firmsCount > 0, s"firmsCount must be positive: $firmsCount")
  require(workersPerFirm > 0, s"workersPerFirm must be positive: $workersPerFirm")
  require(realGdp > PLN.Zero, s"realGdp must be positive: $realGdp")
  require(firmSizeLargeMax >= 250, s"firmSizeLargeMax must be >= 250: $firmSizeLargeMax")
  require(
    initialUnemploymentRate >= Share.Zero && initialUnemploymentRate < Share.One,
    s"initialUnemploymentRate must be in [0,1): $initialUnemploymentRate",
  )
