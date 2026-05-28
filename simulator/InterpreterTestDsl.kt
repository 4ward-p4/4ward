package fourward.simulator

import fourward.AssignmentStmt
import fourward.BehavioralConfig
import fourward.BitType
import fourward.BlockStmt
import fourward.ControlDecl
import fourward.Expr
import fourward.FieldAccess
import fourward.FieldDecl
import fourward.IfStmt
import fourward.Literal
import fourward.MethodCall
import fourward.MethodCallStmt
import fourward.NameRef
import fourward.SourceInfo
import fourward.Stmt
import fourward.Type
import p4.v1.P4RuntimeOuterClass

/**
 * Shared proto-building helpers for interpreter unit tests.
 *
 * Extracted from the individual `Interpreter*Test.kt` files where these were duplicated as private
 * functions with identical or near-identical implementations.
 */

/** Unsigned integer literal of [value] in [width] bits. */
fun bit(value: Long, width: Int): Expr =
  Expr.newBuilder()
    .setLiteral(Literal.newBuilder().setInteger(value))
    .setType(Type.newBuilder().setBit(BitType.newBuilder().setWidth(width)))
    .build()

fun boolLit(v: Boolean): Expr =
  Expr.newBuilder()
    .setLiteral(Literal.newBuilder().setBoolean(v))
    .setType(Type.newBuilder().setBoolean(true))
    .build()

/** String literal expression, e.g. for log_msg format strings. */
fun stringLit(value: String): Expr =
  Expr.newBuilder().setLiteral(Literal.newBuilder().setStringLiteral(value)).build()

fun nameRef(name: String, type: Type? = null): Expr =
  Expr.newBuilder()
    .setNameRef(NameRef.newBuilder().setName(name))
    .apply { if (type != null) setType(type) }
    .build()

fun bitType(width: Int): Type =
  Type.newBuilder().setBit(BitType.newBuilder().setWidth(width)).build()

fun namedType(name: String): Type = Type.newBuilder().setNamed(name).build()

fun fieldAccess(expr: Expr, fieldName: String, type: Type? = null): Expr =
  Expr.newBuilder()
    .setFieldAccess(FieldAccess.newBuilder().setExpr(expr).setFieldName(fieldName))
    .apply { if (type != null) setType(type) }
    .build()

fun ifStmt(
  condition: Expr,
  thenStmts: List<Stmt> = emptyList(),
  elseStmts: List<Stmt> = emptyList(),
  sourceInfo: SourceInfo? = null,
): Stmt {
  val builder =
    Stmt.newBuilder()
      .setIfStmt(
        IfStmt.newBuilder()
          .setCondition(condition)
          .setThenBlock(BlockStmt.newBuilder().addAllStmts(thenStmts))
          .setElseBlock(BlockStmt.newBuilder().addAllStmts(elseStmts))
      )
  if (sourceInfo != null) builder.sourceInfo = sourceInfo
  return builder.build()
}

/** A `bit<[width]>` [FieldDecl] with the given [name]. */
fun field(name: String, width: Int): FieldDecl =
  FieldDecl.newBuilder().setName(name).setType(bitType(width)).build()

/** Assignment statement: `target.fieldName = value` (integer literal). */
fun assignField(target: String, fieldName: String, value: Long, width: Int): Stmt =
  Stmt.newBuilder()
    .setAssignment(
      AssignmentStmt.newBuilder()
        .setLhs(
          Expr.newBuilder()
            .setFieldAccess(
              FieldAccess.newBuilder().setExpr(nameRef(target)).setFieldName(fieldName)
            )
            .setType(bitType(width))
        )
        .setRhs(
          Expr.newBuilder()
            .setLiteral(Literal.newBuilder().setInteger(value))
            .setType(bitType(width))
        )
    )
    .build()

/** Assignment statement: `lhsTarget.lhsField = rhsTarget.rhsField`. */
fun assignFieldFromField(
  lhsTarget: String,
  lhsField: String,
  lhsWidth: Int,
  rhsTarget: String,
  rhsField: String,
  rhsWidth: Int,
): Stmt =
  Stmt.newBuilder()
    .setAssignment(
      AssignmentStmt.newBuilder()
        .setLhs(
          Expr.newBuilder()
            .setFieldAccess(
              FieldAccess.newBuilder().setExpr(nameRef(lhsTarget)).setFieldName(lhsField)
            )
            .setType(bitType(lhsWidth))
        )
        .setRhs(
          Expr.newBuilder()
            .setFieldAccess(
              FieldAccess.newBuilder().setExpr(nameRef(rhsTarget)).setFieldName(rhsField)
            )
            .setType(bitType(rhsWidth))
        )
    )
    .build()

/** Assignment statement: `varName = rhs`. */
fun assign(varName: String, rhs: Expr): Stmt =
  Stmt.newBuilder()
    .setAssignment(
      AssignmentStmt.newBuilder()
        .setLhs(Expr.newBuilder().setNameRef(NameRef.newBuilder().setName(varName)))
        .setRhs(rhs)
    )
    .build()

/** Assignment statement with an arbitrary expression [lhs], e.g. `hdr.eth.dst = rhs`. */
fun assign(lhs: Expr, rhs: Expr): Stmt =
  Stmt.newBuilder().setAssignment(AssignmentStmt.newBuilder().setLhs(lhs).setRhs(rhs)).build()

/**
 * Statement that calls a free extern function: `name(args...)`.
 *
 * In the IR, free function calls use `"__call__"` as the method name (as opposed to instance method
 * calls like `register.read()`).
 */
fun externCall(name: String, vararg args: Expr): Stmt = methodCallStmt(name, "__call__", *args)

/** Statement that calls `target.method(args...)` — for extern method calls. */
fun methodCallStmt(
  target: String,
  method: String,
  vararg args: Expr,
  targetType: Type? = null,
): Stmt =
  Stmt.newBuilder()
    .setMethodCall(
      MethodCallStmt.newBuilder()
        .setCall(
          Expr.newBuilder()
            .setMethodCall(
              MethodCall.newBuilder()
                .setTarget(nameRef(target, targetType))
                .setMethod(method)
                .addAllArgs(args.toList())
            )
        )
    )
    .build()

/** Statement that calls `target.method(args...)` where [target] is already an expression. */
fun methodCallStmt(target: Expr, method: String, vararg args: Expr): Stmt =
  Stmt.newBuilder()
    .setMethodCall(
      MethodCallStmt.newBuilder()
        .setCall(
          Expr.newBuilder()
            .setMethodCall(
              MethodCall.newBuilder().setTarget(target).setMethod(method).addAllArgs(args.toList())
            )
        )
    )
    .build()

/** Builds a config with a single control named "MyControl" whose apply body is [stmts]. */
fun controlConfig(vararg stmts: Stmt): BehavioralConfig = controlConfig("MyControl", *stmts)

/** Builds a config with a single control named [controlName] whose apply body is [stmts]. */
fun controlConfig(controlName: String, vararg stmts: Stmt): BehavioralConfig =
  BehavioralConfig.newBuilder()
    .addControls(ControlDecl.newBuilder().setName(controlName).addAllApplyBody(stmts.toList()))
    .build()

/** Inserts a clone session into [store] with the given replicas (instance, egressPort). */
fun writeCloneSession(store: TableStore, sessionId: Int, replicas: List<Pair<Int, Int>>) {
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
                    replicas.map { (instance, port) ->
                      P4RuntimeOuterClass.Replica.newBuilder()
                        .setInstance(instance)
                        .setPort(portToBytes(port))
                        .build()
                    }
                  )
              )
          )
      )
      .build()
  )
  store.publishSnapshot()
}
