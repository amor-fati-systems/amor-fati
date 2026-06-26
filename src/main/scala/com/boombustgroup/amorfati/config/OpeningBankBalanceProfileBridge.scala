package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.montecarlo.DelimitedTextFormat

import scala.math.BigDecimal.RoundingMode

object OpeningBankBalanceProfileBridge:

  final case class Row(
      rowType: String,
      bankId: String,
      bankName: String,
      runtimeBankName: String,
      sourceProvider: String,
      sourceUrl: String,
      datasetCode: String,
      vintage: String,
      accessedAt: String,
      frequency: String,
      unit: String,
      bridgeStatus: String,
      relationshipWeightPrior: Option[BigDecimal],
      depositsMPln: Option[BigDecimal],
      firmLoansMPln: Option[BigDecimal],
      consumerLoansMPln: Option[BigDecimal],
      mortgageLoansMPln: Option[BigDecimal],
      govBondsMPln: Option[BigDecimal],
      reservesMPln: Option[BigDecimal],
      corpBondsMPln: Option[BigDecimal],
      rwaMPln: Option[BigDecimal],
      ownFundsMPln: Option[BigDecimal],
      cet1Ratio: Option[BigDecimal],
      tier1Ratio: Option[BigDecimal],
      totalCapitalRatio: Option[BigDecimal],
      depositShare: Option[BigDecimal],
      firmLoanShare: Option[BigDecimal],
      consumerLoanShare: Option[BigDecimal],
      mortgageLoanShare: Option[BigDecimal],
      govBondShare: Option[BigDecimal],
      rwaShare: Option[BigDecimal],
      transformation: String,
      notes: String,
  ):
    require(rowType.trim.nonEmpty, "rowType must be non-empty")
    require(bankName.trim.nonEmpty, "bankName must be non-empty")
    require(runtimeBankName.trim.nonEmpty, "runtimeBankName must be non-empty")
    require(sourceProvider.trim.nonEmpty, "sourceProvider must be non-empty")
    require(sourceUrl.trim.nonEmpty, "sourceUrl must be non-empty")
    require(datasetCode.trim.nonEmpty, "datasetCode must be non-empty")
    require(vintage.trim.nonEmpty, "vintage must be non-empty")
    require(accessedAt.trim.nonEmpty, "accessedAt must be non-empty")
    require(frequency.trim.nonEmpty, "frequency must be non-empty")
    require(unit.trim.nonEmpty, "unit must be non-empty")
    require(bridgeStatus.trim.nonEmpty, "bridgeStatus must be non-empty")
    require(transformation.trim.nonEmpty, "transformation must be non-empty")
    require(notes.trim.nonEmpty, "notes must be non-empty")
    require(rowType == "sector_total" || bankId.trim.nonEmpty, s"bankId must be non-empty for rowType=$rowType")

    Vector(
      relationshipWeightPrior,
      depositsMPln,
      firmLoansMPln,
      consumerLoansMPln,
      mortgageLoansMPln,
      govBondsMPln,
      reservesMPln,
      corpBondsMPln,
      rwaMPln,
      ownFundsMPln,
      cet1Ratio,
      tier1Ratio,
      totalCapitalRatio,
      depositShare,
      firmLoanShare,
      consumerLoanShare,
      mortgageLoanShare,
      govBondShare,
      rwaShare,
    ).flatten.foreach: value =>
      require(value >= BigDecimal(0), s"numeric profile values must be non-negative: $value")

  val Header: Vector[String] = Vector(
    "row_type",
    "bank_id",
    "bank_name",
    "runtime_bank_name",
    "source_provider",
    "source_url",
    "dataset_code",
    "vintage",
    "accessed_at",
    "frequency",
    "unit",
    "bridge_status",
    "relationship_weight_prior",
    "deposits_m_pln",
    "firm_loans_m_pln",
    "consumer_loans_m_pln",
    "mortgage_loans_m_pln",
    "gov_bonds_m_pln",
    "reserves_m_pln",
    "corp_bonds_m_pln",
    "rwa_m_pln",
    "own_funds_m_pln",
    "cet1_ratio",
    "tier1_ratio",
    "total_capital_ratio",
    "deposit_share",
    "firm_loan_share",
    "consumer_loan_share",
    "mortgage_loan_share",
    "gov_bond_share",
    "rwa_share",
    "transformation",
    "notes",
  )

  private val KnfUrl          =
    "https://www.knf.gov.pl/publikacje_i_opracowania/dane_statystyczne?articleId=56224&p_id=18"
  private val NbpMfiUrl       =
    "https://nbp.pl/en/statistic-and-financial-reporting/monetary-and-financial-statistics/consolidated-balance-sheet-of-mfis/"
  private val SectorSourceUrl = s"$KnfUrl ; $NbpMfiUrl"

  private val AccessedAt = "2026-06-26"
  private val Unit       = "PLN million for stocks; decimal ratios and shares"

  object SectorTotals:
    val depositsMPln: BigDecimal      = BigDecimal("2542300")
    val firmLoansMPln: BigDecimal     = BigDecimal("557400")
    val consumerLoansMPln: BigDecimal = BigDecimal("225200")
    val mortgageLoansMPln: BigDecimal = BigDecimal("506300")
    val govBondsMPln: BigDecimal      = BigDecimal("400000")
    val reservesMPln: BigDecimal      = depositsMPln * BigDecimal("0.035")
    val ownFundsMPln: BigDecimal      = BigDecimal("199000")
    val totalCapitalRatio: BigDecimal = BigDecimal("0.211")
    val rwaMPln: BigDecimal           = BigDecimal("943127.962085308")

  final case class RuntimeBankPrior(
      bankId: String,
      runtimeBankName: String,
      sourceUrl: String,
      relationshipWeightPrior: BigDecimal,
  )

  private final case class NamedBankEvidence(
      sourceProvider: String,
      sourceUrl: String,
      datasetCode: String,
      vintage: String,
      depositsMPln: Option[BigDecimal] = None,
      firmLoansMPln: Option[BigDecimal] = None,
      consumerLoansMPln: Option[BigDecimal] = None,
      mortgageLoansMPln: Option[BigDecimal] = None,
      govBondsMPln: Option[BigDecimal] = None,
      reservesMPln: Option[BigDecimal] = None,
      corpBondsMPln: Option[BigDecimal] = None,
      rwaMPln: Option[BigDecimal] = None,
      ownFundsMPln: Option[BigDecimal] = None,
      cet1Ratio: Option[BigDecimal] = None,
      tier1Ratio: Option[BigDecimal] = None,
      totalCapitalRatio: Option[BigDecimal] = None,
      transformation: String,
      notes: String,
  )

  val RuntimeBankPriors: Vector[RuntimeBankPrior] = Vector(
    RuntimeBankPrior(
      "0",
      "PKO BP",
      "https://www.pkobp.pl/grupa-pko-banku-polskiego/relacje-inwestorskie/",
      BigDecimal("0.175"),
    ),
    RuntimeBankPrior(
      "1",
      "Pekao",
      "https://www.pekao.com.pl/relacje-inwestorskie/",
      BigDecimal("0.120"),
    ),
    RuntimeBankPrior(
      "2",
      "mBank",
      "https://www.mbank.pl/relacje-inwestorskie/",
      BigDecimal("0.085"),
    ),
    RuntimeBankPrior(
      "3",
      "ING BSK",
      "https://www.ing.pl/relacje-inwestorskie",
      BigDecimal("0.075"),
    ),
    RuntimeBankPrior(
      "4",
      "Santander",
      "https://www.erste.pl/relacje-inwestorskie",
      BigDecimal("0.070"),
    ),
    RuntimeBankPrior(
      "5",
      "BPS/Coop",
      "https://www.bankbps.pl/o-nas/raporty",
      BigDecimal("0.050"),
    ),
    RuntimeBankPrior(
      "6",
      "BNP Paribas",
      "https://www.bnpparibas.pl/relacje-inwestorskie",
      BigDecimal("0.085"),
    ),
    RuntimeBankPrior(
      "7",
      "Millennium",
      "https://www.bankmillennium.pl/o-banku/relacje-inwestorskie",
      BigDecimal("0.070"),
    ),
    RuntimeBankPrior(
      "8",
      "Alior",
      "https://www.aliorbank.pl/dodatkowe-informacje/relacje-inwestorskie.html",
      BigDecimal("0.055"),
    ),
    RuntimeBankPrior(
      "9",
      "Other banks",
      KnfUrl,
      BigDecimal("0.215"),
    ),
  )

  private val NamedBankEvidenceById: Map[String, NamedBankEvidence] = Map(
    "0" -> NamedBankEvidence(
      sourceProvider = "PKO BP Q3 2025 financial-data XLS",
      sourceUrl = "https://www.pkobp.pl/media_files/d13f0962-61ac-486c-9358-55a347a04bdc.xlsx",
      datasetCode = "PKO BP Group Q3 2025 financial data XLS: loans, deposits, capital adequacy",
      vintage = "2025-09-30 latest published disclosure found on PKO BP IR page",
      depositsMPln = Some(BigDecimal("440454")),
      firmLoansMPln = Some(BigDecimal("89238")),
      consumerLoansMPln = Some(BigDecimal("41143")),
      mortgageLoansMPln = Some(BigDecimal("130142")),
      rwaMPln = Some(BigDecimal("280625")),
      ownFundsMPln = Some(BigDecimal("50361")),
      cet1Ratio = Some(BigDecimal("0.163")),
      tier1Ratio = Some(BigDecimal("0.162943429844098")),
      totalCapitalRatio = Some(BigDecimal("0.17946013363028954")),
      transformation =
        "Customer liabilities use Q3 2025 key financial data. Firm, consumer and mortgage loans use the Q3 2025 loans worksheet. RWA is total capital requirement divided by 8%. Government-bond holdings remain blank because the XLS does not expose a central-government issuer split.",
      notes =
        "Source-backed partial named-bank evidence row. PKO BP's IR page did not expose a 2026 Q1 result file when accessed, so this row uses the nearest official bank-level disclosure rather than a relationship-weight proxy.",
    ),
    "1" -> NamedBankEvidence(
      sourceProvider = "Bank Pekao Q1 2026 interim report",
      sourceUrl =
        "https://www.pekao.com.pl/dam/jcr%3Ae3b63a74-5baf-4e18-a9df-0c78f64a31dd/Raport_Grupy_Kapita%C5%82owej_Banku_Pekao_SA_za_I_kwarta%C5%82_2026_roku.pdf",
      datasetCode = "Bank Pekao Group Q1 2026 report: customer liabilities, loan structure, securities note, capital adequacy",
      vintage = "2026-03-31 Q1 disclosure",
      depositsMPln = Some(BigDecimal("273849")),
      firmLoansMPln = Some(BigDecimal("119583")),
      consumerLoansMPln = Some(BigDecimal("6868")),
      mortgageLoansMPln = Some(BigDecimal("82130")),
      govBondsMPln = Some(BigDecimal("89919")),
      rwaMPln = Some(BigDecimal("181200")),
      ownFundsMPln = Some(BigDecimal("32014")),
      cet1Ratio = Some(BigDecimal("0.152")),
      tier1Ratio = Some(BigDecimal("0.152")),
      totalCapitalRatio = Some(BigDecimal("0.177")),
      transformation =
        "Customer liabilities use the Q1 2026 customer-liabilities note. Firm loans use corporate loans plus non-sovereign debt securities. Consumer loans are retail loans less property loans. Government bonds sum central-government securities across trading, amortised-cost and FVOCI books. RWA is total capital requirement divided by 8%.",
      notes =
        "Source-backed named-bank evidence row. It is not a runtime target until all runtime bank rows have complete mandatory stock coverage and are explicitly marked runtime-ready.",
    ),
    "2" -> NamedBankEvidence(
      sourceProvider = "mBank Q1 2026 investor presentation",
      sourceUrl = "https://www.mbank.pl/pdf/msp-korporacje/relacje-inwestorskie/wyniki-finansowe/2026/presentation-q1-pol.pdf",
      datasetCode = "mBank Group Q1 2026 presentation: balance sheet, loan/deposit structure, capital position",
      vintage = "2026-03-31 Q1 disclosure",
      depositsMPln = Some(BigDecimal("237097")),
      firmLoansMPln = Some(BigDecimal("64761")),
      consumerLoansMPln = Some(BigDecimal("25600")),
      mortgageLoansMPln = Some(BigDecimal("53455")),
      rwaMPln = Some(BigDecimal("135901")),
      ownFundsMPln = Some(BigDecimal("21688")),
      cet1Ratio = Some(BigDecimal("0.1303")),
      tier1Ratio = Some(BigDecimal("0.1414")),
      totalCapitalRatio = Some(BigDecimal("0.1596")),
      transformation =
        "Customer deposits and capital metrics use the Q1 2026 presentation. Firm, consumer and mortgage loans use the gross loan-portfolio structure. Government-bond holdings remain blank because the presentation exposes investment securities but not a central-government issuer split.",
      notes =
        "Source-backed partial named-bank evidence row. Do not infer the missing government-bond target from relationshipWeight or total investment securities.",
    ),
    "3" -> NamedBankEvidence(
      sourceProvider = "ING Bank Slaski Q1 2026 financial-data XLS",
      sourceUrl = "https://www.ing.pl/_fileserver/item/bcc3fz9",
      datasetCode = "ING Bank Slaski Q1 2026 financial data XLS: loan portfolio, deposit portfolio, TCR worksheet",
      vintage = "2026-03-31 Q1 disclosure",
      depositsMPln = Some(BigDecimal("242489")),
      firmLoansMPln = Some(BigDecimal("102940")),
      consumerLoansMPln = Some(BigDecimal("11432")),
      mortgageLoansMPln = Some(BigDecimal("70823")),
      rwaMPln = Some(BigDecimal("130337.5")),
      ownFundsMPln = Some(BigDecimal("20606")),
      cet1Ratio = Some(BigDecimal("0.1424")),
      tier1Ratio = Some(BigDecimal("0.1424")),
      totalCapitalRatio = Some(BigDecimal("0.1581")),
      transformation =
        "Customer deposits use the quarterly table. Firm, consumer and mortgage loans use the gross loan-portfolio worksheet. RWA is total capital requirement divided by 8%. Government-bond holdings remain blank because the XLS exposes debt securities without a central-government issuer split.",
      notes =
        "Source-backed partial named-bank evidence row. It remains evidence-only until government-bond and remaining named-bank target coverage are complete.",
    ),
    "4" -> NamedBankEvidence(
      sourceProvider = "Erste Bank Polska Q1 2026 report and presentation",
      sourceUrl =
        "https://www.erste.pl/regulation_file_server/time20260430065429/download?id=169388&lang=pl_PL ; https://www.erste.pl/regulation_file_server/time20260527134210/download?id=169387&lang=pl_PL",
      datasetCode = "Erste Bank Polska Group Q1 2026 report and presentation: loan structure, key volumes, government securities, Basel 3 capital and RWA",
      vintage = "2026-03-31 Q1 disclosure",
      depositsMPln = Some(BigDecimal("228000")),
      firmLoansMPln = Some(BigDecimal("91839")),
      consumerLoansMPln = Some(BigDecimal("23577.174")),
      mortgageLoansMPln = Some(BigDecimal("56553.797")),
      govBondsMPln = Some(BigDecimal("75499.412")),
      rwaMPln = Some(BigDecimal("139500")),
      ownFundsMPln = Some(BigDecimal("26100")),
      tier1Ratio = Some(BigDecimal("0.1842")),
      totalCapitalRatio = Some(BigDecimal("0.1872")),
      transformation =
        "Runtime bank name remains Santander, while the current public issuer disclosure is Erste Bank Polska. Deposits, RWA and own funds use the presentation. Firm loans use gross business/public-sector loans plus finance leases and other customer receivables. Consumer loans are retail customer receivables less residential-property loans. Government bonds sum State Treasury securities across trading, FVOCI and amortised-cost books.",
      notes =
        "Source-backed partial named-bank evidence row for the runtime Santander slot after the Erste rebrand. CET1 remains blank because the Q1 report and presentation expose Tier 1 but not a separate CET1 ratio.",
    ),
    "5" -> NamedBankEvidence(
      sourceProvider = "Bank BPS 2024 annual report",
      sourceUrl = "https://www.bankbps.pl/images/Dokumenty/bankowosc_elektroniczna/raport-roczny-2024.pdf",
      datasetCode = "Bank BPS annual report 2024: associated cooperative banks financial situation and loan-portfolio structure",
      vintage = "2024-12-31 annual disclosure",
      depositsMPln = Some(BigDecimal("120042.373")),
      firmLoansMPln = Some(BigDecimal("24803.3016")),
      ownFundsMPln = Some(BigDecimal("11985.070")),
      transformation =
        "Runtime BPS/Coop is mapped to cooperative banks associated with Bank BPS S.A. Deposits and own funds are converted from PLN thousand to PLN million. Firm loans use the gross loan portfolio of associated cooperative banks multiplied by companies, individual-entrepreneur and individual-farmer shares. Consumer and mortgage loans remain blank because the private-individual split is not a product split.",
      notes =
        "Source-backed partial row for the cooperative-bank runtime slot. This is not a 2026 Q1 bank-level balance sheet and should not be promoted to runtime-ready without a product-level cooperative-sector source bridge.",
    ),
    "6" -> NamedBankEvidence(
      sourceProvider = "BNP Paribas Bank Polska Q1 2026 presentation",
      sourceUrl = "https://www.bnpparibas.pl/_fileserver/item/1551435",
      datasetCode = "BNP Paribas Bank Polska Q1 2026 presentation: loan structure, deposits, own funds, RWA and capital ratios",
      vintage = "2026-03-31 Q1 disclosure",
      depositsMPln = Some(BigDecimal("137000")),
      firmLoansMPln = Some(BigDecimal("60794")),
      consumerLoansMPln = Some(BigDecimal("13288")),
      mortgageLoansMPln = Some(BigDecimal("21188")),
      rwaMPln = Some(BigDecimal("102900")),
      ownFundsMPln = Some(BigDecimal("17300")),
      tier1Ratio = Some(BigDecimal("0.1342")),
      totalCapitalRatio = Some(BigDecimal("0.1679")),
      transformation =
        "Customer deposits use the key balance-sheet slide. Firm loans use institutional loans. Consumer loans sum cash loans and other individual loans. Mortgage loans sum PLN and FX housing loans. RWA and own funds use the capital-position slide. Government-bond holdings remain blank because the presentation does not expose a central-government issuer split.",
      notes =
        "Source-backed partial named-bank evidence row. Institutional loans include the small public-sector loan line because the runtime bridge does not split public-sector loans separately.",
    ),
    "7" -> NamedBankEvidence(
      sourceProvider = "Bank Millennium Q1 2026 interim report",
      sourceUrl = "https://www.bankmillennium.pl/documents/10184/38150617/MILLENNIUM_1kw2026_PL.pdf/f7b55ea2-d9c9-f8b4-6500-4acffad973fd?t=1777353336572",
      datasetCode = "Bank Millennium Group Q1 2026 interim report: customer loans, deposits and capital ratios",
      vintage = "2026-03-31 Q1 disclosure",
      depositsMPln = Some(BigDecimal("134806")),
      firmLoansMPln = Some(BigDecimal("23310")),
      consumerLoansMPln = Some(BigDecimal("19174")),
      mortgageLoansMPln = Some(BigDecimal("35766")),
      govBondsMPln = Some(BigDecimal("34102.847")),
      rwaMPln = Some(BigDecimal("58386")),
      ownFundsMPln = Some(BigDecimal("10258")),
      cet1Ratio = Some(BigDecimal("0.1379")),
      tier1Ratio = Some(BigDecimal("0.1636")),
      totalCapitalRatio = Some(BigDecimal("0.1757")),
      transformation =
        "Customer deposits use the Q1 2026 liabilities table. Firm loans use loans to enterprises and the public sector. Mortgage loans sum PLN and FX mortgage loans. Consumer loans use the consumer-loan line. Government bonds sum State Treasury securities across trading and FVOCI books. RWA, own funds and capital ratios use the group capital-adequacy table.",
      notes =
        "Source-backed partial named-bank evidence row. Millennium's government-securities line includes securities issued by other EU governments, so the issuer perimeter is explicit but not purely domestic. Mortgage stock is net of the disclosed legal-risk allocation embedded in the reported loan portfolio.",
    ),
    "8" -> NamedBankEvidence(
      sourceProvider = "Alior Bank Q1 2026 report and presentation",
      sourceUrl =
        "https://www.aliorbank.pl/dam/jcr:f22e486e-8eab-41a6-9130-16157802c1ea/Raport%20Grupy%20Kapita%C5%82owej%20Alior%20Banku%20S.A.%20za%20I%20kwarta%C5%82%202026%20r.zip ; https://www.aliorbank.pl/dam/jcr:499f4349-b35c-42b3-a5ff-cfe30bcef290/Alior%20Bank%201Q%202026%20PL.pdf",
      datasetCode = "Alior Bank Group Q1 2026 report and presentation: customer liabilities, loan portfolio and capital adequacy",
      vintage = "2026-03-31 Q1 disclosure",
      depositsMPln = Some(BigDecimal("85413.751")),
      firmLoansMPln = Some(BigDecimal("24798")),
      consumerLoansMPln = Some(BigDecimal("20754.416")),
      mortgageLoansMPln = Some(BigDecimal("24094.288")),
      govBondsMPln = Some(BigDecimal("25645.757")),
      rwaMPln = Some(BigDecimal("61388.7")),
      ownFundsMPln = Some(BigDecimal("10957.875")),
      cet1Ratio = Some(BigDecimal("0.1785")),
      tier1Ratio = Some(BigDecimal("0.1785")),
      totalCapitalRatio = Some(BigDecimal("0.1785")),
      transformation =
        "Customer liabilities and retail loan products use the Q1 2026 report. Firm loans use the presentation's business-segment loan stock excluding reverse repo/BSB. Government bonds sum treasury bonds and treasury bills across FVOCI, FVTPL and amortised-cost books. RWA is derived from own funds divided by TCR, rounded to 0.1 mPLN because the source exposes own funds and capital ratios directly.",
      notes =
        "Source-backed partial named-bank evidence row. The business loan bridge excludes reverse repo/BSB so the stock better matches ordinary firm-credit exposure.",
    ),
  )

  private val NamedBankPriorIds      = RuntimeBankPriors.filterNot(_.runtimeBankName == "Other banks").map(_.bankId).toSet
  private val MissingNamedBankIds    = NamedBankPriorIds.diff(NamedBankEvidenceById.keySet)
  private val UnusedNamedEvidenceIds = NamedBankEvidenceById.keySet.diff(NamedBankPriorIds)

  require(
    MissingNamedBankIds.isEmpty && UnusedNamedEvidenceIds.isEmpty,
    s"Named-bank evidence must cover every runtime named bank exactly once; missing=${MissingNamedBankIds.toVector.sorted
        .mkString(",")}, unused=${UnusedNamedEvidenceIds.toVector.sorted.mkString(",")}",
  )

  private val SectorTotalRow: Row =
    Row(
      rowType = "sector_total",
      bankId = "",
      bankName = "Polish banking sector total",
      runtimeBankName = "ALL",
      sourceProvider = "KNF/NBP sector bridge from existing calibration register",
      sourceUrl = SectorSourceUrl,
      datasetCode = "KNF monthly banking data February 2026; NBP MFI government-securities bridge 2026-04-30; model opening mortgage bridge",
      vintage = "2026-04-30 model-start opening bridge",
      accessedAt = AccessedAt,
      frequency = "monthly/opening bridge",
      unit = Unit,
      bridgeStatus = "EMPIRICAL_SECTOR_TOTAL",
      relationshipWeightPrior = None,
      depositsMPln = Some(SectorTotals.depositsMPln),
      firmLoansMPln = Some(SectorTotals.firmLoansMPln),
      consumerLoansMPln = Some(SectorTotals.consumerLoansMPln),
      mortgageLoansMPln = Some(SectorTotals.mortgageLoansMPln),
      govBondsMPln = Some(SectorTotals.govBondsMPln),
      reservesMPln = Some(SectorTotals.reservesMPln),
      corpBondsMPln = None,
      rwaMPln = Some(SectorTotals.rwaMPln),
      ownFundsMPln = Some(SectorTotals.ownFundsMPln),
      cet1Ratio = None,
      tier1Ratio = None,
      totalCapitalRatio = Some(SectorTotals.totalCapitalRatio),
      depositShare = Some(BigDecimal(1)),
      firmLoanShare = Some(BigDecimal(1)),
      consumerLoanShare = Some(BigDecimal(1)),
      mortgageLoanShare = Some(BigDecimal(1)),
      govBondShare = Some(BigDecimal(1)),
      rwaShare = Some(BigDecimal(1)),
      transformation =
        "Sector stocks are the current opening calibration totals. RWA is implied from own_funds_m_pln divided by total_capital_ratio until direct sector RWA is extracted.",
      notes = "This row is an aggregate anchor only. It does not allocate named-bank balance sheets and does not change runtime initialization.",
    )

  private val NamedBankRows: Vector[Row] =
    RuntimeBankPriors
      .filterNot(_.runtimeBankName == "Other banks")
      .map: prior =>
        val evidence = NamedBankEvidenceById.getOrElse(prior.bankId, sys.error(s"Missing named-bank evidence for ${prior.runtimeBankName}"))
        sourceBackedNamedBankRow(prior, evidence)

  private def sourceBackedNamedBankRow(prior: RuntimeBankPrior, evidence: NamedBankEvidence): Row =
    Row(
      rowType = "named_bank",
      bankId = prior.bankId,
      bankName = prior.runtimeBankName,
      runtimeBankName = prior.runtimeBankName,
      sourceProvider = evidence.sourceProvider,
      sourceUrl = evidence.sourceUrl,
      datasetCode = evidence.datasetCode,
      vintage = evidence.vintage,
      accessedAt = AccessedAt,
      frequency = "quarterly bank-level disclosure",
      unit = Unit,
      bridgeStatus = "SOURCE_BACKED_PARTIAL_EVIDENCE",
      relationshipWeightPrior = Some(prior.relationshipWeightPrior),
      depositsMPln = evidence.depositsMPln,
      firmLoansMPln = evidence.firmLoansMPln,
      consumerLoansMPln = evidence.consumerLoansMPln,
      mortgageLoansMPln = evidence.mortgageLoansMPln,
      govBondsMPln = evidence.govBondsMPln,
      reservesMPln = evidence.reservesMPln,
      corpBondsMPln = evidence.corpBondsMPln,
      rwaMPln = evidence.rwaMPln,
      ownFundsMPln = evidence.ownFundsMPln,
      cet1Ratio = evidence.cet1Ratio,
      tier1Ratio = evidence.tier1Ratio,
      totalCapitalRatio = evidence.totalCapitalRatio,
      depositShare = shareOf(evidence.depositsMPln, SectorTotals.depositsMPln),
      firmLoanShare = shareOf(evidence.firmLoansMPln, SectorTotals.firmLoansMPln),
      consumerLoanShare = shareOf(evidence.consumerLoansMPln, SectorTotals.consumerLoansMPln),
      mortgageLoanShare = shareOf(evidence.mortgageLoansMPln, SectorTotals.mortgageLoansMPln),
      govBondShare = shareOf(evidence.govBondsMPln, SectorTotals.govBondsMPln),
      rwaShare = shareOf(evidence.rwaMPln, SectorTotals.rwaMPln),
      transformation = evidence.transformation,
      notes = evidence.notes,
    )

  private def shareOf(value: Option[BigDecimal], total: BigDecimal): Option[BigDecimal] =
    value.map(v => (v / total).setScale(9, RoundingMode.HALF_EVEN))

  private[config] def residualFromNamedValues(label: String, namedValues: Vector[Option[BigDecimal]], total: BigDecimal): Option[BigDecimal] =
    Option.when(namedValues.forall(_.isDefined)):
      val namedTotal = namedValues.flatten.sum
      val residual   = total - namedTotal
      if residual < BigDecimal(0) then
        throw new IllegalStateException(s"Named-bank $label coverage exceeds sector total: named=$namedTotal, sector=$total, residual=$residual")
      residual

  private def residualOf(label: String, values: NamedBankEvidence => Option[BigDecimal], total: BigDecimal): Option[BigDecimal] =
    val namedValues = NamedBankEvidenceById.values.toVector.map(values)
    residualFromNamedValues(label, namedValues, total)

  private val OtherBankResidualRow: Row =
    val prior = RuntimeBankPriors
      .find(_.runtimeBankName == "Other banks")
      .getOrElse:
        sys.error("Missing Other banks runtime prior")

    val depositResidual = residualOf("deposit", _.depositsMPln, SectorTotals.depositsMPln)

    Row(
      rowType = "residual_bank",
      bankId = prior.bankId,
      bankName = "Other banks",
      runtimeBankName = "Other banks",
      sourceProvider = "KNF sector total minus named-bank profile rows",
      sourceUrl = KnfUrl,
      datasetCode = "residual from sector total after named-bank extraction",
      vintage = "2026-04-30 model-start opening bridge",
      accessedAt = AccessedAt,
      frequency = "monthly/opening bridge",
      unit = Unit,
      bridgeStatus = "RESIDUAL_PARTIAL_SOURCE_COVERAGE",
      relationshipWeightPrior = Some(prior.relationshipWeightPrior),
      depositsMPln = depositResidual,
      firmLoansMPln = None,
      consumerLoansMPln = None,
      mortgageLoansMPln = None,
      govBondsMPln = None,
      reservesMPln = None,
      corpBondsMPln = None,
      rwaMPln = None,
      ownFundsMPln = None,
      cet1Ratio = None,
      tier1Ratio = None,
      totalCapitalRatio = None,
      depositShare = shareOf(depositResidual, SectorTotals.depositsMPln),
      firmLoanShare = None,
      consumerLoanShare = None,
      mortgageLoanShare = None,
      govBondShare = None,
      rwaShare = None,
      transformation =
        "For each stock numerator, compute sector total minus all named-bank values only when every named row has direct non-negative source coverage for that field; otherwise leave the field blank.",
      notes =
        "This row is intentionally not a plug from relationshipWeight. Deposit residual is available because every named bank has deposit evidence. Other stock fields remain blank while product-level or perimeter-consistent coverage is incomplete.",
    )

  val Rows: Vector[Row] =
    SectorTotalRow +: (NamedBankRows :+ OtherBankResidualRow)

  val tsvLines: Vector[String] =
    DelimitedTextFormat.Tsv.join(Header) +: Rows.map(row => DelimitedTextFormat.Tsv.join(rowCells(row)))

  private def rowCells(row: Row): Vector[String] = Vector(
    row.rowType,
    row.bankId,
    row.bankName,
    row.runtimeBankName,
    row.sourceProvider,
    row.sourceUrl,
    row.datasetCode,
    row.vintage,
    row.accessedAt,
    row.frequency,
    row.unit,
    row.bridgeStatus,
    cell(row.relationshipWeightPrior),
    cell(row.depositsMPln),
    cell(row.firmLoansMPln),
    cell(row.consumerLoansMPln),
    cell(row.mortgageLoansMPln),
    cell(row.govBondsMPln),
    cell(row.reservesMPln),
    cell(row.corpBondsMPln),
    cell(row.rwaMPln),
    cell(row.ownFundsMPln),
    cell(row.cet1Ratio),
    cell(row.tier1Ratio),
    cell(row.totalCapitalRatio),
    cell(row.depositShare),
    cell(row.firmLoanShare),
    cell(row.consumerLoanShare),
    cell(row.mortgageLoanShare),
    cell(row.govBondShare),
    cell(row.rwaShare),
    row.transformation,
    row.notes,
  )

  private def cell(value: Option[BigDecimal]): String =
    value.map(_.bigDecimal.stripTrailingZeros.toPlainString).getOrElse("")
