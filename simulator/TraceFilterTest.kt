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

import fourward.AssignmentEvent
import fourward.BranchEvent
import fourward.Choice
import fourward.Continuation
import fourward.Drop
import fourward.MarkToDropEvent
import fourward.OutputPacket
import fourward.PacketIngressEvent
import fourward.Replication
import fourward.TableLookupEvent
import fourward.TraceEvent
import fourward.TraceFilter
import fourward.TraceTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [filterEvents] — the caller-selected view over a [TraceTree]. */
class TraceFilterTest {

  // ---------------------------------------------------------------------------
  // Event builders. Ids are explicit in every test so the assertions can show
  // exactly which events survived.
  // ---------------------------------------------------------------------------

  private fun packetIngress(id: Long, port: Int): TraceEvent =
    TraceEvent.newBuilder()
      .setId(id)
      .setPacketIngress(PacketIngressEvent.newBuilder().setDataplaneIngressPort(port))
      .build()

  private fun tableLookup(id: Long, tableName: String): TraceEvent =
    TraceEvent.newBuilder()
      .setId(id)
      .setTableLookup(TableLookupEvent.newBuilder().setTableName(tableName))
      .build()

  private fun branch(id: Long, taken: Boolean): TraceEvent =
    TraceEvent.newBuilder().setId(id).setBranch(BranchEvent.newBuilder().setTaken(taken)).build()

  private fun assignment(id: Long, target: String): TraceEvent =
    TraceEvent.newBuilder()
      .setId(id)
      .setAssignment(AssignmentEvent.newBuilder().setTarget(target))
      .build()

  private fun markToDrop(id: Long): TraceEvent =
    TraceEvent.newBuilder().setId(id).setMarkToDrop(MarkToDropEvent.getDefaultInstance()).build()

  private fun outputTree(events: List<TraceEvent>, port: Int = 1): TraceTree =
    TraceTree.newBuilder()
      .addAllEvents(events)
      .setOutput(OutputPacket.newBuilder().setDataplaneEgressPort(port))
      .build()

  private fun include(vararg kinds: TraceEvent.Kind): TraceFilter =
    TraceFilter.newBuilder()
      .setInclude(TraceFilter.KindSet.newBuilder().addAllKinds(kinds.toList()))
      .build()

  private fun exclude(vararg kinds: TraceEvent.Kind): TraceFilter =
    TraceFilter.newBuilder()
      .setExclude(TraceFilter.KindSet.newBuilder().addAllKinds(kinds.toList()))
      .build()

  private fun eventIds(tree: TraceTree): List<Long> = tree.eventsList.map { it.id }

  // ---------------------------------------------------------------------------
  // Filter modes
  // ---------------------------------------------------------------------------

  @Test
  fun `unset filter returns the trace unchanged`() {
    val trace =
      outputTree(
        listOf(packetIngress(1, 0), tableLookup(2, "ipv4_lpm"), assignment(3, "hdr.ipv4.ttl"))
      )

    val filtered = trace.filterEvents(TraceFilter.getDefaultInstance())

    assertEquals(trace, filtered)
  }

  @Test
  fun `include keeps only the requested kinds`() {
    val trace =
      outputTree(
        listOf(
          packetIngress(1, 0),
          tableLookup(2, "ipv4_lpm"),
          assignment(3, "hdr.ipv4.ttl"),
          branch(4, taken = true),
          tableLookup(5, "acl"),
        )
      )

    val filtered = trace.filterEvents(include(TraceEvent.Kind.TABLE_LOOKUP))

    assertEquals(listOf(2L, 5L), eventIds(filtered))
    assertEquals("ipv4_lpm", filtered.getEvents(0).tableLookup.tableName)
    assertEquals("acl", filtered.getEvents(1).tableLookup.tableName)
  }

  @Test
  fun `include accepts several kinds and preserves chronological order`() {
    val trace =
      outputTree(
        listOf(
          packetIngress(1, 0),
          tableLookup(2, "ipv4_lpm"),
          assignment(3, "hdr.ipv4.ttl"),
          branch(4, taken = false),
        )
      )

    val filtered =
      trace.filterEvents(include(TraceEvent.Kind.BRANCH, TraceEvent.Kind.PACKET_INGRESS))

    assertEquals(listOf(1L, 4L), eventIds(filtered))
  }

