package com.boombustgroup.amorfati.engine.economics.banking

/** Contract for every production path that writes or computes
  * `Banking.BankState.capital`.
  *
  * Bank capital is a regulatory/accounting buffer rather than a holder-resolved
  * equity asset. This registry is the guardrail for that contract: every
  * production write site must have a named economic category, an SFC treatment,
  * and an explicit loss absorber.
  */
object BankCapitalSemantics:

  enum Category(val token: String):
    /** Opening stock seeded by bank calibration, not a monthly SFC delta. */
    case OpeningCalibration extends Category("opening-calibration")

    /** Retained ordinary income and realized banking losses inside the monthly
      * P&L waterfall.
      */
    case OrdinaryPnlWaterfall extends Category("ordinary-pnl-waterfall")

    /** IFRS 9 provision movement, positive when additional allowance hits
      * capital.
      */
    case EclProvisionChange extends Category("ecl-provision-change")

    /** Hidden HTM mark-to-market loss realized on forced reclassification. */
    case HtmRealizedLoss extends Category("htm-realized-loss")

    /** Counterparty loss from failed-bank interbank exposure. */
    case InterbankContagionLoss extends Category("interbank-contagion-loss")

    /** Regulatory buffer wiped when a bank newly enters failure/resolution. */
    case FailureCapitalDestruction extends Category("failure-capital-destruction")

    /** Exactness patch that keeps aggregate SFC identities closed after
      * per-bank allocation.
      */
    case ExactnessReconciliation extends Category("exactness-reconciliation")

  final case class SourceAnchor(
      path: String,
      fragment: String,
  )

  final case class WriteSite(
      id: String,
      categories: Set[Category],
      source: SourceAnchor,
      sfcTreatment: String,
      absorber: String,
      diagnostics: String,
  )

  val writeSites: Vector[WriteSite] = Vector(
    WriteSite(
      id = "opening-bank-capital-calibration",
      categories = Set(Category.OpeningCalibration),
      source = SourceAnchor(
        "src/main/scala/com/boombustgroup/amorfati/init/BankInit.scala",
        "capital = totalCapital * cfg.initMarketShare",
      ),
      sfcTreatment = "Opening regulatory/accounting stock. It seeds BankState.capital and is not a monthly SFC delta.",
      absorber = "No monthly absorber; source is model-start bank calibration.",
      diagnostics = "Opening value appears in BankBalanceSheetBenchmarkExport and BankCapital_Opening.",
    ),
    WriteSite(
      id = "ordinary-bank-pnl-formula",
      categories = Set(Category.OrdinaryPnlWaterfall),
      source = SourceAnchor(
        "src/main/scala/com/boombustgroup/amorfati/agents/banking/BankCapitalWaterfall.scala",
        "CapitalPnlOutput(newCapital = in.prevCapital - losses + retainedIncome)",
      ),
      sfcTreatment = "Monthly BankCapital identity: retained income increases capital; realized banking losses reduce it.",
      absorber = "Bank regulatory/accounting capital buffer absorbs losses; unretained income has no holder-resolved bank-equity receiver.",
      diagnostics = "BankCapital_RetainedIncome, BankCapital_RealizedCreditLoss, BankCapital_BfgLevy, BankCapital_UnrealizedBondLoss.",
    ),
    WriteSite(
      id = "monthly-bank-capital-and-ecl-write",
      categories = Set(Category.OrdinaryPnlWaterfall, Category.EclProvisionChange),
      source = SourceAnchor(
        "src/main/scala/com/boombustgroup/amorfati/engine/economics/banking/BankMultiBankStage.scala",
        "capital = capitalPnl.newCapital - eclResult.provisionChange",
      ),
      sfcTreatment = "Applies the ordinary P&L formula and the IFRS 9 provision-change hit before failure checks.",
      absorber = "Bank regulatory/accounting capital buffer.",
      diagnostics = "BankCapital_EclProvisionChange plus the ordinary BankCapital_* waterfall columns.",
    ),
    WriteSite(
      id = "htm-forced-sale-capital-loss",
      categories = Set(Category.HtmRealizedLoss),
      source = SourceAnchor(
        "src/main/scala/com/boombustgroup/amorfati/agents/banking/BankBondPortfolio.scala",
        "b.copy(capital = b.capital - loss)",
      ),
      sfcTreatment = "Non-batch monthly BankCapital identity term htmRealizedLoss.",
      absorber = "Bank regulatory/accounting capital buffer absorbs realized HTM valuation loss.",
      diagnostics = "BankCapital_HtmRealizedLoss.",
    ),
    WriteSite(
      id = "interbank-contagion-counterparty-loss",
      categories = Set(Category.InterbankContagionLoss),
      source = SourceAnchor(
        "src/main/scala/com/boombustgroup/amorfati/agents/InterbankContagion.scala",
        "b.copy(capital = b.capital - loss)",
      ),
      sfcTreatment =
        "Same-month per-bank counterparty loss. Monthly BankCapital identity term interbankContagionLoss keeps it separate from failure capital destruction and reconciliation residual.",
      absorber = "Initially the exposed bank capital buffer; if resolution is triggered, remaining loss is handled by the failure/bail-in path.",
      diagnostics = "BankCapital_InterbankContagionLoss plus failure-trigger evidence at aggregate close.",
    ),
    WriteSite(
      id = "failure-check-capital-wipe",
      categories = Set(Category.FailureCapitalDestruction),
      source = SourceAnchor(
        "src/main/scala/com/boombustgroup/amorfati/agents/banking/BankFailureResolution.scala",
        "b.copy(status = BankStatus.Failed(month), capital = PLN.Zero)",
      ),
      sfcTreatment = "Monthly BankCapital identity term bankCapitalDestruction.",
      absorber = "Shareholder/regulatory capital buffer is wiped; depositor loss is separate BankDeposits bail-in, not an equity transfer.",
      diagnostics = "BankCapital_CapitalDestruction and BankFailure_* reason columns.",
    ),
    WriteSite(
      id = "aggregate-exactness-capital-reconciliation",
      categories = Set(Category.ExactnessReconciliation),
      source = SourceAnchor(
        "src/main/scala/com/boombustgroup/amorfati/engine/economics/banking/BankAggregateReconciliation.scala",
        "nextBanks(index) = nextBanks(index).copy(capital = nextBanks(index).capital + capitalMove)",
      ),
      sfcTreatment = "Documented exactness patch after per-bank allocation; this is the only named residual capital writer.",
      absorber =
        "The aggregate exactness patch is distributed across live bank rows; diagnostics expose the most impacted bank id, allocation size, and CAR impact.",
      diagnostics = "BankCapital_ReconciliationResidual and BankReconciliation_*.",
    ),
  )

  val writeSiteIds: Set[String] = writeSites.map(_.id).toSet

end BankCapitalSemantics
