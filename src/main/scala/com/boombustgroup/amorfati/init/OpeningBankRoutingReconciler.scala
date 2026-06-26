package com.boombustgroup.amorfati.init

import com.boombustgroup.amorfati.agents.{Firm, Household}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.Distribute

object OpeningBankRoutingReconciler:

  final case class Result(
      firms: Vector[Firm.State],
      firmFinancialStocks: Vector[Firm.FinancialStocks],
      households: Vector[Household.State],
      householdFinancialStocks: Vector[Household.FinancialStocks],
  )

  def reconcile(
      firms: Vector[Firm.State],
      firmFinancialStocks: Vector[Firm.FinancialStocks],
      households: Vector[Household.State],
      householdFinancialStocks: Vector[Household.FinancialStocks],
      targets: OpeningBankProfileTargets.Targets,
  )(using p: SimParams): Result =
    require(
      firms.length == firmFinancialStocks.length,
      s"OpeningBankRoutingReconciler requires aligned firms and stocks, got ${firms.length} firms and ${firmFinancialStocks.length} stocks",
    )
    require(
      households.length == householdFinancialStocks.length,
      s"OpeningBankRoutingReconciler requires aligned households and stocks, got ${households.length} households and ${householdFinancialStocks.length} stocks",
    )

    val firmDepositTotal        = firmFinancialStocks.map(_.cash).sumPln
    val firmDepositTargets      = distributePln(firmDepositTotal, targets.deposits)
    val householdDepositTargets =
      targets.deposits
        .zip(firmDepositTargets)
        .map: (deposits, firmDeposits) =>
          val householdDeposits = deposits - firmDeposits
          require(
            householdDeposits >= PLN.Zero,
            s"Opening bank deposit target cannot cover firm-deposit split: deposits=$deposits firmDeposits=$firmDeposits",
          )
          householdDeposits

    val firmWeights = targets.firmLoans.zip(firmDepositTargets).map { case (firmLoans, firmDeposits) =>
      firmLoans + firmDeposits
    }
    val firmBankIds = assignBankIds(firms.map(_.bankId), firmWeights, "firms")

    val householdWeights =
      householdDepositTargets.zip(targets.consumerLoans).zip(targets.mortgageLoans).map { case ((deposits, consumerLoans), mortgageLoans) =>
        deposits + consumerLoans + mortgageLoans
      }
    val householdBankIds = assignBankIds(households.map(_.bankId), householdWeights, "households")

    val firmLoans     = distributeProductByBank(targets.firmLoans, firmBankIds, firmFinancialStocks.map(_.firmLoan), "firm loans")
    val firmCash      = distributeProductByBank(firmDepositTargets, firmBankIds, firmFinancialStocks.map(_.cash), "firm deposits")
    val hhDeposits    =
      distributeProductByBank(householdDepositTargets, householdBankIds, householdFinancialStocks.map(_.demandDeposit), "household deposits")
    val consumerLoans =
      distributeProductByBank(targets.consumerLoans, householdBankIds, householdFinancialStocks.map(_.consumerLoan), "household consumer loans")
    val mortgageLoans =
      distributeProductByBank(targets.mortgageLoans, householdBankIds, householdFinancialStocks.map(_.mortgageLoan), "household mortgage loans")

    val reconciledFirms      = firms.zip(firmBankIds).map { case (firm, bankId) =>
      firm.copy(bankId = bankId)
    }
    val reconciledFirmStocks = firmFinancialStocks.indices.map: i =>
      firmFinancialStocks(i).copy(
        cash = firmCash(i),
        firmLoan = firmLoans(i),
      )

    val reconciledHouseholds      = households.zip(householdBankIds).map { case (household, bankId) =>
      household.copy(bankId = bankId)
    }
    val reconciledHouseholdStocks = householdFinancialStocks.indices.map: i =>
      val mortgageLoan = mortgageLoans(i)
      householdFinancialStocks(i).copy(
        demandDeposit = hhDeposits(i),
        consumerLoan = consumerLoans(i),
        mortgageLoan = mortgageLoan,
        mortgageRemainingMonths =
          if mortgageLoan <= PLN.Zero then 0
          else if householdFinancialStocks(i).mortgageRemainingMonths > 0 then householdFinancialStocks(i).mortgageRemainingMonths
          else p.housing.mortgageMaturity,
      )

    require(firmCash.sumPln + hhDeposits.sumPln == targets.deposits.sumPln, "Opening bank reconciler did not preserve aggregate deposits")
    require(firmLoans.sumPln == targets.firmLoans.sumPln, "Opening bank reconciler did not preserve aggregate firm loans")
    require(consumerLoans.sumPln == targets.consumerLoans.sumPln, "Opening bank reconciler did not preserve aggregate consumer loans")
    require(mortgageLoans.sumPln == targets.mortgageLoans.sumPln, "Opening bank reconciler did not preserve aggregate mortgage loans")

    Result(
      firms = reconciledFirms,
      firmFinancialStocks = reconciledFirmStocks.toVector,
      households = reconciledHouseholds,
      householdFinancialStocks = reconciledHouseholdStocks.toVector,
    )

  private def assignBankIds(currentBankIds: Vector[BankId], targetStocks: Vector[PLN], label: String): Vector[BankId] =
    val entityCount = currentBankIds.length
    if entityCount == 0 then
      require(targetStocks.forall(_ <= PLN.Zero), s"Cannot route positive opening bank $label targets without $label")
      Vector.empty
    else
      val desiredCounts = desiredEntityCounts(entityCount, targetStocks, label)
      val assigned      = Array.fill[Option[BankId]](entityCount)(None)
      val rowsByBank    = currentBankIds.zipWithIndex.groupMap(_._1.toInt)(_._2)

      desiredCounts.indices.foreach: bankIndex =>
        val kept = rowsByBank.getOrElse(bankIndex, Vector.empty).take(desiredCounts(bankIndex))
        kept.foreach(index => assigned(index) = Some(BankId(bankIndex)))

      val surplus = currentBankIds.indices.filter(index => assigned(index).isEmpty).iterator
      desiredCounts.indices.foreach: bankIndex =>
        val current = assigned.count(_.contains(BankId(bankIndex)))
        val needed  = desiredCounts(bankIndex) - current
        require(needed >= 0, s"Opening bank routing over-assigned BankId $bankIndex for $label")
        (0 until needed).foreach: _ =>
          require(surplus.hasNext, s"Opening bank routing ran out of $label while filling BankId $bankIndex")
          assigned(surplus.next()) = Some(BankId(bankIndex))

      assigned.toVector.map:
        case Some(bankId) => bankId
        case None         => throw new IllegalStateException(s"Opening bank routing left an unassigned $label row")

  private def desiredEntityCounts(entityCount: Int, targetStocks: Vector[PLN], label: String): Vector[Int] =
    val positiveBanks = targetStocks.zipWithIndex.collect { case (target, bankIndex) if target > PLN.Zero => bankIndex }
    require(
      entityCount >= positiveBanks.length,
      s"Cannot assign $entityCount $label rows across ${positiveBanks.length} positive opening bank targets",
    )
    val weights       = targetStocks.map(_.distributeRaw.max(0L)).toArray
    val base          =
      if weights.exists(_ > 0L) then Distribute.distribute(entityCount.toLong, weights).map(_.toInt).toArray
      else Array.fill(targetStocks.length)(0)

    positiveBanks.foreach: bankIndex =>
      if base(bankIndex) == 0 then
        val donor = base.indices
          .filter(index => base(index) > 1 || (targetStocks(index) <= PLN.Zero && base(index) > 0))
          .maxByOption(base)
          .getOrElse:
            throw new IllegalStateException(s"Cannot reserve one $label row for BankId $bankIndex")
        base(donor) -= 1
        base(bankIndex) += 1

    require(base.sum == entityCount, s"Opening bank routing count mismatch for $label: ${base.sum} != $entityCount")
    base.toVector

  private def distributeProductByBank(
      targets: Vector[PLN],
      bankIds: Vector[BankId],
      currentValues: Vector[PLN],
      label: String,
  ): Vector[PLN] =
    require(bankIds.length == currentValues.length, s"Opening bank $label distribution requires aligned bank ids and values")
    val out = Array.fill(currentValues.length)(PLN.Zero)
    targets.indices.foreach: bankIndex =>
      val indices = bankIds.zipWithIndex.collect { case (bankId, index) if bankId.toInt == bankIndex => index }
      val target  = targets(bankIndex)
      if indices.isEmpty then require(target <= PLN.Zero, s"Opening bank $label target for BankId $bankIndex is positive but no rows are assigned")
      else
        val weights          = indices.map(index => currentValues(index).distributeRaw.max(0L)).toArray
        val effectiveWeights =
          if weights.exists(_ > 0L) then weights
          else Array.fill(indices.length)(1L)
        val allocated        = Distribute.distribute(target.distributeRaw, effectiveWeights)
        indices.zip(allocated).foreach { case (index, raw) =>
          out(index) = PLN.fromRaw(raw)
        }
    out.toVector

  private def distributePln(amount: PLN, weights: Vector[PLN]): Vector[PLN] =
    require(amount >= PLN.Zero, s"Opening bank distribution amount must be non-negative: $amount")
    if weights.isEmpty then
      require(amount == PLN.Zero, s"Cannot distribute $amount across an empty target vector")
      Vector.empty
    else
      val rawWeights = weights.map(_.distributeRaw.max(0L)).toArray
      val effective  =
        if rawWeights.exists(_ > 0L) then rawWeights
        else Array.fill(weights.length)(1L)
      Distribute.distribute(amount.distributeRaw, effective).map(PLN.fromRaw).toVector
