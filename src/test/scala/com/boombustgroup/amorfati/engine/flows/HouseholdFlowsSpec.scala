package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class HouseholdFlowsSpec extends AnyFlatSpec with Matchers:

  private val baseInput = HouseholdFlows.Input(
    consumption = PLN(40000000),
    rent = PLN(8000000),
    pit = PLN(5000000),
    depositInterest = PLN(1000000),
    remittances = PLN(500000),
    approvedCcOrigination = PLN(2000000),
    liquidityShortfallFinancing = PLN(300000),
    ccDebtService = PLN(1500000),
    ccDefault = PLN(200000),
  )

  "HouseholdFlows" should "preserve total wealth at exactly 0L" in {
    val flows    = HouseholdFlows.emit(baseInput)
    val balances = Interpreter.applyAll(Map.empty[Int, Long], flows)
    Interpreter.totalWealth(balances) shouldBe 0L
  }

  it should "have HH balance = -outflows + inflows" in {
    val flows    = HouseholdFlows.emit(baseInput)
    val balances = Interpreter.applyAll(Map.empty[Int, Long], flows)

    val outflows = baseInput.consumption + baseInput.rent + baseInput.pit +
      baseInput.remittances + baseInput.ccDebtService + baseInput.ccDefault
    val inflows  = baseInput.depositInterest + baseInput.approvedCcOrigination + baseInput.liquidityShortfallFinancing

    balances(HouseholdFlows.HH_ACCOUNT) shouldBe (inflows - outflows).toLong
  }

  it should "have bank balance = ccDebtService + ccDefault - depositInterest - credit origination" in {
    val flows    = HouseholdFlows.emit(baseInput)
    val balances = Interpreter.applyAll(Map.empty[Int, Long], flows)

    val bankNet = baseInput.ccDebtService + baseInput.ccDefault -
      baseInput.depositInterest - baseInput.approvedCcOrigination - baseInput.liquidityShortfallFinancing

    balances(HouseholdFlows.BANK_ACCOUNT) shouldBe bankNet.toLong
  }

  it should "skip zero-amount flows" in {
    val minimal = HouseholdFlows.Input(PLN(1000000), PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero)
    val flows   = HouseholdFlows.emit(minimal)
    flows.length shouldBe 1
    flows.head.mechanism shouldBe FlowMechanism.HhConsumption.toInt
  }
