package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.CloneSessionLookupEvent
import fourward.Drop
import fourward.DropReason
import fourward.Fork
import fourward.ForkBranch
import fourward.ForkReason
import fourward.OutputPacket
import fourward.PacketIngressEvent
import fourward.PacketOutcome
import fourward.TableLookupEvent
import fourward.TraceEvent
import fourward.TraceTree
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.config.v1.P4Types
import p4.v1.P4RuntimeOuterClass

class TraceEnricherTest {

  // ===========================================================================
  // No-op when translator has no translations
  // ===========================================================================

  @Test
  fun `returns same trace when translator has no translations`() {
    val translator = TypeTranslator.create()
    val trace = simpleTrace(ingressPort = 1, egressPort = 2)
    assertSame(trace, TraceEnricher.enrich(trace, translator))
  }

  // ===========================================================================
  // Port enrichment
  // ===========================================================================

  @Test
  fun `enriches ingress port`() {
    val translator = portTranslator()
    // Install mapping: "Ethernet0" → dp=0.
    translator.p4rtToDataplane(PORT_TYPE, "Ethernet0")

    val trace = traceWithIngress(ingressPort = 0)
    val enriched = TraceEnricher.enrich(trace, translator)

    val ingress = enriched.getEvents(0).packetIngress
    assertEquals(0, ingress.dataplaneIngressPort)
    assertEquals(ByteString.copyFromUtf8("Ethernet0"), ingress.p4RtIngressPort)
  }

  @Test
  fun `enriches egress port in output`() {
    val translator = portTranslator()
    translator.p4rtToDataplane(PORT_TYPE, "Ethernet1")

    val trace = simpleTrace(ingressPort = 0, egressPort = 0)
    val enriched = TraceEnricher.enrich(trace, translator)

    val output = enriched.packetOutcome.output
    assertEquals(0, output.dataplaneEgressPort)
    assertEquals(ByteString.copyFromUtf8("Ethernet1"), output.p4RtEgressPort)
  }

  @Test
  fun `enriches clone session lookup port`() {
    val translator = portTranslator()
    translator.p4rtToDataplane(PORT_TYPE, "Ethernet2")

    val event =
      TraceEvent.newBuilder()
        .setCloneSessionLookup(
          CloneSessionLookupEvent.newBuilder()
            .setSessionId(1)
            .setSessionFound(true)
            .setDataplaneEgressPort(0)
        )
        .build()
    val trace = TraceTree.newBuilder().addEvents(event).build()
    val enriched = TraceEnricher.enrich(trace, translator)

    val csl = enriched.getEvents(0).cloneSessionLookup
    assertEquals(0, csl.dataplaneEgressPort)
    assertEquals(ByteString.copyFromUtf8("Ethernet2"), csl.p4RtEgressPort)
  }

  @Test
  fun `skips clone session lookup when session not found`() {
    val translator = portTranslator()
    translator.p4rtToDataplane(PORT_TYPE, "Ethernet0")

    val event =
      TraceEvent.newBuilder()
        .setCloneSessionLookup(
          CloneSessionLookupEvent.newBuilder().setSessionId(1).setSessionFound(false)
        )
        .build()
    val trace = TraceTree.newBuilder().addEvents(event).build()
    val enriched = TraceEnricher.enrich(trace, translator)

    assertTrue(enriched.getEvents(0).cloneSessionLookup.p4RtEgressPort.isEmpty)
  }

  @Test
  fun `leaves port unenriched when no mapping exists`() {
    val translator = portTranslator()
    // No mappings installed — dataplaneToP4rt returns null.

    val trace = simpleTrace(ingressPort = 42, egressPort = 42)
    val enriched = TraceEnricher.enrich(trace, translator)

    // Ingress event not enriched (no p4rt port set).
    assertTrue(enriched.getEvents(0).packetIngress.p4RtIngressPort.isEmpty)
  }

  // ===========================================================================
  // Table entry enrichment
  // ===========================================================================

