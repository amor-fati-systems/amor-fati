package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.engine.{EngineFailure, EngineFailureCategory}
import com.boombustgroup.ledger.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RuntimeFlowExecutorSpec extends AnyFlatSpec with Matchers:

  private given RuntimeLedgerTopology = RuntimeLedgerTopology.zeroPopulation

  private def validTransfer(amount: Long): BatchedFlow =
    val topology = summon[RuntimeLedgerTopology]
    BatchedFlow.Broadcast(
      from = EntitySector.Government,
      fromIndex = topology.government.sovereignIssuer,
      to = EntitySector.Households,
      amounts = Array(amount),
      targetIndices = Array(topology.households.aggregate),
      asset = AssetType.Cash,
      mechanism = FlowMechanism.GovPurchases,
    )

  "RuntimeFlowExecutor" should "execute runtime batches and capture delta ledger evidence" in {
    val topology = summon[RuntimeLedgerTopology]
    val result   = RuntimeFlowExecutor.execute(Vector(validTransfer(100L))) match
      case Right(value) => value
      case Left(error)  => fail(error.diagnosticMessage)

    result.topology shouldBe topology
    result.netDelta shouldBe 0L
    result.deltaLedger((EntitySector.Government, AssetType.Cash, topology.government.sovereignIssuer)) shouldBe -100L
    result.deltaLedger((EntitySector.Households, AssetType.Cash, topology.households.aggregate)) shouldBe 100L
  }

  it should "return interpreter validation failures without throwing" in {
    RuntimeFlowExecutor.execute(Vector(validTransfer(-1L))) match
      case Left(error)  =>
        error.category shouldBe EngineFailureCategory.RuntimeLedgerExecutionFailure
        error.boundary shouldBe "RuntimeFlowExecutor.execute"
        error.details should include("negative")
      case Right(value) => fail(s"Expected runtime execution failure, got $value")
  }

  it should "map execution failures through one exception path for month-step callers" in {
    val err = intercept[EngineFailure]:
      RuntimeFlowExecutor.executeOrThrow(Vector(validTransfer(-1L)))

    err.category shouldBe EngineFailureCategory.RuntimeLedgerExecutionFailure
    err.getMessage should include("Ledger batch execution failed")
    err.getMessage should include("negative")
  }
