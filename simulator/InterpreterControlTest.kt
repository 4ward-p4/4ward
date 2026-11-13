package fourward.simulator

import com.google.protobuf.ByteString
import fourward.ActionDecl
import fourward.AssignmentStmt
import fourward.BehavioralConfig
import fourward.BitType
import fourward.BlockStmt
import fourward.ControlDecl
import fourward.ControlPlaneBinding
import fourward.ControlPlaneBindings
import fourward.DeviceConfig
import fourward.ExitStmt
import fourward.Expr
import fourward.FieldAccess
import fourward.MethodCallStmt
import fourward.NameRef
import fourward.ParamDecl
import fourward.Stmt
import fourward.SwitchCase
import fourward.SwitchStmt
import fourward.TableApplyExpr
import fourward.TableBehavior
import fourward.Type
import fourward.VarDecl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.p4.config.v1.Action as P4InfoAction
import org.p4.config.v1.ActionProfile
import org.p4.config.v1.ActionRef
import org.p4.config.v1.P4Info
import org.p4.config.v1.Preamble
import org.p4.config.v1.Table
import org.p4.v1.Action
import org.p4.v1.ActionProfileAction
import org.p4.v1.ActionProfileActionSet
import org.p4.v1.ActionProfileMember
import org.p4.v1.Entity
import org.p4.v1.TableAction
import org.p4.v1.TableEntry
import org.p4.v1.Update

/**
 * Unit tests for [Interpreter] control and statement execution.
 *
 * Tests cover assignments, if/else branching, field mutation, and exit. Each test builds a minimal
 * [ControlDecl] proto and calls [Interpreter.runControl], observing side effects on the
 * [Environment].
 */
class InterpreterControlTest {

  private val emptyEnv
    get() = Environment()

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Creates an extern handler with register/meter/counter method support for unit tests. */
  private fun testExternHandler(tableStore: TableStore) = ExternHandler { call, eval ->
    when (call) {
      is ExternCall.FreeFunction -> error("unexpected extern call: ${call.name}")
      is ExternCall.Method ->
        when (call.externType) {
          "register" ->
            when (call.method) {
              "read" -> {
                val index = (eval.evalArg(1) as BitVal).bits.value.toInt()
                val stored = tableStore.registerRead(call.instanceName, index)
                eval.writeOutArg(0, stored ?: eval.defaultValue(eval.argType(0)))
                UnitVal
              }
              "write" -> {
                val index = (eval.evalArg(0) as BitVal).bits.value.toInt()
                tableStore.registerWrite(call.instanceName, index, eval.evalArg(1))
                UnitVal
              }
              else -> error("unhandled register method: ${call.method}")
            }
          "direct_meter" ->
            when (call.method) {
              "read" -> {
                eval.writeOutArg(0, eval.defaultValue(eval.argType(0)))
                UnitVal
              }
              else -> error("unhandled direct_meter method: ${call.method}")
            }
          "meter" ->
            when (call.method) {
              "execute_meter" -> {
                eval.writeOutArg(1, eval.defaultValue(eval.argType(1)))
                UnitVal
              }
              else -> error("unhandled meter method: ${call.method}")
            }
          "counter",
          "direct_counter" ->
            when (call.method) {
              "count" -> UnitVal
              else -> error("unhandled counter method: ${call.method}")
            }
          else ->
            error(
              "unhandled extern method: ${call.externType}.${call.method}" +
                " on ${call.instanceName}"
            )
        }
    }
  }

  private fun interp(config: BehavioralConfig, tableStore: TableStore = TableStore()) =
    interpreterExecution(config, tableStore, externHandler = testExternHandler(tableStore))

  /** Builds a `switch (tableName.apply().action_run)` statement. */
  private fun switchOn(
    tableName: String,
    cases: Map<String, List<Stmt>>,
    defaultStmts: List<Stmt> = emptyList(),
  ): Stmt =
    Stmt.newBuilder()
      .setSwitchStmt(
        SwitchStmt.newBuilder()
          .setSubject(
            Expr.newBuilder().setTableApply(TableApplyExpr.newBuilder().setTableName(tableName))
          )
          .addAllCases(
            cases.map { (actionName, stmts) ->
              SwitchCase.newBuilder()
                .setActionName(actionName)
                .setBlock(BlockStmt.newBuilder().addAllStmts(stmts))
                .build()
            }
          )
          .setDefaultBlock(BlockStmt.newBuilder().addAllStmts(defaultStmts))
      )
      .build()

