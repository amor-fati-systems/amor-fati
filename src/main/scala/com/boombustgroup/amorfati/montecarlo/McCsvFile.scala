package com.boombustgroup.amorfati.montecarlo

import zio.stream.ZStream
import zio.{Scope, ZIO}

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption}

private[amorfati] object McCsvFile:

  type OutputFailure[E] = (String, Path, Throwable) => E

  def writeAll[E, A](
      outputFile: Path,
      rows: Iterable[A],
      schema: McCsvSchema[A],
  )(outputFailure: OutputFailure[E]): ZIO[Any, E, Path] =
    writeFold(outputFile, ZStream.fromIterable(rows), schema, ())((_, _) => ())(outputFailure).as(outputFile)

  def writeStreaming[E, A](
      outputFile: Path,
      rows: ZStream[Any, E, A],
      schema: McCsvSchema[A],
      emptyError: => E,
  )(outputFailure: OutputFailure[E]): ZIO[Any, E, A] =
    writeFoldValidated(outputFile, rows, schema, Option.empty[A])((_, row) => Some(row))(outputFailure):
      case Some(last) => ZIO.succeed(last)
      case None       => ZIO.fail(emptyError)

  def writeFold[E, A, S](
      outputFile: Path,
      rows: ZStream[Any, E, A],
      schema: McCsvSchema[A],
      initial: S,
  )(fold: (S, A) => S)(outputFailure: OutputFailure[E]): ZIO[Any, E, S] =
    writeFoldValidated(outputFile, rows, schema, initial)(fold)(outputFailure)(ZIO.succeed(_))

  private def writeFoldValidated[E, A, S, B](
      outputFile: Path,
      rows: ZStream[Any, E, A],
      schema: McCsvSchema[A],
      initial: S,
  )(fold: (S, A) => S)(outputFailure: OutputFailure[E])(validate: S => ZIO[Any, E, B]): ZIO[Any, E, B] =
    val tempFile  = outputFile.resolveSibling(s"${outputFile.getFileName.toString}.tmp")
    val writeFile =
      ZIO.scoped:
        for
          _          <- createParentDirectories(outputFile, outputFailure)
          writer     <- openWriter(tempFile, outputFailure)
          _          <- writeLine(writer, schema.header, tempFile, outputFailure)
          finalState <- rows.runFoldZIO(initial): (state, row) =>
            writeLine(writer, schema.render(row), tempFile, outputFailure).as(fold(state, row))
        yield finalState

    writeFile
      .flatMap(state => validate(state).tap(_ => finalizeFile(tempFile, outputFile, outputFailure)))
      .onError(_ => deleteIfExists(tempFile, outputFailure).ignore)

  private def createParentDirectories[E](outputFile: Path, outputFailure: OutputFailure[E]): ZIO[Any, E, Unit] =
    Option(outputFile.getParent) match
      case Some(parent) =>
        ZIO
          .attemptBlocking(Files.createDirectories(parent))
          .unit
          .mapError(err => outputFailure("prepare CSV parent directory", parent, err))
      case None         => ZIO.unit

  private def openWriter[E](outputFile: Path, outputFailure: OutputFailure[E]): ZIO[Scope, E, BufferedWriter] =
    ZIO.fromAutoCloseable(
      ZIO
        .attemptBlocking(Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8))
        .mapError(err => outputFailure("open CSV writer", outputFile, err)),
    )

  private def writeLine[E](writer: BufferedWriter, line: String, outputFile: Path, outputFailure: OutputFailure[E]): ZIO[Any, E, Unit] =
    ZIO
      .attemptBlocking:
        writer.write(line)
        writer.newLine()
      .mapError(err => outputFailure("write CSV row", outputFile, err))

  private def finalizeFile[E](tempFile: Path, outputFile: Path, outputFailure: OutputFailure[E]): ZIO[Any, E, Unit] =
    ZIO
      .attemptBlocking(Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING))
      .unit
      .mapError(err => outputFailure("finalize CSV file", outputFile, err))

  private def deleteIfExists[E](file: Path, outputFailure: OutputFailure[E]): ZIO[Any, E, Unit] =
    ZIO
      .attemptBlocking(Files.deleteIfExists(file))
      .unit
      .mapError(err => outputFailure("cleanup CSV temp file", file, err))
