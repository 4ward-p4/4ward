package fourward.web

import fourward.BehavioralConfig
import fourward.BinaryOperator
import fourward.ControlDecl
import fourward.Expr
import fourward.IfStmt
import fourward.Stmt
import fourward.SwitchStmt
import fourward.TableApplyExpr

/**
 * Extracts a simplified control-flow graph from a [ControlDecl]'s apply body.
 *
 * Nodes are tables and conditions; edges are the control-flow paths connecting them. The result is
 * a DAG (P4 controls have no loops) suitable for client-side layout with dagre/elkjs.
 */
object ControlGraphExtractor {

  data class Node(val id: String, val type: String, val name: String)

  data class Edge(val from: String, val to: String, val label: String = "")

  data class ControlGraph(val name: String, val nodes: List<Node>, val edges: List<Edge>)

  fun extract(config: BehavioralConfig): List<ControlGraph> =
    config.controlsList.map { extractControl(it) }

  private fun extractControl(control: ControlDecl): ControlGraph {
    val ctx = Context()
    val entryId = ctx.addNode("entry", "entry")
    val exitId = ctx.addNode("exit", "exit")
    val lastIds = walkStmts(control.applyBodyList, setOf(entryId), ctx)
    for (id in lastIds) ctx.addEdge(id, exitId)
    return ControlGraph(control.name, ctx.nodes, ctx.edges)
  }

  /**
   * Walk a list of statements, threading the set of "current" node IDs through. Returns the set of
   * node IDs that are live after the last statement (i.e., the nodes that should connect to
   * whatever comes next). An empty set means all paths exited/returned.
   */
  private fun walkStmts(stmts: List<Stmt>, currentIds: Set<String>, ctx: Context): Set<String> {
    var live = currentIds
    for (stmt in stmts) {
      if (live.isEmpty()) break
      live = walkStmt(stmt, live, ctx)
    }
    return live
  }

  private fun walkStmt(stmt: Stmt, currentIds: Set<String>, ctx: Context): Set<String> =
    when (stmt.kindCase) {
      Stmt.KindCase.SWITCH_STMT -> walkSwitch(stmt.switchStmt, currentIds, ctx)
      Stmt.KindCase.IF_STMT -> walkIf(stmt.ifStmt, currentIds, ctx)
      Stmt.KindCase.BLOCK -> walkStmts(stmt.block.stmtsList, currentIds, ctx)
      Stmt.KindCase.METHOD_CALL -> walkMethodCall(stmt.methodCall.call, currentIds, ctx)
      Stmt.KindCase.EXIT,
      Stmt.KindCase.RETURN_STMT -> emptySet()
      Stmt.KindCase.ASSIGNMENT,
      Stmt.KindCase.KIND_NOT_SET,
      null -> currentIds // assignments and other non-branching stmts: invisible
    }

  private fun walkSwitch(sw: SwitchStmt, currentIds: Set<String>, ctx: Context): Set<String> {
    val tableName = sw.subject.tableApply.tableName
    val tableId = ctx.addNode(tableName, "table")
    for (id in currentIds) ctx.addEdge(id, tableId)

    val exits = mutableSetOf<String>()
    for (case in sw.casesList) {
      exits += walkBranch(case.block.stmtsList, tableId, case.actionName, ctx)
    }
    if (sw.hasDefaultBlock() && sw.defaultBlock.stmtsList.isNotEmpty()) {
      exits += walkBranch(sw.defaultBlock.stmtsList, tableId, "default", ctx)
    }
    // If no case handles all actions, the table implicitly falls through.
    exits += tableId
    return exits
  }

  private fun walkIf(ifStmt: IfStmt, currentIds: Set<String>, ctx: Context): Set<String> {
    // Check if the condition is a table apply (e.g., `if (table.apply().hit)`).
    val tableApply = findTableApply(ifStmt.condition)
    if (tableApply != null) {
      val tableName = tableApply.tableName
      val tableId = ctx.addNode(tableName, "table")
      for (id in currentIds) ctx.addEdge(id, tableId)

      val hitLabel =
        when (tableApply.accessKind) {
          TableApplyExpr.AccessKind.HIT -> "hit"
          TableApplyExpr.AccessKind.MISS -> "miss"
          else -> "true"
        }
      val missLabel =
        when (tableApply.accessKind) {
          TableApplyExpr.AccessKind.HIT -> "miss"
          TableApplyExpr.AccessKind.MISS -> "hit"
          else -> "false"
        }

      val thenExits = walkBranch(ifStmt.thenBlock.stmtsList, tableId, hitLabel, ctx)
      val elseExits = walkBranch(ifStmt.elseBlock.stmtsList, tableId, missLabel, ctx)
      return thenExits + elseExits
    }

    // Non-table condition: create a condition node.
    val label = conditionLabel(ifStmt.condition)
    val condId = ctx.addNode(label, "condition")
    for (id in currentIds) ctx.addEdge(id, condId)

    val thenExits = walkBranch(ifStmt.thenBlock.stmtsList, condId, "T", ctx)
    val elseExits = walkBranch(ifStmt.elseBlock.stmtsList, condId, "F", ctx)
    return thenExits + elseExits
  }

