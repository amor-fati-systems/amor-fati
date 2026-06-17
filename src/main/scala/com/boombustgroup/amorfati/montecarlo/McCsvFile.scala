package com.boombustgroup.amorfati.montecarlo

import zio.stream.ZStream
import zio.ZIO

import java.nio.file.Path

private[amorfati] object McCsvFile:

  type OutputFailure[E] = DelimitedTextFile.OutputFailure[E]

  def writeAll[E, A](
      outputFile: Path,
      rows: Iterable[A],
      schema: McCsvSchema[A],
  )(outputFailure: OutputFailure[E]): ZIO[Any, E, Path] =
    DelimitedTextFile.writeAll(outputFile, rows, schema.asDelimitedTextSchema, DelimitedTextFormat.SemicolonCsv)(outputFailure)

  def writeStreaming[E, A](
      outputFile: Path,
      rows: ZStream[Any, E, A],
      schema: McCsvSchema[A],
      emptyError: => E,
  )(outputFailure: OutputFailure[E]): ZIO[Any, E, A] =
    DelimitedTextFile.writeStreaming(outputFile, rows, schema.asDelimitedTextSchema, DelimitedTextFormat.SemicolonCsv, emptyError)(outputFailure)

  def writeFold[E, A, S](
      outputFile: Path,
      rows: ZStream[Any, E, A],
      schema: McCsvSchema[A],
      initial: S,
  )(fold: (S, A) => S)(outputFailure: OutputFailure[E]): ZIO[Any, E, S] =
    DelimitedTextFile.writeFold(outputFile, rows, schema.asDelimitedTextSchema, DelimitedTextFormat.SemicolonCsv, initial)(fold)(outputFailure)

  def writeSplitFold[E, A, L, R, S](
      leftOutputFile: Path,
      rightOutputFile: Path,
      rows: ZStream[Any, E, A],
      leftSchema: McCsvSchema[L],
      rightSchema: McCsvSchema[R],
      initial: S,
  )(route: A => Either[L, R])(fold: (S, A) => S)(outputFailure: OutputFailure[E]): ZIO[Any, E, S] =
    DelimitedTextFile.writeSplitFold(
      leftOutputFile,
      rightOutputFile,
      rows,
      leftSchema.asDelimitedTextSchema,
      rightSchema.asDelimitedTextSchema,
      DelimitedTextFormat.SemicolonCsv,
      initial,
    )(route)(fold)(outputFailure)

  private[montecarlo] def finalizeFile[E](tempFile: Path, outputFile: Path, outputFailure: OutputFailure[E]): ZIO[Any, E, Unit] =
    DelimitedTextFile.finalizeFile(tempFile, outputFile, DelimitedTextFormat.SemicolonCsv, outputFailure)
