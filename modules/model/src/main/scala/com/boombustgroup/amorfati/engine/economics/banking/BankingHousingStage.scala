package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.engine.markets.HousingMarket
import com.boombustgroup.amorfati.engine.mechanisms.YieldCurve
import com.boombustgroup.amorfati.types.*

/** Computes the same-month housing and mortgage leg owned by banking close. */
private[banking] object BankingHousingStage:

  def compute(in: StepInput)(using p: SimParams): HousingResult =
    val unempRate              = in.world.unemploymentRate(in.labor.employed)
    val openingHousing         =
      HousingMarket.withMortgageStock(in.world.real.housing, LedgerFinancialState.householdMortgageStock(in.ledgerFinancialState))
    val prevMortgageRate       = openingHousing.avgMortgageRate
    val mortgageBaseRate: Rate =
      val exp = in.world.mechanisms.expectations
      YieldCurve
        .compute(
          in.world.bankingSector.interbankRate,
          nplRatio = Banking
            .aggregateFromBankStocks(
              in.banks,
              in.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks),
              bankId => CorporateBondOwnership.bankHolderFor(in.ledgerFinancialState, bankId),
            )
            .nplRatio,
          credibility = exp.credibility,
          expectedInflation = exp.expectedInflation,
          targetInflation = p.monetary.targetInfl,
        )
        .wibor3m
    val mortgageRate           = mortgageBaseRate + p.housing.mortgageSpread
    val housingAfterPrice      = HousingMarket.step(
      HousingMarket.StepInput(
        prev = openingHousing,
        mortgageRate = mortgageRate,
        inflation = in.priceEquity.newInfl,
        incomeGrowth = in.labor.wageGrowth.toMultiplier.toRate,
        employed = in.labor.employed,
        prevMortgageRate = prevMortgageRate,
      ),
    )
    val housingAfterOrig       =
      HousingMarket.processOrigination(housingAfterPrice, in.householdIncome.totalIncome, mortgageRate, true)
    val scheduledPrincipal     = Some(in.householdIncome.hhAgg.totalMortgagePrincipal)
    val mortgageFlows          = HousingMarket.processMortgageFlows(housingAfterOrig, mortgageRate, unempRate, scheduledPrincipal)
    val housingAfterFlows      = HousingMarket.applyFlows(housingAfterOrig, mortgageFlows)

    HousingResult(housingAfterFlows = housingAfterFlows, mortgageFlows = mortgageFlows)
