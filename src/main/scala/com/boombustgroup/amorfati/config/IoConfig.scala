package com.boombustgroup.amorfati.config

import com.boombustgroup.amorfati.types.*

/** Input-Output matrix for inter-sectoral intermediate demand.
  *
  * Implements the 6x6 technical coefficients matrix A (Leontief, 1936). Entry
  * `matrix(i)(j)` is the share of using sector j's output purchased from
  * supplier/input sector i. Inter-sector purchases are deposit transfers within
  * the same bank (zero-sum for total deposits), so they do not break existing
  * SFC identities. Column sums are pre-computed for efficiency.
  *
  * Default matrix is the 6-sector technical-coefficients assumption for the
  * 2026-04-30 Poland baseline. The GUS 2020 domestic input-output bridge is the
  * comparison surface for later retuning, not a silent overwrite of the runtime
  * defaults.
  *
  * @param matrix
  *   6x6 technical coefficients matrix A[i][j] = input from supplier sector i
  *   used by sector j
  * @param scale
  *   scaling factor for I-O flows (1.0 = full strength, for sensitivity
  *   analysis)
  * @param crossSectorSpillover
  *   substitutable share of excess sector demand that can spill to compatible
  *   slack sectors
  */
case class IoConfig(
    matrix: Vector[Vector[Share]] = IoConfig.DefaultMatrix,
    scale: Multiplier = Multiplier(1),
    crossSectorSpillover: Share = Share.decimal(65, 2),
):
  require(matrix.nonEmpty, "IoConfig.matrix must be non-empty")

  private val rowCount = matrix.length
  private val colCount = matrix.head.length

  require(matrix.forall(_.length == colCount), "IoConfig.matrix must have rows of equal length")
  require(rowCount == colCount, "IoConfig.matrix must be square")
  require(matrix.flatten.forall(_ >= Share.Zero), "IoConfig.matrix entries must be non-negative")
  require(scale >= Multiplier.Zero, "IoConfig.scale must be non-negative")
  require(crossSectorSpillover >= Share.Zero && crossSectorSpillover <= Share.One, s"crossSectorSpillover must be in [0,1]: $crossSectorSpillover")

  /** Pre-computed column sums of the technical coefficients matrix (used in
    * intermediate demand calculation).
    */
  val columnSums: Vector[Share] =
    (0 until colCount).map(j => matrix.map(_(j)).foldLeft(Share.Zero)(_ + _)).toVector
  require(columnSums.forall(_ < Share.One), "IoConfig matrix column sums must be < 1.0")

object IoConfig:
  /** Reviewed default 6x6 I-O technical coefficients for the 2026-04-30 Poland
    * baseline bridge.
    *
    * Rows/columns: BPO/SSC, Manufacturing, Retail/Services, Healthcare, Public,
    * Agriculture.
    */
  val DefaultMatrix: Vector[Vector[Share]] = Vector(
    Vector(Share.decimal(5, 2), Share.decimal(3, 2), Share.decimal(4, 2), Share.decimal(2, 2), Share.decimal(3, 2), Share.decimal(1, 2)),
    Vector(Share.decimal(4, 2), Share.decimal(35, 2), Share.decimal(12, 2), Share.decimal(15, 2), Share.decimal(5, 2), Share.decimal(18, 2)),
    Vector(Share.decimal(15, 2), Share.decimal(10, 2), Share.decimal(12, 2), Share.decimal(8, 2), Share.decimal(7, 2), Share.decimal(8, 2)),
    Vector(Share.decimal(1, 2), Share.Zero, Share.decimal(1, 2), Share.decimal(5, 2), Share.decimal(2, 2), Share.decimal(1, 2)),
    Vector(Share.decimal(1, 2), Share.decimal(1, 2), Share.decimal(1, 2), Share.decimal(1, 2), Share.decimal(3, 2), Share.decimal(1, 2)),
    Vector(Share.Zero, Share.decimal(8, 2), Share.decimal(5, 2), Share.decimal(1, 2), Share.decimal(1, 2), Share.decimal(12, 2)),
  )
