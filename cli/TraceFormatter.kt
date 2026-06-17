package fourward.cli

import fourward.TraceEvent
import fourward.TraceTree

/** Renders a [TraceTree] as a human-readable indented string. */
object TraceFormatter {

  fun format(tree: TraceTree): String = buildString { appendTree(tree, indent = 0) }

  private fun StringBuilder.appendTree(tree: TraceTree, indent: Int) {
    for (event in tree.eventsList) {
      appendEvent(event, indent)
    }
    when (tree.outcomeCase) {
      TraceTree.OutcomeCase.REPLICATION -> {
        appendLine("${pad(indent)}replication")
        for (branch in tree.replication.branchesList) {
          appendTree(branch, indent + 1)
        }
      }
      TraceTree.OutcomeCase.CHOICE -> {
        appendLine("${pad(indent)}choice")
        for (branch in tree.choice.branchesList) {
          appendTree(branch, indent + 1)
        }
      }
      TraceTree.OutcomeCase.CONTINUATION -> {
        appendLine("${pad(indent)}continuation")
        appendTree(tree.continuation.next, indent + 1)
      }
      TraceTree.OutcomeCase.OUTPUT -> {
        val out = tree.output
        appendLine(
          "${pad(indent)}output port ${out.dataplaneEgressPort}, ${out.payload.size()} bytes"
        )
      }
      TraceTree.OutcomeCase.DROP -> appendLine("${pad(indent)}drop")
      TraceTree.OutcomeCase.OUTCOME_NOT_SET,
      null -> {}
    }
  }

  private fun StringBuilder.appendEvent(event: TraceEvent, indent: Int) {
    val prefix = pad(indent)
    when (event.eventCase) {
      TraceEvent.EventCase.PARSER_TRANSITION -> {
        val pt = event.parserTransition
        appendLine("${prefix}parse: ${pt.fromState} -> ${pt.toState}")
      }
      TraceEvent.EventCase.TABLE_LOOKUP -> {
        val tl = event.tableLookup
        val result = if (tl.hit) "hit" else "miss"
        appendLine("${prefix}table ${tl.tableName}: $result -> ${tl.actionName}")
      }
      TraceEvent.EventCase.ACTION_EXECUTION -> {
        val ae = event.actionExecution
        if (ae.paramsMap.isEmpty()) {
          appendLine("${prefix}action ${ae.actionName}")
        } else {
          val params = ae.paramsMap.entries.joinToString(", ") { (k, v) -> "$k=${v.decimal()}" }
          appendLine("${prefix}action ${ae.actionName}($params)")
        }
      }
      TraceEvent.EventCase.BRANCH -> {
        val b = event.branch
        val dir = if (b.taken) "then" else "else"
        appendLine("${prefix}branch ${b.controlName}: $dir")
      }
      TraceEvent.EventCase.EXTERN_CALL -> {
        val ec = event.externCall
        appendLine("${prefix}extern ${ec.externInstanceName}.${ec.method}()")
      }
      TraceEvent.EventCase.MARK_TO_DROP -> appendLine("${prefix}mark_to_drop()")
      TraceEvent.EventCase.CLONE -> appendLine("${prefix}clone session ${event.clone.sessionId}")
      TraceEvent.EventCase.LOG_MESSAGE ->
        appendLine("${prefix}log_msg: ${event.logMessage.message}")
      TraceEvent.EventCase.ASSERTION -> {
        val result = if (event.assertion.passed) "passed" else "FAILED"
        appendLine("${prefix}assert: $result")
      }
      TraceEvent.EventCase.ASSIGNMENT -> {
        val a = event.assignment
        appendLine("${prefix}${a.target} = ${event.resultValue}")
      }
      TraceEvent.EventCase.MULTICAST_GROUP_LOOKUP -> {
        val ml = event.multicastGroupLookup
        val result = if (ml.groupFound) "${ml.replicaCount} replicas" else "not found"
        appendLine("${prefix}multicast group ${ml.multicastGroupId}: $result")
      }
      TraceEvent.EventCase.CONTINUATION_TRIGGER -> {
        val ct = event.continuationTrigger
        val kind = ct.kind.name.lowercase()
        if (ct.preservedFieldsCount > 0) {
          val fields = ct.preservedFieldsMap.entries.joinToString(", ") { "${it.key}=${it.value}" }
          appendLine("${prefix}$kind (preserved: $fields)")
        } else {
          appendLine("${prefix}$kind")
        }
      }
      TraceEvent.EventCase.PACKET_INGRESS,
      TraceEvent.EventCase.PIPELINE_STAGE,
      TraceEvent.EventCase.CLONE_SESSION_LOOKUP,
      TraceEvent.EventCase.DEPARSER_EMIT,
      TraceEvent.EventCase.EVENT_NOT_SET,
      null -> {}
    }
  }

  private fun pad(level: Int): String = "  ".repeat(level)

  private fun com.google.protobuf.ByteString.decimal(): String =
    java.math.BigInteger(1, toByteArray()).toString()
}
