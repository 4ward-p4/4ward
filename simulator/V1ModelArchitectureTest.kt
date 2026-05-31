package fourward.simulator

import com.google.protobuf.ByteString
import fourward.Architecture
import fourward.BehavioralConfig
import fourward.BinaryOp
import fourward.BinaryOperator
import fourward.BlockStmt
import fourward.ControlDecl
import fourward.DropReason
import fourward.ExitStmt
import fourward.Expr
import fourward.FieldAccess
import fourward.FieldDecl
import fourward.ForkReason
import fourward.IfStmt
import fourward.Literal
import fourward.ParamDecl
import fourward.ParserDecl
import fourward.ParserState
import fourward.PipelineStage
import fourward.PipelineStageEvent.Direction
import fourward.StageKind
import fourward.Stmt
import fourward.StructDecl
import fourward.TraceEvent
import fourward.TraceTree
import fourward.Transition
import fourward.Type
import fourward.TypeDecl
import fourward.VarDecl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass

/**
 * Unit tests for [V1ModelArchitecture].
 *
 * These exercise the pipeline orchestration — multicast replication, unicast routing, and drop
 * semantics — using minimal synthetic BehavioralConfig protos, without a full p4c compile.
 */
class V1ModelArchitectureTest {

  // ---------------------------------------------------------------------------
  // Helpers: minimal v1model config construction
  // ---------------------------------------------------------------------------

  private fun param(name: String, typeName: String): ParamDecl =
    ParamDecl.newBuilder().setName(name).setType(namedType(typeName)).build()

  /** standard_metadata_t with the minimal fields V1ModelArchitecture reads/writes. */
  private val standardMetaType: TypeDecl =
    standardMetaTypeWithPortWidth(V1ModelArchitecture.DEFAULT_PORT_BITS)

  /** Empty headers struct (no parsed headers needed for these tests). */
  private val headersType: TypeDecl =
    TypeDecl.newBuilder().setName("headers_t").setStruct(StructDecl.getDefaultInstance()).build()

  /** Empty metadata struct. */
  private val metaType: TypeDecl =
    TypeDecl.newBuilder().setName("meta_t").setStruct(StructDecl.getDefaultInstance()).build()

  private val parserParams =
    listOf(
      ParamDecl.newBuilder().setName("pkt").setType(namedType("packet_in")).build(),
      param("hdr", "headers_t"),
      param("meta", "meta_t"),
      param("sm", "standard_metadata_t"),
    )

  private val controlParams =
    listOf(param("hdr", "headers_t"), param("meta", "meta_t"), param("sm", "standard_metadata_t"))

  private fun noopControl(name: String): ControlDecl =
    ControlDecl.newBuilder().setName(name).addAllParams(controlParams).build()

  private val noopParser: ParserDecl =
    ParserDecl.newBuilder()
      .setName("MyParser")
      .addAllParams(parserParams)
      .addStates(
        ParserState.newBuilder()
          .setName("start")
          .setTransition(Transition.newBuilder().setNextState("accept"))
      )
      .build()

  private val v1modelArch: Architecture =
    Architecture.newBuilder()
      .setName("v1model")
      .addStages(
        PipelineStage.newBuilder()
          .setName("parser")
          .setKind(StageKind.PARSER)
          .setBlockName("MyParser")
      )
      .addStages(
        PipelineStage.newBuilder()
          .setName("verify_checksum")
          .setKind(StageKind.CONTROL)
          .setBlockName("MyVerifyChecksum")
      )
      .addStages(
        PipelineStage.newBuilder()
          .setName("ingress")
          .setKind(StageKind.CONTROL)
          .setBlockName("MyIngress")
      )
      .addStages(
        PipelineStage.newBuilder()
          .setName("egress")
          .setKind(StageKind.CONTROL)
          .setBlockName("MyEgress")
      )
      .addStages(
        PipelineStage.newBuilder()
          .setName("compute_checksum")
          .setKind(StageKind.CONTROL)
          .setBlockName("MyComputeChecksum")
      )
      .addStages(
        PipelineStage.newBuilder()
          .setName("deparser")
          .setKind(StageKind.DEPARSER)
          .setBlockName("MyDeparser")
      )
      .build()

  /** Wraps [body] in `if (target.fieldName == value) { body }`. */
  private fun ifFieldEquals(
    target: String,
    fieldName: String,
    value: Long,
    width: Int,
    body: Stmt,
  ): Stmt =
    Stmt.newBuilder()
      .setIfStmt(
        IfStmt.newBuilder()
          .setCondition(
            Expr.newBuilder()
              .setBinaryOp(
                BinaryOp.newBuilder()
                  .setOp(BinaryOperator.EQ)
                  .setLeft(
                    Expr.newBuilder()
                      .setFieldAccess(
                        FieldAccess.newBuilder().setExpr(nameRef(target)).setFieldName(fieldName)
                      )
                      .setType(bitType(width))
                  )
                  .setRight(
                    Expr.newBuilder()
                      .setLiteral(Literal.newBuilder().setInteger(value))
                      .setType(bitType(width))
                  )
              )
              .setType(Type.newBuilder().setBoolean(true))
          )
          .setThenBlock(BlockStmt.newBuilder().addStmts(body))
      )
      .build()

  /** Wraps [body] in `if (name == value) { body }`. */
  private fun ifNameEquals(name: String, value: Long, width: Int, body: Stmt): Stmt =
    Stmt.newBuilder()
      .setIfStmt(
        IfStmt.newBuilder()
          .setCondition(
            Expr.newBuilder()
              .setBinaryOp(
                BinaryOp.newBuilder()
                  .setOp(BinaryOperator.EQ)
                  .setLeft(nameRef(name, bitType(width)))
                  .setRight(
                    Expr.newBuilder()
                      .setLiteral(Literal.newBuilder().setInteger(value))
                      .setType(bitType(width))
                  )
              )
              .setType(Type.newBuilder().setBoolean(true))
          )
          .setThenBlock(BlockStmt.newBuilder().addStmts(body))
      )
      .build()

  private fun enumArg(member: String): Expr =
    Expr.newBuilder().setLiteral(Literal.newBuilder().setEnumMember(member)).build()

  private fun intArg(value: Long, width: Int): Expr =
    Expr.newBuilder()
      .setLiteral(Literal.newBuilder().setInteger(value))
      .setType(bitType(width))
      .build()

  /** Unsized integer literal (no type annotation). p4c emits these for some constant arguments. */
  private fun unsizedIntArg(value: Long): Expr =
    Expr.newBuilder().setLiteral(Literal.newBuilder().setInteger(value)).build()

  /**
   * Builds a minimal v1model [BehavioralConfig].
   *
   * The pipeline has: parser -> verify_checksum -> ingress ([ingressStmts]) -> egress
   * ([egressStmts]) -> compute_checksum -> deparser. All stages default to no-op.
   */
  private fun v1modelConfig(
    ingressStmts: List<Stmt> = emptyList(),
    egressStmts: List<Stmt> = emptyList(),
    parser: ParserDecl = noopParser,
    smType: TypeDecl = standardMetaType,
    ingressLocalVars: List<VarDecl> = emptyList(),
    metaTypeDecl: TypeDecl = metaType,
  ): BehavioralConfig {
    fun control(name: String, stmts: List<Stmt>, localVars: List<VarDecl> = emptyList()) =
      ControlDecl.newBuilder()
        .setName(name)
        .addAllParams(controlParams)
        .addAllLocalVars(localVars)
        .addAllApplyBody(stmts)
        .build()

    return BehavioralConfig.newBuilder()
      .setArchitecture(v1modelArch)
      .addTypes(smType)
      .addTypes(headersType)
      .addTypes(metaTypeDecl)
      .addParsers(parser)
      .addControls(noopControl("MyVerifyChecksum"))
      .addControls(control("MyIngress", ingressStmts, ingressLocalVars))
      .addControls(control("MyEgress", egressStmts))
      .addControls(noopControl("MyComputeChecksum"))
      .addControls(noopControl("MyDeparser"))
      .build()
  }

