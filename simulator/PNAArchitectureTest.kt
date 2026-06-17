package fourward.simulator

import com.google.protobuf.ByteString
import fourward.Architecture
import fourward.BehavioralConfig
import fourward.BinaryOp
import fourward.BinaryOperator
import fourward.ContinuationEvent
import fourward.ControlDecl
import fourward.EnumDecl
import fourward.Expr
import fourward.ExternInstanceDecl
import fourward.FieldDecl
import fourward.HeaderDecl
import fourward.ParamDecl
import fourward.ParserDecl
import fourward.ParserState
import fourward.PipelineStage
import fourward.PipelineStageEvent.Direction
import fourward.StageKind
import fourward.Stmt
import fourward.StructDecl
import fourward.Transition
import fourward.Type
import fourward.TypeDecl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass

/**
 * Unit tests for [PNAArchitecture].
 *
 * These exercise PNA-specific pipeline semantics — drop-by-default, send_to_port, drop_packet,
 * recirculate, register read/write, and the single-pipeline structure — using minimal synthetic
 * BehavioralConfig protos.
 */
class PNAArchitectureTest {

  // ---------------------------------------------------------------------------
  // Helpers: minimal PNA config construction
  // ---------------------------------------------------------------------------

  private fun port(value: Long): DataplanePort = DataplanePort.fromUnsignedLong(value)

  private fun field(name: String, width: Int): FieldDecl =
    FieldDecl.newBuilder().setName(name).setType(bitType(width)).build()

  private fun boolField(name: String): FieldDecl =
    FieldDecl.newBuilder().setName(name).setType(Type.newBuilder().setBoolean(true)).build()

  private fun enumField(name: String, enumType: String): FieldDecl =
    FieldDecl.newBuilder().setName(name).setType(namedType(enumType)).build()

  private fun errorField(name: String): FieldDecl =
    FieldDecl.newBuilder().setName(name).setType(Type.newBuilder().setError(true)).build()

  private fun param(name: String, typeName: String): ParamDecl =
    ParamDecl.newBuilder().setName(name).setType(namedType(typeName)).build()

  // PNA metadata types — fields match pna.p4.
  private val preInputMeta =
    TypeDecl.newBuilder()
      .setName("pna_pre_input_metadata_t")
      .setStruct(
        StructDecl.newBuilder()
          .addFields(field("input_port", 32))
          .addFields(errorField("parser_error"))
          .addFields(enumField("direction", "PNA_Direction_t"))
          .addFields(field("pass", 3))
          .addFields(boolField("loopedback"))
      )
      .build()

  private val preOutputMeta =
    TypeDecl.newBuilder()
      .setName("pna_pre_output_metadata_t")
      .setStruct(
        StructDecl.newBuilder()
          .addFields(boolField("decrypt"))
          .addFields(field("said", 32))
          .addFields(field("decrypt_start_offset", 16))
      )
      .build()

  private val mainParserInputMeta =
    TypeDecl.newBuilder()
      .setName("pna_main_parser_input_metadata_t")
      .setStruct(
        StructDecl.newBuilder()
          .addFields(enumField("direction", "PNA_Direction_t"))
          .addFields(field("pass", 3))
          .addFields(boolField("loopedback"))
          .addFields(field("input_port", 32))
      )
      .build()

  private val mainInputMeta =
    TypeDecl.newBuilder()
      .setName("pna_main_input_metadata_t")
      .setStruct(
        StructDecl.newBuilder()
          .addFields(enumField("direction", "PNA_Direction_t"))
          .addFields(field("pass", 3))
          .addFields(boolField("loopedback"))
          .addFields(field("timestamp", 64))
          .addFields(errorField("parser_error"))
          .addFields(field("class_of_service", 8))
          .addFields(field("input_port", 32))
      )
      .build()

  private val mainOutputMeta =
    TypeDecl.newBuilder()
      .setName("pna_main_output_metadata_t")
      .setStruct(StructDecl.newBuilder().addFields(field("class_of_service", 8)))
      .build()

