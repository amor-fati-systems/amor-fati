package com.boombustgroup.amorfati.montecarlo.diagnostics

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.EngineFailure
import com.boombustgroup.amorfati.montecarlo.core.{McSeedMonth, SimError}
import com.boombustgroup.amorfati.montecarlo.runner.McRunner
import zio.{Cause, ZIO}
import zio.stream.ZStream

private[amorfati] object McDiagnosticRunner:

  final case class Job[Scenario](
      scenario: Scenario,
      scenarioId: String,
      seed: Long,
  )

  def seedParallelism(jobCount: Int): Int =
    scala.math.max(1, scala.math.min(java.lang.Runtime.getRuntime.availableProcessors(), jobCount))

  def runSeeds[A](
      seeds: Vector[Long],
      months: Int,
      params: SimParams,
      label: String = "baseline",
      traceFirmDecisions: Boolean = false,
  )(
      reduce: (Long, ZStream[Any, String, McSeedMonth]) => ZIO[Any, String, A],
  ): ZStream[Any, String, A] =
    runScenarioSeeds[(String, SimParams), A](
      Vector(label -> params),
      seeds,
      months,
      _._1,
      _._2,
      traceFirmDecisions = traceFirmDecisions,
    ) { (_, seed, monthsStream) =>
      reduce(seed, monthsStream)
    }

  def runScenarioSeeds[Scenario, A](
      scenarios: Vector[Scenario],
      seeds: Vector[Long],
      months: Int,
      scenarioId: Scenario => String,
      params: Scenario => SimParams,
      parallelism: Option[Int] = None,
      traceFirmDecisions: Boolean = false,
  )(
      reduce: (Scenario, Long, ZStream[Any, String, McSeedMonth]) => ZIO[Any, String, A],
  ): ZStream[Any, String, A] =
    val jobs = for
      scenario <- scenarios
      seed     <- seeds
    yield Job(scenario, scenarioId(scenario), seed)

    ZStream
      .fromIterable(jobs)
      .mapZIOPar(parallelism.getOrElse(seedParallelism(jobs.length))): job =>
        runJob(job, months, params(job.scenario), traceFirmDecisions, reduce)

  def runScenarioSeedStreams[Scenario, A](
      scenarios: Vector[Scenario],
      seeds: Vector[Long],
      months: Int,
      scenarioId: Scenario => String,
      params: Scenario => SimParams,
      parallelism: Option[Int] = None,
      traceFirmDecisions: Boolean = false,
  )(
      rows: (Scenario, Long, ZStream[Any, String, McSeedMonth]) => ZStream[Any, String, A],
  ): ZStream[Any, String, A] =
    val jobs = for
      scenario <- scenarios
      seed     <- seeds
    yield Job(scenario, scenarioId(scenario), seed)

    ZStream
      .fromIterable(jobs)
      .flatMapPar(parallelism.getOrElse(seedParallelism(jobs.length))): job =>
        runJobStream(job, months, params(job.scenario), traceFirmDecisions, rows)

  private def runJob[Scenario, A](
      job: Job[Scenario],
      months: Int,
      params: SimParams,
      traceFirmDecisions: Boolean,
      reduce: (Scenario, Long, ZStream[Any, String, McSeedMonth]) => ZIO[Any, String, A],
  ): ZIO[Any, String, A] =
    val context = s"Scenario ${job.scenarioId} seed ${job.seed}"
    val effect  =
      ZIO.suspendSucceed:
        given SimParams  = params
        val monthsStream = McRunner
          .seedMonths(job.seed, months, traceFirmDecisions)
          .mapError(renderSimError)
        reduce(job.scenario, job.seed, monthsStream)
          .catchAll(err => ZIO.fail(s"$context failed: $err"))

    effect.catchAllCause: cause =>
      ZIO.fail(renderCause(context, cause))

  private def runJobStream[Scenario, A](
      job: Job[Scenario],
      months: Int,
      params: SimParams,
      traceFirmDecisions: Boolean,
      rows: (Scenario, Long, ZStream[Any, String, McSeedMonth]) => ZStream[Any, String, A],
  ): ZStream[Any, String, A] =
    val context = s"Scenario ${job.scenarioId} seed ${job.seed}"
    ZStream
      .unwrap:
        ZIO.suspendSucceed:
          given SimParams  = params
          val monthsStream = McRunner
            .seedMonths(job.seed, months, traceFirmDecisions)
            .mapError(renderSimError)
          ZIO.succeed:
            rows(job.scenario, job.seed, monthsStream)
              .mapError(err => s"$context failed: $err")
      .catchAllCause: cause =>
        ZStream.fail(renderCause(context, cause))

  private def renderSimError(error: SimError): String =
    error match
      case SimError.Engine(failure) => failure.diagnosticMessage
      case other                    => other.toString

  private def renderCause(context: String, cause: Cause[String]): String =
    cause.failureOption.getOrElse:
      EngineFailure
        .firstIn(cause.defects)
        .map(failure => s"$context crashed: ${failure.diagnosticMessage}")
        .getOrElse(s"$context crashed: ${cause.prettyPrint}")
