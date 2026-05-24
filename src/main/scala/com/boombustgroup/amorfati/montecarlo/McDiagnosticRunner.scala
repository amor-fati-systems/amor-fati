package com.boombustgroup.amorfati.montecarlo

import com.boombustgroup.amorfati.config.SimParams
import zio.ZIO
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
  )(
      reduce: (Long, ZStream[Any, String, McSeedMonth]) => ZIO[Any, String, A],
  ): ZStream[Any, String, A] =
    runScenarioSeeds[(String, SimParams), A](Vector(label -> params), seeds, months, _._1, _._2) { (_, seed, monthsStream) =>
      reduce(seed, monthsStream)
    }

  def runScenarioSeeds[Scenario, A](
      scenarios: Vector[Scenario],
      seeds: Vector[Long],
      months: Int,
      scenarioId: Scenario => String,
      params: Scenario => SimParams,
      parallelism: Option[Int] = None,
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
        runJob(job, months, params(job.scenario), reduce)

  private def runJob[Scenario, A](
      job: Job[Scenario],
      months: Int,
      params: SimParams,
      reduce: (Scenario, Long, ZStream[Any, String, McSeedMonth]) => ZIO[Any, String, A],
  ): ZIO[Any, String, A] =
    val context = s"Scenario ${job.scenarioId} seed ${job.seed}"
    val effect  =
      ZIO.suspendSucceed:
        given SimParams  = params
        val monthsStream = McRunner
          .seedMonths(job.seed, months)
          .mapError(_.toString)
        reduce(job.scenario, job.seed, monthsStream)
          .catchAll(err => ZIO.fail(s"$context failed: $err"))

    effect.catchAllCause: cause =>
      cause.failureOption match
        case Some(err) => ZIO.fail(err)
        case None      => ZIO.fail(s"$context crashed: ${cause.prettyPrint}")
