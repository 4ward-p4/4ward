package fourward.cli

import fourward.DropReason
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
      TraceTree.OutcomeCase.FORK_OUTCOME -> {
        val fork = tree.forkOutcome
        appendLine("${pad(indent)}fork (${fork.reason.humanName()})")
        for (branch in fork.branchesList) {
          appendLine("${pad(indent + 1)}branch: ${branch.label}")
          appendTree(branch.subtree, indent + 2)
        }
      }
      TraceTree.OutcomeCase.PACKET_OUTCOME -> {
        val outcome = tree.packetOutcome
        when (outcome.outcomeCase) {
          fourward.PacketOutcome.OutcomeCase.OUTPUT -> {
            val out = outcome.output
            appendLine(
              "${pad(indent)}output port ${out.dataplaneEgressPort}, ${out.payload.size()} bytes"
            )
          }
          fourward.PacketOutcome.OutcomeCase.DROP -> {
            appendLine("${pad(indent)}drop (reason: ${outcome.drop.reason.humanName()})")
          }
          fourward.PacketOutcome.OutcomeCase.OUTCOME_NOT_SET,
          null -> {}
        }
      }
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

  private fun DropReason.humanName(): String =
    when (this) {
      DropReason.MARK_TO_DROP -> "mark_to_drop"
      DropReason.PARSER_REJECT -> "parser reject"
      DropReason.PIPELINE_EXECUTION_LIMIT_REACHED -> "execution limit"
      DropReason.ASSERTION_FAILURE -> "assertion failure"
      else -> "unknown"
    }

  private fun fourward.ForkReason.humanName(): String =
    name.removePrefix("ACTION_").lowercase().replace('_', ' ')
}
