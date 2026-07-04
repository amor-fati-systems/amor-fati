package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.config.ProductionSectorGvaShareBridge

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object ProductionSectorGvaShareBridgeExport:

  private val DefaultOutput =
    Path.of("docs/empirical-source-extracts/production-sector-gva-shares.tsv")
  private val LineSeparator = "\n"

  private[diagnostics] def renderTsv: String =
    ProductionSectorGvaShareBridge.tsvLines.mkString(LineSeparator) + LineSeparator

  def main(args: Array[String]): Unit =
    val output = args.toVector match
      case Vector()               => DefaultOutput
      case Vector("--out", value) => Path.of(value)
      case _                      =>
        throw IllegalArgumentException(
          "Usage: ProductionSectorGvaShareBridgeExport [--out <path>]",
        )

    Option(output.getParent).foreach(Files.createDirectories(_))
    Files.write(output, renderTsv.getBytes(StandardCharsets.UTF_8))
