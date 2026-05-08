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

end CalibrationRegisterRendererSpec
