package com.boombustgroup.amorfati.engine.mechanisms

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Climate-policy price path used by firm energy costs and world diagnostics.
  */
object ClimatePolicy:

  def etsPrice(previousCompletedMonths: Int)(using p: SimParams): Multiplier =
    p.climate.etsBasePrice * etsPriceGrowth(previousCompletedMonths)

  def etsPriceGrowth(previousCompletedMonths: Int)(using p: SimParams): Multiplier =
    p.climate.etsPriceDrift.monthly.growthMultiplier.pow(Scalar(previousCompletedMonths))

  def carbonSurcharge(sector: SectorIdx, previousCompletedMonths: Int)(using p: SimParams): Multiplier =
    val surcharge =
      p.climate.carbonIntensity(sector.toInt) *
        (etsPriceGrowth(previousCompletedMonths).toScalar - Scalar.One)
    surcharge.max(Scalar.Zero).toMultiplier
