package com.boombustgroup.amorfati.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CalibrationProvenanceSpec extends AnyFlatSpec with Matchers:

  import CalibrationProvenance.*
  import CalibrationProvenance.CalibrationExemptionKind.*
  import CalibrationProvenance.CalibrationStatus.*
  import CalibrationProvenance.CalibrationStatusKind.*
  import CalibrationProvenance.CalibrationValidationMode.*

  private def baselineParameter(id: String): CalibrationParameter =
    CalibrationProvenance.Baseline.parameters
      .find(_.id == id)
      .getOrElse(fail(s"Expected parameter '$id' not found in CalibrationProvenance.Baseline.parameters"))

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
    CalibrationProvenance.Baseline.parseErrors shouldBe empty
    CalibrationProvenance.Baseline.parameters should have size 248
    CalibrationProvenance.Baseline.statusCounts should contain(Empirical -> 37)
    CalibrationProvenance.Baseline.statusCounts should contain(EmpiricalTransformed -> 19)
    CalibrationProvenance.Baseline.statusCounts should contain(CodeNoteEmpirical -> 62)
    CalibrationProvenance.Baseline.statusCounts should contain(TunedNeedsValidation -> 88)
    CalibrationProvenance.Baseline.statusCounts.getOrElse(UnknownSource, 0) shouldBe 0
    CalibrationProvenance.Baseline.statusCounts should contain(Placeholder -> 1)
    CalibrationProvenance.Baseline.statusCounts should contain(Assumed -> 34)
    CalibrationProvenance.Baseline.statusCounts should contain(PolicyScenario -> 7)
  }

  it should "report remaining statuses from the typed registry" in {
    val report = CalibrationProvenance.Baseline.statusReport.map(row => row.status -> row.count).toMap

    report(UnknownSource) shouldBe CalibrationProvenance.Baseline.rowsWithStatus(UnknownSource).size
    report(TunedNeedsValidation) shouldBe CalibrationProvenance.Baseline.rowsWithStatus(TunedNeedsValidation).size
    report(CodeNoteEmpirical) shouldBe CalibrationProvenance.Baseline.rowsWithStatus(CodeNoteEmpirical).size
  }

  it should "carry stable ids, owners, rendered values, source metadata, and transformations" in {
    val baseWage = baselineParameter("household.baseWage")

    baseWage.parameterIds shouldBe Vector("household.baseWage")
    baseWage.renderedValue shouldBe "9652"
    baseWage.unit shouldBe "PLN/month"
    baseWage.provenance should include("GUS March 2026")
    baseWage.empiricalTarget shouldBe "Mean monthly gross wage"
    baseWage.transformation shouldBe "Direct"
    baseWage.ownerModules shouldBe Vector("HouseholdConfig")
    baseWage.status shouldBe Empirical
  }

  it should "carry typed source metadata for migrated code-note empirical rows" in {
    val expectedSources = Vector(
      ("pop.initialUnemploymentRate", "Labor market", "GUS registered unemployment", "March 2026", "stat.gov.pl", "opening household"),
      ("fiscal.govBaseSpending", "Fiscal stance", "MF state-budget 2026 spending plan", "2026 budget plan", "gov.pl", "divided by 12"),
      (
        "banking.initGovBonds",
        "Banking and central-bank balance sheets",
        "NBP MFI and central-bank government securities bridge",
        "2026-04-30",
        "nbp.pl",
        "model holder buckets",
      ),
      (
        "banking.initialCcyb",
        "Banking macroprudential buffers",
        "Dz.U. 2024 poz. 1400 countercyclical buffer regulation",
        "2026-04-30",
        "eli.gov.pl",
        "opening macroprudential state",
      ),
      (
        "banking.osiiBuffers",
        "Banking macroprudential buffers",
        "KNF O-SII buffer adequacy review",
        "2026-04-30",
        "knf.gov.pl",
        "Named bank rows",
      ),
      ("forex.importPropensity", "External sector", "GUS/NBP import-to-GDP bridge", "2026-04-30", "Current account", "normalized to GDP"),
      (
        "equity.peMean",
        "Financial markets and non-bank finance",
        "policy-rates-market-yields-and-gpw",
        "2026-04-30",
        "Monetary and financial market conditions",
        "P/E",
      ),
      ("housing.mortgageSpread", "Housing and mortgages", "NBP MIR housing-loan rate spread", "2026-04-30", "mir-statistics", "spread over the policy-rate"),
      (
        "io.matrix",
        "Input-output accounts",
        "GUS 2020 domestic input-output table, Table 3",
        "published 2024-06-27",
        "io-technical-coefficients.tsv",
        "rounded to percentage-point technical coefficients",
      ),
    )

    expectedSources.foreach: (id, family, tableOrCode, vintage, referenceFragment, transformFragment) =>
      withClue(id) {
        val parameter = baselineParameter(id)
        parameter.status should not equal CodeNoteEmpirical
        val source    = parameter.sourceMetadata.getOrElse(fail(s"Expected typed source metadata for '$id'"))
        source.sourceFamily shouldBe family
        source.sourceTableOrCode shouldBe tableOrCode
        source.vintage should include(vintage)
        source.sourceReference should include(referenceFragment)
        source.transformationNotes should include(transformFragment)
      }

    CalibrationProvenance.Baseline.rowsWithStatus(CodeNoteEmpirical).flatMap(_.sourceMetadata) shouldBe empty
  }

  it should "expand grouped shorthand parameter ids to stable fully-qualified ids" in {
    val rent = baselineParameter("household.rentMean")

    rent.parameterIds shouldBe Vector("household.rentMean", "household.rentStd", "household.rentFloor")
  }

  it should "preserve current gaps as typed data" in {
    val revenueMultiplier = baselineParameter("sectorDefs.revenueMultiplier")
    val mpc               = baselineParameter("household.mpc")

    CalibrationProvenance.Baseline.rowsWithStatus(UnknownSource) shouldBe empty
    revenueMultiplier.status shouldBe Assumed
    revenueMultiplier.provenance shouldBe "Structural sector productivity prior"
    revenueMultiplier.effectiveExemption shouldBe Some(StructuralAssumption)
    revenueMultiplier.needsSourceMetadata shouldBe false
    mpc.status shouldBe TunedNeedsValidation
    mpc.needsValidationEvidence shouldBe true
  }

  it should "classify sector sigma as a structural automation-substitution assumption" in {
    val sectorSigma = baselineParameter("sectorDefs.sigma")

    sectorSigma.status shouldBe Assumed
    sectorSigma.effectiveExemption shouldBe Some(StructuralAssumption)
    sectorSigma.validationEvidence shouldBe None
    sectorSigma.provenance shouldBe "Structural automation/substitution ranking"
    sectorSigma.empiricalTarget should include("BPO/SSC stylized high")
    sectorSigma.empiricalTarget should include("Public stylized very low")
    sectorSigma.transformation should include("sector output")
    sectorSigma.transformation should include("do not validate it")
    sectorSigma.transformation should include("sigma scenario envelope")
  }

  it should "classify tuned parameters by validation mode and preserve missing evidence paths" in {
    val tuned      = CalibrationProvenance.Baseline.rowsWithStatus(TunedNeedsValidation)
    val pathCounts = CalibrationProvenance.Baseline.tunedValidationEvidencePathCounts

    CalibrationProvenance.Baseline.tunedValidationEvidenceErrors shouldBe empty
    tuned should have size 88
    tuned.flatMap(_.validationEvidence) should have size tuned.size
    CalibrationProvenance.Baseline.tunedValidationModeCounts.values.sum shouldBe tuned.size
    CalibrationValidationMode.values.foreach: mode =>
      withClue(mode.token) {
        CalibrationProvenance.Baseline.tunedValidationModeCounts.getOrElse(mode, 0) should be > 0
      }
    pathCounts("linked") should be > 0
    pathCounts("missing") should be > 0
    CalibrationProvenance.Baseline.tunedValidationRowsMissingEvidence should not be empty

    val firmSize = baselineParameter("pop.firmSizeMicroShare").validationEvidence.getOrElse(fail("Expected firm-size validation evidence"))
    firmSize.mode shouldBe StylizedFactTarget
    firmSize.evidencePath.getOrElse(fail("Expected firm-size evidence path")) shouldBe "docs/empirical-validation/baseline-validation-snapshot.tsv"
    firmSize.artifactLabel shouldBe Some("Firm-size distribution - Micro")
    firmSize.evidenceTarget should include("Micro")

    val sectorShares = baselineParameter("sectorDefs.share").validationEvidence.getOrElse(fail("Expected sector-share validation evidence"))
    sectorShares.mode shouldBe StylizedFactTarget
    sectorShares.evidencePath.getOrElse(fail("Expected sector-share evidence path")) shouldBe
      "docs/empirical-validation/baseline-validation-snapshot.tsv"
    sectorShares.artifactLabel shouldBe Some("Sector firm-population shares")
    sectorShares.evidenceTarget should include("firm-population")
    sectorShares.notes should include("sectorDefs.share")
    sectorShares.notes should include("bridge assumptions")
    sectorShares.notes should include("separate diagnostics")

    val sectorWages = baselineParameter("sectorDefs.wageMultiplier").validationEvidence.getOrElse(fail("Expected sector-wage validation evidence"))
    sectorWages.mode shouldBe StylizedFactTarget
    sectorWages.evidencePath.getOrElse(fail("Expected sector-wage evidence path")) shouldBe
      "docs/empirical-validation/baseline-validation-snapshot.tsv"
    sectorWages.artifactLabel shouldBe Some("Sector wage ratios")
    sectorWages.evidenceTarget should include("wage-ratio")
    sectorWages.notes should include("wageMultiplier validation")
    sectorWages.notes should include("compensation-per-employee bridge assumptions")

    val ioMatrix = baselineParameter("io.matrix")
    ioMatrix.status shouldBe EmpiricalTransformed
    ioMatrix.validationEvidence shouldBe None
    ioMatrix.transformation should include("rounded to percentage-point coefficients")
    ioMatrix.transformation should include("supplier/input sector i used by sector j")

    val ioSpillover = baselineParameter("io.crossSectorSpillover").validationEvidence.getOrElse(fail("Expected spillover validation mode"))
    ioSpillover.mode shouldBe SensitivityRange
    ioSpillover.evidencePath shouldBe None
    ioSpillover.notes should include("do not estimate the share of unmet demand")

    val mpc = baselineParameter("household.mpc").validationEvidence.getOrElse(fail("Expected MPC validation evidence"))
    mpc.mode shouldBe SensitivityRange
    mpc.evidencePath.getOrElse(fail("Expected MPC evidence path")) shouldBe "docs/sensitivity-robustness-workflow.md"
    mpc.artifactLabel shouldBe Some("sensitivity-summary.tsv")
    mpc.scenarioIds shouldBe Vector("mpc-low", "mpc-high")
    mpc.notes should include("one-at-a-time household MPC")

    val depositPanic = baselineParameter("banking.depositPanicRate").validationEvidence.getOrElse(fail("Expected deposit panic validation mode"))
    depositPanic.mode shouldBe SensitivityRange
    depositPanic.evidencePath shouldBe None
    depositPanic.notes should include("no concrete validation artifact")
  }

  it should "infer exemptions for structural, scenario, and startup-placeholder rows" in {
    val bufferTarget   = baselineParameter("household.bufferTargetMonths")
    val riskOff        = baselineParameter("forex.riskOffShockMonth")
    val govCapital     = baselineParameter("fiscal.govInitCapital")
    val immigrantStock = baselineParameter("immigration.initStock")

    bufferTarget.effectiveExemption shouldBe Some(StructuralAssumption)
    riskOff.effectiveExemption shouldBe Some(ScenarioSwitch)
    immigrantStock.effectiveExemption shouldBe Some(StartupPlaceholder)
    govCapital.status shouldBe TunedNeedsValidation
    govCapital.renderedValue shouldBe "2332e9"
    govCapital.ownerModules should contain("FiscalConfig")
    govCapital.ownerModules should contain("SimParams")
    govCapital.ownerModules should contain("WorldInit")
    govCapital.effectiveExemption shouldBe None
  }

  it should "make remaining placeholder decisions visible as typed metadata" in {
    val placeholders = CalibrationProvenance.Baseline.rowsWithStatus(Placeholder)

    placeholders.map(_.id) shouldBe Vector("immigration.initStock")
    CalibrationProvenance.Baseline.placeholderDecisionErrors shouldBe empty

    val decision = CalibrationProvenance.Baseline.placeholderDecisions.headOption
      .getOrElse(fail("Expected typed placeholder decision for immigration.initStock"))
    decision.parameterId shouldBe "immigration.initStock"
    decision.decision shouldBe StartupPlaceholder
    decision.reason should include("zero immigrant households")
    decision.validationImpact should include("Opening migration-stock comparisons")
    decision.followUpPath should include("data-bridge initial stock")
    placeholders.head.placeholderDecision shouldBe Some(decision)
    CalibrationProvenance.Baseline.parameters
      .filterNot(_.status == Placeholder)
      .flatMap(_.placeholderDecision) shouldBe empty
  }

end CalibrationProvenanceSpec
