package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.diagnostics.EmpiricalValidationExport.{Config, SnapshotStatus}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

class EmpiricalValidationExportSpec extends AnyFlatSpec with Matchers:

  "EmpiricalValidationExport" should "parse CLI arguments" in {
    val parsed = EmpiricalValidationExport.parseArgs(
      Vector(
        "--source-manifest",
        "target/source.csv",
        "--mc-dir",
        "target/mc",
        "--out",
        "target/out",
        "--run-id",
        "validation-baseline",
        "--output-prefix",
        "validation-baseline",
        "--duration",
        "24",
        "--seeds",
        "3",
        "--commit",
        "abc123",
        "--parameter-branch",
        "main",
      ),
    )

    parsed.map(config => (config.sourceManifest, config.mcDir, config.out, config.durationMonths, config.seeds, config.commit, config.parameterBranch)) shouldBe
      Right((Path.of("target/source.csv"), Path.of("target/mc"), Path.of("target/out"), 24, 3, "abc123", "main"))
  }

  it should "write a snapshot from timeseries and terminal household targets" in {
    Files.createDirectories(Path.of("target"))
    val root = Files.createTempDirectory(Path.of("target"), "empirical-validation-spec-")
    val mc   = root.resolve("mc")
    val out  = root.resolve("out")
    Files.createDirectories(mc)

    try {
      write(
        mc.resolve("validation-baseline_validation-baseline_2m_seed001.csv"),
        """Month;Inflation;Unemployment
          |1;0.030000;0.050000
          |2;0.040000;0.060000
          |""".stripMargin,
      )
      write(
        mc.resolve("validation-baseline_validation-baseline_2m_seed002.csv"),
        """Month;Inflation;Unemployment
          |1;0.050000;0.070000
          |2;0.050000;0.080000
          |""".stripMargin,
      )
      write(
        mc.resolve("validation-baseline_validation-baseline_2m_hh.csv"),
        """Seed;Gini_Individual;Gini_Wealth;ConsumptionP90;PovertyRate_50pct
          |1;0.300000;0.650000;9000.00;0.100000
          |2;0.320000;0.670000;9200.00;0.120000
          |""".stripMargin,
      )

      val sourceManifest = root.resolve("source-manifest.csv")
      write(
        sourceManifest,
        """target;source_provider;source_url;dataset_code;vintage;accessed_at;license_or_reuse_note;frequency;unit;transformation;model_target;status;empirical_value;tolerance;criterion;notes
          |Inflation;GUS;https://stat.gov.pl;CPI;2026-04;2026-04-30;public citation;monthly;ratio;annualized mean;timeseries:Inflation:terminal;READY;0.05;0.01;absolute distance;fixture source
          |Inequality Gini;GUS;https://stat.gov.pl;HBS;2025;2026-04-30;public citation;annual;ratio;terminal household distribution;terminal_hh:Gini_Individual:mean;MISSING_DATA_BRIDGE;;;;terminal household field exists
          |Missing target;GUS;https://stat.gov.pl;NA;2026;2026-04-30;public citation;monthly;ratio;none;timeseries:MissingColumn:mean;READY;1.0;0.1;absolute distance;
          |""".stripMargin,
      )

      val result = EmpiricalValidationExport
        .run(
          Config(
            sourceManifest = sourceManifest,
            mcDir = mc,
            out = out,
            runId = "validation-baseline",
            outputPrefix = "validation-baseline",
            durationMonths = 2,
            seeds = 2,
            commit = "test-commit",
            parameterBranch = "test",
          ),
        )
        .fold(err => fail(err), identity)

      result.paths.map(_.getFileName.toString).toSet shouldBe Set(
        "source-manifest.csv",
        "model-run-manifest.csv",
        "baseline-validation-snapshot.csv",
        "baseline-validation-snapshot.md",
      )
      result.rows.map(row => row.target -> row.status).toMap shouldBe Map(
        "Inflation"       -> SnapshotStatus.PassBaseline,
        "Inequality Gini" -> SnapshotStatus.MissingDataBridge,
        "Missing target"  -> SnapshotStatus.MissingOutput,
      )
      result.rows.find(_.target == "Inflation").flatMap(_.modelValue).map(_.toDouble) shouldBe Some(0.045)
      result.rows.find(_.target == "Inequality Gini").flatMap(_.modelValue).map(_.toDouble) shouldBe Some(0.31)

      val snapshot = Files.readString(out.resolve("baseline-validation-snapshot.csv"), StandardCharsets.UTF_8)
      snapshot should include("PASS_BASELINE")
      snapshot should include("MISSING_DATA_BRIDGE")
      snapshot should include("MISSING_OUTPUT")
      snapshot should include("terminal_hh:Gini_Individual:mean")
      snapshot should include(
        "Inflation;GUS;https://stat.gov.pl;CPI;2026-04;2026-04-30;monthly;ratio;annualized mean;validation-baseline, 2 seeds, 2m, test-commit;timeseries:Inflation:terminal;0.05;0.05;0.01",
      )
      snapshot should include(
        "Inequality Gini;GUS;https://stat.gov.pl;HBS;2025;2026-04-30;annual;ratio;terminal household distribution;validation-baseline, 2 seeds, 2m, test-commit;terminal_hh:Gini_Individual:mean;;0.31",
      )
    } finally deleteRecursively(root)
  }

  private def write(path: Path, contents: String): Unit =
    Files.writeString(path, contents, StandardCharsets.UTF_8)

  private def deleteRecursively(path: Path): Unit =
    if Files.exists(path) then
      val stream = Files.walk(path)
      try
        val paths = stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse
        paths.foreach(Files.deleteIfExists)
      finally stream.close()

end EmpiricalValidationExportSpec
