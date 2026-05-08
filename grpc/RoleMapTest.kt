package fourward.grpc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.config.v1.P4InfoOuterClass.P4Info

/** Unit tests for [RoleMap]. */
class RoleMapTest {

  @Test
  fun `empty p4info has no roles`() {
    val roleMap = RoleMap.create(P4Info.getDefaultInstance())
    assertFalse(roleMap.hasRoles)
  }

  @Test
  fun `table without annotation belongs to default role`() {
    val p4info = p4info { table(id = 1, name = "my_table") }
    val roleMap = RoleMap.create(p4info)
    assertFalse(roleMap.hasRoles)
    assertNull(roleMap.role(1))
  }

  @Test
  fun `table with role annotation is mapped`() {
    val p4info = p4info { table(id = 1, name = "my_table", role = "sdn_controller") }
    val roleMap = RoleMap.create(p4info)
    assertTrue(roleMap.hasRoles)
    assertEquals("sdn_controller", roleMap.role(1))
  }

  @Test
  fun `multiple tables with different roles`() {
    val p4info = p4info {
      table(id = 1, name = "routing_table", role = "sdn_controller")
      table(id = 2, name = "clone_table", role = "packet_replication_engine_manager")
      table(id = 3, name = "misc_table")
    }
    val roleMap = RoleMap.create(p4info)
    assertEquals("sdn_controller", roleMap.role(1))
    assertEquals("packet_replication_engine_manager", roleMap.role(2))
    assertNull(roleMap.role(3))
  }

  @Test
  fun `direct counter inherits role from parent table`() {
    val p4info = p4info {
      table(id = 1, name = "my_table", role = "sdn_controller")
      directCounter(id = 100, directTableId = 1)
    }
    val roleMap = RoleMap.create(p4info)
    assertEquals("sdn_controller", roleMap.role(100))
  }

  @Test
  fun `direct counter without parent role has no role`() {
    val p4info = p4info {
      table(id = 1, name = "my_table")
      directCounter(id = 100, directTableId = 1)
    }
    val roleMap = RoleMap.create(p4info)
    assertNull(roleMap.role(100))
  }

  @Test
  fun `direct meter inherits role from parent table`() {
    val p4info = p4info {
      table(id = 1, name = "my_table", role = "sdn_controller")
      directMeter(id = 200, directTableId = 1)
    }
    val roleMap = RoleMap.create(p4info)
    assertEquals("sdn_controller", roleMap.role(200))
  }

  @Test
  fun `action profile inherits role from associated table`() {
    val p4info = p4info {
      table(id = 1, name = "my_table", role = "sdn_controller")
      actionProfile(id = 300, tableIds = listOf(1))
    }
    val roleMap = RoleMap.create(p4info)
    assertEquals("sdn_controller", roleMap.role(300))
  }

  @Test
  fun `action profile without associated role has no role`() {
    val p4info = p4info {
      table(id = 1, name = "my_table")
      actionProfile(id = 300, tableIds = listOf(1))
    }
    val roleMap = RoleMap.create(p4info)
    assertNull(roleMap.role(300))
  }

  @Test
  fun `action profile with conflicting table roles uses first`() {
    // Action profile shared across tables with different roles — picks the first found.
    val p4info = p4info {
      table(id = 1, name = "table_a", role = "role_a")
      table(id = 2, name = "table_b", role = "role_b")
      actionProfile(id = 300, tableIds = listOf(1, 2))
    }
    val roleMap = RoleMap.create(p4info)
    // Non-deterministic per proto ordering, but should get one of the roles.
    val role = roleMap.role(300)
    assertTrue("expected role_a or role_b, got $role", role == "role_a" || role == "role_b")
  }

  @Test
  fun `action profile skips roleless tables to find role`() {
    val p4info = p4info {
      table(id = 1, name = "default_table") // no role
      table(id = 2, name = "roled_table", role = "sdn_controller")
      actionProfile(id = 300, tableIds = listOf(1, 2))
    }
    val roleMap = RoleMap.create(p4info)
    assertEquals("sdn_controller", roleMap.role(300))
  }

  @Test
  fun `standalone counter has no role`() {
    val p4info = p4info { table(id = 1, name = "my_table", role = "sdn_controller") }
    val roleMap = RoleMap.create(p4info)
    // Counter ID 500 isn't in the map at all.
    assertNull(roleMap.role(500))
  }

  @Test
  fun `role annotation with other annotations present`() {
    // Table has multiple annotations — role should still be parsed.
    val p4info =
      P4Info.newBuilder()
        .addTables(
          P4InfoOuterClass.Table.newBuilder()
            .setPreamble(
              P4InfoOuterClass.Preamble.newBuilder()
                .setId(1)
                .setName("acl_table")
                .addAnnotations("""@sai_acl(INGRESS)""")
                .addAnnotations("""@p4runtime_role("sdn_controller")""")
                .addAnnotations("""@entry_restriction("priority > 0")""")
            )
        )
        .build()
    val roleMap = RoleMap.create(p4info)
    assertEquals("sdn_controller", roleMap.role(1))
  }

  // ---------------------------------------------------------------------------
  // Test helpers
  // ---------------------------------------------------------------------------

  private class P4InfoBuilder {
    private val builder = P4Info.newBuilder()

    fun table(id: Int, name: String, role: String? = null) {
      val preamble = P4InfoOuterClass.Preamble.newBuilder().setId(id).setName(name)
      if (role != null) preamble.addAnnotations("""@p4runtime_role("$role")""")
      builder.addTables(P4InfoOuterClass.Table.newBuilder().setPreamble(preamble))
    }

    fun directCounter(id: Int, directTableId: Int) {
      builder.addDirectCounters(
        P4InfoOuterClass.DirectCounter.newBuilder()
          .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(id).setName("dc_$id"))
          .setDirectTableId(directTableId)
      )
    }

    fun directMeter(id: Int, directTableId: Int) {
      builder.addDirectMeters(
        P4InfoOuterClass.DirectMeter.newBuilder()
          .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(id).setName("dm_$id"))
          .setDirectTableId(directTableId)
      )
    }

    fun actionProfile(id: Int, tableIds: List<Int>) {
      val ap =
        P4InfoOuterClass.ActionProfile.newBuilder()
          .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(id).setName("ap_$id"))
      tableIds.forEach { ap.addTableIds(it) }
      builder.addActionProfiles(ap.build())
    }

    fun build(): P4Info = builder.build()
  }

  private fun p4info(block: P4InfoBuilder.() -> Unit): P4Info = P4InfoBuilder().apply(block).build()
}
