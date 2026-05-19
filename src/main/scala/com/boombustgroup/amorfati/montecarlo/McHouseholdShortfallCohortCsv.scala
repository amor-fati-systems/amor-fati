package com.boombustgroup.amorfati.montecarlo

import com.boombustgroup.amorfati.agents.Household
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.flows.FlowSimulation
import zio.stream.ZStream
import zio.{Scope, ZIO}

import java.io.{BufferedWriter, File}
import java.nio.file.{Files, StandardCopyOption}
import scala.util.Using

private[montecarlo] object McHouseholdShortfallCohortCsv:

  private val operation = "write household shortfall cohort CSV"

  def tapSeedCohorts[A](
      seed: Long,
      rc: McRunConfig,
      outputDir: File,
      rows: ZStream[Any, SimError, A],
  )(
      executionMonth: A => ExecutionMonth,
      state: A => FlowSimulation.HouseholdSnapshotState,
      monthlyFlows: A => Vector[Household.MonthlyFlow],
  ): ZStream[Any, SimError, A] =
    if !rc.householdSnapshotSchedule.enabled then rows
    else
      val partFile = seedPartFile(outputDir, seed, rc)
      ZStream.unwrapScoped:
        openWriter(partFile).map: writer =>
          rows.tap: row =>
            writeCohortRows(writer, partFile, rc, seed, executionMonth(row), state(row), monthlyFlows(row))

  def combineSeedFiles(rc: McRunConfig, outputDir: File): ZIO[Any, SimError, Unit] =
    if !rc.householdSnapshotSchedule.enabled then ZIO.unit
    else
      val outputFile = McOutputFiles.householdShortfallCohortFile(outputDir, rc)
      val tempFile   = new File(s"${outputFile.getPath}.tmp")
      ZIO
        .attemptBlocking:
          Using.resource(Files.newBufferedWriter(tempFile.toPath)): writer =>
            writer.write(McHouseholdShortfallCohortSchema.header)
            writer.newLine()
            for seed <- 1L to rc.nSeeds.toLong do
              val partFile = seedPartFile(outputDir, seed, rc)
              if Files.exists(partFile.toPath) then
                Using.resource(Files.lines(partFile.toPath)): lines =>
                  val it = lines.iterator()
                  while it.hasNext do
                    writer.write(it.next())
                    writer.newLine()
          Files.move(tempFile.toPath, outputFile.toPath, StandardCopyOption.REPLACE_EXISTING)
          for seed <- 1L to rc.nSeeds.toLong do Files.deleteIfExists(seedPartFile(outputDir, seed, rc).toPath)
        .unit
        .mapError(outputFailure("combine household shortfall cohort CSV", outputFile))
        .onError(_ => deleteIfExists(tempFile).ignore)

  private def writeCohortRows(
      writer: BufferedWriter,
      outputFile: File,
      rc: McRunConfig,
      seed: Long,
      month: ExecutionMonth,
      state: FlowSimulation.HouseholdSnapshotState,
      monthlyFlows: Vector[Household.MonthlyFlow],
  ): ZIO[Any, SimError, Unit] =
    if !rc.householdSnapshotSchedule.includes(month, ExecutionMonth(rc.runDurationMonths)) then ZIO.unit
    else
      ZIO
        .attemptBlocking:
          val schema = McHouseholdShortfallCohortSchema.csvSchema
          McHouseholdShortfallCohortSchema
            .rows(rc.runId, seed, month, state, monthlyFlows)
            .foreach: row =>
              writer.write(schema.render(row))
              writer.newLine()
        .unit
        .mapError(outputFailure(operation, outputFile))

  private def openWriter(outputFile: File): ZIO[Scope, SimError, BufferedWriter] =
    ZIO.fromAutoCloseable(
      ZIO
        .attemptBlocking(Files.newBufferedWriter(outputFile.toPath))
        .mapError(outputFailure(s"open $operation writer", outputFile)),
    )

  private def seedPartFile(outputDir: File, seed: Long, rc: McRunConfig): File =
    new File(outputDir, f".${McOutputFiles.householdShortfallCohortFile(outputDir, rc).getName}.seed${seed}%03d.part")

  private def deleteIfExists(file: File): ZIO[Any, SimError, Unit] =
    ZIO
      .attemptBlocking(Files.deleteIfExists(file.toPath))
      .unit
      .mapError(outputFailure(s"cleanup $operation temp file", file))

  private def outputFailure(operation: String, path: File)(err: Throwable): SimError =
    SimError.OutputFailure(operation, path.getPath, Option(err.getMessage).filter(_.nonEmpty).getOrElse(err.getClass.getSimpleName))
