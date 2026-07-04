package com.boombustgroup.amorfati.montecarlo.snapshots

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.flows.FlowSimulation
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.init.{InitRandomness, WorldInit}
import com.boombustgroup.amorfati.montecarlo.runner.{McTerminalSummaryId, McTerminalSummarySchema}
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class McHouseholdLiquidityDiagnosticsSpec extends AnyFlatSpec with Matchers:

  given SimParams = SimParams.defaults

  private def balance(deposit: PLN): LedgerFinancialState.HouseholdBalances =
    LedgerFinancialState.HouseholdBalances(
      demandDeposit = deposit,
      mortgageLoan = PLN.Zero,
      consumerLoan = PLN.Zero,
      equity = PLN.Zero,
    )

  private val balances = Vector(
    balance(PLN(-100)),
    balance(PLN.Zero),
    balance(PLN(50)),
    balance(PLN(200)),
  )

  "McHouseholdLiquidityDiagnostics" should "split positive deposits from implicit overdrafts and report percentiles" in {
    val summary = McHouseholdLiquidityDiagnostics.fromBalances(balances)

    summary.netDemandDeposit shouldBe PLN(150)
    summary.positiveDemandDeposits shouldBe PLN(250)
    summary.implicitOverdraft shouldBe PLN(100)
    summary.negativeDepositCount shouldBe 1
    summary.negativeDepositShare shouldBe Share.fraction(1, 4)
    summary.minDemandDeposit shouldBe PLN(-100)
    summary.depositP01 shouldBe PLN(-100)
    summary.depositP05 shouldBe PLN(-100)
    summary.depositP10 shouldBe PLN(-100)
    summary.depositP25 shouldBe PLN.Zero
    summary.depositP50 shouldBe PLN(50)
    summary.depositP75 shouldBe PLN(200)
    summary.depositP90 shouldBe PLN(200)
    summary.depositP95 shouldBe PLN(200)
    summary.depositP99 shouldBe PLN(200)
  }

  it should "append household liquidity diagnostics to terminal household summary rows" in {
    val init  = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
    val state = FlowSimulation.SimState
      .fromInit(init)
      .copy(ledgerFinancialState = init.ledgerFinancialState.copy(households = balances))

    val spec   = McTerminalSummarySchema.specs.find(_.id == McTerminalSummaryId.Household).getOrElse(fail("missing household summary spec"))
    val header = spec.tsvSchema.header.split("\t").toVector
    val row    = McTerminalSummarySchema.fromTerminalState(1L, state).rowsFor(McTerminalSummaryId.Household).head.split("\t").toVector

    def field(name: String): String =
      val idx = header.indexOf(name)
      idx.should(be >= 0)
      row(idx)

    field("MeanSavings") should not be empty
    field("MedianSavings") should not be empty
    field("HouseholdLiquidity_NetDemandDeposit") shouldBe "150.00"
    field("HouseholdLiquidity_PositiveDemandDeposits") shouldBe "250.00"
    field("HouseholdLiquidity_ImplicitOverdraft") shouldBe "100.00"
    field("HouseholdLiquidity_NegativeDepositCount") shouldBe "1"
    field("HouseholdLiquidity_NegativeDepositShare") shouldBe "0.250000"
    field("HouseholdLiquidity_MinDemandDeposit") shouldBe "-100.00"
    field("HouseholdLiquidity_DepositP50") shouldBe "50.00"
    field("HouseholdLiquidity_DepositP99") shouldBe "200.00"
  }
