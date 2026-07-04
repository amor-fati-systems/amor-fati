package com.boombustgroup.amorfati.montecarlo.snapshots

import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.types.*

private[montecarlo] object McHouseholdLiquidityDiagnostics:

  final case class Summary(
      /** Signed sum of household demand-deposit balances. */
      netDemandDeposit: PLN,
      /** Sum of positive household demand-deposit balances only. */
      positiveDemandDeposits: PLN,
      /** Absolute value of negative demand-deposit balances, treated as
        * implicit overdraft pressure.
        */
      implicitOverdraft: PLN,
      /** Number of households whose demand-deposit balance is below zero. */
      negativeDepositCount: Int,
      /** Share of households whose demand-deposit balance is below zero. */
      negativeDepositShare: Share,
      /** Lowest household demand-deposit balance. */
      minDemandDeposit: PLN,
      /** First percentile of household demand-deposit balances. */
      depositP01: PLN,
      /** Fifth percentile of household demand-deposit balances. */
      depositP05: PLN,
      /** Tenth percentile of household demand-deposit balances. */
      depositP10: PLN,
      /** Twenty-fifth percentile of household demand-deposit balances. */
      depositP25: PLN,
      /** Median household demand-deposit balance. */
      depositP50: PLN,
      /** Seventy-fifth percentile of household demand-deposit balances. */
      depositP75: PLN,
      /** Ninetieth percentile of household demand-deposit balances. */
      depositP90: PLN,
      /** Ninety-fifth percentile of household demand-deposit balances. */
      depositP95: PLN,
      /** Ninety-ninth percentile of household demand-deposit balances. */
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
