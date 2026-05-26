package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.init.{InitRandomness, WorldInit}
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HouseholdIncomeEconomicsSpec extends AnyFlatSpec with Matchers:

  private given SimParams = SimParams.defaults

  private val init = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
  private val w    = init.world
  private val s1   = FiscalConstraintEconomics.compute(w, init.banks, init.ledgerFinancialState, ExecutionMonth.First)
  private val s2   = LaborEconomics.compute(w, init.firms, init.households, s1)

  private def compute(pensionIncome: PLN): HouseholdIncomeEconomics.StepOutput =
    HouseholdIncomeEconomics.compute(
      w,
      init.firms,
      init.households,
      init.banks,
      init.ledgerFinancialState,
      s1.lendingBaseRate,
      s1.resWage,
      s2.newWage,
      RandomStream.seeded(99),
      pensionIncome = pensionIncome,
    )

  "HouseholdIncomeEconomics.compute" should "route aggregate pension income into household consumption demand" in {
    val base          = compute(PLN.Zero)
    val pensionIncome = PLN(1000000)
    val withPensions  = compute(pensionIncome)

    val pensionConsumption = pensionIncome * summon[SimParams].household.mpc
    val pensionImports     = pensionConsumption * base.importAdj
    val pensionDomestic    = pensionConsumption - pensionImports

    withPensions.totalIncome shouldBe base.totalIncome + pensionIncome
    withPensions.consumption shouldBe base.consumption + pensionConsumption
    withPensions.importCons shouldBe base.importCons + pensionImports
    withPensions.domesticCons shouldBe base.domesticCons + pensionDomestic
    withPensions.hhAgg.totalIncome shouldBe withPensions.totalIncome
    withPensions.hhAgg.consumption shouldBe withPensions.consumption
    withPensions.hhAgg.importConsumption shouldBe withPensions.importCons
    withPensions.hhAgg.domesticConsumption shouldBe withPensions.domesticCons
  }

  it should "count same-month approved consumer loans against projected bank CAR" in {
    val bank   = Banking.BankState(
      id = BankId(0),
      capital = PLN(150),
      nplAmount = PLN.Zero,
      htmBookYield = Rate.Zero,
      status = Banking.BankStatus.Active(0),
      loansShort = PLN(200),
      loansMedium = PLN(300),
      loansLong = PLN(500),
      consumerNpl = PLN.Zero,
      eclStaging = EclStaging.State.zero,
    )
    val stocks = Banking.BankFinancialStocks(
      totalDeposits = PLN(10000),
      firmLoan = PLN(1000),
      govBondAfs = PLN.Zero,
      govBondHtm = PLN.Zero,
      reserve = PLN(10000),
      interbankLoan = PLN.Zero,
      demandDeposit = PLN(10000),
      termDeposit = PLN.Zero,
      consumerLoan = PLN.Zero,
    )
    val gate   = HouseholdIncomeEconomics.capitalAwareConsumerCreditGate(
      banks = Vector(bank),
      bankStocks = Vector(stocks),
      ccyb = Multiplier.Zero,
      bankCorpBonds = _ => PLN.Zero,
    )

    gate(BankId(0), PLN(200), RandomStream.seeded(1)) shouldBe true
    gate(BankId(0), PLN(200), RandomStream.seeded(2)) shouldBe false
  }

  it should "fail fast when bank configs do not align with bank rows" in {
    val badWorld = w.copy(bankingSector = w.bankingSector.copy(configs = w.bankingSector.configs.dropRight(1)))

    val err = intercept[IllegalArgumentException]:
      HouseholdIncomeEconomics.compute(
        badWorld,
        init.firms,
        init.households,
        init.banks,
        init.ledgerFinancialState,
        s1.lendingBaseRate,
        s1.resWage,
        s2.newWage,
        RandomStream.seeded(99),
      )

    err.getMessage should include("HouseholdIncomeEconomics.hhBankRates")
    err.getMessage should include(s"banks=${init.banks.length}")
    err.getMessage should include(s"configs=${w.bankingSector.configs.length - 1}")
  }
