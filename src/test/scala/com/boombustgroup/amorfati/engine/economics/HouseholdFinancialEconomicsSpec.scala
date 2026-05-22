package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.Generators
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HouseholdFinancialEconomicsSpec extends AnyFlatSpec with Matchers:

  private given SimParams = SimParams.defaults

  "HouseholdFinancialEconomics.compute" should "exclude liquidity-bridge charge-offs from ordinary consumer NPL loss" in {
    val hhAgg = Generators
      .testHouseholdAggregates()
      .copy(
        totalLiquidityShortfallFinancing = PLN(1000),
        totalConsumerDefault = PLN(1000),
        totalConsumerLoanDefault = PLN.Zero,
        totalLiquidityBridgeChargeOff = PLN(1000),
      )

    val result = HouseholdFinancialEconomics.compute(
      Generators.testWorld(),
      ExecutionMonth.First,
      employed = 100,
      hhAgg = hhAgg,
      rng = RandomStream.seeded(42),
    )

    result.consumerDefaultAmt shouldBe PLN(1000)
    result.consumerLoanDefaultAmt shouldBe PLN.Zero
    result.consumerNplLoss shouldBe PLN.Zero
  }

  it should "apply recovery only to ordinary consumer-loan defaults" in {
    val hhAgg = Generators
      .testHouseholdAggregates()
      .copy(
        totalLiquidityShortfallFinancing = PLN(800),
        totalConsumerDefault = PLN(1000),
        totalConsumerLoanDefault = PLN(200),
        totalLiquidityBridgeChargeOff = PLN(800),
      )

    val result = HouseholdFinancialEconomics.compute(
      Generators.testWorld(),
      ExecutionMonth.First,
      employed = 100,
      hhAgg = hhAgg,
      rng = RandomStream.seeded(42),
    )

    result.consumerDefaultAmt shouldBe PLN(1000)
    result.consumerLoanDefaultAmt shouldBe PLN(200)
    result.consumerNplLoss shouldBe PLN(170)
  }
