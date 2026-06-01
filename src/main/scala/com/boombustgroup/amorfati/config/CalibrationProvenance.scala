package com.boombustgroup.amorfati.config

import java.nio.file.Path
import scala.io.Source

/** Typed baseline calibration provenance for active `SimParams.defaults`
  * parameters.
  *
  * This is the initial code-level inventory migrated from
  * `docs/calibration-register.md`. It intentionally preserves the current
  * provenance gaps so tests and diagnostics can report them from code while
  * follow-up issues fill sources and validation evidence.
  */
object CalibrationProvenance:

  enum CalibrationStatusKind:
    case EmpiricalEvidence
    case SourceDetailMissing
    case ValidationNeeded
    case MissingSource
    case ExplicitAssumption
    case PolicyOrScenario
    case PlaceholderAssumption

  enum CalibrationExemptionKind:
    case StructuralAssumption
    case ScenarioSwitch
    case StartupPlaceholder

  enum CalibrationStatus(val token: String, val kind: CalibrationStatusKind):
    case Empirical            extends CalibrationStatus("EMPIRICAL", CalibrationStatusKind.EmpiricalEvidence)
    case EmpiricalTransformed extends CalibrationStatus("EMPIRICAL_TRANSFORMED", CalibrationStatusKind.EmpiricalEvidence)
    case CodeNoteEmpirical    extends CalibrationStatus("CODE_NOTE_EMPIRICAL", CalibrationStatusKind.SourceDetailMissing)
    case Assumed              extends CalibrationStatus("ASSUMED", CalibrationStatusKind.ExplicitAssumption)
    case TunedNeedsValidation extends CalibrationStatus("TUNED_NEEDS_VALIDATION", CalibrationStatusKind.ValidationNeeded)
    case PolicyScenario       extends CalibrationStatus("POLICY_SCENARIO", CalibrationStatusKind.PolicyOrScenario)
    case Placeholder          extends CalibrationStatus("PLACEHOLDER", CalibrationStatusKind.PlaceholderAssumption)
    case UnknownSource        extends CalibrationStatus("UNKNOWN_SOURCE", CalibrationStatusKind.MissingSource)

    def inferredExemption: Option[CalibrationExemptionKind] =
      this match
        case CalibrationStatus.Assumed        => Some(CalibrationExemptionKind.StructuralAssumption)
        case CalibrationStatus.PolicyScenario => Some(CalibrationExemptionKind.ScenarioSwitch)
        case CalibrationStatus.Placeholder    => Some(CalibrationExemptionKind.StartupPlaceholder)
        case _                                => None

  object CalibrationStatus:
    private val byToken: Map[String, CalibrationStatus] =
      values.map(status => status.token -> status).toMap

    def parse(value: String): Either[String, CalibrationStatus] =
      byToken.get(cleanToken(value)).toRight(s"Unknown calibration status: $value")

    private def cleanToken(value: String): String =
      value.trim.stripPrefix("`").stripSuffix("`")

  final case class PlaceholderDecision(
      parameterId: String,
      decision: CalibrationExemptionKind,
      reason: String,
      validationImpact: String,
      followUpPath: String,
  )

  final case class SourceMetadata(
      sourceFamily: String,
      sourceTableOrCode: String,
      vintage: String,
      sourceReference: String,
      transformationNotes: String,
  )

  enum CalibrationValidationMode(val token: String):
    case HistoricalFit            extends CalibrationValidationMode("HISTORICAL_FIT")
    case StylizedFactTarget       extends CalibrationValidationMode("STYLIZED_FACT_TARGET")
    case SensitivityRange         extends CalibrationValidationMode("SENSITIVITY_RANGE")
    case ModelBehaviorCalibration extends CalibrationValidationMode("MODEL_BEHAVIOR_CALIBRATION")

  final case class CalibrationValidationEvidence(
      mode: CalibrationValidationMode,
      evidencePath: Option[String],
      evidenceTarget: String,
      notes: String,
      artifactLabel: Option[String] = None,
      scenarioIds: Vector[String] = Vector.empty,
  ):
    require(
      evidencePath.forall(path => path == CalibrationValidationEvidence.normalizedPath(path)),
      s"Validation evidence path must be normalized and path-only: ${evidencePath.getOrElse("")}",
    )

    def hasEvidencePath: Boolean =
      evidencePath.nonEmpty

  object CalibrationValidationEvidence:
    def normalizedPath(rawPath: String): String =
      val trimmed = rawPath.trim
      require(trimmed.nonEmpty, "Validation evidence path cannot be empty")
      require(!trimmed.exists(_.isWhitespace), s"Validation evidence path must be path-only, got: $rawPath")
      require(!trimmed.contains(":"), s"Validation evidence path must not include URI or label prefixes, got: $rawPath")

      val path = Path.of(trimmed).normalize()
      require(path.getFileName != null, s"Validation evidence path must include a file name, got: $rawPath")
      path.toString

    def normalizedLabel(rawLabel: String): String =
      val trimmed = rawLabel.trim
      require(trimmed.nonEmpty, "Validation evidence label cannot be empty")
      trimmed

  final case class CalibrationParameter(
      id: String,
      parameterIds: Vector[String],
      renderedValue: String,
      unit: String,
      provenance: String,
      empiricalTarget: String,
      transformation: String,
      ownerModules: Vector[String],
      status: CalibrationStatus,
      validationEvidence: Option[CalibrationValidationEvidence] = None,
      exemption: Option[CalibrationExemptionKind] = None,
      placeholderDecision: Option[PlaceholderDecision] = None,
      sourceMetadata: Option[SourceMetadata] = None,
  ):
    def effectiveExemption: Option[CalibrationExemptionKind] =
      exemption.orElse(status.inferredExemption)

    def needsValidationEvidence: Boolean =
      status == CalibrationStatus.TunedNeedsValidation

    def hasValidationEvidencePath: Boolean =
      validationEvidence.exists(_.hasEvidencePath)

    def lacksValidationEvidencePath: Boolean =
      needsValidationEvidence && !hasValidationEvidencePath

    def needsSourceMetadata: Boolean =
      status == CalibrationStatus.UnknownSource || status == CalibrationStatus.CodeNoteEmpirical

  final case class CalibrationStatusReport(status: CalibrationStatus, count: Int)

  object Baseline:

    lazy val parameters: Vector[CalibrationParameter] =
      parsedRows.collect { case Right(parameter) => parameter }

    lazy val parseErrors: Vector[String] =
      parsedRows.collect { case Left(error) => error }

    lazy val statusCounts: Map[CalibrationStatus, Int] =
      parameters.groupMapReduce(_.status)(_ => 1)(_ + _)

    lazy val statusReport: Vector[CalibrationStatusReport] =
      CalibrationStatus.values.toVector.map(status => CalibrationStatusReport(status, statusCounts.getOrElse(status, 0)))

    def rowsWithStatus(status: CalibrationStatus): Vector[CalibrationParameter] =
      parameters.filter(_.status == status)

    lazy val placeholderDecisions: Vector[PlaceholderDecision] =
      rowsWithStatus(CalibrationStatus.Placeholder).flatMap(_.placeholderDecision)

    lazy val placeholderDecisionErrors: Vector[String] =
      val placeholderIds = rowsWithStatus(CalibrationStatus.Placeholder).map(_.id).toSet
      val decisionIds    = placeholderDecisionById.keySet
      (placeholderIds -- decisionIds).toVector.sorted.map(id => s"Missing placeholder decision for $id") ++
        (decisionIds -- placeholderIds).toVector.sorted.map(id => s"Placeholder decision is stale for non-placeholder $id")

    lazy val tunedValidationModeCounts: Map[CalibrationValidationMode, Int] =
      rowsWithStatus(CalibrationStatus.TunedNeedsValidation).flatMap(_.validationEvidence.map(_.mode)).groupMapReduce(identity)(_ => 1)(_ + _)

    lazy val tunedValidationEvidencePathCounts: Map[String, Int] =
      rowsWithStatus(CalibrationStatus.TunedNeedsValidation).groupMapReduce(parameter => if parameter.hasValidationEvidencePath then "linked" else "missing")(
        _ => 1,
      )(_ + _)

    lazy val tunedValidationRowsMissingEvidence: Vector[CalibrationParameter] =
      rowsWithStatus(CalibrationStatus.TunedNeedsValidation).filter(_.lacksValidationEvidencePath)

    lazy val tunedValidationEvidenceErrors: Vector[String] =
      val tunedRows      = rowsWithStatus(CalibrationStatus.TunedNeedsValidation)
      val tunedIds       = tunedRows.map(_.id).toSet
      val missingModeIds = tunedRows.filter(_.validationEvidence.isEmpty).map(parameter => s"Missing tuned validation mode for ${parameter.id}")
      val staleLinkIds   = validationEvidenceById.keySet.diff(tunedIds).toVector.sorted.map(id => s"Tuned validation evidence is stale for non-tuned $id")
      missingModeIds ++ staleLinkIds

    private def parseRow(line: String): Either[String, CalibrationParameter] =
      val cells = line.trim.stripPrefix("|").stripSuffix("|").split("\\|", -1).iterator.map(_.trim).toVector
      if cells.length != 8 then Left(s"Expected 8 calibration columns, got ${cells.length}: $line")
      else
        val parameterIds = splitParameterIds(cells(0))
        val ownerModules = splitCodeList(cells(6))
        for
          id     <- parameterIds.headOption.toRight(s"Missing parameter id: $line")
          status <- CalibrationStatus.parse(cells(7))
        yield
          val parameter = CalibrationParameter(
            id = id,
            parameterIds = parameterIds,
            renderedValue = stripCode(cells(1)),
            unit = cells(2),
            provenance = stripCode(cells(3)),
            empiricalTarget = stripCode(cells(4)),
            transformation = stripCode(cells(5)),
            ownerModules = ownerModules,
            status = status,
            placeholderDecision =
              if status == CalibrationStatus.Placeholder then placeholderDecisionById.get(id)
              else None,
            sourceMetadata = sourceMetadataById.get(id),
          )
          parameter.copy(validationEvidence = validationEvidenceFor(parameter))

    private val placeholderDecisionById: Map[String, PlaceholderDecision] =
      Vector(
        PlaceholderDecision(
          parameterId = "immigration.initStock",
          decision = CalibrationExemptionKind.StartupPlaceholder,
          reason = "The baseline starts with zero immigrant households; migration enters through monthly inflow dynamics.",
          validationImpact = "Opening migration-stock comparisons are not meaningful until a data-bridge initial stock is added.",
          followUpPath = "Add a data-bridge initial stock for immigrant households before validating opening migration-stock levels.",
        ),
      ).map(decision => decision.parameterId -> decision).toMap

    private val sourceMetadataById: Map[String, SourceMetadata] =
      Vector(
        "pop.initialUnemploymentRate" -> SourceMetadata(
          sourceFamily = "Labor market",
          sourceTableOrCode = "GUS registered unemployment",
          vintage = "March 2026",
          sourceReference = "https://stat.gov.pl/en/topics/labour-market/",
          transformationNotes = "Registered-unemployment stock used as the opening household labor-force unemployment rate.",
        ),
        "fiscal.govBaseSpending"      -> SourceMetadata(
          sourceFamily = "Fiscal stance",
          sourceTableOrCode = "MF state-budget 2026 spending plan",
          vintage = "2026 budget plan",
          sourceReference = "https://www.gov.pl/web/finance/state-budget",
          transformationNotes = "Annual expenditure plan 918.9bn PLN divided by 12 and scaled by gdpRatio at runtime.",
        ),
        "banking.initGovBonds"        -> SourceMetadata(
          sourceFamily = "Banking and central-bank balance sheets",
          sourceTableOrCode = "NBP MFI and central-bank government securities bridge",
          vintage = "2026-04-30 model-start bridge",
          sourceReference = "https://nbp.pl/en/statistic-and-financial-reporting/monetary-and-financial-statistics/consolidated-balance-sheet-of-mfis/",
          transformationNotes = "Bank and NBP government-bond opening stocks are mapped to model holder buckets and scaled by gdpRatio.",
        ),
        "forex.importPropensity"      -> SourceMetadata(
          sourceFamily = "External sector",
          sourceTableOrCode = "GUS/NBP import-to-GDP bridge",
          vintage = "2026-04-30 model-start bridge",
          sourceReference = "docs/empirical-validation-source-manifest.csv target: Current account",
          transformationNotes = "Aggregate imports are normalized to GDP and used as the import-propensity coefficient.",
        ),
        "equity.peMean"               -> SourceMetadata(
          sourceFamily = "Financial markets and non-bank finance",
          sourceTableOrCode = "policy-rates-market-yields-and-gpw",
          vintage = "2026-04-30 model-start bridge",
          sourceReference = "docs/empirical-validation-source-manifest.csv target: Monetary and financial market conditions",
          transformationNotes = "GPW valuation notes are reduced to long-run P/E and annual dividend-yield anchors.",
        ),
        "housing.mortgageSpread"      -> SourceMetadata(
          sourceFamily = "Housing and mortgages",
          sourceTableOrCode = "NBP MIR housing-loan rate spread",
          vintage = "2026-04-30 model-start bridge",
          sourceReference = "https://nbp.pl/en/statistic-and-financial-reporting/monetary-and-financial-statistics/mir-statistics/",
          transformationNotes = "Mortgage lending-rate spread over the policy-rate anchor is used directly.",
        ),
      ).toMap

    private val validationEvidenceById: Map[String, CalibrationValidationEvidence] =
      Vector(
        "pop.firmSizeDist"             -> linkedEvidence(
          CalibrationValidationMode.StylizedFactTarget,
          "docs/empirical-validation/baseline-validation-snapshot.csv",
          "Firm-size distribution family",
          "EmpiricalValidationExport exposes the current aggregate firm-size bridge.",
          artifactLabel = Some("Firm-size distribution"),
        ),
        "pop.firmSizeMicroShare"       -> linkedEvidence(
          CalibrationValidationMode.StylizedFactTarget,
          "docs/empirical-validation/baseline-validation-snapshot.csv",
          "Micro-enterprise share",
          "EmpiricalValidationExport compares the terminal micro-firm share to the GUS active-enterprise comparator.",
          artifactLabel = Some("Firm-size distribution - Micro"),
        ),
        "pop.firmSizeSmallShare"       -> linkedEvidence(
          CalibrationValidationMode.StylizedFactTarget,
          "docs/empirical-validation/baseline-validation-snapshot.csv",
          "Small-enterprise share",
          "EmpiricalValidationExport compares the terminal small-firm share to the GUS active-enterprise comparator.",
          artifactLabel = Some("Firm-size distribution - Small"),
        ),
        "pop.firmSizeMediumShare"      -> linkedEvidence(
          CalibrationValidationMode.StylizedFactTarget,
          "docs/empirical-validation/baseline-validation-snapshot.csv",
          "Medium-enterprise share",
          "EmpiricalValidationExport compares the terminal medium-firm share to the GUS active-enterprise comparator.",
          artifactLabel = Some("Firm-size distribution - Medium"),
        ),
        "pop.firmSizeLargeShare"       -> linkedEvidence(
          CalibrationValidationMode.StylizedFactTarget,
          "docs/empirical-validation/baseline-validation-snapshot.csv",
          "Large-enterprise share",
          "EmpiricalValidationExport compares the terminal large-firm share to the GUS active-enterprise comparator.",
          artifactLabel = Some("Firm-size distribution - Large"),
        ),
        "sectorDefs.share"             -> linkedEvidence(
          CalibrationValidationMode.StylizedFactTarget,
          "docs/empirical-validation/baseline-validation-snapshot.csv",
          "Six-sector output and employment bridge",
          "EmpiricalValidationExport carries the sectoral-output bridge as missing-data evidence until a full source crosswalk is available.",
          artifactLabel = Some("Sectoral output"),
        ),
        "household.mpc"                -> linkedEvidence(
          CalibrationValidationMode.SensitivityRange,
          "docs/sensitivity-robustness-workflow.md",
          "Consumption-led demand sensitivity",
          "SensitivityRobustnessExport contains one-at-a-time household MPC scenarios for output, inflation, credit, and fiscal metrics.",
          artifactLabel = Some("sensitivity-summary.csv"),
          scenarioIds = Vector("mpc-low", "mpc-high"),
        ),
        "firm.productivityGrowth"      -> linkedEvidence(
          CalibrationValidationMode.HistoricalFit,
          "docs/empirical-validation/baseline-validation-snapshot.csv",
          "Baseline GDP growth path",
          "EmpiricalValidationExport records the current GDP-growth bridge and model output used to judge the productivity/catch-up path.",
          artifactLabel = Some("GDP growth"),
        ),
        "capital.adjustSpeed"          -> linkedEvidence(
          CalibrationValidationMode.SensitivityRange,
          "docs/sensitivity-robustness-workflow.md",
          "Investment and balance-sheet sensitivity",
          "SensitivityRobustnessExport varies capital adjustment speed and records terminal deltas against baseline.",
          artifactLabel = Some("sensitivity-summary.csv"),
          scenarioIds = Vector("investment-fast"),
        ),
        "fiscal.govInitCapital"        -> linkedEvidence(
          CalibrationValidationMode.HistoricalFit,
          "docs/empirical-validation/baseline-validation-snapshot.csv",
          "Public investment and fiscal stance bridge",
          "EmpiricalValidationExport keeps the current fiscal coverage bridge visible while public-capital stock validation remains partial.",
          artifactLabel = Some("Fiscal stance - state budget expenditure plan 2026"),
        ),
        "monetary.neutralRate"         -> linkedEvidence(
          CalibrationValidationMode.SensitivityRange,
          "docs/sensitivity-robustness-workflow.md",
          "Monetary-policy sensitivity",
          "SensitivityRobustnessExport varies neutral rate and Taylor response together in the monetary-tight scenario.",
          artifactLabel = Some("sensitivity-summary.csv"),
          scenarioIds = Vector("monetary-tight"),
        ),
        "forex.irpSensitivity"         -> linkedEvidence(
          CalibrationValidationMode.SensitivityRange,
          "docs/sensitivity-robustness-workflow.md",
          "FX and external-balance sensitivity",
          "SensitivityRobustnessExport varies IRP sensitivity in the external-risk-off scenario and reports FX/current-account metrics.",
          artifactLabel = Some("sensitivity-summary.csv"),
          scenarioIds = Vector("external-risk-off"),
        ),
        "pricing.demandSensitivity"    -> linkedEvidence(
          CalibrationValidationMode.SensitivityRange,
          "docs/sensitivity-robustness-workflow.md",
          "Price-level and markup sensitivity",
          "SensitivityRobustnessExport varies cost pass-through in the markup-high scenario and reports inflation and wage-path metrics.",
          artifactLabel = Some("sensitivity-summary.csv"),
          scenarioIds = Vector("markup-high"),
        ),
        "housing.originationRate"      -> linkedEvidence(
          CalibrationValidationMode.HistoricalFit,
          "docs/empirical-validation/baseline-validation-snapshot.csv",
          "Mortgage origination/default bridge",
          "EmpiricalValidationExport carries mortgage stock and default-flow validation rows for the housing credit channel.",
          artifactLabel = Some("Housing and mortgages - mortgage default bridge"),
        ),
        "household.ccEligRate"         -> linkedEvidence(
          CalibrationValidationMode.StylizedFactTarget,
          "docs/household-credit-stress-calibration.md",
          "Liquidity shortfall versus approved consumer-credit origination",
          "HouseholdCreditStressCalibrationExport reports ShortfallToApprovedOrigination and rejected consumer-credit demand diagnostics for the #534 credit-access calibration.",
          artifactLabel = Some("household-credit-stress-summary.csv"),
          scenarioIds = Vector("issue-534"),
        ),
        "nbfi.creditBaseRate"          -> linkedEvidence(
          CalibrationValidationMode.HistoricalFit,
          "docs/private-credit-renewal-calibration.md",
          "Private credit renewal calibration",
          "Issue #610 documents the Nix-built 10-seed 60-month NBFI stock-renewal calibration and terminal private-credit split.",
          artifactLabel = Some("20260525-610-nbfi-only"),
        ),
        "gvc.exportCapacityElasticity" -> linkedEvidence(
          CalibrationValidationMode.HistoricalFit,
          "docs/external-sector-baseline-calibration.md",
          "Current-account and trade-balance baseline path",
          "Issue #617 documents the Nix-built 5-seed 60-month external-sector baseline calibration and before/after BoP decomposition.",
          artifactLabel = Some("20260525-617-external-baseline-after035"),
        ),
      ).toMap

    private def linkedEvidence(
        mode: CalibrationValidationMode,
        evidencePath: String,
        evidenceTarget: String,
        notes: String,
        artifactLabel: Option[String],
        scenarioIds: Vector[String] = Vector.empty,
    ): CalibrationValidationEvidence =
      CalibrationValidationEvidence(
        mode = mode,
        evidencePath = Some(CalibrationValidationEvidence.normalizedPath(evidencePath)),
        evidenceTarget = evidenceTarget,
        notes = notes,
        artifactLabel = artifactLabel.map(CalibrationValidationEvidence.normalizedLabel),
        scenarioIds = scenarioIds.map(CalibrationValidationEvidence.normalizedLabel),
      )

    private def validationEvidenceFor(parameter: CalibrationParameter): Option[CalibrationValidationEvidence] =
      if parameter.status != CalibrationStatus.TunedNeedsValidation then None
      else
        validationEvidenceById
          .get(parameter.id)
          .orElse:
            Some(
              CalibrationValidationEvidence(
                mode = inferValidationMode(parameter),
                evidencePath = None,
                evidenceTarget = parameter.empiricalTarget,
                notes = "Expected validation mode is classified, but no concrete validation artifact is linked yet.",
              ),
            )

    private def inferValidationMode(parameter: CalibrationParameter): CalibrationValidationMode =
      val ids         = parameter.parameterIds
      val description = s"${parameter.provenance} ${parameter.empiricalTarget} ${parameter.transformation}".toLowerCase

      if ids.exists(id => id.startsWith("pop.firmSize") || id.startsWith("sectorDefs") || id == "io.matrix") then CalibrationValidationMode.StylizedFactTarget
      else if ids.exists(id =>
          id.startsWith("firm.ai") ||
            id.startsWith("firm.hybrid") ||
            id.startsWith("firm.digi") ||
            id.startsWith("firm.demo") ||
            id.startsWith("firm.adoption") ||
            id.startsWith("fdi.ma") ||
            id.startsWith("regional.") ||
            id.startsWith("soe.") ||
            id.startsWith("informal."),
        )
      then CalibrationValidationMode.ModelBehaviorCalibration
      else if containsAny(
          description,
          "gdp",
          "inflation",
          "unemployment",
          "wage",
          "debt",
          "deficit",
          "current account",
          "mortgage",
          "firm-size",
          "reference rate",
        )
      then CalibrationValidationMode.HistoricalFit
      else CalibrationValidationMode.SensitivityRange

    private def containsAny(value: String, needles: String*): Boolean =
      needles.exists(value.contains)

    private def splitCodeList(value: String): Vector[String] =
      stripCode(value)
        .split(",")
        .iterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .toVector

    private def splitParameterIds(value: String): Vector[String] =
      val ids    = splitCodeList(value)
      val prefix = ids.headOption.flatMap: id =>
        val dotIndex = id.lastIndexOf('.')
        if dotIndex < 0 then None else Some(id.take(dotIndex + 1))
      ids.map: id =>
        if id.contains(".") then id else prefix.fold(id)(_ + id)

    private def stripCode(value: String): String =
      value.replace("`", "").trim

    private lazy val parsedRows: Vector[Either[String, CalibrationParameter]] =
      rawRows.map(parseRow)

    val baselineRowsResource: String =
      "com/boombustgroup/amorfati/config/calibration-provenance-baseline.md"

    private lazy val rawRows: Vector[String] =
      loadBaselineRows(baselineRowsResource)

    private def loadBaselineRows(resourcePath: String): Vector[String] =
      val stream = Option(Thread.currentThread().getContextClassLoader)
        .flatMap(loader => Option(loader.getResourceAsStream(resourcePath)))
        .orElse(Option(getClass.getClassLoader).flatMap(loader => Option(loader.getResourceAsStream(resourcePath))))
        .getOrElse(throw IllegalStateException(s"Missing calibration provenance resource: $resourcePath"))
      val source = Source.fromInputStream(stream, "UTF-8")
      try source.getLines().toVector.filter(_.trim.startsWith("| `"))
      finally source.close()
