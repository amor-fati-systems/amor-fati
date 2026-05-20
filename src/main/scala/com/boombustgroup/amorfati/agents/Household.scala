package com.boombustgroup.amorfati.agents

import com.boombustgroup.amorfati.config.*
import com.boombustgroup.amorfati.engine.World
import com.boombustgroup.amorfati.engine.mechanisms.SectoralMobility
import com.boombustgroup.amorfati.init.InitRandomness
import com.boombustgroup.amorfati.networks.Network
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.amorfati.util.Distributions

import com.boombustgroup.amorfati.fp.FixedPointBase

// ---- Top-level types (widely referenced, kept flat) ----

/** Employment/activity status of an individual household. */
enum HhStatus:
  case Employed(firmId: FirmId, sectorIdx: SectorIdx, wage: PLN)       // employed at firm, earning wage
  case Unemployed(monthsUnemployed: Int)                               // unemployed for N months (zasilek eligible)
  case Retraining(monthsLeft: Int, targetSector: SectorIdx, cost: PLN) // transitioning to target sector
  case Bankrupt // absorbing barrier

/** Per-bank lending and deposit rates for individual HH mode. */
case class BankRates(
    lendingRates: Vector[Rate], // annual lending rate per bank (index = BankId)
    depositRates: Vector[Rate], // annual deposit rate per bank (index = BankId)
)

/** Per-bank HH flow accumulator for multi-bank mode (one per BankId). */
case class PerBankFlow(
    income: PLN,                      // total income (incl. deposit interest)
    consumption: PLN,                 // total consumption (goods + rent)
    debtService: PLN,                 // total mortgage/secured debt service
    mortgageInterest: PLN,            // mortgage interest routed to this bank
    depositInterest: PLN,             // total deposit interest paid
    consumerDebtService: PLN,         // consumer (unsecured) debt service
    consumerOrigination: PLN,         // gross underwritten loan plus same-month bridge origination
    consumerApprovedOrigination: PLN, // underwritten consumer credit originated by the DTI rule
    liquidityShortfallFinancing: PLN, // same-month bridge/write-off preventing negative deposits
    consumerDefault: PLN,             // consumer defaults plus same-month bridge charge-offs
    consumerPrincipal: PLN,           // consumer loan principal repaid
)

object PerBankFlow:
  val zero: PerBankFlow = PerBankFlow(PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero)

