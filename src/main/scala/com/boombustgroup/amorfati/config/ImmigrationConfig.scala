package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.types.*

/** Labor immigration: inflow dynamics, sectoral allocation, skill profile, and
  * return migration.
  *
  * Models endogenous labor immigration driven by wage differentials (NBP 2023
  * survey), with sector-specific allocation (GUS LFS bridge prior), skill
  * distribution, wage discount for immigrants, and return migration. Immigrant
  * workers are added to the household agent population and affect labor supply,
  * ZUS contributions, and (optionally) 800+ eligibility.
  *
  * @param monthlyRate
  *   base monthly immigration rate as fraction of labor force
  * @param wageElasticity
  *   elasticity of immigration to domestic/foreign wage ratio
  * @param foreignWage
  *   reference foreign wage for push-pull calculation (PLN equivalent)
  * @param remitRate
  *   fraction of immigrant income sent as remittances (outflow, NBP 2023)
  * @param returnRate
  *   monthly probability of return migration
  * @param returnUnempThreshold
  *   immigrant unemployment rate above which return migration accelerates
  * @param returnUnempSensitivity
  *   return-rate response to immigrant unemployment above the threshold
  * @param sectorShares
  *   sectoral allocation of new immigrants (6 sectors, GUS LFS bridge prior)
  * @param skillMean
  *   mean skill level of immigrant workers (0-1 scale)
  * @param wageDiscount
  *   initial wage discount for immigrants vs. natives (NBP 2023 survey: ~20%)
  * @param initStock
  *   initial immigrant stock at simulation start (number of workers)
  */
case class ImmigrationConfig(
    monthlyRate: Share = Share.decimal(8, 4),
    wageElasticity: Coefficient = Coefficient(2),
    foreignWage: PLN = PLN(6500),
    remitRate: Share = Share.decimal(15, 2),
    returnRate: Share = Share.decimal(5, 3),
    returnUnempThreshold: Share = Share.decimal(20, 2),
    returnUnempSensitivity: Coefficient = Coefficient.decimal(10, 2),
    sectorShares: Vector[Share] =
      Vector(Share.decimal(5, 2), Share.decimal(35, 2), Share.decimal(25, 2), Share.decimal(5, 2), Share.decimal(5, 2), Share.decimal(25, 2)),
    skillMean: Share = Share.decimal(55, 2),
    wageDiscount: Share = Share.decimal(20, 2),
    initStock: Int = 0,
):
  require(sectorShares.length == 6, s"sectorShares must have 6 sectors: ${sectorShares.length}")
