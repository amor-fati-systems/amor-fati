package com.boombustgroup.amorfati.engine.closedmonth

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.{DecisionSignals, MonthExecution, MonthRandomness, MonthTraceStage, OperationalSignals, SignalExtraction, World}
import com.boombustgroup.amorfati.engine.economics.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.init.{InitRandomness, WorldInit}
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SignalTimingRegressionSpec extends AnyFlatSpec with Matchers:

  private given p: SimParams = SimParams.defaults

  private case class PipelineFixture(
      world: World,
      firms: Vector[Firm.State],
      households: Vector[Household.State],
      banks: Vector[Banking.BankState],
      ledgerFinancialState: LedgerFinancialState,
      s1: FiscalConstraintEconomics.StepOutput,
      s2Pre: LaborEconomics.StepOutput,
      s2: LaborEconomics.StepOutput,
      s3: HouseholdIncomeEconomics.StepOutput,
      s4: DemandEconomics.StepOutput,
      s5: FirmEconomics.StepOutput,
      s6: HouseholdFinancialEconomics.StepOutput,
      s7: PriceEquityEconomics.StepOutput,
      s8: OpenEconEconomics.StepOutput,
      s9: BankingEconomics.StepOutput,
  )

  private val baseline = buildFixture(42L)

  private def buildFixture(seed: Long): PipelineFixture =
    val init                 = WorldInit.initialize(InitRandomness.Contract.fromSeed(seed))
    val world                = init.world
    val contract             = MonthRandomness.Contract.fromSeed(seed)
    val ledgerFinancialState = init.ledgerFinancialState

    val s1     = FiscalConstraintEconomics.compute(world, init.banks, ledgerFinancialState, ExecutionMonth.First)
    val s2Pre  = LaborEconomics.compute(world, init.firms, init.households, s1)
    val s3     =
      HouseholdIncomeEconomics.compute(
        world,
        init.firms,
        init.households,
        init.banks,
        ledgerFinancialState,
        s1.lendingBaseRate,
        s1.resWage,
        s2Pre.newWage,
        contract.stages.householdIncomeEconomics.newStream(),
      )
    val s4     = DemandEconomics.compute(world, s2Pre.employed, s2Pre.living, s3.domesticCons)
    val s5     =
      FirmEconomics.runStep(world, init.firms, init.households, init.banks, ledgerFinancialState, s1, s2Pre, s3, s4, contract.stages.firmEconomics.newStream())
    val living = s5.ioFirms.filter(Firm.isAlive)
    val s2     = LaborEconomics.reconcilePostFirmStep(world, s1, s2Pre, living, s5.households)
    val s6     = HouseholdFinancialEconomics.compute(world, s1.m, s2.employed, s3.hhAgg, contract.stages.householdFinancialEconomics.newStream())
    val s7     = PriceEquityEconomics.compute(
      w = world,
      month = s1.m,
      wageGrowth = s2.wageGrowth,
      avgDemandMult = s4.avgDemandMult,
      sectorMults = s4.sectorMults,
      totalSystemLoans = ledgerFinancialState.banks.map(_.firmLoan).sumPln,
      firmStep = s5,
      ledgerFinancialState = ledgerFinancialState,
    )
    val s8     =
      OpenEconEconomics.runStep(
        OpenEconEconomics.StepInput(
          world,
          ledgerFinancialState,
          s1,
          s2,
          s3,
          s4,
          s5,
          s6,
          s7,
          init.banks,
          contract.stages.openEconEconomics.newStream(),
        ),
      )
    val s9     =
      BankingEconomics.runStep(
        BankingEconomics.StepInput(
          world,
          ledgerFinancialState,
          s1,
          s2,
          s3,
          s4,
          s5,
          s6,
          s7,
          s8,
          init.banks,
          contract.stages.bankingEconomics.newStream(),
        ),
      )

    PipelineFixture(world, init.firms, init.households, init.banks, ledgerFinancialState, s1, s2Pre, s2, s3, s4, s5, s6, s7, s8, s9)

  private def baseMonthExecution: MonthExecution =
    MonthExecution(
      openingWorld = baseline.world,
      fiscal = baseline.s1,
      labor = baseline.s2,
      householdIncome = baseline.s3,
      demand = baseline.s4,
      firm = baseline.s5,
      householdFinancial = baseline.s6,
      priceEquity = baseline.s7,
      openEconomy = baseline.s8,
      banking = baseline.s9,
    )

  private def multiplierVector(value: BigDecimal): Vector[Multiplier] =
    Vector.fill(p.sectorDefs.length)(multiplierBD(value))

  private def baseOperationalSignals: OperationalSignals =
    OperationalSignals(
      sectorDemandMult = baseline.s4.sectorMults,
      sectorDemandPressure = baseline.s4.sectorDemandPressure,
      sectorHiringSignal = baseline.s4.sectorHiringSignal,
      operationalHiringSlack = baseline.s2.operationalHiringSlack,
    )

  private def baseFirmRunStep(world: World, operationalSignals: OperationalSignals, seed: Long): FirmEconomics.StepOutput =
    val labor  = baseline.s2Pre.copy(operationalHiringSlack = operationalSignals.operationalHiringSlack)
    val demand = baseline.s4.copy(
      sectorMults = operationalSignals.sectorDemandMult,
      sectorDemandPressure = operationalSignals.sectorDemandPressure,
      sectorHiringSignal = operationalSignals.sectorHiringSignal,
    )
    FirmEconomics.runStep(
      world,
      baseline.firms,
      baseline.households,
      baseline.banks,
      baseline.ledgerFinancialState,
      baseline.s1,
      labor,
      baseline.s3,
      demand,
      RandomStream.seeded(seed),
    )

  private def baseBankingRunStep(world: World, seed: Long): BankingEconomics.StepOutput =
    BankingEconomics.runStep(
      BankingEconomics.StepInput(
        world,
        baseline.ledgerFinancialState,
        baseline.s1,
        baseline.s2,
        baseline.s3,
        baseline.s4,
        baseline.s5,
        baseline.s6,
        baseline.s7,
        baseline.s8,
        baseline.banks,
        RandomStream.seeded(seed),
      ),
    )

  private def baseOpenEconRunStep(
      world: World,
      ledgerFinancialState: LedgerFinancialState,
      s7: PriceEquityEconomics.StepOutput,
      seed: Long,
  ): OpenEconEconomics.StepOutput =
    OpenEconEconomics.runStep(
      OpenEconEconomics.StepInput(
        world,
        ledgerFinancialState,
        baseline.s1,
        baseline.s2,
        baseline.s3,
        baseline.s4,
        baseline.s5,
        baseline.s6,
        s7,
        baseline.banks,
        RandomStream.seeded(seed),
      ),
    )

  private def closingRandomness(seed: Long): MonthRandomness.ClosingStreams =
    MonthRandomness.Contract.fromSeed(seed).closing.newStreams()

  private def allEmployed(
      households: Vector[Household.State],
      firms: Vector[Firm.State],
      wage: PLN,
  ): Vector[Household.State] =
    val employer = firms.find(Firm.isAlive).getOrElse(fail("expected at least one living firm"))
    households.map(_.copy(status = HhStatus.Employed(employer.id, employer.sector, wage)))

  private def withUnemploymentShare(
      households: Vector[Household.State],
      firms: Vector[Firm.State],
      wage: PLN,
      unemploymentShare: BigDecimal,
  ): Vector[Household.State] =
    val employedBase    = allEmployed(households, firms, wage)
    val boundedShare    = DecimalMath.max(BigDecimal("0.0"), DecimalMath.min(BigDecimal("1.0"), unemploymentShare))
    val unemployedCount = DecimalMath.round(employedBase.length * boundedShare).toInt
    employedBase.zipWithIndex.map: (hh, idx) =>
      if idx < unemployedCount then hh.copy(status = HhStatus.Unemployed(1))
      else hh

  private def withSeedSignals(world: World, f: DecisionSignals => DecisionSignals): World =
    world.copy(pipeline = world.pipeline.withDecisionSignals(f(world.seedIn)))

  private def withBoundaryEquityReturn(world: World, equityReturn: Rate): World =
    world.copy(
      financialMarkets = world.financialMarkets.copy(
        equity = world.financialMarkets.equity.copy(monthlyReturn = equityReturn),
      ),
    )

  private def withSameMonthEquityReturn(s7: PriceEquityEconomics.StepOutput, equityReturn: Rate): PriceEquityEconomics.StepOutput =
    s7.copy(
      equityAfterForeignStock = s7.equityAfterForeignStock.copy(monthlyReturn = equityReturn),
    )

  private def entrySensitiveInput: MonthExecution =
    val base = baseMonthExecution
    base.copy(
      openingWorld = withSeedSignals(
        base.openingWorld,
        _.copy(
          unemploymentRate = Share.decimal(15, 2),
          inflation = Rate.decimal(3, 2),
          expectedInflation = Rate.decimal(25, 3),
          laggedHiringSlack = Share.One,
          startupAbsorptionRate = Share.One,
          sectorDemandPressure = multiplierVector(BigDecimal("1.05")),
        ),
      ),
      labor = base.labor.copy(operationalHiringSlack = Share.One),
      priceEquity = base.priceEquity.copy(newInfl = Rate.decimal(3, 2)),
      openEconomy = base.openEconomy.copy(
        monetary = base.openEconomy.monetary.copy(
          newExp = base.openEconomy.monetary.newExp.copy(expectedInflation = Rate.decimal(25, 3)),
        ),
      ),
      banking = base.banking.copy(
        reassignedHouseholds = withUnemploymentShare(base.banking.reassignedHouseholds, base.banking.reassignedFirms, base.labor.newWage, BigDecimal("0.15")),
      ),
    )

  private def netBirths(in: MonthExecution): Int =
    MonthClosing.closeExecution(in, closingRandomness(1234L)).world.flows.netFirmBirths

  "DemandEconomics.compute" should "smooth sector hiring plans from lagged decision signals while keeping same-month pressure fixed" in {
    val weakLagged   = withSeedSignals(
      baseline.world,
      _.copy(sectorHiringSignal = multiplierVector(BigDecimal("0.40"))),
    )
    val strongLagged = withSeedSignals(
      baseline.world,
      _.copy(sectorHiringSignal = multiplierVector(BigDecimal("1.60"))),
    )
    val weakResult   = DemandEconomics.compute(weakLagged, baseline.s2Pre.employed, baseline.s2Pre.living, baseline.s3.domesticCons)
    val strongResult = DemandEconomics.compute(strongLagged, baseline.s2Pre.employed, baseline.s2Pre.living, baseline.s3.domesticCons)

    weakResult.sectorDemandPressure shouldBe strongResult.sectorDemandPressure
    weakResult.sectorMults shouldBe strongResult.sectorMults
    weakResult.avgDemandMult shouldBe strongResult.avgDemandMult

    val currentPressure = weakResult.sectorDemandPressure.head
    weakResult.sectorHiringSignal.head shouldBe Multiplier.decimal(40, 2) * Share.decimal(65, 2) + currentPressure * Share.decimal(35, 2)
    strongResult.sectorHiringSignal.head shouldBe Multiplier.decimal(160, 2) * Share.decimal(65, 2) + currentPressure * Share.decimal(35, 2)
    strongResult.sectorHiringSignal.head should be > weakResult.sectorHiringSignal.head
  }

  "SignalExtraction.fromClosedMonth" should "derive next-month decision inputs through one explicit closed-to-next-pre boundary" in {
    val base            = entrySensitiveInput
    val finalHouseholds = withUnemploymentShare(base.banking.reassignedHouseholds, base.banking.reassignedFirms, base.labor.newWage, BigDecimal("0.22"))
    val finalWorld      = base.openingWorld.copy(
      social = base.openingWorld.social.copy(demographics = base.labor.newDemographics),
      inflation = Rate.decimal(-1, 2),
      mechanisms = base.openingWorld.mechanisms.copy(
        expectations = base.openingWorld.mechanisms.expectations.copy(expectedInflation = Rate.decimal(4, 2)),
      ),
    )
    val extracted       = SignalExtraction.fromClosedMonth(
      world = finalWorld,
      households = finalHouseholds,
      operationalHiringSlack = Share.One,
      startupAbsorptionRate = Share.decimal(35, 2),
      demand = SignalExtraction.DemandOutcomes(
        sectorDemandMult = base.demand.sectorMults,
        sectorDemandPressure = base.demand.sectorDemandPressure,
        sectorHiringSignal = base.demand.sectorHiringSignal,
      ),
    )

    extracted.seedOut.unemploymentRate shouldBe finalWorld.unemploymentRate(finalHouseholds.count(_.status.isInstanceOf[HhStatus.Employed]))
    extracted.seedOut.inflation shouldBe Rate.decimal(-1, 2)
    extracted.seedOut.expectedInflation shouldBe Rate.decimal(4, 2)
    extracted.seedOut.laggedHiringSlack shouldBe Share.One
    extracted.seedOut.startupAbsorptionRate shouldBe Share.decimal(35, 2)
    extracted.seedOut.sectorDemandMult shouldBe base.demand.sectorMults
    extracted.seedOut.sectorDemandPressure shouldBe base.demand.sectorDemandPressure
    extracted.seedOut.sectorHiringSignal shouldBe base.demand.sectorHiringSignal
    extracted.provenance.unemploymentRate.stage shouldBe MonthTraceStage.MonthClosing
    extracted.provenance.startupAbsorptionRate.stage shouldBe MonthTraceStage.StartupStaffing
  }

  "FirmEconomics.runStep" should "ignore stale persisted demand signals when stage outputs define the same-month surface" in {
    val staleWorld       = withSeedSignals(
      baseline.world,
      _.copy(
        sectorDemandMult = multiplierVector(BigDecimal("0.35")),
        sectorDemandPressure = multiplierVector(BigDecimal("0.35")),
        sectorHiringSignal = multiplierVector(BigDecimal("0.35")),
      ),
    )
    val explicitResult   = baseFirmRunStep(staleWorld, baseOperationalSignals, seed = 9001L)
    val freshWorldResult = baseFirmRunStep(baseline.world, baseOperationalSignals, seed = 9001L)
    val bridgedResult    =
      baseFirmRunStep(staleWorld, OperationalSignals.fromDecisionSignals(staleWorld.seedIn, staleWorld.pipeline.operationalHiringSlack), seed = 9001L)

    explicitResult.sumNewLoans shouldBe freshWorldResult.sumNewLoans
    explicitResult.sumGrossInvestment shouldBe freshWorldResult.sumGrossInvestment
    explicitResult.sumInventoryChange shouldBe freshWorldResult.sumInventoryChange
    explicitResult.perBankWorkers shouldBe freshWorldResult.perBankWorkers

    explicitResult.sumNewLoans should not be bridgedResult.sumNewLoans
    explicitResult.perBankWorkers should not be bridgedResult.perBankWorkers
  }

  it should "price Calvo markups from same-month sector demand pressure even when sector demand multipliers are capped" in {
    val cappedDemand = multiplierVector(BigDecimal("1.0"))
    val weakSignals  = baseOperationalSignals.copy(
      sectorDemandMult = cappedDemand,
      sectorDemandPressure = multiplierVector(BigDecimal("1.0")),
      sectorHiringSignal = cappedDemand,
    )
    val hotSignals   = weakSignals.copy(
      sectorDemandPressure = multiplierVector(BigDecimal("1.6")),
    )

    val weakResult = baseFirmRunStep(baseline.world, weakSignals, seed = 9011L)
    val hotResult  = baseFirmRunStep(baseline.world, hotSignals, seed = 9011L)
    val weakById   = weakResult.ioFirms.map(f => f.id -> f.markup).toMap
    val hotById    = hotResult.ioFirms.map(f => f.id -> f.markup).toMap

    hotResult.markupInflation should be > weakResult.markupInflation
    hotById.keySet.intersect(weakById.keySet).count(id => hotById(id) > weakById(id)) should be > 0
  }

  "BankingEconomics.runStep" should "remain insensitive to stale persisted demand signals when stage outputs are supplied" in {
    val staleWorld       = withSeedSignals(
      baseline.world,
      _.copy(
        sectorDemandMult = multiplierVector(BigDecimal("0.35")),
        sectorDemandPressure = multiplierVector(BigDecimal("0.35")),
        sectorHiringSignal = multiplierVector(BigDecimal("0.35")),
      ),
    )
    val explicitResult   = baseBankingRunStep(staleWorld, seed = 777L)
    val freshWorldResult = baseBankingRunStep(baseline.world, seed = 777L)

    explicitResult shouldBe freshWorldResult
  }

  "OpenEconEconomics.runStep" should "price non-bank equity returns from same-month equity market output" in {
    val ledger             = baseline.ledgerFinancialState.copy(
      insurance = baseline.ledgerFinancialState.insurance.copy(equityHoldings = PLN(1000000)),
      funds = baseline.ledgerFinancialState.funds.copy(
        nbfi = baseline.ledgerFinancialState.funds.nbfi.copy(equityHoldings = PLN(2000000)),
      ),
    )
    val sameMonthReturn    = Rate.decimal(4, 2)
    val staleBoundaryLoss  = withBoundaryEquityReturn(baseline.world, Rate.decimal(-20, 2))
    val staleBoundaryGain  = withBoundaryEquityReturn(baseline.world, Rate.decimal(20, 2))
    val sameMonthS7        = withSameMonthEquityReturn(baseline.s7, sameMonthReturn)
    val lossBoundaryResult = baseOpenEconRunStep(staleBoundaryLoss, ledger, sameMonthS7, seed = 5150L)
    val gainBoundaryResult = baseOpenEconRunStep(staleBoundaryGain, ledger, sameMonthS7, seed = 5150L)
    val lowerSameMonthS7   = withSameMonthEquityReturn(baseline.s7, Rate.decimal(-4, 2))
    val lowerSameMonth     = baseOpenEconRunStep(staleBoundaryGain, ledger, lowerSameMonthS7, seed = 5150L)

    lossBoundaryResult.nonBank.newInsurance.lastInvestmentIncome shouldBe gainBoundaryResult.nonBank.newInsurance.lastInvestmentIncome
    lossBoundaryResult.nonBank.newInsuranceBalances shouldBe gainBoundaryResult.nonBank.newInsuranceBalances
    lossBoundaryResult.nonBank.newNbfi.lastTfiNetInflow shouldBe gainBoundaryResult.nonBank.newNbfi.lastTfiNetInflow
    lossBoundaryResult.nonBank.newNbfiBalances shouldBe gainBoundaryResult.nonBank.newNbfiBalances

    gainBoundaryResult.nonBank.newInsurance.lastInvestmentIncome should not equal lowerSameMonth.nonBank.newInsurance.lastInvestmentIncome
    gainBoundaryResult.nonBank.newNbfi.lastTfiNetInflow should not equal lowerSameMonth.nonBank.newNbfi.lastTfiNetInflow
  }

  "MonthClosing.closeExecution" should "derive entry tight-demand unemployment from lagged decision signals instead of post-firm households" in {
    val base       = entrySensitiveInput
    val lowUnemp   = base.copy(
      banking = base.banking.copy(
        reassignedHouseholds = withUnemploymentShare(base.banking.reassignedHouseholds, base.banking.reassignedFirms, base.labor.newWage, BigDecimal("0.04")),
      ),
    )
    val highUnemp  = base.copy(
      banking = base.banking.copy(
        reassignedHouseholds = withUnemploymentShare(base.banking.reassignedHouseholds, base.banking.reassignedFirms, base.labor.newWage, BigDecimal("0.15")),
      ),
    )
    val lowLagged  = base.copy(
      openingWorld = withSeedSignals(base.openingWorld, _.copy(unemploymentRate = Share.decimal(4, 2))),
    )
    val highLagged = base.copy(
      openingWorld = withSeedSignals(base.openingWorld, _.copy(unemploymentRate = Share.decimal(15, 2))),
    )

    netBirths(highUnemp) shouldBe netBirths(lowUnemp)
    netBirths(lowLagged) should be > netBirths(highLagged)
  }

  it should "ignore closed month-t inflation when entry uses lagged nominal signals" in {
    val base           = entrySensitiveInput
    val deflation      = base.copy(priceEquity = base.priceEquity.copy(newInfl = Rate.decimal(-2, 2)))
    val positive       = base.copy(priceEquity = base.priceEquity.copy(newInfl = Rate.decimal(3, 2)))
    val negativeLagged = base.copy(openingWorld = withSeedSignals(base.openingWorld, _.copy(inflation = Rate.decimal(-2, 2))))
    val positiveLagged = base.copy(openingWorld = withSeedSignals(base.openingWorld, _.copy(inflation = Rate.decimal(3, 2))))

    netBirths(positive) shouldBe netBirths(deflation)
    netBirths(positiveLagged) should be > netBirths(negativeLagged)
  }

  it should "ignore closed month-t expected inflation when entry uses lagged nominal signals" in {
    val base           = entrySensitiveInput
    val negativeExp    = base.copy(
      openEconomy = base.openEconomy.copy(
        monetary = base.openEconomy.monetary.copy(
          newExp = base.openEconomy.monetary.newExp.copy(expectedInflation = Rate.decimal(-1, 2)),
        ),
      ),
    )
    val positiveExp    = base.copy(
      openEconomy = base.openEconomy.copy(
        monetary = base.openEconomy.monetary.copy(
          newExp = base.openEconomy.monetary.newExp.copy(expectedInflation = Rate.decimal(25, 3)),
        ),
      ),
    )
    val laggedNegative = base.copy(
      openingWorld = withSeedSignals(base.openingWorld, _.copy(expectedInflation = Rate.decimal(-1, 2))),
    )
    val laggedPositive = base.copy(
      openingWorld = withSeedSignals(base.openingWorld, _.copy(expectedInflation = Rate.decimal(25, 3))),
    )

    netBirths(positiveExp) shouldBe netBirths(negativeExp)
    netBirths(laggedPositive) should be > netBirths(laggedNegative)
  }

  it should "derive entry labor tightness from lagged decision signals instead of refreshed same-month slack" in {
    val base        = entrySensitiveInput
    val tight       = base.copy(labor = base.labor.copy(operationalHiringSlack = Share.decimal(10, 2)))
    val loose       = base.copy(labor = base.labor.copy(operationalHiringSlack = Share.One))
    val tightLagged = base.copy(openingWorld = withSeedSignals(base.openingWorld, _.copy(laggedHiringSlack = Share.decimal(10, 2))))
    val looseLagged = base.copy(openingWorld = withSeedSignals(base.openingWorld, _.copy(laggedHiringSlack = Share.One)))

    netBirths(loose) shouldBe netBirths(tight)
    netBirths(looseLagged) should be > netBirths(tightLagged)
  }

  it should "source startup absorption from lagged decision signals" in {
    val base   = entrySensitiveInput
    val weak   = base.copy(openingWorld = base.openingWorld.copy(pipeline = base.openingWorld.pipeline.copy(startupAbsorptionRate = Share.decimal(10, 2))))
    val strong = base.copy(openingWorld = base.openingWorld.copy(pipeline = base.openingWorld.pipeline.copy(startupAbsorptionRate = Share.One)))

    netBirths(strong) should be > netBirths(weak)
  }

  it should "keep month closing distinct from the next-month seed boundary" in {
    val input  = entrySensitiveInput.copy(labor = entrySensitiveInput.labor.copy(operationalHiringSlack = Share.decimal(21, 2)))
    val closed = MonthClosing.closeExecution(input, closingRandomness(1234L))

    closed.world.pipeline.operationalHiringSlack shouldBe Share.decimal(21, 2)
    closed.world.seedIn shouldBe input.openingWorld.seedIn
    closed.world.pipeline.sectorDemandMult shouldBe input.openingWorld.pipeline.sectorDemandMult
    closed.world.pipeline.sectorDemandPressure shouldBe input.openingWorld.pipeline.sectorDemandPressure
    closed.world.pipeline.sectorHiringSignal shouldBe input.openingWorld.pipeline.sectorHiringSignal
  }

  it should "keep explicit OperationalSignals aligned with post-reconcile labor slack" in {
    baseOperationalSignals.operationalHiringSlack shouldBe baseline.s2.operationalHiringSlack
  }
