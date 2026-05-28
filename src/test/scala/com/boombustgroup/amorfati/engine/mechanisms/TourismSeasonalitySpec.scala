package com.boombustgroup.amorfati.engine.mechanisms

import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TourismSeasonalitySpec extends AnyFlatSpec with Matchers:

  "TourismSeasonality" should "keep deterministic monthly phase anchors" in {
    TourismSeasonality.seasonalPhase(monthInYear = 7, peakMonth = 7) shouldBe Coefficient.One
    TourismSeasonality.seasonalPhase(monthInYear = 1, peakMonth = 7) shouldBe Coefficient(-1)
    TourismSeasonality.seasonalPhase(monthInYear = 10, peakMonth = 7) shouldBe Coefficient.Zero
  }

  it should "convert the phase into a multiplicative factor" in {
    val amplitude = Share.decimal(40, 2)

    TourismSeasonality.factor(monthInYear = 7, peakMonth = 7, amplitude) shouldBe Multiplier.decimal(14, 1)
    TourismSeasonality.factor(monthInYear = 1, peakMonth = 7, amplitude) shouldBe Multiplier.decimal(6, 1)
  }
