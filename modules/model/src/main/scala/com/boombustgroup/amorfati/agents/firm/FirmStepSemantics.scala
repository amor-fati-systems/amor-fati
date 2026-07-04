package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.agents.Firm.*
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.{OperationalSignals, World}
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Zero-cost timeline for one firm's monthly agent step.
  *
  * `At[A, P]` is only a compile-time phase tag. It makes the legal order of the
  * firm pipeline explicit while keeping the runtime representation equal to the
  * wrapped value.
  */
private[agents] object FirmStepSemantics:

  sealed trait Phase
  sealed trait Opening          extends Phase
  sealed trait DecisionSelected extends Phase
  sealed trait PrimaryExecuted  extends Phase
  sealed trait HiringSignaled   extends Phase
  sealed trait DebtSettled      extends Phase
  sealed trait OpeningTraced    extends Phase
  sealed trait GreenInvested    extends Phase
  sealed trait CapitalInvested  extends Phase
  sealed trait DigitalDrifted   extends Phase
  sealed trait InventorySettled extends Phase
  sealed trait FdiSettled       extends Phase
  sealed trait Settled          extends Phase
  sealed trait Audited          extends Phase
  sealed trait Closed           extends Phase

  opaque type At[+A, P <: Phase] = A

  private inline def wrap[A, P <: Phase](value: A): At[A, P] = value

  private inline def unwrap[A, P <: Phase](staged: At[A, P]): A = staged

  /** Firm-local state and same-month world boundary visible at opening. */
  final case class OpeningState(
      firm: State,
      financialStocks: FinancialStocks,
      world: World,
      executionMonth: ExecutionMonth,
      operationalSignals: OperationalSignals,
      corpBondDebt: PLN,
  )

  /** Credit-pricing and bank-approval boundary used by firm decisions. */
  final case class CreditSurface(
      lendRate: Rate,
      bankCreditDecision: PLN => CreditDecision,
  )

  /** Stochastic and peer context for this firm decision. */
  final case class DecisionEnvironment(
      allFirms: Vector[State],
      rng: RandomStream,
      traceDecision: Boolean,
  )

  /** Complete opening boundary for processing one firm during one execution
    * month.
    */
  final case class Input(
      opening: OpeningState,
      credit: CreditSurface,
      environment: DecisionEnvironment,
  )

  type OpeningInput = At[Input, Opening]

  inline def opening(input: Input): OpeningInput =
    wrap[Input, Opening](input)

  extension (opening: OpeningInput)
    inline def input: Input =
      unwrap(opening)

  type SelectedDecision = At[DecisionWithAudit, DecisionSelected]

  inline def selectedDecision(decision: DecisionWithAudit): SelectedDecision =
    wrap[DecisionWithAudit, DecisionSelected](decision)

  extension (selected: SelectedDecision)
    inline def decisionWithAudit: DecisionWithAudit =
      unwrap(selected)

  type PrimaryExecution    = At[Result, PrimaryExecuted]
  type HiringSignalUpdate  = At[Result, HiringSignaled]
  type DebtSettlement      = At[Result, DebtSettled]
  type OpeningTrace        = At[Result, OpeningTraced]
  type GreenInvestment     = At[Result, GreenInvested]
  type CapitalInvestment   = At[Result, CapitalInvested]
  type DigitalDrift        = At[Result, DigitalDrifted]
  type InventorySettlement = At[Result, InventorySettled]
  type FdiSettlement       = At[Result, FdiSettled]
  type SettledResult       = At[Result, Settled]
  type AuditedResult       = At[Result, Audited]
  type ClosedResult        = At[Result, Closed]

  inline def primaryExecution(result: Result): PrimaryExecution =
    wrap[Result, PrimaryExecuted](result)

  inline def hiringSignalUpdate(result: Result): HiringSignalUpdate =
    wrap[Result, HiringSignaled](result)

  inline def debtSettlement(result: Result): DebtSettlement =
    wrap[Result, DebtSettled](result)

  inline def openingTrace(result: Result): OpeningTrace =
    wrap[Result, OpeningTraced](result)

  inline def greenInvestment(result: Result): GreenInvestment =
    wrap[Result, GreenInvested](result)

  inline def capitalInvestment(result: Result): CapitalInvestment =
    wrap[Result, CapitalInvested](result)

  inline def digitalDrift(result: Result): DigitalDrift =
    wrap[Result, DigitalDrifted](result)

  inline def inventorySettlement(result: Result): InventorySettlement =
    wrap[Result, InventorySettled](result)

  inline def fdiSettlement(result: Result): FdiSettlement =
    wrap[Result, FdiSettled](result)

  inline def settledResult(result: Result): SettledResult =
    wrap[Result, Settled](result)

  inline def auditedResult(result: Result): AuditedResult =
    wrap[Result, Audited](result)

  inline def closedResult(result: Result): ClosedResult =
    wrap[Result, Closed](result)

  extension [P <: Phase](staged: At[Result, P])
    inline def result: Result =
      unwrap(staged)
