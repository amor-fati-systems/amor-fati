package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.montecarlo.DelimitedTextFormat

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
      capitalRatioPrior: Option[BigDecimal],
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
      capitalRatioPrior,
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
    "capital_ratio_prior",
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

  private val AccessedAt = "2026-06-25"
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
      capitalRatioPrior: BigDecimal,
  )

  val RuntimeBankPriors: Vector[RuntimeBankPrior] = Vector(
    RuntimeBankPrior(
      "0",
      "PKO BP",
      "https://www.pkobp.pl/grupa-pko-banku-polskiego/relacje-inwestorskie/",
      BigDecimal("0.175"),
      BigDecimal("0.185"),
    ),
    RuntimeBankPrior(
      "1",
      "Pekao",
      "https://www.pekao.com.pl/relacje-inwestorskie/",
      BigDecimal("0.120"),
      BigDecimal("0.178"),
    ),
    RuntimeBankPrior(
      "2",
      "mBank",
      "https://www.mbank.pl/relacje-inwestorskie/",
      BigDecimal("0.085"),
      BigDecimal("0.169"),
    ),
    RuntimeBankPrior(
      "3",
      "ING BSK",
      "https://www.ing.pl/relacje-inwestorskie",
      BigDecimal("0.075"),
      BigDecimal("0.172"),
    ),
    RuntimeBankPrior(
      "4",
      "Santander",
      "https://www.santander.pl/relacje-inwestorskie",
      BigDecimal("0.070"),
      BigDecimal("0.170"),
    ),
    RuntimeBankPrior(
      "5",
      "BPS/Coop",
      "https://www.bankbps.pl/relacje-inwestorskie",
      BigDecimal("0.050"),
      BigDecimal("0.150"),
    ),
    RuntimeBankPrior(
      "6",
      "BNP Paribas",
      "https://www.bnpparibas.pl/relacje-inwestorskie",
      BigDecimal("0.085"),
      BigDecimal("0.165"),
    ),
    RuntimeBankPrior(
      "7",
      "Millennium",
      "https://www.bankmillennium.pl/o-banku/relacje-inwestorskie",
      BigDecimal("0.070"),
      BigDecimal("0.160"),
    ),
    RuntimeBankPrior(
      "8",
      "Alior",
      "https://www.aliorbank.pl/dodatkowe-informacje/relacje-inwestorskie.html",
      BigDecimal("0.055"),
      BigDecimal("0.150"),
    ),
    RuntimeBankPrior(
      "9",
      "Other banks",
      KnfUrl,
      BigDecimal("0.215"),
      BigDecimal("0.165"),
    ),
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
      capitalRatioPrior = None,
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
        Row(
          rowType = "named_bank",
          bankId = prior.bankId,
          bankName = prior.runtimeBankName,
          runtimeBankName = prior.runtimeBankName,
          sourceProvider = "bank public financial reports and Pillar 3 disclosures to be extracted",
          sourceUrl = prior.sourceUrl,
          datasetCode = "pending named-bank report extraction",
          vintage = "target: 2026 Q1 or nearest opening balance-sheet disclosure",
          accessedAt = AccessedAt,
          frequency = "quarterly/annual bank-level disclosure",
          unit = Unit,
          bridgeStatus = "PENDING_PUBLIC_REPORT_EXTRACTION",
          relationshipWeightPrior = Some(prior.relationshipWeightPrior),
          capitalRatioPrior = Some(prior.capitalRatioPrior),
          depositsMPln = None,
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
          depositShare = None,
          firmLoanShare = None,
          consumerLoanShare = None,
          mortgageLoanShare = None,
          govBondShare = None,
          rwaShare = None,
          transformation =
            "Extract bank-level stocks and capital metrics from public filings, map them to the runtime bank row, then compute metric-specific shares against the sector total.",
          notes = "The two populated prior columns expose current runtime calibration only; they are not treated as empirical balance-sheet values.",
        )

  private val OtherBankResidualRow: Row =
    val prior = RuntimeBankPriors
      .find(_.runtimeBankName == "Other banks")
      .getOrElse:
        sys.error("Missing Other banks runtime prior")

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
      bridgeStatus = "RESIDUAL_PENDING_NAMED_COVERAGE",
      relationshipWeightPrior = Some(prior.relationshipWeightPrior),
      capitalRatioPrior = Some(prior.capitalRatioPrior),
      depositsMPln = None,
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
      depositShare = None,
      firmLoanShare = None,
      consumerLoanShare = None,
      mortgageLoanShare = None,
      govBondShare = None,
      rwaShare = None,
      transformation =
        "For each stock or ratio numerator, compute sector total minus all named-bank values with direct source coverage; leave blank while named coverage is incomplete.",
      notes = "This row is intentionally not a plug from initMarketShare. Current runtime priors remain visible only to audit the replacement path.",
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
    cell(row.capitalRatioPrior),
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
