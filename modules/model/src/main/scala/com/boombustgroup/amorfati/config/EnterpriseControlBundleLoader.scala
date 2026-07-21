package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.config.EnterpriseControlSchema.*
import com.boombustgroup.amorfati.tsv.TsvRows

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.LocalDate
import scala.util.Try

/** Data-only loader for the registry-based enterprise-control component of a
  * reference economy.
  *
  * The component contains normalized TSV data derived from a declared source
  * artifact. It does not parse XLSX, evaluate Scala configuration, initialize
  * runtime firms, or bridge registry counts to BAEL employment. Keeping raw
  * source ingestion outside this loader prevents an Excel dependency from
  * entering the model runtime and makes the normalized payload independently
  * integrity-checkable.
  */
object EnterpriseControlBundleLoader:

  private type TsvRow = TsvRows.Row

  private val ManifestFile         = "manifest.tsv"
  private val RegionsFile          = "registered-seat-regions.tsv"
  private val Pkd2007SectionsFile  = "pkd2007-sections.tsv"
  private val ExpectedWorkersFile  = "expected-workers-bands.tsv"
  private val EnterpriseStrataFile = "enterprise-strata.tsv"
  private val SourceResidualsFile  = "source-residuals.tsv"
  private val SourceTotalsFile     = "source-totals.tsv"

  private val ComponentFiles = Vector(
    RegionsFile,
    Pkd2007SectionsFile,
    ExpectedWorkersFile,
    EnterpriseStrataFile,
    SourceResidualsFile,
    SourceTotalsFile,
  )
  private val SnapshotFiles  = ManifestFile +: ComponentFiles

  /** SHA-256 digest of the normalized component payload. It is distinct from
    * the upstream source-artifact hash in [[SourceArtifact]].
    */
  final case class EnterpriseControlsDigest private (value: String):
    override def toString: String = value

  object EnterpriseControlsDigest:
    def fromSha256Hex(value: String): EnterpriseControlsDigest =
      val normalized = Option(value).fold("")(_.trim.toLowerCase)
      require(normalized.matches("[0-9a-f]{64}"), "enterprise-controls digest must be a 64-character SHA-256 hex value")
      EnterpriseControlsDigest(normalized)

    private[config] def sha256Utf8(value: String): EnterpriseControlsDigest =
      EnterpriseControlsDigest(sha256Hex(value.getBytes(UTF_8)))

  /** Immutable identity and vintage of the raw source artifact that produced
    * the normalized TSV payload. The raw artifact itself is intentionally not
    * stored in this component.
    */
  final case class SourceArtifact(
      provider: String,
      location: String,
      sha256: String,
      observationPeriod: String,
      release: String,
      accessedAt: LocalDate,
  ):
    require(provider.trim.nonEmpty, "source provider must be non-empty")
    require(location.trim.nonEmpty, "source location must be non-empty")
    require(sha256.matches("[0-9a-f]{64}"), "source SHA-256 must be a 64-character lowercase hex value")
    require(observationPeriod.trim.nonEmpty, "source observation period must be non-empty")
    require(release.trim.nonEmpty, "source release must be non-empty")

  /** Parsed manifest for one enterprise-control component. The component digest
    * covers all fields except `enterprise_controls_digest` and every normalized
    * TSV file; changing either the source artifact or a derived row changes the
    * digest.
    */
  final case class Manifest(
      schemaVersion: Int,
      baseline: BaselineRef,
      enterpriseControlsDigest: EnterpriseControlsDigest,
      classifications: Classifications,
      source: SourceArtifact,
  )

  /** Successfully loaded normalized component and the validation evidence that
    * its source totals have been preserved.
    */
  final case class Loaded(manifest: Manifest, controls: Bundle, validation: ValidationReport)

  /** Failure that prevents this component from entering a future enterprise
    * compiler. Diagnostics retain physical TSV paths and source line numbers
    * where available.
    */
  enum LoadError:
    case InvalidTsv(path: Path, detail: String)
    case InvalidManifest(path: Path, detail: String)
    case UnsupportedSchemaVersion(expected: Int, actual: Int)
    case InvalidControlRow(path: Path, lineNumber: Int, detail: String)
    case EnterpriseControlsDigestMismatch(expected: EnterpriseControlsDigest, actual: EnterpriseControlsDigest)
    case ControlValidationFailed(errors: Vector[ValidationError])

  /** Read an immutable file snapshot, verify its component digest, parse the
    * normalized controls, and preserve validation evidence. A successful call
    * parses the same bytes that were hashed.
    */
  def load(root: Path): Either[LoadError, Loaded] =
    for
      snapshot    <- readSnapshot(root)
      manifest    <- readManifest(snapshot)
      _           <- ensureSupportedSchema(manifest)
      actualDigest = computeDigest(snapshot, manifest)
      _           <- Either.cond(
        actualDigest == manifest.enterpriseControlsDigest,
        (),
        LoadError.EnterpriseControlsDigestMismatch(manifest.enterpriseControlsDigest, actualDigest),
      )
      controls    <- readControls(snapshot, manifest.classifications)
      validation   = Validator.validate(controls)
      _           <- Either.cond(validation.isValid, (), LoadError.ControlValidationFailed(validation.errors))
    yield Loaded(manifest, controls, validation)

  /** Recompute a normalized-payload digest for a fixture or extraction output.
    * Production callers use [[load]], which snapshots once before digesting and
    * parsing.
    */
  private[config] def computeDigest(root: Path, manifest: Manifest): Either[LoadError, EnterpriseControlsDigest] =
    readSnapshot(root).map(snapshot => computeDigest(snapshot, manifest))

  private final class ComponentSnapshot(
      val root: Path,
      private val bytesByFile: Map[String, Array[Byte]],
  ):
    def path(file: String): Path = root.resolve(file)

    def copiedBytes(file: String): Array[Byte] = bytesByFile(file).clone()

    def utf8(file: String): Either[LoadError, String] =
      Try(
        UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(copiedBytes(file)))
          .toString,
      ).toEither.left.map: error =>
        val detail = s"invalid UTF-8: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
        if file == ManifestFile then LoadError.InvalidManifest(path(file), detail)
        else LoadError.InvalidTsv(path(file), detail)

  private def readManifest(snapshot: ComponentSnapshot): Either[LoadError, Manifest] =
    val path = snapshot.path(ManifestFile)
    readExactlyOne(
      snapshot,
      ManifestFile,
      Vector(
        "schema_version",
        "baseline_id",
        "enterprise_controls_digest",
        "registered_seat_region_classification_id",
        "registered_seat_region_classification_version",
        "pkd2007_section_classification_id",
        "pkd2007_section_classification_version",
        "expected_workers_band_classification_id",
        "expected_workers_band_classification_version",
        "source_provider",
        "source_location",
        "source_sha256",
        "source_observation_period",
        "source_release",
        "source_accessed_at",
      ),
    ).flatMap: row =>
      for
        schemaVersion <- requiredInt(row, "schema_version").left.map(detail => LoadError.InvalidManifest(path, detail))
        baselineId    <- row.required("baseline_id").flatMap(BaselineId.from).left.map(detail => LoadError.InvalidManifest(path, detail))
        digest        <- row.required("enterprise_controls_digest").flatMap(parseDigest).left.map(detail => LoadError.InvalidManifest(path, detail))
        regionRef     <- classificationRef(row, "registered_seat_region").left.map(detail => LoadError.InvalidManifest(path, detail))
        pkdRef        <- classificationRef(row, "pkd2007_section").left.map(detail => LoadError.InvalidManifest(path, detail))
        bandRef       <- classificationRef(row, "expected_workers_band").left.map(detail => LoadError.InvalidManifest(path, detail))
        source        <- sourceArtifact(row).left.map(detail => LoadError.InvalidManifest(path, detail))
        classes       <- readClassifications(snapshot, regionRef, pkdRef, bandRef)
      yield Manifest(schemaVersion, BaselineRef(baselineId), digest, classes, source)

  private def readClassifications(
      snapshot: ComponentSnapshot,
      region: ClassificationRef,
      pkd: ClassificationRef,
      band: ClassificationRef,
  ): Either[LoadError, Classifications] =
    for
      regions  <- readTypedRows(snapshot, RegionsFile, Vector("code"))((row, _) =>
        row.required("code").flatMap(value => buildValue(RegisteredSeatRegionCode(value))),
      )
      sections <- readTypedRows(snapshot, Pkd2007SectionsFile, Vector("code"))((row, _) =>
        row.required("code").flatMap(value => buildValue(Pkd2007SectionCode(value))),
      )
      bands    <- readTypedRows(snapshot, ExpectedWorkersFile, Vector("code"))((row, _) =>
        row.required("code").flatMap(value => buildValue(ExpectedWorkersBandCode(value))),
      )
      classes  <- buildManifestValue(snapshot, "classifications")(Classifications(region, pkd, band, regions, sections, bands))
    yield classes

  private def readControls(snapshot: ComponentSnapshot, classifications: Classifications): Either[LoadError, Bundle] =
    val regions  = classifications.regions.map(value => value.value -> value).toMap
    val sections = classifications.sections.map(value => value.value -> value).toMap
    val bands    = classifications.workerBands.map(value => value.value -> value).toMap
    for
      strata    <- readTypedRows(snapshot, EnterpriseStrataFile, Vector("registered_seat_region", "pkd2007_section", "expected_workers_band", "count")):
        (row, _) =>
          for
            region  <- known(row, "registered_seat_region", regions)
            section <- known(row, "pkd2007_section", sections)
            band    <- known(row, "expected_workers_band", bands)
            count   <- enterpriseCount(row)
          yield EnterpriseStratumRow(region, section, band, count)
      residuals <- readTypedRows(snapshot, SourceResidualsFile, Vector("registered_seat_region", "pkd2007_section", "expected_workers_band", "count")):
        (row, _) =>
          for
            region   <- optionalKnown(row, "registered_seat_region", regions)
            section  <- optionalKnown(row, "pkd2007_section", sections)
            band     <- known(row, "expected_workers_band", bands)
            count    <- enterpriseCount(row)
            residual <- buildValue(SourceResidualRow(region, section, band, count))
          yield residual
      totals    <- readTypedRows(snapshot, SourceTotalsFile, Vector("expected_workers_band", "count")): (row, _) =>
        for
          band  <- known(row, "expected_workers_band", bands)
          count <- enterpriseCount(row)
        yield SourceTotalRow(band, count)
      bundle    <- buildManifestValue(snapshot, "enterprise controls")(Bundle(classifications, strata, residuals, totals))
    yield bundle

  private def ensureSupportedSchema(manifest: Manifest): Either[LoadError, Unit] =
    Either.cond(
      manifest.schemaVersion == EnterpriseControlSchema.SchemaVersion,
      (),
      LoadError.UnsupportedSchemaVersion(EnterpriseControlSchema.SchemaVersion, manifest.schemaVersion),
    )

  private def computeDigest(snapshot: ComponentSnapshot, manifest: Manifest): EnterpriseControlsDigest =
    val manifestFields = Vector(
      "enterprise-control-bundle-v1",
      s"schema_version=${manifest.schemaVersion}",
      s"baseline_id=${manifest.baseline.id}",
      s"registered_seat_region_classification=${manifest.classifications.registeredSeatRegion.id}@${manifest.classifications.registeredSeatRegion.version}",
      s"pkd2007_section_classification=${manifest.classifications.pkd2007Section.id}@${manifest.classifications.pkd2007Section.version}",
      s"expected_workers_band_classification=${manifest.classifications.expectedWorkersBand.id}@${manifest.classifications.expectedWorkersBand.version}",
      s"source_provider=${manifest.source.provider}",
      s"source_location=${manifest.source.location}",
      s"source_sha256=${manifest.source.sha256}",
      s"source_observation_period=${manifest.source.observationPeriod}",
      s"source_release=${manifest.source.release}",
      s"source_accessed_at=${manifest.source.accessedAt}",
    )
    val fileFields     = ComponentFiles.sorted.map(file => s"$file=${sha256Hex(snapshot.copiedBytes(file))}")
    EnterpriseControlsDigest.sha256Utf8((manifestFields ++ fileFields).mkString("\n") + "\n")

  private def readSnapshot(root: Path): Either[LoadError, ComponentSnapshot] =
    sequenceLoad(
      SnapshotFiles.map: file =>
        val path = root.resolve(file)
        Try(Files.readAllBytes(path)).toEither.left
          .map: error =>
            val detail = s"failed to read component bytes: ${error.getMessage}"
            if file == ManifestFile then LoadError.InvalidManifest(path, detail)
            else LoadError.InvalidTsv(path, detail)
          .map(bytes => file -> bytes),
    ).map(files => new ComponentSnapshot(root, files.toMap))

  private def readExactlyOne(snapshot: ComponentSnapshot, file: String, requiredColumns: Vector[String]): Either[LoadError, TsvRow] =
    val path = snapshot.path(file)
    snapshot
      .utf8(file)
      .flatMap(content => TsvRows.readRows(path, content, requiredColumns).left.map(detail => LoadError.InvalidManifest(path, detail)))
      .flatMap:
        case Vector(row) => Right(row)
        case rows        => Left(LoadError.InvalidManifest(path, s"must contain exactly one data row, got ${rows.size}"))

  private def readTypedRows[A](snapshot: ComponentSnapshot, file: String, requiredColumns: Vector[String])(
      parse: (TsvRow, Int) => Either[String, A],
  ): Either[LoadError, Vector[A]] =
    val path = snapshot.path(file)
    snapshot
      .utf8(file)
      .flatMap(content => TsvRows.readRows(path, content, requiredColumns).left.map(detail => LoadError.InvalidTsv(path, detail)))
      .flatMap: rows =>
        sequenceLoad(rows.map(row => parse(row, row.lineNumber).left.map(detail => LoadError.InvalidControlRow(path, row.lineNumber, detail))))

  private def classificationRef(row: TsvRow, prefix: String): Either[String, ClassificationRef] =
    for
      id      <- row.required(s"${prefix}_classification_id")
      version <- row.required(s"${prefix}_classification_version")
      ref     <- buildValue(ClassificationRef(id, version))
    yield ref

  private def sourceArtifact(row: TsvRow): Either[String, SourceArtifact] =
    for
      provider   <- row.required("source_provider")
      location   <- row.required("source_location")
      sha256     <- row.required("source_sha256").map(_.toLowerCase)
      period     <- row.required("source_observation_period")
      release    <- row.required("source_release")
      accessedAt <- row.required("source_accessed_at").flatMap(parseDate(_, "source_accessed_at"))
      source     <- buildValue(SourceArtifact(provider, location, sha256, period, release, accessedAt))
    yield source

  private def requiredInt(row: TsvRow, column: String): Either[String, Int] =
    row.required(column).flatMap(value => value.toIntOption.toRight(s"$column must be an integer, got: $value"))

  private def enterpriseCount(row: TsvRow): Either[String, EnterpriseCount] =
    row
      .required("count")
      .flatMap(value => value.toLongOption.toRight(s"count must be a Long, got: $value"))
      .flatMap(value => buildValue(EnterpriseCount(value)))

  private def known[A](row: TsvRow, column: String, values: Map[String, A]): Either[String, A] =
    row.required(column).flatMap(value => values.get(value).toRight(s"$column references undeclared value: $value"))

  private def optionalKnown[A](row: TsvRow, column: String, values: Map[String, A]): Either[String, Option[A]] =
    row
      .optional(column)
      .fold[Either[String, Option[A]]](Right(None)): value =>
        values.get(value).map(Some(_)).toRight(s"$column references undeclared value: $value")

  private def parseDigest(value: String): Either[String, EnterpriseControlsDigest] =
    buildValue(EnterpriseControlsDigest.fromSha256Hex(value))

  private def parseDate(value: String, column: String): Either[String, LocalDate] =
    Try(LocalDate.parse(value)).toEither.left.map(_ => s"$column must be an ISO-8601 date, got: $value")

  private def buildManifestValue[A](snapshot: ComponentSnapshot, label: String)(value: => A): Either[LoadError, A] =
    buildValue(value).left.map(detail => LoadError.InvalidManifest(snapshot.path(ManifestFile), s"invalid $label: $detail"))

  private def buildValue[A](value: => A): Either[String, A] =
    Try(value).toEither.left.map(error => Option(error.getMessage).getOrElse(error.getClass.getSimpleName))

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).iterator.map(byte => f"${byte & 0xff}%02x").mkString

  private def sequenceLoad[A](values: Iterable[Either[LoadError, A]]): Either[LoadError, Vector[A]] =
    values.foldLeft[Either[LoadError, Vector[A]]](Right(Vector.empty)): (accumulator, next) =>
      for
        accumulated <- accumulator
        value       <- next
      yield accumulated :+ value

end EnterpriseControlBundleLoader
