package com.boombustgroup.amorfati.engine

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.agents.Firm.CreditRejectionBreakdown
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.markets.{CorporateBondMarket, EquityMarket, FiscalBudget, GvcTrade, HousingMarket, OpenEconomy}
import com.boombustgroup.amorfati.engine.mechanisms.{Expectations, Macroprudential, SectoralMobility}
import com.boombustgroup.amorfati.types.*

/** Immutable snapshot of the entire simulation state at the end of one month.
  *
  * Fields with defaults (`bop`) are populated during the step pipeline and do
  * not need to be provided at init.
  */
case class World(
    inflation: Rate,                                                                  // CPI YoY inflation
    priceLevel: PriceIndex,                                                           // cumulative CPI index (base = 1.0)
    currentSigmas: Vector[Sigma],                                                     // per-sector σ (Arthur increasing returns)
    gov: FiscalBudget.GovState,                                                       // government budget & debt
    nbp: Nbp.State,                                                                   // central bank: rate, QE regime, monthly FX operations
    bankingSector: Banking.MarketState,                                               // banking macro state: interbank conditions, configs, term structure
    forex: OpenEconomy.ForexState,                                                    // EUR/PLN, exports, imports, trade balance
    bop: OpenEconomy.BopState = OpenEconomy.BopState.zero,                            // balance of payments: NFA, CA, KA, FDI
    householdMarket: HouseholdMarketState = HouseholdMarketState.zero,                // explicit household wage-market state used in hot paths
    social: SocialState,                                                              // JST, ZUS, PPK, demographics
    financialMarkets: FinancialMarketsState,                                          // financial-market memory; ownership lives in LedgerFinancialState
    external: ExternalState,                                                          // GVC, immigration, tourism
    real: RealState,                                                                  // housing, mobility, investment, energy, automation
    mechanisms: MechanismsState,                                                      // macropru, expectations, BFG, informal economy
    plumbing: MonetaryPlumbingState,                                                  // reserve corridor, standing facilities, interbank
    pipeline: PipelineState = PipelineState.zero(SimParams.DefaultSectorDefs.length), // inter-step demand / hiring / fiscal signals
    flows: FlowState,                                                                 // single-step derived flow outputs → SFC identities
    regionalWages: Map[Region, PLN] = Map.empty,                                      // per-region wage levels (NUTS-1)
):
  def seedIn: DecisionSignals = pipeline.seedIn

  def derivedTotalPopulation: Int =
    social.demographics.workingAgePop + social.demographics.retirees

  def laborForcePopulation: Int =
    social.demographics.workingAgePop.max(1)

  def unemploymentRate(employed: Int): Share =
    val laborForce = laborForcePopulation
    Share.One - Share.fraction(Math.max(0, Math.min(employed, laborForce)), laborForce)

  def cachedMonthlyGdpProxy: PLN = flows.monthlyGdpProxy

// ---------------------------------------------------------------------------
// Nested state types
// ---------------------------------------------------------------------------

/** Social security system and local government state. */
case class SocialState(
    jst: Jst.State,                                 // local government (JST): budget and debt; cash lives in LedgerFinancialState
    zus: SocialSecurity.ZusState,                   // ZUS: contributions, pensions, FUS balance
    nfz: SocialSecurity.NfzState,                   // NFZ: health insurance contributions, spending, balance
    ppk: SocialSecurity.PpkState,                   // PPK monthly contribution flow; holdings live in LedgerFinancialState
    demographics: SocialSecurity.DemographicsState, // working-age, retirees, monthly retirements
    earmarked: EarmarkedFunds.State,                // FP, PFRON, FGŚP
)
object SocialState:
  val zero: SocialState = SocialState(
    jst = Jst.State.zero,
    zus = SocialSecurity.ZusState.zero,
    nfz = SocialSecurity.NfzState.zero,
    ppk = SocialSecurity.PpkState.zero,
    demographics = SocialSecurity.DemographicsState.zero,
    earmarked = EarmarkedFunds.State.zero,
  )

/** Explicit household labor-market state carried outside aggregate caches. */
case class HouseholdMarketState(
    marketWage: PLN,
    reservationWage: PLN,
)
object HouseholdMarketState:
  val zero: HouseholdMarketState = HouseholdMarketState(PLN.Zero, PLN.Zero)

  def fromAggregates(agg: Household.Aggregates): HouseholdMarketState =
    HouseholdMarketState(
      marketWage = agg.marketWage,
      reservationWage = agg.reservationWage,
    )

