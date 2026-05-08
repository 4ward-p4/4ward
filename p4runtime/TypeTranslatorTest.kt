package fourward.p4runtime

import com.google.protobuf.ByteString
import fourward.TranslationEntry
import fourward.TypeTranslation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.config.v1.P4Types
import p4.v1.P4RuntimeOuterClass

/**
 * Unit tests for [TypeTranslator]'s core mapping logic.
 *
 * These test the bidirectional P4RT ↔ data-plane value mapping directly, without going through the
 * P4Runtime gRPC service or simulator. Three modes are covered:
 * 1. **Explicit** — all mappings provided upfront; unknown values rejected.
 * 2. **Auto-allocate** — mappings created on first use; data-plane values assigned sequentially.
 * 3. **Hybrid** — explicit pins for known values, auto-allocate for the rest.
 *
 * Both `sdn_bitwidth` (numeric ↔ numeric) and `sdn_string` (string ↔ numeric) are tested.
 */
class TypeTranslatorTest {

  // ===========================================================================
  // Explicit mapping: sdn_bitwidth (numeric → numeric)
  // ===========================================================================

  @Test
  fun `explicit numeric mapping translates P4RT to dataplane`() {
    val translator =
      buildTranslator(
        translation {
          typeName = "port_id_t"
          addEntries(entry(p4rtBytes(1000), dpBytes(1)))
          addEntries(entry(p4rtBytes(2000), dpBytes(2)))
        }
      )

    assertArrayEquals(dpBytes(1), translator.p4rtToDataplane("port_id_t", p4rtBytes(1000)))
    assertArrayEquals(dpBytes(2), translator.p4rtToDataplane("port_id_t", p4rtBytes(2000)))
  }

  @Test
  fun `explicit numeric mapping translates dataplane to P4RT`() {
    val translator =
      buildTranslator(
        translation {
          typeName = "port_id_t"
          addEntries(entry(p4rtBytes(1000), dpBytes(1)))
          addEntries(entry(p4rtBytes(2000), dpBytes(2)))
        }
      )

    val result = translator.dataplaneToP4rt("port_id_t", dpBytes(1))
    assertEquals(P4rtValue.Bitstring(bs(p4rtBytes(1000))), result)
  }

  @Test
  fun `explicit mapping rejects unknown P4RT value`() {
    val translator =
      buildTranslator(
        translation {
          typeName = "port_id_t"
          autoAllocate = false
          addEntries(entry(p4rtBytes(1000), dpBytes(1)))
        }
      )

    assertThrows(TranslationException::class.java) {
      translator.p4rtToDataplane("port_id_t", p4rtBytes(9999))
    }
  }

  @Test
  fun `explicit mapping rejects unknown dataplane value`() {
    val translator =
      buildTranslator(
        translation {
          typeName = "port_id_t"
          autoAllocate = false
          addEntries(entry(p4rtBytes(1000), dpBytes(1)))
        }
      )

    assertThrows(TranslationException::class.java) {
      translator.dataplaneToP4rt("port_id_t", dpBytes(99))
    }
  }

  // ===========================================================================
  // Explicit mapping: sdn_string (string → numeric)
  // ===========================================================================

  @Test
  fun `explicit string mapping translates P4RT to dataplane`() {
    val translator =
      buildTranslator(
        translation {
          typeName = "port_name_t"
          addEntries(stringEntry("Ethernet0", dpBytes(0)))
          addEntries(stringEntry("Ethernet1", dpBytes(1)))
          addEntries(stringEntry("CpuPort", dpBytes(510)))
        }
      )

    assertArrayEquals(dpBytes(0), translator.p4rtToDataplane("port_name_t", "Ethernet0"))
    assertArrayEquals(dpBytes(510), translator.p4rtToDataplane("port_name_t", "CpuPort"))
  }

  @Test
  fun `explicit string mapping translates dataplane to P4RT`() {
    val translator =
      buildTranslator(
        translation {
          typeName = "port_name_t"
          addEntries(stringEntry("Ethernet0", dpBytes(0)))
        }
      )

    val result = translator.dataplaneToP4rt("port_name_t", dpBytes(0))
    assertEquals(P4rtValue.Str("Ethernet0"), result)
  }

  // ===========================================================================
  // Auto-allocate: sdn_bitwidth
  // ===========================================================================

  @Test
  fun `auto-allocate assigns sequential dataplane values for numeric P4RT`() {
    val translator =
      buildTranslator(
        translation {
          typeName = "port_id_t"
          autoAllocate = true
        }
      )

    // First value seen gets dataplane value 0, second gets 1, etc.
    val dp0 = translator.p4rtToDataplane("port_id_t", p4rtBytes(5000))
    val dp1 = translator.p4rtToDataplane("port_id_t", p4rtBytes(6000))

    assertArrayEquals(dpBytes(0), dp0)
    assertArrayEquals(dpBytes(1), dp1)

    // Same P4RT value returns same dataplane value (idempotent).
    assertArrayEquals(dpBytes(0), translator.p4rtToDataplane("port_id_t", p4rtBytes(5000)))
  }

  @Test
  fun `auto-allocate reverse lookup works after allocation`() {
    val translator =
      buildTranslator(
        translation {
          typeName = "port_id_t"
          autoAllocate = true
        }
      )

    translator.p4rtToDataplane("port_id_t", p4rtBytes(5000))

    val result = translator.dataplaneToP4rt("port_id_t", dpBytes(0))
    assertEquals(P4rtValue.Bitstring(bs(p4rtBytes(5000))), result)
  }

