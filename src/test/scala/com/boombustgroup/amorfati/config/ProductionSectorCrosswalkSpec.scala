package com.boombustgroup.amorfati.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ProductionSectorCrosswalkSpec extends AnyFlatSpec with Matchers:

  import ProductionSectorCrosswalk.*

  "ProductionSectorCrosswalk" should "preserve the runtime sector schema order and output stems" in {
    RuntimeSectors.map(_.name) shouldBe SimParams.SchemaSectorNames
    RuntimeSectors.map(_.outputStem) shouldBe SimParams.SchemaSectors.map(_.outputStem)
    SectorMappings.map(_.runtimeSector) shouldBe RuntimeSectors
  }

  it should "assign or exclude every NACE Rev. 2 section A-U exactly once" in {
    val expectedCodes = ('A' to 'U').map(_.toString).toVector

    NaceSections.map(_.code) shouldBe expectedCodes
    Assignments.map(_.section.code).sorted shouldBe expectedCodes
    Assignments.groupBy(_.section.code).view.mapValues(_.size).toMap.values.foreach(_ shouldBe 1)
  }

  it should "map at least one included source section to every runtime sector" in {
    SectorMappings.map(_.runtimeSector.name) shouldBe Vector(
      "BPO/SSC",
      "Manufacturing",
      "Retail/Services",
      "Healthcare",
      "Public",
      "Agriculture",
    )
    SectorMappings.foreach(_.assignments should not be empty)
  }

  it should "mark mixed business-service mappings as bridge assumptions" in {
    assignmentForSection("J").map(_.kind) shouldBe Some(AssignmentKind.BridgeAssumption)
    assignmentForSection("M").map(_.kind) shouldBe Some(AssignmentKind.BridgeAssumption)
    assignmentForSection("N").map(_.kind) shouldBe Some(AssignmentKind.BridgeAssumption)
  }

  it should "keep financial and out-of-perimeter sections outside production-sector validation" in {
    assignmentForSection("K").map(_.kind) shouldBe Some(AssignmentKind.Excluded)
    assignmentForSection("T").map(_.kind) shouldBe Some(AssignmentKind.Excluded)
    assignmentForSection("U").map(_.kind) shouldBe Some(AssignmentKind.Excluded)
    ExcludedAssignments.flatMap(_.runtimeSector) shouldBe empty
  }

  it should "label broad residual sections explicitly" in {
    val residualCodes = Assignments.collect {
      case assignment if assignment.kind == AssignmentKind.Residual => assignment.section.code
    }

    residualCodes should contain allOf ("B", "D", "E", "F", "L", "R", "S")
  }

  it should "keep direct high-level mappings distinguishable from bridge assumptions" in {
    val directBySector = SectorMappings.map { mapping =>
      mapping.runtimeSector.name -> mapping.assignments.filter(_.kind == AssignmentKind.Direct).map(_.section.code)
    }.toMap

    directBySector("Agriculture") shouldBe Vector("A")
    directBySector("Manufacturing") shouldBe Vector("C")
    directBySector("Healthcare") shouldBe Vector("Q")
    directBySector("Public") shouldBe Vector("O", "P")
    directBySector("Retail/Services") should contain allOf ("G", "H", "I")
  }
