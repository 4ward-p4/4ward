// Copyright 2026 4ward Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package fourward.simulator

import com.google.protobuf.ByteString
import fourward.BehavioralConfig
import fourward.KeysetExpr
import fourward.ParserDecl
import fourward.ParserState
import fourward.SelectCase
import fourward.SelectTransition
import fourward.Stmt
import fourward.Transition
import fourward.ValueSetDecl
import org.junit.Assert.assertEquals
import org.junit.Test
import p4.v1.P4RuntimeOuterClass

/**
 * Unit tests for [Interpreter.runParser] state machine traversal.
 *
 * Verifies that the iterative parser correctly visits states in order, executes statements in each
 * state, and halts at the "accept" and "reject" terminal states without treating them as user
 * states.
 */
class InterpreterParserTest {

  private fun state(name: String, nextState: String, vararg stmts: Stmt): ParserState =
    ParserState.newBuilder()
      .setName(name)
      .setTransition(Transition.newBuilder().setNextState(nextState))
      .addAllStmts(stmts.toList())
      .build()

  private fun interp(
    vararg states: ParserState,
    tableStore: TableStore = TableStore(),
    valueSets: List<ValueSetDecl> = emptyList(),
  ): Interpreter.Execution {
    val parser =
      ParserDecl.newBuilder()
        .setName("MyParser")
        .addAllStates(states.toList())
        .addAllValueSets(valueSets)
        .build()
    return interpreterExecution(
      BehavioralConfig.newBuilder().addParsers(parser).build(),
      tableStore,
    )
  }

  @Test
  fun `single-state parser executes stmts and halts at accept`() {
    val env = Environment()
    env.define("x", BitVal(0, 8))

    interp(state("start", "accept", assign("x", bit(1, 8)))).runParser("MyParser", env)

    assertEquals(BitVal(1, 8), env.lookup("x"))
  }

  @Test
  fun `two-state parser traverses states in order`() {
    // start(x=1) → middle(x=2) → accept: both states must run.
    val env = Environment()
    env.define("x", BitVal(0, 8))

    interp(
        state("start", "middle", assign("x", bit(1, 8))),
        state("middle", "accept", assign("x", bit(2, 8))),
      )
      .runParser("MyParser", env)

    assertEquals(BitVal(2, 8), env.lookup("x"))
  }

  @Test
  fun `three-state chain visits all states`() {
    // start(x=1) → s1(x=2) → s2(x=3) → accept
    val env = Environment()
    env.define("x", BitVal(0, 8))

    interp(
        state("start", "s1", assign("x", bit(1, 8))),
        state("s1", "s2", assign("x", bit(2, 8))),
        state("s2", "accept", assign("x", bit(3, 8))),
      )
      .runParser("MyParser", env)

    assertEquals(BitVal(3, 8), env.lookup("x"))
  }

  @Test
  fun `parser stops at reject without error`() {
    // reject is a terminal state, not an exception — the pipeline drops the packet separately.
    val env = Environment()
    interp(state("start", "reject")).runParser("MyParser", env)
  }

  @Test
  fun `select with no matching case and no default rejects`() {
    // P4 spec §12.6: if no case matches and there is no default, transition to reject.
    val env = Environment()
    env.define("x", BitVal(0, 8))

    val selectState =
      ParserState.newBuilder()
        .setName("start")
        .addStmts(assign("x", bit(1, 8)))
        .setTransition(
          Transition.newBuilder()
            .setSelect(
              SelectTransition.newBuilder()
                .addKeys(bit(99, 8))
                .addCases(
                  SelectCase.newBuilder()
                    .addKeysets(KeysetExpr.newBuilder().setExact(bit(0, 8)))
                    .setNextState("accept")
                )
              // no default_state set — should reject
            )
        )
        .build()

    interp(selectState).runParser("MyParser", env)

    // The start state's stmts should have executed before the select.
    assertEquals(BitVal(1, 8), env.lookup("x"))
    // Parser stopped at reject (no exception thrown = success).
  }

  // ---------------------------------------------------------------------------
  // Parser value_set (P4 spec §12.14)
  // ---------------------------------------------------------------------------

