package com.boombustgroup.amorfati

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.montecarlo.{McFirmDecisionTraceSelection, McFirmSnapshotSchedule, McRunConfig, McRunner}
import com.boombustgroup.amorfati.util.BuildInfo
import zio.*

// $COVERAGE-OFF$ entry point only
object Main extends ZIOAppDefault:
  override def run: ZIO[ZIOAppArgs, Any, Any] =
    for
      args           <- getArgs
      rc             <- parseArgs(args)
      given SimParams = SimParams.defaults
      _              <- Console.printLine(prepareBanner(rc))
      _              <- McRunner
        .runZIO(rc)
        .catchAll: err =>
          Console.printLineError(err.toString) *> ZIO.fail(err)
    yield ()

  private def prepareBanner(rc: McRunConfig)(using p: SimParams): String =
    val commit   = BuildInfo.gitCommit
    val firms    = String.format(java.util.Locale.US, "%,d", p.pop.firmsCount: Integer)
    val asciiArt = com.github.lalyos.jfiglet.FigletFont.convertOneLine("AMOR-FATI").stripTrailing()
    s"""
       |$asciiArt
       |
       |  SFC-ABM  |  commit: $commit
       |
       |  N=${rc.nSeeds} seeds  |  PLN (NBP)  |  HH=${p.household.count}  |  BANK=multi (6+Others)
       |  $firms firms x 6 sectors x Poland 2026-04-30 x ${p.topology.label} x ${rc.runDurationMonths}m
       |
       |  Apache 2.0 | Copyright 2026 BoomBustGroup | www.boombustgroup.com
       |""".stripMargin

  private[amorfati] def parseArgs(args: Chunk[String]): ZIO[Any, IllegalArgumentException, McRunConfig] =
    val usage =
      "Usage: amor-fati <nSeeds> <prefix> [--duration <months>] [--run-id <id>] [--firm-snapshots <terminal|every:N|months:M1,M2,...>] [--firm-decision-trace <ids:I1,I2,...|first:N|all|none>]"

    final case class ParsedFlags(
        runDuration: Int = McRunConfig.DefaultRunDuration,
        runId: Option[String] = None,
        firmSnapshots: McFirmSnapshotSchedule = McFirmSnapshotSchedule.Disabled,
        firmDecisionTrace: McFirmDecisionTraceSelection = McFirmDecisionTraceSelection.Disabled,
    )

    def knownFlag(flag: String): Boolean =
      flag == "--duration" || flag == "--run-id" || flag == "--firm-snapshots" || flag == "--firm-decision-trace"

    def missingValue(flag: String): Left[String, ParsedFlags] =
      Left(s"Missing value for $flag")

    def parseInt(value: String, label: String): Either[String, Int] =
      scala.util.Try(value.toInt).toOption.toRight(s"$label must be an integer")

    def parseFirmSnapshotSchedule(value: String): Either[String, McFirmSnapshotSchedule] =
      val normalized = value.trim.toLowerCase(java.util.Locale.ROOT)
      if normalized == "none" || normalized == "disabled" then Right(McFirmSnapshotSchedule.Disabled)
      else if normalized == "terminal" || normalized == "terminal-only" then Right(McFirmSnapshotSchedule.TerminalOnly)
      else if normalized.startsWith("every:") then
        parseInt(normalized.stripPrefix("every:"), "--firm-snapshots every:N").flatMap: months =>
          scala.util.Try(McFirmSnapshotSchedule.EveryNMonths(months)).toEither.left.map(_.getMessage)
      else if normalized.startsWith("months:") then
        val rawMonths = normalized.stripPrefix("months:").split(',').toVector.filter(_.nonEmpty)
        val parsed    = rawMonths.foldLeft[Either[String, Set[Int]]](Right(Set.empty)): (acc, raw) =>
          for
            months <- acc
            month  <- parseInt(raw, "--firm-snapshots months:M1,M2,...")
          yield months + month
        parsed.flatMap: months =>
          scala.util.Try(McFirmSnapshotSchedule.ExplicitMonths(months)).toEither.left.map(_.getMessage)
      else Left("--firm-snapshots must be terminal, every:N, months:M1,M2,..., or none")

    def parseFirmDecisionTraceSelection(value: String): Either[String, McFirmDecisionTraceSelection] =
      val normalized = value.trim.toLowerCase(java.util.Locale.ROOT)
      if normalized == "none" || normalized == "disabled" then Right(McFirmDecisionTraceSelection.Disabled)
      else if normalized == "all" then Right(McFirmDecisionTraceSelection.All)
      else if normalized.startsWith("first:") then
        parseInt(normalized.stripPrefix("first:"), "--firm-decision-trace first:N").flatMap: count =>
          scala.util.Try(McFirmDecisionTraceSelection.FirstN(count)).toEither.left.map(_.getMessage)
      else if normalized.startsWith("ids:") then
        val rawIds = normalized.stripPrefix("ids:").split(',').toVector.filter(_.nonEmpty)
        val parsed = rawIds.foldLeft[Either[String, Set[Int]]](Right(Set.empty)): (acc, raw) =>
          for
            ids <- acc
            id  <- parseInt(raw, "--firm-decision-trace ids:I1,I2,...")
          yield ids + id
        parsed.flatMap: ids =>
          scala.util.Try(McFirmDecisionTraceSelection.ExplicitFirmIds(ids)).toEither.left.map(_.getMessage)
      else Left("--firm-decision-trace must be ids:I1,I2,..., first:N, all, or none")

    def parseFlags(rest: Seq[String], flags: ParsedFlags): Either[String, ParsedFlags] =
      rest match
        case Seq()                                  => Right(flags)
        case Seq(flag, tail*) if knownFlag(flag)    =>
          tail match
            case Seq()                                                => missingValue(flag)
            case Seq(value, _*) if value.startsWith("--")             => missingValue(flag)
            case Seq(value, next*) if flag == "--duration"            =>
              parseInt(value, flag).flatMap(duration => parseFlags(next, flags.copy(runDuration = duration)))
            case Seq(value, next*) if flag == "--run-id"              =>
              parseFlags(next, flags.copy(runId = Some(value)))
            case Seq(value, next*) if flag == "--firm-snapshots"      =>
              parseFirmSnapshotSchedule(value).flatMap(schedule => parseFlags(next, flags.copy(firmSnapshots = schedule)))
            case Seq(value, next*) if flag == "--firm-decision-trace" =>
              parseFirmDecisionTraceSelection(value).flatMap(selection => parseFlags(next, flags.copy(firmDecisionTrace = selection)))
            case Seq(_, _*)                                           => Left(s"Unknown argument: $flag")
        case Seq(flag, _*) if flag.startsWith("--") => Left(s"Unknown argument: $flag")
        case Seq(value, _*)                         => Left(s"Unexpected positional argument: $value")

    ZIO
      .fromEither(for
        _           <- Either.cond(args.length >= 2, (), usage)
        nSeedsValue <- args.headOption.toRight(usage)
        nSeeds      <- parseInt(nSeedsValue, "<nSeeds>")
        prefix      <- args.lift(1).toRight(usage)
        flags       <- parseFlags(args.drop(2), ParsedFlags())
        mcRunConfig <- scala.util
          .Try:
            flags.runId match
              case Some(id) => McRunConfig(nSeeds, prefix, flags.runDuration, id, flags.firmSnapshots, flags.firmDecisionTrace)
              case None     =>
                McRunConfig(
                  nSeeds,
                  prefix,
                  flags.runDuration,
                  firmSnapshotSchedule = flags.firmSnapshots,
                  firmDecisionTraceSelection = flags.firmDecisionTrace,
                )
          .toEither
          .left
          .map(_.getMessage)
      yield mcRunConfig)
      .mapError(err => new IllegalArgumentException(if err == usage then usage else s"$err\n$usage"))
// $COVERAGE-ON$