/** Financial-market memory carried by `World`.
  *
  * This is deliberately not the ownership ledger. Keep market prices,
  * last-month diagnostics, and unsupported transition fields here; keep
  * ledger-contracted financial stocks in `LedgerFinancialState`.
  */
case class FinancialMarketsState(
    equity: EquityMarket.State,                // GPW market prices, returns, issuance, dividends
    corporateBonds: CorporateBondMarket.State, // Catalyst pricing and last-month diagnostics
    insurance: Insurance.State,                // monthly premium, claims, investment-income diagnostics
    nbfi: Nbfi.State,                          // monthly TFI/NBFI origination, defaults, deposit-drain diagnostics
    quasiFiscal: QuasiFiscal.State,            // quasi-fiscal monthly issuance/lending diagnostics
)
object FinancialMarketsState:
  val zero: FinancialMarketsState = FinancialMarketsState(
    equity = EquityMarket.zero,
    corporateBonds = CorporateBondMarket.zero,
    insurance = Insurance.State.zero,
    nbfi = Nbfi.State.zero,
    quasiFiscal = QuasiFiscal.State.zero,
  )

/** Structural external-sector state carried across steps. */
case class ExternalState(
    gvc: GvcTrade.State,                               // GVC: disruption, foreign prices, sector trade
    immigration: Immigration.State,                    // immigrant stock, monthly flows, remittances
    tourismSeasonalFactor: Multiplier = Multiplier.One, // seasonal multiplier (base = 1.0)
)
object ExternalState:
  val zero: ExternalState = ExternalState(
    gvc = GvcTrade.zero,
    immigration = Immigration.State.zero,
  )

/** Real economy state — physical and wealth structure. */
case class RealState(
    housing: HousingMarket.State,                   // price index, mortgage stock, regional sub-markets
    sectoralMobility: SectoralMobility.State,       // cross-sector hires, quits, mobility rate
    grossInvestment: PLN = PLN.Zero,                // aggregate GFCF by firms
    aggGreenInvestment: PLN = PLN.Zero,             // green investment (renewables, energy efficiency)
    aggGreenCapital: PLN = PLN.Zero,                // green capital stock across all firms
    etsPrice: Multiplier = Multiplier.Zero,         // EU ETS allowance price (EUR/tCO2)
    productivityIndex: Multiplier = Multiplier.One, // baseline real productivity trend multiplier
    automationRatio: Share = Share.Zero,            // share of Automated firms
    hybridRatio: Share = Share.Zero,                // share of Hybrid firms
)
object RealState:
  val zero: RealState = RealState(
    housing = HousingMarket.zero,
    sectoralMobility = SectoralMobility.zero,
  )

/** Macro-mechanism state — policies and endogenous phenomena carried across
  * steps.
  */
case class MechanismsState(
    macropru: Macroprudential.State,         // CCyB, credit-to-GDP gap
    expectations: Expectations.State,        // inflation forecast, credibility, forward guidance
    bfgFundBalance: PLN = PLN.Zero,          // cumulative BFG resolution fund
    informalCyclicalAdj: Share = Share.Zero, // smoothed cyclical shadow-economy adjustment
    nextTaxShadowShare: Share = Share.Zero,  // next-period smoothed tax-side shadow share
)
object MechanismsState:
  def zero(using SimParams): MechanismsState = MechanismsState(
    macropru = Macroprudential.State.zero,
    expectations = Expectations.initial,
  )

/** NBP monetary plumbing */
case class MonetaryPlumbingState(
    reserveInterestTotal: PLN = PLN.Zero, // NBP interest on required reserves
    standingFacilityNet: PLN = PLN.Zero,  // net standing facility income (deposit − Lombard)
    interbankInterestNet: PLN = PLN.Zero, // net interbank interest flows
    depositFacilityUsage: PLN = PLN.Zero, // voluntary reserves at NBP above minimum
    fofResidual: PLN = PLN.Zero,          // flow-of-funds residual
)
object MonetaryPlumbingState:
  val zero: MonetaryPlumbingState = MonetaryPlumbingState()

