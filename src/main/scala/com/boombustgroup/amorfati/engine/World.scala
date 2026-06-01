package com.boombustgroup.amorfati.engine

import com.boombustgroup.amorfati.agents.{Banking, Nbp, Region}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.markets.{FiscalBudget, OpenEconomy}
import com.boombustgroup.amorfati.types.*

/** Immutable snapshot of the simulation world at a month boundary.
  *
  * `World` owns only the root state surface. Domain-specific state segments,
  * timing signals, flow diagnostics, and bank diagnostics live in focused files
  * in this package so the month boundary stays navigable.
  */
case class World(
    inflation: Rate,                                                                  // CPI YoY inflation
    priceLevel: PriceIndex,                                                           // cumulative CPI index (base = 1.0)
    currentSigmas: Vector[Sigma],                                                     // per-sector sigma (Arthur increasing returns)
    gov: FiscalBudget.GovState,                                                       // government budget & debt
    nbp: Nbp.State,                                                                   // central bank: rate, QE regime, monthly FX operations
    bankingSector: Banking.MarketState,                                               // banking macro state: interbank conditions, configs, term structure
    forex: OpenEconomy.ForexState,                                                    // EUR/PLN, exports, imports, trade balance
    bop: OpenEconomy.BopState = OpenEconomy.BopState.zero,                            // balance of payments: NFA, CA, KA, FDI
    householdMarket: HouseholdMarketState = HouseholdMarketState.zero,                // explicit household wage-market state used in hot paths
    social: SocialState,                                                              // JST, ZUS, PPK, demographics
    financialMarkets: FinancialMarketsState,                                          // financial-market memory; ownership lives in LedgerFinancialState
    external: ExternalState,                                                          // GVC, immigration, tourism
    real: RealState,                                                                  // housing, mobility, investment, energy, automation
    mechanisms: MechanismsState,                                                      // macropru, expectations, BFG, informal economy
    plumbing: MonetaryPlumbingState,                                                  // reserve corridor, standing facilities, interbank
    pipeline: PipelineState = PipelineState.zero(SimParams.DefaultSectorDefs.length), // inter-step demand / hiring / fiscal signals
    flows: FlowState,                                                                 // single-step derived flow outputs for SFC identities
    regionalWages: Map[Region, PLN] = Map.empty,                                      // per-region wage levels (NUTS-1)
):
  def seedIn: DecisionSignals = pipeline.seedIn

  def derivedTotalPopulation: Int =
    social.demographics.workingAgePop + social.demographics.retirees

  def laborForcePopulation: Int =
    social.demographics.workingAgePop.max(1)

  def unemploymentRate(employed: Int): Share =
    val laborForce = laborForcePopulation
    Share.One - Share.fraction(Math.max(0, Math.min(employed, laborForce)), laborForce)

  def cachedMonthlyGdpProxy: PLN = flows.monthlyGdpProxy
