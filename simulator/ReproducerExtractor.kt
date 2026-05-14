package fourward.simulator

import fourward.TraceEvent
import fourward.TraceTree
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update

/**
 * Extracts the minimal set of P4Runtime entities needed to reproduce a trace.
 *
 * Walks the [TraceTree] recursively and collects entities referenced by trace events: matched table
 * entries, modified default actions, clone sessions, multicast groups, and action profile
 * members/groups. Static entries (declared with `const entries` in the P4 source) are excluded —
 * they come with the pipeline config.
 */
fun extractReproducerEntities(
  trace: TraceTree,
  snapshot: TableStore.ForwardingSnapshot,
  tableStore: TableStore,
  staticEntries: List<Update>,
): List<Entity> {
  val staticTableEntries =
    staticEntries.mapNotNullTo(mutableSetOf()) { update ->
      update.entity.takeIf { it.hasTableEntry() }?.tableEntry
    }
  val entities = linkedSetOf<Entity>()
  collectEntities(trace, snapshot, tableStore, staticTableEntries, entities)
  return entities.toList()
}

private fun collectEntities(
  trace: TraceTree,
  snapshot: TableStore.ForwardingSnapshot,
  tableStore: TableStore,
  staticEntries: Set<TableEntry>,
  out: MutableSet<Entity>,
) {
  for (event in trace.eventsList) {
    collectFromEvent(event, snapshot, tableStore, staticEntries, out)
  }
  when (trace.outcomeCase) {
    TraceTree.OutcomeCase.FORK_OUTCOME -> {
      for (branch in trace.forkOutcome.branchesList) {
        collectEntities(branch.subtree, snapshot, tableStore, staticEntries, out)
      }
    }
    TraceTree.OutcomeCase.PACKET_OUTCOME,
    TraceTree.OutcomeCase.OUTCOME_NOT_SET,
    null -> {}
  }
}

private fun collectFromEvent(
  event: TraceEvent,
  snapshot: TableStore.ForwardingSnapshot,
  tableStore: TableStore,
  staticEntries: Set<TableEntry>,
  out: MutableSet<Entity>,
) {
  when (event.eventCase) {
    TraceEvent.EventCase.TABLE_LOOKUP -> {
      val lookup = event.tableLookup
      if (lookup.hit && lookup.hasMatchedEntry()) {
        val entry = lookup.matchedEntry
        if (entry !in staticEntries) {
          out += Entity.newBuilder().setTableEntry(entry).build()
          collectActionProfileEntities(entry, snapshot, tableStore, out)
        }
      } else if (!lookup.hit) {
        tableStore.buildModifiedDefaultActionEntity(lookup.tableName, snapshot)?.let { out += it }
      }
    }
    TraceEvent.EventCase.CLONE_SESSION_LOOKUP -> {
      val cloneLookup = event.cloneSessionLookup
      if (cloneLookup.sessionFound) {
        snapshot.cloneSessions[cloneLookup.sessionId]?.let { cloneSession ->
          out += buildPreEntity(cloneSession) { setCloneSessionEntry(it) }
        }
      }
    }
    TraceEvent.EventCase.MULTICAST_GROUP_LOOKUP -> {
      val lookup = event.multicastGroupLookup
      if (lookup.groupFound) {
        snapshot.multicastGroups[lookup.multicastGroupId]?.let { group ->
          out += buildPreEntity(group) { setMulticastGroupEntry(it) }
        }
      }
    }
    TraceEvent.EventCase.PACKET_INGRESS,
    TraceEvent.EventCase.PIPELINE_STAGE,
    TraceEvent.EventCase.PARSER_TRANSITION,
    TraceEvent.EventCase.ACTION_EXECUTION,
    TraceEvent.EventCase.BRANCH,
    TraceEvent.EventCase.ASSIGNMENT,
    TraceEvent.EventCase.EXTERN_CALL,
    TraceEvent.EventCase.LOG_MESSAGE,
    TraceEvent.EventCase.ASSERTION,
    TraceEvent.EventCase.CLONE,
    TraceEvent.EventCase.MARK_TO_DROP,
    TraceEvent.EventCase.DEPARSER_EMIT,
    TraceEvent.EventCase.EVENT_NOT_SET,
    null -> {}
  }
}

/** Collects action profile members and groups referenced by a matched table entry. */
private fun collectActionProfileEntities(
  entry: TableEntry,
  snapshot: TableStore.ForwardingSnapshot,
  tableStore: TableStore,
  out: MutableSet<Entity>,
) {
  if (!entry.hasAction()) return
  val profileId = tableStore.actionProfileIdForTable(entry.tableId) ?: return
  val tableAction = entry.action
  when (tableAction.typeCase) {
    p4.v1.P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_MEMBER_ID -> {
      snapshot.profileMembers[profileId]?.get(tableAction.actionProfileMemberId)?.let { member ->
        out += Entity.newBuilder().setActionProfileMember(member).build()
      }
    }
    p4.v1.P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_GROUP_ID -> {
      snapshot.profileGroups[profileId]?.get(tableAction.actionProfileGroupId)?.let { group ->
        out += Entity.newBuilder().setActionProfileGroup(group).build()
        for (memberRef in group.membersList) {
          snapshot.profileMembers[profileId]?.get(memberRef.memberId)?.let { member ->
            out += Entity.newBuilder().setActionProfileMember(member).build()
          }
        }
      }
    }
    p4.v1.P4RuntimeOuterClass.TableAction.TypeCase.ACTION,
    p4.v1.P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_ACTION_SET,
    p4.v1.P4RuntimeOuterClass.TableAction.TypeCase.TYPE_NOT_SET,
    null -> {}
  }
}