object Household:
  def isEmployed(hh: State): Boolean =
    hh.status match
      case HhStatus.Employed(_, _, _) => true
      case _                          => false

  def countEmployed(households: Vector[State]): Int =
    households.count(isEmployed)

  private inline def scaledDivRaw(numerator: BigInt, denominator: BigInt): Long =
    if denominator == 0 then 0L
    else
      val scaled         = numerator * BigInt(FixedPointBase.Scale)
      val quotient       = scaled / denominator
      val remainder      = (scaled % denominator).abs
      val denominatorAbs = denominator.abs
      val twiceRemainder = remainder * 2
      if twiceRemainder < denominatorAbs then quotient.toLong
      else
        val resultSign = scaled.signum * denominator.signum
        if twiceRemainder > denominatorAbs then (quotient + resultSign).toLong
        else if quotient % 2 == 0 then quotient.toLong
        else (quotient + resultSign).toLong

  // ---- Named constants ----

  // MPC sampling bounds (Beta distribution, NBP household survey)
  private val MpcFloor   = Share.decimal(5, 1)
  private val MpcCeiling = Share.decimal(98, 2)

  // Social network precautionary saving
  private val NeighborDistressThreshold    = Share.decimal(30, 2) // fraction of neighbors in distress that triggers adj
  private val NeighborDistressConsAdj      = Share.decimal(90, 2) // consumption multiplier when distress exceeds threshold
  private val NeighborDistressRetrainBoost = Share.decimal(5, 2)  // additional retraining prob when neighbors distressed

  // Labor market
  private val UnemploymentRetrainingThreshold = 6 // months unemployed before eligible for retraining
  private val PostFailedRetrainingMonths      = 7 // months assigned after failed retraining (duration + 1)

  // Consumer credit
  private val DisposableWageThreshold = Share.decimal(3, 1) // disposable/wage ratio below which HH may borrow
  private val MinConsumerLoanSize     = PLN(100)            // minimum loan size (PLN)
  private val ConsumerDebtInitFrac    = Share.decimal(3, 1) // init consumer debt as fraction of mortgage draw

  // Init sampling
  private val GpwEquityInitFrac     = Share.decimal(5, 2)  // fraction of savings allocated to GPW equity at init
  private val SectorSkillBonusCoeff = Scalar.decimal(2, 2) // coefficient for sector-specific skill bonus (log sigma)
  private val SectorSkillBonusMax   = Scalar.decimal(1, 1) // maximum sector-specific skill bonus

  // Aggregates
  private val ImportRatioCap   = Share.decimal(65, 2) // cap on import ratio applied to goods consumption
  private val PovertyRate50Pct = Share.decimal(50, 2) // poverty line at 50% of median income (EU AROP)
  private val PovertyRate30Pct = Share.decimal(30, 2) // poverty line at 30% of median income (deep poverty)
  private val ConsumptionP10   = Share.decimal(10, 2) // P10 percentile index
  private val ConsumptionP90   = Share.decimal(90, 2) // P90 percentile index

  // ---- Individual household ----

  /** Full state of a single household agent, carried across simulation months.
    */
  case class State(
      id: HhId,                                            // unique household identifier
      monthlyRent: PLN,                                    // monthly rent payment (to landlord / housing market)
      skill: Share,                                        // labor productivity multiplier [0,1], decays during unemployment
      healthPenalty: Share,                                // cumulative health penalty from long-term unemployment (scarring)
      mpc: Share,                                          // marginal propensity to consume (Beta-sampled at init)
      status: HhStatus,                                    // current employment/activity status
      socialNeighbors: Array[HhId],                        // Watts-Strogatz social network neighbor IDs
      bankId: BankId,                                      // index into the explicit bank vector (multi-bank)
      lastSectorIdx: SectorIdx,                            // last sector employed in (-1 = never)
      isImmigrant: Boolean,                                // immigrant status for wage discount + remittances
      numDependentChildren: Int,                           // children ≤ 18 for 800+ social transfers
      education: Int,                                      // education level: 0=Primary, 1=Vocational, 2=Secondary, 3=Tertiary
      taskRoutineness: Share,                              // how routine is this worker's task bundle [0,1] (Acemoglu & Restrepo 2020)
      wageScar: Share,                                     // persistent wage penalty from unemployment spell (Jacobson et al. 1993)
      financialDistressMonths: Int = 0,                    // consecutive months of deep financial distress
      contractType: ContractType = ContractType.Permanent, // employment contract type (Kodeks Pracy / umowa zlecenie / B2B)
      region: Region = Region.Central,                     // NUTS-1 macroregion (geographic labor market)
  )

  /** Ledger-contracted household financial stocks carried through household
    * execution.
    */
  case class FinancialStocks(
      demandDeposit: PLN, // non-negative bank demand deposits owned by the household
      mortgageLoan: PLN,  // outstanding secured mortgage principal
      consumerLoan: PLN,  // outstanding unsecured consumer-loan principal
      equity: PLN,        // listed equity owned by the household
  )

  /** Diagnostic attribution of residual household liquidity settlement.
    * Components sum to `HouseholdLiquidity_ShortfallFinancing`, which is
    * charged off in the same month instead of becoming ordinary consumer debt.
    */
  case class LiquidityShortfallComponents(
      consumptionShortfall: PLN, // same-month bridge/write-off attributable to modeled consumption outflow
      rentArrears: PLN,          // same-month bridge/write-off attributable to rent/housing payment
      mortgageArrears: PLN,      // same-month bridge/write-off attributable to secured mortgage debt service
      consumerDebtArrears: PLN,  // same-month bridge/write-off attributable to unsecured consumer debt service
      temporaryOverdraft: PLN,   // same-month bridge/write-off for other current-month liquidity gaps
  ):
    def total: PLN =
      consumptionShortfall + rentArrears + mortgageArrears + consumerDebtArrears + temporaryOverdraft

    def +(other: LiquidityShortfallComponents): LiquidityShortfallComponents =
      LiquidityShortfallComponents(
        consumptionShortfall = consumptionShortfall + other.consumptionShortfall,
        rentArrears = rentArrears + other.rentArrears,
        mortgageArrears = mortgageArrears + other.mortgageArrears,
        consumerDebtArrears = consumerDebtArrears + other.consumerDebtArrears,
        temporaryOverdraft = temporaryOverdraft + other.temporaryOverdraft,
      )

  object LiquidityShortfallComponents:
    val Zero: LiquidityShortfallComponents =
      LiquidityShortfallComponents(PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero)

  /** Aggregate statistics computed from individual households (Paper-06). */
  case class Aggregates(
      employed: Int,                             // count of employed HH
      unemployed: Int,                           // count of unemployed HH
      retraining: Int,                           // count of HH in retraining
      bankrupt: Int,                             // count of bankrupt HH
      totalIncome: PLN,                          // aggregate income (wages + benefits + interest + transfers)
      consumption: PLN,                          // aggregate consumption (goods + rent)
      domesticConsumption: PLN,                  // domestic component of consumption
      importConsumption: PLN,                    // import component of consumption
      marketWage: PLN,                           // current market-clearing wage
      reservationWage: PLN,                      // minimum acceptable wage for job search
      giniIndividual: Share,                     // Gini of income distribution
      giniWealth: Share,                         // Gini of liquid demand-deposit wealth
      meanSavings: PLN,                          // mean liquid demand deposits across all HH
      medianSavings: PLN,                        // median liquid demand deposits across all HH
      povertyRate50: Share,                      // share with income < 50% median (EU AROP)
      bankruptcyRate: Share,                     // share of bankrupt HH
      meanSkill: Share,                          // mean skill of alive (non-bankrupt) HH
      meanHealthPenalty: Share,                  // mean health scarring of alive HH
      retrainingAttempts: Int,                   // retraining attempts this month
      retrainingSuccesses: Int,                  // successful retraining completions this month
      consumptionP10: PLN,                       // 10th percentile of consumption
      consumptionP50: PLN,                       // median consumption
      consumptionP90: PLN,                       // 90th percentile of consumption
      meanMonthsToRuin: Scalar,                  // mean months until bankruptcy (placeholder)
      povertyRate30: Share,                      // share with income < 30% median (deep poverty)
      totalRent: PLN,                            // aggregate rent payments
      totalDebtService: PLN,                     // aggregate secured debt service
      totalUnempBenefits: PLN,                   // aggregate unemployment benefits paid
      totalDepositInterest: PLN,                 // aggregate deposit interest received
      crossSectorHires: Int,                     // cross-sector hires this month
      voluntaryQuits: Int,                       // voluntary quits (cross-sector search)
      sectorMobilityRate: Share,                 // fraction employed in different sector than last
      totalRemittances: PLN,                     // aggregate remittances sent abroad
      totalPit: PLN,                             // aggregate PIT paid
      totalSocialTransfers: PLN,                 // aggregate 800+ social transfers
      totalConsumerDebtService: PLN,             // aggregate consumer debt service
      totalConsumerOrigination: PLN,             // aggregate gross consumer-loan and bridge origination
      totalConsumerApprovedOrigination: PLN,     // aggregate underwritten consumer-credit origination
      totalLiquidityShortfallFinancing: PLN,     // aggregate same-month bridge/write-off for liquidity gaps
      totalConsumerDefault: PLN,                 // aggregate consumer defaults plus bridge charge-offs
      totalConsumerPrincipal: PLN,               // aggregate consumer loan principal repaid
      totalConsumptionShortfall: PLN = PLN.Zero, // shortfall component attributed to consumption
      totalRentArrears: PLN = PLN.Zero,          // shortfall component attributed to rent
      totalMortgageArrears: PLN = PLN.Zero,      // shortfall component attributed to mortgage service
      totalConsumerDebtArrears: PLN = PLN.Zero,  // shortfall component attributed to consumer debt service
      totalTemporaryOverdraft: PLN = PLN.Zero,   // shortfall component attributed to other liquidity gaps
      totalMortgagePrincipal: PLN = PLN.Zero,    // aggregate secured mortgage principal repaid
      totalMortgageInterest: PLN = PLN.Zero,     // aggregate secured mortgage interest paid
  ):
    def totalLiquidityShortfallComponents: PLN =
      totalConsumptionShortfall + totalRentArrears + totalMortgageArrears + totalConsumerDebtArrears + totalTemporaryOverdraft

    def withFlowTotalsFrom(flowTotals: Aggregates): Aggregates =
      copy(
        totalIncome = flowTotals.totalIncome,
        consumption = flowTotals.consumption,
        domesticConsumption = flowTotals.domesticConsumption,
        importConsumption = flowTotals.importConsumption,
        retrainingAttempts = flowTotals.retrainingAttempts,
        retrainingSuccesses = flowTotals.retrainingSuccesses,
        totalRent = flowTotals.totalRent,
        totalDebtService = flowTotals.totalDebtService,
        totalUnempBenefits = flowTotals.totalUnempBenefits,
        totalDepositInterest = flowTotals.totalDepositInterest,
        crossSectorHires = flowTotals.crossSectorHires,
        voluntaryQuits = flowTotals.voluntaryQuits,
        totalRemittances = flowTotals.totalRemittances,
        totalPit = flowTotals.totalPit,
        totalSocialTransfers = flowTotals.totalSocialTransfers,
        totalConsumerDebtService = flowTotals.totalConsumerDebtService,
        totalConsumerOrigination = flowTotals.totalConsumerOrigination,
        totalConsumerApprovedOrigination = flowTotals.totalConsumerApprovedOrigination,
        totalLiquidityShortfallFinancing = flowTotals.totalLiquidityShortfallFinancing,
        totalConsumerDefault = flowTotals.totalConsumerDefault,
        totalConsumerPrincipal = flowTotals.totalConsumerPrincipal,
        totalConsumptionShortfall = flowTotals.totalConsumptionShortfall,
        totalRentArrears = flowTotals.totalRentArrears,
        totalMortgageArrears = flowTotals.totalMortgageArrears,
        totalConsumerDebtArrears = flowTotals.totalConsumerDebtArrears,
        totalTemporaryOverdraft = flowTotals.totalTemporaryOverdraft,
        totalMortgagePrincipal = flowTotals.totalMortgagePrincipal,
        totalMortgageInterest = flowTotals.totalMortgageInterest,
      )

    def unemploymentRate(totalPopulation: Int): Share =
      if totalPopulation <= 0 then Share.Zero
      else Share.fraction(totalPopulation - employed, totalPopulation)

  /** Monthly household-step output. Behavioral state remains separate from the
    * financial stock balances consumed by the ledger boundary.
    */
  case class StepResult(
      households: Vector[State],
      aggregates: Aggregates,
      perBankFlows: Option[Vector[PerBankFlow]],
      financialStocks: Vector[FinancialStocks],
      monthlyFlows: Vector[MonthlyFlow],
  )

  /** Per-household monthly consumer-liquidity flow diagnostics, aligned by
    * household id where possible and used only by optional micro snapshot
    * exports.
    */
  case class MonthlyFlow(
      householdId: HhId,                    // household identifier for joining with post-month state
      openingDemandDeposit: PLN,            // opening liquid deposit before the household month step
      openingConsumerLoan: PLN,             // opening unsecured consumer-loan principal
      monthlyIncome: PLN,                   // net monthly household income after PIT and transfers
      consumption: PLN,                     // monthly household goods consumption
      rent: PLN,                            // monthly rent paid by the household
      mortgageDebtService: PLN,             // monthly secured mortgage debt service
      consumerApprovedOrigination: PLN,     // underwritten consumer credit originated by the DTI rule
      liquidityShortfallFinancing: PLN,     // same-month bridge/write-off preventing negative closing deposits
      consumerDebtService: PLN,             // monthly unsecured consumer-credit debt service
      consumerDefault: PLN,                 // gross consumer default plus bridge charge-off this month
      consumerPrincipal: PLN,               // principal component of consumer debt service
      closingConsumerLoan: PLN,             // closing unsecured consumer-loan principal
      consumptionShortfall: PLN = PLN.Zero, // shortfall attributed to modeled consumption outflow
      rentArrears: PLN = PLN.Zero,          // shortfall attributed to rent payment
      mortgageArrears: PLN = PLN.Zero,      // shortfall attributed to mortgage debt service
      consumerDebtArrears: PLN = PLN.Zero,  // shortfall attributed to consumer debt service
      temporaryOverdraft: PLN = PLN.Zero,   // shortfall attributed to other current liquidity gaps
  )

  object MonthlyFlow:
    def inactive(householdId: HhId, stocks: FinancialStocks): MonthlyFlow =
      MonthlyFlow(
        householdId = householdId,
        openingDemandDeposit = stocks.demandDeposit,
        openingConsumerLoan = stocks.consumerLoan,
        monthlyIncome = PLN.Zero,
        consumption = PLN.Zero,
        rent = PLN.Zero,
        mortgageDebtService = PLN.Zero,
        consumerApprovedOrigination = PLN.Zero,
        liquidityShortfallFinancing = PLN.Zero,
        consumerDebtService = PLN.Zero,
        consumerDefault = PLN.Zero,
        consumerPrincipal = PLN.Zero,
        closingConsumerLoan = stocks.consumerLoan,
        consumptionShortfall = PLN.Zero,
        rentArrears = PLN.Zero,
        mortgageArrears = PLN.Zero,
        consumerDebtArrears = PLN.Zero,
        temporaryOverdraft = PLN.Zero,
      )

  /** Household population plus the ledger-owned financial stocks aligned by
    * household vector position.
    */
  case class Population(
      households: Vector[State],
      financialStocks: Vector[FinancialStocks],
  )

  // ---- Init ----

  object Init:

    /** Create individual households with multi-bank assignment. Firm job slots
      * are initialized as employed households; the configured initial
      * unemployment stock is added as unemployed labor-force participants
      * outside those firm slots, so baseline unemployment does not create
      * artificial same-month vacancies.
      */
    def create(randomness: InitRandomness.HouseholdStreams, firms: Vector[Firm.State])(using p: SimParams): Population =
      val employedSlots    = firms.map(Firm.workerCount).sum
      val nUnemployed      = initialUnemployedCount(employedSlots)
      val totalCount       = employedSlots + nUnemployed
      val hhNetwork        = Network.wattsStrogatz(totalCount, p.household.socialK, p.household.socialP, randomness.network)
      val initialized      = initialize(employedSlots, firms, hhNetwork, randomness.attributes)
      // Assign households to same bank as their employer
      val banked           = initialized.households.map: h =>
        h.status match
          case HhStatus.Employed(fid, _, _) if fid.toInt < firms.length => h.copy(bankId = firms(fid.toInt).bankId)
          case _                                                        => h
      val unemployed       = initializeUnemployed(employedSlots, nUnemployed, firms, hhNetwork, randomness.attributes, randomness.initialUnemployment)
      val households       = banked ++ unemployed.map(_.state)
      val financialStocks  = initialized.financialStocks ++ unemployed.map(_.financialStocks)
      val mortgageStocks   = calibrateMortgageLoans(financialStocks)
      val calibratedStocks = calibrateConsumerLoans(mortgageStocks)
      Population(households, calibratedStocks)

    private def initialUnemployedCount(employedSlots: Int)(using p: SimParams): Int =
      if employedSlots <= 0 || p.pop.initialUnemploymentRate <= Share.Zero then 0
      else
        val employedShareRaw = (Share.One - p.pop.initialUnemploymentRate).toLong
        val total            =
          ((BigInt(employedSlots) * BigInt(FixedPointBase.Scale)) + BigInt(employedShareRaw - 1L)) / BigInt(employedShareRaw)
        Math.max(0, total.toInt - employedSlots)

    private def initializeUnemployed(
        startId: Int,
        count: Int,
        firms: Vector[Firm.State],
        socialNetwork: Array[Array[Int]],
        attributeRng: RandomStream,
        sectorRng: RandomStream,
    )(using p: SimParams): Vector[SampledHousehold] =
      if count <= 0 then Vector.empty
      else
        val living = firms.filter(Firm.isAlive)
        require(living.nonEmpty, "Household.Init.create requires at least one living firm to seed unemployed workers")
        (0 until count)
          .map: offset =>
            val firm    = living(sectorRng.nextInt(living.length))
            val sampled = sampleHousehold(startId + offset, firm, firm.sector, socialNetwork, attributeRng)
            sampled.copy(
              state = sampled.state.copy(
                status = HhStatus.Unemployed(0),
                bankId = firm.bankId,
                lastSectorIdx = firm.sector,
              ),
            )
          .toVector

    /** Initialize households, all employed, assigned proportionally to firm
      * sizes.
      */
    def initialize(
        nHouseholds: Int,
        firms: Vector[Firm.State],
        socialNetwork: Array[Array[Int]],
        rng: RandomStream,
    )(using p: SimParams): Population =
      // Expand alive firms into (firm, sectorIdx) per worker slot, capped at nHouseholds
      val assignments: Vector[(Firm.State, SectorIdx)] =
        firms
          .filter(Firm.isAlive)
          .flatMap(f => Vector.fill(Firm.workerCount(f))((f, f.sector)))
          .take(nHouseholds)

      val sampled = assignments.zipWithIndex.map { case ((firm, sectorIdx), hhId) =>
        sampleHousehold(hhId, firm, sectorIdx, socialNetwork, rng)
      }
      Population(sampled.map(_.state), sampled.map(_.financialStocks))

    private def calibrateConsumerLoans(stocks: Vector[FinancialStocks])(using p: SimParams): Vector[FinancialStocks] =
      if stocks.isEmpty then stocks
      else
        val target = p.banking.initConsumerLoans
        if target <= PLN.Zero then stocks.map(_.copy(consumerLoan = PLN.Zero))
        else
          val current   = stocks.iterator.map(_.consumerLoan).sumPln
          val weights   =
            if current > PLN.Zero then stocks.map(_.consumerLoan.distributeRaw).toArray
            else Array.fill(stocks.length)(1L)
          val allocated = com.boombustgroup.ledger.Distribute.distribute(target.toLong, weights)
          stocks.zip(allocated).map { case (stock, rawConsumerLoan) =>
            stock.copy(consumerLoan = PLN.fromRaw(rawConsumerLoan))
          }

    private def calibrateMortgageLoans(stocks: Vector[FinancialStocks])(using p: SimParams): Vector[FinancialStocks] =
      if stocks.isEmpty then stocks
      else
        val target = p.housing.initMortgage
        if target <= PLN.Zero then stocks.map(_.copy(mortgageLoan = PLN.Zero))
        else
          val current   = stocks.iterator.map(_.mortgageLoan).sumPln
          val weights   =
            if current > PLN.Zero then stocks.map(_.mortgageLoan.distributeRaw).toArray
            else Array.fill(stocks.length)(1L)
          val allocated = com.boombustgroup.ledger.Distribute.distribute(target.toLong, weights)
          stocks.zip(allocated).map { case (stock, rawMortgageLoan) =>
            stock.copy(mortgageLoan = PLN.fromRaw(rawMortgageLoan))
          }

    private case class SampledHousehold(
        state: State,
        financialStocks: FinancialStocks,
    )

    /** Sample attributes for a single household from init distributions. */
    private def sampleHousehold(
        hhId: Int,
        firm: Firm.State,
        sectorIdx: SectorIdx,
        socialNetwork: Array[Array[Int]],
        rng: RandomStream,
    )(using p: SimParams): SampledHousehold =
      val savings: PLN  = Distributions.lognormalPln(p.household.savingsMu, p.household.savingsSigma, rng)
      val hasDebt       = p.household.debtFraction.sampleBelow(rng)
      val debt: PLN     = if hasDebt then Distributions.lognormalPln(p.household.debtMu, p.household.debtSigma, rng) else PLN.Zero
      val baseRent: PLN = Distributions.gaussianPlnAtLeast(p.household.rentMean, p.household.rentStd, p.household.rentFloor, rng)
      val rent: PLN     = baseRent * firm.region.housingCostIndex
      val mpc           = Distributions.betaSample(p.household.mpcAlpha, p.household.mpcBeta, rng)
      val (edu, skill)  = sampleEducationAndSkill(sectorIdx, rng)
      val wage: PLN     = p.household.baseWage * Region.normalizedWageMultiplier(firm.region) * p.sectorDefs(sectorIdx.toInt).wageMultiplier * skill
      val eqWealth: PLN = if p.equity.hhEquityFrac.sampleBelow(rng) then savings * GpwEquityInitFrac else PLN.Zero
      val numChildren   = Distributions.poissonSample(p.fiscal.social800ChildrenPerHh, rng)
      val consDebt: PLN =
        if hasDebt then Distributions.lognormalPln(p.household.debtMu, p.household.debtSigma, rng) * ConsumerDebtInitFrac else PLN.Zero
      val routineness   = sampleTaskRoutineness(edu, sectorIdx, rng)

      SampledHousehold(
        state = State(
          id = HhId(hhId),
          monthlyRent = rent,
          skill = skill,
          healthPenalty = Share.Zero,
          mpc = mpc.clamp(MpcFloor, MpcCeiling),
          status = HhStatus.Employed(firm.id, sectorIdx, wage),
          socialNeighbors = if hhId < socialNetwork.length then socialNetwork(hhId).map(HhId(_)) else Array.empty[HhId],
          bankId = BankId(0),
          lastSectorIdx = sectorIdx,
          isImmigrant = false,
          numDependentChildren = numChildren,
          education = edu,
          taskRoutineness = routineness,
          wageScar = Share.Zero,
          contractType = ContractType.sampleForSector(sectorIdx, rng),
          region = firm.region,
        ),
        financialStocks = FinancialStocks(
          demandDeposit = savings,
          mortgageLoan = debt,
          consumerLoan = consDebt,
          equity = eqWealth,
        ),
      )

    /** Sample education level and skill for a sector, clamped to edu range. */
    private def sampleEducationAndSkill(sectorIdx: SectorIdx, rng: RandomStream)(using p: SimParams): (Int, Share) =
      val edu                          = p.social.drawEducation(sectorIdx.toInt, rng)
      val (skillFloorS, skillCeilingS) = p.social.eduSkillRange(edu)
      val baseSkill                    = Distributions.randomShareBetween(skillFloorS, skillCeilingS, rng)
      val sectorBonus                  = (SectorSkillBonusCoeff * p.sectorDefs(sectorIdx.toInt).sigma.toScalar.log10).min(SectorSkillBonusMax).toShare
      val skill                        = (baseSkill + sectorBonus).clamp(skillFloorS, skillCeilingS)
      (edu, skill)

    /** Sample task routineness based on education and sector sigma.
      *
      * High-σ sectors (BPO) have more automatable routine tasks; high education
      * workers tend toward cognitive (non-routine) tasks. Acemoglu & Restrepo
      * 2020.
      *
      * routineness = base(edu) + σ-bonus, clamped to [0.05, 0.95], with noise.
      */
    private[agents] def sampleTaskRoutineness(edu: Int, sectorIdx: SectorIdx, rng: RandomStream)(using p: SimParams): Share =
      val eduBase  = p.labor.sbtcEduRoutineness(edu)
      val sigmaAdj = (Scalar.decimal(5, 2) * p.sectorDefs(sectorIdx.toInt).sigma.toScalar.log10).toShare
      val noise    = Scalar.randomBetween(Scalar.decimal(-25, 3), Scalar.decimal(25, 3), rng).toShare
      (eduBase + sigmaAdj + noise).clamp(Share.decimal(5, 2), Share.decimal(95, 2))

  // ---- Step flow totals (immutable, folded from per-HH results) ----

  /** Accumulated flow totals from one step. PLN is Long — addition is exact, no
    * Kahan compensation needed.
    */
  private class StepTotals:
    private var incomeAcc: PLN               = PLN.Zero
    private var benefitAcc: PLN              = PLN.Zero
    private var debtSvcAcc: PLN              = PLN.Zero
    private var mortgagePrincipalAcc: PLN    = PLN.Zero
    private var mortgageInterestAcc: PLN     = PLN.Zero
    private var depIntAcc: PLN               = PLN.Zero
    private var goodsConsAcc: PLN            = PLN.Zero
    private var rentAcc: PLN                 = PLN.Zero
    private var remitAcc: PLN                = PLN.Zero
    private var pitAcc: PLN                  = PLN.Zero
    private var socialAcc: PLN               = PLN.Zero
    private var ccDebtSvcAcc: PLN            = PLN.Zero
    private var ccOrigAcc: PLN               = PLN.Zero
    private var ccApprovedOrigAcc: PLN       = PLN.Zero
    private var liquidityShortfallAcc: PLN   = PLN.Zero
    private var consumptionShortfallAcc: PLN = PLN.Zero
    private var rentArrearsAcc: PLN          = PLN.Zero
    private var mortgageArrearsAcc: PLN      = PLN.Zero
    private var consumerDebtArrearsAcc: PLN  = PLN.Zero
    private var temporaryOverdraftAcc: PLN   = PLN.Zero
    private var ccDefaultAcc: PLN            = PLN.Zero
    private var ccPrincipalAcc: PLN          = PLN.Zero
    var retrainingAttempts: Int              = 0
    var retrainingSuccesses: Int             = 0
    var voluntaryQuits: Int                  = 0

    def add(r: HhMonthlyResult): Unit =
      incomeAcc = incomeAcc + r.income
      benefitAcc = benefitAcc + r.benefit
      debtSvcAcc = debtSvcAcc + r.debtService
      mortgagePrincipalAcc = mortgagePrincipalAcc + r.mortgagePrincipal
      mortgageInterestAcc = mortgageInterestAcc + r.mortgageInterest
      depIntAcc = depIntAcc + r.depositInterest
      goodsConsAcc = goodsConsAcc + r.consumption
      rentAcc = rentAcc + r.rent
      remitAcc = remitAcc + r.remittance
      pitAcc = pitAcc + r.pitTax
      socialAcc = socialAcc + r.socialTransfer
      ccDebtSvcAcc = ccDebtSvcAcc + r.credit.debtService
      ccOrigAcc = ccOrigAcc + r.credit.totalOrigination
      ccApprovedOrigAcc = ccApprovedOrigAcc + r.credit.newLoan
      liquidityShortfallAcc = liquidityShortfallAcc + r.credit.liquidityShortfallFinancing
      consumptionShortfallAcc = consumptionShortfallAcc + r.credit.liquidityShortfall.consumptionShortfall
      rentArrearsAcc = rentArrearsAcc + r.credit.liquidityShortfall.rentArrears
      mortgageArrearsAcc = mortgageArrearsAcc + r.credit.liquidityShortfall.mortgageArrears
      consumerDebtArrearsAcc = consumerDebtArrearsAcc + r.credit.liquidityShortfall.consumerDebtArrears
      temporaryOverdraftAcc = temporaryOverdraftAcc + r.credit.liquidityShortfall.temporaryOverdraft
      ccDefaultAcc = ccDefaultAcc + r.credit.defaultAmt
      ccPrincipalAcc = ccPrincipalAcc + r.credit.principal
      retrainingAttempts += r.retrainingAttempt
      retrainingSuccesses += r.retrainingSuccess
      voluntaryQuits += r.voluntaryQuit

    def income: PLN                      = incomeAcc
    def unempBenefits: PLN               = benefitAcc
    def debtService: PLN                 = debtSvcAcc
    def mortgagePrincipal: PLN           = mortgagePrincipalAcc
    def mortgageInterest: PLN            = mortgageInterestAcc
    def depositInterest: PLN             = depIntAcc
    def goodsConsumption: PLN            = goodsConsAcc
    def rent: PLN                        = rentAcc
    def remittances: PLN                 = remitAcc
    def pit: PLN                         = pitAcc
    def socialTransfers: PLN             = socialAcc
    def consumerDebtService: PLN         = ccDebtSvcAcc
    def consumerOrigination: PLN         = ccOrigAcc
    def consumerApprovedOrigination: PLN = ccApprovedOrigAcc
    def liquidityShortfallFinancing: PLN = liquidityShortfallAcc
    def consumptionShortfall: PLN        = consumptionShortfallAcc
    def rentArrears: PLN                 = rentArrearsAcc
    def mortgageArrears: PLN             = mortgageArrearsAcc
    def consumerDebtArrears: PLN         = consumerDebtArrearsAcc
    def temporaryOverdraft: PLN          = temporaryOverdraftAcc
    def consumerDefault: PLN             = ccDefaultAcc
    def consumerPrincipal: PLN           = ccPrincipalAcc

  /** Build per-bank flow vector from (BankId, HhMonthlyResult) pairs. PLN is
    * Long — addition is exact, no Kahan needed.
    */
  private def buildPerBankFlows(flows: Vector[(BankId, HhMonthlyResult)], nBanks: Int): Vector[PerBankFlow] =
    val acc = Array.fill(nBanks)(PerBankFlow.zero)
    flows.foreach: (bankId, r) =>
      val b   = bankId.toInt
      val cur = acc(b)
      acc(b) = PerBankFlow(
        income = cur.income + r.income,
        consumption = cur.consumption + r.consumption + r.rent,
        debtService = cur.debtService + r.debtService,
        mortgageInterest = cur.mortgageInterest + r.mortgageInterest,
        depositInterest = cur.depositInterest + r.depositInterest,
        consumerDebtService = cur.consumerDebtService + r.credit.debtService,
        consumerOrigination = cur.consumerOrigination + r.credit.totalOrigination,
        consumerApprovedOrigination = cur.consumerApprovedOrigination + r.credit.newLoan,
        liquidityShortfallFinancing = cur.liquidityShortfallFinancing + r.credit.liquidityShortfallFinancing,
        consumerDefault = cur.consumerDefault + r.credit.defaultAmt,
        consumerPrincipal = cur.consumerPrincipal + r.credit.principal,
      )
    acc.toVector

  // ---- Extracted per-HH pipeline types ----

  /** Consumer credit result for a single household in one month. */
  private case class CreditResult(
      debtService: PLN,                                 // total consumer debt service (amortization + interest)
      principal: PLN,                                   // principal component of debt service
      interest: PLN,                                    // interest component of debt service
      newLoan: PLN,                                     // underwritten new consumer loan, excluding same-month liquidity bridge
      liquidityShortfall: LiquidityShortfallComponents, // same-month bridge/write-off split by diagnostic source
      defaultAmt: PLN,                                  // same-month bridge charge-off plus bankruptcy default amount
      updatedDebt: PLN,                                 // outstanding consumer debt after this month's flows
  ):
    def liquidityShortfallFinancing: PLN = liquidityShortfall.total
    // Gross flow preserves the SFC bridge leg; defaultAmt offsets bridge stock in the same month.
    def totalOrigination: PLN            = newLoan + liquidityShortfallFinancing

  /** Per-HH monthly result — updated state + all flow variables for
    * aggregation.
    */
  private case class HhMonthlyResult(
      newState: State,        // updated household state
      income: PLN,            // gross income net of PIT plus social transfers
      benefit: PLN,           // unemployment benefit component
      consumption: PLN,       // total consumption (goods + wealth effect, before rent)
      debtService: PLN,       // secured (mortgage) debt service
      mortgagePrincipal: PLN, // mortgage principal component of secured debt service
      mortgageInterest: PLN,  // mortgage interest component of secured debt service
      depositInterest: PLN,   // deposit interest received
      remittance: PLN,        // remittance sent abroad (immigrants only)
      pitTax: PLN,            // PIT paid
      socialTransfer: PLN,    // 800+ social transfer received
      credit: CreditResult,   // consumer credit flows
      voluntaryQuit: Int,     // 1 if voluntary cross-sector quit, 0 otherwise
      retrainingAttempt: Int, // 1 if retraining attempted, 0 otherwise
      retrainingSuccess: Int, // 1 if retraining succeeded, 0 otherwise
      equityWealth: PLN,      // updated equity wealth after revaluation
      rent: PLN,              // monthly rent payment
      financialStocks: FinancialStocks,
  )

  // ---- Logic ----

  /** Monthly PIT: progressive Polish brackets (12%/32%), minus kwota wolna. PIT
    * base = gross income − ZUS employee contribution (Art. 26 ustawy o PIT).
    */
  def computeMonthlyPit(monthlyIncome: PLN)(using p: SimParams): PLN =
    if monthlyIncome <= PLN.Zero then PLN.Zero
    else
      val afterZus   = monthlyIncome - monthlyIncome * p.social.zusEmployeeRate
      val annualized = afterZus * Multiplier(12)
      val grossTax   =
        if annualized <= p.fiscal.pitBracket1Annual then annualized * p.fiscal.pitRate1
        else
          p.fiscal.pitBracket1Annual * p.fiscal.pitRate1 +
            (annualized - p.fiscal.pitBracket1Annual) * p.fiscal.pitRate2
      (grossTax - p.fiscal.pitTaxCreditAnnual).max(PLN.Zero) / 12L

  /** Compute 800+ social transfer (PIT-exempt, lump-sum per child ≤ 18). */
  def computeSocialTransfer(numChildren: Int)(using p: SimParams): PLN =
    if numChildren <= 0 then PLN.Zero
    else numChildren * p.fiscal.social800

  /** Unemployment benefit (zasilek): 1500 PLN m1-3, 1200 PLN m4-6, 0 after. */
  def computeBenefit(monthsUnemployed: Int)(using p: SimParams): PLN =
    if monthsUnemployed <= p.fiscal.govBenefitDuration / 2 then p.fiscal.govBenefitM1to3
    else if monthsUnemployed <= p.fiscal.govBenefitDuration then p.fiscal.govBenefitM4to6
    else PLN.Zero

  /** Voluntary cross-sector search for employed HH → (newStatus, quitFlag). */
  private def tryVoluntarySearch(
      financialStocks: FinancialStocks,
      status: HhStatus.Employed,
      sectorWages: Vector[PLN],
      sectorVacancies: Vector[Int],
      rng: RandomStream,
  )(using p: SimParams): (HhStatus, Int) =
    if !p.labor.voluntarySearchProb.sampleBelow(rng) then return (status, 0)
    val targetSector      =
      SectoralMobility.selectTargetSector(status.sectorIdx.toInt, sectorWages, sectorVacancies, p.labor.frictionMatrix, p.labor.vacancyWeight, rng)
    if sectorVacancies(targetSector) <= 0 then return (status, 0)
    val targetAvgWage     = sectorWages(targetSector)
    val friction          = p.labor.frictionMatrix(status.sectorIdx.toInt)(targetSector)
    val frictionNetWage   = targetAvgWage * SectoralMobility.crossSectorWagePenalty(friction)
    val wageThresholdMult = Multiplier.One + p.labor.voluntaryWageThreshold.toMultiplier
    if frictionNetWage <= status.wage * wageThresholdMult then return (status, 0)
    if friction <= p.labor.adjacentFrictionMax then
      val rp = SectoralMobility.frictionAdjustedParams(friction, p.labor.frictionDurationMult, p.labor.frictionCostMult)
      if financialStocks.demandDeposit > rp.cost then (HhStatus.Retraining(rp.duration, SectorIdx(targetSector), rp.cost), 1)
      else (status, 0)
    else (status, 0)

  /** Retraining for unemployed HH → (newStatus, attemptFlag, successFlag). */
  private def tryRetraining(
      hh: State,
      financialStocks: FinancialStocks,
      status: HhStatus,
      neighborDistress: Share,
      sectorWages: Option[Vector[PLN]],
      sectorVacancies: Option[Vector[Int]],
      rng: RandomStream,
  )(using p: SimParams): (HhStatus, Int, Int) =
    status match
      case HhStatus.Unemployed(months) if months > UnemploymentRetrainingThreshold && p.household.retrainingEnabled =>
        val retrainProb = p.household.retrainingProb +
          (if neighborDistress > NeighborDistressThreshold then NeighborDistressRetrainBoost else Share.Zero)
        if financialStocks.demandDeposit > p.household.retrainingCost && retrainProb.sampleBelow(rng) then
          if sectorWages.isDefined then
            val sw           = sectorWages.get
            val sv           = sectorVacancies.get
            val fromSector   = if hh.lastSectorIdx.toInt >= 0 then hh.lastSectorIdx.toInt else 0
            val targetSector = SectoralMobility.selectTargetSector(fromSector, sw, sv, p.labor.frictionMatrix, p.labor.vacancyWeight, rng)
            val friction     = p.labor.frictionMatrix(fromSector)(targetSector)
            val rp           = SectoralMobility.frictionAdjustedParams(friction, p.labor.frictionDurationMult, p.labor.frictionCostMult)
            if financialStocks.demandDeposit > rp.cost then (HhStatus.Retraining(rp.duration, SectorIdx(targetSector), rp.cost), 1, 0)
            else (status, 0, 0)
          else
            val targetSector = rng.nextInt(p.sectorDefs.length)
            (HhStatus.Retraining(p.household.retrainingDuration, SectorIdx(targetSector), p.household.retrainingCost), 1, 0)
        else (status, 0, 0)

      case HhStatus.Retraining(monthsLeft, targetSector, cost) =>
        if monthsLeft <= 1 then
          val afterSkill      = applySkillDecay(hh, status)
          val afterHealth     = applyHealthScarring(hh, status)
          // baseSuccess × skill × (1 − healthPenalty) × eduMultiplier
          val skillHealthProb = p.household.retrainingBaseSuccess * afterSkill * (Share.One - afterHealth)
          val eduMult         = p.social.eduRetrainMultiplier(hh.education)
          val baseSuccessProb = (skillHealthProb * eduMult).toShare // Multiplier → Share (probability)
          val successProb     =
            val fromSector = if hh.lastSectorIdx.toInt >= 0 then hh.lastSectorIdx.toInt else 0
            val friction   = p.labor.frictionMatrix(fromSector)(targetSector.toInt)
            baseSuccessProb * (Share.One - friction * SectoralMobility.FrictionSuccessDiscount)
          if successProb.sampleBelow(rng) then (HhStatus.Unemployed(0), 0, 1)
          else (HhStatus.Unemployed(PostFailedRetrainingMonths), 0, 0)
        else (HhStatus.Retraining(monthsLeft - 1, targetSector, cost), 0, 0)

      case _ => (status, 0, 0)

  /** Consumer credit for one HH: debt service, origination, principal. */
  private def processConsumerCredit(
      hh: State,
      financialStocks: FinancialStocks,
      income: PLN,
      disposable: PLN,
      debtService: PLN,
      world: World,
      bankRates: Option[BankRates],
      rng: RandomStream,
  )(using p: SimParams): CreditResult =
    val consumerRate: Rate = bankRates match
      case Some(br) => br.lendingRates(hh.bankId.toInt) + p.household.ccSpread
      case None     => world.nbp.referenceRate + p.household.ccSpread
    val consumerPrin       = financialStocks.consumerLoan * p.household.ccAmortRate
    val consumerInterest   = financialStocks.consumerLoan * consumerRate.monthly
    val consumerDebtSvc    = consumerPrin + consumerInterest

    val newConsumerLoan = hh.status match
      case HhStatus.Employed(_, _, wage)                                             =>
        val stressed = disposable < wage * DisposableWageThreshold
        val eligible = stressed && p.household.ccEligRate.sampleBelow(rng)
        if !eligible then PLN.Zero
        else
          val totalDbtSvc   = debtService + consumerDebtSvc
          val existingDti   = if income > PLN.Zero then (totalDbtSvc / income).toShare else Share.One
          val headroomShare = (p.household.ccMaxDti - existingDti).max(Share.Zero)
          val headroom      = income * headroomShare
          val desired       = headroom.min(p.household.ccMaxLoan)
          if desired > MinConsumerLoanSize then desired else PLN.Zero
      case HhStatus.Unemployed(_) | HhStatus.Retraining(_, _, _) | HhStatus.Bankrupt => PLN.Zero

    val updatedDebt = (financialStocks.consumerLoan + newConsumerLoan - consumerPrin).max(PLN.Zero)

    CreditResult(
      debtService = consumerDebtSvc,
      principal = consumerPrin,
      interest = consumerInterest,
      newLoan = newConsumerLoan,
      liquidityShortfall = LiquidityShortfallComponents.Zero,
      defaultAmt = PLN.Zero,
      updatedDebt = updatedDebt,
    )

  /** Intermediate result after income/consumption pipeline, before branching.
    */
  private case class MonthlyFlows(
      hh: State,
      financialStocks: FinancialStocks,
      income: PLN,
      benefit: PLN,
      newStatus: HhStatus,
      debtService: PLN,
      mortgagePrincipal: PLN,
      mortgageInterest: PLN,
      depositInterest: PLN,
      remittance: PLN,
      pitTax: PLN,
      socialTransfer: PLN,
      credit: CreditResult,
      consumption: PLN,
      newEquityWealth: PLN,
      newSavings: PLN, // raw closing liquid balance before non-negative deposit settlement
      newDebt: PLN,
      neighborDistress: Share,
  )

  private def bankruptcyFloor(f: MonthlyFlows)(using p: SimParams): PLN =
    val essentialOutflows = (f.hh.monthlyRent + f.debtService + f.credit.debtService).max(f.hh.monthlyRent)
    essentialOutflows * p.household.bankruptcyThreshold.toMultiplier

  /** Per-HH monthly pipeline: income → tax → credit → consumption → equity. */
  private def computeMonthlyFlows(
      hh: State,
      financialStocks: FinancialStocks,
      world: World,
      rng: RandomStream,
      bankRates: Option[BankRates],
      equityIndexReturn: Rate,
      distressedIds: java.util.BitSet,
  )(using p: SimParams): MonthlyFlows =
    val (baseIncome, benefit, newStatus) = computeIncome(hh)

    // Mortgage service uses housing maturity for principal and bank rates for interest.
    val mortgageRate: Rate = bankRates match
      case Some(br) => br.lendingRates(hh.bankId.toInt)
      case None     => world.nbp.referenceRate + p.housing.mortgageSpread

    // Deposit interest (monetary transmission channel 2)
    val depInterest: PLN = bankRates match
      case Some(br) => financialStocks.demandDeposit * br.depositRates(hh.bankId.toInt).monthly
      case None     => PLN.Zero

    val grossIncome       = baseIncome + depInterest.max(PLN.Zero)
    val pitTax            = computeMonthlyPit(grossIncome)
    val socialTransfer    = computeSocialTransfer(hh.numDependentChildren)
    val income            = grossIncome - pitTax + socialTransfer
    val mortgagePrincipal = financialStocks.mortgageLoan / p.housing.mortgageMaturity
    val mortgageInterest  = financialStocks.mortgageLoan * mortgageRate.max(Rate.Zero).monthly
    val thisDebtService   = mortgagePrincipal + mortgageInterest

    val remittance =
      if hh.isImmigrant then income * p.immigration.remitRate
      else PLN.Zero

    val obligations         = hh.monthlyRent + thisDebtService + remittance
    val disposablePreCredit = (income - obligations).max(PLN.Zero)
    val credit              = processConsumerCredit(hh, financialStocks, income, disposablePreCredit, thisDebtService, world, bankRates, rng)
    val fullObligations     = obligations + credit.debtService
    val disposable          = (income - fullObligations).max(PLN.Zero)
    val savingsDrawdown     = computeSavingsDrawdown(financialStocks, income, newStatus)
    val consumptionBudget   = disposable + credit.newLoan + savingsDrawdown
    val consumption         = consumptionBudget * hh.mpc

    // Social network precautionary effect
    val neighborDistress = neighborDistressRatioFast(hh, distressedIds)
    val consumptionAdj   =
      if neighborDistress > NeighborDistressThreshold then consumption * NeighborDistressConsAdj else consumption

    // Wealth effects: equity (GPW) + housing (Meen HPI)
    val newEquityWealth       = (financialStocks.equity * (Multiplier.One + equityIndexReturn.toMultiplier)).max(PLN.Zero)
    val equityGain            = newEquityWealth - financialStocks.equity
    val equityBoost           =
      if equityGain > PLN.Zero then equityGain * p.equity.wealthEffectMpc
      else PLN.Zero
    val housingBoost          = world.real.housing.lastWealthEffect / world.derivedTotalPopulation.toLong.max(1L)
    val consumptionWithWealth = consumptionAdj + equityBoost + housingBoost

    MonthlyFlows(
      hh = hh,
      financialStocks = financialStocks,
      income = income,
      benefit = benefit,
      newStatus = newStatus,
      debtService = thisDebtService,
      mortgagePrincipal = mortgagePrincipal,
      mortgageInterest = mortgageInterest,
      depositInterest = depInterest.max(PLN.Zero),
      remittance = remittance,
      pitTax = pitTax,
      socialTransfer = socialTransfer,
      credit = credit,
      consumption = consumptionWithWealth,
      newEquityWealth = newEquityWealth,
      newSavings = financialStocks.demandDeposit + income - fullObligations + credit.newLoan - consumptionWithWealth,
      newDebt = (financialStocks.mortgageLoan - mortgagePrincipal).max(PLN.Zero),
      neighborDistress = neighborDistress,
    )

  /** Resolve flows into final HhMonthlyResult: bankruptcy or survival branch.
    */
  private def processHousehold(
      hh: State,
      financialStocks: FinancialStocks,
      world: World,
      rng: RandomStream,
      bankRates: Option[BankRates],
      equityIndexReturn: Rate,
      sectorWages: Option[Vector[PLN]],
      sectorVacancies: Option[Vector[Int]],
      distressedIds: java.util.BitSet,
  )(using p: SimParams): HhMonthlyResult =
    val f              = computeMonthlyFlows(hh, financialStocks, world, rng, bankRates, equityIndexReturn, distressedIds)
    val distressMonths =
      if f.newSavings < bankruptcyFloor(f) then hh.financialDistressMonths + 1 else 0
    if distressMonths >= p.household.bankruptcyDistressMonths then resolveBankruptcy(f, distressMonths)
    else resolveSurvival(f, sectorWages, sectorVacancies, rng, distressMonths)

  /** Diagnostic-only attribution: outflows consume available cash in a stable
    * audit priority, and any unfunded tail becomes the component split of the
    * same-month liquidity bridge/default diagnostic.
    */
  private def attributeLiquidityShortfall(f: MonthlyFlows, rawDemandDeposit: PLN, temporaryOutflow: PLN): LiquidityShortfallComponents =
    if rawDemandDeposit >= PLN.Zero then LiquidityShortfallComponents.Zero
    else
      var available = f.financialStocks.demandDeposit + f.income + f.credit.newLoan

      def gap(outflow: PLN): PLN =
        if outflow <= PLN.Zero then
          available = available - outflow
          PLN.Zero
        else if available >= outflow then
          available = available - outflow
          PLN.Zero
        else
          val shortfall = outflow - available.max(PLN.Zero)
          available = PLN.Zero
          shortfall

      val components = LiquidityShortfallComponents(
        consumptionShortfall = gap(f.consumption),
        rentArrears = gap(f.hh.monthlyRent),
        mortgageArrears = gap(f.debtService),
        consumerDebtArrears = gap(f.credit.debtService),
        temporaryOverdraft = gap(f.remittance + temporaryOutflow),
      )
      val expected   = PLN.Zero - rawDemandDeposit
      require(
        components.total == expected,
        s"Household liquidity shortfall components must sum to settlement shortfall, got ${components.total} and $expected",
      )
      components

  private def settleLiquidityShortfall(rawDemandDeposit: PLN, credit: CreditResult, components: LiquidityShortfallComponents): (PLN, CreditResult) =
    if rawDemandDeposit >= PLN.Zero then (rawDemandDeposit, credit)
    else
      val shortfall = PLN.Zero - rawDemandDeposit
      require(
        components.total == shortfall,
        s"Household liquidity shortfall settlement requires components to match total, got ${components.total} and $shortfall",
      )
      (
        PLN.Zero,
        // Residual gaps are explicit same-month bridge defaults, not new ordinary consumer-loan stock.
        credit.copy(
          liquidityShortfall = credit.liquidityShortfall + components,
          defaultAmt = credit.defaultAmt + shortfall,
        ),
      )

  /** Personal-insolvency branch: write off consumer debt and equity without
    * removing the household from the labor force.
    */
  private def resolveBankruptcy(f: MonthlyFlows, distressMonths: Int): HhMonthlyResult =
    // Consumer credit stock is reduced earlier by same-month principal only.
    // Default the remaining balance so bankruptcy stays aligned with the stock
    // identity used by BankingEconomics/SFC.
    val _                                   = distressMonths
    val liquidityShortfall                  = attributeLiquidityShortfall(f, f.newSavings, temporaryOutflow = PLN.Zero)
    val (finalDemandDeposit, settledCredit) = settleLiquidityShortfall(f.newSavings, f.credit, liquidityShortfall)
    val ccDefaultAmt                        = settledCredit.defaultAmt + settledCredit.updatedDebt
    val creditWithDef                       = settledCredit.copy(defaultAmt = ccDefaultAmt, updatedDebt = PLN.Zero)
    val financial                           = FinancialStocks(
      demandDeposit = finalDemandDeposit,
      mortgageLoan = f.newDebt,
      consumerLoan = PLN.Zero,
      equity = PLN.Zero,
    )
    HhMonthlyResult(
      newState = f.hh.copy(
        status = f.newStatus,
        financialDistressMonths = 0,
      ),
      income = f.income,
      benefit = f.benefit,
      consumption = f.consumption,
      debtService = f.debtService,
      mortgagePrincipal = f.mortgagePrincipal,
      mortgageInterest = f.mortgageInterest,
      depositInterest = f.depositInterest,
      remittance = f.remittance,
      pitTax = f.pitTax,
      socialTransfer = f.socialTransfer,
      credit = creditWithDef,
      voluntaryQuit = 0,
      retrainingAttempt = 0,
      retrainingSuccess = 0,
      equityWealth = PLN.Zero,
      rent = f.hh.monthlyRent,
      financialStocks = financial,
    )

  /** Survival branch: skill decay, labor transitions, state update. */
  private def resolveSurvival(
      f: MonthlyFlows,
      sectorWages: Option[Vector[PLN]],
      sectorVacancies: Option[Vector[Int]],
      rng: RandomStream,
      distressMonths: Int,
  )(using p: SimParams): HhMonthlyResult =
    val afterSkill    = applySkillDecay(f.hh, f.newStatus)
    val afterHealth   = applyHealthScarring(f.hh, f.newStatus)
    val afterWageScar = applyWageScar(f.hh, f.newStatus)
    val afterMpc      = updateMpc(f.hh, f.financialStocks, f.income, f.newStatus)

    val (afterVoluntary, vQuit) = f.newStatus match
      case emp: HhStatus.Employed if sectorWages.isDefined =>
        tryVoluntarySearch(f.financialStocks, emp, sectorWages.get, sectorVacancies.get, rng)
      case _                                               => (f.newStatus, 0)

    val (finalStatus, rAttempt, rSuccess) =
      tryRetraining(f.hh, f.financialStocks, afterVoluntary, f.neighborDistress, sectorWages, sectorVacancies, rng)

    val retrainingCostThisMonth             = finalStatus match
      case HhStatus.Retraining(ml, _, cost) if ml == p.household.retrainingDuration - 1 => cost
      case _                                                                            => PLN.Zero
    val rawFinalDemandDeposit               = f.newSavings - retrainingCostThisMonth
    val liquidityShortfall                  = attributeLiquidityShortfall(f, rawFinalDemandDeposit, temporaryOutflow = retrainingCostThisMonth)
    val (finalDemandDeposit, settledCredit) =
      settleLiquidityShortfall(rawFinalDemandDeposit, f.credit, liquidityShortfall)
    val financial                           = FinancialStocks(
      demandDeposit = finalDemandDeposit,
      mortgageLoan = f.newDebt,
      consumerLoan = settledCredit.updatedDebt,
      equity = f.newEquityWealth,
    )

    HhMonthlyResult(
      newState = f.hh.copy(
        skill = afterSkill,
        healthPenalty = afterHealth,
        wageScar = afterWageScar,
        mpc = afterMpc,
        status = finalStatus,
        financialDistressMonths = distressMonths,
      ),
      income = f.income,
      benefit = f.benefit,
      consumption = f.consumption,
      debtService = f.debtService,
      mortgagePrincipal = f.mortgagePrincipal,
      mortgageInterest = f.mortgageInterest,
      depositInterest = f.depositInterest,
      remittance = f.remittance,
      pitTax = f.pitTax,
      socialTransfer = f.socialTransfer,
      credit = settledCredit,
      voluntaryQuit = vQuit,
      retrainingAttempt = rAttempt,
      retrainingSuccess = rSuccess,
      equityWealth = f.newEquityWealth,
      rent = f.hh.monthlyRent,
      financialStocks = financial,
    )

  /** Monthly entry point: map processHousehold + accumulate + aggregate. */
  def step(
      households: Vector[State],
      financialStocks: Vector[FinancialStocks],
      world: World,
      marketWage: PLN,
      reservationWage: PLN,
      importAdj: Share,
      rng: RandomStream,
      nBanks: Int = 1,
      bankRates: Option[BankRates] = None,
      equityIndexReturn: Rate = Rate.Zero,
      sectorWages: Option[Vector[PLN]] = None,
      sectorVacancies: Option[Vector[Int]] = None,
  )(using p: SimParams): StepResult =
    require(
      households.length == financialStocks.length,
      s"Household.step requires aligned households and financialStocks, got ${households.length} households and ${financialStocks.length} stock rows",
    )
    require(
      financialStocks.forall(_.demandDeposit >= PLN.Zero),
      "Household.step requires non-negative opening demandDeposit balances; liquidity shortfalls must enter as consumer-loan stocks",
    )
    val distressedIds = buildDistressedSet(households)

    val mapped = households
      .zip(financialStocks)
      .map: (hh, stocks) =>
        if hh.status == HhStatus.Bankrupt then (hh, None, stocks, MonthlyFlow.inactive(hh.id, stocks)) // absorbing barrier
        else
          val result = processHousehold(hh, stocks, world, rng, bankRates, equityIndexReturn, sectorWages, sectorVacancies, distressedIds)
          val flow   = MonthlyFlow(
            householdId = hh.id,
            openingDemandDeposit = stocks.demandDeposit,
            openingConsumerLoan = stocks.consumerLoan,
            monthlyIncome = result.income,
            consumption = result.consumption,
            rent = result.rent,
            mortgageDebtService = result.debtService,
            consumerApprovedOrigination = result.credit.newLoan,
            liquidityShortfallFinancing = result.credit.liquidityShortfallFinancing,
            consumerDebtService = result.credit.debtService,
            consumerDefault = result.credit.defaultAmt,
            consumerPrincipal = result.credit.principal,
            closingConsumerLoan = result.financialStocks.consumerLoan,
            consumptionShortfall = result.credit.liquidityShortfall.consumptionShortfall,
            rentArrears = result.credit.liquidityShortfall.rentArrears,
            mortgageArrears = result.credit.liquidityShortfall.mortgageArrears,
            consumerDebtArrears = result.credit.liquidityShortfall.consumerDebtArrears,
            temporaryOverdraft = result.credit.liquidityShortfall.temporaryOverdraft,
          )
          (result.newState, Some((hh.bankId, result)), result.financialStocks, flow)

    val updated = mapped.map(_._1)
    val stocks  = mapped.map(_._3)
    val flows   = mapped.flatMap(_._2)
    val monthly = mapped.map(_._4)
    val totals  = { val t = StepTotals(); flows.foreach((_, r) => t.add(r)); t }
    val agg     = computeAggregates(updated, stocks, marketWage, reservationWage, importAdj, totals)
    val pbf     = if bankRates.isDefined then Some(buildPerBankFlows(flows, nBanks)) else None
    require(
      monthly.map(_.liquidityShortfallFinancing).sumPln == agg.totalLiquidityShortfallFinancing,
      "Household.step monthly flow diagnostics must reconcile to aggregate liquidity shortfall financing",
    )
    require(
      agg.totalLiquidityShortfallComponents == agg.totalLiquidityShortfallFinancing,
      "Household.step shortfall components must reconcile to aggregate liquidity shortfall financing",
    )
    StepResult(updated, agg, pbf, stocks, monthly)

  /** Pre-compute distressed HH set for O(1) neighbor lookups. */
  private def buildDistressedSet(households: Vector[State]): java.util.BitSet =
    val bits = new java.util.BitSet(households.length)
    var i    = 0
    while i < households.length do
      households(i).status match
        case HhStatus.Bankrupt | HhStatus.Unemployed(_) => bits.set(i)
        case _                                          =>
      i += 1
    bits

  /** Sector mobility rate: fraction of employed in different sector than last.
    */
  private def sectorMobilityRate(updated: Vector[State]): Share =
    val employed    = updated.flatMap: hh =>
      hh.status match
        case HhStatus.Employed(_, sec, _) => Some((hh.lastSectorIdx, sec))
        case _                            => None
    if employed.isEmpty then return Share.Zero
    val crossSector = employed.count((last, cur) => last.toInt >= 0 && last != cur)
    Share.fraction(crossSector, employed.length)

  /** Base income, benefit, and updated status for one HH. */
  private def computeIncome(hh: State)(using SimParams): (PLN, PLN, HhStatus) =
    hh.status match
      case HhStatus.Employed(firmId, sectorIdx, wage) =>
        (wage, PLN.Zero, hh.status)
      case HhStatus.Unemployed(months)                =>
        val benefit = computeBenefit(months)
        (benefit, benefit, HhStatus.Unemployed(months + 1))
      case HhStatus.Retraining(monthsLeft, _, cost)   =>
        (PLN.Zero, PLN.Zero, hh.status)
      case HhStatus.Bankrupt                          =>
        (PLN.Zero, PLN.Zero, HhStatus.Bankrupt)

  /** Skill decay for long-term unemployed (onset after scarringOnset months).
    */
  private def applySkillDecay(hh: State, status: HhStatus)(using p: SimParams): Share =
    status match
      case HhStatus.Unemployed(months) if months >= p.household.scarringOnset =>
        hh.skill * (Share.One - p.household.skillDecayRate)
      case _                                                                  => hh.skill

  /** Apply health scarring for long-term unemployed (cumulative, capped). */
  private def applyHealthScarring(hh: State, status: HhStatus)(using p: SimParams): Share =
    status match
      case HhStatus.Unemployed(months) if months >= p.household.scarringOnset =>
        (hh.healthPenalty + p.household.scarringRate).min(p.household.scarringCap)
      case _                                                                  => hh.healthPenalty

  /** Wage scar: accumulates during long-term unemployment, decays slowly once
    * reemployed. Jacobson, LaLonde & Sullivan 1993; Davis & von Wachter 2011.
    */
  private def applyWageScar(hh: State, status: HhStatus)(using p: SimParams): Share =
    status match
      case HhStatus.Unemployed(months) if months >= p.household.scarringOnset =>
        (hh.wageScar + p.household.wageScarRate).min(p.household.wageScarCap)
      case _: HhStatus.Employed                                               =>
        (hh.wageScar - p.household.wageScarDecay).max(Share.Zero)
      case _                                                                  => hh.wageScar

  /** State-dependent MPC: Carroll (1997) buffer-stock model.
    *
    * When savings/income > target → buffer is fat → MPC falls (more saving).
    * When buffer depleted → MPC rises (spend everything). Unemployed get an
    * additional boost (desperate spending from depleted buffers).
    */
  private[amorfati] def updateMpc(hh: State, financialStocks: FinancialStocks, income: PLN, status: HhStatus)(using p: SimParams): Share =
    val baseMpc = hh.mpc
    if income <= PLN.Zero then baseMpc
    else
      val targetSavings = income * p.household.bufferTargetMonths
      val bufferRatio   = financialStocks.demandDeposit.ratioTo(targetSavings).toMultiplier
      val deviation     = bufferRatio - Multiplier.One              // >0 = fat, <0 = depleted
      val adjustment    = p.household.bufferSensitivity * deviation // Coefficient × Multiplier → Share
      val bufferAdj     = (Share.One - adjustment).clamp(Share.Zero, Share.One)
      val unemployedAdj = status match
        case _: HhStatus.Unemployed => Share.One + p.household.mpcUnemployedBoost
        case _                      => Share.One
      (baseMpc * bufferAdj * unemployedAdj).clamp(MpcFloor, MpcCeiling)

  /** Controlled drawdown from liquid savings buffers.
    *
    * Employed households only draw from savings above their target buffer.
    * Stressed households may also draw from the lower half of the buffer, but
    * still keep a protected floor.
    */
  private[amorfati] def computeSavingsDrawdown(
      financialStocks: FinancialStocks,
      cashOnHand: PLN,
      status: HhStatus,
  )(using p: SimParams): PLN =
    val targetIncome  = cashOnHand.max(p.household.baseReservationWage)
    val targetSavings = targetIncome * p.household.bufferTargetMonths
    val protectedBuff = targetSavings * p.household.bufferProtectedShare
    status match
      case _: HhStatus.Unemployed | _: HhStatus.Retraining =>
        (financialStocks.demandDeposit - protectedBuff).max(PLN.Zero) * p.household.bufferStressDrawdownRate
      case _: HhStatus.Employed | HhStatus.Bankrupt        =>
        (financialStocks.demandDeposit - targetSavings).max(PLN.Zero) * p.household.bufferExcessDrawdownRate

  /** Fraction of social neighbors in distress (BitSet, O(k) per HH). */
  private def neighborDistressRatioFast(hh: State, distressedIds: java.util.BitSet): Share =
    if hh.socialNeighbors.isEmpty then Share.Zero
    else
      var count = 0
      var i     = 0
      while i < hh.socialNeighbors.length do
        if distressedIds.get(hh.socialNeighbors(i).toInt) then count += 1
        i += 1
      Share.fraction(count, hh.socialNeighbors.length)

  /** Public entry point for aggregate stats (used by BankingEconomics and
    * tests). Flow totals default to zero — only distribution stats are
    * computed.
    */
  def computeAggregates(
      households: Vector[State],
      financialStocks: Vector[FinancialStocks],
      marketWage: PLN,
      reservationWage: PLN,
      importAdj: Share,
      retrainingAttempts: Int,
      retrainingSuccesses: Int,
  )(using SimParams): Aggregates =
    computeAggregates(
      households,
      financialStocks,
      marketWage,
      reservationWage,
      importAdj,
      { val t = StepTotals(); t.retrainingAttempts = retrainingAttempts; t.retrainingSuccesses = retrainingSuccesses; t },
    )

  /** Aggregate stats: single-pass accumulation + sorted-array Gini/percentiles.
    * Merges per-HH distribution stats with flow totals from StepTotals in one
    * construction — no intermediate Aggregates + copy overwrite.
    */
  private def computeAggregates(
      households: Vector[State],
      financialStocks: Vector[FinancialStocks],
      marketWage: PLN,
      reservationWage: PLN,
      importAdj: Share,
      t: StepTotals,
  )(using p: SimParams): Aggregates =
    require(
      households.length == financialStocks.length,
      s"Household.computeAggregates requires aligned households and financialStocks, got ${households.length} households and ${financialStocks.length} stock rows",
    )
    val n = households.length

    var nEmployed    = 0
    var nUnemployed  = 0
    var nRetraining  = 0
    var nBankrupt    = 0
    var sumSkill     = 0L
    var sumHealth    = 0L
    var sumSavings   = 0L
    val incomes      = new Array[Long](n)
    val consumptions = new Array[Long](n)
    val savingsArr   = new Array[Long](n)

    // Hot path: O(N_hh) single-pass with mutable accumulators + in-place arrays.
    // Intentionally imperative — foldLeft with 9-field accumulator would be slower and less readable.
    var i = 0
    while i < n do
      val hh     = households(i)
      val stocks = financialStocks(i)
      hh.status match
        case HhStatus.Employed(_, _, wage) =>
          nEmployed += 1
          incomes(i) = wage.toLong
          sumSkill += hh.skill.toLong
          sumHealth += hh.healthPenalty.toLong
        case HhStatus.Unemployed(months)   =>
          nUnemployed += 1
          incomes(i) = computeBenefit(months).toLong
          sumSkill += hh.skill.toLong
          sumHealth += hh.healthPenalty.toLong
        case HhStatus.Retraining(_, _, _)  =>
          nRetraining += 1
          incomes(i) = 0L
          sumSkill += hh.skill.toLong
          sumHealth += hh.healthPenalty.toLong
        case HhStatus.Bankrupt             =>
          nBankrupt += 1
          incomes(i) = 0L

      val rentRaw       = hh.monthlyRent.toLong
      val mortgageRate  = p.monetary.initialRate + p.housing.mortgageSpread
      val debtSvcRaw    = ((stocks.mortgageLoan / p.housing.mortgageMaturity) + (stocks.mortgageLoan * mortgageRate.monthly)).toLong
      val disposableRaw = math.max(0L, incomes(i) - rentRaw - debtSvcRaw)
      consumptions(i) = FixedPointBase.multiplyRaw(disposableRaw, hh.mpc.toLong)
      savingsArr(i) = stocks.demandDeposit.toLong
      sumSavings += savingsArr(i)
      i += 1

    val nAlive = n - nBankrupt

    // Sort each array once — reuse for Gini + percentiles + poverty
    java.util.Arrays.sort(incomes)
    java.util.Arrays.sort(savingsArr)
    java.util.Arrays.sort(consumptions)

    // Consumption split: flow totals (from StepTotals) are authoritative
    val totalConsumption = t.goodsConsumption + t.rent
    val importCons       = t.goodsConsumption * importAdj.min(ImportRatioCap)
    val domesticCons     = totalConsumption - importCons

    val medianIncomeRaw = if n > 0 then incomes(n / 2) else 0L

    Aggregates(
      employed = nEmployed,
      unemployed = nUnemployed,
      retraining = nRetraining,
      bankrupt = nBankrupt,
      totalIncome = t.income,
      consumption = totalConsumption,
      domesticConsumption = domesticCons,
      importConsumption = importCons,
      marketWage = marketWage,
      reservationWage = reservationWage,
      giniIndividual = giniSorted(incomes),
      giniWealth = giniSorted(savingsArr),
      meanSavings = if n > 0 then PLN.fromRaw(sumSavings / n) else PLN.Zero,
      medianSavings = if n > 0 then PLN.fromRaw(savingsArr(n / 2)) else PLN.Zero,
      povertyRate50 =
        if n > 0 && medianIncomeRaw > 0L then Share.fraction(lowerBound(incomes, (PLN.fromRaw(medianIncomeRaw) * PovertyRate50Pct).toLong), n) else Share.Zero,
      bankruptcyRate = if n > 0 then Share.fraction(nBankrupt, n) else Share.Zero,
      meanSkill = if nAlive > 0 then Share.fromRaw(sumSkill / nAlive) else Share.Zero,
      meanHealthPenalty = if nAlive > 0 then Share.fromRaw(sumHealth / nAlive) else Share.Zero,
      retrainingAttempts = t.retrainingAttempts,
      retrainingSuccesses = t.retrainingSuccesses,
      consumptionP10 =
        if n > 0 then PLN.fromRaw(consumptions((n * ConsumptionP10.toLong / com.boombustgroup.amorfati.fp.FixedPointBase.Scale).toInt)) else PLN.Zero,
      consumptionP50 = if n > 0 then PLN.fromRaw(consumptions(n / 2)) else PLN.Zero,
      consumptionP90 =
        if n > 0 then PLN.fromRaw(consumptions(Math.min(n - 1, (n * ConsumptionP90.toLong / com.boombustgroup.amorfati.fp.FixedPointBase.Scale).toInt)))
        else PLN.Zero,
      meanMonthsToRuin = Scalar.Zero,
      povertyRate30 =
        if n > 0 && medianIncomeRaw > 0L then Share.fraction(lowerBound(incomes, (PLN.fromRaw(medianIncomeRaw) * PovertyRate30Pct).toLong), n) else Share.Zero,
      totalRent = t.rent,
      totalDebtService = t.debtService,
      totalUnempBenefits = t.unempBenefits,
      totalDepositInterest = t.depositInterest,
      crossSectorHires = 0,
      voluntaryQuits = t.voluntaryQuits,
      sectorMobilityRate = sectorMobilityRate(households),
      totalRemittances = t.remittances,
      totalPit = t.pit,
      totalSocialTransfers = t.socialTransfers,
      totalConsumerDebtService = t.consumerDebtService,
      totalConsumerOrigination = t.consumerOrigination,
      totalConsumerApprovedOrigination = t.consumerApprovedOrigination,
      totalLiquidityShortfallFinancing = t.liquidityShortfallFinancing,
      totalConsumerDefault = t.consumerDefault,
      totalConsumerPrincipal = t.consumerPrincipal,
      totalConsumptionShortfall = t.consumptionShortfall,
      totalRentArrears = t.rentArrears,
      totalMortgageArrears = t.mortgageArrears,
      totalConsumerDebtArrears = t.consumerDebtArrears,
      totalTemporaryOverdraft = t.temporaryOverdraft,
      totalMortgagePrincipal = t.mortgagePrincipal,
      totalMortgageInterest = t.mortgageInterest,
    )

  /** Gini coefficient for a pre-sorted array (handles negatives by shifting).
    */
  def giniSorted(sorted: Array[Long]): Share =
    try giniSortedLong(sorted)
    catch case _: ArithmeticException => giniSortedBig(sorted)

  private def giniSortedLong(sorted: Array[Long]): Share =
    val n           = sorted.length
    if n <= 1 then return Share.Zero
    val minVal      = sorted(0)
    if minVal == Long.MinValue then throw new ArithmeticException("gini shift overflows Long")
    val shift       = if minVal < 0L then -minVal else 0L
    var total       = 0L
    var weightedSum = 0L
    var i           = 0
    while i < n do
      val v      = java.lang.Math.addExact(sorted(i), shift)
      total = java.lang.Math.addExact(total, v)
      val weight = 2L * (i.toLong + 1L) - n.toLong - 1L
      weightedSum = java.lang.Math.addExact(weightedSum, java.lang.Math.multiplyExact(weight, v))
      i += 1
    if total <= 0L then Share.Zero
    else Share.fromRaw(FixedPointBase.ratioRaw(weightedSum, java.lang.Math.multiplyExact(n.toLong, total)))

  private def giniSortedBig(sorted: Array[Long]): Share =
    val n           = sorted.length
    if n <= 1 then return Share.Zero
    val minVal      = sorted(0)
    val shift       = if minVal < 0L then BigInt(minVal).abs else BigInt(0)
    var total       = BigInt(0)
    var weightedSum = BigInt(0)
    var i           = 0
    while i < n do
      val v = BigInt(sorted(i)) + shift
      total += v
      weightedSum += BigInt(2L * (i.toLong + 1L) - n.toLong - 1L) * v
      i += 1
    if total <= 0 then Share.Zero else Share.fromRaw(scaledDivRaw(weightedSum, BigInt(n) * total))

  /** Binary search: count of elements < threshold in a sorted array. */
  private def lowerBound(sorted: Array[Long], threshold: Long): Int =
    var lo = 0
    var hi = sorted.length
    while lo < hi do
      val mid = (lo + hi) >>> 1
      if sorted(mid) < threshold then lo = mid + 1
      else hi = mid
    lo
