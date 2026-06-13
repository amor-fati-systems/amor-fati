package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import com.boombustgroup.amorfati.agents.Nbp
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.World
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.flows.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.init.{InitRandomness, WorldInit}
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests OpenEconEconomics produces self-consistent results. */
class OpenEconEconomicsSpec extends AnyFlatSpec with Matchers:

  private given p: SimParams = SimParams.defaults
  private val TestSeed       = 42L

  private val init                     = WorldInit.initialize(InitRandomness.Contract.fromSeed(TestSeed))
  private val w                        = init.world
  private val baseLedgerFinancialState = FlowSimulation.SimState.fromInit(init).ledgerFinancialState
  private val rng                      = RandomStream.seeded(TestSeed)

  // Run pipeline through Economics objects
  private val s1 = FiscalConstraintEconomics.compute(w, init.banks, baseLedgerFinancialState, ExecutionMonth.First)
  private val s2 = LaborEconomics.compute(w, init.firms, init.households, s1)
  private val s3 =
    HouseholdIncomeEconomics.compute(w, init.firms, init.households, init.banks, baseLedgerFinancialState, s1.lendingBaseRate, s1.resWage, s2.newWage, rng)
  private val s4 = DemandEconomics.compute(w, s2.employed, s2.living, s3.domesticCons)
  private val s5 = FirmEconomics.runStep(w, init.firms, init.households, init.banks, baseLedgerFinancialState, s1, s2, s3, s4, rng)
  private val s6 = HouseholdFinancialEconomics.compute(w, s1.m, s2.employed, s3.hhAgg, rng)
  private val s7 = PriceEquityEconomics.compute(
    w = w,
    month = s1.m,
    wageGrowth = s2.wageGrowth,
    avgDemandMult = s4.avgDemandMult,
    sectorMults = s4.sectorMults,
    totalSystemLoans = baseLedgerFinancialState.banks.map(_.firmLoan).sumPln,
    firmStep = s5,
    ledgerFinancialState = baseLedgerFinancialState,
  )

  private def runOpenEcon(
      world: World,
      ledgerFinancialState: LedgerFinancialState = baseLedgerFinancialState,
      priceEquity: PriceEquityEconomics.StepOutput = s7,
      params: SimParams = p,
  ): OpenEconEconomics.StepOutput =
    OpenEconEconomics.runStep(
      OpenEconEconomics.StepInput(
        world = world,
        ledgerFinancialState = ledgerFinancialState,
        fiscal = s1,
        labor = s2,
        householdIncome = s3,
        demand = s4,
        firm = s5,
        householdFinancial = s6,
        priceEquity = priceEquity,
        banks = init.banks,
        commodityRng = RandomStream.seeded(TestSeed),
      ),
    )(using params)

  private val result = runOpenEcon(w)

  "OpenEconEconomics (self-contained)" should "produce a valid reference rate" in {
    decimal(result.monetary.newRefRate) should be >= BigDecimal("0.0")
  }

  it should "keep diaspora inflow growth at baseline in the first execution month" in {

    val exchangeRate  = decimal(w.forex.exchangeRate)
    val wap           = w.social.demographics.workingAgePop
    val base          = decimal(p.remittance.perCapita) * decimal(wap)
    val erAdj         = powDecimal(exchangeRate / decimal(p.forex.baseExRate), decimal(p.remittance.erElasticity))
    val unempForRemit = decimal(w.unemploymentRate(s2.employed))
    val cyclicalAdj   = BigDecimal(1) + decimal(p.remittance.cyclicalSens) * (unempForRemit - BigDecimal("0.05")).max(BigDecimal(0))
    val expected      = plnBD(base * erAdj * cyclicalAdj)

    s6.diasporaInflow shouldBe expected
  }

  it should "keep tourism growth at baseline in the first execution month" in {

    val exchangeRate   = decimal(w.forex.exchangeRate)
    val monthInYear    = s1.m.monthInYear
    val seasonalFactor = BigDecimal(1) + decimal(p.tourism.seasonality) * cosTurns(monthInYear - p.tourism.peakMonth, 12)
    val inboundErAdj   = powDecimal(exchangeRate / decimal(p.forex.baseExRate), decimal(p.tourism.erElasticity))
    val outboundErAdj  = powDecimal(decimal(p.forex.baseExRate) / exchangeRate, decimal(p.tourism.erElasticity))
    val baseGdp        = decimal(w.cachedMonthlyGdpProxy).max(BigDecimal(0))
    val expectedExport = plnBD(baseGdp * decimal(p.tourism.inboundShare) * seasonalFactor * inboundErAdj)
    val expectedImport = plnBD(baseGdp * decimal(p.tourism.outboundShare) * seasonalFactor * outboundErAdj)

    s6.tourismExport shouldBe expectedExport
    s6.tourismImport shouldBe expectedImport
  }

  it should "produce a valid bond yield" in {
    decimal(result.monetary.newBondYield) should be >= BigDecimal("0.0")
  }

  it should "cap QE requests against annualized GDP in the open-economy call-site" in {
    val monthlyGdp        = s7.gdp
    val annualGdp         = monthlyGdp * 12
    val annualCapHeadroom = p.monetary.qePace
    val openingNbpBonds   = annualGdp * p.monetary.qeMaxGdpShare - annualCapHeadroom
    val bankSupply        = annualCapHeadroom * 2
    val qeLedger          = baseLedgerFinancialState.copy(
      banks = baseLedgerFinancialState.banks.zipWithIndex.map {
        case (bank, 0) => bank.copy(govBondAfs = bankSupply, govBondHtm = PLN.Zero)
        case (bank, _) => bank.copy(govBondAfs = PLN.Zero, govBondHtm = PLN.Zero)
      },
      nbp = baseLedgerFinancialState.nbp.copy(govBondHoldings = openingNbpBonds),
    )
    val qeWorld           = w.copy(
      nbp = Nbp.State(p.monetary.rateFloor, qeActive = true, qeCumulative = PLN.Zero, lastFxTraded = PLN.Zero),
      mechanisms = w.mechanisms.copy(
        expectations = w.mechanisms.expectations.copy(
          expectedInflation = Rate.decimal(-2, 2),
          expectedRate = p.monetary.rateFloor,
          forwardGuidanceRate = p.monetary.rateFloor,
        ),
      ),
    )
    val qeSurface         = s7.copy(newInfl = Rate.decimal(-5, 2))
    val qeResult          = runOpenEcon(qeWorld, qeLedger, qeSurface)

    openingNbpBonds should be > (monthlyGdp * p.monetary.qeMaxGdpShare)
    qeResult.monetary.qePurchaseAmount shouldBe annualCapHeadroom
  }

  it should "return corporate bond projection separately from market memory in runStep" in {
    val aligned = runOpenEcon(w)

    aligned.corpBonds.newCorpBonds.corpBondYield should be > Rate.Zero
    aligned.corpBonds.closingCorpBondProjection.outstanding should be > PLN.Zero
    aligned.corpBonds.closingCorpBondProjection.bankHoldings should be > PLN.Zero
    aligned.corpBonds.closingCorpBondProjection.ppkHoldings should be > PLN.Zero
    aligned.corpBonds.closingCorpBondProjection.otherHoldings should be >= PLN.Zero
    aligned.corpBonds.closingCorpBondProjection.insuranceHoldings should be > PLN.Zero
    aligned.corpBonds.closingCorpBondProjection.nbfiHoldings should be > PLN.Zero
  }

  it should "produce non-negative interbank flows" in {
    result.banking.totalReserveInterest should be >= PLN.Zero
  }

  it should "produce flows that close at SFC == 0L" in {
    val flowBop = result.external.flowBop
    val flows   = OpenEconFlows.emit(
      OpenEconFlows.Input(
        exports = flowBop.exports,
        imports = flowBop.totalImports,
        tourismExport = s6.tourismExport,
        tourismImport = s6.tourismImport,
        fdi = flowBop.fdi,
        portfolioFlows = flowBop.portfolioFlows,
        carryTradeFlow = flowBop.carryTradeFlow,
        primaryIncome = flowBop.primaryIncome,
        euFunds = flowBop.euFundsMonthly,
        diasporaInflow = s6.diasporaInflow,
        capitalFlightOutflow = flowBop.capitalFlightOutflow,
      ),
    )
    Interpreter.totalWealth(Interpreter.applyAll(Map.empty[Int, Long], flows)) shouldBe 0L
  }
