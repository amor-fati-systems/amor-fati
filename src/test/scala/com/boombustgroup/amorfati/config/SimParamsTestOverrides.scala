package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.types.*

object SimParamsTestOverrides:

  def withIo(matrix: Vector[Vector[Share]], crossSectorSpillover: Share = Share.decimal(65, 2)): SimParams =
    SimParams.defaults.copy(
      io = SimParams.defaults.io.copy(
        matrix = matrix,
        crossSectorSpillover = crossSectorSpillover,
      ),
    )

  val voluntarySearchAlways: SimParams =
    SimParams.defaults.copy(
      labor = SimParams.defaults.labor.copy(
        voluntarySearchProb = Share.One,
      ),
    )

  val noTightLaborWagePressure: SimParams =
    SimParams.defaults.copy(
      labor = SimParams.defaults.labor.copy(
        tightLaborWageSensitivity = Coefficient.Zero,
      ),
    )

  def consumerCreditEligibility(rate: Share): SimParams =
    SimParams.defaults.copy(
      household = SimParams.defaults.household.copy(
        ccEligRate = rate,
      ),
    )

  def personalInsolvencyHazard(
      baseHazard: Share,
      maxHazard: Share,
      burdenWeight: Share = Share.Zero,
      minDistressMonths: Int = SimParams.defaults.household.personalInsolvencyMinDistressMonths,
      distressHorizonMonths: Int = SimParams.defaults.household.personalInsolvencyDistressMonths,
      restructuringOutstandingShare: Share = SimParams.defaults.household.ccRestructuringDefaultOutstandingShare,
      bankruptcyOutstandingShare: Share = SimParams.defaults.household.ccBankruptcyDefaultOutstandingShare,
  ): SimParams =
    SimParams.defaults.copy(
      household = SimParams.defaults.household.copy(
        personalInsolvencyMinDistressMonths = minDistressMonths,
        personalInsolvencyDistressMonths = distressHorizonMonths,
        personalInsolvencyBaseHazard = baseHazard,
        personalInsolvencyMaxHazard = maxHazard,
        personalInsolvencyBurdenHazardWeight = burdenWeight,
        ccRestructuringDefaultOutstandingShare = restructuringOutstandingShare,
        ccBankruptcyDefaultOutstandingShare = bankruptcyOutstandingShare,
      ),
    )

  def bankRiskWeights(firmLoan: Share, consumerLoan: Share): SimParams =
    SimParams.defaults.copy(
      banking = SimParams.defaults.banking.copy(
        firmLoanRiskWeight = firmLoan,
        consumerLoanRiskWeight = consumerLoan,
      ),
    )

  def bankPortfolioChoice(priceShare: Share, quantitySensitivity: Multiplier): SimParams =
    SimParams.defaults.copy(
      banking = SimParams.defaults.banking.copy(
        portfolioWedgePriceShare = priceShare,
        portfolioWedgeQuantitySensitivity = quantitySensitivity,
      ),
    )

  val pfronDeficit: SimParams =
    SimParams.defaults.copy(
      earmarked = SimParams.defaults.earmarked.copy(
        pfronMonthlyRevenue = PLN(1),
        pfronMonthlySpending = PLN(2),
      ),
    )

  val equityIssuanceFinancing: SimParams =
    SimParams.defaults.copy(
      equity = SimParams.defaults.equity.copy(
        issuanceMinSize = 1,
      ),
    )

end SimParamsTestOverrides
