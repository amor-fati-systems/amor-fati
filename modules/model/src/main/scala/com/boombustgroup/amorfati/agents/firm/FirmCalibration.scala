package com.boombustgroup.amorfati.agents.firm

import com.boombustgroup.amorfati.types.*

/** Internal firm-model constants that are not yet experiment parameters. */
private[agents] object FirmCalibration:

  val FullAiLoanShare: Share      = Share.decimal(85, 2)
  val FullAiDownShare: Share      = Share.decimal(15, 2)
  val HybridLoanShare: Share      = Share.decimal(80, 2)
  val HybridDownShare: Share      = Share.decimal(20, 2)
  val HybridToFullCapexMul: Share = Share.decimal(6, 1)

  val FullAiProfitMargin: Multiplier = Multiplier.decimal(11, 1)
  val HybridProfitMargin: Multiplier = Multiplier.decimal(105, 2)

  val RiskWeightFullAi: Share     = Share.decimal(1, 1)
  val RiskWeightHybrid: Share     = Share.decimal(4, 2)
  val RiskWeightHybUpgrade: Share = Share.decimal(15, 2)
  val AutoRatioWeight: Share      = Share.decimal(3, 1)
  val LocalPanicWeight: Share     = Share.decimal(4, 1)
  val GlobalPanicWeight: Share    = Share.decimal(4, 1)
  val HybridPanicDiscount: Share  = Share.decimal(5, 1)
  val DesperationBonus: Share     = Share.decimal(2, 1)
  val StrategicAdoptBase: Share   = Share.decimal(5, 3)

  val UncertaintyBase: Share  = Share.decimal(8, 2)
  val UncertaintySlope: Share = Share.decimal(7, 2)

  val FullAiBaseFailRate: Share   = Share.decimal(5, 2)
  val FullAiFailDrSens: Share     = Share.decimal(10, 2)
  val HybridBaseFailRate: Share   = Share.decimal(3, 2)
  val HybridFailDrSens: Share     = Share.decimal(7, 2)
  val CatastrophicFailFrac: Share = Share.decimal(4, 1)

  val FailCapexFrac: Share = Share.decimal(5, 1)
  val FailLoanFrac: Share  = Share.decimal(3, 1)
  val FailDownFrac: Share  = Share.decimal(5, 1)

  val HybToFullEffMin: Scalar      = Scalar.decimal(2, 1)
  val HybToFullEffMax: Scalar      = Scalar.decimal(6, 1)
  val TradToFullEffMin: Scalar     = Scalar.decimal(5, 2)
  val TradToFullEffMax: Scalar     = Scalar.decimal(6, 1)
  val BadHybridEffBase: Multiplier = Multiplier.decimal(85, 2)
  val BadHybridEffRange: Scalar    = Scalar.decimal(20, 2)
  val GoodHybridEffBase: Scalar    = Scalar.decimal(5, 2)
  val GoodHybridEffRange: Scalar   = Scalar.decimal(15, 2)
  val GoodHybridDrBlend: Share     = Share.decimal(5, 1)

  val FiringAdjustFrac: Share                    = Share.decimal(5, 2)
  val InvestmentSignalRampMonths: Int            = 24
  val WorkingCapitalGraceMonths: Multiplier      = Multiplier.decimal(15, 1)
  val StartupRunwayCashShare: Share              = Share.decimal(35, 2)
  val StartupDownsizeSpeedMultiplier: Multiplier = Multiplier.decimal(5, 1)
  val StartupRunwayMonths: Int                   = 4
  val StartupCostFloor: Multiplier               = Multiplier.decimal(50, 2)
  val MicroFirmHiringThreshold: Int              = 1
  val HiringSignalPersistenceMonths: Int         = 2
  val SmallFirmDesiredAddCap: Int                = 1
  val MidFirmDesiredAddCap: Int                  = 2
  val LargeFirmDesiredGrowthShare: Share         = Share.decimal(5, 2)
  val HiringPressureBlend: Share                 = Share.decimal(35, 2)
  val NegativeCashHiringPenalty: Share           = Share.decimal(5, 1)

  val HybridLaborCapShare: Share = Share.decimal(4, 1)
  val HybridAiCapShare: Share    = Share.decimal(6, 1)

  val OpexDomesticShare: Share = Share.decimal(60, 2)
  val OpexImportShare: Share   = Share.decimal(40, 2)

  val CapexSizeExponent: Scalar = Scalar.decimal(6, 1)
  val OpexSizeExponent: Scalar  = Scalar.decimal(5, 1)
  val SkeletonCrewFrac: Share   = Share.decimal(2, 2)

  val HybridMonthlyDrDrift: Share    = Share.decimal(5, 3)
  val DigiInvestCashMult: Multiplier = Multiplier(2)

  val SigmaThreshBase: Multiplier  = Multiplier.decimal(88, 2)
  val SigmaThreshScale: Multiplier = Multiplier.decimal(75, 3)
