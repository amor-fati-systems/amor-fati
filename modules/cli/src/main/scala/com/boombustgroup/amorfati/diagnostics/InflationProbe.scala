package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.{MonthExecution, MonthRandomness, SignalExtraction}
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.World
import com.boombustgroup.amorfati.engine.closedmonth.MonthClosing
import com.boombustgroup.amorfati.engine.economics.*
import com.boombustgroup.amorfati.engine.markets.{PriceLevel, RegionalClearing}
import com.boombustgroup.amorfati.init.{InitRandomness, WorldInit}
import com.boombustgroup.amorfati.types.*

object InflationProbe:

  private def laborSupplyCount(wage: PLN, resWage: PLN, totalPopulation: Int)(using p: SimParams): Int =
    if resWage <= PLN.Zero then if wage > PLN.Zero then totalPopulation else 0
    else
      val wageGap     = wage.ratioTo(resWage).toCoefficient - Coefficient.One
      val slope       = p.household.laborSupplySteepness * wageGap
      val denominator = Multiplier.One + (-slope).exp
      Multiplier.One.ratioTo(denominator).toShare.applyTo(totalPopulation)

  private def pct(value: Scalar, digits: Int = 2): String =
    (value * Scalar(100)).format(digits) + "%"

  private def topPressures(pressures: Vector[Multiplier])(using p: SimParams): String =
    p.sectorDefs
      .zip(pressures)
      .sortBy { case (_, v) => -(v / Multiplier.One) }
      .take(3)
      .map { case (sec, v) =>
        s"${sec.name}=${(v / Multiplier.One).format(2)}"
      }
      .mkString(", ")

  private def sectorSignals(label: String, values: Vector[Multiplier])(using p: SimParams): String =
    p.sectorDefs
      .zip(values)
      .map { case (sec, v) =>
        s"${sec.name}=${(v / Multiplier.One).format(2)}"
      }
      .mkString(s"$label: ", ", ", "")

  private case class GovPurchasesBreakdown(
      zusNetSurplus: PLN,
      unempGap: Share,
      base: PLN,
      recycleCounterfactual: PLN,
      stimulus: PLN,
      rawTarget: PLN,
  )

  private def govPurchasesBreakdown(world: World, employed: Int)(using p: SimParams): GovPurchasesBreakdown =
    val zusNetSurplus = (world.social.zus.contributions - world.social.zus.pensionPayments).max(PLN.Zero)
    val unempRate     = world.unemploymentRate(employed)
    val unempGap      = (unempRate - p.monetary.nairu).max(Share.Zero)
    val priceIdx      = world.priceLevel.toMultiplier.max(Multiplier.One)
    val wageIdx       =
      if p.household.baseWage > PLN.Zero then world.householdMarket.marketWage.ratioTo(p.household.baseWage).toMultiplier.max(priceIdx)
      else priceIdx
    val costIdx       = priceIdx * (Share.One - p.fiscal.govWageIndexShare) + wageIdx * p.fiscal.govWageIndexShare
    val base          = p.fiscal.govBaseSpending * costIdx
    val recycling     = (world.gov.taxRevenue + zusNetSurplus) * p.fiscal.govFiscalRecyclingRate
    val stimulus      = p.fiscal.govBaseSpending * unempGap * p.fiscal.govAutoStabMult
    GovPurchasesBreakdown(zusNetSurplus, unempGap, base, recycling, stimulus, base + stimulus)

  @main def runInflationProbe(seed: Long = 1L, months: Int = 12): Unit =
    given SimParams = SimParams.defaults

    val init                 = WorldInit.initialize(InitRandomness.Contract.fromSeed(seed))
    var world                = init.world
    var firms                = init.firms
    var hhs                  = init.households
    var banks                = init.banks
    var ledgerFinancialState = init.ledgerFinancialState

    println(s"seed=$seed months=$months")

    (1 to months).foreach: month =>
      val population         = world.laborForcePopulation
      val contract           = MonthRandomness.Contract.fromSeed(seed * 1000 + month)
      val fiscal             = FiscalConstraintEconomics.compute(world, banks, ledgerFinancialState, ExecutionMonth(month))
      val openingLabor       = LaborEconomics.compute(world, firms, hhs, fiscal)
      val prevWage           = world.householdMarket.marketWage
      val rawLaborWage       = RegionalClearing.clear(world.regionalWages, fiscal.resWage, openingLabor.laborDemand, population).nationalWage
      val expWagePressure    =
        (summon[SimParams].labor.expWagePassthrough * world.mechanisms.expectations.expectedInflation.max(Rate.Zero).toCoefficient) / 12
      val observedEmployed   = Household.countEmployed(hhs)
      val tightWagePressure  = LaborEconomics.nairuWagePressure(population, Math.max(openingLabor.employed, observedEmployed))
      val expectedWage       = rawLaborWage * expWagePressure.growthMultiplier
      val tightnessFloor     = prevWage * tightWagePressure.growthMultiplier
      val wageAfterExp       = fiscal.resWage.max(expectedWage.max(tightnessFloor))
      val aggUnionDensity    =
        summon[SimParams].sectorDefs.zipWithIndex
          .map((s, i) => s.share * summon[SimParams].labor.unionDensity(i))
          .foldLeft(Share.Zero)(_ + _)
      val unionAdjustedWage  =
        if wageAfterExp < prevWage then
          val decline = prevWage - wageAfterExp
          fiscal.resWage.max(wageAfterExp + decline * summon[SimParams].labor.unionRigidity * aggUnionDensity)
        else wageAfterExp
      val supplyAtPrev       = laborSupplyCount(world.householdMarket.marketWage, fiscal.resWage, population)
      val newSupply          = laborSupplyCount(unionAdjustedWage, fiscal.resWage, population)
      val excessDemand       = (openingLabor.laborDemand - supplyAtPrev).ratioTo(population)
      val phillipsGrowth     = if prevWage > PLN.Zero then rawLaborWage / prevWage - Scalar.One else Scalar.Zero
      val expGrowth          = expWagePressure.toScalar
      val tightGrowth        = tightWagePressure.toScalar
      val unionGrowth        = if wageAfterExp > PLN.Zero then unionAdjustedWage / wageAfterExp - Scalar.One else Scalar.Zero
      val payroll            = SocialSecurity.payrollBase(hhs)
      val payrollZus         = SocialSecurity.zusStep(payroll, openingLabor.newDemographics.retirees)
      val householdIncome    =
        HouseholdIncomeEconomics.compute(
          world,
          firms,
          hhs,
          banks,
          ledgerFinancialState,
          fiscal.lendingBaseRate,
          fiscal.resWage,
          openingLabor.newWage,
          contract.stages.householdIncomeEconomics.newStream(),
          pensionIncome = payrollZus.pensionPayments,
        )
      val demand             = DemandEconomics.compute(world, openingLabor.employed, openingLabor.living, householdIncome.domesticCons)
      val firm               = FirmEconomics.runStep(
        world,
        firms,
        hhs,
        banks,
        ledgerFinancialState,
        fiscal,
        openingLabor,
        householdIncome,
        demand,
        contract.stages.firmEconomics.newStream(),
      )
      val living             = firm.ioFirms.filter(Firm.isAlive)
      val labor              = LaborEconomics.reconcilePostFirmStep(world, fiscal, openingLabor, living, firm.households)
      val householdFinancial =
        HouseholdFinancialEconomics.compute(world, fiscal.m, labor.employed, householdIncome.hhAgg, contract.stages.householdFinancialEconomics.newStream())
      val priceEquity        = PriceEquityEconomics.compute(
        w = world,
        month = fiscal.m,
        wageGrowth = labor.wageGrowth,
        avgDemandMult = demand.avgDemandMult,
        sectorMults = demand.sectorMults,
        totalSystemLoans = ledgerFinancialState.banks.map(_.firmLoan).sumPln,
        firmStep = firm,
        ledgerFinancialState = ledgerFinancialState,
      )
      val openEconomy        =
        OpenEconEconomics.runStep(
          OpenEconEconomics.StepInput(
            world = world,
            ledgerFinancialState = ledgerFinancialState,
            fiscal = fiscal,
            labor = labor,
            householdIncome = householdIncome,
            demand = demand,
            firm = firm,
            householdFinancial = householdFinancial,
            priceEquity = priceEquity,
            banks = banks,
            commodityRng = contract.stages.openEconEconomics.newStream(),
          ),
        )
      val banking            =
        BankingEconomics.runStep(
          BankingEconomics.StepInput(
            world = world,
            ledgerFinancialState = ledgerFinancialState,
            fiscal = fiscal,
            labor = labor,
            householdIncome = householdIncome,
            demand = demand,
            firm = firm,
            householdFinancial = householdFinancial,
            priceEquity = priceEquity,
            openEconomy = openEconomy,
            banks = banks,
            depositRng = contract.stages.bankingEconomics.newStream(),
          ),
        )

      val exRateDeviation = world.forex.exchangeRate.deviationFrom(summon[SimParams].forex.baseExRate)
      val priceUpd        = PriceLevel.update(
        // PriceLevel reads the current pre-policy expectation; openEconomy.monetary.newExp is next month's post-policy anchor.
        expectedInflation = world.mechanisms.expectations.expectedInflation,
        prevPrice = world.priceLevel,
        demandMult = demand.avgDemandMult,
        wageGrowth = labor.wageGrowth,
        exRateDeviation = exRateDeviation,
        importCostIndex = world.external.gvc.importCostIndex,
      )
      val unemp           = world.unemploymentRate(labor.employed)
      val realRate        = openEconomy.monetary.newRefRate - openEconomy.monetary.newExp.expectedInflation
      val govBreakdown    = govPurchasesBreakdown(world, openingLabor.employed)
      val debtToGdp       =
        if priceEquity.gdp > PLN.Zero then banking.newGovWithYield.cumulativeDebt / (priceEquity.gdp * 12) else Scalar.Zero
      val deficitToGdp    =
        if priceEquity.gdp > PLN.Zero then banking.newGovWithYield.deficit / priceEquity.gdp else Scalar.Zero

      println(
        s"m=$month u=${pct(unemp.toScalar)} pi=${pct(priceEquity.newInfl.toScalar)} wage=${labor.newWage.format(0)} wg=${pct(labor.wageGrowth.toScalar)} demand=${(demand.avgDemandMult / Multiplier.One).format(3)} markup=${pct(firm.markupInflation.toScalar)}",
      )
      println(
        s"  channels monthly: demand=${pct(priceUpd.demandPull.toScalar)}pp cost=${pct(priceUpd.costPush.toScalar)}pp import=${pct(priceUpd.importPush.toScalar)}pp raw=${pct(priceUpd.rawMonthly.toScalar)}pp floor=${pct(priceUpd.flooredMonthly.toScalar)}pp",
      )
      println(
        s"  annualized: base=${pct(priceUpd.inflation.toScalar)} markup=${pct(firm.markupInflation.toScalar)} total=${pct(priceEquity.newInfl.toScalar)} exDev=${pct(exRateDeviation.toCoefficient.toScalar)} importCost=${world.external.gvc.importCostIndex.format(3)} commodity=${world.external.gvc.commodityPriceIndex.format(3)}",
      )
      println(
        s"  policy: ref=${pct(openEconomy.monetary.newRefRate.toScalar)} expPi=${pct(openEconomy.monetary.newExp.expectedInflation.toScalar)} real=${pct(realRate.toScalar)} cred=${pct(openEconomy.monetary.newExp.credibility.toScalar)} fg=${pct(openEconomy.monetary.newExp.forwardGuidanceRate.toScalar)}",
      )
      println(
        s"  wages: phillips=${pct(phillipsGrowth)} exp=${pct(expGrowth)} tight=${pct(tightGrowth)} union=${pct(unionGrowth)} raw=${rawLaborWage.format(0)} afterExp=${wageAfterExp.format(0)} final=${unionAdjustedWage.format(0)}",
      )
      println(
        s"  labor: demand=${openingLabor.laborDemand} supplyPrev=${supplyAtPrev} supplyNew=${newSupply} excess=${pct(excessDemand)} employedPre=${openingLabor.employed} employedPost=${labor.employed}",
      )
      println(
        s"  households: income=${householdIncome.totalIncome.format(0)} cons=${householdIncome.consumption.format(0)} domesticCons=${householdIncome.domesticCons.format(0)} importCons=${householdIncome.importCons.format(0)}",
      )
      println(
        s"  fiscal: govPurch=${demand.govPurchases.format(0)} govCur=${banking.newGovWithYield.govCurrentSpend.format(0)} govCapDom=${banking.newGovWithYield.govCapitalSpend.format(0)} euProjCap=${banking.newGovWithYield.euProjectCapital.format(0)} euCofinDom=${banking.newGovWithYield.euCofinancing.format(0)} def=${banking.newGovWithYield.deficit.format(0)} def/gdp=${pct(deficitToGdp)} debt/gdp=${pct(debtToGdp)} rule=${demand.fiscalRuleStatus.bindingRule} cut=${pct(demand.fiscalRuleStatus.spendingCutRatio.toScalar)}",
      )
      println(
        s"  gov raw target: base=${govBreakdown.base.format(0)} recycleCf=${govBreakdown.recycleCounterfactual.format(0)} stimulus=${govBreakdown.stimulus.format(0)} raw=${govBreakdown.rawTarget.format(0)} zusSurplus=${govBreakdown.zusNetSurplus.format(0)} unempGap=${pct(govBreakdown.unempGap.toScalar)}",
      )
      println(s"  top pressure: ${topPressures(demand.sectorDemandPressure)}")
      println(s"  ${sectorSignals("sectorMult", demand.sectorMults)}")
      println(s"  ${sectorSignals("pressure", demand.sectorDemandPressure)}")
      println(s"  ${sectorSignals("hiring", demand.sectorHiringSignal)}")

      val monthExecution = MonthExecution(
        openingWorld = world,
        fiscal = fiscal,
        labor = labor,
        householdIncome = householdIncome,
        demand = demand,
        firm = firm,
        householdFinancial = householdFinancial,
        priceEquity = priceEquity,
        openEconomy = openEconomy,
        banking = banking,
      )
      val closing        = MonthClosing.closeExecution(monthExecution, contract.closing.newStreams())
      val seedOut        = SignalExtraction
        .fromClosedMonth(
          world = closing.world,
          households = closing.households,
          operationalHiringSlack = monthExecution.labor.operationalHiringSlack,
          startupAbsorptionRate = closing.startupAbsorptionRate,
          demand = SignalExtraction.DemandOutcomes(
            sectorDemandMult = monthExecution.demand.sectorMults,
            sectorDemandPressure = monthExecution.demand.sectorDemandPressure,
            sectorHiringSignal = monthExecution.demand.sectorHiringSignal,
          ),
        )
        .seedOut

      world = closing.world.copy(pipeline = closing.world.pipeline.withDecisionSignals(seedOut))
      firms = closing.firms
      hhs = closing.households
      banks = closing.banks
      ledgerFinancialState = closing.ledgerFinancialState
