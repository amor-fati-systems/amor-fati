package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth
import com.boombustgroup.amorfati.engine.diagnostics.banking.BankReconciliationDiagnostics
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BankingStepRunnerStageSpec extends AnyFlatSpec with Matchers:

  private given p: SimParams = SimParams.defaults

  private def bank(id: Int, failed: Boolean = false, capital: PLN = PLN(1000000)): Banking.BankState =
    Banking.BankState(
      id = BankId(id),
      capital = capital,
      nplAmount = PLN.Zero,
      htmBookYield = Rate.Zero,
      status = if failed then Banking.BankStatus.Failed(SimulationMonth.ExecutionMonth.First) else Banking.BankStatus.Active(0),
      loansShort = PLN.Zero,
      loansMedium = PLN.Zero,
      loansLong = PLN.Zero,
      consumerNpl = PLN.Zero,
    )

  private def stocks(deposits: PLN): Banking.BankFinancialStocks =
    Banking.BankFinancialStocks(
      totalDeposits = deposits,
      firmLoan = PLN.Zero,
      govBondAfs = PLN.Zero,
      govBondHtm = PLN.Zero,
      reserve = PLN.Zero,
      interbankLoan = PLN.Zero,
      demandDeposit = deposits.max(PLN.Zero),
      termDeposit = PLN.Zero,
      consumerLoan = PLN.Zero,
    )

  private def failureDetection(
      banks: Vector[Banking.BankState],
      financialStocks: Vector[Banking.BankFinancialStocks],
      failedBankIds: Set[BankId],
      events: Vector[Banking.FailureEvent],
  ): FailureDetectionResult =
    FailureDetectionResult(
      banks = banks,
      financialStocks = financialStocks,
      primary = Banking.FailureCheckResult(banks, failedBankIds.nonEmpty, events),
      secondary = Banking.FailureCheckResult(banks, anyFailed = false),
      contagion = InterbankContagion.ContagionLossResult.unchanged(banks),
      failedBankIds = failedBankIds,
      anyFailed = failedBankIds.nonEmpty,
      newFailures = failedBankIds.size,
      capitalDestruction = events.map(event => banks(event.bankId.toInt).capital).sumPln,
      events = events,
    )

  private def failureEvent(bankId: BankId): Banking.FailureEvent =
    Banking.FailureEvent(bankId, SimulationMonth.ExecutionMonth.First, Banking.BankFailureReason.NegativeCapital)

  "BankingStepRunner.runBailIn" should "return an explicit bail-in stage output for eligible failed banks only" in {
    val rows        = Vector(bank(0, failed = true), bank(1))
    val stockRows   = Vector(stocks(PLN(1000000)), stocks(PLN(1000000)))
    val failedBank  = BankId(0)
    val failure     = failureDetection(rows, stockRows, Set(failedBank), Vector(failureEvent(failedBank)))
    val result      = BankingStepRunner.runBailIn(failure)
    val expectedCut = (stockRows.head.totalDeposits - p.banking.bfgDepositGuarantee) * p.banking.bailInDepositHaircut

    result.eligibleBankIds shouldBe Set(failedBank)
    result.loss shouldBe expectedCut
    result.financialStocks.head.totalDeposits shouldBe stockRows.head.totalDeposits - expectedCut
    result.financialStocks(1).totalDeposits shouldBe stockRows(1).totalDeposits
  }

  "BankingStepRunner.runBankResolution" should "return a named purchase-and-assumption resolution output" in {
    val rows         = Vector(bank(0, failed = true), bank(1))
    val stockRows    = Vector(stocks(PLN(1000000)), stocks(PLN(2000000)))
    val failedBank   = BankId(0)
    val failure      = failureDetection(rows, stockRows, Set(failedBank), Vector(failureEvent(failedBank)))
    val bailIn       = BankingStepRunner.runBailIn(failure)
    val resolved     = BankingStepRunner.runBankResolution(failure, bailIn, Vector(PLN.Zero, PLN.Zero))
    val transferred  = bailIn.financialStocks.head.totalDeposits
    val survivorCash = stockRows(1).totalDeposits + transferred

    resolved.resolvedBankCount shouldBe 1
    resolved.absorberBankId shouldBe BankId(1)
    resolved.financialStocks.head.totalDeposits shouldBe PLN.Zero
    resolved.financialStocks(1).totalDeposits shouldBe survivorCash
  }

  "BankingStepRunner.reconcileResolution" should "combine explicit resolution and reconciliation deltas without hiding their source" in {
    val rows       = Vector(bank(0, failed = true), bank(1))
    val stockRows  = Vector(stocks(PLN.Zero), stocks(PLN(2000000)))
    val firstEvent = failureEvent(BankId(0))
    val extraEvent = failureEvent(BankId(1))
    val failure    = failureDetection(rows, stockRows, Set(BankId(0)), Vector(firstEvent))
    val bailIn     = BailInStageResult(rows, stockRows, Set(BankId(0)), loss = PLN(10))
    val resolution = BankResolutionStageResult(
      banks = rows,
      financialStocks = stockRows,
      bankCorpBondHoldings = Vector(PLN.Zero, PLN.Zero),
      absorberBankId = BankId(1),
      resolvedBankCount = 1,
      allFailedFallbackUsed = false,
    )
    val patch      = AggregateReconciliationResult(
      banks = rows,
      financialStocks = stockRows,
      bankCorpBondHoldings = Vector(PLN.Zero, PLN.Zero),
      depositResidual = PLN.Zero,
      capitalResidual = PLN(3),
      bailInLossDelta = PLN(5),
      capitalDestructionDelta = PLN(7),
      newFailuresDelta = 1,
      failureEvents = Vector(extraEvent),
      allFailedFallbackUsed = true,
      bankReconciliationDiagnostics = BankReconciliationDiagnostics.zero,
      resolvedBanksDelta = 1,
    )

    val result = BankingStepRunner.reconcileResolution(failure, bailIn, resolution, patch)

    result.bailInLoss shouldBe PLN(15)
    result.capitalDestruction shouldBe failure.capitalDestruction + PLN(7)
    result.newFailures shouldBe 2
    result.failureEvents shouldBe Vector(firstEvent, extraEvent)
    result.resolvedBankCount shouldBe 2
    result.allFailedFallbackUsed shouldBe true
    result.capitalReconciliationResidual shouldBe PLN(3)
  }

end BankingStepRunnerStageSpec
