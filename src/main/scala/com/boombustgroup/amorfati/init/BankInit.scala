package com.boombustgroup.amorfati.init

import com.boombustgroup.amorfati.agents.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.mechanisms.Macroprudential
import com.boombustgroup.amorfati.types.*

/** Banking sector initialization from actual agent populations.
  *
  * Computes per-bank balances from firm and household populations, ensuring the
  * initial bank state is consistent with agent-level bank assignments.
  */
object BankInit:

  case class Result(
      banks: Vector[Banking.BankState],
      financialStocks: Vector[Banking.BankFinancialStocks],
      market: Banking.MarketState,
  )

  private final case class OpeningRow(
      config: Banking.Config,
      financialStocks: Banking.BankFinancialStocks,
      eclCoveredLoans: PLN,
      corpBondHoldings: PLN,
  )

  private val OpeningCapitalAggregateTolerance: Share = Share.decimal(1, 3)
  private val OpeningCapitalToleranceFloor: PLN       = PLN(1)

  def create(
      firms: Vector[Firm.State],
      firmFinancialStocks: Vector[Firm.FinancialStocks],
      households: Vector[Household.State],
      householdFinancialStocks: Vector[Household.FinancialStocks],
      bankCorpBondHoldings: Vector[PLN] = Vector.empty,
      openingCapitalProfiles: Vector[Banking.OpeningCapitalProfile] = Banking.DefaultOpeningCapitalProfiles,
  )(using p: SimParams): Result =
    require(
      firms.length == firmFinancialStocks.length,
      s"BankInit.create requires aligned firms and financial stocks, got ${firms.length} firms and ${firmFinancialStocks.length} stock rows",
    )
    require(
      households.length == householdFinancialStocks.length,
      s"BankInit.create requires aligned households and financial stocks, got ${households.length} households and ${householdFinancialStocks.length} stock rows",
    )
    val firmRows          = firms.zip(firmFinancialStocks)
    val perBankCorpLoans  = firmRows.groupMapReduce(_._1.bankId.toInt)(_._2.firmLoan)(_ + _)
    val perBankCash       = firmRows.groupMapReduce(_._1.bankId.toInt)(_._2.cash)(_ + _)
    val householdRows     = households.zip(householdFinancialStocks)
    val perBankConsLoans  = householdRows.groupMapReduce(_._1.bankId.toInt)(_._2.consumerLoan)(_ + _)
    val perBankMortgages  = householdRows.groupMapReduce(_._1.bankId.toInt)(_._2.mortgageLoan)(_ + _)
    val perBankHhDeposits = householdRows.groupMapReduce(_._1.bankId.toInt)(_._2.demandDeposit)(_ + _)

    val totalGovBonds           = p.banking.initGovBonds
    val bondAlloc               = com.boombustgroup.ledger.Distribute.distribute(
      totalGovBonds.toLong,
      Banking.DefaultConfigs.map(_.openingBalanceWeight.toLong).toArray,
    )
    val corpBondHoldings        =
      if bankCorpBondHoldings.isEmpty then Vector.fill(Banking.DefaultConfigs.length)(PLN.Zero)
      else
        require(
          bankCorpBondHoldings.length == Banking.DefaultConfigs.length,
          s"BankInit.create requires ${Banking.DefaultConfigs.length} bank corporate-bond rows, got ${bankCorpBondHoldings.length}",
        )
        bankCorpBondHoldings
    val capitalProfilesByBankId = openingCapitalProfiles.map(profile => profile.bankId -> profile).toMap
    require(
      capitalProfilesByBankId.size == openingCapitalProfiles.size,
      "BankInit.create requires at most one opening capital profile per bank",
    )

    val openingRows             = Banking.DefaultConfigs
      .zip(bondAlloc)
      .zip(corpBondHoldings)
      .map { case ((cfg, bankBondRaw), bankCorpBonds) =>
        val bId             = cfg.id.toInt
        val corpLoans       = perBankCorpLoans.getOrElse(bId, PLN.Zero)
        val consLoans       = perBankConsLoans.getOrElse(bId, PLN.Zero)
        val mortgageLoans   = perBankMortgages.getOrElse(bId, PLN.Zero)
        val firmDeposits    = perBankCash.getOrElse(bId, PLN.Zero)
        val hhDeposits      = perBankHhDeposits.getOrElse(bId, PLN.Zero)
        val bankBonds       = PLN.fromRaw(bankBondRaw)
        val deposits        = firmDeposits + hhDeposits
        val termDeposits    = deposits * p.banking.termDepositFrac
        val demandDeposits  = deposits - termDeposits
        val eclCoveredLoans = corpLoans + consLoans
        OpeningRow(
          config = cfg,
          financialStocks = Banking.BankFinancialStocks(
            totalDeposits = deposits,
            firmLoan = corpLoans,
            govBondAfs = bankBonds * (Share.One - p.banking.htmShare),
            govBondHtm = bankBonds * p.banking.htmShare,
            reserve = deposits * p.banking.reserveReq,
            interbankLoan = PLN.Zero,
            demandDeposit = demandDeposits,
            termDeposit = termDeposits,
            consumerLoan = consLoans,
            mortgageLoan = mortgageLoans,
          ),
          eclCoveredLoans = eclCoveredLoans,
          corpBondHoldings = bankCorpBonds,
        )
      }
    val totalOpeningRwa         = openingRows.map(row => Banking.riskWeightedAssets(row.financialStocks, row.corpBondHoldings)).sumPln
    require(
      totalOpeningRwa > PLN.Zero,
      "BankInit.create requires positive opening RWA to derive sector opening capital ratio",
    )
    val sectorTotalCapitalRatio = p.banking.initCapital.ratioTo(totalOpeningRwa).toMultiplier

    val rows = openingRows.map: row =>
      val profile       = capitalProfilesByBankId.getOrElse(row.config.id, Banking.OpeningCapitalProfile(row.config.id))
      val capitalResult = Banking.openingCapitalFromProfile(
        profile,
        row.financialStocks,
        row.corpBondHoldings,
        sectorTotalCapitalRatio,
      )
      val bank          = Banking.BankState(
        id = row.config.id,
        capital = capitalResult.capital,
        nplAmount = PLN.Zero,
        htmBookYield = p.banking.initHtmBookYield,
        status = Banking.BankStatus.Active(0),
        loansShort = PLN.Zero,
        loansMedium = PLN.Zero,
        loansLong = PLN.Zero,
        consumerNpl = PLN.Zero,
        eclStaging = EclStaging.State.allStage1(row.eclCoveredLoans),
      )
      val minCar        = Macroprudential.effectiveMinCar(row.config.id.toInt, p.banking.initialCcyb)
      val car           = Banking.car(bank, row.financialStocks, row.corpBondHoldings)
      require(
        car >= minCar,
        s"Opening bank ${row.config.name} CAR $car is below effective minimum $minCar",
      )
      (bank, row.financialStocks)

    val aggregateOpeningCapital = rows.map(_._1.capital).sumPln
    val capitalDelta            = absoluteDelta(aggregateOpeningCapital, p.banking.initCapital)
    val capitalTolerance        = (p.banking.initCapital * OpeningCapitalAggregateTolerance).max(OpeningCapitalToleranceFloor)
    require(
      capitalDelta <= capitalTolerance,
      s"Opening bank capital $aggregateOpeningCapital differs from banking.initCapital ${p.banking.initCapital} by $capitalDelta, above tolerance $capitalTolerance",
    )

    Result(
      banks = rows.map(_._1),
      financialStocks = rows.map(_._2),
      market = Banking.MarketState(
        interbankRate = Rate.Zero,
        configs = Banking.DefaultConfigs,
        interbankCurve = None,
      ),
    )

  private def absoluteDelta(left: PLN, right: PLN): PLN =
    if left >= right then left - right else right - left
