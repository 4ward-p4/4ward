// See comment in GoldenErrorTest.kt — `val unused = stub.X(...)` discards must-be-used gRPC
// return values flagged by the downstream sync's `@CheckReturnValue` lint.
@file:Suppress("UnusedPrivateProperty")

package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.PipelineConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.findAction
import fourward.p4runtime.P4RuntimeTestHarness.Companion.findTable
import fourward.p4runtime.P4RuntimeTestHarness.Companion.loadConfig
import fourward.p4runtime.P4RuntimeTestHarness.Companion.matchFieldId
import fourward.p4runtime.P4RuntimeTestHarness.Companion.paramId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import p4.v1.P4RuntimeOuterClass.Action
import p4.v1.P4RuntimeOuterClass.ActionProfileGroup
import p4.v1.P4RuntimeOuterClass.ActionProfileMember
import p4.v1.P4RuntimeOuterClass.CloneSessionEntry
import p4.v1.P4RuntimeOuterClass.CounterData
import p4.v1.P4RuntimeOuterClass.CounterEntry
import p4.v1.P4RuntimeOuterClass.DirectCounterEntry
import p4.v1.P4RuntimeOuterClass.DirectMeterEntry
import p4.v1.P4RuntimeOuterClass.Entity
import p4.v1.P4RuntimeOuterClass.FieldMatch
import p4.v1.P4RuntimeOuterClass.Index
import p4.v1.P4RuntimeOuterClass.MeterConfig
import p4.v1.P4RuntimeOuterClass.MeterEntry
import p4.v1.P4RuntimeOuterClass.MulticastGroupEntry
import p4.v1.P4RuntimeOuterClass.PacketReplicationEngineEntry
import p4.v1.P4RuntimeOuterClass.ReadRequest
import p4.v1.P4RuntimeOuterClass.RegisterEntry
import p4.v1.P4RuntimeOuterClass.Replica
import p4.v1.P4RuntimeOuterClass.TableAction
import p4.v1.P4RuntimeOuterClass.TableEntry
import p4.v1.P4RuntimeOuterClass.Update

/**
 * Read-write symmetry tests, per P4Runtime spec §11.2: an entity Read response must be canonically
 * equivalent to what was written. Every entity type 4ward supports is exercised here, with
 * translated values where the entity carries any. A new entity type added without its matching
 * read-side translation breaks one of these assertions.
 *
 * The fixture pipeline (round_trip.p4) declares a `@p4runtime_translation` port type and uses it
 * in: a table match field, an action param, a direct counter and direct meter on that table, and an
 * action profile attached to that table. PRE replicas use the same translation at runtime without
 * any P4-level declaration.
 */
class RoundTripTest {

  private lateinit var harness: P4RuntimeTestHarness
  private lateinit var config: PipelineConfig

  @Before
  fun setUp() {
    harness = P4RuntimeTestHarness()
    config = loadConfig("e2e_tests/translation_round_trip/round_trip.txtpb")
    val unused = harness.loadPipeline(config)
  }

  @After
  fun tearDown() {
    harness.close()
  }

  // Arbitrary controller-side port identifiers used throughout. Format is opaque to the test —
  // the simulator allocates an internal dataplane integer on first Write and must reverse-
  // translate to the original string on Read. Any two distinct strings would do.
  private val sdnPortA = "port-A"
  private val sdnPortB = "port-B"

  // ---------------------------------------------------------------------------
  // Translated entity types
  // ---------------------------------------------------------------------------

  @Test
  fun `TABLE_ENTRY round-trips translated match value and action param`() {
    assertRoundTrips(
      installed = translatedTableEntry(matchPort = sdnPortA, actionPort = sdnPortB),
      // The table has a direct counter and direct meter attached, so reads come back with empty
      // counter_data / meter_config that the install request never set; strip those before
      // comparison.
      normalize = ::stripAmbientTableEntryFields,
    )
  }

