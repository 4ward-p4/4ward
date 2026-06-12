package fourward.cli

import com.google.protobuf.ByteString
import fourward.ActionExecutionEvent
import fourward.Alternative
import fourward.AssertionEvent
import fourward.Choice
import fourward.Continuation
import fourward.ContinuationKind
import fourward.Continuations
import fourward.Drop
import fourward.DropReason
import fourward.LogMessageEvent
import fourward.MarkToDropEvent
import fourward.OutputPacket
import fourward.PacketOutcome
import fourward.ParserTransitionEvent
import fourward.TableLookupEvent
import fourward.TraceEvent
import fourward.TraceTree
import org.junit.Assert.assertEquals
import org.junit.Test

class TraceFormatterTest {

  @Test
  fun simplePassthrough() {
    val tree =
      TraceTree.newBuilder()
        .addEvents(
          TraceEvent.newBuilder()
            .setParserTransition(
              ParserTransitionEvent.newBuilder()
                .setParserName("MyParser")
                .setFromState("start")
                .setToState("accept")
            )
        )
        .addEvents(
          TraceEvent.newBuilder()
            .setActionExecution(ActionExecutionEvent.newBuilder().setActionName("NoAction"))
        )
        .setPacketOutcome(
          PacketOutcome.newBuilder()
            .setOutput(
              OutputPacket.newBuilder()
                .setDataplaneEgressPort(1)
                .setPayload(ByteString.copyFrom(byteArrayOf(0xDE.toByte(), 0xAD.toByte())))
            )
        )
        .build()

    val output = TraceFormatter.format(tree)
    assertEquals(
      """
      |parse: start -> accept
      |action NoAction
      |output port 1, 2 bytes
      |"""
        .trimMargin(),
      output,
    )
  }

  @Test
  fun tableLookupWithParams() {
    val tree =
      TraceTree.newBuilder()
        .addEvents(
          TraceEvent.newBuilder()
            .setTableLookup(
              TableLookupEvent.newBuilder()
                .setTableName("port_table")
                .setHit(true)
                .setActionName("forward")
            )
        )
        .addEvents(
          TraceEvent.newBuilder()
            .setActionExecution(
              ActionExecutionEvent.newBuilder()
                .setActionName("forward")
                .putParams("port", ByteString.copyFrom(byteArrayOf(0x01)))
            )
        )
        .setPacketOutcome(
          PacketOutcome.newBuilder()
            .setOutput(
              OutputPacket.newBuilder().setDataplaneEgressPort(1).setPayload(ByteString.EMPTY)
            )
        )
        .build()

    val output = TraceFormatter.format(tree)
    assertEquals(
      """
      |table port_table: hit -> forward
      |action forward(port=1)
      |output port 1, 0 bytes
      |"""
        .trimMargin(),
      output,
    )
  }

  @Test
  fun dropWithMarkToDrop() {
    val tree =
      TraceTree.newBuilder()
        .addEvents(
          TraceEvent.newBuilder()
            .setMarkToDrop(MarkToDropEvent.newBuilder().setReason(DropReason.MARK_TO_DROP))
        )
        .setPacketOutcome(
          PacketOutcome.newBuilder().setDrop(Drop.newBuilder().setReason(DropReason.MARK_TO_DROP))
        )
        .build()

    val output = TraceFormatter.format(tree)
    assertEquals(
      """
      |mark_to_drop()
      |drop (reason: mark_to_drop)
      |"""
        .trimMargin(),
      output,
    )
  }

