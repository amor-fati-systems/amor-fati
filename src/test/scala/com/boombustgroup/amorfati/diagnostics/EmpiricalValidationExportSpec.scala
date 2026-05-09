package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.diagnostics.EmpiricalValidationExport.{CliCommand, Config, SnapshotStatus}
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

    val config = parsed match
      case Right(CliCommand.Export(config)) => config
      case other                            => fail(s"Expected export command, got $other")

    (config.sourceManifest, config.mcDir, config.out, config.durationMonths, config.seeds, config.commit, config.parameterBranch) shouldBe
      (Path.of("target/source.csv"), Path.of("target/mc"), Path.of("target/out"), 24, 3, "abc123", "main")
  }

  it should "parse help as a successful command" in {
    EmpiricalValidationExport.parseArgs(Vector("--help")) shouldBe Right(CliCommand.Help)
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
        "Inflation;GUS;https://stat.gov.pl;CPI;2026-04;2026-04-30;monthly;ratio;annualized mean;test@test-commit, 2 seeds, 2m, run-id validation-baseline;timeseries:Inflation:terminal;0.05;0.045;0.01",
      )
      snapshot should include(
        "Inequality Gini;GUS;https://stat.gov.pl;HBS;2025;2026-04-30;annual;ratio;terminal household distribution;test@test-commit, 2 seeds, 2m, run-id validation-baseline;terminal_hh:Gini_Individual:mean;;0.31",
      )
    } finally deleteRecursively(root)
  }

  it should "fail clearly on malformed ready rows" in {
    Files.createDirectories(Path.of("target"))
    val root = Files.createTempDirectory(Path.of("target"), "empirical-validation-bad-ready-")

    try {
      val sourceManifest = root.resolve("source-manifest.csv")
      write(
        sourceManifest,
        """target;source_provider;source_url;dataset_code;vintage;accessed_at;license_or_reuse_note;frequency;unit;transformation;model_target;status;empirical_value;tolerance;criterion;notes
          |Inflation;GUS;https://stat.gov.pl;CPI;2026-04;2026-04-30;public citation;monthly;ratio;annualized mean;timeseries:Inflation:terminal;READY;;;;
          |""".stripMargin,
      )

      val error = EmpiricalValidationExport
        .run(
          Config(
            sourceManifest = sourceManifest,
            mcDir = root.resolve("missing-mc"),
            out = root.resolve("out"),
            runId = "validation-baseline",
            outputPrefix = "validation-baseline",
            durationMonths = 2,
            seeds = 1,
            commit = "test-commit",
            parameterBranch = "test",
          ),
        )
        .left
        .getOrElse(fail("Expected malformed READY row to fail"))

      error should include("row 2")
      error should include("READY row must have numeric empirical_value")
      error should include("READY row must have numeric tolerance")
      error should include("READY row must have criterion")
    } finally deleteRecursively(root)
  }

  it should "preserve documented partial rows with model values" in {
    Files.createDirectories(Path.of("target"))
    val root = Files.createTempDirectory(Path.of("target"), "empirical-validation-partial-")
    val mc   = root.resolve("mc")
    val out  = root.resolve("out")
    Files.createDirectories(mc)

    try {
      write(
        mc.resolve("validation-baseline_validation-baseline_1m_seed001.csv"),
        """Month;Inflation
          |1;0.030000
          |""".stripMargin,
      )

      val sourceManifest = root.resolve("source-manifest.csv")
      write(
        sourceManifest,
        """target;source_provider;source_url;dataset_code;vintage;accessed_at;license_or_reuse_note;frequency;unit;transformation;model_target;status;empirical_value;tolerance;criterion;notes
          |Inflation bridge;GUS;https://stat.gov.pl;CPI;2026-04;2026-04-30;public citation;monthly;ratio;annualized mean;timeseries:Inflation:terminal;PARTIAL;;;;documented bridge remains open
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
            durationMonths = 1,
            seeds = 1,
            commit = "test-commit",
            parameterBranch = "test",
          ),
        )
        .fold(err => fail(err), identity)

      val row = result.rows.find(_.target == "Inflation bridge").getOrElse(fail("Expected partial row"))
      row.status shouldBe SnapshotStatus.Partial
      row.modelValue.map(_.toDouble) shouldBe Some(0.03)
      row.notes should include("documented bridge remains open")
    } finally deleteRecursively(root)
  }

  it should "fail when an explicit seed count has no matching seed files" in {
    Files.createDirectories(Path.of("target"))
    val root = Files.createTempDirectory(Path.of("target"), "empirical-validation-missing-seeds-")
    val mc   = root.resolve("mc")
    Files.createDirectories(mc)

    try {
      val error = EmpiricalValidationExport
        .run(
          Config(
            sourceManifest = Path.of("docs/empirical-validation-source-manifest.csv"),
            mcDir = mc,
            out = root.resolve("out"),
            runId = "missing",
            outputPrefix = "validation-baseline",
            durationMonths = 120,
            seeds = 3,
            commit = "test-commit",
            parameterBranch = "test",
          ),
        )
        .left
        .getOrElse(fail("Expected missing seed files to fail"))

      error should include("Missing expected seed CSV files")
      error should include("validation-baseline_missing_120m_seed001.csv")
      error should include("validation-baseline_missing_120m_seed003.csv")
      Files.exists(root.resolve("out").resolve("baseline-validation-snapshot.csv")) shouldBe false
    } finally deleteRecursively(root)
  }

  it should "consume the checked-in manifest with real-economy comparator rows" in {
    Files.createDirectories(Path.of("target"))
    val root = Files.createTempDirectory(Path.of("target"), "empirical-validation-real-economy-")
    val mc   = root.resolve("mc")
    val out  = root.resolve("out")
    Files.createDirectories(mc)

    try {
      write(
        mc.resolve("fixture_real-economy_1m_seed001.csv"),
        """Month;MonthlyGdpProxy;Inflation;Unemployment;MarketWage;CreditToGdpGap;DebtToGdp;Esa2010DebtToGdp;CurrentAccount;FirmDeaths;MinBankCAR;MinBankLCR;Manuf_Output;ExRate;HousingPriceIndex;MortgageToGdp;MortgageDefault;DeficitToGdp;GovDomesticBudgetOutlays;RefRate
          |1;1000000;0.030;0.061;9652.19;0.50;0.538;0.597;0.01;102;0.18;1.30;200000;4.25;100;0.1217;0;0.073;918900000000;0.0375
          |""".stripMargin,
      )
      write(
        mc.resolve("fixture_real-economy_1m_firms.csv"),
        """Seed;FirmSize_MicroShare;FirmSize_SmallShare;FirmSize_MediumShare;FirmSize_LargeShare
          |1;0.959;0.034;0.006;0.001
          |""".stripMargin,
      )
      write(
        mc.resolve("fixture_real-economy_1m_hh.csv"),
        """Seed;Gini_Individual
          |1;0.300
          |""".stripMargin,
      )
      write(
        mc.resolve("fixture_real-economy_1m_banks.csv"),
        """Seed;BankId;CAR;NPL;Capital;Deposits;Loans
          |1;0;0.211;0.030;168000000000;2542300000000;557400000000
          |""".stripMargin,
      )

      val result = EmpiricalValidationExport
        .run(
          Config(
            sourceManifest = Path.of("docs/empirical-validation-source-manifest.csv"),
            mcDir = mc,
            out = out,
            runId = "real-economy",
            outputPrefix = "fixture",
            durationMonths = 1,
            seeds = 1,
            commit = "test-commit",
            parameterBranch = "test",
          ),
        )
        .fold(err => fail(err), identity)

      val statuses = result.rows.map(row => row.target -> row.status).toMap
      statuses("Inflation") shouldBe SnapshotStatus.PassBaseline
      statuses("Unemployment") shouldBe SnapshotStatus.PassBaseline
      statuses("Firm-size distribution - Micro") shouldBe SnapshotStatus.PassBaseline
      statuses("Firm-size distribution - Large") shouldBe SnapshotStatus.PassBaseline
      statuses("FX rate - EUR/PLN") shouldBe SnapshotStatus.PassBaseline
      statuses("NBP reference rate") shouldBe SnapshotStatus.PassBaseline
      statuses("Public debt/GDP - PDP forecast 2026") shouldBe SnapshotStatus.PassBaseline
      statuses("Public debt/GDP - ESA2010 debt 2025") shouldBe SnapshotStatus.PassBaseline
      statuses("Fiscal stance - general government deficit 2025") shouldBe SnapshotStatus.PassBaseline
      statuses("Fiscal stance - state budget expenditure plan 2026") shouldBe SnapshotStatus.Partial
      statuses("Bank capital/liquidity - total capital ratio") shouldBe SnapshotStatus.PassBaseline
      statuses("Bank capital/liquidity - LCR NSFR NPL bridge") shouldBe SnapshotStatus.Partial
      statuses("Housing and mortgages - mortgage stock/GDP") shouldBe SnapshotStatus.PassBaseline
      statuses("Housing and mortgages - mortgage default bridge") shouldBe SnapshotStatus.Partial

      Files.exists(out.resolve("baseline-validation-snapshot.csv")) shouldBe true
      Files.exists(out.resolve("source-manifest.csv")) shouldBe true
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
