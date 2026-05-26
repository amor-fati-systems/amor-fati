package com.boombustgroup.amorfati.engine.assembly

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Observable world values surfaced after monthly stage execution. */
object WorldObservables:

  final case class Values(
      depositFacilityUsage: PLN,
      etsPrice: Multiplier,
      tourismSeasonalFactor: Multiplier,
  )

  def compute(in: WorldAssemblyEconomics.StepInput)(using p: SimParams): Values =
    val aliveBankIds         = in.s9.banks.filterNot(_.failed).map(_.id.toInt).toSet
    val depositFacilityUsage = in.s9.ledgerFinancialState.banks.zipWithIndex
      .filter((_, index) => aliveBankIds.contains(index))
      .map(_._1.reserve)
      .filter(_ > PLN.Zero)
      .sumPln

    val elapsedMonths = in.s1.m.previousCompleted.toInt
    val etsPrice      = p.climate.etsBasePrice * p.climate.etsPriceDrift.monthly.growthMultiplier.pow(Scalar(elapsedMonths))

    val monthInYear           = in.s1.m.monthInYear
    val tourismSeasonalFactor = (p.tourism.seasonality * monthlySeasonalCos(monthInYear, p.tourism.peakMonth)).growthMultiplier

    Values(depositFacilityUsage, etsPrice, tourismSeasonalFactor)

  private[assembly] def monthlySeasonalCos(monthInYear: Int, peakMonth: Int): Coefficient =
    Math.floorMod(monthInYear - peakMonth, 12) match
      case 0  => Coefficient.One
      case 1  => Coefficient.decimal(8660254038L, 10)
      case 2  => Coefficient.decimal(5, 1)
      case 3  => Coefficient.Zero
      case 4  => Coefficient.decimal(-5, 1)
      case 5  => Coefficient.decimal(-8660254038L, 10)
      case 6  => Coefficient(-1)
      case 7  => Coefficient.decimal(-8660254038L, 10)
      case 8  => Coefficient.decimal(-5, 1)
      case 9  => Coefficient.Zero
      case 10 => Coefficient.decimal(5, 1)
      case _  => Coefficient.decimal(8660254038L, 10)
