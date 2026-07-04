package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.Firm
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Financing-channel split for firm CAPEX and technology loans. */
private[firm] object FirmFinancing:

  def split(r: Firm.Result)(using p: SimParams): FinancingSplit =
    val channels = financingChannelAmounts(r.newLoan, r.techNewLoan, Firm.workerCount(r.firm))
    val stocks   = r.financialStocks.copy(
      firmLoan = r.financialStocks.firmLoan - channels.equity - channels.bonds,
      equity = r.financialStocks.equity + channels.equity,
    )

    FinancingSplit(channels.bankLoan, channels.equity, channels.bonds, channels.techBankLoan, channels.techBonds, r.firm, stocks)

  private[amorfati] def financingChannelAmounts(newLoan: PLN, techNewLoan: PLN, workers: Int)(using p: SimParams): FinancingChannelAmounts =
    val requestedTechLoan = techNewLoan.min(newLoan).max(PLN.Zero)

    val (afterEquityLoan, equityAmt, afterEquityTechLoan) =
      if newLoan > PLN.Zero && workers >= p.equity.issuanceMinSize
      then
        val eq       = newLoan * p.equity.issuanceFrac
        val adj      = newLoan - eq
        val techEq   = proratedPln(eq, requestedTechLoan, newLoan)
        val techLeft = (requestedTechLoan - techEq).max(PLN.Zero).min(adj)
        (adj, eq, techLeft)
      else (newLoan, PLN.Zero, requestedTechLoan)

    val (finalLoan, bondAmt, techBankLoan, techBondAmt) =
      if afterEquityLoan > PLN.Zero && workers >= p.corpBond.minSize then
        val ba       = afterEquityLoan * p.corpBond.issuanceFrac
        val adj      = afterEquityLoan - ba
        val techBond = proratedPln(ba, afterEquityTechLoan, afterEquityLoan)
        val techBank = (afterEquityTechLoan - techBond).max(PLN.Zero).min(adj)
        (adj, ba, techBank, techBond)
      else (afterEquityLoan, PLN.Zero, afterEquityTechLoan, PLN.Zero)

    FinancingChannelAmounts(finalLoan, equityAmt, bondAmt, techBankLoan, techBondAmt)

  private[amorfati] def automationTechLoanAmount(techBankLoan: PLN, techBondAmt: PLN, bondAmt: PLN, revertedBond: PLN): PLN =
    techBankLoan + proratedPln(revertedBond, techBondAmt, bondAmt)

  private[firm] def proratedPln(amount: PLN, part: PLN, total: PLN): PLN =
    if amount <= PLN.Zero || part <= PLN.Zero || total <= PLN.Zero then PLN.Zero
    else if part >= total then amount
    else
      val raw = ((BigInt(amount.distributeRaw) * BigInt(part.distributeRaw)) / BigInt(total.distributeRaw)).toLong
      PLN.fromRaw(raw).min(amount)
