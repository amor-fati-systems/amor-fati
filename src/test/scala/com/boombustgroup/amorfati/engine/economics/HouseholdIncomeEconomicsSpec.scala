package com.boombustgroup.amorfati.engine.economics

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

  private def compute(pensionIncome: PLN): HouseholdIncomeEconomics.Output =
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
