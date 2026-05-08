package com.boombustgroup.amorfati.diagnostics

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path

class CalibrationRegisterExportSpec extends AnyFlatSpec with Matchers:

  "CalibrationRegisterExport" should "parse CLI output path arguments" in {
    CalibrationRegisterExport.parseArgs(Vector()) shouldBe Right(CalibrationRegisterExport.Config())
    CalibrationRegisterExport.parseArgs(Vector("--out", "target/register.md")) shouldBe
      Right(CalibrationRegisterExport.Config(out = Path.of("target/register.md")))
  }

  it should "report a missing output path" in {
    CalibrationRegisterExport.parseArgs(Vector("--out")) shouldBe Left("Missing path for --out")
  }

end CalibrationRegisterExportSpec
