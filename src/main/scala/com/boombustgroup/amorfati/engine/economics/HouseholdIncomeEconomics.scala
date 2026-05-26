package com.boombustgroup.amorfati.engine.economics

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.World
import com.boombustgroup.amorfati.engine.ledger.{CorporateBondOwnership, LedgerFinancialState}
import com.boombustgroup.amorfati.engine.markets.LaborMarket
import com.boombustgroup.amorfati.engine.mechanisms.SectoralMobility
import com.boombustgroup.amorfati.types.*

import com.boombustgroup.amorfati.random.RandomStream

/** Pure economic logic for household income determination — no state mutation,
  * no flows.
  *
  * Computes individual household income, consumption, saving, and portfolio
  * decisions. Integrates labor market separations, wage updates, bank-specific
  * lending/deposit rates, equity returns, and sectoral mobility signals into
  * household-level state updates.
  *
  * Extracted from HouseholdIncomeStep (Calculus vs Accounting split).
  */
object HouseholdIncomeEconomics:

  // ---- Calibration constants ----
  private val ImportErElasticity = Coefficient.decimal(5, 1) // exchange rate elasticity of import propensity

  case class Output(
      totalIncome: PLN,                                     // aggregate household income (wages + benefits + transfers)
      consumption: PLN,                                     // aggregate household consumption spending
      importCons: PLN,                                      // import component of household consumption (forex demand)
      domesticCons: PLN,                                    // domestic component of household consumption
      updatedHouseholds: Vector[Household.State],           // post-income household population
      hhAgg: Household.Aggregates,                          // household-level aggregates (employment, savings, etc.)
      perBankHhFlowsOpt: Option[Vector[PerBankFlow]],       // per-bank household flow breakdown (multi-bank mode)
      householdMonthlyFlows: Vector[Household.MonthlyFlow], // per-HH monthly consumer-liquidity diagnostics
      pitRevenue: PLN,                                      // personal income tax collected from households
      importAdj: Share,                                     // ER-adjusted import propensity (base * ER elasticity)
      aggUnempBenefit: PLN,                                 // aggregate unemployment benefit payments
      ledgerFinancialState: LedgerFinancialState,           // ledger after household financial stock updates
  )

  type StepOutput = Output

  def compute(
      w: World,
      firms: Vector[Firm.State],
      households: Vector[Household.State],
      banks: Vector[Banking.BankState],
      ledgerFinancialState: LedgerFinancialState,
      lendingBaseRate: Rate,
      resWage: PLN,
      newWage: PLN,
      rng: RandomStream,
      pensionIncome: PLN = PLN.Zero,
  )(using p: SimParams): StepOutput =
    val importAdj =
      (p.forex.importPropensity * p.forex.baseExRate.ratioTo(w.forex.exchangeRate).pow(ImportErElasticity.toScalar)).toShare.clamp(
        Share.Zero,
        Share.One,
      )

    val afterSep           = LaborMarket.separations(households, firms, firms)
    val afterWages         = LaborMarket.updateWages(afterSep, firms, newWage)
    val bsec               = w.bankingSector
    val nBanksHh           = banks.length
    val bankStocks         = ledgerFinancialState.banks.map(LedgerFinancialState.projectBankFinancialStocks)
    requireBankRateInputsAligned(banks, bankStocks, bsec.configs)
    val ccyb               = w.mechanisms.macropru.ccyb
    val bankCorpBonds      = (bankId: BankId) => CorporateBondOwnership.bankHolderFor(ledgerFinancialState, bankId)
    val hhBankRates        = Some(
      BankRates(
        lendingRates = banks
          .zip(bankStocks)
          .zip(bsec.configs)
          .map { case ((b, stocks), cfg) =>
            Banking.lendingRate(b, stocks, cfg, lendingBaseRate, w.gov.bondYield, bankCorpBonds(b.id))
          },
        depositRates = banks.map(_ => Banking.hhDepositRate(w.nbp.referenceRate)),
      ),
    )
    val consumerCreditGate = capitalAwareConsumerCreditGate(banks, bankStocks, ccyb, bankCorpBonds)
    val eqReturn           = w.financialMarkets.equity.monthlyReturn
    val secWages           = Some(SectoralMobility.sectorWages(afterWages))
    val secVacancies       = Some(SectoralMobility.sectorVacancies(afterWages, firms))
    val openingStocks      = ledgerFinancialState.households.map(LedgerFinancialState.projectHouseholdFinancialStocks)
    val householdStep      = Household.step(
      afterWages,
      openingStocks,
      w,
      newWage,
      resWage,
      importAdj,
      rng,
      nBanksHh,
      hhBankRates,
      eqReturn,
      secWages,
      secVacancies,
      Some(consumerCreditGate),
    )
    val agg                = householdStep.aggregates
    val pensionCons        = pensionIncome * p.household.mpc
    val pensionImport      = pensionCons * importAdj
    val pensionDomestic    = pensionCons - pensionImport
    val aggWithPensions    =
      if pensionIncome > PLN.Zero then
        agg.copy(
          totalIncome = agg.totalIncome + pensionIncome,
          consumption = agg.consumption + pensionCons,
          domesticConsumption = agg.domesticConsumption + pensionDomestic,
          importConsumption = agg.importConsumption + pensionImport,
        )
      else agg

    Output(
      aggWithPensions.totalIncome,
      aggWithPensions.consumption,
      aggWithPensions.importConsumption,
      aggWithPensions.domesticConsumption,
      householdStep.households,
      aggWithPensions,
      householdStep.perBankFlows,
      householdStep.monthlyFlows,
      aggWithPensions.totalPit,
      importAdj,
      aggUnempBenefit = PLN.Zero,
      ledgerFinancialState = ledgerFinancialState.copy(households = householdStep.financialStocks.map(LedgerFinancialState.householdBalances)),
    )

  private def requireBankRateInputsAligned(
      banks: Vector[Banking.BankState],
      bankStocks: Vector[Banking.BankFinancialStocks],
      configs: Vector[Banking.Config],
  ): Unit =
    val bankIds   = banks.map(_.id.toInt)
    val configIds = configs.map(_.id.toInt)
    if banks.length != bankStocks.length || banks.length != configs.length || bankIds != configIds then
      val configNames = configs.map(config => s"${config.id.toInt}:${config.name}")
      throw new IllegalArgumentException(
        "HouseholdIncomeEconomics.hhBankRates requires aligned bank rows, ledger bank stocks, and bank configs; " +
          s"banks=${banks.length} ids=${bankIds.mkString("[", ",", "]")}, " +
          s"ledgerBanks=${bankStocks.length}, " +
          s"configs=${configs.length} ids=${configIds.mkString("[", ",", "]")} names=${configNames.mkString("[", ",", "]")}",
      )

  private[economics] def capitalAwareConsumerCreditGate(
      banks: Vector[Banking.BankState],
      bankStocks: Vector[Banking.BankFinancialStocks],
      ccyb: Multiplier,
      bankCorpBonds: BankId => PLN,
  )(using p: SimParams): Household.ConsumerCreditGate =
    require(
      banks.length == bankStocks.length,
      s"HouseholdIncomeEconomics consumer-credit gate requires aligned bank rows, got ${banks.length} banks and ${bankStocks.length} stock rows",
    )
    val approvedExposureByBank = Array.fill(banks.length)(PLN.Zero)

    (bankId, amount, approvalRng) =>
      val idx = bankId.toInt
      if amount <= PLN.Zero || idx < 0 || idx >= banks.length then false
      else
        val projectedStocks = bankStocks(idx).copy(
          consumerLoan = bankStocks(idx).consumerLoan + approvedExposureByBank(idx),
        )
        val approval        =
          Banking.creditApproval(banks(idx), projectedStocks, amount, approvalRng, ccyb, bankCorpBonds(bankId))
        if approval.approved then
          approvedExposureByBank(idx) = approvedExposureByBank(idx) + amount
          true
        else false
