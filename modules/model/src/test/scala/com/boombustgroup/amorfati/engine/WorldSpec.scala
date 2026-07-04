package com.boombustgroup.amorfati.engine

import com.boombustgroup.amorfati.Generators
import com.boombustgroup.amorfati.types.Share
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WorldSpec extends AnyFlatSpec with Matchers:

  "World.unemploymentRate" should "not go negative when realized employment exceeds demographic labor force" in {
    val world = Generators.testWorld(totalPopulation = 100, employed = 100)

    world.unemploymentRate(105) shouldBe Share.Zero
  }

  it should "exclude retirees from the unemployment denominator" in {
    val world = Generators.testWorld(
      totalPopulation = 100,
      employed = 80,
      social = com.boombustgroup.amorfati.engine.SocialState.zero.copy(
        demographics = com.boombustgroup.amorfati.agents.SocialSecurity.DemographicsState(
          retirees = 50,
          workingAgePop = 100,
          monthlyRetirements = 0,
        ),
      ),
    )

    world.derivedTotalPopulation shouldBe 150
    world.laborForcePopulation shouldBe 100
    world.unemploymentRate(80) shouldBe Share.decimal(20, 2)
  }