/** Explicit informational surface available at the start of a month.
  *
  * This is the persisted `pre` side of the timing contract. Same-month
  * operational artifacts belong on [[OperationalSignals]], not here.
  */
case class DecisionSignals(
    unemploymentRate: Share,
    inflation: Rate,
    expectedInflation: Rate,
    laggedHiringSlack: Share,
    startupAbsorptionRate: Share,
    sectorDemandMult: Vector[Multiplier],
    sectorDemandPressure: Vector[Multiplier],
    sectorHiringSignal: Vector[Multiplier],
)
object DecisionSignals:
  def zero(sectorCount: Int): DecisionSignals =
    DecisionSignals(
      unemploymentRate = Share.Zero,
      inflation = Rate.Zero,
      expectedInflation = Rate.Zero,
      laggedHiringSlack = Share.One,
      startupAbsorptionRate = Share.One,
      sectorDemandMult = Vector.fill(sectorCount)(Multiplier.One),
      sectorDemandPressure = Vector.fill(sectorCount)(Multiplier.One),
      sectorHiringSignal = Vector.fill(sectorCount)(Multiplier.One),
    )

/** Inter-step pipeline signals carried into the next month.
  *
  * This remains the persisted substrate, while [[World.seedIn]] exposes the
  * narrower decision-oriented surface consumed by timing-sensitive blocks.
  * Same-month operational consumers should prefer [[OperationalSignals]]
  * instead of reading directly from this structure.
  */
case class PipelineState(
    sectorDemandMult: Vector[Multiplier],       // per-sector demand multipliers from S4
    sectorDemandPressure: Vector[Multiplier],   // persistent sector demand/capacity pressure signal
    sectorHiringSignal: Vector[Multiplier],     // smoothed sector hiring signal used by firm labor planning
    fiscalRuleSeverity: Int = 0,                // 0=none, 1=SRW, 2=SGP, 3=Art86_55, 4=Art216_60
    govSpendingCutRatio: Share = Share.Zero,    // fraction of raw spending cut by fiscal rules
    laggedHiringSlack: Share = Share.One,       // month t labor-tightness signal carried into month t+1 macro decisions
    operationalHiringSlack: Share = Share.One,  // persisted snapshot of month-t labor compression for observability / compatibility
    startupAbsorptionRate: Share = Share.One,   // share of startup hiring targets filled across active startup firms
    laggedUnemploymentRate: Share = Share.Zero, // end-of-month unemployment extracted for next-month decisions
    laggedInflation: Rate = Rate.Zero,          // realized inflation lagged into next month
    laggedExpectedInflation: Rate = Rate.Zero,  // expected inflation lagged into next month
):
  def seedIn: DecisionSignals =
    DecisionSignals(
      unemploymentRate = laggedUnemploymentRate,
      inflation = laggedInflation,
      expectedInflation = laggedExpectedInflation,
      laggedHiringSlack = laggedHiringSlack,
      startupAbsorptionRate = startupAbsorptionRate,
      sectorDemandMult = sectorDemandMult,
      sectorDemandPressure = sectorDemandPressure,
      sectorHiringSignal = sectorHiringSignal,
    )

  def withDecisionSignals(signals: DecisionSignals): PipelineState =
    copy(
      sectorDemandMult = signals.sectorDemandMult,
      sectorDemandPressure = signals.sectorDemandPressure,
      sectorHiringSignal = signals.sectorHiringSignal,
      laggedHiringSlack = signals.laggedHiringSlack,
      startupAbsorptionRate = signals.startupAbsorptionRate,
      laggedUnemploymentRate = signals.unemploymentRate,
      laggedInflation = signals.inflation,
      laggedExpectedInflation = signals.expectedInflation,
    )

  def withSameMonthDiagnostics(
      operationalHiringSlack: Share,
      fiscalRuleSeverity: Int,
      govSpendingCutRatio: Share,
  ): PipelineState =
    copy(
      operationalHiringSlack = operationalHiringSlack,
      fiscalRuleSeverity = fiscalRuleSeverity,
      govSpendingCutRatio = govSpendingCutRatio,
    )

