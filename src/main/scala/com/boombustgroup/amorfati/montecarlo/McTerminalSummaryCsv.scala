package com.boombustgroup.amorfati.montecarlo

import zio.ZIO

import java.io.File
import java.nio.file.Path

private[montecarlo] object McTerminalSummaryCsv:

  def writeAll(rc: McRunConfig, outputDir: File, results: zio.Chunk[McTerminalSummaryRows]): ZIO[Any, SimError, Unit] =
    val sorted = results.sortBy(_.seed)
    ZIO.foreachDiscard(McTerminalSummarySchema.specs)(spec => writeSummaryCsv(spec, rc, outputDir, sorted))

  private def writeSummaryCsv(
      spec: McTerminalSummarySchema.SummarySpec,
      rc: McRunConfig,
      outputDir: File,
      sortedResults: zio.Chunk[McTerminalSummaryRows],
  ) =
    val outputFile = spec.outputFile(outputDir, rc)
    val rows       = sortedResults.flatMap(_.rowsFor(spec.id))
    McCsvFile.writeAll(outputFile.toPath, rows, spec.csvSchema)(outputFailure).unit

  private def outputFailure(operation: String, path: Path, err: Throwable): SimError =
    SimError.OutputFailure(operation, path.toString, Option(err.getMessage).filter(_.nonEmpty).getOrElse(err.getClass.getSimpleName))
