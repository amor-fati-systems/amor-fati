package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.montecarlo.DelimitedTextFormat

object ProductionSectorLaborSourceBridge:

  final case class Row(
      rowType: String,
      metric: String,
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
      numeratorValue: BigDecimal,
      denominatorValue: BigDecimal,
      empiricalValue: BigDecimal,
      transformation: String,
      reuseNote: String,
      notes: String,
  ):
    require(rowType.trim.nonEmpty, "rowType must be non-empty")
    require(metric.trim.nonEmpty, "metric must be non-empty")
    require(runtimeSector.trim.nonEmpty, "runtimeSector must be non-empty")
    require(outputStem.trim.nonEmpty, "outputStem must be non-empty")
    require(sourceProvider.trim.nonEmpty, "sourceProvider must be non-empty")
    require(sourceUrl.trim.nonEmpty, "sourceUrl must be non-empty")
    require(datasetCode.trim.nonEmpty, "datasetCode must be non-empty")
    require(vintage.trim.nonEmpty, "vintage must be non-empty")
    require(accessedAt.trim.nonEmpty, "accessedAt must be non-empty")
    require(frequency.trim.nonEmpty, "frequency must be non-empty")
    require(unit.trim.nonEmpty, "unit must be non-empty")
    require(naceSections.trim.nonEmpty, "naceSections must be non-empty")
    require(bridgeStatus.trim.nonEmpty, "bridgeStatus must be non-empty")
    require(denominatorValue > 0, "denominatorValue must be positive")
    require(transformation.trim.nonEmpty, "transformation must be non-empty")
    require(reuseNote.trim.nonEmpty, "reuseNote must be non-empty")
    require(notes.trim.nonEmpty, "notes must be non-empty")

  val Header: Vector[String] = Vector(
    "row_type",
    "metric",
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
    "numerator_value",
    "denominator_value",
    "empirical_value",
    "transformation",
    "reuse_note",
    "notes",
  )

  private val GusReuseNote      =
    "Statistics Poland public statistics; cite provider, release or dataset code, vintage, and access date."
  private val EurostatReuseNote =
    "Eurostat public statistics; cite provider, dataset code, vintage, and access date."

  private val GusActiveEnterprisesPageUrl   =
    "https://stat.gov.pl/en/topics/economic-activities-finances/activity-of-enterprises-activity-of-companies/active-enterprises-in-the-first-quarter-of-2026,29,6.html"
  private val GusActiveEnterprisesXlsxUrl   =
    "https://stat.gov.pl/download/gfx/portalinformacyjny/en/defaultaktualnosci/3317/29/6/1/active_enterprises_in_the_first_quarter_of_2026.xlsx"
  private val GusActiveEnterprisesSourceUrl =
    s"$GusActiveEnterprisesPageUrl ; $GusActiveEnterprisesXlsxUrl"

  private val EurostatEmploymentUrl =
    "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/nama_10_a64_e?geo=PL&unit=THS_PER&na_item=EMP_DC&time=2024&lang=en"
  private val EurostatEmployeesUrl  =
    "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/nama_10_a64_e?geo=PL&unit=THS_PER&na_item=SAL_DC&time=2024&lang=en"
  private val EurostatCompUrl       =
    "https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/nama_10_a64?geo=PL&unit=CP_MEUR&na_item=D1&time=2024&lang=en"
  private val EurostatWageSourceUrl = s"$EurostatCompUrl ; $EurostatEmployeesUrl"

  object GusActiveEnterprisesQ12026:
    val total: BigDecimal                     = BigDecimal("2872113")
    val agricultureA: BigDecimal              = BigDecimal("24730")
    val miningB: BigDecimal                   = BigDecimal("2400")
    val manufacturingC: BigDecimal            = BigDecimal("235145")
    val utilitiesD: BigDecimal                = BigDecimal("9088")
    val waterWasteE: BigDecimal               = BigDecimal("8316")
    val constructionF: BigDecimal             = BigDecimal("433349")
    val tradeRepairG: BigDecimal              = BigDecimal("479518")
    val transportationH: BigDecimal           = BigDecimal("167487")
    val accommodationFoodI: BigDecimal        = BigDecimal("78260")
    val informationJ: BigDecimal              = BigDecimal("238857")
    val financialInsuranceK: BigDecimal       = BigDecimal("57640")
    val realEstateL: BigDecimal               = BigDecimal("76771")
    val professionalM: BigDecimal             = BigDecimal("398469")
    val administrativeN: BigDecimal           = BigDecimal("132375")
    val educationP: BigDecimal                = BigDecimal("89033")
    val healthcareQ: BigDecimal               = BigDecimal("255471")
    val artsR: BigDecimal                     = BigDecimal("32652")
    val otherServicesSExcluding94: BigDecimal = BigDecimal("152552")

    val includedProductionEnterprises: BigDecimal =
      total - financialInsuranceK

  object Eurostat2024A64:
    final case class Section(employmentThousandPersons: BigDecimal, employeesThousandPersons: BigDecimal, compensationMEur: BigDecimal)

    val sections: Map[String, Section] = Map(
      "A" -> Section(BigDecimal("1204.9"), BigDecimal("125.0"), BigDecimal("4376.4")),
      "B" -> Section(BigDecimal("187.9"), BigDecimal("186.3"), BigDecimal("6329.4")),
      "C" -> Section(BigDecimal("3333.8"), BigDecimal("3109.7"), BigDecimal("68282.3")),
      "D" -> Section(BigDecimal("182.1"), BigDecimal("175.9"), BigDecimal("4968.9")),
      "E" -> Section(BigDecimal("181.7"), BigDecimal("175.6"), BigDecimal("4430.1")),
      "F" -> Section(BigDecimal("1245.8"), BigDecimal("848.9"), BigDecimal("18054.0")),
      "G" -> Section(BigDecimal("2305.5"), BigDecimal("1909.5"), BigDecimal("41519.6")),
      "H" -> Section(BigDecimal("1181.0"), BigDecimal("1048.3"), BigDecimal("23229.6")),
      "I" -> Section(BigDecimal("395.3"), BigDecimal("330.6"), BigDecimal("5105.2")),
      "J" -> Section(BigDecimal("699.8"), BigDecimal("499.5"), BigDecimal("15084.5")),
      "K" -> Section(BigDecimal("466.5"), BigDecimal("411.9"), BigDecimal("13152.3")),
      "L" -> Section(BigDecimal("152.1"), BigDecimal("109.4"), BigDecimal("4047.8")),
      "M" -> Section(BigDecimal("831.1"), BigDecimal("538.0"), BigDecimal("17658.0")),
      "N" -> Section(BigDecimal("503.9"), BigDecimal("438.3"), BigDecimal("11611.5")),
      "O" -> Section(BigDecimal("1242.7"), BigDecimal("1242.7"), BigDecimal("40906.9")),
      "P" -> Section(BigDecimal("1384.6"), BigDecimal("1324.6"), BigDecimal("32949.0")),
      "Q" -> Section(BigDecimal("1182.8"), BigDecimal("1028.7"), BigDecimal("24012.3")),
      "R" -> Section(BigDecimal("239.5"), BigDecimal("198.6"), BigDecimal("4461.2")),
      "S" -> Section(BigDecimal("426.3"), BigDecimal("290.1"), BigDecimal("3747.0")),
      "T" -> Section(BigDecimal("72.2"), BigDecimal("72.2"), BigDecimal("125.4")),
      "U" -> Section(BigDecimal("0.5"), BigDecimal("0.5"), BigDecimal("0.0")),
    )

    def section(code: String): Section =
      sections.getOrElse(code, throw new IllegalArgumentException(s"Unknown Eurostat A64 section: $code"))

  private final case class RuntimeBridge(
      runtimeSector: String,
      firmSections: Vector[String],
      employmentSections: Vector[String],
      bridgeStatus: String,
      firmNotes: String,
      laborNotes: String,
  ):
    val outputStem: String             = outputStemFor(runtimeSector)
    val firmNaceSections: String       = firmSections.mkString("+")
    val employmentNaceSections: String = employmentSections.mkString("+")

  private val RuntimeRows: Vector[RuntimeBridge] = Vector(
    RuntimeBridge(
      "BPO/SSC",
      Vector("J", "M", "N"),
      Vector("J", "M", "N"),
      "BRIDGE_ASSUMPTION",
      "J/M/N are the active-enterprise business-services proxy for the BPO/SSC runtime sector.",
      "J/M/N are the national-accounts business-services proxy for the BPO/SSC runtime sector.",
    ),
    RuntimeBridge(
      "Manufacturing",
      Vector("B", "C", "D", "E"),
      Vector("B", "C", "D", "E"),
      "DIRECT_WITH_RESIDUALS",
      "B/D/E are retained with C because the runtime schema has one industrial production bucket.",
      "B/D/E are retained with C because the runtime schema has one industrial production bucket.",
    ),
    RuntimeBridge(
      "Retail/Services",
      Vector("F", "G", "H", "I", "L", "R", "S"),
      Vector("F", "G", "H", "I", "L", "R", "S"),
      "DIRECT_WITH_RESIDUALS",
      "Broad market services include construction, trade, transport, accommodation, real estate, arts, and other services; GUS S excludes division 94.",
      "Broad market services include construction, trade, transport, accommodation, real estate, arts, and other services.",
    ),
    RuntimeBridge(
      "Healthcare",
      Vector("Q"),
      Vector("Q"),
      "DIRECT",
      "Q maps directly to the runtime Healthcare sector.",
      "Q maps directly to the runtime Healthcare sector.",
    ),
    RuntimeBridge(
      "Public",
      Vector("P"),
      Vector("O", "P"),
      "BRIDGE_ASSUMPTION",
      "GUS active-enterprise Table 2 does not include O public administration; firm-population Public is education-only on the market-producer perimeter.",
      "Eurostat national accounts maps O+P to the runtime Public sector.",
    ),
    RuntimeBridge(
      "Agriculture",
      Vector("A"),
      Vector("A"),
      "BRIDGE_ASSUMPTION",
      "GUS active-enterprise Table 2 excludes individual agricultural holdings.",
      "Eurostat national accounts A maps directly to the runtime Agriculture sector.",
    ),
  )

  val IncludedFirmEnterprises: BigDecimal =
    RuntimeRows.map(row => firmValue(row.firmSections)).sum

  val IncludedEmploymentThousandPersons: BigDecimal =
    RuntimeRows.map(row => employmentValue(row.employmentSections)).sum

  val IncludedEmployeesThousandPersons: BigDecimal =
    RuntimeRows.map(row => employeesValue(row.employmentSections)).sum

  val IncludedCompensationMEur: BigDecimal =
    RuntimeRows.map(row => compensationValue(row.employmentSections)).sum

  val IncludedCompensationPerEmployeeKEur: BigDecimal =
    IncludedCompensationMEur / IncludedEmployeesThousandPersons

  require(
    IncludedFirmEnterprises == GusActiveEnterprisesQ12026.includedProductionEnterprises,
    "Runtime firm-population bridge must reconstruct GUS active enterprises after excluding K",
  )
  require(IncludedEmploymentThousandPersons > 0, "Included employment denominator must be positive")
  require(IncludedCompensationPerEmployeeKEur > 0, "Included compensation-per-employee denominator must be positive")

  val Rows: Vector[Row] =
    RuntimeRows.flatMap(row => Vector(firmShareRow(row), employmentShareRow(row), wageRatioRow(row)))

  def tsvLines: Vector[String] =
    Header.mkString("\t") +: Rows.map(row => DelimitedTextFormat.Tsv.join(cells(row)))

  private def firmShareRow(row: RuntimeBridge): Row =
    val numerator = firmValue(row.firmSections)
    Row(
      rowType = "production",
      metric = "firm_population_share",
      runtimeSector = row.runtimeSector,
      outputStem = row.outputStem,
      sourceProvider = "GUS",
      sourceUrl = GusActiveEnterprisesSourceUrl,
      datasetCode = "GUS Active enterprises in Q1 2026 Table 2",
      vintage = "2026 Q1 release 2026-06-10",
      accessedAt = "2026-06-24",
      frequency = "quarterly",
      unit = "active enterprises and share of included production-enterprise perimeter",
      naceSections = row.firmNaceSections,
      bridgeStatus = row.bridgeStatus,
      numeratorValue = numerator,
      denominatorValue = IncludedFirmEnterprises,
      empiricalValue = numerator / IncludedFirmEnterprises,
      transformation =
        "Sum GUS Q1 2026 active-enterprise counts by mapped NACE sections and divide by included production-enterprise perimeter. K is excluded because financial-sector agents are separate; O/T/U are outside the GUS active-enterprise Table 2 perimeter.",
      reuseNote = GusReuseNote,
      notes = row.firmNotes,
    )

  private def employmentShareRow(row: RuntimeBridge): Row =
    val numerator = employmentValue(row.employmentSections)
    Row(
      rowType = "production",
      metric = "employment_share",
      runtimeSector = row.runtimeSector,
      outputStem = row.outputStem,
      sourceProvider = "Eurostat",
      sourceUrl = EurostatEmploymentUrl,
      datasetCode = "Eurostat nama_10_a64_e EMP_DC THS_PER PL 2024",
      vintage = "Eurostat 2024 A64 national-accounts data updated 2026-06-19T23:00:00+0200",
      accessedAt = "2026-06-24",
      frequency = "annual",
      unit = "thousand employed persons and share of included production-sector employment",
      naceSections = row.employmentNaceSections,
      bridgeStatus = row.bridgeStatus,
      numeratorValue = numerator,
      denominatorValue = IncludedEmploymentThousandPersons,
      empiricalValue = numerator / IncludedEmploymentThousandPersons,
      transformation =
        "Sum Eurostat EMP_DC thousand persons by mapped NACE sections and divide by included production-sector employment. K, T, and U are excluded from the denominator.",
      reuseNote = EurostatReuseNote,
      notes = row.laborNotes,
    )

  private def wageRatioRow(row: RuntimeBridge): Row =
    val employees    = employeesValue(row.employmentSections)
    val compensation = compensationValue(row.employmentSections)
    val cpe          = compensation / employees
    Row(
      rowType = "production",
      metric = "wage_ratio",
      runtimeSector = row.runtimeSector,
      outputStem = row.outputStem,
      sourceProvider = "Eurostat",
      sourceUrl = EurostatWageSourceUrl,
      datasetCode = "Eurostat nama_10_a64 D1 CP_MEUR and nama_10_a64_e SAL_DC THS_PER PL 2024",
      vintage = "Eurostat 2024 A64 national-accounts data updated 2026-06-19T23:00:00+0200",
      accessedAt = "2026-06-24",
      frequency = "annual",
      unit = "compensation per employee ratio to included production-sector mean",
      naceSections = row.employmentNaceSections,
      bridgeStatus = row.bridgeStatus,
      numeratorValue = cpe,
      denominatorValue = IncludedCompensationPerEmployeeKEur,
      empiricalValue = cpe / IncludedCompensationPerEmployeeKEur,
      transformation =
        "Compute sector compensation per employee as Eurostat D1 current-price million euro divided by SAL_DC thousand employees, then normalize to the included production-sector compensation-per-employee mean. K, T, and U are excluded from the denominator.",
      reuseNote = EurostatReuseNote,
      notes =
        s"${row.laborNotes} Sector compensation is ${formatDecimal(compensation)} million euro and employees are ${formatDecimal(employees)} thousand persons.",
    )

  private def firmValue(sections: Vector[String]): BigDecimal =
    sections
      .map:
        case "A"   => GusActiveEnterprisesQ12026.agricultureA
        case "B"   => GusActiveEnterprisesQ12026.miningB
        case "C"   => GusActiveEnterprisesQ12026.manufacturingC
        case "D"   => GusActiveEnterprisesQ12026.utilitiesD
        case "E"   => GusActiveEnterprisesQ12026.waterWasteE
        case "F"   => GusActiveEnterprisesQ12026.constructionF
        case "G"   => GusActiveEnterprisesQ12026.tradeRepairG
        case "H"   => GusActiveEnterprisesQ12026.transportationH
        case "I"   => GusActiveEnterprisesQ12026.accommodationFoodI
        case "J"   => GusActiveEnterprisesQ12026.informationJ
        case "L"   => GusActiveEnterprisesQ12026.realEstateL
        case "M"   => GusActiveEnterprisesQ12026.professionalM
        case "N"   => GusActiveEnterprisesQ12026.administrativeN
        case "P"   => GusActiveEnterprisesQ12026.educationP
        case "Q"   => GusActiveEnterprisesQ12026.healthcareQ
        case "R"   => GusActiveEnterprisesQ12026.artsR
        case "S"   => GusActiveEnterprisesQ12026.otherServicesSExcluding94
        case other => throw new IllegalArgumentException(s"GUS active-enterprise bridge has no section value for $other")
      .sum

  private def employmentValue(sections: Vector[String]): BigDecimal =
    sections.map(section => Eurostat2024A64.section(section).employmentThousandPersons).sum

  private def employeesValue(sections: Vector[String]): BigDecimal =
    sections.map(section => Eurostat2024A64.section(section).employeesThousandPersons).sum

  private def compensationValue(sections: Vector[String]): BigDecimal =
    sections.map(section => Eurostat2024A64.section(section).compensationMEur).sum

  private[config] def outputStemFor(runtimeSector: String): String =
    ProductionSectorCrosswalk
      .mappingForSectorName(runtimeSector)
      .map(_.runtimeSector.outputStem)
      .getOrElse(sys.error(s"Missing crosswalk mapping for sector: $runtimeSector"))

  private def cells(row: Row): Vector[String] = Vector(
    row.rowType,
    row.metric,
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
    formatDecimal(row.numeratorValue),
    formatDecimal(row.denominatorValue),
    formatShare(row.empiricalValue),
    row.transformation,
    row.reuseNote,
    row.notes,
  )

  private def formatDecimal(value: BigDecimal): String =
    value.setScale(6, BigDecimal.RoundingMode.HALF_UP).toString

  private def formatShare(value: BigDecimal): String =
    value.setScale(9, BigDecimal.RoundingMode.HALF_UP).toString
