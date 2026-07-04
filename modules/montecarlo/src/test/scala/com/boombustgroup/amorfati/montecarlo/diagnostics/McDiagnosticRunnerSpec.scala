package com.boombustgroup.amorfati.montecarlo.diagnostics

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.{EngineFailure, EngineFailureCategory}
import com.boombustgroup.amorfati.montecarlo.core.SimError
import org.scalatest.EitherValues.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.{Runtime, Unsafe, ZIO}

class McDiagnosticRunnerSpec extends AnyFlatSpec with Matchers:

  "McDiagnosticRunner" should "render EngineFailure defects with category and boundary" in {
    val failure = EngineFailure.unsupportedTopology("McDiagnosticRunnerSpec.step", "synthetic topology failure")

    val result = unsafeRun:
      McDiagnosticRunner
        .runScenarioSeeds[String, Nothing](
          scenarios = Vector("synthetic"),
          seeds = Vector(1L),
          months = 1,
          scenarioId = identity,
          params = _ => SimParams.defaults,
          parallelism = Some(1),
        ) { (_, _, _) =>
          ZIO.die(failure)
        }
        .runCollect

    val rendered = result.left.value
    rendered should include("Scenario synthetic seed 1 crashed")
    rendered should include("category=unsupported_topology")
    rendered should include("boundary=McDiagnosticRunnerSpec.step")
    rendered should include("synthetic topology failure")
  }

  it should "preserve EngineFailure category in SimError rendering" in {
    val failure = EngineFailure.runtimeLedgerExecution("RuntimeFlowExecutor.execute", "synthetic ledger failure")

    SimError.Engine(failure).toString should include(s"category=${EngineFailureCategory.RuntimeLedgerExecutionFailure.code}")
    SimError.Engine(failure).toString should include("boundary=RuntimeFlowExecutor.execute")
  }

  private def unsafeRun[E, A](effect: ZIO[Any, E, A]): Either[E, A] =
    Unsafe.unsafe: unsafe =>
      given Unsafe = unsafe
      Runtime.default.unsafe.run(effect.either).getOrThrowFiberFailure()
