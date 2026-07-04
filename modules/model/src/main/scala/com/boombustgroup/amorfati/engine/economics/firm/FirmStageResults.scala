package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.{Firm, Household}
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.types.*

/** Catalyst absorption result after unsold corporate bonds are reconciled. */
private[firm] final case class BondAbsorptionResult(
    firms: Vector[Firm.State],
    financialStocks: Vector[Firm.FinancialStocks],
    ledgerFinancialState: LedgerFinancialState,
    sumNewLoans: PLN,
    corpBondAbsorption: Share,
    actualBondIssuance: PLN,
    bondReversionByFirm: Map[FirmId, PLN],
)

/** Intermediate-goods market result with aligned firm stock changes. */
private[firm] final case class IntermediateResult(
    firms: Vector[Firm.State],
    financialStocks: Vector[Firm.FinancialStocks],
    totalPaid: PLN,
    effectiveCapacities: Vector[PLN],
)

/** Calvo repricing result over I-O-adjusted firms. */
private[firm] final case class PricingResult(
    firms: Vector[Firm.State],
    markupInflation: Rate,
)

/** Labor matching result after separations, migration, search, and wage update.
  */
private[firm] final case class LaborMarketResult(
    households: Vector[Household.State],
    crossSectorHires: Int,
    hires: Int,
    hireCapacity: Int,
    newHouseholdFinancialStocksById: Map[HhId, Household.FinancialStocks],
)

/** Firm default and per-bank interest/NPL aggregates. */
private[firm] final case class NplResult(
    nplNew: PLN,
    nplLoss: PLN,
    totalBondDefault: PLN,
    firmDeaths: Int,
    intIncome: PLN,
    perBankNplDebt: Vector[PLN],
    perBankIntIncome: Vector[PLN],
    perBankWorkers: Vector[Int],
    defaultedBondFirmIds: Set[FirmId],
)
