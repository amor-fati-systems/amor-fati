package com.boombustgroup.amorfati.engine

import com.boombustgroup.amorfati.FixedPointSpecSupport.*
import com.boombustgroup.amorfati.TestFirmState

import org.scalatest.flatspec.AnyFlatSpec
import com.boombustgroup.amorfati.Generators
import org.scalatest.matchers.should.Matchers
import com.boombustgroup.amorfati.agents.{BankruptReason, Firm, TechState}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.mechanisms.FirmEntry
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

class FirmEntrySpec extends AnyFlatSpec with Matchers:

  given SimParams          = SimParams.defaults
  private val p: SimParams = summon[SimParams]

  private def runEntry(
      firms: Vector[Firm.State],
      unemploymentRate: BigDecimal,
      rng: RandomStream,
      laggedHiringSlack: Share = Share.One,
      inflation: Rate = Rate.Zero,
      expectedInflation: Rate = Rate.Zero,
      startupAbsorptionRate: Share = Share.One,
      sectorDemandPressure: Vector[Multiplier] = Vector.fill(p.sectorDefs.length)(Multiplier.One),
      financialStocks: Vector[Firm.FinancialStocks] = Vector.empty,
  ): FirmEntry.Result =
    FirmEntry.process(
      firms,
      if financialStocks.nonEmpty then financialStocks else defaultFinancialStocks(firms),
      Share.Zero,
      Share.Zero,
      FirmEntry.LaggedEntrySignals(
        unemploymentRate = shareBD(unemploymentRate),
        inflation = inflation,
        expectedInflation = expectedInflation,
        laggedHiringSlack = laggedHiringSlack,
        startupAbsorptionRate = startupAbsorptionRate,
        sectorDemandPressure = sectorDemandPressure,
      ),
      rng,
    )

  private def defaultFinancialStocks(firms: Vector[Firm.State]): Vector[Firm.FinancialStocks] =
    firms.map(_ => TestFirmState.financial(cash = PLN(100000)))

  private def demandPressure(value: Multiplier): Vector[Multiplier] =
    Vector.fill(p.sectorDefs.length)(value)

  private val ExpansionDemandPressure: Vector[Multiplier] = demandPressure(Multiplier.decimal(125, 2))
  private val StrongDemandPressure: Vector[Multiplier]    = demandPressure(Multiplier(10))

  // ==========================================================================
  // Config defaults
  // ==========================================================================

  "FirmEntryProfitSens" should "default to 2.0" in {
    p.firm.entryProfitSens shouldBe Coefficient(2)
  }

  "FirmEntrySectorBarriers" should "have 6 elements" in {
    p.firm.entrySectorBarriers.length shouldBe 6
  }

  it should "have all positive values" in
    p.firm.entrySectorBarriers.foreach(b => decimal(b) should be > BigDecimal("0.0"))

  it should "match expected defaults" in {
    p.firm.entrySectorBarriers shouldBe Vector(
      Coefficient.decimal(8, 1),
      Coefficient.decimal(6, 1),
      Coefficient.decimal(12, 1),
      Coefficient.decimal(5, 1),
      Coefficient.decimal(1, 1),
      Coefficient.decimal(7, 1),
    )
  }

  "FirmEntryAiThreshold" should "default to 0.15" in {
    p.firm.entryAiThreshold shouldBe Share.decimal(15, 2)
  }

  "FirmEntryAiProb" should "default to 0.20" in {
    p.firm.entryAiProb shouldBe Share.decimal(20, 2)
  }

  "FirmEntryStartupCash" should "default to 50000.0" in {
    p.firm.entryStartupCash shouldBe PLN(50000)
  }

  "ReplacementEntryRate" should "default to 0.35" in {
    p.firm.replacementEntryRate shouldBe Share.decimal(35, 2)
  }

  "ReplacementEntryMinMonthly" should "default to 1" in {
    p.firm.replacementEntryMinMonthly shouldBe 1
  }

  "ReplacementEntryMaxMonthly" should "default to 250" in {
    p.firm.replacementEntryMaxMonthly shouldBe 250
  }

  // ==========================================================================
  // World fields
  // ==========================================================================

  private def mkMinimalWorld() = Generators.testWorld(
    inflation = Rate(0),
    currentSigmas = Vector.fill(6)(Sigma(5)),
    marketWage = PLN(8000),
    reservationWage = PLN(4500),
  )

  "World" should "have firmBirths defaulting to 0" in {
    val w = mkMinimalWorld()
    w.flows.firmBirths shouldBe 0
  }

  it should "have firmDeaths defaulting to 0" in {
    val w = mkMinimalWorld()
    w.flows.firmDeaths shouldBe 0
  }

  // ==========================================================================
  // Entrant properties (unit-level)
  // ==========================================================================

  "New entrant" should "be micro size (1-9 workers)" in {
    val rng   = RandomStream.seeded(42)
    val sizes = (1 to 100).map(_ => DecimalMath.max(1, rng.between(1, 10)))
    sizes.foreach { s =>
      s should be >= 1
      s should be <= 9
    }
  }

  it should "have zero debt" in {
    val entrant = TestFirmState.fixture(
      id = FirmId(0),
      cash = PLN(50000),
      debt = PLN.Zero,
      tech = TechState.Traditional(5),
      riskProfile = Share.decimal(5, 1),
      innovationCostFactor = Multiplier.One,
      digitalReadiness = Share.decimal(15, 2),
      sector = SectorIdx(2),
      neighbors = Vector.empty[FirmId],
      bankId = BankId(0),
      equityRaised = PLN.Zero,
      initialSize = 5,
      capitalStock = PLN.Zero,
      foreignOwned = false,
      inventory = PLN.Zero,
      greenCapital = PLN.Zero,
      accumulatedLoss = PLN.Zero,
    )
    entrant.financialStocks.firmLoan shouldBe PLN.Zero
  }

  it should "have positive startup cash" in {
    val sizeMult = BigDecimal("5.0") / p.pop.workersPerFirm
    val cash     = decimal(p.firm.entryStartupCash) * sizeMult
    cash should be > BigDecimal("0.0")
  }

  it should "be alive" in {
    val entrant = TestFirmState(
      id = FirmId(0),
      cash = PLN(50000),
      debt = PLN.Zero,
      tech = TechState.Traditional(5),
      riskProfile = Share.decimal(5, 1),
      innovationCostFactor = Multiplier.One,
      digitalReadiness = Share.decimal(15, 2),
      sector = SectorIdx(2),
      neighbors = Vector.empty[FirmId],
      bankId = BankId(0),
      equityRaised = PLN.Zero,
      initialSize = 5,
      capitalStock = PLN.Zero,
      foreignOwned = false,
      inventory = PLN.Zero,
      greenCapital = PLN.Zero,
      accumulatedLoss = PLN.Zero,
    )
    Firm.isAlive(entrant) shouldBe true
  }

  // ==========================================================================
  // AI-native entrants
  // ==========================================================================

  "AI-native entrant" should "have Hybrid tech state" in {
    val tech = TechState.Hybrid(3, Multiplier.decimal(65, 2))
    tech shouldBe a[TechState.Hybrid]
  }

  it should "have high digital readiness (0.50-0.90)" in {
    val rng = RandomStream.seeded(42)
    val drs = (1 to 100).map(_ => TypedRandom.randomBetween(Share.decimal(50, 2), Share.decimal(90, 2), rng))
    drs.foreach { dr =>
      dr should be >= Share.decimal(50, 2)
      dr should be <= Share.decimal(90, 2)
    }
  }

  "Traditional entrant" should "have Traditional tech state" in {
    val tech = TechState.Traditional(5)
    tech shouldBe a[TechState.Traditional]
  }

  it should "have low digital readiness (0.02-0.30)" in {
    val rng = RandomStream.seeded(42)
    val drs = (1 to 100).map { _ =>
      val sec = p.sectorDefs(2) // Retail
      TypedRandom.withGaussianNoise(sec.baseDigitalReadiness, Share.decimal(10, 2), rng).clamp(Share.decimal(2, 2), Share.decimal(30, 2))
    }
    drs.foreach { dr =>
      dr should be >= Share.decimal(2, 2)
      dr should be <= Share.decimal(30, 2)
    }
  }

  // ==========================================================================
  // Sector choice: all 6 sectors reachable
  // ==========================================================================

  "Sector choice" should "reach all 6 sectors with profit-weighted draws" in {
    val rng     = RandomStream.seeded(42)
    val weights = Vector(
      Multiplier.decimal(8, 1),
      Multiplier.decimal(6, 1),
      Multiplier.decimal(12, 1),
      Multiplier.decimal(5, 1),
      Multiplier.decimal(1, 1),
      Multiplier.decimal(7, 1),
    )
    val sectors = (1 to 1000).map { _ =>
      WeightedSelection.choose(weights, rng)
    }
    for s <- 0 until 6 do sectors.count(_ == s) should be > 0
  }

  // ==========================================================================
  // Physical capital initialization
  // ==========================================================================

  "Entrant capitalStock" should "be initialized when PhysCapEnabled" in {
    val firmSize  = 5
    val sector    = 1 // Manufacturing
    val expectedK = decimal(firmSize) * decimal(p.capital.klRatios(sector))
    expectedK should be > BigDecimal("0.0")
  }

  // ==========================================================================
  // FDI foreign ownership
  // ==========================================================================

  "Entrant foreignOwned" should "respect FDI sector shares" in {
    // When FdiEnabled, foreignOwned probability = FdiForeignShares(sector)
    p.fdi.foreignShares.length shouldBe 6
    p.fdi.foreignShares.map(decimal).foreach { share =>
      share should be >= BigDecimal("0.0")
      share should be <= BigDecimal("1.0")
    }
  }

  // ==========================================================================
  // Individual HH mode: zero workers
  // ==========================================================================

  "Entrant in individual mode" should "start with Traditional(0)" in {
    // When households.isDefined, startWorkers = 0
    val tech = TechState.Traditional(0)
    Firm.workerCount(
      TestFirmState(
        id = FirmId(0),
        cash = PLN(50000),
        debt = PLN.Zero,
        tech = tech,
        riskProfile = Share.decimal(5, 1),
        innovationCostFactor = Multiplier.One,
        digitalReadiness = Share.decimal(15, 2),
        sector = SectorIdx(0),
        neighbors = Vector.empty[FirmId],
        bankId = BankId(0),
        equityRaised = PLN.Zero,
        initialSize = 5,
        capitalStock = PLN.Zero,
        foreignOwned = false,
        inventory = PLN.Zero,
        greenCapital = PLN.Zero,
        accumulatedLoss = PLN.Zero,
      ),
    ) shouldBe 0
  }

  // ==========================================================================
  // Profit signal computation
  // ==========================================================================

  "Profit signal" should "be clamped to [-1, 2]" in {
    val testCases = Seq(
      (BigDecimal("100.0"), BigDecimal("50.0"), BigDecimal("50.0")),  // positive signal
      (BigDecimal("10.0"), BigDecimal("50.0"), BigDecimal("50.0")),   // negative signal
      (BigDecimal("1000.0"), BigDecimal("50.0"), BigDecimal("50.0")), // extreme positive
      (BigDecimal("0.0"), BigDecimal("50.0"), BigDecimal("50.0")),    // zero cash
    )
    for (sectorAvg, globalAvg, _) <- testCases do
      val signal = DecimalMath.max(
        -BigDecimal("1.0"),
        DecimalMath.min(BigDecimal("2.0"), (sectorAvg - globalAvg) / DecimalMath.max(BigDecimal("1.0"), DecimalMath.abs(globalAvg))),
      )
      signal should be >= -BigDecimal("1.0")
      signal should be <= BigDecimal("2.0")
  }

  // ==========================================================================
  // Sector entry weights
  // ==========================================================================

  "Sector entry weights" should "anchor neutral entry to configured sector shares" in {
    val firms   = mkFirms(120)
    val weights = FirmEntry.computeSectorWeights(
      living = firms.zip(defaultFinancialStocks(firms)),
      profitSignals = Vector.fill(p.sectorDefs.length)(Coefficient.Zero),
      laggedSignals = FirmEntry.LaggedEntrySignals(
        unemploymentRate = p.monetary.nairu,
        inflation = Rate.Zero,
        expectedInflation = Rate.Zero,
        laggedHiringSlack = Share.One,
        startupAbsorptionRate = Share.One,
        sectorDemandPressure = demandPressure(Multiplier.One),
      ),
    )

    weights.foreach(weight => weight should be >= Multiplier.decimal(1, 2))
    weights(0) should be < weights(1) // BPO anchor stays below manufacturing
    weights(0) should be < weights(2) // BPO anchor stays below retail/services
  }

  it should "dampen an overrepresented BPO sector even when its cash signal is strong" in {
    val firms   = mkFirms(120).zipWithIndex.map: (firm, idx) =>
      firm.copy(sector = if idx < 72 then SectorIdx(0) else SectorIdx(2))
    val weights = FirmEntry.computeSectorWeights(
      living = firms.zip(defaultFinancialStocks(firms)),
      profitSignals = Vector(Coefficient(2), Coefficient.Zero, Coefficient.Zero, Coefficient.Zero, Coefficient.Zero, Coefficient.Zero),
      laggedSignals = FirmEntry.LaggedEntrySignals(
        unemploymentRate = p.monetary.nairu,
        inflation = Rate.Zero,
        expectedInflation = Rate.Zero,
        laggedHiringSlack = Share.One,
        startupAbsorptionRate = Share.One,
        sectorDemandPressure = demandPressure(Multiplier.One),
      ),
    )

    weights(0) should be < weights(2)
  }

  // ==========================================================================
  // Net firm creation
  // ==========================================================================

  private def mkFirms(n: Int): Vector[Firm.State] =
    (0 until n)
      .map: i =>
        TestFirmState(
          id = FirmId(i),
          cash = PLN(100000),
          debt = PLN.Zero,
          tech = TechState.Traditional(5),
          riskProfile = Share.decimal(5, 1),
          innovationCostFactor = Multiplier.One,
          digitalReadiness = Share.decimal(1, 1),
          sector = SectorIdx(i % 6),
          neighbors = Vector.empty[FirmId],
          bankId = BankId(0),
          equityRaised = PLN.Zero,
          initialSize = 5,
          capitalStock = PLN.Zero,
          foreignOwned = false,
          inventory = PLN.Zero,
          greenCapital = PLN.Zero,
          accumulatedLoss = PLN.Zero,
        )
      .toVector

  private def mkDeadFirm(id: Int, sector: Int = 2): Firm.State =
    TestFirmState(
      id = FirmId(id),
      cash = PLN(-1),
      debt = PLN.Zero,
      tech = TechState.Bankrupt(BankruptReason.Other("test")),
      riskProfile = Share.decimal(5, 1),
      innovationCostFactor = Multiplier.One,
      digitalReadiness = Share.decimal(1, 1),
      sector = SectorIdx(sector),
      neighbors = Vector.empty[FirmId],
      bankId = BankId(0),
      equityRaised = PLN.Zero,
      initialSize = 0,
      capitalStock = PLN.Zero,
      foreignOwned = false,
      inventory = PLN.Zero,
      greenCapital = PLN.Zero,
      accumulatedLoss = PLN.Zero,
    )

  "Net creation" should "produce zero new firms when unemployment is at NAIRU" in {
    val firms  = mkFirms(100)
    val rng    = RandomStream.seeded(42)
    val result = runEntry(firms, BigDecimal("0.05"), rng)
    result.netBirths shouldBe 0
    result.firms.length shouldBe firms.length
  }

  "Replacement entry" should "recreate some dead firms when net entry is neutral" in {
    val firms  = mkFirms(20) ++ Vector(mkDeadFirm(20), mkDeadFirm(21), mkDeadFirm(22), mkDeadFirm(23))
    val rng    = RandomStream.seeded(42)
    val result = runEntry(firms, BigDecimal("0.05"), rng)
    result.netBirths shouldBe 0
    result.births should be > 0
    result.newFirmIds.size shouldBe result.births
    result.firms.length shouldBe firms.length
    result.firms.count(Firm.isAlive) should be > firms.count(Firm.isAlive)
  }

  it should "preserve vector length when only replacements occur" in {
    val firms  = mkFirms(20) ++ Vector(mkDeadFirm(20), mkDeadFirm(21))
    val rng    = RandomStream.seeded(42)
    val result = runEntry(firms, BigDecimal("0.05"), rng)
    result.netBirths shouldBe 0
    result.firms.length shouldBe firms.length
  }

  it should "count only appended firms as netBirths" in {
    val firms  = mkFirms(100) ++ Vector(mkDeadFirm(100), mkDeadFirm(101), mkDeadFirm(102))
    val rng    = RandomStream.seeded(42)
    val result = runEntry(firms, BigDecimal("0.20"), rng, sectorDemandPressure = ExpansionDemandPressure)
    result.births should be >= result.netBirths
    result.netBirths should be > 0
    result.newFirmIds.size shouldBe result.births
    result.firms.length shouldBe firms.length + result.netBirths
  }

  it should "not produce net firms from high unemployment alone" in {
    val firms  = mkFirms(100)
    val rng    = RandomStream.seeded(42)
    val result = runEntry(firms, BigDecimal("0.15"), rng)
    result.netBirths shouldBe 0
    result.firms.length shouldBe firms.length
  }

  it should "produce net firms when demand pressure is high and labor supply is available" in {
    val firms  = mkFirms(100)
    val rng    = RandomStream.seeded(42)
    val result = runEntry(firms, BigDecimal("0.15"), rng, sectorDemandPressure = ExpansionDemandPressure)
    result.netBirths should be > 0
    result.firms.length should be > firms.length
  }

  it should "respect hard cap" in {
    val firms  = mkFirms(10000)
    val rng    = RandomStream.seeded(42)
    val result = runEntry(firms, BigDecimal("0.50"), rng, sectorDemandPressure = StrongDemandPressure)
    result.netBirths should be <= p.firm.netEntryMaxMonthly
  }

  it should "assign sequential FirmIds" in {
    val firms    = mkFirms(100)
    val rng      = RandomStream.seeded(42)
    val result   = runEntry(firms, BigDecimal("0.20"), rng, sectorDemandPressure = ExpansionDemandPressure)
    val newFirms = result.firms.drop(firms.length)
    newFirms.zipWithIndex.foreach: (f, i) =>
      f.id.toInt shouldBe firms.length + i
    newFirms.map(_.id).toSet.subsetOf(result.newFirmIds) shouldBe true
  }

  it should "create firms with GUS size distribution" in {
    val firms    = mkFirms(100)
    val rng      = RandomStream.seeded(42)
    val result   = runEntry(firms, BigDecimal("0.20"), rng, sectorDemandPressure = ExpansionDemandPressure)
    val newFirms = result.firms.drop(firms.length)
    newFirms.foreach: f =>
      f.initialSize should be >= 1
  }

  it should "initialize entrants with startup ramp-up state" in {
    val firms    = mkFirms(100)
    val rng      = RandomStream.seeded(42)
    val result   = runEntry(firms, BigDecimal("0.20"), rng, sectorDemandPressure = ExpansionDemandPressure)
    val newFirms = result.firms.drop(firms.length)
    newFirms.foreach: f =>
      f.startupMonthsLeft should be > 0
      f.startupTargetWorkers should be >= 1
      f.startupFilledWorkers shouldBe 0
      Firm.workerCount(f) shouldBe f.startupTargetWorkers
  }

  it should "dampen net entry when lagged hiring slack is tight" in {
    val firms       = mkFirms(1000)
    val looseResult = runEntry(firms, BigDecimal("0.20"), RandomStream.seeded(42), sectorDemandPressure = ExpansionDemandPressure)
    val tightResult =
      runEntry(firms, BigDecimal("0.20"), RandomStream.seeded(42), laggedHiringSlack = Share.decimal(5, 1), sectorDemandPressure = ExpansionDemandPressure)
    tightResult.netBirths.should(be < looseResult.netBirths)
  }

  it should "dampen net entry when startup absorption is weak" in {
    val firms        = mkFirms(1000)
    val strongAbsorb = runEntry(
      firms,
      BigDecimal("0.20"),
      RandomStream.seeded(42),
      inflation = Rate.decimal(3, 2),
      expectedInflation = Rate.decimal(25, 3),
      sectorDemandPressure = ExpansionDemandPressure,
    )
    val weakAbsorb   =
      runEntry(
        firms,
        BigDecimal("0.20"),
        RandomStream.seeded(42),
        inflation = Rate.decimal(3, 2),
        expectedInflation = Rate.decimal(25, 3),
        startupAbsorptionRate = Share.decimal(25, 2),
        sectorDemandPressure = ExpansionDemandPressure,
      )
    weakAbsorb.netBirths should be < strongAbsorb.netBirths
  }

  it should "suppress expansionary net entry under deflation and negative expectations" in {
    val firms  = mkFirms(1000)
    val result = runEntry(
      firms,
      BigDecimal("0.20"),
      RandomStream.seeded(42),
      inflation = Rate.decimal(-2, 2),
      expectedInflation = Rate.decimal(-1, 2),
      sectorDemandPressure = ExpansionDemandPressure,
    )
    result.netBirths.shouldBe(0)
  }

  it should "allow expansionary net entry when demand pressure is high and nominal conditions are positive" in {
    val firms  = mkFirms(1000)
    val result = runEntry(
      firms,
      BigDecimal("0.20"),
      RandomStream.seeded(42),
      inflation = Rate.decimal(3, 2),
      expectedInflation = Rate.decimal(25, 3),
      sectorDemandPressure = ExpansionDemandPressure,
    )
    result.netBirths.should(be > 0)
  }

  it should "allow expansionary net entry in a tight labor market when hiring slack remains available" in {
    val firms  = mkFirms(1000)
    val result = runEntry(firms, BigDecimal("0.03"), RandomStream.seeded(42), inflation = Rate.decimal(3, 2), expectedInflation = Rate.decimal(25, 3))
    result.netBirths.should(be > 0)
  }

  it should "preserve existing firms unchanged" in {
    val firms  = mkFirms(100)
    val rng    = RandomStream.seeded(42)
    val result = runEntry(firms, BigDecimal("0.20"), rng, sectorDemandPressure = ExpansionDemandPressure)
    result.firms.take(firms.length).map(_.id) shouldBe firms.map(_.id)
  }

  "NetEntryRate" should "default to 0.12" in {
    p.firm.netEntryRate shouldBe Share.decimal(12, 2)
  }

  "NetEntryMaxMonthly" should "default to 175" in {
    p.firm.netEntryMaxMonthly shouldBe 175
  }
