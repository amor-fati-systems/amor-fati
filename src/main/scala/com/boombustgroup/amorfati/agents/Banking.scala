package com.boombustgroup.amorfati.agents

import com.boombustgroup.amorfati.agents.banking.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.mechanisms.YieldCurve
import com.boombustgroup.amorfati.types.*

import com.boombustgroup.amorfati.random.RandomStream

object Banking:

  // ---------------------------------------------------------------------------
  // Public facade aliases
  // ---------------------------------------------------------------------------

  type BankCorpBondHoldings = BankId => PLN

  def noBankCorpBondHoldings: BankCorpBondHoldings =
    BankRegulatoryMetrics.noBankCorpBondHoldings

  def bankCorpBondHoldingsFromVector(holdings: Vector[PLN]): BankCorpBondHoldings =
    BankRegulatoryMetrics.bankCorpBondHoldingsFromVector(holdings)

  def nplRatio(totalLoans: PLN, nplAmount: PLN): Share =
    BankRegulatoryMetrics.nplRatio(totalLoans, nplAmount)

  // ---------------------------------------------------------------------------
  // ADT: BankStatus
  // ---------------------------------------------------------------------------

  /** Operational status of a bank.
    *
    *   - [[Active]] tracks consecutive months with CAR below regulatory
    *     minimum.
    *   - [[Failed]] records the month of failure (BFG resolution triggered).
    */
  enum BankStatus:
    case Active(consecutiveLowCar: Int)
    case Failed(month: ExecutionMonth)

  /** Primary reason for a bank newly entering failure/resolution state.
    *
    * Codes are stable TSV diagnostics: 0 is reserved for "none".
    */
  enum BankFailureReason(val code: Int):
    case NegativeCapital    extends BankFailureReason(1)
    case CarBreach          extends BankFailureReason(2)
    case LiquidityBreach    extends BankFailureReason(3)
    case AllFailedFallback  extends BankFailureReason(4)
    case InvariantViolation extends BankFailureReason(5)

  // ---------------------------------------------------------------------------
  // Aggregate balance sheet (sum over all per-bank BankStates)
  // ---------------------------------------------------------------------------

  /** Aggregate banking-sector RWA decomposition.
    *
    * Raw exposure fields preserve the regulatory perimeter used by
    * [[Aggregate.car]]. RWA component fields apply configured risk weights and
    * floors, making the CAR numerator and denominator auditable from exported
    * diagnostics.
    */
  case class AggregateRwaBreakdown(
      explicitAssetBase: PLN,
      firmLoanExposure: PLN,
      consumerLoanExposure: PLN,
      mortgageLoanExposure: PLN,
      corpBondExposure: PLN,
      interbankAssetExposure: PLN,
      govBondExposure: PLN,
      reserveExposure: PLN,
      firmLoanRwa: PLN,
      consumerLoanRwa: PLN,
      mortgageLoanRwa: PLN,
      corpBondRwa: PLN,
      interbankAssetRwa: PLN,
      sovereignRwa: PLN,
      reserveRwa: PLN,
      weightedExposureRwa: PLN,
      operationalRiskFloor: PLN,
      capitalBackstopFloor: PLN,
      totalRwa: PLN,
  )

  /** Aggregate banking-sector balance sheet — sum over all banking-sector rows.
    *
    * Pure DTO recomputed from bank operational state plus explicit financial
    * stock rows. Read-only snapshot consumed by output columns, SFC identities,
    * macro feedback loops (corporate bond absorption, insurance/NBFI asset
    * allocation), and government fiscal arithmetic. Ledger-owned corporate-bond
    * holdings are supplied by the caller; this aggregate is derived, never
    * written back.
    */
  case class Aggregate(
      totalLoans: PLN,       // Outstanding corporate loans (sum of per-bank `loans`)
      nplAmount: PLN,        // Non-performing corporate loan stock (KNF Stage 3)
      capital: PLN,          // Regulatory capital (Tier 1 + retained earnings)
      deposits: PLN,         // Total customer deposits (households + firms)
      afsBonds: PLN,         // AFS gov bond portfolio (marked to market)
      htmBonds: PLN,         // HTM gov bond portfolio (accrual only)
      consumerLoans: PLN,    // Outstanding unsecured household credit
      consumerNpl: PLN,      // Non-performing consumer loan stock
      corpBondHoldings: PLN, // Corporate bond portfolio — bank share only (default 30%, CORPBOND_BANK_SHARE)
      mortgageLoans: PLN,    // Mortgage loan assets mirrored from household liabilities
      reserves: PLN,         // NBP reserve assets
      interbankAssets: PLN,  // Positive interbank asset positions only
  ):
    /** Total government bond holdings (AFS + HTM). */
    def govBondHoldings: PLN = afsBonds + htmBonds

    /** Non-performing loan ratio: nplAmount / totalLoans. Returns Share.Zero
      * when loan book is empty.
      */
    def nplRatio: Share = Banking.nplRatio(totalLoans, nplAmount)

    /** Capital adequacy ratio: capital / risk-weighted assets using the
      * configured regulatory RWA perimeter.
      */
    def car(using SimParams): Multiplier =
      BankRegulatoryMetrics.capitalAdequacyRatio(capital, BankRiskWeightedAssets.aggregateExposure(this))

    /** Decomposes the aggregate CAR denominator into raw exposures, weighted
      * RWA components, and active floors.
      */
    def rwaBreakdown(using SimParams): AggregateRwaBreakdown =
      val exposure   = BankRiskWeightedAssets.aggregateExposure(this)
      val components = BankRiskWeightedAssets.components(exposure)
      AggregateRwaBreakdown(
        explicitAssetBase = exposure.explicitAssetBase,
        firmLoanExposure = exposure.firmLoans,
        consumerLoanExposure = exposure.consumerLoans,
        mortgageLoanExposure = exposure.mortgageLoans,
        corpBondExposure = exposure.corpBondHoldings,
        interbankAssetExposure = exposure.interbankAssets,
        govBondExposure = exposure.govBondHoldings,
        reserveExposure = exposure.reserves,
        firmLoanRwa = components.firmLoanRwa,
        consumerLoanRwa = components.consumerLoanRwa,
        mortgageLoanRwa = components.mortgageLoanRwa,
        corpBondRwa = components.corpBondRwa,
        interbankAssetRwa = components.interbankAssetRwa,
        sovereignRwa = components.sovereignRwa,
        reserveRwa = components.reserveRwa,
        weightedExposureRwa = components.weightedExposureRwa,
        operationalRiskFloor = components.operationalRiskFloor,
        capitalBackstopFloor = components.capitalBackstopFloor,
        totalRwa = components.total,
      )

  def aggregateFromBankStocks(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      bankCorpBondHoldings: BankCorpBondHoldings = noBankCorpBondHoldings,
  ): Aggregate =
    BankRegulatoryMetrics.aggregateFromBankStocks(banks, financialStocks, bankCorpBondHoldings)

  // ---------------------------------------------------------------------------
  // Monetary aggregates (diagnostic, not SFC-relevant)
  // ---------------------------------------------------------------------------

  /** Monetary aggregates — diagnostic, NBP-compatible definitions. M0 =
    * reserves at NBP (monetary base, excl. currency in circulation — model is
    * cashless) M1 = demand deposits only (overnight, NBP definition) M2 = M1 +
    * term deposits (deposits with agreed maturity) M3 = M2 + TFI AUM +
    * short-term corporate bonds (money market instruments)
    */
  case class MonetaryAggregates(
      m0: PLN,                     // monetary base: reserves at NBP
      m1: PLN,                     // narrow money: demand deposits
      m2: PLN,                     // intermediate: M1 + term deposits
      m3: PLN,                     // broad money: M2 + TFI AUM + corp bonds
      creditMultiplier: Multiplier, // m2 / m0 (broad deposit multiplier)
  )
  object MonetaryAggregates:
    val zero: MonetaryAggregates = MonetaryAggregates(PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, Multiplier.Zero)

    def computeFromBankStocks(
        banks: Vector[BankState],
        financialStocks: Vector[BankFinancialStocks],
        tfiAum: PLN,
        corpBondOutstanding: PLN,
    ): MonetaryAggregates =
      BankRegulatoryMetrics.computeMonetaryAggregates(banks, financialStocks, tfiAum, corpBondOutstanding)

  // ---------------------------------------------------------------------------
  // Named result types
  // ---------------------------------------------------------------------------

  /** Per-bank monetary flow with sector-wide total. */
  case class PerBankAmounts(perBank: Vector[PLN], total: PLN)

  /** Pair of operational bank state and ledger-owned bank financial stocks. */
  case class BankStockState(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks])

  /** Source profile for opening regulatory capital. */
  case class OpeningCapitalProfile(
      bankId: BankId,
      ownFunds: PLN,
  ):
    require(
      ownFunds >= PLN.Zero,
      s"OpeningCapitalProfile requires non-negative ownFunds for bank $bankId",
    )

  /** Auditable opening-capital computation result. */
  case class OpeningCapitalResult(
      bankId: BankId,
      riskWeightedAssets: PLN,
      capital: PLN,
      totalCapitalRatio: Multiplier,
  )

  /** Single newly failed bank with its primary trigger reason. */
  case class FailureEvent(bankId: BankId, month: ExecutionMonth, reason: BankFailureReason)

  /** Result of monthly failure check. */
  case class FailureCheckResult(banks: Vector[BankState], anyFailed: Boolean, events: Vector[FailureEvent] = Vector.empty)

  /** Result of BRRD bail-in on newly failed banks. */
  case class BailInResult(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], totalLoss: PLN)

  /** Result of BFG purchase-and-assumption resolution. */
  case class ResolutionResult(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      absorberId: BankId,
      bankCorpBondHoldings: Vector[PLN],
      allFailedFallbackUsed: Boolean = false,
      resolvedBankCount: Int = 0,
  )

  /** Product class of a new bank-credit exposure under approval.
    *
    * The product selects the projected regulatory exposure bucket.
    * Borrower-side underwriting remains outside the bank approval engine.
    */
  enum CreditProduct(val diagnosticCode: String):
    case FirmLoan     extends CreditProduct("firm-loan")
    case ConsumerLoan extends CreditProduct("consumer-loan")
    case MortgageLoan extends CreditProduct("mortgage-loan")

  /** Low-allocation, product-aware credit-supply boundary used by agents that
    * need a bank-side approval decision.
    */
  trait CreditSupply:
    def approve(bankId: BankId, product: CreditProduct, amount: PLN, rng: RandomStream): CreditApproval

  /** Rejection reason emitted by the bank-side approval engine. */
  enum CreditRejectionReason(val diagnosticCode: String):
    case FailedBank          extends CreditRejectionReason("failed-bank")
    case CapitalAdequacy     extends CreditRejectionReason("car")
    case CapitalBuffer       extends CreditRejectionReason("capital-buffer")
    case LiquidityCoverage   extends CreditRejectionReason("lcr")
    case StableFunding       extends CreditRejectionReason("nsfr")
    case PortfolioPreference extends CreditRejectionReason("portfolio-preference")

  /** Audit fields for the bank-side approval gates. */
  case class CreditApprovalAudit(
      rejectionReason: Option[CreditRejectionReason] = None,
      projectedCar: Option[Multiplier] = None,
      minCar: Option[Multiplier] = None,
      managementCarTarget: Option[Multiplier] = None,
      capitalThrottle: Option[Share] = None,
      lcr: Option[Multiplier] = None,
      lcrMin: Option[Multiplier] = None,
      nsfr: Option[Multiplier] = None,
      nsfrMin: Option[Multiplier] = None,
      portfolioChoice: Option[BankPortfolioChoiceAudit] = None,
  ):
    def isEmpty: Boolean =
      rejectionReason.isEmpty && projectedCar.isEmpty && minCar.isEmpty &&
        managementCarTarget.isEmpty && capitalThrottle.isEmpty && lcr.isEmpty &&
        lcrMin.isEmpty && nsfr.isEmpty && nsfrMin.isEmpty && portfolioChoice.isEmpty

    def orElse(fallback: CreditApprovalAudit): CreditApprovalAudit =
      if isEmpty then fallback else this

  object CreditApprovalAudit:
    val empty: CreditApprovalAudit = CreditApprovalAudit()

  /** Auditable result of a bank-credit approval check. `approvalRoll` is
    * defined only when balance-sheet constraints pass and the replay/audit draw
    * is sampled.
    */
  case class CreditApproval(
      product: CreditProduct,
      amount: PLN,
      approved: Boolean,
      approvalProbability: Option[Share],
      approvalRoll: Option[Share],
      audit: CreditApprovalAudit = CreditApprovalAudit.empty,
  )

  /** Rate and sovereign-yield context needed to evaluate the private-credit
    * portfolio wedge during approval.
    */
  case class CreditPortfolioContext(
      config: Config,
      refRate: Rate,
      bondYield: Rate,
  )

  /** Bank and macroprudential context for product-specific credit approval.
    *
    * Financial stocks stay outside this context because callers may pass
    * projected intra-month stocks without allocating a new approval request.
    */
  case class CreditApprovalContext(
      bank: BankState,
      ccyb: Multiplier,
      corpBondHoldings: PLN,
      portfolio: CreditPortfolioContext,
  )

  // ---------------------------------------------------------------------------
  // Config
  // ---------------------------------------------------------------------------

  /** Configuration for a single bank in the multi-bank system. */
  case class Config(
      id: BankId,                   // unique bank identifier (index into DefaultConfigs)
      name: String,                 // human-readable label (KNF registry)
      relationshipWeight: Share,    // borrower relationship sampling weight
      lendingSpread: Rate,          // bank-specific spread over base lending rate
      sectorAffinity: Vector[Share], // relative lending preference per sector
  )

  // ---------------------------------------------------------------------------
  // BankState
  // ---------------------------------------------------------------------------

  /** Ledger-contracted bank financial stocks owned by LedgerFinancialState. */
  case class BankFinancialStocks(
      totalDeposits: PLN,              // total customer deposits (HH + firms)
      firmLoan: PLN,                   // outstanding corporate loan book
      govBondAfs: PLN,                 // Available-for-Sale gov bonds
      govBondHtm: PLN,                 // Held-to-Maturity gov bonds
      reserve: PLN,                    // reserves held at NBP
      interbankLoan: PLN,              // net interbank position (positive = lender)
      demandDeposit: PLN,              // demand deposits
      termDeposit: PLN,                // term deposits
      consumerLoan: PLN,               // outstanding unsecured household credit
      mortgageLoan: PLN = PLN.Zero,    // mortgage loan assets mirrored from household liabilities
      bailedInDeposits: PLN = PLN.Zero, // deposits already processed by resolution bail-in
  )

  /** Operational state of an individual bank.
    *
    * Financial ownership stocks are intentionally absent; callers must carry
    * the aligned `BankFinancialStocks` row explicitly from
    * LedgerFinancialState.
    */
  case class BankState(
      id: BankId,                                          // unique bank identifier (index into banks vector)
      capital: PLN,                                        // regulatory capital (Tier 1 + retained earnings)
      nplAmount: PLN,                                      // non-performing corporate loan stock (KNF Stage 3)
      htmBookYield: Rate,                                  // weighted-average acquisition yield on HTM portfolio
      status: BankStatus,                                  // operational status (Active with CAR counter, or Failed)
      loansShort: PLN,                                     // short-term corporate-loan maturity bucket (< 1 year)
      loansMedium: PLN,                                    // medium-term corporate-loan maturity bucket (1–5 years)
      loansLong: PLN,                                      // long-term corporate-loan maturity bucket (> 5 years)
      consumerNpl: PLN,                                    // consumer credit NPL stock
      eclStaging: EclStaging.State = EclStaging.State.zero, // IFRS 9 ECL staging (Stage 1/2/3)
  ):

    /** Whether this bank has been resolved by BFG. */
    def failed: Boolean = status match
      case BankStatus.Failed(_) => true
      case _                    => false

    /** Month of failure, if this bank has already been resolved. */
    def failedMonth: Option[ExecutionMonth] = status match
      case BankStatus.Failed(m) => Some(m)
      case _                    => None

    /** Consecutive months with CAR below regulatory minimum, or 0 if failed. */
    def consecutiveLowCar: Int = status match
      case BankStatus.Active(c) => c
      case _                    => 0

  def govBondHoldings(stocks: BankFinancialStocks): PLN =
    BankRegulatoryMetrics.govBondHoldings(stocks)

  def nplRatio(bank: BankState, stocks: BankFinancialStocks): Share =
    BankRegulatoryMetrics.nplRatio(bank, stocks)

  def car(bank: BankState, stocks: BankFinancialStocks, corpBondHoldings: PLN)(using SimParams): Multiplier =
    BankRegulatoryMetrics.car(bank, stocks, corpBondHoldings)

  def riskWeightedAssets(stocks: BankFinancialStocks, corpBondHoldings: PLN)(using SimParams): PLN =
    BankRiskWeightedAssets.total(BankRiskWeightedAssets.exposure(stocks, corpBondHoldings))

  def openingCapitalFromProfile(
      profile: OpeningCapitalProfile,
      stocks: BankFinancialStocks,
      corpBondHoldings: PLN,
  )(using p: SimParams): OpeningCapitalResult =
    val rwa     = riskWeightedAssets(stocks, corpBondHoldings)
    val capital = profile.ownFunds
    val ratio   =
      if rwa > BankRiskWeightedAssets.MinBalanceThreshold then capital.ratioTo(rwa).toMultiplier
      else BankRiskWeightedAssets.SafeRatioFloor
    OpeningCapitalResult(profile.bankId, rwa, capital, ratio)

  def hqla(stocks: BankFinancialStocks): PLN =
    BankRegulatoryMetrics.hqla(stocks)

  def netCashOutflows(stocks: BankFinancialStocks)(using p: SimParams): PLN =
    BankRegulatoryMetrics.netCashOutflows(stocks)

  def lcr(stocks: BankFinancialStocks)(using p: SimParams): Multiplier =
    BankRegulatoryMetrics.lcr(stocks)

  def asf(bank: BankState, stocks: BankFinancialStocks): PLN =
    BankRegulatoryMetrics.asf(bank, stocks)

  def rsf(bank: BankState, stocks: BankFinancialStocks, corpBondHoldings: PLN): PLN =
    BankRegulatoryMetrics.rsf(bank, stocks, corpBondHoldings)

  def nsfr(bank: BankState, stocks: BankFinancialStocks, corpBondHoldings: PLN): Multiplier =
    BankRegulatoryMetrics.nsfr(bank, stocks, corpBondHoldings)

  /** Banking-sector state that belongs to macro/market runtime state, without
    * the explicit bank population.
    */
  case class MarketState(
      interbankRate: Rate,
      configs: Vector[Config],
      interbankCurve: Option[YieldCurve.State],
  )

  // ---------------------------------------------------------------------------
  // Default configs: Poland-facing bank archetypes plus residual Other banks,
  // Poland 2026-04-30 baseline.
  // ---------------------------------------------------------------------------

  val DefaultConfigs: Vector[Config] = BankDefaultConfigs.defaultConfigs

  // ---------------------------------------------------------------------------
  // Bank assignment
  // ---------------------------------------------------------------------------

  /** Assign a firm to a bank based on sector affinity and market share. */
  def assignBank(firmSector: SectorIdx, configs: Vector[Config], rng: RandomStream): BankId =
    BankCreditApproval.assignBank(firmSector, configs, rng)

  // ---------------------------------------------------------------------------
  // Rates
  // ---------------------------------------------------------------------------

  /** HH deposit rate (annual). Polish banks: NBP rate − spread, floored at
    * zero.
    */
  def hhDepositRate(refRate: Rate)(using p: SimParams): Rate =
    BankCreditApproval.hhDepositRate(refRate)

  /** Lending rate charged for a bank-credit product. Reflects credit risk,
    * capital pressure, and the portfolio-choice wedge against government bonds.
    * Failed banks get a flat penalty rate.
    */
  def lendingRate(
      bank: BankState,
      stocks: BankFinancialStocks,
      cfg: Config,
      refRate: Rate,
      bondYield: Rate,
      corpBondHoldings: PLN,
      ccyb: Multiplier,
      product: CreditProduct,
  )(using p: SimParams): Rate =
    BankCreditApproval.lendingRate(bank, stocks, cfg, refRate, bondYield, corpBondHoldings, ccyb, product)

  /** Interbank rate (WIBOR O/N proxy): blends credit stress (NPL) and liquidity
    * position (excess reserves). Under excess liquidity (post-QE, post-FX
    * intervention) rate falls toward deposit facility floor. Under scarce
    * liquidity + NPL stress, rate rises toward lombard ceiling.
    *
    * rate = depositRate + (1 − liquidityRatio) × creditStress × corridor
    */
  def interbankRate(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], refRate: Rate)(using p: SimParams): Rate =
    BankInterbankMarket.interbankRate(banks, financialStocks, refRate)

  // ---------------------------------------------------------------------------
  // Credit approval
  // ---------------------------------------------------------------------------

  /** Can this bank approve the product-specific credit amount? Checks projected
    * CAR and LCR/NSFR. Credit-risk pressure reaches approval through pricing,
    * ECL/provisioning, the resulting capital path, and the management-buffer
    * quantity throttle.
    */
  def canLend(
      context: CreditApprovalContext,
      stocks: BankFinancialStocks,
      product: CreditProduct,
      amount: PLN,
      rng: RandomStream,
  )(using p: SimParams): Boolean =
    BankCreditApproval.canLend(context, stocks, product, amount, rng)

  /** Evaluates audited bank-side approval for a product-specific credit
    * exposure.
    */
  def creditApproval(
      context: CreditApprovalContext,
      stocks: BankFinancialStocks,
      product: CreditProduct,
      amount: PLN,
      rng: RandomStream,
  )(using p: SimParams): CreditApproval =
    BankCreditApproval.creditApproval(context, stocks, product, amount, rng)

  // ---------------------------------------------------------------------------
  // Interbank market
  // ---------------------------------------------------------------------------

  /** Clear the interbank market: excess reserves → lender/borrower netting.
    * Hoarding factor [0,1] scales lending: 0 = full freeze, 1 = normal.
    */
  def clearInterbank(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      configs: Vector[Config],
      hoarding: Share = Share.One,
  )(using
      p: SimParams,
  ): BankStockState =
    BankInterbankMarket.clearInterbank(banks, financialStocks, configs, hoarding)

  // ---------------------------------------------------------------------------
  // Failure detection and resolution
  // ---------------------------------------------------------------------------

  /** Check for bank failures: negative capital immediately, CAR <
    * effectiveMinCar for 3 consecutive months, or LCR breach at 50% of minimum.
    * Already-failed banks pass through. If several triggers are true in the
    * same month, diagnostics report the primary reason in this priority order:
    * negative capital, CAR breach, liquidity breach.
    */
  def failureReason(
      bank: BankState,
      financialStocks: BankFinancialStocks,
      ccyb: Multiplier,
      bankCorpBondHoldings: BankCorpBondHoldings = noBankCorpBondHoldings,
  )(using p: SimParams): Option[BankFailureReason] =
    BankFailureResolution.failureReason(bank, financialStocks, ccyb, bankCorpBondHoldings)

  def checkFailures(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      month: ExecutionMonth, // execution month (recorded in BankStatus.Failed)
      enabled: Boolean,      // whether failure mechanism is active
      ccyb: Multiplier,      // countercyclical capital buffer
      bankCorpBondHoldings: BankCorpBondHoldings = noBankCorpBondHoldings,
  )(using p: SimParams): FailureCheckResult =
    BankFailureResolution.checkFailures(banks, financialStocks, month, enabled, ccyb, bankCorpBondHoldings)

  /** Failure check for repeated same-month banking stages.
    *
    * CAR persistence is derived from `carCounterBase`, so primary detection,
    * contagion, and reconciliation cannot count the same low-CAR month several
    * times.
    */
  def checkFailuresWithCarCounterBase(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      month: ExecutionMonth,
      enabled: Boolean,
      ccyb: Multiplier,
      bankCorpBondHoldings: BankCorpBondHoldings,
      carCounterBase: Vector[BankState],
  )(using p: SimParams): FailureCheckResult =
    BankFailureResolution.checkFailuresWithCarCounterBase(banks, financialStocks, month, enabled, ccyb, bankCorpBondHoldings, carCounterBase)

  /** Compute monthly BFG levy for all banks.
    *
    * Failed banks pay no levy. Active banks pay deposits × bfgLevyRate / 12.
    */
  def computeBfgLevy(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks])(using p: SimParams): PerBankAmounts =
    BankFailureResolution.computeBfgLevy(banks, financialStocks)

  /** Taxable asset base for the Polish tax on selected financial institutions.
    */
  def computePolishBankLevyTaxableAssets(bank: BankState, assetPerimeter: PolishBankLevyAssetPerimeter)(using p: SimParams): PLN =
    BankTaxation.computePolishBankLevyTaxableAssets(bank, assetPerimeter)

  /** Compute monthly Polish bank levy for all live bank rows. */
  def computePolishBankLevy(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      bankCorpBondHoldings: BankCorpBondHoldings,
  )(using p: SimParams): PerBankAmounts =
    BankTaxation.computePolishBankLevy(banks, financialStocks, bankCorpBondHoldings)

  /** Bail-in: haircut uninsured deposits only for banks that entered resolution
    * in the current event set. Deposits below bfgDepositGuarantee are
    * protected.
    */
  def applyBailIn(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], eligibleBankIds: Set[BankId])(using
      p: SimParams,
  ): BailInResult =
    BankFailureResolution.applyBailIn(banks, financialStocks, eligibleBankIds)

  /** BFG P&A resolution: transfer deposits, bonds, performing loans, consumer
    * loans from failed banks to the healthiest surviving bank.
    *
    * An all-failed banking sector is deliberately fail-fast. Reviving one
    * failed row as an active bridge bank would be a
    * recapitalization/nationalization mechanism, and that must be modeled
    * explicitly before it can enter the baseline resolution path.
    */
  def resolveFailures(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      bankCorpBondHoldings: Vector[PLN],
  )(using p: SimParams): ResolutionResult =
    BankFailureResolution.resolveFailures(banks, financialStocks, bankCorpBondHoldings)

  /** Find the healthiest surviving bank.
    *
    * Banks with no material risk-weighted assets get the CAR safe floor, so
    * they are excluded from CAR ranking while any risk-bearing survivor exists.
    * This prevents empty balance-sheet shells from attracting all
    * flight-to-safety deposits or failure-resolution assets. If no active bank
    * exists, callers must fail fast or invoke an explicit
    * recapitalization/shutdown mechanism instead of silently selecting a failed
    * row.
    */
  def healthiestBankId(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      bankCorpBondHoldings: BankCorpBondHoldings = noBankCorpBondHoldings,
  )(using p: SimParams): BankId =
    BankFailureResolution.healthiestBankId(banks, financialStocks, bankCorpBondHoldings)

  /** Reassign a firm/household from a failed bank to the healthiest surviving
    * bank.
    */
  def reassignBankId(
      currentBankId: BankId,
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      bankCorpBondHoldings: BankCorpBondHoldings = noBankCorpBondHoldings,
  )(using p: SimParams): BankId =
    BankFailureResolution.reassignBankId(currentBankId, banks, financialStocks, bankCorpBondHoldings)

  // ---------------------------------------------------------------------------
  // Bond allocation
  // ---------------------------------------------------------------------------

  /** Allocate new bond issuance to banks proportional to deposits using
    * `ledger.Distribute` for exact residual closure.
    */
  def allocateBondIssuance(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      issuance: PLN,
      currentYield: Rate,
  )(using p: SimParams): BankStockState =
    BankBondPortfolio.allocateBondIssuance(banks, financialStocks, issuance, currentYield)

  /** Allocate bond redemptions to banks proportional to deposits using
    * `ledger.Distribute` for exact residual closure.
    */
  def allocateBondRedemption(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      redemption: PLN,
      currentYield: Rate,
  )(using p: SimParams): BankStockState =
    BankBondPortfolio.allocateBondRedemption(banks, financialStocks, redemption, currentYield)

  /** Result of a bond sale from banks to a single buyer. */
  case class BondSaleResult(
      banks: Vector[BankState],
      financialStocks: Vector[BankFinancialStocks],
      actualSold: PLN,
      soldByBank: Vector[PLN],
  )

  /** Remove bonds from banks proportional to holdings, transferring to a buyer.
    * Returns updated banks and the actual PLN sold (may be less than requested
    * if banks lack sufficient holdings). Sells AFS first; spills into HTM. SFC
    * invariant: actualSold leaves banks = actualSold arrives at buyer.
    */
  def sellToBuyer(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], requested: PLN): BondSaleResult =
    BankBondPortfolio.sellToBuyer(banks, financialStocks, requested)

  // ---------------------------------------------------------------------------
  // HTM forced reclassification (interest rate risk)
  // ---------------------------------------------------------------------------

  /** Result of HTM forced reclassification across all banks. */
  case class HtmForcedSaleResult(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], totalRealizedLoss: PLN)

  /** Forcibly reclassify HTM bonds to AFS when LCR drops below
    * `htmForcedSaleThreshold × lcrMin`. On reclassification the hidden
    * mark-to-market loss is realized: realizedLoss = reclassified × duration ×
    * max(currentYield − htmBookYield, 0) Total `govBondHoldings` is unchanged —
    * only the AFS/HTM split moves.
    */
  def processHtmForcedSale(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], currentYield: Rate)(using
      p: SimParams,
  ): HtmForcedSaleResult =
    BankBondPortfolio.processHtmForcedSale(banks, financialStocks, currentYield)

  // ---------------------------------------------------------------------------
  // Per-bank capital PnL
  // ---------------------------------------------------------------------------

  /** All per-bank PnL components needed to compute the capital delta. */
  case class CapitalPnlInput(
      prevCapital: PLN,            // previous period capital
      nplLoss: PLN,                // corporate NPL loss (after recovery)
      mortgageNplLoss: PLN,        // mortgage default loss (bank share)
      consumerNplLoss: PLN,        // ordinary consumer-loan NPL loss (after recovery)
      corpBondDefaultLoss: PLN,    // corporate bond default loss (bank share)
      bfgLevy: PLN,                // BFG resolution fund levy
      polishBankLevyTax: PLN,      // Polish tax on selected financial institutions
      unrealizedBondLoss: PLN,     // mark-to-market loss on gov bond portfolio (interest rate risk channel)
      intIncome: PLN,              // interest income on corporate loans
      bondIncome: PLN,             // government bond coupon income
      depositInterest: PLN,        // interest paid on deposits (cost)
      reserveInterest: PLN,        // reserve remuneration from NBP
      standingFacilityIncome: PLN, // standing facility net income
      interbankInterest: PLN,      // interbank market interest
      mortgageInterestIncome: PLN, // mortgage interest income (bank share)
      consumerInterestIncome: PLN, // consumer credit interest income
      corpBondCoupon: PLN,         // corporate bond coupon income (bank share)
  )

  /** Result of per-bank capital PnL computation. */
  case class CapitalPnlOutput(
      newCapital: PLN, // updated capital after all PnL flows
  )

  /** Compute new bank capital from previous capital and monthly PnL flows.
    *
    * Pure function: losses reduce capital 1:1, income items are retained at
    * `profitRetention` rate (SimParams).
    */
  def computeCapitalDelta(in: CapitalPnlInput)(using p: SimParams): CapitalPnlOutput =
    BankCapitalWaterfall.computeCapitalDelta(in)

  // ---------------------------------------------------------------------------
  // Monetary plumbing
  // ---------------------------------------------------------------------------

  /** Monthly reserve interest for a single bank: reserves × refRate × mult / 12.
    */
  def reserveInterest(bank: BankState, stocks: BankFinancialStocks, refRate: Rate)(using p: SimParams): PLN =
    BankInterbankMarket.reserveInterest(bank, stocks, refRate)

  /** Reserve interest for all banks → per-bank amounts + sector total. */
  def computeReserveInterest(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], refRate: Rate)(using
      SimParams,
  ): PerBankAmounts =
    BankInterbankMarket.computeReserveInterest(banks, financialStocks, refRate)

  def computeReserveInterestFromBankStocks(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], refRate: Rate)(using
      SimParams,
  ): PerBankAmounts =
    BankInterbankMarket.computeReserveInterestFromBankStocks(banks, financialStocks, refRate)

  /** Standing facility flows (monthly): deposit rate for excess reserves,
    * lombard rate for borrowers. Always-on — the NBP corridor (ref ± 100 bps)
    * is structural, not optional.
    */
  def computeStandingFacilities(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], refRate: Rate)(using
      p: SimParams,
  ): PerBankAmounts =
    BankInterbankMarket.computeStandingFacilities(banks, financialStocks, refRate)

  def computeStandingFacilitiesFromBankStocks(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], refRate: Rate)(using
      p: SimParams,
  ): PerBankAmounts =
    BankInterbankMarket.computeStandingFacilitiesFromBankStocks(banks, financialStocks, refRate)

  /** Interbank interest flows (monthly). Net zero in aggregate (closed system).
    */
  def interbankInterestFlows(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], rate: Rate): PerBankAmounts =
    BankInterbankMarket.interbankInterestFlows(banks, financialStocks, rate)

  def interbankInterestFlowsFromBankStocks(banks: Vector[BankState], financialStocks: Vector[BankFinancialStocks], rate: Rate): PerBankAmounts =
    BankInterbankMarket.interbankInterestFlowsFromBankStocks(banks, financialStocks, rate)
