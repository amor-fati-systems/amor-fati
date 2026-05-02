package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.types.*

/** Quasi-fiscal entities: BGK + PFR consolidated configuration.
  *
  * BGK (Bank Gospodarstwa Krajowego) and PFR (Polski Fundusz Rozwoju) issue
  * state-guaranteed bonds off the MF balance sheet to finance infrastructure,
  * crisis programs, and subsidized lending. Outstanding stock uses the
  * 2026-04-30 quasi-fiscal bridge prior. The opening bond stock bridges the
  * domestic PDP-style fiscal-rule debt metric to ESA/EDP general-government
  * debt.
  *
  * @param initBondsOutstanding
  *   opening BGK/PFR-style quasi-fiscal bond stock in raw PLN (scaled by
  *   gdpRatio)
  * @param initNbpBondHoldings
  *   opening NBP holdings of quasi-fiscal bonds in raw PLN (scaled by gdpRatio)
  *
  * @param issuanceShare
  *   fraction of gov capital spending routed through BGK/PFR (NIK bridge prior:
  *   ~40%)
  * @param avgMaturityMonths
  *   average maturity of BGK/PFR bonds (longer than SPW, ~72 months / 6 years)
  * @param nbpAbsorptionShare
  *   share of new issuance bought by NBP when quasi-QE active (COVID: ~70%)
  * @param lendingShare
  *   fraction of issuance directed to subsidized lending (BGK kredyty, ~50%)
  * @param loanMaturityMonths
  *   average maturity of BGK subsidized loans (~120 months / 10 years)
  */
case class QuasiFiscalConfig(
    initBondsOutstanding: PLN = PLN(421653000000L),
    initNbpBondHoldings: PLN = PLN(106000000000L),
    issuanceShare: Share = Share.decimal(40, 2),
    avgMaturityMonths: Int = 72,
    nbpAbsorptionShare: Share = Share.decimal(70, 2),
    lendingShare: Share = Share.decimal(50, 2),
    loanMaturityMonths: Int = 120,
):
  require(initBondsOutstanding >= PLN.Zero, s"initBondsOutstanding must be non-negative: $initBondsOutstanding")
  require(initNbpBondHoldings >= PLN.Zero, s"initNbpBondHoldings must be non-negative: $initNbpBondHoldings")
  require(
    initNbpBondHoldings <= initBondsOutstanding,
    s"initNbpBondHoldings must not exceed initBondsOutstanding: $initNbpBondHoldings > $initBondsOutstanding",
  )
