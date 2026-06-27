package com.boombustgroup.amorfati.agents

import com.boombustgroup.amorfati.TestFirmState

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import org.scalatest.flatspec.AnyFlatSpec
import com.boombustgroup.amorfati.Generators
import org.scalatest.matchers.should.Matchers
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.agents.firm.FirmPostProcessing
import com.boombustgroup.amorfati.engine.*
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.markets.OpenEconomy
import com.boombustgroup.amorfati.types.*

import com.boombustgroup.amorfati.random.RandomStream

class FirmSpec extends AnyFlatSpec with Matchers:

  given SimParams              = SimParams.defaults
  private val p: SimParams     = summon[SimParams]
  private val ExecutionMonth31 = ExecutionMonth(31)

  private def defaultStocks(cash: PLN = PLN(50000), debt: PLN = PLN.Zero, equity: PLN = PLN.Zero): Firm.FinancialStocks =
    TestFirmState.financial(cash = cash, debt = debt, equityRaised = equity)

  private def hiringDiagnostics(firm: Firm.State, world: World): Firm.HiringDiagnostics =
    Firm.hiringDiagnostics(firm, defaultStocks(), world, OperationalSignals.fromDecisionSignals(world.seedIn, world.pipeline.operationalHiringSlack))

  private def process(
      firm: Firm.State,
      world: World,
      lendRate: Rate,
      bankCanLend: PLN => Boolean,
      allFirms: Vector[Firm.State],
      rng: RandomStream,
      financialStocks: Firm.FinancialStocks = defaultStocks(),
  ): Firm.Result =
    Firm.process(
      firm,
      financialStocks,
      world,
      ExecutionMonth31,
      OperationalSignals.fromDecisionSignals(world.seedIn, world.pipeline.operationalHiringSlack),
      lendRate,
      bankCanLend,
      allFirms,
      rng,
      PLN.Zero,
    )

  // --- Firm.isAlive ---

  "Firm.isAlive" should "return true for Traditional" in {
    Firm.isAlive(mkFirm(TechState.Traditional(10))) shouldBe true
  }

  it should "return true for Hybrid" in {
    Firm.isAlive(mkFirm(TechState.Hybrid(5, Multiplier.decimal(12, 1)))) shouldBe true
  }

  it should "return true for Automated" in {
    Firm.isAlive(mkFirm(TechState.Automated(Multiplier.decimal(15, 1)))) shouldBe true
  }

  it should "return false for Bankrupt" in {
    Firm.isAlive(mkFirm(TechState.Bankrupt(BankruptReason.Other("test")))) shouldBe false
  }

  // --- Firm.workers ---

  "Firm.workerCount" should "return workers for Traditional" in {
    Firm.workerCount(mkFirm(TechState.Traditional(10))) shouldBe 10
  }

  it should "return workers for Hybrid" in {
    Firm.workerCount(mkFirm(TechState.Hybrid(7, Multiplier.One))) shouldBe 7
  }

  it should "return skeletonCrew for Automated" in {
    val f = mkFirm(TechState.Automated(Multiplier.decimal(15, 1)))
    Firm.workerCount(f) shouldBe Firm.skeletonCrew(f)
  }

  it should "return 0 for Bankrupt" in {
    Firm.workerCount(mkFirm(TechState.Bankrupt(BankruptReason.Other("test")))) shouldBe 0
  }

  "Firm.computeEffectiveCapacity" should "scale base capacity by the runtime productivity index" in {
    val firm              = mkFirm(TechState.Traditional(10))
    val productivityIndex = Multiplier.decimal(105, 2)

    Firm.computeEffectiveCapacity(firm, productivityIndex) shouldBe Firm.computeCapacity(firm) * productivityIndex
  }

  "Firm.applyOperationalHiringSlack" should "compress worker targets when aggregate labor plans exceed supply" in {
    Firm.applyOperationalHiringSlack(currentWorkers = 10, rawTarget = 24, minWorkers = 3, slackFactor = Multiplier.decimal(5, 1)) shouldBe 17
  }

  it should "not compress incumbent workers when expansion hiring slack is exhausted" in {
    Firm.applyOperationalHiringSlack(currentWorkers = 4, rawTarget = 8, minWorkers = 3, slackFactor = Multiplier.Zero) shouldBe 4
  }

  it should "leave downsizing targets unchanged when labor hiring slack is tight" in {
    Firm.applyOperationalHiringSlack(currentWorkers = 10, rawTarget = 4, minWorkers = 3, slackFactor = Multiplier.decimal(25, 2)) shouldBe 4
  }

  "Firm.hiringDiagnostics" should "delay non-micro hiring until demand persists" in {
    val world       = mkWorld().copy(
      pipeline = PipelineState(
        sectorDemandMult = Vector.fill(p.sectorDefs.length)(Multiplier.One),
        sectorDemandPressure = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(175, 2)),
        sectorHiringSignal = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(175, 2)),
        operationalHiringSlack = Share.One,
      ),
      flows = FlowState.zero,
    )
    val firstSignal = hiringDiagnostics(mkFirm(TechState.Traditional(8), sector = 3), world)
    firstSignal.desiredWorkers should be > firstSignal.workers
    firstSignal.feasibleWorkers shouldBe firstSignal.workers
    firstSignal.signalMonths shouldBe 1
    firstSignal.requiredSignalMonths shouldBe 2

    val persistedSignal = hiringDiagnostics(mkFirm(TechState.Traditional(8), sector = 3).copy(hiringSignalMonths = 1), world)
    persistedSignal.desiredWorkers should be > persistedSignal.workers
    persistedSignal.feasibleWorkers should be > persistedSignal.workers
    persistedSignal.signalMonths shouldBe 2
  }

  it should "allow micro firms to react on the first sustained positive gap" in {
    val world = mkWorld().copy(
      pipeline = PipelineState(
        sectorDemandMult = Vector.fill(p.sectorDefs.length)(Multiplier.One),
        sectorDemandPressure = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(175, 2)),
        sectorHiringSignal = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(175, 2)),
        operationalHiringSlack = Share.One,
      ),
      flows = FlowState.zero,
    )
    val diag  = hiringDiagnostics(mkFirm(TechState.Traditional(4), sector = 3), world)
    diag.desiredWorkers should be > diag.workers
    diag.feasibleWorkers should be > diag.workers
    diag.requiredSignalMonths shouldBe 1
  }

  it should "keep startup firms on their startup staffing target even under weak demand" in {
    val world   = mkWorld().copy(
      pipeline = PipelineState(
        sectorDemandMult = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(8, 1)),
        sectorDemandPressure = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(8, 1)),
        sectorHiringSignal = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(8, 1)),
        operationalHiringSlack = Share.One,
      ),
      flows = FlowState.zero,
    )
    val startup = mkFirm(TechState.Traditional(1), sector = 3).copy(startupMonthsLeft = 4, startupTargetWorkers = 3)
    val diag    = hiringDiagnostics(startup, world)
    diag.desiredWorkers shouldBe 3
    diag.feasibleWorkers shouldBe 2
    diag.signalMonths shouldBe 1
  }

  it should "prefer explicit OperationalSignals over bridged world signal fields" in {
    val weakWorld         = mkWorld().copy(
      pipeline = PipelineState(
        sectorDemandMult = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(4, 1)),
        sectorDemandPressure = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(4, 1)),
        sectorHiringSignal = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(4, 1)),
        operationalHiringSlack = Share.One,
      ),
      flows = FlowState.zero,
    )
    val strongOperational = OperationalSignals(
      sectorDemandMult = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(175, 2)),
      sectorDemandPressure = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(175, 2)),
      sectorHiringSignal = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(175, 2)),
      operationalHiringSlack = Share.One,
    )
    val firm              = mkFirm(TechState.Traditional(8), sector = 3).copy(hiringSignalMonths = 1)
    val bridged           = hiringDiagnostics(firm, weakWorld)
    val explicit          = Firm.hiringDiagnostics(firm, defaultStocks(), weakWorld, strongOperational)

    explicit.desiredWorkers should be > bridged.desiredWorkers
    explicit.feasibleWorkers should be >= bridged.feasibleWorkers
  }

  it should "reuse cached desired workers when updating the hiring signal state" in {
    val world       = mkWorld().copy(
      pipeline = PipelineState(
        sectorDemandMult = Vector.fill(p.sectorDefs.length)(Multiplier.One),
        sectorDemandPressure = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(175, 2)),
        sectorHiringSignal = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(175, 2)),
        operationalHiringSlack = Share.One,
      ),
      flows = FlowState.zero,
    )
    val operational = OperationalSignals.fromDecisionSignals(world.seedIn, world.pipeline.operationalHiringSlack)
    val firm        = mkFirm(TechState.Traditional(8), sector = 3)
    val result      = Firm.Result.zero(firm)
    val desired     = Firm.desiredWorkers(firm, world, operational)

    val uncached = FirmPostProcessing.updateHiringSignalState(result, firm, world, operational)
    val cached   = FirmPostProcessing.updateHiringSignalState(result, firm, world, operational, cachedDesiredWorkers = Some(desired))

    cached.firm.hiringSignalMonths shouldBe uncached.firm.hiringSignalMonths
  }

  "Firm.hasWorkingCapitalGrace" should "give startups a larger temporary liquidity runway" in {
    val startup   = mkFirm(TechState.Traditional(2), sector = 3).copy(startupMonthsLeft = 4, startupTargetWorkers = 3)
    val incumbent = mkFirm(TechState.Traditional(2), sector = 3)
    val pnl       = Firm.PnL(
      revenue = PLN(1000),
      costs = PLN(900),
      tax = PLN.Zero,
      netAfterTax = PLN(100),
      profitShiftCost = PLN.Zero,
      energyCost = PLN.Zero,
      newAccumulatedLoss = PLN.Zero,
    )
    val cashGap   = PLN(-15000)
    Firm.hasWorkingCapitalGrace(startup, pnl, cashGap) shouldBe true
    Firm.hasWorkingCapitalGrace(incumbent, pnl, cashGap) shouldBe false
  }

  "Firm.canFundUpsize" should "let startups hire against their startup runway when incumbents are not profitable" in {
    val startup         = mkFirm(TechState.Traditional(2), sector = 3).copy(startupMonthsLeft = 4, startupTargetWorkers = 4)
    val incumbent       = mkFirm(TechState.Traditional(2), sector = 3)
    val pnl             = Firm.PnL(
      revenue = PLN(1000),
      costs = PLN(1000),
      tax = PLN.Zero,
      netAfterTax = PLN(-1),
      profitShiftCost = PLN.Zero,
      energyCost = PLN.Zero,
      newAccumulatedLoss = PLN.Zero,
    )
    val cashAfterHiring = PLN(-12000)
    Firm.canFundUpsize(startup, pnl, cashAfterHiring, addedWorkers = 1, marketWage = p.household.baseWage) shouldBe true
    Firm.canFundUpsize(incumbent, pnl, cashAfterHiring, addedWorkers = 1, marketWage = p.household.baseWage) shouldBe false
  }

  // --- Firm.capacity ---

  "Firm.computeCapacity" should "be positive for alive firms" in {
    Firm.computeCapacity(mkFirm(TechState.Traditional(10))) should be > PLN.Zero
    Firm.computeCapacity(mkFirm(TechState.Hybrid(5, Multiplier.decimal(12, 1)))) should be > PLN.Zero
    Firm.computeCapacity(mkFirm(TechState.Automated(Multiplier.decimal(15, 1)))) should be > PLN.Zero
  }

  it should "be 0 for Bankrupt" in {
    Firm.computeCapacity(mkFirm(TechState.Bankrupt(BankruptReason.Other("test")))) shouldBe PLN.Zero
  }

  // --- Firm.aiCapex / hybridCapex ---

  "Firm.computeAiCapex" should "be positive and scale with multipliers" in {
    val f  = mkFirm(TechState.Traditional(10))
    Firm.computeAiCapex(f) should be > PLN.Zero
    // With higher innovationCostFactor → higher capex
    val f2 = f.copy(innovationCostFactor = Multiplier.decimal(15, 1))
    Firm.computeAiCapex(f2) should be > Firm.computeAiCapex(f)
  }

  "Firm.computeHybridCapex" should "be positive" in {
    val f = mkFirm(TechState.Traditional(10))
    Firm.computeHybridCapex(f) should be > PLN.Zero
  }

  // --- Firm.sigmaThreshold ---

  "Firm.sigmaThreshold" should "be monotonically increasing with sigma" in {
    // Sectors ordered by sigma: Public(1.0) < Healthcare(2.0) < Agriculture(3.0) < Retail(5.0) < Manuf(10.0) < BPO(50.0)
    val sigmasOrdered = Vector(BigDecimal("1.0"), BigDecimal("2.0"), BigDecimal("3.0"), BigDecimal("5.0"), BigDecimal("10.0"), BigDecimal("50.0"))
    val thresholds    = sigmasOrdered.map(s => Firm.sigmaThreshold(sigmaBD(s)))
    for i <- 0 until thresholds.length - 1 do thresholds(i) should be <= thresholds(i + 1)
  }

  it should "be bounded in [0, 1]" in {
    for s <- p.sectorDefs do
      val t = Firm.sigmaThreshold(s.sigma)
      t.bd should be >= BigDecimal(0)
      t.bd should be <= BigDecimal("1.0")
  }

  // --- Firm.localAutoRatio ---

  "Firm.computeLocalAutoRatio" should "return 0.0 when no automated neighbors" in {
    val firms = Vector(
      mkFirmWithNeighbors(0, TechState.Traditional(10), Vector(FirmId(1), FirmId(2))),
      mkFirmWithNeighbors(1, TechState.Traditional(10), Vector(FirmId(0))),
      mkFirmWithNeighbors(2, TechState.Traditional(10), Vector(FirmId(0))),
    )
    Firm.computeLocalAutoRatio(firms(0), firms) shouldBe Share.Zero
  }

  it should "return 1.0 when all neighbors are Automated" in {
    val firms = Vector(
      mkFirmWithNeighbors(0, TechState.Traditional(10), Vector(FirmId(1), FirmId(2))),
      mkFirmWithNeighbors(1, TechState.Automated(Multiplier.decimal(12, 1)), Vector(FirmId(0))),
      mkFirmWithNeighbors(2, TechState.Automated(Multiplier.decimal(11, 1)), Vector(FirmId(0))),
    )
    Firm.computeLocalAutoRatio(firms(0), firms) shouldBe Share.One
  }

  it should "count Hybrid as automated in ratio" in {
    val firms = Vector(
      mkFirmWithNeighbors(0, TechState.Traditional(10), Vector(FirmId(1), FirmId(2), FirmId(3))),
      mkFirmWithNeighbors(1, TechState.Automated(Multiplier.decimal(12, 1)), Vector(FirmId(0))),
      mkFirmWithNeighbors(2, TechState.Hybrid(5, Multiplier.One), Vector(FirmId(0))),
      mkFirmWithNeighbors(3, TechState.Traditional(10), Vector(FirmId(0))),
    )
    Firm.computeLocalAutoRatio(firms(0), firms).bd shouldBe (BigDecimal(2) / 3 +- BigDecimal("0.001"))
  }

  "Firm.adoptionWillingnessMultiplier" should "increase with local demonstration effects above threshold" in {
    val below = Firm.adoptionWillingnessMultiplier(month = ExecutionMonth(12), localAuto = Share.decimal(30, 2))
    val above = Firm.adoptionWillingnessMultiplier(month = ExecutionMonth(12), localAuto = Share.decimal(80, 2))

    above.should(be > below)
  }

  it should "increase over time until the ramp saturates" in {
    val early = Firm.adoptionWillingnessMultiplier(month = ExecutionMonth.First, localAuto = Share.Zero)
    val mid   = Firm.adoptionWillingnessMultiplier(month = ExecutionMonth(18), localAuto = Share.Zero)
    val late  = Firm.adoptionWillingnessMultiplier(month = ExecutionMonth(72), localAuto = Share.Zero)

    mid.should(be > early)
    late.should(be >= mid)
  }

  it should "start at the documented base willingness in the first execution month" in {
    val first = Firm.adoptionWillingnessMultiplier(month = ExecutionMonth.First, localAuto = Share.Zero)

    first.shouldBe(Share.decimal(8, 2))
  }

  it should "return 0.0 for firm with no neighbors" in {
    val firms = Vector(mkFirmWithNeighbors(0, TechState.Traditional(10), Vector.empty[FirmId]))
    Firm.computeLocalAutoRatio(firms(0), firms) shouldBe Share.Zero
  }

  "Firm.computePnL" should "keep ETS surcharge at baseline in the first execution month" in {
    val firm         = mkFirm(TechState.Traditional(10), sector = 1)
    val commodity    = PriceIndex.Base
    val pnl          = Firm.computePnL(
      firm = firm,
      financialStocks = defaultStocks(),
      wage = p.household.baseWage,
      sectorDemandMult = Multiplier.One,
      domesticPrice = PriceIndex.Base,
      importPrice = PriceIndex.Base,
      commodityPrice = commodity,
      lendRate = Rate.Zero,
      month = ExecutionMonth.First,
    )
    val baseEnergy   = pnl.revenue * p.climate.energyCostShares(firm.sector.toInt)
    val expectedCost = commodity * baseEnergy

    pnl.energyCost shouldBe expectedCost
  }

  "FirmPostProcessing.applyInvestment" should "request target bank debt for cash-rich physical investment without blocking rejected capex" in {
    val firm   = investmentTestFirm(workers = 10)
    val stocks = defaultStocks(cash = PLN(1000000000))
    val base   = Firm.Result.zero(firm, stocks)

    val approved = FirmPostProcessing.applyInvestment(base, Multiplier.One, investmentCreditDecision(approved = true))
    val rejected = FirmPostProcessing.applyInvestment(base, Multiplier.One, investmentCreditDecision(approved = false))

    approved.grossInvestment should be > PLN.Zero
    approved.investmentCreditDemand shouldBe approved.grossInvestment * p.capital.investmentDebtTargetShare
    approved.investmentCreditApproved shouldBe approved.investmentCreditDemand
    approved.newLoan shouldBe approved.investmentCreditApproved

    rejected.grossInvestment shouldBe approved.grossInvestment
    rejected.investmentCreditDemand shouldBe approved.investmentCreditDemand
    rejected.investmentCreditApproved shouldBe PLN.Zero
    rejected.investmentCreditRejected shouldBe approved.investmentCreditDemand
    rejected.newLoan shouldBe PLN.Zero
  }

  it should "request shortfall bank debt for cash-poor physical investment" in {
    val firm           = investmentTestFirm(workers = 500)
    val cashRichBase   = Firm.Result.zero(firm, defaultStocks(cash = PLN(1000000000)))
    val cashPoorStocks = defaultStocks(cash = PLN(5000000))
    val cashPoorBase   = Firm.Result.zero(firm, cashPoorStocks)

    val cashRichApproved = FirmPostProcessing.applyInvestment(cashRichBase, Multiplier.One, investmentCreditDecision(approved = true))
    val approved         = FirmPostProcessing.applyInvestment(cashPoorBase, Multiplier.One, investmentCreditDecision(approved = true))
    val rejected         = FirmPostProcessing.applyInvestment(cashPoorBase, Multiplier.One, investmentCreditDecision(approved = false))

    approved.grossInvestment shouldBe cashRichApproved.grossInvestment
    approved.investmentCreditDemand should be > (approved.grossInvestment * p.capital.investmentDebtTargetShare)
    approved.investmentCreditApproved shouldBe approved.investmentCreditDemand
    approved.newLoan shouldBe approved.investmentCreditApproved

    rejected.investmentCreditDemand shouldBe approved.investmentCreditDemand
    rejected.investmentCreditApproved shouldBe PLN.Zero
    rejected.investmentCreditRejected shouldBe approved.investmentCreditDemand
    rejected.newLoan shouldBe PLN.Zero
    rejected.grossInvestment shouldBe cashPoorStocks.cash
  }

  // --- Firm.process ---

  "Firm.process" should "keep a Bankrupt firm bankrupt with zero tax/capex" in {
    val f      = mkFirm(TechState.Bankrupt(BankruptReason.Other("test")))
    val result = process(f, mkWorld(), Rate.decimal(7, 2), _ => true, Vector(f), RandomStream.seeded(42))
    result.taxPaid shouldBe PLN.Zero
    result.capexSpent shouldBe PLN.Zero
    result.firm.tech shouldBe a[TechState.Bankrupt]
  }

  it should "keep an Automated firm alive with large cash" in {
    val f      = mkFirm(TechState.Automated(Multiplier.decimal(15, 1)))
    val result = process(f, mkWorld(), Rate.decimal(7, 2), _ => true, Vector(f), RandomStream.seeded(42), defaultStocks(cash = PLN(10000000)))
    Firm.isAlive(result.firm) shouldBe true
  }

  it should "bankrupt an Automated firm with negative cash when P&L is negative" in {
    // Very low cash + high price level = deep losses → bankrupt
    val f      = mkFirm(TechState.Automated(Multiplier.decimal(1, 1)))
    val baseW  = mkWorld()
    val w      = baseW.copy(
      priceLevel = PriceIndex.decimal(3, 1),
      pipeline = baseW.pipeline.copy(
        sectorDemandMult = Vector.fill(baseW.pipeline.sectorDemandMult.length)(Multiplier.decimal(1, 1)),
      ),
    )
    val result = process(f, w, Rate.decimal(20, 2), _ => true, Vector(f), RandomStream.seeded(42), defaultStocks(cash = PLN(-500000), debt = PLN(5000000)))
    result.firm.tech shouldBe a[TechState.Bankrupt]
  }

  it should "use the carried informal adjustment from world state for CIT evasion" in {
    val firm       = mkFirm(TechState.Traditional(10), sector = 2)
    val stocks     = defaultStocks(cash = PLN(500000))
    val baseWorld  =
      mkWorld().copy(
        pipeline = mkWorld().pipeline.copy(
          sectorDemandMult = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(25, 1)),
          sectorDemandPressure = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(25, 1)),
          sectorHiringSignal = Vector.fill(p.sectorDefs.length)(Multiplier.decimal(25, 1)),
        ),
      )
    val lowAdj     = baseWorld.copy(mechanisms = baseWorld.mechanisms.copy(informalCyclicalAdj = Share.Zero))
    val highAdj    = baseWorld.copy(mechanisms = baseWorld.mechanisms.copy(informalCyclicalAdj = Share.decimal(4, 1)))
    val lowResult  = process(firm, lowAdj, Rate.decimal(7, 2), _ => true, Vector(firm), RandomStream.seeded(42), stocks)
    val highResult =
      process(firm, highAdj, Rate.decimal(7, 2), _ => true, Vector(firm), RandomStream.seeded(42), stocks)

    lowResult.taxPaid should be > PLN.Zero
    highResult.taxPaid should be > PLN.Zero
    highResult.citEvasion should be > lowResult.citEvasion
  }

  it should "allow hybrid firms to hire when demand supports a larger workforce" in {
    val world = mkWorld().copy(
      pipeline = PipelineState(
        sectorDemandMult = Vector.fill(p.sectorDefs.length)(Multiplier(20)),
        sectorDemandPressure = Vector.fill(p.sectorDefs.length)(Multiplier(20)),
        sectorHiringSignal = Vector.fill(p.sectorDefs.length)(Multiplier(20)),
        operationalHiringSlack = Share.One,
      ),
      flows = FlowState.zero,
    )
    val firm  = mkFirm(TechState.Hybrid(400, Multiplier.One), sector = 0).copy(
      initialSize = 400,
      digitalReadiness = Share.decimal(25, 2),
      hiringSignalMonths = 1,
    )
    val diag  = Firm.hiringDiagnostics(
      firm,
      defaultStocks(cash = PLN(1000000000)),
      world,
      OperationalSignals.fromDecisionSignals(world.seedIn, world.pipeline.operationalHiringSlack),
    )

    diag.desiredWorkers should be > diag.workers
    diag.feasibleWorkers should be > diag.workers

    val result = process(firm, world, Rate.decimal(7, 2), _ => true, Vector(firm), RandomStream.seeded(7), defaultStocks(cash = PLN(1000000000)))

    result.firm.tech shouldBe a[TechState.Hybrid]
    Firm.workerCount(result.firm) should be > Firm.workerCount(firm)
  }

  it should "adjust inventory toward realized-sales target without adding unsold capacity residuals" in {
    val demand = Multiplier.decimal(8, 1)
    val world  = mkWorld().copy(
      pipeline = PipelineState(
        sectorDemandMult = Vector.fill(p.sectorDefs.length)(demand),
        sectorDemandPressure = Vector.fill(p.sectorDefs.length)(Multiplier.One),
        sectorHiringSignal = Vector.fill(p.sectorDefs.length)(Multiplier.One),
        operationalHiringSlack = Share.One,
      ),
    )
    val firm   = mkFirm(TechState.Traditional(10), sector = 2)

    val result = process(firm, world, Rate.decimal(7, 2), _ => true, Vector(firm), RandomStream.seeded(17), defaultStocks(cash = PLN(1000000000)))
    val target = Firm.computeCapacity(result.firm) * demand.toShare * p.capital.inventoryTargetRatios(firm.sector.toInt)

    result.inventoryChange should be <= (target * p.capital.inventoryAdjustSpeed + PLN(1))
  }

  // --- helpers ---

  private def mkFirmWithNeighbors(id: Int, tech: TechState, neighbors: Vector[FirmId]): Firm.State =
    TestFirmState(
      FirmId(id),
      PLN(50000),
      PLN.Zero,
      tech,
      Share.decimal(5, 1),
      Multiplier.One,
      Share.decimal(5, 1),
      SectorIdx(0),
      neighbors,
      bankId = BankId(0),
      equityRaised = PLN.Zero,
      initialSize = 10,
      capitalStock = PLN.Zero,
      foreignOwned = false,
      inventory = PLN.Zero,
      greenCapital = PLN.Zero,
      accumulatedLoss = PLN.Zero,
    )

  private def mkFirm(tech: TechState, sector: Int = 2): Firm.State =
    TestFirmState(
      FirmId(0),
      PLN(50000),
      PLN.Zero,
      tech,
      Share.decimal(5, 1),
      Multiplier.One,
      Share.decimal(5, 1),
      SectorIdx(sector),
      Vector.empty[FirmId],
      bankId = BankId(0),
      equityRaised = PLN.Zero,
      initialSize = 10,
      capitalStock = PLN.Zero,
      foreignOwned = false,
      inventory = PLN.Zero,
      greenCapital = PLN.Zero,
      accumulatedLoss = PLN.Zero,
    )

  private def investmentTestFirm(workers: Int): Firm.State =
    mkFirm(TechState.Traditional(workers), sector = 2).copy(capitalStock = PLN(1000000))

  private def investmentCreditDecision(approved: Boolean): PLN => Firm.CreditDecision =
    _ => Firm.CreditDecision(approved = approved, approvalProbability = Some(Share.One), approvalRoll = Some(Share.Zero))

  private def mkWorld(): World =
    Generators.testWorld(
      totalPopulation = 100000,
      employed = 100000,
      forex = OpenEconomy.ForexState(ExchangeRate.decimal(433, 2), PLN.Zero, PLN(190000000), PLN.Zero, PLN.Zero),
      marketWage = p.household.baseWage,
      reservationWage = p.household.baseReservationWage,
    )
