package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.config.IoTechnicalCoefficientBridge
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

class IoTechnicalCoefficientBridgeExportSpec extends AnyFlatSpec with Matchers:

  "IoTechnicalCoefficientBridgeExport" should "write deterministic LF-delimited UTF-8 TSV bytes" in {
    val dir    = Files.createTempDirectory("io-technical-coefficient-bridge-export")
    val output = dir.resolve("io-technical-coefficients.tsv")

    try
      IoTechnicalCoefficientBridgeExport.main(Array("--out", output.toString))

      val rendered = Files.readString(output, UTF_8)
      rendered shouldBe IoTechnicalCoefficientBridge.tsvLines.mkString("\n") + "\n"
      rendered should not include "\r\n"
    finally ()
  }
