package com.boombustgroup.amorfati.agents.banking

import com.boombustgroup.amorfati.agents.Banking.Config
import com.boombustgroup.amorfati.types.*

/** Default banking-sector archetype calibration exposed through the public
  * `Banking.DefaultConfigs` facade.
  */
private[agents] object BankDefaultConfigs:

  /** Builds a sector-affinity vector while keeping the table compact. */
  private def affinity(xs: Share*): Vector[Share] = xs.toVector

  private def bank(
      id: Int,
      name: String,
      relationshipWeight: Share,
      lendingSpread: Rate,
      sectorAffinity: Vector[Share],
  ): Config =
    Config(
      id = BankId(id),
      name = name,
      relationshipWeight = relationshipWeight,
      openingBalanceWeight = relationshipWeight,
      lendingSpread = lendingSpread,
      sectorAffinity = sectorAffinity,
    )

  /** Poland-facing bank archetypes plus residual Other banks, 2026-04-30
    * baseline.
    */
  val defaultConfigs: Vector[Config] = Vector(
    bank(
      id = 0,
      name = "PKO BP",
      relationshipWeight = Share.decimal(175, 3),
      lendingSpread = Rate.decimal(-2, 3),
      sectorAffinity =
        affinity(Share.decimal(15, 2), Share.decimal(15, 2), Share.decimal(15, 2), Share.decimal(10, 2), Share.decimal(30, 2), Share.decimal(15, 2)),
    ),
    bank(
      id = 1,
      name = "Pekao",
      relationshipWeight = Share.decimal(120, 3),
      lendingSpread = Rate.decimal(-1, 3),
      sectorAffinity =
        affinity(Share.decimal(15, 2), Share.decimal(20, 2), Share.decimal(20, 2), Share.decimal(15, 2), Share.decimal(15, 2), Share.decimal(15, 2)),
    ),
    bank(
      id = 2,
      name = "mBank",
      relationshipWeight = Share.decimal(85, 3),
      lendingSpread = Rate(0),
      sectorAffinity =
        affinity(Share.decimal(30, 2), Share.decimal(10, 2), Share.decimal(25, 2), Share.decimal(10, 2), Share.decimal(10, 2), Share.decimal(15, 2)),
    ),
    bank(
      id = 3,
      name = "ING BSK",
      relationshipWeight = Share.decimal(75, 3),
      lendingSpread = Rate.decimal(-1, 3),
      sectorAffinity =
        affinity(Share.decimal(15, 2), Share.decimal(35, 2), Share.decimal(15, 2), Share.decimal(10, 2), Share.decimal(10, 2), Share.decimal(15, 2)),
    ),
    bank(
      id = 4,
      name = "Santander",
      relationshipWeight = Share.decimal(70, 3),
      lendingSpread = Rate(0),
      sectorAffinity =
        affinity(Share.decimal(15, 2), Share.decimal(10, 2), Share.decimal(35, 2), Share.decimal(15, 2), Share.decimal(10, 2), Share.decimal(15, 2)),
    ),
    bank(
      id = 5,
      name = "BPS/Coop",
      relationshipWeight = Share.decimal(50, 3),
      lendingSpread = Rate.decimal(3, 3),
      sectorAffinity = affinity(Share.decimal(5, 2), Share.decimal(10, 2), Share.decimal(10, 2), Share.decimal(5, 2), Share.decimal(5, 2), Share.decimal(65, 2)),
    ),
    bank(
      id = 6,
      name = "BNP Paribas",
      relationshipWeight = Share.decimal(85, 3),
      lendingSpread = Rate(0),
      sectorAffinity =
        affinity(Share.decimal(15, 2), Share.decimal(20, 2), Share.decimal(20, 2), Share.decimal(15, 2), Share.decimal(15, 2), Share.decimal(15, 2)),
    ),
    bank(
      id = 7,
      name = "Millennium",
      relationshipWeight = Share.decimal(70, 3),
      lendingSpread = Rate.decimal(1, 3),
      sectorAffinity =
        affinity(Share.decimal(15, 2), Share.decimal(10, 2), Share.decimal(25, 2), Share.decimal(15, 2), Share.decimal(15, 2), Share.decimal(20, 2)),
    ),
    bank(
      id = 8,
      name = "Alior",
      relationshipWeight = Share.decimal(55, 3),
      lendingSpread = Rate.decimal(2, 3),
      sectorAffinity =
        affinity(Share.decimal(15, 2), Share.decimal(15, 2), Share.decimal(25, 2), Share.decimal(10, 2), Share.decimal(10, 2), Share.decimal(25, 2)),
    ),
    bank(
      id = 9,
      name = "Other banks",
      relationshipWeight = Share.decimal(215, 3),
      lendingSpread = Rate.decimal(1, 3),
      sectorAffinity =
        affinity(Share.decimal(15, 2), Share.decimal(17, 2), Share.decimal(17, 2), Share.decimal(17, 2), Share.decimal(17, 2), Share.decimal(17, 2)),
    ),
  )
