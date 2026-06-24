package com.boombustgroup.amorfati.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class IoTechnicalCoefficientBridgeSpec extends AnyFlatSpec with Matchers:

  import IoTechnicalCoefficientBridge.*

  "IoTechnicalCoefficientBridge" should "emit all supplier-using sector pairs in runtime order" in {
    Rows should have size 36
    RuntimeSectors.map(_.runtimeSector) shouldBe SimParams.SchemaSectorNames
    Rows.take(6).map(_.supplierSector).distinct shouldBe Vector("BPO/SSC")
    Rows.take(6).map(_.usingSector) shouldBe SimParams.SchemaSectorNames
    Rows.foreach(_.sourceProvider shouldBe "GUS")
    Rows.map(_.modelCoefficient) should contain(BigDecimal("0.35"))
    Rows.foreach(_.notes should include("remains the 2026-04-30 baseline assumption"))
    Rows.foreach(_.notes should include("validate io.crossSectorSpillover"))
  }

  it should "reconstruct technical coefficients from source flow divided by using-sector output" in {
    val agricultureToManufacturing = row("Agriculture", "Manufacturing")
    agricultureToManufacturing.sourceFlowThousandPln shouldBe BigDecimal("65032271")
    agricultureToManufacturing.usingOutputThousandPln shouldBe BigDecimal("1663078187")
    assertClose(agricultureToManufacturing.sourceCoefficient, BigDecimal("0.039103556"))
    agricultureToManufacturing.modelCoefficient shouldBe BigDecimal("0.08")

    val manufacturingToAgriculture = row("Manufacturing", "Agriculture")
    manufacturingToAgriculture.sourceFlowThousandPln shouldBe BigDecimal("27435636")
    manufacturingToAgriculture.usingOutputThousandPln shouldBe BigDecimal("146526547")
    assertClose(manufacturingToAgriculture.sourceCoefficient, BigDecimal("0.187240036"))
    manufacturingToAgriculture.modelCoefficient shouldBe BigDecimal("0.18")
  }

  it should "make matrix orientation explicit and non-transposed" in {
    val supplierInputToUsingSector = row("BPO/SSC", "Retail/Services")
    val transposedPair             = row("Retail/Services", "BPO/SSC")

    supplierInputToUsingSector.orientation should include("supplier sector i used by sector j")
    supplierInputToUsingSector.sourceFlowThousandPln shouldBe BigDecimal("106972170")
    transposedPair.sourceFlowThousandPln shouldBe BigDecimal("46379004")
    supplierInputToUsingSector.sourceCoefficient should not equal transposedPair.sourceCoefficient
  }

  it should "preserve the committed TSV extract from the typed bridge" in {
    val path  = Path.of("docs/empirical-source-extracts/io-technical-coefficients.tsv")
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector

    lines shouldBe IoTechnicalCoefficientBridge.tsvLines
  }

  it should "fail fast when a production sector is missing from the crosswalk" in {
    val err = intercept[RuntimeException]:
      IoTechnicalCoefficientBridge.outputStemFor("Missing sector")

    err.getMessage should include("Missing crosswalk mapping for sector: Missing sector")
  }

  private def row(supplierSector: String, usingSector: String): Row =
    Rows
      .find(row => row.supplierSector == supplierSector && row.usingSector == usingSector)
      .getOrElse(fail(s"Missing I-O row for $supplierSector -> $usingSector"))

  private def assertClose(actual: BigDecimal, expected: BigDecimal): Unit =
    (actual - expected).abs should be <= BigDecimal("0.000000001")
