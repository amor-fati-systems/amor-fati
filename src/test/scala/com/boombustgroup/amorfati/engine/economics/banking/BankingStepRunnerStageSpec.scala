package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth
import com.boombustgroup.amorfati.engine.diagnostics.banking.BankReconciliationDiagnostics
import com.boombustgroup.amorfati.engine.markets.CorporateBondMarket
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

  private def stocks(deposits: PLN, firmLoan: PLN = PLN.Zero): Banking.BankFinancialStocks =
    Banking.BankFinancialStocks(
      totalDeposits = deposits,
      firmLoan = firmLoan,
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

  "BankMultiBankStage" should "remove gross firm defaults from the regular firm-loan book" in {
    BankMultiBankStage.closingFirmLoanBook(
      openingFirmLoan = PLN(1000),
      newLoans = PLN(100),
      principalRepaid = PLN(200),
      grossDefault = PLN(300),
    ) shouldBe PLN(600)
  }

  "BankFailurePipeline.runBailIn" should "return an explicit bail-in stage output for eligible failed banks only" in {
    val rows        = Vector(bank(0, failed = true), bank(1))
    val stockRows   = Vector(stocks(PLN(1000000)), stocks(PLN(1000000)))
    val failedBank  = BankId(0)
    val failure     = failureDetection(rows, stockRows, Set(failedBank), Vector(failureEvent(failedBank)))
    val result      = BankFailurePipeline.runBailIn(failure)
    val expectedCut = (stockRows.head.totalDeposits - p.banking.bfgDepositGuarantee) * p.banking.bailInDepositHaircut

    result.eligibleBankIds shouldBe Set(failedBank)
    result.loss shouldBe expectedCut
    result.financialStocks.head.totalDeposits shouldBe stockRows.head.totalDeposits - expectedCut
    result.financialStocks(1).totalDeposits shouldBe stockRows(1).totalDeposits
  }

  "BankFailurePipeline.runBankResolution" should "return a named purchase-and-assumption resolution output" in {
    val rows         = Vector(bank(0, failed = true), bank(1))
    val stockRows    = Vector(stocks(PLN(1000000)), stocks(PLN(2000000)))
    val failedBank   = BankId(0)
    val failure      = failureDetection(rows, stockRows, Set(failedBank), Vector(failureEvent(failedBank)))
    val bailIn       = BankFailurePipeline.runBailIn(failure)
    val resolved     = BankFailurePipeline.runBankResolution(failure, bailIn, Vector(PLN.Zero, PLN.Zero))
    val transferred  = bailIn.financialStocks.head.totalDeposits
    val survivorCash = stockRows(1).totalDeposits + transferred

    resolved.resolvedBankCount shouldBe 1
    resolved.absorberBankId shouldBe BankId(1)
    resolved.financialStocks.head.totalDeposits shouldBe PLN.Zero
    resolved.financialStocks(1).totalDeposits shouldBe survivorCash
  }

  "BankFailurePipeline.reconcileResolution" should "combine explicit resolution and reconciliation deltas without hiding their source" in {
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

    val result = BankFailurePipeline.reconcileResolution(failure, bailIn, resolution, patch)

    result.bailInLoss shouldBe PLN(15)
    result.capitalDestruction shouldBe failure.capitalDestruction + PLN(7)
    result.newFailures shouldBe 2
    result.failureEvents shouldBe Vector(firstEvent, extraEvent)
    result.resolvedBankCount shouldBe 2
    result.allFailedFallbackUsed shouldBe true
    result.capitalReconciliationResidual shouldBe PLN(3)
  }

  "BankAggregateReconciliation.applyPatch" should "spread sector capital residuals across active banks" in {
    val rows      = Vector(
      bank(0, capital = PLN(1000)),
      bank(1, capital = PLN(1000)),
      bank(2, failed = true, capital = PLN.Zero),
    )
    val stockRows = Vector(stocks(PLN(1000)), stocks(PLN(1000)), stocks(PLN.Zero))

    val result = BankAggregateReconciliation.applyPatch(
      banks = rows,
      financialStocks = stockRows,
      bankCorpBondHoldings = Vector(PLN.Zero, PLN.Zero, PLN.Zero),
      depositResidual = PLN.Zero,
      capitalResidual = PLN(-400),
      ccyb = Multiplier.Zero,
      carCounterBase = rows,
    )

    result.banks.map(_.capital) shouldBe Vector(PLN(800), PLN(800), PLN.Zero)
    result.bankReconciliationDiagnostics.capitalResidual shouldBe PLN(-200)
    result.bankReconciliationDiagnostics.targetBankId should (be(0) or be(1))
  }

  it should "avoid creating an artificial single-bank CAR breach from a sector residual" in {
    val rows      = Vector(
      bank(0, capital = PLN(100)).copy(loansLong = PLN(1000), status = Banking.BankStatus.Active(2)),
      bank(1, capital = PLN(100)).copy(loansLong = PLN(1000), status = Banking.BankStatus.Active(2)),
    )
    val stockRows = Vector(stocks(PLN(1000), firmLoan = PLN(1000)), stocks(PLN(1000), firmLoan = PLN(1000)))

    val result = BankAggregateReconciliation.applyPatch(
      banks = rows,
      financialStocks = stockRows,
      bankCorpBondHoldings = Vector(PLN.Zero, PLN.Zero),
      depositResidual = PLN.Zero,
      capitalResidual = PLN(-20),
      ccyb = Multiplier.Zero,
      carCounterBase = rows,
    )

    result.banks.map(_.capital) shouldBe Vector(PLN(90), PLN(90))
    result.bankReconciliationDiagnostics.crossedFailureThreshold shouldBe 0
  }

  it should "fail fast when a deposit patch would make a bank balance negative" in {
    val rows      = Vector(bank(0))
    val stockRows = Vector(stocks(PLN(100)))

    an[IllegalArgumentException] shouldBe thrownBy {
      BankAggregateReconciliation.applyPatch(
        banks = rows,
        financialStocks = stockRows,
        bankCorpBondHoldings = Vector(PLN.Zero),
        depositResidual = PLN(-101),
        capitalResidual = PLN.Zero,
        ccyb = Multiplier.Zero,
        carCounterBase = rows,
      )
    }
  }

  "BankBondWaterfall.settleCorpBondHoldings" should "fail fast when bank dimensions are misaligned" in {
    an[IllegalArgumentException] shouldBe thrownBy {
      BankBondWaterfall.settleCorpBondHoldings(
        previous = Vector(PLN.Zero),
        previousAggregateStock = CorporateBondMarket.StockState.zero,
        nextAggregateStock = CorporateBondMarket.StockState.zero,
        totalBondIssuance = PLN.Zero,
        perBankWorkers = Vector.empty,
      )
    }
  }

  "BankInterbankSettlement.applyNbpReserveSettlement" should "fail fast when per-bank rows are misaligned" in {
    val zeroAmounts = Banking.PerBankAmounts(Vector(PLN.Zero), PLN.Zero)

    an[IllegalArgumentException] shouldBe thrownBy {
      BankInterbankSettlement.applyNbpReserveSettlement(
        banks = Vector(bank(0)),
        financialStocks = Vector.empty,
        reserveInterest = zeroAmounts,
        standingFacilityIncome = zeroAmounts,
        interbankInterest = zeroAmounts,
        fxInjection = PLN.Zero,
      )
    }
  }

end BankingStepRunnerStageSpec
