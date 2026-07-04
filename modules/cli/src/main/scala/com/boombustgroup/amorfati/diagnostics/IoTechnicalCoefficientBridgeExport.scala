package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.config.IoTechnicalCoefficientBridge

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object IoTechnicalCoefficientBridgeExport:

  private val DefaultOutput =
    Path.of("docs/empirical-source-extracts/io-technical-coefficients.tsv")
  private val LineSeparator = "\n"

  private[diagnostics] def renderTsv: String =
    IoTechnicalCoefficientBridge.tsvLines.mkString(LineSeparator) + LineSeparator

  def main(args: Array[String]): Unit =
    val output = args.toVector match
      case Vector()               => DefaultOutput
      case Vector("--out", value) => Path.of(value)
      case _                      =>
        throw IllegalArgumentException(
          "Usage: IoTechnicalCoefficientBridgeExport [--out <path>]",
        )

    Option(output.getParent).foreach(Files.createDirectories(_))
    Files.write(output, renderTsv.getBytes(StandardCharsets.UTF_8))
