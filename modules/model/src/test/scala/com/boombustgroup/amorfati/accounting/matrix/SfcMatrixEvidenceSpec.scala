package com.boombustgroup.amorfati.accounting.matrix

import com.boombustgroup.amorfati.accounting.matrix.SfcMatrixEvidence.*
import com.boombustgroup.amorfati.accounting.Sfc
import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.engine.flows.FlowMechanism
import com.boombustgroup.amorfati.engine.ledger.RuntimeMechanismSurvivability
import com.boombustgroup.ledger.{AssetType, BatchedFlow, EntitySector}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SfcMatrixEvidenceSpec extends AnyFlatSpec with Matchers:

  private given SimParams = SimParams.defaults

  private lazy val step =
    SfcMatrixEvidenceTestSupport.deterministicStep()

  private lazy val bundle =
    SfcMatrixEvidenceTestSupport.bundleFrom(step, seed = SfcMatrixEvidenceTestSupport.EvidenceSeed, commit = "test")

  "BsmEvidence" should "derive opening and closing matrices from ledger-owned state" in {
    val closing = bundle.closingBsm

    closing.row(AssetType.DemandDeposit).amountRaw(EntitySector.Households) should not be 0L
    closing.row(AssetType.FirmLoan).amountRaw(EntitySector.Banks) should not be 0L
    closing.row(AssetType.MortgageLoan).amountRaw(EntitySector.Banks) shouldBe -closing.row(AssetType.MortgageLoan).amountRaw(EntitySector.Households)
    closing.row(AssetType.MortgageLoan).gaps shouldBe empty
    closing.row(AssetType.GovBondHTM).amountRaw(EntitySector.Government) should be < 0L
    closing.row(AssetType.CorpBond).amountRaw(EntitySector.Funds) should not be 0L
    closing.row(AssetType.Reserve).amountRaw(EntitySector.NBP) shouldBe -closing.row(AssetType.Reserve).amountRaw(EntitySector.Banks)
    closing.row(AssetType.Reserve).gaps shouldBe empty
    closing.row(AssetType.LifeReserve).amountRaw(EntitySector.Households) shouldBe -closing.row(AssetType.LifeReserve).amountRaw(EntitySector.Insurance)
    closing.row(AssetType.LifeReserve).gaps shouldBe empty
    closing.row(AssetType.NonLifeReserve).amountRaw(EntitySector.Households) shouldBe -closing
      .row(AssetType.NonLifeReserve)
      .amountRaw(
        EntitySector.Insurance,
      )
    closing.row(AssetType.NonLifeReserve).gaps shouldBe empty
    closing.row(AssetType.Equity).amountRaw(EntitySector.Foreign) shouldBe step.nextState.ledgerFinancialState.foreign.equityHoldings.toLong
    closing.row(AssetType.Equity).gaps.map(_.reason).mkString(" ") should not include "metric-only"
    closing.row(AssetType.Capital).amountRaw(EntitySector.Banks) should not be 0L
    closing.row(AssetType.Capital).gaps.map(_.kind) should contain only GapKind.UnsupportedDiagnostic
    closing.row(AssetType.ForeignAsset).amountRaw(EntitySector.NBP) should not be 0L
    closing.row(AssetType.Cash).amountRaw(EntitySector.Funds) should not be 0L
  }

  it should "validate complete financial-claim rows to zero for deterministic evidence" in {
    val completeAssets = SfcMatrixRegistry.instruments
      .filter(_.completeness == SfcMatrixRegistry.RowCompleteness.Complete)
      .map(_.asset)

    for
      bsm   <- Vector(bundle.openingBsm, bundle.closingBsm)
      asset <- completeAssets
    do
      withClue(s"${bsm.snapshotKind} $asset") {
        bsm.row(asset).rowSumRaw shouldBe 0L
      }
  }

  it should "reconcile insurance reserve stock deltas directly to transaction deltas" in {
    val reserveAssets  = Vector(AssetType.LifeReserve, AssetType.NonLifeReserve)
    val reserveSectors = Vector(EntitySector.Households, EntitySector.Insurance)

    for
      asset  <- reserveAssets
      sector <- reserveSectors
    do
      val maybeCell = bundle.otherChanges.cells.find(cell => cell.asset == asset && cell.sector == sector)

      withClue(s"$asset $sector") {
        val cell = maybeCell.getOrElse(fail("Missing insurance reserve other-change cell"))
        cell.stockDeltaRaw should not be 0L
        cell.stockDeltaRaw shouldBe cell.transactionDeltaRaw
        cell.otherChangeRaw shouldBe 0L
        cell.kind shouldBe OtherChangeKind.TransactionReconciliation
      }
  }

  it should "describe mortgage reconciliation as a household-stock identity with separate mirror validation" in {
    val mortgageRow = bundle.reconciliation.rows.find(_.identity == Sfc.SfcIdentity.MortgageStock).get

    mortgageRow.source shouldBe "Actual delta from household mortgage stock; expected delta from origination minus principal repayment and defaults."
    mortgageRow.note should include("bank mortgage asset mirror is checked by BSM row validation and InitCheck")
  }

  it should "classify bank capital residuals as unsupported diagnostics instead of coverage gaps" in {
    val opening = BsmEvidence(
      SnapshotKind.Opening,
      Vector(BsmRow(AssetType.Capital, Map(EntitySector.Banks -> -100L), Vector.empty)),
      Vector.empty,
    )
    val closing = BsmEvidence(
      SnapshotKind.Closing,
      Vector(BsmRow(AssetType.Capital, Map(EntitySector.Banks -> -90L), Vector.empty)),
      Vector.empty,
    )

    val cell = OtherChangesEvidence
      .from(
        opening,
        closing,
        TfmEvidence(
          rows = Vector.empty,
          omittedZeroBatches = 0,
          omittedZeroRows = 0,
          droppedRows = Vector.empty,
          droppedNonRegistrySectors = Vector.empty,
        ),
      )
      .nonZeroCells
      .find(cell => cell.asset == AssetType.Capital && cell.sector == EntitySector.Banks)
      .get

    cell.kind shouldBe OtherChangeKind.UnsupportedDiagnostic
    cell.reason should include("unsupported diagnostic row")
  }

  "TfmEvidence" should "derive transaction rows from executed batches and reconcile sector totals to the delta ledger" in {
    val tfm = bundle.tfm

    tfm.rows should not be empty
    all(tfm.rows.map(_.rowSumRaw)) shouldBe 0L
    tfm.rows.exists(_.contributors.nonEmpty) shouldBe true

    val expectedSectorTotals = EntitySector.values.toVector.map: sector =>
      sector -> step.execution.deltaLedger.iterator.collect { case ((`sector`, _, _), raw) =>
        raw
      }.sum

    tfm.sectorTotals shouldBe expectedSectorTotals.toMap
  }

  it should "surface equity revaluation evidence and reclassify remaining equity residuals" in {
    val equityRevaluationRows = bundle.tfm.rows.filter(row => row.mechanism == FlowMechanism.EquityRevaluation && row.asset == AssetType.Equity)
    val holderSectors         = Vector(EntitySector.Households, EntitySector.Insurance, EntitySector.Funds, EntitySector.Foreign)
    val revaluationInput      = step.calculus.equityRevaluation
    val householdDeltas       = revaluationInput.householdDeltas
    var householdExpectedRaw  = 0L
    var householdIndex        = 0
    while householdIndex < householdDeltas.length do
      householdExpectedRaw += householdDeltas(householdIndex).toLong
      householdIndex += 1
    val expectedRawBySector   = Map(
      EntitySector.Households -> householdExpectedRaw,
      EntitySector.Insurance  -> revaluationInput.insuranceDelta.toLong,
      EntitySector.Funds      -> revaluationInput.fundsDelta.toLong,
      EntitySector.Foreign    -> revaluationInput.foreignDelta.toLong,
    )
    val equityKinds           = bundle.otherChanges.nonZeroCells.filter(_.asset == AssetType.Equity).map(_.kind).toSet

    equityRevaluationRows should not be empty
    all(equityRevaluationRows.map(_.rowSumRaw)) shouldBe 0L
    holderSectors.foreach: sector =>
      withClue(s"EquityRevaluation $sector") {
        equityRevaluationRows.map(_.amountRaw(sector)).sum shouldBe expectedRawBySector(sector)
      }
    equityKinds should contain(OtherChangeKind.Revaluation)
    equityKinds should not contain OtherChangeKind.CoverageGap
  }

  it should "count all-zero batches separately from omitted transaction rows" in {
    val zero    = BatchedFlow.Broadcast(
      from = EntitySector.Government,
      fromIndex = 0,
      to = EntitySector.Firms,
      amounts = Array(0L),
      targetIndices = Array(0),
      asset = AssetType.Cash,
      mechanism = FlowMechanism.GovPurchases,
    )
    val nonZero = zero.copy(amounts = Array(10L))

    val evidence = TfmEvidence.fromBatches(Vector(zero, nonZero))

    evidence.omittedZeroBatches shouldBe 1
    evidence.omittedZeroRows shouldBe 0
    evidence.droppedRows shouldBe empty
    evidence.rows should have size 1
    evidence.rows.head.rowSumRaw shouldBe 0L
  }

  it should "retain provenance for rows that net to zero after aggregation" in {
    val govToFirm = BatchedFlow.Broadcast(
      from = EntitySector.Government,
      fromIndex = 0,
      to = EntitySector.Firms,
      amounts = Array(10L),
      targetIndices = Array(0),
      asset = AssetType.Cash,
      mechanism = FlowMechanism.GovPurchases,
    )
    val firmToGov = govToFirm.copy(
      from = EntitySector.Firms,
      to = EntitySector.Government,
    )

    val evidence = TfmEvidence.fromBatches(Vector(govToFirm, firmToGov))

    evidence.omittedZeroBatches shouldBe 0
    evidence.omittedZeroRows shouldBe 1
    evidence.rows shouldBe empty
    evidence.droppedRows should have size 1
    evidence.droppedRows.head.contributors should have size 2
    evidence.droppedRows.head.cells shouldBe empty
  }

  "MatrixValidation" should "include SFC status and pass for deterministic evidence" in {
    bundle.metadata.sfcStatus shouldBe "pass"
    bundle.metadata.matrixStatus shouldBe "pass"
    bundle.validation.isValid shouldBe true
  }

  "FlowMechanismSemantics" should "cover every emitted mechanism with audit metadata" in {
    val rows = FlowMechanismSemantics.rows

    rows.map(_.mechanism).toSet shouldBe FlowMechanism.emittedRuntimeMechanisms
    rows.map(_.mechanism.toInt).distinct should have size rows.size
    all(rows.map(_.flowFamily.trim.nonEmpty)) shouldBe true
    all(rows.map(_.expectedTopology.trim.nonEmpty)) shouldBe true
    all(rows.map(_.assetClass.trim.nonEmpty)) shouldBe true
    all(rows.map(_.sfcImpact.trim.nonEmpty)) shouldBe true
    all(rows.map(_.coverage.trim.nonEmpty)) shouldBe true

    val bankCapital = rows.find(_.mechanism == FlowMechanism.BankNplLoss).get
    bankCapital.sfcImpact should include("BankCapital")
    bankCapital.survivability shouldBe RuntimeMechanismSurvivability.Classification.UnsupportedOrMetricOnly

    val backstop = rows.find(_.mechanism == FlowMechanism.BankStandingFacilityBackstop).get
    backstop.survivability shouldBe RuntimeMechanismSurvivability.Classification.UnsupportedOrMetricOnly
    backstop.assetClass should include("StandingFacility")
  }

  "StockFlowReconciliationEvidence" should "render exact identity rows from independent semantic flow channels" in {
    val reconciliation = bundle.reconciliation

    reconciliation.rows.map(_.identity).toSet should contain(Sfc.SfcIdentity.Nfa)
    reconciliation.rows.map(_.identity).toSet should contain(Sfc.SfcIdentity.BankCapital)
    reconciliation.failures shouldBe empty

    val nfa         = reconciliation.rows.find(_.identity == Sfc.SfcIdentity.Nfa).get
    nfa.source should include("current account")
    nfa.note should include("valuationEffect")
    val bankCapital = reconciliation.rows.find(_.identity == Sfc.SfcIdentity.BankCapital).get
    bankCapital.note should include("persisted unsupported diagnostic stock")
  }

  "MatrixValidation" should "report actionable BSM and TFM perturbations" in {
    val openingIndex = bundle.openingBsm.rows.indexWhere(_.asset == AssetType.GovBondHTM)
    val openingRow   = bundle.openingBsm.rows(openingIndex)
    val badOpening   = bundle.openingBsm.copy(
      rows = bundle.openingBsm.rows.updated(
        openingIndex,
        openingRow.copy(cells = openingRow.cells.updated(EntitySector.Banks, openingRow.amountRaw(EntitySector.Banks) + 1L)),
      ),
    )
    val bsmReport    = MatrixValidation.validate(badOpening, bundle.closingBsm, bundle.tfm, Right(()))
    bsmReport.errors.exists(_.isInstanceOf[MatrixValidationError.BsmRowSumError]) shouldBe true

    val tfmRow    = bundle.tfm.rows.head
    val badTfmRow = tfmRow.copy(cells = tfmRow.cells.updated(EntitySector.Households, tfmRow.amountRaw(EntitySector.Households) + 1L))
    val badTfm    = bundle.tfm.copy(rows = bundle.tfm.rows.updated(0, badTfmRow))
    val tfmReport = MatrixValidation.validate(bundle.openingBsm, bundle.closingBsm, badTfm, Right(()))
    tfmReport.errors.exists(_.isInstanceOf[MatrixValidationError.TfmRowSumError]) shouldBe true

    val nonRegistryTfm    = bundle.tfm.copy(droppedNonRegistrySectors = Vector(EntitySector.Foreign))
    val nonRegistryReport = MatrixValidation.validate(bundle.openingBsm, bundle.closingBsm, nonRegistryTfm, Right(()))
    nonRegistryReport.errors.exists(_.isInstanceOf[MatrixValidationError.TfmNonRegistrySectorError]) shouldBe true
  }

end SfcMatrixEvidenceSpec
