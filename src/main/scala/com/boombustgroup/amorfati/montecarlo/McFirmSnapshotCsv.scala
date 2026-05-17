package com.boombustgroup.amorfati.montecarlo

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.flows.FlowSimulation
import zio.stream.ZStream
import zio.{Scope, ZIO}

import java.io.{BufferedWriter, File}
import java.nio.file.{Files, StandardCopyOption}
import scala.util.Using

private[montecarlo] object McFirmSnapshotCsv:

  private val operation = "write firm snapshot CSV"

  def tapSeedSnapshots[A](
      seed: Long,
      rc: McRunConfig,
      outputDir: File,
      rows: ZStream[Any, SimError, A],
  )(
      executionMonth: A => ExecutionMonth,
      state: A => FlowSimulation.SimState,
  )(using SimParams): ZStream[Any, SimError, A] =
    if !rc.firmSnapshotSchedule.enabled then rows
    else
      val partFile = seedPartFile(outputDir, seed, rc)
      ZStream.unwrapScoped:
        openWriter(partFile).map: writer =>
          rows.tap: row =>
            writeSnapshotRows(writer, partFile, rc, seed, executionMonth(row), state(row))

  def combineSeedFiles(rc: McRunConfig, outputDir: File): ZIO[Any, SimError, Unit] =
    if !rc.firmSnapshotSchedule.enabled then ZIO.unit
    else
      val outputFile = McOutputFiles.firmSnapshotFile(outputDir, rc)
      val tempFile   = new File(s"${outputFile.getPath}.tmp")
      ZIO
        .attemptBlocking:
          Using.resource(Files.newBufferedWriter(tempFile.toPath)): writer =>
            writer.write(McFirmSnapshotSchema.header)
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
        .mapError(outputFailure("combine firm snapshot CSV", outputFile))
        .onError(_ => deleteIfExists(tempFile).ignore)

  private def writeSnapshotRows(
      writer: BufferedWriter,
      outputFile: File,
      rc: McRunConfig,
      seed: Long,
      month: ExecutionMonth,
      state: FlowSimulation.SimState,
  )(using SimParams): ZIO[Any, SimError, Unit] =
    if !rc.firmSnapshotSchedule.includes(month, ExecutionMonth(rc.runDurationMonths)) then ZIO.unit
    else
      ZIO
        .attemptBlocking:
          val schema = McFirmSnapshotSchema.csvSchema
          McFirmSnapshotSchema
            .rows(rc.runId, seed, month, state)
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
    new File(outputDir, f".${McOutputFiles.firmSnapshotFile(outputDir, rc).getName}.seed${seed}%03d.part")

  private def deleteIfExists(file: File): ZIO[Any, SimError, Unit] =
    ZIO
      .attemptBlocking(Files.deleteIfExists(file.toPath))
      .unit
      .mapError(outputFailure(s"cleanup $operation temp file", file))

  private def outputFailure(operation: String, path: File)(err: Throwable): SimError =
    SimError.OutputFailure(operation, path.getPath, Option(err.getMessage).filter(_.nonEmpty).getOrElse(err.getClass.getSimpleName))