  // ---------------------------------------------------------------------------
  // Tests
  // ---------------------------------------------------------------------------

  @Test
  fun `assignment updates variable in environment`() {
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(controlConfig(assign("x", bit(99, 8)))).runControl("MyControl", env)
    assertEquals(BitVal(99, 8), env.lookup("x"))
  }

  @Test
  fun `if-stmt takes then-block when condition is true`() {
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    val stmt = ifStmt(boolLit(true), listOf(assign("x", bit(1, 8))), listOf(assign("x", bit(2, 8))))
    interp(controlConfig(stmt)).runControl("MyControl", env)
    assertEquals(BitVal(1, 8), env.lookup("x"))
  }

  @Test
  fun `if-stmt takes else-block when condition is false`() {
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    val stmt =
      ifStmt(boolLit(false), listOf(assign("x", bit(1, 8))), listOf(assign("x", bit(2, 8))))
    interp(controlConfig(stmt)).runControl("MyControl", env)
    assertEquals(BitVal(2, 8), env.lookup("x"))
  }

  @Test
  fun `field assignment updates struct field`() {
    val env = emptyEnv
    val meta = StructVal("meta_t", mutableMapOf("count" to BitVal(0, 8)))
    env.define("meta", meta)
    val fieldLhs =
      Expr.newBuilder()
        .setFieldAccess(
          FieldAccess.newBuilder()
            .setExpr(Expr.newBuilder().setNameRef(NameRef.newBuilder().setName("meta")))
            .setFieldName("count")
        )
        .build()
    val stmt =
      Stmt.newBuilder()
        .setAssignment(AssignmentStmt.newBuilder().setLhs(fieldLhs).setRhs(bit(7, 8)))
        .build()
    interp(controlConfig(stmt)).runControl("MyControl", env)
    assertEquals(BitVal(7, 8), meta.fields["count"])
  }

  @Test
  fun `exit statement propagates ExitException and halts execution`() {
    // Statements after exit must not run.
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    val exitStmt = Stmt.newBuilder().setExit(ExitStmt.getDefaultInstance()).build()
    assertThrows(ExitException::class.java) {
      interp(controlConfig(exitStmt, assign("x", bit(99, 8)))).runControl("MyControl", env)
    }
    assertEquals(BitVal(0, 8), env.lookup("x"))
  }

  // ---------------------------------------------------------------------------
  // Switch statement
  // ---------------------------------------------------------------------------

  @Test
  fun `switch executes matching case block`() {
    // Table default action is "drop"; the switch case for "drop" sets x=1.
    val ts =
      TableStore().also {
        it.setDefaultAction("t", "drop")
        it.publishSnapshot()
      }
    val config =
      BehavioralConfig.newBuilder()
        .addTables(TableBehavior.newBuilder().setName("t"))
        .addActions(ActionDecl.newBuilder().setName("drop"))
        .addControls(
          ControlDecl.newBuilder()
            .setName("MyControl")
            .addApplyBody(
              switchOn(
                "t",
                mapOf("drop" to listOf(assign("x", bit(1, 8)))),
                listOf(assign("x", bit(2, 8))),
              )
            )
        )
        .build()
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(config, ts).runControl("MyControl", env)
    assertEquals(BitVal(1, 8), env.lookup("x"))
  }

  @Test
  fun `switch executes default block when no case matches action`() {
    // Table default action is "NoAction"; the only declared case is for "drop" (won't match).
    val ts =
      TableStore().also {
        it.setDefaultAction("t", "NoAction")
        it.publishSnapshot()
      }
    val config =
      BehavioralConfig.newBuilder()
        .addTables(TableBehavior.newBuilder().setName("t"))
        .addActions(ActionDecl.newBuilder().setName("NoAction"))
        .addActions(ActionDecl.newBuilder().setName("drop"))
        .addControls(
          ControlDecl.newBuilder()
            .setName("MyControl")
            .addApplyBody(
              switchOn(
                "t",
                mapOf("drop" to listOf(assign("x", bit(1, 8)))),
                listOf(assign("x", bit(2, 8))),
              )
            )
        )
        .build()
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(config, ts).runControl("MyControl", env)
    assertEquals(BitVal(2, 8), env.lookup("x"))
  }

  // ---------------------------------------------------------------------------
  // Local variable scoping
  // ---------------------------------------------------------------------------

