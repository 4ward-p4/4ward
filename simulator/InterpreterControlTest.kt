package fourward.simulator

import fourward.ActionDecl
import fourward.AssignmentStmt
import fourward.BehavioralConfig
import fourward.BitType
import fourward.BlockStmt
import fourward.ControlDecl
import fourward.ExitStmt
import fourward.Expr
import fourward.FieldAccess
import fourward.MethodCallStmt
import fourward.NameRef
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
}
