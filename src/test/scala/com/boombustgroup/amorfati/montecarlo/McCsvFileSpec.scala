package com.boombustgroup.amorfati.montecarlo

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.{Runtime, Unsafe, ZIO}
import zio.stream.ZStream

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

class McCsvFileSpec extends AnyFlatSpec with Matchers:

  private val schema = McCsvSchema[Int]("Value", _.toString)

  "McCsvFile" should "write streamed rows through a temp file and return the fold state" in
    withTempDir: dir =>
      val output = dir.resolve("nested").resolve("values.csv")
      val result = run:
        McCsvFile.writeFold(output, ZStream.fromIterable(Vector(1, 2, 3)), schema, Vector.newBuilder[Int])((builder, row) => builder += row)(
          outputFailure,
        )

      result.result() shouldBe Vector(1, 2, 3)
      Files.readAllLines(output, UTF_8).asScala.toVector shouldBe Vector("Value", "1", "2", "3")
      Files.exists(output.resolveSibling("values.csv.tmp")) shouldBe false

  it should "remove the temp file and leave no final file when the row stream fails" in
    withTempDir: dir =>
      val output = dir.resolve("values.csv")
      val rows   = ZStream.succeed(1) ++ ZStream.fail("boom")

      run(McCsvFile.writeFold(output, rows, schema, ())((_, _) => ())(outputFailure).either) shouldBe Left("boom")
      Files.exists(output) shouldBe false
      Files.exists(output.resolveSibling("values.csv.tmp")) shouldBe false

  it should "not finalize a streaming CSV when a non-empty stream is required" in
    withTempDir: dir =>
      val output = dir.resolve("values.csv")

      run(McCsvFile.writeStreaming(output, ZStream.empty, schema, "empty")(outputFailure).either) shouldBe Left("empty")
      Files.exists(output) shouldBe false
      Files.exists(output.resolveSibling("values.csv.tmp")) shouldBe false

  private def run[A](effect: ZIO[Any, String, A]): A =
    Unsafe.unsafe: unsafe =>
      given Unsafe = unsafe
      Runtime.default.unsafe.run(effect).getOrThrowFiberFailure()

  private def outputFailure(operation: String, path: Path, err: Throwable): String =
    s"$operation $path ${err.getClass.getSimpleName}"

  private def withTempDir[A](f: Path => A): A =
    val outputDir = Files.createTempDirectory("mc-csv-file")
    try f(outputDir)
    finally deleteRecursively(outputDir)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      Using.resource(Files.walk(path)) { paths =>
        paths.iterator().asScala.toVector.sortBy(_.getNameCount)(using Ordering.Int.reverse).foreach(Files.deleteIfExists)
      }
