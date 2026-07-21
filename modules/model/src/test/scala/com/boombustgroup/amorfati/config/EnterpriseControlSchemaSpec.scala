package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.config.EnterpriseControlSchema.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EnterpriseControlSchemaSpec extends AnyFlatSpec with Matchers:

  private val RegionClassification = ClassificationRef("fixture-teryt", "v1")
  private val PkdClassification    = ClassificationRef("PKD2007", "v1")
  private val BandClassification   = ClassificationRef("fixture-worker-band", "v1")

  private val North = RegisteredSeatRegionCode("north")
  private val South = RegisteredSeatRegionCode("south")
  private val A     = Pkd2007SectionCode("A")
  private val C     = Pkd2007SectionCode("C")
  private val Small = ExpectedWorkersBandCode("0-9")
  private val Mid   = ExpectedWorkersBandCode("10-49")

  private val classifications = Classifications(
    registeredSeatRegion = RegionClassification,
    pkd2007Section = PkdClassification,
    expectedWorkersBand = BandClassification,
    regions = Vector(North, South),
    sections = Vector(A, C),
    workerBands = Vector(Small, Mid),
  )

  private val validBundle = Bundle(
    classifications = classifications,
    enterpriseStrata = Vector(
      EnterpriseStratumRow(North, A, Small, EnterpriseCount(7)),
      EnterpriseStratumRow(South, C, Small, EnterpriseCount(4)),
      EnterpriseStratumRow(North, A, Mid, EnterpriseCount(2)),
    ),
    sourceResiduals = Vector(
      SourceResidualRow(None, Some(A), Small, EnterpriseCount(1)),
      SourceResidualRow(None, None, Small, EnterpriseCount(1)),
      SourceResidualRow(Some(South), None, Mid, EnterpriseCount(3)),
    ),
    sourceTotals = Vector(
      SourceTotalRow(Small, EnterpriseCount(13)),
      SourceTotalRow(Mid, EnterpriseCount(5)),
    ),
  )

  "EnterpriseControlSchema.Validator" should "accept normal strata plus explicit source residuals that reconcile to every source total" in {
    val report = Validator.validate(validBundle)

    withClue(s"Validation errors: ${report.errors.mkString(", ")}") {
      report.isValid shouldBe true
    }
    report.reconciliations.map(_.id) should contain allOf (
      "source-total-by-expected-workers-band:0-9",
      "source-total-by-expected-workers-band:10-49",
    )
  }

  it should "reject undeclared axes, duplicate cells, and unpreserved source totals" in {
    val undeclaredRegion  = RegisteredSeatRegionCode("undeclared")
    val undeclaredSection = Pkd2007SectionCode("B")
    val undeclaredBand    = ExpectedWorkersBandCode("250=>")
    val invalid           = validBundle.copy(
      enterpriseStrata = validBundle.enterpriseStrata ++ Vector(
        EnterpriseStratumRow(North, A, Small, EnterpriseCount(7)),
        EnterpriseStratumRow(undeclaredRegion, undeclaredSection, undeclaredBand, EnterpriseCount(1)),
      ),
      sourceResiduals = validBundle.sourceResiduals :+ SourceResidualRow(None, Some(A), undeclaredBand, EnterpriseCount(1)),
    )

    val report = Validator.validate(invalid)

    report.errors should contain(ValidationError.DuplicateRow("enterprise-strata", "north|A|0-9"))
    report.errors should contain(ValidationError.UnknownRegisteredSeatRegion("enterprise-strata", undeclaredRegion))
    report.errors should contain(ValidationError.UnknownPkd2007Section("enterprise-strata", undeclaredSection))
    report.errors should contain(ValidationError.UnknownExpectedWorkersBand("enterprise-strata", undeclaredBand))
    report.errors should contain(ValidationError.UnknownExpectedWorkersBand("source-residuals", undeclaredBand))
    report.errors.collect { case ValidationError.FailedReconciliation(reconciliation) => reconciliation.id } should contain(
      "source-total-by-expected-workers-band:0-9",
    )
  }

  it should "require a national source total for every declared expected-workers band" in {
    val invalid = validBundle.copy(sourceTotals = validBundle.sourceTotals.filterNot(_.expectedWorkersBand == Mid))

    Validator.validate(invalid).errors should contain(ValidationError.MissingSourceTotal(Mid))
  }

end EnterpriseControlSchemaSpec
