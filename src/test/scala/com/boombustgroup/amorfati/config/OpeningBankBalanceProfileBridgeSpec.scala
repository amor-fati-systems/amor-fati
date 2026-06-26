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
    namedRows.map(_.bridgeStatus).distinct shouldBe Vector("SOURCE_BACKED_PARTIAL_EVIDENCE")

    val pko = namedRows.find(_.runtimeBankName == "PKO BP").value
    pko.depositsMPln.value shouldBe BigDecimal("440454")
    pko.firmLoansMPln.value shouldBe BigDecimal("89238")
    pko.consumerLoansMPln.value shouldBe BigDecimal("41143")
    pko.mortgageLoansMPln.value shouldBe BigDecimal("130142")
    pko.rwaMPln.value shouldBe BigDecimal("280625")
    pko.ownFundsMPln.value shouldBe BigDecimal("50361")
    pko.totalCapitalRatio.value shouldBe BigDecimal("0.17946013363028954")
    pko.notes should include("nearest official bank-level disclosure")

    val pekao = namedRows.find(_.runtimeBankName == "Pekao").value
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
    mBank.depositsMPln.value shouldBe BigDecimal("237097")
    mBank.firmLoansMPln.value shouldBe BigDecimal("64761")
    mBank.consumerLoansMPln.value shouldBe BigDecimal("25600")
    mBank.mortgageLoansMPln.value shouldBe BigDecimal("53455")
    mBank.govBondsMPln shouldBe None
    mBank.totalCapitalRatio.value shouldBe BigDecimal("0.1596")
    mBank.notes should include("Do not infer the missing government-bond target")

    val ing = namedRows.find(_.runtimeBankName == "ING BSK").value
    ing.depositsMPln.value shouldBe BigDecimal("242489")
    ing.firmLoansMPln.value shouldBe BigDecimal("102940")
    ing.consumerLoansMPln.value shouldBe BigDecimal("11432")
    ing.mortgageLoansMPln.value shouldBe BigDecimal("70823")
    ing.govBondsMPln shouldBe None
    ing.rwaMPln.value shouldBe BigDecimal("130337.5")
    ing.totalCapitalRatio.value shouldBe BigDecimal("0.1581")

    val santander = namedRows.find(_.runtimeBankName == "Santander").value
    santander.sourceProvider should include("Erste")
    santander.depositsMPln.value shouldBe BigDecimal("228000")
    santander.firmLoansMPln.value shouldBe BigDecimal("91839")
    santander.consumerLoansMPln.value shouldBe BigDecimal("23577.174")
    santander.mortgageLoansMPln.value shouldBe BigDecimal("56553.797")
    santander.govBondsMPln.value shouldBe BigDecimal("75499.412")
    santander.rwaMPln.value shouldBe BigDecimal("139500")
    santander.totalCapitalRatio.value shouldBe BigDecimal("0.1872")

    val bps = namedRows.find(_.runtimeBankName == "BPS/Coop").value
    bps.depositsMPln.value shouldBe BigDecimal("120042.373")
    bps.firmLoansMPln.value shouldBe BigDecimal("24803.3016")
    bps.consumerLoansMPln shouldBe None
    bps.mortgageLoansMPln shouldBe None
    bps.ownFundsMPln.value shouldBe BigDecimal("11985.070")
    bps.notes should include("cooperative-bank runtime slot")

    val bnp = namedRows.find(_.runtimeBankName == "BNP Paribas").value
    bnp.depositsMPln.value shouldBe BigDecimal("137000")
    bnp.firmLoansMPln.value shouldBe BigDecimal("60794")
    bnp.consumerLoansMPln.value shouldBe BigDecimal("13288")
    bnp.mortgageLoansMPln.value shouldBe BigDecimal("21188")
    bnp.rwaMPln.value shouldBe BigDecimal("102900")
    bnp.totalCapitalRatio.value shouldBe BigDecimal("0.1679")

    val millennium = namedRows.find(_.runtimeBankName == "Millennium").value
    millennium.depositsMPln.value shouldBe BigDecimal("134806")
    millennium.firmLoansMPln.value shouldBe BigDecimal("23310")
    millennium.consumerLoansMPln.value shouldBe BigDecimal("19174")
    millennium.mortgageLoansMPln.value shouldBe BigDecimal("35766")
    millennium.govBondsMPln.value shouldBe BigDecimal("34102.847")
    millennium.rwaMPln.value shouldBe BigDecimal("58386")
    millennium.totalCapitalRatio.value shouldBe BigDecimal("0.1757")

    val alior = namedRows.find(_.runtimeBankName == "Alior").value
    alior.depositsMPln.value shouldBe BigDecimal("85413.751")
    alior.firmLoansMPln.value shouldBe BigDecimal("24798")
    alior.consumerLoansMPln.value shouldBe BigDecimal("20754.416")
    alior.mortgageLoansMPln.value shouldBe BigDecimal("24094.288")
    alior.govBondsMPln.value shouldBe BigDecimal("25645.757")
    alior.rwaMPln.value shouldBe BigDecimal("61388.7")
    alior.totalCapitalRatio.value shouldBe BigDecimal("0.1785")

    namedRows.foreach: row =>
      row.notes should not include "Do not derive bank-level stock targets from relationship_weight_prior"

    residual.runtimeBankName shouldBe "Other banks"
    residual.bridgeStatus shouldBe "RESIDUAL_PARTIAL_SOURCE_COVERAGE"
    residual.transformation should include("sector total minus all named-bank values")
    residual.depositsMPln.value shouldBe BigDecimal("643148.876")
    residual.ownFundsMPln shouldBe None
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
