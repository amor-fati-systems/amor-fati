package com.boombustgroup.amorfati.montecarlo.core

import zio.ZIO

import java.io.File

private[montecarlo] object McOutputFiles:

  def prepareOutputDir(outputDir: File): ZIO[Any, SimError, Unit] =
    ZIO
      .attemptBlocking:
        if outputDir.exists() then
          if !outputDir.isDirectory then throw java.io.IOException(s"path exists but is not a directory: ${outputDir.getPath}")
        else if !outputDir.mkdirs() && (!outputDir.exists() || !outputDir.isDirectory) then
          throw java.io.IOException(s"failed to create output directory: ${outputDir.getPath}")
      .mapError(outputFailure("prepare output directory", outputDir))

  def seedFile(outputDir: File, seed: Long, rc: McRunConfig): File =
    new File(outputDir, f"${filePrefix(rc)}_seed${seed}%03d.tsv")

  def householdFile(outputDir: File, rc: McRunConfig): File =
    new File(outputDir, s"${filePrefix(rc)}_hh.tsv")

  def bankFile(outputDir: File, rc: McRunConfig): File =
    new File(outputDir, s"${filePrefix(rc)}_banks.tsv")

  def firmFile(outputDir: File, rc: McRunConfig): File =
    new File(outputDir, s"${filePrefix(rc)}_firms.tsv")

  def firmSnapshotFile(outputDir: File, rc: McRunConfig): File =
    new File(outputDir, s"${filePrefix(rc)}_firm_snapshots.tsv")

  def householdSnapshotFile(outputDir: File, rc: McRunConfig): File =
    new File(outputDir, s"${filePrefix(rc)}_household_snapshots.tsv")

  def householdShortfallCohortFile(outputDir: File, rc: McRunConfig): File =
    new File(outputDir, s"${filePrefix(rc)}_household_shortfall_cohorts.tsv")

  def firmDecisionTraceFile(outputDir: File, rc: McRunConfig): File =
    new File(outputDir, s"${filePrefix(rc)}_firm_decision_trace.tsv")

  def savedFiles(outputDir: File, rc: McRunConfig): Vector[File] =
    val baselineFiles          =
      (1L to rc.nSeeds.toLong).map(seed => seedFile(outputDir, seed, rc)).toVector ++
        Vector(householdFile(outputDir, rc), bankFile(outputDir, rc), firmFile(outputDir, rc))
    val withSnapshots          =
      if rc.firmSnapshotSchedule.enabled then baselineFiles :+ firmSnapshotFile(outputDir, rc)
      else baselineFiles
    val withHouseholdSnapshots =
      if rc.householdSnapshotSchedule.enabled then withSnapshots :+ householdSnapshotFile(outputDir, rc) :+ householdShortfallCohortFile(outputDir, rc)
      else withSnapshots
    if rc.firmDecisionTraceSelection.enabled then withHouseholdSnapshots :+ firmDecisionTraceFile(outputDir, rc)
    else withHouseholdSnapshots

  private def filePrefix(rc: McRunConfig): String =
    s"${rc.outputPrefix}_${rc.runId}_${rc.runDurationMonths}m"

  private def outputFailure(operation: String, path: File)(err: Throwable): SimError =
    SimError.OutputFailure(operation, path.getPath, Option(err.getMessage).filter(_.nonEmpty).getOrElse(err.getClass.getSimpleName))