object PipelineState:
  def zero(sectorCount: Int): PipelineState =
    PipelineState(
      sectorDemandMult = Vector.fill(sectorCount)(Multiplier.One),
      sectorDemandPressure = Vector.fill(sectorCount)(Multiplier.One),
      sectorHiringSignal = Vector.fill(sectorCount)(Multiplier.One),
    )

  def bootstrap(
      sectorCount: Int,
      unemploymentRate: Share,
      inflation: Rate,
      expectedInflation: Rate,
  ): PipelineState =
    zero(sectorCount).copy(
      laggedUnemploymentRate = unemploymentRate,
      laggedInflation = inflation,
      laggedExpectedInflation = expectedInflation,
    )

  def zero(using p: SimParams): PipelineState = zero(p.sectorDefs.length)

/** Monthly banking-sector capital attribution used by Monte Carlo diagnostics.
  *
  * Amounts are aggregate sector PLN. Loss components are positive when they
  * reduce bank capital; retained income is positive when it increases capital.
  * Deposit bail-in is carried here as a resolution-adjacent diagnostic, but it
  * is not part of the bank-capital identity because it haircuts deposits rather
  * than equity capital.
  */
case class BankCapitalDiagnostics(
    openingCapital: PLN = PLN.Zero,         // aggregate bank capital at the start of the month
    closingCapital: PLN = PLN.Zero,         // aggregate bank capital after monthly banking settlement
    retainedIncome: PLN = PLN.Zero,         // retained ordinary bank income after profit-retention rule
    firmNplLoss: PLN = PLN.Zero,            // realized firm-loan credit loss net of recovery
    mortgageNplLoss: PLN = PLN.Zero,        // realized mortgage credit loss net of recovery
    consumerNplLoss: PLN = PLN.Zero,        // realized ordinary consumer-loan loss net of recovery
    corpBondDefaultLoss: PLN = PLN.Zero,    // bank-held corporate-bond default loss
    bfgLevy: PLN = PLN.Zero,                // monthly BFG levy paid by active banks
    unrealizedBondLoss: PLN = PLN.Zero,     // AFS government-bond mark-to-market capital hit
    htmRealizedLoss: PLN = PLN.Zero,        // HTM forced-reclassification realized loss
    eclProvisionChange: PLN = PLN.Zero,     // IFRS 9 provision increase, positive when capital is hit
    capitalDestruction: PLN = PLN.Zero,     // shareholder capital wiped when banks newly fail
    interbankContagionLoss: PLN = PLN.Zero, // failed-counterparty interbank exposure loss
    reconciliationResidual: PLN = PLN.Zero, // per-bank exactness patch; positive values add capital
    depositBailInLoss: PLN = PLN.Zero,      // depositor haircut from resolution, not equity-capital P&L
    newFailures: Int = 0,                   // banks newly marked failed during the month
):
  def delta: PLN = closingCapital - openingCapital

  def realizedCreditLoss: PLN =
    firmNplLoss + mortgageNplLoss + consumerNplLoss + corpBondDefaultLoss

  def expectedDelta: PLN =
    retainedIncome - realizedCreditLoss - bfgLevy - unrealizedBondLoss -
      htmRealizedLoss - eclProvisionChange - interbankContagionLoss - capitalDestruction

  /** Unexplained capital delta after ordinary waterfall terms and the per-bank
    * exactness patch. Values away from zero indicate a missing diagnostic term.
    */
  def waterfallResidual: PLN =
    delta - expectedDelta - reconciliationResidual

object BankCapitalDiagnostics:
  val zero: BankCapitalDiagnostics = BankCapitalDiagnostics()

/** Monthly bank-failure trigger diagnostics.
  *
  * Reason-code mapping for `firstNewReasonCode`: 0 = none, 1 = negative
  * capital, 2 = CAR breach, 3 = LCR/liquidity breach, 4 = all-failed resolution
  * fallback (stable legacy diagnostic; current all-failed semantics fail fast),
  * 5 = invariant mismatch.
  */
case class BankFailureDiagnostics(
    newNegativeCapital: Int = 0, // newly failed banks whose primary trigger was negative capital
    newCarBreach: Int = 0,       // newly failed banks whose primary trigger was the consecutive low-CAR rule
    newLiquidityBreach: Int = 0, // newly failed banks whose primary trigger was the LCR liquidity rule
    allFailedFallback: Int = 0,  // stable legacy column; should stay 0 because all-failed resolution now fails fast
    invariantViolation: Int = 0, // failure-event accounting mismatch, should remain zero
    firstNewReasonCode: Int = 0, // reason code for first new failure event in bank-id order, or 0 when none
    firstNewBankId: Int = -1,    // bank id for first new failure event, or -1 when none
)

