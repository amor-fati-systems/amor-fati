package com.boombustgroup.amorfati.engine.markets

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import com.boombustgroup.amorfati.agents.Nbp
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class FiscalRulesSpec extends AnyWordSpec with Matchers:

  given SimParams = SimParams.defaults

  // monthlyGdp = 250e9 → annualGdp = 3000e9
  private val baseInput = FiscalRules.Input(
    rawGovPurchases = PLN(100000000000L),
    prevGovSpend = PLN(95000000000L),
    cumulativeDebt = PLN(300000000000L),
    monthlyGdp = PLN(250000000000L), // 10% debt/GDP — well below any threshold
    prevRevenue = PLN(80000000000L),
    prevDeficit = PLN(5000000000L),
    inflation = Rate.decimal(25, 3),
    outputGap = Coefficient(0),
  )

  "FiscalRules.constrain" should {

    "apply no constraint at 30% debt/GDP" in {
      val in     = baseInput.copy(cumulativeDebt = PLN(900000000000L))
      val result = FiscalRules.constrain(in)
      // SRW blending still applies (always-on), but no consolidation cuts
      result.status.bindingRule should be <= 1
      result.constrainedGovPurchases should be > PLN.Zero
    }

    "cap spending via SRW ceiling" in {
      // Large raw spending, small prev spending — SRW should pull it down
      val in     = baseInput.copy(
        rawGovPurchases = PLN(200000000000L),
        prevGovSpend = PLN(90000000000L),
      )
      val result = FiscalRules.constrain(in)
      // SRW blends raw toward ceiling — result should be less than raw
      decimal(result.constrainedGovPurchases) should be < BigDecimal("200e9")
    }

    "loosen the SRW ceiling when unemployment slack is positive" in {
      val tight = baseInput.copy(
        rawGovPurchases = PLN(200000000000L),
        prevGovSpend = PLN(90000000000L),
        outputGap = Coefficient.Zero,
      )
      val slack = tight.copy(outputGap = Coefficient.decimal(50, 2))

      val tightResult = FiscalRules.constrain(tight)
      val slackResult = FiscalRules.constrain(slack)

      slackResult.constrainedGovPurchases should be >= tightResult.constrainedGovPurchases
      slackResult.constrainedGovPurchases should be <= slack.rawGovPurchases
    }

    "apply gradual SGP correction when deficit/GDP exceeds 3%" in {
      val in     = baseInput.copy(
        prevDeficit = PLN(15000000000L), // 15e9 / 250e9 = 6% > 3%
      )
      val result = FiscalRules.constrain(in)
      decimal(result.status.deficitToGdp) should be > BigDecimal("0.03")
      decimal(result.constrainedGovPurchases) should be < decimal(in.rawGovPurchases)
      decimal(result.constrainedGovPurchases) should be > BigDecimal("0.0")
    }

    "include prior non-purchase outlays in the SGP discretionary spending path" in {
      val withDebtService    = baseInput.copy(
        rawGovPurchases = PLN(120000000000L),
        prevGovSpend = PLN(90000000000L),
        prevRevenue = PLN(80000000000L),
        prevDeficit = PLN(40000000000L),
      )
      val withoutDebtService = withDebtService.copy(prevGovSpend = PLN(120000000000L))

      val constrained = FiscalRules.constrain(withDebtService)
      val looser      = FiscalRules.constrain(withoutDebtService)

      decimal(constrained.constrainedGovPurchases) should be < decimal(looser.constrainedGovPurchases)
    }

    "scale SGP correction with the deficit overshoot" in {
      val mildBreach   = baseInput.copy(
        rawGovPurchases = PLN(120000000000L),
        prevRevenue = PLN(80000000000L),
        prevDeficit = PLN(15000000000L), // 6% deficit/GDP
        prevGovSpend = PLN(95000000000L),
      )
      val severeBreach = mildBreach.copy(
        prevDeficit = PLN(120000000000L), // 48% deficit/GDP
        prevGovSpend = PLN(200000000000L),
      )

      val mildResult   = FiscalRules.constrain(mildBreach)
      val severeResult = FiscalRules.constrain(severeBreach)

      mildResult.status.bindingRule shouldBe 2
      severeResult.status.bindingRule shouldBe 2
      decimal(severeResult.constrainedGovPurchases) should be < decimal(mildResult.constrainedGovPurchases)
    }

    "apply Art. 86 (55%) consolidation" in {
      val in     = baseInput.copy(cumulativeDebt = PLN(1700000000000L)) // 56.7% debt/GDP
      val result = FiscalRules.constrain(in)
      decimal(result.status.debtToGdp) should be > BigDecimal("0.55")
      result.status.bindingRule should be >= 3
      decimal(result.status.spendingCutRatio) should be > BigDecimal("0.0")
    }

    "apply staged consolidation at Art. 216 (60%)" in {
      val in     = baseInput.copy(cumulativeDebt = PLN(1900000000000L)) // 63.3% debt/GDP
      val result = FiscalRules.constrain(in)
      decimal(result.status.debtToGdp) should be > BigDecimal("0.60")
      result.status.bindingRule shouldBe 4
      decimal(result.constrainedGovPurchases) should be < decimal(in.rawGovPurchases)
      decimal(result.constrainedGovPurchases) should be > decimal(in.prevRevenue)
    }

    "most restrictive rule wins when multiple bind" in {
      // Both SGP and Art. 86 binding
      val in     = baseInput.copy(
        cumulativeDebt = PLN(1700000000000L), // 56.7% — Art. 86 binding
        prevDeficit = PLN(120000000000L),     // huge deficit — SGP binding
      )
      val result = FiscalRules.constrain(in)
      // Art. 86 is more severe than SGP in the cascade
      result.status.bindingRule should be >= 2
    }

    "guarantee constrained ≤ raw always" in {
      val scenarios = Seq(
        baseInput,                                            // low debt
        baseInput.copy(cumulativeDebt = PLN(1700000000000L)), // 55%+
        baseInput.copy(cumulativeDebt = PLN(1900000000000L)), // 60%+
        baseInput.copy(prevDeficit = PLN(120000000000L)),     // high deficit
      )
      for in <- scenarios do
        val result = FiscalRules.constrain(in)
        decimal(result.constrainedGovPurchases) should be <= decimal(in.rawGovPurchases)
    }
  }

  "Nbp.piecewiseFiscalRisk (via bondYield)" should {

    "have zero fiscal risk at 35% debt/GDP" in {
      val y        = Nbp.bondYield(Rate.decimal(5, 2), Share.decimal(35, 2), Share.Zero, PLN.Zero, Rate.Zero)
      val expected = BigDecimal("0.05") + BigDecimal("0.005") // ref + termPremium
      decimal(y) shouldBe expected +- BigDecimal("0.001")
    }

    "have base-only risk at 45% debt/GDP" in {
      val y        = Nbp.bondYield(Rate.decimal(5, 2), Share.decimal(45, 2), Share.Zero, PLN.Zero, Rate.Zero)
      val baseRisk = BigDecimal("0.03") * BigDecimal("0.05")
      val expected = BigDecimal("0.05") + BigDecimal("0.005") + baseRisk
      decimal(y) shouldBe expected +- BigDecimal("0.001")
    }

    "be monotonically non-decreasing with debt/GDP" in {
      val debtLevels = Seq(
        BigDecimal("0.30"),
        BigDecimal("0.35"),
        BigDecimal("0.40"),
        BigDecimal("0.42"),
        BigDecimal("0.45"),
        BigDecimal("0.50"),
        BigDecimal("0.55"),
        BigDecimal("0.56"),
        BigDecimal("0.60"),
        BigDecimal("0.62"),
        BigDecimal("0.70"),
        BigDecimal("0.90"),
      )
      val yields     = debtLevels.map(d => decimal(Nbp.bondYield(Rate.decimal(5, 2), shareBD(d), Share.Zero, PLN.Zero, Rate.Zero)))
      for (y1, y2) <- yields.zip(yields.tail) do y2 should be >= y1
    }

    "increase above 40% threshold" in {
      val yBelow = Nbp.bondYield(Rate.decimal(5, 2), Share.decimal(39, 2), Share.Zero, PLN.Zero, Rate.Zero)
      val yAbove = Nbp.bondYield(Rate.decimal(5, 2), Share.decimal(42, 2), Share.Zero, PLN.Zero, Rate.Zero)
      decimal(yAbove) should be > decimal(yBelow)
    }

    "never exceed FiscalRiskCap (10%)" in {
      val y = Nbp.bondYield(Rate.decimal(5, 2), Share.decimal(90, 2), Share.Zero, PLN.Zero, Rate.Zero)
      decimal(y) should be <= BigDecimal("0.05") + BigDecimal("0.005") + BigDecimal("0.10") + BigDecimal("0.001")
    }
  }
