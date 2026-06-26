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
      bankGovBondHoldings: Vector[PLN] = Vector.empty,
      bankReserveHoldings: Vector[PLN] = Vector.empty,
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

    val govBondHoldings         =
      if bankGovBondHoldings.isEmpty then
        com.boombustgroup.ledger.Distribute
          .distribute(
            p.banking.initGovBonds.toLong,
            Banking.DefaultConfigs.map(_.openingBalanceWeight.toLong).toArray,
          )
          .map(PLN.fromRaw)
          .toVector
      else requireBankVectorTotal("government-bond", bankGovBondHoldings, p.banking.initGovBonds)
    val reserveHoldings         =
      if bankReserveHoldings.isEmpty then Vector.empty
      else requireBankVectorTotal("reserve", bankReserveHoldings, p.banking.initDeposits * p.banking.reserveReq)
    val corpBondHoldings        =
      if bankCorpBondHoldings.isEmpty then Vector.fill(Banking.DefaultConfigs.length)(PLN.Zero)
      else requireBankVectorTotal("corporate-bond", bankCorpBondHoldings, p.corpBond.initStock * p.corpBond.bankShare)
    val capitalProfilesByBankId = openingCapitalProfiles.map(profile => profile.bankId -> profile).toMap
    require(
      capitalProfilesByBankId.size == openingCapitalProfiles.size,
      "BankInit.create requires at most one opening capital profile per bank",
    )

    val openingRows             = Banking.DefaultConfigs
      .zip(govBondHoldings)
      .zip(corpBondHoldings)
      .map { case ((cfg, bankBonds), bankCorpBonds) =>
        val bId             = cfg.id.toInt
        val corpLoans       = perBankCorpLoans.getOrElse(bId, PLN.Zero)
        val consLoans       = perBankConsLoans.getOrElse(bId, PLN.Zero)
        val mortgageLoans   = perBankMortgages.getOrElse(bId, PLN.Zero)
        val firmDeposits    = perBankCash.getOrElse(bId, PLN.Zero)
        val hhDeposits      = perBankHhDeposits.getOrElse(bId, PLN.Zero)
        val deposits        = firmDeposits + hhDeposits
        val termDeposits    = deposits * p.banking.termDepositFrac
        val demandDeposits  = deposits - termDeposits
        val reserve         =
          if reserveHoldings.isEmpty then deposits * p.banking.reserveReq
          else bankVectorValue("reserve", reserveHoldings, bId)
        val eclCoveredLoans = corpLoans + consLoans
        OpeningRow(
          config = cfg,
          financialStocks = Banking.BankFinancialStocks(
            totalDeposits = deposits,
            firmLoan = corpLoans,
            govBondAfs = bankBonds * (Share.One - p.banking.htmShare),
            govBondHtm = bankBonds * p.banking.htmShare,
            reserve = reserve,
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

  private def requireBankVector(label: String, values: Vector[PLN]): Vector[PLN] =
    require(
      values.length == Banking.DefaultConfigs.length,
      s"BankInit.create requires ${Banking.DefaultConfigs.length} bank $label rows, got ${values.length}",
    )
    require(values.forall(_ >= PLN.Zero), s"BankInit.create requires non-negative bank $label rows")
    values

  private def requireBankVectorTotal(label: String, values: Vector[PLN], expectedTotal: PLN): Vector[PLN] =
    val checked = requireBankVector(label, values)
    val actual  = checked.sumPln
    require(
      actual == expectedTotal,
      s"BankInit.create requires bank $label rows to sum to $expectedTotal, got $actual",
    )
    checked

  private def bankVectorValue(label: String, values: Vector[PLN], bankIndex: Int): PLN =
    require(
      bankIndex >= 0 && bankIndex < values.length,
      s"BankInit.create $label rows missing BankId index $bankIndex; rows=${values.length}",
    )
    values(bankIndex)
