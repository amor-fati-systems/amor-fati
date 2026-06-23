package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.config.ProductionSectorGvaShareBridge
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class ProductionSectorGvaShareBridgeExportSpec extends AnyFlatSpec with Matchers:

  "ProductionSectorGvaShareBridgeExport" should "write deterministic LF-delimited UTF-8 TSV bytes" in {
    val output = Files.createTempFile("production-sector-gva-share-bridge", ".tsv")
    try
      ProductionSectorGvaShareBridgeExport.main(Array("--out", output.toString))

      val rendered = new String(Files.readAllBytes(output), StandardCharsets.UTF_8)
      rendered shouldBe ProductionSectorGvaShareBridge.tsvLines.mkString("\n") + "\n"
      rendered should not include "\r"
    finally Files.deleteIfExists(output)
  }
