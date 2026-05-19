package com.boombustgroup.amorfati.montecarlo

import com.boombustgroup.amorfati.types.*

/** Row selector for optional household micro snapshots. */
sealed trait McHouseholdSnapshotSelection:
  def includes(demandDeposit: PLN, liquidityShortfallFinancing: PLN): Boolean

object McHouseholdSnapshotSelection:
  case object All extends McHouseholdSnapshotSelection:
    override def includes(demandDeposit: PLN, liquidityShortfallFinancing: PLN): Boolean = true

  case object NegativeBalances extends McHouseholdSnapshotSelection:
    override def includes(demandDeposit: PLN, liquidityShortfallFinancing: PLN): Boolean =
      demandDeposit < PLN.Zero

  case object LiquidityShortfall extends McHouseholdSnapshotSelection:
    override def includes(demandDeposit: PLN, liquidityShortfallFinancing: PLN): Boolean =
      liquidityShortfallFinancing > PLN.Zero

  case object NegativeOrLiquidityShortfall extends McHouseholdSnapshotSelection:
    override def includes(demandDeposit: PLN, liquidityShortfallFinancing: PLN): Boolean =
      demandDeposit < PLN.Zero || liquidityShortfallFinancing > PLN.Zero