  @Test
  fun cloneContinuations() {
    val tree =
      TraceTree.newBuilder()
        .addEvents(
          TraceEvent.newBuilder()
            .setParserTransition(
              ParserTransitionEvent.newBuilder().setFromState("start").setToState("accept")
            )
        )
        .setContinuations(
          Continuations.newBuilder()
            .addContinuations(
              Continuation.newBuilder()
                .setKind(ContinuationKind.ORIGINAL)
                .setSubtree(
                  TraceTree.newBuilder()
                    .setPacketOutcome(
                      PacketOutcome.newBuilder()
                        .setOutput(
                          OutputPacket.newBuilder()
                            .setDataplaneEgressPort(1)
                            .setPayload(ByteString.EMPTY)
                        )
                    )
                )
            )
            .addContinuations(
              Continuation.newBuilder()
                .setKind(ContinuationKind.CLONE)
                .setDataplaneEgressPort(2)
                .setInstance(1)
                .setSubtree(
                  TraceTree.newBuilder()
                    .setPacketOutcome(
                      PacketOutcome.newBuilder()
                        .setOutput(
                          OutputPacket.newBuilder()
                            .setDataplaneEgressPort(2)
                            .setPayload(ByteString.EMPTY)
                        )
                    )
                )
            )
        )
        .build()

    val output = TraceFormatter.format(tree)
    assertEquals(
      """
      |parse: start -> accept
      |continues as:
      |  original:
      |    output port 1, 0 bytes
      |  clone port 2 instance 1:
      |    output port 2, 0 bytes
      |"""
        .trimMargin(),
      output,
    )
  }

  @Test
  fun choiceTree() {
    val tree =
      TraceTree.newBuilder()
        .addEvents(
          TraceEvent.newBuilder()
            .setParserTransition(
              ParserTransitionEvent.newBuilder().setFromState("start").setToState("accept")
            )
        )
        .setChoice(
          Choice.newBuilder()
            .addAlternatives(
              Alternative.newBuilder()
                .setMemberId(7)
                .setSubtree(
                  TraceTree.newBuilder()
                    .setPacketOutcome(
                      PacketOutcome.newBuilder()
                        .setOutput(
                          OutputPacket.newBuilder()
                            .setDataplaneEgressPort(1)
                            .setPayload(ByteString.EMPTY)
                        )
                    )
                )
            )
            .addAlternatives(
              Alternative.newBuilder()
                .setMemberId(8)
                .setSubtree(
                  TraceTree.newBuilder()
                    .setPacketOutcome(
                      PacketOutcome.newBuilder()
                        .setOutput(
                          OutputPacket.newBuilder()
                            .setDataplaneEgressPort(2)
                            .setPayload(ByteString.EMPTY)
                        )
                    )
                )
            )
        )
        .build()

    val output = TraceFormatter.format(tree)
    assertEquals(
      """
      |parse: start -> accept
      |one of (action selector):
      |  member 7:
      |    output port 1, 0 bytes
      |  member 8:
      |    output port 2, 0 bytes
      |"""
        .trimMargin(),
      output,
    )
  }

  @Test
  fun logMessageEvent() {
    val tree =
      TraceTree.newBuilder()
        .addEvents(
          TraceEvent.newBuilder()
            .setLogMessage(LogMessageEvent.newBuilder().setMessage("TTL = 64, port = 1"))
        )
        .setPacketOutcome(
          PacketOutcome.newBuilder()
            .setOutput(
              OutputPacket.newBuilder().setDataplaneEgressPort(1).setPayload(ByteString.EMPTY)
            )
        )
        .build()

    assertEquals(
      """
      |log_msg: TTL = 64, port = 1
      |output port 1, 0 bytes
      |"""
        .trimMargin(),
      TraceFormatter.format(tree),
    )
  }

  @Test
  fun assertionPassedEvent() {
    val tree =
      TraceTree.newBuilder()
        .addEvents(
          TraceEvent.newBuilder().setAssertion(AssertionEvent.newBuilder().setPassed(true))
        )
        .setPacketOutcome(
          PacketOutcome.newBuilder()
            .setOutput(
              OutputPacket.newBuilder().setDataplaneEgressPort(1).setPayload(ByteString.EMPTY)
            )
        )
        .build()

    assertEquals(
      """
      |assert: passed
      |output port 1, 0 bytes
      |"""
        .trimMargin(),
      TraceFormatter.format(tree),
    )
  }

  @Test
  fun assertionFailedDrop() {
    val tree =
      TraceTree.newBuilder()
        .addEvents(
          TraceEvent.newBuilder().setAssertion(AssertionEvent.newBuilder().setPassed(false))
        )
        .setPacketOutcome(
          PacketOutcome.newBuilder()
            .setDrop(Drop.newBuilder().setReason(DropReason.ASSERTION_FAILURE))
        )
        .build()

    assertEquals(
      """
      |assert: FAILED
      |drop (reason: assertion failure)
      |"""
        .trimMargin(),
      TraceFormatter.format(tree),
    )
  }
}
