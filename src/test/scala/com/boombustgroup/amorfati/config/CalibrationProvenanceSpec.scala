package com.boombustgroup.amorfati.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CalibrationProvenanceSpec extends AnyFlatSpec with Matchers:

  import CalibrationProvenance.*
  import CalibrationProvenance.CalibrationExemptionKind.*
  import CalibrationProvenance.CalibrationStatus.*
  import CalibrationProvenance.CalibrationStatusKind.*

  "CalibrationStatus" should "parse all documented status tokens" in {
    CalibrationStatus.values.foreach: status =>
      withClue(status.token) {
        CalibrationStatus.parse(status.token) shouldBe Right(status)
        CalibrationStatus.parse(s"`${status.token}`") shouldBe Right(status)
      }

    CalibrationStatus.parse("NOT_A_STATUS").isLeft shouldBe true
  }

  it should "classify every accepted provenance status" in {
    Empirical.kind shouldBe EmpiricalEvidence
    EmpiricalTransformed.kind shouldBe EmpiricalEvidence
    CodeNoteEmpirical.kind shouldBe SourceDetailMissing
    TunedNeedsValidation.kind shouldBe ValidationNeeded
    UnknownSource.kind shouldBe MissingSource
    Placeholder.kind shouldBe PlaceholderAssumption
    Assumed.kind shouldBe ExplicitAssumption
    PolicyScenario.kind shouldBe PolicyOrScenario
  }

  "Baseline calibration provenance" should "preserve the active default inventory status counts in code" in {
    CalibrationProvenance.Baseline.parameters should have size 238
    CalibrationProvenance.Baseline.statusCounts should contain(Empirical -> 35)
    CalibrationProvenance.Baseline.statusCounts should contain(EmpiricalTransformed -> 12)
    CalibrationProvenance.Baseline.statusCounts should contain(CodeNoteEmpirical -> 67)
    CalibrationProvenance.Baseline.statusCounts should contain(TunedNeedsValidation -> 84)
    CalibrationProvenance.Baseline.statusCounts should contain(UnknownSource -> 20)
    CalibrationProvenance.Baseline.statusCounts should contain(Placeholder -> 2)
    CalibrationProvenance.Baseline.statusCounts should contain(Assumed -> 11)
    CalibrationProvenance.Baseline.statusCounts should contain(PolicyScenario -> 7)
  }

  it should "report remaining statuses from the typed registry" in {
    val report = CalibrationProvenance.Baseline.statusReport.map(row => row.status -> row.count).toMap

    report(UnknownSource) shouldBe CalibrationProvenance.Baseline.rowsWithStatus(UnknownSource).size
    report(TunedNeedsValidation) shouldBe CalibrationProvenance.Baseline.rowsWithStatus(TunedNeedsValidation).size
    report(CodeNoteEmpirical) shouldBe CalibrationProvenance.Baseline.rowsWithStatus(CodeNoteEmpirical).size
  }

  it should "carry stable ids, owners, rendered values, source metadata, and transformations" in {
    val baseWage = CalibrationProvenance.Baseline.parameters.find(_.id == "household.baseWage").get

    baseWage.parameterIds shouldBe Vector("household.baseWage")
    baseWage.renderedValue shouldBe "9652"
    baseWage.unit shouldBe "PLN/month"
    baseWage.provenance should include("GUS March 2026")
    baseWage.empiricalTarget shouldBe "Mean monthly gross wage"
    baseWage.transformation shouldBe "Direct"
    baseWage.ownerModules shouldBe Vector("HouseholdConfig")
    baseWage.status shouldBe Empirical
  }

  it should "expand grouped shorthand parameter ids to stable fully-qualified ids" in {
    val rent = CalibrationProvenance.Baseline.parameters.find(_.id == "household.rentMean").get

    rent.parameterIds shouldBe Vector("household.rentMean", "household.rentStd", "household.rentFloor")
  }

  it should "preserve current gaps as typed data" in {
    val revenueMultiplier = CalibrationProvenance.Baseline.parameters.find(_.id == "sectorDefs.revenueMultiplier").get
    val mpc               = CalibrationProvenance.Baseline.parameters.find(_.id == "household.mpc").get

    revenueMultiplier.status shouldBe UnknownSource
    revenueMultiplier.needsSourceMetadata shouldBe true
    mpc.status shouldBe TunedNeedsValidation
    mpc.needsValidationEvidence shouldBe true
  }

  it should "infer exemptions for structural, scenario, and startup-placeholder rows" in {
    val bufferTarget = CalibrationProvenance.Baseline.parameters.find(_.id == "household.bufferTargetMonths").get
    val riskOff      = CalibrationProvenance.Baseline.parameters.find(_.id == "forex.riskOffShockMonth").get
    val govCapital   = CalibrationProvenance.Baseline.parameters.find(_.id == "fiscal.govInitCapital").get

    bufferTarget.effectiveExemption shouldBe Some(StructuralAssumption)
    riskOff.effectiveExemption shouldBe Some(ScenarioSwitch)
    govCapital.effectiveExemption shouldBe Some(StartupPlaceholder)
  }

end CalibrationProvenanceSpec
