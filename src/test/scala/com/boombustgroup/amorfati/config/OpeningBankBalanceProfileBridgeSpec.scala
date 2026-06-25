package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.fp.FixedPointBase
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class OpeningBankBalanceProfileBridgeSpec extends AnyFlatSpec with Matchers with OptionValues:

  import OpeningBankBalanceProfileBridge.*

  "OpeningBankBalanceProfileBridge" should "cover every runtime bank row exactly once" in {
    val profileRows = Rows.filterNot(_.rowType == "sector_total")

    profileRows should have size Banking.DefaultConfigs.size
    profileRows.map(_.bankId.toInt) shouldBe Banking.DefaultConfigs.map(_.id.toInt)
    profileRows.map(_.runtimeBankName) shouldBe Banking.DefaultConfigs.map(_.name)
    profileRows.map(_.relationshipWeightPrior.value) shouldBe Banking.DefaultConfigs.map(cfg => fixedPointDecimal(cfg.relationshipWeight.toLong))
  }

  it should "anchor aggregate opening banking stocks without allocating them to named banks" in {
    val sectorTotal = Rows.find(_.rowType == "sector_total").value

    sectorTotal.depositsMPln.value shouldBe BigDecimal("2542300")
    sectorTotal.firmLoansMPln.value shouldBe BigDecimal("557400")
    sectorTotal.consumerLoansMPln.value shouldBe BigDecimal("225200")
    sectorTotal.mortgageLoansMPln.value shouldBe BigDecimal("506300")
    sectorTotal.govBondsMPln.value shouldBe BigDecimal("400000")
    sectorTotal.reservesMPln.value shouldBe BigDecimal("88980.5")
    sectorTotal.ownFundsMPln.value shouldBe BigDecimal("199000")
    sectorTotal.totalCapitalRatio.value shouldBe BigDecimal("0.211")
    (sectorTotal.rwaMPln.value - BigDecimal("943127.962085308")).abs should be <= BigDecimal("0.000001")
    sectorTotal.bridgeStatus shouldBe "EMPIRICAL_SECTOR_TOTAL"
  }

  it should "make named-bank extraction gaps and the residual calculation explicit" in {
    val namedRows = Rows.filter(_.rowType == "named_bank")
    val residual  = Rows.find(_.rowType == "residual_bank").value

    namedRows should have size (Banking.DefaultConfigs.size - 1)
    namedRows.map(_.bridgeStatus).distinct shouldBe Vector("PENDING_PUBLIC_REPORT_EXTRACTION")
    namedRows.foreach: row =>
      row.depositsMPln shouldBe None
      row.rwaMPln shouldBe None
      row.cet1Ratio shouldBe None
      row.tier1Ratio shouldBe None
      row.totalCapitalRatio shouldBe None
      row.notes should include("bank capital ratios must come from the explicit ratio columns")

    residual.runtimeBankName shouldBe "Other banks"
    residual.bridgeStatus shouldBe "RESIDUAL_PENDING_NAMED_COVERAGE"
    residual.transformation should include("sector total minus all named-bank values")
    residual.depositsMPln shouldBe None
  }

  it should "keep current relationship-weight priors normalized but separate from empirical stocks" in {
    val profileRows = Rows.filterNot(_.rowType == "sector_total")
    val priorSum    = profileRows.flatMap(_.relationshipWeightPrior).sum

    priorSum shouldBe BigDecimal(1)
    profileRows.foreach: row =>
      row.relationshipWeightPrior.value should be > BigDecimal(0)
  }

  it should "render the committed TSV extract from the typed bridge" in {
    val path  = Path.of("docs/empirical-source-extracts/opening-bank-balance-profile.tsv")
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector

    lines shouldBe OpeningBankBalanceProfileBridge.tsvLines
  }

  private def fixedPointDecimal(raw: Long): BigDecimal =
    BigDecimal(raw) / BigDecimal(FixedPointBase.Scale)
