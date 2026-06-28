package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.config.{OpeningBankProfileScenario, ScenarioRegistry, SimParams}
import com.boombustgroup.amorfati.types.Multiplier
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path

class ScenarioRunExportSpec extends AnyFlatSpec with Matchers:

  private def tsv(fields: String*): String =
    fields.mkString("\t")

  "ScenarioRunExport" should "preserve default scenarios and all-scenario CLI selection" in {
    ScenarioRunExport.Config().scenarios.map(_.id) shouldBe ScenarioRegistry.defaultScenarioIds
    ScenarioRunExport.parseArgs(Vector("--scenarios", "all")).map(_.scenarios.map(_.id)) shouldBe Right(ScenarioRegistry.all.map(_.id))
  }

  "ScenarioRunExport" should "export scenario provenance in registry and delta artifacts" in {
    val scenarios = ScenarioRegistry.select("monetary-tightening").fold(err => fail(err), identity)

    val registry = ScenarioRunExport.renderRegistry(scenarios)
    registry should include("Provenance")
    registry should include("Source / provider")
    registry should include("policy counterfactual")
    registry should include("Internal policy scenario over the NBP rate channel")

    val deltas = ScenarioRunExport.renderDeltas(scenarios)
    deltas.linesIterator.next() shouldBe
      tsv("Scenario", "Parameter", "Baseline", "ScenarioValue", "Note", "ProvenanceClassification", "SourceProvider", "Vintage", "TransformationNotes")
    deltas should include(tsv("monetary-tightening", "monetary.initialRate", "0.0375", "0.075", "Higher starting reference rate.", "policy_counterfactual"))
    deltas should include("2026-04-30 baseline counterfactual")
    deltas should include("Raises the policy-rate starting point and Taylor-rule response relative to baseline.")
  }

  it should "export scenario provenance in per-scenario metadata" in {
    val scenario = ScenarioRegistry.get("energy-shock").fold(err => fail(err), identity)
    val metadata = ScenarioRunExport.renderScenarioMetadata(
      ScenarioRunExport.Config(scenarios = Vector(scenario), runId = "provenance-spec", out = Path.of("target/scenarios")),
      scenario,
    )

    metadata should include("- Provenance classification: historical analogue")
    metadata should include("- Source/provider: EU ETS and commodity-price stress analogue")
    metadata should include("| Parameter | Baseline | Scenario | Note | Provenance | Source / provider | Vintage | Transformation notes |")
    metadata should include("| climate.etsBasePrice | 80 | 120 | Higher EU ETS starting price. | historical analogue |")
    metadata should include("Raises ETS and energy-cost burden, then starts a commodity-price shock in month 6.")
  }

  it should "route bank-failure opening capital stress through the bank profile scenario" in {
    val scenario = ScenarioRegistry.get("bank-failure").fold(err => fail(err), identity)

    scenario.params.banking.openingBankCapitalAggregateTarget shouldBe SimParams.defaults.banking.openingBankCapitalAggregateTarget
    scenario.params.banking.openingBankProfileScenario shouldBe OpeningBankProfileScenario.haircutOwnFunds(Multiplier.decimal(55, 2))
    scenario.deltas.map(_.parameter) should contain("banking.openingBankProfileScenario")
  }

  it should "keep scenario runs from overriding the opening bank capital aggregate target directly" in
    ScenarioRegistry.all
      .filterNot(_.id == "baseline")
      .foreach: scenario =>
        scenario.params.banking.openingBankCapitalAggregateTarget shouldBe SimParams.defaults.banking.openingBankCapitalAggregateTarget

end ScenarioRunExportSpec
