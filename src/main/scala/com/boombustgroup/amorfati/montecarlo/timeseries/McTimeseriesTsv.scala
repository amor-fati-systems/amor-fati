package com.boombustgroup.amorfati.montecarlo.timeseries

import com.boombustgroup.amorfati.montecarlo.core.SimError
import com.boombustgroup.amorfati.montecarlo.io.{McTsvFile, McTsvSchema}
import zio.stream.ZStream
import zio.ZIO

import java.io.File

private[montecarlo] object McTimeseriesTsv:

  def writeStreaming[A](
      outputFile: File,
      rows: ZStream[Any, SimError, A],
      schema: McTsvSchema[A],
      emptyError: => SimError,
  ): ZIO[Any, SimError, A] =
    McTsvFile.writeStreaming(outputFile.toPath, rows, schema, emptyError)(outputFailure)

  private def outputFailure(operation: String, path: java.nio.file.Path, err: Throwable): SimError =
    SimError.OutputFailure(operation, path.toString, Option(err.getMessage).filter(_.nonEmpty).getOrElse(err.getClass.getSimpleName))
