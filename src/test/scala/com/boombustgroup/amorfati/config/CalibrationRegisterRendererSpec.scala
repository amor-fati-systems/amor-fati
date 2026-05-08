package com.boombustgroup.amorfati.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

class CalibrationRegisterRendererSpec extends AnyFlatSpec with Matchers:

  import CalibrationProvenance.CalibrationStatus

  "CalibrationRegisterRenderer" should "match the checked-in calibration register" in {
    val rendered  = CalibrationRegisterRenderer.render()
    val checkedIn = Files.readString(Path.of("docs/calibration-register.md"), StandardCharsets.UTF_8)

    checkedIn shouldBe rendered
  }

  it should "render status counts from the typed registry" in {
    val rendered = CalibrationRegisterRenderer.render()
    val counts   = CalibrationProvenance.Baseline.statusCounts

    CalibrationStatus.values.foreach: status =>
      rendered should include(s"| `${status.token}` | ${counts.getOrElse(status, 0)} |")
  }

  it should "render typed placeholder decisions" in {
    val rendered = CalibrationRegisterRenderer.render()

    rendered should include("## Placeholder Decisions")
    rendered should include("| `immigration.initStock` | `StartupPlaceholder` |")
    rendered should include("Opening migration-stock comparisons")
  }

  it should "fail fast when a placeholder row lacks typed decision metadata" in {
    val brokenPlaceholder = CalibrationProvenance.CalibrationParameter(
      id = "immigration.initStock",
      parameterIds = Vector("immigration.initStock"),
      renderedValue = "0",
      unit = "agents",
      provenance = "test",
      empiricalTarget = "test",
      transformation = "test",
      ownerModules = Vector("ImmigrationConfig"),
      status = CalibrationProvenance.CalibrationStatus.Placeholder,
    )

    val thrown = intercept[IllegalArgumentException]:
      CalibrationRegisterRenderer.render(Vector(brokenPlaceholder))

    thrown.getMessage should include("Missing placeholder decision")
    thrown.getMessage should include("immigration.initStock")
  }

end CalibrationRegisterRendererSpec
