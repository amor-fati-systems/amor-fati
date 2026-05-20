package com.boombustgroup.amorfati.montecarlo

import com.boombustgroup.amorfati.agents.{HhStatus, Household}
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.flows.FlowSimulation
import com.boombustgroup.amorfati.types.*

private[montecarlo] object McHouseholdShortfallCohortSchema:

  final case class Row(
      runId: String,
      seed: Long,
      month: ExecutionMonth,
      dimension: String,
      cohort: String,
      householdCount: Int,
      shortfallHouseholdCount: Int,
      shortfallHouseholdShare: Share,
      liquidityShortfallFinancing: PLN,
      shortfallShareOfMonth: Share,
      consumptionShortfall: PLN,
      rentArrears: PLN,
      mortgageArrears: PLN,
      consumerDebtArrears: PLN,
      temporaryOverdraft: PLN,
      consumerApprovedOrigination: PLN,
      consumerCreditDemand: PLN,
      consumerRejectedOrigination: PLN,
      consumerDebtService: PLN,
      consumerDefault: PLN,
      consumerLoanDefault: PLN,
      liquidityBridgeChargeOff: PLN,
      consumerPrincipal: PLN,
      openingDemandDeposit: PLN,
      closingDemandDeposit: PLN,
      openingConsumerLoan: PLN,
      closingConsumerLoan: PLN,
      monthlyIncome: PLN,
      consumption: PLN,
      unmetBasicConsumption: PLN,
      discretionaryConsumptionCompression: PLN,
      rent: PLN,
      mortgageDebtService: PLN,
      rentToIncome: Scalar,
      mortgageDebtServiceToIncome: Scalar,
      consumerDebtServiceToIncome: Scalar,
      consumerLoanToIncome: Scalar,
  )

  private val columns: Vector[(String, Row => String)] = Vector(
    "RunId"                               -> (row => text(row.runId)),
    "Seed"                                -> (row => row.seed.toString),
    "Month"                               -> (row => row.month.toInt.toString),
    "Dimension"                           -> (row => text(row.dimension)),
    "Cohort"                              -> (row => text(row.cohort)),
    "HouseholdCount"                      -> (row => row.householdCount.toString),
    "ShortfallHouseholdCount"             -> (row => row.shortfallHouseholdCount.toString),
    "ShortfallHouseholdShare"             -> (row => row.shortfallHouseholdShare.format(6)),
    "LiquidityShortfallFinancing"         -> (row => row.liquidityShortfallFinancing.format(2)),
    "ShortfallShareOfMonth"               -> (row => row.shortfallShareOfMonth.format(6)),
    "ConsumptionShortfall"                -> (row => row.consumptionShortfall.format(2)),
    "RentArrears"                         -> (row => row.rentArrears.format(2)),
    "MortgageArrears"                     -> (row => row.mortgageArrears.format(2)),
    "ConsumerDebtArrears"                 -> (row => row.consumerDebtArrears.format(2)),
    "TemporaryOverdraft"                  -> (row => row.temporaryOverdraft.format(2)),
    "ConsumerApprovedOrigination"         -> (row => row.consumerApprovedOrigination.format(2)),
    "ConsumerCreditDemand"                -> (row => row.consumerCreditDemand.format(2)),
    "ConsumerRejectedOrigination"         -> (row => row.consumerRejectedOrigination.format(2)),
    "ConsumerDebtService"                 -> (row => row.consumerDebtService.format(2)),
    "ConsumerDefault"                     -> (row => row.consumerDefault.format(2)),
    "ConsumerLoanDefault"                 -> (row => row.consumerLoanDefault.format(2)),
    "LiquidityBridgeChargeOff"            -> (row => row.liquidityBridgeChargeOff.format(2)),
    "ConsumerPrincipal"                   -> (row => row.consumerPrincipal.format(2)),
    "OpeningDemandDeposit"                -> (row => row.openingDemandDeposit.format(2)),
    "ClosingDemandDeposit"                -> (row => row.closingDemandDeposit.format(2)),
    "OpeningConsumerLoan"                 -> (row => row.openingConsumerLoan.format(2)),
    "ClosingConsumerLoan"                 -> (row => row.closingConsumerLoan.format(2)),
    "MonthlyIncome"                       -> (row => row.monthlyIncome.format(2)),
    "Consumption"                         -> (row => row.consumption.format(2)),
    "UnmetBasicConsumption"               -> (row => row.unmetBasicConsumption.format(2)),
    "DiscretionaryConsumptionCompression" -> (row => row.discretionaryConsumptionCompression.format(2)),
    "Rent"                                -> (row => row.rent.format(2)),
    "MortgageDebtService"                 -> (row => row.mortgageDebtService.format(2)),
    "RentToIncome"                        -> (row => row.rentToIncome.format(6)),
    "MortgageDebtServiceToIncome"         -> (row => row.mortgageDebtServiceToIncome.format(6)),
    "ConsumerDebtServiceToIncome"         -> (row => row.consumerDebtServiceToIncome.format(6)),
    "ClosingConsumerLoanToIncome"         -> (row => row.consumerLoanToIncome.format(6)),
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
  ): Vector[Row] =
    val snapshotRows   = McHouseholdSnapshotSchema.rows(runId, seed, month, state, monthlyFlows, McHouseholdSnapshotSelection.All)
    val monthShortfall = snapshotRows.map(_.monthlyFlow.liquidityShortfallFinancing).sumPln
    val incomeDeciles  = incomeDecileByIndex(snapshotRows)

    // Cohort dimensions are diagnostic cuts over the full household snapshot,
    // independent of the micro row selector used for the snapshot CSV.
    val dimensions: Vector[(String, (McHouseholdSnapshotSchema.Row, Int) => String)] = Vector(
      "All"                       -> ((_, _) => "All"),
      "Status"                    -> ((row, _) => status(row.household.status)),
      "FinancialDistressState"    -> ((row, _) => row.household.financialDistressState.toString),
      "Region"                    -> ((row, _) => row.household.region.toString),
      "ContractType"              -> ((row, _) => row.household.contractType.toString),
      "IncomeDecile"              -> ((_, idx) => incomeDeciles(idx)),
      "RentBurden"                -> ((row, _) => burden(row.monthlyFlow.rent, row.monthlyFlow.monthlyIncome)),
      "MortgageDebtServiceBurden" -> ((row, _) => burden(row.monthlyFlow.mortgageDebtService, row.monthlyFlow.monthlyIncome)),
      "ConsumerDebtServiceBurden" -> ((row, _) => burden(row.monthlyFlow.consumerDebtService, row.monthlyFlow.monthlyIncome)),
      "ClosingConsumerLoanBurden" -> ((row, _) => stockBurden(row.monthlyFlow.closingConsumerLoan, row.monthlyFlow.monthlyIncome)),
    )

    dimensions.flatMap: (dimension, cohortOf) =>
      snapshotRows.zipWithIndex
        .groupBy((row, idx) => cohortOf(row, idx))
        .toVector
        .sortBy(_._1)
        .map((cohort, rows) => aggregate(runId, seed, month, dimension, cohort, rows.map(_._1), monthShortfall))

  private def aggregate(
      runId: String,
      seed: Long,
      month: ExecutionMonth,
      dimension: String,
      cohort: String,
      rows: Vector[McHouseholdSnapshotSchema.Row],
      monthShortfall: PLN,
  ): Row =
    val householdCount      = rows.length
    val shortfallCount      = rows.count(_.monthlyFlow.liquidityShortfallFinancing > PLN.Zero)
    val flow                = rows.map(_.monthlyFlow)
    val balances            = rows.map(_.balances)
    val monthlyIncome       = flow.map(_.monthlyIncome).sumPln
    val rent                = flow.map(_.rent).sumPln
    val mortgageDebtService = flow.map(_.mortgageDebtService).sumPln
    val consumerDebtService = flow.map(_.consumerDebtService).sumPln
    val closingConsumerLoan = flow.map(_.closingConsumerLoan).sumPln
    Row(
      runId = runId,
      seed = seed,
      month = month,
      dimension = dimension,
      cohort = cohort,
      householdCount = householdCount,
      shortfallHouseholdCount = shortfallCount,
      shortfallHouseholdShare = Share.fraction(shortfallCount, householdCount),
      liquidityShortfallFinancing = flow.map(_.liquidityShortfallFinancing).sumPln,
      shortfallShareOfMonth =
        if monthShortfall > PLN.Zero then flow.map(_.liquidityShortfallFinancing).sumPln.ratioTo(monthShortfall).toShare else Share.Zero,
      consumptionShortfall = flow.map(_.consumptionShortfall).sumPln,
      rentArrears = flow.map(_.rentArrears).sumPln,
      mortgageArrears = flow.map(_.mortgageArrears).sumPln,
      consumerDebtArrears = flow.map(_.consumerDebtArrears).sumPln,
      temporaryOverdraft = flow.map(_.temporaryOverdraft).sumPln,
      consumerApprovedOrigination = flow.map(_.consumerApprovedOrigination).sumPln,
      consumerCreditDemand = flow.map(_.consumerCreditDemand).sumPln,
      consumerRejectedOrigination = flow.map(_.consumerRejectedOrigination).sumPln,
      consumerDebtService = consumerDebtService,
      consumerDefault = flow.map(_.consumerDefault).sumPln,
      consumerLoanDefault = flow.map(_.consumerLoanDefault).sumPln,
      liquidityBridgeChargeOff = flow.map(_.liquidityBridgeChargeOff).sumPln,
      consumerPrincipal = flow.map(_.consumerPrincipal).sumPln,
      openingDemandDeposit = flow.map(_.openingDemandDeposit).sumPln,
      closingDemandDeposit = balances.map(_.demandDeposit).sumPln,
      openingConsumerLoan = flow.map(_.openingConsumerLoan).sumPln,
      closingConsumerLoan = closingConsumerLoan,
      monthlyIncome = monthlyIncome,
      consumption = flow.map(_.consumption).sumPln,
      unmetBasicConsumption = flow.map(_.unmetBasicConsumption).sumPln,
      discretionaryConsumptionCompression = flow.map(_.discretionaryConsumptionCompression).sumPln,
      rent = rent,
      mortgageDebtService = mortgageDebtService,
      rentToIncome = ratio(rent, monthlyIncome),
      mortgageDebtServiceToIncome = ratio(mortgageDebtService, monthlyIncome),
      consumerDebtServiceToIncome = ratio(consumerDebtService, monthlyIncome),
      consumerLoanToIncome = ratio(closingConsumerLoan, monthlyIncome),
    )

  private def incomeDecileByIndex(rows: Vector[McHouseholdSnapshotSchema.Row]): Vector[String] =
    val labels = Array.fill(rows.length)("D01")
    rows.zipWithIndex
      .sortBy:
        case (row, _) => row.monthlyFlow.monthlyIncome
      .zipWithIndex
      .foreach:
        case ((_, originalIdx), rank) =>
          labels(originalIdx) = f"D${((rank.toLong * 10L) / rows.length.toLong).toInt + 1}%02d"
    labels.toVector

  private def burden(numerator: PLN, income: PLN): String =
    if numerator <= PLN.Zero then "None"
    else if income <= PLN.Zero then "NoIncome"
    else
      val value = numerator.ratioTo(income)
      if value <= Scalar.decimal(20, 2) then "00_20pct"
      else if value <= Scalar.decimal(40, 2) then "20_40pct"
      else if value <= Scalar.decimal(60, 2) then "40_60pct"
      else "60pct_plus"

  private def stockBurden(stock: PLN, income: PLN): String =
    if stock <= PLN.Zero then "None"
    else if income <= PLN.Zero then "NoIncome"
    else
      val value = stock.ratioTo(income)
      if value <= Scalar(3) then "00_03m_income"
      else if value <= Scalar(6) then "03_06m_income"
      else if value <= Scalar(12) then "06_12m_income"
      else "12m_plus_income"

  private def ratio(numerator: PLN, denominator: PLN): Scalar =
    if denominator > PLN.Zero then numerator.ratioTo(denominator) else Scalar.Zero

  private def status(status: HhStatus): String =
    status match
      case HhStatus.Employed(_, _, _)   => "Employed"
      case HhStatus.Unemployed(_)       => "Unemployed"
      case HhStatus.Retraining(_, _, _) => "Retraining"
      case HhStatus.Bankrupt            => "Bankrupt"

  private def text(value: String): String =
    value.replace(';', ',').replace('\n', ' ').replace('\r', ' ')
