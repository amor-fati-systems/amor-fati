package com.boombustgroup.amorfati.config

/** Typed baseline calibration provenance for active `SimParams.defaults`
  * parameters.
  *
  * This is the initial code-level inventory migrated from
  * `docs/calibration-register.md`. It intentionally preserves the current
  * provenance gaps so tests and diagnostics can report them from code while
  * follow-up issues fill sources and validation evidence.
  */
object CalibrationProvenance:

  enum CalibrationStatusKind:
    case EmpiricalEvidence
    case SourceDetailMissing
    case ValidationNeeded
    case MissingSource
    case ExplicitAssumption
    case PolicyOrScenario
    case PlaceholderAssumption

  enum CalibrationExemptionKind:
    case StructuralAssumption
    case ScenarioSwitch
    case StartupPlaceholder

  enum CalibrationStatus(val token: String, val kind: CalibrationStatusKind):
    case Empirical            extends CalibrationStatus("EMPIRICAL", CalibrationStatusKind.EmpiricalEvidence)
    case EmpiricalTransformed extends CalibrationStatus("EMPIRICAL_TRANSFORMED", CalibrationStatusKind.EmpiricalEvidence)
    case CodeNoteEmpirical    extends CalibrationStatus("CODE_NOTE_EMPIRICAL", CalibrationStatusKind.SourceDetailMissing)
    case Assumed              extends CalibrationStatus("ASSUMED", CalibrationStatusKind.ExplicitAssumption)
    case TunedNeedsValidation extends CalibrationStatus("TUNED_NEEDS_VALIDATION", CalibrationStatusKind.ValidationNeeded)
    case PolicyScenario       extends CalibrationStatus("POLICY_SCENARIO", CalibrationStatusKind.PolicyOrScenario)
    case Placeholder          extends CalibrationStatus("PLACEHOLDER", CalibrationStatusKind.PlaceholderAssumption)
    case UnknownSource        extends CalibrationStatus("UNKNOWN_SOURCE", CalibrationStatusKind.MissingSource)

    def inferredExemption: Option[CalibrationExemptionKind] =
      this match
        case CalibrationStatus.Assumed        => Some(CalibrationExemptionKind.StructuralAssumption)
        case CalibrationStatus.PolicyScenario => Some(CalibrationExemptionKind.ScenarioSwitch)
        case CalibrationStatus.Placeholder    => Some(CalibrationExemptionKind.StartupPlaceholder)
        case _                                => None

  object CalibrationStatus:
    private val byToken: Map[String, CalibrationStatus] =
      values.map(status => status.token -> status).toMap

    def parse(value: String): Either[String, CalibrationStatus] =
      byToken.get(cleanToken(value)).toRight(s"Unknown calibration status: $value")

    private def cleanToken(value: String): String =
      value.trim.stripPrefix("`").stripSuffix("`")

  final case class CalibrationParameter(
      id: String,
      parameterIds: Vector[String],
      renderedValue: String,
      unit: String,
      provenance: String,
      empiricalTarget: String,
      transformation: String,
      ownerModules: Vector[String],
      status: CalibrationStatus,
      validationPath: Option[String] = None,
      exemption: Option[CalibrationExemptionKind] = None,
  ):
    def effectiveExemption: Option[CalibrationExemptionKind] =
      exemption.orElse(status.inferredExemption)

    def needsValidationEvidence: Boolean =
      status == CalibrationStatus.TunedNeedsValidation

    def needsSourceMetadata: Boolean =
      status == CalibrationStatus.UnknownSource || status == CalibrationStatus.CodeNoteEmpirical

  final case class CalibrationStatusReport(status: CalibrationStatus, count: Int)

  object Baseline:

    lazy val parameters: Vector[CalibrationParameter] =
      parsedRows.collect { case Right(parameter) => parameter }

    lazy val parseErrors: Vector[String] =
      parsedRows.collect { case Left(error) => error }

    lazy val statusCounts: Map[CalibrationStatus, Int] =
      parameters.groupMapReduce(_.status)(_ => 1)(_ + _)

    lazy val statusReport: Vector[CalibrationStatusReport] =
      CalibrationStatus.values.toVector.map(status => CalibrationStatusReport(status, statusCounts.getOrElse(status, 0)))

    def rowsWithStatus(status: CalibrationStatus): Vector[CalibrationParameter] =
      parameters.filter(_.status == status)

    private def parseRow(line: String): Either[String, CalibrationParameter] =
      val cells = line.trim.stripPrefix("|").stripSuffix("|").split("\\|", -1).iterator.map(_.trim).toVector
      if cells.length != 8 then Left(s"Expected 8 calibration columns, got ${cells.length}: $line")
      else
        val parameterIds = splitParameterIds(cells(0))
        val ownerModules = splitCodeList(cells(6))
        for
          id     <- parameterIds.headOption.toRight(s"Missing parameter id: $line")
          status <- CalibrationStatus.parse(cells(7))
        yield CalibrationParameter(
          id = id,
          parameterIds = parameterIds,
          renderedValue = stripCode(cells(1)),
          unit = cells(2),
          provenance = stripCode(cells(3)),
          empiricalTarget = stripCode(cells(4)),
          transformation = stripCode(cells(5)),
          ownerModules = ownerModules,
          status = status,
        )

    private def splitCodeList(value: String): Vector[String] =
      stripCode(value)
        .split(",")
        .iterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .toVector

    private def splitParameterIds(value: String): Vector[String] =
      val ids    = splitCodeList(value)
      val prefix = ids.headOption.flatMap: id =>
        val dotIndex = id.lastIndexOf('.')
        if dotIndex < 0 then None else Some(id.take(dotIndex + 1))
      ids.map: id =>
        if id.contains(".") then id else prefix.fold(id)(_ + id)

    private def stripCode(value: String): String =
      value.replace("`", "").trim

    private lazy val parsedRows: Vector[Either[String, CalibrationParameter]] =
      rawRows.map(parseRow)

    private val rawRowsString: String =
      """
| `pop.firmsCount` | `10000` | agents | Simulation design choice | Tractable heterogeneous firm population | Direct | `PopulationConfig` | `ASSUMED` |
| `pop.workersPerFirm` | `10` | workers/firm normalizer | Simulation design choice | Normalizer for population and `gdpRatio` | Direct | `PopulationConfig` | `ASSUMED` |
| `household.count` | `firmsCount * workersPerFirm = 100000` | agents | Derived from simulation scale | Household population tied to firm scale | Derived in `SimParams.defaults` | `SimParams` | `ASSUMED` |
| `pop.firmSizeDist` | `Gus` | enum | 2026-04-30 enterprise-size bridge | Polish firm-size distribution mode | Direct | `PopulationConfig` | `TUNED_NEEDS_VALIDATION` |
| `pop.firmSizeMicroShare` | `0.962` | share | 2026-04-30 enterprise-size bridge | Micro enterprise share | Direct | `PopulationConfig` | `TUNED_NEEDS_VALIDATION` |
| `pop.firmSizeSmallShare` | `0.028` | share | 2026-04-30 enterprise-size bridge | Small firm share | Direct | `PopulationConfig` | `TUNED_NEEDS_VALIDATION` |
| `pop.firmSizeMediumShare` | `0.008` | share | 2026-04-30 enterprise-size bridge residual | Medium firm share | Direct | `PopulationConfig` | `TUNED_NEEDS_VALIDATION` |
| `pop.firmSizeLargeShare` | `0.002` | share | 2026-04-30 enterprise-size bridge | Large firm share | Direct | `PopulationConfig` | `TUNED_NEEDS_VALIDATION` |
| `pop.realGdp` | `4160e9` | PLN/year | MF 2026 budget macro scale / GDP-implied budget ratios | Polish current-price GDP scale | Feeds `gdpRatio` | `PopulationConfig` | `CODE_NOTE_EMPIRICAL` |
| `pop.initialUnemploymentRate` | `0.061` | share | GUS registered unemployment, March 2026 | Starting unemployment stock | Initial household labor-force stock | `PopulationConfig`, `Household.Init` | `CODE_NOTE_EMPIRICAL` |
| `gdpRatio` | `computeGdpRatio(pop, firm.baseRevenue)` | scalar | Derived from agent flow scale and current-price GDP | Map agent flows to Polish macro scale | Derived | `SimParams` | `ASSUMED` |
| `topology` | `Watts-Strogatz` | enum | Network modeling convention | Small-world interaction topology | Direct | `SimParams` | `ASSUMED` |
| `sectorDefs` | 6 sectors | vector | 2026-04-30 sector bridge | Polish sector composition | Direct | `SimParams` | `TUNED_NEEDS_VALIDATION` |
| `sectorDefs.share` | `[0.03, 0.16, 0.45, 0.06, 0.22, 0.08]` | share by sector | 2026-04-30 sector bridge | Firm/employment sector weights | Direct | `SimParams` | `TUNED_NEEDS_VALIDATION` |
| `sectorDefs.sigma` | `[50, 10, 5, 2, 1, 3]` | CES elasticity | Structural automation/substitution assumption | Sectoral substitutability ranking | Direct | `SimParams` | `TUNED_NEEDS_VALIDATION` |
| `sectorDefs.wageMultiplier` | `[1.35, 0.94, 0.79, 0.97, 0.91, 0.67]` | multiplier | 2026-04-30 sector bridge | Relative sector wages | Direct | `SimParams` | `TUNED_NEEDS_VALIDATION` |
| `sectorDefs.revenueMultiplier` | `[1.50, 1.05, 0.91, 1.10, 1.08, 0.80]` | multiplier | UNKNOWN_SOURCE | Relative sector revenue/productivity | Direct | `SimParams` | `UNKNOWN_SOURCE` |
| `household.baseWage` | `9652` | PLN/month | GUS March 2026 enterprise-sector wage, rounded from 9652.19 PLN | Mean monthly gross wage | Direct | `HouseholdConfig` | `EMPIRICAL` |
| `household.baseReservationWage` | `4806` | PLN/month | Statutory 2026 minimum wage | Minimum acceptable wage | Direct | `HouseholdConfig` | `EMPIRICAL` |
| `household.mpc` | `0.92` | share | #461 demand-side calibration | Aggregate mean MPC supporting Poland 2026 consumption-led growth | Direct | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.mpcAlpha`, `household.mpcBeta` | `9.2`, `0.8` | beta params | #461 demand-side calibration | Heterogeneous MPC distribution centered on stronger private-consumption channel | Beta draw | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.savingsMu`, `household.savingsSigma` | `9.6`, `1.2` | log PLN params | UNKNOWN_SOURCE | Initial savings distribution | Lognormal draw | `HouseholdConfig` | `UNKNOWN_SOURCE` |
| `household.debtFraction` | `0.40` | share | Code note bridge: BIK bridge prior | Household positive debt share | Bernoulli init | `HouseholdConfig` | `CODE_NOTE_EMPIRICAL` |
| `household.debtMu`, `household.debtSigma` | `10.5`, `1.5` | log PLN params | UNKNOWN_SOURCE | Initial debt distribution | Lognormal draw | `HouseholdConfig` | `UNKNOWN_SOURCE` |
| `household.rentMean`, `rentStd`, `rentFloor` | `3500`, `800`, `1200` | PLN/month | Otodom March 2026 provincial-city asking-rent bridge | Rent distribution | Truncated normal draw | `HouseholdConfig` | `EMPIRICAL_TRANSFORMED` |
| `household.bufferTargetMonths` | `6.0` | months | Carroll-style buffer-stock model | Target liquid buffer | Direct | `HouseholdConfig` | `ASSUMED` |
| `household.laborSupplySteepness` | `4.0` | coefficient | #461 labor-market calibration | Labor-supply response steepness; calibrated so wage clearing supports strong 2026 growth without forcing persistent labor shedding | Direct | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.bufferSensitivity` | `0.2` | coefficient | #461 calibration | MPC response to buffer gap | Direct | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.mpcUnemployedBoost` | `0.10` | share | UNKNOWN_SOURCE | Extra MPC while unemployed | Direct | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.skillDecayRate` | `0.02` | monthly share | UNKNOWN_SOURCE | Skill decay under unemployment | Direct | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.scarringRate`, `scarringCap`, `scarringOnset` | `0.02`, `0.50`, `3` | share/months | Literature note in code | Long-run unemployment scarring | Direct | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.wageScarRate`, `wageScarCap`, `wageScarDecay` | `0.025`, `0.30`, `0.005` | share/month | Code note bridge: Jacobson et al.; Davis and von Wachter | Wage scar accumulation and recovery | Direct | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.retrainingCost`, `retrainingDuration` | `5000`, `6` | PLN/months | UNKNOWN_SOURCE | Retraining cost and duration | Direct | `HouseholdConfig` | `UNKNOWN_SOURCE` |
| `household.retrainingBaseSuccess`, `retrainingProb` | `0.60`, `0.15` | share | UNKNOWN_SOURCE | Retraining success/enrollment | Direct | `HouseholdConfig` | `TUNED_NEEDS_VALIDATION` |
| `household.bankruptcyDistressMonths` | `3` | months | ASSUMED | Distress persistence before bankruptcy | Direct | `HouseholdConfig` | `ASSUMED` |
| `household.depositSpread` | `0.02` | annual rate | UNKNOWN_SOURCE | Deposit rate below policy rate | Direct | `HouseholdConfig` | `UNKNOWN_SOURCE` |
| `household.ccSpread` | `0.04` | annual rate | Code note bridge: NBP MIR bridge prior | Consumer credit spread | Direct | `HouseholdConfig` | `CODE_NOTE_EMPIRICAL` |
| `household.ccMaxDti` | `0.40` | share | Code note bridge: KNF Recommendation T | Consumer credit DTI cap | Direct | `HouseholdConfig` | `CODE_NOTE_EMPIRICAL` |
| `household.ccMaxLoan` | `50000` | PLN | UNKNOWN_SOURCE | Maximum unsecured consumer loan | Direct | `HouseholdConfig` | `UNKNOWN_SOURCE` |
| `household.ccNplRecovery` | `0.15` | share | Code note bridge: BIK bridge prior | Consumer loan recovery | Direct | `HouseholdConfig` | `CODE_NOTE_EMPIRICAL` |
| `labor.frictionMatrix` | `DefaultFrictionMatrix` | 6x6 share | Code note bridge: GUS LFS bridge prior, Shimer 2005 | Cross-sector mobility friction | Direct | `LaborConfig` | `CODE_NOTE_EMPIRICAL` |
| `labor.voluntarySearchProb` | `0.02` | monthly share | UNKNOWN_SOURCE | Employed voluntary sector search | Direct | `LaborConfig` | `TUNED_NEEDS_VALIDATION` |
| `labor.voluntaryWageThreshold` | `0.20` | share | UNKNOWN_SOURCE | Required wage gain for voluntary search | Direct | `LaborConfig` | `TUNED_NEEDS_VALIDATION` |
| `labor.unionDensity` | `[0.02, 0.15, 0.03, 0.12, 0.30, 0.04]` | share by sector | Code note bridge: GUS bridge prior | Union membership density | Direct | `LaborConfig` | `CODE_NOTE_EMPIRICAL` |
| `labor.unionWagePremium` | `0.08` | share | Code note bridge: empirical approx. | Union wage premium | Direct | `LaborConfig` | `CODE_NOTE_EMPIRICAL` |
| `labor.unionRigidity` | `0.50` | share | UNKNOWN_SOURCE | Downward nominal wage rigidity | Direct | `LaborConfig` | `TUNED_NEEDS_VALIDATION` |
| `labor.expLambda` | `0.70` | coefficient | Code note bridge: Carroll 2003 | Adaptive expectations speed | Direct | `LaborConfig` | `TUNED_NEEDS_VALIDATION` |
| `labor.expCredibilityInit` | `0.80` | share | UNKNOWN_SOURCE | Initial NBP credibility | Direct | `LaborConfig` | `TUNED_NEEDS_VALIDATION` |
| `labor.expWagePassthrough`, `tightLaborWageSensitivity` | `0.75`, `0.06` | coefficient | #461 GDP-growth calibration / wage-pressure response | Nominal wage-setting pass-through and unemployment-below-NAIRU wage pressure | Monthly transform plus labor tightness pressure | `LaborConfig`, `LaborEconomics` | `TUNED_NEEDS_VALIDATION` |
| `labor.structuralMatchRate`, `cyclicalMatchRate` | `0.005`, `0.15` | monthly share | Poland 2026-04-30 labor-market matching calibration | Beveridge-style matching capacity | Monthly cap on job-search matches | `LaborConfig`, `LaborMarket`, `FirmEconomics` | `TUNED_NEEDS_VALIDATION` |
| `social.zusContribRate`, `zusEmployeeRate` | `0.1952`, `0.1371` | annual rate/share | Code note bridge: Social insurance law | ZUS payroll contribution rates | Direct | `SocialConfig` | `EMPIRICAL` |
| `social.zusBasePension` | `4321` | PLN/month | GUS Q1 2026 non-agricultural social-insurance pension/rent, rounded | Average pension payment | Direct | `SocialConfig` | `EMPIRICAL` |
| `social.nfzContribRate` | `0.09` | rate | Code note bridge: health-care law | NFZ contribution rate | Direct | `SocialConfig` | `EMPIRICAL` |
| `social.nfzPerCapitaCost` | `500` | PLN/month | #461 pension-stock recalibration | Health spending per effective capita | Direct, with `nfzAgingElasticity` | `SocialConfig` | `TUNED_NEEDS_VALIDATION` |
| `social.ppkEmployeeRate`, `ppkEmployerRate` | `0.02`, `0.015` | rate | Code note bridge: PPK law | PPK contribution rates | Direct | `SocialConfig` | `EMPIRICAL` |
| `social.eduShares` | `[0.08, 0.25, 0.30, 0.37]` | share | Code note bridge: GUS LFS bridge prior | Education composition | CDF draw | `SocialConfig` | `CODE_NOTE_EMPIRICAL` |
| `social.demInitialRetirees` | `pop.firmsCount * pop.workersPerFirm / 3` | agents | #461 pension-consumption/GDP calibration | Effective initial retiree stock | Derived in `SimParams.defaults`; consumed by `HouseholdIncomeEconomics` | `SocialConfig`, `SimParams`, `HouseholdIncomeEconomics` | `TUNED_NEEDS_VALIDATION` |
| `firm.baseRevenue` | `180000` | PLN/month/worker | Code note bridge: GUS F-01 bridge prior | Revenue per worker before demand shocks | Direct, unscaled | `FirmConfig` | `CODE_NOTE_EMPIRICAL` |
| `firm.productivityGrowth` | `0.020` | annual rate | #461 GDP-growth calibration | Baseline productivity/catch-up trend | `.monthly` in use | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.otherCosts` | `16667` | PLN/month/worker | UNKNOWN_SOURCE | Fixed non-wage operating cost | Direct, unscaled | `FirmConfig` | `UNKNOWN_SOURCE` |
| `firm.aiCapex` | `1200000` | PLN/firm | UNKNOWN_SOURCE | Full automation capex | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.hybridCapex` | `350000` | PLN/firm | UNKNOWN_SOURCE | Hybrid automation capex | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.aiOpex`, `firm.hybridOpex` | `30000`, `12000` | PLN/month/firm | UNKNOWN_SOURCE | AI/hybrid operating cost | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.hybridReadinessMin`, `fullAiReadinessMin` | `0.20`, `0.55` | share | #461 calibration | Digital readiness adoption thresholds | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.entrySectorBarriers` | `[0.8, 0.6, 1.2, 0.5, 0.1, 0.7]` | coefficient by sector | Code note bridge: enterprise-size bridge prior | Entry barriers | Direct | `FirmConfig` | `CODE_NOTE_EMPIRICAL` |
| `firm.entryAiThreshold`, `entryAiProb` | `0.15`, `0.20` | share | UNKNOWN_SOURCE | AI-native entrant trigger/probability | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.entryStartupCash` | `50000` | PLN | UNKNOWN_SOURCE | Entrant liquidity | Direct | `FirmConfig` | `UNKNOWN_SOURCE` |
| `firm.replacementEntryRate` | `0.35` | monthly share | UNKNOWN_SOURCE | Replacement of dead firm slots | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.netEntryRate`, `netEntryMaxMonthly` | `0.08`, `175` | monthly share / firms | #461 labor/GDP calibration | Utilization-backed net births constrained by staffing feasibility | Direct | `FirmConfig`, `FirmEntry` | `TUNED_NEEDS_VALIDATION` |
| `firm.laborAdjustSpeed`, `hiringWorkingCapitalMonths`, `startupHiringWorkingCapitalMonths` | `0.15`, `3`, `4` | monthly share / wage-months | #461 GDP-growth calibration | Firm hiring absorption and payroll working-capital runway | Direct in firm workforce decision | `FirmConfig`, `Firm` | `TUNED_NEEDS_VALIDATION` |
| `firm.digiDrift` | `0.001` | monthly share | UNKNOWN_SOURCE | Exogenous digital readiness drift | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.digiInvestCost`, `digiInvestBoost` | `50000`, `0.05` | PLN/share | UNKNOWN_SOURCE | Discretionary digital investment | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.networkK`, `networkRewireP` | `6`, `0.10` | degree/share | Network design assumption | Watts-Strogatz firm network | Direct | `FirmConfig` | `ASSUMED` |
| `firm.demoEffectThresh`, `demoEffectBoost` | `0.40`, `0.15` | share | UNKNOWN_SOURCE | Peer adoption demonstration effect | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.adoptionRampMonths` | `60` | months | #461 calibration | Adoption willingness ramp | Direct | `FirmConfig` | `TUNED_NEEDS_VALIDATION` |
| `firm.sigmaLambda` | `0.0` | coefficient | Scenario switch | Arthur-style sigma learning off by default | Direct | `FirmConfig` | `POLICY_SCENARIO` |
| `capital.klRatios` | `[180000, 375000, 120000, 300000, 225000, 270000]` | PLN/worker | GUS F-01 bridge prior note + #461 K/GDP calibration | Sector capital-labor ratios | Direct via `Firm.capitalPlanningWorkers` | `CapitalConfig`, `Firm` | `TUNED_NEEDS_VALIDATION` |
| `capital.depRates` | `[0.15, 0.08, 0.10, 0.07, 0.05, 0.08]` | annual rate | Code note bridge: GUS F-01 bridge prior | Sector depreciation rates | `.monthly` in use | `CapitalConfig` | `CODE_NOTE_EMPIRICAL` |
| `capital.importShare` | `0.18` | share | #461 GDP-growth calibration | Import share of investment | Direct | `CapitalConfig` | `TUNED_NEEDS_VALIDATION` |
| `capital.adjustSpeed` | `0.10` | monthly coefficient | #461 fiscal-investment/GDP calibration | Capital partial-adjustment speed | Direct | `CapitalConfig` | `TUNED_NEEDS_VALIDATION` |
| `capital.demandExpansionSensitivity` | `0.30` | coefficient | #461 GDP-growth calibration | Target-capital uplift under persistent excess demand | Direct | `CapitalConfig` | `TUNED_NEEDS_VALIDATION` |
| `capital.investmentCreditShare` | `1.0` | share | #461 calibration | Credit-financed share of cash-unfunded desired investment | Direct | `CapitalConfig` | `TUNED_NEEDS_VALIDATION` |
| `capital.inventoryTargetRatios` | `[0.05, 0.25, 0.15, 0.10, 0.02, 0.30]` | share by sector | Code note bridge: GUS bridge prior | Inventory/revenue targets | Direct | `CapitalConfig` | `CODE_NOTE_EMPIRICAL` |
| `climate.energyCostShares` | `[0.02, 0.10, 0.04, 0.05, 0.03, 0.06]` | share of revenue | Code note bridge: Eurostat/GUS 2023 | Energy burden by sector | Direct | `ClimateConfig` | `CODE_NOTE_EMPIRICAL` |
| `climate.etsBasePrice` | `80` | EUR/tCO2 | Code note bridge: KOBiZE bridge prior | EU ETS starting price | Direct | `ClimateConfig` | `CODE_NOTE_EMPIRICAL` |
| `climate.etsPriceDrift` | `0.03` | annual rate | Code note bridge: EC Fit for 55 trajectory | ETS trend | `.monthly` in use | `ClimateConfig` | `CODE_NOTE_EMPIRICAL` |
| `climate.greenBudgetShare` | `0.20` | share | UNKNOWN_SOURCE | Green investment budget share | Direct | `ClimateConfig` | `TUNED_NEEDS_VALIDATION` |
| `climate.greenImportShare` | `0.35` | share | UNKNOWN_SOURCE | Import share of green capex | Direct | `ClimateConfig` | `UNKNOWN_SOURCE` |
| `fiscal.citRate` | `0.19` | rate | Code note bridge: MF bridge prior / CIT law | Corporate income tax rate | Direct | `FiscalConfig` | `EMPIRICAL` |
| `fiscal.vatRates` | `[0.23, 0.19, 0.12, 0.06, 0.10, 0.07]` | rate by sector | Code note bridge: MF bridge prior effective rates | Sector VAT rates | Direct | `FiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.exciseRates` | `[0.01, 0.04, 0.03, 0.005, 0.002, 0.02]` | rate by sector | Code note bridge: MF bridge prior aggregate | Effective excise rates | Direct | `FiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.customsDutyRate` | `0.04` | rate | Code note bridge: EU CET/Eurostat TARIC | Average non-EU customs duty | Direct | `FiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.govBaseSpending` | `76.575e9` | raw PLN/month | MF 2026 budget spending plan (918.9e9 / 12) | Government base spending | Scaled by `gdpRatio` | `FiscalConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.govWageIndexShare` | `0.75` | share | #461 GDP-growth calibration | Labor-cost indexation of government purchases | CPI/wage blended cost index in `DemandEconomics.computeGovPurchases` | `FiscalConfig`, `DemandEconomics` | `TUNED_NEEDS_VALIDATION` |
| `fiscal.fofConsWeights`, `fofGovWeights` | `[0.02, 0.18, 0.59, 0.06, 0.07, 0.08]`, `[0.04, 0.08, 0.08, 0.20, 0.58, 0.02]` | sector shares | #461 demand-allocation calibration | Flow-of-funds allocation of household consumption and government purchases | Direct sector allocation | `FiscalConfig`, `DemandEconomics` | `TUNED_NEEDS_VALIDATION` |
| `io.crossSectorSpillover` | `0.65` | share | #461 GDP-growth calibration | Substitutable share of unmet sector demand | I-O-weighted partial spillover inside `DemandEconomics.applySpillover` | `DemandEconomics`, `IoConfig` | `TUNED_NEEDS_VALIDATION` |
| `fiscal.govInvestShare` | `0.20` | share | Code note bridge: MF bridge prior | Capital share of government spending | Direct | `FiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.govCapitalMultiplier`, `govCurrentMultiplier` | `1.5`, `0.8` | multiplier | Code note bridge: Ilzetzki, Mendoza and Vegh 2013 | Fiscal multipliers | Direct | `FiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.govInitCapital` | `0` | PLN | Explicit startup simplification | Initial public capital stock | Built from investment flows | `FiscalConfig` | `PLACEHOLDER` |
| `fiscal.euFundsTotalEur`, `euFundsAlpha`, `euFundsBeta` | `110e9`, `2`, `3` | EUR / beta shape | #461 GDP-growth calibration | EU cohesion plus KPO-style investment absorption window | Beta absorption path | `FiscalConfig`, `EuFunds` | `TUNED_NEEDS_VALIDATION` |
| `fiscal.euCofinanceRate`, `euCapitalShare` | `0.15`, `0.60` | share | Code note bridge: MFiPR / EU funds | National cofinance and capex split | Direct | `FiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.minWageTargetRatio` | `0.50` | share | Code note bridge: minimum wage act | Target minimum/average wage ratio | Annual adjustment | `FiscalConfig` | `EMPIRICAL` |
| `fiscal.govBenefitM1to3`, `govBenefitM4to6` | `1784`, `1401` | PLN/month | 2026 statutory unemployment benefit schedule, rounded | Unemployment benefit amounts | Direct | `FiscalConfig` | `EMPIRICAL` |
| `fiscal.govFiscalRiskBeta`, `fiscalRiskBeta55`, `fiscalRiskBeta60` | `0.03`, `0.04`, `0.08` | coefficient | #461 Poland debt-service calibration | Bond-yield sensitivity to public-debt pressure without a 60% debt cliff | Direct | `FiscalConfig`, `Nbp.bondYield` | `TUNED_NEEDS_VALIDATION` |
| `fiscal.govInitialWeightedCoupon` | `0.04` | annual rate | #461 calibration | Opening weighted coupon on Treasury debt stock | Direct | `FiscalConfig`, `WorldInit` | `TUNED_NEEDS_VALIDATION` |
| `fiscal.govAvgMaturityMonths` | `69` | months | MF public-debt bridge prior | Total State Treasury debt average maturity; domestic-only maturity is shorter | WAM coupon update | `FiscalConfig`, `OpenEconEconomics` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.baseForeignShare`, `maxForeignShare` | `0.35`, `0.55` | share | Code note bridge: NBP SPW holder bridge prior | Foreign government-bond holdings | Direct | `FiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `fiscal.fiscalRuleDebtCeiling` | `0.60` | debt/GDP share | Code note bridge: Polish constitution Art. 216 | Constitutional debt ceiling | Direct | `FiscalConfig` | `EMPIRICAL` |
| `fiscal.fiscalRuleCautionThreshold` | `0.55` | debt/GDP share | Code note bridge: public finance act Art. 86 | Caution threshold | Direct | `FiscalConfig` | `EMPIRICAL` |
| `fiscal.sgpDeficitLimit` | `0.03` | deficit/GDP share | Maastricht / SGP | Deficit limit | Direct | `FiscalConfig` | `EMPIRICAL` |
| `fiscal.sgpCorrectionSpeed` | `0.85` | annual share | #461 Poland EDP/GDP calibration | Gradual excessive-deficit correction speed | Direct | `FiscalConfig`, `FiscalRules` | `TUNED_NEEDS_VALIDATION` |
| `fiscal.fiscalConsolidationSpeed55`, `fiscalConsolidationSpeed60` | `0.18`, `0.45` | annual share | #461 Poland EDP/GDP calibration | Debt-threshold discretionary-spending consolidation path | Direct | `FiscalConfig`, `FiscalRules` | `TUNED_NEEDS_VALIDATION` |
| `fiscal.initGovDebt` | `1913.5e9` | raw PLN | MF public-debt release for IV kw. 2025: PDP 1913.5 mld PLN | Initial domestic fiscal-rule debt metric | Scaled by `gdpRatio`; ESA/EDP bridge is carried in `quasiFiscal.initBondsOutstanding` | `FiscalConfig`, `SimParams`, `FiscalRules` | `EMPIRICAL` |
| `fiscal.jstPitShare`, `jstCitShare` | `0.3846`, `0.0671` | share | Code note bridge: JST revenue act | Local-government tax shares | Direct | `FiscalConfig` | `EMPIRICAL` |
| `fiscal.pitRate1`, `pitRate2`, `pitBracket1Annual` | `0.12`, `0.32`, `120000` | rate/PLN/year | Current PIT-law bridge prior | PIT brackets | Annualized monthly PIT | `FiscalConfig` | `EMPIRICAL` |
| `fiscal.social800` | `800` | PLN/month/child | Code note bridge: legal act 2023 | 800+ benefit | Direct | `FiscalConfig` | `EMPIRICAL` |
| `monetary.initialRate` | `0.0375` | annual rate | NBP MPC decision, April 2026 | NBP reference rate | Direct | `MonetaryConfig` | `EMPIRICAL` |
| `monetary.initialInflation` | `0.030` | annual rate | GUS CPI, March 2026 | Starting CPI inflation stock | Direct | `MonetaryConfig`, `WorldInit` | `EMPIRICAL` |
| `monetary.initialExpectedInflation` | `0.025` | annual rate | NBP target anchor | Starting expected inflation stock | Direct | `MonetaryConfig`, `Expectations` | `TUNED_NEEDS_VALIDATION` |
| `monetary.initialExpectedRate` | `0.0375` | annual rate | NBP MPC decision, April 2026 | Starting expected policy-rate stock | Direct | `MonetaryConfig`, `Expectations` | `EMPIRICAL` |
| `monetary.targetInfl` | `0.025` | annual rate | NBP inflation target | Inflation target | Direct | `MonetaryConfig` | `EMPIRICAL` |
| `monetary.neutralRate` | `0.03` | annual rate | #461 calibration | Long-run neutral policy-rate anchor | Direct | `MonetaryConfig` | `TUNED_NEEDS_VALIDATION` |
| `monetary.taylorAlpha`, `taylorBeta`, `taylorDelta` | `1.2`, `0.8`, `0.1` | coefficients | #461 calibration / Taylor-rule convention | Policy reaction coefficients | Direct | `MonetaryConfig` | `TUNED_NEEDS_VALIDATION` |
| `monetary.taylorExpectedInflationWeight` | `0.80` | share | #461 2026-04-30 disinflation calibration | Forward-looking inflation weight in the policy reaction | Direct | `MonetaryConfig`, `Nbp.updateRate` | `TUNED_NEEDS_VALIDATION` |
| `monetary.taylorInertia` | `0.70` | share | UNKNOWN_SOURCE | Policy-rate smoothing | Direct | `MonetaryConfig` | `TUNED_NEEDS_VALIDATION` |
| `monetary.rateFloor`, `rateCeiling` | `0.001`, `0.15` | annual rate | Structural lower/upper bounds | Policy-rate corridor bounds | Direct | `MonetaryConfig` | `ASSUMED` |
| `monetary.maxRateChange` | `0.0025` | monthly annual-rate step | #461 calibration | Monthly policy-rate adjustment cap | Direct | `MonetaryConfig` | `TUNED_NEEDS_VALIDATION` |
| `monetary.nairu` | `0.05` | share | Code note bridge: estimated | NAIRU | Direct | `MonetaryConfig` | `TUNED_NEEDS_VALIDATION` |
| `monetary.reserveRateMult` | `0.5` | share | Code note bridge: NBP bridge prior | Reserve remuneration fraction | Direct | `MonetaryConfig` | `CODE_NOTE_EMPIRICAL` |
| `monetary.depositFacilitySpread`, `lombardSpread` | `0.01`, `0.01` | annual rate | Code note bridge: NBP corridor | Corridor +/- 100 bp | Direct | `MonetaryConfig` | `EMPIRICAL` |
| `monetary.qePace` | `5e9` | raw PLN/month | UNKNOWN_SOURCE | QE monthly purchase pace | Scaled by `gdpRatio` | `MonetaryConfig`, `SimParams` | `POLICY_SCENARIO` |
| `monetary.qeMaxGdpShare` | `0.30` | GDP share | UNKNOWN_SOURCE | QE stock ceiling | Direct | `MonetaryConfig` | `POLICY_SCENARIO` |
| `monetary.fxReserves` | `1078.313e9` | raw PLN | NBP March 2026 official reserve assets, EUR 253.5bn converted at model-start PLN/EUR 4.2537 | Initial official reserve assets | Scaled by `gdpRatio` | `MonetaryConfig`, `SimParams` | `EMPIRICAL_TRANSFORMED` |
| `banking.initCapital` | `168e9` | raw PLN | KNF February 2026 TCR 21.1%, mapped to model RWA proxy | Aggregate regulatory-capital proxy | Scaled by `gdpRatio` | `BankingConfig`, `SimParams` | `EMPIRICAL_TRANSFORMED` |
| `banking.initDeposits` | `2542.3e9` | raw PLN | KNF monthly banking data, February 2026 | Aggregate banking-sector deposits | Scaled by `gdpRatio` | `BankingConfig`, `SimParams` | `EMPIRICAL` |
| `banking.initLoans` | `557.4e9` | raw PLN | KNF monthly banking data, February 2026: SME + large enterprises + individual entrepreneurs + individual farmers | Corporate/nonfinancial business loans | Scaled by `gdpRatio` | `BankingConfig`, `SimParams` | `EMPIRICAL_TRANSFORMED` |
| `banking.initGovBonds`, `initNbpGovBonds` | `400e9`, `300e9` | raw PLN | Code note bridge: NBP bridge prior | Bank/NBP government-bond holdings | Scaled by `gdpRatio` | `BankingConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `banking.initConsumerLoans` | `225.2e9` | raw PLN | KNF monthly banking data, February 2026 | Consumer loan stock | Scaled by `gdpRatio`; household opening consumer loans are normalized to this target | `BankingConfig`, `SimParams`, `Household.Init` | `EMPIRICAL` |
| `banking.baseSpread` | `0.015` | annual rate | UNKNOWN_SOURCE | Base firm-loan spread | Direct | `BankingConfig` | `UNKNOWN_SOURCE` |
| `banking.minCar` | `0.08` | multiplier/share | Code note bridge: Basel III CRR | Minimum capital adequacy | Direct | `BankingConfig` | `EMPIRICAL` |
| `banking.loanRecovery` | `0.30` | share | UNKNOWN_SOURCE | Corporate loan recovery | Direct | `BankingConfig` | `UNKNOWN_SOURCE` |
| `banking.firmLoanAmortRate` | `1/60` | monthly rate | Code note bridge: NBP bridge prior maturity | Five-year average loan maturity | Direct | `BankingConfig` | `CODE_NOTE_EMPIRICAL` |
| `banking.reserveReq` | `0.035` | share | Code note bridge: NBP bridge prior | Required reserve ratio | Direct | `BankingConfig` | `EMPIRICAL` |
| `banking.lcrMin`, `nsfrMin` | `1.0`, `1.0` | multiplier | Basel III | Minimum LCR/NSFR | Direct | `BankingConfig` | `EMPIRICAL` |
| `banking.p2rAddons` | `[0.015, 0.010, 0.030, 0.015, 0.020, 0.025, 0.020]` | multiplier by bank | Code note bridge: KNF bridge prior | SREP/P2R add-ons | Direct | `BankingConfig` | `CODE_NOTE_EMPIRICAL` |
| `banking.bfgLevyRate` | `0.0024` | annual rate | Code note bridge: BFG bridge prior | Resolution levy | `.monthly` in use | `BankingConfig` | `CODE_NOTE_EMPIRICAL` |
| `banking.bfgDepositGuarantee` | `425370` | PLN/depositor | BFG EUR 100,000 guarantee converted at model-start PLN/EUR 4.2537 | Deposit guarantee threshold | Direct | `BankingConfig` | `EMPIRICAL_TRANSFORMED` |
| `banking.ccybMax` | `0.025` | multiplier | Code note bridge: KNF bridge prior | Max CCyB | Direct | `BankingConfig` | `CODE_NOTE_EMPIRICAL` |
| `banking.osiiBuffers` | `[0.020, 0.010, 0.005, 0.010, 0.015, 0.0025, 0.0025]` | multiplier by bank | KNF O-SII decisions announced November 2025 | O-SII buffers for default bank archetypes | Direct | `BankingConfig`, `Macroprudential` | `EMPIRICAL_TRANSFORMED` |
| `banking.htmShare` | `0.60` | share | Code note bridge: NBP bridge prior | HTM share of gov bond portfolio | Direct | `BankingConfig` | `CODE_NOTE_EMPIRICAL` |
| `banking.depositPanicRate` | `0.03` | monthly share | Code note bridge: Diamond-Dybvig mechanism | Panic switching after failure | Direct | `BankingConfig` | `TUNED_NEEDS_VALIDATION` |
| `banking.eclRate1`, `eclRate2`, `eclRate3` | `0.01`, `0.08`, `0.50` | share | Code note bridge: KNF IFRS 9 | ECL provision rates | Direct | `BankingConfig` | `CODE_NOTE_EMPIRICAL` |
| `forex.baseExRate` | `4.2537` | PLN/EUR | NBP table A, 2026-04-29 | Starting exchange rate | Direct | `ForexConfig` | `EMPIRICAL` |
| `forex.foreignRate` | `0.0215` | annual rate | ECB main refinancing rate | Foreign reference rate | Direct | `ForexConfig` | `EMPIRICAL` |
| `forex.importPropensity` | `0.22` | GDP share | Code note bridge: GUS/NBP bridge prior | Aggregate import-to-GDP ratio | Direct | `ForexConfig` | `CODE_NOTE_EMPIRICAL` |
| `forex.techImportShare` | `0.40` | share | UNKNOWN_SOURCE | Technology/capital goods share of imports | Direct | `ForexConfig` | `UNKNOWN_SOURCE` |
| `forex.irpSensitivity`, `exRateAdjSpeed` | `0.15`, `0.02` | coefficient | IRP / FX adjustment model | Exchange-rate response speed | Direct | `ForexConfig` | `TUNED_NEEDS_VALIDATION` |
| `forex.riskOffShockMonth` | `0` | month | Scenario switch | No baseline risk-off shock | Direct | `ForexConfig` | `POLICY_SCENARIO` |
| `openEcon.importContent` | `[0.15, 0.50, 0.20, 0.15, 0.05, 0.12]` | share by sector | Code note bridge: supply-use bridge prior | Import content of production | Direct | `OpenEconConfig` | `CODE_NOTE_EMPIRICAL` |
| `priceLevel.importPush` | `FX depreciation + GVC import-cost pressure` | monthly coefficient | #461 inflation audit | Imported inflation pass-through to CPI | Scaled by `forex.importPropensity` and capped by `openEcon.importPushCap` | `PriceLevel`, `PriceEquityEconomics` | `TUNED_NEEDS_VALIDATION` |
| `openEcon.exportBase` | `157.6e9` | raw PLN/month | NBP BoP January 2026 goods-and-services export bridge | Monthly export base | Scaled by `gdpRatio` | `OpenEconConfig`, `SimParams` | `EMPIRICAL_TRANSFORMED` |
| `openEcon.foreignGdpGrowth` | `0.015` | annual rate | Code note bridge: ECB/IMF projections | Foreign GDP growth | `.monthly` in export rule | `OpenEconConfig` | `CODE_NOTE_EMPIRICAL` |
| `openEcon.exportPriceElasticity`, `importPriceElasticity` | `0.8`, `0.6` | coefficient | Code note bridge: Marshall-Lerner / Campa-Goldberg | Trade price elasticities | Direct | `OpenEconConfig` | `CODE_NOTE_EMPIRICAL` |
| `openEcon.erElasticity` | `0.5` | coefficient | UNKNOWN_SOURCE | Exchange-rate elasticity of trade | Direct | `OpenEconConfig` | `TUNED_NEEDS_VALIDATION` |
| `openEcon.euTransfers` | `1.458e9` | raw PLN/month | Code note bridge: MFiPR bridge prior | EU transfer monthly flow | Scaled by `gdpRatio` | `OpenEconConfig`, `SimParams` | `CODE_NOTE_EMPIRICAL` |
| `openEcon.fdiBase` | `4.963e9` | raw PLN/month | NBP BoP trailing-12-month FDI bridge through January 2026, monthly average converted at model-start FX | Monthly FDI base flow | Scaled by `gdpRatio` | `OpenEconConfig`, `SimParams` | `EMPIRICAL_TRANSFORMED` |
| `openEcon.portfolioSensitivity`, `riskPremiumSensitivity` | `0.20`, `0.10` | coefficient | UNKNOWN_SOURCE | Portfolio/risk premium response | Direct | `OpenEconConfig` | `TUNED_NEEDS_VALIDATION` |
| `openEcon.pppSpeed` | `0.10` | annual coefficient | Code note bridge: Rogoff 1996 | PPP convergence speed | `.monthly` in FX rule | `OpenEconConfig` | `CODE_NOTE_EMPIRICAL` |
| `fdi.foreignShares` | `[0.15, 0.30, 0.10, 0.03, 0.00, 0.05]` | share by sector | Code note bridge: NBP IIP bridge prior and GUS bridge prior | Foreign-owned firm share by sector | Direct | `FdiConfig` | `CODE_NOTE_EMPIRICAL` |
| `fdi.profitShiftRate`, `repatriationRate` | `0.15`, `0.70` | share | Code note bridge: FDI outflow calibration | Profit shifting and dividend repatriation | Direct | `FdiConfig` | `TUNED_NEEDS_VALIDATION` |
| `fdi.maProb`, `maSizeMin` | `0.001`, `50` | monthly share / employees | UNKNOWN_SOURCE | Domestic firm acquisition probability and eligibility | Direct | `FdiConfig` | `TUNED_NEEDS_VALIDATION` |
| `gvc.euTradeShare` | `0.70` | share | Code note bridge: GUS/NBP bridge prior | EU share of total trade | Direct | `GvcConfig` | `CODE_NOTE_EMPIRICAL` |
| `gvc.exportShares` | `[0.05, 0.55, 0.15, 0.03, 0.02, 0.20]` | share by sector | Code note bridge: GUS bridge prior | Sector export shares | Direct | `GvcConfig` | `CODE_NOTE_EMPIRICAL` |
| `gvc.depth` | `[0.35, 0.75, 0.30, 0.40, 0.10, 0.45]` | share by sector | Code note bridge: WIOD/OECD ICIO | GVC backward linkage | Direct | `GvcConfig` | `CODE_NOTE_EMPIRICAL` |
| `gvc.foreignInflation`, `foreignGdpGrowth` | `0.02`, `0.015` | annual rate | Code note bridge: ECB/IMF | Foreign inflation/growth | `.monthly` where used | `GvcConfig` | `CODE_NOTE_EMPIRICAL` |
| `gvc.commodityVolatility`, `commodityMeanReversion` | `0.015`, `0.08` | monthly sigma / share | #461 GDP-growth calibration | No-shock baseline commodity path; explicit `energy-shock` scenario carries crisis dynamics | Mean-reverting stochastic process plus scenario shock | `GvcConfig`, `GvcTrade` | `TUNED_NEEDS_VALIDATION` |
| `gvc.demandShockMonth`, `commodityShockMonth` | `0`, `0` | month | Scenario switches | No baseline external shocks | Direct | `GvcConfig` | `POLICY_SCENARIO` |
| `immigration.monthlyRate`, `foreignWage` | `0.0008`, `6500` | monthly share / PLN | #461 labor/GDP calibration | Base labor-immigration rate and reference wage; moderated after 48m diagnostics showed the prior pull channel added roughly 3% of represented working-age population per year and over-amplified real GDP catch-up | Direct | `ImmigrationConfig` | `TUNED_NEEDS_VALIDATION` |
| `immigration.wageElasticity` | `2.0` | coefficient | Code note bridge: NBP 2023 survey | Wage differential migration response | Direct | `ImmigrationConfig` | `CODE_NOTE_EMPIRICAL` |
| `immigration.remitRate` | `0.15` | income share | Code note bridge: NBP 2023 | Immigrant remittance outflow | Direct | `ImmigrationConfig` | `CODE_NOTE_EMPIRICAL` |
| `immigration.returnRate`, `returnUnempThreshold`, `returnUnempSensitivity` | `0.005`, `0.20`, `0.10` | monthly share / coefficient | #461 labor-market calibration | Baseline and unemployment-sensitive return migration | Direct | `ImmigrationConfig`, `Immigration` | `TUNED_NEEDS_VALIDATION` |
| `immigration.sectorShares` | `[0.05, 0.35, 0.25, 0.05, 0.05, 0.25]` | share by sector | Code note bridge: GUS LFS bridge prior | Immigrant sector allocation | CDF draw | `ImmigrationConfig` | `CODE_NOTE_EMPIRICAL` |
| `immigration.skillMean` | `0.55` | share | #461 labor-market calibration | New immigrant productivity distribution used by job matching | Gaussian draw clamped by education tier | `ImmigrationConfig`, `Immigration` | `TUNED_NEEDS_VALIDATION` |
| `immigration.initStock` | `0` | agents | Explicit startup simplification | Initial immigrant stock | Immigration accumulates from monthly flows | `ImmigrationConfig` | `PLACEHOLDER` |
| `remittance.perCapita` | `40` | PLN/person/month | Code note bridge: NBP BoP bridge prior | Diaspora remittance inflow | Direct | `RemittanceConfig` | `CODE_NOTE_EMPIRICAL` |
| `tourism.inboundShare`, `outboundShare` | `0.05`, `0.03` | GDP share | Code note bridge: GUS TSA 2023 / NBP BoP 2023 | Tourism exports/imports | GDP-proportional | `TourismConfig` | `CODE_NOTE_EMPIRICAL` |
| `tourism.seasonality`, `peakMonth` | `0.40`, `7` | share/month | Code note bridge: GUS TSA | Tourism seasonality | Cosine seasonal factor | `TourismConfig` | `CODE_NOTE_EMPIRICAL` |
| `equity.initIndex`, `initMcap` | `128508.77`, `1232.99264e9` | index/raw PLN | GPW Benchmark WIG close and GPW domestic-company market capitalization on 2026-04-30 | WIG index and market cap | `initMcap` scaled by `gdpRatio` | `EquityConfig`, `SimParams` | `EMPIRICAL` |
| `equity.peMean`, `divYield` | `10.0`, `0.057` | scalar/annual rate | Code note bridge: GPW bridge prior | Long-run P/E and dividend yield | Direct | `EquityConfig` | `CODE_NOTE_EMPIRICAL` |
| `equity.foreignShare` | `0.67` | share | Code note bridge: KNF/KDPW bridge prior | Foreign ownership share | Direct | `EquityConfig` | `CODE_NOTE_EMPIRICAL` |
| `equity.listedProfitShare` | `0.10` | share | #461 calibration | Listed-company slice of aggregate modeled firm profits | Direct | `EquityConfig`, `EquityMarket` | `TUNED_NEEDS_VALIDATION` |
| `corpBond.spread` | `0.025` | annual rate | Code note bridge: RRRF bridge prior BBB | Corporate bond spread | Direct | `CorpBondConfig` | `CODE_NOTE_EMPIRICAL` |
| `corpBond.initStock` | `108.5e9` | raw PLN | Catalyst corporate instruments on 2026-04-30, PLN stock plus EUR stock converted at model-start FX | Corporate bonds outstanding | Scaled by `gdpRatio` | `CorpBondConfig`, `SimParams` | `EMPIRICAL_TRANSFORMED` |
| `corpBond.recovery` | `0.30` | share | UNKNOWN_SOURCE | Corporate bond recovery | Direct | `CorpBondConfig` | `UNKNOWN_SOURCE` |
| `housing.initHpi` | `100` | index | Base-index convention | National HPI starting point | Direct | `HousingConfig` | `ASSUMED` |
| `housing.initValue` | `7.8e12` | raw PLN | Latest NBP comprehensive residential-property stock estimate | Aggregate housing stock value | Scaled by `gdpRatio` | `HousingConfig`, `SimParams` | `EMPIRICAL_TRANSFORMED` |
| `housing.initMortgage` | `506.3e9` | raw PLN | KNF monthly banking data, February 2026 | Aggregate mortgage stock | Scaled by `gdpRatio` | `HousingConfig`, `SimParams` | `EMPIRICAL` |
| `housing.priceIncomeElast`, `priceRateElast`, `priceReversion` | `1.2`, `-0.8`, `0.05` | coefficients | UNKNOWN_SOURCE | HPI response to income, rates, and fundamentals | Direct | `HousingConfig` | `TUNED_NEEDS_VALIDATION` |
| `housing.mortgageSpread` | `0.025` | annual rate | Code note bridge: NBP bridge prior | Mortgage spread over policy rate | Direct | `HousingConfig` | `CODE_NOTE_EMPIRICAL` |
| `housing.mortgageMaturity`, `ltvMax` | `300`, `0.80` | months/share | Code note bridge: KNF Recommendation S | Mortgage maturity and LTV cap | Direct | `HousingConfig` | `EMPIRICAL` |
| `housing.originationRate`, `defaultBase`, `defaultUnempSens` | `0.003`, `0.001`, `0.05` | share/coefficient | UNKNOWN_SOURCE | Mortgage origination and default dynamics | Direct | `HousingConfig` | `TUNED_NEEDS_VALIDATION` |
| `housing.mortgageRecovery` | `0.70` | share | UNKNOWN_SOURCE | Defaulted mortgage recovery | Direct | `HousingConfig` | `UNKNOWN_SOURCE` |
| `housing.wealthMpc`, `rentalYield` | `0.05`, `0.045` | share/annual rate | Code note bridge: Case, Quigley and Shiller 2005; Otodom/NBP | Housing wealth consumption effect and rental yield | Direct | `HousingConfig` | `CODE_NOTE_EMPIRICAL` |
| `housing.regionalMarkets` | 7 regional rows | vector | Code note bridge: NBP/GUS bridge prior | Regional HPI, value share, mortgage share, income multipliers | Direct | `HousingConfig` | `CODE_NOTE_EMPIRICAL` |
| `regional.baseMigrationRate` | `0.005` | monthly share | Code note bridge: GUS bridge prior | Internal migration probability for unemployed workers | Direct | `RegionalConfig` | `CODE_NOTE_EMPIRICAL` |
| `regional.housingBarrierThreshold` | `0.7` | share | UNKNOWN_SOURCE | Housing-cost migration barrier | Direct | `RegionalConfig` | `TUNED_NEEDS_VALIDATION` |
| `pricing.calvoTheta` | `0.15` | monthly share | Code note bridge: Alvarez et al. 2006 | Average EU price duration around 6.7 months | Direct | `PricingConfig` | `CODE_NOTE_EMPIRICAL` |
| `pricing.baseMarkup` | `1.15` | multiplier | Code note bridge: Polish microdata approximation | Steady-state markup over marginal cost | Direct | `PricingConfig` | `CODE_NOTE_EMPIRICAL` |
| `pricing.demandSensitivity`, `costPassthrough` | `0.10`, `0.4` | coefficients | #461 calibration | Markup response to demand and cost shocks | Direct | `PricingConfig` | `TUNED_NEEDS_VALIDATION` |
| `pricing.minMarkup`, `maxMarkup` | `0.95`, `1.50` | multiplier | Structural bounds | Markup floor and ceiling | Direct | `PricingConfig` | `ASSUMED` |
| `io.matrix` | 6x6 matrix | technical coefficients | 2026-04-30 six-sector IO bridge prior | Inter-sector intermediate demand | Direct | `IoConfig` | `TUNED_NEEDS_VALIDATION` |
| `io.scale` | `1.0` | multiplier | Sensitivity switch | Full-strength I-O flows by default | Direct | `IoConfig` | `POLICY_SCENARIO` |
| `informal.sectorShares` | `[0.05, 0.15, 0.30, 0.20, 0.02, 0.35]` | share by sector | Code note bridge: Schneider 2023 | Shadow-economy sector shares | Direct | `InformalConfig` | `CODE_NOTE_EMPIRICAL` |
| `informal.citEvasion`, `vatEvasion`, `pitEvasion`, `exciseEvasion` | `0.50`, `0.30`, `0.40`, `0.30` | share | #461 calibration | Tax evasion rates by tax channel | Direct | `InformalConfig` | `TUNED_NEEDS_VALIDATION` |
| `informal.unempThreshold`, `cyclicalSens`, `smoothing` | `0.05`, `0.50`, `0.92` | rate/coefficient | UNKNOWN_SOURCE | Counter-cyclical informal-sector response | Direct | `InformalConfig` | `TUNED_NEEDS_VALIDATION` |
| `soe.baseDividendMultiplier` | `1.3` | multiplier | Code note bridge: MF | Baseline SOE dividend payout versus private firms | Direct | `SoeConfig` | `CODE_NOTE_EMPIRICAL` |
| `soe.dividendFiscalThreshold`, `dividendFiscalSensitivity` | `0.03`, `5.0` | share/coefficient | UNKNOWN_SOURCE | Fiscal-pressure dividend response | Direct | `SoeConfig` | `TUNED_NEEDS_VALIDATION` |
| `soe.firingReduction`, `investmentMultiplier`, `energyPassthrough` | `0.70`, `1.2`, `0.60` | share/multiplier | UNKNOWN_SOURCE | SOE labor buffer, directed investment, energy pass-through | Firing buffer applies to standard workforce adjustment and insolvency downsizing | `SoeConfig`, `Firm` | `TUNED_NEEDS_VALIDATION` |
| `ins.lifeReserves`, `nonLifeReserves` | `76.981e9`, `105.869e9` | raw PLN | KNF insurance financial report, 2025Q4 technical provisions by life and non-life segment | Insurance reserve pools | Scaled by `gdpRatio` | `InsuranceConfig`, `SimParams` | `EMPIRICAL` |
| `ins.govBondShare`, `corpBondShare`, `equityShare` | `0.35`, `0.08`, `0.12` | share | UNKNOWN_SOURCE | Insurance asset allocation | Portfolio rebalance target | `InsuranceConfig` | `UNKNOWN_SOURCE` |
| `ins.lifePremiumRate`, `nonLifePremiumRate` | `0.003`, `0.0025` | wage-bill share | UNKNOWN_SOURCE | Insurance premium flow | Direct | `InsuranceConfig` | `UNKNOWN_SOURCE` |
| `ins.lifeLossRatio`, `nonLifeLossRatio` | `0.85`, `0.70` | share | UNKNOWN_SOURCE | Insurance claims/premiums | Direct | `InsuranceConfig` | `UNKNOWN_SOURCE` |
| `nbfi.tfiInitAum` | `448.3e9` | raw PLN | Analizy/IZFiA fund assets, March 2026 | TFI AUM | Scaled by `gdpRatio` | `NbfiConfig`, `SimParams` | `EMPIRICAL` |
| `nbfi.creditInitStock` | `234e9` | raw PLN | ZPL active leasing and leasing-loan portfolio, end-2025 | NBFI credit stock | Scaled by `gdpRatio` | `NbfiConfig`, `SimParams` | `EMPIRICAL_TRANSFORMED` |
| `nbfi.tfiGovBondShare`, `tfiCorpBondShare`, `tfiEquityShare` | `0.40`, `0.10`, `0.10` | share | UNKNOWN_SOURCE | TFI portfolio allocation | Portfolio rebalance target | `NbfiConfig` | `UNKNOWN_SOURCE` |
| `nbfi.creditBaseRate` | `0.005` | monthly share | UNKNOWN_SOURCE | NBFI credit origination rate | Direct | `NbfiConfig` | `TUNED_NEEDS_VALIDATION` |
| `nbfi.creditRate` | `0.10` | annual rate | UNKNOWN_SOURCE | NBFI loan rate | `.monthly` in income | `NbfiConfig` | `UNKNOWN_SOURCE` |
| `nbfi.defaultBase`, `defaultUnempSens` | `0.002`, `3.0` | share/coefficient | UNKNOWN_SOURCE | NBFI default dynamics | Direct | `NbfiConfig` | `TUNED_NEEDS_VALIDATION` |
| `quasiFiscal.issuanceShare` | `0.40` | share | Code note bridge: NIK bridge prior | BGK/PFR share of capital programs | Direct | `QuasiFiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `quasiFiscal.initBondsOutstanding` | `421.653e9` | raw PLN | MF public-debt release for IV kw. 2025: EDP debt ca. 2335.2 mld PLN less PDP 1913.5 mld PLN; exact bridge retains prior 2335.153 mld PLN EDP baseline | Opening BGK/PFR-style quasi-fiscal stock bridging PDP to ESA/EDP debt | Scaled by `gdpRatio`; added to domestic fiscal debt only in `Esa2010DebtToGdp` | `QuasiFiscalConfig`, `WorldInit`, `McTimeseriesSchema` | `EMPIRICAL_TRANSFORMED` |
| `quasiFiscal.initNbpBondHoldings` | `106e9` | raw PLN | Code note bridge: NBP COVID quasi-QE purchase stock | Opening NBP holdings of quasi-fiscal bonds | Scaled by `gdpRatio`; residual opening stock is attributed to the bank holder slot | `QuasiFiscalConfig`, `WorldInit` | `CODE_NOTE_EMPIRICAL` |
| `quasiFiscal.avgMaturityMonths` | `72` | months | Code note bridge: NIK/BGK/PFR | BGK/PFR bond maturity | Amortization `1/maturity` | `QuasiFiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `quasiFiscal.nbpAbsorptionShare` | `0.70` | share | Code note bridge: COVID quasi-QE | NBP absorption under QE | Active when NBP QE active | `QuasiFiscalConfig` | `POLICY_SCENARIO` |
| `quasiFiscal.lendingShare` | `0.50` | share | Code note bridge: BGK subsidized lending | Lending share of issuance | Direct | `QuasiFiscalConfig` | `CODE_NOTE_EMPIRICAL` |
| `earmarked.fpRate` | `0.0245` | payroll rate | Code note bridge: employment promotion law | Fundusz Pracy levy | Direct | `EarmarkedConfig` | `EMPIRICAL` |
| `earmarked.pfronMonthlyRevenue`, `pfronMonthlySpending` | `460e6`, `420e6` | PLN/month | Code note bridge: PFRON bridge prior | PFRON revenue/spending | Direct, currently unscaled | `EarmarkedConfig` | `CODE_NOTE_EMPIRICAL` |
| `earmarked.fgspRate` | `0.001` | payroll rate | Code note bridge: employee claims law | FGSP levy | Direct | `EarmarkedConfig` | `EMPIRICAL` |
| `earmarked.fgspPayoutPerWorker` | `10000` | PLN/worker | UNKNOWN_SOURCE | Bankruptcy wage payout | Direct | `EarmarkedConfig` | `UNKNOWN_SOURCE` |
      """

    private val rawRows: Vector[String] =
      rawRowsString.linesIterator.toVector.filter(_.trim.startsWith("| `"))
