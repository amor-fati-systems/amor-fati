package com.boombustgroup.amorfati.engine.economics.banking

import com.boombustgroup.amorfati.config.SimParams
import com.boombustgroup.amorfati.types.*

/** Credit-loss accounting bridge between borrower defaults, IFRS 9 allowance,
  * and the bank-capital waterfall.
  *
  * `expectedLoss` is the gross credit loss after recovery. `allowanceDraw` is
  * the part already covered by the product's ECL allowance rate. Only
  * `netCapitalLoss` is allowed to hit capital as realized credit loss in the
  * same month; provision movement remains a separate capital term.
  */
private[banking] object BankCreditLossAccounting:

  final case class ProductLoss(
      grossDefault: PLN,
      expectedLoss: PLN,
      allowanceDraw: PLN,
      netCapitalLoss: PLN,
  )

  object ProductLoss:
    val zero: ProductLoss = ProductLoss(PLN.Zero, PLN.Zero, PLN.Zero, PLN.Zero)

  final case class Breakdown(
      firm: ProductLoss = ProductLoss.zero,
      mortgage: ProductLoss = ProductLoss.zero,
      consumer: ProductLoss = ProductLoss.zero,
      corpBondDefaultLoss: PLN = PLN.Zero,
  ):
    def +(other: Breakdown): Breakdown =
      Breakdown(
        firm = add(firm, other.firm),
        mortgage = add(mortgage, other.mortgage),
        consumer = add(consumer, other.consumer),
        corpBondDefaultLoss = corpBondDefaultLoss + other.corpBondDefaultLoss,
      )

    def realizedCreditLoss: PLN =
      firm.netCapitalLoss + mortgage.netCapitalLoss + consumer.netCapitalLoss + corpBondDefaultLoss

    def allowanceDraw: PLN =
      firm.allowanceDraw + mortgage.allowanceDraw + consumer.allowanceDraw

  object Breakdown:
    val zero: Breakdown = Breakdown()

  def firm(grossDefault: PLN)(using p: SimParams): ProductLoss =
    fromGrossDefault(grossDefault, p.banking.loanRecovery, p.banking.eclRate3)

  def consumer(grossDefault: PLN)(using p: SimParams): ProductLoss =
    fromGrossDefault(grossDefault, p.household.ccNplRecovery, Share.Zero)

  def mortgage(grossDefault: PLN)(using p: SimParams): ProductLoss =
    fromGrossDefault(grossDefault, p.housing.mortgageRecovery, Share.Zero)

  def fromGrossDefault(grossDefault: PLN, recoveryRate: Share, allowanceRate: Share): ProductLoss =
    require(grossDefault >= PLN.Zero, s"Credit-loss gross default must be non-negative, got $grossDefault")
    require(recoveryRate >= Share.Zero && recoveryRate <= Share.One, s"Recovery rate must be in [0,1], got $recoveryRate")
    require(allowanceRate >= Share.Zero && allowanceRate <= Share.One, s"Allowance rate must be in [0,1], got $allowanceRate")
    val expectedLoss  = grossDefault * (Share.One - recoveryRate)
    val allowanceDraw = (grossDefault * allowanceRate).min(expectedLoss)
    ProductLoss(
      grossDefault = grossDefault,
      expectedLoss = expectedLoss,
      allowanceDraw = allowanceDraw,
      netCapitalLoss = (expectedLoss - allowanceDraw).max(PLN.Zero),
    )

  private def add(left: ProductLoss, right: ProductLoss): ProductLoss =
    ProductLoss(
      grossDefault = left.grossDefault + right.grossDefault,
      expectedLoss = left.expectedLoss + right.expectedLoss,
      allowanceDraw = left.allowanceDraw + right.allowanceDraw,
      netCapitalLoss = left.netCapitalLoss + right.netCapitalLoss,
    )
