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
    val bankCorpBonds      = (bankId: BankId) => CorporateBondOwnership.bankHolderFor(in.ledgerFinancialState, bankId)
    val rates              = in.banks.zip(bankStocks).zip(bsec.configs).map { case ((b, stocks), cfg) =>
      Banking.lendingRate(b, stocks, cfg, in.fiscal.lendingBaseRate, in.w.gov.bondYield, bankCorpBonds(b.id))
    }
    val creditDecision     = (bankId: Int, amt: PLN) =>
      val approval = Banking.creditApproval(in.banks(bankId), bankStocks(bankId), amt, rng, ccyb, bankCorpBonds(BankId(bankId)))
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
