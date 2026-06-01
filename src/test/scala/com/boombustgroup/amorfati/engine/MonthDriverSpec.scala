package com.boombustgroup.amorfati.engine

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.flows.RuntimeFlowsTestSupport.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MonthDriverSpec extends AnyFlatSpec with Matchers:

  private given SimParams = SimParams.defaults

  "MonthDriver" should "emit a structured step failure once and stop typed unfolding" in {
    val state   = stateFromSeed()
    val failure = EngineFailure.invariantViolation("MonthDriverSpec.stepBoundary", "synthetic typed failure")
    var calls   = 0

    val results = MonthDriver
      .unfoldStepResultsWithBoundary(state)(_ => Some(monthRandomness(42L))) { (_, _, _) =>
        calls += 1
        Left(failure)
      }
      .toVector

    results shouldBe Vector(Left(failure))
    calls shouldBe 1
  }
