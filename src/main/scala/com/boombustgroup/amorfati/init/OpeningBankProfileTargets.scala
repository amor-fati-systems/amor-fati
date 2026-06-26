package com.boombustgroup.amorfati.init

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.config.{OpeningBankBalanceProfileBridge, SimParams}
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.types.*

import scala.math.BigDecimal.RoundingMode

object OpeningBankProfileTargets:

  sealed trait Resolution

  object Resolution:
    final case class Complete(targets: Targets) extends Resolution
    final case class Pending(reason: String)    extends Resolution

  final case class Targets(
      bankIds: Vector[BankId],
      deposits: Vector[PLN],
      firmLoans: Vector[PLN],
      consumerLoans: Vector[PLN],
      mortgageLoans: Vector[PLN],
      govBonds: Vector[PLN],
      reserves: Option[Vector[PLN]],
      corpBonds: Option[Vector[PLN]],
      openingCapitalProfiles: Vector[Banking.OpeningCapitalProfile],
  ):
    val bankCount: Int = bankIds.length

    require(bankCount > 0, "Opening bank profile targets require at least one bank")
    require(
      deposits.length == bankCount &&
        firmLoans.length == bankCount &&
        consumerLoans.length == bankCount &&
        mortgageLoans.length == bankCount &&
        govBonds.length == bankCount &&
        openingCapitalProfiles.length == bankCount,
      "Opening bank profile targets require aligned per-bank vectors",
    )
    reserves.foreach(rows => require(rows.length == bankCount, s"Opening bank reserve targets require $bankCount rows, got ${rows.length}"))
    corpBonds.foreach(rows => require(rows.length == bankCount, s"Opening bank corporate-bond targets require $bankCount rows, got ${rows.length}"))

  private val MandatoryColumns: Vector[(String, OpeningBankBalanceProfileBridge.Row => Option[BigDecimal])] = Vector(
    "deposits_m_pln"       -> (_.depositsMPln),
    "firm_loans_m_pln"     -> (_.firmLoansMPln),
    "consumer_loans_m_pln" -> (_.consumerLoansMPln),
    "mortgage_loans_m_pln" -> (_.mortgageLoansMPln),
    "gov_bonds_m_pln"      -> (_.govBondsMPln),
  )

  private val AggregateToleranceFloor: PLN   = PLN(1000000)
  private val AggregateToleranceShare: Share = Share.decimal(1, 6)

  def fromBridgeRows(rows: Vector[OpeningBankBalanceProfileBridge.Row])(using p: SimParams): Resolution =
    val orderedRows      = runtimeRows(rows)
    val missingMandatory =
      orderedRows.flatMap: row =>
        MandatoryColumns.collect:
          case (column, value) if value(row).isEmpty => s"${row.runtimeBankName}.$column"

    val anyMandatoryValue =
      orderedRows.exists(row => MandatoryColumns.exists((_, value) => value(row).nonEmpty))

    if missingMandatory.nonEmpty then
      if anyMandatoryValue then
        throw new IllegalStateException(
          s"Opening bank profile has partial runtime stock targets; missing ${missingMandatory.mkString(", ")}",
        )
      else Resolution.Pending("Opening bank profile has no complete runtime stock targets yet")
    else
      val bankIds       = orderedRows.map(row => BankId(row.bankId.toInt))
      val deposits      = orderedRows.map(row => mPlnToRuntime(row.depositsMPln.get))
      val firmLoans     = orderedRows.map(row => mPlnToRuntime(row.firmLoansMPln.get))
      val consumerLoans = orderedRows.map(row => mPlnToRuntime(row.consumerLoansMPln.get))
      val mortgageLoans = orderedRows.map(row => mPlnToRuntime(row.mortgageLoansMPln.get))
      val govBonds      = orderedRows.map(row => mPlnToRuntime(row.govBondsMPln.get))
      val reserves      = optionalPlnVector("reserves_m_pln", orderedRows, _.reservesMPln)
      val corpBonds     = optionalPlnVector("corp_bonds_m_pln", orderedRows, _.corpBondsMPln)
      val ownFunds      = optionalPlnVector("own_funds_m_pln", orderedRows, _.ownFundsMPln)
      val capitalRatios = optionalMultiplierVector("total_capital_ratio", orderedRows, _.totalCapitalRatio)

      val capitalProfiles = bankIds.indices.map: i =>
        Banking.OpeningCapitalProfile(
          bankId = bankIds(i),
          ownFunds = ownFunds.map(_(i)),
          totalCapitalRatio = capitalRatios.map(_(i)),
        )

      requireClose("opening bank deposits", deposits.sumPln, p.banking.initDeposits)
      requireClose("opening bank firm loans", firmLoans.sumPln, p.banking.initLoans)
      requireClose("opening bank consumer loans", consumerLoans.sumPln, p.banking.initConsumerLoans)
      requireClose("opening bank mortgage loans", mortgageLoans.sumPln, p.housing.initMortgage)
      requireClose("opening bank government bonds", govBonds.sumPln, p.banking.initGovBonds)
      reserves.foreach(values => requireClose("opening bank reserves", values.sumPln, p.banking.initDeposits * p.banking.reserveReq))
      corpBonds.foreach(values => requireClose("opening bank corporate bonds", values.sumPln, p.corpBond.initStock * p.corpBond.bankShare))
      ownFunds.foreach(values => requireClose("opening bank own funds", values.sumPln, p.banking.initCapital))

      Resolution.Complete(
        Targets(
          bankIds = bankIds,
          deposits = deposits,
          firmLoans = firmLoans,
          consumerLoans = consumerLoans,
          mortgageLoans = mortgageLoans,
          govBonds = govBonds,
          reserves = reserves,
          corpBonds = corpBonds,
          openingCapitalProfiles = capitalProfiles.toVector,
        ),
      )

  private def runtimeRows(rows: Vector[OpeningBankBalanceProfileBridge.Row]): Vector[OpeningBankBalanceProfileBridge.Row] =
    val runtime = rows.filterNot(_.rowType == "sector_total")
    require(
      runtime.length == Banking.DefaultConfigs.length,
      s"Opening bank profile requires ${Banking.DefaultConfigs.length} runtime bank rows, got ${runtime.length}",
    )
    val byId    = runtime.map(row => row.bankId.toInt -> row).toMap
    require(byId.size == runtime.length, "Opening bank profile requires unique runtime bank_id values")

    Banking.DefaultConfigs.map: config =>
      val bankId = config.id.toInt
      val row    = byId.getOrElse(bankId, throw new IllegalStateException(s"Missing opening bank profile row for BankId $bankId"))
      require(
        row.runtimeBankName == config.name,
        s"Opening bank profile row $bankId names ${row.runtimeBankName}, expected ${config.name}",
      )
      row

  private def optionalPlnVector(
      column: String,
      rows: Vector[OpeningBankBalanceProfileBridge.Row],
      value: OpeningBankBalanceProfileBridge.Row => Option[BigDecimal],
  )(using SimParams): Option[Vector[PLN]] =
    optionalVector(column, rows, value).map(_.map(mPlnToRuntime))

  private def optionalMultiplierVector(
      column: String,
      rows: Vector[OpeningBankBalanceProfileBridge.Row],
      value: OpeningBankBalanceProfileBridge.Row => Option[BigDecimal],
  ): Option[Vector[Multiplier]] =
    optionalVector(column, rows, value).map(_.map(decimalToMultiplier))

  private def optionalVector(
      column: String,
      rows: Vector[OpeningBankBalanceProfileBridge.Row],
      value: OpeningBankBalanceProfileBridge.Row => Option[BigDecimal],
  ): Option[Vector[BigDecimal]] =
    val extracted = rows.map(value)
    if extracted.forall(_.isEmpty) then None
    else if extracted.forall(_.nonEmpty) then Some(extracted.flatten)
    else
      val missing = rows.zip(extracted).collect { case (row, None) => s"${row.runtimeBankName}.$column" }
      throw new IllegalStateException(s"Opening bank profile has partial $column coverage; missing ${missing.mkString(", ")}")

  private def mPlnToRuntime(value: BigDecimal)(using p: SimParams): PLN =
    val pln = value * BigDecimal(1000000L)
    val raw = (pln * FixedPointBase.ScaleDecimal)
      .setScale(0, RoundingMode.HALF_EVEN)
      .toLongExact
    PLN.fromRaw(raw) * p.gdpRatio

  private def decimalToMultiplier(value: BigDecimal): Multiplier =
    Multiplier.fromRaw(
      (value * FixedPointBase.ScaleDecimal)
        .setScale(0, RoundingMode.HALF_EVEN)
        .toLongExact,
    )

  private def requireClose(label: String, actual: PLN, expected: PLN): Unit =
    val delta     = (actual - expected).abs
    val tolerance = (expected * AggregateToleranceShare).max(AggregateToleranceFloor)
    require(
      delta <= tolerance,
      s"$label target sum $actual differs from model aggregate $expected by $delta, above tolerance $tolerance",
    )
