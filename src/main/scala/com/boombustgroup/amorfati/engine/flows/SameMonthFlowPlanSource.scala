package com.boombustgroup.amorfati.engine.flows

import com.boombustgroup.amorfati.agents.{Banking, SocialSecurity}
import com.boombustgroup.amorfati.engine.MonthExecution
import com.boombustgroup.amorfati.engine.ledger.LedgerFinancialState

/** Named same-month source surfaces used to build the runtime flow plan.
  *
  * `MonthExecution` remains the economics transcript. The other fields are
  * deliberately narrow opening-boundary facts needed only for translating that
  * transcript into `MonthlyCalculus`.
  */
private[flows] final case class SameMonthFlowPlanSource(
    openingFinancial: SameMonthFlowPlanSource.OpeningFinancialBoundary,
    execution: MonthExecution,
    laborOpening: SameMonthFlowPlanSource.LaborOpeningBoundary,
    firmDemography: SameMonthFlowPlanSource.FirmDemographyBoundary,
)

private[flows] object SameMonthFlowPlanSource:

  /** Opening ledger-owned financial state and operational bank rows. */
  final case class OpeningFinancialBoundary(
      ledger: LedgerFinancialState,
      banks: Vector[Banking.BankState],
  )

  /** Opening labor/payroll facts pinned to the month-t flow boundary. */
  final case class LaborOpeningBoundary(
      payroll: SocialSecurity.PayrollBase,
      retirees: Int,
      workingAgePopulation: Int,
  )

  /** Firm-demography facts produced by same-month firm processing. */
  final case class FirmDemographyBoundary(
      bankruptFirms: Int,
      averageFirmWorkers: Int,
  )
