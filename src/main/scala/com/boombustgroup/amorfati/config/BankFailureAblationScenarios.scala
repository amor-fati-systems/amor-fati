package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.types.*

/** Diagnostic-only SimParams variants for bank-failure root-cause ablations.
  *
  * The variants live in `config` because `SimParams.copy` is intentionally
  * package-scoped. They are not production scenarios.
  */
object BankFailureAblationScenarios:

  final case class Spec(
      id: String,
      label: String,
      interpretation: String,
      params: SimParams,
  )

  private val Baseline = SimParams.defaults

  val all: Vector[Spec] = Vector(
    Spec(
      id = "baseline",
      label = "Baseline",
      interpretation = "Current production configuration after the opening bank calibration fixes.",
      params = Baseline,
    ),
    Spec(
      id = "no-ecl-stress-migration",
      label = "No ECL stress migration",
      interpretation = "Keeps realized defaults, but removes macro-driven Stage 1 to Stage 2 ECL migration.",
      params = Baseline.copy(
        banking = Baseline.banking.copy(
          eclMigrationSensitivity = Coefficient.Zero,
          eclGdpSensitivity = Coefficient.Zero,
          eclMaxMigration = Share.Zero,
        ),
      ),
    ),
    Spec(
      id = "no-realized-credit-loss-capital-hit",
      label = "No realized credit-loss capital hit",
      interpretation = "Sets firm, mortgage, consumer-credit, and corporate-bond recoveries to 100%, while leaving ECL provisioning active.",
      params = Baseline.copy(
        banking = Baseline.banking.copy(loanRecovery = Share.One),
        household = Baseline.household.copy(ccNplRecovery = Share.One),
        housing = Baseline.housing.copy(mortgageRecovery = Share.One),
        corpBond = Baseline.corpBond.copy(recovery = Share.One),
      ),
    ),
    Spec(
      id = "no-resolution-feedback",
      label = "No resolution feedback",
      interpretation = "Neutralizes bail-in haircuts, failure-panic switching, CAR-driven deposit flight, and interbank contagion losses.",
      params = Baseline.copy(
        banking = Baseline.banking.copy(
          bailInDepositHaircut = Share.Zero,
          depositPanicRate = Share.Zero,
          depositFlightSensitivity = Coefficient.Zero,
          interbankRecoveryRate = Share.One,
        ),
      ),
    ),
    Spec(
      id = "initial-capital-150pct",
      label = "Initial bank capital 150%",
      interpretation = "Raises opening regulatory capital by 50% to test whether failures are mostly a capital-buffer calibration problem.",
      params = Baseline.copy(
        banking = Baseline.banking.copy(initCapital = Baseline.banking.initCapital * Multiplier.decimal(150, 2)),
      ),
    ),
  )
