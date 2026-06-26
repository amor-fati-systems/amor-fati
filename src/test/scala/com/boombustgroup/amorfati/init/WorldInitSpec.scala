package com.boombustgroup.amorfati.init

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import com.boombustgroup.amorfati.agents.{Banking, EclStaging}
import com.boombustgroup.amorfati.config.{OpeningBankBalanceProfileBridge, SimParams}
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.engine.mechanisms.Macroprudential
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
    world.mechanisms.macropru.ccyb shouldBe p.banking.initialCcyb
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
    decimal(init.world.pipeline.laggedUnemploymentRate) shouldBe decimal(p.pop.initialUnemploymentRate) +- (BigDecimal(1) / BigDecimal(laborForce))
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

  it should "compute opening bank capital from opening RWA and preserve the sector capital target" in {
    val init                = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
    val aggregateCapital    = init.banks.map(_.capital).sumPln
    val bankRows            = init.banks.zip(init.ledgerFinancialState.banks)
    val productionTargets   = OpeningBankProfileTargets.fromBridgeRows(OpeningBankBalanceProfileBridge.Rows) match
      case OpeningBankProfileTargets.Resolution.Complete(targets) => targets
      case other                                                  => fail(s"Expected complete production bank targets, got $other")
    val expectedCapitalById = productionTargets.openingCapitalProfiles
      .map: profile =>
        profile.bankId -> profile.ownFunds.getOrElse(fail(s"Expected own-funds target for bank ${profile.bankId}"))
      .toMap

    decimal(aggregateCapital) shouldBe decimal(p.banking.initCapital) +- (decimal(p.banking.initCapital) * BigDecimal("0.001"))
    bankRows.foreach { case (bank, balances) =>
      val stocks = LedgerFinancialState.projectBankFinancialStocks(balances)
      val rwa    = Banking.riskWeightedAssets(stocks, balances.corpBond)
      val car    = Banking.car(bank, stocks, balances.corpBond)
      val minCar = Macroprudential.effectiveMinCar(bank.id.toInt, p.banking.initialCcyb)

      bank.capital shouldBe expectedCapitalById(bank.id)
      rwa should be > PLN.Zero
      car should be >= minCar
    }
  }

  it should "normalize opening customer deposits to the banking deposit stock" in {
    val init              = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
    val householdDeposits = init.ledgerFinancialState.households.map(_.demandDeposit).sumPln
    val firmDeposits      = init.ledgerFinancialState.firms.map(_.cash).sumPln
    val bankDeposits      = init.ledgerFinancialState.banks.map(_.totalDeposits).sumPln

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

  it should "reconcile opening bank books to complete explicit bank profile targets" in {
    val rows    = OpeningBankProfileTestFixtures.completeRows
    val targets = OpeningBankProfileTargets.fromBridgeRows(rows) match
      case OpeningBankProfileTargets.Resolution.Complete(targets) => targets
      case other                                                  => fail(s"Expected complete opening bank targets, got $other")
    val init    = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L), openingBankProfileRows = rows)

    val bankBalances = init.ledgerFinancialState.banks
    bankBalances.map(_.totalDeposits) shouldBe targets.deposits
    bankBalances.map(_.firmLoan) shouldBe targets.firmLoans
    bankBalances.map(_.consumerLoan) shouldBe targets.consumerLoans
    bankBalances.map(_.mortgageLoan) shouldBe targets.mortgageLoans
    bankBalances.map(_.reserve) shouldBe targets.reserves.getOrElse(fail("Expected reserve targets in complete fixture"))
    bankBalances.map(_.corpBond) shouldBe targets.corpBonds.getOrElse(fail("Expected corporate-bond targets in complete fixture"))
    bankBalances.map(bank => bank.govBondAfs + bank.govBondHtm).zip(targets.govBonds).foreach { case (actual, expected) =>
      (actual - expected).abs should be <= PLN(1)
    }

    val firmRows         = init.firms.zip(init.ledgerFinancialState.firms)
    val householdRows    = init.households.zip(init.ledgerFinancialState.households)
    val firmCashByBank   = firmRows.groupMapReduce(_._1.bankId.toInt)(_._2.cash)(_ + _)
    val firmDebtByBank   = firmRows.groupMapReduce(_._1.bankId.toInt)(_._2.firmLoan)(_ + _)
    val hhSavingsByBank  = householdRows.groupMapReduce(_._1.bankId.toInt)(_._2.demandDeposit)(_ + _)
    val hhConsDebtByBank = householdRows.groupMapReduce(_._1.bankId.toInt)(_._2.consumerLoan)(_ + _)
    val hhMortgageByBank = householdRows.groupMapReduce(_._1.bankId.toInt)(_._2.mortgageLoan)(_ + _)

    bankBalances.zipWithIndex.foreach { case (bank, bankId) =>
      firmCashByBank.getOrElse(bankId, PLN.Zero) + hhSavingsByBank.getOrElse(bankId, PLN.Zero) shouldBe bank.totalDeposits
      firmDebtByBank.getOrElse(bankId, PLN.Zero) shouldBe bank.firmLoan
      hhConsDebtByBank.getOrElse(bankId, PLN.Zero) shouldBe bank.consumerLoan
      hhMortgageByBank.getOrElse(bankId, PLN.Zero) shouldBe bank.mortgageLoan
    }
  }

  it should "use production opening bank profile targets by default" in {
    val targets = OpeningBankProfileTargets.fromBridgeRows(OpeningBankBalanceProfileBridge.Rows) match
      case OpeningBankProfileTargets.Resolution.Complete(targets) => targets
      case other                                                  => fail(s"Expected complete production bank targets, got $other")
    val init    = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))

    val bankBalances = init.ledgerFinancialState.banks
    bankBalances.map(_.totalDeposits) shouldBe targets.deposits
    bankBalances.map(_.firmLoan) shouldBe targets.firmLoans
    bankBalances.map(_.consumerLoan) shouldBe targets.consumerLoans
    bankBalances.map(_.mortgageLoan) shouldBe targets.mortgageLoans
    bankBalances.map(bank => bank.govBondAfs + bank.govBondHtm).zip(targets.govBonds).foreach { case (actual, expected) =>
      (actual - expected).abs should be <= PLN(1)
    }
  }

  it should "reject explicit bank holding vectors whose totals do not match opening sector stocks" in {
    val init            = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
    val firmStocks      = init.ledgerFinancialState.firms.map(LedgerFinancialState.projectFirmFinancialStocks)
    val householdStocks = init.ledgerFinancialState.households.map(LedgerFinancialState.projectHouseholdFinancialStocks)
    val zeroBankRows    = Vector.fill(Banking.DefaultConfigs.length)(PLN.Zero)

    intercept[IllegalArgumentException]:
      BankInit.create(
        init.firms,
        firmStocks,
        init.households,
        householdStocks,
        bankGovBondHoldings = zeroBankRows,
      )

    intercept[IllegalArgumentException]:
      BankInit.create(
        init.firms,
        firmStocks,
        init.households,
        householdStocks,
        bankReserveHoldings = zeroBankRows,
      )

    intercept[IllegalArgumentException]:
      BankInit.create(
        init.firms,
        firmStocks,
        init.households,
        householdStocks,
        bankCorpBondHoldings = zeroBankRows,
      )
  }