  private val directionEnum =
    TypeDecl.newBuilder()
      .setName("PNA_Direction_t")
      .setEnum(EnumDecl.newBuilder().addMembers("NET_TO_HOST").addMembers("HOST_TO_NET"))
      .build()

  private val packetPathEnum =
    TypeDecl.newBuilder()
      .setName("PNA_PacketPath_t")
      .setEnum(
        EnumDecl.newBuilder()
          .addMembers("FROM_NET_PORT")
          .addMembers("FROM_NET_LOOPEDBACK")
          .addMembers("FROM_NET_RECIRCULATED")
          .addMembers("FROM_HOST")
          .addMembers("FROM_HOST_LOOPEDBACK")
          .addMembers("FROM_HOST_RECIRCULATED")
      )
      .build()

  private val headersType =
    TypeDecl.newBuilder().setName("headers_t").setStruct(StructDecl.getDefaultInstance()).build()

  private val metaType =
    TypeDecl.newBuilder().setName("meta_t").setStruct(StructDecl.getDefaultInstance()).build()

  private val allTypes =
    listOf(
      preInputMeta,
      preOutputMeta,
      mainParserInputMeta,
      mainInputMeta,
      mainOutputMeta,
      directionEnum,
      packetPathEnum,
      headersType,
      metaType,
    )

  private val pnaArch =
    Architecture.newBuilder()
      .setName("pna")
      .addStages(stage("main_parser", "MainParser", StageKind.PARSER))
      .addStages(stage("pre_control", "PreControl", StageKind.CONTROL))
      .addStages(stage("main_control", "MainControl", StageKind.CONTROL))
      .addStages(stage("main_deparser", "MainDeparser", StageKind.DEPARSER))
      .build()

  private fun stage(name: String, blockName: String, kind: StageKind): PipelineStage =
    PipelineStage.newBuilder().setName(name).setBlockName(blockName).setKind(kind).build()

  private val noopParser =
    ParserDecl.newBuilder()
      .setName("MainParser")
      .addParams(param("pkt", "packet_in"))
      .addParams(param("hdr", "headers_t"))
      .addParams(param("meta", "meta_t"))
      .addParams(param("istd", "pna_main_parser_input_metadata_t"))
      .addStates(
        ParserState.newBuilder()
          .setName("start")
          .setTransition(Transition.newBuilder().setNextState("accept"))
      )
      .build()

  private val preControlParams =
    listOf(
      "hdr" to "headers_t",
      "meta" to "meta_t",
      "istd" to "pna_pre_input_metadata_t",
      "ostd" to "pna_pre_output_metadata_t",
    )

  private val mainControlParams =
    listOf(
      "hdr" to "headers_t",
      "meta" to "meta_t",
      "istd" to "pna_main_input_metadata_t",
      "ostd" to "pna_main_output_metadata_t",
    )

  private val mainDeparserParams =
    listOf(
      "pkt" to "packet_out",
      "hdr" to "headers_t",
      "meta" to "meta_t",
      "ostd" to "pna_main_output_metadata_t",
    )

  private fun control(
    name: String,
    params: List<Pair<String, String>>,
    stmts: List<Stmt> = emptyList(),
    externInstances: List<ExternInstanceDecl> = emptyList(),
  ): ControlDecl =
    ControlDecl.newBuilder()
      .setName(name)
      .addAllParams(params.map { (n, t) -> param(n, t) })
      .addAllApplyBody(stmts)
      .addAllExternInstances(externInstances)
      .build()

  /**
   * Builds a minimal PNA [BehavioralConfig].
   *
   * All stages default to no-op; override stage statement lists to add behaviour.
   */
  private fun pnaConfig(
    preControlStmts: List<Stmt> = emptyList(),
    parserStmts: List<Stmt> = emptyList(),
    mainControlStmts: List<Stmt> = emptyList(),
    mainDeparserStmts: List<Stmt> = emptyList(),
    mainControlExterns: List<ExternInstanceDecl> = emptyList(),
    extraTypes: List<TypeDecl> = emptyList(),
  ): BehavioralConfig =
    BehavioralConfig.newBuilder()
      .setArchitecture(pnaArch)
      .addAllTypes(allTypes + extraTypes)
      .addParsers(parser(parserStmts))
      .addControls(control("PreControl", preControlParams, preControlStmts))
      .addControls(control("MainControl", mainControlParams, mainControlStmts, mainControlExterns))
      .addControls(control("MainDeparser", mainDeparserParams, mainDeparserStmts))
      .build()

