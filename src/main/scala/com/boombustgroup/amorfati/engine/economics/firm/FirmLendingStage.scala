package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.{Banking, Firm}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.OperationalSignals
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.fp.EntityIds.BankId
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.PLN

/** Prepares bank-specific lending rates and approval functions for firms. */
private[firm] object FirmLendingStage:

  def prepare(in: StepInput, rng: RandomStream)(using p: SimParams): LendingConditions =
    val bsec               = in.w.bankingSector
    val nBanks             = in.banks.length
    val ccyb               = in.w.mechanisms.macropru.ccyb
    val bankStocks         = in.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks)
    requireAlignedBankInputs(in.banks, bankStocks, bsec.configs, in.ledgerFinancialState.banks.length)
    val bankCorpBonds      = (bankId: BankId) => CorporateBondOwnership.bankHolderFor(in.ledgerFinancialState, bankId)
    val approvalContexts   = in.banks.indices.map { idx =>
      val bank = in.banks(idx)
      Banking.CreditApprovalContext(
        bank = bank,
        ccyb = ccyb,
        corpBondHoldings = bankCorpBonds(bank.id),
        portfolio = Banking.CreditPortfolioContext(
          config = bsec.configs(idx),
          refRate = in.fiscal.lendingBaseRate,
          bondYield = in.w.gov.bondYield,
        ),
      )
    }.toVector
    val rates              = in.banks.indices.map { idx =>
      val b      = in.banks(idx)
      val stocks = bankStocks(idx)
      val cfg    = bsec.configs(idx)
      Banking.lendingRate(b, stocks, cfg, in.fiscal.lendingBaseRate, in.w.gov.bondYield, bankCorpBonds(b.id), ccyb, Banking.CreditProduct.FirmLoan)
    }.toVector
    val creditDecision     = (bankId: Int, amt: PLN) =>
      val approval = Banking.creditApproval(approvalContexts(bankId), bankStocks(bankId), Banking.CreditProduct.FirmLoan, amt, rng)
      Firm.CreditDecision.fromApproval(approval)
    val operationalSignals = OperationalSignals(
      sectorDemandMult = in.demand.sectorMults,
      sectorDemandPressure = in.demand.sectorDemandPressure,
      sectorHiringSignal = in.demand.sectorHiringSignal,
      operationalHiringSlack = in.labor.operationalHiringSlack,
    )
    val world              = in.w.copy(
      householdMarket = in.w.householdMarket.copy(
        marketWage = in.labor.newWage,
        reservationWage = in.fiscal.resWage,
      ),
    )
    LendingConditions(world, in.fiscal.m, operationalSignals, rates, creditDecision, nBanks)

  private def requireAlignedBankInputs(
      banks: Vector[Banking.BankState],
      bankStocks: Vector[Banking.BankFinancialStocks],
      configs: Vector[Banking.Config],
      ledgerBankCount: Int,
  ): Unit =
    val bankIds     = banks.map(_.id.toInt)
    val configIds   = configs.map(_.id.toInt)
    val expectedIds = (0 until banks.length).toVector
    if banks.length != bankStocks.length || banks.length != configs.length || ledgerBankCount != banks.length || bankIds != expectedIds || configIds != expectedIds
    then
      val configNames = configs.map(config => s"${config.id.toInt}:${config.name}")
      throw new IllegalArgumentException(
        "FirmLendingStage.prepare requires aligned bank rows, ledger bank stocks, and bank configs; " +
          s"banks=${banks.length} ids=${bankIds.mkString("[", ",", "]")}, " +
          s"ledgerBanks=$ledgerBankCount projectedStocks=${bankStocks.length}, " +
          s"configs=${configs.length} ids=${configIds.mkString("[", ",", "]")} names=${configNames.mkString("[", ",", "]")}, " +
          s"expectedIds=${expectedIds.mkString("[", ",", "]")}",
      )
