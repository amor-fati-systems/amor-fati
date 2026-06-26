package com.boombustgroup.amorfati.init

import com.boombustgroup.amorfati.config.OpeningBankBalanceProfileBridge

import scala.math.BigDecimal.RoundingMode

object OpeningBankProfileTestFixtures:

  import OpeningBankBalanceProfileBridge.*

  def completeRows: Vector[Row] =
    val runtimeRows = Rows.filterNot(_.rowType == "sector_total")
    val weights     = runtimeRows.map(_.relationshipWeightPrior.getOrElse(BigDecimal(0)))

    val deposits      = split(SectorTotals.depositsMPln, weights)
    val firmLoans     = split(SectorTotals.firmLoansMPln, weights)
    val consumerLoans = split(SectorTotals.consumerLoansMPln, weights)
    val mortgageLoans = split(SectorTotals.mortgageLoansMPln, weights)
    val govBonds      = split(SectorTotals.govBondsMPln, weights)
    val reserves      = split(SectorTotals.reservesMPln, weights)
    val ownFunds      = split(SectorTotals.ownFundsMPln, weights)
    val rwa           = split(SectorTotals.rwaMPln, weights)

    Rows.map:
      case row if row.rowType == "sector_total" => row
      case row                                  =>
        val bankIndex = row.bankId.toInt
        row.copy(
          bridgeStatus = "TEST_COMPLETE_RUNTIME_PROFILE",
          depositsMPln = Some(deposits(bankIndex)),
          firmLoansMPln = Some(firmLoans(bankIndex)),
          consumerLoansMPln = Some(consumerLoans(bankIndex)),
          mortgageLoansMPln = Some(mortgageLoans(bankIndex)),
          govBondsMPln = Some(govBonds(bankIndex)),
          reservesMPln = Some(reserves(bankIndex)),
          rwaMPln = Some(rwa(bankIndex)),
          ownFundsMPln = Some(ownFunds(bankIndex)),
          totalCapitalRatio = Some(SectorTotals.totalCapitalRatio),
          depositShare = row.relationshipWeightPrior,
          firmLoanShare = row.relationshipWeightPrior,
          consumerLoanShare = row.relationshipWeightPrior,
          mortgageLoanShare = row.relationshipWeightPrior,
          govBondShare = row.relationshipWeightPrior,
          rwaShare = row.relationshipWeightPrior,
          transformation = "Test fixture: complete runtime profile split by normalized runtime priors.",
          notes = "Used only to exercise explicit opening-bank target reconciliation in tests.",
        )

  private def split(total: BigDecimal, weights: Vector[BigDecimal]): Vector[BigDecimal] =
    require(weights.nonEmpty, "OpeningBankProfileTestFixtures.split requires non-empty weights")
    val leading = weights.dropRight(1).map(weight => (total * weight).setScale(6, RoundingMode.HALF_EVEN))
    leading :+ (total - leading.sum)
