package com.boombustgroup.amorfati.montecarlo

import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.types.*

private[montecarlo] object McHouseholdLiquidityDiagnostics:

  final case class Summary(
      netDemandDeposit: PLN,
      positiveDemandDeposits: PLN,
      implicitOverdraft: PLN,
      negativeDepositCount: Int,
      negativeDepositShare: Share,
      minDemandDeposit: PLN,
      depositP01: PLN,
      depositP05: PLN,
      depositP10: PLN,
      depositP25: PLN,
      depositP50: PLN,
      depositP75: PLN,
      depositP90: PLN,
      depositP95: PLN,
      depositP99: PLN,
  )

  def fromBalances(balances: Vector[LedgerFinancialState.HouseholdBalances]): Summary =
    val deposits      = balances.map(_.demandDeposit).sorted
    var net           = PLN.Zero
    var positive      = PLN.Zero
    var overdraft     = PLN.Zero
    var negativeCount = 0

    balances.foreach: balance =>
      val deposit = balance.demandDeposit
      net += deposit
      if deposit >= PLN.Zero then positive += deposit
      else
        overdraft -= deposit
        negativeCount += 1

    Summary(
      netDemandDeposit = net,
      positiveDemandDeposits = positive,
      implicitOverdraft = overdraft,
      negativeDepositCount = negativeCount,
      negativeDepositShare = if balances.nonEmpty then Share.fraction(negativeCount, balances.length) else Share.Zero,
      minDemandDeposit = deposits.headOption.getOrElse(PLN.Zero),
      depositP01 = percentile(deposits, Share.decimal(1, 2)),
      depositP05 = percentile(deposits, Share.decimal(5, 2)),
      depositP10 = percentile(deposits, Share.decimal(10, 2)),
      depositP25 = percentile(deposits, Share.decimal(25, 2)),
      depositP50 = percentile(deposits, Share.decimal(50, 2)),
      depositP75 = percentile(deposits, Share.decimal(75, 2)),
      depositP90 = percentile(deposits, Share.decimal(90, 2)),
      depositP95 = percentile(deposits, Share.decimal(95, 2)),
      depositP99 = percentile(deposits, Share.decimal(99, 2)),
    )

  private def percentile(sortedValues: Vector[PLN], p: Share): PLN =
    if sortedValues.isEmpty then PLN.Zero
    else
      val idx = Math.min(sortedValues.length - 1, (sortedValues.length.toLong * p.toLong / FixedPointBase.Scale).toInt)
      sortedValues(idx)
