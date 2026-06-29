package com.boombustgroup.amorfati.agents

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import com.boombustgroup.amorfati.TestHouseholdState

import com.boombustgroup.amorfati.TestFirmState

import org.scalatest.flatspec.AnyFlatSpec
import com.boombustgroup.amorfati.Generators
import org.scalatest.matchers.should.Matchers
import com.boombustgroup.amorfati.config.{SimParams, SimParamsTestOverrides}
import com.boombustgroup.amorfati.engine.World
import com.boombustgroup.amorfati.engine.markets.OpenEconomy
import com.boombustgroup.amorfati.types.*

import com.boombustgroup.amorfati.random.RandomStream

class HouseholdSpec extends AnyFlatSpec with Matchers:

  given SimParams          = SimParams.defaults
  private val p: SimParams = summon[SimParams]

  private inline def shareValue(s: Share): BigDecimal =
    decimal(s)

  private val financialById = scala.collection.mutable.Map.empty[Int, Household.FinancialStocks]

  extension (hh: Household.State)
    private def savings: PLN      = stockOf(hh).demandDeposit
    private def debt: PLN         = stockOf(hh).mortgageLoan
    private def consumerDebt: PLN = stockOf(hh).consumerLoan
    private def equityWealth: PLN = stockOf(hh).equity

  private def stockOf(hh: Household.State): Household.FinancialStocks =
    financialById.getOrElse(hh.id.toInt, Household.FinancialStocks(PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero))

  private def step(
      households: Vector[Household.State],
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
      bankCreditSupply: Option[Household.BankCreditSupply] = None,
  ): Household.StepResult =
    val result = Household.step(
      households,
      households.map(stockOf),
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
    result.households.zip(result.financialStocks).foreach((hh, stocks) => financialById.update(hh.id.toInt, stocks))
    result

  private def computeAggregates(
      households: Vector[Household.State],
      marketWage: PLN,
      reservationWage: PLN,
      importAdj: Share,
      retrainingAttempts: Int,
      retrainingSuccesses: Int,
  ): Household.Aggregates =
    Household.computeAggregates(
      households,
      households.map(stockOf),
      marketWage,
      reservationWage,
      importAdj,
      retrainingAttempts,
      retrainingSuccesses,
    )

  // --- HouseholdInit ---

  "Household.Init.initialize" should "create correct number of households" in {
    val rng     = RandomStream.seeded(42)
    val firms   = mkFirms(100)
    val network = Array.fill(1000)(Array.empty[Int])
    val hhs     = Household.Init.initialize(1000, firms, network, rng).households
    hhs.length shouldBe 1000
  }

  it should "start all households as Employed" in {
    val rng     = RandomStream.seeded(42)
    val firms   = mkFirms(100)
    val network = Array.fill(500)(Array.empty[Int])
    val hhs     = Household.Init.initialize(500, firms, network, rng).households
    hhs.foreach { hh =>
      hh.status shouldBe a[HhStatus.Employed]
    }
  }

  it should "assign endogenous contract types from sector mixes" in {
    val rng     = RandomStream.seeded(42)
    val firms   = mkFirms(100)
    val network = Array.fill(1000)(Array.empty[Int])
    val hhs     = Household.Init.initialize(1000, firms, network, rng).households

    hhs.exists(_.contractType == ContractType.Zlecenie) shouldBe true
    hhs.exists(_.contractType == ContractType.B2B) shouldBe true
    hhs.count(_.contractType == ContractType.Permanent) should be < hhs.length
  }

  it should "assign positive savings to all households" in {
    val rng     = RandomStream.seeded(42)
    val firms   = mkFirms(50)
    val network = Array.fill(200)(Array.empty[Int])
    val init    = Household.Init.initialize(200, firms, network, rng)
    init.financialStocks.foreach(_.demandDeposit should be > PLN.Zero)
  }

  it should "have MPC in [0.5, 0.98]" in {
    val rng     = RandomStream.seeded(42)
    val firms   = mkFirms(50)
    val network = Array.fill(500)(Array.empty[Int])
    val hhs     = Household.Init.initialize(500, firms, network, rng).households
    hhs.foreach { hh =>
      hh.mpc should be >= Share.decimal(5, 1)
      hh.mpc should be <= Share.decimal(98, 2)
    }
  }

  it should "have skill in [0.3, 1.0]" in {
    val rng     = RandomStream.seeded(42)
    val firms   = mkFirms(50)
    val network = Array.fill(500)(Array.empty[Int])
    val hhs     = Household.Init.initialize(500, firms, network, rng).households
    hhs.foreach { hh =>
      hh.skill should be >= Share.decimal(3, 1)
      hh.skill should be <= Share.One
    }
  }

  it should "have rent >= floor" in {
    val rng     = RandomStream.seeded(42)
    val firms   = mkFirms(50)
    val network = Array.fill(500)(Array.empty[Int])
    val hhs     = Household.Init.initialize(500, firms, network, rng).households
    hhs.foreach(_.monthlyRent should be >= p.household.rentFloor)
  }

  // --- Household.step ---

  "Household.step" should "not change bankrupt households" in {
    val rng     = RandomStream.seeded(42)
    val hhs     = Vector(
      mkHousehold(0, HhStatus.Bankrupt, savings = PLN(0)),
      mkHousehold(1, HhStatus.Employed(FirmId(0), SectorIdx(2), PLN(8000)), savings = PLN(50000)),
    )
    val updated = step(hhs, mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng).households
    updated(0).status shouldBe HhStatus.Bankrupt
  }

  it should "increase unemployment months for unemployed" in {
    val rng     = RandomStream.seeded(42)
    val hhs     = Vector(
      mkHousehold(0, HhStatus.Unemployed(3), savings = PLN(50000)),
    )
    val updated = step(hhs, mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng).households
    updated(0).status match
      case HhStatus.Unemployed(m)       => m should be >= 4
      case HhStatus.Retraining(_, _, _) => succeed // may enter retraining
      case HhStatus.Bankrupt            => succeed // may go bankrupt
      case other                        => fail(s"Unexpected status: $other")
  }

  it should "apply skill decay after scarring onset" in {
    val rng     = RandomStream.seeded(42)
    val hh      = mkHousehold(0, HhStatus.Unemployed(5), savings = PLN(100000), skill = BigDecimal("0.8"))
    val updated = step(Vector(hh), mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng).households
    updated(0).skill should be < Share.decimal(8, 1)
  }

  it should "not decay skill before scarring onset" in {
    val rng     = RandomStream.seeded(42)
    val hh      = mkHousehold(0, HhStatus.Unemployed(1), savings = PLN(100000), skill = BigDecimal("0.8"))
    val updated = step(Vector(hh), mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng).households
    updated(0).skill shouldBe Share.decimal(8, 1)
  }

  it should "apply health scarring after onset" in {
    val rng     = RandomStream.seeded(42)
    val hh      = mkHousehold(0, HhStatus.Unemployed(5), savings = PLN(100000), healthPenalty = BigDecimal("0.0"))
    val updated = step(Vector(hh), mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng).households
    updated(0).healthPenalty should be > Share.Zero
  }

  it should "not bankrupt household after a single month of deep distress" in {
    val rng     = RandomStream.seeded(42)
    val hh      = mkHousehold(0, HhStatus.Unemployed(1), savings = PLN.Zero, rent = PLN(10000))
    val updated = step(Vector(hh), mkLiquidityShockWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng).households
    updated(0).status should not be HhStatus.Bankrupt
    updated(0).financialDistressMonths shouldBe 1
    updated(0).financialDistressState shouldBe HhFinancialDistressState.LiquidityStress
  }

  it should "escalate repeated financial stress into arrears before insolvency" in {
    val rng = RandomStream.seeded(42)
    val hh  = mkHousehold(0, HhStatus.Unemployed(1), savings = PLN.Zero, rent = PLN(10000))
      .copy(financialDistressMonths = 1, financialDistressState = HhFinancialDistressState.LiquidityStress)

    val updated = step(Vector(hh), mkLiquidityShockWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng).households

    updated(0).status should not be HhStatus.Bankrupt
    updated(0).financialDistressMonths shouldBe 2
    updated(0).financialDistressState shouldBe HhFinancialDistressState.Arrears
  }

  it should "enter default at the distress threshold before personal insolvency write-off" in {
    val rng     = RandomStream.seeded(42)
    val hh      = mkHousehold(
      0,
      HhStatus.Unemployed(1),
      savings = PLN.Zero,
      rent = PLN(10000),
    ).copy(financialDistressMonths = p.household.bankruptcyDistressMonths - 1, financialDistressState = HhFinancialDistressState.Arrears)
    val updated = step(Vector(hh), mkLiquidityShockWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng).households
    updated(0).status shouldBe HhStatus.Unemployed(2)
    updated(0).financialDistressMonths shouldBe p.household.bankruptcyDistressMonths
    updated(0).financialDistressState shouldBe HhFinancialDistressState.Defaulted
  }

  it should "stay defaulted before the personal insolvency write-off threshold" in {
    val rng     = RandomStream.seeded(42)
    val hh      = mkHousehold(
      0,
      HhStatus.Unemployed(1),
      savings = PLN.Zero,
      rent = PLN(10000),
    ).copy(financialDistressMonths = p.household.bankruptcyDistressMonths, financialDistressState = HhFinancialDistressState.Defaulted)
    val updated = step(Vector(hh), mkLiquidityShockWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng).households
    updated(0).status shouldBe HhStatus.Unemployed(2)
    updated(0).financialDistressMonths shouldBe p.household.bankruptcyDistressMonths + 1
    updated(0).financialDistressState shouldBe HhFinancialDistressState.Defaulted
  }

  it should "resolve persistent deep distress without removing the household from the labor force" in {
    val rng     = RandomStream.seeded(42)
    val hh      = mkHousehold(
      0,
      HhStatus.Unemployed(1),
      savings = PLN.Zero,
      rent = PLN(10000),
    ).copy(financialDistressMonths = p.household.personalInsolvencyDistressMonths, financialDistressState = HhFinancialDistressState.Defaulted)
    val updated = step(Vector(hh), mkLiquidityShockWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng).households
    updated(0).status shouldBe HhStatus.Unemployed(2)
    updated(0).financialDistressMonths shouldBe 0
    updated(0).financialDistressState shouldBe HhFinancialDistressState.Bankruptcy
  }

  it should "preserve employment when resolving household financial insolvency" in {
    val rng = RandomStream.seeded(42)
    val hh  = mkHousehold(
      0,
      HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(8000)),
      savings = PLN.Zero,
      rent = PLN(200000),
    ).copy(financialDistressMonths = p.household.personalInsolvencyDistressMonths, financialDistressState = HhFinancialDistressState.Defaulted)

    val updated = step(Vector(hh), mkLiquidityShockWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng).households

    updated(0).status shouldBe a[HhStatus.Employed]
    updated(0).financialDistressMonths shouldBe 0
    updated(0).financialDistressState shouldBe HhFinancialDistressState.Bankruptcy
  }

  it should "default the remaining consumer credit balance after same-month debt service on bankruptcy" in {
    val rng                 = RandomStream.seeded(42)
    val world               = mkLiquidityShockWorld()
    val openingLoan         = PLN(12000)
    val hh                  = mkHousehold(
      0,
      HhStatus.Unemployed(1),
      savings = PLN.Zero,
      rent = PLN(10000),
    ).copy(financialDistressMonths = p.household.personalInsolvencyDistressMonths, financialDistressState = HhFinancialDistressState.Defaulted)
    val totalRate           = p.household.ccAmortRate + (world.nbp.referenceRate + p.household.ccSpread).monthly
    val expectedDebtService = openingLoan * totalRate
    val expectedPrincipal   = openingLoan * p.household.ccAmortRate
    val expectedDefault     = openingLoan - expectedPrincipal

    financialById.update(
      hh.id.toInt,
      TestHouseholdState.financial(
        savings = PLN.Zero,
        debt = PLN.Zero,
        consumerDebt = openingLoan,
        equityWealth = PLN.Zero,
      ),
    )

    val result = step(Vector(hh), world, PLN(8000), PLN(4666), Share.decimal(4, 1), rng)

    result.households.head.status shouldBe HhStatus.Unemployed(2)
    result.households.head.financialDistressMonths shouldBe 0
    result.financialStocks.head.demandDeposit shouldBe PLN.Zero
    result.financialStocks.head.consumerLoan shouldBe PLN.Zero
    result.aggregates.totalConsumerDebtService shouldBe expectedDebtService
    result.aggregates.totalConsumerOrigination shouldBe PLN.Zero
    result.aggregates.totalConsumerApprovedOrigination shouldBe PLN.Zero
    result.aggregates.totalLiquidityShortfallComponents shouldBe result.aggregates.totalLiquidityShortfallFinancing
    result.aggregates.totalConsumerDefault shouldBe expectedDefault
    result.aggregates.totalConsumerLoanDefault shouldBe expectedDefault
    result.aggregates.totalLiquidityBridgeChargeOff shouldBe result.monthlyFlows.head.liquidityBridgeChargeOff
    result.aggregates.totalConsumerPrincipal + result.aggregates.totalConsumerDefault shouldBe openingLoan
  }

  it should "reset financial distress months after recovery" in {
    val rng     = RandomStream.seeded(42)
    val hh      = mkHousehold(
      0,
      HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(8000)),
      savings = PLN(20000),
      rent = PLN(1800),
    ).copy(financialDistressMonths = 2, financialDistressState = HhFinancialDistressState.Arrears)
    val updated = step(Vector(hh), mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng).households
    updated(0).status shouldBe a[HhStatus.Employed]
    updated(0).financialDistressMonths shouldBe 0
    updated(0).financialDistressState shouldBe HhFinancialDistressState.Restructuring
  }

  it should "not enter voluntary retraining when the target sector has no vacancies" in {
    val rng       = RandomStream.seeded(42)
    val hhs       = (0 until 500).map: id =>
      mkHousehold(
        id,
        HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(5000)),
        savings = PLN(100000),
        rent = PLN(1800),
      )
    val stocks    = hhs.map(_ => TestHouseholdState.financial(savings = PLN(100000))).toVector
    val wages     = Some(Vector(PLN(5000), PLN(25000), PLN(24000), PLN(23000), PLN(22000), PLN(21000)))
    val vacancies = Some(Vector.fill(p.sectorDefs.length)(0))

    val result = Household.step(
      hhs.toVector,
      stocks,
      mkWorld(),
      PLN(8000),
      PLN(4666),
      Share.decimal(4, 1),
      rng,
      sectorWages = wages,
      sectorVacancies = vacancies,
    )

    result.households.foreach(_.status shouldBe a[HhStatus.Employed])
    result.aggregates.voluntaryQuits shouldBe 0
  }

  it should "move adjacent voluntary sector search into retraining eligibility" in {
    val searchP      = SimParamsTestOverrides.voluntarySearchAlways
    val rng          = RandomStream.seeded(42)
    val sourceSector = 0
    val sectorCount  = searchP.labor.frictionMatrix.length
    val targetSector = searchP.labor
      .frictionMatrix(sourceSector)
      .zipWithIndex
      .collectFirst:
        case (friction, idx) if idx != sourceSector && friction <= searchP.labor.adjacentFrictionMax => idx
      .get
    val hhs          = (0 until 500).map: id =>
      mkHousehold(
        id,
        HhStatus.Employed(FirmId(0), SectorIdx(sourceSector), PLN(5000)),
        savings = PLN(100000),
        rent = PLN(1800),
      )
    val stocks       = hhs.map(_ => TestHouseholdState.financial(savings = PLN(100000))).toVector
    val sectorWages  = Vector.tabulate(sectorCount)(idx => if idx == targetSector then PLN(25000) else PLN.Zero)
    val vacancies    = Vector.tabulate(sectorCount)(idx => if idx == targetSector then 10000 else 0)
    val result       = Household.step(
      hhs.toVector,
      stocks,
      mkWorld(),
      PLN(8000),
      PLN(4666),
      Share.decimal(4, 1),
      rng,
      sectorWages = Some(sectorWages),
      sectorVacancies = Some(vacancies),
    )(using searchP)

    result.households.foreach(_.status shouldBe a[HhStatus.Retraining])
    result.aggregates.unemployed shouldBe 0
    result.aggregates.retraining shouldBe hhs.length
    result.aggregates.voluntaryQuits shouldBe hhs.length
  }

  it should "return None for perBankHhFlows when bankRates not provided" in {
    val rng = RandomStream.seeded(42)
    val hhs = Vector(mkHousehold(0, HhStatus.Employed(FirmId(0), SectorIdx(2), PLN(8000)), savings = PLN(50000)))
    val pbf = step(hhs, mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng).perBankFlows
    pbf shouldBe None
  }

  it should "let unemployed households smooth consumption by drawing down savings" in {
    val rng            = RandomStream.seeded(42)
    val hh             = mkHousehold(0, HhStatus.Unemployed(1), savings = PLN(30000), rent = PLN(1800))
    val openingSavings = hh.savings
    val result         = step(Vector(hh), mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng)
    val updated        = result.households
    val agg            = result.aggregates

    agg.consumption should be > PLN.Zero
    updated(0).savings should be < openingSavings
  }

  it should "convert consumer-credit DTI payment headroom into principal demand" in {
    val creditP = SimParamsTestOverrides.consumerCreditEligibility(Share.One)
    val rng     = RandomStream.seeded(42)
    val hh      = mkHousehold(0, HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(8000)), savings = PLN.Zero, rent = PLN(7500))

    val result = Household.step(
      Vector(hh),
      Vector(TestHouseholdState.financial(savings = PLN.Zero, debt = PLN.Zero, consumerDebt = PLN.Zero, equityWealth = PLN.Zero)),
      mkWorld(),
      PLN(8000),
      PLN(4666),
      Share.decimal(4, 1),
      rng,
    )(using creditP)
    val flow   = result.monthlyFlows.head

    flow.consumerCreditDemand should be > PLN(1600)
    flow.consumerApprovedOrigination shouldBe flow.consumerCreditDemand
    flow.consumerApprovedOrigination should be <= creditP.household.ccMaxLoan
    flow.consumerRejectedOrigination shouldBe PLN.Zero
    flow.consumerBankRejectedOrigination shouldBe PLN.Zero
    result.aggregates.totalConsumerCreditDemand shouldBe flow.consumerCreditDemand
  }

  it should "reject underwritten consumer credit when the bank supply gate rejects" in {
    val creditP = SimParamsTestOverrides.consumerCreditEligibility(Share.One)
    val rng     = RandomStream.seeded(42)
    val hh      = mkHousehold(0, HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(8000)), savings = PLN.Zero, rent = PLN(7500))

    val result = Household.step(
      Vector(hh),
      Vector(TestHouseholdState.financial(savings = PLN.Zero, debt = PLN.Zero, consumerDebt = PLN.Zero, equityWealth = PLN.Zero)),
      mkWorld(),
      PLN(8000),
      PLN(4666),
      Share.decimal(4, 1),
      rng,
      bankCreditSupply = Some(
        new Household.BankCreditSupply:
          override def approve(bankId: BankId, product: Banking.CreditProduct, amount: PLN, rng: RandomStream): Banking.CreditApproval =
            Banking.CreditApproval(
              product = product,
              amount = amount,
              approved = false,
              approvalProbability = Some(Share.Zero),
              approvalRoll = Some(Share.One),
              audit = Banking.CreditApprovalAudit(rejectionReason = Some(Banking.CreditRejectionReason.CapitalAdequacy)),
            ),
      ),
    )(using creditP)
    val flow   = result.monthlyFlows.head

    flow.consumerCreditDemand should be > PLN.Zero
    flow.consumerApprovedOrigination shouldBe PLN.Zero
    flow.consumerRejectedOrigination shouldBe flow.consumerCreditDemand
    flow.consumerBankRejectedOrigination shouldBe flow.consumerCreditDemand
    flow.consumerBankApprovalProduct shouldBe Banking.CreditProduct.ConsumerLoan.diagnosticCode
    flow.consumerBankRejectionReason shouldBe Banking.CreditRejectionReason.CapitalAdequacy.diagnosticCode
    flow.consumerBankApprovalProbability shouldBe Some(Share.Zero)
    result.aggregates.totalConsumerBankRejectedOrigination shouldBe flow.consumerBankRejectedOrigination
  }

  it should "expose rejected consumer-credit demand when access eligibility fails" in {
    val creditP = SimParamsTestOverrides.consumerCreditEligibility(Share.Zero)
    val rng     = RandomStream.seeded(42)
    val hh      = mkHousehold(0, HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(8000)), savings = PLN.Zero, rent = PLN(7500))

    val result = Household.step(
      Vector(hh),
      Vector(TestHouseholdState.financial(savings = PLN.Zero, debt = PLN.Zero, consumerDebt = PLN.Zero, equityWealth = PLN.Zero)),
      mkWorld(),
      PLN(8000),
      PLN(4666),
      Share.decimal(4, 1),
      rng,
    )(using creditP)
    val flow   = result.monthlyFlows.head

    flow.consumerCreditDemand should be > PLN.Zero
    flow.consumerApprovedOrigination shouldBe PLN.Zero
    flow.consumerRejectedOrigination shouldBe flow.consumerCreditDemand
    flow.consumerBankRejectedOrigination shouldBe PLN.Zero
    result.aggregates.totalConsumerRejectedOrigination shouldBe flow.consumerRejectedOrigination
  }

  it should "block new underwritten consumer credit while household is in arrears" in {
    val creditP = SimParamsTestOverrides.consumerCreditEligibility(Share.One)
    val rng     = RandomStream.seeded(42)
    val hh      = mkHousehold(0, HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(8000)), savings = PLN.Zero, rent = PLN(7500))
      .copy(financialDistressState = HhFinancialDistressState.Arrears)

    val result = Household.step(
      Vector(hh),
      Vector(TestHouseholdState.financial(savings = PLN.Zero, debt = PLN.Zero, consumerDebt = PLN.Zero, equityWealth = PLN.Zero)),
      mkWorld(),
      PLN(8000),
      PLN(4666),
      Share.decimal(4, 1),
      rng,
    )(using creditP)
    val flow   = result.monthlyFlows.head

    flow.consumerCreditDemand should be > PLN.Zero
    flow.consumerApprovedOrigination shouldBe PLN.Zero
    flow.consumerRejectedOrigination shouldBe flow.consumerCreditDemand
    flow.consumerBankRejectedOrigination shouldBe PLN.Zero
  }

  it should "use remaining underwritten consumer-credit capacity before liquidity shortfall financing" in {
    val creditP = SimParamsTestOverrides.consumerCreditEligibility(Share.One)
    val rng     = RandomStream.seeded(42)
    val hh      = mkHousehold(0, HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(8000)), savings = PLN.Zero, rent = PLN(9000))

    val result = Household.step(
      Vector(hh),
      Vector(TestHouseholdState.financial(savings = PLN.Zero, debt = PLN.Zero, consumerDebt = PLN.Zero, equityWealth = PLN.Zero)),
      mkWorld(),
      PLN(8000),
      PLN(4666),
      Share.decimal(4, 1),
      rng,
    )(using creditP)
    val flow   = result.monthlyFlows.head

    flow.consumerApprovedOrigination should be > PLN(2400)
    flow.consumerApprovedOrigination shouldBe flow.consumerCreditDemand
    flow.consumerRejectedOrigination shouldBe PLN.Zero
    flow.liquidityShortfallFinancing shouldBe PLN.Zero
    result.financialStocks.head.demandDeposit shouldBe PLN.Zero
  }

  it should "compress consumption while household is in financial arrears" in {
    val current = mkHousehold(0, HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(8000)), savings = PLN.Zero, rent = PLN(1000))
    val arrears = current.copy(financialDistressState = HhFinancialDistressState.Arrears)

    val currentResult = Household.step(
      Vector(current),
      Vector(TestHouseholdState.financial(savings = PLN.Zero, debt = PLN.Zero, consumerDebt = PLN.Zero, equityWealth = PLN.Zero)),
      mkWorld(),
      PLN(8000),
      PLN(4666),
      Share.decimal(4, 1),
      RandomStream.seeded(42),
    )
    val arrearsResult = Household.step(
      Vector(arrears),
      Vector(TestHouseholdState.financial(savings = PLN.Zero, debt = PLN.Zero, consumerDebt = PLN.Zero, equityWealth = PLN.Zero)),
      mkWorld(),
      PLN(8000),
      PLN(4666),
      Share.decimal(4, 1),
      RandomStream.seeded(42),
    )

    arrearsResult.monthlyFlows.head.consumption should be < currentResult.monthlyFlows.head.consumption
    arrearsResult.households.head.financialDistressState shouldBe HhFinancialDistressState.Restructuring
  }

  it should "compress discretionary consumption before creating liquidity shortfall financing" in {
    val rng   = RandomStream.seeded(42)
    val hh    = mkHousehold(0, HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(8000)), savings = PLN.Zero, rent = PLN(1000), mpc = BigDecimal("0.80"))
    val base  = mkWorld()
    val world = base.copy(real = base.real.copy(housing = base.real.housing.copy(lastWealthEffect = PLN(1000000000L))))

    val result = step(Vector(hh), world, PLN(8000), PLN(4666), Share.decimal(4, 1), rng)
    val flow   = result.monthlyFlows.head

    flow.discretionaryConsumptionCompression should be > PLN.Zero
    flow.unmetBasicConsumption shouldBe PLN.Zero
    flow.liquidityShortfallFinancing shouldBe PLN.Zero
    result.aggregates.totalDiscretionaryConsumptionCompression shouldBe flow.discretionaryConsumptionCompression
    result.aggregates.totalUnmetBasicConsumption shouldBe PLN.Zero
  }

  it should "return ledger-facing financial balances in the step result" in {
    val rng     = RandomStream.seeded(42)
    val hh      = mkHousehold(0, HhStatus.Employed(FirmId(0), SectorIdx(2), PLN(8000)), savings = PLN(50000))
    val result  = step(Vector(hh), mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng)
    val updated = result.households.head

    result.financialStocks.head shouldBe Household.FinancialStocks(
      demandDeposit = updated.savings,
      mortgageLoan = updated.debt,
      consumerLoan = updated.consumerDebt,
      equity = updated.equityWealth,
    )
  }

  it should "charge off closing liquidity shortfalls instead of rolling them into consumer-loan stock" in {
    val rng    = RandomStream.seeded(42)
    val hh     = mkHousehold(0, HhStatus.Unemployed(10), savings = PLN.Zero, rent = PLN(10000), mpc = BigDecimal("0.50"))
    val result = step(Vector(hh), mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng)
    val stocks = result.financialStocks.head

    stocks.demandDeposit shouldBe PLN.Zero
    stocks.consumerLoan shouldBe PLN.Zero
    result.aggregates.totalConsumerOrigination shouldBe PLN.Zero
    result.aggregates.totalConsumerApprovedOrigination shouldBe PLN.Zero
    result.aggregates.totalConsumerDefault shouldBe PLN.Zero
    result.aggregates.totalConsumerLoanDefault shouldBe PLN.Zero
    result.aggregates.totalLiquidityBridgeChargeOff shouldBe result.monthlyFlows.head.liquidityBridgeChargeOff
    result.aggregates.totalLiquidityShortfallComponents shouldBe result.aggregates.totalLiquidityShortfallFinancing
    result.monthlyFlows.head.rentArrears + result.monthlyFlows.head.temporaryOverdraft should be > PLN.Zero
    result.aggregates.meanSavings shouldBe PLN.Zero
  }

  it should "route mortgage arrears through shortfall components without consumer-credit origination or default" in {
    val rng = RandomStream.seeded(42)
    val hh  = mkHousehold(
      0,
      HhStatus.Unemployed(10),
      savings = PLN.Zero,
      debt = PLN(120000),
      rent = PLN.Zero,
      mpc = BigDecimal("0.50"),
      mortgageRemainingMonths = 1,
    )

    val result = step(Vector(hh), mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng)
    val flow   = result.monthlyFlows.head

    flow.mortgageArrears should be > PLN.Zero
    result.aggregates.totalMortgageArrears shouldBe flow.mortgageArrears
    result.aggregates.totalConsumerOrigination shouldBe PLN.Zero
    result.aggregates.totalConsumerDefault shouldBe PLN.Zero
    result.aggregates.totalConsumerLoanDefault shouldBe PLN.Zero
    result.aggregates.totalLiquidityBridgeChargeOff shouldBe flow.liquidityBridgeChargeOff
  }

  it should "route residual consumer-debt-service financing through bridge charge-off" in {
    val rng         = RandomStream.seeded(42)
    val openingLoan = PLN(12000)
    val hh          = mkHousehold(0, HhStatus.Unemployed(10), savings = PLN.Zero, rent = PLN.Zero, mpc = BigDecimal("0.50"))
    financialById.update(
      hh.id.toInt,
      TestHouseholdState.financial(savings = PLN.Zero, debt = PLN.Zero, consumerDebt = openingLoan, equityWealth = PLN.Zero),
    )

    val result = step(Vector(hh), mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng)
    val flow   = result.monthlyFlows.head

    flow.consumerDebtArrears shouldBe flow.consumerDebtService
    flow.consumerDefault shouldBe PLN.Zero
    flow.consumerLoanDefault shouldBe PLN.Zero
    flow.liquidityBridgeChargeOff shouldBe flow.consumerDebtArrears
    flow.consumerDebtService should be > flow.consumerPrincipal
    flow.closingConsumerLoan shouldBe (openingLoan - flow.consumerPrincipal).max(PLN.Zero)
    result.financialStocks.head.consumerLoan shouldBe flow.closingConsumerLoan
    result.aggregates.totalConsumerApprovedOrigination shouldBe PLN.Zero
  }

  it should "reconcile shortfall components when wealth effects make consumption negative" in {
    val rng   = RandomStream.seeded(42)
    val hh    = mkHousehold(0, HhStatus.Unemployed(10), savings = PLN.Zero, rent = PLN(1000000), mpc = BigDecimal("0.50"))
    val base  = mkWorld()
    val world =
      base.copy(real = base.real.copy(housing = base.real.housing.copy(lastWealthEffect = PLN(-20000000000L))))

    val result = step(Vector(hh), world, PLN(8000), PLN(4666), Share.decimal(4, 1), rng)
    val flow   = result.monthlyFlows.head

    flow.consumption should be < PLN.Zero
    result.aggregates.totalLiquidityShortfallComponents shouldBe result.aggregates.totalLiquidityShortfallFinancing
    result.aggregates.totalRentArrears + result.aggregates.totalTemporaryOverdraft should be > PLN.Zero
  }

  // --- Variable-rate debt service + deposit interest ---

  "Household.step with bankRates" should "use variable lending rate for debt service" in {
    val rng          = RandomStream.seeded(42)
    val debt         = PLN(100000)
    val hhs          = Vector(
      mkHousehold(
        0,
        HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(8000)),
        savings = PLN(50000),
        debt = debt,
        bankId = 0,
      ),
      mkHousehold(
        1,
        HhStatus.Employed(FirmId(1), SectorIdx(0), PLN(8000)),
        savings = PLN(50000),
        debt = debt,
        bankId = 1,
      ),
    )
    // Bank 0: 6% annual lending rate, Bank 1: 10% annual
    val br           = BankRates(
      lendingRates = Vector(Rate.decimal(6, 2), Rate.decimal(10, 2)),
      depositRates = Vector(Rate.decimal(4, 2), Rate.decimal(4, 2)),
    )
    val result       = step(hhs, mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng, nBanks = 2, bankRates = Some(br))
    val pbf          = result.perBankFlows.get
    val principal    = decimal(debt) / BigDecimal(p.housing.mortgageMaturity)
    val expectedInt0 = decimal(debt) * BigDecimal("0.06") / BigDecimal("12.0")
    val expectedInt1 = decimal(debt) * BigDecimal("0.10") / BigDecimal("12.0")
    val expectedDs0  = principal + expectedInt0
    val expectedDs1  = principal + expectedInt1
    decimal(pbf(0).debtService) shouldBe (decimal(plnBD(expectedDs0)) +- decimal(PLN(10)))
    decimal(pbf(1).debtService) shouldBe (decimal(plnBD(expectedDs1)) +- decimal(PLN(10)))
    decimal(pbf(0).mortgageInterest) shouldBe (decimal(plnBD(expectedInt0)) +- decimal(PLN(10)))
    decimal(pbf(1).mortgageInterest) shouldBe (decimal(plnBD(expectedInt1)) +- decimal(PLN(10)))
    decimal(result.aggregates.totalMortgagePrincipal) shouldBe (decimal(plnBD(principal * 2)) +- decimal(PLN(10)))
    decimal(result.aggregates.totalMortgageInterest) shouldBe (decimal(plnBD(expectedDs0 + expectedDs1 - principal * 2)) +- decimal(PLN(10)))
    // Bank 1's higher rate should mean higher debt service
    pbf(1).debtService should be > pbf(0).debtService
  }

  it should "reduce mortgage stock by scheduled principal only" in {
    val rng  = RandomStream.seeded(42)
    val debt = PLN(120000)
    val hh   = mkHousehold(20, HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(9000)), savings = PLN(100000), debt = debt, bankId = 0)
    val br   = BankRates(
      lendingRates = Vector(Rate.decimal(6, 2)),
      depositRates = Vector(Rate.decimal(4, 2)),
    )

    val result    = step(Vector(hh), mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng, bankRates = Some(br))
    val principal = debt / p.housing.mortgageMaturity
    val interest  = debt * Rate.decimal(6, 2).monthly

    result.financialStocks.head.mortgageLoan shouldBe debt - principal
    result.aggregates.totalMortgagePrincipal shouldBe principal
    result.aggregates.totalMortgageInterest shouldBe interest
    result.aggregates.totalDebtService shouldBe principal + interest
  }

  it should "close a mortgage to zero at its remaining contractual maturity" in {
    val debt = PLN(3000)
    val hh   = mkHousehold(
      22,
      HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(9000)),
      savings = PLN(100000),
      debt = debt,
      rent = PLN.Zero,
      bankId = 0,
      mortgageRemainingMonths = 3,
    )
    val br   = BankRates(
      lendingRates = Vector(Rate.Zero),
      depositRates = Vector(Rate.Zero),
    )

    val month1 = step(Vector(hh), mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), RandomStream.seeded(101), bankRates = Some(br))
    month1.financialStocks.head.mortgageLoan shouldBe PLN(2000)
    month1.financialStocks.head.mortgageRemainingMonths shouldBe 2
    month1.aggregates.totalMortgagePrincipal shouldBe PLN(1000)

    val month2 = step(month1.households, mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), RandomStream.seeded(102), bankRates = Some(br))
    month2.financialStocks.head.mortgageLoan shouldBe PLN(1000)
    month2.financialStocks.head.mortgageRemainingMonths shouldBe 1
    month2.aggregates.totalMortgagePrincipal shouldBe PLN(1000)

    val month3 = step(month2.households, mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), RandomStream.seeded(103), bankRates = Some(br))
    month3.financialStocks.head.mortgageLoan shouldBe PLN.Zero
    month3.financialStocks.head.mortgageRemainingMonths shouldBe 0
    month3.aggregates.totalMortgagePrincipal shouldBe PLN(1000)
  }

  it should "use the live policy rate for mortgage interest when bankRates are absent" in {
    val rng          = RandomStream.seeded(42)
    val debt         = PLN(120000)
    val policyRate   = Rate.decimal(12, 2)
    val world        = mkWorld().copy(nbp = Nbp.State(policyRate, false, PLN.Zero, PLN.Zero))
    val hh           = mkHousehold(21, HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(9000)), savings = PLN(100000), debt = debt, bankId = 0)
    val result       = step(Vector(hh), world, PLN(8000), PLN(4666), Share.decimal(4, 1), rng)
    val mortgageRate = policyRate + p.housing.mortgageSpread
    val expectedInt  = debt * mortgageRate.monthly
    val expectedPrin = debt / p.housing.mortgageMaturity

    result.aggregates.totalMortgageInterest shouldBe expectedInt
    result.aggregates.totalDebtService shouldBe expectedPrin + expectedInt
  }

  it should "pay deposit interest to HH with positive savings" in {
    val rng            = RandomStream.seeded(42)
    val savings        = PLN(100000)
    val hhs            = Vector(
      mkHousehold(0, HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(8000)), savings = savings, bankId = 0),
    )
    val depRate        = BigDecimal("0.04") // 4% annual
    val br             = BankRates(
      lendingRates = Vector(Rate.decimal(7, 2)),
      depositRates = Vector(rateBD(depRate)),
    )
    val result         =
      step(hhs, mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng, nBanks = 1, bankRates = Some(br))
    val agg            = result.aggregates
    val maybePbf       = result.perBankFlows
    val pbf            = maybePbf.get
    val expectedDepInt = depRate / BigDecimal("12.0") * decimal(savings)
    decimal(pbf(0).depositInterest) shouldBe (decimal(plnBD(expectedDepInt)) +- decimal(PLN(10)))
    decimal(agg.totalDepositInterest) shouldBe (decimal(plnBD(expectedDepInt)) +- decimal(PLN(10)))
  }

  it should "include deposit interest in totalIncome" in {
    val rng            = RandomStream.seeded(42)
    val savings        = PLN(200000)
    val wage           = BigDecimal("8000.0")
    val depRate        = BigDecimal("0.04")
    val hhs            = Vector(
      mkHousehold(0, HhStatus.Employed(FirmId(0), SectorIdx(0), plnBD(wage)), savings = savings, bankId = 0),
    )
    val br             = BankRates(
      lendingRates = Vector(Rate.decimal(7, 2)),
      depositRates = Vector(rateBD(depRate)),
    )
    val agg            = step(hhs, mkWorld(), plnBD(wage), PLN(4666), Share.decimal(4, 1), rng, nBanks = 1, bankRates = Some(br)).aggregates
    val expectedDepInt = depRate / BigDecimal("12.0") * decimal(savings)
    val grossIncome    = wage + expectedDepInt
    val pitTax         = Household.computeMonthlyPit(plnBD(grossIncome))
    // totalIncome = grossIncome - PIT + socialTransfer (0 children → no transfer)
    decimal(agg.totalIncome) shouldBe (decimal(plnBD(grossIncome - decimal(pitTax))) +- decimal(PLN(20)))
  }

  it should "preserve monthly flow totals when recomputing snapshot aggregates" in {
    val rng       = RandomStream.seeded(42)
    val household = mkHousehold(9900, HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(9000)), savings = PLN(100000), bankId = 0)
    val flowAgg   = step(Vector(household), mkWorld(), PLN(9000), PLN(4666), Share.decimal(4, 1), rng).aggregates
    val snapshot  = computeAggregates(
      Vector(household.copy(status = HhStatus.Unemployed(0))),
      PLN(9000),
      PLN(4666),
      Share.decimal(4, 1),
      retrainingAttempts = 0,
      retrainingSuccesses = 0,
    ).withFlowTotalsFrom(flowAgg)

    flowAgg.totalPit should be > PLN.Zero
    snapshot.employed shouldBe 0
    snapshot.unemployed shouldBe 1
    snapshot.totalIncome shouldBe flowAgg.totalIncome
    snapshot.totalPit shouldBe flowAgg.totalPit
    snapshot.consumption shouldBe flowAgg.consumption
  }

  it should "accumulate per-bank flows correctly for 2 banks" in {
    val rng        = RandomStream.seeded(42)
    val hhs        = Vector(
      mkHousehold(
        0,
        HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(8000)),
        savings = PLN(50000),
        debt = PLN(0),
        bankId = 0,
      ),
      mkHousehold(
        1,
        HhStatus.Employed(FirmId(1), SectorIdx(0), PLN(7000)),
        savings = PLN(30000),
        debt = PLN(0),
        bankId = 0,
      ),
      mkHousehold(
        2,
        HhStatus.Employed(FirmId(2), SectorIdx(0), PLN(9000)),
        savings = PLN(80000),
        debt = PLN(0),
        bankId = 1,
      ),
    )
    val br         = BankRates(
      lendingRates = Vector(Rate.decimal(7, 2), Rate.decimal(8, 2)),
      depositRates = Vector(Rate.decimal(35, 3), Rate.decimal(35, 3)),
    )
    val maybePbf   =
      step(hhs, mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng, nBanks = 2, bankRates = Some(br)).perBankFlows
    val pbf        = maybePbf.get
    // Bank 0 has HH 0 and 1: income should include both
    pbf(0).income should be > PLN.Zero
    pbf(1).income should be > PLN.Zero
    // Bank 0 deposit interest: (50000 + 30000) * 0.035/12
    val expDepInt0 = (BigDecimal("50000.0") + BigDecimal("30000.0")) * BigDecimal("0.035") / BigDecimal("12.0")
    decimal(pbf(0).depositInterest) shouldBe (decimal(plnBD(expDepInt0)) +- decimal(PLN(10)))
    // Bank 1 deposit interest: 80000 * 0.035/12
    val expDepInt1 = BigDecimal("80000.0") * BigDecimal("0.035") / BigDecimal("12.0")
    decimal(pbf(1).depositInterest) shouldBe (decimal(plnBD(expDepInt1)) +- decimal(PLN(10)))
  }

  it should "reject negative opening demand deposits before deposit interest accrues" in {
    val rng = RandomStream.seeded(42)
    val hhs = Vector(
      mkHousehold(0, HhStatus.Employed(FirmId(0), SectorIdx(0), PLN(8000)), savings = PLN(-5000), bankId = 0),
    )
    val br  = BankRates(
      lendingRates = Vector(Rate.decimal(7, 2)),
      depositRates = Vector(Rate.decimal(4, 2)),
    )

    an[IllegalArgumentException] should be thrownBy
      step(hhs, mkWorld(), PLN(8000), PLN(4666), Share.decimal(4, 1), rng, nBanks = 1, bankRates = Some(br))
  }

  // --- Immigration: remittance deduction ---

  "Household.step" should "not deduct remittances from non-immigrant HH" in {
    val rng  = RandomStream.seeded(42)
    val wage = BigDecimal("8000.0")
    val hhs  = Vector(
      mkHousehold(0, HhStatus.Employed(FirmId(0), SectorIdx(2), plnBD(wage)), savings = PLN(50000))
        .copy(isImmigrant = false),
    )
    val agg  = step(hhs, mkWorld(), plnBD(wage), PLN(4666), Share.decimal(4, 1), rng).aggregates
    agg.totalRemittances shouldBe PLN.Zero
  }

  // --- Household.giniSorted ---

  "Household.giniSorted" should "return 0 for equal values" in {
    shareValue(Household.giniSorted(Array(100L, 100L, 100L, 100L))) shouldBe (BigDecimal("0.0") +- BigDecimal("0.001"))
  }

  it should "return 0 for single element" in {
    Household.giniSorted(Array(42L)) shouldBe Share.Zero
  }

  it should "return value in [0, 1] for typical distribution" in {
    val values = Array(1000L, 2000L, 3000L, 5000L, 10000L, 50000L)
    val g      = Household.giniSorted(values)
    g should be >= Share.Zero
    g should be <= Share.One
  }

  it should "increase with more inequality" in {
    val equal   = Array(1000L, 1000L, 1000L, 1000L)
    val unequal = Array(0L, 0L, 0L, 4000L)
    Household.giniSorted(unequal) should be > Household.giniSorted(equal)
  }

  // --- Household.computeAggregates ---

  "Household.computeAggregates" should "count statuses correctly" in {
    val hhs = Vector(
      mkHousehold(0, HhStatus.Employed(FirmId(0), SectorIdx(2), PLN(8000))),
      mkHousehold(1, HhStatus.Employed(FirmId(1), SectorIdx(2), PLN(7000))),
      mkHousehold(2, HhStatus.Unemployed(3)),
      mkHousehold(3, HhStatus.Retraining(4, SectorIdx(1), PLN(5000))),
      mkHousehold(4, HhStatus.Bankrupt),
    )
    val agg = computeAggregates(hhs, PLN(8000), PLN(4666), Share.decimal(4, 1), 0, 0)
    agg.employed shouldBe 2
    agg.unemployed shouldBe 1
    agg.retraining shouldBe 1
    agg.bankrupt shouldBe 1
    decimal(agg.bankruptcyRate) shouldBe (decimal(Share.decimal(2, 1)) +- decimal(Share.decimal(1, 3)))
  }

  // --- helpers ---

  private def mkFirms(n: Int): Vector[Firm.State] =
    (0 until n).map { i =>
      TestFirmState(
        FirmId(i),
        PLN(50000),
        PLN(0),
        TechState.Traditional(10),
        Share.decimal(5, 1),
        Multiplier.One,
        Share.decimal(5, 1),
        SectorIdx(i % p.sectorDefs.length),
        Vector.empty[FirmId],
        bankId = BankId(0),
        equityRaised = PLN.Zero,
        initialSize = 10,
        capitalStock = PLN.Zero,
        foreignOwned = false,
        inventory = PLN.Zero,
        greenCapital = PLN.Zero,
        accumulatedLoss = PLN.Zero,
      )
    }.toVector

  private def mkHousehold(
      id: Int,
      status: HhStatus,
      savings: PLN = PLN(20000),
      debt: PLN = PLN(0),
      rent: PLN = PLN(1800),
      skill: BigDecimal = BigDecimal("0.7"),
      healthPenalty: BigDecimal = BigDecimal("0.0"),
      mpc: BigDecimal = BigDecimal("0.82"),
      bankId: Int = 0,
      mortgageRemainingMonths: Int = 0,
  ): Household.State =
    val hh = TestHouseholdState(
      HhId(id),
      savings,
      debt,
      rent,
      shareBD(skill),
      shareBD(healthPenalty),
      shareBD(mpc),
      status,
      Array.empty[HhId],
      BankId(bankId),
      equityWealth = PLN.Zero,
      lastSectorIdx = SectorIdx(-1),
      isImmigrant = false,
      numDependentChildren = 0,
      consumerDebt = PLN.Zero,
      education = 2,
      taskRoutineness = Share.decimal(5, 1),
      wageScar = Share.Zero,
    )
    financialById.update(
      id,
      TestHouseholdState.financial(
        savings = savings,
        debt = debt,
        consumerDebt = PLN.Zero,
        equityWealth = PLN.Zero,
        mortgageRemainingMonths = mortgageRemainingMonths,
      ),
    )
    hh

  private def mkWorld(): World =
    Generators.testWorld(
      totalPopulation = 100000,
      employed = 100000,
      forex = OpenEconomy.ForexState(ExchangeRate.decimal(433, 2), PLN(0), PLN(190000000), PLN(0), PLN(0)),
      marketWage = p.household.baseWage,
      reservationWage = p.household.baseReservationWage,
    )

  private def mkLiquidityShockWorld(): World =
    val world = mkWorld()
    world.copy(real = world.real.copy(housing = world.real.housing.copy(lastWealthEffect = PLN(20000000000L))))
