package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.{OperationalSignals, World}
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.engine.markets.{CalvoPricing, CorporateBondMarket, IntermediateMarket, LaborMarket}
import com.boombustgroup.amorfati.types.*

import com.boombustgroup.amorfati.random.RandomStream

/** Firm sector economics — production, I-O intermediate market, CAPEX
  * decisions, financing splits (equity/bonds/bank loans), labor matching, NPL
  * detection.
  *
  * `runStep` is the single runtime entry point; flow emission reads the
  * returned `StepOutput` directly.
  */
object FirmEconomics:

  // ---- Calibration constants ----
  private val BondRevertThreshold: Share = Share.decimal(1, 3) // minimum revert ratio to trigger bond-to-loan reversion
  private type CreditRejectionBreakdown = Firm.CreditRejectionBreakdown

  // ---- Accumulated flows (monoid on PLN) ----

  /** Accumulated monetary flows from firm processing — one per firm, reduced
    * via `+`.
    */
  private case class FirmFlows(
      tax: PLN,                         // CIT paid (after informal evasion)
      capex: PLN,                       // technology upgrade CAPEX (AI or hybrid)
      techImp: PLN,                     // import content of CAPEX (forex demand)
      newLoans: PLN,                    // new bank loans net of equity/bond splits
      equityIssuance: PLN,              // GPW equity raised this month
      grossInvestment: PLN,             // physical capital investment
      bondIssuance: PLN,                // corporate bond issuance (pre-absorption)
      profitShifting: PLN,              // FDI profit shifting outflow
      fdiRepatriation: PLN,             // FDI dividend repatriation outflow
      inventoryChange: PLN,             // net inventory change (+ accumulation, - drawdown)
      citEvasion: PLN,                  // CIT evaded via informal economy
      energyCost: PLN,                  // total energy + ETS cost
      greenInvestment: PLN,             // green capital investment
      principalRepaid: PLN,             // firm loan principal repaid
      investmentCreditDemand: PLN,      // physical-investment bank credit requested
      investmentCreditApproved: PLN,    // physical-investment bank credit approved
      investmentCreditRejected: PLN,    // physical-investment bank credit rejected by bank supply
      techCreditDemand: PLN,            // technology-upgrade bank credit requested or bank-rejected
      techCreditApproved: PLN,          // technology-upgrade bank credit approved
      techCreditRejected: PLN,          // technology-upgrade bank credit rejected by bank supply
      techSelectedCreditDemand: PLN,    // actual selected technology-upgrade bank credit requested
      techSelectedCreditApproved: PLN,  // actual selected technology-upgrade bank credit approved
      techSelectedCreditRejected: PLN,  // actual selected technology-upgrade bank credit rejected by bank supply
      techCandidateCreditRejected: PLN, // otherwise feasible technology-upgrade candidate rejected by bank supply
      creditRejectedByReason: CreditRejectionBreakdown,
  ):
    def +(o: FirmFlows): FirmFlows = FirmFlows(
      tax + o.tax,
      capex + o.capex,
      techImp + o.techImp,
      newLoans + o.newLoans,
      equityIssuance + o.equityIssuance,
      grossInvestment + o.grossInvestment,
      bondIssuance + o.bondIssuance,
      profitShifting + o.profitShifting,
      fdiRepatriation + o.fdiRepatriation,
      inventoryChange + o.inventoryChange,
      citEvasion + o.citEvasion,
      energyCost + o.energyCost,
      greenInvestment + o.greenInvestment,
      principalRepaid + o.principalRepaid,
      investmentCreditDemand + o.investmentCreditDemand,
      investmentCreditApproved + o.investmentCreditApproved,
      investmentCreditRejected + o.investmentCreditRejected,
      techCreditDemand + o.techCreditDemand,
      techCreditApproved + o.techCreditApproved,
      techCreditRejected + o.techCreditRejected,
      techSelectedCreditDemand + o.techSelectedCreditDemand,
      techSelectedCreditApproved + o.techSelectedCreditApproved,
      techSelectedCreditRejected + o.techSelectedCreditRejected,
      techCandidateCreditRejected + o.techCandidateCreditRejected,
      creditRejectedByReason + o.creditRejectedByReason,
    )

  private object FirmFlows:
    val zero: FirmFlows = FirmFlows(
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      PLN.Zero,
      Firm.CreditRejectionBreakdown.zero,
    )

  // ---- Internal phase result types ----

  /** Per-bank lending rates and credit approval, shared across phases. */
  private case class LendingConditions(
      firmWorld: World,                                      // world with current-month wages for firm decision-making
      executionMonth: ExecutionMonth,                        // realized month currently being executed
      operationalSignals: OperationalSignals,                // same-month demand / labor surface for incumbent firms
      rates: Vector[Rate],                                   // per-bank lending rates
      bankCreditDecision: (Int, PLN) => Firm.CreditDecision, // credit approval audit: (bankId, amount) => result
      nBanks: Int,                                           // number of banks in multi-bank system
  )

  /** Per-firm processing outcome — immutable, one per firm. */
  private case class FirmOutcome(
      firm: Firm.State,                         // updated firm state after decision + financing
      financialStocks: Firm.FinancialStocks,    // closing firm cash/loan/equity stocks after financing split
      flows: FirmFlows,                         // monetary flows from this firm
      realizedPostTaxProfit: PLN,               // realized monthly profit after tax, floored at zero
      bankId: BankId,                           // relationship bank (for per-bank aggregation)
      finalLoan: PLN,                           // bank loan after equity/bond splits
      bondAmt: PLN,                             // corporate bond issuance (pre-absorption)
      techBankLoan: PLN,                        // technology-loan component left as bank credit after financing split
      techBondAmt: PLN,                         // technology-loan component shifted into corporate bonds
      automationUpgradeFailure: Boolean,        // firm became bankrupt from AI/hybrid implementation failure
      automationAiDebtTrap: Boolean,            // firm became bankrupt from automated-firm debt trap
      automationNewFullAi: Boolean,             // firm newly entered full-AI automation this month
      automationNewHybrid: Boolean,             // firm newly entered hybrid automation this month
      principalRepaid: PLN,                     // scheduled loan principal repaid this month
      decisionTrace: Option[Firm.DecisionTrace], // optional auditable firm decision trace
  )

  /** Result of per-firm processing (phase 2). */
  private case class FirmProcessingResult(
      outcomes: Vector[FirmOutcome],     // per-firm immutable outcomes
      flows: FirmFlows,                  // aggregate flows (monoid sum)
      firmBondAmounts: Map[FirmId, PLN], // per-firm bond issuance for reversion
      traceDecisions: Boolean,           // whether per-firm decision traces were requested for this step
  )

  private case class TraceLoanComponents(techCreditAmount: PLN, investmentCreditAmount: Option[PLN])

  /** Result of bond absorption (phase 3). */
  private case class BondAbsorptionResult(
      firms: Vector[Firm.State],                     // firms after bond reversion (unsold → bank loans)
      financialStocks: Vector[Firm.FinancialStocks], // firm stocks after unsold bonds revert to bank loans
      ledgerFinancialState: LedgerFinancialState,    // ledger with issuer-side corporate bond balances after accepted issuance
      sumNewLoans: PLN,                              // total new bank loans incl. reversion
      corpBondAbsorption: Share,                     // Catalyst absorption ratio (0-1)
      actualBondIssuance: PLN,                       // bonds issued after absorption constraint
      bondReversionByFirm: Map[FirmId, PLN],         // unsold bonds reverted to relationship-bank loans
  )

  /** Financing split: how a firm's CAPEX loan is divided across three channels.
    */
  private[amorfati] case class FinancingChannelAmounts(
      bankLoan: PLN,     // residual bank-loan amount after equity and bond channels
      equity: PLN,       // GPW equity issuance substituted for requested debt
      bonds: PLN,        // Catalyst bond issuance substituted for requested debt
      techBankLoan: PLN, // technology-loan component remaining as bank credit
      techBonds: PLN,    // technology-loan component shifted into corporate bonds
  )

  private case class FinancingSplit(
      bankLoan: PLN,     // remainder after equity and bond channels
      equity: PLN,       // GPW equity issuance (large firms only)
      bonds: PLN,        // Catalyst corporate bonds (medium+ firms only)
      techBankLoan: PLN, // technology-loan component left in bank credit
      techBonds: PLN,    // technology-loan component shifted into corporate bonds
      firm: Firm.State,  // firm with updated bank debt/equity; corporate bonds stay in LedgerFinancialState
      financialStocks: Firm.FinancialStocks,
  )

  /** Result of the intermediate market phase with ledger-facing firm balances
    * updated by the same cash deltas as firm states.
    */
  private case class IntermediateResult(
      firms: Vector[Firm.State],                     // firms after intermediate-market production state updates
      financialStocks: Vector[Firm.FinancialStocks], // firm stocks after I-O cash adjustments
      totalPaid: PLN,                                // total intermediate goods payments
  )

  /** Result of NPL and interest computation (phase 6). */
  private case class NplResult(
      nplNew: PLN,                      // new NPL volume from bankruptcies
      nplLoss: PLN,                     // NPL loss net of recovery
      totalBondDefault: PLN,            // bond default from bankrupt firms
      firmDeaths: Int,                  // count of newly bankrupt firms
      intIncome: PLN,                   // aggregate bank interest income
      perBankNplDebt: Vector[PLN],      // NPL debt by bank index
      perBankIntIncome: Vector[PLN],    // interest income by bank index
      perBankWorkers: Vector[Int],      // worker count by bank index
      defaultedBondFirmIds: Set[FirmId], // firms whose issuer liability defaulted this month
  )

  // ---- Public I/O types ----

  /** Full step output — all fields previously in FirmProcessingStep.Output. */
  case class StepOutput(
      ioFirms: Vector[Firm.State],                // firms after I-O intermediate market
      households: Vector[Household.State],        // households after labor matching + immigration
      sumTax: PLN,                                // aggregate CIT paid
      sumCapex: PLN,                              // aggregate technology CAPEX
      sumTechImp: PLN,                            // aggregate technology imports
      sumNewLoans: PLN,                           // aggregate new bank loans (incl. bond reversion)
      automationTechCapex: PLN,                   // technology CAPEX exposed as generic automation diagnostic
      automationTechImports: PLN,                 // import content of technology CAPEX
      automationTechLoans: PLN,                   // technology bank-credit creation after financing split and bond reversion
      automationUpgradeFailures: Int,             // newly bankrupt firms from AI/hybrid implementation failures
      automationAiDebtTrap: Int,                  // newly bankrupt firms from AI debt trap
      automationNewFullAi: Int,                   // firms newly entering full automation
      automationNewHybrid: Int,                   // firms newly entering hybrid automation
      sumEquityIssuance: PLN,                     // aggregate GPW equity raised
      sumGrossInvestment: PLN,                    // aggregate physical capital investment
      sumBondIssuance: PLN,                       // aggregate bond issuance (pre-absorption)
      sumProfitShifting: PLN,                     // aggregate FDI profit shifting
      sumFdiRepatriation: PLN,                    // aggregate FDI repatriation
      sumInventoryChange: PLN,                    // aggregate net inventory change
      sumCitEvasion: PLN,                         // aggregate CIT evasion
      sumEnergyCost: PLN,                         // aggregate energy + ETS cost
      sumGreenInvestment: PLN,                    // aggregate green capital investment
      totalIoPaid: PLN,                           // total intermediate goods payments
      nplNew: PLN,                                // new non-performing loan volume
      nplLoss: PLN,                               // NPL loss net of recovery
      totalBondDefault: PLN,                      // bond default from bankrupt firms
      firmDeaths: Int,                            // number of firms that went bankrupt
      intIncome: PLN,                             // aggregate bank interest income
      corpBondAbsorption: Share,                  // Catalyst absorption ratio (0-1)
      actualBondIssuance: PLN,                    // bond issuance after absorption constraint
      netMigration: Int,                          // net immigration (inflow - outflow)
      perBankNewLoans: Vector[PLN],               // new loans by bank index
      sumFirmPrincipal: PLN,                      // aggregate firm loan principal repaid
      perBankFirmPrincipal: Vector[PLN],          // firm principal repaid by bank index
      perBankNplDebt: Vector[PLN],                // NPL debt by bank index
      perBankIntIncome: Vector[PLN],              // interest income by bank index
      perBankWorkers: Vector[Int],                // worker count by bank index
      lendingRates: Vector[Rate],                 // per-bank lending rates
      sumInvestmentCreditDemand: PLN,             // physical-investment bank credit requested
      sumInvestmentCreditApproved: PLN,           // physical-investment bank credit approved
      sumInvestmentCreditRejected: PLN,           // physical-investment bank credit rejected by bank supply
      sumTechCreditDemand: PLN,                   // technology-upgrade bank credit requested or bank-rejected
      sumTechCreditApproved: PLN,                 // technology-upgrade bank credit approved
      sumTechCreditRejected: PLN,                 // technology-upgrade bank credit rejected by bank supply
      sumTechSelectedCreditDemand: PLN,           // actual selected technology-upgrade bank credit requested
      sumTechSelectedCreditApproved: PLN,         // actual selected technology-upgrade bank credit approved
      sumTechSelectedCreditRejected: PLN,         // actual selected technology-upgrade bank credit rejected by bank supply
      sumTechCandidateCreditRejected: PLN,        // otherwise feasible technology-upgrade candidate rejected by bank supply
      sumCreditRejectedByReason: CreditRejectionBreakdown,
      postFirmCrossSectorHires: Int,              // cross-sector hires in labor matching
      postFirmHires: Int,                         // total hires consumed by firm-stage matching
      postFirmHireCapacity: Int,                  // monthly hire capacity available at firm-stage matching
      markupInflation: Rate,                      // Calvo: annualized revenue-weighted avg markup change
      sumRealizedPostTaxProfit: PLN,              // aggregate realized post-tax profits from Firm.process
      sumStateOwnedPostTaxProfit: PLN,            // aggregate realized post-tax profits of SOEs
      decisionTraces: Vector[Firm.DecisionTrace], // per-firm decision trace rows for optional exports
      ledgerFinancialState: LedgerFinancialState, // ledger with firm corporate-bond issuer balances after firm stage
  )

  /** Run the full firm processing pipeline from stage outputs. */
  def runStep(
      w: World,
      firms: Vector[Firm.State],
      households: Vector[Household.State],
      banks: Vector[Banking.BankState],
      ledgerFinancialState: LedgerFinancialState,
      s1: FiscalConstraintEconomics.Output,
      s2: LaborEconomics.Output,
      s3: HouseholdIncomeEconomics.Output,
      s4: DemandEconomics.Output,
      rng: RandomStream,
      traceDecisions: Boolean = false,
  )(using p: SimParams): StepOutput =
    val stepIn = StepInput(w, firms, households, banks, ledgerFinancialState, s1, s2, s3, s4, traceDecisions)
    runInternal(stepIn, rng)

  // ---- Core pipeline ----

  private def runInternal(stepIn: StepInput, rng: RandomStream)(using p: SimParams): StepOutput =
    val lending             = prepareLending(stepIn, rng)
    val fp                  = processFirms(stepIn.firms, stepIn.ledgerFinancialState, lending, rng, stepIn.traceDecisions)
    val bonded              = applyBondAbsorption(fp, stepIn.w, stepIn.banks, stepIn.ledgerFinancialState, lending.executionMonth)
    val intermediate        = applyIntermediateMarket(bonded.firms, bonded.financialStocks, stepIn)
    // Calvo staggered pricing: per-firm markup update
    val calvoFirms          = intermediate.firms.map: f =>
      val sectorPressure     = stepIn.s4.sectorDemandPressure(f.sector.toInt)
      val passthrough        =
        if f.stateOwned then StateOwned.effectiveEnergyPassthrough(f.sector.toInt)
        else Share.One
      val energyCostPressure =
        CalvoPricing.energyCostPressure(
          stepIn.w.external.gvc.commodityPriceIndex,
          p.climate.energyCostShares(f.sector.toInt),
          passthrough,
        )
      val calvo              = CalvoPricing.updateFirmMarkup(f.markup, sectorPressure, stepIn.s2.wageGrowth, energyCostPressure, rng)
      f.copy(markup = calvo.newMarkup)
    val laborMarket         = processLaborMarket(calvoFirms, stepIn, rng)
    val staffedFirms        = LaborMarket.syncFirmStaffing(calvoFirms, laborMarket.households)
    val npl                 = computeNplAndInterest(
      stepIn.firms,
      staffedFirms,
      stepIn.ledgerFinancialState.firms.map(LedgerFinancialState.projectFirmFinancialStocks),
      intermediate.financialStocks,
      bonded.ledgerFinancialState,
      lending,
    )
    val issuerSettledLedger = bonded.ledgerFinancialState.copy(
      firms = CorporateBondOwnership.clearDefaultedIssuerDebt(bonded.ledgerFinancialState.firms, npl.defaultedBondFirmIds),
    )
    val markupInfl          = CalvoPricing.aggregateMarkupInflation(calvoFirms, intermediate.firms, stepIn.w.real.productivityIndex).annualize
    assembleOutput(
      fp,
      bonded,
      staffedFirms,
      intermediate.financialStocks,
      issuerSettledLedger,
      intermediate.totalPaid,
      laborMarket.households,
      laborMarket.crossSectorHires,
      laborMarket.hires,
      laborMarket.hireCapacity,
      laborMarket.newHouseholdFinancialStocksById,
      npl,
      stepIn,
      lending,
      markupInfl,
    )

  // ---- Internal step input ----

  private case class StepInput(
      w: World,                                   // opening world snapshot for this firm-stage step
      firms: Vector[Firm.State],                  // opening firm states
      households: Vector[Household.State],        // opening household states before firm-stage labor matching
      banks: Vector[Banking.BankState],           // opening bank states used for lending conditions
      ledgerFinancialState: LedgerFinancialState, // opening ledger-owned financial stocks
      s1: FiscalConstraintEconomics.Output,       // fiscal/lending-base outputs from stage 1
      s2: LaborEconomics.Output,                  // labor-market outputs from stage 2
      s3: HouseholdIncomeEconomics.Output,        // household income and balance outputs from stage 3
      s4: DemandEconomics.Output,                 // demand and sector pressure outputs from stage 4
      traceDecisions: Boolean,                    // whether to collect per-firm decision traces
  )

  private case class LaborMarketResult(
      households: Vector[Household.State],                                  // households after separations, immigration, matching, and wage update
      crossSectorHires: Int,                                                // hires into a sector different from the household's prior sector
      hires: Int,                                                           // total new hires filled during firm-stage matching
      hireCapacity: Int,                                                    // maximum hires allowed by monthly matching capacity
      newHouseholdFinancialStocksById: Map[HhId, Household.FinancialStocks], // initial financial stocks for newly spawned immigrants
  )

  // ---- Phase 1: Lending conditions ----

  /** Prepare per-bank rates, lending functions, and world snapshot with updated
    * wages for firm decision-making.
    */
  private def prepareLending(in: StepInput, rng: RandomStream)(using p: SimParams): LendingConditions =
    val bsec               = in.w.bankingSector
    val nBanks             = in.banks.length
    val ccyb               = in.w.mechanisms.macropru.ccyb
    val bankStocks         = in.ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks)
    val bankCorpBonds      = (bankId: BankId) => CorporateBondOwnership.bankHolderFor(in.ledgerFinancialState, bankId)
    val rates              = in.banks.zip(bankStocks).zip(bsec.configs).map { case ((b, stocks), cfg) =>
      Banking.lendingRate(b, stocks, cfg, in.s1.lendingBaseRate, in.w.gov.bondYield, bankCorpBonds(b.id))
    }
    val creditDecision     = (bankId: Int, amt: PLN) =>
      val approval = Banking.creditApproval(in.banks(bankId), bankStocks(bankId), amt, rng, ccyb, bankCorpBonds(BankId(bankId)))
      Firm.CreditDecision.fromApproval(approval)
    val operationalSignals = OperationalSignals(
      sectorDemandMult = in.s4.sectorMults,
      sectorDemandPressure = in.s4.sectorDemandPressure,
      sectorHiringSignal = in.s4.sectorHiringSignal,
      operationalHiringSlack = in.s2.operationalHiringSlack,
    )
    val world              = in.w.copy(
      householdMarket = in.w.householdMarket.copy(
        marketWage = in.s2.newWage,
        reservationWage = in.s1.resWage,
      ),
    )
    LendingConditions(world, in.s1.m, operationalSignals, rates, creditDecision, nBanks)

  // ---- Phase 2: Per-firm processing ----

  /** Process each firm: technology decisions, financing splits (equity → bonds
    * → bank loans). Returns immutable per-firm outcomes and aggregate flows.
    */
  private def processFirms(
      firms: Vector[Firm.State],
      ledgerFinancialState: LedgerFinancialState,
      lending: LendingConditions,
      rng: RandomStream,
      traceDecisions: Boolean,
  )(using p: SimParams): FirmProcessingResult =
    require(
      firms.length == ledgerFinancialState.firms.length,
      s"FirmEconomics.processFirms requires aligned firms and ledger firm balances, got ${firms.length} firms and ${ledgerFinancialState.firms.length} balance rows",
    )
    val openingStocks = ledgerFinancialState.firms.map(LedgerFinancialState.projectFirmFinancialStocks)
    val outcomes      = firms
      .zip(openingStocks)
      .map: (f, stocks) =>
        val rate           = lending.rates(f.bankId.toInt)
        val creditDecision = (amt: PLN) => lending.bankCreditDecision(f.bankId.toInt, amt)
        val r              = Firm.processWithCreditAudit(
          f,
          stocks,
          lending.firmWorld,
          lending.executionMonth,
          lending.operationalSignals,
          rate,
          creditDecision,
          firms,
          rng,
          CorporateBondOwnership.issuerBalanceFor(ledgerFinancialState, f.id),
          traceDecision = traceDecisions,
        )
        val fin            = splitFinancing(r)
        val trace          =
          if traceDecisions then
            val baseTrace  = r.decisionTrace.getOrElse:
              throw IllegalStateException(s"Firm.process did not return a decision trace for firm ${f.id.toInt}")
            val components = traceLoanComponents(baseTrace, finalLoan = fin.bankLoan, finalTechCreditAmount = fin.techBankLoan)
            Some(
              baseTrace.copy(
                firmLoanAfter = fin.financialStocks.firmLoan,
                newLoan = fin.bankLoan,
                techCreditAmount = components.techCreditAmount,
                investmentCreditAmount = components.investmentCreditAmount,
              ),
            )
          else None

        FirmOutcome(
          firm = fin.firm,
          financialStocks = fin.financialStocks,
          flows = FirmFlows(
            tax = r.taxPaid,
            capex = r.capexSpent,
            techImp = r.techImports,
            newLoans = fin.bankLoan,
            equityIssuance = fin.equity,
            grossInvestment = r.grossInvestment,
            bondIssuance = fin.bonds,
            profitShifting = r.profitShiftCost,
            fdiRepatriation = r.fdiRepatriation,
            inventoryChange = r.inventoryChange,
            citEvasion = r.citEvasion,
            energyCost = r.energyCost,
            greenInvestment = r.greenInvestment,
            principalRepaid = r.principalRepaid,
            investmentCreditDemand = r.investmentCreditDemand,
            investmentCreditApproved = r.investmentCreditApproved,
            investmentCreditRejected = r.investmentCreditRejected,
            techCreditDemand = r.techCreditDemand,
            techCreditApproved = r.techCreditApproved,
            techCreditRejected = r.techCreditRejected,
            techSelectedCreditDemand = r.techSelectedCreditDemand,
            techSelectedCreditApproved = r.techSelectedCreditApproved,
            techSelectedCreditRejected = r.techSelectedCreditRejected,
            techCandidateCreditRejected = r.techCandidateCreditRejected,
            creditRejectedByReason = r.investmentCreditRejectionBreakdown + r.techCreditRejectionBreakdown,
          ),
          realizedPostTaxProfit = r.realizedPostTaxProfit,
          bankId = f.bankId,
          finalLoan = fin.bankLoan,
          bondAmt = fin.bonds,
          techBankLoan = fin.techBankLoan,
          techBondAmt = fin.techBonds,
          automationUpgradeFailure = becameBankruptWith(f, fin.firm, isImplementationFailure),
          automationAiDebtTrap = becameBankruptWith(f, fin.firm, _ == BankruptReason.AiDebtTrap),
          automationNewFullAi = !isAutomated(f) && isAutomated(fin.firm),
          automationNewHybrid = !isHybrid(f) && isHybrid(fin.firm),
          principalRepaid = r.principalRepaid,
          decisionTrace = trace,
        )

    val aggFlows = outcomes.foldLeft(FirmFlows.zero)((acc, o) => acc + o.flows)
    val bondMap  = outcomes.collect { case o if o.bondAmt > PLN.Zero => o.firm.id -> o.bondAmt }.toMap

    FirmProcessingResult(outcomes, aggFlows, bondMap, traceDecisions)

  /** Split a firm's new loan into three channels: GPW equity → Catalyst bonds →
    * bank loan remainder.
    */
  private def splitFinancing(r: Firm.Result)(using p: SimParams): FinancingSplit =
    val channels = financingChannelAmounts(r.newLoan, r.techNewLoan, Firm.workerCount(r.firm))
    val stocks   = r.financialStocks.copy(
      firmLoan = r.financialStocks.firmLoan - channels.equity - channels.bonds,
      equity = r.financialStocks.equity + channels.equity,
    )

    FinancingSplit(channels.bankLoan, channels.equity, channels.bonds, channels.techBankLoan, channels.techBonds, r.firm, stocks)

  private[amorfati] def financingChannelAmounts(newLoan: PLN, techNewLoan: PLN, workers: Int)(using p: SimParams): FinancingChannelAmounts =
    val requestedTechLoan = techNewLoan.min(newLoan).max(PLN.Zero)

    // Channel 1: GPW equity issuance (large firms only)
    val (afterEquityLoan, equityAmt, afterEquityTechLoan) =
      if newLoan > PLN.Zero && workers >= p.equity.issuanceMinSize
      then
        val eq       = newLoan * p.equity.issuanceFrac
        val adj      = newLoan - eq
        val techEq   = proratedPln(eq, requestedTechLoan, newLoan)
        val techLeft = (requestedTechLoan - techEq).max(PLN.Zero).min(adj)
        (adj, eq, techLeft)
      else (newLoan, PLN.Zero, requestedTechLoan)

    // Channel 2: Catalyst corporate bonds (medium+ firms only)
    val (finalLoan, bondAmt, techBankLoan, techBondAmt) =
      if afterEquityLoan > PLN.Zero && workers >= p.corpBond.minSize then
        val ba       = afterEquityLoan * p.corpBond.issuanceFrac
        val adj      = afterEquityLoan - ba
        val techBond = proratedPln(ba, afterEquityTechLoan, afterEquityLoan)
        val techBank = (afterEquityTechLoan - techBond).max(PLN.Zero).min(adj)
        (adj, ba, techBank, techBond)
      else (afterEquityLoan, PLN.Zero, afterEquityTechLoan, PLN.Zero)

    FinancingChannelAmounts(finalLoan, equityAmt, bondAmt, techBankLoan, techBondAmt)

  private def proratedPln(amount: PLN, part: PLN, total: PLN): PLN =
    if amount <= PLN.Zero || part <= PLN.Zero || total <= PLN.Zero then PLN.Zero
    else if part >= total then amount
    else
      val raw = ((BigInt(amount.distributeRaw) * BigInt(part.distributeRaw)) / BigInt(total.distributeRaw)).toLong
      PLN.fromRaw(raw).min(amount)

  private def bankruptcyReason(firm: Firm.State): Option[BankruptReason] =
    firm.tech match
      case TechState.Bankrupt(reason) => Some(reason)
      case _                          => None

  private def becameBankruptWith(opening: Firm.State, closing: Firm.State, matches: BankruptReason => Boolean): Boolean =
    Firm.isAlive(opening) && bankruptcyReason(closing).exists(matches)

  private def isImplementationFailure(reason: BankruptReason): Boolean =
    reason == BankruptReason.AiImplFailure || reason == BankruptReason.HybridImplFailure

  private def isAutomated(firm: Firm.State): Boolean =
    firm.tech.isInstanceOf[TechState.Automated]

  private def isHybrid(firm: Firm.State): Boolean =
    firm.tech.isInstanceOf[TechState.Hybrid]

  // ---- Phase 3: Bond absorption ----

  /** Apply Catalyst demand-side absorption constraint. Unsold bonds revert to
    * bank loans on the issuing firm's relationship bank.
    */
  private def applyBondAbsorption(
      result: FirmProcessingResult,
      w: World,
      banks: Vector[Banking.BankState],
      ledgerFinancialState: LedgerFinancialState,
      executionMonth: ExecutionMonth,
  )(using p: SimParams): BondAbsorptionResult =
    val bankAgg              = Banking.aggregateFromBankStocks(
      banks,
      ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks),
      bankId => CorporateBondOwnership.bankHolderFor(ledgerFinancialState, bankId),
    )
    val absorption           = CorporateBondMarket
      .computeAbsorption(w.financialMarkets.corporateBonds, result.flows.bondIssuance, bankAgg.car, p.banking.minCar)
    val revertShare          = Share.One - absorption
    val requestedByFirm      = result.outcomes
      .map(o => o.firm.id -> result.firmBondAmounts.getOrElse(o.firm.id, PLN.Zero))
      .filter((_, amount) => amount > PLN.Zero)
    val shouldRevert         = revertShare > BondRevertThreshold
    val absorbedBondIssuance = result.flows.bondIssuance * absorption
    val actualIssuanceByFirm =
      allocateAbsorbedBondIssuance(requestedByFirm, absorbedBondIssuance, executionMonth)
    val issuanceMapToApply   =
      if shouldRevert then actualIssuanceByFirm
      else requestedByFirm.toMap
    val bondReversionByFirm  =
      if shouldRevert then
        requestedByFirm
          .map((firmId, requested) => firmId -> (requested - actualIssuanceByFirm.getOrElse(firmId, PLN.Zero)).max(PLN.Zero))
          .filter((_, amount) => amount > PLN.Zero)
          .toMap
      else Map.empty[FirmId, PLN]

    val adjusted =
      result.outcomes.map: o =>
        val revert = bondReversionByFirm.getOrElse(o.firm.id, PLN.Zero)
        if revert > PLN.Zero then
          (
            o.firm,
            o.financialStocks.copy(firmLoan = o.financialStocks.firmLoan + revert),
          )
        else (o.firm, o.financialStocks)

    val issuerLedger = ledgerFinancialState.copy(
      firms = CorporateBondOwnership.applyIssuance(ledgerFinancialState.firms, issuanceMapToApply),
    )

    val bondRevertLoans    = bondReversionByFirm.valuesIterator.sumPln
    val actualBondIssuance =
      if shouldRevert then absorbedBondIssuance
      else result.flows.bondIssuance

    BondAbsorptionResult(
      adjusted.map(_._1),
      adjusted.map(_._2),
      issuerLedger,
      result.flows.newLoans + bondRevertLoans,
      absorption,
      actualBondIssuance,
      bondReversionByFirm,
    )

  private[amorfati] def allocateAbsorbedBondIssuance(
      requestedByFirm: Vector[(FirmId, PLN)],
      actualBondIssuance: PLN,
      executionMonth: ExecutionMonth = ExecutionMonth.First,
  ): Map[FirmId, PLN] =
    val positiveRequests = requestedByFirm.filter((_, amount) => amount > PLN.Zero)
    val target           = actualBondIssuance.distributeRaw
    val totalRequested   = positiveRequests.iterator.map((_, amount) => amount.distributeRaw).sum
    if positiveRequests.isEmpty || target <= 0L || totalRequested <= 0L then Map.empty
    else if target >= totalRequested then positiveRequests.toMap
    else
      case class AllocationRow(
          index: Int,      // original request order for deterministic final tie-breaks
          firmId: FirmId,  // issuing firm receiving an absorbed-bond allocation
          requested: Long, // requested bond issuance in raw fixed-point units
          base: Long,      // floor of proportional allocation in raw units
          remainder: Long, // proportional-allocation remainder used for bonus units
          tieBreak: Long,  // deterministic firm/month hash used when remainders tie
      )

      val rows =
        try
          positiveRequests.zipWithIndex.map { case ((firmId, requestedAmount), index) =>
            val requested = requestedAmount.distributeRaw
            val product   = java.lang.Math.multiplyExact(target, requested)
            AllocationRow(
              index = index,
              firmId = firmId,
              requested = requested,
              base = product / totalRequested,
              remainder = product % totalRequested,
              tieBreak = allocationTieBreak(firmId, executionMonth),
            )
          }
        catch case _: ArithmeticException => return allocateAbsorbedBondIssuanceBig(positiveRequests, target, totalRequested, executionMonth)

      val remaining         = target - rows.iterator.map(_.base).sum
      val (_, bonusByIndex) = rows
        .sortWith: (left, right) =>
          if left.remainder == right.remainder then
            if left.tieBreak == right.tieBreak then left.index < right.index
            else left.tieBreak < right.tieBreak
          else left.remainder > right.remainder
        .foldLeft((remaining, Map.empty[Int, Long])) { case ((left, bonuses), row) =>
          if left <= 0L || row.base >= row.requested then (left, bonuses)
          else (left - 1L, bonuses.updated(row.index, 1L))
        }

      val allocations  = rows.map: row =>
        row.firmId -> PLN.fromRaw(row.base + bonusByIndex.getOrElse(row.index, 0L))
      val allocatedRaw = allocations.iterator.map((_, amount) => amount.distributeRaw).sum
      require(
        allocatedRaw == target,
        s"Corporate bond absorption allocation must sum to target=$target, got $allocatedRaw",
      )
      allocations.filter((_, amount) => amount > PLN.Zero).toMap

  private def allocateAbsorbedBondIssuanceBig(
      positiveRequests: Vector[(FirmId, PLN)],
      target: Long,
      totalRequested: Long,
      executionMonth: ExecutionMonth,
  ): Map[FirmId, PLN] =
    case class AllocationRow(
        index: Int,        // original request order for deterministic final tie-breaks
        firmId: FirmId,    // issuing firm receiving an absorbed-bond allocation
        requested: Long,   // requested bond issuance in raw fixed-point units
        base: Long,        // floor of proportional allocation in raw units
        remainder: BigInt, // proportional-allocation remainder used for bonus units
        tieBreak: Long,    // deterministic firm/month hash used when remainders tie
    )

    val rows = positiveRequests.zipWithIndex.map { case ((firmId, requestedAmount), index) =>
      val requested = requestedAmount.distributeRaw
      val product   = BigInt(target) * BigInt(requested)
      AllocationRow(
        index = index,
        firmId = firmId,
        requested = requested,
        base = (product / BigInt(totalRequested)).toLong,
        remainder = product % BigInt(totalRequested),
        tieBreak = allocationTieBreak(firmId, executionMonth),
      )
    }

    val remaining         = target - rows.iterator.map(_.base).sum
    val (_, bonusByIndex) = rows
      .sortWith: (left, right) =>
        if left.remainder == right.remainder then
          if left.tieBreak == right.tieBreak then left.index < right.index
          else left.tieBreak < right.tieBreak
        else left.remainder > right.remainder
      .foldLeft((remaining, Map.empty[Int, Long])) { case ((left, bonuses), row) =>
        if left <= 0L || row.base >= row.requested then (left, bonuses)
        else (left - 1L, bonuses.updated(row.index, 1L))
      }

    val allocations  = rows.map: row =>
      row.firmId -> PLN.fromRaw(row.base + bonusByIndex.getOrElse(row.index, 0L))
    val allocatedRaw = allocations.iterator.map((_, amount) => amount.distributeRaw).sum
    require(
      allocatedRaw == target,
      s"Corporate bond absorption allocation must sum to target=$target, got $allocatedRaw",
    )
    allocations.filter((_, amount) => amount > PLN.Zero).toMap

  private def allocationTieBreak(firmId: FirmId, executionMonth: ExecutionMonth): Long =
    val firm  = firmId.toInt.toLong
    val month = executionMonth.toLong
    val mixed = (firm * 0x9e3779b97f4a7c15L) ^ (month * 0xbf58476d1ce4e5b9L)
    val step1 = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L
    val step2 = (step1 ^ (step1 >>> 27)) * 0x94d049bb133111ebL
    (step2 ^ (step2 >>> 31)) & Long.MaxValue

  // ---- Phase 4: Intermediate market ----

  /** Run I-O intermediate goods market. Adjusts firm cash positions (zero-sum
    * transfers between sectors). Returns updated firms and total paid.
    */
  private def applyIntermediateMarket(
      firms: Vector[Firm.State],
      financialStocks: Vector[Firm.FinancialStocks],
      in: StepInput,
  )(using p: SimParams): IntermediateResult =

    val r              = IntermediateMarket.process(
      IntermediateMarket.Input(
        firms = firms,
        sectorMults = in.s4.sectorMults,
        price = in.w.priceLevel,
        ioMatrix = p.io.matrix,
        columnSums = p.io.columnSums,
        scale = p.io.scale,
        productivityIndex = in.w.real.productivityIndex,
      ),
    )
    val adjustedStocks = financialStocks
      .zip(r.cashAdjustments)
      .map: (stocks, cashAdjustment) =>
        stocks.copy(cash = stocks.cash + cashAdjustment)
    IntermediateResult(r.firms, adjustedStocks, r.totalPaid)

  // ---- Phase 5: Labor market + immigration ----

  /** Run labor market: separate displaced workers, process immigration flows,
    * match available workers to vacancies (skill-ranked), update wages.
    */
  private def processLaborMarket(
      ioFirms: Vector[Firm.State],
      in: StepInput,
      rng: RandomStream,
  )(using p: SimParams): LaborMarketResult =
    val afterSep       = LaborMarket.separations(in.s3.updatedHouseholds, in.firms, ioFirms)
    val afterRemoval   = Immigration.removeReturnMigrants(afterSep, in.s2.newImmig.monthlyOutflow)
    val startId        = afterRemoval.map(_.id.toInt).maxOption.getOrElse(-1) + 1
    val newImmigrants  = Immigration.spawnImmigrantPopulation(in.s2.newImmig.monthlyInflow, startId, rng)
    val availableLabor = afterRemoval ++ newImmigrants.households
    val maxHires       = LaborMarket.monthlyMatchingCapacity(availableLabor, in.s2.newDemographics.workingAgePop)
    val employedBefore = availableLabor.count(_.status.isInstanceOf[HhStatus.Employed])
    val searchResult   = LaborMarket.jobSearch(
      availableLabor,
      ioFirms,
      in.s2.newWage,
      rng,
      in.s2.regionalWages,
      maxHires = Some(maxHires),
      priorityHouseholdIds = newImmigrants.households.map(_.id).toSet,
    )
    val postWages      = LaborMarket.updateWages(searchResult.households, ioFirms, in.s2.newWage)
    val hires          = Math.max(0, postWages.count(_.status.isInstanceOf[HhStatus.Employed]) - employedBefore)
    val newStocksById  = newImmigrants.households.zip(newImmigrants.financialStocks).map((household, stocks) => household.id -> stocks).toMap

    LaborMarketResult(postWages, searchResult.crossSectorHires, hires, maxHires, newStocksById)

  // ---- Phase 6: NPL and interest income ----

  /** Detect newly bankrupt firms, compute per-bank NPL losses and interest
    * income on pre-step debt stock.
    */
  private def computeNplAndInterest(
      preFirms: Vector[Firm.State],
      postFirms: Vector[Firm.State],
      openingFinancialStocks: Vector[Firm.FinancialStocks],
      closingFinancialStocks: Vector[Firm.FinancialStocks],
      ledgerFinancialState: LedgerFinancialState,
      lending: LendingConditions,
  )(using p: SimParams): NplResult =
    val prevAlive            = preFirms.filter(Firm.isAlive).map(_.id).toSet
    val newlyDead            = postFirms.filter(f => !Firm.isAlive(f) && prevAlive.contains(f.id))
    val closingStocksById    = postFirms.zip(closingFinancialStocks).map((firm, stocks) => firm.id -> stocks).toMap
    val openingStocksById    = preFirms.zip(openingFinancialStocks).map((firm, stocks) => firm.id -> stocks).toMap
    val nplNew               = newlyDead.flatMap(f => closingStocksById.get(f.id).map(_.firmLoan)).sumPln
    val nplLoss              = nplNew * (Share.One - p.banking.loanRecovery)
    val defaultedBondFirmIds = newlyDead.map(_.id).toSet
    val totalBondDefault     = CorporateBondOwnership.defaultedIssuerDebt(ledgerFinancialState.firms, defaultedBondFirmIds)

    // Per-bank aggregation via groupMapReduce (pure, no mutable arrays)
    val emptyPln  = Vector.fill(lending.nBanks)(PLN.Zero)
    val emptyInts = Vector.fill(lending.nBanks)(0)

    val perBankNplDebt = newlyDead.foldLeft(emptyPln): (acc, f) =>
      acc.updated(f.bankId.toInt, acc(f.bankId.toInt) + closingStocksById.get(f.id).fold(PLN.Zero)(_.firmLoan))

    val perBankIntIncome = preFirms
      .filter(Firm.isAlive)
      .foldLeft(emptyPln): (acc, f) =>
        val interest = openingStocksById.get(f.id).fold(PLN.Zero)(_.firmLoan) * lending.rates(f.bankId.toInt).monthly
        acc.updated(f.bankId.toInt, acc(f.bankId.toInt) + interest)

    val perBankWorkers = postFirms
      .filter(Firm.isAlive)
      .foldLeft(emptyInts): (acc, f) =>
        acc.updated(f.bankId.toInt, acc(f.bankId.toInt) + Firm.workerCount(f))

    NplResult(
      nplNew,
      nplLoss,
      totalBondDefault,
      newlyDead.length,
      perBankIntIncome.foldLeft(PLN.Zero)(_ + _),
      perBankNplDebt,
      perBankIntIncome,
      perBankWorkers,
      defaultedBondFirmIds,
    )

  // ---- Output assembly ----

  /** Assemble final StepOutput from phase results. Pure mapping, no
    * computation.
    */
  private def assembleOutput(
      fp: FirmProcessingResult,
      bonded: BondAbsorptionResult,
      ioFirms: Vector[Firm.State],
      firmFinancialStocks: Vector[Firm.FinancialStocks],
      ledgerFinancialState: LedgerFinancialState,
      totalIoPaid: PLN,
      households: Vector[Household.State],
      crossSectorHires: Int,
      hires: Int,
      hireCapacity: Int,
      newHouseholdFinancialStocksById: Map[HhId, Household.FinancialStocks],
      npl: NplResult,
      in: StepInput,
      lending: LendingConditions,
      markupInflation: Rate,
  )(using p: SimParams): StepOutput =
    val flows                 = fp.flows
    val ledgerAfterFirmStocks = in.s3.ledgerFinancialState.copy(
      households = LedgerFinancialState.refreshHouseholdBalances(
        households,
        in.s3.updatedHouseholds,
        in.s3.ledgerFinancialState.households,
        newHouseholdFinancialStocksById,
      ),
      firms = LedgerFinancialState.refreshFirmFinancialBalances(firmFinancialStocks, ledgerFinancialState.firms),
    )

    // Derive per-bank vectors from immutable outcomes
    val emptyPln = Vector.fill(lending.nBanks)(PLN.Zero)

    val perBankNewLoans = fp.outcomes.foldLeft(emptyPln): (acc, o) =>
      acc.updated(o.bankId.toInt, acc(o.bankId.toInt) + o.finalLoan)

    // Add bond reversion amounts to per-bank loans
    val perBankNewLoansWithRevert =
      if bonded.corpBondAbsorption < Share.One then
        fp.outcomes.foldLeft(perBankNewLoans): (acc, o) =>
          val revert = bonded.bondReversionByFirm.getOrElse(o.firm.id, PLN.Zero)
          if revert > PLN.Zero then acc.updated(o.bankId.toInt, acc(o.bankId.toInt) + revert)
          else acc
      else perBankNewLoans

    val perBankFirmPrincipal = fp.outcomes.foldLeft(emptyPln): (acc, o) =>
      acc.updated(o.bankId.toInt, acc(o.bankId.toInt) + o.principalRepaid)
    val decisionTraces       =
      if fp.traceDecisions then finalizedDecisionTraces(fp, bonded, ioFirms, firmFinancialStocks)
      else Vector.empty
    val automationTechLoans  = automationTechLoanTotal(fp, bonded)

    StepOutput(
      ioFirms = ioFirms,
      households = households,
      sumTax = flows.tax,
      sumCapex = flows.capex,
      sumTechImp = flows.techImp,
      sumNewLoans = bonded.sumNewLoans,
      automationTechCapex = flows.capex,
      automationTechImports = flows.techImp,
      automationTechLoans = automationTechLoans,
      automationUpgradeFailures = fp.outcomes.count(_.automationUpgradeFailure),
      automationAiDebtTrap = fp.outcomes.count(_.automationAiDebtTrap),
      automationNewFullAi = fp.outcomes.count(_.automationNewFullAi),
      automationNewHybrid = fp.outcomes.count(_.automationNewHybrid),
      sumEquityIssuance = flows.equityIssuance,
      sumGrossInvestment = flows.grossInvestment,
      sumBondIssuance = flows.bondIssuance,
      sumProfitShifting = flows.profitShifting,
      sumFdiRepatriation = flows.fdiRepatriation,
      sumInventoryChange = flows.inventoryChange,
      sumCitEvasion = flows.citEvasion,
      sumEnergyCost = flows.energyCost,
      sumGreenInvestment = flows.greenInvestment,
      totalIoPaid = totalIoPaid,
      nplNew = npl.nplNew,
      nplLoss = npl.nplLoss,
      totalBondDefault = npl.totalBondDefault,
      firmDeaths = npl.firmDeaths,
      intIncome = npl.intIncome,
      corpBondAbsorption = bonded.corpBondAbsorption,
      actualBondIssuance = bonded.actualBondIssuance,
      netMigration = in.s2.newImmig.monthlyInflow - in.s2.newImmig.monthlyOutflow,
      perBankNewLoans = perBankNewLoansWithRevert,
      sumFirmPrincipal = flows.principalRepaid,
      perBankFirmPrincipal = perBankFirmPrincipal,
      perBankNplDebt = npl.perBankNplDebt,
      perBankIntIncome = npl.perBankIntIncome,
      perBankWorkers = npl.perBankWorkers,
      lendingRates = lending.rates,
      sumInvestmentCreditDemand = flows.investmentCreditDemand,
      sumInvestmentCreditApproved = flows.investmentCreditApproved,
      sumInvestmentCreditRejected = flows.investmentCreditRejected,
      sumTechCreditDemand = flows.techCreditDemand,
      sumTechCreditApproved = flows.techCreditApproved,
      sumTechCreditRejected = flows.techCreditRejected,
      sumTechSelectedCreditDemand = flows.techSelectedCreditDemand,
      sumTechSelectedCreditApproved = flows.techSelectedCreditApproved,
      sumTechSelectedCreditRejected = flows.techSelectedCreditRejected,
      sumTechCandidateCreditRejected = flows.techCandidateCreditRejected,
      sumCreditRejectedByReason = flows.creditRejectedByReason,
      postFirmCrossSectorHires = crossSectorHires,
      postFirmHires = hires,
      postFirmHireCapacity = hireCapacity,
      markupInflation = markupInflation,
      sumRealizedPostTaxProfit = fp.outcomes.foldLeft(PLN.Zero)(_ + _.realizedPostTaxProfit),
      sumStateOwnedPostTaxProfit = fp.outcomes.filter(_.firm.stateOwned).foldLeft(PLN.Zero)((acc, o) => acc + o.realizedPostTaxProfit),
      decisionTraces = decisionTraces,
      ledgerFinancialState = ledgerAfterFirmStocks,
    )

  private def automationTechLoanTotal(fp: FirmProcessingResult, bonded: BondAbsorptionResult): PLN =
    fp.outcomes.iterator
      .map: outcome =>
        automationTechLoanAmount(
          techBankLoan = outcome.techBankLoan,
          techBondAmt = outcome.techBondAmt,
          bondAmt = outcome.bondAmt,
          revertedBond = bonded.bondReversionByFirm.getOrElse(outcome.firm.id, PLN.Zero),
        )
      .sumPln

  private[amorfati] def automationTechLoanAmount(techBankLoan: PLN, techBondAmt: PLN, bondAmt: PLN, revertedBond: PLN): PLN =
    techBankLoan + proratedPln(revertedBond, techBondAmt, bondAmt)

  private def traceLoanComponents(trace: Firm.DecisionTrace, finalLoan: PLN, finalTechCreditAmount: PLN): TraceLoanComponents =
    val techCap        = trace.techCreditNeed.max(finalTechCreditAmount)
    val techAmount     = finalTechCreditAmount.max(PLN.Zero).min(finalLoan.max(PLN.Zero)).min(techCap)
    val investmentBase = (finalLoan - techAmount).max(PLN.Zero)
    val hasInvestment  = trace.investmentCreditNeed.exists(_ > PLN.Zero) || trace.investmentCreditAmount.exists(_ > PLN.Zero) || investmentBase > PLN.Zero
    val investmentCap  = trace.investmentCreditNeed.orElse(trace.investmentCreditAmount).getOrElse(investmentBase)
    TraceLoanComponents(
      techCreditAmount = techAmount,
      investmentCreditAmount = Option.when(hasInvestment)(investmentBase.min(investmentCap.max(PLN.Zero))),
    )

  private def finalizedDecisionTraces(
      fp: FirmProcessingResult,
      bonded: BondAbsorptionResult,
      closingFirms: Vector[Firm.State],
      closingFinancialStocks: Vector[Firm.FinancialStocks],
  )(using p: SimParams): Vector[Firm.DecisionTrace] =
    if closingFirms.length != closingFinancialStocks.length then
      throw IllegalStateException(
        s"FirmEconomics.finalizedDecisionTraces requires aligned closing firms and stocks, got ${closingFirms.length} firms and ${closingFinancialStocks.length} stock rows",
      )

    val closingFirmById       = closingFirms.map(firm => firm.id -> firm).toMap
    val closingStocksByFirmId = closingFirms.zip(closingFinancialStocks).map((firm, stocks) => firm.id -> stocks).toMap

    fp.outcomes.map { outcome =>
      val firmId        = outcome.firm.id
      val closingFirm   = closingFirmById.getOrElse(
        firmId,
        throw IllegalStateException(s"FirmEconomics.finalizedDecisionTraces missing closing firm for firm ${firmId.toInt}"),
      )
      val closingStocks = closingStocksByFirmId.getOrElse(
        firmId,
        throw IllegalStateException(s"FirmEconomics.finalizedDecisionTraces missing closing stocks for firm ${firmId.toInt}"),
      )
      val revertedLoan  = bonded.bondReversionByFirm.getOrElse(firmId, PLN.Zero)
      val trace         = outcome.decisionTrace.getOrElse:
        throw IllegalStateException(s"FirmEconomics.finalizedDecisionTraces missing decision trace for firm ${firmId.toInt}")
      val finalLoan     = outcome.finalLoan + revertedLoan
      val finalTechLoan = automationTechLoanAmount(
        techBankLoan = outcome.techBankLoan,
        techBondAmt = outcome.techBondAmt,
        bondAmt = outcome.bondAmt,
        revertedBond = revertedLoan,
      )
      val components    = traceLoanComponents(trace, finalLoan = finalLoan, finalTechCreditAmount = finalTechLoan)
      trace.copy(
        closingTech = closingFirm.tech,
        cashAfter = closingStocks.cash,
        firmLoanAfter = closingStocks.firmLoan,
        digitalReadinessAfter = closingFirm.digitalReadiness,
        workersAfter = Firm.workerCount(closingFirm),
        newLoan = finalLoan,
        techCreditAmount = components.techCreditAmount,
        investmentCreditAmount = components.investmentCreditAmount,
      )
    }
