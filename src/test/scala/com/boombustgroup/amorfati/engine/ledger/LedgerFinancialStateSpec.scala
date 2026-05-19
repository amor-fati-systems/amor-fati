package com.boombustgroup.amorfati.engine.ledger

import com.boombustgroup.amorfati.agents.{Banking, Firm, Household, Nbp, QuasiFiscal}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.init.{InitRandomness, WorldInit}
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LedgerFinancialStateSpec extends AnyFlatSpec with Matchers:

  private given SimParams = SimParams.defaults

  private lazy val defaultInit: WorldInit.InitResult =
    WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))

  "LedgerFinancialState.refreshHouseholdBalances" should "preserve existing ledger balances and initialize only new households" in {
    val init              = defaultInit
    val existing          = init.households.head
    val existingIndex     = existing.id.toInt
    val previous          = init.ledgerFinancialState.households.updated(
      existingIndex,
      init.ledgerFinancialState.households(existingIndex).copy(demandDeposit = PLN(123)),
    )
    val existingHousehold = existing
    val newHousehold      = init.households.last.copy(id = HhId(previous.length))
    val newStocks         =
      Household.FinancialStocks(demandDeposit = PLN(777), mortgageLoan = PLN(55), consumerLoan = PLN(11), equity = PLN(22))

    val refreshed =
      LedgerFinancialState.refreshHouseholdBalances(Vector(existingHousehold, newHousehold), init.households, previous, Map(newHousehold.id -> newStocks))

    refreshed.head.demandDeposit shouldBe PLN(123)
    refreshed.last shouldBe LedgerFinancialState.householdBalances(newStocks)
  }

  it should "reject negative household demand deposits at the ledger projection boundary" in {
    val stocks = Household.FinancialStocks(demandDeposit = PLN(-1), mortgageLoan = PLN.Zero, consumerLoan = PLN.Zero, equity = PLN.Zero)

    an[IllegalArgumentException] should be thrownBy LedgerFinancialState.householdBalances(stocks)
  }

  "LedgerFinancialState.settleHouseholdMortgageStock" should "write an aggregate closing mortgage stock into household rows" in {
    val households = Vector(
      LedgerFinancialState.HouseholdBalances(
        demandDeposit = PLN(1),
        mortgageLoan = PLN(10),
        consumerLoan = PLN.Zero,
        equity = PLN.Zero,
      ),
      LedgerFinancialState.HouseholdBalances(
        demandDeposit = PLN(2),
        mortgageLoan = PLN(30),
        consumerLoan = PLN.Zero,
        equity = PLN.Zero,
      ),
    )

    val settled = LedgerFinancialState.settleHouseholdMortgageStock(households, PLN(80))

    LedgerFinancialState.householdMortgageStock(settled) shouldBe PLN(80)
    settled.map(_.demandDeposit) shouldBe households.map(_.demandDeposit)
    settled.map(_.consumerLoan) shouldBe households.map(_.consumerLoan)
    settled.map(_.equity) shouldBe households.map(_.equity)
  }

  it should "allocate a positive closing mortgage stock across zero-debt household rows" in {
    val households = Vector.fill(2)(
      LedgerFinancialState.HouseholdBalances(
        demandDeposit = PLN.Zero,
        mortgageLoan = PLN.Zero,
        consumerLoan = PLN.Zero,
        equity = PLN.Zero,
      ),
    )

    val settled = LedgerFinancialState.settleHouseholdMortgageStock(households, PLN(30))

    LedgerFinancialState.householdMortgageStock(settled) shouldBe PLN(30)
  }

  "LedgerFinancialState.settleHouseholdInsuranceReserveAssets" should "mirror aggregate insurance reserves into household asset rows" in {
    val households = Vector(
      LedgerFinancialState.HouseholdBalances(
        demandDeposit = PLN(1),
        mortgageLoan = PLN.Zero,
        consumerLoan = PLN.Zero,
        equity = PLN(5),
        lifeReserveAsset = PLN(10),
        nonLifeReserveAsset = PLN(20),
      ),
      LedgerFinancialState.HouseholdBalances(
        demandDeposit = PLN(3),
        mortgageLoan = PLN.Zero,
        consumerLoan = PLN.Zero,
        equity = PLN(7),
        lifeReserveAsset = PLN(30),
        nonLifeReserveAsset = PLN(20),
      ),
    )

    val settled = LedgerFinancialState.settleHouseholdInsuranceReserveAssets(households, PLN(80), PLN(60))

    LedgerFinancialState.householdLifeReserveAsset(settled) shouldBe PLN(80)
    LedgerFinancialState.householdNonLifeReserveAsset(settled) shouldBe PLN(60)
    settled.map(_.lifeReserveAsset) shouldBe Vector(PLN(20), PLN(60))
    settled.map(_.nonLifeReserveAsset) shouldBe Vector(PLN(30), PLN(30))
    settled.map(_.demandDeposit) shouldBe households.map(_.demandDeposit)
    settled.map(_.equity) shouldBe households.map(_.equity)
  }

  it should "allocate initial household reserve assets by deposit weights when no reserve weights exist" in {
    val households = Vector(
      LedgerFinancialState.HouseholdBalances(
        demandDeposit = PLN(1),
        mortgageLoan = PLN.Zero,
        consumerLoan = PLN.Zero,
        equity = PLN.Zero,
      ),
      LedgerFinancialState.HouseholdBalances(
        demandDeposit = PLN(3),
        mortgageLoan = PLN.Zero,
        consumerLoan = PLN.Zero,
        equity = PLN.Zero,
      ),
    )

    val settled = LedgerFinancialState.settleHouseholdInsuranceReserveAssets(households, PLN(80), PLN(40))

    settled.map(_.lifeReserveAsset) shouldBe Vector(PLN(20), PLN(60))
    settled.map(_.nonLifeReserveAsset) shouldBe Vector(PLN(10), PLN(30))
  }

  "LedgerFinancialState.settleBankMortgageAssets" should "mirror the aggregate household mortgage stock into bank asset rows" in {
    val banks = Vector(
      LedgerFinancialState.BankBalances(
        totalDeposits = PLN.Zero,
        demandDeposit = PLN.Zero,
        termDeposit = PLN.Zero,
        firmLoan = PLN(100),
        consumerLoan = PLN.Zero,
        govBondAfs = PLN.Zero,
        govBondHtm = PLN.Zero,
        reserve = PLN.Zero,
        interbankLoan = PLN.Zero,
        corpBond = PLN.Zero,
      ),
      LedgerFinancialState.BankBalances(
        totalDeposits = PLN.Zero,
        demandDeposit = PLN.Zero,
        termDeposit = PLN.Zero,
        firmLoan = PLN(300),
        consumerLoan = PLN.Zero,
        govBondAfs = PLN.Zero,
        govBondHtm = PLN.Zero,
        reserve = PLN.Zero,
        interbankLoan = PLN.Zero,
        corpBond = PLN.Zero,
      ),
    )

    val settled = LedgerFinancialState.settleBankMortgageAssets(banks, PLN(80))

    LedgerFinancialState.bankMortgageStock(settled) shouldBe PLN(80)
    settled.map(_.firmLoan) shouldBe banks.map(_.firmLoan)
    settled.map(_.mortgageLoan) shouldBe Vector(PLN(20), PLN(60))
  }

  "LedgerFinancialState.refreshFirmPopulationBalances" should "refresh execution stocks while preserving existing corporate bonds" in {
    val init          = defaultInit
    val existingIndex = init.firms.head.id.toInt
    val previous      = init.ledgerFinancialState.firms.updated(
      existingIndex,
      init.ledgerFinancialState.firms(existingIndex).copy(cash = PLN(123), corpBond = PLN(456)),
    )
    val closingStocks = Firm.FinancialStocks(cash = PLN(999), firmLoan = PLN(88), equity = PLN(77))
    val appended      = Firm.FinancialStocks(cash = PLN(777), firmLoan = PLN(55), equity = PLN(22))
    val recycled      = Firm.FinancialStocks(cash = PLN(333), firmLoan = PLN(44), equity = PLN(12))

    val stockRows         =
      previous.map(LedgerFinancialState.projectFirmFinancialStocks).updated(existingIndex, closingStocks) :+ appended
    val recycledRows      =
      previous.map(LedgerFinancialState.projectFirmFinancialStocks).updated(existingIndex, recycled)
    val refreshed         =
      LedgerFinancialState.refreshFirmPopulationBalances(stockRows, previous, newFirmIds = Set(FirmId(previous.length)))
    val refreshedRecycled =
      LedgerFinancialState.refreshFirmPopulationBalances(recycledRows, previous, newFirmIds = Set(FirmId(existingIndex)))

    refreshed(existingIndex) shouldBe LedgerFinancialState.firmBalances(closingStocks, corpBond = PLN(456))
    refreshed.last shouldBe LedgerFinancialState.firmBalances(appended, corpBond = PLN.Zero)
    refreshedRecycled(existingIndex) shouldBe LedgerFinancialState.firmBalances(recycled, corpBond = PLN.Zero)
  }

  "LedgerFinancialState.refreshFirmFinancialBalances" should "update operational balances while preserving corporate bonds" in {
    val init     = defaultInit
    val previous = init.ledgerFinancialState.firms.updated(
      0,
      init.ledgerFinancialState.firms.head.copy(corpBond = PLN(456)),
    )
    val balances = Vector(
      Firm.FinancialStocks(
        cash = PLN(123),
        firmLoan = PLN(88),
        equity = PLN(77),
      ),
    )

    val refreshed = LedgerFinancialState.refreshFirmFinancialBalances(balances, previous)

    refreshed.head shouldBe LedgerFinancialState.FirmBalances(
      cash = PLN(123),
      firmLoan = PLN(88),
      corpBond = PLN(456),
      equity = PLN(77),
    )
  }

  "LedgerFinancialState.bankBalances" should "write bank execution stocks directly" in {
    val stocks = Banking.BankFinancialStocks(
      totalDeposits = PLN(123),
      demandDeposit = PLN(100),
      termDeposit = PLN(23),
      firmLoan = PLN(88),
      consumerLoan = PLN(77),
      govBondAfs = PLN(66),
      govBondHtm = PLN(55),
      reserve = PLN(44),
      interbankLoan = PLN(33),
    )
    LedgerFinancialState.bankBalances(stocks, corpBond = PLN(22), mortgageLoan = PLN(11)) shouldBe LedgerFinancialState.BankBalances(
      totalDeposits = PLN(123),
      demandDeposit = PLN(100),
      termDeposit = PLN(23),
      firmLoan = PLN(88),
      consumerLoan = PLN(77),
      govBondAfs = PLN(66),
      govBondHtm = PLN(55),
      reserve = PLN(44),
      interbankLoan = PLN(33),
      corpBond = PLN(22),
      mortgageLoan = PLN(11),
    )
  }

  "LedgerFinancialState.foreignEquityOwnershipShare" should "fail fast on positive holdings without positive market cap" in {
    val err = intercept[IllegalArgumentException]:
      LedgerFinancialState.foreignEquityOwnershipShare(PLN(10), PLN.Zero)

    err.getMessage should include(s"foreignEquityHoldings=${PLN(10)}")
    err.getMessage should include(s"marketCap=${PLN.Zero}")
  }

  "LedgerFinancialState.nbpBalances" should "write NBP execution stocks and reserve liabilities directly" in {
    val stocks = Nbp.FinancialStocks(
      govBondHoldings = PLN(123),
      foreignAssets = PLN(456),
    )

    LedgerFinancialState.nbpBalances(stocks, reserveLiability = PLN(789)) shouldBe LedgerFinancialState.NbpBalances(
      govBondHoldings = PLN(123),
      foreignAssets = PLN(456),
      reserveLiability = PLN(789),
    )
  }

  "LedgerFinancialState.quasiFiscalBalances" should "round-trip holder split through ledger-owned stock" in {
    val stock = QuasiFiscal.StockState(
      bondsOutstanding = PLN(123),
      loanPortfolio = PLN(45),
      bankHoldings = PLN(67),
      nbpHoldings = PLN(56),
    )

    val balances = LedgerFinancialState.quasiFiscalBalances(stock)

    balances shouldBe LedgerFinancialState.QuasiFiscalBalances(
      bondsOutstanding = PLN(123),
      loanPortfolio = PLN(45),
      bankHoldings = PLN(67),
      nbpHoldings = PLN(56),
    )
  }
