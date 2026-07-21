package com.boombustgroup.amorfati.config

/** Versioned, data-only contract for the registry-based enterprise controls
  * consumed by the future enterprise compiler.
  *
  * The first component represents the published REGON stratum of
  * registered-seat voivodeship, PKD 2007 section, and expected-number-of-
  * workers band. It is deliberately not an employment table: its counts do not
  * measure BAEL jobholders, job positions, or FTE.
  */
object EnterpriseControlSchema:

  /** Version of this logical contract. Persisted bundles must declare it so a
    * later reader never silently reinterprets source strata.
    */
  val SchemaVersion: Int = 1

  /** Non-negative count of entities in the source statistical universe. This is
    * neither a simulated-firm count nor a representation weight.
    */
  final case class EnterpriseCount(value: Long):
    require(value >= 0L, s"enterprise count must be non-negative: $value")

  /** Stable code in the bundle's declared registered-seat territorial
    * classification. It is not the legacy six-region runtime enum and it is not
    * a workplace location.
    */
  final case class RegisteredSeatRegionCode(value: String):
    require(value.trim.nonEmpty, "registered-seat region code must be non-empty")

  /** PKD 2007 section used by the published Q2 REGON workbook. The source
    * component preserves this input classification; any PKD-2025 or runtime
    * production-sector bridge is a separate, manifested transformation.
    */
  final case class Pkd2007SectionCode(value: String):
    require(value.matches("[A-U]"), s"PKD 2007 section code must be A-U, got: $value")

  /** Declared expected-number-of-workers band from the REGON source. It is a
    * classification label only and must never be converted to an employment
    * count through a midpoint or another implicit assumption.
    */
  final case class ExpectedWorkersBandCode(value: String):
    require(value.trim.nonEmpty, "expected-workers band code must be non-empty")

  /** Identity and version of a classification referenced by a bundle. */
  final case class ClassificationRef(id: String, version: String):
    require(id.trim.nonEmpty, "classification ID must be non-empty")
    require(version.trim.nonEmpty, s"classification $id must have a version")

  /** Declared source classifications and their accepted row vocabularies.
    *
    * Source residuals such as `Brak województwa` and `Brak PKD` are not added
    * to these classifications. They are carried by [[SourceResidualRow]] so an
    * unknown source value cannot masquerade as a TERYT region or PKD section.
    */
  final case class Classifications(
      registeredSeatRegion: ClassificationRef,
      pkd2007Section: ClassificationRef,
      expectedWorkersBand: ClassificationRef,
      regions: Vector[RegisteredSeatRegionCode],
      sections: Vector[Pkd2007SectionCode],
      workerBands: Vector[ExpectedWorkersBandCode],
  ):
    require(regions.nonEmpty, "enterprise controls require at least one registered-seat region")
    require(sections.nonEmpty, "enterprise controls require at least one PKD 2007 section")
    require(workerBands.nonEmpty, "enterprise controls require at least one expected-workers band")
    require(regions.distinct.size == regions.size, "enterprise-control registered-seat regions must be unique")
    require(sections.distinct.size == sections.size, "enterprise-control PKD 2007 sections must be unique")
    require(workerBands.distinct.size == workerBands.size, "enterprise-control expected-workers bands must be unique")

  /** One supported source cell. A row is a count of registered entities, not a
    * count of realised workers. The location is the registered seat only.
    */
  final case class EnterpriseStratumRow(
      registeredSeatRegion: RegisteredSeatRegionCode,
      pkd2007Section: Pkd2007SectionCode,
      expectedWorkersBand: ExpectedWorkersBandCode,
      count: EnterpriseCount,
  )

  /** Source cell with an explicit missing registered-seat or PKD dimension. The
    * absent axis corresponds to the Q2 REGON `Brak województwa` or `Brak PKD`
    * category. The other axis stays present whenever the source supplies it, so
    * the source's missing-both intersection remains one partition cell rather
    * than being double-counted in two aggregate residuals.
    *
    * These rows are retained for reconciliation but cannot be sampled as
    * ordinary enterprise geography or activity. An empty TSV cell means this
    * explicit source residual only; it must never be inferred from an absent
    * normal stratum.
    */
  final case class SourceResidualRow(
      registeredSeatRegion: Option[RegisteredSeatRegionCode],
      pkd2007Section: Option[Pkd2007SectionCode],
      expectedWorkersBand: ExpectedWorkersBandCode,
      count: EnterpriseCount,
  ):
    require(
      registeredSeatRegion.isEmpty || pkd2007Section.isEmpty,
      "source residual must have an explicit missing registered-seat or PKD dimension",
    )

  /** National source total by expected-workers band, copied from the source
    * table before the normal and residual cells are partitioned. It makes the
    * preservation of source residuals independently checkable.
    */
  final case class SourceTotalRow(expectedWorkersBand: ExpectedWorkersBandCode, count: EnterpriseCount)

  /** A coherent registry-control component. It has no claim about ownership,
    * financial stocks, actual employment, or a complete model baseline.
    */
  final case class Bundle(
      classifications: Classifications,
      enterpriseStrata: Vector[EnterpriseStratumRow],
      sourceResiduals: Vector[SourceResidualRow],
      sourceTotals: Vector[SourceTotalRow],
  ):
    require(enterpriseStrata.nonEmpty, "enterprise controls require at least one normal source stratum")
    require(sourceTotals.nonEmpty, "enterprise controls require source totals")

  /** One reconciliation between a national source total and the normal plus
    * residual cells preserved in the component.
    */
  final case class Reconciliation(
      id: String,
      expected: BigInt,
      actual: BigInt,
      tolerance: Long,
  ):
    def residual: BigInt = actual - expected
    def passes: Boolean  = residual.abs <= BigInt(tolerance)

  /** Structural failure that prevents a component from becoming compiler input.
    * These checks do not qualify source methodology or decide how the component
    * maps to runtime firms.
    */
  enum ValidationError:
    case DuplicateRow(table: String, key: String)
    case UnknownRegisteredSeatRegion(table: String, region: RegisteredSeatRegionCode)
    case UnknownPkd2007Section(table: String, section: Pkd2007SectionCode)
    case UnknownExpectedWorkersBand(table: String, band: ExpectedWorkersBandCode)
    case MissingSourceTotal(band: ExpectedWorkersBandCode)
    case FailedReconciliation(reconciliation: Reconciliation)

  /** Validation evidence retained with the loaded component. */
  final case class ValidationReport(reconciliations: Vector[Reconciliation], errors: Vector[ValidationError]):
    def isValid: Boolean = errors.isEmpty

  object Validator:

    /** Verify declared axes, uniqueness, and exact per-band preservation of the
      * source totals. A source residual may not be silently dropped just
      * because it cannot be represented as a normal firm stratum.
      */
    def validate(bundle: Bundle): ValidationReport =
      val axisErrors           = axisErrorsFor(bundle)
      val duplicates           = duplicateErrors(bundle)
      val reconciliations      = reconciliationChecks(bundle)
      val missingTotals        = bundle.classifications.workerBands.collect:
        case band if !bundle.sourceTotals.exists(_.expectedWorkersBand == band) => ValidationError.MissingSourceTotal(band)
      val reconciliationErrors = reconciliations.filterNot(_.passes).map(ValidationError.FailedReconciliation.apply)
      ValidationReport(reconciliations, axisErrors ++ duplicates ++ missingTotals ++ reconciliationErrors)

    private def axisErrorsFor(bundle: Bundle): Vector[ValidationError] =
      val regions  = bundle.classifications.regions.toSet
      val sections = bundle.classifications.sections.toSet
      val bands    = bundle.classifications.workerBands.toSet
      bundle.enterpriseStrata.flatMap: row =>
        Vector(
          Option.when(!regions(row.registeredSeatRegion))(ValidationError.UnknownRegisteredSeatRegion("enterprise-strata", row.registeredSeatRegion)),
          Option.when(!sections(row.pkd2007Section))(ValidationError.UnknownPkd2007Section("enterprise-strata", row.pkd2007Section)),
          Option.when(!bands(row.expectedWorkersBand))(ValidationError.UnknownExpectedWorkersBand("enterprise-strata", row.expectedWorkersBand)),
        ).flatten
      ++ bundle.sourceResiduals.flatMap: row =>
        Vector(
          row.registeredSeatRegion.filterNot(regions).map(ValidationError.UnknownRegisteredSeatRegion("source-residuals", _)),
          row.pkd2007Section.filterNot(sections).map(ValidationError.UnknownPkd2007Section("source-residuals", _)),
          Option.when(!bands(row.expectedWorkersBand))(ValidationError.UnknownExpectedWorkersBand("source-residuals", row.expectedWorkersBand)),
        ).flatten
      ++ bundle.sourceTotals.flatMap: row =>
        Option.when(!bands(row.expectedWorkersBand))(ValidationError.UnknownExpectedWorkersBand("source-totals", row.expectedWorkersBand)).toVector

    private def duplicateErrors(bundle: Bundle): Vector[ValidationError] =
      duplicateRows("enterprise-strata", bundle.enterpriseStrata)(row =>
        s"${row.registeredSeatRegion.value}|${row.pkd2007Section.value}|${row.expectedWorkersBand.value}",
      ) ++
        duplicateRows("source-residuals", bundle.sourceResiduals)(row =>
          s"${row.registeredSeatRegion.map(_.value)}|${row.pkd2007Section.map(_.value)}|${row.expectedWorkersBand.value}",
        ) ++
        duplicateRows("source-totals", bundle.sourceTotals)(row => row.expectedWorkersBand.value)

    private def reconciliationChecks(bundle: Bundle): Vector[Reconciliation] =
      bundle.sourceTotals.map: total =>
        val band     = total.expectedWorkersBand
        val normal   = bundle.enterpriseStrata.iterator.filter(_.expectedWorkersBand == band).map(row => BigInt(row.count.value)).sum
        val residual = bundle.sourceResiduals.iterator.filter(_.expectedWorkersBand == band).map(row => BigInt(row.count.value)).sum
        Reconciliation(
          id = s"source-total-by-expected-workers-band:${band.value}",
          expected = BigInt(total.count.value),
          actual = normal + residual,
          tolerance = 0L,
        )

    private def duplicateRows[A](table: String, rows: Vector[A])(key: A => String): Vector[ValidationError] =
      rows
        .groupBy(key)
        .collect { case (duplicateKey, values) if values.size > 1 => ValidationError.DuplicateRow(table, duplicateKey) }
        .toVector
        .sortBy(_.toString)

end EnterpriseControlSchema