  @Test
  fun `control local vars are not visible in outer scope after control returns`() {
    // "local" is declared as a local var in the control; the outer env must not see it.
    val config =
      BehavioralConfig.newBuilder()
        .addControls(
          ControlDecl.newBuilder()
            .setName("MyControl")
            .addLocalVars(
              VarDecl.newBuilder()
                .setName("local")
                .setType(Type.newBuilder().setBit(BitType.newBuilder().setWidth(8)))
            )
        )
        .build()
    val env = emptyEnv
    interp(config).runControl("MyControl", env)
    assertNull(env.lookup("local"))
  }

  @Test
  fun `uninitialized local var defaults to zero`() {
    // Verifies that defaultValue() is called for vars with no explicit initializer.
    val config =
      BehavioralConfig.newBuilder()
        .addControls(
          ControlDecl.newBuilder()
            .setName("MyControl")
            .addLocalVars(
              VarDecl.newBuilder()
                .setName("x")
                .setType(Type.newBuilder().setBit(BitType.newBuilder().setWidth(8)))
              // no initializer
            )
            .addApplyBody(assign("result", nameRef("x")))
        )
        .build()
    val env = emptyEnv
    env.define("result", BitVal(99, 8)) // sentinel
    interp(config).runControl("MyControl", env)
    assertEquals(BitVal(0, 8), env.lookup("result"))
  }

  @Test
  fun `local var initializer runs and is accessible in the apply body`() {
    // Declare local var "count" initialised to 42; the apply body copies it to "x".
    val config =
      BehavioralConfig.newBuilder()
        .addControls(
          ControlDecl.newBuilder()
            .setName("MyControl")
            .addLocalVars(
              VarDecl.newBuilder()
                .setName("count")
                .setType(Type.newBuilder().setBit(BitType.newBuilder().setWidth(8)))
                .setInitializer(bit(42, 8))
            )
            .addApplyBody(assign("x", nameRef("count")))
        )
        .build()
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(config).runControl("MyControl", env)
    assertEquals(BitVal(42, 8), env.lookup("x"))
  }

  // ---------------------------------------------------------------------------
  // table.apply().hit / .miss
  // ---------------------------------------------------------------------------

  /** Builds `if (tableApplyExpr) { thenStmts } else { elseStmts }`. */
  private fun ifTableHit(
    tableName: String,
    accessKind: TableApplyExpr.AccessKind,
    thenStmts: List<Stmt>,
    elseStmts: List<Stmt>,
  ): Stmt {
    val subject =
      Expr.newBuilder()
        .setTableApply(
          TableApplyExpr.newBuilder().setTableName(tableName).setAccessKind(accessKind)
        )
        .setType(Type.newBuilder().setBoolean(true))
        .build()
    return ifStmt(subject, thenStmts, elseStmts)
  }

  /** Minimal config with table "t" and actions "fwd" and "NoAction". */
  private fun tableHitConfig(bodyStmt: Stmt): BehavioralConfig =
    BehavioralConfig.newBuilder()
      .addTables(TableBehavior.newBuilder().setName("t"))
      .addActions(ActionDecl.newBuilder().setName("fwd"))
      .addActions(ActionDecl.newBuilder().setName("NoAction"))
      .addControls(ControlDecl.newBuilder().setName("MyControl").addApplyBody(bodyStmt))
      .build()

  @Test
  fun `table apply hit returns true when entry matches`() {
    val ts = TableStore().also { it.setForcedHit("t", "fwd") }
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(
        tableHitConfig(
          ifTableHit(
            "t",
            TableApplyExpr.AccessKind.HIT,
            listOf(assign("x", bit(1, 8))),
            listOf(assign("x", bit(2, 8))),
          )
        ),
        ts,
      )
      .runControl("MyControl", env)
    assertEquals(BitVal(1, 8), env.lookup("x"))
  }

  @Test
  fun `table apply hit returns false when no entry matches`() {
    // TableStore with no entry for "t" — lookup misses (default action).
    val ts = TableStore()
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(
        tableHitConfig(
          ifTableHit(
            "t",
            TableApplyExpr.AccessKind.HIT,
            listOf(assign("x", bit(1, 8))),
            listOf(assign("x", bit(2, 8))),
          )
        ),
        ts,
      )
      .runControl("MyControl", env)
    assertEquals(BitVal(2, 8), env.lookup("x"))
  }

  @Test
  fun `table apply miss returns true when no entry matches`() {
    val ts = TableStore()
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(
        tableHitConfig(
          ifTableHit(
            "t",
            TableApplyExpr.AccessKind.MISS,
            listOf(assign("x", bit(1, 8))),
            listOf(assign("x", bit(2, 8))),
          )
        ),
        ts,
      )
      .runControl("MyControl", env)
    assertEquals(BitVal(1, 8), env.lookup("x"))
  }

