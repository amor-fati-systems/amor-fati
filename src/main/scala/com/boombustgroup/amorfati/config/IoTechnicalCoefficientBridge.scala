package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.montecarlo.DelimitedTextFormat
import com.boombustgroup.amorfati.types.*

object IoTechnicalCoefficientBridge:

  final case class Row(
      supplierSector: String,
      supplierOutputStem: String,
      usingSector: String,
      usingOutputStem: String,
      sourceProvider: String,
      sourceUrl: String,
      datasetCode: String,
      vintage: String,
      accessedAt: String,
      frequency: String,
      unit: String,
      supplierProducts: String,
      usingProducts: String,
      supplierBridgeStatus: String,
      usingBridgeStatus: String,
      orientation: String,
      sourceFlowThousandPln: BigDecimal,
      usingOutputThousandPln: BigDecimal,
      sourceCoefficient: BigDecimal,
      modelCoefficient: BigDecimal,
      absoluteDelta: BigDecimal,
      comparisonStatus: String,
      transformation: String,
      reuseNote: String,
      notes: String,
  ):
    require(supplierSector.trim.nonEmpty, "supplierSector must be non-empty")
    require(supplierOutputStem.trim.nonEmpty, "supplierOutputStem must be non-empty")
    require(usingSector.trim.nonEmpty, "usingSector must be non-empty")
    require(usingOutputStem.trim.nonEmpty, "usingOutputStem must be non-empty")
    require(sourceProvider.trim.nonEmpty, "sourceProvider must be non-empty")
    require(sourceUrl.trim.nonEmpty, "sourceUrl must be non-empty")
    require(datasetCode.trim.nonEmpty, "datasetCode must be non-empty")
    require(vintage.trim.nonEmpty, "vintage must be non-empty")
    require(accessedAt.trim.nonEmpty, "accessedAt must be non-empty")
    require(frequency.trim.nonEmpty, "frequency must be non-empty")
    require(unit.trim.nonEmpty, "unit must be non-empty")
    require(supplierProducts.trim.nonEmpty, "supplierProducts must be non-empty")
    require(usingProducts.trim.nonEmpty, "usingProducts must be non-empty")
    require(supplierBridgeStatus.trim.nonEmpty, "supplierBridgeStatus must be non-empty")
    require(usingBridgeStatus.trim.nonEmpty, "usingBridgeStatus must be non-empty")
    require(orientation.trim.nonEmpty, "orientation must be non-empty")
    require(sourceFlowThousandPln >= 0, "sourceFlowThousandPln must be non-negative")
    require(usingOutputThousandPln > 0, "usingOutputThousandPln must be positive")
    require(sourceCoefficient >= 0, "sourceCoefficient must be non-negative")
    require(modelCoefficient >= 0, "modelCoefficient must be non-negative")
    require(absoluteDelta >= 0, "absoluteDelta must be non-negative")
    require(comparisonStatus.trim.nonEmpty, "comparisonStatus must be non-empty")
    require(transformation.trim.nonEmpty, "transformation must be non-empty")
    require(reuseNote.trim.nonEmpty, "reuseNote must be non-empty")
    require(notes.trim.nonEmpty, "notes must be non-empty")

  val Header: Vector[String] = Vector(
    "supplier_sector",
    "supplier_output_stem",
    "using_sector",
    "using_output_stem",
    "source_provider",
    "source_url",
    "dataset_code",
    "vintage",
    "accessed_at",
    "frequency",
    "unit",
    "supplier_products",
    "using_products",
    "supplier_bridge_status",
    "using_bridge_status",
    "orientation",
    "source_flow_thousand_pln",
    "using_output_thousand_pln",
    "source_coefficient",
    "model_coefficient",
    "absolute_delta",
    "comparison_status",
    "transformation",
    "reuse_note",
    "notes",
  )

  final case class RuntimeSectorSource(
      runtimeSector: String,
      productCodes: Vector[String],
      bridgeStatus: String,
      notes: String,
  ):
    val outputStem: String              = outputStemFor(runtimeSector)
    val productCodeList: String         = productCodes.mkString("+")
    val normalizedCodes: Vector[String] =
      productCodes.map(_.stripPrefix("PKWIU_"))

  private val SourceProvider    = "GUS"
  private val SourceUrl         =
    "https://stat.gov.pl/download/gfx/portalinformacyjny/pl/defaultaktualnosci/5481/7/4/1/bilans_przeplywow_miedzygaleziowych_w_biezacych_cenach_bazowych_w_2020_r.xlsx"
  private val DatasetCode       = "GUS input-output table at basic prices for domestic output 2020, Table 3"
  private val Vintage           =
    "GUS Bilans przeplywow miedzygaleziowych w biezacych cenach bazowych w 2020 roku; publication 2024-06-27; accessed 2026-06-24"
  private val AccessedAt        = "2026-06-24"
  private val Frequency         = "annual"
  private val Unit              = "thousand PLN domestic intermediate product flow divided by using-product total output"
  private val ReuseNote         = "Statistics Poland public statistics; cite provider, release, table, vintage, and access date."
  private val Orientation       =
    "source row/supplier product -> source column/using product; runtime matrix(i)(j) is input from supplier sector i used by sector j"
  private val RetunedMatrixNote =
    "IoConfig.DefaultMatrix is retuned to the rounded GUS input-output coefficients for the 2026-04-30 baseline; this bridge remains the comparison surface."

  val RuntimeSectors: Vector[RuntimeSectorSource] = Vector(
    RuntimeSectorSource(
      "BPO/SSC",
      Vector(
        "PKWIU_58",
        "PKWIU_59",
        "PKWIU_60",
        "PKWIU_61",
        "PKWIU_62",
        "PKWIU_63",
        "PKWIU_69",
        "PKWIU_70",
        "PKWIU_71",
        "PKWIU_72",
        "PKWIU_73",
        "PKWIU_74",
        "PKWIU_75",
        "PKWIU_77",
        "PKWIU_78",
        "PKWIU_79",
        "PKWIU_80",
        "PKWIU_81",
        "PKWIU_82",
      ),
      "BRIDGE_ASSUMPTION",
      "PKWiU 58-63, 69-75, and 77-82 are the business-services proxy for the BPO/SSC runtime sector.",
    ),
    RuntimeSectorSource(
      "Manufacturing",
      Vector(
        "PKWIU_05",
        "PKWIU_06-09",
        "PKWIU_10",
        "PKWIU_11",
        "PKWIU_12",
        "PKWIU_13",
        "PKWIU_14",
        "PKWIU_15",
        "PKWIU_16",
        "PKWIU_17",
        "PKWIU_18",
        "PKWIU_19",
        "PKWIU_20",
        "PKWIU_21",
        "PKWIU_22",
        "PKWIU_23",
        "PKWIU_24",
        "PKWIU_25",
        "PKWIU_26",
        "PKWIU_27",
        "PKWIU_28",
        "PKWIU_29",
        "PKWIU_30",
        "PKWIU_31",
        "PKWIU_32",
        "PKWIU_33",
        "PKWIU_35",
        "PKWIU_36",
        "PKWIU_38",
        "PKWIU_37,39",
      ),
      "DIRECT_WITH_RESIDUALS",
      "PKWiU 05-39 products are retained in one industrial production bucket.",
    ),
    RuntimeSectorSource(
      "Retail/Services",
      Vector(
        "PKWIU_41-43",
        "PKWIU_45",
        "PKWIU_46",
        "PKWIU_47",
        "PKWIU_49",
        "PKWIU_50-51",
        "PKWIU_52-53",
        "PKWIU_55",
        "PKWIU_56",
        "PKWIU_68",
        "PKWIU_90",
        "PKWIU_91",
        "PKWIU_92",
        "PKWIU_93",
        "PKWIU_94",
        "PKWIU_95",
        "PKWIU_96",
      ),
      "DIRECT_WITH_RESIDUALS",
      "Broad market services include construction, trade, transport, accommodation, real estate, arts, and personal services.",
    ),
    RuntimeSectorSource(
      "Healthcare",
      Vector("PKWIU_86", "PKWIU_87-88"),
      "DIRECT",
      "PKWiU 86 and 87-88 products map directly to the runtime Healthcare sector.",
    ),
    RuntimeSectorSource(
      "Public",
      Vector("PKWIU_84", "PKWIU_85"),
      "BRIDGE_ASSUMPTION",
      "PKWiU 84 and 85 map public administration and education to the runtime Public sector; health remains in Healthcare.",
    ),
    RuntimeSectorSource(
      "Agriculture",
      Vector("PKWIU_01", "PKWIU_02", "PKWIU_03"),
      "BRIDGE_ASSUMPTION",
      "PKWiU 01, 02, and 03 map agriculture, forestry, fishing, and aquaculture to the runtime Agriculture sector.",
    ),
  )

  require(RuntimeSectors.map(_.runtimeSector) == SimParams.SchemaSectorNames, "I-O bridge must preserve runtime sector order")
  require(IoConfig.DefaultMatrix.length == RuntimeSectors.length, "IoConfig.DefaultMatrix must match runtime sector count")
  require(
    IoConfig.DefaultMatrix.forall(_.length == RuntimeSectors.length),
    "IoConfig.DefaultMatrix rows must match runtime sector count",
  )

  val UsingOutputThousandPln: Vector[BigDecimal] =
    Vector("569367264", "1663078187", "1685624131", "168615375", "279660267", "146526547").map(BigDecimal(_))

  val SourceFlowThousandPln: Vector[Vector[BigDecimal]] =
    Vector(
      Vector("129994670", "75128250", "106972170", "5265365", "6446611", "3434210"),
      Vector("23478124", "379818489", "191337602", "7304686", "10587755", "27435636"),
      Vector("46379004", "211559620", "397969204", "14904984", "18905691", "13176966"),
      Vector("658176", "688505", "870193", "21281197", "433941", "44702"),
      Vector("4494000", "3504282", "4012200", "305259", "5487649", "171994"),
      Vector("734018", "65032271", "2699820", "176861", "157198", "25310835"),
    ).map(_.map(BigDecimal(_)))

  require(UsingOutputThousandPln.length == RuntimeSectors.length, "I-O bridge output vector must match runtime sector count")
  require(SourceFlowThousandPln.length == RuntimeSectors.length, "I-O bridge source flow matrix must match runtime sector count")
  require(
    SourceFlowThousandPln.forall(_.length == RuntimeSectors.length),
    "I-O bridge source flow rows must match runtime sector count",
  )

  val SourceCoefficientMatrix: Vector[Vector[BigDecimal]] =
    SourceFlowThousandPln.map: supplierRow =>
      supplierRow
        .zip(UsingOutputThousandPln)
        .map: (flow, output) =>
          flow / output

  val ModelCoefficientMatrix: Vector[Vector[BigDecimal]] =
    IoConfig.DefaultMatrix.map(_.map(shareToBigDecimal))

  val Rows: Vector[Row] =
    RuntimeSectors.indices.toVector.flatMap: supplierIndex =>
      RuntimeSectors.indices.toVector.map: usingIndex =>
        row(supplierIndex, usingIndex)

  def tsvLines: Vector[String] =
    DelimitedTextFormat.Tsv.join(Header) +: Rows.map(row => DelimitedTextFormat.Tsv.join(cells(row)))

  private def row(supplierIndex: Int, usingIndex: Int): Row =
    val supplier         = RuntimeSectors(supplierIndex)
    val using            = RuntimeSectors(usingIndex)
    val sourceFlow       = SourceFlowThousandPln(supplierIndex)(usingIndex)
    val output           = UsingOutputThousandPln(usingIndex)
    val sourceCoeff      = SourceCoefficientMatrix(supplierIndex)(usingIndex)
    val modelCoeff       = ModelCoefficientMatrix(supplierIndex)(usingIndex)
    val delta            = (modelCoeff - sourceCoeff).abs
    val sectorNotes      =
      if supplierIndex == usingIndex then supplier.notes
      else s"${supplier.notes} ${using.notes}"
    val comparisonStatus =
      if delta <= BigDecimal("0.02") then "CLOSE"
      else if delta <= BigDecimal("0.08") then "MATERIAL_DELTA"
      else "LARGE_DELTA"

    Row(
      supplierSector = supplier.runtimeSector,
      supplierOutputStem = supplier.outputStem,
      usingSector = using.runtimeSector,
      usingOutputStem = using.outputStem,
      sourceProvider = SourceProvider,
      sourceUrl = SourceUrl,
      datasetCode = DatasetCode,
      vintage = Vintage,
      accessedAt = AccessedAt,
      frequency = Frequency,
      unit = Unit,
      supplierProducts = supplier.productCodeList,
      usingProducts = using.productCodeList,
      supplierBridgeStatus = supplier.bridgeStatus,
      usingBridgeStatus = using.bridgeStatus,
      orientation = Orientation,
      sourceFlowThousandPln = sourceFlow,
      usingOutputThousandPln = output,
      sourceCoefficient = sourceCoeff,
      modelCoefficient = modelCoeff,
      absoluteDelta = delta,
      comparisonStatus = comparisonStatus,
      transformation =
        "Sum GUS Table 3 domestic-output product-by-product intermediate flows from supplier PKWiU products to using PKWiU products, then divide by total output at basic prices for the using runtime sector. Financial services K64-66 and household services T97-98 stay outside the six-sector production-firm perimeter.",
      reuseNote = ReuseNote,
      notes = s"$sectorNotes $RetunedMatrixNote It does not validate io.crossSectorSpillover.",
    )

  private def shareToBigDecimal(value: Share): BigDecimal =
    BigDecimal(value.toLong) / BigDecimal(10000)

  private[config] def outputStemFor(runtimeSector: String): String =
    ProductionSectorCrosswalk
      .mappingForSectorName(runtimeSector)
      .map(_.runtimeSector.outputStem)
      .getOrElse(sys.error(s"Missing crosswalk mapping for sector: $runtimeSector"))

  private def cells(row: Row): Vector[String] = Vector(
    row.supplierSector,
    row.supplierOutputStem,
    row.usingSector,
    row.usingOutputStem,
    row.sourceProvider,
    row.sourceUrl,
    row.datasetCode,
    row.vintage,
    row.accessedAt,
    row.frequency,
    row.unit,
    row.supplierProducts,
    row.usingProducts,
    row.supplierBridgeStatus,
    row.usingBridgeStatus,
    row.orientation,
    formatDecimal(row.sourceFlowThousandPln),
    formatDecimal(row.usingOutputThousandPln),
    formatShare(row.sourceCoefficient),
    formatShare(row.modelCoefficient),
    formatShare(row.absoluteDelta),
    row.comparisonStatus,
    row.transformation,
    row.reuseNote,
    row.notes,
  )

  private def formatDecimal(value: BigDecimal): String =
    value.setScale(6, BigDecimal.RoundingMode.HALF_UP).toString

  private def formatShare(value: BigDecimal): String =
    value.setScale(9, BigDecimal.RoundingMode.HALF_UP).toString
