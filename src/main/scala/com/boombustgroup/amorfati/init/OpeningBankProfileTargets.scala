package com.boombustgroup.amorfati.init

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.config.{OpeningBankBalanceProfileBridge, SimParams}
import com.boombustgroup.amorfati.fp.FixedPointBase
import com.boombustgroup.amorfati.types.*

import scala.math.BigDecimal.RoundingMode

object OpeningBankProfileTargets:

  final case class Targets(
      bankIds: Vector[BankId],
      deposits: Vector[PLN],
      firmLoans: Vector[PLN],
      consumerLoans: Vector[PLN],
      mortgageLoans: Vector[PLN],
      govBonds: Vector[PLN],
      reserves: Vector[PLN],
      corpBonds: Vector[PLN],
      openingCapitalProfiles: Vector[Banking.OpeningCapitalProfile],
  ):
    val bankCount: Int = bankIds.length

    require(bankCount > 0, "Opening bank profile targets require at least one bank")
    require(
      bankIds == Banking.DefaultConfigs.map(_.id),
      s"Opening bank profile targets must follow DefaultConfigs BankId order ${Banking.DefaultConfigs.map(_.id.toInt).mkString("[", ",", "]")}",
    )
    require(
      deposits.length == bankCount &&
        firmLoans.length == bankCount &&
        consumerLoans.length == bankCount &&
        mortgageLoans.length == bankCount &&
        govBonds.length == bankCount &&
        reserves.length == bankCount &&
        corpBonds.length == bankCount &&
        openingCapitalProfiles.length == bankCount,
      "Opening bank profile targets require aligned per-bank vectors",
    )

  private val MandatoryColumns: Vector[(String, OpeningBankBalanceProfileBridge.Row => Option[BigDecimal])] = Vector(
    "deposits_m_pln"       -> (_.depositsMPln),
    "firm_loans_m_pln"     -> (_.firmLoansMPln),
    "consumer_loans_m_pln" -> (_.consumerLoansMPln),
    "mortgage_loans_m_pln" -> (_.mortgageLoansMPln),
    "gov_bonds_m_pln"      -> (_.govBondsMPln),
    "reserves_m_pln"       -> (_.reservesMPln),
    "corp_bonds_m_pln"     -> (_.corpBondsMPln),
    "own_funds_m_pln"      -> (_.ownFundsMPln),
  )

  private val RuntimeReadyBridgeStatuses: Set[String] = Set(
    "EMPIRICAL_RUNTIME_TARGET",
    "EMPIRICAL_PROXY_RUNTIME_TARGET",
    "RESIDUAL_RUNTIME_TARGET",
    "TEST_COMPLETE_RUNTIME_PROFILE",
  )

  private val AggregateToleranceFloor: PLN   = PLN(1000000)
  private val AggregateToleranceShare: Share = Share.decimal(1, 6)

  def fromBridgeRows(rows: Vector[OpeningBankBalanceProfileBridge.Row])(using p: SimParams): Targets =
    val orderedRows      = runtimeRows(rows)
    val runtimeReadyRows =
      orderedRows.filter(row => RuntimeReadyBridgeStatuses.contains(row.bridgeStatus))

    if runtimeReadyRows.isEmpty then throw new IllegalStateException("Opening bank profile has no runtime-ready bank targets")
    else if runtimeReadyRows.length != orderedRows.length then
      val nonReady = orderedRows.filterNot(row => RuntimeReadyBridgeStatuses.contains(row.bridgeStatus)).map(_.runtimeBankName)
      throw new IllegalStateException(
        s"Opening bank profile mixes runtime-ready and non-runtime-ready rows; non-runtime-ready rows: ${nonReady.mkString(", ")}",
      )
    else
      val missingMandatory =
        orderedRows.flatMap: row =>
          MandatoryColumns.collect:
            case (column, value) if value(row).isEmpty => s"${row.runtimeBankName}.$column"

      if missingMandatory.nonEmpty then
        throw new IllegalStateException(
          s"Opening bank profile has partial runtime stock targets; missing ${missingMandatory.mkString(", ")}",
        )
      else completeTargets(orderedRows)

  private def completeTargets(orderedRows: Vector[OpeningBankBalanceProfileBridge.Row])(using p: SimParams): Targets =
    val bankIds       = orderedRows.map(row => BankId(row.bankId.toInt))
    val residualIndex = residualBankIndex(orderedRows)
    val deposits      = closeResidual("opening bank deposits", orderedRows.map(row => mPlnToRuntime(row.depositsMPln.get)), p.banking.initDeposits, residualIndex)
    val firmLoans     = closeResidual("opening bank firm loans", orderedRows.map(row => mPlnToRuntime(row.firmLoansMPln.get)), p.banking.initLoans, residualIndex)
    val consumerLoans =
      closeResidual(
        "opening bank consumer loans",
        orderedRows.map(row => mPlnToRuntime(row.consumerLoansMPln.get)),
        p.banking.initConsumerLoans,
        residualIndex,
      )
    val mortgageLoans =
      closeResidual("opening bank mortgage loans", orderedRows.map(row => mPlnToRuntime(row.mortgageLoansMPln.get)), p.housing.initMortgage, residualIndex)
    val govBonds      =
      closeResidual("opening bank government bonds", orderedRows.map(row => mPlnToRuntime(row.govBondsMPln.get)), p.banking.initGovBonds, residualIndex)
    val reserves      =
      closeResidual(
        "opening bank reserves",
        orderedRows.map(row => mPlnToRuntime(row.reservesMPln.get)),
        p.banking.initDeposits * p.banking.reserveReq,
        residualIndex,
      )
    val corpBonds     =
      closeResidual(
        "opening bank corporate bonds",
        orderedRows.map(row => mPlnToRuntime(row.corpBondsMPln.get)),
        p.corpBond.initStock * p.corpBond.bankShare,
        residualIndex,
      )
    val ownFunds      = closeResidual("opening bank own funds", orderedRows.map(row => mPlnToRuntime(row.ownFundsMPln.get)), p.banking.initCapital, residualIndex)

    val capitalProfiles = bankIds.indices.map: i =>
      Banking.OpeningCapitalProfile(
        bankId = bankIds(i),
        ownFunds = ownFunds(i),
      )

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

  private def residualBankIndex(rows: Vector[OpeningBankBalanceProfileBridge.Row]): Int =
    val index = rows.indexWhere(_.rowType == "residual_bank")
    require(index >= 0, "Opening bank profile requires a residual_bank row for residual closure")
    index

  private def mPlnToRuntime(value: BigDecimal)(using p: SimParams): PLN =
    val pln = value * BigDecimal(1000000L)
    val raw = (pln * FixedPointBase.ScaleDecimal)
      .setScale(0, RoundingMode.HALF_EVEN)
      .toLongExact
    PLN.fromRaw(raw) * p.gdpRatio

  private def requireClose(label: String, actual: PLN, expected: PLN): Unit =
    val delta     = (actual - expected).abs
    val tolerance = (expected * AggregateToleranceShare).max(AggregateToleranceFloor)
    require(
      delta <= tolerance,
      s"$label target sum $actual differs from model aggregate $expected by $delta, above tolerance $tolerance",
    )

  private def closeResidual(label: String, values: Vector[PLN], expected: PLN, residualIndex: Int): Vector[PLN] =
    requireClose(label, values.sumPln, expected)
    val delta = expected - values.sumPln
    if delta == PLN.Zero then values
    else
      val closedResidual = values(residualIndex) + delta
      require(
        closedResidual >= PLN.Zero,
        s"$label residual closure would make residual bank negative: current=${values(residualIndex)} delta=$delta",
      )
      val closed         = values.updated(residualIndex, closedResidual)
      require(closed.sumPln == expected, s"$label residual closure failed: ${closed.sumPln} != $expected")
      closed
