package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.agents.{BankRates, Household, PerBankFlow}
import com.boombustgroup.amorfati.agents.household.HouseholdStepAccumulator.StepTotals
import com.boombustgroup.amorfati.agents.household.HouseholdStepTypes.*
import com.boombustgroup.amorfati.engine.World
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*

/** Zero-cost timeline for the household monthly batch step.
  *
  * `At[A, P]` is only a compile-time phase tag. It makes household execution
  * order explicit while preserving the same runtime representation as the
  * wrapped value.
  */
private[household] object HouseholdStepSemantics:

  sealed trait Phase
  sealed trait Opening       extends Phase
  sealed trait Prepared      extends Phase
  sealed trait RowsProcessed extends Phase
  sealed trait Aggregated    extends Phase
  sealed trait Validated     extends Phase
  sealed trait Closed        extends Phase

  opaque type At[+A, P <: Phase] = A

  private inline def wrap[A, P <: Phase](value: A): At[A, P] = value

  private inline def unwrap[A, P <: Phase](staged: At[A, P]): A = staged

  /** Household state, financial stocks, and same-month world visible at
    * opening.
    */
  final case class OpeningState(
      households: Vector[Household.State],
      financialStocks: Vector[Household.FinancialStocks],
      world: World,
  )

  /** Wage, consumption-import, equity-return, and labor-market boundary used by
    * the household month.
    */
  final case class MarketSurface(
      marketWage: PLN,
      reservationWage: PLN,
      importAdj: Share,
      equityIndexReturn: Rate,
      sectorWages: Option[Vector[PLN]],
      sectorVacancies: Option[Vector[Int]],
  )

  /** Bank-rate and consumer-credit underwriting boundary. */
  final case class CreditSurface(
      nBanks: Int,
      bankRates: Option[BankRates],
      consumerCreditGate: Option[Household.ConsumerCreditGate],
  )

  /** Stochastic boundary consumed by household labor and credit transitions. */
  final case class StochasticSurface(rng: RandomStream)

  /** Complete opening boundary for processing one household batch during one
    * execution month.
    */
  final case class Input(
      opening: OpeningState,
      market: MarketSurface,
      credit: CreditSurface,
      stochastic: StochasticSurface,
  )

  /** Validated opening plus derived context shared by all rows. */
  final case class PreparedMonth(
      input: Input,
      distressedIds: java.util.BitSet,
      laborContext: Option[HouseholdLaborTransitionContext],
  )

  /** Aligned row buffers and exact flow totals after per-household execution.
    */
  final case class ProcessedRows(
      input: Input,
      updatedRows: Array[Household.State],
      stockRows: Array[Household.FinancialStocks],
      monthlyRows: Array[Household.MonthlyFlow],
      bankedFlows: Vector[BankedMonthlyResult],
      totals: StepTotals,
  )

  /** Public household aggregates and diagnostics after row buffers are closed
    * into immutable vectors.
    */
  final case class AggregatedRows(
      updated: Vector[Household.State],
      stocks: Vector[Household.FinancialStocks],
      monthly: Vector[Household.MonthlyFlow],
      aggregates: Household.Aggregates,
      perBankFlows: Option[Vector[PerBankFlow]],
  )

  type OpeningInput = At[Input, Opening]

  inline def opening(input: Input): OpeningInput =
    wrap[Input, Opening](input)

  extension (opening: OpeningInput)
    inline def input: Input =
      unwrap(opening)

  type PreparedInput = At[PreparedMonth, Prepared]

  inline def preparedMonth(prepared: PreparedMonth): PreparedInput =
    wrap[PreparedMonth, Prepared](prepared)

  extension (prepared: PreparedInput)
    inline def month: PreparedMonth =
      unwrap(prepared)

  type ProcessedHouseholds = At[ProcessedRows, RowsProcessed]

  inline def processedHouseholds(processed: ProcessedRows): ProcessedHouseholds =
    wrap[ProcessedRows, RowsProcessed](processed)

  extension (processed: ProcessedHouseholds)
    inline def rows: ProcessedRows =
      unwrap(processed)

  type AggregatedHouseholds = At[AggregatedRows, Aggregated]

  inline def aggregatedHouseholds(aggregated: AggregatedRows): AggregatedHouseholds =
    wrap[AggregatedRows, Aggregated](aggregated)

  extension (aggregated: AggregatedHouseholds)
    inline def rows: AggregatedRows =
      unwrap(aggregated)

  type ValidatedHouseholds = At[AggregatedRows, Validated]

  inline def validatedHouseholds(aggregated: AggregatedRows): ValidatedHouseholds =
    wrap[AggregatedRows, Validated](aggregated)

  extension (validated: ValidatedHouseholds)
    inline def validatedRows: AggregatedRows =
      unwrap(validated)

  type ClosedResult = At[Household.StepResult, Closed]

  inline def closedResult(result: Household.StepResult): ClosedResult =
    wrap[Household.StepResult, Closed](result)

  extension (closed: ClosedResult)
    inline def result: Household.StepResult =
      unwrap(closed)
