package com.boombustgroup.amorfati.agents.banking

import com.boombustgroup.amorfati.agents.Banking.Config
import com.boombustgroup.amorfati.types.*

private[agents] object BankDefaultConfigs:

  private def affinity(xs: Share*): Vector[Share] = xs.toVector

  /** Poland-facing bank archetypes plus residual Other banks, 2026-04-30
    * baseline.
    */
  val defaultConfigs: Vector[Config] = Vector(
    Config(
      BankId(0),
      "PKO BP",
      Share.decimal(175, 3),
      Share.decimal(185, 3),
      Rate.decimal(-2, 3),
      affinity(Share.decimal(15, 2), Share.decimal(15, 2), Share.decimal(15, 2), Share.decimal(10, 2), Share.decimal(30, 2), Share.decimal(15, 2)),
    ),
    Config(
      BankId(1),
      "Pekao",
      Share.decimal(120, 3),
      Share.decimal(178, 3),
      Rate.decimal(-1, 3),
      affinity(Share.decimal(15, 2), Share.decimal(20, 2), Share.decimal(20, 2), Share.decimal(15, 2), Share.decimal(15, 2), Share.decimal(15, 2)),
    ),
    Config(
      BankId(2),
      "mBank",
      Share.decimal(85, 3),
      Share.decimal(169, 3),
      Rate(0),
      affinity(Share.decimal(30, 2), Share.decimal(10, 2), Share.decimal(25, 2), Share.decimal(10, 2), Share.decimal(10, 2), Share.decimal(15, 2)),
    ),
    Config(
      BankId(3),
      "ING BSK",
      Share.decimal(75, 3),
      Share.decimal(172, 3),
      Rate.decimal(-1, 3),
      affinity(Share.decimal(15, 2), Share.decimal(35, 2), Share.decimal(15, 2), Share.decimal(10, 2), Share.decimal(10, 2), Share.decimal(15, 2)),
    ),
    Config(
      BankId(4),
      "Santander",
      Share.decimal(70, 3),
      Share.decimal(170, 3),
      Rate(0),
      affinity(Share.decimal(15, 2), Share.decimal(10, 2), Share.decimal(35, 2), Share.decimal(15, 2), Share.decimal(10, 2), Share.decimal(15, 2)),
    ),
    Config(
      BankId(5),
      "BPS/Coop",
      Share.decimal(50, 3),
      Share.decimal(150, 3),
      Rate.decimal(3, 3),
      affinity(Share.decimal(5, 2), Share.decimal(10, 2), Share.decimal(10, 2), Share.decimal(5, 2), Share.decimal(5, 2), Share.decimal(65, 2)),
    ),
    Config(
      BankId(6),
      "BNP Paribas",
      Share.decimal(85, 3),
      Share.decimal(165, 3),
      Rate(0),
      affinity(Share.decimal(15, 2), Share.decimal(20, 2), Share.decimal(20, 2), Share.decimal(15, 2), Share.decimal(15, 2), Share.decimal(15, 2)),
    ),
    Config(
      BankId(7),
      "Millennium",
      Share.decimal(70, 3),
      Share.decimal(160, 3),
      Rate.decimal(1, 3),
      affinity(Share.decimal(15, 2), Share.decimal(10, 2), Share.decimal(25, 2), Share.decimal(15, 2), Share.decimal(15, 2), Share.decimal(20, 2)),
    ),
    Config(
      BankId(8),
      "Alior",
      Share.decimal(55, 3),
      Share.decimal(150, 3),
      Rate.decimal(2, 3),
      affinity(Share.decimal(15, 2), Share.decimal(15, 2), Share.decimal(25, 2), Share.decimal(10, 2), Share.decimal(10, 2), Share.decimal(25, 2)),
    ),
    Config(
      BankId(9),
      "Other banks",
      Share.decimal(215, 3),
      Share.decimal(165, 3),
      Rate.decimal(1, 3),
      affinity(Share.decimal(15, 2), Share.decimal(17, 2), Share.decimal(17, 2), Share.decimal(17, 2), Share.decimal(17, 2), Share.decimal(17, 2)),
    ),
  )
