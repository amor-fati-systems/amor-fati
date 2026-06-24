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
      sourceFlowMEur: BigDecimal,
      usingOutputMEur: BigDecimal,
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
    require(sourceFlowMEur >= 0, "sourceFlowMEur must be non-negative")
    require(usingOutputMEur > 0, "usingOutputMEur must be positive")
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
    "source_flow_m_eur",
    "using_output_m_eur",
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
      productCodes.map(_.stripPrefix("CPA_"))

  private val SourceProvider = "Eurostat"
  private val SourceUrl      =
    "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/naio_10_cp1700?freq=A&unit=MIO_EUR&geo=PL&time=2020&lang=en"
  private val DatasetCode    = "Eurostat naio_10_cp1700 symmetric input-output table at basic prices, product-by-product, PL 2020"
  private val Vintage        =
    "Eurostat 2020 PL product-by-product SIOT; data updated 2026-06-10T23:00:00+0200; latest PL P1 total observation found through the API on 2026-06-24 was 2020"
  private val AccessedAt     = "2026-06-24"
  private val Frequency      = "annual"
  private val Unit           = "million euro domestic intermediate product flow divided by using-sector total output"
  private val ReuseNote      = "Eurostat public statistics; cite provider, dataset code, vintage, and access date."
  private val Orientation    =
    "source row/supplier product -> source column/using product; runtime matrix(i)(j) is input from supplier sector i used by sector j"

  val RuntimeSectors: Vector[RuntimeSectorSource] = Vector(
    RuntimeSectorSource(
      "BPO/SSC",
      Vector(
        "CPA_J58",
        "CPA_J59_60",
        "CPA_J61",
        "CPA_J62_63",
        "CPA_M69_70",
        "CPA_M71",
        "CPA_M72",
        "CPA_M73",
        "CPA_M74_75",
        "CPA_N77",
        "CPA_N78",
        "CPA_N79",
        "CPA_N80-82",
      ),
      "BRIDGE_ASSUMPTION",
      "J/M/N products are the business-services proxy for the BPO/SSC runtime sector.",
    ),
    RuntimeSectorSource(
      "Manufacturing",
      Vector(
        "CPA_B",
        "CPA_C10-12",
        "CPA_C13-15",
        "CPA_C16",
        "CPA_C17",
        "CPA_C18",
        "CPA_C19",
        "CPA_C20",
        "CPA_C21",
        "CPA_C22",
        "CPA_C23",
        "CPA_C24",
        "CPA_C25",
        "CPA_C26",
        "CPA_C27",
        "CPA_C28",
        "CPA_C29",
        "CPA_C30",
        "CPA_C31_32",
        "CPA_C33",
        "CPA_D",
        "CPA_E36",
        "CPA_E37-39",
      ),
      "DIRECT_WITH_RESIDUALS",
      "B/C/D/E products are retained with C because the runtime schema has one industrial production bucket.",
    ),
    RuntimeSectorSource(
      "Retail/Services",
      Vector(
        "CPA_F",
        "CPA_G45",
        "CPA_G46",
        "CPA_G47",
        "CPA_H49",
        "CPA_H50",
        "CPA_H51",
        "CPA_H52",
        "CPA_H53",
        "CPA_I",
        "CPA_L68A",
        "CPA_L68B",
        "CPA_R90-92",
        "CPA_R93",
        "CPA_S94",
        "CPA_S95",
        "CPA_S96",
      ),
      "DIRECT_WITH_RESIDUALS",
      "Broad market services include construction, trade, transport, accommodation, real estate, arts, and other services.",
    ),
    RuntimeSectorSource(
      "Healthcare",
      Vector("CPA_Q86", "CPA_Q87_88"),
      "DIRECT",
      "Q86/Q87_88 products map directly to the runtime Healthcare sector.",
    ),
    RuntimeSectorSource(
      "Public",
      Vector("CPA_O", "CPA_P"),
      "BRIDGE_ASSUMPTION",
      "O/P products map public administration and education to the runtime Public sector; health remains in Healthcare.",
    ),
    RuntimeSectorSource(
      "Agriculture",
      Vector("CPA_A01", "CPA_A02", "CPA_A03"),
      "BRIDGE_ASSUMPTION",
      "A01/A02/A03 products map agriculture, forestry, fishing, and aquaculture to the runtime Agriculture sector.",
    ),
  )

  require(RuntimeSectors.map(_.runtimeSector) == SimParams.SchemaSectorNames, "I-O bridge must preserve runtime sector order")
  require(IoConfig.DefaultMatrix.length == RuntimeSectors.length, "IoConfig.DefaultMatrix must match runtime sector count")
  require(
    IoConfig.DefaultMatrix.forall(_.length == RuntimeSectors.length),
    "IoConfig.DefaultMatrix rows must match runtime sector count",
  )

  val UsingOutputMEur: Vector[BigDecimal] =
    Vector("128149.27", "374314.25", "345011.14", "37950.79", "62944.02", "32979.19").map(BigDecimal(_))

  val SourceFlowMEur: Vector[Vector[BigDecimal]] =
    Vector(
      Vector("29258.42", "16909.35", "22426.31", "1185.08", "1450.97", "772.93"),
      Vector("5284.26", "85486.89", "41713.46", "1644.05", "2383.02", "6175.03"),
      Vector("8922.41", "45300.26", "66835.31", "3294.73", "4194.84", "2899.05"),
      Vector("148.13", "154.96", "173.19", "4789.83", "97.67", "10.05"),
      Vector("1011.48", "788.71", "841.46", "68.70", "1235.12", "38.70"),
      Vector("165.18", "14637.02", "605.79", "39.80", "35.37", "5696.79"),
    ).map(_.map(BigDecimal(_)))

  require(UsingOutputMEur.length == RuntimeSectors.length, "I-O bridge output vector must match runtime sector count")
  require(SourceFlowMEur.length == RuntimeSectors.length, "I-O bridge source flow matrix must match runtime sector count")
  require(
    SourceFlowMEur.forall(_.length == RuntimeSectors.length),
    "I-O bridge source flow rows must match runtime sector count",
  )

  val SourceCoefficientMatrix: Vector[Vector[BigDecimal]] =
    SourceFlowMEur.map: supplierRow =>
      supplierRow
        .zip(UsingOutputMEur)
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
    val sourceFlow       = SourceFlowMEur(supplierIndex)(usingIndex)
    val output           = UsingOutputMEur(usingIndex)
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
      sourceFlowMEur = sourceFlow,
      usingOutputMEur = output,
      sourceCoefficient = sourceCoeff,
      modelCoefficient = modelCoeff,
      absoluteDelta = delta,
      comparisonStatus = comparisonStatus,
      transformation =
        "Sum Eurostat DOM product-by-product intermediate flows from supplier CPA products to using CPA products, then divide by P1 total output for the using runtime sector. K/T/U and final-use columns stay outside the six-sector production-firm perimeter.",
      reuseNote = ReuseNote,
      notes = s"$sectorNotes This comparison artifact does not change IoConfig.DefaultMatrix or validate io.crossSectorSpillover.",
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
    formatDecimal(row.sourceFlowMEur),
    formatDecimal(row.usingOutputMEur),
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
