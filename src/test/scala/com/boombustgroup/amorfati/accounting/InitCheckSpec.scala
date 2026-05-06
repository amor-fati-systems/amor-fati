package com.boombustgroup.amorfati.accounting

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.boombustgroup.amorfati.agents.{Firm, Household}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.flows.FlowSimulation
import com.boombustgroup.amorfati.init.{InitRandomness, WorldInit}
import com.boombustgroup.amorfati.types.*

class InitCheckSpec extends AnyFlatSpec with Matchers:

  given SimParams = SimParams.defaults

  private lazy val defaultInit: WorldInit.InitResult =
    WorldInit.initialize(InitRandomness.Contract.fromSeed(42L))

  private def runtimeState(result: WorldInit.InitResult): Sfc.RuntimeState =
    val state = FlowSimulation.SimState.fromInit(result)
    Sfc.RuntimeState(state.world, state.firms, state.households, state.banks, state.ledgerFinancialState)

  private def stockSnapshot(result: WorldInit.InitResult): Sfc.StockState =
    Sfc.snapshot(runtimeState(result))

  "InitCheck" should "pass for default init" in:
    val result = defaultInit
    val errors = InitCheck.validate(runtimeState(result))
    errors shouldBe empty

  it should "initialize firm employment consistently with unemployed households" in:
    val result          = defaultInit
    val employed        = Household.countEmployed(result.households)
    val firmEmployment  = result.firms.filter(Firm.isAlive).map(Firm.workerCount).sum
    val unemployedShare = result.householdAggregates.unemploymentRate(result.households.length)

    result.householdAggregates.employed shouldBe employed
    firmEmployment shouldBe employed
    unemployedShare shouldBe result.world.seedIn.unemploymentRate

  it should "have exact bond clearing at raw-unit precision for default init" in:
    val result   = defaultInit
    val snapshot = stockSnapshot(result)
    val holdings =
      snapshot.bankBondHoldings + snapshot.nbpBondHoldings + snapshot.foreignBondHoldings +
        snapshot.ppkBondHoldings + snapshot.insuranceGovBondHoldings + snapshot.tfiGovBondHoldings

    holdings shouldBe snapshot.bondsOutstanding

  it should "initialize tradable government bonds against the opening debt stock" in:
    val result = defaultInit

    result.ledgerFinancialState.government.govBondOutstanding shouldBe result.world.gov.cumulativeDebt
    result.ledgerFinancialState.foreign.govBondHoldings should be > PLN.Zero
    result.world.gov.weightedCoupon shouldBe summon[SimParams].fiscal.govInitialWeightedCoupon

  it should "initialize domestic and ESA debt metrics through quasi-fiscal stock" in:
    val result = defaultInit
    val p      = summon[SimParams]
    val qf     = result.ledgerFinancialState.funds.quasiFiscal

    result.world.gov.cumulativeDebt shouldBe p.fiscal.initGovDebt
    qf.bondsOutstanding shouldBe p.quasiFiscal.initBondsOutstanding
    qf.nbpHoldings shouldBe p.quasiFiscal.initNbpBondHoldings
    qf.bankHoldings + qf.nbpHoldings shouldBe qf.bondsOutstanding

  it should "detect tampered bondsOutstanding" in:
    val result   = defaultInit
    val snap     = stockSnapshot(result)
    val tampered = snap.copy(bondsOutstanding = snap.bondsOutstanding + PLN(1000))
    val errors   = InitCheck.validate(tampered, result.banks, result.firms, result.households, result.ledgerFinancialState)
    errors should not be empty
    errors.exists(_.identity == "Bond clearing") shouldBe true

  it should "detect tampered bank deposits" in:
    val result         = defaultInit
    val snap           = stockSnapshot(result)
    val bankBalances   = result.ledgerFinancialState.banks
    val tamperedLedger = result.ledgerFinancialState.copy(
      banks = bankBalances.updated(0, bankBalances(0).copy(totalDeposits = bankBalances(0).totalDeposits + PLN(5000))),
    )
    val errors         = InitCheck.validate(snap, result.banks, result.firms, result.households, tamperedLedger)
    errors should not be empty
    errors.exists(_.identity.startsWith("Deposit consistency")) shouldBe true

  it should "detect tampered NBP reserve liability" in:
    val result         = defaultInit
    val snap           = stockSnapshot(result)
    val tamperedLedger = result.ledgerFinancialState.copy(
      nbp = result.ledgerFinancialState.nbp.copy(reserveLiability = result.ledgerFinancialState.nbp.reserveLiability + PLN(5000)),
    )
    val errors         = InitCheck.validate(snap, result.banks, result.firms, result.households, tamperedLedger)
    errors should not be empty
    errors.exists(_.identity == "NBP reserve liability") shouldBe true

  it should "detect tampered firm debt" in:
    val result         = defaultInit
    val snap           = stockSnapshot(result)
    val bankBalances   = result.ledgerFinancialState.banks
    val tamperedLedger = result.ledgerFinancialState.copy(
      banks = bankBalances.updated(0, bankBalances(0).copy(firmLoan = bankBalances(0).firmLoan + PLN(5000))),
    )
    val errors         = InitCheck.validate(snap, result.banks, result.firms, result.households, tamperedLedger)
    errors should not be empty
    errors.exists(_.identity.startsWith("Corp loan consistency")) shouldBe true

  it should "detect tampered consumer loans" in:
    val result         = defaultInit
    val snap           = stockSnapshot(result)
    val bankBalances   = result.ledgerFinancialState.banks
    val tamperedLedger = result.ledgerFinancialState.copy(
      banks = bankBalances.updated(0, bankBalances(0).copy(consumerLoan = bankBalances(0).consumerLoan + PLN(5000))),
    )
    val errors         = InitCheck.validate(snap, result.banks, result.firms, result.households, tamperedLedger)
    errors should not be empty
    errors.exists(_.identity.startsWith("Consumer loan consistency")) shouldBe true

  it should "detect tampered bank mortgage mirror" in:
    val result         = defaultInit
    val snap           = stockSnapshot(result)
    val bankBalances   = result.ledgerFinancialState.banks
    val tamperedLedger = result.ledgerFinancialState.copy(
      banks = bankBalances.updated(0, bankBalances(0).copy(mortgageLoan = bankBalances(0).mortgageLoan + PLN(5000))),
    )
    val errors         = InitCheck.validate(snap, result.banks, result.firms, result.households, tamperedLedger)
    errors should not be empty
    errors.exists(_.identity == "Mortgage bank asset mirror") shouldBe true

  it should "detect tampered household insurance reserve mirror" in:
    val result             = defaultInit
    val snap               = stockSnapshot(result)
    val householdBalances  = result.ledgerFinancialState.households
    val tamperedHouseholds = householdBalances.updated(
      0,
      householdBalances(0).copy(lifeReserveAsset = householdBalances(0).lifeReserveAsset + PLN(5000)),
    )
    val tamperedLedger     = result.ledgerFinancialState.copy(households = tamperedHouseholds)
    val errors             = InitCheck.validate(snap, result.banks, result.firms, result.households, tamperedLedger)
    errors should not be empty
    errors.exists(_.identity == "Life reserve household asset mirror") shouldBe true
