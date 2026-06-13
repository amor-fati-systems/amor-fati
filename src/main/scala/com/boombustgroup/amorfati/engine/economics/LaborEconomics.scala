package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.World
import com.boombustgroup.amorfati.engine.markets.{LaborMarket, RegionalClearing}
import com.boombustgroup.amorfati.types.*

/** Pure economic logic for labor market — no state mutation, no flows.
  *
  * Computes wages (Phillips curve + expectations + union rigidity), employment,
  * demographics, immigration. Output feeds into flow mechanisms.
  *
  * Extracted from LaborDemographicsStep (Calculus vs Accounting split).
  */
object LaborEconomics:

  private[amorfati] def operationalHiringSlackFactor(laborDemand: Int, availableLabor: Int)(using p: SimParams): Share =
    if laborDemand <= 0 then Share.One
    else
      val raw = Share.fraction(availableLabor, laborDemand) * p.firm.aggregateLaborSlackBuffer
      raw.clamp(p.firm.aggregateLaborSlackFloor, Share.One)

  private[amorfati] def operationalExpansionSlackFactor(
      laborDemand: Int,
      availableLabor: Int,
      monthlyHiringHeadroom: Int,
      newLaborInflow: Int,
  )(using p: SimParams): Share =
    if monthlyHiringHeadroom <= 0 then Share.One
    else
      val expansionPool = Math.max(0, availableLabor - laborDemand) + Math.max(0, newLaborInflow)
      val raw           = Share.fraction(expansionPool, monthlyHiringHeadroom) * p.firm.aggregateLaborSlackBuffer
      raw.clamp(Share.Zero, Share.One)

  private case class ClearedLaborMarket(
      wage: PLN,
      employed: Int,
      regionalWages: Map[Region, PLN],
  )

  private[amorfati] def nairuWagePressure(laborForcePopulation: Int, employed: Int)(using p: SimParams): Coefficient =
    if laborForcePopulation <= 0 then Coefficient.Zero
    else
      val unemployed       = Math.max(0, laborForcePopulation - employed)
      val unemploymentRate = Share.fraction(unemployed, laborForcePopulation)
      (p.monetary.nairu - unemploymentRate).max(Share.Zero).toCoefficient * p.labor.tightLaborWageSensitivity

  case class StepOutput(
      newWage: PLN,
      employed: Int,
      laborDemand: Int,
      wageGrowth: Coefficient,
      operationalHiringSlack: Share = Share.One,
      newImmig: Immigration.State,
      netMigration: Int,
      newDemographics: SocialSecurity.DemographicsState,
      newZus: SocialSecurity.ZusState,
      newNfz: SocialSecurity.NfzState,
      newPpk: SocialSecurity.PpkState,
      rawPpkBondPurchase: PLN,
      newEarmarked: EarmarkedFunds.State,
      living: Vector[Firm.State],
      regionalWages: Map[Region, PLN],
  )

  /** Compatibility alias for older type references; new code should use
    * [[StepOutput]].
    */
  type Output = StepOutput

  def compute(
      w: World,
      firms: Vector[Firm.State],
      households: Vector[Household.State],
      fiscal: FiscalConstraintEconomics.StepOutput,
  )(using p: SimParams): StepOutput =
    val living           = firms.filter(Firm.isAlive)
    val laborDemand      = living.map(f => Firm.workerCount(f)).sum
    val laborForce       = w.laborForcePopulation
    val observedEmployed = Household.countEmployed(households)
    val cleared          = clearLaborMarket(w, fiscal.resWage, laborDemand, laborForce, observedEmployed)
    val availableLabor   = LaborMarket.laborSupplyAtWage(cleared.wage, fiscal.resWage, laborForce)

    // Immigration
    val unempRateForImmig      = w.unemploymentRate(cleared.employed)
    val newImmig               = Immigration.step(w.external.immigration, households, cleared.wage, unempRateForImmig)
    val netMigration           = newImmig.monthlyInflow - newImmig.monthlyOutflow
    val hiringHeadroom         = living.map(f => Firm.monthlyHiringHeadroom(Firm.workerCount(f))).sum
    val operationalHiringSlack = operationalExpansionSlackFactor(
      laborDemand = cleared.employed,
      availableLabor = availableLabor,
      monthlyHiringHeadroom = hiringHeadroom,
      newLaborInflow = newImmig.monthlyInflow,
    )

    // Demographics
    val newDemographics = SocialSecurity.demographicsStep(w.social.demographics, cleared.employed, netMigration, households.length)

    // Wage growth
    val wageGrowth = wageGrowthFrom(w.householdMarket.marketWage, cleared.wage)
    val newNfz     = SocialSecurity.nfzStep(
      cleared.employed,
      cleared.wage,
      newDemographics.workingAgePop,
      newDemographics.retirees,
    )

    StepOutput(
      newWage = cleared.wage,
      employed = cleared.employed,
      laborDemand = laborDemand,
      wageGrowth = wageGrowth,
      operationalHiringSlack = operationalHiringSlack,
      newImmig = newImmig,
      netMigration = netMigration,
      newDemographics = newDemographics,
      newZus = SocialSecurity.ZusState.zero,
      newNfz = newNfz,
      newPpk = SocialSecurity.PpkState.zero,
      rawPpkBondPurchase = PLN.Zero,
      newEarmarked = EarmarkedFunds.State.zero,
      living = living,
      regionalWages = cleared.regionalWages,
    )

  /** Reconcile labor outputs after firm-side separations and matching so
    * downstream blocks use effective post-firm labor demand rather than stale
    * inherited headcount.
    */
  def reconcilePostFirmStep(
      w: World,
      fiscal: FiscalConstraintEconomics.StepOutput,
      pre: StepOutput,
      postLiving: Vector[Firm.State],
      postHouseholds: Vector[Household.State],
  )(using p: SimParams): StepOutput =
    val postLaborDemand    = postLiving.map(Firm.workerCount).sum
    val postLaborForce     = pre.newDemographics.workingAgePop.max(1)
    val realizedEmployment = Household.countEmployed(postHouseholds)
    val cleared            = clearLaborMarket(w, fiscal.resWage, postLaborDemand, postLaborForce, realizedEmployment)
    val employedCap        = Math.min(realizedEmployment, pre.newDemographics.workingAgePop)
    val postAvailableLabor = LaborMarket.laborSupplyAtWage(cleared.wage, fiscal.resWage, postLaborForce)
    val postHiringHeadroom = postLiving.map(f => Firm.monthlyHiringHeadroom(Firm.workerCount(f))).sum
    val newNfz             = SocialSecurity.nfzStep(
      employedCap,
      cleared.wage,
      pre.newDemographics.workingAgePop,
      pre.newDemographics.retirees,
    )
    pre.copy(
      newWage = cleared.wage,
      employed = employedCap,
      laborDemand = postLaborDemand,
      wageGrowth = wageGrowthFrom(w.householdMarket.marketWage, cleared.wage),
      operationalHiringSlack = operationalExpansionSlackFactor(
        laborDemand = employedCap,
        availableLabor = postAvailableLabor,
        monthlyHiringHeadroom = postHiringHeadroom,
        newLaborInflow = 0,
      ),
      newNfz = newNfz,
      living = postLiving,
      regionalWages = cleared.regionalWages,
    )

  private def clearLaborMarket(
      w: World,
      resWage: PLN,
      laborDemand: Int,
      laborForcePopulation: Int,
      observedEmployed: Int,
  )(using p: SimParams): ClearedLaborMarket =
    val (rawWage, rawEmployed, regWages) =
      val rc          = RegionalClearing.clear(w.regionalWages, resWage, laborDemand, laborForcePopulation)
      val natEmployed = LaborMarket.employmentAtWage(rc.nationalWage, resWage, laborDemand, laborForcePopulation)
      (rc.nationalWage, natEmployed, rc.regionalWages)

    val wageAfterExp =
      val expectedNominalInflation = w.mechanisms.expectations.expectedInflation.max(Rate.Zero).toCoefficient
      val expWagePressure          = (p.labor.expWagePassthrough * expectedNominalInflation) / 12
      val tightnessWagePressure    = nairuWagePressure(laborForcePopulation, Math.max(rawEmployed, observedEmployed))
      val expectedWage             = rawWage * expWagePressure.growthMultiplier
      val tightnessFloor           = w.householdMarket.marketWage * tightnessWagePressure.growthMultiplier
      resWage.max(expectedWage.max(tightnessFloor))

    val productivityWage =
      wageAfterExp * p.firm.productivityGrowth.monthly.growthMultiplier

    val newWage =
      val aggDensity =
        p.sectorDefs.zipWithIndex.map((s, i) => s.share * p.labor.unionDensity(i)).foldLeft(Share.Zero)(_ + _)
      val decline    = w.householdMarket.marketWage - productivityWage
      resWage.max(productivityWage + decline * p.labor.unionRigidity * aggDensity)

    val employed = Math.min(rawEmployed, laborForcePopulation)

    ClearedLaborMarket(newWage, employed, regWages)

  private def wageGrowthFrom(prevWage: PLN, newWage: PLN): Coefficient =
    if prevWage > PLN.Zero then (newWage / prevWage - Scalar.One).toCoefficient else Coefficient.Zero
