package com.boombustgroup.amorfati.init

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import com.boombustgroup.amorfati.agents.{Banking, EclStaging}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WorldInitSpec extends AnyFlatSpec with Matchers:

  given SimParams          = SimParams.defaults
  private val p: SimParams = summon[SimParams]

  private def initialUnemployedCount(employedSlots: Int, unemploymentRate: Share): Int =
    if employedSlots <= 0 || unemploymentRate <= Share.Zero then 0
    else
      val employedShareRaw = (Share.One - unemploymentRate).toLong
      val total            =
        ((BigInt(employedSlots) * BigInt(FixedPointBase.Scale)) + BigInt(employedShareRaw - 1L)) / BigInt(employedShareRaw)
      Math.max(0, total.toInt - employedSlots)

  "WorldInit" should "seed the Poland 2026-04-30 macro baseline from config" in {
    val init  = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
    val world = init.world

    world.inflation shouldBe p.monetary.initialInflation
    world.nbp.referenceRate shouldBe p.monetary.initialRate
    world.mechanisms.expectations.expectedInflation shouldBe p.monetary.initialExpectedInflation
    world.mechanisms.expectations.expectedRate shouldBe p.monetary.initialExpectedRate
  }

  it should "initialize unemployment and GDP scale from the production baseline" in {
    val init               = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
    val agg                = init.householdAggregates
    val laborForce         = agg.employed + agg.unemployed
    val expectedUnemployed = initialUnemployedCount(agg.employed, p.pop.initialUnemploymentRate)
    val unempRate          = Share.fraction(agg.unemployed, laborForce)
    val annualGdp          = (init.world.cachedMonthlyGdpProxy * 12) / p.gdpRatio.toMultiplier

    agg.unemployed shouldBe expectedUnemployed
    unempRate shouldBe Share.fraction(expectedUnemployed, laborForce)
    decimal(unempRate) shouldBe decimal(p.pop.initialUnemploymentRate) +- (BigDecimal(1) / BigDecimal(laborForce))
    decimal(annualGdp) shouldBe decimal(p.pop.realGdp) +- BigDecimal("1000000.0")
  }

  it should "honor the banking consumer-loan opening stock" in {
    val init             = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
    val householdLoans   = init.ledgerFinancialState.households.map(_.consumerLoan).sumPln
    val bankConsumerBook = init.ledgerFinancialState.banks.map(_.consumerLoan).sumPln

    householdLoans shouldBe p.banking.initConsumerLoans
    bankConsumerBook shouldBe p.banking.initConsumerLoans
  }

  it should "seed bank ECL staging from the opening covered loan book" in {
    val init         = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
    val bankBalances = init.ledgerFinancialState.banks
    val allowance    = init.banks.map(bank => EclStaging.allowance(bank.eclStaging)).sumPln

    allowance should be > PLN.Zero
    init.banks.size shouldBe bankBalances.size
    init.banks.map(_.id.toInt).distinct.size shouldBe init.banks.size
    val balancesByBankId = bankBalances.zipWithIndex.map { case (balances, bankId) => bankId -> balances }.toMap
    init.banks.foreach { bank =>
      val balances     = balancesByBankId(bank.id.toInt)
      val coveredLoans = balances.firmLoan + balances.consumerLoan

      bank.eclStaging.stage1 shouldBe coveredLoans
      bank.eclStaging.stage2 shouldBe PLN.Zero
      bank.eclStaging.stage3 shouldBe PLN.Zero
      EclStaging.allowance(bank.eclStaging) shouldBe coveredLoans * p.banking.eclRate1
    }
  }

  it should "honor the housing mortgage opening stock" in {
    val init              = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
    val householdMortgage = LedgerFinancialState.householdMortgageStock(init.ledgerFinancialState)
    val bankMortgageBook  = LedgerFinancialState.bankMortgageStock(init.ledgerFinancialState)

    householdMortgage shouldBe p.housing.initMortgage
    bankMortgageBook shouldBe p.housing.initMortgage
    init.world.real.housing.mortgageStock shouldBe p.housing.initMortgage
  }

  it should "normalize opening customer deposits to the banking deposit stock" in {
    val init              = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
    val householdDeposits = init.ledgerFinancialState.households.map(_.demandDeposit).sumPln
    val firmDeposits      = init.ledgerFinancialState.firms.map(_.cash).sumPln
    val bankDeposits      = init.ledgerFinancialState.banks.map(_.totalDeposits).sumPln

    householdDeposits should be > PLN.Zero
    firmDeposits should be > PLN.Zero
    householdDeposits + firmDeposits shouldBe p.banking.initDeposits
    bankDeposits shouldBe p.banking.initDeposits
  }

  it should "seed bank deposit buckets and NBP reserve liabilities from opening deposits" in {
    val init         = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
    val bankBalances = init.ledgerFinancialState.banks
    val deposits     = bankBalances.map(_.totalDeposits).sumPln
    val reserves     = bankBalances.map(_.reserve).sumPln

    deposits should be > PLN.Zero
    deposits shouldBe p.banking.initDeposits
    bankBalances.foreach { bank =>
      val expectedTermDeposit = bank.totalDeposits * p.banking.termDepositFrac
      val expectedReserve     = bank.totalDeposits * p.banking.reserveReq
      val stocks              = LedgerFinancialState.projectBankFinancialStocks(bank)

      bank.demandDeposit + bank.termDeposit shouldBe bank.totalDeposits
      bank.termDeposit shouldBe expectedTermDeposit
      bank.reserve shouldBe expectedReserve
      Banking.netCashOutflows(stocks) should be > PLN.Zero
    }
    init.ledgerFinancialState.nbp.reserveLiability shouldBe reserves
  }
