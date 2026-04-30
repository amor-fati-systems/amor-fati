package com.boombustgroup.amorfati.init

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WorldInitSpec extends AnyFlatSpec with Matchers:

  given SimParams          = SimParams.defaults
  private val p: SimParams = summon[SimParams]

  "WorldInit" should "seed the Poland 2026-04-30 macro baseline from config" in {
    val init  = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
    val world = init.world

    world.inflation shouldBe p.monetary.initialInflation
    world.nbp.referenceRate shouldBe p.monetary.initialRate
    world.mechanisms.expectations.expectedInflation shouldBe p.monetary.initialExpectedInflation
    world.mechanisms.expectations.expectedRate shouldBe p.monetary.initialExpectedRate
  }

  it should "initialize unemployment and GDP scale from the production baseline" in {
    val init       = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
    val agg        = init.householdAggregates
    val laborForce = agg.employed + agg.unemployed
    val unempRate  = Share.fraction(agg.unemployed, laborForce)
    val annualGdp  = (init.world.cachedMonthlyGdpProxy * 12) / p.gdpRatio.toMultiplier

    decimal(unempRate) shouldBe decimal(p.pop.initialUnemploymentRate) +- BigDecimal("0.0001")
    decimal(annualGdp) shouldBe decimal(p.pop.realGdp) +- BigDecimal("1000000.0")
  }
