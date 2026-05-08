package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.diagnostics.EmpiricalValidationExport.{ModelTarget, SourceStatus}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.LocalDate
import scala.jdk.CollectionConverters.*

class EmpiricalValidationManifestSpec extends AnyFlatSpec with Matchers:

  private val manifestPath = Path.of("docs/empirical-validation-source-manifest.csv")

  private val canonicalStatusTokens = Set(
    "READY",
    "PARTIAL",
    "MISSING_OUTPUT",
    "MISSING_DATA_BRIDGE",
    "MISSING_SOURCE_DETAIL",
    "BRIDGE_ASSUMPTION",
  )

  private val requiredTargets = Set(
    "GDP growth",
    "Inflation",
    "Unemployment",
    "Wages",
    "Credit/GDP",
    "Public debt/GDP",
    "Current account",
    "Firm-size distribution",
    "Bankruptcies",
    "Bank capital/liquidity",
    "Inequality",
    "Sectoral output",
    "External prices and FX",
    "Housing and mortgages",
    "Fiscal stance",
    "Monetary and financial market conditions",
  )

  private final case class ManifestRow(line: Int, values: Map[String, String]):
    def value(column: String): String =
      values.getOrElse(column, "")

    def nonEmpty(column: String): Boolean =
      value(column).trim.nonEmpty

    def target: String = value("target")
    def status: String = value("status")

  "empirical validation source manifest" should "cover every validation target family from the report" in {
    val rows           = readManifest()
    val targets        = rows.map(_.target).toSet
    val missingTargets = requiredTargets -- targets

    withClue(missingTargets.mkString("Missing manifest targets: ", ", ", "")) {
      missingTargets shouldBe empty
    }
  }

  it should "use canonical status tokens and report current status counts" in {
    val rows = readManifest()

    val nonCanonical = rows.filterNot(row => canonicalStatusTokens.contains(row.status)).map(row => s"line ${row.line}: ${row.status}")
    withClue(nonCanonical.mkString("\n")) {
      nonCanonical shouldBe empty
    }

    val counts = rows.groupMapReduce(row => parseStatus(row))(_ => 1)(_ + _)
    withClue(renderCounts(counts)) {
      rows should not be empty
      counts.values.sum shouldBe rows.size
    }
  }

  it should "require metadata fields according to manifest status" in {
    val errors = readManifest().flatMap(validateMetadata)

    withClue(errors.mkString("\n")) {
      errors shouldBe empty
    }
  }

  private def validateMetadata(row: ManifestRow): Vector[String] =
    val commonRequired = Vector(
      "target",
      "source_provider",
      "source_url",
      "dataset_code",
      "vintage",
      "license_or_reuse_note",
      "frequency",
      "unit",
      "transformation",
      "model_target",
      "status",
    )

    val commonErrors = commonRequired.flatMap: column =>
      if row.nonEmpty(column) then None else Some(error(row, s"missing required '$column'"))

    val parseErrors = Vector(
      ModelTarget.parse(row.value("model_target")).left.toOption.map(err => error(row, err)),
      SourceStatus.parse(row.status).left.toOption.map(err => error(row, err)),
      validateOptionalDate(row, "accessed_at"),
      validateOptionalDecimal(row, "empirical_value"),
      validateOptionalDecimal(row, "tolerance"),
    ).flatten

    val statusErrors =
      parseStatus(row) match
        case SourceStatus.Ready =>
          Vector(
            Option.when(row.value("vintage") == "TBD")(error(row, "READY row must have concrete vintage")),
            Option.when(!row.nonEmpty("accessed_at"))(error(row, "READY row must have accessed_at")),
            Option.when(!row.nonEmpty("criterion"))(error(row, "READY row must have criterion")),
            validateReadyDecimal(row, "empirical_value"),
            validateReadyDecimal(row, "tolerance"),
          ).flatten
        case SourceStatus.Partial | SourceStatus.MissingOutput | SourceStatus.MissingDataBridge | SourceStatus.MissingSourceDetail |
            SourceStatus.BridgeAssumption =>
          Vector(
            Option.when(!row.nonEmpty("notes"))(error(row, s"${row.status} row must explain the remaining blocker in notes")),
          ).flatten

    commonErrors ++ parseErrors ++ statusErrors

  private def readManifest(): Vector[ManifestRow] =
    val lines  = Files.readAllLines(manifestPath, StandardCharsets.UTF_8).asScala.toVector.filter(_.trim.nonEmpty)
    lines.headOption.getOrElse(fail(s"$manifestPath is empty"))
    val header = lines.head.split(";", -1).toVector
    lines.tail.zipWithIndex.map: (line, index) =>
      val cells = line.split(";", -1).toVector
      withClue(s"$manifestPath line ${index + 2}") {
        cells should have size header.size
      }
      ManifestRow(index + 2, header.zip(cells).toMap)

  private def parseStatus(row: ManifestRow): SourceStatus =
    SourceStatus.parse(row.status).fold(err => fail(error(row, err)), identity)

  private def validateOptionalDate(row: ManifestRow, column: String): Option[String] =
    Option
      .when(row.nonEmpty(column)):
        try
          LocalDate.parse(row.value(column))
          ""
        catch case err: Exception => error(row, s"invalid '$column': ${err.getMessage}")
      .filter(_.nonEmpty)

  private def validateOptionalDecimal(row: ManifestRow, column: String): Option[String] =
    if row.nonEmpty(column) then None else None

  private def validateReadyDecimal(row: ManifestRow, column: String): Option[String] =
    if !row.nonEmpty(column) then Some(error(row, s"READY row must have $column"))
    else
      try
        BigDecimal(row.value(column))
        None
      catch case err: NumberFormatException => Some(error(row, s"READY row must have numeric '$column': ${err.getMessage}"))

  private def renderCounts(counts: Map[SourceStatus, Int]): String =
    counts.toVector.sortBy(_._1.token).map((status, count) => s"${status.token}=$count").mkString("Manifest status counts: ", ", ", "")

  private def error(row: ManifestRow, message: String): String =
    s"$manifestPath line ${row.line} '${row.target}': $message"

end EmpiricalValidationManifestSpec