  @Test
  fun `enriches matched entry with P4RT values`() {
    val translator = tableTranslator()
    // Install mapping: P4RT bytes(5000) → dp bytes(0).
    translator.p4rtToDataplane(PORT_TYPE, p4rtBytes(5000))

    val matchedEntry =
      P4RuntimeOuterClass.TableEntry.newBuilder()
        .setTableId(TABLE_ID)
        .addMatch(
          P4RuntimeOuterClass.FieldMatch.newBuilder()
            .setFieldId(MATCH_FIELD_ID)
            .setExact(
              P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                .setValue(ByteString.copyFrom(dpBytes(0)))
            )
        )
        .setAction(
          P4RuntimeOuterClass.TableAction.newBuilder()
            .setAction(
              P4RuntimeOuterClass.Action.newBuilder()
                .setActionId(ACTION_ID)
                .addParams(
                  P4RuntimeOuterClass.Action.Param.newBuilder()
                    .setParamId(PARAM_ID)
                    .setValue(ByteString.copyFrom(dpBytes(0)))
                )
            )
        )
        .build()

    val event =
      TraceEvent.newBuilder()
        .setTableLookup(
          TableLookupEvent.newBuilder()
            .setTableName("my_table")
            .setHit(true)
            .setMatchedEntry(matchedEntry)
            .setActionName("forward")
        )
        .build()
    val trace = TraceTree.newBuilder().addEvents(event).build()
    val enriched = TraceEnricher.enrich(trace, translator)

    val tl = enriched.getEvents(0).tableLookup
    assertTrue(tl.hasP4RtMatchedEntry())

    val p4rtMatch = tl.p4RtMatchedEntry.matchList.first()
    assertEquals(ByteString.copyFrom(p4rtBytes(5000)), p4rtMatch.exact.value)

    val p4rtParam = tl.p4RtMatchedEntry.action.action.paramsList.first()
    assertEquals(ByteString.copyFrom(p4rtBytes(5000)), p4rtParam.value)
  }

  @Test
  fun `skips table enrichment on miss`() {
    val translator = tableTranslator()

    val event =
      TraceEvent.newBuilder()
        .setTableLookup(
          TableLookupEvent.newBuilder()
            .setTableName("my_table")
            .setHit(false)
            .setActionName("NoAction")
        )
        .build()
    val trace = TraceTree.newBuilder().addEvents(event).build()
    val enriched = TraceEnricher.enrich(trace, translator)

    val tl = enriched.getEvents(0).tableLookup
    assertTrue(!tl.hasP4RtMatchedEntry())
  }

  // ===========================================================================
  // Fork propagation
  // ===========================================================================

  @Test
  fun `enrichment propagates through fork branches`() {
    val translator = portTranslator()
    translator.p4rtToDataplane(PORT_TYPE, "Ethernet0")
    translator.p4rtToDataplane(PORT_TYPE, "Ethernet1")

    val branch1 =
      TraceTree.newBuilder()
        .setPacketOutcome(
          PacketOutcome.newBuilder()
            .setOutput(OutputPacket.newBuilder().setDataplaneEgressPort(0).setPayload(EMPTY))
        )
        .build()
    val branch2 =
      TraceTree.newBuilder()
        .setPacketOutcome(
          PacketOutcome.newBuilder()
            .setOutput(OutputPacket.newBuilder().setDataplaneEgressPort(1).setPayload(EMPTY))
        )
        .build()

    val trace =
      TraceTree.newBuilder()
        .setForkOutcome(
          Fork.newBuilder()
            .setReason(ForkReason.MULTICAST)
            .addBranches(ForkBranch.newBuilder().setLabel("replica_0").setSubtree(branch1))
            .addBranches(ForkBranch.newBuilder().setLabel("replica_1").setSubtree(branch2))
        )
        .build()

    val enriched = TraceEnricher.enrich(trace, translator)

    val out0 = enriched.forkOutcome.getBranches(0).subtree.packetOutcome.output
    assertEquals(ByteString.copyFromUtf8("Ethernet0"), out0.p4RtEgressPort)

    val out1 = enriched.forkOutcome.getBranches(1).subtree.packetOutcome.output
    assertEquals(ByteString.copyFromUtf8("Ethernet1"), out1.p4RtEgressPort)
  }