  private fun parser(stmts: List<Stmt>): ParserDecl =
    if (stmts.isEmpty()) {
      noopParser
    } else {
      ParserDecl.newBuilder(noopParser)
        .clearStates()
        .addStates(
          ParserState.newBuilder()
            .setName("start")
            .addAllStmts(stmts)
            .setTransition(Transition.newBuilder().setNextState("accept"))
        )
        .build()
    }

  /** send_to_port(port) — PNA free function with a single port argument. */
  private fun sendToPort(port: Long): Stmt = externCall("send_to_port", bit(port, 32))

  /** drop_packet() — PNA free function with no arguments. */
  private fun dropPacket(): Stmt = externCall("drop_packet")

  /** recirculate() — PNA free function with no arguments. */
  private fun recirculate(): Stmt = externCall("recirculate")

  /** Binary EQ expression with boolean result type. */
  private fun eq(left: Expr, right: Expr): Expr =
    Expr.newBuilder()
      .setBinaryOp(BinaryOp.newBuilder().setOp(BinaryOperator.EQ).setLeft(left).setRight(right))
      .setType(Type.newBuilder().setBoolean(true))
      .build()

  private fun counterInstance(name: String): ExternInstanceDecl =
    ExternInstanceDecl.newBuilder()
      .setTypeName("Counter")
      .setName(name)
      .addConstructorArgs(bit(8, 32))
      .build()

  private fun counterCount(instanceName: String, index: Long): Stmt =
    methodCallStmt(instanceName, "count", bit(index, 32), targetType = namedType("Counter"))

