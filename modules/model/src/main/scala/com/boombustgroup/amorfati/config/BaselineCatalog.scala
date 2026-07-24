package com.boombustgroup.amorfati.config

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate

/** Immutable researcher-facing identity for a reference-economy baseline. */
final case class BaselineId private (value: String):
  override def toString: String = value

object BaselineId:
  def from(value: String): Either[String, BaselineId] =
    val normalized = Option(value).fold("")(_.trim)
    if normalized.isEmpty then Left("baseline ID must be non-blank")
    else Right(BaselineId(normalized))

/** Immutable selector used by future Research API experiment specifications. */
final case class BaselineRef(id: BaselineId)

/** SHA-256 digest of the exact baseline payload selected for preparation. */
final case class BaselineDigest private (value: String):
  override def toString: String = value

object BaselineDigest:
  def fromSha256Hex(value: String): BaselineDigest =
    val normalized = Option(value).fold("")(_.trim.toLowerCase)
    require(normalized.matches("[0-9a-f]{64}"), "baseline digest must be a 64-character SHA-256 hex value")
    BaselineDigest(normalized)

  def sha256Utf8(value: String): BaselineDigest =
    val digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
    val hex    = digest.iterator.map(byte => f"${byte & 0xff}%02x").mkString
    BaselineDigest(hex)

/** Qualification status shown by the baseline catalog. */
enum BaselineQualification:
  /** Scientifically released for its stated scope after required reconciliation
    * and validation evidence. This is the only status suitable for canonical
    * research workflows.
    */
  case Canonical

  /** Structurally loadable but not yet qualified for canonical scientific
    * claims. It remains visible for controlled development and review.
    */
  case Experimental

  /** Compatibility adapter over an older or incomplete configuration surface.
    * It may reproduce legacy work, but must not be presented as a canonical
    * reference economy.
    */
  case Legacy

  /** Immutable former canonical release retained solely so prior experiments
    * remain reproducible after a later baseline succeeds it.
    */
  case Superseded

/** Version of the model-side contract required to compile a baseline. */
final case class ModelContractVersion private (value: String):
  override def toString: String = value

object ModelContractVersion:
  val LegacySimParamsV1: ModelContractVersion = ModelContractVersion("legacy-simparams-v1")

  def from(value: String): ModelContractVersion =
    require(value != null && value.trim.nonEmpty, "model contract version must be non-blank")
    ModelContractVersion(value.trim)

/** Descriptive and integrity metadata for one immutable baseline provider. */
final case class BaselineManifest(
    id: BaselineId,
    displayName: String,
    qualification: BaselineQualification,
    openingBoundary: LocalDate,
    baselineSchemaVersion: Int,
    requiredModelContract: ModelContractVersion,
    contentDigest: BaselineDigest,
    description: String,
)

/** Logical components of an immutable reference-economy baseline.
  *
  * Identity, compatibility, and integrity remain manifest metadata. These
  * components hold the economic input and evidence needed to compile a run.
  */
enum BaselineBundleComponentKind:
  /** Typed calibrated model parameters, including units, domains, and their
    * parameter-level provenance references.
    */
  case Parameters

  /** Reconciled controls for persons, households, labor, firms, migration, and
    * other represented populations used by the population compiler.
    */
  case PopulationControls

  /** Named institutions, opening balance-sheet controls, financial-account
    * totals, and classification crosswalks needed to initialize the economy.
    */
  case InstitutionalOpeningState

  /** Declared no-shock or expected paths that belong to the reference economy,
    * rather than an intervention selected by a research scenario.
    */
  case ExogenousBaselineAssumptions

  /** Per-source observation periods, releases, access dates, transformations,
    * reconciliation rules, and redistribution constraints.
    */
  case Provenance

  /** Compilation invariants, opening reconciliations, empirical targets, known
    * gaps, and the evidence supporting the qualification status.
    */
  case ValidationProfile

/** Whether a component is materially part of the resolved bundle, merely
  * documented elsewhere, or not available yet.
  */
enum BaselineBundleComponentAvailability:
  /** The component is material input to this bundle and must be covered by its
    * integrity contract when persisted bundle artifacts are introduced.
    */
  case Present

  /** Related evidence exists elsewhere, but is not yet a versioned and
    * integrity-pinned component of this baseline bundle.
    */
  case Referenced

  /** The bundle has no representation of this component. A canonical bundle may
    * not use this availability.
    */
  case Missing

/** One declared logical component of a baseline bundle. */
final case class BaselineBundleComponent(
    kind: BaselineBundleComponentKind,
    availability: BaselineBundleComponentAvailability,
    detail: String,
):
  require(detail.trim.nonEmpty, s"baseline component $kind must describe its availability")

enum BaselineLoadError:
  /** The supplied textual selector cannot become a valid immutable ID. */
  case InvalidId(input: String, reason: String)

  /** The selector is syntactically valid but no installed provider has it. */
  case UnknownBaseline(requested: BaselineId, available: Vector[BaselineId])

  /** The provider was built for a different model-side compilation contract. */
  case IncompatibleModel(
      baseline: BaselineId,
      required: ModelContractVersion,
      current: ModelContractVersion,
  )

  /** The compiled payload differs from the reviewed digest pinned in the
    * provider manifest.
    */
  case IntegrityMismatch(baseline: BaselineId, expected: BaselineDigest, actual: BaselineDigest)

/** Immutable internal baseline bundle prepared for compilation. Its engine
  * configuration remains config-private, and it does not expose a file format
  * or a public Research API.
  */