  @Test
  fun `table apply miss returns false when entry matches`() {
    val ts = TableStore().also { it.setForcedHit("t", "fwd") }
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(
        tableHitConfig(
          ifTableHit(
            "t",
            TableApplyExpr.AccessKind.MISS,
            listOf(assign("x", bit(1, 8))),
            listOf(assign("x", bit(2, 8))),
          )
        ),
        ts,
      )
      .runControl("MyControl", env)
    assertEquals(BitVal(2, 8), env.lookup("x"))
  }

  @Test
  fun `table miss default action side effects are visible to later statements`() {
    val ts =
      TableStore().also {
        it.setDefaultAction("route", "drop")
        it.publishSnapshot()
      }
    val applyRoute =
      Stmt.newBuilder()
        .setAssignment(
          AssignmentStmt.newBuilder()
            .setLhs(
              fieldAccess(nameRef("meta"), "route_hit", Type.newBuilder().setBoolean(true).build())
            )
            .setRhs(
              Expr.newBuilder()
                .setTableApply(
                  TableApplyExpr.newBuilder()
                    .setTableName("route")
                    .setAccessKind(TableApplyExpr.AccessKind.HIT)
                )
                .setType(Type.newBuilder().setBoolean(true))
            )
        )
        .build()
    val config =
      BehavioralConfig.newBuilder()
        .addTables(TableBehavior.newBuilder().setName("route"))
        .addTables(TableBehavior.newBuilder().setName("resolution"))
        .addActions(
          ActionDecl.newBuilder()
            .setName("drop")
            .addBody(
              assign(
                fieldAccess(nameRef("meta"), "drop", Type.newBuilder().setBoolean(true).build()),
                boolLit(true),
              )
            )
        )
        .addActions(ActionDecl.newBuilder().setName("NoAction"))
        .addControls(
          ControlDecl.newBuilder()
            .setName("MyControl")
            .addApplyBody(applyRoute)
            .addApplyBody(
              ifStmt(
                fieldAccess(nameRef("meta"), "drop", Type.newBuilder().setBoolean(true).build()),
                thenStmts = emptyList(),
                elseStmts =
                  listOf(
                    Stmt.newBuilder()
                      .setMethodCall(
                        MethodCallStmt.newBuilder()
                          .setCall(
                            Expr.newBuilder()
                              .setTableApply(TableApplyExpr.newBuilder().setTableName("resolution"))
                          )
                      )
                      .build()
                  ),
              )
            )
        )
        .build()
    val env = emptyEnv
    env.define(
      "meta",
      StructVal("meta_t", mutableMapOf("drop" to BoolVal(false), "route_hit" to BoolVal(true))),
    )
    interp(config, ts).runControl("MyControl", env)
    val meta = env.lookup("meta") as StructVal
    assertEquals(BoolVal(true), meta.fields["drop"])
    assertEquals(BoolVal(false), meta.fields["route_hit"])
  }

  // ---------------------------------------------------------------------------
  // Per-table action override resolution
  // ---------------------------------------------------------------------------

  /**
   * Config with table "t" having action_overrides "setbyte" → "setbyte_1". The first copy
   * ("setbyte") sets x=1; the second copy ("setbyte_1") sets x=2.
   */
  private fun actionOverrideConfig(controlBody: Stmt): BehavioralConfig =
    BehavioralConfig.newBuilder()
      .addTables(TableBehavior.newBuilder().setName("t").putActionOverrides("setbyte", "setbyte_1"))
      .addActions(ActionDecl.newBuilder().setName("setbyte").addBody(assign("x", bit(1, 8))))
      .addActions(
        ActionDecl.newBuilder()
          .setName("setbyte")
          .setCurrentName("setbyte_1")
          .addBody(assign("x", bit(2, 8)))
      )
      .addControls(ControlDecl.newBuilder().setName("MyControl").addApplyBody(controlBody))
      .build()

  @Test
  fun `action override resolves per-table specialized copy`() {
    // The p4info returns "setbyte" for all tables, but action_overrides maps it
    // to "setbyte_1" for table "t", so setbyte_1's body should execute (x=2).
    val ts = TableStore().also { it.setForcedHit("t", "setbyte") }
    val applyTable =
      Stmt.newBuilder()
        .setMethodCall(
          MethodCallStmt.newBuilder()
            .setCall(Expr.newBuilder().setTableApply(TableApplyExpr.newBuilder().setTableName("t")))
        )
        .build()
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    interp(actionOverrideConfig(applyTable), ts).runControl("MyControl", env)
    assertEquals(BitVal(2, 8), env.lookup("x"))
  }

