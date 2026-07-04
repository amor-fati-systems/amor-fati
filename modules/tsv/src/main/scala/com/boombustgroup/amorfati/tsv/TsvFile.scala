package com.boombustgroup.amorfati.tsv

import zio.stream.ZStream
import zio.{Scope, ZIO}

import java.io.BufferedWriter
import java.nio.charset.StandardCharsets
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path, StandardCopyOption}

private[amorfati] object TsvFile:

  type OutputFailure[E] = (String, Path, Throwable) => E

  def writeAll[E, A](
      outputFile: Path,
      rows: Iterable[A],
      schema: TsvSchema[A],
  )(outputFailure: OutputFailure[E]): ZIO[Any, E, Path] =
    writeFold(outputFile, ZStream.fromIterable(rows), schema, ())((_, _) => ())(outputFailure).as(outputFile)

  def writeStreaming[E, A](
      outputFile: Path,
      rows: ZStream[Any, E, A],
      schema: TsvSchema[A],
      emptyError: => E,
  )(outputFailure: OutputFailure[E]): ZIO[Any, E, A] =
    writeFoldValidated(outputFile, rows, schema, Option.empty[A])((_, row) => Some(row))(outputFailure):
      case Some(last) => ZIO.succeed(last)
      case None       => ZIO.fail(emptyError)

  def writeFold[E, A, S](
      outputFile: Path,
      rows: ZStream[Any, E, A],
      schema: TsvSchema[A],
      initial: S,
  )(fold: (S, A) => S)(outputFailure: OutputFailure[E]): ZIO[Any, E, S] =
    writeFoldValidated(outputFile, rows, schema, initial)(fold)(outputFailure)(ZIO.succeed(_))

  def writeSplitFold[E, A, L, R, S](
      leftOutputFile: Path,
      rightOutputFile: Path,
      rows: ZStream[Any, E, A],
      leftSchema: TsvSchema[L],
      rightSchema: TsvSchema[R],
      initial: S,
  )(route: A => Either[L, R])(fold: (S, A) => S)(outputFailure: OutputFailure[E]): ZIO[Any, E, S] =
    if leftOutputFile == rightOutputFile then
      ZIO.fail(
        outputFailure(
          s"prepare split ${TsvFormat.Label} outputs",
          leftOutputFile,
          IllegalArgumentException("leftOutputFile and rightOutputFile must be different"),
        ),
      )
    else
      val leftTempFile  = leftOutputFile.resolveSibling(s"${leftOutputFile.getFileName.toString}.tmp")
      val rightTempFile = rightOutputFile.resolveSibling(s"${rightOutputFile.getFileName.toString}.tmp")
      val cleanupTemps  =
        deleteIfExists(leftTempFile, outputFailure) *>
          deleteIfExists(rightTempFile, outputFailure)
      val writeFiles    =
        ZIO.scoped:
          for
            _           <- createParentDirectories(leftOutputFile, outputFailure)
            _           <- createParentDirectories(rightOutputFile, outputFailure)
            leftWriter  <- openWriter(leftTempFile, outputFailure)
            rightWriter <- openWriter(rightTempFile, outputFailure)
            _           <- writeLine(leftWriter, leftSchema.header, leftTempFile, outputFailure)
            _           <- writeLine(rightWriter, rightSchema.header, rightTempFile, outputFailure)
            finalState  <- rows.runFoldZIO(initial): (state, row) =>
              val writeRow = route(row) match
                case Left(left)   => writeLine(leftWriter, leftSchema.render(left), leftTempFile, outputFailure)
                case Right(right) => writeLine(rightWriter, rightSchema.render(right), rightTempFile, outputFailure)
              writeRow.as(fold(state, row))
          yield finalState

      writeFiles
        .flatMap: finalState =>
          (finalizeFile(leftTempFile, leftOutputFile, outputFailure) *>
            finalizeFile(rightTempFile, rightOutputFile, outputFailure))
            .as(finalState)
            .onError(_ => cleanupTemps.ignore)
        .onError(_ => cleanupTemps.ignore)

  private def writeFoldValidated[E, A, S, B](
      outputFile: Path,
      rows: ZStream[Any, E, A],
      schema: TsvSchema[A],
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

  private def createParentDirectories[E](
      outputFile: Path,
      outputFailure: OutputFailure[E],
  ): ZIO[Any, E, Unit] =
    Option(outputFile.getParent) match
      case Some(parent) =>
        ZIO
          .attemptBlocking(Files.createDirectories(parent))
          .unit
          .mapError(err => outputFailure(s"prepare ${TsvFormat.Label} parent directory", parent, err))
      case None         => ZIO.unit

  private def openWriter[E](
      outputFile: Path,
      outputFailure: OutputFailure[E],
  ): ZIO[Scope, E, BufferedWriter] =
    ZIO.fromAutoCloseable(
      ZIO
        .attemptBlocking(Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8))
        .mapError(err => outputFailure(s"open ${TsvFormat.Label} writer", outputFile, err)),
    )

  private def writeLine[E](
      writer: BufferedWriter,
      line: String,
      outputFile: Path,
      outputFailure: OutputFailure[E],
  ): ZIO[Any, E, Unit] =
    ZIO
      .attemptBlocking:
        writer.write(line)
        writer.newLine()
      .mapError(err => outputFailure(s"write ${TsvFormat.Label} row", outputFile, err))

  private[amorfati] def finalizeFile[E](
      tempFile: Path,
      outputFile: Path,
      outputFailure: OutputFailure[E],
  ): ZIO[Any, E, Unit] =
    ZIO
      .attemptBlocking:
        try Files.move(tempFile, outputFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        catch case _: AtomicMoveNotSupportedException => Files.move(tempFile, outputFile, StandardCopyOption.REPLACE_EXISTING)
      .unit
      .mapError(err => outputFailure(s"finalize ${TsvFormat.Label} file", outputFile, err))

  private def deleteIfExists[E](
      file: Path,
      outputFailure: OutputFailure[E],
  ): ZIO[Any, E, Unit] =
    ZIO
      .attemptBlocking(Files.deleteIfExists(file))
      .unit
      .mapError(err => outputFailure(s"cleanup ${TsvFormat.Label} temp file", file, err))