  /** Convenience overload: ingress-only statements. */
  private fun v1modelConfig(vararg stmts: Stmt): BehavioralConfig =
    v1modelConfig(ingressStmts = stmts.toList())

  /**
   * Builds a Replica using the deprecated `egress_port` field when [usePortBytes] is false, or the
   * newer `port` (bytes) field when true.
   */
  private fun buildReplica(
    egressPort: Int,
    instance: Int = 0,
    usePortBytes: Boolean = false,
  ): P4RuntimeOuterClass.Replica =
    P4RuntimeOuterClass.Replica.newBuilder()
      .setInstance(instance)
      .apply {
        if (usePortBytes) {
          setPort(ByteString.copyFrom(intToMinWidthBytes(egressPort)))
        } else {
          @Suppress("DEPRECATION") setEgressPort(egressPort)
        }
      }
      .build()

  private fun writeCloneSession(
    store: TableStore,
    sessionId: Int,
    egressPort: Int,
    instance: Int = 0,
    usePortBytes: Boolean = false,
    packetLengthBytes: Int = 0,
  ) {
    writeCloneSession(
      store,
      sessionId,
      listOf(egressPort to instance),
      usePortBytes,
      packetLengthBytes,
    )
  }

  private fun writeCloneSession(
    store: TableStore,
    sessionId: Int,
    replicas: List<Pair<Int, Int>>,
    usePortBytes: Boolean = false,
    packetLengthBytes: Int = 0,
  ) {
    store.write(
      P4RuntimeOuterClass.Update.newBuilder()
        .setType(P4RuntimeOuterClass.Update.Type.INSERT)
        .setEntity(
          P4RuntimeOuterClass.Entity.newBuilder()
            .setPacketReplicationEngineEntry(
              P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
                .setCloneSessionEntry(
                  P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
                    .setSessionId(sessionId)
                    .addAllReplicas(
                      replicas.map { (port, instance) ->
                        buildReplica(port, instance, usePortBytes)
                      }
                    )
                    .setPacketLengthBytes(packetLengthBytes)
                )
            )
        )
        .build()
    )
    store.publishSnapshot()
  }

  private fun writeMulticastGroup(
    store: TableStore,
    groupId: Int,
    replicas: List<Pair<Int, Int>>,
    usePortBytes: Boolean = false,
  ) {
    store.write(
      P4RuntimeOuterClass.Update.newBuilder()
        .setType(P4RuntimeOuterClass.Update.Type.INSERT)
        .setEntity(
          P4RuntimeOuterClass.Entity.newBuilder()
            .setPacketReplicationEngineEntry(
              P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
                .setMulticastGroupEntry(
                  P4RuntimeOuterClass.MulticastGroupEntry.newBuilder()
                    .setMulticastGroupId(groupId)
                    .addAllReplicas(
                      replicas.map { (rid, port) ->
                        buildReplica(port, instance = rid, usePortBytes = usePortBytes)
                      }
                    )
                )
            )
        )
        .build()
    )
    store.publishSnapshot()
  }

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  fun `branch trace events include variable values`() {
    // Ingress branches on egress_spec (set to 5). The trace should carry
    // the variable's runtime value so failures are self-explanatory.
    val config =
      v1modelConfig(
        assignField("sm", "egress_spec", 5, V1ModelArchitecture.DEFAULT_PORT_BITS),
        ifFieldEquals("sm", "egress_spec", 5, V1ModelArchitecture.DEFAULT_PORT_BITS, markToDrop),
      )
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    val branchEvents = result.trace.eventsList.filter { it.hasBranch() }
    val egressSpecBranch = branchEvents.first { it.branch.taken }
    assertEquals("5", egressSpecBranch.variableValuesMap["sm.egress_spec"])
    assertEquals("true", egressSpecBranch.resultValue)
  }

  @Test
  fun `assignment trace events include rhs variable values and result`() {
    // Ingress assigns egress_spec from a constant. The trace should show
    // the assigned value.
    val config =
      v1modelConfig(assignField("sm", "egress_spec", 42, V1ModelArchitecture.DEFAULT_PORT_BITS))
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    val assignEvents = result.trace.eventsList.filter { it.hasAssignment() }
    assertTrue(assignEvents.isNotEmpty())
    val egressAssign = assignEvents.first { it.assignment.target == "sm.egress_spec" }
    assertEquals("42 (0x2a)", egressAssign.resultValue)
  }

  @Test
  fun `formatValue shows decimal only for small values, both for large`() {
    // 0 — small, decimal only
    val configZero =
      v1modelConfig(assignField("sm", "egress_spec", 0, V1ModelArchitecture.DEFAULT_PORT_BITS))
    val zeroAssign =
      V1ModelArchitecture(configZero)
        .processPacket(0u, byteArrayOf(0x01), TableStore())
        .trace
        .eventsList
        .first { it.hasAssignment() && it.assignment.target == "sm.egress_spec" }
    assertEquals("0", zeroAssign.resultValue)

    // 9 — boundary, decimal only
    val configNine =
      v1modelConfig(assignField("sm", "egress_spec", 9, V1ModelArchitecture.DEFAULT_PORT_BITS))
    val nineAssign =
      V1ModelArchitecture(configNine)
        .processPacket(0u, byteArrayOf(0x01), TableStore())
        .trace
        .eventsList
        .first { it.hasAssignment() && it.assignment.target == "sm.egress_spec" }
    assertEquals("9", nineAssign.resultValue)

    // 10 — boundary, both
    val configTen =
      v1modelConfig(assignField("sm", "egress_spec", 10, V1ModelArchitecture.DEFAULT_PORT_BITS))
    val tenAssign =
      V1ModelArchitecture(configTen)
        .processPacket(0u, byteArrayOf(0x01), TableStore())
        .trace
        .eventsList
        .first { it.hasAssignment() && it.assignment.target == "sm.egress_spec" }
    assertEquals("10 (0xa)", tenAssign.resultValue)

    // 510 — large value
    val config510 =
      v1modelConfig(assignField("sm", "egress_spec", 510, V1ModelArchitecture.DEFAULT_PORT_BITS))
    val assign510 =
      V1ModelArchitecture(config510)
        .processPacket(0u, byteArrayOf(0x01), TableStore())
        .trace
        .eventsList
        .first { it.hasAssignment() && it.assignment.target == "sm.egress_spec" }
    assertEquals("510 (0x1fe)", assign510.resultValue)
  }

  @Test
  fun `table lookup trace events include key variable values`() {
    val config =
      v1modelConfig(assignField("sm", "egress_spec", 3, V1ModelArchitecture.DEFAULT_PORT_BITS))
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    // The ingress port is a table key in some pipelines. For our minimal config,
    // there are no tables — but we can verify the mechanism doesn't crash.
    // A proper test with a table key is in InterpreterControlTest.
    assertTrue(result.trace.eventsList.none { it.hasTableLookup() })
  }

  @Test
  fun `multicast fork produces output packets for each replica`() {
    val config = v1modelConfig(assignField("sm", "mcast_grp", 1, 16))
    val tableStore = TableStore()
    writeMulticastGroup(tableStore, groupId = 1, replicas = listOf(0 to 2, 0 to 3))

    val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
    val result = V1ModelArchitecture(config).processPacket(0u, payload, tableStore)
    val outputs = result.possibleOutcomes.single()

    assertEquals(2, outputs.size)
    assertEquals(2, outputs[0].dataplaneEgressPort)
    assertEquals(3, outputs[1].dataplaneEgressPort)
    // Payload should pass through unchanged (no parser extraction, no deparser emit).
    assertEquals(ByteString.copyFrom(payload), outputs[0].payload)
    assertEquals(ByteString.copyFrom(payload), outputs[1].payload)
  }

