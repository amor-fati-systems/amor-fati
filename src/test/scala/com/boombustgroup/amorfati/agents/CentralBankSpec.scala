package com.boombustgroup.amorfati.agents

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

class CentralBankSpec extends AnyFlatSpec with Matchers:

  given SimParams          = SimParams.defaults
  private val p: SimParams = summon[SimParams]
  private val zeroStocks   = Nbp.FinancialStocks(PLN.Zero, PLN.Zero)

  // --- bondYield ---

  "Nbp.bondYield" should "include calibrated fiscal risk premium" in {
    val y = Nbp.bondYield(Rate.decimal(5, 2), Share.decimal(50, 2), Share.Zero, PLN.Zero, Rate.Zero)
    // debtToGdp=0.50 > 0.40 -> fiscalRisk = 0.03 * 0.10 = 0.003
    // yield = max(0.05, 0.025) + 0.005 + 0.003 - 0 - 0 = 0.058
    decimal(y) shouldBe BigDecimal("0.058") +- BigDecimal("0.001")
  }

  it should "keep Poland-like debt levels below crisis pricing" in {
    val y = Nbp.bondYield(Rate.decimal(375, 4), Share.decimal(70, 2), Share.Zero, PLN.Zero, Rate.Zero)
    decimal(y) shouldBe BigDecimal("0.0655") +- BigDecimal("0.001")
  }

  it should "use Bund as a floor for the long end of the sovereign curve" in {
    val y = Nbp.bondYield(Rate.decimal(1, 2), Share.decimal(35, 2), Share.Zero, PLN.Zero, Rate.Zero)
    decimal(y) shouldBe BigDecimal("0.030") +- BigDecimal("0.001")
  }

  it should "increase with debtToGdp (fiscal risk premium)" in {
    val low  = Nbp.bondYield(Rate.decimal(5, 2), Share.decimal(30, 2), Share.Zero, PLN.Zero, Rate.Zero)
    val high = Nbp.bondYield(Rate.decimal(5, 2), Share.decimal(70, 2), Share.Zero, PLN.Zero, Rate.Zero)
    decimal(high) should be > decimal(low)
  }

  it should "decrease with nbpBondGdpShare (QE compression)" in {
    val noQe   = Nbp.bondYield(Rate.decimal(5, 2), Share.decimal(50, 2), Share.Zero, PLN.Zero, Rate.Zero)
    val withQe = Nbp.bondYield(Rate.decimal(5, 2), Share.decimal(50, 2), Share.decimal(20, 2), PLN.Zero, Rate.Zero)
    decimal(withQe) should be < decimal(noQe)
  }

  it should "apply foreign demand discount when NFA > 0" in {
    val nfaNeg = Nbp.bondYield(Rate.decimal(5, 2), Share.decimal(50, 2), Share.Zero, PLN(-1000), Rate.Zero)
    val nfaPos = Nbp.bondYield(Rate.decimal(5, 2), Share.decimal(50, 2), Share.Zero, PLN(1000), Rate.Zero)
    decimal(nfaPos) should be < decimal(nfaNeg)
  }

  it should "have a floor at 0" in {
    val y = Nbp.bondYield(Rate.decimal(1, 2), Share.decimal(30, 2), Share.decimal(50, 2), PLN(1000), Rate.Zero)
    decimal(y) should be >= BigDecimal("0.0")
  }

  it should "have zero fiscal risk when debtToGdp <= 0.40" in {
    val y1 = Nbp.bondYield(Rate.decimal(5, 2), Share.decimal(30, 2), Share.Zero, PLN.Zero, Rate.Zero)
    val y2 = Nbp.bondYield(Rate.decimal(5, 2), Share.decimal(40, 2), Share.Zero, PLN.Zero, Rate.Zero)
    // Both below threshold -> same yield (only termPremium differs)
    y1 shouldBe y2
  }

  // --- shouldActivateQe ---

  "Nbp.shouldActivateQe" should "be true at ZLB with deflation" in {
    // NBP March 2020 precedent
    Nbp.shouldActivateQe(p.monetary.rateFloor, Rate.decimal(-5, 2), Rate.decimal(-2, 2)) shouldBe true
  }

  it should "be true at ZLB when expectations remain deeply below target" in {
    Nbp.shouldActivateQe(p.monetary.rateFloor, Rate.decimal(15, 3), Rate.decimal(-2, 2)) shouldBe true
  }

  // --- shouldTaperQe ---

  "Nbp.shouldTaperQe" should "be true when inflation exceeds target" in {
    Nbp.shouldTaperQe(rateBD(decimal(p.monetary.targetInfl) + BigDecimal("0.01")), rateBD(decimal(p.monetary.targetInfl) + BigDecimal("0.005"))) shouldBe true
  }

  it should "be false when expectations remain below target" in {
    Nbp.shouldTaperQe(rateBD(decimal(p.monetary.targetInfl) + BigDecimal("0.01")), rateBD(decimal(p.monetary.targetInfl) - BigDecimal("0.01"))) shouldBe false
  }

  // --- executeQe ---

  "Nbp.executeQe" should "return 0 purchase when not active" in {
    val stocks   = Nbp.FinancialStocks(PLN(1000), PLN.Zero)
    val nbp      = Nbp.State(Rate.decimal(5, 2), false, PLN.Zero, PLN.Zero)
    val qeResult = Nbp.executeQe(nbp, stocks, PLN(5000), PLN(10000000000L), Rate.decimal(-5, 2), Rate.decimal(-2, 2))
    qeResult.requestedPurchase shouldBe PLN.Zero
    qeResult.nbpState shouldBe nbp
  }

  it should "not exceed available bank bond holdings" in {
    val nbp       = Nbp.State(Rate.decimal(5, 2), true, PLN.Zero, PLN.Zero)
    val bankBonds = PLN(100)
    val qeResult  = Nbp.executeQe(nbp, zeroStocks, bankBonds, PLN(1000000000000L), Rate.decimal(-5, 2), Rate.decimal(-2, 2))
    decimal(qeResult.requestedPurchase) should be <= decimal(bankBonds)
  }

  it should "not exceed max GDP share" in {
    val nbp       = Nbp.State(Rate.decimal(5, 2), true, PLN.Zero, PLN.Zero)
    val annualGdp = PLN(1000)
    val maxByGdp  = decimal(p.monetary.qeMaxGdpShare) * decimal(annualGdp)
    val qeResult  = Nbp.executeQe(nbp, zeroStocks, PLN(1000000000000L), annualGdp, Rate.decimal(-5, 2), Rate.decimal(-2, 2))
    decimal(qeResult.requestedPurchase) should be <= maxByGdp
  }

  it should "return positive purchase when active with available bonds" in {
    val nbp      = Nbp.State(Rate.decimal(5, 2), true, PLN.Zero, PLN.Zero)
    val qeResult = Nbp.executeQe(nbp, zeroStocks, PLN(1000000000000L), PLN(1000000000000L), Rate.decimal(-5, 2), Rate.decimal(-2, 2))
    decimal(qeResult.requestedPurchase) should be > BigDecimal("0.0")
    // executeQe no longer updates cumulative; that happens in BankingEconomics
    qeResult.nbpState.qeCumulative shouldBe nbp.qeCumulative
  }

  it should "scale QE more aggressively in a lower-bound regime" in {
    val zlbNbp    = Nbp.State(p.monetary.rateFloor, true, PLN.Zero, PLN.Zero)
    val normalNbp = Nbp.State(Rate.decimal(3, 2), true, PLN.Zero, PLN.Zero)
    val annualGdp = PLN(1000000000000L)
    val zlbQe     = Nbp.executeQe(zlbNbp, zeroStocks, PLN(1000000000000L), annualGdp, Rate.decimal(-5, 2), Rate.decimal(-2, 2))
    val normalQe  = Nbp.executeQe(normalNbp, zeroStocks, PLN(1000000000000L), annualGdp, Rate.decimal(15, 3), Rate.decimal(2, 2))
    decimal(zlbQe.requestedPurchase) should be > decimal(normalQe.requestedPurchase)
  }

  // --- NbpState defaults ---

  "Nbp.State" should "carry policy and QE state without financial stocks" in {
    val nbp = Nbp.State(p.monetary.initialRate, false, PLN.Zero, PLN.Zero)
    nbp.referenceRate shouldBe p.monetary.initialRate
    nbp.qeActive shouldBe false
    nbp.qeCumulative shouldBe PLN.Zero
  }
