package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.config.OpeningBankBalanceProfileBridge
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class OpeningBankBalanceProfileBridgeExportSpec extends AnyFlatSpec with Matchers:

  "OpeningBankBalanceProfileBridgeExport" should "write deterministic LF-delimited UTF-8 TSV bytes" in {
    val output = Files.createTempFile("opening-bank-balance-profile-bridge", ".tsv")
    try
      OpeningBankBalanceProfileBridgeExport.main(Array("--out", output.toString))

      val rendered = new String(Files.readAllBytes(output), StandardCharsets.UTF_8)
      rendered shouldBe OpeningBankBalanceProfileBridge.tsvLines.mkString("\n") + "\n"
      rendered should not include "\r"
    finally Files.deleteIfExists(output)
  }
