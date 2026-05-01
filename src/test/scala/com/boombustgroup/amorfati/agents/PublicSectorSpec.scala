package com.boombustgroup.amorfati.agents

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.boombustgroup.amorfati.agents.QuasiFiscal
import com.boombustgroup.amorfati.types.*

/** Public sector unit tests — ZUS, PPK, Demographics. */
class PublicSectorSpec extends AnyFlatSpec with Matchers:

  import com.boombustgroup.amorfati.config.SimParams
  private given SimParams = SimParams.defaults
  private val p           = summon[SimParams]

  // =========================================================================
  // ZUS
  // =========================================================================

  "SocialSecurity.zusStep" should "compute deficit flows and cash change from payroll" in {
    val payroll               = SocialSecurity.PayrollBase.aggregate(100000, p.household.baseWage)
    val result                = SocialSecurity.zusStep(payroll, nRetirees = 50000)
    val openingCash           = PLN(100000000)
    val expectedContributions = PLN(188407040)
    val expectedPensions      = PLN(216050000)
    val expectedCashChange    = PLN(-27642960)

    // Assumes SimParams.defaults.social.zusContribRate/zusScale/zusBasePension; literals come from SocialSecurity.PayrollBase.aggregate/zusStep.
    result.contributions shouldBe expectedContributions
    result.pensionPayments shouldBe expectedPensions
    result.contributions should be < result.pensionPayments
    result.govSubvention shouldBe result.pensionPayments - result.contributions
    result.govSubvention shouldBe PLN(27642960)
    SocialSecurity.zusCashChange(result) shouldBe result.contributions - result.pensionPayments
    SocialSecurity.zusCashChange(result) shouldBe expectedCashChange
    SocialSecurity.zusCashAfter(openingCash, result) shouldBe openingCash + SocialSecurity.zusCashChange(result)
    SocialSecurity.zusCashAfter(openingCash, result) shouldBe PLN(72357040)
  }

  it should "compute zero government subvention when FUS is in surplus" in {
    val result = SocialSecurity.zusStep(employed = 100000, wage = p.household.baseWage, nRetirees = 1000)

    result.contributions should be > result.pensionPayments
    result.govSubvention shouldBe PLN.Zero
  }

  "SocialSecurity.ZusState.zero" should "have all zero fields" in {
    SocialSecurity.ZusState.zero.contributions shouldBe PLN.Zero
    SocialSecurity.ZusState.zero.pensionPayments shouldBe PLN.Zero
    SocialSecurity.ZusState.zero.govSubvention shouldBe PLN.Zero
  }

  // =========================================================================
  // PPK
  // =========================================================================

  "SocialSecurity.ppkBondPurchase" should "be contributions × bondAlloc" in {
    val ppk      = SocialSecurity.PpkState(contributions = PLN(1000000))
    // Default bondAlloc = 0.60
    val purchase = SocialSecurity.ppkBondPurchase(ppk)
    decimal(purchase) shouldBe (BigDecimal("1e6") * BigDecimal("0.60") +- BigDecimal("0.01"))
  }

  "SocialSecurity.PpkState.zero" should "have zero contributions" in {
    SocialSecurity.PpkState.zero.contributions shouldBe PLN.Zero
  }

  // =========================================================================
  // Demographics
  // =========================================================================

  "SocialSecurity.DemographicsState.zero" should "have all zero fields" in {
    SocialSecurity.DemographicsState.zero.retirees shouldBe 0
    SocialSecurity.DemographicsState.zero.workingAgePop shouldBe 0
    SocialSecurity.DemographicsState.zero.monthlyRetirements shouldBe 0
  }

  "SocialSecurity.demographicsStep" should "keep labor supply aligned with represented household agents" in {
    val prev   = SocialSecurity.DemographicsState(retirees = 1000, workingAgePop = 100000, monthlyRetirements = 0)
    val result = SocialSecurity.demographicsStep(prev, employed = 90000, netMigration = -25, representedLaborForce = 100000)

    result.workingAgePop shouldBe 99975
    result.retirees should be > prev.retirees
    result.monthlyRetirements shouldBe result.retirees - prev.retirees
  }

  // =========================================================================
  // BGK (stub)
  // =========================================================================

  "QuasiFiscal.StockState.zero" should "have zero outstanding and loans" in {
    QuasiFiscal.StockState.zero.bondsOutstanding shouldBe PLN.Zero
    QuasiFiscal.StockState.zero.loanPortfolio shouldBe PLN.Zero
    QuasiFiscal.StockState.zero.bankHoldings shouldBe PLN.Zero
    QuasiFiscal.StockState.zero.nbpHoldings shouldBe PLN.Zero
  }
