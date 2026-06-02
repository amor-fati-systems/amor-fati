package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.agents.{Banking, InterbankContagion}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.mechanisms.YieldCurve
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.ledger.Distribute

/** Clears interbank positions and NBP reserve-side settlements before bond
  * waterfall and failure detection.
  */
private[amorfati] object BankInterbankSettlement:

  def run(
      in: StepInput,
      updatedBanks: Vector[Banking.BankState],
      updatedBankStocks: Vector[Banking.BankFinancialStocks],
      bankConfigs: Vector[Banking.Config],
      prevBankAgg: Banking.Aggregate,
      reserveInterest: Banking.PerBankAmounts,
      standingFacilityIncome: Banking.PerBankAmounts,
      interbankInterest: Banking.PerBankAmounts,
  )(using p: SimParams): InterbankSettlementResult =
    val interbankRate  = Banking.interbankRate(updatedBanks, updatedBankStocks, in.world.nbp.referenceRate)
    val hoarding       = InterbankContagion.hoardingFactor(prevBankAgg.nplRatio)
    val afterInterbank = Banking.clearInterbank(updatedBanks, updatedBankStocks, bankConfigs, hoarding)
    val nbpSettlement  = applyNbpReserveSettlement(
      afterInterbank.banks,
      afterInterbank.financialStocks,
      reserveInterest,
      standingFacilityIncome,
      interbankInterest,
      in.openEconomy.monetary.fxPlnInjection,
    )
    if nbpSettlement.residual != PLN.Zero then
      throw IllegalStateException(
        s"NBP reserve settlement left unallocated FX residual ${nbpSettlement.residual} after reserve-side settlement.",
      )
    val htmResult      = Banking.processHtmForcedSale(nbpSettlement.banks, nbpSettlement.financialStocks, in.openEconomy.monetary.newBondYield)

    InterbankSettlementResult(
      banks = htmResult.banks,
      financialStocks = htmResult.financialStocks,
      interbankRate = interbankRate,
      standingFacilityBackstop = nbpSettlement.standingFacilityBackstop,
      htmRealizedLoss = htmResult.totalRealizedLoss,
    )

  def marketState(
      settlement: InterbankSettlementResult,
      bankConfigs: Vector[Banking.Config],
      prevBankAgg: Banking.Aggregate,
      in: StepInput,
  )(using p: SimParams): Banking.MarketState =
    val exp = in.world.mechanisms.expectations
    Banking.MarketState(
      interbankRate = settlement.interbankRate,
      configs = bankConfigs,
      interbankCurve = Some(
        YieldCurve.compute(
          settlement.interbankRate,
          nplRatio = prevBankAgg.nplRatio,
          credibility = exp.credibility,
          expectedInflation = exp.expectedInflation,
          targetInflation = p.monetary.targetInfl,
        ),
      ),
    )

  /** Apply NBP-side reserve settlement deltas to bank reserve balances.
    *
    * Reserve remuneration, standing-facility settlement, interbank settlement
    * routed via NBP, and FX intervention PLN injection/drain all land on the
    * same reserve-side settlement channel. If a drain would push a bank below
    * zero reserves, the shortfall is converted into explicit standing-facility
    * borrowing while reserve balances stay non-negative.
    */
  private[amorfati] def applyNbpReserveSettlement(
      banks: Vector[Banking.BankState],
      financialStocks: Vector[Banking.BankFinancialStocks],
      reserveInterest: Banking.PerBankAmounts,
      standingFacilityIncome: Banking.PerBankAmounts,
      interbankInterest: Banking.PerBankAmounts,
      fxInjection: PLN,
  ): ReserveSettlementResult =
    val n = banks.length
    require(
      financialStocks.length == n,
      s"BankInterbankSettlement.applyNbpReserveSettlement requires aligned banks and financialStocks, got banks=$n and financialStocks=${financialStocks.length}",
    )
    require(
      reserveInterest.perBank.length == n,
      s"BankInterbankSettlement.applyNbpReserveSettlement requires reserveInterest rows=$n, got ${reserveInterest.perBank.length}",
    )
    require(
      standingFacilityIncome.perBank.length == n,
      s"BankInterbankSettlement.applyNbpReserveSettlement requires standingFacilityIncome rows=$n, got ${standingFacilityIncome.perBank.length}",
    )
    require(
      interbankInterest.perBank.length == n,
      s"BankInterbankSettlement.applyNbpReserveSettlement requires interbankInterest rows=$n, got ${interbankInterest.perBank.length}",
    )

    val distributedFx = distributeFxInjectionByDeposits(financialStocks, fxInjection)
    require(
      distributedFx.allocations.length == n,
      s"BankInterbankSettlement.applyNbpReserveSettlement requires distributedFx allocations=$n, got ${distributedFx.allocations.length}",
    )

    val updatedStocks            = new Array[Banking.BankFinancialStocks](n)
    var standingFacilityBackstop = PLN.Zero
    var idx                      = 0
    while idx < n do
      val stocks  = financialStocks(idx)
      val delta   =
        reserveInterest.perBank(idx) +
          standingFacilityIncome.perBank(idx) +
          interbankInterest.perBank(idx) +
          distributedFx.allocations(idx)
      val updated = stocks.reserve + delta
      if updated >= PLN.Zero then updatedStocks(idx) = stocks.copy(reserve = updated)
      else
        updatedStocks(idx) = stocks.copy(reserve = PLN.Zero)
        standingFacilityBackstop -= updated
      idx += 1

    ReserveSettlementResult(banks, updatedStocks.toVector, standingFacilityBackstop, distributedFx.residual)

  /** Distribute FX intervention PLN injection across banks proportional to
    * deposit market share, adjusting reservesAtNbp. EUR purchase -> PLN
    * injected into banking system; EUR sale -> PLN drained. Any amount that
    * cannot be allocated is surfaced via `residual`.
    */
  private[amorfati] def distributeFxInjection(
      banks: Vector[Banking.BankState],
      financialStocks: Vector[Banking.BankFinancialStocks],
      injection: PLN,
  ): ReserveSettlementResult =
    val zeros = Banking.PerBankAmounts(Vector.fill(banks.size)(PLN.Zero), PLN.Zero)
    applyNbpReserveSettlement(
      banks,
      financialStocks,
      reserveInterest = zeros,
      standingFacilityIncome = zeros,
      interbankInterest = zeros,
      fxInjection = injection,
    )

  private def distributeFxInjectionByDeposits(
      financialStocks: Vector[Banking.BankFinancialStocks],
      injection: PLN,
  ): FxSettlementAllocation =
    if injection == PLN.Zero then FxSettlementAllocation(Vector.fill(financialStocks.size)(PLN.Zero), PLN.Zero)
    else
      val weights = financialStocks.map(_.totalDeposits.toLong.max(0L)).toArray
      if weights.sum <= 0L then FxSettlementAllocation(Vector.fill(financialStocks.size)(PLN.Zero), injection)
      else
        val allocations = Distribute
          .distribute(math.abs(injection.toLong), weights)
          .iterator
          .map { rawAllocated =>
            if injection >= PLN.Zero then PLN.fromRaw(rawAllocated) else PLN.fromRaw(-rawAllocated)
          }
          .toVector
        FxSettlementAllocation(
          allocations = allocations,
          residual = injection - allocations.foldLeft(PLN.Zero)(_ + _),
        )
