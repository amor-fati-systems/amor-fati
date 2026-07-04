package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.markets.{BondAuction, CorporateBondMarket}
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.Distribute

/** Government-bond and bank corporate-bond settlement owned by the banking
  * stage after reserve/interbank settlement has produced final bank liquidity.
  */
private[banking] object BankBondWaterfall:

  /** Compute raw bond-waterfall requests. Actual allocations happen in `run`
    * through holder-resolved sales.
    */
  def inputs(in: StepInput, newGovBondOutstanding: PLN): BondWaterfallInputs =
    val actualBondChange = newGovBondOutstanding - in.ledgerFinancialState.government.govBondOutstanding
    val insRequested     =
      (in.openEconomy.nonBank.newInsuranceBalances.govBondHoldings - in.ledgerFinancialState.insurance.govBondHoldings).max(PLN.Zero)
    val tfiRequested     =
      (in.openEconomy.nonBank.newNbfiBalances.tfiGovBondHoldings - in.ledgerFinancialState.funds.nbfi.govBondHoldings).max(PLN.Zero)
    val prevEr           = in.world.forex.exchangeRate
    val currEr           = in.openEconomy.external.newForex.exchangeRate
    val erChange         = currEr.deviationFrom(prevEr).toCoefficient
    BondWaterfallInputs(
      actualBondChange = actualBondChange,
      qeRequested = in.openEconomy.monetary.qePurchaseAmount,
      ppkRequested = in.labor.rawPpkBondPurchase,
      insRequested = insRequested,
      tfiRequested = tfiRequested,
      erChange = erChange,
    )

  def settleCorpBondHoldings(
      previous: Vector[PLN],
      previousAggregateStock: CorporateBondMarket.StockState,
      nextAggregateStock: CorporateBondMarket.StockState,
      totalBondIssuance: PLN,
      perBankWorkers: Vector[Int],
  )(using p: SimParams): Vector[PLN] =
    require(
      previous.length == perBankWorkers.length,
      s"BankBondWaterfall.settleCorpBondHoldings requires aligned bank rows, got previous.length=${previous.length} and perBankWorkers.length=${perBankWorkers.length}",
    )
    val bankIssuance   = CorporateBondMarket.processIssuance(CorporateBondMarket.StockState.zero, totalBondIssuance).bankHoldings
    val bankReduction  = (previousAggregateStock.bankHoldings + bankIssuance - nextAggregateStock.bankHoldings).max(PLN.Zero)
    val afterReduction = reduceBankCorpBondHoldings(previous, bankReduction)
    val issuance       = allocateBankCorpBondIssuance(bankIssuance, perBankWorkers)
    afterReduction.zip(issuance).map(_ + _)

  /** Executes the government-bond waterfall after reserve settlement and HTM
    * reclassification. Issuer stock changes land on bank portfolios first; the
    * waterfall then sells actual available bonds to foreign, NBP, PPK,
    * insurance, and TFI buyers in sequence.
    */
  def run(
      in: StepInput,
      wf: BondWaterfallInputs,
      banks: Vector[Banking.BankState],
      financialStocks: Vector[Banking.BankFinancialStocks],
  )(using p: SimParams): BondWaterfallResult =
    val afterBonds    =
      if wf.actualBondChange > PLN.Zero then
        Banking.allocateBondIssuance(banks, financialStocks, wf.actualBondChange, in.openEconomy.monetary.newGovBondMarketYield)
      else if wf.actualBondChange < PLN.Zero then
        Banking.allocateBondRedemption(banks, financialStocks, PLN.fromRaw(-wf.actualBondChange.toLong), in.openEconomy.monetary.newGovBondMarketYield)
      else Banking.BankStockState(banks, financialStocks)
    val primaryByBank = afterBonds.financialStocks
      .zip(financialStocks)
      .map((after, before) => Banking.govBondHoldings(after) - Banking.govBondHoldings(before))

    val bankDeposits  = afterBonds.financialStocks.map(_.totalDeposits).sumPln
    val auctionResult = BondAuction.auction(
      newIssuance = wf.actualBondChange.max(PLN.Zero),
      bankBondCapacity = bankDeposits * p.fiscal.bankBondAbsorptionShare,
      govBondMarketYield = in.openEconomy.monetary.newGovBondMarketYield,
      erChange = wf.erChange,
    )
    val foreignSale   = Banking.sellToBuyer(afterBonds.banks, afterBonds.financialStocks, auctionResult.foreignAbsorbed)
    val qeSale        = Banking.sellToBuyer(foreignSale.banks, foreignSale.financialStocks, wf.qeRequested)
    val ppkSale       = Banking.sellToBuyer(qeSale.banks, qeSale.financialStocks, wf.ppkRequested)
    val insSale       = Banking.sellToBuyer(ppkSale.banks, ppkSale.financialStocks, wf.insRequested)
    val tfiSale       = Banking.sellToBuyer(insSale.banks, insSale.financialStocks, wf.tfiRequested)

    val finalNbp                = in.openEconomy.monetary.postFxNbp.copy(qeCumulative = in.openEconomy.monetary.postFxNbp.qeCumulative + qeSale.actualSold)
    val finalNbpFinancialStocks = in.openEconomy.monetary.postFxNbpFinancialStocks.copy(
      govBondHoldings = in.openEconomy.monetary.postFxNbpFinancialStocks.govBondHoldings + qeSale.actualSold,
    )
    val finalInsuranceBalances  = in.openEconomy.nonBank.newInsuranceBalances.copy(
      govBondHoldings = in.ledgerFinancialState.insurance.govBondHoldings + insSale.actualSold,
    )
    val finalNbfiBalances       = in.openEconomy.nonBank.newNbfiBalances.copy(
      tfiGovBondHoldings = in.ledgerFinancialState.funds.nbfi.govBondHoldings + tfiSale.actualSold,
    )

    BondWaterfallResult(
      banks = tfiSale.banks,
      financialStocks = tfiSale.financialStocks,
      finalNbp = finalNbp,
      finalNbpFinancialStocks = finalNbpFinancialStocks,
      finalPpk = in.labor.newPpk,
      finalPpkGovBondHoldings = in.ledgerFinancialState.funds.ppkGovBondHoldings + ppkSale.actualSold,
      finalInsurance = in.openEconomy.nonBank.newInsurance,
      finalInsuranceBalances = finalInsuranceBalances,
      finalNbfi = in.openEconomy.nonBank.newNbfi,
      finalNbfiBalances = finalNbfiBalances,
      foreignBondHoldings = in.ledgerFinancialState.foreign.govBondHoldings + foreignSale.actualSold,
      bidToCover = auctionResult.bidToCover,
      govBondRuntimeMovements = GovBondRuntimeMovements(
        primaryByBank = primaryByBank,
        foreignPurchaseByBank = foreignSale.soldByBank,
        nbpQePurchaseByBank = qeSale.soldByBank,
        ppkPurchaseByBank = ppkSale.soldByBank,
        insurancePurchaseByBank = insSale.soldByBank,
        tfiPurchaseByBank = tfiSale.soldByBank,
      ),
    )

  private def allocateBankCorpBondIssuance(issuance: PLN, perBankWorkers: Vector[Int]): Vector[PLN] =
    if perBankWorkers.isEmpty then Vector.empty
    else if issuance <= PLN.Zero then Vector.fill(perBankWorkers.length)(PLN.Zero)
    else
      val weights =
        val workerWeights = perBankWorkers.map(_.toLong.max(0L)).toArray
        if workerWeights.exists(_ > 0L) then workerWeights
        else Array.fill(perBankWorkers.length)(1L)
      Distribute.distribute(issuance.distributeRaw, weights).map(PLN.fromRaw).toVector

  private def reduceBankCorpBondHoldings(holdings: Vector[PLN], reduction: PLN): Vector[PLN] =
    if holdings.isEmpty then Vector.empty
    else if reduction <= PLN.Zero then holdings
    else
      val total           = holdings.sumPln
      val actualReduction = reduction.min(total)
      val reductions      = Distribute.distribute(actualReduction.distributeRaw, holdings.map(_.distributeRaw).toArray)
      holdings.zip(reductions).map((holding, rawReduction) => (holding - PLN.fromRaw(rawReduction)).max(PLN.Zero))
