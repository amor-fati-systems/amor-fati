package com.boombustgroup.amorfati.montecarlo

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Try

private[amorfati] object DelimitedTextRows:

  final case class Row(values: Map[String, String]):
    def required(column: String): Either[String, String] =
      optional(column).toRight(s"Missing required column: $column")

    def optional(column: String): Option[String] =
      values.get(column).map(_.trim).filter(_.nonEmpty)

  def readRows(
      path: Path,
      format: DelimitedTextFormat,
      requiredColumns: Vector[String] = Vector.empty,
  ): Either[String, Vector[Row]] =
    Try(Files.readAllLines(path, StandardCharsets.UTF_8).asScala.toVector).toEither.left
      .map(err => s"Failed to read $path: ${err.getMessage}")
      .flatMap: lines =>
        val dataLines = lines.zipWithIndex.filter((line, _) => line.trim.nonEmpty && !line.trim.startsWith("#"))
        dataLines.headOption match
          case None                     => Left(s"$path is empty")
          case Some((headerLine, hidx)) =>
            for
              header <- parseLine(headerLine, format).left.map(err => s"$path line ${hidx + 1}: $err")
              _      <- validateHeader(path, header, requiredColumns)
              rows   <- sequence(
                dataLines.tail
                  .map((line, idx) =>
                    parseLine(line, format).left
                      .map(err => s"$path line ${idx + 1}: $err")
                      .flatMap(cells => rowFromCells(path, idx + 1, header, cells)),
                  )
                  .toVector,
              )
            yield rows

  private def validateHeader(path: Path, header: Vector[String], requiredColumns: Vector[String]): Either[String, Unit] =
    val missing = requiredColumns.filterNot(header.contains)
    Either.cond(missing.isEmpty, (), s"$path is missing required columns: ${missing.mkString(", ")}")

  private def rowFromCells(path: Path, lineNumber: Int, header: Vector[String], cells: Vector[String]): Either[String, Row] =
    Either.cond(
      cells.length == header.length,
      Row(header.zip(cells).toMap),
      s"$path line $lineNumber has ${cells.length} cells, expected ${header.length}",
    )

  private def parseLine(line: String, format: DelimitedTextFormat): Either[String, Vector[String]] =
    val cells  = Vector.newBuilder[String]
    val cell   = new StringBuilder
    var quoted = false
    var i      = 0
    while i < line.length do
      val ch = line.charAt(i)
      if quoted then
        if ch == '"' then
          if i + 1 < line.length && line.charAt(i + 1) == '"' then
            cell.append('"')
            i += 1
          else quoted = false
        else cell.append(ch)
      else if ch == format.delimiter then
        cells += cell.toString
        cell.clear()
      else if ch == '"' && cell.isEmpty then quoted = true
      else cell.append(ch)
      i += 1
    if quoted then Left("Unterminated quoted cell")
    else
      cells += cell.toString
      Right(cells.result().map(_.trim))

  private def sequence[A](values: Iterable[Either[String, A]]): Either[String, Vector[A]] =
    values.foldLeft[Either[String, Vector[A]]](Right(Vector.empty)): (acc, next) =>
      for
        rows <- acc
        row  <- next
      yield rows :+ row