  private fun p4InfoWithCounter(name: String, size: Int): P4InfoOuterClass.P4Info =
    P4InfoOuterClass.P4Info.newBuilder()
      .addCounters(
        P4InfoOuterClass.Counter.newBuilder()
          .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(1).setName(name))
          .setSize(size.toLong())
      )
      .build()

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  fun `PNA drops by default without send_to_port`() {
    val config = pnaConfig()
    val result = PNAArchitecture(config).processPacket(port(0), byteArrayOf(0x01), TableStore())

    assertTrue(result.trace.hasDrop())
  }

  @Test
  fun `send_to_port forwards packet`() {
    val config = pnaConfig(mainControlStmts = listOf(sendToPort(5)))
    val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
    val result = PNAArchitecture(config).processPacket(port(0), payload, TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(5, outputs[0].dataplaneEgressPort)
    assertEquals(ByteString.copyFrom(payload), outputs[0].payload)
  }

  @Test
  fun `drop_packet explicitly drops`() {
    // send_to_port then drop_packet — last writer wins, packet drops.
    val config = pnaConfig(mainControlStmts = listOf(sendToPort(5), dropPacket()))
    val result = PNAArchitecture(config).processPacket(port(0), byteArrayOf(0x01), TableStore())

    assertTrue(result.trace.hasDrop())
  }

  @Test
  fun `send_to_port overrides drop_packet`() {
    // drop_packet then send_to_port — last writer wins, packet forwards.
    val config = pnaConfig(mainControlStmts = listOf(dropPacket(), sendToPort(5)))
    val result = PNAArchitecture(config).processPacket(port(0), byteArrayOf(0x01), TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(5, outputs[0].dataplaneEgressPort)
  }

  @Test
  fun `uint32 ingress port with high bit set reaches PNA metadata unsigned`() {
    val highBitPort = 0xFFFF_FFFEL
    val isHighBitInputPort =
      eq(fieldAccess(nameRef("istd"), "input_port", bitType(32)), bit(highBitPort, 32))
    val config =
      pnaConfig(
        mainControlStmts =
          listOf(ifStmt(condition = isHighBitInputPort, thenStmts = listOf(sendToPort(9))))
      )
    val result =
      PNAArchitecture(config).processPacket(port(highBitPort), byteArrayOf(0x01), TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(9, outputs[0].dataplaneEgressPort)
    assertEquals(
      DataplanePort.fromUnsignedLong(highBitPort).protoValue,
      result.trace.eventsList.first { it.hasPacketIngress() }.packetIngress.dataplaneIngressPort,
    )
  }

  @Test
  fun `trace has enter-exit pairs for all 4 PNA stages`() {
    val config = pnaConfig(mainControlStmts = listOf(sendToPort(1)))
    val result = PNAArchitecture(config).processPacket(port(7), byteArrayOf(0x01), TableStore())
    val events = result.trace.eventsList.filter { it.hasPacketIngress() || it.hasPipelineStage() }

    // First event: packet ingress.
    assertTrue(events[0].hasPacketIngress())
    assertEquals(7, events[0].packetIngress.dataplaneIngressPort)

    // 4 stages x 2 (enter/exit) = 8 stage events.
    // PNA execution order (matching DPDK): main_parser -> pre_control -> main_control ->
    // main_deparser.
    val stages = events.drop(1).map { it.pipelineStage }
    val expected =
      listOf(
        Triple("main_parser", StageKind.PARSER, Direction.ENTER),
        Triple("main_parser", StageKind.PARSER, Direction.EXIT),
        Triple("pre_control", StageKind.CONTROL, Direction.ENTER),
        Triple("pre_control", StageKind.CONTROL, Direction.EXIT),
        Triple("main_control", StageKind.CONTROL, Direction.ENTER),
        Triple("main_control", StageKind.CONTROL, Direction.EXIT),
        Triple("main_deparser", StageKind.DEPARSER, Direction.ENTER),
        Triple("main_deparser", StageKind.DEPARSER, Direction.EXIT),
      )
    assertEquals(expected, stages.map { Triple(it.stageName, it.stageKind, it.direction) })
  }

  @Test
  fun `register write then read returns written value`() {
    val config =
      pnaConfig(
        mainControlStmts =
          listOf(
            methodCallStmt(
              "my_reg",
              "write",
              bit(0, 32),
              bit(0xBEEF, 16),
              targetType = namedType("Register"),
            ),
            sendToPort(1),
          )
      )
    val tableStore = TableStore()
    val result = PNAArchitecture(config).processPacket(port(0), byteArrayOf(0x01), tableStore)

    // Packet should forward (register write doesn't affect drop).
    val outputs = result.possibleOutcomes.single()
    assertEquals(1, outputs.size)

    // Verify the register was actually written to the store.
    val stored = tableStore.registerRead("my_reg", 0)
    assertTrue("register should contain written value", stored is BitVal)
    assertEquals(0xBEEF.toLong(), (stored as BitVal).bits.value.toLong())
  }

  @Test
  fun `Counter count increments P4Runtime-readable packet and byte counts`() {
    val config =
      pnaConfig(
        mainControlStmts = listOf(counterCount("pkt_counter", 3), sendToPort(1)),
        mainControlExterns = listOf(counterInstance("pkt_counter")),
      )
    val tableStore = TableStore()
    tableStore.loadMappings(p4info = p4InfoWithCounter("pkt_counter", 8))

    PNAArchitecture(config).processPacket(port(0), byteArrayOf(0x01, 0x02, 0x03, 0x04), tableStore)
    PNAArchitecture(config)
      .processPacket(port(0), byteArrayOf(0xAA.toByte(), 0xBB.toByte()), tableStore)

    val results =
      tableStore.readCounterEntries(
        P4RuntimeOuterClass.CounterEntry.newBuilder()
          .setCounterId(1)
          .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(3))
          .build()
      )

    assertEquals(1, results.size)
    assertEquals(2, results[0].counterEntry.data.packetCount)
    assertEquals(6, results[0].counterEntry.data.byteCount)
  }

  @Test
  fun `recirculate exceeds max depth`() {
    // With recirculate() as the only forwarding call, every pass recirculates.
    // This should hit the MAX_RECIRCULATIONS guard.
    val config = pnaConfig(mainControlStmts = listOf(recirculate()))
    try {
      PNAArchitecture(config).processPacket(port(0), byteArrayOf(0xAA.toByte()), TableStore())
      fail("expected recirculation depth exceeded")
    } catch (e: IllegalStateException) {
      assertTrue(e.message!!.contains("recirculation depth exceeded"))
    }
  }

  @Test
  fun `recirculate emits ContinuationEvent and anchors Continuation cause`() {
    // First pass (loopedback=false): call recirculate().
    // Second pass (loopedback=true): send to port 5.
    val boolType = Type.newBuilder().setBoolean(true).build()
    val loopedback = fieldAccess(nameRef("istd"), "loopedback", boolType)
    val config =
      pnaConfig(
        mainControlStmts =
          listOf(
            ifStmt(
              condition = loopedback,
              thenStmts = listOf(sendToPort(5)),
              elseStmts = listOf(recirculate()),
            )
          )
      )
    val result = PNAArchitecture(config).processPacket(0u, byteArrayOf(0xAA.toByte()), TableStore())

    // Trace should show a CONTINUATION anchored by a RECIRCULATE ContinuationEvent.
    val contEvent = result.trace.eventsList.single { it.hasContinuationTrigger() }
    assertEquals(ContinuationEvent.Kind.RECIRCULATE, contEvent.continuationTrigger.kind)
    assertEquals(contEvent.id, result.trace.continuation.cause)
    // Second pass exits on port 5.
    assertEquals(5, result.possibleOutcomes.single().single().dataplaneEgressPort)
  }

  @Test
  fun `assertion failure drops packet`() {
    val config = pnaConfig(mainControlStmts = listOf(externCall("assert", boolLit(false))))
    val result = PNAArchitecture(config).processPacket(port(0), byteArrayOf(0x01), TableStore())

    assertTrue(result.trace.hasDrop())
  }

  // ---------------------------------------------------------------------------
  // mirror_packet tests
  // ---------------------------------------------------------------------------

  /** mirror_packet(slot, session) — PNA free function with slot and session ID arguments. */
  private fun mirrorPacket(slotId: Long, sessionId: Long): Stmt =
    externCall("mirror_packet", bit(slotId, 8), bit(sessionId, 16))

  @Test
  fun `mirror_packet creates fork with original and mirror branches`() {
    val config = pnaConfig(mainControlStmts = listOf(sendToPort(2), mirrorPacket(0, 100)))
    val store = TableStore()
    writeCloneSession(store, 100, listOf(0 to 5))

    val result = PNAArchitecture(config).processPacket(port(0), byteArrayOf(0xAA.toByte()), store)
    val outputs = result.possibleOutcomes.single()

    assertEquals(2, outputs.size)
    assertTrue(outputs.any { it.dataplaneEgressPort == 2 })
    assertTrue(outputs.any { it.dataplaneEgressPort == 5 })
  }

  @Test
  fun `mirror_packet with drop still emits mirror`() {
    // Mirror + drop: original is dropped, but mirror still goes out.
    val config = pnaConfig(mainControlStmts = listOf(mirrorPacket(0, 100)))
    val store = TableStore()
    writeCloneSession(store, 100, listOf(0 to 7))

    val result = PNAArchitecture(config).processPacket(port(0), byteArrayOf(0xBB.toByte()), store)
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(7, outputs[0].dataplaneEgressPort)
  }

  @Test
  fun `mirror_packet mirrors deparsed packet after header modification`() {
    val oneByteHeaderType =
      TypeDecl.newBuilder()
        .setName("one_byte_t")
        .setHeader(HeaderDecl.newBuilder().addFields(field("value", 8)))
        .build()
    val headersWithOneByte =
      TypeDecl.newBuilder()
        .setName("headers_t")
        .setStruct(
          StructDecl.newBuilder()
            .addFields(FieldDecl.newBuilder().setName("h").setType(namedType("one_byte_t")))
        )
        .build()
    val headerRef = fieldAccess(nameRef("hdr"), "h", namedType("one_byte_t"))
    val config =
      pnaConfig(
        parserStmts = listOf(methodCallStmt(nameRef("pkt"), "extract", headerRef)),
        mainControlStmts =
          listOf(
            assign(fieldAccess(headerRef, "value", bitType(8)), bit(0x42, 8)),
            sendToPort(2),
            mirrorPacket(0, 100),
          ),
        mainDeparserStmts = listOf(methodCallStmt(nameRef("pkt"), "emit", headerRef)),
        extraTypes = listOf(oneByteHeaderType, headersWithOneByte),
      )
    val store = TableStore()
    writeCloneSession(store, 100, listOf(0 to 5))

    val result = PNAArchitecture(config).processPacket(port(0), byteArrayOf(0x11), store)
    val outputs = result.possibleOutcomes.single()

    assertEquals(2, outputs.size)
    assertTrue(outputs.all { it.payload == ByteString.copyFrom(byteArrayOf(0x42)) })
    assertTrue(outputs.any { it.dataplaneEgressPort == 2 })
    assertTrue(outputs.any { it.dataplaneEgressPort == 5 })
  }

  @Test
  fun `mirror_packet trace has REPLICATION outcome`() {
    val config = pnaConfig(mainControlStmts = listOf(sendToPort(2), mirrorPacket(0, 100)))
    val store = TableStore()
    writeCloneSession(store, 100, listOf(0 to 5))

    val result = PNAArchitecture(config).processPacket(port(0), byteArrayOf(0x01), store)

    assertTrue(result.trace.hasReplication())
    assertEquals(2, result.trace.replication.branchesList.size)
  }

  @Test
  fun `mirror_packet with unknown session silently ignores mirror`() {
    val config = pnaConfig(mainControlStmts = listOf(sendToPort(2), mirrorPacket(0, 999)))
    val result = PNAArchitecture(config).processPacket(port(0), byteArrayOf(0x01), TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(2, outputs[0].dataplaneEgressPort)
  }

  @Test
  fun `mirror_packet with multiple replicas`() {
    val config = pnaConfig(mainControlStmts = listOf(sendToPort(2), mirrorPacket(0, 100)))
    val store = TableStore()
    writeCloneSession(store, 100, listOf(0 to 5, 1 to 6, 2 to 7))

    val result = PNAArchitecture(config).processPacket(port(0), byteArrayOf(0x01), store)
    val outputs = result.possibleOutcomes.single()

    // Original + 3 mirror replicas = 4 outputs.
    assertEquals(4, outputs.size)
    assertTrue(outputs.any { it.dataplaneEgressPort == 2 })
    assertTrue(outputs.any { it.dataplaneEgressPort == 5 })
    assertTrue(outputs.any { it.dataplaneEgressPort == 6 })
    assertTrue(outputs.any { it.dataplaneEgressPort == 7 })
  }

  @Test
  fun `mirror_packet with recirculate emits both`() {
    // Mirror is independent of recirculate — both should take effect.
    // Every pass calls recirculate(), so this hits MAX_RECIRCULATIONS, but the first
    // fork should contain both mirror and recirculate branches.
    val config =
      pnaConfig(mainControlStmts = listOf(sendToPort(2), mirrorPacket(0, 100), recirculate()))
    val store = TableStore()
    writeCloneSession(store, 100, listOf(0 to 5))

    try {
      PNAArchitecture(config).processPacket(port(0), byteArrayOf(0x01), store)
      fail("expected recirculation depth exceeded")
    } catch (e: IllegalStateException) {
      assertTrue(e.message!!.contains("recirculation depth exceeded"))
    }
  }

  // ---------------------------------------------------------------------------
  // drop_packet scope enforcement
  // ---------------------------------------------------------------------------

  @Test
  fun `drop_packet in pre_control is rejected`() {
    val config = pnaConfig(preControlStmts = listOf(dropPacket()))
    try {
      PNAArchitecture(config).processPacket(port(0), byteArrayOf(0x01), TableStore())
      fail("expected drop_packet to be rejected in pre_control")
    } catch (e: IllegalArgumentException) {
      assertTrue(e.message!!.contains("main_control"))
    }
  }
}
