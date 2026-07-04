package com.boombustgroup.amorfati.config

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class ProductionSectorLaborSourceBridgeSpec extends AnyFlatSpec with Matchers with OptionValues:

  import ProductionSectorLaborSourceBridge.*

  "ProductionSectorLaborSourceBridge" should "emit firm, employment, and wage rows in runtime sector order" in {
    val metrics = Rows.groupBy(_.metric)

    metrics.keySet shouldBe Set("firm_population_share", "employment_share", "wage_ratio")
    metrics.foreach: (_, rows) =>
      rows.map(_.runtimeSector) shouldBe SimParams.SchemaSectorNames
      rows.map(_.outputStem) shouldBe SimParams.SchemaSectors.map(_.outputStem)
      rows.foreach(_.empiricalValue should be > BigDecimal(0))
  }

  it should "compute firm-population shares on the GUS active-enterprise production perimeter" in {
    IncludedFirmEnterprises shouldBe BigDecimal("2814473")
    assertClose(row("firm_population_share", "BPO/SSC").empiricalValue, BigDecimal("0.2734796176762043906621239571"))
    assertClose(row("firm_population_share", "Manufacturing").empiricalValue, BigDecimal("0.09058498695848210304380251649"))
    assertClose(row("firm_population_share", "Retail/Services").empiricalValue, BigDecimal("0.5047442274273016653561785812"))
    assertClose(row("firm_population_share", "Healthcare").empiricalValue, BigDecimal("0.09077045684929292268925656775"))
    assertClose(row("firm_population_share", "Public").empiricalValue, BigDecimal("0.03163398618498027872358342041"))
    assertClose(row("firm_population_share", "Agriculture").empiricalValue, BigDecimal("0.008786724903738639525054957003"))
  }

  it should "compute employment shares on the Eurostat included production-sector perimeter" in {
    IncludedEmploymentThousandPersons shouldBe BigDecimal("16880.8")
    assertClose(row("employment_share", "BPO/SSC").empiricalValue, BigDecimal("0.1205393109331311312260082461"))
    assertClose(row("employment_share", "Manufacturing").empiricalValue, BigDecimal("0.2301727406284062366712478082"))
    assertClose(row("employment_share", "Retail/Services").empiricalValue, BigDecimal("0.3522048718070233638216198284"))
    assertClose(row("employment_share", "Healthcare").empiricalValue, BigDecimal("0.07006776930003317378323302213"))
    assertClose(row("employment_share", "Public").empiricalValue, BigDecimal("0.1556383583716411544476565092"))
    assertClose(row("employment_share", "Agriculture").empiricalValue, BigDecimal("0.07137694895976494005023458604"))
  }

  it should "compute wage ratios from Eurostat compensation per employee" in {
    assertClose(row("wage_ratio", "BPO/SSC").empiricalValue, BigDecimal("1.233856037667009678735608387"))
    assertClose(row("wage_ratio", "Manufacturing").empiricalValue, BigDecimal("0.9455804994875077694008566246"))
    assertClose(row("wage_ratio", "Retail/Services").empiricalValue, BigDecimal("0.8683922291831118373918523115"))
    assertClose(row("wage_ratio", "Healthcare").empiricalValue, BigDecimal("0.9583060395718527268508466375"))
    assertClose(row("wage_ratio", "Public").empiricalValue, BigDecimal("1.181048625206965947183894443"))
    assertClose(row("wage_ratio", "Agriculture").empiricalValue, BigDecimal("1.437362138041809249042472240"))
  }

  it should "fail fast when a production sector is missing from the crosswalk" in {
    val err = intercept[RuntimeException]:
      ProductionSectorLaborSourceBridge.outputStemFor("Missing sector")

    err.getMessage should include("Missing crosswalk mapping for sector: Missing sector")
  }

  it should "render the committed TSV extract from the typed bridge" in {
    val path  = Path.of("docs/empirical-source-extracts/production-sector-labor-bridges.tsv")
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector

    lines shouldBe ProductionSectorLaborSourceBridge.tsvLines
  }

  private def row(metric: String, runtimeSector: String): Row =
    Rows
      .find(row => row.metric == metric && row.runtimeSector == runtimeSector)
      .getOrElse(fail(s"Missing $metric row for $runtimeSector"))

  private def assertClose(actual: BigDecimal, expected: BigDecimal): Unit =
    (actual - expected).abs should be <= BigDecimal("0.000000000000000000000000001")
