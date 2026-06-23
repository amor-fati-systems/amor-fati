package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.config.ProductionSectorGvaShareBridge

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object ProductionSectorGvaShareBridgeExport:

  private val DefaultOutput =
    Path.of("docs/empirical-source-extracts/production-sector-gva-shares.tsv")

  def main(args: Array[String]): Unit =
    val output = args.toVector match
      case Vector()               => DefaultOutput
      case Vector("--out", value) => Path.of(value)
      case _                      =>
        throw IllegalArgumentException(
          "Usage: ProductionSectorGvaShareBridgeExport [--out <path>]",
        )

    Option(output.getParent).foreach(Files.createDirectories(_))
    Files.write(output, ProductionSectorGvaShareBridge.tsvLines.asJava, StandardCharsets.UTF_8)
