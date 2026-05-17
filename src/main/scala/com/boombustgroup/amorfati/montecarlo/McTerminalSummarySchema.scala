package com.boombustgroup.amorfati.montecarlo

import com.boombustgroup.amorfati.agents.Banking
import com.boombustgroup.amorfati.agents.Banking.BankState
import com.boombustgroup.amorfati.agents.Firm
import com.boombustgroup.amorfati.agents.HhStatus
import com.boombustgroup.amorfati.agents.Household
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.flows.FlowSimulation
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState
import com.boombustgroup.amorfati.types.*

import java.io.File

private[montecarlo] enum McTerminalSummaryId:
  case Household, Banks, Firms

private[montecarlo] final case class McTerminalSummaryRows(seed: Long, rowsById: Map[McTerminalSummaryId, Vector[String]]):
  def rowsFor(id: McTerminalSummaryId): Vector[String] =
    rowsById.getOrElse(id, Vector.empty)

private[montecarlo] object McTerminalSummarySchema:

  private case class BankRow(bank: BankState, balances: LedgerFinancialState.BankBalances)
  private case class HouseholdRow(aggregates: Household.Aggregates, households: Vector[Household.State]):
    private lazy val employedWages: Vector[PLN] =
      households
        .flatMap: household =>
          household.status match
            case HhStatus.Employed(_, _, wage) => Some(wage)
            case _                             => None
        .sorted

    def meanMonthlyIncome: PLN =
      if households.nonEmpty then aggregates.totalIncome.divideBy(households.length) else PLN.Zero

    def meanEmployedWage: PLN =
      if employedWages.nonEmpty then employedWages.sumPln.divideBy(employedWages.length) else PLN.Zero

    def wageP10: PLN = percentile(employedWages, Share.decimal(10, 2))
    def wageP50: PLN = percentile(employedWages, Share.decimal(50, 2))
    def wageP90: PLN = percentile(employedWages, Share.decimal(90, 2))

  private case class FirmSizeCounts(living: Int, micro: Int, small: Int, medium: Int, large: Int):
    private def share(count: Int): Share =
      if living > 0 then Share.fraction(count, living) else Share.Zero

    def microShare: Share  = share(micro)
    def smallShare: Share  = share(small)
    def mediumShare: Share = share(medium)
    def largeShare: Share  = share(large)

  private def nplRatio(row: BankRow): Share =
    Banking.nplRatio(row.balances.firmLoan, row.bank.nplAmount)

  private def car(row: BankRow): Multiplier =
    Banking.capitalAdequacyRatio(row.bank.capital, row.balances.firmLoan, row.balances.consumerLoan, row.balances.corpBond)

  private[montecarlo] final case class SummarySpec(
      id: McTerminalSummaryId,
      outputFile: (File, McRunConfig) => File,
      csvSchema: McCsvSchema[String],
  )

  private val hhSchema: Vector[(String, HouseholdRow => String)] = Vector(
    ("HH_Employed", row => s"${row.aggregates.employed}"),
    ("HH_Unemployed", row => s"${row.aggregates.unemployed}"),
    ("HH_Retraining", row => s"${row.aggregates.retraining}"),
    ("HH_Bankrupt", row => s"${row.aggregates.bankrupt}"),
    ("MeanMonthlyIncome", row => row.meanMonthlyIncome.format(2)),
    ("MeanEmployedWage", row => row.meanEmployedWage.format(2)),
    ("WageP10", row => row.wageP10.format(2)),
    ("WageP50", row => row.wageP50.format(2)),
    ("WageP90", row => row.wageP90.format(2)),
    ("MeanSavings", row => row.aggregates.meanSavings.format(2)),
    ("MedianSavings", row => row.aggregates.medianSavings.format(2)),
    ("Gini_Individual", row => row.aggregates.giniIndividual.format(6)),
    ("Gini_Wealth", row => row.aggregates.giniWealth.format(6)),
    ("MeanSkill", row => row.aggregates.meanSkill.format(6)),
    ("MeanHealthPenalty", row => row.aggregates.meanHealthPenalty.format(6)),
    ("RetrainingAttempts", row => s"${row.aggregates.retrainingAttempts}"),
    ("RetrainingSuccesses", row => s"${row.aggregates.retrainingSuccesses}"),
    ("ConsumptionP10", row => row.aggregates.consumptionP10.format(2)),
    ("ConsumptionP50", row => row.aggregates.consumptionP50.format(2)),
    ("ConsumptionP90", row => row.aggregates.consumptionP90.format(2)),
    ("BankruptcyRate", row => row.aggregates.bankruptcyRate.format(6)),
    ("MeanMonthsToRuin", row => row.aggregates.meanMonthsToRuin.format(2)),
    ("PovertyRate_50pct", row => row.aggregates.povertyRate50.format(6)),
    ("PovertyRate_30pct", row => row.aggregates.povertyRate30.format(6)),
  )

  private val bankSchema: Vector[(String, BankRow => String)] = Vector(
    ("BankId", row => s"${row.bank.id}"),
    ("Deposits", row => row.balances.totalDeposits.format(2)),
    ("Loans", row => row.balances.firmLoan.format(2)),
    ("Capital", row => row.bank.capital.format(2)),
    ("NPL", row => nplRatio(row).format(6)),
    ("CAR", row => car(row).format(6)),
    ("GovBonds", row => (row.balances.govBondAfs + row.balances.govBondHtm).format(2)),
    ("InterbankNet", row => row.balances.interbankLoan.format(2)),
    ("Failed", row => s"${row.bank.failed}"),
  )

  private val firmSchema: Vector[(String, FirmSizeCounts => String)] = Vector(
    ("Firm_Living", summary => s"${summary.living}"),
    ("FirmSize_Micro", summary => s"${summary.micro}"),
    ("FirmSize_Small", summary => s"${summary.small}"),
    ("FirmSize_Medium", summary => s"${summary.medium}"),
    ("FirmSize_Large", summary => s"${summary.large}"),
    ("FirmSize_MicroShare", summary => summary.microShare.format(6)),
    ("FirmSize_SmallShare", summary => summary.smallShare.format(6)),
    ("FirmSize_MediumShare", summary => summary.mediumShare.format(6)),
    ("FirmSize_LargeShare", summary => summary.largeShare.format(6)),
  )

  private[montecarlo] val specs = Vector(
    // SummarySpec rows are pre-formatted by fromTerminalState, so McCsvSchema only
    // carries the header contract here and render is intentionally identity.
    SummarySpec(
      McTerminalSummaryId.Household,
      McOutputFiles.householdFile,
      McCsvSchema(
        header = "Seed;" + hhSchema.map(_._1).mkString(";"),
        render = identity,
      ),
    ),
    SummarySpec(
      McTerminalSummaryId.Banks,
      McOutputFiles.bankFile,
      McCsvSchema(
        header = "Seed;" + bankSchema.map(_._1).mkString(";"),
        render = identity,
      ),
    ),
    SummarySpec(
      McTerminalSummaryId.Firms,
      McOutputFiles.firmFile,
      McCsvSchema(
        header = "Seed;" + firmSchema.map(_._1).mkString(";"),
        render = identity,
      ),
    ),
  )

  def fromTerminalState(seed: Long, terminalState: FlowSimulation.SimState)(using SimParams): McTerminalSummaryRows =
    McTerminalSummaryRows(
      seed,
      Map(
        McTerminalSummaryId.Household -> Vector(renderHouseholdRow(seed, HouseholdRow(terminalState.householdAggregates, terminalState.households))),
        McTerminalSummaryId.Banks     -> terminalState.banks.map(bank =>
          renderBankRow(seed, BankRow(bank, terminalState.ledgerFinancialState.banks(bank.id.toInt))),
        ),
        McTerminalSummaryId.Firms     -> Vector(renderFirmRow(seed, terminalState.firms)),
      ),
    )

  private def renderHouseholdRow(seed: Long, row: HouseholdRow): String =
    s"$seed;" + hhSchema.map(_._2(row)).mkString(";")

  private def renderBankRow(seed: Long, row: BankRow): String =
    s"$seed;" + bankSchema.map(_._2(row)).mkString(";")

  private def renderFirmRow(seed: Long, firms: Vector[Firm.State])(using SimParams): String =
    val summary = firmSizeCounts(firms)
    s"$seed;" + firmSchema.map(_._2(summary)).mkString(";")

  private def firmSizeCounts(firms: Vector[Firm.State])(using SimParams): FirmSizeCounts =
    val living = firms.filter(Firm.isAlive)
    val counts = living.foldLeft(FirmSizeCounts(living = living.length, micro = 0, small = 0, medium = 0, large = 0)): (acc, firm) =>
      McFirmSizeClass.fromWorkerCount(Firm.workerCount(firm)) match
        case McFirmSizeClass.Micro  => acc.copy(micro = acc.micro + 1)
        case McFirmSizeClass.Small  => acc.copy(small = acc.small + 1)
        case McFirmSizeClass.Medium => acc.copy(medium = acc.medium + 1)
        case McFirmSizeClass.Large  => acc.copy(large = acc.large + 1)
    counts

  private def percentile(values: Vector[PLN], p: Share): PLN =
    if values.isEmpty then PLN.Zero
    else
      val idx = Math.min(values.length - 1, (values.length.toLong * p.toLong / com.boombustgroup.amorfati.fp.FixedPointBase.Scale).toInt)
      values(idx)