  @Test
  fun `exclude drops the requested kinds and keeps everything else`() {
    val trace =
      outputTree(
        listOf(
          packetIngress(1, 0),
          assignment(2, "hdr.ipv4.ttl"),
          tableLookup(3, "ipv4_lpm"),
          assignment(4, "standard_metadata.egress_spec"),
        )
      )

    val filtered = trace.filterEvents(exclude(TraceEvent.Kind.ASSIGNMENT))

    assertEquals(listOf(1L, 3L), eventIds(filtered))
  }

  @Test
  fun `present but empty include drops every event and keeps the outcome`() {
    val trace = outputTree(listOf(packetIngress(1, 0), tableLookup(2, "ipv4_lpm")), port = 7)

    val filtered = trace.filterEvents(include())

    assertEquals(emptyList<Long>(), eventIds(filtered))
    assertEquals(7, filtered.output.dataplaneEgressPort)
  }

  @Test
  fun `present but empty exclude keeps every event`() {
    val trace = outputTree(listOf(packetIngress(1, 0), tableLookup(2, "ipv4_lpm")))

    val filtered = trace.filterEvents(exclude())

    assertEquals(trace, filtered)
  }

  // ---------------------------------------------------------------------------
  // Event ids are never renumbered
  // ---------------------------------------------------------------------------

  @Test
  fun `filtering leaves gaps in event ids rather than renumbering`() {
    // Ids come from the unfiltered trace, so a filtered trace stays diffable against
    // a full one and outcome causes keep resolving.
    val trace =
      outputTree(
        listOf(
          packetIngress(1, 0),
          assignment(2, "a"),
          assignment(3, "b"),
          tableLookup(4, "ipv4_lpm"),
        )
      )

    val filtered = trace.filterEvents(include(TraceEvent.Kind.TABLE_LOOKUP))

    assertEquals(listOf(4L), eventIds(filtered))
  }

  // ---------------------------------------------------------------------------
  // Cause events survive any filter
  // ---------------------------------------------------------------------------

  @Test
  fun `choice cause survives a filter that would otherwise drop it`() {
    val trace =
      TraceTree.newBuilder()
        .addEvents(packetIngress(1, 0))
        .addEvents(tableLookup(2, "wcmp_group"))
        .setChoice(
          Choice.newBuilder()
            .setCauseId(2)
            .addBranches(outputTree(listOf(branch(3, taken = true)), port = 1))
            .addBranches(outputTree(listOf(branch(4, taken = false)), port = 2))
        )
        .build()

    val filtered = trace.filterEvents(include(TraceEvent.Kind.BRANCH))

    // TABLE_LOOKUP is not in the filter, but event 2 is the Choice's cause: dropping it
    // would leave the outcome pointing at an event that isn't there.
    assertEquals(listOf(2L), eventIds(filtered))
    assertEquals(2L, filtered.choice.causeId)
  }

  @Test
  fun `drop cause survives a filter that would otherwise drop it`() {
    val trace =
      TraceTree.newBuilder()
        .addEvents(packetIngress(1, 0))
        .addEvents(markToDrop(2))
        .setDrop(Drop.newBuilder().setCauseId(2))
        .build()

    val filtered = trace.filterEvents(include(TraceEvent.Kind.PACKET_INGRESS))

    assertEquals(listOf(1L, 2L), eventIds(filtered))
    assertEquals(2L, filtered.drop.causeId)
  }

  @Test
  fun `replication cause survives a filter that would otherwise drop it`() {
    val trace =
      TraceTree.newBuilder()
        .addEvents(tableLookup(1, "ipv4_lpm"))
        .setReplication(
          Replication.newBuilder()
            .setCauseId(1)
            .addBranches(outputTree(listOf(assignment(2, "a")), port = 1))
        )
        .build()

    val filtered = trace.filterEvents(exclude(TraceEvent.Kind.TABLE_LOOKUP))

    assertEquals(listOf(1L), eventIds(filtered))
    assertEquals(1L, filtered.replication.causeId)
  }

  @Test
  fun `a causeless drop needs no special handling`() {
    val trace =
      TraceTree.newBuilder()
        .addEvents(packetIngress(1, 0))
        .addEvents(assignment(2, "a"))
        .setDrop(Drop.getDefaultInstance())
        .build()

    val filtered = trace.filterEvents(include(TraceEvent.Kind.PACKET_INGRESS))

    assertEquals(listOf(1L), eventIds(filtered))
    assertTrue(filtered.hasDrop())
  }

  // ---------------------------------------------------------------------------
  // Structure is never filtered — only events lists shrink
  // ---------------------------------------------------------------------------

