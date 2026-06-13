package com.boombustgroup.amorfati.diagnostics

import com.boombustgroup.amorfati.types.sumPln
import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.{MonthExecution, MonthRandomness, OperationalSignals, SignalExtraction, World}
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.closedmonth.MonthClosing
import com.boombustgroup.amorfati.engine.economics.*
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.init.{InitRandomness, WorldInit}

object LaborDemandProbe:

  private case class SectorSnapshot(
      workers: Int,
      firms: Int,
      startups: Int,
      startupWorkers: Int,
  )

  private case class SectorChangeSummary(
      upsizeFirms: Int,
      totalWorkersAdded: Int,
      medianAddedWorkers: Int,
      maxAddedWorkers: Int,
  )

  private case class HiringSummary(
      firms: Int,
      upsizeCandidates: Int,
      totalDesiredGap: Int,
      medianDesiredGap: Int,
      maxDesiredGap: Int,
      totalFeasibleGap: Int,
      medianFeasibleGap: Int,
      maxFeasibleGap: Int,
      medianDesiredTarget: Int,
      maxDesiredTarget: Int,
      medianFeasibleTarget: Int,
      maxFeasibleTarget: Int,
      medianProposedAdd: Int,
      maxProposedAdd: Int,
      medianSignalMonths: Int,
      maxSignalMonths: Int,
      requiredSignalMonths: Int,
  )

  private case class LaborMatchSnapshot(
      employed: Int,
      unemployed: Int,
      retraining: Int,
      bankrupt: Int,
      vacancies: Int,
      overstaffed: Int,
      invalidEmployed: Int,
  )

  private def sectorSnapshots(firms: Vector[Firm.State])(using p: SimParams): Vector[SectorSnapshot] =
    (0 until p.sectorDefs.length)
      .map: s =>
        val sectorFirms = firms.filter(f => Firm.isAlive(f) && f.sector.toInt == s)
        val startups    = sectorFirms.filter(Firm.isInStartup)
        SectorSnapshot(
          workers = sectorFirms.map(Firm.workerCount).sum,
          firms = sectorFirms.length,
          startups = startups.length,
          startupWorkers = startups.map(Firm.workerCount).sum,
        )
      .toVector

  private def printSectorTable(label: String, prev: Vector[SectorSnapshot], next: Vector[SectorSnapshot])(using p: SimParams): Unit =
    println(label)
    p.sectorDefs.zipWithIndex.foreach { case (sec, i) =>
      val a  = prev(i)
      val b  = next(i)
      val dw = b.workers - a.workers
      val df = b.firms - a.firms
      val ds = b.startups - a.startups
      println(
        f"  ${sec.name}%-16s workers=${a.workers}%6d -> ${b.workers}%6d  dW=${dw}%+6d  firms=${a.firms}%5d -> ${b.firms}%5d  dF=${df}%+4d  startups=${a.startups}%4d -> ${b.startups}%4d  dS=${ds}%+3d  startupWorkers=${b.startupWorkers}%5d",
      )
    }

  private def sectorChangeSummaries(prev: Vector[Firm.State], next: Vector[Firm.State])(using p: SimParams): Vector[SectorChangeSummary] =
    val prevById = prev.map(f => f.id -> f).toMap
    (0 until p.sectorDefs.length)
      .map: s =>
        val deltas = next
          .filter(f => Firm.isAlive(f) && f.sector.toInt == s)
          .flatMap: f =>
            prevById
              .get(f.id)
              .map: pf =>
                Firm.workerCount(f) - Firm.workerCount(pf)
          .filter(_ > 0)
          .sorted
        val median =
          if deltas.isEmpty then 0
          else deltas(deltas.length / 2)
        SectorChangeSummary(
          upsizeFirms = deltas.length,
          totalWorkersAdded = deltas.sum,
          medianAddedWorkers = median,
          maxAddedWorkers = deltas.lastOption.getOrElse(0),
        )
      .toVector

  private def printChangeSummaries(label: String, summaries: Vector[SectorChangeSummary])(using p: SimParams): Unit =
    println(label)
    p.sectorDefs.zipWithIndex.foreach { case (sec, i) =>
      val s = summaries(i)
      println(
        f"  ${sec.name}%-16s upsizeFirms=${s.upsizeFirms}%5d  totalAdded=${s.totalWorkersAdded}%6d  medianAdded=${s.medianAddedWorkers}%3d  maxAdded=${s.maxAddedWorkers}%3d",
      )
    }

  private def hiringSummaries(world: World, firms: Vector[Firm.State], ledgerFinancialState: LedgerFinancialState)(using p: SimParams): Vector[HiringSummary] =
    val signals             = OperationalSignals.fromDecisionSignals(world.seedIn, world.pipeline.operationalHiringSlack)
    val financialStocksById =
      firms.zip(ledgerFinancialState.firms.map(LedgerFinancialState.projectFirmFinancialStocks)).map((firm, stocks) => firm.id -> stocks).toMap
    (0 until p.sectorDefs.length)
      .map: s =>
        val ds                           = firms
          .filter(f => Firm.isAlive(f) && f.sector.toInt == s)
          .map(f => Firm.hiringDiagnostics(f, financialStocksById(f.id), world, signals))
        val positiveDesired              = ds.filter(_.desiredGap > 0)
        val desiredGaps                  = positiveDesired.map(_.desiredGap).sorted
        val feasibleGaps                 = positiveDesired.map(_.feasibleGap).sorted
        val desiredTargets               = positiveDesired.map(_.desiredWorkers).sorted
        val feasibleTargets              = positiveDesired.map(_.feasibleWorkers).sorted
        val candidateAdds                = positiveDesired.map(d => d.proposedWorkers - d.workers).sorted
        val signalMonths                 = positiveDesired.map(_.signalMonths).sorted
        def median(xs: Vector[Int]): Int = if xs.isEmpty then 0 else xs(xs.length / 2)
        HiringSummary(
          firms = ds.length,
          upsizeCandidates = ds.count(_.shouldAdjust),
          totalDesiredGap = desiredGaps.sum,
          medianDesiredGap = median(desiredGaps),
          maxDesiredGap = desiredGaps.lastOption.getOrElse(0),
          totalFeasibleGap = feasibleGaps.sum,
          medianFeasibleGap = median(feasibleGaps),
          maxFeasibleGap = feasibleGaps.lastOption.getOrElse(0),
          medianDesiredTarget = median(desiredTargets),
          maxDesiredTarget = desiredTargets.lastOption.getOrElse(0),
          medianFeasibleTarget = median(feasibleTargets),
          maxFeasibleTarget = feasibleTargets.lastOption.getOrElse(0),
          medianProposedAdd = median(candidateAdds),
          maxProposedAdd = candidateAdds.lastOption.getOrElse(0),
          medianSignalMonths = median(signalMonths),
          maxSignalMonths = signalMonths.lastOption.getOrElse(0),
          requiredSignalMonths = positiveDesired.headOption.map(_.requiredSignalMonths).getOrElse(0),
        )
      .toVector

  private def printHiringSummaries(label: String, summaries: Vector[HiringSummary])(using p: SimParams): Unit =
    println(label)
    p.sectorDefs.zipWithIndex.foreach { case (sec, i) =>
      val s = summaries(i)
      println(
        f"  ${sec.name}%-16s firms=${s.firms}%5d  upsizeCandidates=${s.upsizeCandidates}%5d  desiredGap=${s.totalDesiredGap}%7d  feasibleGap=${s.totalFeasibleGap}%6d  medDesiredGap=${s.medianDesiredGap}%3d  medFeasibleGap=${s.medianFeasibleGap}%3d  medDesiredT=${s.medianDesiredTarget}%3d  medFeasibleT=${s.medianFeasibleTarget}%3d  medAdd=${s.medianProposedAdd}%3d  medSignal=${s.medianSignalMonths}%2d/${s.requiredSignalMonths}%1d",
      )
    }

  private def laborMatchSnapshot(firms: Vector[Firm.State], households: Vector[Household.State])(using p: SimParams): LaborMatchSnapshot =
    val livingIds = firms.filter(Firm.isAlive).map(_.id).toSet
    val staffed   = households
      .flatMap: hh =>
        hh.status match
          case HhStatus.Employed(fid, _, _) => Some(fid)
          case _                            => None
      .groupMapReduce(identity)(_ => 1)(_ + _)

    val employedInvalid          = staffed.filter((fid, _) => !livingIds.contains(fid)).values.sum
    val (vacancies, overstaffed) = firms
      .filter(Firm.isAlive)
      .foldLeft((0, 0)) { case ((vac, over), firm) =>
        val target = Firm.workerCount(firm)
        val filled = staffed.getOrElse(firm.id, 0)
        (vac + (target - filled).max(0), over + (filled - target).max(0))
      }

    households.foldLeft(LaborMatchSnapshot(0, 0, 0, 0, vacancies, overstaffed, employedInvalid)): (acc, hh) =>
      hh.status match
        case HhStatus.Employed(_, _, _)   => acc.copy(employed = acc.employed + 1)
        case HhStatus.Unemployed(_)       => acc.copy(unemployed = acc.unemployed + 1)
        case HhStatus.Retraining(_, _, _) => acc.copy(retraining = acc.retraining + 1)
        case HhStatus.Bankrupt            => acc.copy(bankrupt = acc.bankrupt + 1)

  @main def runLaborDemandProbe(seed: Long = 1L, months: Int = 2): Unit =
    given SimParams = SimParams.defaults

    val init                 = WorldInit.initialize(InitRandomness.Contract.fromSeed(seed))
    var world                = init.world
    var firms                = init.firms
    var hhs                  = init.households
    var banks                = init.banks
    var ledgerFinancialState = init.ledgerFinancialState

    println(s"seed=$seed months=$months")

    (1 to months).foreach: month =>
      val contract  = MonthRandomness.Contract.fromSeed(seed * 1000 + month)
      val beforeAll = sectorSnapshots(firms)
      val hiring    = hiringSummaries(world, firms, ledgerFinancialState)

      val fiscal             = FiscalConstraintEconomics.compute(world, banks, ledgerFinancialState, ExecutionMonth(month))
      val openingLabor       = LaborEconomics.compute(world, firms, hhs, fiscal)
      val payroll            = SocialSecurity.payrollBase(hhs)
      val zus                = SocialSecurity.zusStep(payroll, openingLabor.newDemographics.retirees)
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
          pensionIncome = zus.pensionPayments,
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
      val labor              = LaborEconomics.reconcilePostFirmStep(world, fiscal, openingLabor, firm.ioFirms.filter(Firm.isAlive), firm.households)
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

      val afterFirm = sectorSnapshots(firm.ioFirms)
      val changes   = sectorChangeSummaries(firms, firm.ioFirms)
      val matchPost = laborMatchSnapshot(firm.ioFirms, firm.households)
      println(
        s"m=$month preLaborDemand=${openingLabor.laborDemand} postFirmDemand=${firm.ioFirms.filter(Firm.isAlive).map(Firm.workerCount).sum} employedPre=${openingLabor.employed} employedPost=${labor.employed} unempPost=${matchPost.unemployed} retrainingPost=${matchPost.retraining} bankruptPost=${matchPost.bankrupt} vacanciesPost=${matchPost.vacancies} overstaffedPost=${matchPost.overstaffed} invalidEmp=${matchPost.invalidEmployed} immIn=${openingLabor.newImmig.monthlyInflow} immOut=${openingLabor.newImmig.monthlyOutflow}",
      )
      printHiringSummaries("  pre-step hiring diagnostics:", hiring)
      printSectorTable("  sector deltas after FirmEconomics:", beforeAll, afterFirm)
      printChangeSummaries("  firm-level positive worker changes:", changes)

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