  @Test
  fun `action override returns original name for switch matching`() {
    // The override resolves to "setbyte_1" for execution, but the switch
    // statement should match on the original name "setbyte".
    val ts = TableStore().also { it.setForcedHit("t", "setbyte") }
    val config =
      actionOverrideConfig(
        switchOn(
          "t",
          mapOf("setbyte" to listOf(assign("y", bit(10, 8)))),
          listOf(assign("y", bit(20, 8))),
        )
      )
    val env = emptyEnv
    env.define("x", BitVal(0, 8))
    env.define("y", BitVal(0, 8))
    interp(config, ts).runControl("MyControl", env)
    // The override executed setbyte_1's body (x=2)
    assertEquals(BitVal(2, 8), env.lookup("x"))
    // The switch matched on the original name "setbyte" (y=10, not default 20)
    assertEquals(BitVal(10, 8), env.lookup("y"))
  }

  // ---------------------------------------------------------------------------
  // Registers
  // ---------------------------------------------------------------------------

  @Test
  fun `register read returns written value`() {
    val regType = namedType("register")
    val writeStmt = methodCallStmt("my_reg", "write", bit(0, 32), bit(42, 8), targetType = regType)
    val readStmt =
      methodCallStmt("my_reg", "read", nameRef("dst", bitType(8)), bit(0, 32), targetType = regType)
    val config = controlConfig(writeStmt, readStmt)
    val env = emptyEnv
    env.define("dst", BitVal(0, 8))
    interp(config).runControl("MyControl", env)
    assertEquals(BitVal(42, 8), env.lookup("dst"))
  }

  @Test
  fun `register read returns zero for unwritten index`() {
    val readStmt =
      methodCallStmt(
        "my_reg",
        "read",
        nameRef("dst", bitType(8)),
        bit(5, 32),
        targetType = namedType("register"),
      )
    val config = controlConfig(readStmt)
    val env = emptyEnv
    env.define("dst", BitVal(0xFF, 8))
    interp(config).runControl("MyControl", env)
    assertEquals(BitVal(0, 8), env.lookup("dst"))
  }

  // ---------------------------------------------------------------------------
  // Meters
  // ---------------------------------------------------------------------------

  @Test
  fun `direct_meter read writes GREEN to out parameter`() {
    val stmt =
      methodCallStmt(
        "my_meter",
        "read",
        nameRef("color", bitType(2)),
        targetType = namedType("direct_meter"),
      )
    val config = controlConfig(stmt)
    val env = emptyEnv
    env.define("color", BitVal(3, 2))
    interp(config).runControl("MyControl", env)
    assertEquals(BitVal(0, 2), env.lookup("color"))
  }

  @Test
  fun `execute_meter writes GREEN to out parameter`() {
    val stmt =
      methodCallStmt(
        "my_meter",
        "execute_meter",
        bit(0, 32),
        nameRef("color", bitType(8)),
        targetType = namedType("meter"),
      )
    val config = controlConfig(stmt)
    val env = emptyEnv
    env.define("color", BitVal(0xFF, 8))
    interp(config).runControl("MyControl", env)
    assertEquals(BitVal(0, 8), env.lookup("color"))
  }

  // ---------------------------------------------------------------------------
  // Extern dispatch error paths
  // ---------------------------------------------------------------------------

  @Test
  fun `free-function extern without handler throws`() {
    val stmt = externCall("unknown_extern", bit(0, 8))
    val config = controlConfig(stmt)
    val noHandler = interpreterExecution(config, TableStore())
    val e =
      assertThrows(IllegalStateException::class.java) {
        noHandler.runControl("MyControl", emptyEnv)
      }
    assertEquals("no extern handler for: unknown_extern", e.message)
  }

  @Test
  fun `extern method without handler throws`() {
    val stmt = methodCallStmt("obj", "some_method", bit(0, 8), targetType = namedType("my_extern"))
    val config = controlConfig(stmt)
    val noHandler = interpreterExecution(config, TableStore())
    val e =
      assertThrows(IllegalStateException::class.java) {
        noHandler.runControl("MyControl", emptyEnv)
      }
    assertEquals(
      "unhandled method call: some_method on ${nameRef("obj", namedType("my_extern"))}",
      e.message,
    )
  }

