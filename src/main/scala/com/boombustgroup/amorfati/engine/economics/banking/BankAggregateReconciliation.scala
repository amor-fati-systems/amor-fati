package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.diagnostics.banking.BankReconciliationDiagnostics
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.engine.markets.HousingMarket
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.Distribute

/** Closes aggregate bank stock-flow exactness after explicit bank settlement,
  * failure resolution, and bond-portfolio movements have run.
  *
  * This is deliberately outside `BankingStepRunner`: the runner owns monthly
  * stage ordering, while this object owns the accounting repair semantics and
  * diagnostics for the aggregate bank deposit/capital identities.
  */
private[banking] object BankAggregateReconciliation:

  /** Computes aggregate deposit/capital residuals, distributes any exactness
    * patch across live banks, and resolves any failure caused by that patch.
    */
  def reconcile(
      banks: Vector[Banking.BankState],
      financialStocks: Vector[Banking.BankFinancialStocks],
      bankCorpBondHoldings: Vector[PLN],
      prevBankAgg: Banking.Aggregate,
      in: StepInput,
      jstDepositChange: PLN,
      investNetDepositFlow: PLN,
      quasiFiscalDepositChange: PLN,
      mortgageFlows: HousingMarket.MortgageFlows,
      bailInLoss: PLN,
      multiCapDestruction: PLN,
      interbankContagionLoss: PLN,
      htmRealizedLoss: PLN,
      bankCapitalTerms: BankCapitalTerms,
  )(using p: SimParams): AggregateReconciliationResult =
    if banks.isEmpty then
      AggregateReconciliationResult(
        banks,
        financialStocks,
        bankCorpBondHoldings,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        PLN.Zero,
        0,
        Vector.empty,
        false,
        BankReconciliationDiagnostics.zero,
      )
    else
      val target         = targetAggregateStocks(
        prevBankAgg = prevBankAgg,
        in = in,
        jstDepositChange = jstDepositChange,
        investNetDepositFlow = investNetDepositFlow,
        quasiFiscalDepositChange = quasiFiscalDepositChange,
        mortgageFlows = mortgageFlows,
        bailInLoss = bailInLoss,
        multiCapDestruction = multiCapDestruction,
        interbankContagionLoss = interbankContagionLoss,
        htmRealizedLoss = htmRealizedLoss,
        bankCapitalTerms = bankCapitalTerms,
      )
      val actualDeposits = financialStocks.iterator.map(_.totalDeposits).sumPln
      val actualCapital  = banks.iterator.map(_.capital).sumPln
      val depResidual    = target.depositsResidual - actualDeposits
      val capResidual    = target.capitalResidual - actualCapital
      if depResidual == PLN.Zero && capResidual == PLN.Zero then
        AggregateReconciliationResult(
          banks,
          financialStocks,
          bankCorpBondHoldings,
          PLN.Zero,
          PLN.Zero,
          PLN.Zero,
          PLN.Zero,
          0,
          Vector.empty,
          false,
          BankReconciliationDiagnostics.zero,
        )
      else
        val targetCorpBonds = (bankId: BankId) => bankCorpBondHoldings.lift(bankId.toInt).getOrElse(PLN.Zero)
        val patched         = applyPatch(
          banks = banks,
          financialStocks = financialStocks,
          bankCorpBondHoldings = bankCorpBondHoldings,
          depositResidual = depResidual,
          capitalResidual = capResidual,
          ccyb = in.priceEquity.newMacropru.ccyb,
          carCounterBase = in.banks,
        )
        val failCheck       =
          Banking.checkFailuresWithCarCounterBase(
            patched.banks,
            patched.financialStocks,
            in.fiscal.m,
            true,
            in.priceEquity.newMacropru.ccyb,
            targetCorpBonds,
            in.banks,
          )
        if !failCheck.anyFailed then
          AggregateReconciliationResult(
            patched.banks,
            patched.financialStocks,
            bankCorpBondHoldings,
            depResidual,
            capResidual,
            PLN.Zero,
            PLN.Zero,
            0,
            Vector.empty,
            false,
            patched.bankReconciliationDiagnostics,
          )
        else
          val failedBankIds           = failCheck.events.map(_.bankId).toSet
          val banksAfterFailureCheck  =
            patched.banks.zip(failCheck.banks).map { case (before, after) =>
              if before.id == after.id then after else before
            }
          val bailIn                  = Banking.applyBailIn(banksAfterFailureCheck, patched.financialStocks, failedBankIds)
          val resolved                = Banking.resolveFailures(bailIn.banks, bailIn.financialStocks, bankCorpBondHoldings)
          val capitalDestructionDelta = failCheck.events.map(event => patched.banks(event.bankId.toInt).capital).sumPln
          AggregateReconciliationResult(
            resolved.banks,
            resolved.financialStocks,
            resolved.bankCorpBondHoldings,
            depResidual,
            capResidual,
            bailIn.totalLoss,
            capitalDestructionDelta,
            failCheck.events.length,
            failCheck.events,
            resolved.allFailedFallbackUsed,
            patched.bankReconciliationDiagnostics,
            resolvedBanksDelta = resolved.resolvedBankCount,
          )

  /** Applies aggregate exactness residuals across live banks instead of using
    * one arbitrary row as the sector-level accounting sink.
    */
  def applyPatch(
      banks: Vector[Banking.BankState],
      financialStocks: Vector[Banking.BankFinancialStocks],
      bankCorpBondHoldings: Vector[PLN],
      depositResidual: PLN,
      capitalResidual: PLN,
      ccyb: Multiplier,
      carCounterBase: Vector[Banking.BankState],
  )(using p: SimParams): ReconciliationPatchResult =
    require(
      carCounterBase.length == banks.length,
      s"BankAggregateReconciliation patch requires aligned CAR counter base, got ${banks.length} banks and ${carCounterBase.length} counter rows",
    )
    val patchIndices =
      val active = banks.indices.filter(index => !banks(index).failed).toVector
      if active.nonEmpty then active else banks.indices.toVector

    if patchIndices.isEmpty || (depositResidual == PLN.Zero && capitalResidual == PLN.Zero) then
      ReconciliationPatchResult(banks, financialStocks, BankReconciliationDiagnostics.zero)
    else
      val depositWeights     = positiveOrEqualWeights(patchIndices.map(index => financialStocks(index).totalDeposits.toLong.max(0L)).toArray)
      val capitalWeights     =
        val positiveCapital = patchIndices.map(index => banks(index).capital.toLong.max(0L)).toArray
        val capitalTotal    = positiveCapital.sum
        if capitalTotal > 0L then positiveCapital else depositWeights
      val depositAllocations = distributeSignedResidual(depositResidual, depositWeights)
      val capitalAllocations = distributeSignedResidual(capitalResidual, capitalWeights)
      val nextBanks          = banks.toArray
      val nextStocks         = financialStocks.toArray

      var i = 0
      while i < patchIndices.length do
        val index       = patchIndices(i)
        val depositMove = depositAllocations(i)
        val capitalMove = capitalAllocations(i)
        if depositMove != PLN.Zero then
          val newDeposits = nextStocks(index).totalDeposits + depositMove
          require(
            newDeposits >= PLN.Zero,
            s"BankAggregateReconciliation deposit patch would make bank ${banks(index).id} deposits negative: current=${nextStocks(index).totalDeposits}, move=$depositMove, aggregateResidual=$depositResidual",
          )
          nextStocks(index) = nextStocks(index).copy(
            totalDeposits = newDeposits,
            demandDeposit = newDeposits * (Share.One - p.banking.termDepositFrac),
            termDeposit = newDeposits * p.banking.termDepositFrac,
          )
        if capitalMove != PLN.Zero then nextBanks(index) = nextBanks(index).copy(capital = nextBanks(index).capital + capitalMove)
        i += 1

      val nextBanksVector  = nextBanks.toVector
      val nextStocksVector = nextStocks.toVector
      val targetPosition   = mostImpactedPatchPosition(capitalAllocations, depositAllocations)
      val targetIndex      = patchIndices(targetPosition)
      val corpBondLookup   = (bankId: BankId) => bankCorpBondHoldings.lift(bankId.toInt).getOrElse(PLN.Zero)
      val crossedFailure   =
        var crossed = false
        var j       = 0
        while j < patchIndices.length && !crossed do
          val index        = patchIndices(j)
          val reasonBefore = failureReasonFromCarCounterBase(banks(index), financialStocks(index), carCounterBase(index), ccyb, corpBondLookup)
          val reasonAfter  = failureReasonFromCarCounterBase(nextBanksVector(index), nextStocksVector(index), carCounterBase(index), ccyb, corpBondLookup)
          crossed = reasonBefore.isEmpty && reasonAfter.nonEmpty
          j += 1
        crossed
      val beforeBank       = banks(targetIndex)
      val beforeStocks     = financialStocks(targetIndex)
      val afterBank        = nextBanksVector(targetIndex)
      val afterStocks      = nextStocksVector(targetIndex)
      val diagnostics      = BankReconciliationDiagnostics.fromDistributedPatch(
        targetBankId = beforeBank.id,
        targetCapitalAllocation = capitalAllocations(targetPosition),
        targetCapitalBefore = beforeBank.capital,
        targetCapitalAfter = afterBank.capital,
        targetCarBefore = Banking.car(beforeBank, beforeStocks, corpBondLookup(beforeBank.id)),
        targetCarAfter = Banking.car(afterBank, afterStocks, corpBondLookup(afterBank.id)),
        crossedFailureThreshold = crossedFailure,
        targetReasonAfter = failureReasonFromCarCounterBase(afterBank, afterStocks, carCounterBase(targetIndex), ccyb, corpBondLookup),
      )
      ReconciliationPatchResult(nextBanksVector, nextStocksVector, diagnostics)

  private def failureReasonFromCarCounterBase(
      bank: Banking.BankState,
      financialStocks: Banking.BankFinancialStocks,
      carCounterBase: Banking.BankState,
      ccyb: Multiplier,
      bankCorpBondHoldings: Banking.BankCorpBondHoldings,
  )(using p: SimParams): Option[Banking.BankFailureReason] =
    val bankAtOpeningCounter =
      if bank.failed then bank else bank.copy(status = Banking.BankStatus.Active(carCounterBase.consecutiveLowCar))
    Banking.failureReason(bankAtOpeningCounter, financialStocks, ccyb, bankCorpBondHoldings)

  private def positiveOrEqualWeights(weights: Array[Long]): Array[Long] =
    if weights.exists(_ > 0L) then weights.map(_.max(0L))
    else Array.fill(weights.length)(1L)

  private def distributeSignedResidual(amount: PLN, weights: Array[Long]): Array[PLN] =
    if amount == PLN.Zero || weights.isEmpty then Array.fill(weights.length)(PLN.Zero)
    else
      val magnitudes = Distribute.distribute(amount.abs.toLong, positiveOrEqualWeights(weights))
      val negative   = amount < PLN.Zero
      magnitudes.map(raw => PLN.fromRaw(if negative then -raw else raw))

  private def mostImpactedPatchPosition(capitalAllocations: Array[PLN], depositAllocations: Array[PLN]): Int =
    val preferCapital = capitalAllocations.exists(_ != PLN.Zero)
    val values        = if preferCapital then capitalAllocations else depositAllocations
    var bestIndex     = 0
    var bestAbs       = PLN.Zero
    var i             = 0
    while i < values.length do
      val currentAbs = values(i).abs
      if currentAbs > bestAbs then
        bestAbs = currentAbs
        bestIndex = i
      i += 1
    bestIndex

  private def targetAggregateStocks(
      prevBankAgg: Banking.Aggregate,
      in: StepInput,
      jstDepositChange: PLN,
      investNetDepositFlow: PLN,
      quasiFiscalDepositChange: PLN,
      mortgageFlows: HousingMarket.MortgageFlows,
      bailInLoss: PLN,
      multiCapDestruction: PLN,
      interbankContagionLoss: PLN,
      htmRealizedLoss: PLN,
      bankCapitalTerms: BankCapitalTerms,
  )(using p: SimParams): AggregateReconciliation =
    val capitalLosses  = in.firm.nplLoss + mortgageFlows.defaultLoss + in.householdFinancial.consumerNplLoss +
      in.openEconomy.corpBonds.corpBondBankDefaultLoss +
      Banking.computeBfgLevy(in.banks, in.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks)).total +
      interbankContagionLoss + bankCapitalTerms.unrealizedBondLoss + htmRealizedLoss +
      bankCapitalTerms.eclProvisionChange + multiCapDestruction
    val targetCapital  = prevBankAgg.capital - capitalLosses + bankCapitalTerms.retainedIncome
    val targetDeposits = prevBankAgg.deposits + in.householdIncome.totalIncome - in.householdIncome.consumption +
      investNetDepositFlow + jstDepositChange + quasiFiscalDepositChange + in.priceEquity.netDomesticDividends -
      in.priceEquity.foreignDividendOutflow - in.householdFinancial.remittanceOutflow + in.householdFinancial.diasporaInflow +
      in.householdFinancial.tourismExport - in.householdFinancial.tourismImport - bailInLoss + in.firm.sumNewLoans -
      in.firm.sumFirmPrincipal + in.householdFinancial.consumerOrigination + in.openEconomy.nonBank.insNetDepositChange +
      in.openEconomy.nonBank.nbfiDepositDrain
    AggregateReconciliation(
      depositsResidual = targetDeposits,
      capitalResidual = targetCapital,
    )
