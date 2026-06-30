package com.boombustgroup.amorfati.agents

import com.boombustgroup.amorfati.agents.banking.BankPortfolioChoiceAudit
import com.boombustgroup.amorfati.agents.household.*
import com.boombustgroup.amorfati.agents.household.HouseholdParameters.*
import com.boombustgroup.amorfati.config.*
import com.boombustgroup.amorfati.engine.World
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
  case Unemployed(monthsUnemployed: Int)                               // no wage memory; rehire wage is recomputed by LaborMarket
  case Retraining(monthsLeft: Int, targetSector: SectorIdx, cost: PLN) // transitioning to target sector
  case Bankrupt // absorbing barrier

/** Financial-distress lifecycle, separate from labor-market status. */
enum HhFinancialDistressState:
  case Current         // no active financial distress
  case LiquidityStress // first active liquidity stress month
  case Arrears         // repeated unpaid obligations or residual shortfall
  case Restructuring   // post-arrears workout / constrained recovery
  case Defaulted       // persistent distress before personal-insolvency filing
  case Bankruptcy // personal-insolvency filing/workout state

/** Per-bank lending and deposit rates for individual HH mode. */
case class BankRates(
    lendingRates: Vector[Rate], // annual lending rate per bank (index = BankId)
    depositRates: Vector[Rate], // annual deposit rate per bank (index = BankId)
)

/** Per-bank HH flow accumulator for multi-bank mode (one per BankId). */
case class PerBankFlow(
    income: PLN,                          // total income (incl. deposit interest)
    consumption: PLN,                     // total consumption (goods + rent)
    debtService: PLN,                     // total mortgage/secured debt service
    mortgageInterest: PLN,                // mortgage interest routed to this bank
    depositInterest: PLN,                 // total deposit interest paid
    consumerDebtService: PLN,             // consumer (unsecured) debt service
    consumerOrigination: PLN,             // underwritten consumer-credit origination only
    consumerApprovedOrigination: PLN,     // underwritten consumer credit originated by the DTI rule
    consumerBankRejectedOrigination: PLN, // underwritten consumer-credit demand rejected by bank supply
    liquidityShortfallFinancing: PLN,     // same-month bridge/write-off preventing negative deposits
    consumerDefault: PLN,                 // ordinary consumer-loan principal default
    consumerLoanDefault: PLN,             // default of ordinary outstanding consumer-loan principal
    consumerPrincipal: PLN,               // consumer loan principal repaid
)

object PerBankFlow:
  val zero: PerBankFlow =
    PerBankFlow(PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero)

/** Public household-agent contract. Heavy monthly transition logic lives under
  * `agents.household`; this object keeps the stable API used by the engine,
  * tests, ledger projection, and diagnostics.
  */
