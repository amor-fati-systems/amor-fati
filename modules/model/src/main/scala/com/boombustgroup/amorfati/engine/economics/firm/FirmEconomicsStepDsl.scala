package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.agents.Firm
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.MonthWorkflow
import com.boombustgroup.amorfati.engine.MonthWorkflow.Program
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.random.RandomStream

/** Zero-cost workflow labels for the firm economics step. */
private[firm] object FirmEconomicsStepDsl:

  def lending(stepIn: StepInput, rng: RandomStream)(using SimParams): Program[LendingConditions] =
    MonthWorkflow.pure(FirmLendingStage.prepare(stepIn, rng))

  def firmProcessing(stepIn: StepInput, lending: LendingConditions, rng: RandomStream)(using SimParams): Program[FirmProcessingResult] =
    MonthWorkflow.pure(FirmProcessingStage.process(stepIn.firms, stepIn.ledgerFinancialState, lending, rng, stepIn.traceDecisions))

  def bondAbsorption(stepIn: StepInput, firmProcessing: FirmProcessingResult, lending: LendingConditions)(using SimParams): Program[BondAbsorptionResult] =
    MonthWorkflow.pure(FirmBondAbsorption(firmProcessing, stepIn.w, stepIn.banks, stepIn.ledgerFinancialState, lending.executionMonth))

  def intermediateMarket(stepIn: StepInput, bonded: BondAbsorptionResult)(using SimParams): Program[IntermediateResult] =
    MonthWorkflow.pure(FirmMarketStages.intermediateMarket(bonded.firms, bonded.financialStocks, stepIn))

  def calvoPricing(stepIn: StepInput, intermediate: IntermediateResult, rng: RandomStream)(using SimParams): Program[PricingResult] =
    MonthWorkflow.pure(FirmMarketStages.calvoPricing(stepIn, intermediate, rng))

  def laborMarket(stepIn: StepInput, pricing: PricingResult, rng: RandomStream)(using SimParams): Program[LaborMarketResult] =
    MonthWorkflow.pure(FirmMarketStages.laborMarket(pricing.firms, stepIn, rng))

  def staffing(pricing: PricingResult, laborMarket: LaborMarketResult): Program[Vector[Firm.State]] =
    MonthWorkflow.pure(FirmMarketStages.staffedFirms(pricing, laborMarket))

  def defaultsAndInterest(
      stepIn: StepInput,
      intermediate: IntermediateResult,
      bonded: BondAbsorptionResult,
      staffedFirms: Vector[Firm.State],
      lending: LendingConditions,
  )(using SimParams): Program[NplResult] =
    MonthWorkflow.pure(
      FirmMarketStages.defaultsAndInterest(
        stepIn.firms,
        staffedFirms,
        stepIn.ledgerFinancialState.firms.map(LedgerFinancialState.projectFirmFinancialStocks),
        intermediate.financialStocks,
        bonded.ledgerFinancialState,
        lending,
      ),
    )

  def issuerDebtSettlement(bonded: BondAbsorptionResult, npl: NplResult): Program[LedgerFinancialState] =
    MonthWorkflow.pure(FirmMarketStages.issuerDebtSettlement(bonded, npl))

  def close(
      stepIn: StepInput,
      firmProcessing: FirmProcessingResult,
      bonded: BondAbsorptionResult,
      intermediate: IntermediateResult,
      pricing: PricingResult,
      laborMarket: LaborMarketResult,
      staffedFirms: Vector[Firm.State],
      npl: NplResult,
      issuerSettledLedger: LedgerFinancialState,
      lending: LendingConditions,
  )(using SimParams): Program[StepOutput] =
    MonthWorkflow.pure(
      FirmStepOutputAssembler.assemble(
        firmProcessing,
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
        pricing.markupInflation,
      ),
    )
