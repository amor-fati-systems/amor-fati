package com.boombustgroup.amorfati.montecarlo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class McFirmSnapshotSchemaSpec extends AnyFlatSpec with Matchers:

  "McFirmSnapshotSchema" should "keep the firm snapshot header stable" in {
    McFirmSnapshotSchema.header shouldBe
      "RunId;Seed;Month;FirmId;Sector;Region;SizeClass;Workers;TechState;BankruptcyReason;DigitalReadiness;Cash;FirmLoan;Equity;BankId;RiskProfile;InitialSize;CapitalStock;Inventory;GreenCapital;ForeignOwned;StateOwned"
  }
