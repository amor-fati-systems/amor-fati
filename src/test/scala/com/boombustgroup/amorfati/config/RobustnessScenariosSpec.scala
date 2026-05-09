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
    low.variation should include("0.92 -> 0.82")
    high.variation should include("0.92 -> 0.98")
  }

end RobustnessScenariosSpec
