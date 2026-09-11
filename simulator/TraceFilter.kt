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

import fourward.TraceEvent
import fourward.TraceFilter
import fourward.TraceTree

/**
 * Returns the [TraceEvent.Kind] naming this event's oneof case.
 *
 * The `when` is exhaustive on purpose: a new oneof case in `simulator.proto` breaks this
 * compilation until someone adds the matching [TraceEvent.Kind], which is the only thing keeping
 * the enum and the oneof in step.
 */
fun TraceEvent.kind(): TraceEvent.Kind =
  when (eventCase) {
    TraceEvent.EventCase.PACKET_INGRESS -> TraceEvent.Kind.PACKET_INGRESS
    TraceEvent.EventCase.PIPELINE_STAGE -> TraceEvent.Kind.PIPELINE_STAGE
    TraceEvent.EventCase.PARSER_TRANSITION -> TraceEvent.Kind.PARSER_TRANSITION
    TraceEvent.EventCase.TABLE_LOOKUP -> TraceEvent.Kind.TABLE_LOOKUP
    TraceEvent.EventCase.ACTION_EXECUTION -> TraceEvent.Kind.ACTION_EXECUTION
    TraceEvent.EventCase.BRANCH -> TraceEvent.Kind.BRANCH
    TraceEvent.EventCase.ASSIGNMENT -> TraceEvent.Kind.ASSIGNMENT
    TraceEvent.EventCase.EXTERN_CALL -> TraceEvent.Kind.EXTERN_CALL
    TraceEvent.EventCase.LOG_MESSAGE -> TraceEvent.Kind.LOG_MESSAGE
    TraceEvent.EventCase.ASSERTION -> TraceEvent.Kind.ASSERTION
    TraceEvent.EventCase.CLONE -> TraceEvent.Kind.CLONE
    TraceEvent.EventCase.CLONE_SESSION_LOOKUP -> TraceEvent.Kind.CLONE_SESSION_LOOKUP
    TraceEvent.EventCase.MULTICAST_GROUP_LOOKUP -> TraceEvent.Kind.MULTICAST_GROUP_LOOKUP
    TraceEvent.EventCase.MARK_TO_DROP -> TraceEvent.Kind.MARK_TO_DROP
    TraceEvent.EventCase.DEPARSER_EMIT -> TraceEvent.Kind.DEPARSER_EMIT
    TraceEvent.EventCase.EVENT_NOT_SET,
    null -> error("trace event $id has no event set; every emitted event sets one oneof case")
  }

/**
 * Returns a copy of this tree containing only the events [filter] asks for.
 *
 * Purely subtractive: branches, continuations, and outcomes are preserved exactly, event ids are
 * never renumbered, and events named as an outcome's `cause_id` are retained regardless of the
 * filter. See `TraceFilter` in `simulator.proto` for the full contract.
 *
 * Apply this last, at the point the trace leaves the process. The simulator and its enrichment
 * layers read trace events themselves — resolving causes, extracting reproducer entities,
 * translating P4Runtime values — and all of that needs the complete trace.
 *
 * @throws IllegalArgumentException if the filter names `KIND_UNSPECIFIED`.
 */
fun TraceTree.filterEvents(filter: TraceFilter): TraceTree {
  val kinds = filter.requestedKinds()
  return when (filter.modeCase) {
    TraceFilter.ModeCase.INCLUDE -> retainEvents { it in kinds }
    TraceFilter.ModeCase.EXCLUDE -> retainEvents { it !in kinds }
    // No mode set means no filtering — the default TraceFilter returns the full trace.
    TraceFilter.ModeCase.MODE_NOT_SET,
    null -> this
  }
}

/**
 * Throws if this filter is malformed.
 *
 * Separate from [filterEvents] so a streaming caller can reject a bad filter once, while setting
 * the subscription up, instead of failing identically on every packet that flows through it.
 *
 * @throws IllegalArgumentException if the filter names `KIND_UNSPECIFIED`.
 */
fun TraceFilter.validate() {
  requestedKinds()
}

/**
 * The kinds this filter names, whichever mode it uses. Empty when no mode is set.
 *
 * `KIND_UNSPECIFIED` is never the kind of a real event, so including it would be a silent no-op and
 * excluding it would silently do nothing — both of which look like a working filter to the caller.
 */
private fun TraceFilter.requestedKinds(): Set<TraceEvent.Kind> {
  val kinds =
    when (modeCase) {
      TraceFilter.ModeCase.INCLUDE -> include.kindsList
      TraceFilter.ModeCase.EXCLUDE -> exclude.kindsList
      TraceFilter.ModeCase.MODE_NOT_SET,
      null -> emptyList()
    }
  require(TraceEvent.Kind.KIND_UNSPECIFIED !in kinds) {
    "TraceFilter names KIND_UNSPECIFIED, which no event ever has; list the event kinds explicitly"
  }
  return kinds.toSet()
}

/** Recursively rebuilds the tree, keeping events whose kind satisfies [keep] plus cause events. */
private fun TraceTree.retainEvents(keep: (TraceEvent.Kind) -> Boolean): TraceTree {
  val causeIds = outcomeCauseIds()
  val filtered = TraceTree.newBuilder()
  for (event in eventsList) {
    if (keep(event.kind()) || event.id in causeIds) filtered.addEvents(event)
  }

  when (outcomeCase) {
    TraceTree.OutcomeCase.REPLICATION -> {
      val branches = replication.toBuilder().clearBranches()
      for (branch in replication.branchesList) branches.addBranches(branch.retainEvents(keep))
      filtered.setReplication(branches)
    }
    TraceTree.OutcomeCase.CHOICE -> {
      val branches = choice.toBuilder().clearBranches()
      for (branch in choice.branchesList) branches.addBranches(branch.retainEvents(keep))
      filtered.setChoice(branches)
    }
    TraceTree.OutcomeCase.CONTINUATION ->
      filtered.setContinuation(
        continuation.toBuilder().setNext(continuation.next.retainEvents(keep))
      )
    TraceTree.OutcomeCase.OUTPUT -> filtered.setOutput(output)
    TraceTree.OutcomeCase.DROP -> filtered.setDrop(drop)
    TraceTree.OutcomeCase.OUTCOME_NOT_SET,
    null -> {}
  }
  return filtered.build()
}

/** Ids of events this node's outcome points at. Node-local by construction. */
private fun TraceTree.outcomeCauseIds(): Set<Long> =
  when (outcomeCase) {
    TraceTree.OutcomeCase.REPLICATION ->
      setOfNotNull(replication.causeId.takeIf { replication.hasCauseId() })
    TraceTree.OutcomeCase.CHOICE -> setOfNotNull(choice.causeId.takeIf { choice.hasCauseId() })
    TraceTree.OutcomeCase.DROP -> setOfNotNull(drop.causeId.takeIf { drop.hasCauseId() })
    TraceTree.OutcomeCase.CONTINUATION,
    TraceTree.OutcomeCase.OUTPUT,
    TraceTree.OutcomeCase.OUTCOME_NOT_SET,
    null -> emptySet()
  }
