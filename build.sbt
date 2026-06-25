import complete.DefaultParsers.spaceDelimited

lazy val ledger = ProjectRef(file("modules/ledger"), "root")

lazy val sfcMatrices = inputKey[Unit]("Generate symbolic SFC BSM/TFM matrix artifacts")
lazy val robustnessReport = inputKey[Unit]("Generate lightweight sensitivity and robustness artifacts")
lazy val scenarioRun = inputKey[Unit]("Run named scenario-registry experiments")
lazy val empiricalValidation = inputKey[Unit]("Generate empirical validation baseline snapshot artifacts")
lazy val calibrationRegister = inputKey[Unit]("Generate calibration register docs from typed provenance registry")
lazy val householdCreditStressCalibration = inputKey[Unit]("Generate household liquidity and credit-stress calibration artifacts")
lazy val bankBalanceSheetBenchmark = inputKey[Unit]("Generate initial bank balance-sheet benchmark artifacts")
lazy val bankFailureAblations = inputKey[Unit]("Run controlled bank-failure ablation diagnostics")
lazy val hhBankLeadLagDiagnostics = inputKey[Unit]("Run HH-to-bank lead-lag and causal attribution diagnostics")
lazy val loanOriginationQuality = inputKey[Unit]("Run HH/Firm loan-origination quality diagnostics")
lazy val productionSectorGvaShareBridge = inputKey[Unit]("Generate production-sector GVA share source bridge artifact")
lazy val productionSectorLaborSourceBridge = inputKey[Unit]("Generate production-sector firm, employment, and wage source bridge artifact")
lazy val ioTechnicalCoefficientBridge =
  inputKey[Unit]("Generate I-O technical-coefficient source comparison artifact")
lazy val openingBankBalanceProfileBridge =
  inputKey[Unit]("Generate opening bank balance profile source bridge artifact")

lazy val baseScalacOptions = Seq(
  "-Werror",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-explain",
  "-Wunused:all",
)

lazy val testDeps = Seq(
  "org.scalatest"     %% "scalatest"       % "3.2.19"   % Test,
  "org.scalacheck"    %% "scalacheck"      % "1.18.1"   % Test,
  "org.scalatestplus" %% "scalacheck-1-18" % "3.2.19.0" % Test,
  "dev.zio"           %% "zio-test"        % "2.1.16"   % Test,
  "dev.zio"           %% "zio-test-sbt"    % "2.1.16"   % Test,
)

lazy val commonProjectSettings = Seq(
  organization := "com.boombustgroup",
  scalaVersion := "3.8.2",
  scalacOptions ++= baseScalacOptions,
)

