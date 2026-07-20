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
  def sha256Utf8(value: String): BaselineDigest =
    val digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
    val hex    = digest.iterator.map(byte => f"${byte & 0xff}%02x").mkString
    BaselineDigest(hex)

/** Qualification status shown by the baseline catalog. */
enum BaselineQualification:
  case Canonical
  case Experimental
  case Legacy
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

enum BaselineLoadError:
  case InvalidId(input: String, reason: String)
  case UnknownBaseline(requested: BaselineId, available: Vector[BaselineId])
  case IncompatibleModel(
      baseline: BaselineId,
      required: ModelContractVersion,
      current: ModelContractVersion,
  )
  case IntegrityMismatch(baseline: BaselineId, expected: BaselineDigest, actual: BaselineDigest)

/** Immutable prepared baseline. Its engine configuration remains
  * config-private.
  */
final class ResolvedBaseline private[config] (
    val manifest: BaselineManifest,
    private[config] val params: SimParams,
)

/** Internal source of a baseline payload. Disk-backed bundles replace this seam
  * later.
  */
private[config] trait BaselineProvider:
  def manifest: BaselineManifest
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

  def resolve(input: String): Either[BaselineLoadError, ResolvedBaseline] =
    BaselineId.from(input).left.map(reason => BaselineLoadError.InvalidId(input, reason)).flatMap(id => resolve(BaselineRef(id)))

  def resolve(ref: BaselineRef): Either[BaselineLoadError, ResolvedBaseline] =
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
          else Right(ResolvedBaseline(manifest, params))

object BaselineCatalog:
  val LegacyDefaultsId: BaselineId = BaselineId.from("pl-2026-04-30-legacy-v1").fold(error => throw IllegalStateException(error), identity)

  private object LegacyDefaultsProvider extends BaselineProvider:
    private val params = SimParams.defaults

    val manifest: BaselineManifest =
      BaselineManifest(
        id = LegacyDefaultsId,
        displayName = "Poland 2026-04-30 legacy defaults",
        qualification = BaselineQualification.Legacy,
        openingBoundary = LocalDate.of(2026, 4, 30),
        baselineSchemaVersion = 1,
        requiredModelContract = ModelContractVersion.LegacySimParamsV1,
        contentDigest = legacyPayloadDigest(params),
        description = "Migration-only provider over SimParams.defaults; not the pl-2026q2-v1 reference-economy bundle.",
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
