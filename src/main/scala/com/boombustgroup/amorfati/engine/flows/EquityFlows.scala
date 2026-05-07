package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.engine.ledger.{ForeignRuntimeContract, FundRuntimeIndex, TreasuryRuntimeContract}
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.*

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
      householdDeltas: Vector[PLN],
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

  private def householdRevaluationBatches(deltas: Vector[PLN])(using topology: RuntimeLedgerTopology): Vector[BatchedFlow] =
    val indexed   = deltas.zipWithIndex.filter { case (delta, _) => delta != PLN.Zero }
    val positives = indexed.collect { case (delta, index) if delta > PLN.Zero => delta.toLong -> index }
    val negatives = indexed.collect { case (delta, index) if delta < PLN.Zero => delta.abs.toLong -> index }

    Vector.concat(
      Option
        .when(positives.nonEmpty)(
          BatchedFlow.Broadcast(
            from = EntitySector.Firms,
            fromIndex = topology.firms.aggregate,
            to = EntitySector.Households,
            amounts = positives.map(_._1).toArray,
            targetIndices = positives.map(_._2).toArray,
            asset = AssetType.Equity,
            mechanism = FlowMechanism.EquityRevaluation,
          ),
        )
        .toVector,
      Option
        .when(negatives.nonEmpty) {
          val amounts = Array.fill(topology.households.sectorSize)(0L)
          negatives.foreach { case (amount, householdIndex) => amounts(householdIndex) = amount }
          BatchedFlow.Scatter(
            from = EntitySector.Households,
            to = EntitySector.Firms,
            amounts = amounts,
            targetIndices = Array.fill(topology.households.sectorSize)(topology.firms.aggregate),
            asset = AssetType.Equity,
            mechanism = FlowMechanism.EquityRevaluation,
          )
        }
        .toVector,
    )

  def emit(input: Input): Vector[Flow] =
    val flows = Vector.newBuilder[Flow]
    if input.netDomesticDividends > PLN.Zero then
      flows += Flow(FIRM_ACCOUNT, HH_ACCOUNT, input.netDomesticDividends.toLong, FlowMechanism.EquityDomDividend.toInt)
    if input.foreignDividends > PLN.Zero then flows += Flow(FIRM_ACCOUNT, FOREIGN_ACCOUNT, input.foreignDividends.toLong, FlowMechanism.EquityForDividend.toInt)
    if input.dividendTax > PLN.Zero then flows += Flow(HH_ACCOUNT, GOV_ACCOUNT, input.dividendTax.toLong, FlowMechanism.EquityDividendTax.toInt)
    if input.govDividends > PLN.Zero then flows += Flow(FIRM_ACCOUNT, GOV_ACCOUNT, input.govDividends.toLong, FlowMechanism.EquityGovDividend.toInt)
    flows.result()
