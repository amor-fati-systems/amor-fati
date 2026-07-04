package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.Firm
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Processes incumbent firms through operating decisions and financing splits.
  */
private[firm] object FirmProcessingStage:

  def process(
      firms: Vector[Firm.State],
      ledgerFinancialState: LedgerFinancialState,
      lending: LendingConditions,
      rng: RandomStream,
      traceDecisions: Boolean,
  )(using p: SimParams): FirmProcessingResult =
    require(
      firms.length == ledgerFinancialState.firms.length,
      s"FirmEconomics.processFirms requires aligned firms and ledger firm balances, got ${firms.length} firms and ${ledgerFinancialState.firms.length} balance rows",
    )
    val outcomes = Vector.newBuilder[FirmOutcome]
    outcomes.sizeHint(firms.length)

    val bondAmounts = scala.collection.mutable.Map.empty[FirmId, PLN]
    var aggFlows    = FirmFlows.zero
    var i           = 0
    while i < firms.length do
      val outcome = FirmOutcomeProcessor.processOne(
        firm = firms(i),
        openingStocks = LedgerFinancialState.projectFirmFinancialStocks(ledgerFinancialState.firms(i)),
        allFirms = firms,
        ledgerFinancialState = ledgerFinancialState,
        lending = lending,
        rng = rng,
        traceDecisions = traceDecisions,
      )
      outcomes += outcome
      aggFlows = aggFlows + outcome.flows
      if outcome.bondAmt > PLN.Zero then bondAmounts.update(outcome.firm.id, outcome.bondAmt)
      i += 1

    FirmProcessingResult(outcomes.result(), aggFlows, bondAmounts.toMap, traceDecisions)
