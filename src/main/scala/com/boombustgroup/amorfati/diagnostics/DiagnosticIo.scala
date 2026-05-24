package com.boombustgroup.amorfati.diagnostics

import zio.{Runtime, Unsafe, ZIO}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

private[diagnostics] object DiagnosticIo:

  def unsafeRun[A](effect: ZIO[Any, String, A]): Either[String, A] =
    Unsafe.unsafe: unsafe =>
      given Unsafe = unsafe
      Runtime.default.unsafe
        .run:
          effect.foldCause(
            cause => Left(cause.failureOption.getOrElse(s"Diagnostic crashed: ${cause.prettyPrint}")),
            value => Right(value),
          )
        .getOrThrowFiberFailure()

  def writeText(path: Path, contents: String): ZIO[Any, String, Path] =
    ZIO
      .attemptBlocking:
        Option(path.getParent).foreach(Files.createDirectories(_))
        Files.writeString(path, contents, StandardCharsets.UTF_8)
      .as(path)
      .mapError(err => outputFailure("write text file", path, err))

  def outputFailure(operation: String, path: Path, err: Throwable): String =
    s"Output failure during $operation at $path: ${Option(err.getMessage).filter(_.nonEmpty).getOrElse(err.getClass.getSimpleName)}"