  @Test
  fun `ACTION_PROFILE_MEMBER round-trips translated action param`() {
    assertRoundTrips(installed = actionProfileMember(memberId = 1, port = sdnPortA))
  }

  @Test
  fun `DIRECT_COUNTER_ENTRY round-trips translated table-entry match`() {
    // The embedded TableEntry carries the translated match value; reads must reverse-translate it.
    val parent = translatedTableEntry(matchPort = sdnPortA, actionPort = sdnPortB)
    harness.installEntry(parent)
    assertRoundTrips(
      type = Update.Type.MODIFY,
      installed =
        Entity.newBuilder()
          .setDirectCounterEntry(
            DirectCounterEntry.newBuilder()
              .setTableEntry(parent.tableEntry)
              .setData(CounterData.newBuilder().setPacketCount(42).setByteCount(1500))
          )
          .build(),
    )
  }

  @Test
  fun `DIRECT_METER_ENTRY round-trips translated table-entry match`() {
    val parent = translatedTableEntry(matchPort = sdnPortA, actionPort = sdnPortB)
    harness.installEntry(parent)
    assertRoundTrips(
      type = Update.Type.MODIFY,
      installed =
        Entity.newBuilder()
          .setDirectMeterEntry(
            DirectMeterEntry.newBuilder()
              .setTableEntry(parent.tableEntry)
              .setConfig(
                MeterConfig.newBuilder().setCir(1000).setCburst(2000).setPir(3000).setPburst(4000)
              )
          )
          .build(),
    )
  }

  @Test
  fun `PACKET_REPLICATION_ENGINE_ENTRY clone session round-trips translated replica port`() {
    assertRoundTrips(
      installed =
        Entity.newBuilder()
          .setPacketReplicationEngineEntry(
            PacketReplicationEngineEntry.newBuilder()
              .setCloneSessionEntry(
                CloneSessionEntry.newBuilder()
                  .setSessionId(1)
                  .addReplicas(replica(sdnPortA, instance = 1))
                  .addReplicas(replica(sdnPortB, instance = 2))
              )
          )
          .build()
    )
  }

  @Test
  fun `PACKET_REPLICATION_ENGINE_ENTRY multicast group round-trips translated replica port`() {
    assertRoundTrips(
      installed =
        Entity.newBuilder()
          .setPacketReplicationEngineEntry(
            PacketReplicationEngineEntry.newBuilder()
              .setMulticastGroupEntry(
                MulticastGroupEntry.newBuilder()
                  .setMulticastGroupId(7)
                  .addReplicas(replica(sdnPortA, instance = 1))
              )
          )
          .build()
    )
  }

  // ---------------------------------------------------------------------------
  // Pass-through entity types — no translated content, but must still round-trip.
  // ---------------------------------------------------------------------------

  @Test
  fun `ACTION_PROFILE_GROUP round-trips`() {
    harness.installEntry(actionProfileMember(memberId = 1, port = sdnPortA))
    val ap = config.p4Info.actionProfilesList.first { it.preamble.alias == "ap" }
    assertRoundTrips(
      installed =
        Entity.newBuilder()
          .setActionProfileGroup(
            ActionProfileGroup.newBuilder()
              .setActionProfileId(ap.preamble.id)
              .setGroupId(10)
              .addMembers(ActionProfileGroup.Member.newBuilder().setMemberId(1).setWeight(1))
          )
          .build()
    )
  }

  @Test
  fun `COUNTER_ENTRY round-trips`() {
    val counter = config.p4Info.countersList.first { it.preamble.alias == "plain_counter" }
    assertRoundTrips(
      type = Update.Type.MODIFY,
      installed =
        Entity.newBuilder()
          .setCounterEntry(
            CounterEntry.newBuilder()
              .setCounterId(counter.preamble.id)
              .setIndex(Index.newBuilder().setIndex(3))
              .setData(CounterData.newBuilder().setPacketCount(42).setByteCount(1500))
          )
          .build(),
    )
  }

