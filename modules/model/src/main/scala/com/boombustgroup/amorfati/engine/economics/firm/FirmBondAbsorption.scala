package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.World
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.engine.markets.CorporateBondMarket
import com.boombustgroup.amorfati.fp.EntityIds.FirmId
import com.boombustgroup.amorfati.types.*

/** Applies Catalyst absorption limits and reverts unsold bonds to bank loans.
  */
private[firm] object FirmBondAbsorption:
  private val BondRevertThreshold: Share = Share.decimal(1, 3)

  def apply(
      result: FirmProcessingResult,
      w: World,
      banks: Vector[Banking.BankState],
      ledgerFinancialState: LedgerFinancialState,
      executionMonth: ExecutionMonth,
  )(using p: SimParams): BondAbsorptionResult =
    val bankAgg              = Banking.aggregateFromBankStocks(
      banks,
      ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks),
      bankId => CorporateBondOwnership.bankHolderFor(ledgerFinancialState, bankId),
    )
    val absorption           = CorporateBondMarket
      .computeAbsorption(w.financialMarkets.corporateBonds, result.flows.bondIssuance, bankAgg.car, p.banking.minCar)
    val revertShare          = Share.One - absorption
    val requestedByFirm      = result.outcomes
      .map(o => o.firm.id -> result.firmBondAmounts.getOrElse(o.firm.id, PLN.Zero))
      .filter((_, amount) => amount > PLN.Zero)
    val shouldRevert         = revertShare > BondRevertThreshold
    val absorbedBondIssuance = result.flows.bondIssuance * absorption
    val actualIssuanceByFirm =
      allocateAbsorbedBondIssuance(requestedByFirm, absorbedBondIssuance, executionMonth)
    val issuanceMapToApply   =
      if shouldRevert then actualIssuanceByFirm
      else requestedByFirm.toMap
    val bondReversionByFirm  =
      if shouldRevert then
        requestedByFirm
          .map((firmId, requested) => firmId -> (requested - actualIssuanceByFirm.getOrElse(firmId, PLN.Zero)).max(PLN.Zero))
          .filter((_, amount) => amount > PLN.Zero)
          .toMap
      else Map.empty[FirmId, PLN]

    val adjusted =
      result.outcomes.map: o =>
        val revert = bondReversionByFirm.getOrElse(o.firm.id, PLN.Zero)
        if revert > PLN.Zero then
          (
            o.firm,
            o.financialStocks.copy(firmLoan = o.financialStocks.firmLoan + revert),
          )
        else (o.firm, o.financialStocks)

    val issuerLedger = ledgerFinancialState.copy(
      firms = CorporateBondOwnership.applyIssuance(ledgerFinancialState.firms, issuanceMapToApply),
    )

    val bondRevertLoans    = bondReversionByFirm.valuesIterator.sumPln
    val actualBondIssuance =
      if shouldRevert then absorbedBondIssuance
      else result.flows.bondIssuance

    BondAbsorptionResult(
      adjusted.map(_._1),
      adjusted.map(_._2),
      issuerLedger,
      result.flows.newLoans + bondRevertLoans,
      absorption,
      actualBondIssuance,
      bondReversionByFirm,
    )

  private[amorfati] def allocateAbsorbedBondIssuance(
      requestedByFirm: Vector[(FirmId, PLN)],
      actualBondIssuance: PLN,
      executionMonth: ExecutionMonth = ExecutionMonth.First,
  ): Map[FirmId, PLN] =
    val positiveRequests = requestedByFirm.filter((_, amount) => amount > PLN.Zero)
    val target           = actualBondIssuance.distributeRaw
    val totalRequested   = positiveRequests.iterator.map((_, amount) => amount.distributeRaw).sum
    if positiveRequests.isEmpty || target <= 0L || totalRequested <= 0L then Map.empty
    else if target >= totalRequested then positiveRequests.toMap
    else
      case class AllocationRow(index: Int, firmId: FirmId, requested: Long, base: Long, remainder: Long, tieBreak: Long)

      val rows =
        try
          positiveRequests.zipWithIndex.map { case ((firmId, requestedAmount), index) =>
            val requested = requestedAmount.distributeRaw
            val product   = java.lang.Math.multiplyExact(target, requested)
            AllocationRow(index, firmId, requested, product / totalRequested, product % totalRequested, allocationTieBreak(firmId, executionMonth))
          }
        catch case _: ArithmeticException => return allocateAbsorbedBondIssuanceBig(positiveRequests, target, totalRequested, executionMonth)

      val remaining         = target - rows.iterator.map(_.base).sum
      val (_, bonusByIndex) = rows
        .sortWith: (left, right) =>
          if left.remainder == right.remainder then
            if left.tieBreak == right.tieBreak then left.index < right.index
            else left.tieBreak < right.tieBreak
          else left.remainder > right.remainder
        .foldLeft((remaining, Map.empty[Int, Long])) { case ((left, bonuses), row) =>
          if left <= 0L || row.base >= row.requested then (left, bonuses)
          else (left - 1L, bonuses.updated(row.index, 1L))
        }

      val allocations  = rows.map(row => row.firmId -> PLN.fromRaw(row.base + bonusByIndex.getOrElse(row.index, 0L)))
      val allocatedRaw = allocations.iterator.map((_, amount) => amount.distributeRaw).sum
      require(allocatedRaw == target, s"Corporate bond absorption allocation must sum to target=$target, got $allocatedRaw")
      allocations.filter((_, amount) => amount > PLN.Zero).toMap

  private def allocateAbsorbedBondIssuanceBig(
      positiveRequests: Vector[(FirmId, PLN)],
      target: Long,
      totalRequested: Long,
      executionMonth: ExecutionMonth,
  ): Map[FirmId, PLN] =
    case class AllocationRow(index: Int, firmId: FirmId, requested: Long, base: Long, remainder: BigInt, tieBreak: Long)

    val rows = positiveRequests.zipWithIndex.map { case ((firmId, requestedAmount), index) =>
      val requested = requestedAmount.distributeRaw
      val product   = BigInt(target) * BigInt(requested)
      AllocationRow(
        index,
        firmId,
        requested,
        (product / BigInt(totalRequested)).toLong,
        product % BigInt(totalRequested),
        allocationTieBreak(firmId, executionMonth),
      )
    }

    val remaining         = target - rows.iterator.map(_.base).sum
    val (_, bonusByIndex) = rows
      .sortWith: (left, right) =>
        if left.remainder == right.remainder then
          if left.tieBreak == right.tieBreak then left.index < right.index
          else left.tieBreak < right.tieBreak
        else left.remainder > right.remainder
      .foldLeft((remaining, Map.empty[Int, Long])) { case ((left, bonuses), row) =>
        if left <= 0L || row.base >= row.requested then (left, bonuses)
        else (left - 1L, bonuses.updated(row.index, 1L))
      }

    val allocations  = rows.map(row => row.firmId -> PLN.fromRaw(row.base + bonusByIndex.getOrElse(row.index, 0L)))
    val allocatedRaw = allocations.iterator.map((_, amount) => amount.distributeRaw).sum
    require(allocatedRaw == target, s"Corporate bond absorption allocation must sum to target=$target, got $allocatedRaw")
    allocations.filter((_, amount) => amount > PLN.Zero).toMap

  private def allocationTieBreak(firmId: FirmId, executionMonth: ExecutionMonth): Long =
    val firm  = firmId.toInt.toLong
    val month = executionMonth.toLong
    val mixed = (firm * 0x9e3779b97f4a7c15L) ^ (month * 0xbf58476d1ce4e5b9L)
    val step1 = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L
    val step2 = (step1 ^ (step1 >>> 27)) * 0x94d049bb133111ebL
    (step2 ^ (step2 >>> 31)) & Long.MaxValue
