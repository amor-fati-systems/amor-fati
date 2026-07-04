package com.boombustgroup.amorfati.montecarlo.snapshots

import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.montecarlo.core.McRunConfig
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class McHouseholdSnapshotScheduleSpec extends AnyFlatSpec with Matchers:

  "McHouseholdSnapshotSchedule" should "disable household snapshots by default in run config" in {
    val rc = McRunConfig(nSeeds = 1, outputPrefix = "baseline")
    rc.householdSnapshotSchedule shouldBe McHouseholdSnapshotSchedule.Disabled
    rc.householdSnapshotSelection shouldBe McHouseholdSnapshotSelection.All
  }

  it should "select only the terminal execution month for terminal-only exports" in {
    val schedule = McHouseholdSnapshotSchedule.TerminalOnly
    schedule.includes(ExecutionMonth(2), ExecutionMonth(3)) shouldBe false
    schedule.includes(ExecutionMonth(3), ExecutionMonth(3)) shouldBe true
  }

  it should "select cadence months for every-N-month exports" in {
    val schedule = McHouseholdSnapshotSchedule.EveryNMonths(2)
    schedule.includes(ExecutionMonth(1), ExecutionMonth(5)) shouldBe false
    schedule.includes(ExecutionMonth(2), ExecutionMonth(5)) shouldBe true
    schedule.includes(ExecutionMonth(4), ExecutionMonth(5)) shouldBe true
    schedule.includes(ExecutionMonth(5), ExecutionMonth(5)) shouldBe false
  }

  it should "select explicit month lists" in {
    val schedule = McHouseholdSnapshotSchedule.ExplicitMonths(Set(1, 3))
    schedule.includes(ExecutionMonth(1), ExecutionMonth(4)) shouldBe true
    schedule.includes(ExecutionMonth(2), ExecutionMonth(4)) shouldBe false
    schedule.includes(ExecutionMonth(3), ExecutionMonth(4)) shouldBe true
  }

  it should "reject invalid enabled schedules" in {
    an[IllegalArgumentException] should be thrownBy McHouseholdSnapshotSchedule.EveryNMonths(0)
    an[IllegalArgumentException] should be thrownBy McHouseholdSnapshotSchedule.ExplicitMonths(Set.empty)
    an[IllegalArgumentException] should be thrownBy McHouseholdSnapshotSchedule.ExplicitMonths(Set(0, 1))
  }

  "McHouseholdSnapshotSelection" should "filter by negative balances and shortfall flows" in {
    McHouseholdSnapshotSelection.All.includes(PLN.Zero, PLN.Zero) shouldBe true
    McHouseholdSnapshotSelection.NegativeBalances.includes(PLN(-1), PLN.Zero) shouldBe true
    McHouseholdSnapshotSelection.NegativeBalances.includes(PLN.Zero, PLN(1)) shouldBe false
    McHouseholdSnapshotSelection.LiquidityShortfall.includes(PLN.Zero, PLN(1)) shouldBe true
    McHouseholdSnapshotSelection.LiquidityShortfall.includes(PLN(-1), PLN.Zero) shouldBe false
    McHouseholdSnapshotSelection.NegativeOrLiquidityShortfall.includes(PLN(-1), PLN.Zero) shouldBe true
    McHouseholdSnapshotSelection.NegativeOrLiquidityShortfall.includes(PLN.Zero, PLN(1)) shouldBe true
    McHouseholdSnapshotSelection.NegativeOrLiquidityShortfall.includes(PLN.Zero, PLN.Zero) shouldBe false
  }
