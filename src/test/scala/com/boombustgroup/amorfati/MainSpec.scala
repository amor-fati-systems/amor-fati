package com.boombustgroup.amorfati

import com.boombustgroup.amorfati.montecarlo.{
  McFirmDecisionTraceSelection,
  McFirmSnapshotSchedule,
  McHouseholdSnapshotSchedule,
  McHouseholdSnapshotSelection,
  McRunConfig,
}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.{Chunk, Runtime, Unsafe}

class MainSpec extends AnyFlatSpec with Matchers:

  "Main.parseArgs" should "parse optional runtime flags strictly" in {
    val parsed = parse(
      "3",
      "smoke",
      "--duration",
      "24",
      "--run-id",
      "manual",
      "--firm-snapshots",
      "months:1,12",
      "--household-snapshots",
      "terminal",
      "--household-snapshot-selector",
      "shortfall",
      "--firm-decision-trace",
      "ids:0,5",
    )
      .map: rc =>
        (
          rc.nSeeds,
          rc.outputPrefix,
          rc.runDurationMonths,
          rc.runId,
          rc.firmSnapshotSchedule,
          rc.householdSnapshotSchedule,
          rc.householdSnapshotSelection,
          rc.firmDecisionTraceSelection,
        )

    parsed shouldBe Right(
      (
        3,
        "smoke",
        24,
        "manual",
        McFirmSnapshotSchedule.ExplicitMonths(Set(1, 12)),
        McHouseholdSnapshotSchedule.TerminalOnly,
        McHouseholdSnapshotSelection.LiquidityShortfall,
        McFirmDecisionTraceSelection.ExplicitFirmIds(Set(0, 5)),
      ),
    )
  }

  it should "report missing values for known flags" in {
    expectError(parse("3", "smoke", "--firm-snapshots"), "Missing value for --firm-snapshots")
    expectError(parse("3", "smoke", "--household-snapshots"), "Missing value for --household-snapshots")
    expectError(parse("3", "smoke", "--household-snapshot-selector"), "Missing value for --household-snapshot-selector")
    expectError(parse("3", "smoke", "--firm-decision-trace"), "Missing value for --firm-decision-trace")
    expectError(parse("3", "smoke", "--duration", "--run-id", "manual"), "Missing value for --duration")
    expectError(parse("3", "smoke", "--run-id"), "Missing value for --run-id")
  }

  it should "reject unknown flags and unexpected positional arguments" in {
    expectError(parse("3", "smoke", "--unknown", "value"), "Unknown argument: --unknown")
    expectError(parse("3", "smoke", "extra"), "Unexpected positional argument: extra")
  }

  private def parse(args: String*): Either[String, McRunConfig] =
    Unsafe.unsafe:
      implicit unsafe =>
        Runtime.default.unsafe
          .run(Main.parseArgs(Chunk.fromIterable(args)).either)
          .getOrThrowFiberFailure()
          .left
          .map(_.getMessage)

  private def expectError(result: Either[String, McRunConfig], expected: String): Unit =
    result match
      case Left(message) =>
        message should include(expected)
        message should include("Usage: amor-fati")
      case Right(value)  =>
        fail(s"Expected parse error, got $value")

end MainSpec
