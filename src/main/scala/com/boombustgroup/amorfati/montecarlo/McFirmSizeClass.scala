package com.boombustgroup.amorfati.montecarlo

private[montecarlo] enum McFirmSizeClass(val csvValue: String):
  case Micro  extends McFirmSizeClass("Micro")
  case Small  extends McFirmSizeClass("Small")
  case Medium extends McFirmSizeClass("Medium")
  case Large  extends McFirmSizeClass("Large")

private[montecarlo] object McFirmSizeClass:
  def fromWorkerCount(workers: Int): McFirmSizeClass =
    require(workers >= 0, s"firm worker count must be >= 0, got $workers")
    workers match
      case size if size <= 9   => Micro
      case size if size <= 49  => Small
      case size if size <= 249 => Medium
      case _                   => Large
