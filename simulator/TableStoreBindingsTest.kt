package fourward.simulator

import fourward.ActionDecl
import fourward.BehavioralConfig
import fourward.ControlDecl
import fourward.ControlPlaneBinding
import fourward.ControlPlaneBindings
import fourward.DeviceConfig
import fourward.ExternInstanceDecl
import fourward.TableBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass

class TableStoreBindingsTest {
  @Test
  fun `loadMappings uses explicit bindings for compiled behavioral names`() {
    val nestedTableId = 3
    val nestedActionId = 30
    val p4info =
      buildP4Info(
        tables =
          listOf(
            P4InfoOuterClass.Table.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder()
                  .setId(nestedTableId)
                  .setName("ingress.inner.myTable")
                  .setAlias("inner.myTable")
              )
              .setConstDefaultActionId(nestedActionId)
              .build()
          ),
        actions =
          listOf(
            P4InfoOuterClass.Action.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder()
                  .setId(nestedActionId)
                  .setName("ingress.inner.myAction")
                  .setAlias("inner.myAction")
              )
              .build()
          ),
      )
    val device =
      DeviceConfig.newBuilder()
        .setBehavioral(
          BehavioralConfig.newBuilder()
            .addTables(
              TableBehavior.newBuilder()
                .setName("inner_myTable")
                .putActionOverrides("ingress.inner.myAction", "inner_myAction")
            )
            .addActions(ActionDecl.newBuilder().setName("inner_myAction"))
        )
        .setControlPlaneBindings(
          ControlPlaneBindings.newBuilder()
            .addTables(binding("ingress.inner.myTable", "inner_myTable"))
            .addActions(binding("ingress.inner.myAction", "ingress.inner.myAction"))
        )
        .build()
    val store = TableStore()
    store.loadMappings(p4info = p4info, device = device)

    val result = store.lookup("inner_myTable", emptyList())
    assertEquals("ingress.inner.myAction", result.actionName)
  }

  @Test
  fun `resolveActionForTable accepts source action name in table namespace`() {
    val tableId = 3
    val hitActionId = 30
    val missActionId = 31
    val p4info =
      buildP4Info(
        tables =
          listOf(
            P4InfoOuterClass.Table.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder()
                  .setId(tableId)
                  .setName("MainControlImpl.outbound.conntrack.ct_tcp_table")
                  .setAlias("outbound.conntrack.ct_tcp_table")
              )
              .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(hitActionId))
              .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(missActionId))
              .build()
          ),
        actions =
          listOf(
            P4InfoOuterClass.Action.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder()
                  .setId(hitActionId)
                  .setName("MainControlImpl.outbound.conntrack.ct_tcp_table_hit")
                  .setAlias("outbound.conntrack.ct_tcp_table_hit")
              )
              .build(),
            P4InfoOuterClass.Action.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder()
                  .setId(missActionId)
                  .setName("MainControlImpl.outbound.conntrack.ct_tcp_table_miss")
                  .setAlias("outbound.conntrack.ct_tcp_table_miss")
              )
              .build(),
          ),
      )
    val device =
      DeviceConfig.newBuilder()
        .setBehavioral(
          BehavioralConfig.newBuilder()
            .addTables(
              TableBehavior.newBuilder()
                .setName("outbound_conntrack_ct_tcp_table")
                .putActionOverrides(
                  "MainControlImpl.outbound.conntrack.ct_tcp_table_hit",
                  "outbound_conntrack_ct_tcp_table_hit_0",
                )
                .putActionOverrides(
                  "MainControlImpl.outbound.conntrack.ct_tcp_table_miss",
                  "outbound_conntrack_ct_tcp_table_miss_0",
                )
            )
            .addActions(ActionDecl.newBuilder().setName("outbound_conntrack_ct_tcp_table_hit_0"))
            .addActions(ActionDecl.newBuilder().setName("outbound_conntrack_ct_tcp_table_miss_0"))
        )
        .setControlPlaneBindings(
          ControlPlaneBindings.newBuilder()
            .addTables(
              binding(
                "MainControlImpl.outbound.conntrack.ct_tcp_table",
                "outbound_conntrack_ct_tcp_table",
              )
            )
            .addActions(
              binding(
                "MainControlImpl.outbound.conntrack.ct_tcp_table_hit",
                "MainControlImpl.outbound.conntrack.ct_tcp_table_hit",
              )
            )
            .addActions(
              binding(
                "MainControlImpl.outbound.conntrack.ct_tcp_table_miss",
                "MainControlImpl.outbound.conntrack.ct_tcp_table_miss",
              )
            )
        )
        .build()
    val store = TableStore()
    store.loadMappings(p4info = p4info, device = device)

    assertEquals(
      "MainControlImpl.outbound.conntrack.ct_tcp_table_hit",
      store.resolveActionForTable("outbound_conntrack_ct_tcp_table", "ct_tcp_table_hit"),
    )
  }

  @Test
  fun `loadMappings rejects compiled behavioral config without table binding`() {
    val p4info =
      buildP4Info(
        tables =
          listOf(
            P4InfoOuterClass.Table.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder()
                  .setId(4)
                  .setName("ingress.inner.myTable")
                  .setAlias("inner.myTable")
              )
              .build()
          )
      )
    val device =
      DeviceConfig.newBuilder()
        .setBehavioral(
          BehavioralConfig.newBuilder()
            .addTables(TableBehavior.newBuilder().setName("inner_myTable"))
        )
        .build()
    val store = TableStore()

    val thrown =
      try {
        store.loadMappings(p4info = p4info, device = device)
        null
      } catch (e: IllegalStateException) {
        e
      }

    assertNotNull(thrown)
    assertEquals(
      "compiled behavioral config is missing table binding for 'ingress.inner.myTable'; " +
        "recompile with the current p4c-4ward",
      thrown!!.message,
    )
  }

  @Test
  fun `loadMappings keeps duplicate action aliases distinct through explicit bindings`() {
    val routeDropId = 30
    val aclDropId = 31
    val p4info =
      buildP4Info(
        tables =
          listOf(
            P4InfoOuterClass.Table.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder()
                  .setId(3)
                  .setName("ingress.routing_lookup.ipv4_table")
                  .setAlias("ipv4_table")
              )
              .setConstDefaultActionId(routeDropId)
              .build()
          ),
        actions =
          listOf(
            P4InfoOuterClass.Action.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder()
                  .setId(routeDropId)
                  .setName("ingress.routing_lookup.drop")
                  .setAlias("drop")
              )
              .build(),
            P4InfoOuterClass.Action.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder()
                  .setId(aclDropId)
                  .setName("ingress.acl.drop")
                  .setAlias("drop")
              )
              .build(),
          ),
      )
    val device =
      DeviceConfig.newBuilder()
        .setBehavioral(
          BehavioralConfig.newBuilder()
            .addTables(
              TableBehavior.newBuilder()
                .setName("routing_lookup_ipv4_table")
                .putActionOverrides("ingress.routing_lookup.drop", "routing_lookup_drop")
            )
            .addActions(ActionDecl.newBuilder().setName("routing_lookup_drop"))
            .addActions(ActionDecl.newBuilder().setName("acl_drop"))
        )
        .setControlPlaneBindings(
          ControlPlaneBindings.newBuilder()
            .addTables(binding("ingress.routing_lookup.ipv4_table", "routing_lookup_ipv4_table"))
            .addActions(binding("ingress.routing_lookup.drop", "ingress.routing_lookup.drop"))
            .addActions(binding("ingress.acl.drop", "ingress.acl.drop"))
        )
        .build()
    val store = TableStore()
    store.loadMappings(p4info = p4info, device = device)

    val result = store.lookup("routing_lookup_ipv4_table", emptyList())
    assertEquals("ingress.routing_lookup.drop", result.actionName)
  }

  @Test
  fun `loadMappings rejects action binding to unknown simulator action`() {
    val p4info =
      buildP4Info(
        actions =
          listOf(
            P4InfoOuterClass.Action.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder()
                  .setId(30)
                  .setName("ingress.inner.myAction")
                  .setAlias("inner.myAction")
              )
              .build()
          )
      )
    val device =
      DeviceConfig.newBuilder()
        .setBehavioral(
          BehavioralConfig.newBuilder().addActions(ActionDecl.newBuilder().setName("known_action"))
        )
        .setControlPlaneBindings(
          ControlPlaneBindings.newBuilder()
            .addActions(binding("ingress.inner.myAction", "missing_action"))
        )
        .build()
    val store = TableStore()

    val thrown =
      try {
        store.loadMappings(p4info = p4info, device = device)
        null
      } catch (e: IllegalArgumentException) {
        e
      }

    assertNotNull(thrown)
    assertEquals(
      "action binding for 'ingress.inner.myAction' points to unknown simulator action " +
        "'missing_action'",
      thrown!!.message,
    )
  }

  @Test
  fun `loadMappings uses explicit bindings for compiled counter extern names`() {
    val counterId = 5
    val p4info =
      buildP4Info(
        counters =
          listOf(
            P4InfoOuterClass.Counter.newBuilder()
              .setPreamble(
                P4InfoOuterClass.Preamble.newBuilder()
                  .setId(counterId)
                  .setName("ingress.inner.pktCounter")
                  .setAlias("inner.pktCounter")
              )
              .setSize(4)
              .build()
          )
      )
    val device =
      DeviceConfig.newBuilder()
        .setBehavioral(
          BehavioralConfig.newBuilder()
            .addControls(
              ControlDecl.newBuilder()
                .addExternInstances(
                  ExternInstanceDecl.newBuilder().setTypeName("Counter").setName("inner_pktCounter")
                )
            )
        )
        .setControlPlaneBindings(
          ControlPlaneBindings.newBuilder()
            .addExterns(binding("ingress.inner.pktCounter", "inner_pktCounter"))
        )
        .build()
    val store = TableStore()
    store.loadMappings(p4info = p4info, device = device)

    store.counterIncrement("inner_pktCounter", index = 2, byteCount = 9)

    val results =
      store.readCounterEntries(
        P4RuntimeOuterClass.CounterEntry.newBuilder()
          .setCounterId(counterId)
          .setIndex(P4RuntimeOuterClass.Index.newBuilder().setIndex(2))
          .build()
      )
    assertEquals(1, results.size)
    assertEquals(1, results[0].counterEntry.data.packetCount)
    assertEquals(9, results[0].counterEntry.data.byteCount)
  }

  private fun buildP4Info(
    tables: List<P4InfoOuterClass.Table> = emptyList(),
    actions: List<P4InfoOuterClass.Action> = emptyList(),
    counters: List<P4InfoOuterClass.Counter> = emptyList(),
  ): P4InfoOuterClass.P4Info =
    P4InfoOuterClass.P4Info.newBuilder()
      .addAllTables(tables)
      .addAllActions(actions)
      .addAllCounters(counters)
      .build()

  private fun binding(p4infoName: String, simulatorName: String): ControlPlaneBinding =
    ControlPlaneBinding.newBuilder()
      .setP4InfoName(p4infoName)
      .setSimulatorName(simulatorName)
      .build()
}