  /**
   * Walk a branch body, connecting [sourceId] to the first node in the body with [label]. If the
   * body is empty, returns a pending labeled edge from [sourceId].
   */
  private fun walkBranch(
    stmts: List<Stmt>,
    sourceId: String,
    label: String,
    ctx: Context,
  ): Set<String> {
    if (stmts.isEmpty()) {
      // Empty branch: the label will be applied to whatever edge connects
      // sourceId to the next node. Track it as a pending labeled exit.
      ctx.addPendingLabel(sourceId, label)
      return setOf(sourceId)
    }
    val exits = walkStmts(stmts, setOf(sourceId), ctx)
    labelLastEdgeFrom(sourceId, label, ctx)
    return exits
  }

  private fun walkMethodCall(expr: Expr, currentIds: Set<String>, ctx: Context): Set<String> {
    val tableApply = findTableApply(expr) ?: return currentIds
    val tableId = ctx.addNode(tableApply.tableName, "table")
    for (id in currentIds) ctx.addEdge(id, tableId)
    return setOf(tableId)
  }

  /** Recursively search an expression tree for a TableApplyExpr. */
  private fun findTableApply(expr: Expr): TableApplyExpr? =
    when (expr.kindCase) {
      Expr.KindCase.TABLE_APPLY -> expr.tableApply
      Expr.KindCase.METHOD_CALL -> findTableApply(expr.methodCall.target)
      Expr.KindCase.UNARY_OP -> findTableApply(expr.unaryOp.expr)
      Expr.KindCase.LITERAL,
      Expr.KindCase.NAME_REF,
      Expr.KindCase.FIELD_ACCESS,
      Expr.KindCase.ARRAY_INDEX,
      Expr.KindCase.SLICE,
      Expr.KindCase.CONCAT,
      Expr.KindCase.CAST,
      Expr.KindCase.BINARY_OP,
      Expr.KindCase.MUX,
      Expr.KindCase.STRUCT_EXPR,
      Expr.KindCase.KIND_NOT_SET,
      null -> null
    }

  /** Extract a human-readable label from a condition expression. */
  private fun conditionLabel(expr: Expr): String =
    when (expr.kindCase) {
      Expr.KindCase.METHOD_CALL -> {
        val target = expr.methodCall.target
        val method = expr.methodCall.method
        when (target.kindCase) {
          Expr.KindCase.FIELD_ACCESS -> "${target.fieldAccess.fieldName}.$method()"
          Expr.KindCase.NAME_REF -> "${target.nameRef.name}.$method()"
          else -> "$method()"
        }
      }
      Expr.KindCase.FIELD_ACCESS -> expr.fieldAccess.fieldName
      Expr.KindCase.NAME_REF -> expr.nameRef.name
      Expr.KindCase.BINARY_OP -> {
        val left = conditionLabel(expr.binaryOp.left)
        val right = conditionLabel(expr.binaryOp.right)
        val op = binaryOpSymbol(expr.binaryOp.op)
        "$left $op $right"
      }
      Expr.KindCase.UNARY_OP -> "!${conditionLabel(expr.unaryOp.expr)}"
      Expr.KindCase.LITERAL,
      Expr.KindCase.ARRAY_INDEX,
      Expr.KindCase.SLICE,
      Expr.KindCase.CONCAT,
      Expr.KindCase.CAST,
      Expr.KindCase.TABLE_APPLY,
      Expr.KindCase.MUX,
      Expr.KindCase.STRUCT_EXPR,
      Expr.KindCase.KIND_NOT_SET,
      null -> "?"
    }

  private fun binaryOpSymbol(op: BinaryOperator): String =
    when (op) {
      BinaryOperator.EQ -> "=="
      BinaryOperator.NEQ -> "!="
      BinaryOperator.AND -> "&&"
      BinaryOperator.OR -> "||"
      else -> op.name.lowercase()
    }

  /** Label the most recently added edge from [fromId]. */
  private fun labelLastEdgeFrom(fromId: String, label: String, ctx: Context) {
    for (i in ctx.edges.indices.reversed()) {
      if (ctx.edges[i].from == fromId && ctx.edges[i].label.isEmpty()) {
        ctx.edges[i] = ctx.edges[i].copy(label = label)
        return
      }
    }
  }

  private class Context {
    val nodes = mutableListOf<Node>()
    val edges = mutableListOf<Edge>()
    private val nodeIds = mutableMapOf<String, Int>()
    private val pendingLabels = mutableMapOf<String, MutableList<String>>()

    fun addNode(name: String, type: String): String {
      val count = nodeIds.getOrPut(name) { 0 }
      nodeIds[name] = count + 1
      val id = if (count == 0) name else "${name}_$count"
      nodes += Node(id, type, name)
      return id
    }

    fun addEdge(from: String, to: String, label: String = "") {
      // Drain all pending labels from empty branches, creating one edge per label.
      val pending = pendingLabels.remove(from)
      if (pending != null) {
        for (l in pending) {
          if (edges.none { it.from == from && it.to == to && it.label == l }) {
            edges += Edge(from, to, l)
          }
        }
      } else {
        if (edges.none { it.from == from && it.to == to && it.label == label }) {
          edges += Edge(from, to, label)
        }
      }
    }

    fun addPendingLabel(nodeId: String, label: String) {
      pendingLabels.getOrPut(nodeId) { mutableListOf() }.add(label)
    }
  }
}
