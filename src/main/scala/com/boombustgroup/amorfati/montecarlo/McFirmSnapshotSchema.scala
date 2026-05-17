package com.boombustgroup.amorfati.montecarlo

import com.boombustgroup.amorfati.agents.{BankruptReason, Firm, TechState}
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.flows.FlowSimulation
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState

private[montecarlo] object McFirmSnapshotSchema:

  final case class Row(
      runId: String,
      seed: Long,
      month: ExecutionMonth,
      firm: Firm.State,
      sectorName: String,
      workers: Int,
      sizeClass: McFirmSizeClass,
      balances: LedgerFinancialState.FirmBalances,
  )

  private val columns: Vector[(String, Row => String)] = Vector(
    "RunId"            -> (row => text(row.runId)),
    "Seed"             -> (row => row.seed.toString),
    "Month"            -> (row => row.month.toInt.toString),
    "FirmId"           -> (row => row.firm.id.toInt.toString),
    "Sector"           -> (row => text(row.sectorName)),
    "Region"           -> (row => text(row.firm.region.toString)),
    "SizeClass"        -> (row => row.sizeClass.csvValue),
    "Workers"          -> (row => row.workers.toString),
    "TechState"        -> (row => techState(row.firm.tech)),
    "BankruptcyReason" -> (row => bankruptcyReason(row.firm.tech)),
    "DigitalReadiness" -> (row => row.firm.digitalReadiness.format(6)),
    "Cash"             -> (row => row.balances.cash.format(2)),
    "FirmLoan"         -> (row => row.balances.firmLoan.format(2)),
    "Equity"           -> (row => row.balances.equity.format(2)),
    "BankId"           -> (row => row.firm.bankId.toInt.toString),
    "RiskProfile"      -> (row => row.firm.riskProfile.format(6)),
    "InitialSize"      -> (row => row.firm.initialSize.toString),
    "CapitalStock"     -> (row => row.firm.capitalStock.format(2)),
    "Inventory"        -> (row => row.firm.inventory.format(2)),
    "GreenCapital"     -> (row => row.firm.greenCapital.format(2)),
    "ForeignOwned"     -> (row => row.firm.foreignOwned.toString),
    "StateOwned"       -> (row => row.firm.stateOwned.toString),
  )

  val header: String =
    columns.map(_._1).mkString(";")

  val csvSchema: McCsvSchema[Row] =
    McCsvSchema(
      header = header,
      render = row => columns.map(_._2(row)).mkString(";"),
    )

  def rows(runId: String, seed: Long, month: ExecutionMonth, state: FlowSimulation.SimState)(using SimParams): Vector[Row] =
    state.firms.map: firm =>
      val workers = Firm.workerCount(firm)
      Row(
        runId = runId,
        seed = seed,
        month = month,
        firm = firm,
        sectorName = sectorName(firm),
        workers = workers,
        sizeClass = McFirmSizeClass.fromWorkerCount(workers),
        balances = state.ledgerFinancialState.firms
          .lift(firm.id.toInt)
          .getOrElse:
            throw IllegalStateException(s"Missing ledger financial balances for firm ${firm.id.toInt}"),
      )

  private def sectorName(firm: Firm.State)(using p: SimParams): String =
    p.sectorDefs.lift(firm.sector.toInt).fold(s"sector-${firm.sector.toInt}")(_.name)

  private def techState(tech: TechState): String =
    tech match
      case TechState.Traditional(_) => "Traditional"
      case TechState.Hybrid(_, _)   => "Hybrid"
      case TechState.Automated(_)   => "Automated"
      case TechState.Bankrupt(_)    => "Bankrupt"

  private def bankruptcyReason(tech: TechState): String =
    tech match
      case TechState.Bankrupt(reason) => text(bankruptcyReasonName(reason))
      case _                          => ""

  private def bankruptcyReasonName(reason: BankruptReason): String =
    reason match
      case BankruptReason.AiDebtTrap          => "AiDebtTrap"
      case BankruptReason.HybridInsolvency    => "HybridInsolvency"
      case BankruptReason.AiImplFailure       => "AiImplFailure"
      case BankruptReason.HybridImplFailure   => "HybridImplFailure"
      case BankruptReason.LaborCostInsolvency => "LaborCostInsolvency"
      case BankruptReason.Other(msg)          => s"Other(${text(msg)})"

  private def text(value: String): String =
    value.replace(';', ',').replace('\n', ' ').replace('\r', ' ')
