package fourward.web

import fourward.BehavioralConfig
import fourward.Expr
import fourward.KeysetExpr
import fourward.Literal
import fourward.ParserDecl
import fourward.ParserState
import fourward.SelectCase
import fourward.SelectTransition
import fourward.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParserGraphExtractorTest {

  @Test
  fun `single state with direct transition to accept`() {
    val parser = parser("MyParser", state("start", directTransition("accept")))
    val g = ParserGraphExtractor.extract(config(parser))[0]

    assertEquals("MyParser", g.name)
    assertNodeNames(g, "start", "accept")
    assertEdge(g, "start", "accept")
  }

  @Test
  fun `select transition creates labeled edges`() {
    val parser =
      parser(
        "MyParser",
        state(
          "start",
          selectTransition(
            cases = listOf(hexCase("0800", "parse_ipv4"), hexCase("86DD", "parse_ipv6")),
            default = "accept",
          ),
        ),
        state("parse_ipv4", directTransition("accept")),
        state("parse_ipv6", directTransition("accept")),
      )
    val g = ParserGraphExtractor.extract(config(parser))[0]

    assertNodeNames(g, "start", "parse_ipv4", "parse_ipv6", "accept")
    assertEdge(g, "start", "parse_ipv4", "0x800")
    assertEdge(g, "start", "parse_ipv6", "0x86DD")
    assertEdge(g, "start", "accept", "default")
    assertEdge(g, "parse_ipv4", "accept")
    assertEdge(g, "parse_ipv6", "accept")
  }

  @Test
  fun `implicit accept node added when not declared`() {
    // The "accept" state is referenced in transitions but not declared as a ParserState.
    val parser = parser("MyParser", state("start", directTransition("accept")))
    val g = ParserGraphExtractor.extract(config(parser))[0]

    val acceptNode = g.nodes.find { it.name == "accept" }!!
    assertEquals("exit", acceptNode.type)
  }

  @Test
  fun `parser states have type state`() {
    val parser =
      parser(
        "MyParser",
        state("start", directTransition("parse_ipv4")),
        state("parse_ipv4", directTransition("accept")),
      )
    val g = ParserGraphExtractor.extract(config(parser))[0]

    val startNode = g.nodes.find { it.name == "start" }!!
    assertEquals("state", startNode.type)
    val parseNode = g.nodes.find { it.name == "parse_ipv4" }!!
    assertEquals("state", parseNode.type)
  }

  @Test
  fun `multiple parsers produce separate graphs`() {
    val graphs =
      ParserGraphExtractor.extract(
        config(
          parser("p1", state("start", directTransition("accept"))),
          parser("p2", state("start", directTransition("accept"))),
        )
      )
    assertEquals(2, graphs.size)
    assertEquals("p1", graphs[0].name)
    assertEquals("p2", graphs[1].name)
  }

  // ---- helpers ----

  private fun config(vararg parsers: ParserDecl): BehavioralConfig =
    BehavioralConfig.newBuilder().addAllParsers(parsers.toList()).build()

  private fun parser(name: String, vararg states: ParserState): ParserDecl =
    ParserDecl.newBuilder().setName(name).addAllStates(states.toList()).build()

  private fun state(name: String, transition: Transition): ParserState =
    ParserState.newBuilder().setName(name).setTransition(transition).build()

  private fun directTransition(nextState: String): Transition =
    Transition.newBuilder().setNextState(nextState).build()

  private fun selectTransition(cases: List<SelectCase>, default: String = ""): Transition =
    Transition.newBuilder()
      .setSelect(
        SelectTransition.newBuilder().addAllCases(cases).also {
          if (default.isNotEmpty()) it.setDefaultState(default)
        }
      )
      .build()

  private fun hexCase(hexValue: String, nextState: String): SelectCase =
    SelectCase.newBuilder()
      .addKeysets(
        KeysetExpr.newBuilder()
          .setExact(
            Expr.newBuilder().setLiteral(Literal.newBuilder().setInteger(hexValue.toLong(16)))
          )
      )
      .setNextState(nextState)
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
