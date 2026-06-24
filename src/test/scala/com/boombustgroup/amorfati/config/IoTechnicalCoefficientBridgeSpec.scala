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
    Rows.map(_.modelCoefficient) should contain(BigDecimal("0.35"))
    Rows.foreach(_.notes should include("does not change IoConfig.DefaultMatrix"))
    Rows.foreach(_.notes should include("validate io.crossSectorSpillover"))
  }

  it should "reconstruct technical coefficients from source flow divided by using-sector output" in {
    val agricultureToManufacturing = row("Agriculture", "Manufacturing")
    agricultureToManufacturing.sourceFlowMEur shouldBe BigDecimal("14637.02")
    agricultureToManufacturing.usingOutputMEur shouldBe BigDecimal("374314.25")
    assertClose(agricultureToManufacturing.sourceCoefficient, BigDecimal("0.039103561"))
    agricultureToManufacturing.modelCoefficient shouldBe BigDecimal("0.08")

    val manufacturingToAgriculture = row("Manufacturing", "Agriculture")
    manufacturingToAgriculture.sourceFlowMEur shouldBe BigDecimal("6175.03")
    manufacturingToAgriculture.usingOutputMEur shouldBe BigDecimal("32979.19")
    assertClose(manufacturingToAgriculture.sourceCoefficient, BigDecimal("0.187240196"))
    manufacturingToAgriculture.modelCoefficient shouldBe BigDecimal("0.18")
  }

  it should "make matrix orientation explicit and non-transposed" in {
    val supplierInputToUsingSector = row("BPO/SSC", "Retail/Services")
    val transposedPair             = row("Retail/Services", "BPO/SSC")

    supplierInputToUsingSector.orientation should include("supplier sector i used by sector j")
    supplierInputToUsingSector.sourceFlowMEur shouldBe BigDecimal("22426.31")
    transposedPair.sourceFlowMEur shouldBe BigDecimal("8922.41")
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
