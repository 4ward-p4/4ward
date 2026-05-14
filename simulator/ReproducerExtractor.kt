package fourward.simulator

import fourward.ForkReason
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
      val fork = trace.forkOutcome
      if (fork.reason == ForkReason.MULTICAST) {
        collectMulticastGroups(snapshot, out)
      }
      for (branch in fork.branchesList) {
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
          collectActionProfileEntities(entry, snapshot, out)
        }
      } else if (!lookup.hit) {
        tableStore.buildModifiedDefaultActionEntity(lookup.tableName, snapshot)?.let { out += it }
      }
    }
    TraceEvent.EventCase.CLONE_SESSION_LOOKUP -> {
      val cloneLookup = event.cloneSessionLookup
      if (cloneLookup.sessionFound) {
        snapshot.cloneSessions[cloneLookup.sessionId]?.let { cloneSession ->
          out +=
            Entity.newBuilder()
              .setPacketReplicationEngineEntry(
                p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
                  .setCloneSessionEntry(cloneSession)
              )
              .build()
        }
      }
    }
    TraceEvent.EventCase.PARSER_TRANSITION,
    TraceEvent.EventCase.ACTION_EXECUTION,
    TraceEvent.EventCase.BRANCH,
    TraceEvent.EventCase.EXTERN_CALL,
    TraceEvent.EventCase.MARK_TO_DROP,
    TraceEvent.EventCase.CLONE,
    TraceEvent.EventCase.PACKET_INGRESS,
    TraceEvent.EventCase.PIPELINE_STAGE,
    TraceEvent.EventCase.LOG_MESSAGE,
    TraceEvent.EventCase.ASSERTION,
    TraceEvent.EventCase.DEPARSER_EMIT,
    TraceEvent.EventCase.ASSIGNMENT,
    TraceEvent.EventCase.EVENT_NOT_SET,
    null -> {}
  }
}

private fun collectMulticastGroups(
  snapshot: TableStore.ForwardingSnapshot,
  out: MutableSet<Entity>,
) {
  // TODO: Extract the specific multicast group ID from the Fork proto once the
  // architecture records it there. For now, include all multicast groups from the
  // snapshot — the typical reproducer involves at most one or two.
  for ((_, group) in snapshot.multicastGroups) {
    out +=
      Entity.newBuilder()
        .setPacketReplicationEngineEntry(
          p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder()
            .setMulticastGroupEntry(group)
        )
        .build()
  }
}

/** Collects action profile members and groups referenced by a matched table entry. */
private fun collectActionProfileEntities(
  entry: TableEntry,
  snapshot: TableStore.ForwardingSnapshot,
  out: MutableSet<Entity>,
) {
  if (!entry.hasAction()) return
  val tableAction = entry.action
  when (tableAction.typeCase) {
    p4.v1.P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_MEMBER_ID -> {
      val memberId = tableAction.actionProfileMemberId
      for ((profileId, members) in snapshot.profileMembers) {
        members[memberId]?.let { member ->
          out += Entity.newBuilder().setActionProfileMember(member).build()
        }
      }
    }
    p4.v1.P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_GROUP_ID -> {
      val groupId = tableAction.actionProfileGroupId
      for ((profileId, groups) in snapshot.profileGroups) {
        groups[groupId]?.let { group ->
          out += Entity.newBuilder().setActionProfileGroup(group).build()
          // Also collect members referenced by the group.
          for (memberRef in group.membersList) {
            snapshot.profileMembers[profileId]?.get(memberRef.memberId)?.let { member ->
              out += Entity.newBuilder().setActionProfileMember(member).build()
            }
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
