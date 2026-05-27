package com.boombustgroup.amorfati.engine.mechanisms

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
    if p.fdi.maProb <= Share.Zero then firms
    else
      var firstChanged = -1
      var i            = 0
      while i < firms.length && firstChanged < 0 do
        if shouldConvert(firms(i), rng) then firstChanged = i
        else i += 1

      if firstChanged < 0 then firms
      else
        val updated = Vector.newBuilder[Firm.State]
        updated.sizeHint(firms.length)

        i = 0
        while i < firstChanged do
          updated += firms(i)
          i += 1

        updated += firms(firstChanged).copy(foreignOwned = true)
        i = firstChanged + 1
        while i < firms.length do
          val firm = firms(i)
          updated += (if shouldConvert(firm, rng) then firm.copy(foreignOwned = true) else firm)
          i += 1

        updated.result()

  private def shouldConvert(firm: Firm.State, rng: RandomStream)(using p: SimParams): Boolean =
    Firm.isAlive(firm) && !firm.foreignOwned &&
      firm.initialSize >= p.fdi.maSizeMin &&
      p.fdi.maProb.sampleBelow(rng)
