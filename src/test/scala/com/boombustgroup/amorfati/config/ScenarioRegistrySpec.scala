package com.boombustgroup.amorfati.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ScenarioRegistrySpec extends AnyFlatSpec with Matchers:

  "ScenarioRegistry" should "record provenance metadata for every non-baseline scenario delta" in {
    val allowedClassifications = ScenarioRegistry.ProvenanceClassification.values.map(_.id).toSet

    ScenarioRegistry.all
      .filterNot(_.id == "baseline")
      .foreach: scenario =>
        scenario.deltas should not be empty
        scenario.deltas.foreach: delta =>
          withClue(s"${scenario.id} ${delta.parameter}: ") {
            allowedClassifications should contain(delta.provenance.classification.id)
            delta.provenance.sourceProvider.exists(_.trim.nonEmpty) shouldBe true
            delta.provenance.vintage.exists(_.trim.nonEmpty) shouldBe true
            delta.provenance.transformationNotes.exists(_.trim.nonEmpty) shouldBe true
          }
  }

  it should "preserve default scenario selection and all-scenario lookup" in {
    ScenarioRegistry.defaultScenarioIds shouldBe Vector("baseline", "monetary-tightening", "fiscal-expansion")
    ScenarioRegistry.select("all").map(_.map(_.id)) shouldBe Right(ScenarioRegistry.all.map(_.id))
    ScenarioRegistry.select("baseline, monetary-tightening").map(_.map(_.id)) shouldBe Right(Vector("baseline", "monetary-tightening"))
  }

end ScenarioRegistrySpec
