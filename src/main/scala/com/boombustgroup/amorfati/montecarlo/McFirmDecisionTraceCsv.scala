package com.boombustgroup.amorfati.montecarlo

import com.boombustgroup.amorfati.agents.Firm
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import zio.stream.ZStream
import zio.{Scope, ZIO}

import java.io.{BufferedWriter, File}
import java.nio.file.Files
import scala.util.Using

private[montecarlo] object McFirmDecisionTraceCsv:

  private val operation = "write firm decision trace CSV"

  def tapSeedTraces[A](
      seed: Long,
      rc: McRunConfig,
      outputDir: File,
      rows: ZStream[Any, SimError, A],
  )(
      executionMonth: A => ExecutionMonth,
      decisionTraces: A => Vector[Firm.DecisionTrace],
  ): ZStream[Any, SimError, A] =
    if !rc.firmDecisionTraceSelection.enabled then rows
    else
      val partFile = seedPartFile(outputDir, seed, rc)
      ZStream.unwrapScoped:
        openWriter(partFile).map: writer =>
          rows.tap: row =>
            writeTraceRows(writer, partFile, rc, seed, executionMonth(row), decisionTraces(row))

  def combineSeedFiles(rc: McRunConfig, outputDir: File): ZIO[Any, SimError, Unit] =
    if !rc.firmDecisionTraceSelection.enabled then ZIO.unit
    else
      val outputFile = McOutputFiles.firmDecisionTraceFile(outputDir, rc)
      val tempFile   = new File(s"${outputFile.getPath}.tmp")
      (for
        _ <- writeCombinedFile(tempFile, outputFile, rc, outputDir)
        _ <- finalizeFile(tempFile, outputFile)
        _ <- deleteSeedPartFiles(outputDir, rc)
      yield ()).onError(_ => deleteIfExists(tempFile).ignore)

  private def writeCombinedFile(tempFile: File, outputFile: File, rc: McRunConfig, outputDir: File): ZIO[Any, SimError, Unit] =
    ZIO
      .attemptBlocking:
        Using.resource(Files.newBufferedWriter(tempFile.toPath)): writer =>
          writer.write(McFirmDecisionTraceSchema.header)
          writer.newLine()
          for seed <- 1L to rc.nSeeds.toLong do
            val partFile = seedPartFile(outputDir, seed, rc)
            if Files.exists(partFile.toPath) then
              Using.resource(Files.lines(partFile.toPath)): lines =>
                val it = lines.iterator()
                while it.hasNext do
                  writer.write(it.next())
                  writer.newLine()
      .unit
      .mapError(outputFailure("combine firm decision trace CSV", outputFile))

  private def deleteSeedPartFiles(outputDir: File, rc: McRunConfig): ZIO[Any, SimError, Unit] =
    ZIO
      .attemptBlocking:
        for seed <- 1L to rc.nSeeds.toLong do Files.deleteIfExists(seedPartFile(outputDir, seed, rc).toPath)
      .unit
      .mapError(outputFailure(s"cleanup $operation seed part files", outputDir))

  private def finalizeFile(tempFile: File, outputFile: File): ZIO[Any, SimError, Unit] =
    McCsvFile.finalizeFile(tempFile.toPath, outputFile.toPath, (operation, path, err) => outputFailure(operation, path.toFile)(err))

  private def writeTraceRows(
      writer: BufferedWriter,
      outputFile: File,
      rc: McRunConfig,
      seed: Long,
      month: ExecutionMonth,
      traces: Vector[Firm.DecisionTrace],
  ): ZIO[Any, SimError, Unit] =
    ZIO
      .attemptBlocking:
        val schema = McFirmDecisionTraceSchema.csvSchema
        McFirmDecisionTraceSchema
          .rows(rc.runId, seed, month.toInt, traces, rc.firmDecisionTraceSelection)
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
    new File(outputDir, f".${McOutputFiles.firmDecisionTraceFile(outputDir, rc).getName}.seed${seed}%03d.part")

  private def deleteIfExists(file: File): ZIO[Any, SimError, Unit] =
    ZIO
      .attemptBlocking(Files.deleteIfExists(file.toPath))
      .unit
      .mapError(outputFailure(s"cleanup $operation temp file", file))

  private def outputFailure(operation: String, path: File)(err: Throwable): SimError =
    SimError.OutputFailure(operation, path.getPath, Option(err.getMessage).filter(_.nonEmpty).getOrElse(err.getClass.getSimpleName))
