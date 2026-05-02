package com.boombustgroup.amorfati.init

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WorldInitSpec extends AnyFlatSpec with Matchers:

  given SimParams          = SimParams.defaults
  private val p: SimParams = summon[SimParams]

  private def initialUnemployedCount(employedSlots: Int, unemploymentRate: Share): Int =
    if employedSlots <= 0 || unemploymentRate <= Share.Zero then 0
    else
      val employedShareRaw = (Share.One - unemploymentRate).toLong
      val total            =
        ((BigInt(employedSlots) * BigInt(FixedPointBase.Scale)) + BigInt(employedShareRaw - 1L)) / BigInt(employedShareRaw)
      Math.max(0, total.toInt - employedSlots)

  "WorldInit" should "seed the Poland 2026-04-30 macro baseline from config" in {
    val init  = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
    val world = init.world

    world.inflation shouldBe p.monetary.initialInflation
    world.nbp.referenceRate shouldBe p.monetary.initialRate
    world.mechanisms.expectations.expectedInflation shouldBe p.monetary.initialExpectedInflation
    world.mechanisms.expectations.expectedRate shouldBe p.monetary.initialExpectedRate
  }

  it should "initialize unemployment and GDP scale from the production baseline" in {
    val init               = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
    val agg                = init.householdAggregates
    val laborForce         = agg.employed + agg.unemployed
    val expectedUnemployed = initialUnemployedCount(agg.employed, p.pop.initialUnemploymentRate)
    val unempRate          = Share.fraction(agg.unemployed, laborForce)
    val annualGdp          = (init.world.cachedMonthlyGdpProxy * 12) / p.gdpRatio.toMultiplier

    agg.unemployed shouldBe expectedUnemployed
    unempRate shouldBe Share.fraction(expectedUnemployed, laborForce)
    decimal(unempRate) shouldBe decimal(p.pop.initialUnemploymentRate) +- (BigDecimal(1) / BigDecimal(laborForce))
    decimal(annualGdp) shouldBe decimal(p.pop.realGdp) +- BigDecimal("1000000.0")
  }

  it should "honor the banking consumer-loan opening stock" in {
    val init             = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
    val householdLoans   = init.ledgerFinancialState.households.map(_.consumerLoan).sumPln
    val bankConsumerBook = init.ledgerFinancialState.banks.map(_.consumerLoan).sumPln

    householdLoans shouldBe p.banking.initConsumerLoans
    bankConsumerBook shouldBe p.banking.initConsumerLoans
  }
