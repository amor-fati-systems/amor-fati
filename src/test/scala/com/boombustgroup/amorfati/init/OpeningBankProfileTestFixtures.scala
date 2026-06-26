package com.boombustgroup.amorfati.init

import com.boombustgroup.amorfati.config.OpeningBankBalanceProfileBridge

import scala.math.BigDecimal.RoundingMode

object OpeningBankProfileTestFixtures:

  import OpeningBankBalanceProfileBridge.*

  private val TestProfileWeights: Vector[BigDecimal] =
    Vector("0.200", "0.130", "0.090", "0.080", "0.070", "0.040", "0.080", "0.060", "0.050", "0.200").map(BigDecimal(_))

  def completeRows: Vector[Row] =
    val runtimeRows = Rows.filterNot(_.rowType == "sector_total")
    require(
      TestProfileWeights.length == runtimeRows.length,
      s"OpeningBankProfileTestFixtures requires ${runtimeRows.length} weights, got ${TestProfileWeights.length}",
    )
    require(
      TestProfileWeights.sum == BigDecimal(1),
      s"OpeningBankProfileTestFixtures weights must sum to 1, got ${TestProfileWeights.sum}",
    )

    val deposits      = split(SectorTotals.depositsMPln, TestProfileWeights)
    val firmLoans     = split(SectorTotals.firmLoansMPln, TestProfileWeights)
    val consumerLoans = split(SectorTotals.consumerLoansMPln, TestProfileWeights)
    val mortgageLoans = split(SectorTotals.mortgageLoansMPln, TestProfileWeights)
    val govBonds      = split(SectorTotals.govBondsMPln, TestProfileWeights)
    val reserves      = split(SectorTotals.reservesMPln, TestProfileWeights)
    val corpBonds     = split(SectorTotals.corpBondsMPln, TestProfileWeights)
    val ownFunds      = split(SectorTotals.ownFundsMPln, TestProfileWeights)
    val rwa           = split(SectorTotals.rwaMPln, TestProfileWeights)

    Rows.map:
      case row if row.rowType == "sector_total" => row
      case row                                  =>
        val bankIndex = row.bankId.toInt
        val share     = Some(TestProfileWeights(bankIndex))
        row.copy(
          bridgeStatus = "TEST_COMPLETE_RUNTIME_PROFILE",
          depositsMPln = Some(deposits(bankIndex)),
          firmLoansMPln = Some(firmLoans(bankIndex)),
          consumerLoansMPln = Some(consumerLoans(bankIndex)),
          mortgageLoansMPln = Some(mortgageLoans(bankIndex)),
          govBondsMPln = Some(govBonds(bankIndex)),
          reservesMPln = Some(reserves(bankIndex)),
          corpBondsMPln = Some(corpBonds(bankIndex)),
          rwaMPln = Some(rwa(bankIndex)),
          ownFundsMPln = Some(ownFunds(bankIndex)),
          totalCapitalRatio = Some(SectorTotals.totalCapitalRatio),
          depositShare = share,
          firmLoanShare = share,
          consumerLoanShare = share,
          mortgageLoanShare = share,
          govBondShare = share,
          rwaShare = share,
          transformation = "Test fixture: complete runtime profile split by explicit non-default test weights.",
          notes = "Used only to exercise explicit opening-bank target reconciliation in tests.",
        )

  private def split(total: BigDecimal, weights: Vector[BigDecimal]): Vector[BigDecimal] =
    require(weights.nonEmpty, "OpeningBankProfileTestFixtures.split requires non-empty weights")
    val leading = weights.dropRight(1).map(weight => (total * weight).setScale(6, RoundingMode.HALF_EVEN))
    leading :+ (total - leading.sum)
