package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.config.PopulationControlSchema.RepresentedCount
import com.boombustgroup.amorfati.config.PopulationRepresentation.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class PopulationRepresentationSpec extends AnyFlatSpec with Matchers:

  private val baseline       = BaselineRef(BaselineId.from("synthetic-population-controls-v1").fold(error => fail(error), identity))
  private val representation = RepresentationSpec(
    residentsPerAgent = ResidentsPerAgent(1000L),
    enterprisePolicy = EnterpriseRepresentationPolicy.Stratified,
    rareStrataPolicy = RareStrataPolicy.PreserveWithAdaptiveWeights,
    systemicInstitutionPolicy = SystemicInstitutionPolicy.CensusWeightOne,
  )

  private def manifest(
      cohorts: Vector[CohortAllocation],
      representationSpec: RepresentationSpec = representation,
      controlSchemaVersion: Int = PopulationControlSchema.SchemaVersion,
  ): PopulationCompilationManifest =
    PopulationCompilationManifest(
      schemaVersion = ManifestSchemaVersion,
      baseline = baseline,
      baselineDigest = BaselineDigest.sha256Utf8("synthetic-baseline-payload"),
      populationControlSchemaVersion = controlSchemaVersion,
      populationControlsDigest = PopulationControlsDigest.sha256Utf8("synthetic-control-payload"),
      representation = representationSpec,
      seed = PopulationCompilationSeed(42L),
      cohorts = cohorts.toVector,
    )

  "PopulationRepresentation.Planner" should "allocate residents exactly at the requested scale without an economy-wide multiplier" in {
    val allocation = Planner.residentCohort(CohortId("persons:north"), RepresentedCount(2500L), representation)
    val report     = Planner.validate(manifest(Vector(allocation)))

    allocation.buckets shouldBe Vector(
      WeightBucket(RepresentationWeight(1000L), SimulatedUnitCount(2L)),
      WeightBucket(RepresentationWeight(500L), SimulatedUnitCount(1L)),
    )
    allocation.simulatedUnitCount shouldBe BigInt(3)
    allocation.representedByAllocation shouldBe BigInt(2500)
    report.isValid shouldBe true
    report.reconciliations should contain(CohortReconciliation(CohortId("persons:north"), BigInt(2500), BigInt(2500)))
  }

  it should "record a controlled zero-count cohort without inventing a simulated unit" in {
    val allocation = Planner.residentCohort(CohortId("persons:empty-stratum"), RepresentedCount(0L), representation)

    allocation.buckets shouldBe empty
    allocation.simulatedUnitCount shouldBe BigInt(0)
    Planner.validate(manifest(Vector(allocation))).isValid shouldBe true
  }

  it should "allocate an explicitly declared census cohort at weight one" in {
    val allocation = Planner.censusCohort(
      CohortId("enterprise:named-systemic"),
      RepresentedUnitFamily.Enterprise,
      RepresentedCount(3L),
      SystemicEligibility.BaselineDeclaredSystemic,
    )

    allocation.buckets shouldBe Vector(WeightBucket(RepresentationWeight(1L), SimulatedUnitCount(3L)))
    allocation.systemicEligibility shouldBe SystemicEligibility.BaselineDeclaredSystemic
    Planner.validate(manifest(Vector(allocation))).isValid shouldBe true
  }

  it should "reject a resident cohort marked as baseline-declared systemic before applying systemic policy" in {
    val invalid = CohortAllocation(
      cohort = CohortId("persons:declared-systemic"),
      family = RepresentedUnitFamily.Resident,
      mode = CohortRepresentationMode.RequestedResidentScale,
      systemicEligibility = SystemicEligibility.BaselineDeclaredSystemic,
      representedCount = RepresentedCount(5L),
      buckets = Vector(WeightBucket(RepresentationWeight(5L), SimulatedUnitCount(1L))),
    )
    val errors  = Planner.validate(manifest(Vector(invalid))).errors

    errors should contain(ManifestValidationError.SystemicEligibilityAppliedToUnsupportedFamily(invalid.cohort, RepresentedUnitFamily.Resident))
    errors should not contain ManifestValidationError.SystemicCohortHasNonUnitWeight(invalid.cohort, RepresentationWeight(5L))
  }

  it should "reject a resident allocation with a weight above the requested scale" in {
    val invalid = CohortAllocation(
      cohort = CohortId("persons:oversized"),
      family = RepresentedUnitFamily.Resident,
      mode = CohortRepresentationMode.RequestedResidentScale,
      systemicEligibility = SystemicEligibility.Ordinary,
      representedCount = RepresentedCount(1001L),
      buckets = Vector(WeightBucket(RepresentationWeight(1001L), SimulatedUnitCount(1L))),
    )

    Planner.validate(manifest(Vector(invalid))).errors should contain(
      ManifestValidationError.ResidentWeightExceedsRequestedScale(invalid.cohort, RepresentationWeight(1001L), representation.residentsPerAgent),
    )
  }

  it should "reject a manifest whose explicit weights do not reconcile to its control count" in {
    val invalid = CohortAllocation(
      cohort = CohortId("enterprise:unreconciled"),
      family = RepresentedUnitFamily.Enterprise,
      mode = CohortRepresentationMode.AdaptiveStratum,
      systemicEligibility = SystemicEligibility.Ordinary,
      representedCount = RepresentedCount(5L),
      buckets = Vector(WeightBucket(RepresentationWeight(4L), SimulatedUnitCount(1L))),
    )

    Planner.validate(manifest(Vector(invalid))).errors should contain(
      ManifestValidationError.FailedReconciliation(CohortReconciliation(invalid.cohort, BigInt(5L), BigInt(4L))),
    )
  }

  it should "reject adaptive weights when the selected rare-stratum policy requires unit weights" in {
    val unitWeightOnly = representation.copy(rareStrataPolicy = RareStrataPolicy.PreserveAtUnitWeight)
    val invalid        = CohortAllocation(
      cohort = CohortId("enterprise:rare"),
      family = RepresentedUnitFamily.Enterprise,
      mode = CohortRepresentationMode.AdaptiveStratum,
      systemicEligibility = SystemicEligibility.Ordinary,
      representedCount = RepresentedCount(5L),
      buckets = Vector(WeightBucket(RepresentationWeight(5L), SimulatedUnitCount(1L))),
    )

    Planner.validate(manifest(Vector(invalid), representationSpec = unitWeightOnly)).errors should contain(
      ManifestValidationError.AdaptiveStratumViolatesUnitWeightPolicy(invalid.cohort, RepresentationWeight(5L)),
    )
  }

  it should "reject a non-unit adaptive allocation for a baseline-declared systemic cohort" in {
    val invalid = CohortAllocation(
      cohort = CohortId("enterprise:declared-systemic"),
      family = RepresentedUnitFamily.Enterprise,
      mode = CohortRepresentationMode.AdaptiveStratum,
      systemicEligibility = SystemicEligibility.BaselineDeclaredSystemic,
      representedCount = RepresentedCount(5L),
      buckets = Vector(WeightBucket(RepresentationWeight(5L), SimulatedUnitCount(1L))),
    )

    Planner.validate(manifest(Vector(invalid))).errors should contain(
      ManifestValidationError.SystemicCohortHasNonUnitWeight(invalid.cohort, RepresentationWeight(5L)),
    )
  }

  it should "reject a manifest for an unsupported population-control schema version" in {
    val allocation = Planner.residentCohort(CohortId("persons:north"), RepresentedCount(1L), representation)

    Planner.validate(manifest(Vector(allocation), controlSchemaVersion = PopulationControlSchema.SchemaVersion + 1)).errors should contain(
      ManifestValidationError.UnsupportedPopulationControlSchemaVersion(PopulationControlSchema.SchemaVersion, PopulationControlSchema.SchemaVersion + 1),
    )
  }

  it should "reject an unsupported population-compilation manifest schema version" in {
    val allocation = Planner.residentCohort(CohortId("persons:north"), RepresentedCount(1L), representation)
    val invalid    = manifest(Vector(allocation)).copy(schemaVersion = ManifestSchemaVersion + 1)

    Planner.validate(invalid).errors should contain(
      ManifestValidationError.UnsupportedManifestSchemaVersion(ManifestSchemaVersion, ManifestSchemaVersion + 1),
    )
  }

end PopulationRepresentationSpec
