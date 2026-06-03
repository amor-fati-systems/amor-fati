package com.boombustgroup.amorfati.accounting.matrix

import com.boombustgroup.amorfati.engine.flows.FlowMechanism
import com.boombustgroup.amorfati.engine.ledger.RuntimeMechanismSurvivability
import com.boombustgroup.ledger.MechanismId

/** Reviewer-facing semantic coverage for every runtime-emitted flow mechanism.
  *
  * This registry deliberately composes existing contracts instead of replacing
  * them. `SfcMatrixRegistry` remains the source for mechanism labels and
  * survivability. `SfcSymbolicMatrices` remains the source for symbolic BSM/TFM
  * row membership. The rows here add the audit dimensions reviewers need when a
  * new mechanism appears: family, topology, asset class, SFC impact, and
  * coverage owner.
  */
object FlowMechanismSemantics:

  final case class FamilyDeclaration(
      mechanisms: Vector[MechanismId],
      flowFamily: String,
      expectedTopology: String,
      assetClass: String,
      sfcImpact: String,
      coverage: String,
  )

  final case class Row(
      mechanism: MechanismId,
      label: String,
      flowFamily: String,
      expectedTopology: String,
      assetClass: String,
      symbolicRows: Vector[String],
      survivability: RuntimeMechanismSurvivability.Classification,
      survivabilityNote: String,
      sfcImpact: String,
      coverage: String,
  )

  private def family(
      flowFamily: String,
      expectedTopology: String,
      assetClass: String,
      sfcImpact: String,
      coverage: String,
  )(mechanisms: MechanismId*): FamilyDeclaration =
    FamilyDeclaration(mechanisms.toVector, flowFamily, expectedTopology, assetClass, sfcImpact, coverage)

  private val declarations: Vector[FamilyDeclaration] = Vector(
    family(
      "Social insurance and public fund cash",
      "Household, firm, government, and named public-fund aggregate cash shells.",
      "Cash / public fund balances; execution-delta cash shells for payroll, benefits, spending, and subventions.",
      "Feeds public-fund cash rows, government budget cash, social contribution, transfer, and government-spending TFM rows.",
      "ZusFlowsSpec, NfzFlowsSpec, FlowSimulationNfzRuntimeSpec, PpkFlowsSpec, EarmarkedFlowsSpec, JstFlowsSpec, SfcMatrixEvidenceSpec.",
    )(
      FlowMechanism.ZusContribution,
      FlowMechanism.ZusPension,
      FlowMechanism.ZusGovSubvention,
      FlowMechanism.NfzContribution,
      FlowMechanism.NfzSpending,
      FlowMechanism.NfzGovSubvention,
      FlowMechanism.PpkContribution,
      FlowMechanism.FpContribution,
      FlowMechanism.FpSpending,
      FlowMechanism.FpGovSubvention,
      FlowMechanism.PfronContribution,
      FlowMechanism.PfronSpending,
      FlowMechanism.PfronGovSubvention,
      FlowMechanism.FgspContribution,
      FlowMechanism.FgspSpending,
      FlowMechanism.FgspGovSubvention,
      FlowMechanism.JstRevenue,
      FlowMechanism.JstSpending,
      FlowMechanism.JstGovSubvention,
    ),
    family(
      "Government budget cash",
      "Government treasury shell against household, firm, bank, foreign, and service-demand shells.",
      "Cash execution deltas plus public-debt service channels.",
      "Feeds GovBudgetCash, GovDebt, taxes, transfers, government-spending, and external/EU-funds evidence.",
      "GovBudgetFlowsSpec, SfcSpec, SfcMatrixEvidenceSpec, nightly health summary accounting residuals.",
    )(
      FlowMechanism.GovVatRevenue,
      FlowMechanism.GovExciseRevenue,
      FlowMechanism.GovCustomsDutyRevenue,
      FlowMechanism.GovPurchases,
      FlowMechanism.GovDebtService,
      FlowMechanism.GovCapitalInvestment,
      FlowMechanism.GovUnempBenefit,
      FlowMechanism.GovSocialTransfer,
      FlowMechanism.GovEuCofin,
    ),
    family(
      "Insurance premiums, claims, and investment income",
      "Household aggregate shell and insurance reserve/cash owner.",
      "LifeReserve, NonLifeReserve, and cash execution deltas.",
      "Feeds insurance reserve BSM completeness plus premiums, claims, and investment-income TFM evidence.",
      "InsuranceFlowsSpec, InsuranceSectorSpec, SfcMatrixEvidenceSpec.",
    )(
      FlowMechanism.InsLifePremium,
      FlowMechanism.InsNonLifePremium,
      FlowMechanism.InsLifeClaim,
      FlowMechanism.InsNonLifeClaim,
      FlowMechanism.InsInvestmentIncome,
    ),
    family(
      "Household operating income, spending, tax, and deposits",
      "Household aggregate execution shell against firms, banks, government, and housing shells.",
      "Cash, DemandDeposit, tax, income, consumption, rent, and deposit-interest execution deltas.",
      "Feeds consumption, wages/income, taxes, deposit-interest, deposit-stock, and household-flow diagnostics.",
      "HouseholdFlowsSpec, HouseholdFinancialEconomicsSpec, HouseholdSpec, SfcMatrixEvidenceSpec.",
    )(
      FlowMechanism.HhConsumption,
      FlowMechanism.HhRent,
      FlowMechanism.HhPit,
      FlowMechanism.HhDepositInterest,
      FlowMechanism.HhTotalIncome,
    ),
    family(
      "Household consumer credit and liquidity shortfall",
      "Household aggregate borrower shell against bank loan/cash shells.",
      "ConsumerLoan stock evidence plus cash execution deltas for origination, repayment, interest, default, and bridge financing.",
      "Feeds consumer-loan stock-flow rows, default/write-off channels, bank loss recognition, and household credit stress diagnostics.",
      "ConsumerCreditSpec, HouseholdFlowsSpec, HouseholdStepDsl aggregation checks, McTimeseriesSchemaSpec, HH-bank lead-lag and loan-origination diagnostics.",
    )(
      FlowMechanism.HhCcOrigination,
      FlowMechanism.HhLiquidityShortfallFinancing,
      FlowMechanism.HhCcDebtService,
      FlowMechanism.HhCcInterest,
      FlowMechanism.HhCcDefault,
    ),
    family(
      "Firm operating cash and ownership flows",
      "Firm aggregate execution shells against households, government, banks, foreign, equity, and investment settlement shells.",
      "Cash, Equity, tax, IO, capex, investment, interest, and FDI execution deltas.",
      "Feeds firm P&L, taxes, investment, dividends/equity, external income, and SFC matrix operating-flow rows.",
      "FirmFlowsSpec, FirmEconomicsSpec, McFirmDecisionTrace specs, SfcMatrixEvidenceSpec.",
    )(
      FlowMechanism.FirmCit,
      FlowMechanism.FirmInterestPaid,
      FlowMechanism.FirmCapex,
      FlowMechanism.FirmEquityIssuance,
      FlowMechanism.FirmIoPayment,
      FlowMechanism.FirmProfitShifting,
      FlowMechanism.FirmFdiRepatriation,
      FlowMechanism.FirmGrossInvestment,
    ),
    family(
      "Firm loan stock-flow",
      "Firm aggregate borrower shell against bank firm-loan asset/liability books.",
      "FirmLoan stock evidence plus cash execution deltas for origination, repayment, and NPL default.",
      "Feeds firm-loan BSM rows, loan origination/repayment/default TFM rows, and bank credit-loss channels.",
      "FirmFlowsSpec, FirmEconomicsSpec, BankCapitalSemanticsSpec, loan-origination diagnostics.",
    )(
      FlowMechanism.FirmLoanRepayment,
      FlowMechanism.FirmNewLoan,
      FlowMechanism.FirmNplDefault,
    ),
    family(
      "Deposit settlement and fund liquidity",
      "Firm, bank, fund, and quasi-fiscal aggregate settlement shells.",
      "DemandDeposit, TermDeposit, and cash settlement deltas.",
      "Feeds deposit-change TFM evidence and fund/quasi-fiscal cash diagnostics.",
      "InvestmentDepositSettlement flow tests, NbfiFlowsSpec, QuasiFiscalFlowsSpec, SfcMatrixEvidenceSpec.",
    )(
      FlowMechanism.InvestmentDepositSettlement,
      FlowMechanism.TfiDepositDrain,
      FlowMechanism.QuasiFiscalLendingDeposit,
      FlowMechanism.QuasiFiscalRepaymentDeposit,
    ),
    family(
      "NBFI private credit",
      "NBFI/fund lender shell against firm or household aggregate borrower shells.",
      "NbfiLoan stock evidence plus cash execution deltas for origination, repayment, and default.",
      "Feeds NBFI loan stock rows, private-credit renewal diagnostics, and loan repayment/default TFM evidence.",
      "NbfiFlowsSpec, private-credit renewal diagnostics, SfcMatrixEvidenceSpec.",
    )(
      FlowMechanism.NbfiOrigination,
      FlowMechanism.NbfiRepayment,
      FlowMechanism.NbfiDefault,
    ),
    family(
      "Quasi-fiscal bonds and NBP absorption",
      "Quasi-fiscal issuer against bank and NBP holder slots.",
      "QuasiFiscalBond supported persisted stock.",
      "Feeds quasi-fiscal bond BSM completeness, bond issuance/purchase TFM rows, and public-sector debt evidence.",
      "QuasiFiscalFlowsSpec, GovBondFlowsSpec, SfcMatrixEvidenceSpec.",
    )(
      FlowMechanism.QuasiFiscalBondIssuance,
      FlowMechanism.QuasiFiscalBondAmortization,
      FlowMechanism.QuasiFiscalNbpAbsorption,
      FlowMechanism.QuasiFiscalNbpBondAmortization,
    ),
    family(
      "Quasi-fiscal lending",
      "Quasi-fiscal fund lender shell against firm borrower and bank deposit settlement shells.",
      "NbfiLoan/quasi-fiscal loan portfolio evidence plus cash/deposit execution deltas.",
      "Feeds loan origination/repayment TFM rows and quasi-fiscal public-sector diagnostics.",
      "QuasiFiscalFlowsSpec, NbfiFlowsSpec, SfcMatrixEvidenceSpec.",
    )(
      FlowMechanism.QuasiFiscalLending,
      FlowMechanism.QuasiFiscalRepayment,
    ),
    family(
      "Equity dividends and revaluation",
      "Firm issuer and household, government, insurance, fund, foreign, and tax receiver shells or holder slots.",
      "Equity stock evidence, dividend cash deltas, and holder-aware revaluation evidence.",
      "Feeds equity BSM row, dividend TFM row, equity revaluation row, dividend-tax row, and equity other-change diagnostics.",
      "EquityFlowsSpec, EquityMarketSpec, EquityMarketPropertySpec, SfcMatrixEvidenceSpec.",
    )(
      FlowMechanism.EquityDomDividend,
      FlowMechanism.EquityForDividend,
      FlowMechanism.EquityDividendTax,
      FlowMechanism.EquityRevaluation,
      FlowMechanism.EquityGovDividend,
    ),
    family(
      "Corporate bond stock-flow",
      "Firm issuer against bank, insurance, fund, and aggregate coupon/default shells.",
      "CorpBond supported persisted stock plus coupon/default execution deltas.",
      "Feeds corporate-bond BSM rows, bond coupon/default TFM rows, and bank capital loss channels.",
      "CorpBondFlowsSpec, CorporateBondOwnershipSpec, BankingFlowsSpec, BankCapitalSemanticsSpec.",
    )(
      FlowMechanism.CorpBondCoupon,
      FlowMechanism.CorpBondDefault,
      FlowMechanism.CorpBondIssuance,
      FlowMechanism.CorpBondAmortization,
      FlowMechanism.BankCorpBondCoupon,
      FlowMechanism.BankCorpBondLoss,
    ),
    family(
      "Mortgage stock-flow",
      "Household aggregate borrower shell against bank mortgage book and principal settlement shell.",
      "MortgageLoan supported persisted stock plus interest/principal/default execution deltas.",
      "Feeds mortgage stock identity, loan repayment/default rows, mortgage interest row, and bank mortgage-loss channels.",
      "MortgageFlowsSpec, HouseholdFinancialEconomicsSpec, BankingHousingStage specs, SfcMatrixEvidenceSpec.",
    )(
      FlowMechanism.MortgageOrigination,
      FlowMechanism.MortgageRepayment,
      FlowMechanism.MortgageInterest,
      FlowMechanism.MortgageDefault,
    ),
    family(
      "External sector and BoP",
      "Domestic aggregate shells against foreign-sector shell and NBP/FX evidence.",
      "ForeignAsset, cash, current-account, capital-flow, remittance, FDI, tourism, and trade execution deltas.",
      "Feeds NFA, current-account closure, external TFM row, FX valuation, and empirical external-sector diagnostics.",
      "OpenEconFlowsSpec, ForeignRuntimeContractSpec, OpenEconEconomicsSpec, external-sector baseline diagnostics.",
    )(
      FlowMechanism.TradeExports,
      FlowMechanism.TradeImports,
      FlowMechanism.TourismExport,
      FlowMechanism.TourismImport,
      FlowMechanism.Fdi,
      FlowMechanism.PortfolioFlow,
      FlowMechanism.CarryTradeFlow,
      FlowMechanism.PrimaryIncome,
      FlowMechanism.EuFunds,
      FlowMechanism.DiasporaInflow,
      FlowMechanism.CapitalFlight,
      FlowMechanism.HhRemittance,
    ),
    family(
      "Bank capital P&L and loss waterfall",
      "Bank aggregate capital diagnostic state against loan, bond, BFG, NBP, and loss-recognition channels.",
      "Capital is persisted bank regulatory state but unsupported as transferable ledger-owned stock.",
      "Feeds BankCapital SFC identity, bank-capital waterfall residuals, BFG levy, NPL, bond-income/loss, provision, valuation, and NBP remittance channels.",
      "BankCapitalSemanticsSpec, BankingFlowsSpec, BankingEconomicsSpec, bank balance-sheet benchmark, nightly health summary residuals.",
    )(
      FlowMechanism.BankFirmInterest,
      FlowMechanism.BankGovBondIncome,
      FlowMechanism.BankNplLoss,
      FlowMechanism.BankMortgageNplLoss,
      FlowMechanism.BankCcNplLoss,
      FlowMechanism.BankBfgLevy,
      FlowMechanism.BankUnrealizedLoss,
      FlowMechanism.BankNbpRemittance,
    ),
    family(
      "Bank reserves, standing facility, interbank, FX, and bail-in",
      "Bank reserve/deposit shells against NBP, banks, and depositor-side claims.",
      "Reserve supported persisted stock plus StandingFacility, InterbankLoan, DemandDeposit, and FX/bail-in execution deltas.",
      "Feeds reserve/standing-facility/interbank SFC projection, bank deposits, NBP channels, bail-in depositor losses, and active-bank invariants.",
      "BankingFlowsSpec, NbpRuntimeContractSpec, MonetaryPlumbingSpec, BankCapitalSemanticsSpec, SfcSemanticProjection tests.",
    )(
      FlowMechanism.BankReserveInterest,
      FlowMechanism.BankStandingFacility,
      FlowMechanism.BankInterbankInterest,
      FlowMechanism.BankBailIn,
      FlowMechanism.NbpFxSettlement,
      FlowMechanism.BankStandingFacilityBackstop,
    ),
    family(
      "Government bond holder circuit",
      "Government issuer against bank, foreign, NBP, insurance, PPK, and TFI holder slots.",
      "GovBondHTM/GovBondAFS supported persisted stock.",
      "Feeds government-bond BSM completeness, bond issuance/purchase TFM rows, QE, foreign purchase, and holder clearing identities.",
      "GovBondFlowsSpec, TreasuryRuntimeContractSpec, GovernmentBondCircuit tests, SfcMatrixEvidenceSpec.",
    )(
      FlowMechanism.GovBondPrimaryMarket,
      FlowMechanism.GovBondForeignPurchase,
      FlowMechanism.NbpQeGovBondPurchase,
      FlowMechanism.PpkBondPurchase,
      FlowMechanism.InsuranceGovBondPurchase,
      FlowMechanism.TfiGovBondPurchase,
    ),
  )

  private val duplicateDeclarations =
    declarations
      .flatMap(_.mechanisms)
      .groupBy(identity)
      .collect { case (mechanism, rows) if rows.size > 1 => mechanism }

  require(
    duplicateDeclarations.isEmpty,
    s"FlowMechanismSemantics must declare each mechanism once: ${duplicateDeclarations.toVector.map(_.toInt).sorted.mkString(",")}.",
  )

  private val declaredMechanisms = declarations.flatMap(_.mechanisms).toSet

  require(
    declaredMechanisms == FlowMechanism.emittedRuntimeMechanisms,
    s"FlowMechanismSemantics must cover every emitted mechanism. missing=${FlowMechanism.emittedRuntimeMechanisms
        .diff(declaredMechanisms)
        .toVector
        .map(_.toInt)
        .sorted
        .mkString(",")} extra=${declaredMechanisms.diff(FlowMechanism.emittedRuntimeMechanisms).toVector.map(_.toInt).sorted.mkString(",")}",
  )

  private val declarationByMechanism: Map[MechanismId, FamilyDeclaration] =
    declarations.flatMap(row => row.mechanisms.map(_ -> row)).toMap

  private val symbolicRowsByMechanism: Map[MechanismId, Vector[String]] =
    SfcSymbolicMatrices.mappingRows
      .flatMap: row =>
        row.mechanisms.map: mechanism =>
          mechanism -> s"${row.matrix} / ${row.rowLabel}"
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2).distinct)
      .toMap

  val rows: Vector[Row] =
    FlowMechanism.allMechanisms
      .sortBy(_.toInt)
      .map: mechanism =>
        val registry      = SfcMatrixRegistry.mechanism(mechanism)
        val declaration   = declarationByMechanism(mechanism)
        val survivability = RuntimeMechanismSurvivability
          .declarationFor(mechanism)
          .getOrElse:
            throw new IllegalArgumentException(s"Missing survivability declaration for mechanism ${mechanism.toInt}")
        Row(
          mechanism = mechanism,
          label = registry.label,
          flowFamily = declaration.flowFamily,
          expectedTopology = declaration.expectedTopology,
          assetClass = declaration.assetClass,
          symbolicRows = symbolicRowsByMechanism.getOrElse(mechanism, Vector.empty),
          survivability = survivability.classification,
          survivabilityNote = survivability.note,
          sfcImpact = declaration.sfcImpact,
          coverage = declaration.coverage,
        )

end FlowMechanismSemantics