object BankFailureDiagnostics:
  val zero: BankFailureDiagnostics = BankFailureDiagnostics()

  def fromEvents(events: Vector[Banking.FailureEvent], allFailedFallbackUsed: Boolean, invariantViolation: Int): BankFailureDiagnostics =
    val firstEvent = events.headOption
    BankFailureDiagnostics(
      newNegativeCapital = events.count(_.reason == Banking.BankFailureReason.NegativeCapital),
      newCarBreach = events.count(_.reason == Banking.BankFailureReason.CarBreach),
      newLiquidityBreach = events.count(_.reason == Banking.BankFailureReason.LiquidityBreach),
      allFailedFallback = if allFailedFallbackUsed then 1 else 0,
      invariantViolation = invariantViolation,
      firstNewReasonCode = firstEvent.map(_.reason.code).getOrElse(if allFailedFallbackUsed then Banking.BankFailureReason.AllFailedFallback.code else 0),
      firstNewBankId = firstEvent.map(_.bankId.toInt).getOrElse(-1),
    )

/** Monthly bank-resolution diagnostics for Monte Carlo timeseries.
  *
  * These fields are intentionally count-oriented and sit beside the richer
  * `BankFailure_*`, `BankCapital_*`, and `BankReconciliation_*` blocks so a run
  * can distinguish credit-demand weakness from bank-supply collapse.
  */
case class BankResolutionDiagnostics(
    activeBanks: Int = 0,                   // active bank rows after monthly failure/resolution
    failedBanks: Int = 0,                   // failed bank rows after monthly failure/resolution
    newFailures: Int = 0,                   // banks newly marked failed during the month
    bailInEvents: Int = 0,                  // distinct newly failed bank ids eligible for event-based bail-in
    resolvedBanks: Int = 0,                 // failed bank rows whose balance sheets were transferred by P&A
    allFailedFallback: Int = 0,             // stable legacy flag; current semantics fail fast before fallback
    bridgeRecapitalization: PLN = PLN.Zero, // explicit bridge/nationalization recap amount; zero until modeled
    invalidActiveBankInvariant: Int = 0,    // 1 when an active bank ends the month with negative capital
)

object BankResolutionDiagnostics:
  val zero: BankResolutionDiagnostics = BankResolutionDiagnostics()

  def fromState(
      banks: Vector[Banking.BankState],
      newFailures: Int,
      bailInEvents: Int,
      resolvedBanks: Int,
      allFailedFallbackUsed: Boolean,
      bridgeRecapitalization: PLN = PLN.Zero,
  ): BankResolutionDiagnostics =
    val activeBanks   = banks.count(bank => !bank.failed)
    val failedBanks   = banks.size - activeBanks
    val invalidActive = banks.exists(bank => !bank.failed && bank.capital < PLN.Zero)
    BankResolutionDiagnostics(
      activeBanks = activeBanks,
      failedBanks = failedBanks,
      newFailures = newFailures,
      bailInEvents = bailInEvents,
      resolvedBanks = resolvedBanks,
      allFailedFallback = if allFailedFallbackUsed then 1 else 0,
      bridgeRecapitalization = bridgeRecapitalization,
      invalidActiveBankInvariant = if invalidActive then 1 else 0,
    )

/** Diagnostics for the aggregate-exactness patch applied to one bank row.
  *
  * `capitalResidual` has the same sign as the patch: positive adds bank
  * capital, negative removes it. The materiality flag marks residuals whose
  * absolute size is at least 1 bp of the target bank's pre-patch capital.
  */
case class BankReconciliationDiagnostics(
    targetBankId: Int = -1,                        // bank id receiving the exactness patch, or -1 when no patch was applied
    capitalResidual: PLN = PLN.Zero,               // capital exactness patch applied to the target bank
    targetCapitalBefore: PLN = PLN.Zero,           // target-bank capital before the patch
    targetCapitalAfter: PLN = PLN.Zero,            // target-bank capital after the patch
    targetCarBefore: Multiplier = Multiplier.Zero, // target-bank CAR before the patch
    targetCarAfter: Multiplier = Multiplier.Zero,  // target-bank CAR after the patch
    residualToTargetCapital: Scalar = Scalar.Zero, // |capitalResidual| / max(|targetCapitalBefore|, PLN 1)
    materialResidual: Int = 0,                     // 1 when residualToTargetCapital is at least 1 bp
    crossedFailureThreshold: Int = 0,              // 1 when the patch alone moves the target bank into a failure trigger
    postResidualReasonCode: Int = 0,               // failure reason after the patch, or 0 when none
)

