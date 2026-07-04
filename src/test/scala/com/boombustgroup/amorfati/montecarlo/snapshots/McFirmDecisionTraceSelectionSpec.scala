package com.boombustgroup.amorfati.montecarlo.snapshots

import com.boombustgroup.amorfati.montecarlo.core.McRunConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class McFirmDecisionTraceSelectionSpec extends AnyFlatSpec with Matchers:

  "McFirmDecisionTraceSelection" should "disable firm decision traces by default in run config" in {
    McRunConfig(nSeeds = 1, outputPrefix = "baseline").firmDecisionTraceSelection shouldBe McFirmDecisionTraceSelection.Disabled
  }

  it should "select explicit firm ids" in {
    val selection = McFirmDecisionTraceSelection.ExplicitFirmIds(Set(2, 5))

    selection.includes(1) shouldBe false
    selection.includes(2) shouldBe true
    selection.includes(5) shouldBe true
  }

  it should "select the first N firm ids deterministically" in {
    val selection = McFirmDecisionTraceSelection.FirstN(3)

    selection.includes(0) shouldBe true
    selection.includes(2) shouldBe true
    selection.includes(3) shouldBe false
  }

  it should "reject invalid enabled selections" in {
    an[IllegalArgumentException] should be thrownBy McFirmDecisionTraceSelection.ExplicitFirmIds(Set.empty)
    an[IllegalArgumentException] should be thrownBy McFirmDecisionTraceSelection.ExplicitFirmIds(Set(0, -1))
    an[IllegalArgumentException] should be thrownBy McFirmDecisionTraceSelection.FirstN(0)
  }