  @Test
  fun `unicast packet emits on egress_spec port`() {
    val config =
      v1modelConfig(assignField("sm", "egress_spec", 5, V1ModelArchitecture.DEFAULT_PORT_BITS))
    val payload = byteArrayOf(0x01, 0x02, 0x03)
    val result = V1ModelArchitecture(config).processPacket(0u, payload, TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(5, outputs[0].dataplaneEgressPort)
    assertEquals(ByteString.copyFrom(payload), outputs[0].payload)
  }

  @Test
  fun `truncate caps output packet after deparser`() {
    val config =
      v1modelConfig(
        externCall("truncate", intArg(2, 32)),
        assignField("sm", "egress_spec", 5, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    val result = V1ModelArchitecture(config).processPacket(0u, payload, TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(5, outputs[0].dataplaneEgressPort)
    assertEquals(ByteString.copyFrom(byteArrayOf(0x01, 0x02)), outputs[0].payload)
  }

  @Test
  fun `truncate zero emits empty packet`() {
    val config =
      v1modelConfig(
        externCall("truncate", intArg(0, 32)),
        assignField("sm", "egress_spec", 5, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val payload = byteArrayOf(0x01, 0x02, 0x03)
    val result = V1ModelArchitecture(config).processPacket(0u, payload, TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(5, outputs[0].dataplaneEgressPort)
    assertEquals(ByteString.EMPTY, outputs[0].payload)
  }

  @Test
  fun `truncate longer than packet is a no-op`() {
    val config =
      v1modelConfig(
        externCall("truncate", intArg(100, 32)),
        assignField("sm", "egress_spec", 5, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val payload = byteArrayOf(0x01, 0x02)
    val result = V1ModelArchitecture(config).processPacket(0u, payload, TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(5, outputs[0].dataplaneEgressPort)
    assertEquals(ByteString.copyFrom(payload), outputs[0].payload)
  }

  @Test
  fun `truncate preserves shortest requested length`() {
    val config =
      v1modelConfig(
        externCall("truncate", intArg(1, 32)),
        externCall("truncate", intArg(3, 32)),
        assignField("sm", "egress_spec", 5, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    val result = V1ModelArchitecture(config).processPacket(0u, payload, TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(5, outputs[0].dataplaneEgressPort)
    assertEquals(ByteString.copyFrom(byteArrayOf(0x01)), outputs[0].payload)
  }

  @Test
  fun `truncate uses shorter later requested length`() {
    val config =
      v1modelConfig(
        externCall("truncate", intArg(3, 32)),
        externCall("truncate", intArg(1, 32)),
        assignField("sm", "egress_spec", 5, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
    val result = V1ModelArchitecture(config).processPacket(0u, payload, TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(5, outputs[0].dataplaneEgressPort)
    assertEquals(ByteString.copyFrom(byteArrayOf(0x01)), outputs[0].payload)
  }

  @Test
  fun `mark_to_drop produces no output packets`() {
    val config =
      v1modelConfig(
        assignField(
          "sm",
          "egress_spec",
          V1ModelArchitecture.DEFAULT_DROP_PORT.toLong(),
          V1ModelArchitecture.DEFAULT_PORT_BITS,
        )
      )
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
  }

  @Test
  fun `traffic manager drop skips egress`() {
    // When egress_spec is the drop port after ingress, the traffic manager drops the packet
    // before egress runs. Verify that no egress/compute_checksum stage events appear.
    val config =
      v1modelConfig(
        assignField(
          "sm",
          "egress_spec",
          V1ModelArchitecture.DEFAULT_DROP_PORT.toLong(),
          V1ModelArchitecture.DEFAULT_PORT_BITS,
        )
      )
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    val stageNames =
      result.trace.eventsList.filter { it.hasPipelineStage() }.map { it.pipelineStage.stageName }
    assertTrue("egress should not appear in trace", "egress" !in stageNames)
    assertTrue("compute_checksum should not appear in trace", "compute_checksum" !in stageNames)
    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
  }

  @Test
  fun `execute_meter does not affect forwarding`() {
    // Meters always return GREEN in the simulator (no real packet rates).
    // Verify the pipeline still forwards normally after a meter call.
    val config =
      v1modelConfig(
        ingressLocalVars =
          listOf(VarDecl.newBuilder().setName("color").setType(bitType(8)).build()),
        ingressStmts =
          listOf(
            methodCallStmt(
              "my_meter",
              "execute_meter",
              bit(0, 32),
              nameRef("color", bitType(8)),
              targetType = namedType("meter"),
            ),
            assignField("sm", "egress_spec", 5, V1ModelArchitecture.DEFAULT_PORT_BITS),
          ),
      )
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(5, outputs[0].dataplaneEgressPort)
  }

  @Test
  fun `execute_meter returns GREEN even when meter config is present`() {
    val config =
      v1modelConfig(
        ingressLocalVars =
          listOf(VarDecl.newBuilder().setName("color").setType(bitType(8)).build()),
        ingressStmts =
          listOf(
            methodCallStmt(
              "my_meter",
              "execute_meter",
              bit(0, 32),
              nameRef("color", bitType(8)),
              targetType = namedType("meter"),
            ),
            ifNameEquals(
              "color",
              0,
              8,
              assignField("sm", "egress_spec", 5, V1ModelArchitecture.DEFAULT_PORT_BITS),
            ),
          ),
      )
    val tableStore = TableStore()
    tableStore.loadMappings(
      p4info =
        P4InfoOuterClass.P4Info.newBuilder()
          .addMeters(
            P4InfoOuterClass.Meter.newBuilder()
              .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(1).setName("my_meter"))
              .setSize(1)
          )
          .build()
    )
    assertEquals(
      WriteResult.Success,
      tableStore.write(
        P4RuntimeOuterClass.Update.newBuilder()
          .setType(P4RuntimeOuterClass.Update.Type.MODIFY)
          .setEntity(
            P4RuntimeOuterClass.Entity.newBuilder()
              .setMeterEntry(
                P4RuntimeOuterClass.MeterEntry.newBuilder()
                  .setMeterId(1)
                  .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(0))
                  .setConfig(P4RuntimeOuterClass.MeterConfig.newBuilder().setCir(1).setPir(1))
              )
          )
          .build()
      ),
    )
    tableStore.publishSnapshot()

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), tableStore)
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(5, outputs[0].dataplaneEgressPort)
  }

  @Test
  fun `random writes inclusive random value to out parameter`() {
    val config =
      v1modelConfig(
        ingressLocalVars =
          listOf(VarDecl.newBuilder().setName("value").setType(bitType(8)).build()),
        ingressStmts =
          listOf(
            externCall("random", nameRef("value", bitType(8)), bit(7, 8), bit(7, 8)),
            ifNameEquals(
              "value",
              7,
              8,
              assignField("sm", "egress_spec", 5, V1ModelArchitecture.DEFAULT_PORT_BITS),
            ),
          ),
      )
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(5, outputs[0].dataplaneEgressPort)
  }

  @Test
  fun `multicast fork trace tree has fork node`() {
    val config = v1modelConfig(assignField("sm", "mcast_grp", 1, 16))
    val tableStore = TableStore()
    writeMulticastGroup(tableStore, groupId = 1, replicas = listOf(0 to 2, 0 to 3))

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), tableStore)

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.MULTICAST, result.trace.forkOutcome.reason)
    assertEquals(2, result.trace.forkOutcome.branchesCount)
  }

  @Test
  fun `I2E clone forks into original and clone branch`() {
    // Ingress calls clone(I2E, session=1), sets egress_spec=2 for the original.
    val config =
      v1modelConfig(
        externCall("clone", enumArg("I2E"), intArg(1, 32)),
        assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 7)

    val payload = byteArrayOf(0xAA.toByte())
    val result = V1ModelArchitecture(config).processPacket(0u, payload, tableStore)

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.CLONE, result.trace.forkOutcome.reason)
    val branches = result.trace.forkOutcome.branchesList
    assertEquals(2, branches.size)
    assertEquals("original", branches[0].label)
    assertEquals("clone", branches[1].label)

    val outputs = result.possibleOutcomes.single()
    assertEquals(2, outputs.size)
    // Original branch uses egress_spec set by ingress.
    assertEquals(2, outputs[0].dataplaneEgressPort)
    // Clone branch uses the clone session's egress port.
    assertEquals(7, outputs[1].dataplaneEgressPort)
  }

  @Test
  fun `I2E clone works with Replica port bytes field`() {
    val config =
      v1modelConfig(
        externCall("clone", enumArg("I2E"), intArg(1, 32)),
        assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 7, usePortBytes = true)

    val result =
      V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0xAA.toByte()), tableStore)
    val outputs = result.possibleOutcomes.single()

    assertEquals(2, outputs.size)
    assertEquals(2, outputs[0].dataplaneEgressPort)
    assertEquals(7, outputs[1].dataplaneEgressPort)
  }

  @Test
  fun `I2E clone sets egress_rid from clone session replica instance`() {
    // Egress copies egress_rid into a user metadata field, then uses it to
    // decide whether to drop. Verifies the replica's instance field flows
    // through to standard_metadata.egress_rid in the clone branch.
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(
            externCall("clone", enumArg("I2E"), intArg(1, 32)),
            assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
          ),
        egressStmts =
          listOf(
            // In the clone branch, drop if egress_rid == 0 (the default / unfixed value).
            ifFieldEquals(
              "sm",
              "instance_type",
              1,
              32,
              ifFieldEquals("sm", "egress_rid", 0, 16, markToDrop),
            )
          ),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 7, instance = 42)

    val result =
      V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0xAA.toByte()), tableStore)
    val outputs = result.possibleOutcomes.single()

    assertEquals(2, outputs.size)
    // Original: egress_spec=2, not a clone so egress doesn't drop.
    assertEquals(2, outputs[0].dataplaneEgressPort)
    // Clone: if egress_rid is correctly set to 42 (not 0), the clone survives.
    assertEquals(7, outputs[1].dataplaneEgressPort)
  }

  @Test
  fun `I2E clone works with multi-byte Replica port`() {
    val config =
      v1modelConfig(
        externCall("clone", enumArg("I2E"), intArg(1, 32)),
        assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val tableStore = TableStore()
    // Port 510 = 0x01FE, exercises multi-byte decoding.
    writeCloneSession(tableStore, sessionId = 1, egressPort = 510, usePortBytes = true)

    val result =
      V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0xAA.toByte()), tableStore)
    val outputs = result.possibleOutcomes.single()

    assertEquals(2, outputs.size)
    assertEquals(510, outputs[1].dataplaneEgressPort)
  }

  @Test
  fun `multicast works with Replica port bytes field`() {
    val config = v1modelConfig(assignField("sm", "mcast_grp", 1, 16))
    val tableStore = TableStore()
    writeMulticastGroup(
      tableStore,
      groupId = 1,
      replicas = listOf(0 to 5, 0 to 9),
      usePortBytes = true,
    )

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), tableStore)
    val outputs = result.possibleOutcomes.single()

    assertEquals(2, outputs.size)
    assertEquals(5, outputs[0].dataplaneEgressPort)
    assertEquals(9, outputs[1].dataplaneEgressPort)
  }