final class BaselineBundle private[config] (
    val manifest: BaselineManifest,
    val components: Vector[BaselineBundleComponent],
    private[config] val params: SimParams,
):
  private val componentKinds = components.map(_.kind)

  require(
    componentKinds.toSet == BaselineBundleComponentKind.values.toSet && componentKinds.distinct.size == componentKinds.size,
    "baseline bundle must declare every logical component exactly once",
  )
  require(
    manifest.qualification != BaselineQualification.Canonical || components.forall(_.availability == BaselineBundleComponentAvailability.Present),
    "a canonical baseline bundle must contain every logical component",
  )

  def component(kind: BaselineBundleComponentKind): BaselineBundleComponent =
    components.find(_.kind == kind).getOrElse(throw IllegalStateException(s"missing declared baseline component: $kind"))

/** Internal source of a baseline payload. Disk-backed bundles replace this seam
  * later.
  */
private[config] trait BaselineProvider:
  def manifest: BaselineManifest
  def components: Vector[BaselineBundleComponent]
  def compile(): SimParams

/** Exact baseline resolution and verification for the current model contract.
  */
final class BaselineCatalog private[config] (
    val modelContract: ModelContractVersion,
    providers: Vector[BaselineProvider],
):
  private val providersById = providers.map(provider => provider.manifest.id -> provider).toMap

  require(
    providersById.size == providers.size,
    "baseline catalog cannot contain duplicate baseline IDs",
  )

  def list: Vector[BaselineManifest] = providers.map(_.manifest)

  def resolve(input: String): Either[BaselineLoadError, BaselineBundle] =
    BaselineId.from(input).left.map(reason => BaselineLoadError.InvalidId(input, reason)).flatMap(id => resolve(BaselineRef(id)))

  def resolve(ref: BaselineRef): Either[BaselineLoadError, BaselineBundle] =
    providersById
      .get(ref.id)
      .toRight(BaselineLoadError.UnknownBaseline(ref.id, list.map(_.id)))
      .flatMap: provider =>
        val manifest = provider.manifest
        if manifest.requiredModelContract != modelContract then Left(BaselineLoadError.IncompatibleModel(ref.id, manifest.requiredModelContract, modelContract))
        else
          val params = provider.compile()
          val actual = BaselineCatalog.legacyPayloadDigest(params)
          if actual != manifest.contentDigest then Left(BaselineLoadError.IntegrityMismatch(ref.id, manifest.contentDigest, actual))
          else Right(new BaselineBundle(manifest, provider.components, params))

object BaselineCatalog:
  val LegacyDefaultsId: BaselineId = BaselineId.from("PL-2026-04-30-legacy-v1").fold(error => throw IllegalStateException(error), identity)

  /** Reviewed digest of the complete legacy SimParams.defaults payload. */
  private val LegacyDefaultsPayloadDigest =
    BaselineDigest.fromSha256Hex("ce7d73afff381af8e9eb97647680b9d576e610af2f2d4866580364090de4d8e9")

  private object LegacyDefaultsProvider extends BaselineProvider:
    private val params = SimParams.defaults

    val components: Vector[BaselineBundleComponent] =
      Vector(
        BaselineBundleComponent(
          BaselineBundleComponentKind.Parameters,
          BaselineBundleComponentAvailability.Present,
          "Pinned in-memory SimParams.defaults payload.",
        ),
        BaselineBundleComponent(
          BaselineBundleComponentKind.PopulationControls,
          BaselineBundleComponentAvailability.Missing,
          "Legacy defaults do not contain versioned population control tables.",
        ),
        BaselineBundleComponent(
          BaselineBundleComponentKind.InstitutionalOpeningState,
          BaselineBundleComponentAvailability.Missing,
          "WorldInit derives opening state from legacy parameters rather than a reconciled institutional component.",
        ),
        BaselineBundleComponent(
          BaselineBundleComponentKind.ExogenousBaselineAssumptions,
          BaselineBundleComponentAvailability.Missing,
          "Legacy defaults do not separate baseline driver assumptions from model parameters.",
        ),
        BaselineBundleComponent(
          BaselineBundleComponentKind.Provenance,
          BaselineBundleComponentAvailability.Referenced,
          "CalibrationProvenance.Baseline documents provenance but is not an immutable bundle artifact.",
        ),
        BaselineBundleComponent(
          BaselineBundleComponentKind.ValidationProfile,
          BaselineBundleComponentAvailability.Referenced,
          "Existing validation evidence is not yet attached as a baseline-specific profile.",
        ),
      )

    val manifest: BaselineManifest =
      BaselineManifest(
        id = LegacyDefaultsId,
        displayName = "Poland 2026-04-30 legacy defaults",
        qualification = BaselineQualification.Legacy,
        openingBoundary = LocalDate.of(2026, 4, 30),
        baselineSchemaVersion = 1,
        requiredModelContract = ModelContractVersion.LegacySimParamsV1,
        contentDigest = LegacyDefaultsPayloadDigest,
        description = "Migration-only provider over SimParams.defaults; not the PL-2025-Q4-v1 reference-economy bundle.",
      )

    def compile(): SimParams = params

  val legacy: BaselineCatalog =
    new BaselineCatalog(ModelContractVersion.LegacySimParamsV1, Vector(LegacyDefaultsProvider))

  private[config] def forTesting(
      modelContract: ModelContractVersion,
      providers: Vector[BaselineProvider],
  ): BaselineCatalog =
    new BaselineCatalog(modelContract, providers)

  /** Legacy-only structural fingerprint. A persisted baseline bundle must
    * replace this with canonical hashes of its schema-validated artifacts.
    */
  private[config] def legacyPayloadDigest(params: SimParams): BaselineDigest =
    BaselineDigest.sha256Utf8(s"legacy-simparams-v1\n$params")
