package com.boombustgroup.amorfati.montecarlo

import zio.stream.ZStream
import zio.ZIO

import java.io.File

private[montecarlo] object McTimeseriesCsv:

  def writeStreaming[A](
      outputFile: File,
      rows: ZStream[Any, SimError, A],
      schema: McCsvSchema[A],
      emptyError: => SimError,
  ): ZIO[Any, SimError, A] =
    McCsvFile.writeStreaming(outputFile.toPath, rows, schema, emptyError)(outputFailure)

  private def outputFailure(operation: String, path: java.nio.file.Path, err: Throwable): SimError =
    SimError.OutputFailure(operation, path.toString, Option(err.getMessage).filter(_.nonEmpty).getOrElse(err.getClass.getSimpleName))