object Household:
  /** Product-aware bank-side credit-supply boundary used after household-side
    * eligibility and capacity checks.
    */
  type BankCreditSupply = Banking.CreditSupply

  def isEmployed(hh: State): Boolean =
    hh.status match
      case HhStatus.Employed(_, _, _) => true
      case _                          => false

  def countEmployed(households: Vector[State]): Int =
    households.count(isEmployed)

  // ---- Individual household ----

  /** Full state of a single household agent, carried across simulation months.
    */
  case class State(
      id: HhId,                                                                           // unique household identifier
      monthlyRent: PLN,                                                                   // monthly rent payment (to landlord / housing market)
      skill: Share,                                                                       // labor productivity multiplier [0,1], decays during unemployment
      healthPenalty: Share,                                                               // cumulative health penalty from long-term unemployment (scarring)
      mpc: Share,                                                                         // marginal propensity to consume (Beta-sampled at init)
      status: HhStatus,                                                                   // current employment/activity status
      socialNeighbors: Array[HhId],                                                       // Watts-Strogatz social network neighbor IDs
      bankId: BankId,                                                                     // index into the explicit bank vector (multi-bank)
      lastSectorIdx: SectorIdx,                                                           // last sector employed in (-1 = never)
      isImmigrant: Boolean,                                                               // immigrant status for wage discount + remittances
      numDependentChildren: Int,                                                          // children <= 18 for 800+ social transfers
      education: Int,                                                                     // education level: 0=Primary, 1=Vocational, 2=Secondary, 3=Tertiary
      taskRoutineness: Share,                                                             // how routine is this worker's task bundle [0,1] (Acemoglu & Restrepo 2020)
      wageScar: Share,                                                                    // persistent wage penalty from unemployment spell (Jacobson et al. 1993)
      financialDistressMonths: Int = 0,                                                   // consecutive months of deep financial distress
      contractType: ContractType = ContractType.Permanent,                                // employment contract type (Kodeks Pracy / umowa zlecenie / B2B)
      region: Region = Region.Central,                                                    // NUTS-1 macroregion (geographic labor market)
      financialDistressState: HhFinancialDistressState = HhFinancialDistressState.Current, // financial arrears/default lifecycle state
  )

  /** Ledger-contracted household financial stocks carried through household
    * execution.
    */
  case class FinancialStocks(
      demandDeposit: PLN,              // non-negative bank demand deposits owned by the household
      mortgageLoan: PLN,               // outstanding secured mortgage principal
      consumerLoan: PLN,               // outstanding unsecured consumer-loan principal
      equity: PLN,                     // listed equity owned by the household
      mortgageRemainingMonths: Int = 0, // remaining contractual mortgage-payment months; 0 means no active mortgage
  ):
    require(mortgageRemainingMonths >= 0, s"mortgageRemainingMonths must be non-negative: $mortgageRemainingMonths")

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

  private[amorfati] def activeMortgageRemainingMonths(stocks: FinancialStocks)(using p: SimParams): Int =
    HouseholdMortgageSchedule.activeMortgageRemainingMonths(stocks)

  private[amorfati] def scheduledMortgagePrincipal(stocks: FinancialStocks)(using p: SimParams): PLN =
    HouseholdMortgageSchedule.scheduledMortgagePrincipal(stocks)

  private[amorfati] def remainingMortgageMonthsAfterPayment(stocks: FinancialStocks, closingMortgageLoan: PLN)(using p: SimParams): Int =
    HouseholdMortgageSchedule.remainingMortgageMonthsAfterPayment(stocks, closingMortgageLoan)

  /** Aggregate statistics computed from individual households (Paper-06). */
  case class Aggregates(
      employed: Int,                                            // count of employed HH
      unemployed: Int,                                          // count of unemployed HH
      retraining: Int,                                          // count of HH in retraining
      bankrupt: Int,                                            // count of bankrupt HH
      totalIncome: PLN,                                         // aggregate income (wages + benefits + interest + transfers)
      consumption: PLN,                                         // aggregate consumption (goods + rent)
      domesticConsumption: PLN,                                 // domestic component of consumption
      importConsumption: PLN,                                   // import component of consumption
      marketWage: PLN,                                          // current market-clearing wage
      reservationWage: PLN,                                     // minimum acceptable wage for job search
      giniIndividual: Share,                                    // Gini of income distribution
      giniWealth: Share,                                        // Gini of liquid demand-deposit wealth
      meanSavings: PLN,                                         // mean liquid demand deposits across all HH
      medianSavings: PLN,                                       // median liquid demand deposits across all HH
      povertyRate50: Share,                                     // share with income < 50% median (EU AROP)
      bankruptcyRate: Share,                                    // share of bankrupt HH
      meanSkill: Share,                                         // mean skill of alive (non-bankrupt) HH
      meanHealthPenalty: Share,                                 // mean health scarring of alive HH
      retrainingAttempts: Int,                                  // retraining attempts this month
      retrainingSuccesses: Int,                                 // successful retraining completions this month
      consumptionP10: PLN,                                      // 10th percentile of consumption
      consumptionP50: PLN,                                      // median consumption
      consumptionP90: PLN,                                      // 90th percentile of consumption
      meanMonthsToRuin: Scalar,                                 // mean months until bankruptcy (placeholder)
      povertyRate30: Share,                                     // share with income < 30% median (deep poverty)
      totalRent: PLN,                                           // aggregate rent payments
      totalDebtService: PLN,                                    // aggregate secured debt service
      totalUnempBenefits: PLN,                                  // aggregate unemployment benefits paid
      totalDepositInterest: PLN,                                // aggregate deposit interest received
      crossSectorHires: Int,                                    // cross-sector hires this month
      voluntaryQuits: Int,                                      // voluntary quits (cross-sector search)
      sectorMobilityRate: Share,                                // fraction employed in different sector than last
      totalRemittances: PLN,                                    // aggregate remittances sent abroad
      totalPit: PLN,                                            // aggregate PIT paid
      totalSocialTransfers: PLN,                                // aggregate 800+ social transfers
      totalConsumerDebtService: PLN,                            // aggregate consumer debt service
      totalConsumerOrigination: PLN,                            // aggregate underwritten consumer-credit origination only
      totalConsumerApprovedOrigination: PLN,                    // aggregate underwritten consumer-credit origination
      totalLiquidityShortfallFinancing: PLN,                    // aggregate same-month bridge/write-off for liquidity gaps
      totalConsumerDefault: PLN,                                // aggregate ordinary consumer-loan principal defaults
      totalConsumerPrincipal: PLN,                              // aggregate consumer loan principal repaid
      totalConsumerCreditDemand: PLN = PLN.Zero,                // aggregate underwritten consumer-credit demand before eligibility denial
      totalConsumerRejectedOrigination: PLN = PLN.Zero,         // aggregate consumer-credit demand rejected by borrower or bank rules
      totalConsumerBankRejectedOrigination: PLN = PLN.Zero,     // aggregate consumer-credit demand rejected by bank supply
      totalConsumerBankPortfolioRejected: PLN = PLN.Zero,       // bank-supply rejection explained by loan-vs-sovereign portfolio preference
      totalConsumerLoanDefault: PLN = PLN.Zero,                 // default of ordinary outstanding consumer-loan principal
      totalLiquidityBridgeChargeOff: PLN = PLN.Zero,            // same-month bridge charge-off, not ordinary consumer-loan default
      totalUnmetBasicConsumption: PLN = PLN.Zero,               // basic consumption need not covered by cash before arrears/default
      totalDiscretionaryConsumptionCompression: PLN = PLN.Zero, // discretionary consumption cut before bridge/default
      totalConsumptionShortfall: PLN = PLN.Zero,                // shortfall component attributed to consumption
      totalRentArrears: PLN = PLN.Zero,                         // shortfall component attributed to rent
      totalMortgageArrears: PLN = PLN.Zero,                     // shortfall component attributed to mortgage service
      totalConsumerDebtArrears: PLN = PLN.Zero,                 // shortfall component attributed to consumer debt service
      totalTemporaryOverdraft: PLN = PLN.Zero,                  // shortfall component attributed to other liquidity gaps
      totalMortgagePrincipal: PLN = PLN.Zero,                   // aggregate secured mortgage principal repaid
      totalMortgageInterest: PLN = PLN.Zero,                    // aggregate secured mortgage interest paid
      distressCurrent: Int = 0,                                 // count of HH with no active financial distress
      distressLiquidityStress: Int = 0,                         // count of HH in first-month liquidity stress
      distressArrears: Int = 0,                                 // count of HH with repeated arrears/shortfall stress
      distressRestructuring: Int = 0,                           // count of HH in post-arrears restructuring/recovery
      distressDefaulted: Int = 0,                               // count of HH in persistent default before personal-insolvency filing
      distressBankruptcy: Int = 0,                              // count of HH in personal-insolvency filing/workout state
  ):
    def totalLiquidityShortfallComponents: PLN =
      totalConsumptionShortfall + totalRentArrears + totalMortgageArrears + totalConsumerDebtArrears + totalTemporaryOverdraft

    def distressActiveCount: Int =
      distressLiquidityStress + distressArrears + distressRestructuring + distressDefaulted + distressBankruptcy

    def distressActiveShare(totalPopulation: Int): Share =
      if totalPopulation > 0 then Share.fraction(distressActiveCount, totalPopulation) else Share.Zero

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
        totalConsumerCreditDemand = flowTotals.totalConsumerCreditDemand,
        totalConsumerRejectedOrigination = flowTotals.totalConsumerRejectedOrigination,
        totalConsumerBankRejectedOrigination = flowTotals.totalConsumerBankRejectedOrigination,
        totalConsumerBankPortfolioRejected = flowTotals.totalConsumerBankPortfolioRejected,
        totalConsumerLoanDefault = flowTotals.totalConsumerLoanDefault,
        totalLiquidityBridgeChargeOff = flowTotals.totalLiquidityBridgeChargeOff,
        totalUnmetBasicConsumption = flowTotals.totalUnmetBasicConsumption,
        totalDiscretionaryConsumptionCompression = flowTotals.totalDiscretionaryConsumptionCompression,
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
      householdId: HhId,                                            // household identifier for joining with closed-month state
      openingDemandDeposit: PLN,                                    // opening liquid deposit before the household month step
      openingConsumerLoan: PLN,                                     // opening unsecured consumer-loan principal
      monthlyIncome: PLN,                                           // net monthly household income after PIT and transfers
      consumption: PLN,                                             // monthly household goods consumption
      rent: PLN,                                                    // monthly rent paid by the household
      mortgageDebtService: PLN,                                     // monthly secured mortgage debt service
      consumerApprovedOrigination: PLN,                             // underwritten consumer credit originated by the DTI rule
      consumerCreditDemand: PLN,                                    // underwritten consumer-credit demand before eligibility denial
      consumerRejectedOrigination: PLN,                             // consumer-credit demand rejected by borrower or bank rules
      consumerBankRejectedOrigination: PLN,                         // consumer-credit demand rejected by bank supply
      consumerCreditCapacity: PLN,                                  // principal capacity implied by payment-factor underwriting
      consumerCreditAccessEligible: Boolean,                        // whether stochastic access allowed underwritten credit
      liquidityShortfallFinancing: PLN,                             // same-month bridge/write-off preventing negative closing deposits
      consumerDebtService: PLN,                                     // monthly unsecured consumer-credit debt service
      consumerDefault: PLN,                                         // ordinary consumer-loan principal default this month
      consumerPrincipal: PLN,                                       // principal component of consumer debt service
      closingConsumerLoan: PLN,                                     // closing unsecured consumer-loan principal
      consumerLoanDefault: PLN,                                     // default of ordinary outstanding consumer-loan principal
      liquidityBridgeChargeOff: PLN,                                // same-month bridge charge-off, not ordinary consumer-loan default
      unmetBasicConsumption: PLN,                                   // non-discretionary consumption need not covered by cash
      discretionaryConsumptionCompression: PLN,                     // discretionary consumption cut before bridge/default
      consumptionShortfall: PLN,                                    // shortfall attributed to modeled consumption outflow
      rentArrears: PLN,                                             // shortfall attributed to rent payment
      mortgageArrears: PLN,                                         // shortfall attributed to mortgage debt service
      consumerDebtArrears: PLN,                                     // shortfall attributed to consumer debt service
      temporaryOverdraft: PLN,                                      // shortfall attributed to other current liquidity gaps
      consumerBankApprovalProduct: String,                          // bank-credit product submitted for consumer-credit supply
      consumerBankRejectionReason: String,                          // bank-side rejection reason, empty when no bank rejection
      consumerBankApprovalProbability: Option[Share],               // bank-side approval probability if evaluated
      consumerBankApprovalRoll: Option[Share],                      // replay/audit draw if sampled by the bank
      consumerBankProjectedCar: Option[Multiplier],                 // projected bank CAR after requested exposure
      consumerBankMinCar: Option[Multiplier],                       // effective minimum CAR used by the bank gate
      consumerBankManagementCarTarget: Option[Multiplier],          // bank management CAR target used by the soft throttle
      consumerBankCapitalThrottle: Option[Share],                   // capital-buffer approval throttle
      consumerBankLcr: Option[Multiplier],                          // LCR observed by the bank gate
      consumerBankNsfr: Option[Multiplier],                         // NSFR observed by the bank gate
      consumerBankPortfolioChoice: Option[BankPortfolioChoiceAudit], // loan-vs-sovereign portfolio-choice audit when bank supply ran
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
        consumerCreditDemand = PLN.Zero,
        consumerRejectedOrigination = PLN.Zero,
        consumerBankRejectedOrigination = PLN.Zero,
        consumerCreditCapacity = PLN.Zero,
        consumerCreditAccessEligible = false,
        liquidityShortfallFinancing = PLN.Zero,
        consumerDebtService = PLN.Zero,
        consumerDefault = PLN.Zero,
        consumerPrincipal = PLN.Zero,
        closingConsumerLoan = stocks.consumerLoan,
        consumerLoanDefault = PLN.Zero,
        liquidityBridgeChargeOff = PLN.Zero,
        unmetBasicConsumption = PLN.Zero,
        discretionaryConsumptionCompression = PLN.Zero,
        consumptionShortfall = PLN.Zero,
        rentArrears = PLN.Zero,
        mortgageArrears = PLN.Zero,
        consumerDebtArrears = PLN.Zero,
        temporaryOverdraft = PLN.Zero,
        consumerBankApprovalProduct = "",
        consumerBankRejectionReason = "",
        consumerBankApprovalProbability = None,
        consumerBankApprovalRoll = None,
        consumerBankProjectedCar = None,
        consumerBankMinCar = None,
        consumerBankManagementCarTarget = None,
        consumerBankCapitalThrottle = None,
        consumerBankLcr = None,
        consumerBankNsfr = None,
        consumerBankPortfolioChoice = None,
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
      calibrateInitialUnemployedRunway(Population(households, calibratedStocks))

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
            val months  = sampleInitialUnemployedMonths(sectorRng)
            sampled.copy(
              state = sampled.state.copy(
                status = HhStatus.Unemployed(months),
                bankId = firm.bankId,
                lastSectorIdx = firm.sector,
              ),
            )
          .toVector

    private def sampleInitialUnemployedMonths(rng: RandomStream)(using p: SimParams): Int =
      if p.household.initialUnemployedMaxMonths <= 0 then 0
      else rng.nextInt(p.household.initialUnemployedMaxMonths + 1)

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
        if target <= PLN.Zero then stocks.map(_.copy(mortgageLoan = PLN.Zero, mortgageRemainingMonths = 0))
        else
          val current   = stocks.iterator.map(_.mortgageLoan).sumPln
          val weights   =
            if current > PLN.Zero then stocks.map(_.mortgageLoan.distributeRaw).toArray
            else Array.fill(stocks.length)(1L)
          val allocated = com.boombustgroup.ledger.Distribute.distribute(target.toLong, weights)
          stocks.zip(allocated).map { case (stock, rawMortgageLoan) =>
            val mortgageLoan = PLN.fromRaw(rawMortgageLoan)
            val remaining    =
              if mortgageLoan <= PLN.Zero then 0
              else if stock.mortgageRemainingMonths > 0 then stock.mortgageRemainingMonths
              else p.housing.mortgageMaturity
            stock.copy(mortgageLoan = mortgageLoan, mortgageRemainingMonths = remaining)
          }

    private case class RunwayCalibrationResult(
        state: State,
        financialStocks: FinancialStocks,
        consumerLoanReduction: PLN,
        mortgageLoanReduction: PLN,
    )

    private def calibrateInitialUnemployedRunway(population: Population)(using p: SimParams): Population =
      if p.household.initialUnemployedRunwayMonths <= 0 || population.households.isEmpty then population
      else
        val households             = population.households.toArray
        val stocks                 = population.financialStocks.toArray
        var consumerLoanReductions = PLN.Zero
        var mortgageLoanReductions = PLN.Zero
        var i                      = 0
        while i < households.length do
          households(i).status match
            case _: HhStatus.Unemployed =>
              val calibrated = calibrateInitialUnemployedHousehold(households(i), stocks(i))
              households(i) = calibrated.state
              stocks(i) = calibrated.financialStocks
              consumerLoanReductions = consumerLoanReductions + calibrated.consumerLoanReduction
              mortgageLoanReductions = mortgageLoanReductions + calibrated.mortgageLoanReduction
            case _                      =>
          i += 1
        val calibratedHouseholds   = households.toVector
        val calibratedStocks       = stocks.toVector
        val withConsumerRebalanced = redistributeConsumerLoansToEmployed(calibratedHouseholds, calibratedStocks, consumerLoanReductions)
        val withMortgageRebalanced = redistributeMortgageLoansToEmployed(calibratedHouseholds, withConsumerRebalanced, mortgageLoanReductions)
        Population(calibratedHouseholds, withMortgageRebalanced)

    private def calibrateInitialUnemployedHousehold(hh: State, stocks: FinancialStocks)(using p: SimParams): RunwayCalibrationResult =
      val consumerRate             = p.monetary.initialRate + p.household.ccSpread
      val consumerPaymentFactorRaw = (p.household.ccAmortRate + consumerRate.monthly).toLong
      val consumerReduction        = principalReductionForRunwayDeficit(runwayDeficit(hh, stocks), consumerPaymentFactorRaw, stocks.consumerLoan)
      val afterConsumer            =
        if consumerReduction > PLN.Zero then stocks.copy(consumerLoan = (stocks.consumerLoan - consumerReduction).max(PLN.Zero))
        else stocks
      val mortgagePaymentFactor    = mortgagePaymentFactorRaw(afterConsumer)
      val mortgageReduction        = principalReductionForRunwayDeficit(runwayDeficit(hh, afterConsumer), mortgagePaymentFactor, afterConsumer.mortgageLoan)
      val afterMortgageLoan        = (afterConsumer.mortgageLoan - mortgageReduction).max(PLN.Zero)
      val afterMortgage            =
        if mortgageReduction > PLN.Zero then
          afterConsumer.copy(
            mortgageLoan = afterMortgageLoan,
            mortgageRemainingMonths = if afterMortgageLoan <= PLN.Zero then 0 else afterConsumer.mortgageRemainingMonths,
          )
        else afterConsumer
      val rentReduction            = rentReductionForRunwayDeficit(runwayDeficit(hh, afterMortgage))
      val afterRent                =
        if rentReduction > PLN.Zero then hh.copy(monthlyRent = (hh.monthlyRent - rentReduction).max(p.household.rentFloor))
        else hh
      RunwayCalibrationResult(afterRent, afterMortgage, consumerReduction, mortgageReduction)

    private def runwayDeficit(hh: State, stocks: FinancialStocks)(using p: SimParams): PLN =
      val surplus = initialUnemployedRunwaySurplus(hh, stocks)
      (PLN.Zero - surplus).max(PLN.Zero)

    private[amorfati] def initialUnemployedRunwaySurplus(hh: State, stocks: FinancialStocks)(using p: SimParams): PLN =
      initialUnemployedLiquidResources(hh, stocks) - initialUnemployedEssentialOutflows(hh, stocks)

    private[amorfati] def initialUnemployedRunwayMonths(hh: State, stocks: FinancialStocks)(using p: SimParams): Int =
      val startingMonths  = hh.status match
        case HhStatus.Unemployed(months) => months
        case _                           => 0
      val monthlyOutflows = initialUnemployedMonthlyEssentialOutflows(stocks) + hh.monthlyRent + p.household.basicConsumptionFloor
      var liquid          = stocks.demandDeposit
      var month           = 0
      while month < p.household.initialUnemployedRunwayMonths do
        val income = HouseholdIncomeConstruction.computeBenefit(startingMonths + month) + (p.fiscal.social800 * hh.numDependentChildren)
        if liquid + income < monthlyOutflows then return month
        liquid = liquid + income - monthlyOutflows
        month += 1
      month

    private def initialUnemployedLiquidResources(hh: State, stocks: FinancialStocks)(using p: SimParams): PLN =
      stocks.demandDeposit + expectedInitialUnemployedBenefits(hh) + expectedInitialUnemployedSocialTransfers(hh)

    private def expectedInitialUnemployedBenefits(hh: State)(using p: SimParams): PLN =
      val startingMonths = hh.status match
        case HhStatus.Unemployed(months) => months
        case _                           => 0
      (0 until p.household.initialUnemployedRunwayMonths)
        .map(offset => HouseholdIncomeConstruction.computeBenefit(startingMonths + offset))
        .sumPln

    private def expectedInitialUnemployedSocialTransfers(hh: State)(using p: SimParams): PLN =
      if hh.numDependentChildren <= 0 then PLN.Zero
      else p.fiscal.social800 * hh.numDependentChildren * p.household.initialUnemployedRunwayMonths

    private def initialUnemployedEssentialOutflows(hh: State, stocks: FinancialStocks)(using p: SimParams): PLN =
      (initialUnemployedMonthlyEssentialOutflows(stocks) + hh.monthlyRent + p.household.basicConsumptionFloor) * p.household.initialUnemployedRunwayMonths

    private def initialUnemployedMonthlyEssentialOutflows(stocks: FinancialStocks)(using p: SimParams): PLN =
      scheduledOpeningMortgageDebtService(stocks) + scheduledOpeningConsumerDebtService(stocks)

    private def scheduledOpeningMortgageDebtService(stocks: FinancialStocks)(using p: SimParams): PLN =
      val mortgageRate = p.monetary.initialRate + p.housing.mortgageSpread
      HouseholdMortgageSchedule.scheduledMortgagePrincipal(stocks) + (stocks.mortgageLoan * mortgageRate.monthly)

    private def scheduledOpeningConsumerDebtService(stocks: FinancialStocks)(using p: SimParams): PLN =
      val consumerRate = p.monetary.initialRate + p.household.ccSpread
      val factor       = p.household.ccAmortRate + consumerRate.monthly
      stocks.consumerLoan * factor

    private def mortgagePaymentFactorRaw(stocks: FinancialStocks)(using p: SimParams): Long =
      if stocks.mortgageLoan <= PLN.Zero then 0L
      else
        val remainingMonths = HouseholdMortgageSchedule.activeMortgageRemainingMonths(stocks).max(1)
        val principalRaw    = FixedPointBase.divideRaw(FixedPointBase.Scale, remainingMonths.toLong)
        val mortgageRate    = p.monetary.initialRate + p.housing.mortgageSpread
        principalRaw + mortgageRate.monthly.toLong

    private def principalReductionForRunwayDeficit(deficit: PLN, monthlyPaymentFactorRaw: Long, principal: PLN)(using p: SimParams): PLN =
      val horizon = p.household.initialUnemployedRunwayMonths
      if deficit <= PLN.Zero || monthlyPaymentFactorRaw <= 0L || principal <= PLN.Zero || horizon <= 0 then PLN.Zero
      else
        val denominator = BigInt(monthlyPaymentFactorRaw) * BigInt(horizon)
        val requiredRaw = ((BigInt(deficit.toLong) * BigInt(FixedPointBase.Scale)) + denominator - 1) / denominator
        PLN.fromRaw(requiredRaw.min(BigInt(principal.toLong)).toLong)

    private def rentReductionForRunwayDeficit(deficit: PLN)(using p: SimParams): PLN =
      val horizon = p.household.initialUnemployedRunwayMonths
      if deficit <= PLN.Zero || horizon <= 0 then PLN.Zero
      else
        val requiredRaw = (BigInt(deficit.toLong) + BigInt(horizon - 1)) / BigInt(horizon)
        PLN.fromRaw(requiredRaw.toLong)

    private def redistributeConsumerLoansToEmployed(households: Vector[State], stocks: Vector[FinancialStocks], amount: PLN): Vector[FinancialStocks] =
      redistributeLoansToEmployed(
        households,
        stocks,
        amount,
        _.consumerLoan.distributeRaw,
        (stock, allocation) => stock.copy(consumerLoan = stock.consumerLoan + allocation),
      )

    private def redistributeMortgageLoansToEmployed(households: Vector[State], stocks: Vector[FinancialStocks], amount: PLN)(using
        p: SimParams,
    ): Vector[FinancialStocks] =
      redistributeLoansToEmployed(
        households,
        stocks,
        amount,
        _.mortgageLoan.distributeRaw,
        (stock, allocation) =>
          val mortgageLoan = stock.mortgageLoan + allocation
          val remaining    =
            if mortgageLoan <= PLN.Zero then 0
            else if stock.mortgageRemainingMonths > 0 then stock.mortgageRemainingMonths
            else p.housing.mortgageMaturity
          stock.copy(mortgageLoan = mortgageLoan, mortgageRemainingMonths = remaining),
      )

    private def redistributeLoansToEmployed(
        households: Vector[State],
        stocks: Vector[FinancialStocks],
        amount: PLN,
        weight: FinancialStocks => Long,
        update: (FinancialStocks, PLN) => FinancialStocks,
    ): Vector[FinancialStocks] =
      if amount <= PLN.Zero then stocks
      else
        val recipients = employedRecipientIndices(households)
        if recipients.isEmpty then stocks
        else
          val weights     = positiveOrEqualWeights(recipients.map(idx => weight(stocks(idx))))
          val allocations = com.boombustgroup.ledger.Distribute.distribute(amount.toLong, weights)
          recipients.zip(allocations).foldLeft(stocks) { case (updated, (idx, rawAmount)) =>
            if rawAmount <= 0L then updated
            else
              val stock = updated(idx)
              updated.updated(idx, update(stock, PLN.fromRaw(rawAmount)))
          }

    private def employedRecipientIndices(households: Vector[State]): Vector[Int] =
      households.indices.filter(idx => households(idx).status.isInstanceOf[HhStatus.Employed]).toVector

    private def positiveOrEqualWeights(weights: Vector[Long]): Array[Long] =
      if weights.exists(_ > 0L) then weights.toArray
      else Array.fill(weights.length)(1L)

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
          mortgageRemainingMonths = if debt > PLN.Zero then p.housing.mortgageMaturity else 0,
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
      * High-sigma sectors (BPO) have more automatable routine tasks; high
      * education workers tend toward cognitive (non-routine) tasks. Acemoglu &
      * Restrepo 2020.
      */
    private[agents] def sampleTaskRoutineness(edu: Int, sectorIdx: SectorIdx, rng: RandomStream)(using p: SimParams): Share =
      val eduBase  = p.labor.sbtcEduRoutineness(edu)
      val sigmaAdj = (Scalar.decimal(5, 2) * p.sectorDefs(sectorIdx.toInt).sigma.toScalar.log10).toShare
      val noise    = Scalar.randomBetween(Scalar.decimal(-25, 3), Scalar.decimal(25, 3), rng).toShare
      (eduBase + sigmaAdj + noise).clamp(Share.decimal(5, 2), Share.decimal(95, 2))

  /** Monthly PIT using the model's progressive Polish tax brackets. */
  def computeMonthlyPit(monthlyIncome: PLN)(using p: SimParams): PLN =
    HouseholdIncomeConstruction.computeMonthlyPit(monthlyIncome)

  /** Child-dependent household social transfer. */
  def computeSocialTransfer(numChildren: Int)(using p: SimParams): PLN =
    HouseholdIncomeConstruction.computeSocialTransfer(numChildren)

  /** Unemployment benefit from spell duration. */
  def computeBenefit(monthsUnemployed: Int)(using p: SimParams): PLN =
    HouseholdIncomeConstruction.computeBenefit(monthsUnemployed)

  /** Public test hook for the buffer-stock MPC rule. */
  private[amorfati] def updateMpc(hh: State, financialStocks: FinancialStocks, income: PLN, status: HhStatus)(using p: SimParams): Share =
    HouseholdDistressMachine.updateMpc(hh, financialStocks, income, status)

  /** Public test hook for controlled savings-buffer drawdown. */
  private[amorfati] def computeSavingsDrawdown(
      financialStocks: FinancialStocks,
      cashOnHand: PLN,
      status: HhStatus,
  )(using p: SimParams): PLN =
    HouseholdDistressMachine.computeSavingsDrawdown(financialStocks, cashOnHand, status)

  /** Monthly entry point for the household agent. */
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
      bankCreditSupply: Option[BankCreditSupply] = None,
  )(using p: SimParams): StepResult =
    HouseholdStepRunner.step(
      households,
      financialStocks,
      world,
      marketWage,
      reservationWage,
      importAdj,
      rng,
      nBanks,
      bankRates,
      equityIndexReturn,
      sectorWages,
      sectorVacancies,
      bankCreditSupply,
    )

  /** Public entry point for household aggregate statistics. */
  def computeAggregates(
      households: Vector[State],
      financialStocks: Vector[FinancialStocks],
      marketWage: PLN,
      reservationWage: PLN,
      importAdj: Share,
      retrainingAttempts: Int,
      retrainingSuccesses: Int,
  )(using SimParams): Aggregates =
    HouseholdAggregateComputation.computeAggregates(
      households,
      financialStocks,
      marketWage,
      reservationWage,
      importAdj,
      retrainingAttempts,
      retrainingSuccesses,
    )

  /** Gini coefficient for a pre-sorted array. */
  def giniSorted(sorted: Array[Long]): Share =
    HouseholdDistributionStats.giniSorted(sorted)
