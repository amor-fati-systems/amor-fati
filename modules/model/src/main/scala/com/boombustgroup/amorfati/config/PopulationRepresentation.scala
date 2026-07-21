package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.config.PopulationControlSchema.RepresentedCount

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Contract between a researcher's requested representation resolution and a
  * future population compiler.
  *
  * The requested `1:N` scale applies to resident cohorts only. Household and
  * enterprise weights must be derived by the compiler from their own controls,
  * memberships, and relationships; this contract deliberately does not turn
  * residents-per-agent into a universal economy-scale multiplier. Every
  * produced cohort carries explicit integral weights and is reconciled back to
  * its true reference-population count.
  */
object PopulationRepresentation:

  /** Version of the compiled-population manifest structure. It evolves
    * independently from the population-control input schema.
    */
  val ManifestSchemaVersion: Int = 1

  /** Researcher-facing requested maximum number of residents represented by one
    * ordinary simulated resident unit. It is a resolution choice, not an
    * economic or monetary calibration parameter.
    */
  final case class ResidentsPerAgent(value: Long):
    require(value > 0L, s"residents per agent must be positive: $value")

  /** Policy for ordinary enterprises. A stratified compiler preserves control
    * strata with adaptive weights; a census compiler keeps every enterprise at
    * weight one when that is an explicitly supported workload. This policy
    * applies to the synthetic population of ordinary firms; it does not decide
    * whether a firm is a named systemic institution.
    */
  enum EnterpriseRepresentationPolicy:
    /** Synthesize ordinary firms by controlled strata such as sector, region,
      * size, and ownership. A simulated firm may represent several firms, but
      * the compiler must report its integral stratum weight and preserve every
      * nonzero hard-control stratum.
      */
    case Stratified

    /** Represent every ordinary firm in the declared enterprise population at
      * weight one. This is primarily a `1:1` or validation mode: it can be
      * expensive and still does not imply that synthetic firms are real legal
      * entities with observed identities.
      */
    case Census

  /** Policy for nonzero rare control strata. Neither option permits a stratum
    * to disappear because its count is below the ordinary requested scale.
    */
  enum RareStrataPolicy:
    /** Keep the stratum with a smaller, explicitly reported integral weight. */
    case PreserveWithAdaptiveWeights

    /** Keep every represented unit separately at weight one. */
    case PreserveAtUnitWeight

  /** Policy for named systemic institutions. Their identities and eligibility
    * are declared by a baseline; the compiler must never infer them from a size
    * threshold.
    */
  enum SystemicInstitutionPolicy:
    /** Preserve every baseline-declared systemic institution as one simulated
      * unit with representation weight one. This is distinct from a census of
      * all ordinary enterprises.
      */
    case CensusWeightOne

  /** Complete representation choice selected for one run. The same baseline may
    * be compiled at different resolutions, but the baseline's empirical
    * controls and monetary inputs do not change as a consequence.
    *
    * `residentsPerAgent` controls resident resolution only. The two enterprise
    * policies define how a future compiler treats ordinary and rare enterprise
    * strata; `systemicInstitutionPolicy` governs only institutions explicitly
    * named by the baseline.
    */
  final case class RepresentationSpec(
      residentsPerAgent: ResidentsPerAgent,
      enterprisePolicy: EnterpriseRepresentationPolicy,
      rareStrataPolicy: RareStrataPolicy,
      systemicInstitutionPolicy: SystemicInstitutionPolicy,
  )

  /** Seed that selects one reproducible micro-realization inside a fixed set of
    * controls and representation policies.
    */
  final case class PopulationCompilationSeed(value: Long)

  /** SHA-256 digest of the exact population-control payload consumed by a
    * compilation. It is intentionally separate from the eventual full baseline
    * digest because a baseline includes more than population controls.
    */
  final case class PopulationControlsDigest private (value: String):
    override def toString: String = value

  object PopulationControlsDigest:
    /** Validate a digest supplied by a persisted control bundle. */
    def fromSha256Hex(value: String): PopulationControlsDigest =
      val normalized = Option(value).fold("")(_.trim.toLowerCase)
      require(normalized.matches("[0-9a-f]{64}"), "population-controls digest must be a 64-character SHA-256 hex value")
      PopulationControlsDigest(normalized)

    /** Compute the digest of canonical UTF-8 control payload bytes. A future
      * loader, not callers, is responsible for defining those canonical bytes.
      */
    def sha256Utf8(value: String): PopulationControlsDigest =
      val digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
      val hex    = digest.iterator.map(byte => f"${byte & 0xff}%02x").mkString
      PopulationControlsDigest(hex)

  /** Statistical family counted by one representation cohort. The unit of a
    * weight is the family unit itself: a resident weight counts residents, a
    * household weight counts households, and an enterprise weight counts
    * enterprises.
    */
  enum RepresentedUnitFamily:
    /** Usual residents, including resident cohorts that later receive person
      * identifiers. The public `1:N` scale is defined in these units.
      */
    case Resident

    /** Private households as distinct economic decision units. Their weights
      * cannot be inferred solely from resident weights because membership and
      * household composition must remain coherent.
      */
    case PrivateHousehold

    /** Ordinary or named enterprises. Their weights must reconcile both firm
      * counts and employment, so they are not derived from `residentsPerAgent`.
      */
    case Enterprise

    /** A named institutional unit such as a bank, central bank, government, or
      * foreign-sector anchor. Institutional census treatment is declared by a
      * baseline rather than inferred from balance-sheet size.
      */
    case Institution

  /** Stable, baseline-defined identity of one controlled cohort. It should be
    * deterministic from the source table and its stratum, rather than from an
    * allocation order or an in-memory agent index.
    */
  final case class CohortId(value: String):
    require(value.trim.nonEmpty, "representation cohort ID must be non-empty")

  /** Why a cohort has the weights reported in the manifest. This is evidence,
    * not an instruction to infer institutional identity or rare-stratum status.
    */
  enum CohortRepresentationMode:
    /** Canonical resident allocation at the requested `residentsPerAgent`
      * scale.
      */
    case RequestedResidentScale

    /** Explicit preservation of a nonzero rare stratum with an adaptive weight.
      */
    case AdaptiveStratum

    /** Explicit weight-one allocation for census or named systemic units. */
    case CensusWeightOne

  /** Number of simulated units represented by a manifest bucket, held
    * separately from the true population count so model code cannot treat an
    * agent-row count as an economic aggregate.
    */
  final case class SimulatedUnitCount(value: Long):
    require(value >= 0L, s"simulated unit count must be non-negative: $value")

  /** Exact integral number of represented family units carried by one simulated
    * unit. For example, a resident weight of 1000 represents 1000 residents; it
    * is neither a probability nor a monetary multiplier. Continuous weights are
    * intentionally unsupported.
    */
  final case class RepresentationWeight(value: Long):
    require(value > 0L, s"representation weight must be positive: $value")

  /** Compact allocation of one or more simulated units with the same weight.
    * Buckets make manifests scale with distinct weights rather than with the
    * number of simulated rows.
    */
  final case class WeightBucket(weight: RepresentationWeight, simulatedUnits: SimulatedUnitCount):
    require(simulatedUnits.value > 0L, "a representation-weight bucket must contain at least one simulated unit")

  /** Allocation of one true-scale controlled cohort into weighted simulated
    * units. An empty bucket set records a controlled zero-count cohort; a
    * nonzero cohort must always have at least one represented simulated unit.
    */
  final case class CohortAllocation(
      cohort: CohortId,
      family: RepresentedUnitFamily,
      mode: CohortRepresentationMode,
      representedCount: RepresentedCount,
      buckets: Vector[WeightBucket],
  ):
    require(
      representedCount.value == 0L || buckets.nonEmpty,
      s"nonzero cohort ${cohort.value} must have a representation allocation",
    )
    require(
      buckets.map(_.weight).distinct.size == buckets.size,
      s"cohort ${cohort.value} must not repeat a representation weight bucket",
    )
    require(
      buckets.map(_.weight.value) == buckets.map(_.weight.value).sorted(using Ordering.Long.reverse),
      s"cohort ${cohort.value} must order representation-weight buckets from largest to smallest",
    )

    /** Total simulated units after expanding buckets. It is intentionally a
      * technical count and must not be used as a represented economic total.
      */
    def simulatedUnitCount: BigInt =
      buckets.iterator.map(bucket => BigInt(bucket.simulatedUnits.value)).foldLeft(BigInt(0))(_ + _)

    /** True-scale quantity implied by the explicit bucket weights. This must
      * equal [[representedCount]] before the manifest can be published.
      */
    def representedByAllocation: BigInt =
      buckets.iterator
        .map(bucket => BigInt(bucket.weight.value) * bucket.simulatedUnits.value)
        .foldLeft(BigInt(0))(_ + _)

  /** One cohort's true-scale reconciliation. Allocation is exact in this first
    * contract, so the tolerance is zero; future declared rounding policies must
    * become an explicit manifest-schema revision rather than an implicit drift.
    */
  final case class CohortReconciliation(cohort: CohortId, expected: BigInt, actual: BigInt):
    /** Implied represented quantity minus the control-table quantity. */
    def residual: BigInt = actual - expected

    /** The first manifest schema permits no rounding residual. */
    def passes: Boolean = residual == 0

  /** Persistable evidence emitted by a population compiler. It records the
    * exact baseline identity and digest, population controls, representation
    * choice, seed, and compact cohort weights needed to reproduce and audit a
    * compiled population.
    */
  final case class PopulationCompilationManifest(
      schemaVersion: Int,
      baseline: BaselineRef,
      baselineDigest: BaselineDigest,
      populationControlSchemaVersion: Int,
      populationControlsDigest: PopulationControlsDigest,
      representation: RepresentationSpec,
      seed: PopulationCompilationSeed,
      cohorts: Vector[CohortAllocation],
  ):
    require(schemaVersion > 0, s"population-compilation manifest schema version must be positive: $schemaVersion")
    require(populationControlSchemaVersion > 0, s"population-control schema version must be positive: $populationControlSchemaVersion")
    require(cohorts.nonEmpty, "population-compilation manifest must contain at least one cohort allocation")
    require(
      cohorts.map(_.cohort).distinct.size == cohorts.size,
      "population-compilation manifest must not repeat a cohort allocation",
    )

    def reconciliations: Vector[CohortReconciliation] =
      cohorts.map(allocation => CohortReconciliation(allocation.cohort, BigInt(allocation.representedCount.value), allocation.representedByAllocation))

  /** Semantic or reconciliation failure that prevents a manifest from being
    * published as evidence for a compiled population.
    */
  enum ManifestValidationError:
    /** A non-resident cohort used a scale that has meaning only for residents.
      */
    case ResidentScaleAppliedToNonResident(cohort: CohortId, family: RepresentedUnitFamily)

    /** A resident bucket represents more residents than the researcher's
      * requested maximum resolution permits.
      */
    case ResidentWeightExceedsRequestedScale(cohort: CohortId, weight: RepresentationWeight, requested: ResidentsPerAgent)

    /** A resident allocation has more than one residual bucket or a residual
      * represented by multiple rows, so it is not the canonical `1:N` split.
      */
    case NonCanonicalResidentScaleAllocation(cohort: CohortId)

    /** A cohort declared as census or systemic has a weight other than one. */
    case CensusAllocationHasNonUnitWeight(cohort: CohortId, weight: RepresentationWeight)

    /** A rare-stratum allocation used an adaptive weight although the selected
      * policy requires every represented unit to remain separate.
      */
    case AdaptiveStratumViolatesUnitWeightPolicy(cohort: CohortId, weight: RepresentationWeight)

    /** An enterprise allocation is weighted even though the selected ordinary
      * enterprise policy is a census.
      */
    case EnterpriseCensusAllocationHasNonUnitWeight(cohort: CohortId, weight: RepresentationWeight)

    /** The reader understands a different compiled-population manifest schema.
      */
    case UnsupportedManifestSchemaVersion(expected: Int, actual: Int)

    /** The manifest refers to an input-control contract that this compiler does
      * not understand and must not silently reinterpret.
      */
    case UnsupportedPopulationControlSchemaVersion(expected: Int, actual: Int)

    /** Explicit bucket weights fail to reproduce the true-scale cohort count.
      */
    case FailedReconciliation(reconciliation: CohortReconciliation)

  /** Complete validation evidence for a population-compilation manifest.
    * Reconciliations are retained even when valid so a result bundle can show
    * the proof of represented-count conservation.
    */
  final case class ManifestValidationReport(
      reconciliations: Vector[CohortReconciliation],
      errors: Vector[ManifestValidationError],
  ):
    /** A manifest is valid only when every schema, policy, and count invariant
      * holds. Callers must not turn an invalid report into a repaired manifest
      * by silently changing its weights.
      */
    def isValid: Boolean = errors.isEmpty

  /** Canonical planners and validators for representation evidence. The planner
    * intentionally handles only resident and explicit census cohorts; the
    * future household and enterprise compiler remains responsible for deriving
    * weights that preserve its relationships and controls.
    */
  object Planner:

    /** Split a resident control cohort into full requested-scale units plus at
      * most one smaller residual unit. This preserves the controlled count
      * exactly without a rounding multiplier.
      */
    def residentCohort(cohort: CohortId, representedCount: RepresentedCount, representation: RepresentationSpec): CohortAllocation =
      val requested = representation.residentsPerAgent.value
      val complete  = representedCount.value / requested
      val residual  = representedCount.value % requested
      val buckets   =
        Vector(
          Option.when(complete > 0L)(WeightBucket(RepresentationWeight(requested), SimulatedUnitCount(complete))),
          Option.when(residual > 0L)(WeightBucket(RepresentationWeight(residual), SimulatedUnitCount(1L))),
        ).flatten
      CohortAllocation(
        cohort = cohort,
        family = RepresentedUnitFamily.Resident,
        mode = CohortRepresentationMode.RequestedResidentScale,
        representedCount = representedCount,
        buckets = buckets,
      )

    /** Allocate a baseline-declared census cohort at weight one. Callers must
      * choose this mode explicitly; it is never inferred from entity size.
      */
    def censusCohort(cohort: CohortId, family: RepresentedUnitFamily, representedCount: RepresentedCount): CohortAllocation =
      val buckets = Option.when(representedCount.value > 0L)(WeightBucket(RepresentationWeight(1L), SimulatedUnitCount(representedCount.value))).toVector
      CohortAllocation(
        cohort = cohort,
        family = family,
        mode = CohortRepresentationMode.CensusWeightOne,
        representedCount = representedCount,
        buckets = buckets,
      )

    /** Validate publication-level representation semantics and exact cohort
      * reconciliation. A caller must reject an invalid report rather than
      * silently normalizing weights or discarding a residual.
      */
    def validate(manifest: PopulationCompilationManifest): ManifestValidationReport =
      val reconciliations = manifest.reconciliations
      val errors          =
        schemaVersionErrors(manifest) ++
          populationControlSchemaErrors(manifest) ++
          manifest.cohorts.flatMap(cohortErrors(_, manifest.representation)) ++
          reconciliations.filterNot(_.passes).map(ManifestValidationError.FailedReconciliation.apply)
      ManifestValidationReport(reconciliations, errors)

    private def schemaVersionErrors(manifest: PopulationCompilationManifest): Vector[ManifestValidationError] =
      Option
        .when(manifest.schemaVersion != ManifestSchemaVersion)(
          ManifestValidationError.UnsupportedManifestSchemaVersion(ManifestSchemaVersion, manifest.schemaVersion),
        )
        .toVector

    private def populationControlSchemaErrors(manifest: PopulationCompilationManifest): Vector[ManifestValidationError] =
      Option
        .when(manifest.populationControlSchemaVersion != PopulationControlSchema.SchemaVersion)(
          ManifestValidationError.UnsupportedPopulationControlSchemaVersion(PopulationControlSchema.SchemaVersion, manifest.populationControlSchemaVersion),
        )
        .toVector

    private def cohortErrors(allocation: CohortAllocation, representation: RepresentationSpec): Vector[ManifestValidationError] =
      allocation.mode match
        case CohortRepresentationMode.RequestedResidentScale => residentScaleErrors(allocation, representation.residentsPerAgent)
        case CohortRepresentationMode.CensusWeightOne        => censusWeightErrors(allocation)
        case CohortRepresentationMode.AdaptiveStratum        => adaptiveStratumErrors(allocation, representation)

    private def residentScaleErrors(allocation: CohortAllocation, requested: ResidentsPerAgent): Vector[ManifestValidationError] =
      val familyError     = Option.when(allocation.family != RepresentedUnitFamily.Resident)(
        ManifestValidationError.ResidentScaleAppliedToNonResident(allocation.cohort, allocation.family),
      )
      val weightErrors    = allocation.buckets.collect:
        case bucket if bucket.weight.value > requested.value =>
          ManifestValidationError.ResidentWeightExceedsRequestedScale(allocation.cohort, bucket.weight, requested)
      val residualBuckets = allocation.buckets.filter(_.weight.value < requested.value)
      val nonCanonical    =
        Option.when(
          residualBuckets.size > 1 || residualBuckets.exists(_.simulatedUnits.value != 1L),
        )(
          ManifestValidationError.NonCanonicalResidentScaleAllocation(allocation.cohort),
        )
      familyError.toVector ++ weightErrors ++ nonCanonical.toVector

    private def censusWeightErrors(allocation: CohortAllocation): Vector[ManifestValidationError] =
      allocation.buckets.collect:
        case bucket if bucket.weight.value != 1L =>
          ManifestValidationError.CensusAllocationHasNonUnitWeight(allocation.cohort, bucket.weight)

    private def adaptiveStratumErrors(allocation: CohortAllocation, representation: RepresentationSpec): Vector[ManifestValidationError] =
      val rareStratumErrors      = representation.rareStrataPolicy match
        case RareStrataPolicy.PreserveWithAdaptiveWeights => Vector.empty
        case RareStrataPolicy.PreserveAtUnitWeight        =>
          allocation.buckets.collect:
            case bucket if bucket.weight.value != 1L =>
              ManifestValidationError.AdaptiveStratumViolatesUnitWeightPolicy(allocation.cohort, bucket.weight)
      val enterpriseCensusErrors =
        if allocation.family == RepresentedUnitFamily.Enterprise && representation.enterprisePolicy == EnterpriseRepresentationPolicy.Census then
          allocation.buckets.collect:
            case bucket if bucket.weight.value != 1L =>
              ManifestValidationError.EnterpriseCensusAllocationHasNonUnitWeight(allocation.cohort, bucket.weight)
        else Vector.empty
      rareStratumErrors ++ enterpriseCensusErrors

end PopulationRepresentation
