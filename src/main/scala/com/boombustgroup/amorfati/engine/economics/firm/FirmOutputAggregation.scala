package com.boombustgroup.amorfati.engine.economics.firm

import com.boombustgroup.amorfati.types.*

/** Single-pass aggregates derived from per-firm outcomes for StepOutput. */
private[firm] object FirmOutputAggregation:

  def derive(fp: FirmProcessingResult, bonded: BondAbsorptionResult, nBanks: Int): OutcomeDerivedTotals =
    val perBankNewLoans      = Array.fill[PLN](nBanks)(PLN.Zero)
    val perBankFirmPrincipal = Array.fill[PLN](nBanks)(PLN.Zero)
    var automationTechLoans  = PLN.Zero
    var upgradeFailures      = 0
    var aiDebtTrap           = 0
    var newFullAi            = 0
    var newHybrid            = 0
    var realizedProfit       = PLN.Zero
    var stateOwnedProfit     = PLN.Zero

    val outcomes = fp.outcomes
    var i        = 0
    while i < outcomes.length do
      val outcome   = outcomes(i)
      val bankIndex = outcome.bankId.toInt
      val reverted  = bonded.bondReversionByFirm.getOrElse(outcome.firm.id, PLN.Zero)

      perBankNewLoans(bankIndex) = perBankNewLoans(bankIndex) + outcome.finalLoan + reverted
      perBankFirmPrincipal(bankIndex) = perBankFirmPrincipal(bankIndex) + outcome.principalRepaid
      automationTechLoans += FirmFinancing.automationTechLoanAmount(
        techBankLoan = outcome.techBankLoan,
        techBondAmt = outcome.techBondAmt,
        bondAmt = outcome.bondAmt,
        revertedBond = reverted,
      )
      if outcome.automationUpgradeFailure then upgradeFailures += 1
      if outcome.automationAiDebtTrap then aiDebtTrap += 1
      if outcome.automationNewFullAi then newFullAi += 1
      if outcome.automationNewHybrid then newHybrid += 1
      realizedProfit += outcome.realizedPostTaxProfit
      if outcome.firm.stateOwned then stateOwnedProfit += outcome.realizedPostTaxProfit
      i += 1

    OutcomeDerivedTotals(
      perBankNewLoans = perBankNewLoans.toVector,
      perBankFirmPrincipal = perBankFirmPrincipal.toVector,
      automationTechLoans = automationTechLoans,
      automationUpgradeFailures = upgradeFailures,
      automationAiDebtTrap = aiDebtTrap,
      automationNewFullAi = newFullAi,
      automationNewHybrid = newHybrid,
      realizedPostTaxProfit = realizedProfit,
      stateOwnedPostTaxProfit = stateOwnedProfit,
    )

private[firm] final case class OutcomeDerivedTotals(
    perBankNewLoans: Vector[PLN],
    perBankFirmPrincipal: Vector[PLN],
    automationTechLoans: PLN,
    automationUpgradeFailures: Int,
    automationAiDebtTrap: Int,
    automationNewFullAi: Int,
    automationNewHybrid: Int,
    realizedPostTaxProfit: PLN,
    stateOwnedPostTaxProfit: PLN,
)
