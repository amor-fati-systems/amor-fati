package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BankCreditLossAccountingSpec extends AnyFlatSpec with Matchers:

  private given SimParams = SimParams.defaults

  "BankCreditLossAccounting" should "cap firm default capital loss by drawing the ECL allowance first" in {
    val loss = BankCreditLossAccounting.firm(PLN(100))

    loss.recoveryFlow shouldBe PLN(30)
    loss.expectedLoss shouldBe PLN(70)
    loss.allowanceDraw shouldBe PLN(50)
    loss.netCapitalLoss shouldBe PLN(20)
    loss.recoveryFlow + loss.expectedLoss shouldBe loss.grossDefault
    loss.allowanceDraw + loss.netCapitalLoss shouldBe loss.expectedLoss
  }

  it should "keep ordinary consumer losses product-specific without drawing aggregate ECL allowance" in {
    val loss = BankCreditLossAccounting.consumerLoan(PLN(100))

    loss.recoveryFlow shouldBe PLN(15)
    loss.expectedLoss shouldBe PLN(85)
    loss.allowanceDraw shouldBe PLN.Zero
    loss.netCapitalLoss shouldBe PLN(85)
  }

  it should "use a separate personal-insolvency recovery assumption" in {
    val loss = BankCreditLossAccounting.consumerInsolvency(PLN(100))

    loss.recoveryFlow shouldBe PLN(5)
    loss.expectedLoss shouldBe PLN(95)
    loss.allowanceDraw shouldBe PLN.Zero
    loss.netCapitalLoss shouldBe PLN(95)
  }

  it should "recognize bridge charge-offs as their own household-credit product" in {
    val loss = BankCreditLossAccounting.liquidityBridge(PLN(100))

    loss.recoveryFlow shouldBe PLN.Zero
    loss.expectedLoss shouldBe PLN(100)
    loss.allowanceDraw shouldBe PLN.Zero
    loss.netCapitalLoss shouldBe PLN(100)
  }

  it should "keep mortgage losses product-specific without drawing aggregate ECL allowance" in {
    val loss = BankCreditLossAccounting.mortgage(PLN(100))

    loss.recoveryFlow shouldBe PLN(70)
    loss.expectedLoss shouldBe PLN(30)
    loss.allowanceDraw shouldBe PLN.Zero
    loss.netCapitalLoss shouldBe PLN(30)
  }

  it should "aggregate household-credit products without double counting allowance draw" in {
    val breakdown = BankCreditLossAccounting.Breakdown(
      consumerLoan = BankCreditLossAccounting.consumerLoan(PLN(100)),
      consumerInsolvency = BankCreditLossAccounting.consumerInsolvency(PLN(20)),
      liquidityBridge = BankCreditLossAccounting.liquidityBridge(PLN(10)),
    )

    breakdown.consumer.grossDefault shouldBe PLN(130)
    breakdown.consumer.recoveryFlow shouldBe PLN(16)
    breakdown.consumer.expectedLoss shouldBe PLN(114)
    breakdown.consumer.allowanceDraw shouldBe PLN.Zero
    breakdown.consumer.netCapitalLoss shouldBe PLN(114)
    breakdown.allowanceDraw shouldBe PLN.Zero
  }
