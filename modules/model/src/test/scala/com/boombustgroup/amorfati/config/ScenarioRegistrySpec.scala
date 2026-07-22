package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.types.Multiplier
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ScenarioRegistrySpec extends AnyFlatSpec with Matchers:

  "ScenarioRegistry" should "record provenance metadata for every scenario delta" in
    ScenarioRegistry.all
      .foreach: scenario =>
        scenario.deltas should not be empty
        scenario.deltas.foreach: delta =>
          withClue(s"${scenario.id} ${delta.parameter}: ") {
            delta.provenance.classification should not be ScenarioRegistry.ProvenanceClassification.ExplicitAssumption
            delta.provenance.sourceProvider.exists(_.trim.nonEmpty) shouldBe true
            delta.provenance.vintage.exists(_.trim.nonEmpty) shouldBe true
            delta.provenance.transformationNotes.exists(_.trim.nonEmpty) shouldBe true
          }

  it should "preserve default scenario selection and all-scenario lookup" in {
    ScenarioRegistry.defaultScenarioIds.map(_.value) shouldBe Vector("monetary-tightening", "fiscal-expansion")
    ScenarioRegistry.select("all").map(_.map(_.id)) shouldBe Right(ScenarioRegistry.all.map(_.id))
    ScenarioRegistry.select("none") shouldBe Right(Vector.empty)
    ScenarioRegistry.select("baseline").isLeft shouldBe true
  }

  it should "materialize every selected patch from the resolved compatible baseline" in {
    val baseline = BaselineCatalog.legacy.resolve(BaselineCatalog.LegacyDefaultsId.value).fold(error => fail(error.toString), identity)
    val scenario = ScenarioRegistry.get("bank-failure").fold(error => fail(error), identity)
    val runs     = ScenarioRegistry.prepare(baseline, Vector(scenario)).fold(error => fail(error.toString), identity)

    runs.map(_.id) shouldBe Vector("baseline", "bank-failure")
    runs.head.params shouldBe SimParams.defaults
    runs(1).params.banking.openingBankCapitalAggregateTarget shouldBe SimParams.defaults.banking.openingBankCapitalAggregateTarget
    runs(1).params.banking.openingBankProfileScenario shouldBe OpeningBankProfileScenario.haircutOwnFunds(Multiplier.decimal(55, 2))
  }

  it should "reject a scenario for an incompatible baseline" in {
    val resolved       = BaselineCatalog.legacy.resolve(BaselineCatalog.LegacyDefaultsId.value).fold(error => fail(error.toString), identity)
    val incompatibleId = BaselineId.from("PL-2026-Q2-v1").fold(error => fail(error), identity)
    val incompatible   = new BaselineBundle(
      resolved.manifest.copy(id = incompatibleId),
      resolved.components,
      SimParams.defaults,
    )
    val scenario       = ScenarioRegistry.get("monetary-tightening").fold(error => fail(error), identity)

    ScenarioRegistry.prepare(incompatible, Vector(scenario)) shouldBe
      Left(
        ScenarioCompositionError.IncompatibleBaseline(
          scenario.id,
          incompatibleId,
          Vector(BaselineCatalog.LegacyDefaultsId),
        ),
      )
  }

end ScenarioRegistrySpec