  @Test
  fun `METER_ENTRY round-trips`() {
    val meter = config.p4Info.metersList.first { it.preamble.alias == "plain_meter" }
    assertRoundTrips(
      type = Update.Type.MODIFY,
      installed =
        Entity.newBuilder()
          .setMeterEntry(
            MeterEntry.newBuilder()
              .setMeterId(meter.preamble.id)
              .setIndex(Index.newBuilder().setIndex(2))
              .setConfig(
                MeterConfig.newBuilder().setCir(1000).setCburst(2000).setPir(3000).setPburst(4000)
              )
          )
          .build(),
    )
  }

  @Test
  fun `REGISTER_ENTRY round-trips`() {
    val register = config.p4Info.registersList.first { it.preamble.alias == "plain_register" }
    assertRoundTrips(
      type = Update.Type.MODIFY,
      installed =
        Entity.newBuilder()
          .setRegisterEntry(
            RegisterEntry.newBuilder()
              .setRegisterId(register.preamble.id)
              .setIndex(Index.newBuilder().setIndex(0))
              .setData(
                p4.v1.P4DataOuterClass.P4Data.newBuilder()
                  .setBitstring(ByteString.copyFrom(byteArrayOf(0, 0, 0, 0x42)))
              )
          )
          .build(),
    )
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Installs [installed] (via [Update.Type.INSERT] or [MODIFY]), reads back all entries of the same
   * kind, and asserts the result equals `[installed]` (after [normalize] is applied to each
   * read-back entity to remove ambient state the system fills in but the install never sets).
   *
   * MODIFY is the right verb for entities that exist implicitly the moment a parent does — every
   * counter/meter index, every direct counter/meter on a table entry. INSERT is for entities the
   * controller creates from scratch — table entries, action profile members/groups, PRE entries.
   */
  private fun assertRoundTrips(
    installed: Entity,
    type: Update.Type = Update.Type.INSERT,
    normalize: (Entity) -> Entity = { it },
  ) {
    when (type) {
      Update.Type.INSERT -> harness.installEntry(installed)
      Update.Type.MODIFY -> harness.modifyEntry(installed)
      else -> error("assertRoundTrips supports INSERT and MODIFY only; got $type")
    }
    val read = harness.readEntries(readAllOf(installed)).map(normalize)
    assertEquals(listOf(installed), read)
  }

  /** Builds an action-profile-member entity carrying a translated `port` action param. */
  private fun actionProfileMember(memberId: Int, port: String): Entity {
    val ap = config.p4Info.actionProfilesList.first { it.preamble.alias == "ap" }
    val forward = findAction(config, "forward")
    return Entity.newBuilder()
      .setActionProfileMember(
        ActionProfileMember.newBuilder()
          .setActionProfileId(ap.preamble.id)
          .setMemberId(memberId)
          .setAction(
            Action.newBuilder()
              .setActionId(forward.preamble.id)
              .addParams(
                Action.Param.newBuilder()
                  .setParamId(paramId(forward, "port"))
                  .setValue(ByteString.copyFromUtf8(port))
              )
          )
      )
      .build()
  }

  private fun translatedTableEntry(matchPort: String, actionPort: String): Entity {
    val table = findTable(config, "translated_table")
    val forward = findAction(config, "forward")
    return Entity.newBuilder()
      .setTableEntry(
        TableEntry.newBuilder()
          .setTableId(table.preamble.id)
          .addMatch(
            FieldMatch.newBuilder()
              .setFieldId(matchFieldId(table, "smeta.ingress_port"))
              .setExact(FieldMatch.Exact.newBuilder().setValue(ByteString.copyFromUtf8(matchPort)))
          )
          .setAction(
            TableAction.newBuilder()
              .setAction(
                Action.newBuilder()
                  .setActionId(forward.preamble.id)
                  .addParams(
                    Action.Param.newBuilder()
                      .setParamId(paramId(forward, "port"))
                      .setValue(ByteString.copyFromUtf8(actionPort))
                  )
              )
          )
      )
      .build()
  }

  private fun replica(port: String, instance: Int): Replica =
    Replica.newBuilder().setPort(ByteString.copyFromUtf8(port)).setInstance(instance).build()

  /**
   * For tables with attached direct counters or direct meters, reads come back with empty
   * counter_data / meter_config that the install request never set. Strip those before comparison
   * so the assertion reflects read-write symmetry, not simulator backfill.
   */
  private fun stripAmbientTableEntryFields(entity: Entity): Entity =
    entity
      .toBuilder()
      .setTableEntry(entity.tableEntry.toBuilder().clearCounterData().clearMeterConfig())
      .build()

  /** Read filter that matches all entries of the same entity case as [example]. */
  private fun readAllOf(example: Entity): ReadRequest {
    val filter =
      when (example.entityCase) {
        Entity.EntityCase.TABLE_ENTRY ->
          Entity.newBuilder()
            .setTableEntry(TableEntry.newBuilder().setTableId(example.tableEntry.tableId))
            .build()
        Entity.EntityCase.ACTION_PROFILE_MEMBER ->
          Entity.newBuilder()
            .setActionProfileMember(
              ActionProfileMember.newBuilder()
                .setActionProfileId(example.actionProfileMember.actionProfileId)
            )
            .build()
        Entity.EntityCase.ACTION_PROFILE_GROUP ->
          Entity.newBuilder()
            .setActionProfileGroup(
              ActionProfileGroup.newBuilder()
                .setActionProfileId(example.actionProfileGroup.actionProfileId)
            )
            .build()
        Entity.EntityCase.DIRECT_COUNTER_ENTRY ->
          // Wildcard read by table_id only; the simulator returns counter entries for every
          // installed table entry of that table.
          Entity.newBuilder()
            .setDirectCounterEntry(
              DirectCounterEntry.newBuilder()
                .setTableEntry(
                  TableEntry.newBuilder().setTableId(example.directCounterEntry.tableEntry.tableId)
                )
            )
            .build()
        Entity.EntityCase.DIRECT_METER_ENTRY ->
          Entity.newBuilder()
            .setDirectMeterEntry(
              DirectMeterEntry.newBuilder()
                .setTableEntry(
                  TableEntry.newBuilder().setTableId(example.directMeterEntry.tableEntry.tableId)
                )
            )
            .build()
        Entity.EntityCase.COUNTER_ENTRY ->
          Entity.newBuilder()
            .setCounterEntry(
              CounterEntry.newBuilder()
                .setCounterId(example.counterEntry.counterId)
                .setIndex(example.counterEntry.index)
            )
            .build()
        Entity.EntityCase.METER_ENTRY ->
          Entity.newBuilder()
            .setMeterEntry(
              MeterEntry.newBuilder()
                .setMeterId(example.meterEntry.meterId)
                .setIndex(example.meterEntry.index)
            )
            .build()
        Entity.EntityCase.REGISTER_ENTRY ->
          Entity.newBuilder()
            .setRegisterEntry(
              RegisterEntry.newBuilder()
                .setRegisterId(example.registerEntry.registerId)
                .setIndex(example.registerEntry.index)
            )
            .build()
        Entity.EntityCase.PACKET_REPLICATION_ENGINE_ENTRY ->
          // PRE entries don't have a P4Info-level identifier to filter on; the wildcard returns
          // every clone session and multicast group on the device. Each test installs into a
          // clean harness, so this is precise enough for round-trip assertions.
          Entity.newBuilder()
            .setPacketReplicationEngineEntry(PacketReplicationEngineEntry.getDefaultInstance())
            .build()
        else -> error("readAllOf does not yet support ${example.entityCase}")
      }
    return ReadRequest.newBuilder().setDeviceId(1).addEntities(filter).build()
  }
}
