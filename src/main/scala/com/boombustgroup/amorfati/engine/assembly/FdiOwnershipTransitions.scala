package com.boombustgroup.amorfati.engine.assembly

import com.boombustgroup.amorfati.agents.Firm
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.Share

/** FDI M&A ownership transitions at the post-month boundary. */
object FdiOwnershipTransitions:

  /** Monthly stochastic conversion of domestic firms to foreign ownership,
    * representing cross-border mergers and acquisitions.
    */
  def apply(firms: Vector[Firm.State], rng: RandomStream)(using p: SimParams): Vector[Firm.State] =
    if p.fdi.maProb > Share.Zero then
      firms.map: f =>
        if Firm.isAlive(f) && !f.foreignOwned &&
          f.initialSize >= p.fdi.maSizeMin &&
          p.fdi.maProb.sampleBelow(rng)
        then f.copy(foreignOwned = true)
        else f
    else firms
