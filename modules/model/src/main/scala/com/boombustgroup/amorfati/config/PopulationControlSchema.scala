package com.boombustgroup.amorfati.config

import java.time.LocalDate

/** Input contract for the person, household, labour, and employment controls
  * used by the future population compiler.
  *
  * This is deliberately independent of the current agent population and
  * SimParams. A real reference-economy bundle supplies these controls; this
  * schema does not itself claim that any particular country or period has been
  * compiled.
  */
object PopulationControlSchema:

  /** A non-negative quantity in the reference population, before sampling or
    * representation weighting.
    */
  final case class RepresentedCount(value: Long):
    require(value >= 0L, s"represented count must be non-negative: $value")

  enum ControlStrength:
    /** The compiler must meet the row total within its declared tolerance. */
    case Hard

    /** The row informs synthesis or calibration, but is not a binding total. */
    case CalibratedMargin

  enum ControlFamily:
    case Persons
    case Households
    case HouseholdMembership
    case Labour
    case Employment

  final case class ClassificationRef(id: String, version: String):
    require(id.trim.nonEmpty, "classification ID must be non-empty")
    require(version.trim.nonEmpty, s"classification $id must have a version")

  final case class SourceProvenance(
      provider: String,
      sourceLocation: String,
      observationPeriod: String,
      release: String,
      accessedAt: LocalDate,
      transformation: String,
  ):
    require(provider.trim.nonEmpty, "source provider must be non-empty")
    require(sourceLocation.trim.nonEmpty, s"source location for $provider must be non-empty")
    require(observationPeriod.trim.nonEmpty, s"observation period for $provider must be non-empty")
    require(release.trim.nonEmpty, s"release for $provider must be non-empty")
    require(transformation.trim.nonEmpty, s"transformation for $provider must be non-empty")

  /** Metadata that makes a control table reproducible and its reconciliation
    * policy explicit.
    */
  final case class TableMetadata(
      family: ControlFamily,
      statisticalUniverse: String,
      strength: ControlStrength,
      absoluteTolerance: Long,
      classifications: Vector[ClassificationRef],
      source: SourceProvenance,
  ):
    require(statisticalUniverse.trim.nonEmpty, s"${family.toString} statistical universe must be non-empty")
    require(absoluteTolerance >= 0L, s"${family.toString} absolute tolerance must be non-negative")
    require(classifications.nonEmpty, s"${family.toString} must declare at least one classification")
    require(
      classifications.distinct.size == classifications.size,
      s"${family.toString} must not repeat a classification reference",
    )

  final case class ControlTable[A](metadata: TableMetadata, rows: Vector[A]):
    require(rows.nonEmpty, s"${metadata.family.toString} controls must not be empty")

  final case class RegionCode(value: String):
    require(value.trim.nonEmpty, "region code must be non-empty")

  final case class ProductionSectorCode(value: String):
    require(value.trim.nonEmpty, "production sector code must be non-empty")

  /** Closed age interval, or an upper open interval such as 90+. */
  final case class AgeBand(code: String, minInclusive: Int, maxInclusive: Option[Int]):
    require(code.trim.nonEmpty, "age-band code must be non-empty")
    require(minInclusive >= 0, s"age-band minimum must be non-negative: $minInclusive")
    require(maxInclusive.forall(_ >= minInclusive), s"age-band maximum must be at least $minInclusive")

  enum DemographicSex:
    case Female
    case Male

  enum ResidenceType:
    case PrivateHousehold
    case CollectiveResidence

  enum HouseholdComposition:
    case OnePerson
    case CoupleWithoutDependentChildren
    case CoupleWithDependentChildren
    case LoneParent
    case OtherMultiPerson

  enum HouseholdMemberRole:
    case Adult
    case DependentChild
    case OtherMember

  enum LabourStatus:
    case Employed
    case Unemployed
    case Inactive

    /** Persons younger than the labour-force universe. */
    case NotApplicable

    /** Declared 90+ residual outside the BAEL labour-status universe. */
    case NonBaelResidual

  /** Classifications accepted by the controls. The target compiler receives
    * these axes from a baseline rather than from current runtime enums.
    */
  final case class Classifications(
      region: ClassificationRef,
      age: ClassificationRef,
      productionSector: ClassificationRef,
      regions: Vector[RegionCode],
      ageBands: Vector[AgeBand],
      productionSectors: Vector[ProductionSectorCode],
  ):
    require(regions.nonEmpty, "population controls require at least one region")
    require(ageBands.nonEmpty, "population controls require at least one age band")
    require(productionSectors.nonEmpty, "population controls require at least one production sector")
    require(regions.distinct.size == regions.size, "population-control regions must be unique")
    require(ageBands.map(_.code).distinct.size == ageBands.size, "population-control age-band codes must be unique")
    require(productionSectors.distinct.size == productionSectors.size, "population-control production sectors must be unique")
    require(nonOverlapping(ageBands), "population-control age bands must not overlap")

  final case class PersonRow(
      region: RegionCode,
      sex: DemographicSex,
      ageBand: AgeBand,
      residence: ResidenceType,
      count: RepresentedCount,
  )

  final case class HouseholdRow(
      region: RegionCode,
      size: Int,
      composition: HouseholdComposition,
      count: RepresentedCount,
  ):
    require(size > 0, s"household size must be positive: $size")

  /** Counts represented member positions, not a count of households. */
  final case class HouseholdMembershipRow(
      composition: HouseholdComposition,
      memberRole: HouseholdMemberRole,
      ageBand: AgeBand,
      count: RepresentedCount,
  )

  final case class DemographicLabourRow(
      sex: DemographicSex,
      ageBand: AgeBand,
      status: LabourStatus,
      count: RepresentedCount,
  )

  final case class RegionalLabourRow(
      region: RegionCode,
      status: LabourStatus,
      count: RepresentedCount,
  )

  /** Filled jobs classified by resident origin, workplace, and production
    * sector. The residence axis reconciles this table to employed residents.
    */
  final case class EmploymentRow(
      residenceRegion: RegionCode,
      workplaceRegion: RegionCode,
      productionSector: ProductionSectorCode,
      count: RepresentedCount,
  )

  /** Five logical control families. Labour contains both demographic and
    * regional margins because both are required to constrain the same status
    * population.
    */
  final case class Bundle(
      classifications: Classifications,
      persons: ControlTable[PersonRow],
      households: ControlTable[HouseholdRow],
      householdMembership: ControlTable[HouseholdMembershipRow],
      demographicLabour: ControlTable[DemographicLabourRow],
      regionalLabour: ControlTable[RegionalLabourRow],
      employment: ControlTable[EmploymentRow],
  )

  final case class Reconciliation(
      id: String,
      expected: BigInt,
      actual: BigInt,
      tolerance: Long,
  ):
    def residual: BigInt = actual - expected
    def passes: Boolean  = residual.abs <= BigInt(tolerance)

  enum ValidationError:
    case WrongControlFamily(expected: ControlFamily, actual: ControlFamily)
    case MissingClassification(table: ControlFamily, required: ClassificationRef)
    case DuplicateRow(table: String, key: String)
    case UnknownRegion(table: String, region: RegionCode)
    case UnknownAgeBand(table: String, ageBand: AgeBand)
    case UnknownProductionSector(table: String, sector: ProductionSectorCode)
    case InvalidLabourStatusAgeBand(status: LabourStatus, ageBand: AgeBand)
    case FailedReconciliation(reconciliation: Reconciliation)

  final case class ValidationReport(
      reconciliations: Vector[Reconciliation],
      errors: Vector[ValidationError],
  ):
    def isValid: Boolean = errors.isEmpty

  object Validator:

    def validate(bundle: Bundle): ValidationReport =
      val classifications = bundle.classifications
      val errors          =
        metadataErrors(bundle, classifications) ++
          duplicateErrors(bundle) ++
          axisErrors(bundle, classifications) ++
          labourAgeErrors(bundle)
      val reconciliations = reconciliationChecks(bundle)
      val allErrors       = errors ++ reconciliations.filterNot(_.passes).map(ValidationError.FailedReconciliation.apply)
      ValidationReport(reconciliations, allErrors)

    private def metadataErrors(bundle: Bundle, classifications: Classifications): Vector[ValidationError] =
      val expected = Vector(
        (bundle.persons.metadata, ControlFamily.Persons, Vector(classifications.region, classifications.age)),
        (bundle.households.metadata, ControlFamily.Households, Vector(classifications.region)),
        (bundle.householdMembership.metadata, ControlFamily.HouseholdMembership, Vector(classifications.age)),
        (bundle.demographicLabour.metadata, ControlFamily.Labour, Vector(classifications.age)),
        (bundle.regionalLabour.metadata, ControlFamily.Labour, Vector(classifications.region)),
        (bundle.employment.metadata, ControlFamily.Employment, Vector(classifications.region, classifications.productionSector)),
      )
      expected.flatMap: (metadata, family, requiredClassifications) =>
        val familyErrors         = Option.when(metadata.family != family)(ValidationError.WrongControlFamily(family, metadata.family))
        val classificationErrors = requiredClassifications
          .filterNot(metadata.classifications.contains)
          .map(required => ValidationError.MissingClassification(family, required))
        familyErrors.toVector ++ classificationErrors

    private def duplicateErrors(bundle: Bundle): Vector[ValidationError] =
      duplicateRows("persons", bundle.persons.rows)(row => s"${row.region.value}|${row.sex}|${row.ageBand.code}|${row.residence}") ++
        duplicateRows("households", bundle.households.rows)(row => s"${row.region.value}|${row.size}|${row.composition}") ++
        duplicateRows("household-membership", bundle.householdMembership.rows)(row => s"${row.composition}|${row.memberRole}|${row.ageBand.code}") ++
        duplicateRows("demographic-labour", bundle.demographicLabour.rows)(row => s"${row.sex}|${row.ageBand.code}|${row.status}") ++
        duplicateRows("regional-labour", bundle.regionalLabour.rows)(row => s"${row.region.value}|${row.status}") ++
        duplicateRows("employment", bundle.employment.rows)(row => s"${row.residenceRegion.value}|${row.workplaceRegion.value}|${row.productionSector.value}")

    private def axisErrors(bundle: Bundle, classifications: Classifications): Vector[ValidationError] =
      val knownRegions = classifications.regions.toSet
      val knownAges    = classifications.ageBands.toSet
      val knownSectors = classifications.productionSectors.toSet
      bundle.persons.rows.flatMap: row =>
        Vector(
          Option.when(!knownRegions(row.region))(ValidationError.UnknownRegion("persons", row.region)),
          Option.when(!knownAges(row.ageBand))(ValidationError.UnknownAgeBand("persons", row.ageBand)),
        ).flatten
      ++ bundle.households.rows.flatMap: row =>
        Vector(Option.when(!knownRegions(row.region))(ValidationError.UnknownRegion("households", row.region))).flatten
      ++ bundle.householdMembership.rows.flatMap: row =>
        Vector(Option.when(!knownAges(row.ageBand))(ValidationError.UnknownAgeBand("household-membership", row.ageBand))).flatten
      ++ bundle.demographicLabour.rows.flatMap: row =>
        Vector(Option.when(!knownAges(row.ageBand))(ValidationError.UnknownAgeBand("demographic-labour", row.ageBand))).flatten
      ++ bundle.regionalLabour.rows.flatMap: row =>
        Vector(Option.when(!knownRegions(row.region))(ValidationError.UnknownRegion("regional-labour", row.region))).flatten
      ++ bundle.employment.rows.flatMap: row =>
        Vector(
          Option.when(!knownRegions(row.residenceRegion))(ValidationError.UnknownRegion("employment residence", row.residenceRegion)),
          Option.when(!knownRegions(row.workplaceRegion))(ValidationError.UnknownRegion("employment workplace", row.workplaceRegion)),
          Option.when(!knownSectors(row.productionSector))(ValidationError.UnknownProductionSector("employment", row.productionSector)),
        ).flatten

    private def labourAgeErrors(bundle: Bundle): Vector[ValidationError] =
      bundle.demographicLabour.rows.collect:
        case row if !labourStatusPermitted(row.status, row.ageBand) =>
          ValidationError.InvalidLabourStatusAgeBand(row.status, row.ageBand)

    private def reconciliationChecks(bundle: Bundle): Vector[Reconciliation] =
      val membershipByComposition        = totalBy(bundle.householdMembership.rows)(_.composition)(row => BigInt(row.count.value))
      val householdCapacityByComposition = totalBy(bundle.households.rows)(_.composition)(row => BigInt(row.count.value) * row.size)
      val personBySexAge                 = totalBy(bundle.persons.rows)(row => row.sex -> row.ageBand)(row => BigInt(row.count.value))
      val demographicLabourBySexAge      = totalBy(bundle.demographicLabour.rows)(row => row.sex -> row.ageBand)(row => BigInt(row.count.value))
      val personByRegion                 = totalBy(bundle.persons.rows)(_.region)(row => BigInt(row.count.value))
      val regionalLabourByRegion         = totalBy(bundle.regionalLabour.rows)(_.region)(row => BigInt(row.count.value))
      val demographicLabourByStatus      = totalBy(bundle.demographicLabour.rows)(_.status)(row => BigInt(row.count.value))
      val regionalLabourByStatus         = totalBy(bundle.regionalLabour.rows)(_.status)(row => BigInt(row.count.value))
      val employedByRegion               = totalBy(bundle.regionalLabour.rows.filter(_.status == LabourStatus.Employed))(_.region)(row => BigInt(row.count.value))
      val jobsByResidenceRegion          = totalBy(bundle.employment.rows)(_.residenceRegion)(row => BigInt(row.count.value))
      val personMembershipTolerance      = combinedTolerance(bundle.persons, bundle.householdMembership)
      val householdMembershipTolerance   = combinedTolerance(bundle.households, bundle.householdMembership)
      val personLabourTolerance          = combinedTolerance(bundle.persons, bundle.demographicLabour)
      val regionalLabourTolerance        = combinedTolerance(bundle.persons, bundle.regionalLabour)
      val labourMarginTolerance          = combinedTolerance(bundle.demographicLabour, bundle.regionalLabour)
      val employmentTolerance            = combinedTolerance(bundle.regionalLabour, bundle.employment)

      Vector(
        Reconciliation(
          "private-person-membership",
          total(bundle.persons.rows.filter(_.residence == ResidenceType.PrivateHousehold).map(row => BigInt(row.count.value))),
          total(bundle.householdMembership.rows.map(row => BigInt(row.count.value))),
          personMembershipTolerance,
        ),
      ) ++
        reconcileMaps("household-member-capacity", householdCapacityByComposition, membershipByComposition, householdMembershipTolerance) ++
        reconcileMaps("person-to-demographic-labour", personBySexAge, demographicLabourBySexAge, personLabourTolerance) ++
        reconcileMaps("person-to-regional-labour", personByRegion, regionalLabourByRegion, regionalLabourTolerance) ++
        reconcileMaps("demographic-to-regional-labour", demographicLabourByStatus, regionalLabourByStatus, labourMarginTolerance) ++
        reconcileMaps("employed-residents-to-filled-jobs", employedByRegion, jobsByResidenceRegion, employmentTolerance)

    private def duplicateRows[A](table: String, rows: Vector[A])(key: A => String): Vector[ValidationError] =
      rows
        .groupBy(key)
        .collect { case (duplicate, values) if values.size > 1 => ValidationError.DuplicateRow(table, duplicate) }
        .toVector
        .sortBy(_.toString)

    private def totalBy[A, K](rows: Vector[A])(key: A => K)(count: A => BigInt): Map[K, BigInt] =
      rows.groupMapReduce(key)(count)(_ + _)

    private def total(counts: Iterable[BigInt]): BigInt =
      counts.iterator.foldLeft(BigInt(0))(_ + _)

    private def reconcileMaps[K](id: String, expected: Map[K, BigInt], actual: Map[K, BigInt], tolerance: Long): Vector[Reconciliation] =
      (expected.keySet ++ actual.keySet).toVector
        .sortBy(_.toString)
        .map: key =>
          Reconciliation(s"$id:$key", expected.getOrElse(key, BigInt(0)), actual.getOrElse(key, BigInt(0)), tolerance)

    private def combinedTolerance[A, B](left: ControlTable[A], right: ControlTable[B]): Long =
      math.max(left.metadata.absoluteTolerance, right.metadata.absoluteTolerance)

    private def labourStatusPermitted(status: LabourStatus, ageBand: AgeBand): Boolean =
      status match
        case LabourStatus.Employed        => containedIn(ageBand, minimum = 15, maximum = 89)
        case LabourStatus.Unemployed      => containedIn(ageBand, minimum = 15, maximum = 74)
        case LabourStatus.Inactive        => containedIn(ageBand, minimum = 15, maximum = 89)
        case LabourStatus.NotApplicable   => ageBand.maxInclusive.exists(_ < 15)
        case LabourStatus.NonBaelResidual => ageBand.minInclusive >= 90

    private def containedIn(ageBand: AgeBand, minimum: Int, maximum: Int): Boolean =
      ageBand.minInclusive >= minimum && ageBand.maxInclusive.exists(_ <= maximum)

  private def nonOverlapping(ageBands: Vector[AgeBand]): Boolean =
    val sorted = ageBands.sortBy(_.minInclusive)
    sorted
      .zip(sorted.drop(1))
      .forall: (left, right) =>
        left.maxInclusive.exists(_ < right.minInclusive)

end PopulationControlSchema
