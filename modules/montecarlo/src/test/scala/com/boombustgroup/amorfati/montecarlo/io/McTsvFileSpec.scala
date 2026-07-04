package com.boombustgroup.amorfati.montecarlo.io

import com.boombustgroup.amorfati.tsv.{TsvFile, TsvRows, TsvSchema}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.{Runtime, Unsafe, ZIO}
import zio.stream.ZStream

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

class McTsvFileSpec extends AnyFlatSpec with Matchers:

  private val schema = McTsvSchema[Int]("Value", _.toString)

  "McTsvFile" should "write streamed rows through a temp file and return the fold state" in
    withTempDir: dir =>
      val output = dir.resolve("nested").resolve("values.tsv")
      val result = run:
        McTsvFile.writeFold(output, ZStream.fromIterable(Vector(1, 2, 3)), schema, Vector.newBuilder[Int])((builder, row) => builder += row)(
          outputFailure,
        )

      result.result() shouldBe Vector(1, 2, 3)
      Files.readAllLines(output, UTF_8).asScala.toVector shouldBe Vector("Value", "1", "2", "3")
      Files.exists(output.resolveSibling("values.tsv.tmp")) shouldBe false

  it should "remove the temp file and leave no final file when the row stream fails" in
    withTempDir: dir =>
      val output = dir.resolve("values.tsv")
      val rows   = ZStream.succeed(1) ++ ZStream.fail("boom")

      run(McTsvFile.writeFold(output, rows, schema, ())((_, _) => ())(outputFailure).either) shouldBe Left("boom")
      Files.exists(output) shouldBe false
      Files.exists(output.resolveSibling("values.tsv.tmp")) shouldBe false

  it should "split streamed rows across two TSV files while returning one fold state" in
    withTempDir: dir =>
      val leftOutput  = dir.resolve("left.tsv")
      val rightOutput = dir.resolve("nested").resolve("right.tsv")
      val rows        = ZStream.fromIterable(Vector(1, 2, 3, 4))
      val result      = run:
        McTsvFile.writeSplitFold(leftOutput, rightOutput, rows, schema, schema, Vector.newBuilder[Int]) { row =>
          if row % 2 == 0 then Right(row) else Left(row)
        }((builder, row) => builder += row)(outputFailure)

      result.result() shouldBe Vector(1, 2, 3, 4)
      Files.readAllLines(leftOutput, UTF_8).asScala.toVector shouldBe Vector("Value", "1", "3")
      Files.readAllLines(rightOutput, UTF_8).asScala.toVector shouldBe Vector("Value", "2", "4")
      Files.exists(leftOutput.resolveSibling("left.tsv.tmp")) shouldBe false
      Files.exists(rightOutput.resolveSibling("right.tsv.tmp")) shouldBe false

  it should "not delete finalized split outputs when the second finalize fails" in
    withTempDir: dir =>
      val leftOutput  = dir.resolve("left.tsv")
      val rightOutput = dir.resolve("right.tsv")
      Files.writeString(leftOutput, "preexisting\n", UTF_8)
      Files.createDirectory(rightOutput)
      Files.writeString(rightOutput.resolve("occupied"), "block replacement\n", UTF_8)

      val result = run:
        McTsvFile
          .writeSplitFold(leftOutput, rightOutput, ZStream.fromIterable(Vector(1, 2)), schema, schema, ()) { row =>
            if row == 1 then Left(row) else Right(row)
          }((_, _) => ())(outputFailure)
          .either

      result.left.getOrElse(fail("Expected split TSV finalize failure")) should include("finalize TSV file")
      Files.readAllLines(leftOutput, UTF_8).asScala.toVector shouldBe Vector("Value", "1")
      Files.isDirectory(rightOutput) shouldBe true
      Files.exists(leftOutput.resolveSibling("left.tsv.tmp")) shouldBe false
      Files.exists(rightOutput.resolveSibling("right.tsv.tmp")) shouldBe false

  it should "reject split TSV writes to the same output path" in
    withTempDir: dir =>
      val output = dir.resolve("same.tsv")
      val result = run:
        McTsvFile
          .writeSplitFold(output, output, ZStream.fromIterable(Vector(1)), schema, schema, ()) { row =>
            Left(row)
          }((_, _) => ())(outputFailure)
          .either

      result.left.getOrElse(fail("Expected split TSV path collision to fail")) should include("prepare split TSV outputs")
      Files.exists(output) shouldBe false

  it should "not finalize a streaming TSV when a non-empty stream is required" in
    withTempDir: dir =>
      val output = dir.resolve("values.tsv")

      run(McTsvFile.writeStreaming(output, ZStream.empty, schema, "empty")(outputFailure).either) shouldBe Left("empty")
      Files.exists(output) shouldBe false
      Files.exists(output.resolveSibling("values.tsv.tmp")) shouldBe false

  it should "write and read TSV rows through the shared TSV contract" in
    withTempDir: dir =>
      val output    = dir.resolve("values.tsv")
      val tsvSchema =
        TsvSchema.fromCells[(String, String)](Vector("Name", "Note")): (name, note) =>
          Vector(name, note)

      run:
        TsvFile.writeAll(
          output,
          Vector(("plain", "contains\ttab"), ("has \"quote\"", "value")),
          tsvSchema,
        )(outputFailure)

      Files.readAllLines(output, UTF_8).asScala.toVector shouldBe Vector(
        "Name\tNote",
        "plain\t\"contains\ttab\"",
        "\"has \"\"quote\"\"\"\tvalue",
      )

      val parsed = TsvRows
        .readRows(output, Vector("Name", "Note"))
        .fold(err => fail(err), identity)
      parsed.map(row => row.required("Name").toOption.get -> row.required("Note").toOption.get) shouldBe Vector(
        "plain"         -> "contains\ttab",
        "has \"quote\"" -> "value",
      )

  it should "read quoted TSV fields that span physical lines" in
    withTempDir: dir =>
      val output = dir.resolve("values.tsv")
      Files.writeString(
        output,
        "Name\tNote\nmulti\t\"line one\nline two\"\nplain\tvalue\n",
        UTF_8,
      )

      val parsed = TsvRows.readRows(output, Vector("Name", "Note")).fold(err => fail(err), identity)
      parsed.map(row => row.required("Name").toOption.get -> row.required("Note").toOption.get) shouldBe Vector(
        "multi" -> "line one\nline two",
        "plain" -> "value",
      )

  it should "reject duplicate TSV headers" in
    withTempDir: dir =>
      val output = dir.resolve("duplicate.tsv")
      Files.writeString(output, "Name\tName\nleft\tright\n", UTF_8)

      val error = TsvRows.readRows(output).left.getOrElse(fail("Expected duplicate header failure"))
      error should include("duplicate columns: Name")

  it should "fail fast when rendered row width differs from the schema header" in {
    val badSchema =
      TsvSchema.fromCells[Int](Vector("One", "Two")): value =>
        Vector(value.toString)

    val error = intercept[IllegalArgumentException](badSchema.render(1))
    error.getMessage should include("TSV row has 1 cells, expected 2")
  }

  private def run[A](effect: ZIO[Any, String, A]): A =
    Unsafe.unsafe: unsafe =>
      given Unsafe = unsafe
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()

  private def outputFailure(operation: String, path: Path, err: Throwable): String =
    s"$operation $path ${err.getClass.getSimpleName}"

  private def withTempDir[A](f: Path => A): A =
    val outputDir = Files.createTempDirectory("mc-tsv-file")
    try f(outputDir)
    finally deleteRecursively(outputDir)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      Using.resource(Files.walk(path)) { paths =>
        paths.iterator().asScala.toVector.sortBy(_.getNameCount)(using Ordering.Int.reverse).foreach(Files.deleteIfExists)
      }
