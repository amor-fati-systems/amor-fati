package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.config.PopulationControlSchema.*
import com.boombustgroup.amorfati.config.PopulationRepresentation.PopulationControlsDigest
import com.boombustgroup.amorfati.tsv.TsvRows

import java.nio.file.{Files, Path}
import java.security.MessageDigest
import java.time.LocalDate
import scala.util.Try

/** Data-only loader for the population-control component of a reference economy
  * bundle.
  *
  * A bundle root contains a one-row `manifest.tsv`, a `tables.tsv` source and
  * metadata registry, classification TSVs, and six control-table TSVs. The
  * loader never evaluates Scala configuration or deserializes implementation
  * objects. It verifies a canonical component digest before constructing a
  * [[PopulationControlSchema.Bundle]] and applying its structural
  * reconciliation validator.
  *
  * This is deliberately only the population-control component. The eventual
  * full baseline bundle will combine its digest with parameters, institutional
  * opening state, provenance, and validation evidence before it produces the
  * full [[BaselineDigest]] recorded by a run manifest.
  */
object PopulationControlBundleLoader:

  private type TsvRow = TsvRows.Row

  private val ManifestFile            = "manifest.tsv"
  private val TableMetadataFile       = "tables.tsv"
  private val RegionsFile             = "regions.tsv"
  private val AgeBandsFile            = "age-bands.tsv"
  private val ProductionSectorsFile   = "production-sectors.tsv"
  private val PersonsFile             = "persons.tsv"
  private val HouseholdsFile          = "households.tsv"
  private val HouseholdMembershipFile = "household-membership.tsv"
  private val DemographicLabourFile   = "demographic-labour.tsv"
  private val RegionalLabourFile      = "regional-labour.tsv"
  private val EmploymentFile          = "employment.tsv"

  private val ComponentFiles = Vector(
    TableMetadataFile,
    RegionsFile,
    AgeBandsFile,
    ProductionSectorsFile,
    PersonsFile,
    HouseholdsFile,
    HouseholdMembershipFile,
    DemographicLabourFile,
    RegionalLabourFile,
    EmploymentFile,
  )

  /** Parsed manifest metadata for one versioned population-control component.
    * Its digest covers these semantic fields and every component TSV; the
    * digest field itself is excluded to avoid self-reference.
    */
  final case class Manifest(
      schemaVersion: Int,
      baseline: BaselineRef,
      populationControlsDigest: PopulationControlsDigest,
      classifications: Classifications,
  )

  /** Successfully loaded, integrity-checked population controls. The retained
    * report is evidence that all cross-table control identities passed before
    * any future population compiler can consume the bundle.
    */
  final case class Loaded(
      manifest: Manifest,
      controls: Bundle,
      validation: ValidationReport,
  )

  /** Structured failure preventing a bundle from entering a population
    * compiler. Source TSV errors preserve a file path and, where available, a
    * row number supplied by the shared TSV parser.
    */
  enum LoadError:
    case InvalidTsv(path: Path, detail: String)
    case InvalidManifest(path: Path, detail: String)
    case UnsupportedSchemaVersion(expected: Int, actual: Int)
    case InvalidTableMetadata(path: Path, detail: String)
    case InvalidControlRow(path: Path, rowNumber: Int, detail: String)
    case PopulationControlsDigestMismatch(expected: PopulationControlsDigest, actual: PopulationControlsDigest)
    case ControlValidationFailed(errors: Vector[ValidationError])

  /** Read, integrity-check, parse, and reconcile a population-control bundle
    * stored below `root`.
    */
  def load(root: Path): Either[LoadError, Loaded] =
    for
      manifest      <- readManifest(root)
      _             <- ensureSupportedSchema(manifest)
      actualDigest  <- computeDigest(root, manifest)
      _             <- Either.cond(
        actualDigest == manifest.populationControlsDigest,
        (),
        LoadError.PopulationControlsDigestMismatch(manifest.populationControlsDigest, actualDigest),
      )
      tableMetadata <- readTableMetadata(root, manifest.classifications)
      controls      <- readControls(root, manifest.classifications, tableMetadata)
      validation     = Validator.validate(controls)
      _             <- Either.cond(validation.isValid, (), LoadError.ControlValidationFailed(validation.errors))
    yield Loaded(manifest, controls, validation)

  private def readManifest(root: Path): Either[LoadError, Manifest] =
    val path = root.resolve(ManifestFile)
    readExactlyOne(
      path,
      Vector(
        "schema_version",
        "baseline_id",
        "population_controls_digest",
        "region_classification_id",
        "region_classification_version",
        "age_classification_id",
        "age_classification_version",
        "production_sector_classification_id",
        "production_sector_classification_version",
      ),
    ).left
      .map(detail => LoadError.InvalidManifest(path, detail))
      .flatMap: row =>
        for
          schemaVersion <- requiredInt(row, "schema_version").left.map(detail => LoadError.InvalidManifest(path, detail))
          baselineId    <- row.required("baseline_id").flatMap(BaselineId.from).left.map(detail => LoadError.InvalidManifest(path, detail))
          digest        <- row.required("population_controls_digest").flatMap(parseControlsDigest).left.map(detail => LoadError.InvalidManifest(path, detail))
          regionRef     <- classificationRef(row, "region_classification").left.map(detail => LoadError.InvalidManifest(path, detail))
          ageRef        <- classificationRef(row, "age_classification").left.map(detail => LoadError.InvalidManifest(path, detail))
          sectorRef     <- classificationRef(row, "production_sector_classification").left.map(detail => LoadError.InvalidManifest(path, detail))
          classes       <- readClassifications(root, regionRef, ageRef, sectorRef)
        yield Manifest(schemaVersion, BaselineRef(baselineId), digest, classes)

  private def readClassifications(
      root: Path,
      region: ClassificationRef,
      age: ClassificationRef,
      productionSector: ClassificationRef,
  ): Either[LoadError, Classifications] =
    for
      regions           <- readRegions(root.resolve(RegionsFile))
      ageBands          <- readAgeBands(root.resolve(AgeBandsFile))
      productionSectors <- readProductionSectors(root.resolve(ProductionSectorsFile))
      classifications   <- build("classifications", root)(Classifications(region, age, productionSector, regions, ageBands, productionSectors))
    yield classifications

  private def readRegions(path: Path): Either[LoadError, Vector[RegionCode]] =
    readTypedRows(path, Vector("code"))((row, _) => row.required("code").flatMap(value => buildValue(RegionCode(value)))).map(_.toVector)

  private def readAgeBands(path: Path): Either[LoadError, Vector[AgeBand]] =
    readTypedRows(path, Vector("code", "min_inclusive", "max_inclusive")): (row, _) =>
      for
        code <- row.required("code")
        min  <- requiredInt(row, "min_inclusive")
        max  <- optionalInt(row, "max_inclusive")
        band <- buildValue(AgeBand(code, min, max))
      yield band

  private def readProductionSectors(path: Path): Either[LoadError, Vector[ProductionSectorCode]] =
    readTypedRows(path, Vector("code"))((row, _) => row.required("code").flatMap(value => buildValue(ProductionSectorCode(value)))).map(_.toVector)

  private def readTableMetadata(root: Path, classifications: Classifications): Either[LoadError, Map[Table, TableMetadata]] =
    val path = root.resolve(TableMetadataFile)
    readTypedRows(
      path,
      Vector(
        "table",
        "control_family",
        "statistical_universe",
        "strength",
        "absolute_tolerance",
        "source_provider",
        "source_location",
        "observation_period",
        "release",
        "accessed_at",
        "transformation",
      ),
    ): (row, _) =>
      for
        table      <- row.required("table").flatMap(Table.parse)
        family     <- row.required("control_family").flatMap(parseControlFamily)
        _          <- Either.cond(family == table.family, (), s"table ${table.id} must declare control_family ${controlFamilyCode(table.family)}")
        universe   <- row.required("statistical_universe")
        strength   <- row.required("strength").flatMap(parseControlStrength)
        tolerance  <- requiredLong(row, "absolute_tolerance")
        provider   <- row.required("source_provider")
        location   <- row.required("source_location")
        period     <- row.required("observation_period")
        release    <- row.required("release")
        accessedAt <- row.required("accessed_at").flatMap(parseDate(_, "accessed_at"))
        transform  <- row.required("transformation")
        source     <- buildValue(SourceProvenance(provider, location, period, release, accessedAt, transform))
        metadata   <- buildValue(TableMetadata(family, universe, strength, tolerance, table.classifications(classifications), source))
      yield table -> metadata
    .flatMap: rows =>
      val duplicate = rows.groupBy(_._1).collectFirst { case (table, entries) if entries.size > 1 => table }
      val metadata  = rows.toMap
      val missing   = Table.all.filterNot(metadata.contains)
      duplicate match
        case Some(table)              => Left(LoadError.InvalidTableMetadata(path, s"duplicate metadata row for table ${table.id}"))
        case None if missing.nonEmpty => Left(LoadError.InvalidTableMetadata(path, s"missing metadata rows for: ${missing.map(_.id).mkString(", ")}"))
        case None                     => Right(metadata)

  private def readControls(
      root: Path,
      classifications: Classifications,
      metadata: Map[Table, TableMetadata],
  ): Either[LoadError, Bundle] =
    val regions = classifications.regions.map(region => region.value -> region).toMap
    val ages    = classifications.ageBands.map(band => band.code -> band).toMap
    val sectors = classifications.productionSectors.map(sector => sector.value -> sector).toMap
    for
      persons             <- readPersons(root.resolve(PersonsFile), regions, ages)
      households          <- readHouseholds(root.resolve(HouseholdsFile), regions)
      householdMembership <- readHouseholdMembership(root.resolve(HouseholdMembershipFile), ages)
      demographicLabour   <- readDemographicLabour(root.resolve(DemographicLabourFile), ages)
      regionalLabour      <- readRegionalLabour(root.resolve(RegionalLabourFile), regions)
      employment          <- readEmployment(root.resolve(EmploymentFile), regions, sectors)
      personTable         <- controlTable(root.resolve(PersonsFile), metadata(Table.Persons), persons)
      householdTable      <- controlTable(root.resolve(HouseholdsFile), metadata(Table.Households), households)
      membershipTable     <- controlTable(root.resolve(HouseholdMembershipFile), metadata(Table.HouseholdMembership), householdMembership)
      demographicTable    <- controlTable(root.resolve(DemographicLabourFile), metadata(Table.DemographicLabour), demographicLabour)
      regionalTable       <- controlTable(root.resolve(RegionalLabourFile), metadata(Table.RegionalLabour), regionalLabour)
      employmentTable     <- controlTable(root.resolve(EmploymentFile), metadata(Table.Employment), employment)
    yield Bundle(classifications, personTable, householdTable, membershipTable, demographicTable, regionalTable, employmentTable)

  private def readPersons(path: Path, regions: Map[String, RegionCode], ages: Map[String, AgeBand]): Either[LoadError, Vector[PersonRow]] =
    readTypedRows(path, Vector("region", "sex", "age_band", "residence", "count")): (row, _) =>
      for
        region    <- known(row, "region", regions)
        sex       <- row.required("sex").flatMap(parseDemographicSex)
        age       <- known(row, "age_band", ages)
        residence <- row.required("residence").flatMap(parseResidenceType)
        count     <- representedCount(row)
      yield PersonRow(region, sex, age, residence, count)

  private def readHouseholds(path: Path, regions: Map[String, RegionCode]): Either[LoadError, Vector[HouseholdRow]] =
    readTypedRows(path, Vector("region", "size", "composition", "count")): (row, _) =>
      for
        region      <- known(row, "region", regions)
        size        <- requiredInt(row, "size")
        composition <- row.required("composition").flatMap(parseHouseholdComposition)
        count       <- representedCount(row)
        household   <- buildValue(HouseholdRow(region, size, composition, count))
      yield household

  private def readHouseholdMembership(path: Path, ages: Map[String, AgeBand]): Either[LoadError, Vector[HouseholdMembershipRow]] =
    readTypedRows(path, Vector("composition", "member_role", "age_band", "count")): (row, _) =>
      for
        composition <- row.required("composition").flatMap(parseHouseholdComposition)
        role        <- row.required("member_role").flatMap(parseHouseholdMemberRole)
        age         <- known(row, "age_band", ages)
        count       <- representedCount(row)
      yield HouseholdMembershipRow(composition, role, age, count)

  private def readDemographicLabour(path: Path, ages: Map[String, AgeBand]): Either[LoadError, Vector[DemographicLabourRow]] =
    readTypedRows(path, Vector("sex", "age_band", "status", "count")): (row, _) =>
      for
        sex    <- row.required("sex").flatMap(parseDemographicSex)
        age    <- known(row, "age_band", ages)
        status <- row.required("status").flatMap(parseLabourStatus)
        count  <- representedCount(row)
      yield DemographicLabourRow(sex, age, status, count)

  private def readRegionalLabour(path: Path, regions: Map[String, RegionCode]): Either[LoadError, Vector[RegionalLabourRow]] =
    readTypedRows(path, Vector("region", "status", "count")): (row, _) =>
      for
        region <- known(row, "region", regions)
        status <- row.required("status").flatMap(parseLabourStatus)
        count  <- representedCount(row)
      yield RegionalLabourRow(region, status, count)

  private def readEmployment(
      path: Path,
      regions: Map[String, RegionCode],
      sectors: Map[String, ProductionSectorCode],
  ): Either[LoadError, Vector[EmploymentRow]] =
    readTypedRows(path, Vector("residence_region", "workplace_region", "production_sector", "count")): (row, _) =>
      for
        residence <- known(row, "residence_region", regions)
        workplace <- known(row, "workplace_region", regions)
        sector    <- known(row, "production_sector", sectors)
        count     <- representedCount(row)
      yield EmploymentRow(residence, workplace, sector, count)

  private def controlTable[A](path: Path, metadata: TableMetadata, rows: Vector[A]): Either[LoadError, ControlTable[A]] =
    buildValue(ControlTable(metadata, rows)).left.map(detail => LoadError.InvalidTsv(path, detail))

  private def ensureSupportedSchema(manifest: Manifest): Either[LoadError, Unit] =
    Either.cond(
      manifest.schemaVersion == PopulationControlSchema.SchemaVersion,
      (),
      LoadError.UnsupportedSchemaVersion(PopulationControlSchema.SchemaVersion, manifest.schemaVersion),
    )

  /** Canonical population-control component digest. The manifest digest field
    * is intentionally excluded from the payload; all other manifest fields and
    * SHA-256 fingerprints of every required component TSV are covered.
    */
  private[config] def computeDigest(root: Path, manifest: Manifest): Either[LoadError, PopulationControlsDigest] =
    sequenceLoad(ComponentFiles.map(file => readBytes(root.resolve(file)).map(file -> _))).map: files =>
      val manifestFields = Vector(
        "population-control-bundle-v1",
        s"schema_version=${manifest.schemaVersion}",
        s"baseline_id=${manifest.baseline.id}",
        s"region_classification=${manifest.classifications.region.id}@${manifest.classifications.region.version}",
        s"age_classification=${manifest.classifications.age.id}@${manifest.classifications.age.version}",
        s"production_sector_classification=${manifest.classifications.productionSector.id}@${manifest.classifications.productionSector.version}",
      )
      val fileFields     = files
        .sortBy(_._1)
        .map: (file, bytes) =>
          s"$file=${sha256Hex(bytes)}"
      PopulationControlsDigest.sha256Utf8((manifestFields ++ fileFields).mkString("\n") + "\n")

  private def readBytes(path: Path): Either[LoadError, Array[Byte]] =
    Try(Files.readAllBytes(path)).toEither.left.map(error => LoadError.InvalidTsv(path, s"failed to read component bytes: ${error.getMessage}"))

  private def readExactlyOne(path: Path, requiredColumns: Vector[String]): Either[String, TsvRow] =
    TsvRows
      .readRows(path, requiredColumns)
      .flatMap:
        case Vector(row) => Right(row)
        case rows        => Left(s"must contain exactly one data row, got ${rows.size}")

  private def readTypedRows[A](path: Path, requiredColumns: Vector[String])(
      parse: (TsvRow, Int) => Either[String, A],
  ): Either[LoadError, Vector[A]] =
    TsvRows
      .readRows(path, requiredColumns)
      .left
      .map(detail => LoadError.InvalidTsv(path, detail))
      .flatMap: rows =>
        sequenceLoad(
          rows.zipWithIndex.map: (row, index) =>
            parse(row, index + 2).left.map(detail => LoadError.InvalidControlRow(path, index + 2, detail)),
        )

  private def classificationRef(row: TsvRow, prefix: String): Either[String, ClassificationRef] =
    for
      id      <- row.required(s"${prefix}_id")
      version <- row.required(s"${prefix}_version")
      ref     <- buildValue(ClassificationRef(id, version))
    yield ref

  private def requiredInt(row: TsvRow, column: String): Either[String, Int] =
    row.required(column).flatMap(value => value.toIntOption.toRight(s"$column must be an integer, got: $value"))

  private def optionalInt(row: TsvRow, column: String): Either[String, Option[Int]] =
    row
      .optional(column)
      .fold[Either[String, Option[Int]]](Right(None))(value => value.toIntOption.map(Some(_)).toRight(s"$column must be an integer, got: $value"))

  private def requiredLong(row: TsvRow, column: String): Either[String, Long] =
    row.required(column).flatMap(value => value.toLongOption.toRight(s"$column must be a Long, got: $value"))

  private def representedCount(row: TsvRow): Either[String, RepresentedCount] =
    requiredLong(row, "count").flatMap(value => buildValue(RepresentedCount(value)))

  private def known[A](row: TsvRow, column: String, values: Map[String, A]): Either[String, A] =
    row.required(column).flatMap(value => values.get(value).toRight(s"$column references undeclared value: $value"))

  private def parseControlsDigest(value: String): Either[String, PopulationControlsDigest] =
    buildValue(PopulationControlsDigest.fromSha256Hex(value))

  private def parseDate(value: String, column: String): Either[String, LocalDate] =
    Try(LocalDate.parse(value)).toEither.left.map(_ => s"$column must be an ISO-8601 date, got: $value")

  private def parseControlFamily(value: String): Either[String, ControlFamily] =
    value match
      case "persons"              => Right(ControlFamily.Persons)
      case "households"           => Right(ControlFamily.Households)
      case "household_membership" => Right(ControlFamily.HouseholdMembership)
      case "labour"               => Right(ControlFamily.Labour)
      case "employment"           => Right(ControlFamily.Employment)
      case _                      => Left(s"unknown control_family: $value")

  private def controlFamilyCode(value: ControlFamily): String =
    value match
      case ControlFamily.Persons             => "persons"
      case ControlFamily.Households          => "households"
      case ControlFamily.HouseholdMembership => "household_membership"
      case ControlFamily.Labour              => "labour"
      case ControlFamily.Employment          => "employment"

  private def parseControlStrength(value: String): Either[String, ControlStrength] =
    value match
      case "hard"              => Right(ControlStrength.Hard)
      case "calibrated_margin" => Right(ControlStrength.CalibratedMargin)
      case _                   => Left(s"unknown control strength: $value")

  private def parseDemographicSex(value: String): Either[String, DemographicSex] =
    value match
      case "female" => Right(DemographicSex.Female)
      case "male"   => Right(DemographicSex.Male)
      case _        => Left(s"unknown demographic sex: $value")

  private def parseResidenceType(value: String): Either[String, ResidenceType] =
    value match
      case "private_household"    => Right(ResidenceType.PrivateHousehold)
      case "collective_residence" => Right(ResidenceType.CollectiveResidence)
      case _                      => Left(s"unknown residence type: $value")

  private def parseHouseholdComposition(value: String): Either[String, HouseholdComposition] =
    value match
      case "one_person"                        => Right(HouseholdComposition.OnePerson)
      case "couple_without_dependent_children" => Right(HouseholdComposition.CoupleWithoutDependentChildren)
      case "couple_with_dependent_children"    => Right(HouseholdComposition.CoupleWithDependentChildren)
      case "lone_parent"                       => Right(HouseholdComposition.LoneParent)
      case "other_multi_person"                => Right(HouseholdComposition.OtherMultiPerson)
      case _                                   => Left(s"unknown household composition: $value")

  private def parseHouseholdMemberRole(value: String): Either[String, HouseholdMemberRole] =
    value match
      case "adult"           => Right(HouseholdMemberRole.Adult)
      case "dependent_child" => Right(HouseholdMemberRole.DependentChild)
      case "other_member"    => Right(HouseholdMemberRole.OtherMember)
      case _                 => Left(s"unknown household member role: $value")

  private def parseLabourStatus(value: String): Either[String, LabourStatus] =
    value match
      case "employed"          => Right(LabourStatus.Employed)
      case "unemployed"        => Right(LabourStatus.Unemployed)
      case "inactive"          => Right(LabourStatus.Inactive)
      case "not_applicable"    => Right(LabourStatus.NotApplicable)
      case "non_bael_residual" => Right(LabourStatus.NonBaelResidual)
      case _                   => Left(s"unknown labour status: $value")

  private def build[A](label: String, root: Path)(value: => A): Either[LoadError, A] =
    buildValue(value).left.map(detail => LoadError.InvalidManifest(root.resolve(ManifestFile), s"invalid $label: $detail"))

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

  private enum Table:
    case Persons
    case Households
    case HouseholdMembership
    case DemographicLabour
    case RegionalLabour
    case Employment

    def id: String =
      this match
        case Persons             => "persons"
        case Households          => "households"
        case HouseholdMembership => "household_membership"
        case DemographicLabour   => "demographic_labour"
        case RegionalLabour      => "regional_labour"
        case Employment          => "employment"

    def family: ControlFamily =
      this match
        case Persons             => ControlFamily.Persons
        case Households          => ControlFamily.Households
        case HouseholdMembership => ControlFamily.HouseholdMembership
        case DemographicLabour   => ControlFamily.Labour
        case RegionalLabour      => ControlFamily.Labour
        case Employment          => ControlFamily.Employment

    def classifications(values: Classifications): Vector[ClassificationRef] =
      this match
        case Persons             => Vector(values.region, values.age)
        case Households          => Vector(values.region)
        case HouseholdMembership => Vector(values.age)
        case DemographicLabour   => Vector(values.age)
        case RegionalLabour      => Vector(values.region)
        case Employment          => Vector(values.region, values.productionSector)

  private object Table:
    val all: Vector[Table] = Vector(Persons, Households, HouseholdMembership, DemographicLabour, RegionalLabour, Employment)

    def parse(value: String): Either[String, Table] =
      all.find(_.id == value).toRight(s"unknown population-control table: $value")

end PopulationControlBundleLoader
