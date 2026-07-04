package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RobustnessScenariosSpec extends AnyFlatSpec with Matchers:

  "RobustnessScenarios" should "keep MPC sensitivity scenarios around the current baseline" in {
    val baseline  = SimParams.defaults.household.mpc
    val scenarios = RobustnessScenarios
      .scenarios(RobustnessScenarios.ScenarioSet.Core)
      .map(scenario => scenario.id -> scenario)
      .toMap

    val low  = scenarios.getOrElse("mpc-low", fail("Expected mpc-low scenario"))
    val high = scenarios.getOrElse("mpc-high", fail("Expected mpc-high scenario"))

    baseline shouldBe Share.decimal(92, 2)
    low.params.household.mpc should be < baseline
    high.params.household.mpc should be > baseline
    low.params.household.mpc shouldBe Share.decimal(82, 2)
    high.params.household.mpc shouldBe Share.decimal(98, 2)
    low.variation should include("0.92 -> 0.82")
    high.variation should include("0.92 -> 0.98")
  }

  it should "make the fiscal stabilizer scenario stronger than the current baseline" in {
    val baseline  = SimParams.defaults
    val scenarios = RobustnessScenarios
      .scenarios(RobustnessScenarios.ScenarioSet.Core)
      .map(scenario => scenario.id -> scenario)
      .toMap

    val scenario = scenarios.getOrElse("fiscal-stabilizer-strong", fail("Expected fiscal-stabilizer-strong scenario"))

    baseline.fiscal.govAutoStabMult shouldBe Coefficient(3)
    baseline.fiscal.fiscalConsolidationSpeed55 shouldBe Share.decimal(18, 2)
    scenario.params.fiscal.govAutoStabMult shouldBe Coefficient(4)
    scenario.params.fiscal.fiscalConsolidationSpeed55 shouldBe Share.decimal(25, 2)
    scenario.params.fiscal.fiscalConsolidationSpeed55 should be > baseline.fiscal.fiscalConsolidationSpeed55
    scenario.variation should include("3.0 -> 4.0")
    scenario.variation should include("0.18 -> 0.25")
  }

end RobustnessScenariosSpec
