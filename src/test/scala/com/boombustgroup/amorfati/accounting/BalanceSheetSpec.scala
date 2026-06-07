package com.boombustgroup.amorfati.accounting

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

class BalanceSheetSpec extends AnyFlatSpec with Matchers:

  given SimParams = SimParams.defaults

  private def bankingAggregate(totalLoans: PLN, nplAmount: PLN, capital: PLN): Banking.Aggregate =
    Banking.Aggregate(
      totalLoans = totalLoans,
      nplAmount = nplAmount,
      capital = capital,
      deposits = PLN(500000),
      afsBonds = PLN.Zero,
      htmBonds = PLN.Zero,
      consumerLoans = PLN.Zero,
      consumerNpl = PLN.Zero,
      corpBondHoldings = PLN.Zero,
      mortgageLoans = PLN.Zero,
      reserves = PLN.Zero,
      interbankAssets = PLN.Zero,
    )

  "BankingAggregate.nplRatio" should "equal nplAmount / totalLoans when totalLoans > 1" in {
    val b =
      bankingAggregate(totalLoans = PLN(1000000), nplAmount = PLN(50000), capital = PLN(200000))
    decimal(b.nplRatio) shouldBe BigDecimal("0.05") +- BigDecimal("0.001")
  }

  it should "return 0.0 when totalLoans <= 1" in {
    bankingAggregate(totalLoans = PLN.Zero, nplAmount = PLN(100), capital = PLN(200000)).nplRatio shouldBe Share.Zero
    bankingAggregate(totalLoans = PLN(1), nplAmount = PLN(100), capital = PLN(200000)).nplRatio shouldBe Share.Zero
  }

  "BankingAggregate.car" should "equal capital / totalLoans when totalLoans > 1" in {
    val b = bankingAggregate(totalLoans = PLN(1000000), nplAmount = PLN.Zero, capital = PLN(200000))
    decimal(b.car) shouldBe BigDecimal("0.2") +- BigDecimal("0.001")
  }

  it should "return 10.0 when totalLoans <= 1" in {
    bankingAggregate(totalLoans = PLN.Zero, nplAmount = PLN.Zero, capital = PLN(200000)).car shouldBe Multiplier(10)
  }

  // lendingRate and canLend removed from BankingAggregate — now only on Banking.BankState
