package com.boombustgroup.amorfati.config

/** Maps source-data industry classifications into AMOR-FATI's runtime
  * production sectors.
  *
  * This is the industry/output axis of the model, not the ESA/SFC
  * institutional-sector axis used for balance sheets and ownership. For
  * example, a manufacturing firm is institutionally a firm, but its production
  * sector is Manufacturing.
  */
object ProductionSectorCrosswalk:

  /** Marks how strong each NACE-to-runtime-sector bridge is.
    *
    * Direct rows can be used as high-level empirical matches. Bridge
    * assumptions are explicit proxies for runtime concepts that have no
    * one-to-one NACE section. Residual rows keep source-data coverage complete
    * without pretending to be direct matches. Excluded rows are intentionally
    * outside the production-firm perimeter.
    */
  enum AssignmentKind(val token: String):
    case Direct           extends AssignmentKind("DIRECT")
    case BridgeAssumption extends AssignmentKind("BRIDGE_ASSUMPTION")
    case Residual         extends AssignmentKind("RESIDUAL")
    case Excluded         extends AssignmentKind("EXCLUDED")

  final case class RuntimeSector(name: String, outputStem: String)

  final case class NaceSection(code: String, label: String):
    require(code.matches("[A-U]"), s"NACE section code must be A-U, got $code")
    require(label.trim.nonEmpty, s"NACE section $code must have a label")

  final case class NaceAssignment(
      section: NaceSection,
      runtimeSector: Option[RuntimeSector],
      kind: AssignmentKind,
      note: String,
  ):
    require(note.trim.nonEmpty, s"NACE section ${section.code} must have a bridge note")
    kind match
      case AssignmentKind.Excluded =>
        require(runtimeSector.isEmpty, s"Excluded NACE section ${section.code} must not target a runtime sector")
      case _                       =>
        require(runtimeSector.nonEmpty, s"Included NACE section ${section.code} must target a runtime sector")

    def included: Boolean = runtimeSector.nonEmpty

  final case class SectorMapping(runtimeSector: RuntimeSector, assignments: Vector[NaceAssignment])

  val RuntimeSectors: Vector[RuntimeSector] =
    SimParams.SchemaSectors.map(sector => RuntimeSector(sector.name, sector.outputStem))

  /** NACE Rev. 2 sections A-U.
    *
    * Keeping the complete source taxonomy here makes missing or duplicated
    * sections fail fast when assignments are edited.
    */
  val NaceSections: Vector[NaceSection] = Vector(
    NaceSection("A", "Agriculture, forestry and fishing"),
    NaceSection("B", "Mining and quarrying"),
    NaceSection("C", "Manufacturing"),
    NaceSection("D", "Electricity, gas, steam and air conditioning supply"),
    NaceSection("E", "Water supply; sewerage, waste management and remediation"),
    NaceSection("F", "Construction"),
    NaceSection("G", "Wholesale and retail trade; repair of motor vehicles and motorcycles"),
    NaceSection("H", "Transportation and storage"),
    NaceSection("I", "Accommodation and food service activities"),
    NaceSection("J", "Information and communication"),
    NaceSection("K", "Financial and insurance activities"),
    NaceSection("L", "Real estate activities"),
    NaceSection("M", "Professional, scientific and technical activities"),
    NaceSection("N", "Administrative and support service activities"),
    NaceSection("O", "Public administration and defence; compulsory social security"),
    NaceSection("P", "Education"),
    NaceSection("Q", "Human health and social work activities"),
    NaceSection("R", "Arts, entertainment and recreation"),
    NaceSection("S", "Other service activities"),
    NaceSection("T", "Households as employers; undifferentiated household production"),
    NaceSection("U", "Activities of extraterritorial organisations and bodies"),
  )

  /** Production-sector bridge from NACE Rev. 2 sections to the six runtime
    * sectors.
    *
    * The runtime schema is intentionally coarser than NACE. K is excluded
    * because financial and insurance institutions are represented by dedicated
    * financial agents. T and U are excluded because they are not Poland
    * production-firm sectors. BPO/SSC is a model concept, so J/M/N are recorded
    * as bridge assumptions until a finer empirical service-export or
    * shared-services extraction exists.
    */
  val Assignments: Vector[NaceAssignment] = Vector(
    assign("A", "Agriculture", AssignmentKind.Direct, "Direct high-level agriculture bridge."),
    assign("B", "Manufacturing", AssignmentKind.Residual, "Industrial residual; no separate mining sector exists in the runtime schema."),
    assign("C", "Manufacturing", AssignmentKind.Direct, "Direct high-level manufacturing bridge."),
    assign("D", "Manufacturing", AssignmentKind.Residual, "Energy and utilities residual; the runtime schema has no separate energy sector."),
    assign("E", "Manufacturing", AssignmentKind.Residual, "Water and waste residual; grouped with the industrial production bridge."),
    assign("F", "Retail/Services", AssignmentKind.Residual, "Construction residual inside the broad market-services bucket."),
    assign("G", "Retail/Services", AssignmentKind.Direct, "Direct high-level trade and repair bridge."),
    assign("H", "Retail/Services", AssignmentKind.Direct, "Direct high-level transport and storage bridge."),
    assign("I", "Retail/Services", AssignmentKind.Direct, "Direct high-level accommodation and food service bridge."),
    assign("J", "BPO/SSC", AssignmentKind.BridgeAssumption, "ICT and information services proxy for the BPO/SSC runtime sector."),
    exclude("K", "Financial and insurance activities are modeled by separate financial-sector agents, not production-sector firms."),
    assign("L", "Retail/Services", AssignmentKind.Residual, "Real-estate activities residual inside broad services."),
    assign("M", "BPO/SSC", AssignmentKind.BridgeAssumption, "Professional services proxy for the business-services runtime sector."),
    assign("N", "BPO/SSC", AssignmentKind.BridgeAssumption, "Administrative support services proxy for the business-services runtime sector."),
    assign("O", "Public", AssignmentKind.Direct, "Direct public-administration bridge."),
    assign("P", "Public", AssignmentKind.Direct, "Direct education bridge for the public runtime sector."),
    assign("Q", "Healthcare", AssignmentKind.Direct, "Direct health and social-work bridge."),
    assign("R", "Retail/Services", AssignmentKind.Residual, "Arts and recreation residual inside broad services."),
    assign("S", "Retail/Services", AssignmentKind.Residual, "Other services residual inside broad services."),
    exclude("T", "Household employer and own-use production is outside the runtime production-firm sector perimeter."),
    exclude("U", "Extraterritorial organisations are outside the Poland production-sector validation perimeter."),
  )

  val IncludedAssignments: Vector[NaceAssignment] = Assignments.filter(_.included)
  val ExcludedAssignments: Vector[NaceAssignment] = Assignments.filterNot(_.included)

  val SectorMappings: Vector[SectorMapping] =
    RuntimeSectors.map: runtimeSector =>
      SectorMapping(runtimeSector, IncludedAssignments.filter(_.runtimeSector.contains(runtimeSector)))

  def assignmentForSection(code: String): Option[NaceAssignment] =
    Assignments.find(_.section.code == code)

  def mappingForSectorName(name: String): Option[SectorMapping] =
    SectorMappings.find(_.runtimeSector.name == name)

  private val assignedCodes = Assignments.map(_.section.code)
  require(
    assignedCodes.distinct.length == NaceSections.length && assignedCodes.toSet == NaceSections.map(_.code).toSet,
    "ProductionSectorCrosswalk must assign or exclude every NACE Rev. 2 section A-U exactly once",
  )
  require(
    SectorMappings.map(_.runtimeSector.name) == SimParams.SchemaSectorNames,
    s"ProductionSectorCrosswalk must preserve runtime sector order ${SimParams.SchemaSectorNames.mkString(" -> ")}",
  )
  require(
    SectorMappings.forall(_.assignments.nonEmpty),
    "ProductionSectorCrosswalk must map at least one NACE section to every runtime production sector",
  )

  private def section(code: String): NaceSection =
    NaceSections
      .find(_.code == code)
      .getOrElse(throw new IllegalArgumentException(s"Unknown NACE section code: $code"))

  private def runtimeSector(name: String): RuntimeSector =
    RuntimeSectors
      .find(_.name == name)
      .getOrElse(throw new IllegalArgumentException(s"Unknown runtime sector: $name"))

  private def assign(
      code: String,
      sectorName: String,
      kind: AssignmentKind,
      note: String,
  ): NaceAssignment =
    NaceAssignment(section(code), Some(runtimeSector(sectorName)), kind, note)

  private def exclude(code: String, note: String): NaceAssignment =
    NaceAssignment(section(code), None, AssignmentKind.Excluded, note)
