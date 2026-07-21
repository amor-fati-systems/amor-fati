package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.config.EnterpriseControlBundleLoader.*
import com.boombustgroup.amorfati.config.EnterpriseControlSchema.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*
import scala.util.Using

class EnterpriseControlBundleLoaderSpec extends AnyFlatSpec with Matchers:

  private val FixtureDigest = "e576c112117f76843122fb3ac277d0b41eab76d200fdb2df23b6535c2e77a181"

  private def fixtureRoot: Path =
    Path.of(Option(getClass.getResource("/enterprise-control-bundles/synthetic-v1")).getOrElse(fail("synthetic enterprise-control fixture is missing")).toURI)

  private def withCopiedFixture[A](f: Path => A): A =
    val destination = Files.createTempDirectory("enterprise-control-bundle-")
    try
      pathsUnder(fixtureRoot).foreach: source =>
        val target = destination.resolve(fixtureRoot.relativize(source))
        if Files.isDirectory(source) then Files.createDirectories(target)
        else Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
      f(destination)
    finally deleteTree(destination)

  private def refreshDigest(root: Path): Unit =
    val originalManifest = EnterpriseControlBundleLoader.load(fixtureRoot).fold(error => fail(s"fixture must load before mutation: $error"), _.manifest)
    val actualDigest     =
      EnterpriseControlBundleLoader.computeDigest(root, originalManifest).fold(error => fail(s"cannot recompute fixture digest: $error"), identity)
    val manifestPath     = root.resolve("manifest.tsv")
    val updated          = Files.readString(manifestPath, UTF_8).replace(FixtureDigest, actualDigest.toString)
    Files.writeString(manifestPath, updated, UTF_8)

  private def deleteTree(root: Path): Unit =
    if Files.exists(root) then pathsUnder(root).sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)

  private def pathsUnder(root: Path): Vector[Path] =
    Using.resource(Files.walk(root))(_.iterator().asScala.toVector)

  "EnterpriseControlBundleLoader" should "load and reconcile a synthetic data-only bundle" in {
    val loaded = EnterpriseControlBundleLoader.load(fixtureRoot).fold(error => fail(s"unexpected loader failure: $error"), identity)

    loaded.manifest.baseline.id.toString shouldBe "synthetic-enterprise-controls-v1"
    loaded.manifest.enterpriseControlsDigest.toString shouldBe FixtureDigest
    loaded.manifest.source.sha256 shouldBe "1111111111111111111111111111111111111111111111111111111111111111"
    loaded.controls.enterpriseStrata.size shouldBe 3
    loaded.controls.sourceResiduals.size shouldBe 3
    loaded.validation.isValid shouldBe true
  }

  it should "reject a changed normalized control file when its manifest digest was not updated" in
    withCopiedFixture: root =>
      val strataPath = root.resolve("enterprise-strata.tsv")
      Files.writeString(
        strataPath,
        Files.readString(strataPath, UTF_8).replace("north\tA\t0-9\t7", "north\tA\t0-9\t8"),
        UTF_8,
      )

      EnterpriseControlBundleLoader.load(root) match
        case Left(LoadError.EnterpriseControlsDigestMismatch(expected, actual)) =>
          expected.toString shouldBe FixtureDigest
          actual should not be expected
        case other                                                              => fail(s"expected enterprise-controls digest mismatch, got: $other")

  it should "reject malformed UTF-8 after its digest has been refreshed" in
    withCopiedFixture: root =>
      val strataPath = root.resolve("enterprise-strata.tsv")
      Files.write(strataPath, Array(0x80.toByte))
      refreshDigest(root)

      EnterpriseControlBundleLoader.load(root) match
        case Left(LoadError.InvalidTsv(path, detail)) =>
          path shouldBe strataPath
          detail should include("invalid UTF-8")
        case other                                    => fail(s"expected invalid UTF-8 failure, got: $other")

  it should "reject an unreconciled bundle after its digest has been refreshed" in
    withCopiedFixture: root =>
      val totalsPath = root.resolve("source-totals.tsv")
      Files.writeString(
        totalsPath,
        Files.readString(totalsPath, UTF_8).replace("0-9\t13", "0-9\t12"),
        UTF_8,
      )
      refreshDigest(root)

      EnterpriseControlBundleLoader.load(root) match
        case Left(LoadError.ControlValidationFailed(errors)) =>
          val hasSourceTotalFailure = errors.exists:
            case ValidationError.FailedReconciliation(reconciliation) => reconciliation.id == "source-total-by-expected-workers-band:0-9"
            case _                                                    => false
          hasSourceTotalFailure shouldBe true
        case other                                           => fail(s"expected reconciliation failure, got: $other")

  it should "attribute a structural classification failure to its classification file" in
    withCopiedFixture: root =>
      val regionsPath = root.resolve("registered-seat-regions.tsv")
      Files.writeString(regionsPath, "code\n", UTF_8)
      refreshDigest(root)

      EnterpriseControlBundleLoader.load(root) match
        case Left(LoadError.InvalidTsv(path, detail)) =>
          path shouldBe regionsPath
          detail should include("registered-seat region")
        case other                                    => fail(s"expected invalid classification TSV, got: $other")

  it should "attribute a structural bundle failure to source-totals.tsv" in
    withCopiedFixture: root =>
      val totalsPath = root.resolve("source-totals.tsv")
      Files.writeString(totalsPath, "expected_workers_band\tcount\n", UTF_8)
      refreshDigest(root)

      EnterpriseControlBundleLoader.load(root) match
        case Left(LoadError.InvalidTsv(path, detail)) =>
          path shouldBe totalsPath
          detail should include("source totals")
        case other                                    => fail(s"expected invalid source totals TSV, got: $other")

  it should "report the physical line for an invalid residual row" in
    withCopiedFixture: root =>
      val residualsPath = root.resolve("source-residuals.tsv")
      Files.writeString(
        residualsPath,
        Files.readString(residualsPath, UTF_8).replace("\tA\t0-9", "\tunknown-section\t0-9"),
        UTF_8,
      )
      refreshDigest(root)

      EnterpriseControlBundleLoader.load(root) match
        case Left(LoadError.InvalidControlRow(path, lineNumber, detail)) =>
          path shouldBe residualsPath
          lineNumber shouldBe 2
          detail should include("pkd2007_section references undeclared value")
        case other                                                       => fail(s"expected invalid control row, got: $other")

  it should "reject a residual row without an explicit missing source dimension" in
    withCopiedFixture: root =>
      val residualsPath = root.resolve("source-residuals.tsv")
      Files.writeString(
        residualsPath,
        Files.readString(residualsPath, UTF_8).replace("\tA\t0-9\t1", "north\tA\t0-9\t1"),
        UTF_8,
      )
      refreshDigest(root)

      EnterpriseControlBundleLoader.load(root) match
        case Left(LoadError.InvalidControlRow(path, lineNumber, detail)) =>
          path shouldBe residualsPath
          lineNumber shouldBe 2
          detail should include("source residual must have an explicit missing")
        case other                                                       => fail(s"expected invalid control row, got: $other")

end EnterpriseControlBundleLoaderSpec
