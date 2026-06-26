package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.init.{OpeningBankProfileTargets, OpeningBankProfileTestFixtures}
import com.boombustgroup.amorfati.types.*
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class OpeningBankBalanceProfileBridgeSpec extends AnyFlatSpec with Matchers with OptionValues:

  import OpeningBankBalanceProfileBridge.*

  given SimParams = SimParams.defaults

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

  it should "publish source-backed named-bank evidence while keeping remaining gaps explicit" in {
    val namedRows = Rows.filter(_.rowType == "named_bank")
    val residual  = Rows.find(_.rowType == "residual_bank").value

    namedRows should have size (Banking.DefaultConfigs.size - 1)

    val pekao = namedRows.find(_.runtimeBankName == "Pekao").value
    pekao.bridgeStatus shouldBe "SOURCE_BACKED_PARTIAL_EVIDENCE"
    pekao.depositsMPln.value shouldBe BigDecimal("273849")
    pekao.firmLoansMPln.value shouldBe BigDecimal("119583")
    pekao.consumerLoansMPln.value shouldBe BigDecimal("6868")
    pekao.mortgageLoansMPln.value shouldBe BigDecimal("82130")
    pekao.govBondsMPln.value shouldBe BigDecimal("89919")
    pekao.rwaMPln.value shouldBe BigDecimal("181200")
    pekao.ownFundsMPln.value shouldBe BigDecimal("32014")
    pekao.totalCapitalRatio.value shouldBe BigDecimal("0.177")
    pekao.depositsMPln.value should not be (SectorTotals.depositsMPln * pekao.relationshipWeightPrior.value)

    val mBank = namedRows.find(_.runtimeBankName == "mBank").value
    mBank.bridgeStatus shouldBe "SOURCE_BACKED_PARTIAL_EVIDENCE"
    mBank.depositsMPln.value shouldBe BigDecimal("237097")
    mBank.firmLoansMPln.value shouldBe BigDecimal("64761")
    mBank.consumerLoansMPln.value shouldBe BigDecimal("25600")
    mBank.mortgageLoansMPln.value shouldBe BigDecimal("53455")
    mBank.govBondsMPln shouldBe None
    mBank.totalCapitalRatio.value shouldBe BigDecimal("0.1596")
    mBank.notes should include("Do not infer the missing government-bond target")

    val ing = namedRows.find(_.runtimeBankName == "ING BSK").value
    ing.bridgeStatus shouldBe "SOURCE_BACKED_PARTIAL_EVIDENCE"
    ing.depositsMPln.value shouldBe BigDecimal("242489")
    ing.firmLoansMPln.value shouldBe BigDecimal("102940")
    ing.consumerLoansMPln.value shouldBe BigDecimal("11432")
    ing.mortgageLoansMPln.value shouldBe BigDecimal("70823")
    ing.govBondsMPln shouldBe None
    ing.rwaMPln.value shouldBe BigDecimal("130337.5")
    ing.totalCapitalRatio.value shouldBe BigDecimal("0.1581")

    val pendingRows = namedRows.filterNot(row => Set("Pekao", "mBank", "ING BSK").contains(row.runtimeBankName))
    pendingRows.map(_.bridgeStatus).distinct shouldBe Vector("PENDING_PUBLIC_REPORT_EXTRACTION")
    pendingRows.foreach: row =>
      row.depositsMPln shouldBe None
      row.rwaMPln shouldBe None
      row.cet1Ratio shouldBe None
      row.tier1Ratio shouldBe None
      row.totalCapitalRatio shouldBe None
      row.notes should include("Do not derive bank-level stock targets from relationship_weight_prior")

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

  it should "leave runtime bank targets pending while source-backed evidence is incomplete" in {
    OpeningBankProfileTargets.fromBridgeRows(Rows) shouldBe OpeningBankProfileTargets.Resolution.Pending(
      "Opening bank profile has source-backed evidence but is not runtime-ready yet",
    )
  }

  it should "resolve complete runtime bank target rows without falling back to relationship weights" in {
    val resolved = OpeningBankProfileTargets.fromBridgeRows(OpeningBankProfileTestFixtures.completeRows)

    resolved shouldBe a[OpeningBankProfileTargets.Resolution.Complete]
    val targets = resolved.asInstanceOf[OpeningBankProfileTargets.Resolution.Complete].targets

    targets.bankIds.map(_.toInt) shouldBe Banking.DefaultConfigs.map(_.id.toInt)
    targets.deposits.sumPln shouldBe summon[SimParams].banking.initDeposits
    targets.firmLoans.sumPln shouldBe summon[SimParams].banking.initLoans
    targets.consumerLoans.sumPln shouldBe summon[SimParams].banking.initConsumerLoans
    targets.mortgageLoans.sumPln shouldBe summon[SimParams].housing.initMortgage
    targets.govBonds.sumPln shouldBe summon[SimParams].banking.initGovBonds
    targets.reserves.value.sumPln shouldBe summon[SimParams].banking.initDeposits * summon[SimParams].banking.reserveReq
    targets.corpBonds.value.sumPln shouldBe summon[SimParams].corpBond.initStock * summon[SimParams].corpBond.bankShare
    targets.openingCapitalProfiles.flatMap(_.ownFunds).sumPln shouldBe summon[SimParams].banking.initCapital
    targets.deposits(0) shouldBe summon[SimParams].banking.initDeposits * Share.decimal(2, 1)
    targets.deposits(1) shouldBe summon[SimParams].banking.initDeposits * Share.decimal(13, 2)
    targets.deposits(0) should not be (summon[SimParams].banking.initDeposits * Banking.DefaultConfigs.head.relationshipWeight)
  }

  it should "fail fast when runtime stock target coverage is mixed" in {
    val mixedRows = OpeningBankProfileTestFixtures.completeRows.map:
      case row if row.rowType != "sector_total" && row.bankId == "0" => row.copy(depositsMPln = None)
      case row                                                       => row

    val err = intercept[IllegalStateException]:
      OpeningBankProfileTargets.fromBridgeRows(mixedRows)

    err.getMessage should include("partial runtime stock targets")
    err.getMessage should include("PKO BP.deposits_m_pln")
  }

  it should "close accepted residual differences into the residual bank target" in {
    val rows = OpeningBankProfileTestFixtures.completeRows.map:
      case row if row.rowType == "residual_bank" => row.copy(corpBondsMPln = row.corpBondsMPln.map(_ - BigDecimal("0.001")))
      case row                                   => row

    val targets = OpeningBankProfileTargets.fromBridgeRows(rows) match
      case OpeningBankProfileTargets.Resolution.Complete(targets) => targets
      case other                                                  => fail(s"Expected complete opening bank targets, got $other")

    targets.corpBonds.value.sumPln shouldBe summon[SimParams].corpBond.initStock * summon[SimParams].corpBond.bankShare
  }

  it should "render the committed TSV extract from the typed bridge" in {
    val path  = Path.of("docs/empirical-source-extracts/opening-bank-balance-profile.tsv")
    val lines = Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector

    lines shouldBe OpeningBankBalanceProfileBridge.tsvLines
  }

  private def fixedPointDecimal(raw: Long): BigDecimal =
    BigDecimal(raw) / BigDecimal(FixedPointBase.Scale)