  @Test
  fun `unrecognised extern method throws`() {
    val stmt =
      methodCallStmt("obj", "unknown_method", bit(0, 8), targetType = namedType("register"))
    val config = controlConfig(stmt)
    val e =
      assertThrows(IllegalStateException::class.java) {
        interp(config).runControl("MyControl", emptyEnv)
      }
    assertEquals("unhandled register method: unknown_method", e.message)
  }

  // ---------------------------------------------------------------------------
  // peekRemainingInput
  // ---------------------------------------------------------------------------

  @Test
  fun `extern handler can peek at remaining packet input`() {
    val payload = byteArrayOf(0xCA.toByte(), 0xFE.toByte())
    var captured: ByteArray? = null
    val handler = ExternHandler { _, eval ->
      captured = eval.peekRemainingInput()
      UnitVal
    }
    val stmt = externCall("capture_input")
    val config = controlConfig(stmt)
    val pktCtx = PacketContext(payload)
    interpreterExecution(config, TableStore(), pktCtx, externHandler = handler)
      .runControl("MyControl", emptyEnv)
    assertEquals(payload.toList(), captured?.toList())
  }

  // ---------------------------------------------------------------------------
  // Action parameter binding by param_id
  // ---------------------------------------------------------------------------

  /**
   * Builds a two-parameter action `set_vlan_and_meta(bit<12> vlan_id, bit<8> acl_metadata)` that
   * copies its parameters into environment variables `out_vlan` and `out_meta`.
   *
   * Parameter 1 (`vlan_id`, 12 bits) is wider than parameter 2 (`acl_metadata`, 8 bits), so binding
   * reversed `Action.Param` protos positionally attempts to load a 12-bit value (e.g. `0x801` =
   * 2049) into an 8-bit parameter and throws `value 2049 does not fit in 8 bits`.
   */
  private fun vlanAndMetaActionDecl(
    currentName: String = "",
    paramSuffix: String = "",
  ): ActionDecl =
    ActionDecl.newBuilder()
      .setName("set_vlan_and_meta")
      .apply { if (currentName.isNotEmpty()) setCurrentName(currentName) }
      .addParams(ParamDecl.newBuilder().setName("vlan_id$paramSuffix").setType(bitType(12)))
      .addParams(ParamDecl.newBuilder().setName("acl_metadata$paramSuffix").setType(bitType(8)))
      .addBody(assign("out_vlan", nameRef("vlan_id$paramSuffix", bitType(12))))
      .addBody(assign("out_meta", nameRef("acl_metadata$paramSuffix", bitType(8))))
      .build()

  private fun applyTableStmt(tableName: String): Stmt =
    Stmt.newBuilder()
      .setMethodCall(
        MethodCallStmt.newBuilder()
          .setCall(
            Expr.newBuilder().setTableApply(TableApplyExpr.newBuilder().setTableName(tableName))
          )
      )
      .build()

  private fun reversedVlanAndMetaParams(): List<Action.Param> =
    listOf(
      Action.Param.newBuilder()
        .setParamId(20)
        .setValue(ByteString.copyFrom(byteArrayOf(0x42)))
        .build(),
      Action.Param.newBuilder()
        .setParamId(10)
        .setValue(ByteString.copyFrom(byteArrayOf(0x08, 0x01)))
        .build(),
    )

  private fun vlanAndMetaP4Info(implementationId: Int = 0): P4Info {
    val tableBuilder =
      Table.newBuilder()
        .setPreamble(Preamble.newBuilder().setId(1).setName("t").setAlias("t"))
        .addActionRefs(ActionRef.newBuilder().setId(10))
    if (implementationId != 0) tableBuilder.setImplementationId(implementationId)

    val p4infoBuilder =
      P4Info.newBuilder()
        .addTables(tableBuilder)
        .addActions(
          P4InfoAction.newBuilder()
            .setPreamble(
              Preamble.newBuilder()
                .setId(10)
                .setName("set_vlan_and_meta")
                .setAlias("set_vlan_and_meta")
            )
            // Non-sequential parameter IDs (10 and 20) ensure binding uses P4Info metadata
            // rather than falling back to 1-based positional indices (i + 1).
            .addParams(P4InfoAction.Param.newBuilder().setId(10).setName("vlan_id").setBitwidth(12))
            .addParams(
              P4InfoAction.Param.newBuilder().setId(20).setName("acl_metadata").setBitwidth(8)
            )
        )
    if (implementationId != 0) {
      p4infoBuilder.addActionProfiles(
        ActionProfile.newBuilder()
          .setPreamble(Preamble.newBuilder().setId(implementationId).setName("ap").setAlias("ap"))
          .addTableIds(1)
      )
    }
    return p4infoBuilder.build()
  }