  // ===========================================================================
  // Auto-allocate: sdn_string
  // ===========================================================================

  @Test
  fun `auto-allocate assigns sequential dataplane values for string P4RT`() {
    val translator =
      buildTranslator(
        translation {
          typeName = "port_name_t"
          autoAllocate = true
        }
      )

    val dp0 = translator.p4rtToDataplane("port_name_t", "Ethernet0")
    val dp1 = translator.p4rtToDataplane("port_name_t", "Ethernet1")

    assertArrayEquals(dpBytes(0), dp0)
    assertArrayEquals(dpBytes(1), dp1)

    // Idempotent.
    assertArrayEquals(dpBytes(0), translator.p4rtToDataplane("port_name_t", "Ethernet0"))
  }

  @Test
  fun `auto-allocate string reverse lookup works`() {
    val translator =
      buildTranslator(
        translation {
          typeName = "port_name_t"
          autoAllocate = true
        }
      )

    translator.p4rtToDataplane("port_name_t", "Ethernet0")

    val result = translator.dataplaneToP4rt("port_name_t", dpBytes(0))
    assertEquals(P4rtValue.Str("Ethernet0"), result)
  }

  // ===========================================================================
  // Default behavior: no TypeTranslation provided for a type
  // ===========================================================================

  @Test
  fun `unknown type defaults to auto-allocate`() {
    // No TypeTranslation provided — the translator should still handle the URI
    // by auto-allocating.
    val translator = buildTranslator()

    val dp0 = translator.p4rtToDataplane("unknown_type_t", p4rtBytes(42))
    assertArrayEquals(dpBytes(0), dp0)
  }

  // ===========================================================================
  // Hybrid: explicit pins + auto-allocate
  // ===========================================================================

  @Test
  fun `hybrid uses explicit mappings and auto-allocates the rest`() {
    val translator =
      buildTranslator(
        translation {
          typeName = "port_name_t"
          autoAllocate = true
          addEntries(stringEntry("CpuPort", dpBytes(510)))
          addEntries(stringEntry("DropPort", dpBytes(511)))
        }
      )

    // Explicit entries work.
    assertArrayEquals(dpBytes(510), translator.p4rtToDataplane("port_name_t", "CpuPort"))
    assertArrayEquals(dpBytes(511), translator.p4rtToDataplane("port_name_t", "DropPort"))

    // Auto-allocated entries skip reserved dataplane values.
    val dp0 = translator.p4rtToDataplane("port_name_t", "Ethernet0")
    val dp1 = translator.p4rtToDataplane("port_name_t", "Ethernet1")

    assertArrayEquals(dpBytes(0), dp0)
    assertArrayEquals(dpBytes(1), dp1)
  }

  @Test
  fun `hybrid auto-allocate skips explicitly reserved dataplane values`() {
    val translator =
      buildTranslator(
        translation {
          typeName = "test_type_t"
          autoAllocate = true
          // Pin dataplane value 0 to P4RT value 100.
          addEntries(entry(p4rtBytes(100), dpBytes(0)))
        }
      )

    // Auto-allocate should skip 0 (reserved) and start at 1.
    val dp = translator.p4rtToDataplane("test_type_t", p4rtBytes(200))
    assertArrayEquals(dpBytes(1), dp)
  }

  @Test
  fun `auto-allocate skips reserved value zero regardless of encoding width`() {
    // Pin dp value 0 using a 2-byte encoding (\x00\x00). The auto-allocator uses
    // encodeMinWidth(0) which produces 1-byte \x00. Before the fix (reservedValues as
    // Set<ByteString>), ByteString comparison missed this collision.
    val translator =
      buildTranslator(
        translation {
          typeName = "test_type_t"
          autoAllocate = true
          addEntries(entry(p4rtBytes(100), byteArrayOf(0, 0)))
        }
      )

    // Auto-allocate must skip 0 (reserved as integer, regardless of byte width).
    val dp = translator.p4rtToDataplane("test_type_t", p4rtBytes(200))
    assertArrayEquals(dpBytes(1), dp)
  }

  // ===========================================================================
  // Multiple types are independent
  // ===========================================================================

  @Test
  fun `different types have independent mapping tables`() {
    val translator =
      buildTranslator(
        translation {
          typeName = "type_a_t"
          autoAllocate = true
        },
        translation {
          typeName = "type_b_t"
          autoAllocate = true
        },
      )

    val dpA = translator.p4rtToDataplane("type_a_t", p4rtBytes(42))
    val dpB = translator.p4rtToDataplane("type_b_t", p4rtBytes(42))

    // Both get dataplane value 0 — they're independent.
    assertArrayEquals(dpBytes(0), dpA)
    assertArrayEquals(dpBytes(0), dpB)
  }

  // ===========================================================================
  // SAI P4: types sharing empty URI get independent tables
  // ===========================================================================