  /** Builds a value_set member with a single exact field match. */
  private fun exactMember(value: ByteArray): P4RuntimeOuterClass.ValueSetMember =
    P4RuntimeOuterClass.ValueSetMember.newBuilder()
      .addMatch(
        P4RuntimeOuterClass.FieldMatch.newBuilder()
          .setFieldId(1)
          .setExact(
            P4RuntimeOuterClass.FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(value))
          )
      )
      .build()

  /** Builds a value_set member with a single ternary field match. */
  private fun ternaryMember(value: ByteArray, mask: ByteArray): P4RuntimeOuterClass.ValueSetMember =
    P4RuntimeOuterClass.ValueSetMember.newBuilder()
      .addMatch(
        P4RuntimeOuterClass.FieldMatch.newBuilder()
          .setFieldId(1)
          .setTernary(
            P4RuntimeOuterClass.FieldMatch.Ternary.newBuilder()
              .setValue(ByteString.copyFrom(value))
              .setMask(ByteString.copyFrom(mask))
          )
      )
      .build()

  /** Creates a select state with a value_set case and a default fallback. */
  private fun valueSetSelectState(
    keyExpr: fourward.Expr,
    valueSetName: String,
    matchNextState: String,
    defaultNextState: String = "reject",
  ): ParserState =
    ParserState.newBuilder()
      .setName("start")
      .setTransition(
        Transition.newBuilder()
          .setSelect(
            SelectTransition.newBuilder()
              .addKeys(keyExpr)
              .addCases(
                SelectCase.newBuilder()
                  .addKeysets(KeysetExpr.newBuilder().setValueSet(valueSetName))
                  .setNextState(matchNextState)
              )
              .setDefaultState(defaultNextState)
          )
      )
      .build()

  @Test
  fun `value_set with no members never matches`() {
    val store = TableStore()
    val env = Environment()
    env.define("x", BitVal(0x42, 8))

    val selectState = valueSetSelectState(nameRef("x", bitType(8)), "pvs", "accept")

    interp(
        selectState,
        state("accept", "accept"),
        tableStore = store,
        valueSets = listOf(ValueSetDecl.newBuilder().setName("pvs").setSize(4).build()),
      )
      .runParser("MyParser", env)
    // Empty value_set → no match → falls through to default (reject).
  }

  @Test
  fun `value_set with exact member that matches transitions`() {
    val store = TableStore()
    store.populateValueSet("pvs", listOf(exactMember(byteArrayOf(0x42))))
    store.publishSnapshot()

    val env = Environment()
    env.define("x", BitVal(0x42, 8))
    env.define("y", BitVal(0, 8))

    val selectState = valueSetSelectState(nameRef("x", bitType(8)), "pvs", "matched")
    val matchedState = state("matched", "accept", assign("y", bit(1, 8)))

    interp(
        selectState,
        matchedState,
        tableStore = store,
        valueSets = listOf(ValueSetDecl.newBuilder().setName("pvs").setSize(4).build()),
      )
      .runParser("MyParser", env)

    assertEquals(BitVal(1, 8), env.lookup("y"))
  }

  @Test
  fun `value_set with exact member that does not match falls through`() {
    val store = TableStore()
    store.populateValueSet("pvs", listOf(exactMember(byteArrayOf(0x99.toByte()))))
    store.publishSnapshot()

    val env = Environment()
    env.define("x", BitVal(0x42, 8))

    val selectState = valueSetSelectState(nameRef("x", bitType(8)), "pvs", "accept")

    interp(
        selectState,
        tableStore = store,
        valueSets = listOf(ValueSetDecl.newBuilder().setName("pvs").setSize(4).build()),
      )
      .runParser("MyParser", env)
    // No match → default → reject.
  }

  @Test
  fun `value_set with ternary member matches masked value`() {
    val store = TableStore()
    // Match 0xA0 with mask 0xF0 → matches any value in 0xA0..0xAF.
    store.populateValueSet(
      "pvs",
      listOf(ternaryMember(byteArrayOf(0xA0.toByte()), byteArrayOf(0xF0.toByte()))),
    )
    store.publishSnapshot()

    val env = Environment()
    env.define("x", BitVal(0xAB, 8))
    env.define("y", BitVal(0, 8))

    val selectState = valueSetSelectState(nameRef("x", bitType(8)), "pvs", "matched")
    val matchedState = state("matched", "accept", assign("y", bit(1, 8)))

    interp(
        selectState,
        matchedState,
        tableStore = store,
        valueSets = listOf(ValueSetDecl.newBuilder().setName("pvs").setSize(4).build()),
      )
      .runParser("MyParser", env)

    assertEquals(BitVal(1, 8), env.lookup("y"))
  }

  @Test
  fun `value_set matches any member in the set`() {
    val store = TableStore()
    // Two members: 0x10 and 0x20. Key is 0x20 → second member matches.
    store.populateValueSet(
      "pvs",
      listOf(exactMember(byteArrayOf(0x10)), exactMember(byteArrayOf(0x20))),
    )
    store.publishSnapshot()

    val env = Environment()
    env.define("x", BitVal(0x20, 8))
    env.define("y", BitVal(0, 8))

    val selectState = valueSetSelectState(nameRef("x", bitType(8)), "pvs", "matched")
    val matchedState = state("matched", "accept", assign("y", bit(1, 8)))

    interp(
        selectState,
        matchedState,
        tableStore = store,
        valueSets = listOf(ValueSetDecl.newBuilder().setName("pvs").setSize(4).build()),
      )
      .runParser("MyParser", env)

    assertEquals(BitVal(1, 8), env.lookup("y"))
  }
}
