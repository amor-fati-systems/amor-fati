package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.montecarlo.DelimitedTextFormat

object ProductionSectorGvaShareBridge:

  final case class Row(
      rowType: String,
      runtimeSector: String,
      outputStem: String,
      sourceProvider: String,
      sourceUrl: String,
      datasetCode: String,
      vintage: String,
      accessedAt: String,
      frequency: String,
      unit: String,
      naceSections: String,
      bridgeStatus: String,
      sourceValueMPln: Option[BigDecimal],
      productionPerimeterShare: Option[BigDecimal],
      transformation: String,
      reuseNote: String,
      notes: String,
  ):
    require(rowType.trim.nonEmpty, "rowType must be non-empty")
    require(sourceProvider.trim.nonEmpty, "sourceProvider must be non-empty")
    require(datasetCode.trim.nonEmpty, "datasetCode must be non-empty")
    require(vintage.trim.nonEmpty, "vintage must be non-empty")
    require(accessedAt.trim.nonEmpty, "accessedAt must be non-empty")
    require(unit.trim.nonEmpty, "unit must be non-empty")
    require(naceSections.trim.nonEmpty, "naceSections must be non-empty")
    require(bridgeStatus.trim.nonEmpty, "bridgeStatus must be non-empty")
    require(transformation.trim.nonEmpty, "transformation must be non-empty")
    require(reuseNote.trim.nonEmpty, "reuseNote must be non-empty")
    require(notes.trim.nonEmpty, "notes must be non-empty")

  val Header: Vector[String] = Vector(
    "row_type",
    "runtime_sector",
    "output_stem",
    "source_provider",
    "source_url",
    "dataset_code",
    "vintage",
    "accessed_at",
    "frequency",
    "unit",
    "nace_sections",
    "bridge_status",
    "source_value_m_pln",
    "production_perimeter_share",
    "transformation",
    "reuse_note",
    "notes",
  )

  private val GusSourceUrl   =
    "https://stat.gov.pl/en/topics/national-accounts/quarterly-national-accounts/gross-domestic-product-in-the-1st-quarter-of-2026-preliminary-estimate,2,94.html"
  private val GusXlsxUrl     =
    "https://stat.gov.pl/download/gfx/portalinformacyjny/en/defaultaktualnosci/3299/2/94/1/gpd_in_the_1st_quarter_of_2026_preliminary_estimate_tables.xlsx"
  private val EurostatApiUrl =
    "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/nama_10_a64?geo=PL&unit=CP_MEUR&na_item=B1G&time=2024&lang=en"
  private val SourceUrl      = s"$GusSourceUrl ; $GusXlsxUrl ; $EurostatApiUrl"

  private val SourceProvider = "GUS primary; Eurostat allocation bridge"
  private val DatasetCode    = "GUS Q1 2026 preliminary estimate Table 3; Eurostat nama_10_a64 B1G CP_MEUR PL 2024"
  private val Vintage        =
    "GUS 2025 annual current-price values in 2026-06-01 Q1 2026 release; Eurostat A64 2024 allocation weights updated 2026-06-19T23:00:00+0200"
  private val AccessedAt     = "2026-06-23"
  private val Frequency      = "annual"
  private val Unit           = "current-price PLN million GVA and share of included production-sector GVA"
  private val ReuseNote      = "Statistics Poland and Eurostat public statistics; cite provider, release or dataset code, vintage, and access date."

  object Gus2025Annual:
    val grossValueAddedTotal: BigDecimal      = BigDecimal("3447444.4")
    val industryBE: BigDecimal                = BigDecimal("743528.7")
    val constructionF: BigDecimal             = BigDecimal("242643.3")
    val tradeRepairG: BigDecimal              = BigDecimal("490128.3")
    val transportationStorageH: BigDecimal    = BigDecimal("252580.0")
    val accommodationFoodI: BigDecimal        = BigDecimal("67474.2")
    val informationCommunicationJ: BigDecimal = BigDecimal("170016.4")
    val financialInsuranceK: BigDecimal       = BigDecimal("175450.3")
    val realEstateL: BigDecimal               = BigDecimal("217638.6")
    val professionalAdminMN: BigDecimal       = BigDecimal("324038.3")
    val publicEducationHealthOPQ: BigDecimal  = BigDecimal("601878.8")

    val visibleAggregates: Vector[BigDecimal] = Vector(
      industryBE,
      constructionF,
      tradeRepairG,
      transportationStorageH,
      accommodationFoodI,
      informationCommunicationJ,
      financialInsuranceK,
      realEstateL,
      professionalAdminMN,
      publicEducationHealthOPQ,
    )

    val residualAandRtoU: BigDecimal =
      grossValueAddedTotal - visibleAggregates.sum

  object Eurostat2024A64:
    val agricultureA: BigDecimal         = BigDecimal("21554.4")
    val publicAdminO: BigDecimal         = BigDecimal("46011.0")
    val educationP: BigDecimal           = BigDecimal("38674.6")
    val healthcareQ: BigDecimal          = BigDecimal("42269.7")
    val artsRecreationR: BigDecimal      = BigDecimal("7079.3")
    val otherServicesS: BigDecimal       = BigDecimal("7928.1")
    val householdProductionT: BigDecimal = BigDecimal("125.4")
    val extraterritorialU: BigDecimal    = BigDecimal("0")

    private val residualDenominator         =
      agricultureA + artsRecreationR + otherServicesS + householdProductionT + extraterritorialU
    private val publicHealthcareDenominator =
      publicAdminO + educationP + healthcareQ

    val agricultureResidualWeight: BigDecimal    =
      agricultureA / residualDenominator
    val retailServicesResidualWeight: BigDecimal =
      (artsRecreationR + otherServicesS) / residualDenominator
    val excludedResidualWeight: BigDecimal       =
      (householdProductionT + extraterritorialU) / residualDenominator
    val publicWeightWithinOPQ: BigDecimal        =
      (publicAdminO + educationP) / publicHealthcareDenominator
    val healthcareWeightWithinOPQ: BigDecimal    =
      healthcareQ / publicHealthcareDenominator

  private val bpoSscValue                                   =
    Gus2025Annual.informationCommunicationJ + Gus2025Annual.professionalAdminMN
  private val manufacturingValue                            =
    Gus2025Annual.industryBE
  private val agricultureValue                              =
    Gus2025Annual.residualAandRtoU * Eurostat2024A64.agricultureResidualWeight
  private val retailServicesResidualValue                   =
    Gus2025Annual.residualAandRtoU * Eurostat2024A64.retailServicesResidualWeight
  private val retailServicesValue                           =
    Gus2025Annual.constructionF +
      Gus2025Annual.tradeRepairG +
      Gus2025Annual.transportationStorageH +
      Gus2025Annual.accommodationFoodI +
      Gus2025Annual.realEstateL +
      retailServicesResidualValue
  private val publicValue                                   =
    Gus2025Annual.publicEducationHealthOPQ * Eurostat2024A64.publicWeightWithinOPQ
  private val healthcareValue                               =
    Gus2025Annual.publicEducationHealthOPQ * Eurostat2024A64.healthcareWeightWithinOPQ
  val ExcludedFinancialValue: BigDecimal                    =
    Gus2025Annual.financialInsuranceK
  val ExcludedHouseholdAndExtraterritorialValue: BigDecimal =
    Gus2025Annual.residualAandRtoU * Eurostat2024A64.excludedResidualWeight

  val IncludedProductionGvaMPln: BigDecimal =
    bpoSscValue + manufacturingValue + retailServicesValue + healthcareValue + publicValue + agricultureValue

  val ExcludedGvaMPln: BigDecimal =
    ExcludedFinancialValue + ExcludedHouseholdAndExtraterritorialValue

  require(Gus2025Annual.residualAandRtoU > 0, "GUS residual A/R/S/T/U allocation must be positive")
  require(
    ((IncludedProductionGvaMPln + ExcludedGvaMPln) - Gus2025Annual.grossValueAddedTotal).abs < BigDecimal("0.000001"),
    "Included production GVA plus explicit exclusions must reconstruct GUS total GVA",
  )

  val Rows: Vector[Row] =
    val productionRows = Vector(
      productionRow(
        runtimeSector = "BPO/SSC",
        naceSections = "J+M+N",
        bridgeStatus = "BRIDGE_ASSUMPTION",
        value = bpoSscValue,
        transformation = "GUS J plus GUS combined M_N; runtime BPO/SSC remains a business-services proxy rather than a one-to-one NACE sector.",
        notes = "GUS reports J and M_N in Table 3. Crosswalk treats J/M/N as the BPO/SSC proxy.",
      ),
      productionRow(
        runtimeSector = "Manufacturing",
        naceSections = "B+C+D+E",
        bridgeStatus = "DIRECT_WITH_RESIDUALS",
        value = manufacturingValue,
        transformation = "GUS Industry aggregate maps to NACE B-E, matching the runtime Manufacturing bucket including B/D/E residuals.",
        notes = "No manufacturing parameter is tuned; this is a nominal GVA share bridge.",
      ),
      productionRow(
        runtimeSector = "Retail/Services",
        naceSections = "F+G+H+I+L+R+S",
        bridgeStatus = "ALLOCATION_BRIDGE",
        value = retailServicesValue,
        transformation = "GUS F, G, H, I, and L are direct; R+S is allocated from the GUS A/R/S/T/U residual using Eurostat 2024 A64 R+S share.",
        notes = "Residual R/S support broad services coverage without treating them as directly reported by GUS Table 3.",
      ),
      productionRow(
        runtimeSector = "Healthcare",
        naceSections = "Q",
        bridgeStatus = "ALLOCATION_BRIDGE",
        value = healthcareValue,
        transformation = "GUS O+P+Q aggregate split with Eurostat 2024 A64 Q weight inside O+P+Q.",
        notes = "GUS Table 3 does not split public administration, education, and health.",
      ),
      productionRow(
        runtimeSector = "Public",
        naceSections = "O+P",
        bridgeStatus = "ALLOCATION_BRIDGE",
        value = publicValue,
        transformation = "GUS O+P+Q aggregate split with Eurostat 2024 A64 O+P weight inside O+P+Q.",
        notes = "Public covers public administration and education; health remains in Healthcare.",
      ),
      productionRow(
        runtimeSector = "Agriculture",
        naceSections = "A",
        bridgeStatus = "ALLOCATION_BRIDGE",
        value = agricultureValue,
        transformation = "NACE A allocated from the GUS A/R/S/T/U residual using Eurostat 2024 A64 A share.",
        notes = "GUS Table 3 residual is exactly the source bucket needed for A plus R/S plus excluded T/U.",
      ),
    )

    productionRows ++ Vector(
      excludedRow(
        rowType = "excluded",
        naceSections = "K",
        bridgeStatus = "EXCLUDED",
        value = ExcludedFinancialValue,
        transformation = "GUS Financial and insurance activity is excluded from the production-firm perimeter.",
        notes = "Banks, insurance, funds, and non-bank finance are modeled by dedicated financial-sector agents.",
      ),
      excludedRow(
        rowType = "excluded",
        naceSections = "T+U",
        bridgeStatus = "EXCLUDED_ALLOCATION_BRIDGE",
        value = ExcludedHouseholdAndExtraterritorialValue,
        transformation = "T+U allocated from the GUS A/R/S/T/U residual using Eurostat 2024 A64 T+U share.",
        notes = "Household own-use production and extraterritorial organisations are outside the production-firm perimeter.",
      ),
    )

  def tsvLines: Vector[String] =
    Header.mkString("\t") +: Rows.map(row => DelimitedTextFormat.Tsv.join(cells(row)))

  private def productionRow(
      runtimeSector: String,
      naceSections: String,
      bridgeStatus: String,
      value: BigDecimal,
      transformation: String,
      notes: String,
  ): Row =
    val outputStem = ProductionSectorCrosswalk.mappingForSectorName(runtimeSector).map(_.runtimeSector.outputStem).getOrElse("")
    Row(
      rowType = "production",
      runtimeSector = runtimeSector,
      outputStem = outputStem,
      sourceProvider = SourceProvider,
      sourceUrl = SourceUrl,
      datasetCode = DatasetCode,
      vintage = Vintage,
      accessedAt = AccessedAt,
      frequency = Frequency,
      unit = Unit,
      naceSections = naceSections,
      bridgeStatus = bridgeStatus,
      sourceValueMPln = Some(value),
      productionPerimeterShare = Some(value / IncludedProductionGvaMPln),
      transformation = transformation,
      reuseNote = ReuseNote,
      notes = notes,
    )

  private def excludedRow(
      rowType: String,
      naceSections: String,
      bridgeStatus: String,
      value: BigDecimal,
      transformation: String,
      notes: String,
  ): Row =
    Row(
      rowType = rowType,
      runtimeSector = "",
      outputStem = "",
      sourceProvider = SourceProvider,
      sourceUrl = SourceUrl,
      datasetCode = DatasetCode,
      vintage = Vintage,
      accessedAt = AccessedAt,
      frequency = Frequency,
      unit = Unit,
      naceSections = naceSections,
      bridgeStatus = bridgeStatus,
      sourceValueMPln = Some(value),
      productionPerimeterShare = None,
      transformation = transformation,
      reuseNote = ReuseNote,
      notes = notes,
    )

  private def cells(row: Row): Vector[String] = Vector(
    row.rowType,
    row.runtimeSector,
    row.outputStem,
    row.sourceProvider,
    row.sourceUrl,
    row.datasetCode,
    row.vintage,
    row.accessedAt,
    row.frequency,
    row.unit,
    row.naceSections,
    row.bridgeStatus,
    row.sourceValueMPln.fold("")(formatMoney),
    row.productionPerimeterShare.fold("")(formatShare),
    row.transformation,
    row.reuseNote,
    row.notes,
  )

  private def formatMoney(value: BigDecimal): String =
    value.setScale(3, BigDecimal.RoundingMode.HALF_UP).toString

  private def formatShare(value: BigDecimal): String =
    value.setScale(9, BigDecimal.RoundingMode.HALF_UP).toString