object BankReconciliationDiagnostics:
  private val MaterialResidualRatio: Scalar = Scalar.decimal(1, 4)

  val zero: BankReconciliationDiagnostics = BankReconciliationDiagnostics()

  def fromPatch(
      targetBankId: BankId,
      capitalResidual: PLN,
      targetCapitalBefore: PLN,
      targetCapitalAfter: PLN,
      targetCarBefore: Multiplier,
      targetCarAfter: Multiplier,
      reasonBefore: Option[Banking.BankFailureReason],
      reasonAfter: Option[Banking.BankFailureReason],
  ): BankReconciliationDiagnostics =
    val ratio    = capitalResidual.abs.ratioTo(targetCapitalBefore.abs.max(PLN(1)))
    val crossed  = reasonBefore.isEmpty && reasonAfter.nonEmpty
    val material = ratio >= MaterialResidualRatio
    BankReconciliationDiagnostics(
      targetBankId = targetBankId.toInt,
      capitalResidual = capitalResidual,
      targetCapitalBefore = targetCapitalBefore,
      targetCapitalAfter = targetCapitalAfter,
      targetCarBefore = targetCarBefore,
      targetCarAfter = targetCarAfter,
      residualToTargetCapital = ratio,
      materialResidual = if material then 1 else 0,
      crossedFailureThreshold = if crossed then 1 else 0,
      postResidualReasonCode = reasonAfter.map(_.code).getOrElse(0),
    )

/** Monthly IFRS 9 / ECL provisioning diagnostics.
  *
  * Allowances are accounting provisions implied by the S1/S2/S3 staging stock.
  * `excessAllowance` is the amount above an all-performing Stage-1 baseline.
  */
case class BankEclDiagnostics(
    openingAllowance: PLN = PLN.Zero,                // ECL allowance implied by opening bank staging
    closingAllowance: PLN = PLN.Zero,                // ECL allowance implied by closing bank staging
    baselineStage1Allowance: PLN = PLN.Zero,         // closing staged ECL book if all stayed at Stage 1
    excessAllowance: PLN = PLN.Zero,                 // closing allowance above the all-Stage-1 baseline
    migrationRate: Share = Share.Zero,               // macro-driven S1->S2 migration rate used this month
    gdpGrowthMonthly: Coefficient = Coefficient.Zero, // month-on-month GDP growth used by ECL staging
)

object BankEclDiagnostics:
  val zero: BankEclDiagnostics = BankEclDiagnostics()

/** Single-step derived flow outputs — recomputed each step, zero at init. Feed
  * into SFC identities and output columns.
  */