lazy val root = project
  .in(file("."))
  .dependsOn(ledger)
  .settings(commonProjectSettings)
  .settings(
    name                       := "amor-fati",
    mainClass                  := Some("com.boombustgroup.amorfati.Main"),
    assembly / assemblyJarName := "amor-fati.jar",
    Compile / resourceGenerators += Def.task {
      import scala.sys.process.*
      val hash = try "git rev-parse --short HEAD".!!.trim
      catch { case _: Exception => "unknown" }
      val dirty = try {
        val s = "git status --porcelain".!!.trim; if (s.nonEmpty) "-dirty" else ""
      } catch { case _: Exception => "" }
      val file = (Compile / resourceManaged).value / "version.properties"
      IO.write(file, s"git.commit=$hash$dirty\n")
      Seq(file)
    },
    libraryDependencies ++= Seq(
      "dev.zio"           %% "zio"             % "2.1.16",
      "dev.zio"           %% "zio-streams"     % "2.1.16",
      "com.github.lalyos"  % "jfiglet"         % "0.0.9",
    ) ++ testDeps,
    sfcMatrices := Def
      .inputTaskDyn {
        val parsedArgs = spaceDelimited("<sfc matrix args>").parsed
        (Compile / runMain)
          .toTask(" com.boombustgroup.amorfati.diagnostics.SfcMatrixExport " + parsedArgs.mkString(" "))
      }
      .evaluated,
    robustnessReport := Def
      .inputTaskDyn {
        val parsedArgs = spaceDelimited("<robustness args>").parsed
        (Compile / runMain)
          .toTask(" com.boombustgroup.amorfati.diagnostics.SensitivityRobustnessExport " + parsedArgs.mkString(" "))
      }
      .evaluated,
    scenarioRun := Def
      .inputTaskDyn {
        val parsedArgs = spaceDelimited("<scenario args>").parsed
        (Compile / runMain)
          .toTask(" com.boombustgroup.amorfati.diagnostics.ScenarioRunExport " + parsedArgs.mkString(" "))
      }
      .evaluated,
    empiricalValidation := Def
      .inputTaskDyn {
        val parsedArgs = spaceDelimited("<empirical validation args>").parsed
        (Compile / runMain)
          .toTask(" com.boombustgroup.amorfati.diagnostics.EmpiricalValidationExport " + parsedArgs.mkString(" "))
      }
      .evaluated,
    calibrationRegister := Def
      .inputTaskDyn {
        val parsedArgs = spaceDelimited("<calibration register args>").parsed
        (Compile / runMain)
          .toTask(" com.boombustgroup.amorfati.diagnostics.CalibrationRegisterExport " + parsedArgs.mkString(" "))
      }
      .evaluated,
    householdCreditStressCalibration := Def
      .inputTaskDyn {
        val parsedArgs = spaceDelimited("<household credit stress args>").parsed
        (Compile / runMain)
          .toTask(" com.boombustgroup.amorfati.diagnostics.HouseholdCreditStressCalibrationExport " + parsedArgs.mkString(" "))
      }
      .evaluated,
    bankBalanceSheetBenchmark := Def
      .inputTaskDyn {
        val parsedArgs = spaceDelimited("<bank balance-sheet benchmark args>").parsed
        (Compile / runMain)
          .toTask(" com.boombustgroup.amorfati.diagnostics.BankBalanceSheetBenchmarkExport " + parsedArgs.mkString(" "))
      }
      .evaluated,
    bankFailureAblations := Def
      .inputTaskDyn {
        val parsedArgs = spaceDelimited("<bank failure ablation args>").parsed
        (Compile / runMain)
          .toTask(" com.boombustgroup.amorfati.diagnostics.BankFailureAblationExport " + parsedArgs.mkString(" "))
      }
      .evaluated,
    hhBankLeadLagDiagnostics := Def
      .inputTaskDyn {
        val parsedArgs = spaceDelimited("<HH-bank lead-lag args>").parsed
        (Compile / runMain)
          .toTask(" com.boombustgroup.amorfati.diagnostics.HhBankLeadLagDiagnosticsExport " + parsedArgs.mkString(" "))
      }
      .evaluated,
    loanOriginationQuality := Def
      .inputTaskDyn {
        val parsedArgs = spaceDelimited("<loan-origination quality args>").parsed
        (Compile / runMain)
          .toTask(" com.boombustgroup.amorfati.diagnostics.LoanOriginationQualityExport " + parsedArgs.mkString(" "))
      }
      .evaluated,
    productionSectorGvaShareBridge := Def
      .inputTaskDyn {
        val parsedArgs = spaceDelimited("<production-sector GVA share bridge args>").parsed
        (Compile / runMain)
          .toTask(" com.boombustgroup.amorfati.diagnostics.ProductionSectorGvaShareBridgeExport " + parsedArgs.mkString(" "))
      }
      .evaluated,
    productionSectorLaborSourceBridge := Def
      .inputTaskDyn {
        val parsedArgs = spaceDelimited("<production-sector labor source bridge args>").parsed
        (Compile / runMain)
          .toTask(" com.boombustgroup.amorfati.diagnostics.ProductionSectorLaborSourceBridgeExport " + parsedArgs.mkString(" "))
      }
      .evaluated,
    ioTechnicalCoefficientBridge := Def
      .inputTaskDyn {
        val parsedArgs = spaceDelimited("<I-O technical-coefficient bridge args>").parsed
        (Compile / runMain)
          .toTask(" com.boombustgroup.amorfati.diagnostics.IoTechnicalCoefficientBridgeExport " + parsedArgs.mkString(" "))
      }
      .evaluated,
    openingBankBalanceProfileBridge := Def
      .inputTaskDyn {
        val parsedArgs = spaceDelimited("<opening bank balance profile bridge args>").parsed
        (Compile / runMain)
          .toTask(" com.boombustgroup.amorfati.diagnostics.OpeningBankBalanceProfileBridgeExport " + parsedArgs.mkString(" "))
      }
      .evaluated,
    Test / testOptions ++= {
      val heavyTag         = "com.boombustgroup.amorfati.tags.Heavy"
      // Standard `sbt test` skips @Heavy suites by default, including coverage
      // runs where scoverage instrumentation makes long simulations expensive.
      // Run the full suite with: `sbt -DamorFati.includeHeavyTests=true test`
      val includeHeavy     = sys.props.get("amorFati.includeHeavyTests").exists { value =>
        val normalized = value.trim.toLowerCase
        normalized == "true" || normalized == "1" || normalized == "yes"
      }
      val excludeHeavyByDefault = !includeHeavy
      if (excludeHeavyByDefault) Seq(Tests.Argument(TestFrameworks.ScalaTest, "-l", heavyTag))
      else Nil
    },
  )

lazy val integrationTests = project
  .in(file("integration-tests"))
  .dependsOn(root % "compile->compile;test->test")
  .settings(commonProjectSettings)
  .settings(
    name := "amor-fati-integration-tests",
    libraryDependencies ++= testDeps,
  )
