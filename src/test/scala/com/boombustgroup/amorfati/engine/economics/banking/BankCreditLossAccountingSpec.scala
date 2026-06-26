package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BankCreditLossAccountingSpec extends AnyFlatSpec with Matchers:

  private given SimParams = SimParams.defaults

  "BankCreditLossAccounting" should "cap firm default capital loss by drawing the ECL allowance first" in {
    val loss = BankCreditLossAccounting.firm(PLN(100))

    loss.expectedLoss shouldBe PLN(70)
    loss.allowanceDraw shouldBe PLN(50)
    loss.netCapitalLoss shouldBe PLN(20)
    loss.allowanceDraw + loss.netCapitalLoss shouldBe loss.expectedLoss
  }

  it should "keep consumer losses raw until consumer-credit ECL staging is modeled" in {
    val loss = BankCreditLossAccounting.consumer(PLN(100))

    loss.expectedLoss shouldBe PLN(85)
    loss.allowanceDraw shouldBe PLN.Zero
    loss.netCapitalLoss shouldBe PLN(85)
  }

  it should "keep mortgage losses raw until mortgage ECL staging is modeled" in {
    val loss = BankCreditLossAccounting.mortgage(PLN(100))

    loss.expectedLoss shouldBe PLN(30)
    loss.allowanceDraw shouldBe PLN.Zero
    loss.netCapitalLoss shouldBe PLN(30)
  }
