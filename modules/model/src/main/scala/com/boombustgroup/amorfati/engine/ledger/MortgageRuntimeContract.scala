package com.boombustgroup.amorfati.engine.ledger

import com.boombustgroup.amorfati.engine.flows.RuntimeLedgerTopology
import com.boombustgroup.ledger.{AssetType, EntitySector}

/** Explicit runtime contract for aggregate mortgage principal execution.
  *
  * Runtime execution uses a household-sector principal settlement shell for the
  * aggregate origination, repayment, and default legs. Persisted end-of-month
  * stock evidence keeps household mortgage liabilities and a bank-side mortgage
  * asset mirror in [[LedgerFinancialState]], so the BSM row can close without
  * making every mortgage flow holder-resolved.
  */
object MortgageRuntimeContract:

  enum Role:
    case PrincipalSettlement

  case class RuntimeNode(
      name: String,
      sector: EntitySector,
      index: Int,
      role: Role,
      persistedAsStock: Boolean,
      asset: AssetType,
  )

  def principalSettlement(topology: RuntimeLedgerTopology): RuntimeNode =
    RuntimeNode(
      name = "Households.MortgagePrincipalSettlement",
      sector = EntitySector.Households,
      index = topology.households.mortgagePrincipalSettlement,
      role = Role.PrincipalSettlement,
      persistedAsStock = false,
      asset = AssetType.MortgageLoan,
    )

  /** Zero-population shape template for ownership-contract registration.
    *
    * `TemplatePrincipalSettlement.index` comes from
    * `principalSettlement(RuntimeLedgerTopology.zeroPopulation)`, so it is not
    * meaningful for live simulations. Runtime logic and membership checks must
    * obtain the concrete index from `principalSettlement` using the actual
    * `RuntimeLedgerTopology`.
    */
  val TemplatePrincipalSettlement: RuntimeNode =
    principalSettlement(RuntimeLedgerTopology.zeroPopulation)

end MortgageRuntimeContract
