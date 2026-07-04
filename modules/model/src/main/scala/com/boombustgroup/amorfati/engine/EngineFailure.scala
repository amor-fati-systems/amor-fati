package com.boombustgroup.amorfati.engine

/** Stable engine failure taxonomy for central runtime boundaries.
  *
  * The engine remains fail-fast, but failures that cross simulation,
  * diagnostics, or Monte Carlo boundaries should carry a machine-readable
  * category instead of requiring callers to parse exception strings.
  */
enum EngineFailureCategory(val code: String):
  case InvariantViolation            extends EngineFailureCategory("invariant_violation")
  case RuntimeLedgerExecutionFailure extends EngineFailureCategory("runtime_ledger_execution_failure")
  case CalibrationInvalidity         extends EngineFailureCategory("calibration_invalidity")
  case ScenarioInvalidity            extends EngineFailureCategory("scenario_invalidity")
  case UnsupportedTopology           extends EngineFailureCategory("unsupported_topology")
  case OwnershipContractViolation    extends EngineFailureCategory("ownership_contract_violation")

/** Fail-fast exception carrying a structured engine failure category. */
final case class EngineFailure(
    category: EngineFailureCategory,
    boundary: String,
    details: String,
    cause: Option[Throwable] = None,
) extends RuntimeException(EngineFailure.message(category, boundary, details), cause.orNull):

  def categoryCode: String =
    category.code

  def diagnosticMessage: String =
    s"Engine failure category=${category.code} boundary=$boundary: $details"

object EngineFailure:

  def invariantViolation(boundary: String, details: String): EngineFailure =
    EngineFailure(EngineFailureCategory.InvariantViolation, boundary, details)

  def runtimeLedgerExecution(boundary: String, details: String): EngineFailure =
    EngineFailure(EngineFailureCategory.RuntimeLedgerExecutionFailure, boundary, details)

  def calibrationInvalidity(boundary: String, details: String): EngineFailure =
    EngineFailure(EngineFailureCategory.CalibrationInvalidity, boundary, details)

  def scenarioInvalidity(boundary: String, details: String): EngineFailure =
    EngineFailure(EngineFailureCategory.ScenarioInvalidity, boundary, details)

  def unsupportedTopology(boundary: String, details: String): EngineFailure =
    EngineFailure(EngineFailureCategory.UnsupportedTopology, boundary, details)

  def ownershipContractViolation(boundary: String, details: String): EngineFailure =
    EngineFailure(EngineFailureCategory.OwnershipContractViolation, boundary, details)

  def ensure(condition: Boolean, category: EngineFailureCategory, boundary: String, details: => String): Unit =
    if !condition then throw EngineFailure(category, boundary, details)

  def fromThrowable(error: Throwable): Option[EngineFailure] =
    if error == null then None
    else
      error match
        case failure: EngineFailure                                      => Some(failure)
        case other if other.getCause != null && other.getCause.ne(other) =>
          fromThrowable(other.getCause)
        case _                                                           => None

  def firstIn(errors: IterableOnce[Throwable]): Option[EngineFailure] =
    errors.iterator.flatMap(fromThrowable).nextOption

  private def message(category: EngineFailureCategory, boundary: String, details: String): String =
    s"[${category.code}] $boundary: $details"
