package com.boombustgroup.amorfati.engine

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.markets.{CorporateBondMarket, EquityMarket, GvcTrade, HousingMarket}
import com.boombustgroup.amorfati.engine.mechanisms.{Expectations, Macroprudential, SectoralMobility}
import com.boombustgroup.amorfati.types.*

/** Social security system and local government state. */
case class SocialState(
    jst: Jst.State,                                 // local government (JST): budget and debt; cash lives in LedgerFinancialState
    zus: SocialSecurity.ZusState,                   // ZUS: contributions, pensions, FUS balance
    nfz: SocialSecurity.NfzState,                   // NFZ: health insurance contributions, spending, balance
    ppk: SocialSecurity.PpkState,                   // PPK monthly contribution flow; holdings live in LedgerFinancialState
    demographics: SocialSecurity.DemographicsState, // working-age, retirees, monthly retirements
    earmarked: EarmarkedFunds.State,                // FP, PFRON, FGSP
)
object SocialState:
  val zero: SocialState = SocialState(
    jst = Jst.State.zero,
    zus = SocialSecurity.ZusState.zero,
    nfz = SocialSecurity.NfzState.zero,
    ppk = SocialSecurity.PpkState.zero,
    demographics = SocialSecurity.DemographicsState.zero,
    earmarked = EarmarkedFunds.State.zero,
  )

/** Explicit household labor-market state carried outside aggregate caches. */
case class HouseholdMarketState(
    marketWage: PLN,
    reservationWage: PLN,
)
object HouseholdMarketState:
  val zero: HouseholdMarketState = HouseholdMarketState(PLN.Zero, PLN.Zero)

  def fromAggregates(agg: Household.Aggregates): HouseholdMarketState =
    HouseholdMarketState(
      marketWage = agg.marketWage,
      reservationWage = agg.reservationWage,
    )

/** Financial-market memory carried by `World`.
  *
  * This is deliberately not the ownership ledger. Keep market prices,
  * last-month diagnostics, and unsupported transition fields here; keep
  * ledger-contracted financial stocks in `LedgerFinancialState`.
  */
case class FinancialMarketsState(
    equity: EquityMarket.State,                // GPW market prices, returns, issuance, dividends
    corporateBonds: CorporateBondMarket.State, // Catalyst pricing and last-month diagnostics
    insurance: Insurance.State,                // monthly premium, claims, investment-income diagnostics
    nbfi: Nbfi.State,                          // monthly TFI/NBFI origination, defaults, deposit-drain diagnostics
    quasiFiscal: QuasiFiscal.State,            // quasi-fiscal monthly issuance/lending diagnostics
)
object FinancialMarketsState:
  val zero: FinancialMarketsState = FinancialMarketsState(
    equity = EquityMarket.zero,
    corporateBonds = CorporateBondMarket.zero,
    insurance = Insurance.State.zero,
    nbfi = Nbfi.State.zero,
    quasiFiscal = QuasiFiscal.State.zero,
  )

/** Structural external-sector state carried across steps. */
case class ExternalState(
    gvc: GvcTrade.State,                               // GVC: disruption, foreign prices, sector trade
    immigration: Immigration.State,                    // immigrant stock, monthly flows, remittances
    tourismSeasonalFactor: Multiplier = Multiplier.One, // seasonal multiplier (base = 1.0)
)
object ExternalState:
  val zero: ExternalState = ExternalState(
    gvc = GvcTrade.zero,
    immigration = Immigration.State.zero,
  )

/** Real economy state: physical and wealth structure. */
case class RealState(
    housing: HousingMarket.State,                   // price index, mortgage stock, regional sub-markets
    sectoralMobility: SectoralMobility.State,       // cross-sector hires, quits, mobility rate
    grossInvestment: PLN = PLN.Zero,                // aggregate GFCF by firms
    aggGreenInvestment: PLN = PLN.Zero,             // green investment (renewables, energy efficiency)
    aggGreenCapital: PLN = PLN.Zero,                // green capital stock across all firms
    etsPrice: Multiplier = Multiplier.Zero,         // EU ETS allowance price (EUR/tCO2)
    productivityIndex: Multiplier = Multiplier.One, // baseline real productivity trend multiplier
    automationRatio: Share = Share.Zero,            // share of Automated firms
    hybridRatio: Share = Share.Zero,                // share of Hybrid firms
)
object RealState:
  val zero: RealState = RealState(
    housing = HousingMarket.zero,
    sectoralMobility = SectoralMobility.zero,
  )

/** Macro-mechanism state: policies and endogenous phenomena carried across
  * steps.
  */
case class MechanismsState(
    macropru: Macroprudential.State,         // CCyB, credit-to-GDP gap
    expectations: Expectations.State,        // inflation forecast, credibility, forward guidance
    bfgFundBalance: PLN = PLN.Zero,          // cumulative BFG resolution fund
    informalCyclicalAdj: Share = Share.Zero, // smoothed cyclical shadow-economy adjustment
    nextTaxShadowShare: Share = Share.Zero,  // next-period smoothed tax-side shadow share
)
object MechanismsState:
  def zero(using SimParams): MechanismsState = MechanismsState(
    macropru = Macroprudential.State.zero,
    expectations = Expectations.initial,
  )

/** NBP monetary plumbing. */
case class MonetaryPlumbingState(
    reserveInterestTotal: PLN = PLN.Zero, // NBP interest on required reserves
    standingFacilityNet: PLN = PLN.Zero,  // net standing facility income (deposit - Lombard)
    interbankInterestNet: PLN = PLN.Zero, // net interbank interest flows
    depositFacilityUsage: PLN = PLN.Zero, // voluntary reserves at NBP above minimum
    fofResidual: PLN = PLN.Zero,          // flow-of-funds residual
)
object MonetaryPlumbingState:
  val zero: MonetaryPlumbingState = MonetaryPlumbingState()
