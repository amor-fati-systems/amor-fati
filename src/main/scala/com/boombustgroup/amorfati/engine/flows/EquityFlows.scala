package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.engine.ledger.{ForeignRuntimeContract, FundRuntimeIndex, TreasuryRuntimeContract}
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.*

import scala.IArray

/** GPW equity market emitting dividend flows.
  *
  * Dividends: domestic (Firm→HH net of Belka tax), foreign (Firm→Foreign).
  * Equity issuance belongs to FirmFlows because the current-month financing
  * decision is produced by FirmEconomics before market memory is finalized.
  *
  * Account IDs: 0=Firm, 1=HH, 2=Foreign, 3=Gov (Belka tax)
  */
object EquityFlows:

  val FIRM_ACCOUNT: Int    = 0
  val HH_ACCOUNT: Int      = 1
  val FOREIGN_ACCOUNT: Int = 2
  val GOV_ACCOUNT: Int     = 3

  case class Input(
      netDomesticDividends: PLN,
      foreignDividends: PLN,
      dividendTax: PLN,
      govDividends: PLN,
  )

  case class RevaluationInput(
      householdDeltas: IArray[PLN],
      insuranceDelta: PLN,
      fundsDelta: PLN,
      foreignDelta: PLN,
  )

  def emitBatches(input: Input)(using topology: RuntimeLedgerTopology): Vector[BatchedFlow] =
    Vector.concat(
      AggregateBatchedEmission.transfer(
        EntitySector.Firms,
        topology.firms.aggregate,
        EntitySector.Households,
        topology.households.investors,
        input.netDomesticDividends,
        AssetType.Cash,
        FlowMechanism.EquityDomDividend,
      ),
      AggregateBatchedEmission.transfer(
        EntitySector.Firms,
        topology.firms.aggregate,
        EntitySector.Foreign,
        ForeignRuntimeContract.IncomeSettlement.index,
        input.foreignDividends,
        AssetType.Cash,
        FlowMechanism.EquityForDividend,
      ),
      AggregateBatchedEmission.transfer(
        EntitySector.Households,
        topology.households.investors,
        EntitySector.Government,
        TreasuryRuntimeContract.TreasuryBudgetSettlement.index,
        input.dividendTax,
        AssetType.Cash,
        FlowMechanism.EquityDividendTax,
      ),
      AggregateBatchedEmission.transfer(
        EntitySector.Firms,
        topology.firms.aggregate,
        EntitySector.Government,
        TreasuryRuntimeContract.TreasuryBudgetSettlement.index,
        input.govDividends,
        AssetType.Cash,
        FlowMechanism.EquityGovDividend,
      ),
    )

  def emitRevaluationBatches(input: RevaluationInput)(using topology: RuntimeLedgerTopology): Vector[BatchedFlow] =
    require(
      input.householdDeltas.length <= topology.households.persistedCount,
      s"EquityFlows.emitRevaluationBatches received ${input.householdDeltas.length} household deltas but topology has ${topology.households.persistedCount} persisted households",
    )
    Vector.concat(
      householdRevaluationBatches(input.householdDeltas),
      holderRevaluationBatch(EntitySector.Insurance, topology.insurance.persistedOwner, input.insuranceDelta),
      holderRevaluationBatch(EntitySector.Funds, FundRuntimeIndex.Nbfi, input.fundsDelta),
      holderRevaluationBatch(EntitySector.Foreign, ForeignRuntimeContract.EquityHolderStock.index, input.foreignDelta),
    )

  private def holderRevaluationBatch(
      holderSector: EntitySector,
      holderIndex: Int,
      signedAmount: PLN,
  )(using topology: RuntimeLedgerTopology): Vector[BatchedFlow] =
    AggregateBatchedEmission.signedTransfer(
      positiveFrom = EntitySector.Firms,
      positiveFromIndex = topology.firms.aggregate,
      positiveTo = holderSector,
      positiveToIndex = holderIndex,
      signedAmount = signedAmount,
      asset = AssetType.Equity,
      mechanism = FlowMechanism.EquityRevaluation,
    )

  private def householdRevaluationBatches(deltas: IArray[PLN])(using topology: RuntimeLedgerTopology): Vector[BatchedFlow] =
    val posAmounts  = Array.fill(deltas.length)(0L)
    val posIndices  = Array.fill(deltas.length)(0)
    val negAmounts  = Array.fill(topology.households.sectorSize)(0L)
    var posCount    = 0
    var hasNegative = false
    var i           = 0
    while i < deltas.length do
      val delta = deltas(i)
      if delta > PLN.Zero then
        posAmounts(posCount) = delta.toLong
        posIndices(posCount) = i
        posCount += 1
      else if delta < PLN.Zero then
        negAmounts(i) = delta.abs.toLong
        hasNegative = true
      i += 1

    val batches = Vector.newBuilder[BatchedFlow]
    if posCount > 0 then
      batches += BatchedFlow.Broadcast(
        from = EntitySector.Firms,
        fromIndex = topology.firms.aggregate,
        to = EntitySector.Households,
        amounts = java.util.Arrays.copyOf(posAmounts, posCount),
        targetIndices = java.util.Arrays.copyOf(posIndices, posCount),
        asset = AssetType.Equity,
        mechanism = FlowMechanism.EquityRevaluation,
      )
    if hasNegative then
      batches += BatchedFlow.Scatter(
        from = EntitySector.Households,
        to = EntitySector.Firms,
        amounts = negAmounts,
        targetIndices = Array.fill(topology.households.sectorSize)(topology.firms.aggregate),
        asset = AssetType.Equity,
        mechanism = FlowMechanism.EquityRevaluation,
      )
    batches.result()

  def emit(input: Input): Vector[Flow] =
    val flows = Vector.newBuilder[Flow]
    if input.netDomesticDividends > PLN.Zero then
      flows += Flow(FIRM_ACCOUNT, HH_ACCOUNT, input.netDomesticDividends.toLong, FlowMechanism.EquityDomDividend.toInt)
    if input.foreignDividends > PLN.Zero then flows += Flow(FIRM_ACCOUNT, FOREIGN_ACCOUNT, input.foreignDividends.toLong, FlowMechanism.EquityForDividend.toInt)
    if input.dividendTax > PLN.Zero then flows += Flow(HH_ACCOUNT, GOV_ACCOUNT, input.dividendTax.toLong, FlowMechanism.EquityDividendTax.toInt)
    if input.govDividends > PLN.Zero then flows += Flow(FIRM_ACCOUNT, GOV_ACCOUNT, input.govDividends.toLong, FlowMechanism.EquityGovDividend.toInt)
    flows.result()
