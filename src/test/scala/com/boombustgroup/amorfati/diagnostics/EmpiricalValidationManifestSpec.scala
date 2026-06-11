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

  it should "carry GUS real-economy ready comparators and documented bridge gaps" in {
    val rows = readManifest()

    val inflation = rowByTarget(rows, "Inflation")
    inflation.status shouldBe "READY"
    inflation.value("source_provider") shouldBe "GUS"
    inflation.value("dataset_code") shouldBe "GUS CPI March 2026"
    inflation.value("vintage") should include("March 2026")
    inflation.value("accessed_at") shouldBe "2026-05-09"
    inflation.value("empirical_value") shouldBe "0.030"
    inflation.value("tolerance") shouldBe "0.010"
    inflation.value("criterion") should include("absolute distance")

    val firmSizeTargets = rows.map(_.target).filter(_.startsWith("Firm-size distribution - ")).toSet
    firmSizeTargets shouldBe Set(
      "Firm-size distribution - Micro",
      "Firm-size distribution - Small",
      "Firm-size distribution - Medium",
      "Firm-size distribution - Large",
    )
    rowByTarget(rows, "Firm-size distribution - Micro").value("model_target") shouldBe "terminal_firms:FirmSize_MicroShare:mean"
    rowByTarget(rows, "Firm-size distribution - Large").value("empirical_value") shouldBe "0.001"

    val gdp = rowByTarget(rows, "GDP growth")
    gdp.status shouldBe "PARTIAL"
    gdp.value("notes") should include("quarterly growth extraction")
  }

  it should "carry NBP ready comparators and documented bridge gaps" in {
    val rows = readManifest()

    val fx = rowByTarget(rows, "FX rate - EUR/PLN")
    fx.status shouldBe "READY"
    fx.value("source_provider") shouldBe "NBP"
    fx.value("dataset_code") should include("082/A/NBP/2026")
    fx.value("vintage") should include("2026-04-29")
    fx.value("accessed_at") shouldBe "2026-05-09"
    fx.value("empirical_value") shouldBe "4.2537"
    fx.value("tolerance") shouldBe "0.1000"
    fx.value("model_target") shouldBe "timeseries:ExRate:mean"

    val referenceRate = rowByTarget(rows, "NBP reference rate")
    referenceRate.status shouldBe "READY"
    referenceRate.value("source_provider") shouldBe "NBP"
    referenceRate.value("empirical_value") shouldBe "0.0375"
    referenceRate.value("tolerance") shouldBe "0.0025"
    referenceRate.value("model_target") shouldBe "timeseries:RefRate:mean"

    val credit = rowByTarget(rows, "Credit/GDP")
    credit.status shouldBe "PARTIAL"
    credit.value("notes") should include("GDP denominator bridge")

    val totalCreditGrowth = rowByTarget(rows, "Credit/GDP - total credit stock growth")
    totalCreditGrowth.status shouldBe "PARTIAL"
    totalCreditGrowth.value("source_provider") shouldBe "NBP"
    totalCreditGrowth.value("model_target") shouldBe "timeseries:TotalCreditStock:pct_change"
    totalCreditGrowth.value("notes") should include("NBP MFI stock-series extraction")

    val firmLoanGrowth = rowByTarget(rows, "Credit/GDP - bank firm loan growth")
    firmLoanGrowth.status shouldBe "PARTIAL"
    firmLoanGrowth.value("model_target") shouldBe "timeseries:BankFirmLoans:pct_change"
    firmLoanGrowth.value("notes") should include("sector-definition mapping")

    val consumerLoanGrowth = rowByTarget(rows, "Credit/GDP - consumer loan growth")
    consumerLoanGrowth.status shouldBe "PARTIAL"
    consumerLoanGrowth.value("model_target") shouldBe "timeseries:ConsumerLoans:pct_change"
    consumerLoanGrowth.value("notes") should include("product-definition mapping")

    val firmApproval = rowByTarget(rows, "Credit supply - firm approval rate bridge")
    firmApproval.status shouldBe "MISSING_DATA_BRIDGE"
    firmApproval.value("vintage") should include("2026-04-30")
    firmApproval.value("model_target") shouldBe "timeseries:FirmCredit_ApprovalRate:delta"
    firmApproval.value("transformation") should include("Do not compare survey levels directly")
    firmApproval.value("notes") should include("sloos-credit-supply-bridge")
    firmApproval.value("notes") should include("Do not use releases after 2026-04-30")

    val consumerApproval = rowByTarget(rows, "Credit supply - consumer approval rate bridge")
    consumerApproval.status shouldBe "MISSING_DATA_BRIDGE"
    consumerApproval.value("vintage") should include("2026-04-30")
    consumerApproval.value("model_target") shouldBe "timeseries:ConsumerCredit_ApprovedToDemand:delta"
    consumerApproval.value("transformation") should include("Do not compare survey levels directly")
    consumerApproval.value("notes") should include("sloos-credit-supply-bridge")
    consumerApproval.value("notes") should include("Do not use releases after 2026-04-30")

    val mortgageStandards = rowByTarget(rows, "Credit supply - mortgage standards bridge")
    mortgageStandards.status shouldBe "MISSING_DATA_BRIDGE"
    mortgageStandards.value("vintage") should include("2026-04-30")
    mortgageStandards.value("unit") shouldBe "supply-constrained mortgage-origination delta"
    mortgageStandards.value("model_target") shouldBe "timeseries:MortgageOriginationSupplyConstrained:delta"
    mortgageStandards.value("transformation") should include("Do not fold mortgage standards into unsecured consumer-credit validation")
    mortgageStandards.value("notes") should include("sloos-mortgage-credit-standards-bridge")
    mortgageStandards.value("notes") should include("Mortgage-demand questions require a separate secured-credit demand bridge")
    mortgageStandards.value("notes") should include("Do not use releases after 2026-04-30")

    val firmDemand = rowByTarget(rows, "Credit demand - firm borrower-demand bridge")
    firmDemand.status shouldBe "MISSING_DATA_BRIDGE"
    firmDemand.value("vintage") should include("2026-04-30")
    firmDemand.value("unit") shouldBe "credit-demand delta"
    firmDemand.value("model_target") shouldBe "timeseries:FirmCredit_CreditDemand:delta"
    firmDemand.value("transformation") should include("Do not use demand questions as evidence of bank-side tightening")
    firmDemand.value("notes") should include("sloos-credit-demand-bridge")
    firmDemand.value("notes") should include("Mortgage-demand questions belong to the secured-credit bridge")
    firmDemand.value("notes") should include("Do not use releases after 2026-04-30")

    val consumerDemand = rowByTarget(rows, "Credit demand - consumer borrower-demand bridge")
    consumerDemand.status shouldBe "MISSING_DATA_BRIDGE"
    consumerDemand.value("vintage") should include("2026-04-30")
    consumerDemand.value("unit") shouldBe "credit-demand delta"
    consumerDemand.value("model_target") shouldBe "timeseries:ConsumerCreditDemand:delta"
    consumerDemand.value("transformation") should include("Do not use demand questions as evidence of bank-side tightening")
    consumerDemand.value("notes") should include("sloos-credit-demand-bridge")
    consumerDemand.value("notes") should include("Mortgage-demand questions belong to the secured-credit bridge")
    consumerDemand.value("notes") should include("Do not use releases after 2026-04-30")

    val currentAccount = rowByTarget(rows, "Current account")
    currentAccount.status shouldBe "PARTIAL"
    currentAccount.value("notes") should include("BoP cadence")
  }

  it should "carry fiscal ready comparators and documented bridge gaps" in {
    val rows = readManifest()

    val domesticDebt = rowByTarget(rows, "Public debt/GDP - PDP forecast 2026")
    domesticDebt.status shouldBe "READY"
    domesticDebt.value("source_provider") shouldBe "MF"
    domesticDebt.value("empirical_value") shouldBe "0.538"
    domesticDebt.value("tolerance") shouldBe "0.050"
    domesticDebt.value("model_target") shouldBe "timeseries:DebtToGdp:terminal"

    val esaDebt = rowByTarget(rows, "Public debt/GDP - ESA2010 debt 2025")
    esaDebt.status shouldBe "READY"
    esaDebt.value("source_provider") shouldBe "Eurostat"
    esaDebt.value("empirical_value") shouldBe "0.597"
    esaDebt.value("model_target") shouldBe "timeseries:Esa2010DebtToGdp:terminal"

    val deficit = rowByTarget(rows, "Fiscal stance - general government deficit 2025")
    deficit.status shouldBe "READY"
    deficit.value("source_provider") shouldBe "Eurostat"
    deficit.value("empirical_value") shouldBe "0.073"
    deficit.value("model_target") shouldBe "timeseries:DeficitToGdp:terminal"

    val expenditure = rowByTarget(rows, "Fiscal stance - state budget expenditure plan 2026")
    expenditure.status shouldBe "PARTIAL"
    expenditure.value("empirical_value") shouldBe "918900000000"
    expenditure.value("notes") should include("coverage bridge")
  }

  it should "carry banking and mortgage-risk ready comparators and documented bridge gaps" in {
    val rows = readManifest()

    val capital = rowByTarget(rows, "Bank capital/liquidity - total capital ratio")
    capital.status shouldBe "READY"
    capital.value("source_provider") shouldBe "KNF"
    capital.value("empirical_value") shouldBe "0.211"
    capital.value("tolerance") shouldBe "0.020"
    capital.value("model_target") shouldBe "timeseries:AggregateBankCAR:terminal"
    capital.value("notes") should include("Terminal per-bank CAR mean is not a sector total-capital-ratio comparator")

    val liquidity = rowByTarget(rows, "Bank capital/liquidity - LCR NSFR NPL bridge")
    liquidity.status shouldBe "PARTIAL"
    liquidity.value("notes") should include("sector average versus model minimum")

    val aggregateNplTrajectory = rowByTarget(rows, "Bank capital/liquidity - aggregate NPL trajectory")
    aggregateNplTrajectory.status shouldBe "PARTIAL"
    aggregateNplTrajectory.value("source_provider") shouldBe "KNF"
    aggregateNplTrajectory.value("model_target") shouldBe "timeseries:MaxBankNPL:max"
    aggregateNplTrajectory.value("notes") should include("KNF NPL trajectory extraction")

    val consumerNplTrajectory = rowByTarget(rows, "Bank capital/liquidity - consumer credit NPL trajectory")
    consumerNplTrajectory.status shouldBe "PARTIAL"
    consumerNplTrajectory.value("model_target") shouldBe "timeseries:ConsumerCredit_NplRatioGross:max"
    consumerNplTrajectory.value("notes") should include("product-level KNF/NBP NPL series")

    val mortgageStock = rowByTarget(rows, "Housing and mortgages - mortgage stock/GDP")
    mortgageStock.status shouldBe "READY"
    mortgageStock.value("source_provider") shouldBe "KNF"
    mortgageStock.value("empirical_value") shouldBe "0.1217"
    mortgageStock.value("model_target") shouldBe "timeseries:MortgageToGdp:terminal"

    val mortgageDefault = rowByTarget(rows, "Housing and mortgages - mortgage default bridge")
    mortgageDefault.status shouldBe "PARTIAL"
    mortgageDefault.value("notes") should include("default-flow bridge")
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

  private def rowByTarget(rows: Vector[ManifestRow], target: String): ManifestRow =
    rows.find(_.target == target).getOrElse(fail(s"Expected manifest target '$target'"))

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