  @Test
  fun `types sharing empty URI are independent when keyed by type name`() {
    // SAI P4 uses @p4runtime_translation("", string) for all translated types.
    // They must get independent translation tables despite sharing the same URI.
    val typeInfo =
      P4Types.P4TypeInfo.newBuilder()
        .putNewTypes(
          "port_id_t",
          P4Types.P4NewTypeSpec.newBuilder()
            .setTranslatedType(
              P4Types.P4NewTypeTranslation.newBuilder()
                .setUri("")
                .setSdnString(P4Types.P4NewTypeTranslation.SdnString.getDefaultInstance())
            )
            .build(),
        )
        .putNewTypes(
          "vrf_id_t",
          P4Types.P4NewTypeSpec.newBuilder()
            .setTranslatedType(
              P4Types.P4NewTypeTranslation.newBuilder()
                .setUri("")
                .setSdnString(P4Types.P4NewTypeTranslation.SdnString.getDefaultInstance())
            )
            .build(),
        )
        .build()
    val p4info =
      P4InfoOuterClass.P4Info.newBuilder()
        .addActions(
          P4InfoOuterClass.Action.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(ACTION_ID))
            .addParams(
              P4InfoOuterClass.Action.Param.newBuilder()
                .setId(1)
                .setBitwidth(9)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName("port_id_t"))
            )
            .addParams(
              P4InfoOuterClass.Action.Param.newBuilder()
                .setId(2)
                .setBitwidth(10)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName("vrf_id_t"))
            )
        )
        .setTypeInfo(typeInfo)
        .build()
    val translator = TypeTranslator.create(p4info)

    // Both types auto-allocate independently — "Ethernet0" and "default" both get dp value 0.
    val portDp = translator.p4rtToDataplane("port_id_t", "Ethernet0")
    val vrfDp = translator.p4rtToDataplane("vrf_id_t", "default")
    assertArrayEquals("port should get dp value 0", dpBytes(0), portDp)
    assertArrayEquals("vrf should also get dp value 0 (independent table)", dpBytes(0), vrfDp)

    // Reverse lookups return the correct type's value.
    assertEquals(P4rtValue.Str("Ethernet0"), translator.dataplaneToP4rt("port_id_t", dpBytes(0)))
    assertEquals(P4rtValue.Str("default"), translator.dataplaneToP4rt("vrf_id_t", dpBytes(0)))
  }

  // ===========================================================================
  // TypeTranslation with type_uri: resolution and ambiguity
  // ===========================================================================

  @Test
  fun `type_uri resolves to type name via p4info`() {
    val p4info =
      P4InfoOuterClass.P4Info.newBuilder()
        .addActions(
          P4InfoOuterClass.Action.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(ACTION_ID))
            .addParams(
              P4InfoOuterClass.Action.Param.newBuilder()
                .setId(PARAM_ID)
                .setBitwidth(32)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(TYPE_NAME))
            )
        )
        .setTypeInfo(bitstringTypeInfo())
        .build()

    // Provide a TypeTranslation using type_uri instead of type_name.
    val translations =
      listOf(TypeTranslation.newBuilder().setTypeUri(TYPE_URI).setAutoAllocate(true).build())
    val translator = TypeTranslator.create(p4info, translations)

    // Translation should work — type_uri resolved to TYPE_NAME.
    val dp = translator.p4rtToDataplane(TYPE_NAME, p4rtBytes(5000))
    assertArrayEquals(dpBytes(0), dp)
  }

  @Test
  fun `ambiguous type_uri is rejected`() {
    // Two types share the same URI — type_uri resolution should fail.
    val typeInfo =
      P4Types.P4TypeInfo.newBuilder()
        .putNewTypes(
          "type_a",
          P4Types.P4NewTypeSpec.newBuilder()
            .setTranslatedType(
              P4Types.P4NewTypeTranslation.newBuilder().setUri("shared.uri").setSdnBitwidth(32)
            )
            .build(),
        )
        .putNewTypes(
          "type_b",
          P4Types.P4NewTypeSpec.newBuilder()
            .setTranslatedType(
              P4Types.P4NewTypeTranslation.newBuilder().setUri("shared.uri").setSdnBitwidth(32)
            )
            .build(),
        )
        .build()
    val p4info = P4InfoOuterClass.P4Info.newBuilder().setTypeInfo(typeInfo).build()

    val translations =
      listOf(TypeTranslation.newBuilder().setTypeUri("shared.uri").setAutoAllocate(true).build())

    assertThrows(IllegalArgumentException::class.java) {
      TypeTranslator.create(p4info, translations)
    }
  }

  @Test
  fun `type_uri without p4info is rejected`() {
    val translations =
      listOf(TypeTranslation.newBuilder().setTypeUri("some.uri").setAutoAllocate(true).build())

    assertThrows(IllegalArgumentException::class.java) { TypeTranslator.create(translations) }
  }

  @Test
  fun `type_uri matching zero types is rejected`() {
    val p4info = P4InfoOuterClass.P4Info.newBuilder().setTypeInfo(bitstringTypeInfo()).build()

    val translations =
      listOf(
        TypeTranslation.newBuilder().setTypeUri("nonexistent.uri").setAutoAllocate(true).build()
      )

    assertThrows(IllegalArgumentException::class.java) {
      TypeTranslator.create(p4info, translations)
    }
  }

  // ===========================================================================
  // ActionProfileMember translation via P4Info
  // ===========================================================================

  @Test
  fun `action profile member params are translated on write`() {
    val translator = buildP4InfoTranslator()

    val update = memberUpdate(ACTION_ID, PARAM_ID, p4rtBytes(5000))
    val translated = translator.translateForWrite(update)
    val param = translated.entity.actionProfileMember.action.paramsList.first()
    assertEquals(ByteString.copyFrom(dpBytes(0)), param.value)
  }

  @Test
  fun `action profile member params round-trip through read`() {
    val translator = buildP4InfoTranslator()

    // Forward translate to install the mapping.
    translator.translateForWrite(memberUpdate(ACTION_ID, PARAM_ID, p4rtBytes(5000)))

    // Reverse: simulate reading back a data-plane entity.
    val dpMember =
      P4RuntimeOuterClass.ActionProfileMember.newBuilder()
        .setActionProfileId(ACTION_PROFILE_ID)
        .setMemberId(1)
        .setAction(
          P4RuntimeOuterClass.Action.newBuilder()
            .setActionId(ACTION_ID)
            .addParams(
              P4RuntimeOuterClass.Action.Param.newBuilder()
                .setParamId(PARAM_ID)
                .setValue(ByteString.copyFrom(dpBytes(0)))
            )
        )
        .build()
    val dpEntity = P4RuntimeOuterClass.Entity.newBuilder().setActionProfileMember(dpMember).build()
    val sdnEntity = translator.translateForRead(dpEntity)
    val param = sdnEntity.actionProfileMember.action.paramsList.first()
    assertEquals(ByteString.copyFrom(p4rtBytes(5000)), param.value)
  }

  // ===========================================================================
  // Match field translation via P4Info
  // ===========================================================================

  @Test
  fun `match field with translated type is discovered from p4info`() {
    val translator = buildP4InfoTranslator()

    // Forward: P4RT exact match value → data-plane value.
    val update =
      writeUpdate(TABLE_ID, MATCH_FIELD_ID, p4rtBytes(5000), ACTION_ID, PARAM_ID, p4rtBytes(1))
    val translated = translator.translateForWrite(update)
    val match = translated.entity.tableEntry.matchList.first()
    assertEquals(ByteString.copyFrom(dpBytes(0)), match.exact.value)
  }

  @Test
  fun `match field translation round-trips through read`() {
    val translator = buildP4InfoTranslator()

    // Forward translate to install the mapping.
    translator.translateForWrite(
      writeUpdate(TABLE_ID, MATCH_FIELD_ID, p4rtBytes(5000), ACTION_ID, PARAM_ID, p4rtBytes(1))
    )

    // Reverse: simulate reading back a data-plane entity.
    val dpEntity = readEntity(TABLE_ID, MATCH_FIELD_ID, dpBytes(0), ACTION_ID, PARAM_ID, dpBytes(0))
    val sdnEntity = translator.translateForRead(dpEntity)
    val match = sdnEntity.tableEntry.matchList.first()
    assertEquals(ByteString.copyFrom(p4rtBytes(5000)), match.exact.value)
  }

  @Test
  fun `optional match field with translated type is translated`() {
    val translator = buildP4InfoTranslator()

    val update =
      writeUpdateOptionalMatch(
        TABLE_ID,
        MATCH_FIELD_ID,
        p4rtBytes(5000),
        ACTION_ID,
        PARAM_ID,
        p4rtBytes(1),
      )
    val translated = translator.translateForWrite(update)
    val match = translated.entity.tableEntry.matchList.first()
    assertEquals(ByteString.copyFrom(dpBytes(0)), match.optional.value)
  }

  @Test
  fun `non-translated match field passes through unchanged`() {
    val translator = buildP4InfoTranslator()

    // Use a match field ID that has no type_name in p4info.
    val rawValue = p4rtBytes(42)
    val update =
      writeUpdate(TABLE_ID, NON_TRANSLATED_FIELD_ID, rawValue, ACTION_ID, PARAM_ID, p4rtBytes(1))
    val translated = translator.translateForWrite(update)
    val match = translated.entity.tableEntry.matchList.first()
    // Match value should be unchanged.
    assertEquals(ByteString.copyFrom(rawValue), match.exact.value)
  }

  // ===========================================================================
  // Match field and action param translation: sdn_string via P4Info
  // ===========================================================================

  @Test
  fun `sdn_string match field write decodes UTF-8 and allocates`() {
    val translator = buildP4InfoTranslatorWithStringType()

    val p4rtBytes = "Ethernet0".toByteArray(Charsets.UTF_8)
    val update = writeUpdate(TABLE_ID, MATCH_FIELD_ID, p4rtBytes, ACTION_ID, PARAM_ID, p4rtBytes)
    val translated = translator.translateForWrite(update)
    val match = translated.entity.tableEntry.matchList.first()
    assertEquals(ByteString.copyFrom(dpBytes(0)), match.exact.value)
  }

  @Test
  fun `sdn_string match field round-trips through read`() {
    val translator = buildP4InfoTranslatorWithStringType()

    // Write to install the mapping.
    val p4rtBytes = "Ethernet0".toByteArray(Charsets.UTF_8)
    translator.translateForWrite(
      writeUpdate(TABLE_ID, MATCH_FIELD_ID, p4rtBytes, ACTION_ID, PARAM_ID, p4rtBytes)
    )

    // Read back.
    val dpEntity = readEntity(TABLE_ID, MATCH_FIELD_ID, dpBytes(0), ACTION_ID, PARAM_ID, dpBytes(0))
    val sdnEntity = translator.translateForRead(dpEntity)
    val match = sdnEntity.tableEntry.matchList.first()
    assertEquals(ByteString.copyFromUtf8("Ethernet0"), match.exact.value)
  }

  @Test
  fun `sdn_string action param write decodes UTF-8`() {
    val translator = buildP4InfoTranslatorWithStringType()

    val matchBytes = "Ethernet0".toByteArray(Charsets.UTF_8)
    val paramBytes = "Ethernet1".toByteArray(Charsets.UTF_8)
    val update = writeUpdate(TABLE_ID, MATCH_FIELD_ID, matchBytes, ACTION_ID, PARAM_ID, paramBytes)
    val translated = translator.translateForWrite(update)
    val param = translated.entity.tableEntry.action.action.paramsList.first()
    // Match allocated "Ethernet0" → dp=0; param allocates "Ethernet1" → dp=1.
    assertEquals(ByteString.copyFrom(dpBytes(1)), param.value)
  }

  @Test
  fun `sdn_string action param round-trips through read`() {
    val translator = buildP4InfoTranslatorWithStringType()

    val matchBytes = "Ethernet0".toByteArray(Charsets.UTF_8)
    val paramBytes = "Ethernet1".toByteArray(Charsets.UTF_8)
    translator.translateForWrite(
      writeUpdate(TABLE_ID, MATCH_FIELD_ID, matchBytes, ACTION_ID, PARAM_ID, paramBytes)
    )

    val dpEntity = readEntity(TABLE_ID, MATCH_FIELD_ID, dpBytes(0), ACTION_ID, PARAM_ID, dpBytes(1))
    val sdnEntity = translator.translateForRead(dpEntity)
    val param = sdnEntity.tableEntry.action.action.paramsList.first()
    assertEquals(ByteString.copyFromUtf8("Ethernet1"), param.value)
  }

  @Test
  fun `sdn_string optional match field is translated on write`() {
    val translator = buildP4InfoTranslatorWithStringType()

    val p4rtBytes = "Ethernet0".toByteArray(Charsets.UTF_8)
    val update =
      writeUpdateOptionalMatch(TABLE_ID, MATCH_FIELD_ID, p4rtBytes, ACTION_ID, PARAM_ID, p4rtBytes)
    val translated = translator.translateForWrite(update)
    val match = translated.entity.tableEntry.matchList.first()
    assertEquals(ByteString.copyFrom(dpBytes(0)), match.optional.value)
  }

  // ===========================================================================
  // Mixed sdn_bitwidth and sdn_string in the same p4info
  // ===========================================================================

  @Test
  fun `mixed sdn_bitwidth and sdn_string types are partitioned correctly`() {
    val translator = buildP4InfoTranslatorWithMixedTypes()

    // Field 1 is sdn_string (port_name_t), field 3 is sdn_bitwidth (port_id_t).
    val stringBytes = "Ethernet0".toByteArray(Charsets.UTF_8)
    val bitstringBytes = p4rtBytes(5000)

    // Write with both match fields.
    val entry =
      P4RuntimeOuterClass.TableEntry.newBuilder()
        .setTableId(TABLE_ID)
        .addMatch(
          P4RuntimeOuterClass.FieldMatch.newBuilder()
            .setFieldId(MATCH_FIELD_ID)
            .setExact(
              P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                .setValue(ByteString.copyFrom(stringBytes))
            )
        )
        .addMatch(
          P4RuntimeOuterClass.FieldMatch.newBuilder()
            .setFieldId(BITWIDTH_FIELD_ID)
            .setExact(
              P4RuntimeOuterClass.FieldMatch.Exact.newBuilder()
                .setValue(ByteString.copyFrom(bitstringBytes))
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
                    .setValue(ByteString.copyFrom(stringBytes))
                )
            )
        )
        .build()
    val update =
      P4RuntimeOuterClass.Update.newBuilder()
        .setType(P4RuntimeOuterClass.Update.Type.INSERT)
        .setEntity(P4RuntimeOuterClass.Entity.newBuilder().setTableEntry(entry))
        .build()

    val translated = translator.translateForWrite(update)
    val matches = translated.entity.tableEntry.matchList

    // String field → auto-allocated dp=0.
    assertEquals(ByteString.copyFrom(dpBytes(0)), matches[0].exact.value)
    // Bitwidth field → also auto-allocated dp=0 (independent table).
    assertEquals(ByteString.copyFrom(dpBytes(0)), matches[1].exact.value)

    // Read back: string field returns UTF-8, bitwidth field returns raw bytes.
    val dpEntity =
      P4RuntimeOuterClass.Entity.newBuilder()
        .setTableEntry(
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
            .addMatch(
              P4RuntimeOuterClass.FieldMatch.newBuilder()
                .setFieldId(BITWIDTH_FIELD_ID)
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
        )
        .build()
    val sdnEntity = translator.translateForRead(dpEntity)
    val sdnMatches = sdnEntity.tableEntry.matchList
    assertEquals(ByteString.copyFromUtf8("Ethernet0"), sdnMatches[0].exact.value)
    assertEquals(ByteString.copyFrom(bitstringBytes), sdnMatches[1].exact.value)
  }

  // ===========================================================================
  // PacketIO metadata translation via P4Info
  // ===========================================================================

  @Test
  fun `packet metadata with translated type is translated for packet out`() {
    val translator = buildP4InfoTranslatorWithPacketIO()

    val packetOut =
      P4RuntimeOuterClass.PacketOut.newBuilder()
        .setPayload(ByteString.copyFrom(byteArrayOf(0x00)))
        .addMetadata(
          P4RuntimeOuterClass.PacketMetadata.newBuilder()
            .setMetadataId(PACKET_METADATA_ID)
            .setValue(ByteString.copyFrom(p4rtBytes(5000)))
        )
        .build()

    val translated = translator.translatePacketOut(packetOut)
    val meta = translated.metadataList.first()
    assertEquals(ByteString.copyFrom(dpBytes(0)), meta.value)
  }

  @Test
  fun `packet metadata with translated type is translated for packet in`() {
    val translator = buildP4InfoTranslatorWithPacketIO()

    // Install forward mapping first.
    val packetOut =
      P4RuntimeOuterClass.PacketOut.newBuilder()
        .setPayload(ByteString.copyFrom(byteArrayOf(0x00)))
        .addMetadata(
          P4RuntimeOuterClass.PacketMetadata.newBuilder()
            .setMetadataId(PACKET_METADATA_ID)
            .setValue(ByteString.copyFrom(p4rtBytes(5000)))
        )
        .build()
    translator.translatePacketOut(packetOut)

    // Reverse: data-plane → P4RT.
    val packetIn =
      P4RuntimeOuterClass.PacketIn.newBuilder()
        .setPayload(ByteString.copyFrom(byteArrayOf(0x00)))
        .addMetadata(
          P4RuntimeOuterClass.PacketMetadata.newBuilder()
            .setMetadataId(PACKET_METADATA_ID)
            .setValue(ByteString.copyFrom(dpBytes(0)))
        )
        .build()

    val translated = translator.translatePacketIn(packetIn)
    val meta = translated.metadataList.first()
    assertEquals(ByteString.copyFrom(p4rtBytes(5000)), meta.value)
  }

  @Test
  fun `sdn_string packet metadata round-trips through packet out and in`() {
    val translator = buildP4InfoTranslatorWithStringPacketIO()

    val packetOut =
      P4RuntimeOuterClass.PacketOut.newBuilder()
        .setPayload(ByteString.copyFrom(byteArrayOf(0x00)))
        .addMetadata(
          P4RuntimeOuterClass.PacketMetadata.newBuilder()
            .setMetadataId(PACKET_METADATA_ID)
            .setValue(ByteString.copyFromUtf8("Ethernet0"))
        )
        .build()

    val translatedOut = translator.translatePacketOut(packetOut)
    assertEquals(ByteString.copyFrom(dpBytes(0)), translatedOut.metadataList.first().value)

    // Reverse: data-plane → P4RT.
    val packetIn =
      P4RuntimeOuterClass.PacketIn.newBuilder()
        .setPayload(ByteString.copyFrom(byteArrayOf(0x00)))
        .addMetadata(
          P4RuntimeOuterClass.PacketMetadata.newBuilder()
            .setMetadataId(PACKET_METADATA_ID)
            .setValue(ByteString.copyFrom(dpBytes(0)))
        )
        .build()

    val translatedIn = translator.translatePacketIn(packetIn)
    assertEquals(ByteString.copyFromUtf8("Ethernet0"), translatedIn.metadataList.first().value)
  }

  @Test
  fun `non-translated packet metadata passes through unchanged`() {
    val translator = buildP4InfoTranslatorWithPacketIO()

    val rawValue = ByteString.copyFrom(p4rtBytes(42))
    val packetOut =
      P4RuntimeOuterClass.PacketOut.newBuilder()
        .setPayload(ByteString.copyFrom(byteArrayOf(0x00)))
        .addMetadata(
          P4RuntimeOuterClass.PacketMetadata.newBuilder()
            .setMetadataId(NON_TRANSLATED_METADATA_ID)
            .setValue(rawValue)
        )
        .build()

    val translated = translator.translatePacketOut(packetOut)
    val meta = translated.metadataList.first()
    assertEquals(rawValue, meta.value)
  }

  // ===========================================================================
  // P4Info test constants and builders
  // ===========================================================================

  companion object {
    private const val TABLE_ID = 100
    private const val MATCH_FIELD_ID = 1
    private const val NON_TRANSLATED_FIELD_ID = 2
    private const val ACTION_ID = 200
    private const val PARAM_ID = 1
    private const val PACKET_METADATA_ID = 1
    private const val NON_TRANSLATED_METADATA_ID = 2
    private const val ACTION_PROFILE_ID = 300
    private const val TYPE_NAME = "port_id_t"
    private const val TYPE_URI = "test.port_id"
    private const val BITWIDTH_FIELD_ID = 3
    private const val STRING_TYPE_NAME = "port_name_t"
    private const val STRING_TYPE_URI = "sai.port"
  }

  /** Builds a single-entry P4TypeInfo for a translated type. */
  private fun typeInfo(
    typeName: String,
    uri: String,
    p4rtType: P4Types.P4NewTypeTranslation.Builder.() -> Unit,
  ): P4Types.P4TypeInfo =
    P4Types.P4TypeInfo.newBuilder()
      .putNewTypes(
        typeName,
        P4Types.P4NewTypeSpec.newBuilder()
          .setTranslatedType(P4Types.P4NewTypeTranslation.newBuilder().setUri(uri).apply(p4rtType))
          .build(),
      )
      .build()

  /** Builds a TypeTranslator with sdn_string translated match field and action param. */
  private fun buildP4InfoTranslatorWithStringType(): TypeTranslator {
    val p4info =
      P4InfoOuterClass.P4Info.newBuilder()
        .addTables(
          P4InfoOuterClass.Table.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(TABLE_ID))
            .addMatchFields(
              P4InfoOuterClass.MatchField.newBuilder()
                .setId(MATCH_FIELD_ID)
                .setBitwidth(32)
                .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(STRING_TYPE_NAME))
            )
        )
        .addActions(
          P4InfoOuterClass.Action.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(ACTION_ID))
            .addParams(
              P4InfoOuterClass.Action.Param.newBuilder()
                .setId(PARAM_ID)
                .setBitwidth(32)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(STRING_TYPE_NAME))
            )
        )
        .setTypeInfo(stringTypeInfo())
        .build()
    return TypeTranslator.create(p4info)
  }

  /** Builds a TypeTranslator with sdn_string translated PacketIO metadata. */
  private fun buildP4InfoTranslatorWithStringPacketIO(): TypeTranslator {
    val translatedMetadata =
      P4InfoOuterClass.ControllerPacketMetadata.Metadata.newBuilder()
        .setId(PACKET_METADATA_ID)
        .setName("ingress_port")
        .setBitwidth(32)
        .setTypeName(P4Types.P4NamedType.newBuilder().setName(STRING_TYPE_NAME))
    val p4info =
      P4InfoOuterClass.P4Info.newBuilder()
        .addControllerPacketMetadata(
          P4InfoOuterClass.ControllerPacketMetadata.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(1).setName("packet_out"))
            .addMetadata(translatedMetadata)
        )
        .addControllerPacketMetadata(
          P4InfoOuterClass.ControllerPacketMetadata.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(2).setName("packet_in"))
            .addMetadata(translatedMetadata)
        )
        .setTypeInfo(stringTypeInfo())
        .build()
    return TypeTranslator.create(p4info)
  }

  /** Builds a TypeTranslator with both sdn_string and sdn_bitwidth match fields. */
  private fun buildP4InfoTranslatorWithMixedTypes(): TypeTranslator {
    val typeInfo =
      bitstringTypeInfo().toBuilder().putAllNewTypes(stringTypeInfo().newTypesMap).build()
    val p4info =
      P4InfoOuterClass.P4Info.newBuilder()
        .addTables(
          P4InfoOuterClass.Table.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(TABLE_ID))
            .addMatchFields(
              P4InfoOuterClass.MatchField.newBuilder()
                .setId(MATCH_FIELD_ID)
                .setBitwidth(32)
                .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(STRING_TYPE_NAME))
            )
            .addMatchFields(
              P4InfoOuterClass.MatchField.newBuilder()
                .setId(BITWIDTH_FIELD_ID)
                .setBitwidth(32)
                .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(TYPE_NAME))
            )
        )
        .addActions(
          P4InfoOuterClass.Action.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(ACTION_ID))
            .addParams(
              P4InfoOuterClass.Action.Param.newBuilder()
                .setId(PARAM_ID)
                .setBitwidth(32)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(STRING_TYPE_NAME))
            )
        )
        .setTypeInfo(typeInfo)
        .build()
    return TypeTranslator.create(p4info)
  }

  private fun bitstringTypeInfo() = typeInfo(TYPE_NAME, TYPE_URI) { setSdnBitwidth(32) }

  private fun stringTypeInfo() =
    typeInfo(STRING_TYPE_NAME, STRING_TYPE_URI) {
      setSdnString(P4Types.P4NewTypeTranslation.SdnString.getDefaultInstance())
    }

  /** Builds a TypeTranslator from synthetic p4info with a translated match field. */
  private fun buildP4InfoTranslator(): TypeTranslator {
    val p4info =
      P4InfoOuterClass.P4Info.newBuilder()
        .addTables(
          P4InfoOuterClass.Table.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(TABLE_ID))
            .addMatchFields(
              P4InfoOuterClass.MatchField.newBuilder()
                .setId(MATCH_FIELD_ID)
                .setBitwidth(32)
                .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(TYPE_NAME))
            )
            .addMatchFields(
              P4InfoOuterClass.MatchField.newBuilder()
                .setId(NON_TRANSLATED_FIELD_ID)
                .setBitwidth(16)
                .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
            )
        )
        .addActions(
          P4InfoOuterClass.Action.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(ACTION_ID))
            .addParams(
              P4InfoOuterClass.Action.Param.newBuilder()
                .setId(PARAM_ID)
                .setBitwidth(32)
                .setTypeName(P4Types.P4NamedType.newBuilder().setName(TYPE_NAME))
            )
        )
        .setTypeInfo(bitstringTypeInfo())
        .build()
    return TypeTranslator.create(p4info)
  }

  /** Builds a TypeTranslator from synthetic p4info with translated PacketIO metadata. */
  private fun buildP4InfoTranslatorWithPacketIO(): TypeTranslator {
    val translatedMetadata =
      P4InfoOuterClass.ControllerPacketMetadata.Metadata.newBuilder()
        .setId(PACKET_METADATA_ID)
        .setName("ingress_port")
        .setBitwidth(32)
        .setTypeName(P4Types.P4NamedType.newBuilder().setName(TYPE_NAME))
    val p4info =
      P4InfoOuterClass.P4Info.newBuilder()
        .addControllerPacketMetadata(
          P4InfoOuterClass.ControllerPacketMetadata.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(1).setName("packet_out"))
            .addMetadata(translatedMetadata)
            .addMetadata(
              P4InfoOuterClass.ControllerPacketMetadata.Metadata.newBuilder()
                .setId(NON_TRANSLATED_METADATA_ID)
                .setName("padding")
                .setBitwidth(7)
            )
        )
        .addControllerPacketMetadata(
          P4InfoOuterClass.ControllerPacketMetadata.newBuilder()
            .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(2).setName("packet_in"))
            .addMetadata(translatedMetadata)
        )
        .setTypeInfo(bitstringTypeInfo())
        .build()
    return TypeTranslator.create(p4info)
  }

  /** Builds an Update wrapping a table entry with an exact match. */
  private fun writeUpdate(
    tableId: Int,
    fieldId: Int,
    matchValue: ByteArray,
    actionId: Int,
    paramId: Int,
    paramValue: ByteArray,
  ): P4RuntimeOuterClass.Update {
    val match = exactMatch(fieldId, matchValue)
    return wrapUpdate(buildTableEntry(tableId, match, actionId, paramId, paramValue))
  }

  /** Builds an Update wrapping a table entry with an optional match. */
  private fun writeUpdateOptionalMatch(
    tableId: Int,
    fieldId: Int,
    matchValue: ByteArray,
    actionId: Int,
    paramId: Int,
    paramValue: ByteArray,
  ): P4RuntimeOuterClass.Update {
    val match =
      P4RuntimeOuterClass.FieldMatch.newBuilder()
        .setFieldId(fieldId)
        .setOptional(
          P4RuntimeOuterClass.FieldMatch.Optional.newBuilder()
            .setValue(ByteString.copyFrom(matchValue))
        )
        .build()
    return wrapUpdate(buildTableEntry(tableId, match, actionId, paramId, paramValue))
  }

  /** Builds an Entity with exact match and action param (simulates a read response). */
  private fun readEntity(
    tableId: Int,
    fieldId: Int,
    matchValue: ByteArray,
    actionId: Int,
    paramId: Int,
    paramValue: ByteArray,
  ): P4RuntimeOuterClass.Entity {
    val match = exactMatch(fieldId, matchValue)
    return P4RuntimeOuterClass.Entity.newBuilder()
      .setTableEntry(buildTableEntry(tableId, match, actionId, paramId, paramValue))
      .build()
  }

  private fun exactMatch(fieldId: Int, value: ByteArray): P4RuntimeOuterClass.FieldMatch =
    P4RuntimeOuterClass.FieldMatch.newBuilder()
      .setFieldId(fieldId)
      .setExact(
        P4RuntimeOuterClass.FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(value))
      )
      .build()

  private fun buildTableEntry(
    tableId: Int,
    match: P4RuntimeOuterClass.FieldMatch,
    actionId: Int,
    paramId: Int,
    paramValue: ByteArray,
  ): P4RuntimeOuterClass.TableEntry =
    P4RuntimeOuterClass.TableEntry.newBuilder()
      .setTableId(tableId)
      .addMatch(match)
      .setAction(
        P4RuntimeOuterClass.TableAction.newBuilder()
          .setAction(
            P4RuntimeOuterClass.Action.newBuilder()
              .setActionId(actionId)
              .addParams(
                P4RuntimeOuterClass.Action.Param.newBuilder()
                  .setParamId(paramId)
                  .setValue(ByteString.copyFrom(paramValue))
              )
          )
      )
      .build()

  private fun wrapUpdate(entry: P4RuntimeOuterClass.TableEntry): P4RuntimeOuterClass.Update =
    P4RuntimeOuterClass.Update.newBuilder()
      .setType(P4RuntimeOuterClass.Update.Type.INSERT)
      .setEntity(P4RuntimeOuterClass.Entity.newBuilder().setTableEntry(entry))
      .build()

  /** Builds an Update wrapping an ActionProfileMember with a single param. */
  private fun memberUpdate(
    actionId: Int,
    paramId: Int,
    paramValue: ByteArray,
  ): P4RuntimeOuterClass.Update {
    val member =
      P4RuntimeOuterClass.ActionProfileMember.newBuilder()
        .setActionProfileId(ACTION_PROFILE_ID)
        .setMemberId(1)
        .setAction(
          P4RuntimeOuterClass.Action.newBuilder()
            .setActionId(actionId)
            .addParams(
              P4RuntimeOuterClass.Action.Param.newBuilder()
                .setParamId(paramId)
                .setValue(ByteString.copyFrom(paramValue))
            )
        )
        .build()
    return P4RuntimeOuterClass.Update.newBuilder()
      .setType(P4RuntimeOuterClass.Update.Type.INSERT)
      .setEntity(P4RuntimeOuterClass.Entity.newBuilder().setActionProfileMember(member))
      .build()
  }

  // ===========================================================================
  // Helpers
  // ===========================================================================

  /** Builds a TypeTranslator from the given TypeTranslation configs. */
  private fun buildTranslator(vararg translations: TypeTranslation): TypeTranslator =
    TypeTranslator.create(translations.toList())

  /** Shorthand for building a TypeTranslation proto. */
  private fun translation(block: TypeTranslation.Builder.() -> Unit): TypeTranslation =
    TypeTranslation.newBuilder().apply(block).build()

  /** Builds a numeric-to-numeric TranslationEntry. */
  private fun entry(sdnValue: ByteArray, dataplaneValue: ByteArray): TranslationEntry =
    TranslationEntry.newBuilder()
      .setSdnBitstring(ByteString.copyFrom(sdnValue))
      .setDataplaneValue(ByteString.copyFrom(dataplaneValue))
      .build()

  /** Builds a string-to-numeric TranslationEntry. */
  private fun stringEntry(sdnStr: String, dataplaneValue: ByteArray): TranslationEntry =
    TranslationEntry.newBuilder()
      .setSdnStr(sdnStr)
      .setDataplaneValue(ByteString.copyFrom(dataplaneValue))
      .build()

  private fun p4rtBytes(value: Int): ByteArray = encodeMinWidth(value)

  private fun dpBytes(value: Int): ByteArray = encodeMinWidth(value)

  private fun encodeMinWidth(value: Int): ByteArray {
    if (value == 0) return byteArrayOf(0)
    val bytes = mutableListOf<Byte>()
    var v = value
    while (v > 0) {
      bytes.add(0, (v and 0xFF).toByte())
      v = v shr 8
    }
    return bytes.toByteArray()
  }

  /** Wraps a ByteArray as ByteString. */
  private fun bs(bytes: ByteArray): ByteString = ByteString.copyFrom(bytes)
}
