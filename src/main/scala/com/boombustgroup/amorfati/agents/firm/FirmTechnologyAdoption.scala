package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.agents.{BankruptReason, TechState}
import com.boombustgroup.amorfati.agents.Firm.*
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.SimulationMonth.ExecutionMonth
import com.boombustgroup.amorfati.engine.{OperationalSignals, World}
import com.boombustgroup.amorfati.random.RandomStream
import com.boombustgroup.amorfati.types.*
import com.boombustgroup.amorfati.agents.firm.FirmCalibration.*
import com.boombustgroup.amorfati.agents.firm.FirmCreditAudit.*
import com.boombustgroup.amorfati.agents.firm.FirmOperatingDecision.*

/** Technology-adoption decision surface for firms.
  *
  * Owns AI/hybrid feasibility, adoption probabilities, implementation rolls,
  * and the audit merge order for technology-credit candidates. Operating
  * survival fallback remains delegated to [[FirmOperatingDecision]].
  */
private[agents] object FirmTechnologyAdoption:

  /** Hybrid firm: attempt full-AI upgrade, else survive/downsize/bankrupt. */
  private[agents] def decideHybrid(
      firm: State,
      financialStocks: FinancialStocks,
      w: World,
      executionMonth: ExecutionMonth,
      operationalSignals: OperationalSignals,
      lendRate: Rate,
      bankCreditDecision: PLN => CreditDecision,
      workers: Int,
      aiEff: Multiplier,
      rng: RandomStream,
      corpBondDebt: PLN,
  )(using p: SimParams): DecisionWithAudit =
    val pnl    = computePnL(
      firm,
      financialStocks,
      w.householdMarket.marketWage,
      operationalSignals.sectorDemandMult(firm.sector.toInt),
      w.priceLevel,
      w.external.gvc.importCostIndex,
      w.external.gvc.commodityPriceIndex,
      lendRate,
      executionMonth,
      corpBondDebt,
      w.real.productivityIndex,
    )
    val ready2 = (firm.digitalReadiness + HybridMonthlyDrDrift).min(Share.One)

    if isInStartup(firm) then
      return DecisionWithAudit(
        startupFallbackDecision(firm, financialStocks, pnl, workers, w => TechState.Hybrid(w, aiEff), w.householdMarket.marketWage),
        DecisionAudit(),
      )

    val upCapex    = computeAiCapex(firm) * HybridToFullCapexMul
    val upLoan     = upCapex * FullAiLoanShare
    val upDown     = upCapex * FullAiDownShare
    val upCost     = estimateMonthlyCost(
      firm,
      financialStocks,
      p.firm.aiOpex,
      skeletonCrew(firm),
      upLoan,
      w.householdMarket.marketWage,
      lendRate,
      w.priceLevel,
      w.external.gvc.importCostIndex,
    )
    val profitable = pnl.costs > upCost * FullAiProfitMargin
    val canPay     = financialStocks.cash > upDown
    val ready      = firm.digitalReadiness >= p.firm.fullAiReadinessMin
    val bankCredit = bankCreditDecision(upLoan)
    val candidate  = UpgradeCandidate(upCapex, upLoan, upDown, profitable, canPay, ready, bankCredit)

    val prob: Share =
      if candidate.feasible then ((firm.riskProfile * RiskWeightHybUpgrade) + (w.real.automationRatio * AutoRatioWeight)) * firm.digitalReadiness
      else Share.Zero
    val audit       = DecisionAudit(
      fullAiFeasible = Some(candidate.feasible),
      fullAiAdoptionProbability = Some(prob.min(Share.One)),
    ).merge(fullAiBankAudit(bankCredit))
      .merge(techCandidateCreditAudit(candidate))
      .merge(rejectedTechCandidateTraceAudit(candidate, DecisionTrace.DecisionType.FullAiUpgrade))
    val roll        = Share.random(rng)

    if roll < prob then
      val effDraw = Scalar.randomBetween(HybToFullEffMin, HybToFullEffMax, rng)
      val eff     = Multiplier.One + (effDraw * firm.digitalReadiness.toScalar).toMultiplier
      DecisionWithAudit(
        Decision.Upgrade(pnl, TechState.Automated(eff), upCapex, upLoan, upDown, drUpdate = Some(ready2)),
        audit
          .merge(
            selectedTechBankAudit(
              bankCredit,
              upLoan,
              DecisionTrace.DecisionType.FullAiUpgrade,
              DecisionTrace.TechCreditSource.SelectedUpgrade,
            ),
          )
          .copy(adoptionRoll = Some(roll), upgradeEfficiencyDraw = Some(effDraw), upgradeEfficiencyMultiplier = Some(eff)),
      )
    else
      val fallback = fallbackDecision(
        firm,
        financialStocks,
        pnl,
        w,
        operationalSignals,
        workers,
        rng,
        nextTech = workers => TechState.Hybrid(workers, aiEff),
        drUpdate = Some(ready2),
        allowDigiInvest = false,
      )
      DecisionWithAudit(fallback.decision, audit.copy(adoptionRoll = Some(roll)).merge(fallback.audit))

  /** Traditional firm: evaluate full-AI, hybrid, downsize, digital invest, or
    * survive/bankrupt.
    */
  private[agents] def decideTraditional(
      firm: State,
      financialStocks: FinancialStocks,
      w: World,
      executionMonth: ExecutionMonth,
      operationalSignals: OperationalSignals,
      lendRate: Rate,
      bankCreditDecision: PLN => CreditDecision,
      allFirms: Vector[State],
      workers: Int,
      rng: RandomStream,
      corpBondDebt: PLN,
  )(using p: SimParams): DecisionWithAudit =
    val pnl           = computePnL(
      firm,
      financialStocks,
      w.householdMarket.marketWage,
      operationalSignals.sectorDemandMult(firm.sector.toInt),
      w.priceLevel,
      w.external.gvc.importCostIndex,
      w.external.gvc.commodityPriceIndex,
      lendRate,
      executionMonth,
      corpBondDebt,
      w.real.productivityIndex,
    )
    if isInStartup(firm) then
      return DecisionWithAudit(
        startupFallbackDecision(firm, financialStocks, pnl, workers, TechState.Traditional(_), w.householdMarket.marketWage),
        DecisionAudit(),
      )
    val ai            = evaluateFullAi(firm, financialStocks, pnl, w, lendRate, bankCreditDecision)
    val (hyb, hWkrs)  = evaluateHybrid(firm, financialStocks, pnl, workers, w, lendRate, bankCreditDecision)
    val (pFull, pHyb) = adoptionProbabilities(firm, pnl, ai, hyb, executionMonth, w, allFirms)
    val roll          = Share.random(rng)
    val baseAudit     = DecisionAudit(
      fullAiFeasible = Some(ai.feasible),
      hybridFeasible = Some(hyb.feasible),
      fullAiAdoptionProbability = Some(pFull),
      hybridAdoptionProbability = Some(pHyb),
      adoptionRoll = Some(roll),
    ).merge(fullAiBankAudit(ai.credit))
      .merge(hybridBankAudit(hyb.credit))
      .merge(techCandidateCreditAudit(ai))
      .merge(techCandidateCreditAudit(hyb))
      .merge(primaryRejectedTechCandidateTraceAudit(ai, hyb))

    if roll < pFull then
      val upgrade = rollFullAiUpgrade(firm, pnl, ai, rng)
      DecisionWithAudit(
        upgrade.decision,
        baseAudit
          .merge(
            selectedTechBankAudit(
              ai.credit,
              ai.loan,
              DecisionTrace.DecisionType.FullAiUpgrade,
              DecisionTrace.TechCreditSource.SelectedUpgrade,
            ),
          )
          .merge(upgrade.audit),
      )
    else if roll < pFull + pHyb then
      val upgrade = rollHybridUpgrade(firm, pnl, hyb, hWkrs, rng)
      DecisionWithAudit(
        upgrade.decision,
        baseAudit
          .merge(
            selectedTechBankAudit(
              hyb.credit,
              hyb.loan,
              DecisionTrace.DecisionType.HybridUpgrade,
              DecisionTrace.TechCreditSource.SelectedUpgrade,
            ),
          )
          .merge(upgrade.audit),
      )
    else
      val fallback = fallbackDecision(firm, financialStocks, pnl, w, operationalSignals, workers, rng, nextTech = TechState.Traditional(_))
      DecisionWithAudit(fallback.decision, baseAudit.merge(fallback.audit))

  /** Adoption willingness under uncertainty and local demonstration effects. */
  private[amorfati] def adoptionWillingnessMultiplier(month: ExecutionMonth, localAuto: Share)(using p: SimParams): Share =
    val elapsedMonths = month.toInt - 1
    val rampFrac      = Scalar.fraction(elapsedMonths, p.firm.adoptionRampMonths).clamp(Scalar.Zero, Scalar.One).toShare
    val baseLevel     = UncertaintyBase + UncertaintySlope * rampFrac
    val demoBoost     =
      if localAuto > p.firm.demoEffectThresh then p.firm.demoEffectBoost * (localAuto - p.firm.demoEffectThresh)
      else Share.Zero
    (baseLevel + demoBoost).min(Share.One)

  /** Estimate monthly operating cost for a hypothetical tech configuration. */
  private def estimateMonthlyCost(
      firm: State,
      financialStocks: FinancialStocks,
      opex: PLN,
      laborWorkers: Int,
      additionalDebt: PLN,
      wage: PLN,
      lendRate: Rate,
      domesticPrice: PriceIndex,
      importPrice: PriceIndex,
  )(using p: SimParams): PLN =
    val opexSizeFactor  = Scalar.fraction(firm.initialSize, p.pop.workersPerFirm).pow(OpexSizeExponent).toMultiplier
    val otherSizeFactor = Scalar.fraction(firm.initialSize, p.pop.workersPerFirm).toMultiplier
    val wMult           = effectiveWageMult(firm.sector)
    opex * ((domesticPrice.toMultiplier * OpexDomesticShare + importPrice.toMultiplier * OpexImportShare) * opexSizeFactor) +
      (financialStocks.firmLoan + additionalDebt) * lendRate.monthly +
      laborWorkers * (wage * wMult) +
      p.firm.otherCosts * (domesticPrice.toMultiplier * otherSizeFactor)

  /** Evaluate full-AI upgrade feasibility for a traditional firm. */
  private def evaluateFullAi(
      firm: State,
      financialStocks: FinancialStocks,
      pnl: PnL,
      w: World,
      lendRate: Rate,
      bankCreditDecision: PLN => CreditDecision,
  )(using p: SimParams): UpgradeCandidate =
    val capex = computeAiCapex(firm)
    val loan  = capex * FullAiLoanShare
    val down  = capex * FullAiDownShare
    val cost  =
      estimateMonthlyCost(
        firm,
        financialStocks,
        p.firm.aiOpex,
        skeletonCrew(firm),
        loan,
        w.householdMarket.marketWage,
        lendRate,
        w.priceLevel,
        w.external.gvc.importCostIndex,
      )
    UpgradeCandidate(
      capex,
      loan,
      down,
      profitable = pnl.costs > (cost * FullAiProfitMargin) / sigmaThreshold(w.currentSigmas(firm.sector.toInt)),
      canPay = financialStocks.cash > down,
      ready = firm.digitalReadiness >= p.firm.fullAiReadinessMin,
      credit = bankCreditDecision(loan),
    )

  /** Evaluate hybrid upgrade feasibility for a traditional firm. */
  private def evaluateHybrid(
      firm: State,
      financialStocks: FinancialStocks,
      pnl: PnL,
      workers: Int,
      w: World,
      lendRate: Rate,
      bankCreditDecision: PLN => CreditDecision,
  )(using p: SimParams): (UpgradeCandidate, Int) =
    val capex = computeHybridCapex(firm)
    val loan  = capex * HybridLoanShare
    val down  = capex * HybridDownShare
    val hWkrs = Math.max(3, p.sectorDefs(firm.sector.toInt).hybridRetainFrac.applyTo(workers))
    val cost  = estimateMonthlyCost(
      firm,
      financialStocks,
      p.firm.hybridOpex,
      hWkrs,
      loan,
      w.householdMarket.marketWage,
      lendRate,
      w.priceLevel,
      w.external.gvc.importCostIndex,
    )
    val cand  = UpgradeCandidate(
      capex,
      loan,
      down,
      profitable = pnl.costs > (cost * HybridProfitMargin) / sigmaThreshold(w.currentSigmas(firm.sector.toInt)),
      canPay = financialStocks.cash > down,
      ready = firm.digitalReadiness >= p.firm.hybridReadinessMin,
      credit = bankCreditDecision(loan),
    )
    (cand, hWkrs)

  /** Compute adoption probabilities for full-AI and hybrid upgrades. */
  private def adoptionProbabilities(
      firm: State,
      pnl: PnL,
      fullAi: UpgradeCandidate,
      hybrid: UpgradeCandidate,
      executionMonth: ExecutionMonth,
      w: World,
      allFirms: Vector[State],
  )(using p: SimParams): (Share, Share) =
    val localAuto   = computeLocalAutoRatio(firm, allFirms)
    val globalPanic = (w.real.automationRatio + w.real.hybridRatio * HybridPanicDiscount) * HybridPanicDiscount
    val panic       = localAuto * LocalPanicWeight + globalPanic * GlobalPanicWeight
    val desper      = if pnl.netAfterTax < PLN.Zero then DesperationBonus else Share.Zero
    val strat       =
      if !fullAi.profitable && fullAi.canPay && fullAi.ready && fullAi.bankOk then firm.riskProfile * firm.digitalReadiness * StrategicAdoptBase
      else Share.Zero

    val willingnessMultiplier = adoptionWillingnessMultiplier(executionMonth, localAuto)

    val rawFull = willingnessMultiplier *
      (if fullAi.feasible then (firm.riskProfile * RiskWeightFullAi + panic + desper) * firm.digitalReadiness
       else strat)
    val rawHyb  = willingnessMultiplier *
      (if hybrid.feasible then (firm.riskProfile * RiskWeightHybrid + panic * HybridPanicDiscount + desper * HybridPanicDiscount) * firm.digitalReadiness
       else Share.Zero)

    val pFull = rawFull.min(Share.One)
    val pHyb  = rawHyb.min(Share.One - pFull)
    (pFull, pHyb)

  /** Roll for full-AI upgrade: success or implementation failure. */
  private def rollFullAiUpgrade(firm: State, pnl: PnL, ai: UpgradeCandidate, rng: RandomStream): DecisionWithAudit =
    val failRate = FullAiBaseFailRate + (Share.One - firm.digitalReadiness) * FullAiFailDrSens
    val roll     = Share.random(rng)
    val audit    = DecisionAudit(
      implementationFailureProbability = Some(failRate.min(Share.One)),
      implementationRoll = Some(roll),
    )
    if roll < failRate then
      DecisionWithAudit(
        Decision.UpgradeFailed(pnl, BankruptReason.AiImplFailure, ai.capex * FailCapexFrac, ai.loan * FailLoanFrac, ai.down * FailDownFrac),
        audit,
      )
    else
      val effDraw = Scalar.randomBetween(TradToFullEffMin, TradToFullEffMax, rng)
      val eff     = Multiplier.One + (effDraw * firm.digitalReadiness.toScalar).toMultiplier
      DecisionWithAudit(
        Decision.Upgrade(pnl, TechState.Automated(eff), ai.capex, ai.loan, ai.down),
        audit.copy(upgradeEfficiencyDraw = Some(effDraw), upgradeEfficiencyMultiplier = Some(eff)),
      )

  /** Roll for hybrid upgrade: catastrophic failure, partial failure, or
    * success.
    */
  private def rollHybridUpgrade(firm: State, pnl: PnL, hyb: UpgradeCandidate, hWkrs: Int, rng: RandomStream): DecisionWithAudit =
    val failRate = HybridBaseFailRate + (Share.One - firm.digitalReadiness) * HybridFailDrSens
    val draw     = Share.random(rng)
    val audit    = DecisionAudit(
      implementationFailureProbability = Some(failRate.min(Share.One)),
      implementationRoll = Some(draw),
    )
    if draw < failRate * CatastrophicFailFrac then
      DecisionWithAudit(
        Decision.UpgradeFailed(
          pnl,
          BankruptReason.HybridImplFailure,
          hyb.capex * FailCapexFrac,
          hyb.loan * FailLoanFrac,
          hyb.down * FailDownFrac,
        ),
        audit,
      )
    else if draw < failRate then
      val effDraw = Scalar.randomBetween(Scalar.Zero, BadHybridEffRange, rng)
      val badEff  = BadHybridEffBase + effDraw.toMultiplier
      DecisionWithAudit(
        Decision.Upgrade(pnl, TechState.Hybrid(hWkrs, badEff), hyb.capex, hyb.loan, hyb.down),
        audit.copy(upgradeEfficiencyDraw = Some(effDraw), upgradeEfficiencyMultiplier = Some(badEff)),
      )
    else
      val effDraw = Scalar.randomBetween(Scalar.Zero, GoodHybridEffRange, rng)
      val goodEff = Multiplier.One +
        (GoodHybridEffBase + effDraw) *
        (GoodHybridDrBlend + firm.digitalReadiness * GoodHybridDrBlend).toScalar.toMultiplier
      DecisionWithAudit(
        Decision.Upgrade(pnl, TechState.Hybrid(hWkrs, goodEff), hyb.capex, hyb.loan, hyb.down),
        audit.copy(upgradeEfficiencyDraw = Some(effDraw), upgradeEfficiencyMultiplier = Some(goodEff)),
      )
