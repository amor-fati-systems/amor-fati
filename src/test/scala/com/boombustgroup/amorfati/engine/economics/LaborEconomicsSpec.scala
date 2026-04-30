package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import com.boombustgroup.amorfati.{Generators, TestFirmState}
import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.SocialState
import com.boombustgroup.amorfati.init.{InitRandomness, WorldInit}
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Tests LaborEconomics.compute() produces reasonable results. */
class LaborEconomicsSpec extends AnyFlatSpec with Matchers:

  private given p: SimParams = SimParams.defaults

  private val initResult = WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))
  private val world      = initResult.world
  private val firms      = initResult.firms
  private val households = initResult.households

  private val s1 = FiscalConstraintEconomics.Output(
    month = ExecutionMonth.First,
    lendingBaseRate = world.nbp.referenceRate,
    resWage = world.householdMarket.reservationWage,
    baseMinWage = world.gov.minWageLevel,
    updatedMinWagePriceLevel = world.priceLevel,
  )

  "LaborEconomics.compute" should "produce positive wage" in {
    val result = LaborEconomics.compute(world, firms, households, s1)
    decimal(result.newWage) should be > BigDecimal("0.0")
  }

  it should "produce positive employment" in {
    val result = LaborEconomics.compute(world, firms, households, s1)
    result.employed should be > 0
  }

  it should "produce demographics with positive working-age pop" in {
    val result = LaborEconomics.compute(world, firms, households, s1)
    result.newDemographics.workingAgePop should be > 0
  }

  it should "produce consistent wage growth" in {
    val result = LaborEconomics.compute(world, firms, households, s1)
    // Wage growth should be a finite number
    decimal(result.wageGrowth).isNaN shouldBe false
  }

  it should "index nominal wage pressure to anchored expected inflation" in {
    val expectationsWorld = Generators
      .testWorld(
        totalPopulation = 1000,
        employed = 900,
        marketWage = PLN(8000),
        reservationWage = PLN(1000),
      )
      .copy(
        regionalWages = Region.all.map(_ -> PLN(8000)).toMap,
      )
    val expectationFirms  = (0 until 100).map: id =>
      TestFirmState(FirmId(id), tech = TechState.Traditional(9), initialSize = 9)
    val expectationS1     = s1.copy(
      lendingBaseRate = expectationsWorld.nbp.referenceRate,
      resWage = expectationsWorld.householdMarket.reservationWage,
      baseMinWage = expectationsWorld.gov.minWageLevel,
      updatedMinWagePriceLevel = expectationsWorld.priceLevel,
    )
    val zeroExpected      = expectationsWorld.copy(
      mechanisms = expectationsWorld.mechanisms.copy(
        expectations = expectationsWorld.mechanisms.expectations.copy(expectedInflation = Rate.Zero),
      ),
    )
    val highExpected      = expectationsWorld.copy(
      mechanisms = expectationsWorld.mechanisms.copy(
        expectations = expectationsWorld.mechanisms.expectations.copy(expectedInflation = Rate.decimal(50, 2)),
      ),
    )

    val zeroResult = LaborEconomics.compute(zeroExpected, expectationFirms.toVector, Vector.empty, expectationS1)
    val highResult = LaborEconomics.compute(highExpected, expectationFirms.toVector, Vector.empty, expectationS1)

    highResult.newWage should be > zeroResult.newWage
    highResult.wageGrowth should be > zeroResult.wageGrowth
  }

  it should "add wage pressure when unemployment is below NAIRU" in {
    val laborForce = 1000

    val pressure = LaborEconomics.nairuWagePressure(laborForce, employed = 980)

    pressure should be > Coefficient.Zero
    decimal(pressure) shouldBe BigDecimal("0.0018") +- BigDecimal("0.0001")
    LaborEconomics.nairuWagePressure(laborForce, employed = 900) shouldBe Coefficient.Zero
  }

  it should "compress aggregate hiring plans when labor demand exceeds available labor" in {
    LaborEconomics.operationalHiringSlackFactor(laborDemand = 120000, availableLabor = 80000) should be < Share.One
  }

  it should "leave hiring plans unchanged when labor demand fits available labor" in {
    LaborEconomics.operationalHiringSlackFactor(laborDemand = 60000, availableLabor = 80000) shouldBe Share.One
  }

  it should "exclude retirees from aggregate labor supply when computing hiring slack" in {
    val laborForce       = 100
    val retired          = 100
    val constrainedWorld = Generators.testWorld(
      totalPopulation = laborForce,
      employed = 95,
      marketWage = PLN(8000),
      reservationWage = PLN(4666),
      social = SocialState.zero.copy(
        demographics = SocialSecurity.DemographicsState(retirees = retired, workingAgePop = laborForce, monthlyRetirements = 0),
      ),
    )
    val laborHeavyFirms  = (0 until 100).map: id =>
      TestFirmState(FirmId(id), tech = TechState.Traditional(150), initialSize = 150)
    val constrainedS1    = s1.copy(
      lendingBaseRate = constrainedWorld.nbp.referenceRate,
      resWage = constrainedWorld.householdMarket.reservationWage,
      baseMinWage = constrainedWorld.gov.minWageLevel,
      updatedMinWagePriceLevel = constrainedWorld.priceLevel,
    )
    val result           = LaborEconomics.compute(constrainedWorld, laborHeavyFirms.toVector, Vector.empty, constrainedS1)

    result.employed should be <= laborForce
    result.operationalHiringSlack should be < Share.One
  }

  it should "reconcile post-firm labor demand and realized employment from post-step state" in {
    val s2Pre = LaborEconomics.compute(world, firms, households, s1)

    val postLiving = firms.take(10).filter(Firm.isAlive)
    val postHh     = households.map(_.copy(status = HhStatus.Unemployed(0)))
    val post       = LaborEconomics.reconcilePostFirmStep(world, s1, s2Pre, postLiving, postHh)

    post.laborDemand shouldBe postLiving.map(Firm.workerCount).sum
    post.employed shouldBe 0
    decimal(post.newWage).should(be >= decimal(s1.resWage))
  }
