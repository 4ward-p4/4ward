package fourward.cli

import com.google.protobuf.ByteString
import fourward.ActionExecutionEvent
import fourward.AssertionEvent
import fourward.Continuation
import fourward.Drop
import fourward.LogMessageEvent
import fourward.MarkToDropEvent
import fourward.OutputPacket
import fourward.ParserTransitionEvent
import fourward.Replication
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
        .setOutput(
          OutputPacket.newBuilder()
            .setDataplaneEgressPort(1)
            .setPayload(ByteString.copyFrom(byteArrayOf(0xDE.toByte(), 0xAD.toByte())))
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
        .setOutput(OutputPacket.newBuilder().setDataplaneEgressPort(1).setPayload(ByteString.EMPTY))
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
        .addEvents(TraceEvent.newBuilder().setMarkToDrop(MarkToDropEvent.newBuilder()))
        .setDrop(Drop.getDefaultInstance())
        .build()

    val output = TraceFormatter.format(tree)
    assertEquals(
      """
      |mark_to_drop()
      |drop
      |"""
        .trimMargin(),
      output,
    )
  }

  @Test
  fun forkTree() {
    val tree =
      TraceTree.newBuilder()
        .addEvents(
          TraceEvent.newBuilder()
            .setParserTransition(
              ParserTransitionEvent.newBuilder().setFromState("start").setToState("accept")
            )
        )
        .setReplication(
          Replication.newBuilder()
            .addBranches(
              TraceTree.newBuilder()
                .setOutput(
                  OutputPacket.newBuilder().setDataplaneEgressPort(1).setPayload(ByteString.EMPTY)
                )
            )
            .addBranches(
              TraceTree.newBuilder()
                .setOutput(
                  OutputPacket.newBuilder().setDataplaneEgressPort(2).setPayload(ByteString.EMPTY)
                )
            )
        )
        .build()

    val output = TraceFormatter.format(tree)
    assertEquals(
      """
      |parse: start -> accept
      |replication
      |  output port 1, 0 bytes
      |  output port 2, 0 bytes
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
        .setOutput(OutputPacket.newBuilder().setDataplaneEgressPort(1).setPayload(ByteString.EMPTY))
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
        .setOutput(OutputPacket.newBuilder().setDataplaneEgressPort(1).setPayload(ByteString.EMPTY))
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
        .setDrop(Drop.getDefaultInstance())
        .build()

    assertEquals(
      """
      |assert: FAILED
      |drop
      |"""
        .trimMargin(),
      TraceFormatter.format(tree),
    )
  }

  @Test
  fun continuationTriggerResubmit() {
    val tree =
      TraceTree.newBuilder()
        .setContinuation(
          Continuation.newBuilder()
            .setKind(Continuation.Kind.RESUBMIT)
            .setNext(
              TraceTree.newBuilder()
                .setOutput(
                  OutputPacket.newBuilder().setDataplaneEgressPort(1).setPayload(ByteString.EMPTY)
                )
            )
        )
        .build()

    assertEquals(
      """
      |resubmit
      |  output port 1, 0 bytes
      |"""
        .trimMargin(),
      TraceFormatter.format(tree),
    )
  }

  @Test
  fun continuationTriggerWithPreservedFields() {
    val tree =
      TraceTree.newBuilder()
        .setContinuation(
          Continuation.newBuilder()
            .setKind(Continuation.Kind.RESUBMIT)
            .putPreservedFields("tag", "48879")
            .setNext(
              TraceTree.newBuilder()
                .setOutput(
                  OutputPacket.newBuilder().setDataplaneEgressPort(1).setPayload(ByteString.EMPTY)
                )
            )
        )
        .build()

    assertEquals(
      """
      |resubmit (preserved: tag=48879)
      |  output port 1, 0 bytes
      |"""
        .trimMargin(),
      TraceFormatter.format(tree),
    )
  }
}
