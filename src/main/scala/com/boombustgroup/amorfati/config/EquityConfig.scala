package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.types.*

/** GPW (Warsaw Stock Exchange) equity market: index, issuance, household
  * equity, and dividends.
  *
  * Models the WIG index with P/E-driven valuation, equity issuance by large
  * firms (Catalyst), household equity participation with wealth effects (Case,
  * Quigley & Shiller 2005), dividend distribution with Belka tax, and foreign
  * ownership channel. Calibrated to GPW bridge prior, KNF/KDPW bridge prior
  * data.
  *
  * `initMcap` is in raw PLN — scaled by `gdpRatio` in `SimParams.defaults`.
  *
  * @param initIndex
  *   initial WIG index value (GPW bridge prior: ~2,400)
  * @param initMcap
  *   initial market capitalization in raw PLN (GPW bridge prior: ~1.4 bln PLN,
  *   scaled by gdpRatio)
  * @param peMean
  *   long-run mean P/E ratio for reversion (GPW bridge prior: ~10)
  * @param divYield
  *   average dividend yield (GPW bridge prior: ~5.7%)
  * @param foreignShare
  *   share of market cap held by foreign investors (KNF/KDPW bridge prior:
  *   ~67%)
  * @param listedProfitShare
  *   share of aggregate modeled firm profits attributable to GPW-listed firms.
  *   The firm population covers the whole economy; only the listed-market slice
  *   should feed GPW dividend and earnings-yield channels.
  * @param issuanceFrac
  *   annual equity issuance as fraction of market cap (eligible large firms)
  * @param issuanceMinSize
  *   minimum firm size (employees) for equity issuance eligibility
  * @param hhEquityFrac
  *   fraction of household savings allocated to equities
  * @param wealthEffectMpc
  *   marginal propensity to consume from equity wealth gains
  * @param divTax
  *   dividend withholding tax rate (Belka tax, Ustawa o PIT Art. 30a: 19%)
  */
case class EquityConfig(
    initIndex: PriceIndex = PriceIndex(2400),
    initMcap: PLN = PLN(1400000000000L), // raw — scaled by gdpRatio
    peMean: Scalar = Scalar(10),
    divYield: Rate = Rate.decimal(57, 3),
    foreignShare: Share = Share.decimal(67, 2),
    listedProfitShare: Share = Share.decimal(10, 2),
    issuanceFrac: Share = Share.decimal(10, 2),
    issuanceMinSize: Int = 5,
    hhEquityFrac: Share = Share.decimal(7, 2),
    wealthEffectMpc: Share = Share.decimal(2, 2),
    divTax: Rate = Rate.decimal(19, 2),
)
