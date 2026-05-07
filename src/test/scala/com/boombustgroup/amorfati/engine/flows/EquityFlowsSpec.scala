package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.types.*
import com.boombustgroup.amorfati.engine.ledger.{ForeignRuntimeContract, FundRuntimeIndex}
import com.boombustgroup.ledger.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class EquityFlowsSpec extends AnyFlatSpec with Matchers:

  "EquityFlows" should "preserve total wealth at exactly 0L" in {
    val flows    = EquityFlows.emit(EquityFlows.Input(PLN(500000), PLN(200000), PLN(100000), PLN.Zero))
    val balances = Interpreter.applyAll(Map.empty[Int, Long], flows)
    Interpreter.totalWealth(balances) shouldBe 0L
  }

  it should "have firm balance = -dividend payouts" in {
    val input    = EquityFlows.Input(PLN(500000), PLN(200000), PLN(100000), PLN(250000))
    val flows    = EquityFlows.emit(input)
    val balances = Interpreter.applyAll(Map.empty[Int, Long], flows)
    balances(EquityFlows.FIRM_ACCOUNT) shouldBe (-input.netDomesticDividends - input.foreignDividends - input.govDividends).toLong
  }

  it should "emit a dedicated firm-to-government SOE dividend flow when government extraction is positive" in {
    val input = EquityFlows.Input(PLN.Zero, PLN.Zero, PLN.Zero, PLN(250000))
    val flows = EquityFlows.emit(input)

    flows.exists(flow =>
      flow.from == EquityFlows.FIRM_ACCOUNT &&
        flow.to == EquityFlows.GOV_ACCOUNT &&
        flow.amount == input.govDividends.toLong &&
        flow.mechanism == FlowMechanism.EquityGovDividend.toInt,
    ) shouldBe true
  }

  it should "emit holder-aware equity revaluation batches for supported holders" in {
    val topology                = RuntimeLedgerTopology.nonZeroPopulation
    given RuntimeLedgerTopology = topology
    val input                   = EquityFlows.RevaluationInput(
      householdDeltas = Vector(PLN(10), PLN(-5), PLN.Zero),
      insuranceDelta = PLN(7),
      fundsDelta = PLN(-3),
      foreignDelta = PLN(11),
    )
    val batches                 = EquityFlows.emitRevaluationBatches(input)
    val householdGain           = batches.collectFirst { case broadcast: BatchedFlow.Broadcast if broadcast.to == EntitySector.Households => broadcast }.get
    val householdLoss           = batches.collectFirst { case scatter: BatchedFlow.Scatter if scatter.from == EntitySector.Households => scatter }.get
    val insuranceGain           = batches.collectFirst { case broadcast: BatchedFlow.Broadcast if broadcast.to == EntitySector.Insurance => broadcast }.get
    val fundsLoss               = batches.collectFirst { case broadcast: BatchedFlow.Broadcast if broadcast.from == EntitySector.Funds => broadcast }.get
    val foreignGain             = batches.collectFirst { case broadcast: BatchedFlow.Broadcast if broadcast.to == EntitySector.Foreign => broadcast }.get
    val balances                = Interpreter.applyAll(Map.empty[Int, Long], topology.toFlatFlows(batches))

    batches.map(_.mechanism).toSet shouldBe Set(FlowMechanism.EquityRevaluation)
    batches.map(_.asset).toSet shouldBe Set(AssetType.Equity)
    Interpreter.totalWealth(balances) shouldBe 0L

    householdGain.from shouldBe EntitySector.Firms
    householdGain.fromIndex shouldBe topology.firms.aggregate
    householdGain.targetIndices.toVector shouldBe Vector(0)
    householdGain.amounts.toVector shouldBe Vector(PLN(10).toLong)
    householdLoss.targetIndices(1) shouldBe topology.firms.aggregate
    householdLoss.amounts(1) shouldBe PLN(5).toLong
    insuranceGain.targetIndices.toVector shouldBe Vector(topology.insurance.persistedOwner)
    fundsLoss.fromIndex shouldBe FundRuntimeIndex.Nbfi
    fundsLoss.amounts.toVector shouldBe Vector(PLN(3).toLong)
    foreignGain.targetIndices.toVector shouldBe Vector(ForeignRuntimeContract.EquityHolderStock.index)
    foreignGain.amounts.toVector shouldBe Vector(PLN(11).toLong)
  }
