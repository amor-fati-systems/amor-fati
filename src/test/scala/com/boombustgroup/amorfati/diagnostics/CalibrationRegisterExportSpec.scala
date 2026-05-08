package com.boombustgroup.amorfati.diagnostics

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path

class CalibrationRegisterExportSpec extends AnyFlatSpec with Matchers:

  import CalibrationRegisterExport.CliCommand

  "CalibrationRegisterExport" should "parse CLI output path arguments" in {
    CalibrationRegisterExport.parseArgs(Vector()) shouldBe Right(CliCommand.Export(CalibrationRegisterExport.Config()))
    CalibrationRegisterExport.parseArgs(Vector("--out", "target/register.md")) shouldBe
      Right(CliCommand.Export(CalibrationRegisterExport.Config(out = Path.of("target/register.md"))))
  }

  it should "parse help as a successful command" in {
    CalibrationRegisterExport.parseArgs(Vector("--help")) shouldBe Right(CliCommand.Help)
  }

  it should "report a missing output path" in {
    CalibrationRegisterExport.parseArgs(Vector("--out")) shouldBe Left("Missing path for --out")
  }

end CalibrationRegisterExportSpec
