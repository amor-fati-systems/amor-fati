package com.boombustgroup.amorfati.montecarlo.io

import zio.stream.ZStream
import zio.ZIO

import java.nio.file.Path

private[amorfati] object McTsvFile:

  type OutputFailure[E] = DelimitedTextFile.OutputFailure[E]

  def writeAll[E, A](
      outputFile: Path,
      rows: Iterable[A],
      schema: McTsvSchema[A],
  )(outputFailure: OutputFailure[E]): ZIO[Any, E, Path] =
    DelimitedTextFile.writeAll(outputFile, rows, schema.asDelimitedTextSchema, DelimitedTextFormat.Tsv)(outputFailure)

  def writeStreaming[E, A](
      outputFile: Path,
      rows: ZStream[Any, E, A],
      schema: McTsvSchema[A],
      emptyError: => E,
  )(outputFailure: OutputFailure[E]): ZIO[Any, E, A] =
    DelimitedTextFile.writeStreaming(outputFile, rows, schema.asDelimitedTextSchema, DelimitedTextFormat.Tsv, emptyError)(outputFailure)

  def writeFold[E, A, S](
      outputFile: Path,
      rows: ZStream[Any, E, A],
      schema: McTsvSchema[A],
      initial: S,
  )(fold: (S, A) => S)(outputFailure: OutputFailure[E]): ZIO[Any, E, S] =
    DelimitedTextFile.writeFold(outputFile, rows, schema.asDelimitedTextSchema, DelimitedTextFormat.Tsv, initial)(fold)(outputFailure)

  def writeSplitFold[E, A, L, R, S](
      leftOutputFile: Path,
      rightOutputFile: Path,
      rows: ZStream[Any, E, A],
      leftSchema: McTsvSchema[L],
      rightSchema: McTsvSchema[R],
      initial: S,
  )(route: A => Either[L, R])(fold: (S, A) => S)(outputFailure: OutputFailure[E]): ZIO[Any, E, S] =
    DelimitedTextFile.writeSplitFold(
      leftOutputFile,
      rightOutputFile,
      rows,
      leftSchema.asDelimitedTextSchema,
      rightSchema.asDelimitedTextSchema,
      DelimitedTextFormat.Tsv,
      initial,
    )(route)(fold)(outputFailure)

  private[montecarlo] def finalizeFile[E](tempFile: Path, outputFile: Path, outputFailure: OutputFailure[E]): ZIO[Any, E, Unit] =
    DelimitedTextFile.finalizeFile(tempFile, outputFile, DelimitedTextFormat.Tsv, outputFailure)
