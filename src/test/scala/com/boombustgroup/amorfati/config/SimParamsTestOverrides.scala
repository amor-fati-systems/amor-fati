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
      banking = SimParams.defaults.banking.copy(
        initCapital = SimParams.defaults.banking.initCapital * Multiplier(2),
      ),
      equity = SimParams.defaults.equity.copy(
        issuanceMinSize = 1,
      ),
    )

end SimParamsTestOverrides
