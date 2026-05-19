package com.boombustgroup.amorfati.montecarlo

import com.boombustgroup.amorfati.agents.{HhStatus, Household}
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.flows.FlowSimulation
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.types.*

private[montecarlo] object McHouseholdSnapshotSchema:

  final case class Row(
      runId: String,
      seed: Long,
      month: ExecutionMonth,
      household: Household.State,
      balances: LedgerFinancialState.HouseholdBalances,
      monthlyFlow: Household.MonthlyFlow,
  )

  private val columns: Vector[(String, Row => String)] = Vector(
    "RunId"                       -> (row => text(row.runId)),
    "Seed"                        -> (row => row.seed.toString),
    "Month"                       -> (row => row.month.toInt.toString),
    "HouseholdId"                 -> (row => row.household.id.toInt.toString),
    "Status"                      -> (row => status(row.household.status)),
    "Region"                      -> (row => text(row.household.region.toString)),
    "ContractType"                -> (row => text(row.household.contractType.toString)),
    "BankId"                      -> (row => row.household.bankId.toInt.toString),
    "Wage"                        -> (row => wage(row.household.status).format(2)),
    "Rent"                        -> (row => row.household.monthlyRent.format(2)),
    "MPC"                         -> (row => row.household.mpc.format(6)),
    "Skill"                       -> (row => row.household.skill.format(6)),
    "HealthPenalty"               -> (row => row.household.healthPenalty.format(6)),
    "FinancialDistressMonths"     -> (row => row.household.financialDistressMonths.toString),
    "DemandDeposit"               -> (row => row.balances.demandDeposit.format(2)),
    "MortgageLoan"                -> (row => row.balances.mortgageLoan.format(2)),
    "ConsumerLoan"                -> (row => row.balances.consumerLoan.format(2)),
    "Equity"                      -> (row => row.balances.equity.format(2)),
    "PositiveDeposit"             -> (row => row.balances.demandDeposit.max(PLN.Zero).format(2)),
    "ImplicitOverdraft"           -> (row => (PLN.Zero - row.balances.demandDeposit).max(PLN.Zero).format(2)),
    "NetLiquidPosition"           -> (row => (row.balances.demandDeposit - row.balances.consumerLoan).format(2)),
    "NetFinancialPosition"        -> (row => (row.balances.demandDeposit + row.balances.equity - row.balances.mortgageLoan - row.balances.consumerLoan).format(2)),
    "OpeningDemandDeposit"        -> (row => row.monthlyFlow.openingDemandDeposit.format(2)),
    "OpeningConsumerLoan"         -> (row => row.monthlyFlow.openingConsumerLoan.format(2)),
    "MonthlyIncome"               -> (row => row.monthlyFlow.monthlyIncome.format(2)),
    "Consumption"                 -> (row => row.monthlyFlow.consumption.format(2)),
    "RentPaid"                    -> (row => row.monthlyFlow.rent.format(2)),
    "MortgageDebtService"         -> (row => row.monthlyFlow.mortgageDebtService.format(2)),
    "ConsumerApprovedOrigination" -> (row => row.monthlyFlow.consumerApprovedOrigination.format(2)),
    "LiquidityShortfallFinancing" -> (row => row.monthlyFlow.liquidityShortfallFinancing.format(2)),
    "ConsumerDebtService"         -> (row => row.monthlyFlow.consumerDebtService.format(2)),
    "ConsumerDefault"             -> (row => row.monthlyFlow.consumerDefault.format(2)),
    "ConsumerPrincipal"           -> (row => row.monthlyFlow.consumerPrincipal.format(2)),
    "ClosingConsumerLoan"         -> (row => row.monthlyFlow.closingConsumerLoan.format(2)),
  )

  val header: String =
    columns.map(_._1).mkString(";")

  val csvSchema: McCsvSchema[Row] =
    McCsvSchema(
      header = header,
      render = row => columns.map(_._2(row)).mkString(";"),
    )

  def rows(
      runId: String,
      seed: Long,
      month: ExecutionMonth,
      state: FlowSimulation.HouseholdSnapshotState,
      monthlyFlows: Vector[Household.MonthlyFlow],
      selection: McHouseholdSnapshotSelection,
  ): Vector[Row] =
    require(
      state.households.length == state.ledgerFinancialState.households.length,
      s"Household snapshot export requires aligned households and ledger balances, got ${state.households.length} households and ${state.ledgerFinancialState.households.length} balance rows",
    )
    require(
      state.households.length == monthlyFlows.length,
      s"Household snapshot export requires aligned households and monthly flow rows, got ${state.households.length} households and ${monthlyFlows.length} flow rows",
    )
    state.households
      .zip(state.ledgerFinancialState.households)
      .zip(monthlyFlows)
      .map: pair =>
        val ((household, balances), monthlyFlow) = pair
        require(
          household.id == monthlyFlow.householdId,
          s"Household snapshot export requires positional household-flow alignment, got household ${household.id.toInt} and flow ${monthlyFlow.householdId.toInt}",
        )
        Row(
          runId = runId,
          seed = seed,
          month = month,
          household = household,
          balances = balances,
          monthlyFlow = monthlyFlow,
        )
      .filter(row => selection.includes(row.balances.demandDeposit, row.monthlyFlow.liquidityShortfallFinancing))

  private def status(status: HhStatus): String =
    status match
      case HhStatus.Employed(_, _, _)   => "Employed"
      case HhStatus.Unemployed(_)       => "Unemployed"
      case HhStatus.Retraining(_, _, _) => "Retraining"
      case HhStatus.Bankrupt            => "Bankrupt"

  private def wage(status: HhStatus): PLN =
    status match
      case HhStatus.Employed(_, _, wage) => wage
      case _                             => PLN.Zero

  private def text(value: String): String =
    value.replace(';', ',').replace('\n', ' ').replace('\r', ' ')