  @Test
  fun `replication branches are filtered recursively`() {
    val trace =
      TraceTree.newBuilder()
        .addEvents(packetIngress(1, 0))
        .setReplication(
          Replication.newBuilder()
            .addBranches(
              outputTree(listOf(assignment(2, "a"), tableLookup(3, "egress_acl")), port = 1)
            )
            .addBranches(
              outputTree(listOf(assignment(4, "b"), tableLookup(5, "egress_acl")), port = 2)
            )
        )
        .build()

    val filtered = trace.filterEvents(include(TraceEvent.Kind.TABLE_LOOKUP))

    assertEquals(emptyList<Long>(), eventIds(filtered))
    assertEquals(2, filtered.replication.branchesCount)
    assertEquals(listOf(3L), eventIds(filtered.replication.getBranches(0)))
    assertEquals(listOf(5L), eventIds(filtered.replication.getBranches(1)))
    assertEquals(1, filtered.replication.getBranches(0).output.dataplaneEgressPort)
    assertEquals(2, filtered.replication.getBranches(1).output.dataplaneEgressPort)
  }

  @Test
  fun `continuation subtrees are filtered recursively`() {
    val trace =
      TraceTree.newBuilder()
        .addEvents(packetIngress(1, 0))
        .addEvents(assignment(2, "resubmit_flag"))
        .setContinuation(
          Continuation.newBuilder()
            .setKind(Continuation.Kind.RESUBMIT)
            .putPreservedFields("meta.x", "0x1")
            .setNext(outputTree(listOf(assignment(3, "b"), tableLookup(4, "ipv4_lpm")), port = 3))
        )
        .build()

    val filtered = trace.filterEvents(exclude(TraceEvent.Kind.ASSIGNMENT))

    assertEquals(listOf(1L), eventIds(filtered))
    assertEquals(Continuation.Kind.RESUBMIT, filtered.continuation.kind)
    assertEquals(mapOf("meta.x" to "0x1"), filtered.continuation.preservedFieldsMap)
    assertEquals(listOf(4L), eventIds(filtered.continuation.next))
    assertEquals(3, filtered.continuation.next.output.dataplaneEgressPort)
  }

  @Test
  fun `nested choice inside a replication branch is filtered at every depth`() {
    val trace =
      TraceTree.newBuilder()
        .addEvents(assignment(1, "a"))
        .setReplication(
          Replication.newBuilder()
            .addBranches(
              TraceTree.newBuilder()
                .addEvents(assignment(2, "b"))
                .addEvents(tableLookup(3, "wcmp_group"))
                .setChoice(
                  Choice.newBuilder()
                    .setCauseId(3)
                    .addBranches(outputTree(listOf(assignment(4, "c")), port = 1))
                    .addBranches(outputTree(listOf(assignment(5, "d")), port = 2))
                )
            )
        )
        .build()

    val filtered = trace.filterEvents(exclude(TraceEvent.Kind.ASSIGNMENT))

    val branch = filtered.replication.getBranches(0)
    assertEquals(emptyList<Long>(), eventIds(filtered))
    assertEquals(listOf(3L), eventIds(branch))
    assertEquals(emptyList<Long>(), eventIds(branch.choice.getBranches(0)))
    assertEquals(emptyList<Long>(), eventIds(branch.choice.getBranches(1)))
    assertEquals(2, branch.choice.branchesCount)
  }

  // ---------------------------------------------------------------------------
  // Malformed filters fail loudly
  // ---------------------------------------------------------------------------

  @Test
  fun `filter rejects KIND_UNSPECIFIED`() {
    val trace = outputTree(listOf(packetIngress(1, 0)))

    val e =
      assertThrows(IllegalArgumentException::class.java) {
        trace.filterEvents(include(TraceEvent.Kind.KIND_UNSPECIFIED))
      }

    assertTrue(e.message!!.contains("KIND_UNSPECIFIED"))
  }

  // ---------------------------------------------------------------------------
  // Drift guard
  // ---------------------------------------------------------------------------

  @Test
  fun `every TraceEvent oneof case has a matching Kind`() {
    // The exhaustive `when` in kind() catches a new oneof case at compile time, but only
    // once someone adds the enum value. This catches the other order: a new oneof case
    // with no Kind to name it.
    val oneofCases =
      TraceEvent.EventCase.values().filter { it != TraceEvent.EventCase.EVENT_NOT_SET }
    val kinds =
      TraceEvent.Kind.values().filter {
        it != TraceEvent.Kind.KIND_UNSPECIFIED && it != TraceEvent.Kind.UNRECOGNIZED
      }

    assertEquals(oneofCases.size, kinds.size)
  }
}
