package com.boombustgroup.amorfati.montecarlo

import zio.stream.ZStream
import zio.{Scope, ZIO}

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}

private[amorfati] object DelimitedTextFile:

  type OutputFailure[E] = (String, Path, Throwable) => E

  def writeAll[E, A](
      outputFile: Path,
      rows: Iterable[A],
      schema: DelimitedTextSchema[A],
      format: DelimitedTextFormat,
  )(outputFailure: OutputFailure[E]): ZIO[Any, E, Path] =
    writeFold(outputFile, ZStream.fromIterable(rows), schema, format, ())((_, _) => ())(outputFailure).as(outputFile)

  def writeStreaming[E, A](
      outputFile: Path,
      rows: ZStream[Any, E, A],
      schema: DelimitedTextSchema[A],
      format: DelimitedTextFormat,
      emptyError: => E,
  )(outputFailure: OutputFailure[E]): ZIO[Any, E, A] =
    writeFoldValidated(outputFile, rows, schema, format, Option.empty[A])((_, row) => Some(row))(outputFailure):
      case Some(last) => ZIO.succeed(last)
      case None       => ZIO.fail(emptyError)

  def writeFold[E, A, S](
      outputFile: Path,
      rows: ZStream[Any, E, A],
      schema: DelimitedTextSchema[A],
      format: DelimitedTextFormat,
      initial: S,
  )(fold: (S, A) => S)(outputFailure: OutputFailure[E]): ZIO[Any, E, S] =
    writeFoldValidated(outputFile, rows, schema, format, initial)(fold)(outputFailure)(ZIO.succeed(_))

  def writeSplitFold[E, A, L, R, S](
      leftOutputFile: Path,
      rightOutputFile: Path,
      rows: ZStream[Any, E, A],
      leftSchema: DelimitedTextSchema[L],
      rightSchema: DelimitedTextSchema[R],
      format: DelimitedTextFormat,
      initial: S,
  )(route: A => Either[L, R])(fold: (S, A) => S)(outputFailure: OutputFailure[E]): ZIO[Any, E, S] =
    if leftOutputFile == rightOutputFile then
      ZIO.fail(
        outputFailure(
          s"prepare split ${format.label} outputs",
          leftOutputFile,
          IllegalArgumentException("leftOutputFile and rightOutputFile must be different"),
        ),
      )
    else
      val leftTempFile  = leftOutputFile.resolveSibling(s"${leftOutputFile.getFileName.toString}.tmp")
      val rightTempFile = rightOutputFile.resolveSibling(s"${rightOutputFile.getFileName.toString}.tmp")
      val cleanupTemps  =
        deleteIfExists(leftTempFile, format, outputFailure) *>
          deleteIfExists(rightTempFile, format, outputFailure)
      val writeFiles    =
        ZIO.scoped:
          for
            _           <- createParentDirectories(leftOutputFile, format, outputFailure)
            _           <- createParentDirectories(rightOutputFile, format, outputFailure)
            leftWriter  <- openWriter(leftTempFile, format, outputFailure)
            rightWriter <- openWriter(rightTempFile, format, outputFailure)
            _           <- writeLine(leftWriter, leftSchema.header, leftTempFile, format, outputFailure)
            _           <- writeLine(rightWriter, rightSchema.header, rightTempFile, format, outputFailure)
            finalState  <- rows.runFoldZIO(initial): (state, row) =>
              val writeRow = route(row) match
                case Left(left)   => writeLine(leftWriter, leftSchema.render(left), leftTempFile, format, outputFailure)
                case Right(right) => writeLine(rightWriter, rightSchema.render(right), rightTempFile, format, outputFailure)
              writeRow.as(fold(state, row))
          yield finalState

      writeFiles
        .flatMap: finalState =>
          (finalizeFile(leftTempFile, leftOutputFile, format, outputFailure) *>
            finalizeFile(rightTempFile, rightOutputFile, format, outputFailure))
            .as(finalState)
            .onError(_ => cleanupTemps.ignore)
        .onError(_ => cleanupTemps.ignore)

  private def writeFoldValidated[E, A, S, B](
      outputFile: Path,
      rows: ZStream[Any, E, A],
      schema: DelimitedTextSchema[A],
      format: DelimitedTextFormat,
      initial: S,
  )(fold: (S, A) => S)(outputFailure: OutputFailure[E])(validate: S => ZIO[Any, E, B]): ZIO[Any, E, B] =
    val tempFile  = outputFile.resolveSibling(s"${outputFile.getFileName.toString}.tmp")
    val writeFile =
      ZIO.scoped:
        for
          _          <- createParentDirectories(outputFile, format, outputFailure)
          writer     <- openWriter(tempFile, format, outputFailure)
          _          <- writeLine(writer, schema.header, tempFile, format, outputFailure)
          finalState <- rows.runFoldZIO(initial): (state, row) =>
            writeLine(writer, schema.render(row), tempFile, format, outputFailure).as(fold(state, row))
        yield finalState

    writeFile
      .flatMap(state => validate(state).tap(_ => finalizeFile(tempFile, outputFile, format, outputFailure)))
      .onError(_ => deleteIfExists(tempFile, format, outputFailure).ignore)

  private def createParentDirectories[E](
      outputFile: Path,
      format: DelimitedTextFormat,
      outputFailure: OutputFailure[E],
  ): ZIO[Any, E, Unit] =
    Option(outputFile.getParent) match
      case Some(parent) =>
        ZIO
          .attemptBlocking(Files.createDirectories(parent))
          .unit
          .mapError(err => outputFailure(s"prepare ${format.label} parent directory", parent, err))
      case None         => ZIO.unit

  private def openWriter[E](
      outputFile: Path,
      format: DelimitedTextFormat,
      outputFailure: OutputFailure[E],
  ): ZIO[Scope, E, BufferedWriter] =
    ZIO.fromAutoCloseable(
      ZIO
        .attemptBlocking(Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8))
        .mapError(err => outputFailure(s"open ${format.label} writer", outputFile, err)),
    )

  private def writeLine[E](
      writer: BufferedWriter,
      line: String,
      outputFile: Path,
      format: DelimitedTextFormat,
      outputFailure: OutputFailure[E],
  ): ZIO[Any, E, Unit] =
    ZIO
      .attemptBlocking:
        writer.write(line)
        writer.newLine()
      .mapError(err => outputFailure(s"write ${format.label} row", outputFile, err))

  private[montecarlo] def finalizeFile[E](
      tempFile: Path,
      outputFile: Path,
      format: DelimitedTextFormat,
      outputFailure: OutputFailure[E],
  ): ZIO[Any, E, Unit] =
    ZIO
      .attemptBlocking:
        try Files.move(tempFile, outputFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        catch case _: AtomicMoveNotSupportedException => Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING)
      .unit
      .mapError(err => outputFailure(s"finalize ${format.label} file", outputFile, err))

  private def deleteIfExists[E](
      file: Path,
      format: DelimitedTextFormat,
      outputFailure: OutputFailure[E],
  ): ZIO[Any, E, Unit] =
    ZIO
      .attemptBlocking(Files.deleteIfExists(file))
      .unit
      .mapError(err => outputFailure(s"cleanup ${format.label} temp file", file, err))
