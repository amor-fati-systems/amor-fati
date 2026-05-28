package com.boombustgroup.amorfati.engine.mechanisms

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.flows.RuntimeFlowsTestSupport
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StartupStaffingSpec extends AnyFlatSpec with Matchers:

  private given p: SimParams = SimParams.defaults

  private lazy val deterministicStep = RuntimeFlowsTestSupport.stepFromSeed()

  "StartupStaffing" should "sync startup filled-worker counts from household employment" in {
    val baseFirm          = deterministicStep.nextState.firms.head
    val startupFirm       = baseFirm.copy(
      id = FirmId(0),
      tech = TechState.Hybrid(0, Multiplier.One),
      startupMonthsLeft = 2,
      startupTargetWorkers = 2,
      startupFilledWorkers = 0,
    )
    val staffedHouseholds = deterministicStep.nextState.households.zipWithIndex.map: (household, idx) =>
      if idx < 3 then household.copy(status = HhStatus.Employed(startupFirm.id, startupFirm.sector, PLN(5000)))
      else household.copy(status = HhStatus.Unemployed(1))

    val synced = StartupStaffing.sync(Vector(startupFirm), staffedHouseholds).head

    synced.startupFilledWorkers shouldBe 2
    synced.tech shouldBe TechState.Hybrid(2, Multiplier.One)
  }
