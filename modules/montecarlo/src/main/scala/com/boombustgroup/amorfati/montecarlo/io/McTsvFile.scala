package com.boombustgroup.amorfati.montecarlo.io

import com.boombustgroup.amorfati.tsv.TsvFile
import zio.stream.ZStream
import zio.ZIO

import java.nio.file.Path

private[amorfati] object McTsvFile:

  type OutputFailure[E] = TsvFile.OutputFailure[E]

  def writeAll[E, A](
      outputFile: Path,
      rows: Iterable[A],
      schema: McTsvSchema[A],
  )(outputFailure: OutputFailure[E]): ZIO[Any, E, Path] =
    TsvFile.writeAll(outputFile, rows, schema.asTsvSchema)(outputFailure)

  def writeStreaming[E, A](
      outputFile: Path,
      rows: ZStream[Any, E, A],
      schema: McTsvSchema[A],
      emptyError: => E,
  )(outputFailure: OutputFailure[E]): ZIO[Any, E, A] =
    TsvFile.writeStreaming(outputFile, rows, schema.asTsvSchema, emptyError)(outputFailure)

  def writeFold[E, A, S](
      outputFile: Path,
      rows: ZStream[Any, E, A],
      schema: McTsvSchema[A],
      initial: S,
  )(fold: (S, A) => S)(outputFailure: OutputFailure[E]): ZIO[Any, E, S] =
    TsvFile.writeFold(outputFile, rows, schema.asTsvSchema, initial)(fold)(outputFailure)

  def writeSplitFold[E, A, L, R, S](
      leftOutputFile: Path,
      rightOutputFile: Path,
      rows: ZStream[Any, E, A],
      leftSchema: McTsvSchema[L],
      rightSchema: McTsvSchema[R],
      initial: S,
  )(route: A => Either[L, R])(fold: (S, A) => S)(outputFailure: OutputFailure[E]): ZIO[Any, E, S] =
    TsvFile.writeSplitFold(
      leftOutputFile,
      rightOutputFile,
      rows,
      leftSchema.asTsvSchema,
      rightSchema.asTsvSchema,
      initial,
    )(route)(fold)(outputFailure)

  private[montecarlo] def finalizeFile[E](tempFile: Path, outputFile: Path, outputFailure: OutputFailure[E]): ZIO[Any, E, Unit] =
    TsvFile.finalizeFile(tempFile, outputFile, outputFailure)