  private fun vlanAndMetaDevice(behavioral: BehavioralConfig): DeviceConfig =
    DeviceConfig.newBuilder()
      .setBehavioral(behavioral)
      .setControlPlaneBindings(
        ControlPlaneBindings.newBuilder()
          .addTables(ControlPlaneBinding.newBuilder().setP4InfoName("t").setSimulatorName("t"))
          .addActions(
            ControlPlaneBinding.newBuilder()
              .setP4InfoName("set_vlan_and_meta")
              .setSimulatorName("set_vlan_and_meta")
          )
      )
      .build()

  @Test
  fun `table entry action binds parameters by param_id regardless of proto order`() {
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTables(
          TableBehavior.newBuilder()
            .setName("t")
            .putActionOverrides("set_vlan_and_meta", "set_vlan_and_meta_0")
        )
        .addActions(vlanAndMetaActionDecl(currentName = "set_vlan_and_meta_0", paramSuffix = "_0"))
        .addControls(
          ControlDecl.newBuilder().setName("MyControl").addApplyBody(applyTableStmt("t"))
        )
        .build()

    val ts = TableStore()
    ts.loadMappings(vlanAndMetaP4Info(), vlanAndMetaDevice(behavioral))
    ts.write(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(
          Entity.newBuilder()
            .setTableEntry(
              TableEntry.newBuilder()
                .setTableId(1)
                .setAction(
                  TableAction.newBuilder()
                    .setAction(
                      Action.newBuilder().setActionId(10).addAllParams(reversedVlanAndMetaParams())
                    )
                )
            )
        )
        .build()
    )
    ts.publishSnapshot()

    val env = emptyEnv
    env.define("out_vlan", BitVal(0, 12))
    env.define("out_meta", BitVal(0, 8))
    interp(behavioral, ts).runControl("MyControl", env)

    assertEquals(BitVal(0x801, 12), env.lookup("out_vlan"))
    assertEquals(BitVal(0x42, 8), env.lookup("out_meta"))
  }

  @Test
  fun `default action binds parameters by param_id regardless of proto order`() {
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTables(TableBehavior.newBuilder().setName("t"))
        .addActions(vlanAndMetaActionDecl())
        .addControls(
          ControlDecl.newBuilder().setName("MyControl").addApplyBody(applyTableStmt("t"))
        )
        .build()
    val ts = TableStore()
    ts.loadMappings(vlanAndMetaP4Info(), vlanAndMetaDevice(behavioral))
    ts.setDefaultAction("t", "set_vlan_and_meta", reversedVlanAndMetaParams())
    ts.publishSnapshot()

    val env = emptyEnv
    env.define("out_vlan", BitVal(0, 12))
    env.define("out_meta", BitVal(0, 8))
    interp(behavioral, ts).runControl("MyControl", env)

    assertEquals(BitVal(0x801, 12), env.lookup("out_vlan"))
    assertEquals(BitVal(0x42, 8), env.lookup("out_meta"))
  }

  @Test
  fun `action profile member binds parameters by param_id regardless of proto order`() {
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTables(TableBehavior.newBuilder().setName("t"))
        .addActions(vlanAndMetaActionDecl())
        .addControls(
          ControlDecl.newBuilder().setName("MyControl").addApplyBody(applyTableStmt("t"))
        )
        .build()

    val ts = TableStore()
    ts.loadMappings(vlanAndMetaP4Info(implementationId = 100), vlanAndMetaDevice(behavioral))
    ts.write(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(
          Entity.newBuilder()
            .setActionProfileMember(
              ActionProfileMember.newBuilder()
                .setActionProfileId(100)
                .setMemberId(5)
                .setAction(
                  Action.newBuilder().setActionId(10).addAllParams(reversedVlanAndMetaParams())
                )
            )
        )
        .build()
    )
    ts.write(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(
          Entity.newBuilder()
            .setTableEntry(
              TableEntry.newBuilder()
                .setTableId(1)
                .setAction(TableAction.newBuilder().setActionProfileMemberId(5))
            )
        )
        .build()
    )
    ts.publishSnapshot()

    val env = emptyEnv
    env.define("out_vlan", BitVal(0, 12))
    env.define("out_meta", BitVal(0, 8))
    interp(behavioral, ts).runControl("MyControl", env)

    assertEquals(BitVal(0x801, 12), env.lookup("out_vlan"))
    assertEquals(BitVal(0x42, 8), env.lookup("out_meta"))
  }

