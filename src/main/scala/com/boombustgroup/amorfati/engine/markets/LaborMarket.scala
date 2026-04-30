package com.boombustgroup.amorfati.engine.markets

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.agents.Region
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.mechanisms.SectoralMobility
import com.boombustgroup.amorfati.types.*

import com.boombustgroup.amorfati.random.RandomStream

/** Labor market: wage determination, separations, job search, wage updating.
  *
  * Wage adjustment via excess demand (Phillips curve); individual wages
  * heterogeneous by sector, skill, health, education, and immigrant status,
  * normalized so mean employed wage equals the macro market wage.
  *
  * Separations: workers displaced by automation (Traditional→Hybrid/Automated)
  * or firm bankruptcy, with education-based retention priority. Job search:
  * skill-ranked matching with same-sector priority and cross-sector friction
  * penalty (when sectoral mobility enabled).
  *
  * Separations use contract protection and automation vulnerability before
  * skill tie-breaks.
  *
  * Calibration: 2026-04-30 labor-market bridge prior and NBP wage-statistics
  * bridge prior.
  */
object LaborMarket:

  private val MaxMonthlyWageIncrease = Coefficient.fromRaw(300L)
  private val MaxMonthlyWageDecrease = Coefficient.fromRaw(-200L)

  /** Aggregate wage clearing result. */
  case class WageResult(wage: PLN, employed: Int)

  /** Job search matching result. */
  case class JobSearchResult(households: Vector[Household.State], crossSectorHires: Int)

  private enum WorkerLossKind:
    case Bankruptcy, Automation, WorkforceReduction

  /** Reconcile firm headcount with actually employed households after matching.
    *
    * Firm decisions create target headcount and vacancies. If the labor market
    * does not fill every vacancy, production capacity must reflect staffed
    * workers rather than still counting the unfilled target as employed labor.
    */
  def syncFirmStaffing(firms: Vector[Firm.State], households: Vector[Household.State]): Vector[Firm.State] =
    val staffedCounts = households
      .flatMap: hh =>
        hh.status match
          case HhStatus.Employed(fid, _, _) => Some(fid)
          case _                            => None
      .groupMapReduce(identity)(_ => 1)(_ + _)

    firms.map: firm =>
      if !Firm.isAlive(firm) then firm
      else
        val filled        = staffedCounts.getOrElse(firm.id, 0)
        val startupFilled = if Firm.isInStartup(firm) then filled.min(firm.startupTargetWorkers) else firm.startupFilledWorkers
        val syncedTech    = firm.tech match
          case TechState.Traditional(_) => TechState.Traditional(filled)
          case TechState.Hybrid(_, eff) => TechState.Hybrid(filled, eff)
          case other                    => other
        firm.copy(startupFilledWorkers = startupFilled, tech = syncedTech)

  // --- Aggregate wage clearing ---

  /** Logistic labor supply curve: fraction of population willing to work at
    * given wage. Steepness controlled by p.household.laborSupplySteepness.
    */
  private def laborSupplyRatio(wage: PLN, resWage: PLN)(using p: SimParams): Share =
    if resWage <= PLN.Zero then if wage > PLN.Zero then Share.One else Share.Zero
    else
      val wageGap     = wage.ratioTo(resWage).toCoefficient - Coefficient.One
      val slope       = p.household.laborSupplySteepness * wageGap
      val denominator = Multiplier.One + (-slope).exp
      Multiplier.One.ratioTo(denominator).toShare

  /** Aggregate wage clearing: adjust market wage via excess demand, then
    * compute employment. New wage = max(reservationWage, prevWage × (1 +
    * excessDemand × adjSpeed)).
    */
  def updateLaborMarket(prevWage: PLN, resWage: PLN, laborDemand: Int, totalPopulation: Int)(using
      p: SimParams,
  ): WageResult =
    if totalPopulation <= 0 then return WageResult(prevWage.max(resWage), 0)
    val supplyAtPrev   = laborSupplyRatio(prevWage, resWage).applyTo(totalPopulation)
    val excessDemand   = (laborDemand - supplyAtPrev).ratioTo(totalPopulation).toCoefficient
    val rawWageAdj     = excessDemand * p.household.wageAdjSpeed
    val boundedWageAdj = rawWageAdj.max(MaxMonthlyWageDecrease).min(MaxMonthlyWageIncrease)
    val wageGrowthMult = boundedWageAdj.growthMultiplier
    val newWage        = resWage.max(prevWage * wageGrowthMult)
    val newSupply      = laborSupplyRatio(newWage, resWage).applyTo(totalPopulation)
    val employed       = Math.min(laborDemand, newSupply)
    WageResult(newWage, employed)

  /** Employment implied by an already-cleared wage. Used when another block has
    * already updated wages and we only need the matching employment level.
    */
  def employmentAtWage(wage: PLN, resWage: PLN, laborDemand: Int, totalPopulation: Int)(using
      p: SimParams,
  ): Int =
    val supply = laborSupplyAtWage(wage, resWage, totalPopulation)
    Math.min(laborDemand, supply)

  def laborSupplyAtWage(wage: PLN, resWage: PLN, totalPopulation: Int)(using p: SimParams): Int =
    laborSupplyRatio(wage, resWage).applyTo(totalPopulation)

  // --- Separations ---

  /** Separate workers from firms that automated, downsized, or went bankrupt
    * this step. Contract protection and automation vulnerability shape
    * retention before skill tie-breaks. Returns updated households with newly
    * unemployed workers.
    */
  def separations(
      households: Vector[Household.State],
      prevFirms: Vector[Firm.State],
      newFirms: Vector[Firm.State],
  )(using SimParams): Vector[Household.State] =
    val lossKinds = workerLossKinds(prevFirms, newFirms)
    if lossKinds.isEmpty then households
    else
      val lostFirms = lossKinds.keySet
      val counts    = retainCounts(newFirms, lostFirms)
      val retained  = retainSets(households, lostFirms, counts, lossKinds)
      applySeparations(households, lostFirms, retained)

  // --- Job search ---

  /** Job search: unemployed households bid for open positions. Matching:
    * highest effective skill (skill × (1 - healthPenalty)) fills vacancies
    * first. Same-sector gets priority; cross-sector hires incur friction
    * penalty when sectoral mobility is enabled.
    *
    * When regionalWages is non-empty: 3-tier matching (region+sector →
    * region+any → cross-region). Hire wage uses regional wage. Cross-region
    * hire relocates the household.
    */
  def jobSearch(
      households: Vector[Household.State],
      firms: Vector[Firm.State],
      marketWage: PLN,
      rng: RandomStream,
      regionalWages: Map[Region, PLN] = Map.empty,
      eligibleFirmIds: Set[FirmId] = Set.empty,
      maxHires: Option[Int] = None,
      priorityHouseholdIds: Set[HhId] = Set.empty,
  )(using p: SimParams): JobSearchResult =
    val vacancies = computeVacancies(households, firms, eligibleFirmIds)
    if vacancies.isEmpty then JobSearchResult(households, 0)
    else if maxHires.exists(_ <= 0) then JobSearchResult(households, 0)
    else if regionalWages.nonEmpty then matchWorkersRegional(households, firms, vacancies, marketWage, regionalWages, rng, maxHires, priorityHouseholdIds)
    else matchWorkers(households, firms, vacancies, marketWage, rng, maxHires, priorityHouseholdIds)

  def monthlyMatchingCapacity(households: Vector[Household.State], laborForcePopulation: Int)(using p: SimParams): Int =
    val unemployed = households.count(_.status.isInstanceOf[HhStatus.Unemployed])
    if unemployed <= 0 then 0
    else
      val structuralPool = Math.min(unemployed, p.monetary.nairu.ceilApplyTo(laborForcePopulation.max(1)))
      val cyclicalPool   = Math.max(0, unemployed - structuralPool)
      val structural     = p.labor.structuralMatchRate.ceilApplyTo(structuralPool)
      val cyclical       = p.labor.cyclicalMatchRate.ceilApplyTo(cyclicalPool)
      Math.min(unemployed, structural + cyclical)

  // --- Wage updating ---

  /** Update wages for all employed households based on current market wage.
    * Individual wages are heterogeneous (sector × skill × health × immigrant
    * discount × education premium) but normalized so mean employed wage =
    * marketWage (macro consistency).
    */
  def updateWages(households: Vector[Household.State], firms: Vector[Firm.State], marketWage: PLN)(using
      p: SimParams,
  ): Vector[Household.State] =
    val rawWages = households.map(rawRelativeWage(_, firms))
    val rawMean  = employedMeanRawWage(households, rawWages)
    val scale    = if rawMean > Multiplier.Zero then Multiplier.One.ratioTo(rawMean).toMultiplier else Multiplier.One
    applyNormalizedWages(households, rawWages, marketWage, scale)

  // --- Shared helpers ---

  /** Effective skill: skill × (1 - healthPenalty). */
  private def effectiveSkill(hh: Household.State): Share =
    hh.skill * (Share.One - hh.healthPenalty)

  /** Infer sector for an unemployed household: last sector if known, else
    * fallback based on household id.
    */
  private def effectivePrevSector(hh: Household.State, firms: Vector[Firm.State]): SectorIdx =
    if hh.lastSectorIdx.toInt >= 0 then hh.lastSectorIdx
    else firms(hh.id.toInt % firms.length).sector

  // --- Separation helpers ---

  /** Identify firms that lost workers: bankruptcy, automation, or workforce
    * reduction.
    */
  private def workerLossKinds(
      prevFirms: Vector[Firm.State],
      newFirms: Vector[Firm.State],
  )(using SimParams): Map[FirmId, WorkerLossKind] =
    newFirms.indices
      .flatMap: i =>
        workerLossKind(prevFirms(i), newFirms(i)).map(newFirms(i).id -> _)
      .toMap

  private def workerLossKind(prev: Firm.State, curr: Firm.State)(using SimParams): Option[WorkerLossKind] =
    val wentBankrupt   = Firm.isAlive(prev) && !Firm.isAlive(curr)
    val newlyAutomated = (prev.tech, curr.tech) match
      case (_: TechState.Automated, _) => false
      case (_, _: TechState.Automated) => true
      case _                           => false
    val reducedWorkers = Firm.isAlive(prev) && Firm.isAlive(curr) && Firm.workerCount(curr) < Firm.workerCount(prev)
    if wentBankrupt then Some(WorkerLossKind.Bankruptcy)
    else if newlyAutomated then Some(WorkerLossKind.Automation)
    else if reducedWorkers then Some(WorkerLossKind.WorkforceReduction)
    else None

  /** How many workers each affected firm retains. */
  private def retainCounts(
      newFirms: Vector[Firm.State],
      lostFirms: Set[FirmId],
  )(using SimParams): Map[FirmId, Int] =
    newFirms.collect {
      case f if lostFirms.contains(f.id) =>
        f.tech match
          case _: TechState.Automated => f.id -> Firm.skeletonCrew(f)
          case _                      => f.id -> Firm.workerCount(f)
    }.toMap

  /** Build retain sets for affected firms. Automation prioritizes low
    * AI/routine displacement risk; other reductions prioritize contract
    * protection.
    */
  private def retainSets(
      households: Vector[Household.State],
      lostFirms: Set[FirmId],
      counts: Map[FirmId, Int],
      lossKinds: Map[FirmId, WorkerLossKind],
  ): Map[FirmId, Set[Int]] =
    households.zipWithIndex
      .flatMap: (hh, idx) =>
        hh.status match
          case HhStatus.Employed(firmId, _, _) if lostFirms.contains(firmId) => Some(firmId -> idx)
          case _                                                             => None
      .groupMap(_._1)(_._2)
      .map: (firmId, indices) =>
        val lossKind  = lossKinds(firmId)
        val sorted    =
          indices.sortWith: (left, right) =>
            val leftHh  = households(left)
            val rightHh = households(right)
            shouldRetainBefore(leftHh, rightHh, lossKind)
        val maxRetain = counts.getOrElse(firmId, 0)
        firmId -> sorted.take(maxRetain).toSet

  private def shouldRetainBefore(left: Household.State, right: Household.State, lossKind: WorkerLossKind): Boolean =
    val leftProtection  = ContractType.firingPriority(left.contractType)
    val rightProtection = ContractType.firingPriority(right.contractType)
    val leftRisk        = displacementRisk(left)
    val rightRisk       = displacementRisk(right)
    lossKind match
      case WorkerLossKind.Automation =>
        if leftRisk != rightRisk then leftRisk < rightRisk
        else if leftProtection != rightProtection then leftProtection > rightProtection
        else left.skill > right.skill
      case _                         =>
        if leftProtection != rightProtection then leftProtection > rightProtection
        else if leftRisk != rightRisk then leftRisk < rightRisk
        else left.skill > right.skill

  private def displacementRisk(hh: Household.State): Multiplier =
    hh.taskRoutineness * ContractType.aiVulnerability(hh.contractType)

  /** Apply separations: retained workers stay, others become unemployed. */
  private def applySeparations(
      households: Vector[Household.State],
      lostFirms: Set[FirmId],
      retained: Map[FirmId, Set[Int]],
  ): Vector[Household.State] =
    households.zipWithIndex.map: (hh, idx) =>
      hh.status match
        case HhStatus.Employed(firmId, sectorIdx, _) if lostFirms.contains(firmId) =>
          if retained.getOrElse(firmId, Set.empty).contains(idx) then hh
          else hh.copy(status = HhStatus.Unemployed(0), lastSectorIdx = sectorIdx)
        case _                                                                     => hh

  // --- Vacancy & matching helpers ---

  /** Build vacancy map: firmId → number of open positions. */
  private def computeVacancies(
      households: Vector[Household.State],
      firms: Vector[Firm.State],
      eligibleFirmIds: Set[FirmId],
  )(using SimParams): Map[FirmId, Int] =
    val workerCounts: Map[FirmId, Int] = households
      .flatMap: hh =>
        hh.status match
          case HhStatus.Employed(fid, _, _) => Some(fid)
          case _                            => None
      .groupMapReduce(identity)(_ => 1)(_ + _)
    firms
      .filter(Firm.isAlive)
      .filter(f => eligibleFirmIds.isEmpty || eligibleFirmIds.contains(f.id))
      .flatMap: f =>
        val targetWorkers =
          if Firm.isInStartup(f) then Math.max(Firm.workerCount(f), f.startupTargetWorkers)
          else Firm.workerCount(f)
        val needed        = targetWorkers - workerCounts.getOrElse(f.id, 0)
        if needed > 0 then Some(f.id -> needed) else None
      .toMap

  /** Accumulator for the pure-FP matching fold. */
  private case class MatchState(
      hired: Map[Int, Household.State],
      vacancies: Map[FirmId, Int],
      crossSectorHires: Int,
  )

  /** Core matching: rank unemployed by effective skill, assign to vacancies
    * with same-sector priority and cross-sector friction penalty.
    */
  private def matchWorkers(
      households: Vector[Household.State],
      firms: Vector[Firm.State],
      vacancies: Map[FirmId, Int],
      marketWage: PLN,
      rng: RandomStream,
      maxHires: Option[Int],
      priorityHouseholdIds: Set[HhId],
  )(using p: SimParams): JobSearchResult =
    val ranked          = rankUnemployed(households, maxHires, priorityHouseholdIds)
    val firmsBySector   = vacancies.keys.groupBy(fid => firms(fid.toInt).sector)
    val firmsByPriority = vacancies.keys.toVector.sortBy(fid => p.sectorDefs(firms(fid.toInt).sector.toInt).sigma)(using summon[Ordering[Sigma]].reverse)

    val init   = MatchState(Map.empty, vacancies, 0)
    val result = ranked.foldLeft(init): (st, idx) =>
      if st.vacancies.isEmpty then st
      else tryHire(st, idx, households(idx), firms, firmsBySector, firmsByPriority, marketWage, rng)

    val updated = households.zipWithIndex.map((hh, i) => result.hired.getOrElse(i, hh))
    JobSearchResult(updated, result.crossSectorHires)

  /** Regional matching: 3-tier (region+sector → region+any → cross-region).
    * Pre-groups firms by (sector, region) for O(1) lookup per hire attempt.
    */
  private def matchWorkersRegional(
      households: Vector[Household.State],
      firms: Vector[Firm.State],
      vacancies: Map[FirmId, Int],
      marketWage: PLN,
      regionalWages: Map[Region, PLN],
      rng: RandomStream,
      maxHires: Option[Int],
      priorityHouseholdIds: Set[HhId],
  )(using p: SimParams): JobSearchResult =
    val ranked = rankUnemployed(households, maxHires, priorityHouseholdIds)

    // Pre-grouped maps for O(1) tier lookups
    val firmsBySectorRegion: Map[(SectorIdx, Region), Vector[FirmId]] =
      vacancies.keys.toVector.groupBy(fid => (firms(fid.toInt).sector, firms(fid.toInt).region))
    val firmsByRegion: Map[Region, Vector[FirmId]]                    =
      vacancies.keys.toVector.groupBy(fid => firms(fid.toInt).region)
    val firmsByPriority                                               =
      vacancies.keys.toVector.sortBy(fid => p.sectorDefs(firms(fid.toInt).sector.toInt).sigma)(using summon[Ordering[Sigma]].reverse)

    val init   = MatchState(Map.empty, vacancies, 0)
    val result = ranked.foldLeft(init): (st, idx) =>
      if st.vacancies.isEmpty then st
      else tryHireRegional(st, idx, households(idx), firms, firmsBySectorRegion, firmsByRegion, firmsByPriority, marketWage, regionalWages, rng)

    val updated = households.zipWithIndex.map((hh, i) => result.hired.getOrElse(i, hh))
    JobSearchResult(updated, result.crossSectorHires)

  /** 3-tier hire: same region+sector → same region any sector → cross-region.
    */
  private def tryHireRegional(
      st: MatchState,
      idx: Int,
      hh: Household.State,
      firms: Vector[Firm.State],
      firmsBySectorRegion: Map[(SectorIdx, Region), Vector[FirmId]],
      firmsByRegion: Map[Region, Vector[FirmId]],
      firmsByPriority: Vector[FirmId],
      marketWage: PLN,
      regionalWages: Map[Region, PLN],
      rng: RandomStream,
  )(using p: SimParams): MatchState =
    val prevSector = effectivePrevSector(hh, firms)
    val hhRegion   = hh.region

    // Tier 1: same region + same sector
    val tier1 = firmsBySectorRegion
      .getOrElse((prevSector, hhRegion), Vector.empty)
      .find(fid => st.vacancies.contains(fid))

    // Tier 2: same region + any sector
    val tier2 = tier1.orElse(
      firmsByRegion
        .getOrElse(hhRegion, Vector.empty)
        .find(fid => st.vacancies.contains(fid)),
    )

    // Tier 3: cross-region fallback
    val bestFirm = tier2.orElse(firmsByPriority.find(fid => st.vacancies.contains(fid)))

    bestFirm match
      case None      => st
      case Some(fid) =>
        val firm          = firms(fid.toInt)
        val isCrossSector = firm.sector.toInt != prevSector.toInt
        val regWage       = regionalWages.getOrElse(firm.region, marketWage)
        val wage          = computeHireWage(hh, firm, regWage, prevSector, isCrossSector)
        val newHh         = hh.copy(
          status = HhStatus.Employed(fid, firm.sector, wage),
          lastSectorIdx = firm.sector,
          contractType = ContractType.sampleForSector(firm.sector, rng),
          region = firm.region,
        )
        val remaining     = st.vacancies(fid) - 1
        val newVacancies  = if remaining <= 0 then st.vacancies - fid else st.vacancies.updated(fid, remaining)
        MatchState(st.hired.updated(idx, newHh), newVacancies, st.crossSectorHires + (if isCrossSector then 1 else 0))

  /** Rank unemployed households by effective skill descending. */
  private def rankUnemployed(households: Vector[Household.State], maxHires: Option[Int], priorityHouseholdIds: Set[HhId]): Vector[Int] =
    val ranked = households.zipWithIndex
      .flatMap: (hh, idx) =>
        hh.status match
          case _: HhStatus.Unemployed => Some(idx)
          case _                      => None
      .sortWith: (left, right) =>
        val leftPriority  = priorityHouseholdIds.contains(households(left).id)
        val rightPriority = priorityHouseholdIds.contains(households(right).id)
        if leftPriority != rightPriority then leftPriority
        else effectiveSkill(households(left)) > effectiveSkill(households(right))
    maxHires match
      case Some(limit) => ranked.take(limit.max(0))
      case None        => ranked

  /** Try hiring one unemployed household into a vacancy. */
  private def tryHire(
      st: MatchState,
      idx: Int,
      hh: Household.State,
      firms: Vector[Firm.State],
      firmsBySector: Map[SectorIdx, Iterable[FirmId]],
      firmsByPriority: Vector[FirmId],
      marketWage: PLN,
      rng: RandomStream,
  )(using p: SimParams): MatchState =
    val prevSector = effectivePrevSector(hh, firms)
    val bestFirm   = firmsBySector
      .getOrElse(prevSector, Iterable.empty)
      .find(fid => st.vacancies.contains(fid))
      .orElse(firmsByPriority.find(fid => st.vacancies.contains(fid)))
    bestFirm match
      case None      => st
      case Some(fid) => hire(st, idx, hh, firms(fid.toInt), fid, prevSector, marketWage, rng)

  /** Execute a hire: update household, decrement vacancy, count cross-sector.
    */
  private def hire(
      st: MatchState,
      idx: Int,
      hh: Household.State,
      firm: Firm.State,
      firmId: FirmId,
      prevSector: SectorIdx,
      marketWage: PLN,
      rng: RandomStream,
  )(using p: SimParams): MatchState =
    val isCrossSector = firm.sector.toInt != prevSector.toInt
    val wage          = computeHireWage(hh, firm, marketWage, prevSector, isCrossSector)
    val newHh         = hh.copy(
      status = HhStatus.Employed(firmId, firm.sector, wage),
      lastSectorIdx = firm.sector,
      contractType = ContractType.sampleForSector(firm.sector, rng),
    )
    val remaining     = st.vacancies(firmId) - 1
    val newVacancies  = if remaining <= 0 then st.vacancies - firmId else st.vacancies.updated(firmId, remaining)
    MatchState(st.hired.updated(idx, newHh), newVacancies, st.crossSectorHires + (if isCrossSector then 1 else 0))

  /** Compute individual wage for a new hire. */
  private def computeHireWage(
      hh: Household.State,
      firm: Firm.State,
      marketWage: PLN,
      prevSector: SectorIdx,
      isCrossSector: Boolean,
  )(using p: SimParams): PLN =
    val sectorMult = Firm.effectiveWageMult(firm.sector)
    val penalty    =
      if isCrossSector
      then SectoralMobility.crossSectorWagePenalty(p.labor.frictionMatrix(prevSector.toInt)(firm.sector.toInt))
      else Multiplier.One
    val scarMult   = (Share.One - hh.wageScar).toMultiplier
    val eduMult    = p.social.eduWagePremium(hh.education)
    marketWage * sectorMult * effectiveSkill(hh) * penalty * eduMult * scarMult

  // --- Wage helpers ---

  /** Raw relative wage weight for a household (unnormalized). */
  private def rawRelativeWage(hh: Household.State, firms: Vector[Firm.State])(using p: SimParams): Multiplier =
    hh.status match
      case HhStatus.Employed(firmId, sectorIdx, _) =>
        val immigrantMult = if hh.isImmigrant then (Share.One - p.immigration.wageDiscount).toMultiplier else Multiplier.One
        val aiMult        = aiComplementFactor(hh, firms(firmId.toInt))
        val scarMult      = (Share.One - hh.wageScar).toMultiplier
        Firm.effectiveWageMult(sectorIdx) * effectiveSkill(hh) * immigrantMult *
          p.social.eduWagePremium(hh.education) * aiMult * scarMult
      case _                                       => Multiplier.Zero

  /** AI-complement wage premium: cognitive workers in automated/hybrid firms
    * get a wage boost (1 - routineness) × complementPremium. Routine workers
    * get no boost. Acemoglu & Restrepo 2020.
    */
  private def aiComplementFactor(hh: Household.State, firm: Firm.State)(using p: SimParams): Multiplier =
    firm.tech match
      case _: TechState.Automated | _: TechState.Hybrid =>
        val cognitiveShare = Share.One - hh.taskRoutineness
        Multiplier.One + (p.labor.sbtcComplementPremium * cognitiveShare).toMultiplier
      case _                                            => Multiplier.One

  /** Mean raw wage across employed households (Kahan summation). */
  private def employedMeanRawWage(
      households: Vector[Household.State],
      rawWages: Vector[Multiplier],
  ): Multiplier =
    val employedIndices = households.indices.flatMap: i =>
      households(i).status match
        case _: HhStatus.Employed => Some(i)
        case _                    => None
    if employedIndices.nonEmpty
    then employedIndices.foldLeft(Multiplier.Zero)((acc, i) => acc + rawWages(i)).divideBy(employedIndices.length)
    else Multiplier.One

  /** Apply normalized wages: each employed gets marketWage × (rawWeight ×
    * scale).
    */
  private def applyNormalizedWages(
      households: Vector[Household.State],
      rawWages: Vector[Multiplier],
      marketWage: PLN,
      scale: Multiplier,
  ): Vector[Household.State] =
    households.zipWithIndex.map: (hh, i) =>
      hh.status match
        case HhStatus.Employed(firmId, sectorIdx, _) =>
          hh.copy(status = HhStatus.Employed(firmId, sectorIdx, marketWage * (rawWages(i) * scale)))
        case _                                       => hh
