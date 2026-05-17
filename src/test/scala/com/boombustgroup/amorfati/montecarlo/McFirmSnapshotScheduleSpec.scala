package com.boombustgroup.amorfati.montecarlo

import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class McFirmSnapshotScheduleSpec extends AnyFlatSpec with Matchers:

  "McFirmSnapshotSchedule" should "disable firm snapshots by default in run config" in {
    McRunConfig(nSeeds = 1, outputPrefix = "baseline").firmSnapshotSchedule shouldBe McFirmSnapshotSchedule.Disabled
  }

  it should "select only the terminal execution month for terminal-only exports" in {
    val schedule = McFirmSnapshotSchedule.TerminalOnly
    schedule.includes(ExecutionMonth(2), ExecutionMonth(3)) shouldBe false
    schedule.includes(ExecutionMonth(3), ExecutionMonth(3)) shouldBe true
  }

  it should "select cadence months for every-N-month exports" in {
    val schedule = McFirmSnapshotSchedule.EveryNMonths(2)
    schedule.includes(ExecutionMonth(1), ExecutionMonth(5)) shouldBe false
    schedule.includes(ExecutionMonth(2), ExecutionMonth(5)) shouldBe true
    schedule.includes(ExecutionMonth(4), ExecutionMonth(5)) shouldBe true
    schedule.includes(ExecutionMonth(5), ExecutionMonth(5)) shouldBe false
  }

  it should "select explicit month lists" in {
    val schedule = McFirmSnapshotSchedule.ExplicitMonths(Set(1, 3))
    schedule.includes(ExecutionMonth(1), ExecutionMonth(4)) shouldBe true
    schedule.includes(ExecutionMonth(2), ExecutionMonth(4)) shouldBe false
    schedule.includes(ExecutionMonth(3), ExecutionMonth(4)) shouldBe true
  }

  it should "reject invalid enabled schedules" in {
    an[IllegalArgumentException] should be thrownBy McFirmSnapshotSchedule.EveryNMonths(0)
    an[IllegalArgumentException] should be thrownBy McFirmSnapshotSchedule.ExplicitMonths(Set.empty)
    an[IllegalArgumentException] should be thrownBy McFirmSnapshotSchedule.ExplicitMonths(Set(0, 1))
  }
