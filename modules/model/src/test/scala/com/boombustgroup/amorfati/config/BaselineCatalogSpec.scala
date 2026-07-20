package com.boombustgroup.amorfati.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.LocalDate

class BaselineCatalogSpec extends AnyFlatSpec with Matchers:

  private val PinnedLegacyPayloadDigest =
    BaselineDigest.fromSha256Hex("ce7d73afff381af8e9eb97647680b9d576e610af2f2d4866580364090de4d8e9")

  private object DefaultsProvider extends BaselineProvider:
    private val params = SimParams.defaults

    val manifest: BaselineManifest =
      BaselineManifest(
        id = BaselineCatalog.LegacyDefaultsId,
        displayName = "Test legacy defaults",
        qualification = BaselineQualification.Legacy,
        openingBoundary = LocalDate.of(2026, 4, 30),
        baselineSchemaVersion = 1,
        requiredModelContract = ModelContractVersion.LegacySimParamsV1,
        contentDigest = PinnedLegacyPayloadDigest,
        description = "Test fixture.",
      )

    def compile(): SimParams = params

  "BaselineCatalog" should "list and resolve the exact legacy baseline" in {
    val catalog = BaselineCatalog.legacy

    catalog.list.map(_.id) shouldBe Vector(BaselineCatalog.LegacyDefaultsId)

    val resolved = catalog.resolve(BaselineCatalog.LegacyDefaultsId.value).fold(error => fail(error.toString), identity)
    resolved.manifest.id shouldBe BaselineCatalog.LegacyDefaultsId
    resolved.manifest.qualification shouldBe BaselineQualification.Legacy
    resolved.manifest.contentDigest shouldBe PinnedLegacyPayloadDigest
    resolved.params shouldBe SimParams.defaults
  }

  it should "reject an unknown baseline identity" in {
    val requested = BaselineId.from("pl-2019q4-v1").fold(error => fail(error), identity)

    BaselineCatalog.legacy.resolve(BaselineRef(requested)) shouldBe
      Left(BaselineLoadError.UnknownBaseline(requested, Vector(BaselineCatalog.LegacyDefaultsId)))
  }

  it should "reject a blank baseline identity before catalog lookup" in {
    BaselineCatalog.legacy.resolve("  ") shouldBe
      Left(BaselineLoadError.InvalidId("  ", "baseline ID must be non-blank"))
  }

  it should "reject a changed legacy payload against the pinned digest" in {
    val changedParams = SimParams.defaults.copy(pop = SimParams.defaults.pop.copy(firmsCount = SimParams.defaults.pop.firmsCount + 1))
    val changed       = new BaselineProvider:
      val manifest: BaselineManifest = DefaultsProvider.manifest

      def compile(): SimParams = changedParams

    val catalog = BaselineCatalog.forTesting(ModelContractVersion.LegacySimParamsV1, Vector(changed))
    val actual  = BaselineCatalog.legacyPayloadDigest(changedParams)

    catalog.resolve(BaselineRef(BaselineCatalog.LegacyDefaultsId)) shouldBe
      Left(BaselineLoadError.IntegrityMismatch(BaselineCatalog.LegacyDefaultsId, PinnedLegacyPayloadDigest, actual))
  }

  it should "reject a baseline compiled for a different model contract" in {
    val incompatibleContract = ModelContractVersion.from("target-core-v1")
    val incompatible         = new BaselineProvider:
      val manifest: BaselineManifest = DefaultsProvider.manifest.copy(requiredModelContract = incompatibleContract)

      def compile(): SimParams = SimParams.defaults

    val catalog = BaselineCatalog.forTesting(ModelContractVersion.LegacySimParamsV1, Vector(incompatible))

    catalog.resolve(BaselineRef(BaselineCatalog.LegacyDefaultsId)) shouldBe
      Left(
        BaselineLoadError.IncompatibleModel(
          BaselineCatalog.LegacyDefaultsId,
          incompatibleContract,
          ModelContractVersion.LegacySimParamsV1,
        ),
      )
  }

  it should "keep resolved legacy parameters immutable across preparations" in {
    val first   = BaselineCatalog.legacy.resolve(BaselineCatalog.LegacyDefaultsId.value).fold(error => fail(error.toString), identity)
    val changed = first.params.copy(pop = first.params.pop.copy(firmsCount = first.params.pop.firmsCount + 1))
    val second  = BaselineCatalog.legacy.resolve(BaselineCatalog.LegacyDefaultsId.value).fold(error => fail(error.toString), identity)

    changed.pop.firmsCount shouldBe first.params.pop.firmsCount + 1
    second.params.pop.firmsCount shouldBe first.params.pop.firmsCount
  }
