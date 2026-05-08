package fourward.web

import fourward.BehavioralConfig
import fourward.BlockStmt
import fourward.ControlDecl
import fourward.Expr
import fourward.IfStmt
import fourward.MethodCallStmt
import fourward.Stmt
import fourward.SwitchCase
import fourward.SwitchStmt
import fourward.TableApplyExpr
import fourward.Type
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlGraphExtractorTest {

  @Test
  fun `empty control produces entry-exit only`() {
    val config = config(control("MyControl"))
    val graphs = ControlGraphExtractor.extract(config)
    assertEquals(1, graphs.size)
    val g = graphs[0]
    assertEquals("MyControl", g.name)
    assertEquals(2, g.nodes.size)
    assertEquals(1, g.edges.size)
    assertEquals("entry", g.edges[0].from)
    assertEquals("exit", g.edges[0].to)
  }

  @Test
  fun `single table apply`() {
    val config = config(control("ingress", tableApplyStmt("ipv4_lpm")))
    val g = ControlGraphExtractor.extract(config)[0]

    assertNodeNames(g, "entry", "ipv4_lpm", "exit")
    assertEdge(g, "entry", "ipv4_lpm")
    assertEdge(g, "ipv4_lpm", "exit")
  }

  @Test
  fun `sequential table applies`() {
    val config = config(control("ingress", tableApplyStmt("acl"), tableApplyStmt("ipv4_lpm")))
    val g = ControlGraphExtractor.extract(config)[0]

    assertNodeNames(g, "entry", "acl", "ipv4_lpm", "exit")
    assertEdge(g, "entry", "acl")
    assertEdge(g, "acl", "ipv4_lpm")
    assertEdge(g, "ipv4_lpm", "exit")
  }

  @Test
  fun `switch on table action_run`() {
    val config =
      config(
        control(
          "ingress",
          switchStmt(
            "dmac",
            listOf("forward" to listOf(tableApplyStmt("nexthop")), "drop" to emptyList()),
          ),
        )
      )
    val g = ControlGraphExtractor.extract(config)[0]

    assertNodeNames(g, "entry", "dmac", "nexthop", "exit")
    assertEdge(g, "entry", "dmac")
    assertEdge(g, "dmac", "nexthop", "forward")
    assertEdge(g, "nexthop", "exit")
  }

  @Test
  fun `switch with multiple empty cases preserves all labels`() {
    // switch (dmac.apply()) { action_a: {} action_b: {} }; nexthop.apply();
    val config =
      config(
        control(
          "ingress",
          switchStmt("dmac", listOf("action_a" to emptyList(), "action_b" to emptyList())),
          tableApplyStmt("nexthop"),
        )
      )
    val g = ControlGraphExtractor.extract(config)[0]

    assertNodeNames(g, "entry", "dmac", "nexthop", "exit")
    assertEdge(g, "dmac", "nexthop", "action_a")
    assertEdge(g, "dmac", "nexthop", "action_b")
  }

  @Test
  fun `if table hit with then and else branches`() {
    val config =
      config(
        control("ingress", ifTableHitStmt("acl", thenStmts = listOf(tableApplyStmt("forward"))))
      )
    val g = ControlGraphExtractor.extract(config)[0]

    assertNodeNames(g, "entry", "acl", "forward", "exit")
    assertEdge(g, "entry", "acl")
    assertEdge(g, "acl", "forward", "hit")
    assertEdge(g, "acl", "exit", "miss")
  }

  @Test
  fun `non-table condition creates condition node`() {
    val config =
      config(
        control(
          "ingress",
          ifConditionStmt(isValidExpr("ipv4"), thenStmts = listOf(tableApplyStmt("ipv4_lpm"))),
        )
      )
    val g = ControlGraphExtractor.extract(config)[0]

    val condNode = g.nodes.find { it.type == "condition" }!!
    assertEquals("ipv4.isValid()", condNode.name)
    assertEdge(g, condNode.id, "ipv4_lpm", "T")
  }

  @Test
  fun `multiple controls produce separate graphs`() {
    val config =
      config(control("ingress", tableApplyStmt("t1")), control("egress", tableApplyStmt("t2")))
    val graphs = ControlGraphExtractor.extract(config)
    assertEquals(2, graphs.size)
    assertEquals("ingress", graphs[0].name)
    assertEquals("egress", graphs[1].name)
  }

  // ---- helpers ----

  private fun config(vararg controls: ControlDecl): BehavioralConfig =
    BehavioralConfig.newBuilder().addAllControls(controls.toList()).build()

  private fun control(name: String, vararg stmts: Stmt): ControlDecl =
    ControlDecl.newBuilder().setName(name).addAllApplyBody(stmts.toList()).build()

  private fun tableApplyExpr(tableName: String): Expr =
    Expr.newBuilder().setTableApply(TableApplyExpr.newBuilder().setTableName(tableName)).build()

  private fun tableApplyStmt(tableName: String): Stmt =
    Stmt.newBuilder()
      .setMethodCall(MethodCallStmt.newBuilder().setCall(tableApplyExpr(tableName)))
      .build()

  private fun switchStmt(tableName: String, cases: List<Pair<String, List<Stmt>>>): Stmt {
    val sw =
      SwitchStmt.newBuilder()
        .setSubject(tableApplyExpr(tableName))
        .setDefaultBlock(BlockStmt.getDefaultInstance())
    for ((actionName, stmts) in cases) {
      sw.addCases(
        SwitchCase.newBuilder()
          .setActionName(actionName)
          .setBlock(BlockStmt.newBuilder().addAllStmts(stmts))
      )
    }
    return Stmt.newBuilder().setSwitchStmt(sw).build()
  }

  private fun ifTableHitStmt(
    tableName: String,
    thenStmts: List<Stmt> = emptyList(),
    elseStmts: List<Stmt> = emptyList(),
  ): Stmt {
    val cond =
      Expr.newBuilder()
        .setTableApply(
          TableApplyExpr.newBuilder()
            .setTableName(tableName)
            .setAccessKind(TableApplyExpr.AccessKind.HIT)
        )
        .build()
    return Stmt.newBuilder()
      .setIfStmt(
        IfStmt.newBuilder()
          .setCondition(cond)
          .setThenBlock(BlockStmt.newBuilder().addAllStmts(thenStmts))
          .setElseBlock(BlockStmt.newBuilder().addAllStmts(elseStmts))
      )
      .build()
  }

  private fun ifConditionStmt(
    condition: Expr,
    thenStmts: List<Stmt> = emptyList(),
    elseStmts: List<Stmt> = emptyList(),
  ): Stmt =
    Stmt.newBuilder()
      .setIfStmt(
        IfStmt.newBuilder()
          .setCondition(condition)
          .setThenBlock(BlockStmt.newBuilder().addAllStmts(thenStmts))
          .setElseBlock(BlockStmt.newBuilder().addAllStmts(elseStmts))
      )
      .build()

  private fun isValidExpr(headerName: String): Expr =
    Expr.newBuilder()
      .setMethodCall(
        fourward.MethodCall.newBuilder()
          .setTarget(
            Expr.newBuilder()
              .setFieldAccess(
                fourward.FieldAccess.newBuilder()
                  .setExpr(
                    Expr.newBuilder().setNameRef(fourward.NameRef.newBuilder().setName("hdr"))
                  )
                  .setFieldName(headerName)
              )
          )
          .setMethod("isValid")
      )
      .setType(Type.newBuilder().setBoolean(true))
      .build()

  private fun assertNodeNames(g: ControlGraphExtractor.ControlGraph, vararg names: String) {
    val actual = g.nodes.map { it.name }.toSet()
    for (name in names) {
      assertTrue("Expected node '$name' in $actual", name in actual)
    }
  }

  private fun assertEdge(
    g: ControlGraphExtractor.ControlGraph,
    from: String,
    to: String,
    label: String = "",
  ) {
    val match =
      g.edges.any { e -> e.from == from && e.to == to && (label.isEmpty() || e.label == label) }
    assertTrue(
      "Expected edge $from -> $to" +
        (if (label.isNotEmpty()) " [$label]" else "") +
        " in ${g.edges}",
      match,
    )
  }
}
