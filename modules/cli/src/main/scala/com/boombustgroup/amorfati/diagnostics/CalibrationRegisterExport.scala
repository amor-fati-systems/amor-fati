package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.config.CalibrationRegisterRenderer

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.util.Try

object CalibrationRegisterExport:

  final case class Config(out: Path = Path.of("docs/calibration-register.md"))

  enum CliCommand:
    case Export(config: Config)
    case Help

  def main(args: Array[String]): Unit =
    parseArgs(args.toVector) match
      case Left(err)                        =>
        Console.err.println(err)
        Console.err.println(usage)
        sys.exit(2)
      case Right(CliCommand.Help)           =>
        println(usage)
      case Right(CliCommand.Export(config)) =>
        run(config) match
          case Left(err)   =>
            Console.err.println(err)
            sys.exit(1)
          case Right(path) =>
            println(path.toString)

  def run(config: Config): Either[String, Path] =
    Try {
      val parent = config.out.getParent
      if parent != null then Files.createDirectories(parent)
      Files.writeString(config.out, CalibrationRegisterRenderer.render(), StandardCharsets.UTF_8)
      config.out
    }.toEither.left.map(error => s"Failed to write calibration register: ${error.getMessage}")

  def parseArgs(args: Vector[String]): Either[String, CliCommand] =
    args match
      case Vector()                                  => Right(CliCommand.Export(Config()))
      case Vector("--out", path)                     => Right(CliCommand.Export(Config(Path.of(path))))
      case Vector("--out")                           => Left("Missing path for --out")
      case Vector("--help")                          => Right(CliCommand.Help)
      case Vector(flag, _*) if flag.startsWith("--") =>
        Left(s"Unknown argument: $flag")
      case Vector(value, _*)                         => Left(s"Unexpected positional argument: $value")

  private val usage: String =
    "Usage: sbt 'calibrationRegister [--out docs/calibration-register.md]'"

end CalibrationRegisterExport
