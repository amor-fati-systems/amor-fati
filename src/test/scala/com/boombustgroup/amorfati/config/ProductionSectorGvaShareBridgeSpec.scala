package com.boombustgroup.amorfati.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class ProductionSectorGvaShareBridgeSpec extends AnyFlatSpec with Matchers with OptionValues:

  import ProductionSectorGvaShareBridge.*

  "ProductionSectorGvaShareBridge" should "emit six production rows in runtime sector order" in {
    val productionRows = Rows.filter(_.rowType == "production")

    productionRows.map(_.runtimeSector) shouldBe SimParams.SchemaSectorNames
    productionRows.map(_.outputStem) shouldBe SimParams.SchemaSectors.map(_.outputStem)
    productionRows.foreach(_.sourceValueMPln.value should be > BigDecimal(0))
    productionRows.foreach(_.productionPerimeterShare.value should be > BigDecimal(0))
  }

  it should "compute production shares on the explicit included-sector perimeter" in {
    val productionRows = Rows.filter(_.rowType == "production")
    val shareSum       = productionRows.flatMap(_.productionPerimeterShare).sum

    (shareSum - BigDecimal(1)).abs should be <= BigDecimal("0.000000001")
    (IncludedProductionGvaMPln - BigDecimal("3271440.139367954")).abs should be <= BigDecimal("0.000001")
    (ExcludedGvaMPln - BigDecimal("176004.260632046")).abs should be <= BigDecimal("0.000001")
    ((IncludedProductionGvaMPln + ExcludedGvaMPln) - Gus2025Annual.grossValueAddedTotal).abs should be <= BigDecimal("0.000001")
  }

  it should "keep direct GUS aggregates separate from allocation bridges" in {
    row("BPO/SSC").bridgeStatus shouldBe "BRIDGE_ASSUMPTION"
    row("Manufacturing").bridgeStatus shouldBe "DIRECT_WITH_RESIDUALS"
    row("Retail/Services").bridgeStatus shouldBe "ALLOCATION_BRIDGE"
    row("Healthcare").bridgeStatus shouldBe "ALLOCATION_BRIDGE"
    row("Public").bridgeStatus shouldBe "ALLOCATION_BRIDGE"
    row("Agriculture").bridgeStatus shouldBe "ALLOCATION_BRIDGE"

    row("BPO/SSC").sourceValueMPln.value shouldBe BigDecimal("494054.7")
    row("Manufacturing").sourceValueMPln.value shouldBe BigDecimal("743528.7")
  }

  it should "fail fast when a production sector is missing from the crosswalk" in {
    val err = intercept[RuntimeException]:
      ProductionSectorGvaShareBridge.outputStemFor("Missing sector")

    err.getMessage should include("Missing crosswalk mapping for sector: Missing sector")
  }

  it should "reconstruct the GUS residual with Eurostat A64 allocation weights" in {
    Gus2025Annual.residualAandRtoU shouldBe BigDecimal("162067.5")
    val allocatedResidual =
      row("Agriculture").sourceValueMPln.value +
        (row("Retail/Services").sourceValueMPln.value -
          Gus2025Annual.constructionF -
          Gus2025Annual.tradeRepairG -
          Gus2025Annual.transportationStorageH -
          Gus2025Annual.accommodationFoodI -
          Gus2025Annual.realEstateL) +
        ExcludedHouseholdAndExtraterritorialValue

    (allocatedResidual - Gus2025Annual.residualAandRtoU).abs should be <= BigDecimal("0.000001")
  }

  it should "render the committed TSV extract from the typed bridge" in {
    val path  = Path.of("docs/empirical-source-extracts/production-sector-gva-shares.tsv")
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector

    lines shouldBe ProductionSectorGvaShareBridge.tsvLines
  }

  private def row(runtimeSector: String): Row =
    Rows
      .find(row => row.rowType == "production" && row.runtimeSector == runtimeSector)
      .getOrElse(fail(s"Missing production row for $runtimeSector"))
