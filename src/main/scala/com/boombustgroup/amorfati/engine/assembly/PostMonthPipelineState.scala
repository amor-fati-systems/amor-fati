package com.boombustgroup.amorfati.engine.assembly

import com.boombustgroup.amorfati.engine.PipelineState

/** Pipeline fields persisted on the month-t World before next-month signal
  * extraction.
  */
object PostMonthPipelineState:

  def build(in: WorldAssemblyEconomics.StepInput): PipelineState =
    in.w.pipeline
      .copy(
        operationalHiringSlack = in.s2.operationalHiringSlack,
        fiscalRuleSeverity = in.s4.fiscalRuleStatus.bindingRule,
        govSpendingCutRatio = in.s4.fiscalRuleStatus.spendingCutRatio,
      )