  @Test
  fun `does not enrich drop outcomes`() {
    val translator = portTranslator()
    translator.p4rtToDataplane(PORT_TYPE, "Ethernet0")

    val trace =
      TraceTree.newBuilder()
        .setPacketOutcome(
          PacketOutcome.newBuilder().setDrop(Drop.newBuilder().setReason(DropReason.MARK_TO_DROP))
        )
        .build()

    assertSame(trace, TraceEnricher.enrich(trace, translator))
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  private fun simpleTrace(ingressPort: Int, egressPort: Int): TraceTree =
    TraceTree.newBuilder()
      .addEvents(
        TraceEvent.newBuilder()
          .setPacketIngress(PacketIngressEvent.newBuilder().setDataplaneIngressPort(ingressPort))
      )
      .setPacketOutcome(
        PacketOutcome.newBuilder()
          .setOutput(OutputPacket.newBuilder().setDataplaneEgressPort(egressPort).setPayload(EMPTY))
      )
      .build()

  private fun traceWithIngress(ingressPort: Int): TraceTree =
    TraceTree.newBuilder()
      .addEvents(
        TraceEvent.newBuilder()
          .setPacketIngress(PacketIngressEvent.newBuilder().setDataplaneIngressPort(ingressPort))
      )
      .build()

  /** Creates a TypeTranslator with string port translation (simulating SAI P4). */
  private fun portTranslator(): TypeTranslator {
    val typeInfo =
      P4Types.P4TypeInfo.newBuilder()
        .putNewTypes(
          PORT_TYPE,
          P4Types.P4NewTypeSpec.newBuilder()
            .setTranslatedType(
              P4Types.P4NewTypeTranslation.newBuilder()
                .setSdnString(P4Types.P4NewTypeTranslation.SdnString.getDefaultInstance())
            )
            .build(),
        )
        .build()
    val p4info = P4InfoOuterClass.P4Info.newBuilder().setTypeInfo(typeInfo).build()
    return TypeTranslator.create(p4info, portTypeName = PORT_TYPE)
  }

  /** Creates a TypeTranslator with a translated match field and action param. */
  private fun tableTranslator(): TypeTranslator {
    val typeInfo =
      P4Types.P4TypeInfo.newBuilder()
        .putNewTypes(
          PORT_TYPE,
          P4Types.P4NewTypeSpec.newBuilder()
            .setTranslatedType(P4Types.P4NewTypeTranslation.newBuilder().setSdnBitwidth(32))
            .build(),
        )
        .build()
    val p4info =
      P4InfoOuterClass.P4Info.newBuilder()
        .setTypeInfo(typeInfo)
        .addTables(
          P4InfoOuterClass.Table.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(TABLE_ID))
            .addMatchFields(
              P4InfoOuterClass.MatchField.newBuilder()
                .setId(MATCH_FIELD_ID)
                .setBitwidth(32)
                .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(PORT_TYPE))
            )
        )
        .addActions(
          P4InfoOuterClass.Action.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(ACTION_ID))
            .addParams(
              P4InfoOuterClass.Action.Param.newBuilder()
                .setId(PARAM_ID)
                .setBitwidth(32)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(PORT_TYPE))
            )
        )
        .build()
    return TypeTranslator.create(p4info)
  }

  companion object {
    private const val PORT_TYPE = "port_id_t"
    private const val TABLE_ID = 100
    private const val MATCH_FIELD_ID = 1
    private const val ACTION_ID = 200
    private const val PARAM_ID = 1
    private val EMPTY: ByteString = ByteString.EMPTY

    private fun p4rtBytes(value: Int): ByteArray = minWidthBytes(value)

    private fun dpBytes(value: Int): ByteArray = minWidthBytes(value)

    private fun minWidthBytes(value: Int): ByteArray {
      if (value == 0) return byteArrayOf(0)
      val bytes = mutableListOf<Byte>()
      var v = value
      while (v > 0) {
        bytes.add(0, (v and 0xFF).toByte())
        v = v shr 8
      }
      return bytes.toByteArray()
    }
  }
}