case class FlowState(
    monthlyGdpProxy: PLN = PLN.Zero,                                                        // cached monthly GDP proxy for diagnostics / output ratios
    sectorOutputs: Vector[PLN] = Vector.empty,                                              // nominal monthly output by schema sector
    ioFlows: PLN = PLN.Zero,                                                                // I-O intermediate payments between sectors
    fdiProfitShifting: PLN = PLN.Zero,                                                      // intangible imports booked abroad (profit shifting)
    fdiRepatriation: PLN = PLN.Zero,                                                        // dividend repatriation by foreign-owned firms
    fdiCitLoss: PLN = PLN.Zero,                                                             // CIT lost to profit shifting
    diasporaRemittanceInflow: PLN = PLN.Zero,                                               // diaspora remittance inflow
    tourismExport: PLN = PLN.Zero,                                                          // inbound tourism services export
    tourismImport: PLN = PLN.Zero,                                                          // outbound tourism services import
    aggInventoryStock: PLN = PLN.Zero,                                                      // aggregate firm inventory stock
    aggInventoryChange: PLN = PLN.Zero,                                                     // ΔInventories (enters GDP)
    aggEnergyCost: PLN = PLN.Zero,                                                          // aggregate energy + CO₂ costs
    automationTechCapex: PLN = PLN.Zero,                                                    // technology CAPEX for automation/hybrid upgrades
    automationTechImports: PLN = PLN.Zero,                                                  // import content of technology CAPEX
    automationTechLoans: PLN = PLN.Zero,                                                    // bank-credit component of technology financing
    automationUpgradeFailures: Int = 0,                                                     // implementation failures causing firm bankruptcy
    automationAiDebtTrap: Int = 0,                                                          // AI debt-trap bankruptcies
    automationNewFullAi: Int = 0,                                                           // new full-AI adopters
    automationNewHybrid: Int = 0,                                                           // new hybrid adopters
    firmNewLoans: PLN = PLN.Zero,                                                           // aggregate firm bank-loan origination, including bond reversion
    firmPrincipalRepaid: PLN = PLN.Zero,                                                    // aggregate scheduled firm-loan principal repayment
    firmGrossDefault: PLN = PLN.Zero,                                                       // gross firm-loan default volume from newly bankrupt firms
    firmNplLoss: PLN = PLN.Zero,                                                            // net firm-loan credit loss after recovery
    firmInvestmentCreditDemand: PLN = PLN.Zero,                                             // physical-investment bank credit requested
    firmInvestmentCreditApproved: PLN = PLN.Zero,                                           // physical-investment bank credit approved
    firmInvestmentCreditRejected: PLN = PLN.Zero,                                           // physical-investment bank credit rejected by bank supply
    firmTechCreditDemand: PLN = PLN.Zero,                                                   // technology-upgrade bank credit requested or bank-rejected
    firmTechCreditApproved: PLN = PLN.Zero,                                                 // technology-upgrade bank credit approved
    firmTechCreditRejected: PLN = PLN.Zero,                                                 // technology-upgrade bank credit rejected by bank supply
    firmTechSelectedCreditDemand: PLN = PLN.Zero,                                           // actual selected technology-upgrade bank credit requested
    firmTechSelectedCreditApproved: PLN = PLN.Zero,                                         // actual selected technology-upgrade bank credit approved
    firmTechSelectedCreditRejected: PLN = PLN.Zero,                                         // actual selected technology-upgrade bank credit rejected by bank supply
    firmTechCandidateCreditDemand: PLN = PLN.Zero,                                          // otherwise feasible technology-upgrade candidate bank credit requested
    firmTechCandidateCreditApproved: PLN = PLN.Zero,                                        // otherwise feasible technology-upgrade candidate bank credit approved
    firmTechCandidateCreditRejected: PLN = PLN.Zero,                                        // otherwise feasible technology-upgrade candidate rejected by bank supply
    firmCreditRejectedByReason: CreditRejectionBreakdown = CreditRejectionBreakdown.zero,   // firm bank-credit rejections by primary reason
    firmBirths: Int = 0,                                                                    // new firms (recycled + net new)
    firmDeaths: Int = 0,                                                                    // firms bankrupt this step
    netFirmBirths: Int = 0,                                                                 // net new firms appended to vector
    taxEvasionLoss: PLN = PLN.Zero,                                                         // tax lost to 4-channel evasion (CIT+VAT+PIT+excise)
    realizedTaxShadowShare: Share = Share.Zero,                                             // current-period realized aggregate tax-side shadow share
    bailInLoss: PLN = PLN.Zero,                                                             // bail-in deposit haircut imposed on bank creditors
    bfgLevyTotal: PLN = PLN.Zero,                                                           // BFG resolution levy from all banks
    bankCapital: BankCapitalDiagnostics = BankCapitalDiagnostics.zero,                      // monthly bank-capital waterfall diagnostics
    bankFailure: BankFailureDiagnostics = BankFailureDiagnostics.zero,                      // monthly bank-failure trigger diagnostics
    bankResolution: BankResolutionDiagnostics = BankResolutionDiagnostics.zero,             // monthly bank-resolution count diagnostics
    bankReconciliation: BankReconciliationDiagnostics = BankReconciliationDiagnostics.zero, // exactness-patch impact on target bank capital/CAR
    bankEcl: BankEclDiagnostics = BankEclDiagnostics.zero,                                  // IFRS 9 ECL allowance and staging diagnostics
)
object FlowState:
  val zero: FlowState = FlowState()
