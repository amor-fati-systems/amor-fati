package com.boombustgroup.amorfati.integration.engine

import com.boombustgroup.amorfati.accounting.{InitCheck, Sfc}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.{MonthDriver, MonthRandomness}
import com.boombustgroup.amorfati.engine.flows.FlowSimulation
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.init.{InitRandomness, WorldInit}
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** PR-level integration gate for normal-path engine health.
  *
  * This deliberately avoids TSV schemas and output-file checks. The contract is
  * the runtime path itself: initialize a baseline world, execute a short
  * multi-month path through the shared month driver, and assert hard accounting
  * and balance-sheet invariants after every realized month.
  */
class BaselineInvariantIntegrationSpec extends AnyFlatSpec with Matchers:

  private given SimParams = SimParams.defaults

  private val Seeds  = Vector(1L, 2L)
  private val Months = 12
  // Blow-up guard for PR health checks, not a calibration target.
  private val MaxTotalCreditToGdp = Scalar(10)

  "Baseline engine path" should "preserve non-TSV runtime invariants over a short PR horizon" in
    Seeds.foreach: seed =>
      var state = initialState(seed)
      assertOpeningStockConsistency(seed, state)

      val steps = MonthDriver
        .unfoldStepResults(state): boundary =>
          Some(MonthRandomness.Contract.fromSeed(runtimeRootSeed(seed, boundary)))
        .take(Months)

      var observedMonths = 0
      steps.foreach:
        case Left(failure) =>
          fail(s"seed=$seed failed before completing baseline horizon: ${failure.diagnosticMessage}")
        case Right(result) =>
          observedMonths += 1
          val month = result.executionMonth
          withClue(s"seed=$seed month=${month.toInt}: ") {
            result.execution.netDelta shouldBe 0L
            result.sfcResult shouldBe Right(())
            result.trace.validations.flatMap(_.failures) shouldBe empty
            assertNoBankFailures(result)
            assertBankRouting(seed, result.nextState)
            assertCriticalStocks(seed, result.nextState)
            assertBoundedMacroRatios(result.nextState)
          }
          state = result.nextState

      observedMonths shouldBe Months
      state.completedMonth.toInt shouldBe Months

  private def initialState(seed: Long): FlowSimulation.SimState =
    FlowSimulation.SimState.fromInit(WorldInit.initialize(InitRandomness.Contract.fromSeed(seed)))

  private def runtimeRootSeed(seed: Long, state: FlowSimulation.SimState): Long =
    seed * 10000L + state.completedMonth.toLong

  private def runtimeState(state: FlowSimulation.SimState): Sfc.RuntimeState =
    Sfc.RuntimeState(
      world = state.world,
      firms = state.firms,
      households = state.households,
      banks = state.banks,
      ledgerFinancialState = state.ledgerFinancialState,
    )

  private def assertOpeningStockConsistency(seed: Long, state: FlowSimulation.SimState): Unit =
    val errors = InitCheck.validate(runtimeState(state))
    withClue(s"seed=$seed completedMonth=${state.completedMonth.toInt} stock consistency: ${renderInitErrors(errors)}") {
      errors shouldBe empty
    }

  private def renderInitErrors(errors: Vector[InitCheck.InitCheckResult]): String =
    if errors.isEmpty then "ok"
    else errors.map(error => s"${error.identity}: expected=${error.expected}, actual=${error.actual}").mkString("; ")

  private def assertNoBankFailures(result: FlowSimulation.StepOutput): Unit =
    val failedBanks = result.nextState.banks.filter(_.failed)
    withClue(s"failedBanks=${failedBanks.map(_.id.toInt)}: ") {
      failedBanks shouldBe empty
    }
    result.nextState.world.flows.bankResolution.newFailures.shouldBe(0)
    result.nextState.world.flows.bankFailure.invariantViolation.shouldBe(0)

  private def assertBankRouting(seed: Long, state: FlowSimulation.SimState): Unit =
    state.banks.length shouldBe state.ledgerFinancialState.banks.length
    state.households.foreach: household =>
      withClue(s"seed=$seed household=${household.id.toInt} bankId=${household.bankId.toInt}: ") {
        household.bankId.toInt should (be >= 0 and be < state.banks.length)
      }
    state.firms.foreach: firm =>
      withClue(s"seed=$seed firm=${firm.id.toInt} bankId=${firm.bankId.toInt}: ") {
        firm.bankId.toInt should (be >= 0 and be < state.banks.length)
      }

  private def assertCriticalStocks(seed: Long, state: FlowSimulation.SimState): Unit =
    state.banks.zip(state.ledgerFinancialState.banks).foreach: (bank, stocks) =>
      withClue(s"seed=$seed bank=${bank.id.toInt}: ") {
        assertNonNegative("capital", bank.capital)
        assertNonNegative("nplAmount", bank.nplAmount)
        assertNonNegative("consumerNpl", bank.consumerNpl)
        assertNonNegative("ecl.stage1", bank.eclStaging.stage1)
        assertNonNegative("ecl.stage2", bank.eclStaging.stage2)
        assertNonNegative("ecl.stage3", bank.eclStaging.stage3)
        assertBankStocksNonNegative(stocks)
      }

    state.ledgerFinancialState.households.zipWithIndex.foreach: (stocks, idx) =>
      withClue(s"seed=$seed householdLedger=$idx: ") {
        assertNonNegative("mortgageLoan", stocks.mortgageLoan)
        assertNonNegative("consumerLoan", stocks.consumerLoan)
        assertNonNegative("equity", stocks.equity)
        assertNonNegative("lifeReserveAsset", stocks.lifeReserveAsset)
        assertNonNegative("nonLifeReserveAsset", stocks.nonLifeReserveAsset)
        stocks.mortgageRemainingMonths should be >= 0
      }

    state.ledgerFinancialState.firms.zipWithIndex.foreach: (stocks, idx) =>
      withClue(s"seed=$seed firmLedger=$idx: ") {
        assertNonNegative("firmLoan", stocks.firmLoan)
        assertNonNegative("corpBond", stocks.corpBond)
        assertNonNegative("equity", stocks.equity)
      }

    state.firms.foreach: firm =>
      withClue(s"seed=$seed firm=${firm.id.toInt}: ") {
        assertNonNegative("capitalStock", firm.capitalStock)
        assertNonNegative("inventory", firm.inventory)
        assertNonNegative("greenCapital", firm.greenCapital)
      }

  private def assertBankStocksNonNegative(stocks: LedgerFinancialState.BankBalances): Unit =
    assertNonNegative("totalDeposits", stocks.totalDeposits)
    assertNonNegative("demandDeposit", stocks.demandDeposit)
    assertNonNegative("termDeposit", stocks.termDeposit)
    assertNonNegative("firmLoan", stocks.firmLoan)
    assertNonNegative("consumerLoan", stocks.consumerLoan)
    assertNonNegative("govBondAfs", stocks.govBondAfs)
    assertNonNegative("govBondHtm", stocks.govBondHtm)
    assertNonNegative("reserve", stocks.reserve)
    assertNonNegative("corpBond", stocks.corpBond)
    assertNonNegative("mortgageLoan", stocks.mortgageLoan)
    assertNonNegative("bailedInDeposits", stocks.bailedInDeposits)
    stocks.bailedInDeposits should be <= stocks.totalDeposits

  private def assertNonNegative(name: String, value: PLN): Unit =
    withClue(s"$name=$value: ") {
      value should be >= PLN.Zero
    }

  private def assertBoundedMacroRatios(state: FlowSimulation.SimState): Unit =
    val annualizedGdp = state.world.cachedMonthlyGdpProxy * 12
    state.world.cachedMonthlyGdpProxy should be > PLN.Zero
    val unemployment = state.world.unemploymentRate(state.householdAggregates.employed)
    unemployment should (be >= Share.Zero and be <= Share.One)

    val totalCreditToGdp =
      if annualizedGdp > PLN.Zero then totalCreditStock(state.ledgerFinancialState).ratioTo(annualizedGdp)
      else Scalar.Zero
    totalCreditToGdp should (be >= Scalar.Zero and be <= MaxTotalCreditToGdp)

  private def totalCreditStock(ledgerFinancialState: LedgerFinancialState): PLN =
    ledgerFinancialState.banks.map(_.firmLoan).sumPln +
      ledgerFinancialState.banks.map(_.consumerLoan).sumPln +
      LedgerFinancialState.householdMortgageStock(ledgerFinancialState) +
      ledgerFinancialState.funds.nbfi.nbfiLoanStock
