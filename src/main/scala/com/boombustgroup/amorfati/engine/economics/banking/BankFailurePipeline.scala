package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.markets.HousingMarket
import com.boombustgroup.amorfati.types.*

/** Failure detection, depositor bail-in, purchase-and-assumption resolution,
  * and post-resolution aggregate exactness reconciliation.
  */
private[banking] object BankFailurePipeline:

  /** Runs the failure-resolution subpipeline after bond allocation has produced
    * the settled bank portfolios used for failure checks and exactness repair.
    */
  def run(
      in: StepInput,
      prevBankAgg: Banking.Aggregate,
      waterfall: BondWaterfallResult,
      settledBankCorpBonds: Vector[PLN],
      jstDepositChange: PLN,
      investNetDepositFlow: PLN,
      quasiFiscalDepositChange: PLN,
      mortgageFlows: HousingMarket.MortgageFlows,
      htmRealizedLoss: PLN,
  )(using p: SimParams): FailureResolutionPipelineResult =
    val bankCorpBondHoldingsAfterSettlement = Banking.bankCorpBondHoldingsFromVector(settledBankCorpBonds)
    val failureDetection                    = runFailureDetection(in, waterfall, bankCorpBondHoldingsAfterSettlement)
    val bailIn                              = runBailIn(failureDetection)
    val bankResolution                      = runBankResolution(failureDetection, bailIn, settledBankCorpBonds)
    val bankCapitalTerms                    = computeBankCapitalTerms(prevBankAgg, waterfall.banks, in, mortgageFlows)
    val aggregateReconciliation             = BankAggregateReconciliation.reconcile(
      banks = bankResolution.banks,
      financialStocks = bankResolution.financialStocks,
      bankCorpBondHoldings = bankResolution.bankCorpBondHoldings,
      prevBankAgg = prevBankAgg,
      in = in,
      jstDepositChange = jstDepositChange,
      investNetDepositFlow = investNetDepositFlow,
      quasiFiscalDepositChange = quasiFiscalDepositChange,
      mortgageFlows = mortgageFlows,
      bailInLoss = bailIn.loss,
      multiCapDestruction = failureDetection.capitalDestruction,
      interbankContagionLoss = failureDetection.contagion.totalLoss,
      htmRealizedLoss = htmRealizedLoss,
      bankCapitalTerms = bankCapitalTerms,
    )
    val finalResolution                     = reconcileResolution(failureDetection, bailIn, bankResolution, aggregateReconciliation)

    FailureResolutionPipelineResult(
      failureDetection = failureDetection,
      bailIn = bailIn,
      bankResolution = bankResolution,
      aggregateReconciliation = aggregateReconciliation,
      finalResolution = finalResolution,
      bankCapitalTerms = bankCapitalTerms,
    )

  /** Detects primary failures, applies interbank contagion losses, and then
    * performs the secondary failure check caused by those counterparty losses.
    */
  def runFailureDetection(
      in: StepInput,
      waterfall: BondWaterfallResult,
      bankCorpBondHoldings: Banking.BankCorpBondHoldings,
  )(using p: SimParams): FailureDetectionResult =
    val carCounterBase = waterfall.banks
    val primary        =
      Banking.checkFailuresWithCarCounterBase(
        waterfall.banks,
        waterfall.financialStocks,
        in.fiscal.m,
        true,
        in.priceEquity.newMacropru.ccyb,
        bankCorpBondHoldings,
        carCounterBase,
      )
    val exposures      = InterbankContagion.buildExposureMatrix(waterfall.banks, waterfall.financialStocks)
    val contagion      =
      if primary.anyFailed then InterbankContagion.applyContagionLosses(primary.banks, exposures)
      else InterbankContagion.ContagionLossResult.unchanged(primary.banks)
    val secondary      =
      if primary.anyFailed then
        Banking.checkFailuresWithCarCounterBase(
          contagion.banks,
          waterfall.financialStocks,
          in.fiscal.m,
          true,
          in.priceEquity.newMacropru.ccyb,
          bankCorpBondHoldings,
          carCounterBase,
        )
      else primary
    val anyFailed      = primary.anyFailed || secondary.anyFailed
    val events         = primary.events ++ secondary.events

    val newFailures                 =
      if anyFailed then waterfall.banks.zip(secondary.banks).count { case (pre, post) => !pre.failed && post.failed } else 0
    val primaryCapitalDestruction   =
      waterfall.banks
        .zip(primary.banks)
        .collect { case (pre, post) if !pre.failed && post.failed => pre.capital }
        .sumPln
    val secondaryCapitalDestruction =
      contagion.banks
        .zip(secondary.banks)
        .collect { case (pre, post) if !pre.failed && post.failed => pre.capital }
        .sumPln

    FailureDetectionResult(
      banks = secondary.banks,
      financialStocks = waterfall.financialStocks,
      primary = primary,
      secondary = secondary,
      contagion = contagion,
      failedBankIds = events.map(_.bankId).toSet,
      anyFailed = anyFailed,
      newFailures = newFailures,
      capitalDestruction = if anyFailed then primaryCapitalDestruction + secondaryCapitalDestruction else PLN.Zero,
      events = events,
    )

  /** Applies the depositor-side BRRD bail-in leg for banks that entered failure
    * in this month.
    */
  def runBailIn(failure: FailureDetectionResult)(using p: SimParams): BailInStageResult =
    val result =
      if failure.failedBankIds.nonEmpty then Banking.applyBailIn(failure.banks, failure.financialStocks, failure.failedBankIds)
      else Banking.BailInResult(failure.banks, failure.financialStocks, PLN.Zero)
    BailInStageResult(
      banks = result.banks,
      financialStocks = result.financialStocks,
      eligibleBankIds = failure.failedBankIds,
      loss = result.totalLoss,
    )

  /** Resolves newly failed banks after bail-in through the purchase-and-
    * assumption path, preserving explicit bank shell slots.
    */
  def runBankResolution(
      failure: FailureDetectionResult,
      bailIn: BailInStageResult,
      bankCorpBondHoldings: Vector[PLN],
  ): BankResolutionStageResult =
    val result =
      if failure.anyFailed then Banking.resolveFailures(bailIn.banks, bailIn.financialStocks, bankCorpBondHoldings)
      else Banking.ResolutionResult(bailIn.banks, bailIn.financialStocks, BankId.NoBank, bankCorpBondHoldings)
    BankResolutionStageResult(
      banks = result.banks,
      financialStocks = result.financialStocks,
      bankCorpBondHoldings = result.bankCorpBondHoldings,
      absorberBankId = result.absorberId,
      resolvedBankCount = result.resolvedBankCount,
      allFailedFallbackUsed = result.allFailedFallbackUsed,
    )

  /** Combines the explicit resolution path with the aggregate exactness
    * reconciliation, which may itself create additional failure events.
    */
  def reconcileResolution(
      failure: FailureDetectionResult,
      bailIn: BailInStageResult,
      resolution: BankResolutionStageResult,
      reconciled: AggregateReconciliationResult,
  ): ReconciledResolutionResult =
    ReconciledResolutionResult(
      banks = reconciled.banks,
      financialStocks = reconciled.financialStocks,
      bankCorpBondHoldings = reconciled.bankCorpBondHoldings,
      bailInLoss = bailIn.loss + reconciled.bailInLossDelta,
      capitalDestruction = failure.capitalDestruction + reconciled.capitalDestructionDelta,
      newFailures = failure.newFailures + reconciled.newFailuresDelta,
      failureEvents = failure.events ++ reconciled.failureEvents,
      resolvedBankCount = resolution.resolvedBankCount + reconciled.resolvedBanksDelta,
      allFailedFallbackUsed = resolution.allFailedFallbackUsed || reconciled.allFailedFallbackUsed,
      bankReconciliationDiagnostics = reconciled.bankReconciliationDiagnostics,
      capitalReconciliationResidual = reconciled.capitalResidual,
    )

  private def computeBankCapitalTerms(
      prevBankAgg: Banking.Aggregate,
      bankRows: Vector[Banking.BankState],
      in: StepInput,
      mortgageFlows: HousingMarket.MortgageFlows,
  )(using p: SimParams): BankCapitalTerms =
    require(
      bankRows.length == in.banks.length,
      s"BankFailurePipeline bank-capital terms require aligned bank rows, got ${bankRows.length} post-update banks and ${in.banks.length} opening banks",
    )
    val yieldChange        = in.openEconomy.monetary.newBondYield - in.world.gov.bondYield
    val unrealizedBondLoss =
      if yieldChange > Rate.Zero then prevBankAgg.afsBonds * yieldChange * p.banking.govBondDuration
      else PLN.Zero
    var eclRaw             = 0L
    var i                  = 0
    while i < bankRows.length do
      val curr     = bankRows(i)
      val prev     = in.banks(i)
      val currProv = EclStaging.allowance(curr.eclStaging)
      val prevProv = EclStaging.allowance(prev.eclStaging)
      eclRaw += (currProv - prevProv).toLong
      i += 1
    val eclProvisionChange = PLN.fromRaw(eclRaw)
    val capitalGrossIncome = in.firm.intIncome +
      prevBankAgg.govBondHoldings * in.openEconomy.monetary.newBondYield.monthly -
      in.householdFinancial.depositInterestPaid + in.openEconomy.banking.totalReserveInterest +
      in.openEconomy.banking.totalStandingFacilityIncome + in.openEconomy.banking.totalInterbankInterest +
      mortgageFlows.interest + (in.householdFinancial.consumerDebtService - in.householdFinancial.consumerPrincipal) +
      in.openEconomy.corpBonds.corpBondBankCoupon
    BankCapitalTerms(
      unrealizedBondLoss = unrealizedBondLoss,
      eclProvisionChange = eclProvisionChange,
      capitalGrossIncome = capitalGrossIncome,
      retainedIncome = capitalGrossIncome * p.banking.profitRetention,
    )
