package com.boombustgroup.amorfati.engine.ledger

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.types.*

/** Ledger-backed reserve diagnostics for active commercial banks. */
object BankReserveDiagnostics:

  def depositFacilityUsage(
      banks: Vector[Banking.BankState],
      ledgerFinancialState: LedgerFinancialState,
  ): PLN =
    var total = PLN.Zero
    var i     = 0
    while i < banks.length do
      val bank = banks(i)
      if !bank.failed then
        val ledgerIndex = bank.id.toInt
        require(
          ledgerFinancialState.banks.isDefinedAt(ledgerIndex),
          s"BankReserveDiagnostics.depositFacilityUsage missing ledger row for active bank ${bank.id}",
        )
        val reserve     = ledgerFinancialState.banks(ledgerIndex).reserve
        if reserve > PLN.Zero then total += reserve
      i += 1
    total
