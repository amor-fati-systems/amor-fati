package com.boombustgroup.amorfati.montecarlo.snapshots

import com.boombustgroup.amorfati.montecarlo.io.McTsvSchema
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class McFirmSnapshotSchemaSpec extends AnyFlatSpec with Matchers:

  "McFirmSnapshotSchema" should "keep the firm snapshot header stable" in {
    McFirmSnapshotSchema.header shouldBe
      McTsvSchema.header(
        "RunId",
        "Seed",
        "Month",
        "FirmId",
        "Sector",
        "Region",
        "SizeClass",
        "Workers",
        "TechState",
        "BankruptcyReason",
        "DigitalReadiness",
        "Cash",
        "FirmLoan",
        "Equity",
        "BankId",
        "RiskProfile",
        "InitialSize",
        "CapitalStock",
        "Inventory",
        "GreenCapital",
        "ForeignOwned",
        "StateOwned",
      )
  }
