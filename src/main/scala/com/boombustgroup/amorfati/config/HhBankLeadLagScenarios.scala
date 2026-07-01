package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.types.*

/** Diagnostic-only scenarios for HH-to-bank causal attribution.
  *
  * They keep household distress/default flows visible but selectively
  * neutralize the bank-capital consumer-credit loss channel.
  */
object HhBankLeadLagScenarios:

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
      interpretation = "Current production configuration.",
      params = Baseline,
    ),
    Spec(
      id = "no-consumer-npl-capital-hit",
      label = "No consumer NPL capital hit",
      interpretation =
        "Sets unsecured consumer-credit and personal-insolvency recoveries to 100%, leaving household default and bridge diagnostics visible.",
      params = Baseline.copy(
        household = Baseline.household.copy(
          ccNplRecovery = Share.One,
          ccInsolvencyRecovery = Share.One,
        ),
      ),
    ),
  )