  @Test
  fun `E2E clone forks into original and clone branch`() {
    // Egress calls clone(E2E, session=1); ingress sets egress_spec=3 for routing.
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(assignField("sm", "egress_spec", 3, V1ModelArchitecture.DEFAULT_PORT_BITS)),
        egressStmts = listOf(externCall("clone", enumArg("E2E"), intArg(1, 32))),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 8)

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), tableStore)

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.CLONE, result.trace.forkOutcome.reason)
    val branches = result.trace.forkOutcome.branchesList
    assertEquals(2, branches.size)
    assertEquals("original", branches[0].label)
    assertEquals("clone", branches[1].label)

    val outputs = result.possibleOutcomes.single()
    assertEquals(2, outputs.size)
    assertEquals(3, outputs[0].dataplaneEgressPort)
    assertEquals(8, outputs[1].dataplaneEgressPort)
  }

  @Test
  fun `E2E clone sets egress_rid from clone session replica instance`() {
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(assignField("sm", "egress_spec", 3, V1ModelArchitecture.DEFAULT_PORT_BITS)),
        egressStmts =
          listOf(
            // First egress pass (instance_type == 0): trigger E2E clone.
            ifFieldEquals(
              "sm",
              "instance_type",
              0,
              32,
              externCall("clone", enumArg("E2E"), intArg(1, 32)),
            ),
            // Clone pass (instance_type == 2): drop if egress_rid == 0.
            ifFieldEquals(
              "sm",
              "instance_type",
              2,
              32,
              ifFieldEquals("sm", "egress_rid", 0, 16, markToDrop),
            ),
          ),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 8, instance = 7)

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), tableStore)
    val outputs = result.possibleOutcomes.single()

    assertEquals(2, outputs.size)
    assertEquals(3, outputs[0].dataplaneEgressPort)
    // Clone survives because egress_rid=7, not 0.
    assertEquals(8, outputs[1].dataplaneEgressPort)
  }

  @Test
  fun `I2E clone with multi-replica session produces one clone per replica`() {
    val config =
      v1modelConfig(
        externCall("clone", enumArg("I2E"), intArg(1, 32)),
        assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, replicas = listOf(7 to 10, 8 to 20))

    val result =
      V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0xAA.toByte()), tableStore)

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.CLONE, result.trace.forkOutcome.reason)
    val branches = result.trace.forkOutcome.branchesList
    assertEquals(3, branches.size)
    assertEquals("original", branches[0].label)
    assertEquals("clone_10_port_7", branches[1].label)
    assertEquals("clone_20_port_8", branches[2].label)

    val outputs = result.possibleOutcomes.single()
    assertEquals(3, outputs.size)
    assertEquals(2, outputs[0].dataplaneEgressPort)
    assertEquals(7, outputs[1].dataplaneEgressPort)
    assertEquals(8, outputs[2].dataplaneEgressPort)
  }

  @Test
  fun `E2E clone with multi-replica session produces one clone per replica`() {
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(assignField("sm", "egress_spec", 3, V1ModelArchitecture.DEFAULT_PORT_BITS)),
        egressStmts =
          listOf(
            ifFieldEquals(
              "sm",
              "instance_type",
              0,
              32,
              externCall("clone", enumArg("E2E"), intArg(1, 32)),
            )
          ),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, replicas = listOf(5 to 1, 6 to 2))

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), tableStore)

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.CLONE, result.trace.forkOutcome.reason)
    val branches = result.trace.forkOutcome.branchesList
    assertEquals(3, branches.size)
    assertEquals("original", branches[0].label)

    val outputs = result.possibleOutcomes.single()
    assertEquals(3, outputs.size)
    assertEquals(3, outputs[0].dataplaneEgressPort)
    assertEquals(5, outputs[1].dataplaneEgressPort)
    assertEquals(6, outputs[2].dataplaneEgressPort)
  }

  @Test
  fun `multi-replica clone sets correct egress_rid per replica`() {
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(
            externCall("clone", enumArg("I2E"), intArg(1, 32)),
            assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
          ),
        egressStmts =
          listOf(
            ifFieldEquals(
              "sm",
              "instance_type",
              1,
              32,
              ifFieldEquals("sm", "egress_rid", 0, 16, markToDrop),
            )
          ),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, replicas = listOf(7 to 0, 8 to 10))

    val result =
      V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0xAA.toByte()), tableStore)
    val outputs = result.possibleOutcomes.single()

    assertEquals(2, outputs.size)
    assertEquals(2, outputs[0].dataplaneEgressPort)
    assertEquals(8, outputs[1].dataplaneEgressPort)
  }

  @Test
  fun `multi-replica clone preserves metadata on all clone branches`() {
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(
            assignField("meta", "preserved", 0xABCD, 16),
            externCall("clone_preserving_field_list", enumArg("I2E"), intArg(1, 32), intArg(1, 8)),
            assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
          ),
        egressStmts =
          listOf(
            ifFieldEquals(
              "sm",
              "instance_type",
              1,
              32,
              ifFieldEquals("meta", "preserved", 0, 16, externCall("mark_to_drop", nameRef("sm"))),
            )
          ),
        metaTypeDecl = metaTypeWithFieldList,
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, replicas = listOf(7 to 1, 8 to 2))

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), tableStore)
    val outputs = result.possibleOutcomes.single()

    assertEquals(3, outputs.size)
    assertEquals(2, outputs[0].dataplaneEgressPort)
    assertEquals(7, outputs[1].dataplaneEgressPort)
    assertEquals(8, outputs[2].dataplaneEgressPort)
  }

  @Test
  fun `clone session trace event includes egress_rid`() {
    val config =
      v1modelConfig(
        externCall("clone", enumArg("I2E"), intArg(1, 32)),
        assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 7, instance = 42)

    val result =
      V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0xAA.toByte()), tableStore)
    val cloneEvent = result.trace.eventsList.first { it.hasCloneSessionLookup() }.cloneSessionLookup

    assertTrue(cloneEvent.sessionFound)
    assertEquals(1, cloneEvent.sessionId)
    assertEquals(7, cloneEvent.dataplaneEgressPort)
    assertEquals(42, cloneEvent.egressRid)
    assertEquals(1, cloneEvent.replicaCount)
  }

  @Test
  fun `clone session trace event reports replica_count for multi-replica sessions`() {
    val config =
      v1modelConfig(
        externCall("clone", enumArg("I2E"), intArg(1, 32)),
        assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, replicas = listOf(7 to 10, 8 to 20))

    val result =
      V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0xAA.toByte()), tableStore)
    val cloneEvent = result.trace.eventsList.first { it.hasCloneSessionLookup() }.cloneSessionLookup

    assertTrue(cloneEvent.sessionFound)
    assertEquals(2, cloneEvent.replicaCount)
    assertEquals(7, cloneEvent.dataplaneEgressPort)
    assertEquals(10, cloneEvent.egressRid)
  }

  @Test
  fun `resubmit forks and re-enters ingress`() {
    // Ingress calls resubmit() only on the first pass (instance_type == 0).
    // The resubmit branch gets instance_type=6 (RESUBMIT), so it won't re-trigger.
    val config =
      v1modelConfig(
        ifFieldEquals("sm", "instance_type", 0, 32, externCall("resubmit", intArg(0, 8)))
      )

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.RESUBMIT, result.trace.forkOutcome.reason)
    val branches = result.trace.forkOutcome.branchesList
    assertEquals(1, branches.size)
    assertEquals("resubmit", branches[0].label)
  }

  @Test
  fun `recirculate forks after deparser`() {
    // Egress calls recirculate() only on the first pass (instance_type == 0).
    // The recirculated branch gets instance_type=4, so it won't re-trigger.
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(assignField("sm", "egress_spec", 1, V1ModelArchitecture.DEFAULT_PORT_BITS)),
        egressStmts =
          listOf(
            ifFieldEquals("sm", "instance_type", 0, 32, externCall("recirculate", intArg(0, 8)))
          ),
      )

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.RECIRCULATE, result.trace.forkOutcome.reason)
    val branches = result.trace.forkOutcome.branchesList
    assertEquals(1, branches.size)
    assertEquals("recirculate", branches[0].label)
  }

  @Test
  fun `I2E clone with missing session is silently ignored`() {
    // Clone session 99 is never installed — BMv2 silently ignores the clone.
    // No fork appears; the packet outputs normally.
    val config =
      v1modelConfig(
        externCall("clone", enumArg("I2E"), intArg(99, 32)),
        assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    assertFalse(result.trace.hasForkOutcome())
    val outputs = result.possibleOutcomes.single()
    assertEquals(1, outputs.size)
    assertEquals(2, outputs[0].dataplaneEgressPort)
  }

  @Test
  fun `E2E clone with missing session is silently ignored`() {
    // Clone session 99 is never installed — BMv2 silently ignores the clone.
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(assignField("sm", "egress_spec", 3, V1ModelArchitecture.DEFAULT_PORT_BITS)),
        egressStmts = listOf(externCall("clone", enumArg("E2E"), intArg(99, 32))),
      )

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    assertFalse(result.trace.hasForkOutcome())
    val outputs = result.possibleOutcomes.single()
    assertEquals(1, outputs.size)
    assertEquals(3, outputs[0].dataplaneEgressPort)
  }

  @Test
  fun `I2E clone with packet_length_bytes truncates cloned packet`() {
    val config =
      v1modelConfig(
        externCall("clone", enumArg("I2E"), intArg(1, 32)),
        assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 7, packetLengthBytes = 3)

    val payload =
      byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte())
    val result = V1ModelArchitecture(config).processPacket(0u, payload, tableStore)

    assertTrue(result.trace.hasForkOutcome())
    val outputs = result.possibleOutcomes.single()
    assertEquals(2, outputs.size)
    assertEquals(5, outputs[0].payload.size())
    assertEquals(3, outputs[1].payload.size())
    assertEquals(payload.sliceArray(0..2).toList(), outputs[1].payload.toByteArray().toList())
  }

  @Test
  fun `E2E clone with packet_length_bytes truncates cloned packet`() {
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(assignField("sm", "egress_spec", 3, V1ModelArchitecture.DEFAULT_PORT_BITS)),
        egressStmts = listOf(externCall("clone", enumArg("E2E"), intArg(1, 32))),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 8, packetLengthBytes = 2)

    val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
    val result = V1ModelArchitecture(config).processPacket(0u, payload, tableStore)

    assertTrue(result.trace.hasForkOutcome())
    val outputs = result.possibleOutcomes.single()
    assertEquals(2, outputs.size)
    assertEquals(4, outputs[0].payload.size())
    assertEquals(2, outputs[1].payload.size())
  }

  @Test
  fun `clone with packet_length_bytes=0 does not truncate`() {
    val config =
      v1modelConfig(
        externCall("clone", enumArg("I2E"), intArg(1, 32)),
        assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 7, packetLengthBytes = 0)

    val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
    val result = V1ModelArchitecture(config).processPacket(0u, payload, tableStore)

    val outputs = result.possibleOutcomes.single()
    assertEquals(2, outputs.size)
    assertEquals(3, outputs[0].payload.size())
    assertEquals(3, outputs[1].payload.size())
  }

  @Test
  fun `clone truncation larger than packet is a no-op`() {
    val config =
      v1modelConfig(
        externCall("clone", enumArg("I2E"), intArg(1, 32)),
        assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 7, packetLengthBytes = 100)

    val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
    val result = V1ModelArchitecture(config).processPacket(0u, payload, tableStore)

    val outputs = result.possibleOutcomes.single()
    assertEquals(2, outputs.size)
    assertEquals(2, outputs[1].payload.size())
  }

  @Test
  fun `unknown multicast group falls through to unicast`() {
    // mcast_grp is set but the group isn't installed — BMv2 treats this as unicast/drop.
    val config =
      v1modelConfig(
        assignField("sm", "mcast_grp", 42, 16),
        assignField("sm", "egress_spec", 5, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    // No fork — falls through to unicast path.
    val outputs = result.possibleOutcomes.single()
    assertEquals(1, outputs.size)
    assertEquals(5, outputs[0].dataplaneEgressPort)
  }

  // ---------------------------------------------------------------------------
  // Stale egress_spec tests — verify mark_to_drop() from ingress or a prior
  // egress run doesn't leak through to replicas, I2E clones, or E2E clones.
  // ---------------------------------------------------------------------------

  /** mark_to_drop() call as an ingress statement. */
  private val markToDrop: Stmt = externCall("mark_to_drop", nameRef("sm"))

  @Test
  fun `multicast replicas survive ingress mark_to_drop`() {
    // Ingress calls mark_to_drop(), then sets mcast_grp. Replicas must still be
    // forwarded — the post-egress drop check should only trigger on mark_to_drop()
    // called during egress, not on stale ingress state.
    val config = v1modelConfig(markToDrop, assignField("sm", "mcast_grp", 1, 16))
    val tableStore = TableStore()
    writeMulticastGroup(tableStore, groupId = 1, replicas = listOf(0 to 2, 0 to 3))

    val result =
      V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0xAA.toByte()), tableStore)
    val outputs = result.possibleOutcomes.single()

    assertEquals(2, outputs.size)
    assertEquals(2, outputs[0].dataplaneEgressPort)
    assertEquals(3, outputs[1].dataplaneEgressPort)
  }

  @Test
  fun `I2E clone survives ingress mark_to_drop on original`() {
    // Ingress calls clone(I2E) then mark_to_drop(). The original should be dropped
    // (egress_spec == drop port), but the clone branch should still forward.
    val config = v1modelConfig(externCall("clone", enumArg("I2E"), intArg(1, 32)), markToDrop)
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 7)

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), tableStore)

    assertTrue(result.trace.hasForkOutcome())
    val outputs = result.possibleOutcomes.single()
    // Original is dropped (mark_to_drop set egress_spec to drop port).
    // Clone survives on port 7.
    assertEquals(1, outputs.size)
    assertEquals(7, outputs[0].dataplaneEgressPort)
  }

  @Test
  fun `E2E clone survives egress mark_to_drop on original`() {
    // Egress calls clone(E2E) then mark_to_drop(). The original should be dropped,
    // but the clone's second egress run should start with a clean egress_spec.
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(assignField("sm", "egress_spec", 3, V1ModelArchitecture.DEFAULT_PORT_BITS)),
        egressStmts =
          listOf(
            // Only clone + drop on the first egress pass (instance_type == 0).
            ifFieldEquals(
              "sm",
              "instance_type",
              0,
              32,
              externCall("clone", enumArg("E2E"), intArg(1, 32)),
            ),
            ifFieldEquals("sm", "instance_type", 0, 32, markToDrop),
          ),
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 8)

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), tableStore)

    assertTrue(result.trace.hasForkOutcome())
    val outputs = result.possibleOutcomes.single()
    // Original is dropped (egress mark_to_drop). Clone survives on port 8.
    assertEquals(1, outputs.size)
    assertEquals(8, outputs[0].dataplaneEgressPort)
  }

  // ---------------------------------------------------------------------------
  // Stage event tests
  // ---------------------------------------------------------------------------

  /** Extracts only PacketIngressEvent and PipelineStageEvent from a trace's events. */
  private fun stageEvents(tree: TraceTree): List<TraceEvent> =
    tree.eventsList.filter { it.hasPacketIngress() || it.hasPipelineStage() }

  @Test
  fun `trace starts with packet ingress and has enter-exit pairs for all stages`() {
    val config =
      v1modelConfig(assignField("sm", "egress_spec", 1, V1ModelArchitecture.DEFAULT_PORT_BITS))
    val result = V1ModelArchitecture(config).processPacket(7u, byteArrayOf(0x01), TableStore())
    val events = stageEvents(result.trace)

    // First event: packet ingress with correct port.
    assertTrue(events[0].hasPacketIngress())
    assertEquals(7, events[0].packetIngress.dataplaneIngressPort)

    // Remaining events: enter/exit pairs for parser, 4 controls, deparser.
    data class StageStep(val name: String, val kind: StageKind, val direction: Direction)

    val stages =
      events.drop(1).map {
        StageStep(
          it.pipelineStage.stageName,
          it.pipelineStage.stageKind,
          it.pipelineStage.direction,
        )
      }
    val expected =
      listOf(
        StageStep("parser", StageKind.PARSER, Direction.ENTER),
        StageStep("parser", StageKind.PARSER, Direction.EXIT),
        StageStep("verify_checksum", StageKind.CONTROL, Direction.ENTER),
        StageStep("verify_checksum", StageKind.CONTROL, Direction.EXIT),
        StageStep("ingress", StageKind.CONTROL, Direction.ENTER),
        StageStep("ingress", StageKind.CONTROL, Direction.EXIT),
        StageStep("egress", StageKind.CONTROL, Direction.ENTER),
        StageStep("egress", StageKind.CONTROL, Direction.EXIT),
        StageStep("compute_checksum", StageKind.CONTROL, Direction.ENTER),
        StageStep("compute_checksum", StageKind.CONTROL, Direction.EXIT),
        StageStep("deparser", StageKind.DEPARSER, Direction.ENTER),
        StageStep("deparser", StageKind.DEPARSER, Direction.EXIT),
      )
    assertEquals(expected, stages)
  }

  @Test
  fun `parser exit emits EXIT event before drop`() {
    // Parser with an exit statement — triggers ExitException in the parser.
    val exitParser =
      ParserDecl.newBuilder()
        .setName("MyParser")
        .addAllParams(parserParams)
        .addStates(
          ParserState.newBuilder()
            .setName("start")
            .addStmts(Stmt.newBuilder().setExit(ExitStmt.getDefaultInstance()))
            .setTransition(Transition.newBuilder().setNextState("accept"))
        )
        .build()

    val config = v1modelConfig(parser = exitParser)
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    // Should be a drop.
    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
    assertEquals(DropReason.MARK_TO_DROP, result.trace.packetOutcome.drop.reason)

    // Parser EXIT event must be present even though the parser exited early.
    val events = stageEvents(result.trace)
    val stages = events.drop(1).map { it.pipelineStage }
    assertEquals(
      listOf(StageKind.PARSER to Direction.ENTER, StageKind.PARSER to Direction.EXIT),
      stages.map { it.stageKind to it.direction },
    )
  }

  // ---------------------------------------------------------------------------
  // Wider port width tests (Track 5: architecture customization)
  // ---------------------------------------------------------------------------

  /** Builds a standard_metadata_t type with a custom port bit width. */
  private fun standardMetaTypeWithPortWidth(portBits: Int): TypeDecl =
    TypeDecl.newBuilder()
      .setName("standard_metadata_t")
      .setStruct(
        StructDecl.newBuilder()
          .addFields(field("ingress_port", portBits))
          .addFields(field("egress_spec", portBits))
          .addFields(field("egress_port", portBits))
          .addFields(field("instance_type", 32))
          .addFields(field("packet_length", 32))
          .addFields(field("mcast_grp", 16))
          .addFields(field("egress_rid", 16))
          .addFields(field("checksum_error", 1))
          .addFields(field("parser_error", 32))
      )
      .build()

  /** Builds a v1model config with a custom port width and ingress statements. */
  private fun widePortConfig(portBits: Int, vararg stmts: Stmt): BehavioralConfig =
    v1modelConfig(ingressStmts = stmts.toList(), smType = standardMetaTypeWithPortWidth(portBits))

  @Test
  fun `wider port width unicast works with large port numbers`() {
    val portBits = 16
    val largePort = 1000L // beyond bit<9> range
    val config = widePortConfig(portBits, assignField("sm", "egress_spec", largePort, portBits))
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(largePort.toInt(), outputs[0].dataplaneEgressPort)
  }

  @Test
  fun `wider port width drop uses correct all-ones value`() {
    val portBits = 16
    val dropPort = (1L shl portBits) - 1 // 65535, not 511
    val config = widePortConfig(portBits, assignField("sm", "egress_spec", dropPort, portBits))
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
  }

  @Test
  fun `wider port width does not drop on standard drop port value`() {
    // Port 511 is NOT the drop port when port width is 16 bits — it's a valid port.
    val portBits = 16
    val config = widePortConfig(portBits, assignField("sm", "egress_spec", 511, portBits))
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(511, outputs[0].dataplaneEgressPort)
  }

  @Test
  fun `wider port mark_to_drop sets correct all-ones value`() {
    // Exercises the Interpreter's mark_to_drop path (which derives drop value from the struct's
    // egress_spec width independently) and the Architecture's drop detection (which uses
    // PipelineState.dropPort derived from ingress_port width). Both must agree.
    val portBits = 16
    val markToDrop = externCall("mark_to_drop", nameRef("sm"))
    val config = widePortConfig(portBits, markToDrop)
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
  }

  @Test
  fun `32-bit port width works without overflow`() {
    val portBits = 32
    val largePort = 100_000L
    val config = widePortConfig(portBits, assignField("sm", "egress_spec", largePort, portBits))
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(largePort.toInt(), outputs[0].dataplaneEgressPort)
  }

  // ---------------------------------------------------------------------------
  // Metadata preservation tests (clone_preserving_field_list)
  // ---------------------------------------------------------------------------

  /** Metadata struct with `preserved` in field list 1 and `not_preserved` in no list. */
  private val metaTypeWithFieldList: TypeDecl =
    TypeDecl.newBuilder()
      .setName("meta_t")
      .setStruct(
        StructDecl.newBuilder()
          .addFields(
            FieldDecl.newBuilder().setName("preserved").setType(bitType(16)).addFieldListIds(1)
          )
          .addFields(field("not_preserved", 16))
      )
      .build()

  @Test
  fun `clone_preserving_field_list preserves annotated metadata`() {
    // Ingress: set meta.preserved = 0xABCD, then clone with field_list 1.
    // Egress (clone branch only): if meta.preserved == 0 → drop.
    // With preservation working, meta.preserved is 0xABCD on the clone, so no drop → 2 outputs.
    // Without preservation, meta.preserved would be 0 (default), so clone drops → 1 output.
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(
            assignField("meta", "preserved", 0xABCD, 16),
            externCall("clone_preserving_field_list", enumArg("I2E"), intArg(1, 32), intArg(1, 8)),
            assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
          ),
        egressStmts =
          listOf(
            // On clone branch (instance_type == 1): drop if metadata was reset.
            ifFieldEquals(
              "sm",
              "instance_type",
              1,
              32,
              ifFieldEquals("meta", "preserved", 0, 16, externCall("mark_to_drop", nameRef("sm"))),
            )
          ),
        metaTypeDecl = metaTypeWithFieldList,
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 7)

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), tableStore)

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.CLONE, result.trace.forkOutcome.reason)
    val outputs = result.possibleOutcomes.single()
    // Both original and clone produce output (metadata was preserved, so no drop).
    assertEquals(2, outputs.size)
    assertEquals(2, outputs[0].dataplaneEgressPort)
    assertEquals(7, outputs[1].dataplaneEgressPort)
  }

  @Test
  fun `E2E clone_preserving_field_list preserves annotated metadata`() {
    // Ingress: set meta.preserved = 0xABCD, set egress_spec = 3.
    // Egress (first pass, instance_type == 0): clone E2E with field_list 1.
    // Egress (clone's second run, instance_type == 2): if meta.preserved == 0 → drop.
    // With preservation: meta.preserved is 0xABCD, no drop → 2 outputs.
    // Without preservation: meta.preserved is 0, clone drops → 1 output.
    val markToDrop = externCall("mark_to_drop", nameRef("sm"))
    val cloneE2E =
      externCall("clone_preserving_field_list", enumArg("E2E"), intArg(1, 32), intArg(1, 8))
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(
            assignField("meta", "preserved", 0xABCD, 16),
            assignField("sm", "egress_spec", 3, V1ModelArchitecture.DEFAULT_PORT_BITS),
          ),
        egressStmts =
          listOf(
            // First pass: clone E2E.
            ifFieldEquals("sm", "instance_type", 0, 32, cloneE2E),
            // Clone's second egress (instance_type == 2): drop if metadata was reset.
            ifFieldEquals(
              "sm",
              "instance_type",
              2,
              32,
              ifFieldEquals("meta", "preserved", 0, 16, markToDrop),
            ),
          ),
        metaTypeDecl = metaTypeWithFieldList,
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 8)

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), tableStore)

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.CLONE, result.trace.forkOutcome.reason)
    val outputs = result.possibleOutcomes.single()
    assertEquals(2, outputs.size)
    assertEquals(3, outputs[0].dataplaneEgressPort)
    assertEquals(8, outputs[1].dataplaneEgressPort)
  }

  @Test
  fun `clone_preserving_field_list handles unsized integer field list arg`() {
    // p4c sometimes emits an unsized integer literal (no type) for the field list ID.
    // The simulator must handle both BitVal and InfIntVal for this argument.
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(
            assignField("meta", "preserved", 0xABCD, 16),
            // unsizedIntArg(1) instead of intArg(1, 8) — exercises the InfIntVal path.
            externCall(
              "clone_preserving_field_list",
              enumArg("I2E"),
              intArg(1, 32),
              unsizedIntArg(1),
            ),
            assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
          ),
        egressStmts =
          listOf(
            ifFieldEquals(
              "sm",
              "instance_type",
              1,
              32,
              ifFieldEquals("meta", "preserved", 0, 16, externCall("mark_to_drop", nameRef("sm"))),
            )
          ),
        metaTypeDecl = metaTypeWithFieldList,
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 7)

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), tableStore)

    val outputs = result.possibleOutcomes.single()
    assertEquals(2, outputs.size)
  }

  @Test
  fun `resubmit_preserving_field_list preserves annotated metadata`() {
    // Ingress (first pass, instance_type == 0): set meta.preserved = 0xABCD, then resubmit.
    // Ingress (resubmit, instance_type == 6): if meta.preserved == 0 → drop.
    // With preservation: meta.preserved is 0xABCD, no drop → output on port 1.
    // Without preservation: meta.preserved is 0, drop → no output.
    val markToDrop = externCall("mark_to_drop", nameRef("sm"))
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(
            // First pass: set metadata and resubmit.
            ifFieldEquals(
              "sm",
              "instance_type",
              0,
              32,
              Stmt.newBuilder()
                .setBlock(
                  BlockStmt.newBuilder()
                    .addStmts(assignField("meta", "preserved", 0xABCD, 16))
                    .addStmts(externCall("resubmit_preserving_field_list", intArg(1, 8)))
                )
                .build(),
            ),
            // Resubmit pass (instance_type == 6): drop if metadata was reset.
            ifFieldEquals(
              "sm",
              "instance_type",
              6,
              32,
              ifFieldEquals("meta", "preserved", 0, 16, markToDrop),
            ),
            // Set egress_spec so the packet outputs (if not dropped).
            assignField("sm", "egress_spec", 1, V1ModelArchitecture.DEFAULT_PORT_BITS),
          ),
        metaTypeDecl = metaTypeWithFieldList,
      )

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.RESUBMIT, result.trace.forkOutcome.reason)
    val outputs = result.possibleOutcomes.single()
    // Resubmit branch outputs (metadata was preserved, so no drop).
    assertEquals(1, outputs.size)
    assertEquals(1, outputs[0].dataplaneEgressPort)
  }

  @Test
  fun `recirculate_preserving_field_list preserves annotated metadata`() {
    // Ingress: set egress_spec = 1, set meta.preserved = 0xABCD on first pass.
    // Egress (first pass, instance_type == 0): recirculate with field_list 1.
    // Ingress (recirculate, instance_type == 4): if meta.preserved == 0 → drop.
    // With preservation: meta.preserved is 0xABCD, no drop → output on port 1.
    // Without preservation: meta.preserved is 0, drop → no output.
    val markToDrop = externCall("mark_to_drop", nameRef("sm"))
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(
            // First pass: set metadata.
            ifFieldEquals(
              "sm",
              "instance_type",
              0,
              32,
              assignField("meta", "preserved", 0xABCD, 16),
            ),
            // Recirculate pass (instance_type == 4): drop if metadata was reset.
            ifFieldEquals(
              "sm",
              "instance_type",
              4,
              32,
              ifFieldEquals("meta", "preserved", 0, 16, markToDrop),
            ),
            assignField("sm", "egress_spec", 1, V1ModelArchitecture.DEFAULT_PORT_BITS),
          ),
        egressStmts =
          listOf(
            ifFieldEquals(
              "sm",
              "instance_type",
              0,
              32,
              externCall("recirculate_preserving_field_list", intArg(1, 8)),
            )
          ),
        metaTypeDecl = metaTypeWithFieldList,
      )

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    assertTrue(result.trace.hasForkOutcome())
    assertEquals(ForkReason.RECIRCULATE, result.trace.forkOutcome.reason)
    val outputs = result.possibleOutcomes.single()
    // Recirculate branch outputs (metadata was preserved, so no drop).
    assertEquals(1, outputs.size)
    assertEquals(1, outputs[0].dataplaneEgressPort)
  }

  @Test
  fun `clone_preserving_field_list does not preserve non-annotated fields`() {
    // Same setup, but check that not_preserved (no @field_list annotation) resets to 0.
    // Egress (clone branch): if meta.not_preserved == 0 → set egress_spec to 42 (distinctive).
    // If not_preserved was correctly reset, the clone outputs on port 42.
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(
            assignField("meta", "not_preserved", 0x1234, 16),
            externCall("clone_preserving_field_list", enumArg("I2E"), intArg(1, 32), intArg(1, 8)),
            assignField("sm", "egress_spec", 2, V1ModelArchitecture.DEFAULT_PORT_BITS),
          ),
        egressStmts =
          listOf(
            // On clone branch: drop if not_preserved was NOT reset (still has ingress value).
            ifFieldEquals(
              "sm",
              "instance_type",
              1,
              32,
              ifFieldEquals(
                "meta",
                "not_preserved",
                0x1234,
                16,
                externCall("mark_to_drop", nameRef("sm")),
              ),
            )
          ),
        metaTypeDecl = metaTypeWithFieldList,
      )
    val tableStore = TableStore()
    writeCloneSession(tableStore, sessionId = 1, egressPort = 7)

    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), tableStore)

    assertTrue(result.trace.hasForkOutcome())
    val outputs = result.possibleOutcomes.single()
    // Clone should NOT drop: not_preserved was reset to 0, so 0x1234 check is false.
    assertEquals(2, outputs.size)
  }

  // ---------------------------------------------------------------------------
  // assert / assume tests
  // ---------------------------------------------------------------------------

  @Test
  fun `assert true passes through normally`() {
    val config =
      v1modelConfig(
        externCall("assert", boolLit(true)),
        assignField("sm", "egress_spec", 1, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    assertEquals(1, outputs[0].dataplaneEgressPort)
    // Trace should contain a passing assertion event.
    assertTrue(result.trace.eventsList.any { it.hasAssertion() && it.assertion.passed })
  }

  @Test
  fun `assert false drops packet with ASSERTION_FAILURE reason`() {
    val config = v1modelConfig(externCall("assert", boolLit(false)))
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
    assertEquals(DropReason.ASSERTION_FAILURE, result.trace.packetOutcome.drop.reason)
    // Trace should contain a failing assertion event.
    assertTrue(result.trace.eventsList.any { it.hasAssertion() && !it.assertion.passed })
  }

  @Test
  fun `assume false drops packet with ASSERTION_FAILURE reason`() {
    val config = v1modelConfig(externCall("assume", boolLit(false)))
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
    assertEquals(DropReason.ASSERTION_FAILURE, result.trace.packetOutcome.drop.reason)
  }

  // ---------------------------------------------------------------------------
  // log_msg tests
  // ---------------------------------------------------------------------------

  @Test
  fun `log_msg emits LogMessageEvent with format string`() {
    val config =
      v1modelConfig(
        externCall("log_msg", stringLit("hello world")),
        assignField("sm", "egress_spec", 1, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    val logEvents = result.trace.eventsList.filter { it.hasLogMessage() }
    assertEquals(1, logEvents.size)
    assertEquals("hello world", logEvents[0].logMessage.message)
  }

  @Test
  fun `log_msg substitutes placeholders from arg`() {
    val config =
      v1modelConfig(
        externCall("log_msg", stringLit("value = {}"), bit(42, 8)),
        assignField("sm", "egress_spec", 1, V1ModelArchitecture.DEFAULT_PORT_BITS),
      )
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())
    val outputs = result.possibleOutcomes.single()

    assertEquals(1, outputs.size)
    val logEvents = result.trace.eventsList.filter { it.hasLogMessage() }
    assertEquals(1, logEvents.size)
    assertEquals("value = 42", logEvents[0].logMessage.message)
  }

  @Test
  fun `egress assert false drops packet with ASSERTION_FAILURE reason`() {
    val config =
      v1modelConfig(
        ingressStmts =
          listOf(assignField("sm", "egress_spec", 1, V1ModelArchitecture.DEFAULT_PORT_BITS)),
        egressStmts = listOf(externCall("assert", boolLit(false))),
      )
    val result = V1ModelArchitecture(config).processPacket(0u, byteArrayOf(0x01), TableStore())

    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
    assertEquals(DropReason.ASSERTION_FAILURE, result.trace.packetOutcome.drop.reason)
  }

  // ---------------------------------------------------------------------------
  // Drop port override tests
  // ---------------------------------------------------------------------------

  @Test
  fun `drop port override changes which port is considered a drop`() {
    // Default drop port for 9-bit ports is 511. Override it to 42.
    // Sending to port 42 should now be a drop.
    val config = v1modelConfig(assignField("sm", "egress_spec", 42, DEFAULT_PORT_BITS))
    val result =
      V1ModelArchitecture(config, dropPortOverride = 42)
        .processPacket(0u, byteArrayOf(0x01), TableStore())

    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
  }

  @Test
  fun `drop port override lets default drop port through as regular output`() {
    // With override=42, port 511 is no longer the drop port — it's a regular port.
    val config =
      v1modelConfig(assignField("sm", "egress_spec", DEFAULT_DROP_PORT.toLong(), DEFAULT_PORT_BITS))
    val result =
      V1ModelArchitecture(config, dropPortOverride = 42)
        .processPacket(0u, byteArrayOf(0x01), TableStore())

    val outputs = result.possibleOutcomes.single()
    assertEquals(1, outputs.size)
    assertEquals(DEFAULT_DROP_PORT, outputs[0].dataplaneEgressPort)
  }

  @Test
  fun `mark_to_drop uses overridden drop port`() {
    // mark_to_drop should set egress_spec to the override value, not the default.
    val config = v1modelConfig(externCall("mark_to_drop", nameRef("sm")))
    val result =
      V1ModelArchitecture(config, dropPortOverride = 42)
        .processPacket(0u, byteArrayOf(0x01), TableStore())

    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
  }

  @Test
  fun `null drop port override uses default behavior`() {
    // Explicitly null = derive from port width (same as no-arg constructor).
    val config =
      v1modelConfig(assignField("sm", "egress_spec", DEFAULT_DROP_PORT.toLong(), DEFAULT_PORT_BITS))
    val result =
      V1ModelArchitecture(config, dropPortOverride = null)
        .processPacket(0u, byteArrayOf(0x01), TableStore())

    assertTrue(result.trace.hasPacketOutcome())
    assertTrue(result.trace.packetOutcome.hasDrop())
  }

  companion object {
    private const val DEFAULT_PORT_BITS = V1ModelArchitecture.DEFAULT_PORT_BITS
    private const val DEFAULT_DROP_PORT = V1ModelArchitecture.DEFAULT_DROP_PORT

    /** Minimum-width big-endian encoding (matches P4Runtime canonical form). */
    private fun intToMinWidthBytes(value: Int): ByteArray {
      if (value == 0) return byteArrayOf(0)
      val bytes = mutableListOf<Byte>()
      var v = value
      while (v > 0) {
        bytes.add(0, (v and 0xFF).toByte())
        v = v shr 8
      }
      return bytes.toByteArray()
    }
  }
}
