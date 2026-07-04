package com.boombustgroup.amorfati.agents.household

import com.boombustgroup.amorfati.types.*

/** Aligned labor-market view used by household voluntary search and retraining.
  */
private[household] final case class HouseholdLaborTransitionContext(
    sectorWages: Vector[PLN],
    sectorVacancies: Vector[Int],
)

private[household] object HouseholdLaborTransitionContext:

  /** Builds the labor-transition context from the legacy public facade inputs.
    */
  def fromOptions(
      sectorWages: Option[Vector[PLN]],
      sectorVacancies: Option[Vector[Int]],
  ): Option[HouseholdLaborTransitionContext] =
    (sectorWages, sectorVacancies) match
      case (None, None)                      => None
      case (Some(wages), Some(vacancies))    =>
        require(
          wages.length == vacancies.length,
          s"Household labor transition context requires aligned sectorWages and sectorVacancies, got ${wages.length} wages and ${vacancies.length} vacancies",
        )
        Some(HouseholdLaborTransitionContext(wages, vacancies))
      case (Some(_), None) | (None, Some(_)) =>
        throw new IllegalArgumentException("Household labor transition context requires sectorWages and sectorVacancies to be provided together")
