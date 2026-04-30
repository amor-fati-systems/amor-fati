package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.types.*

object SimParamsTestOverrides:

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
