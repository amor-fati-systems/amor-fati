package com.boombustgroup.amorfati.engine.mechanisms

import com.boombustgroup.amorfati.types.*

/** Monthly tourism seasonality with a fixed 12-month cosine profile.
  *
  * `seasonalPhase` returns +1 at the configured peak month, 0 at quarter-cycle
  * offsets, and -1 six months after the peak. `factor` converts that phase into
  * a multiplicative demand factor: `1 + amplitude * phase`.
  */
object TourismSeasonality:

  def factor(monthInYear: Int, peakMonth: Int, amplitude: Share): Multiplier =
    (amplitude * seasonalPhase(monthInYear, peakMonth)).growthMultiplier

  private[amorfati] def seasonalPhase(monthInYear: Int, peakMonth: Int): Coefficient =
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
