package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.config.ProductionSectorLaborSourceBridge
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class ProductionSectorLaborSourceBridgeExportSpec extends AnyFlatSpec with Matchers:

  "ProductionSectorLaborSourceBridgeExport" should "write deterministic LF-delimited UTF-8 TSV bytes" in {
    val output = Files.createTempFile("production-sector-labor-source-bridge", ".tsv")
    try
      ProductionSectorLaborSourceBridgeExport.main(Array("--out", output.toString))

      val rendered = new String(Files.readAllBytes(output), StandardCharsets.UTF_8)
      rendered shouldBe ProductionSectorLaborSourceBridge.tsvLines.mkString("\n") + "\n"
      rendered should not include "\r"
    finally Files.deleteIfExists(output)
  }
