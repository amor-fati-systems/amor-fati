package com.boombustgroup.amorfati.engine.assembly

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.CompletedMonth
import com.boombustgroup.amorfati.engine.flows.{FlowSimulation, RuntimeFlowsTestSupport}
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.types.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WorldAssemblyEconomicsSpec extends AnyFlatSpec with Matchers:

  private given p: SimParams = SimParams.defaults

  private lazy val deterministicStep: FlowSimulation.StepOutput =
    RuntimeFlowsTestSupport.stepFromSeed()

  private def firmWithTech(id: Int, tech: TechState): Firm.State =
    deterministicStep.nextState.firms.head.copy(id = FirmId(id), tech = tech)

  "WorldAssemblyEconomics" should "produce valid world after simulation step" in {
    val result = deterministicStep
    val w      = result.nextState.world

    result.nextState.completedMonth shouldBe CompletedMonth(1)
    w.derivedTotalPopulation.should(be > 0)
    result.nextState.householdAggregates.employed.should(be > 0)
    w.external.tourismSeasonalFactor.should(not be Multiplier.Zero)
  }

  it should "keep ETS observables at the base price in the first execution month" in {
    val result = deterministicStep
    val w      = result.nextState.world

    decimal(w.real.etsPrice).shouldBe(decimal(p.climate.etsBasePrice) +- BigDecimal("1e-10"))
  }

  it should "preserve public-spending semantic aggregates on the assembled world" in {
    val result = deterministicStep
    val w      = result.nextState.world

    w.gov.domesticBudgetDemand shouldBe (w.gov.govCurrentSpend + w.gov.govCapitalSpend)
    w.gov.domesticBudgetOutlays shouldBe (
      w.gov.unempBenefitSpend
        + w.gov.socialTransferSpend
        + w.gov.govCurrentSpend
        + w.gov.govCapitalSpend
        + w.gov.debtServiceSpend
        + w.gov.euCofinancing
    )

    w.gov.govCurrentSpend shouldBe result.calculus.govCurrentSpend
    w.gov.domesticBudgetDemand shouldBe (result.calculus.govCurrentSpend + result.calculus.govCapitalSpend)
    w.gov.domesticBudgetOutlays.should(be >= w.gov.domesticBudgetDemand)
  }

  it should "count automation-native firm entrants in transition diagnostics" in {
    val transitions = FirmEntryTransitions.automationEntryTransitions(
      firms = Vector(
        firmWithTech(0, TechState.Hybrid(4, Multiplier.One)),
        firmWithTech(1, TechState.Traditional(4)),
        firmWithTech(2, TechState.Automated(Multiplier.One)),
        firmWithTech(3, TechState.Hybrid(4, Multiplier.One)),
      ),
      newFirmIds = Set(FirmId(0), FirmId(1), FirmId(2)),
    )

    transitions.newHybrid shouldBe 1
    transitions.newFullAi shouldBe 1
  }

  "WorldObservables" should "keep deterministic monthly seasonality anchors" in {
    WorldObservables.monthlySeasonalCos(monthInYear = 7, peakMonth = 7) shouldBe Coefficient.One
    WorldObservables.monthlySeasonalCos(monthInYear = 1, peakMonth = 7) shouldBe Coefficient(-1)
    WorldObservables.monthlySeasonalCos(monthInYear = 10, peakMonth = 7) shouldBe Coefficient.Zero
  }

  "StartupStaffing" should "sync startup filled-worker counts from household employment" in {
    val baseFirm          = deterministicStep.nextState.firms.head
    val startupFirm       = baseFirm.copy(
      id = FirmId(0),
      tech = TechState.Hybrid(0, Multiplier.One),
      startupMonthsLeft = 2,
      startupTargetWorkers = 2,
      startupFilledWorkers = 0,
    )
    val staffedHouseholds = deterministicStep.nextState.households.zipWithIndex.map: (household, idx) =>
      if idx < 3 then household.copy(status = HhStatus.Employed(startupFirm.id, startupFirm.sector, PLN(5000)))
      else household.copy(status = HhStatus.Unemployed(1))

    val synced = StartupStaffing.sync(Vector(startupFirm), staffedHouseholds).head

    synced.startupFilledWorkers shouldBe 2
    synced.tech shouldBe TechState.Hybrid(2, Multiplier.One)
  }

  it should "carry supported financial stocks through stage-owned ledger updates" in {
    val nextState = deterministicStep.nextState
    val ledger    = nextState.ledgerFinancialState

    ledger.households.length shouldBe nextState.households.length
    nextState.households.foreach: household =>
      ledger.households.isDefinedAt(household.id.toInt) shouldBe true

    nextState.firms.foreach: firm =>
      ledger.firms.isDefinedAt(firm.id.toInt) shouldBe true

    nextState.banks.foreach: bank =>
      val balances = ledger.banks(bank.id.toInt)
      LedgerFinancialState.bankBalances(
        LedgerFinancialState.projectBankFinancialStocks(balances),
        balances.corpBond,
        mortgageLoan = balances.mortgageLoan,
      ) shouldBe balances
  }