  @Test
  fun `one-shot action profile action set binds parameters by param_id with action override`() {
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTables(
          TableBehavior.newBuilder()
            .setName("t")
            .putActionOverrides("set_vlan_and_meta", "set_vlan_and_meta_0")
        )
        .addActions(vlanAndMetaActionDecl(currentName = "set_vlan_and_meta_0"))
        .addControls(
          ControlDecl.newBuilder().setName("MyControl").addApplyBody(applyTableStmt("t"))
        )
        .build()

    val ts = TableStore()
    ts.loadMappings(vlanAndMetaP4Info(implementationId = 100), vlanAndMetaDevice(behavioral))
    ts.write(
      Update.newBuilder()
        .setType(Update.Type.INSERT)
        .setEntity(
          Entity.newBuilder()
            .setTableEntry(
              TableEntry.newBuilder()
                .setTableId(1)
                .setAction(
                  TableAction.newBuilder()
                    .setActionProfileActionSet(
                      ActionProfileActionSet.newBuilder()
                        .addActionProfileActions(
                          ActionProfileAction.newBuilder()
                            .setAction(
                              Action.newBuilder()
                                .setActionId(10)
                                .addAllParams(reversedVlanAndMetaParams())
                            )
                            .setWeight(1)
                        )
                    )
                )
            )
        )
        .build()
    )
    ts.publishSnapshot()

    val env = emptyEnv
    env.define("out_vlan", BitVal(0, 12))
    env.define("out_meta", BitVal(0, 8))
    interpreterExecution(
        behavioral,
        ts,
        selectorOverrides = mapOf("t" to 0),
        externHandler = testExternHandler(ts),
      )
      .runControl("MyControl", env)

    assertEquals(BitVal(0x801, 12), env.lookup("out_vlan"))
    assertEquals(BitVal(0x42, 8), env.lookup("out_meta"))
  }

  @Test
  fun `action parameter binding throws when required param_id is missing`() {
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTables(TableBehavior.newBuilder().setName("t"))
        .addActions(vlanAndMetaActionDecl())
        .addControls(
          ControlDecl.newBuilder().setName("MyControl").addApplyBody(applyTableStmt("t"))
        )
        .build()
    val onlyFirstParam =
      listOf(
        Action.Param.newBuilder()
          .setParamId(10)
          .setValue(ByteString.copyFrom(byteArrayOf(0x08, 0x01)))
          .build()
      )
    val ts = TableStore()
    ts.loadMappings(vlanAndMetaP4Info(), vlanAndMetaDevice(behavioral))
    ts.setDefaultAction("t", "set_vlan_and_meta", onlyFirstParam)
    ts.publishSnapshot()

    val env = emptyEnv
    env.define("out_vlan", BitVal(0, 12))
    env.define("out_meta", BitVal(0, 8))
    assertThrows(IllegalStateException::class.java) {
      interp(behavioral, ts).runControl("MyControl", env)
    }
  }

  @Test
  fun `action parameter binding falls back to positional order when param_id is unset`() {
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTables(TableBehavior.newBuilder().setName("t"))
        .addActions(vlanAndMetaActionDecl())
        .addControls(
          ControlDecl.newBuilder().setName("MyControl").addApplyBody(applyTableStmt("t"))
        )
        .build()
    val positionalParamsWithoutIds =
      listOf(
        Action.Param.newBuilder().setValue(ByteString.copyFrom(byteArrayOf(0x08, 0x01))).build(),
        Action.Param.newBuilder().setValue(ByteString.copyFrom(byteArrayOf(0x42))).build(),
      )
    val ts = TableStore()
    ts.setDefaultAction("t", "set_vlan_and_meta", positionalParamsWithoutIds)
    ts.publishSnapshot()

    val env = emptyEnv
    env.define("out_vlan", BitVal(0, 12))
    env.define("out_meta", BitVal(0, 8))
    interp(behavioral, ts).runControl("MyControl", env)

    assertEquals(BitVal(0x801, 12), env.lookup("out_vlan"))
    assertEquals(BitVal(0x42, 8), env.lookup("out_meta"))
  }
}
