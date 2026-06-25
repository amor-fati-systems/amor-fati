package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.engine.economics.banking.BankCapitalSemantics.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class BankCapitalSemanticsSpec extends AnyFlatSpec with Matchers:

  private final case class SourceLine(path: String, text: String)

  private val SourceRoot = Path.of("src/main/scala/com/boombustgroup/amorfati")

  private val KnownNonBankCapitalCandidates: Set[SourceLine] = Set(
    SourceLine(
      "src/main/scala/com/boombustgroup/amorfati/config/RobustnessScenarios.scala",
      "params = baseline.copy(capital = baseline.capital.copy(adjustSpeed = Coefficient.decimal(16, 2))),",
    ),
  )

  "BankCapitalSemantics" should "classify every known production bank-capital writer" in {
    val ids = writeSites.map(_.id)

    ids.distinct shouldBe ids
    writeSites.foreach: site =>
      site.categories should not be empty
      site.sfcTreatment.trim should not be empty
      site.absorber.trim should not be empty
      site.diagnostics.trim should not be empty

    writeSites.flatMap(_.categories).toSet should contain allOf (
      Category.OpeningCalibration,
      Category.OrdinaryPnlWaterfall,
      Category.EclProvisionChange,
      Category.HtmRealizedLoss,
      Category.InterbankContagionLoss,
      Category.FailureCapitalDestruction,
      Category.ExactnessReconciliation,
    )
  }

  it should "keep source anchors aligned with the current code" in
    writeSites.foreach: site =>
      matchingLines(site.source) should have size 1

  it should "fail when a new production bank-capital writer lacks a semantic category" in {
    val classifiedAnchors = writeSites.map(_.source)
    val unclassified      = candidateCapitalWriters.filterNot(line =>
      KnownNonBankCapitalCandidates.contains(line) ||
        classifiedAnchors.exists(anchor => line.path == anchor.path && line.text.contains(anchor.fragment)),
    )

    unclassified shouldBe Vector.empty
  }

  it should "keep exactness reconciliation as the only residual capital writer" in {
    val residualSites = writeSites.filter(_.categories.contains(Category.ExactnessReconciliation)).map(_.id)

    residualSites shouldBe Vector("aggregate-exactness-capital-reconciliation")
  }

  private def matchingLines(anchor: SourceAnchor): Vector[String] =
    Files
      .readAllLines(Path.of(anchor.path), StandardCharsets.UTF_8)
      .asScala
      .toVector
      .map(_.trim)
      .filter(_.contains(anchor.fragment))

  private def candidateCapitalWriters: Vector[SourceLine] =
    scalaFiles.flatMap: path =>
      val relativePath = path.toString.replace(java.io.File.separatorChar, '/')
      Files
        .readAllLines(path, StandardCharsets.UTF_8)
        .asScala
        .toVector
        .map(_.trim)
        .filter(isCandidateCapitalWriter)
        .map(SourceLine(relativePath, _))

  private def scalaFiles: Vector[Path] =
    val stream = Files.walk(SourceRoot)
    try
      stream
        .iterator()
        .asScala
        .filter(path => Files.isRegularFile(path) && path.toString.endsWith(".scala"))
        .filterNot(_.toString.endsWith("BankCapitalSemantics.scala"))
        .toVector
        .sortBy(_.toString)
    finally stream.close()

  private def isCandidateCapitalWriter(line: String): Boolean =
    line.contains("copy(capital =") ||
      line.contains("capital = totalCapital * cfg.openingCapitalWeight") ||
      line.contains("capital = capitalPnl.newCapital") ||
      line.contains("capital = PLN.Zero") ||
      line.contains("CapitalPnlOutput(newCapital =")

end BankCapitalSemanticsSpec
