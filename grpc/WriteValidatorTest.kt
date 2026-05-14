package fourward.grpc

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass

/**
 * Unit tests for [WriteValidator].
 *
 * Tests validation logic directly without the gRPC service or simulator. Each test constructs a
 * synthetic p4info and checks that the validator accepts valid updates and rejects invalid ones
 * with the correct gRPC status code.
 */
class WriteValidatorTest {

  // =========================================================================
  // Valid updates — should not throw
  // =========================================================================

  @Test
  fun `valid insert passes validation`() {
    val v = validator()
    v.validate(insertUpdate(EXACT_TABLE_ID, exactMatch(MATCH_FIELD_ID, bytes(2)), action()))
  }

  @Test
  fun `delete skips content validation`() {
    // DELETE with a bogus action ID — should pass because DELETE only needs the match key.
    val v = validator()
    v.validate(
      tableUpdate(
        P4RuntimeOuterClass.Update.Type.DELETE,
        EXACT_TABLE_ID,
        listOf(exactMatch(MATCH_FIELD_ID, bytes(2))),
        action(actionId = 99999),
      )
    )
  }

  // =========================================================================
  // Table ID validation
  // =========================================================================

  @Test
  fun `unknown table ID returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(insertUpdate(99999, exactMatch(MATCH_FIELD_ID, bytes(2)), action()))
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
  }

  // =========================================================================
  // Action validation (§9.1.2)
  // =========================================================================

  @Test
  fun `unknown action ID returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(EXACT_TABLE_ID, exactMatch(MATCH_FIELD_ID, bytes(2)), action(actionId = 99))
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
  }

  @Test
  fun `action not in table action_refs returns INVALID_ARGUMENT`() {
    val v = validator()
    // OTHER_ACTION_ID exists in p4info but is not in the exact table's action_refs.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            EXACT_TABLE_ID,
            exactMatch(MATCH_FIELD_ID, bytes(2)),
            action(actionId = OTHER_ACTION_ID),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("not valid for table"))
  }

  @Test
  fun `wrong param count returns INVALID_ARGUMENT`() {
    val v = validator()
    // forward expects 1 param; send 0.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            EXACT_TABLE_ID,
            exactMatch(MATCH_FIELD_ID, bytes(2)),
            action(params = emptyList()),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("expects"))
  }

  @Test
  fun `unknown param ID returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            EXACT_TABLE_ID,
            exactMatch(MATCH_FIELD_ID, bytes(2)),
            action(params = listOf(param(paramId = 99, value = bytes(2)))),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("unknown param ID"))
  }

  @Test
  fun `param value too narrow accepted as canonical encoding`() {
    // §8.3: shorter-than-fixed-width is the canonical form (read-write symmetric).
    val v = validator()
    v.validate(
      insertUpdate(
        EXACT_TABLE_ID,
        exactMatch(MATCH_FIELD_ID, bytes(2)),
        action(params = listOf(param(value = bytes(1)))),
      )
    )
  }

  @Test
  fun `param value out of range returns OUT_OF_RANGE`() {
    // §8.3: value 0x10000 doesn't fit in bit<16>, regardless of byte count.
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            EXACT_TABLE_ID,
            exactMatch(MATCH_FIELD_ID, bytes(2)),
            action(params = listOf(param(value = byteArrayOf(0x01, 0x00, 0x00)))),
          )
        )
      }
    assertEquals(Status.Code.OUT_OF_RANGE, e.status.code)
  }

  @Test
  fun `zero-bitwidth param skips width check`() {
    // Simulates @p4runtime_translation with sdn_string where bitwidth is 0.
    val v = validatorWithZeroBitwidthParam()
    v.validate(
      insertUpdate(
        EXACT_TABLE_ID,
        exactMatch(MATCH_FIELD_ID, bytes(2)),
        action(params = listOf(param(value = bytes(5)))),
      )
    )
  }

  // =========================================================================
  // Match field validation (§9.1.1)
  // =========================================================================

  @Test
  fun `unknown match field ID returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(insertUpdate(EXACT_TABLE_ID, exactMatch(fieldId = 99, bytes(2)), action()))
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("unknown match field ID"))
  }

  @Test
  fun `wrong match kind returns INVALID_ARGUMENT`() {
    val v = validator()
    // Table expects EXACT; send TERNARY.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(insertUpdate(EXACT_TABLE_ID, ternaryMatch(MATCH_FIELD_ID, bytes(2)), action()))
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("EXACT"))
  }

  @Test
  fun `match value shorter than fixed width accepted as canonical encoding`() {
    // §8.3: 1 byte for a bit<16> match field is the canonical form (value fits in 16 bits).
    val v = validator()
    v.validate(insertUpdate(EXACT_TABLE_ID, exactMatch(MATCH_FIELD_ID, bytes(1)), action()))
  }

  @Test
  fun `match value out of range returns OUT_OF_RANGE`() {
    // §8.3: 3-byte value 0x010000 overflows the field's bit<16> width.
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            EXACT_TABLE_ID,
            exactMatch(MATCH_FIELD_ID, byteArrayOf(0x01, 0x00, 0x00)),
            action(),
          )
        )
      }
    assertEquals(Status.Code.OUT_OF_RANGE, e.status.code)
  }

  @Test
  fun `missing required exact field returns INVALID_ARGUMENT`() {
    val v = validator()
    // No match fields at all.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(insertUpdate(EXACT_TABLE_ID, matches = emptyList(), action = action()))
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("missing"))
  }

  @Test
  fun `ternary match value out of range returns OUT_OF_RANGE`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            TERNARY_TABLE_ID,
            ternaryMatch(TERNARY_FIELD_ID, value = byteArrayOf(0x01, 0x00, 0x00), mask = bytes(2)),
            ternaryAction(),
            priority = 1,
          )
        )
      }
    assertEquals(Status.Code.OUT_OF_RANGE, e.status.code)
  }

  @Test
  fun `LPM match value out of range returns OUT_OF_RANGE`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            LPM_TABLE_ID,
            lpmMatch(LPM_FIELD_ID, value = byteArrayOf(0x01, 0x00, 0x00), prefixLen = 8),
            ternaryAction(),
          )
        )
      }
    assertEquals(Status.Code.OUT_OF_RANGE, e.status.code)
  }

  // =========================================================================
  // Priority validation (§9.1.1)
  // =========================================================================

  @Test
  fun `exact table with nonzero priority returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(EXACT_TABLE_ID, exactMatch(MATCH_FIELD_ID, bytes(2)), action(), priority = 5)
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("priority"))
  }

  @Test
  fun `ternary table with zero priority returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            TERNARY_TABLE_ID,
            ternaryMatch(TERNARY_FIELD_ID, bytes(2)),
            ternaryAction(),
            priority = 0,
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("priority"))
  }

  // =========================================================================
  // Ternary/LPM semantic validation (§8.3)
  // =========================================================================

  @Test
  fun `ternary value with bits set outside mask returns INVALID_ARGUMENT`() {
    val v = validator()
    // value = 0x0F00, mask = 0x00FF → byte 0 has bits set where mask is 0.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            TERNARY_TABLE_ID,
            ternaryMatch(
              TERNARY_FIELD_ID,
              value = byteArrayOf(0x0F, 0x00),
              mask = byteArrayOf(0x00, 0xFF.toByte()),
            ),
            ternaryAction(),
            priority = 1,
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("masked"))
  }

  @Test
  fun `ternary value properly masked passes`() {
    val v = validator()
    // value = 0x00AB, mask = 0x00FF → value & ~mask == 0.
    v.validate(
      insertUpdate(
        TERNARY_TABLE_ID,
        ternaryMatch(
          TERNARY_FIELD_ID,
          value = byteArrayOf(0x00, 0xAB.toByte()),
          mask = byteArrayOf(0x00, 0xFF.toByte()),
        ),
        ternaryAction(),
        priority = 1,
      )
    )
  }

  @Test
  fun `LPM value with bits set beyond prefix_len returns INVALID_ARGUMENT`() {
    val v = validator()
    // 16-bit field, prefix_len = 8 → second byte must be zero.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            LPM_TABLE_ID,
            lpmMatch(LPM_FIELD_ID, value = byteArrayOf(0xFF.toByte(), 0x01), prefixLen = 8),
            ternaryAction(),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("prefix"))
  }

  @Test
  fun `LPM value with partial byte correctly masked passes`() {
    val v = validator()
    // 16-bit field, prefix_len = 12 → second byte low 4 bits must be zero. 0xF0 is valid.
    v.validate(
      insertUpdate(
        LPM_TABLE_ID,
        lpmMatch(LPM_FIELD_ID, value = byteArrayOf(0xFF.toByte(), 0xF0.toByte()), prefixLen = 12),
        ternaryAction(),
      )
    )
  }

  @Test
  fun `LPM value with partial byte bits set returns INVALID_ARGUMENT`() {
    val v = validator()
    // 16-bit field, prefix_len = 12 → second byte low 4 bits must be zero. 0xFF violates.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            LPM_TABLE_ID,
            lpmMatch(
              LPM_FIELD_ID,
              value = byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
              prefixLen = 12,
            ),
            ternaryAction(),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("prefix"))
  }

  // =========================================================================
  // Duplicate match field validation (§9.1)
  // =========================================================================

  @Test
  fun `duplicate match field ID returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            EXACT_TABLE_ID,
            matches =
              listOf(exactMatch(MATCH_FIELD_ID, bytes(2)), exactMatch(MATCH_FIELD_ID, bytes(2))),
            action = action(),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("duplicate"))
  }

  // =========================================================================
  // Const table validation (§9.1)
  // =========================================================================

  @Test
  fun `insert into const table returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(insertUpdate(CONST_TABLE_ID, exactMatch(CONST_FIELD_ID, bytes(2)), action()))
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("const"))
  }

  @Test
  fun `modify const table returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          tableUpdate(
            P4RuntimeOuterClass.Update.Type.MODIFY,
            CONST_TABLE_ID,
            listOf(exactMatch(CONST_FIELD_ID, bytes(2))),
            action(),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("const"))
  }

  @Test
  fun `delete from const table returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          tableUpdate(
            P4RuntimeOuterClass.Update.Type.DELETE,
            CONST_TABLE_ID,
            listOf(exactMatch(CONST_FIELD_ID, bytes(2))),
            action(),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("const"))
  }

  // =========================================================================
  // Default entry validation (§9.1)
  // =========================================================================

  @Test
  fun `default entry MODIFY without match fields passes`() {
    val v = validator()
    v.validate(
      tableUpdate(
        P4RuntimeOuterClass.Update.Type.MODIFY,
        EXACT_TABLE_ID,
        action = action(),
        isDefaultAction = true,
      )
    )
  }

  @Test
  fun `default entry INSERT returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          tableUpdate(
            P4RuntimeOuterClass.Update.Type.INSERT,
            EXACT_TABLE_ID,
            action = action(),
            isDefaultAction = true,
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("MODIFY"))
  }

  @Test
  fun `default entry DELETE returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          tableUpdate(
            P4RuntimeOuterClass.Update.Type.DELETE,
            EXACT_TABLE_ID,
            isDefaultAction = true,
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("MODIFY"))
  }

  @Test
  fun `default entry with match fields returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          tableUpdate(
            P4RuntimeOuterClass.Update.Type.MODIFY,
            EXACT_TABLE_ID,
            listOf(exactMatch(MATCH_FIELD_ID, bytes(2))),
            action(),
            isDefaultAction = true,
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("match fields"))
  }

  // =========================================================================
  // One-shot action profile action set (§9.2.3)
  // =========================================================================

  @Test
  fun `valid one-shot action set passes validation`() {
    val v = validator()
    v.validate(oneShotUpdate(EXACT_TABLE_ID, exactMatch(MATCH_FIELD_ID, bytes(2)), action()))
  }

  @Test
  fun `one-shot with unknown action ID returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          oneShotUpdate(EXACT_TABLE_ID, exactMatch(MATCH_FIELD_ID, bytes(2)), action(actionId = 99))
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
  }

  @Test
  fun `one-shot with wrong param count returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          oneShotUpdate(
            EXACT_TABLE_ID,
            exactMatch(MATCH_FIELD_ID, bytes(2)),
            action(params = emptyList()),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
  }

  // =========================================================================
  // Range semantic validation (§9.1.1)
  // =========================================================================

  @Test
  fun `range match with low less than high passes`() {
    val v = validator()
    v.validate(
      insertUpdate(
        RANGE_TABLE_ID,
        rangeMatch(low = byteArrayOf(0x00, 0x10), high = byteArrayOf(0x00, 0x20)),
        ternaryAction(),
        priority = 1,
      )
    )
  }

  @Test
  fun `range match with low equal to high passes`() {
    val v = validator()
    v.validate(
      insertUpdate(
        RANGE_TABLE_ID,
        rangeMatch(low = byteArrayOf(0x00, 0x10), high = byteArrayOf(0x00, 0x10)),
        ternaryAction(),
        priority = 1,
      )
    )
  }

  @Test
  fun `range match with low greater than high returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            RANGE_TABLE_ID,
            rangeMatch(low = byteArrayOf(0x00, 0x20), high = byteArrayOf(0x00, 0x10)),
            ternaryAction(),
            priority = 1,
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("low > high"))
  }

  @Test
  fun `range match with first byte determining result passes`() {
    val v = validator()
    // low = {0x01, 0xFF}, high = {0x02, 0x00}: first byte decides (1 < 2).
    v.validate(
      insertUpdate(
        RANGE_TABLE_ID,
        rangeMatch(low = byteArrayOf(0x01, 0xFF.toByte()), high = byteArrayOf(0x02, 0x00)),
        ternaryAction(),
        priority = 1,
      )
    )
  }

  @Test
  fun `range match with first byte greater rejects`() {
    val v = validator()
    // low = {0x02, 0x00}, high = {0x01, 0xFF}: first byte decides (2 > 1).
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            RANGE_TABLE_ID,
            rangeMatch(low = byteArrayOf(0x02, 0x00), high = byteArrayOf(0x01, 0xFF.toByte())),
            ternaryAction(),
            priority = 1,
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("low > high"))
  }

  @Test
  fun `range match endpoints out of range return OUT_OF_RANGE`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            RANGE_TABLE_ID,
            rangeMatch(low = bytes(1), high = byteArrayOf(0x01, 0x00, 0x00)),
            ternaryAction(),
            priority = 1,
          )
        )
      }
    assertEquals(Status.Code.OUT_OF_RANGE, e.status.code)
  }

  // =========================================================================
  // Optional match validation
  // =========================================================================

  @Test
  fun `optional match passes validation`() {
    val v = validator()
    v.validate(
      insertUpdate(
        OPTIONAL_TABLE_ID,
        optionalMatch(value = bytes(2)),
        ternaryAction(),
        priority = 1,
      )
    )
  }

  @Test
  fun `optional match value out of range returns OUT_OF_RANGE`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            OPTIONAL_TABLE_ID,
            optionalMatch(value = byteArrayOf(0x01, 0x00, 0x00)),
            ternaryAction(),
            priority = 1,
          )
        )
      }
    assertEquals(Status.Code.OUT_OF_RANGE, e.status.code)
  }

  @Test
  fun `optional table requires priority`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            OPTIONAL_TABLE_ID,
            optionalMatch(value = bytes(2)),
            ternaryAction(),
            priority = 0,
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("priority"))
  }

  @Test
  fun `optional field can be omitted`() {
    val v = validator()
    // Optional fields can be omitted — unlike exact fields, they are not required.
    v.validate(
      insertUpdate(OPTIONAL_TABLE_ID, matches = emptyList(), action = ternaryAction(), priority = 1)
    )
  }

  // =========================================================================
  // Priority validation (§9.1.1)
  // =========================================================================

  @Test
  fun `ternary table with positive priority passes`() {
    val v = validator()
    v.validate(
      insertUpdate(
        TERNARY_TABLE_ID,
        ternaryMatch(TERNARY_FIELD_ID, bytes(2)),
        ternaryAction(),
        priority = 10,
      )
    )
  }

  // =========================================================================
  // Action profile member validation (§9.2.1)
  // =========================================================================

  @Test
  fun `valid member insert passes validation`() {
    val v = validator()
    v.validate(memberUpdate(P4RuntimeOuterClass.Update.Type.INSERT, action()))
  }

  @Test
  fun `member delete skips content validation`() {
    val v = validator()
    // DELETE with a bogus action — should pass because DELETE only needs the key.
    v.validate(memberUpdate(P4RuntimeOuterClass.Update.Type.DELETE, action(actionId = 99999)))
  }

  @Test
  fun `member modify validates content`() {
    val v = validator()
    // MODIFY should validate action just like INSERT.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(memberUpdate(P4RuntimeOuterClass.Update.Type.MODIFY, action(actionId = 99)))
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("unknown action ID"))
  }

  @Test
  fun `member with unknown profile ID returns NOT_FOUND`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(memberUpdate(P4RuntimeOuterClass.Update.Type.INSERT, action(), profileId = 99))
      }
    assertEquals(Status.Code.NOT_FOUND, e.status.code)
    assert(e.status.description!!.contains("action_profile_id"))
  }

  @Test
  fun `member with unknown action ID returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(memberUpdate(P4RuntimeOuterClass.Update.Type.INSERT, action(actionId = 99)))
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("unknown action ID"))
  }

  @Test
  fun `member with action not in profile returns INVALID_ARGUMENT`() {
    val v = validator()
    // TERNARY_ACTION_ID exists in p4info but is not in the profile's associated tables'
    // action_refs.
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          memberUpdate(
            P4RuntimeOuterClass.Update.Type.INSERT,
            action(actionId = TERNARY_ACTION_ID, params = emptyList()),
          )
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("not valid for action profile"))
  }

  @Test
  fun `member with wrong param count returns INVALID_ARGUMENT`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          memberUpdate(P4RuntimeOuterClass.Update.Type.INSERT, action(params = emptyList()))
        )
      }
    assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    assert(e.status.description!!.contains("expects"))
  }

  @Test
  fun `member with param value out of range returns OUT_OF_RANGE`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          memberUpdate(
            P4RuntimeOuterClass.Update.Type.INSERT,
            action(params = listOf(param(value = byteArrayOf(0x01, 0x00, 0x00)))),
          )
        )
      }
    assertEquals(Status.Code.OUT_OF_RANGE, e.status.code)
  }

  // =========================================================================
  // Action profile group validation (§9.2.2)
  // =========================================================================

  @Test
  fun `valid group insert passes validation`() {
    val v = validator()
    v.validate(groupUpdate(P4RuntimeOuterClass.Update.Type.INSERT))
  }

  @Test
  fun `group with unknown profile ID returns NOT_FOUND`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(groupUpdate(P4RuntimeOuterClass.Update.Type.INSERT, profileId = 99))
      }
    assertEquals(Status.Code.NOT_FOUND, e.status.code)
    assert(e.status.description!!.contains("action_profile_id"))
  }

  @Test
  fun `group delete with valid profile ID passes`() {
    val v = validator()
    v.validate(groupUpdate(P4RuntimeOuterClass.Update.Type.DELETE))
  }

  @Test
  fun `group delete with unknown profile ID returns NOT_FOUND`() {
    val v = validator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(groupUpdate(P4RuntimeOuterClass.Update.Type.DELETE, profileId = 99))
      }
    assertEquals(Status.Code.NOT_FOUND, e.status.code)
    assert(e.status.description!!.contains("action_profile_id"))
  }

  // =========================================================================
  // Encoding edge cases (§8.3)
  // =========================================================================

  @Test
  fun `bit1 field accepts 1-byte value`() {
    val v = bitwidthValidator()
    v.validate(insertUpdate(BIT1_TABLE_ID, listOf(exactMatch(BIT1_FIELD_ID, bytes(1))), action()))
  }

  @Test
  fun `bit7 field accepts 1-byte value`() {
    val v = bitwidthValidator()
    v.validate(insertUpdate(BIT7_TABLE_ID, listOf(exactMatch(BIT7_FIELD_ID, bytes(1))), action()))
  }

  @Test
  fun `bit7 field rejects value greater than 127`() {
    // §8.3: 0x80 = 128 doesn't fit in 7 bits (max = 127), even when sent as a single byte.
    val v = bitwidthValidator()
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(
            BIT7_TABLE_ID,
            listOf(exactMatch(BIT7_FIELD_ID, byteArrayOf(0x80.toByte()))),
            action(),
          )
        )
      }
    assertEquals(Status.Code.OUT_OF_RANGE, e.status.code)
  }

  @Test
  fun `bit128 field accepts 16-byte value`() {
    val v = bitwidthValidator()
    v.validate(
      insertUpdate(BIT128_TABLE_ID, listOf(exactMatch(BIT128_FIELD_ID, bytes(16))), action())
    )
  }

  @Test
  fun `bit128 field accepts 15-byte canonical value`() {
    // §8.3: shorter-than-fixed-width is the canonical form, accepted regardless of bitwidth.
    val v = bitwidthValidator()
    v.validate(
      insertUpdate(BIT128_TABLE_ID, listOf(exactMatch(BIT128_FIELD_ID, bytes(15))), action())
    )
  }

  @Test
  fun `bit128 field rejects value exceeding 128 bits`() {
    // 17-byte value with high byte 0x01 = 2^128 — first integer that doesn't fit.
    val v = bitwidthValidator()
    val tooLarge = ByteArray(17)
    tooLarge[0] = 0x01
    val e =
      assertThrows(StatusException::class.java) {
        v.validate(
          insertUpdate(BIT128_TABLE_ID, listOf(exactMatch(BIT128_FIELD_ID, tooLarge)), action())
        )
      }
    assertEquals(Status.Code.OUT_OF_RANGE, e.status.code)
  }

  @Test
  fun `leading zero bytes are allowed in exact match`() {
    // bit<16> field with value 0x0001 — the leading zero byte is valid.
    val v = validator()
    v.validate(
      insertUpdate(
        EXACT_TABLE_ID,
        listOf(exactMatch(MATCH_FIELD_ID, byteArrayOf(0x00, 0x01))),
        action(),
      )
    )
  }

  // =========================================================================
  // Test fixtures
  // =========================================================================

  companion object {
    private const val EXACT_TABLE_ID = 1
    private const val TERNARY_TABLE_ID = 2
    private const val LPM_TABLE_ID = 3
    private const val CONST_TABLE_ID = 4
    private const val RANGE_TABLE_ID = 5
    private const val OPTIONAL_TABLE_ID = 6
    private const val BIT1_TABLE_ID = 100
    private const val BIT7_TABLE_ID = 101
    private const val BIT128_TABLE_ID = 102
    private const val BIT1_FIELD_ID = 100
    private const val BIT7_FIELD_ID = 101
    private const val BIT128_FIELD_ID = 102
    private const val MATCH_FIELD_ID = 1
    private const val TERNARY_FIELD_ID = 2
    private const val LPM_FIELD_ID = 3
    private const val CONST_FIELD_ID = 4
    private const val RANGE_FIELD_ID = 5
    private const val OPTIONAL_FIELD_ID = 6
    private const val ACTION_ID = 10
    private const val OTHER_ACTION_ID = 11
    private const val TERNARY_ACTION_ID = 12
    private const val ACTION_PROFILE_ID = 20
    private const val PARAM_ID = 1
    private const val MATCH_BITWIDTH = 16
    private const val PARAM_BITWIDTH = 16
  }

  private fun validator(): WriteValidator = WriteValidator(testP4Info())

  private fun validatorWithZeroBitwidthParam(): WriteValidator =
    WriteValidator(testP4InfoWithZeroBitwidthParam())

  private fun bitwidthValidator(): WriteValidator = WriteValidator(testP4InfoWithBitwidths())

  private fun testP4Info(): P4InfoOuterClass.P4Info =
    P4InfoOuterClass.P4Info.newBuilder()
      .addTables(
        P4InfoOuterClass.Table.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(EXACT_TABLE_ID).setName("exact_table")
          )
          .addMatchFields(
            P4InfoOuterClass.MatchField.newBuilder()
              .setId(MATCH_FIELD_ID)
              .setName("f1")
              .setBitwidth(MATCH_BITWIDTH)
              .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
          )
          .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(ACTION_ID))
      )
      .addTables(
        P4InfoOuterClass.Table.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(TERNARY_TABLE_ID).setName("ternary_table")
          )
          .addMatchFields(
            P4InfoOuterClass.MatchField.newBuilder()
              .setId(TERNARY_FIELD_ID)
              .setName("f2")
              .setBitwidth(MATCH_BITWIDTH)
              .setMatchType(P4InfoOuterClass.MatchField.MatchType.TERNARY)
          )
          .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(TERNARY_ACTION_ID))
      )
      .addTables(
        P4InfoOuterClass.Table.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(LPM_TABLE_ID).setName("lpm_table")
          )
          .addMatchFields(
            P4InfoOuterClass.MatchField.newBuilder()
              .setId(LPM_FIELD_ID)
              .setName("f3")
              .setBitwidth(MATCH_BITWIDTH)
              .setMatchType(P4InfoOuterClass.MatchField.MatchType.LPM)
          )
          .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(TERNARY_ACTION_ID))
      )
      .addTables(
        P4InfoOuterClass.Table.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(CONST_TABLE_ID).setName("const_table")
          )
          .addMatchFields(
            P4InfoOuterClass.MatchField.newBuilder()
              .setId(CONST_FIELD_ID)
              .setName("f4")
              .setBitwidth(MATCH_BITWIDTH)
              .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
          )
          .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(ACTION_ID))
          .setIsConstTable(true)
      )
      .addTables(
        P4InfoOuterClass.Table.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(RANGE_TABLE_ID).setName("range_table")
          )
          .addMatchFields(
            P4InfoOuterClass.MatchField.newBuilder()
              .setId(RANGE_FIELD_ID)
              .setName("f5")
              .setBitwidth(MATCH_BITWIDTH)
              .setMatchType(P4InfoOuterClass.MatchField.MatchType.RANGE)
          )
          .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(TERNARY_ACTION_ID))
      )
      .addTables(
        P4InfoOuterClass.Table.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder()
              .setId(OPTIONAL_TABLE_ID)
              .setName("optional_table")
          )
          .addMatchFields(
            P4InfoOuterClass.MatchField.newBuilder()
              .setId(OPTIONAL_FIELD_ID)
              .setName("f6")
              .setBitwidth(MATCH_BITWIDTH)
              .setMatchType(P4InfoOuterClass.MatchField.MatchType.OPTIONAL)
          )
          .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(TERNARY_ACTION_ID))
      )
      .addActions(
        P4InfoOuterClass.Action.newBuilder()
          .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(ACTION_ID).setName("forward"))
          .addParams(
            P4InfoOuterClass.Action.Param.newBuilder()
              .setId(PARAM_ID)
              .setName("port")
              .setBitwidth(PARAM_BITWIDTH)
          )
      )
      .addActions(
        P4InfoOuterClass.Action.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(OTHER_ACTION_ID).setName("other")
          )
      )
      .addActions(
        P4InfoOuterClass.Action.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(TERNARY_ACTION_ID).setName("drop")
          )
      )
      .addActionProfiles(
        P4InfoOuterClass.ActionProfile.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(ACTION_PROFILE_ID).setName("test_profile")
          )
          // Associated with exact_table — valid actions are ACTION_ID only.
          .addTableIds(EXACT_TABLE_ID)
          .setSize(64)
      )
      .build()

  private fun testP4InfoWithZeroBitwidthParam(): P4InfoOuterClass.P4Info =
    P4InfoOuterClass.P4Info.newBuilder()
      .addTables(
        P4InfoOuterClass.Table.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(EXACT_TABLE_ID).setName("exact_table")
          )
          .addMatchFields(
            P4InfoOuterClass.MatchField.newBuilder()
              .setId(MATCH_FIELD_ID)
              .setName("f1")
              .setBitwidth(MATCH_BITWIDTH)
              .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
          )
          .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(ACTION_ID))
      )
      .addActions(
        P4InfoOuterClass.Action.newBuilder()
          .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(ACTION_ID).setName("forward"))
          .addParams(
            P4InfoOuterClass.Action.Param.newBuilder()
              .setId(PARAM_ID)
              .setName("port")
              .setBitwidth(0) // sdn_string translation — bitwidth 0
          )
      )
      .build()

  /** P4info with tables using bit<1>, bit<7>, and bit<128> match fields for encoding edge cases. */
  private fun testP4InfoWithBitwidths(): P4InfoOuterClass.P4Info =
    P4InfoOuterClass.P4Info.newBuilder()
      .addTables(
        P4InfoOuterClass.Table.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(BIT1_TABLE_ID).setName("bit1_table")
          )
          .addMatchFields(
            P4InfoOuterClass.MatchField.newBuilder()
              .setId(BIT1_FIELD_ID)
              .setName("f_bit1")
              .setBitwidth(1)
              .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
          )
          .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(ACTION_ID))
      )
      .addTables(
        P4InfoOuterClass.Table.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(BIT7_TABLE_ID).setName("bit7_table")
          )
          .addMatchFields(
            P4InfoOuterClass.MatchField.newBuilder()
              .setId(BIT7_FIELD_ID)
              .setName("f_bit7")
              .setBitwidth(7)
              .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
          )
          .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(ACTION_ID))
      )
      .addTables(
        P4InfoOuterClass.Table.newBuilder()
          .setPreamble(
            P4InfoOuterClass.Preamble.newBuilder().setId(BIT128_TABLE_ID).setName("bit128_table")
          )
          .addMatchFields(
            P4InfoOuterClass.MatchField.newBuilder()
              .setId(BIT128_FIELD_ID)
              .setName("f_bit128")
              .setBitwidth(128)
              .setMatchType(P4InfoOuterClass.MatchField.MatchType.EXACT)
          )
          .addActionRefs(P4InfoOuterClass.ActionRef.newBuilder().setId(ACTION_ID))
      )
      .addActions(
        P4InfoOuterClass.Action.newBuilder()
          .setPreamble(P4InfoOuterClass.Preamble.newBuilder().setId(ACTION_ID).setName("forward"))
          .addParams(
            P4InfoOuterClass.Action.Param.newBuilder()
              .setId(PARAM_ID)
              .setName("port")
              .setBitwidth(PARAM_BITWIDTH)
          )
      )
      .build()

  // =========================================================================
  // Proto builders
  // =========================================================================

  private fun bytes(len: Int): ByteArray = ByteArray(len)

  private fun exactMatch(
    fieldId: Int = MATCH_FIELD_ID,
    value: ByteArray,
  ): P4RuntimeOuterClass.FieldMatch =
    P4RuntimeOuterClass.FieldMatch.newBuilder()
      .setFieldId(fieldId)
      .setExact(
        P4RuntimeOuterClass.FieldMatch.Exact.newBuilder().setValue(ByteString.copyFrom(value))
      )
      .build()

  private fun ternaryMatch(
    fieldId: Int = TERNARY_FIELD_ID,
    value: ByteArray,
    mask: ByteArray = value,
  ): P4RuntimeOuterClass.FieldMatch =
    P4RuntimeOuterClass.FieldMatch.newBuilder()
      .setFieldId(fieldId)
      .setTernary(
        P4RuntimeOuterClass.FieldMatch.Ternary.newBuilder()
          .setValue(ByteString.copyFrom(value))
          .setMask(ByteString.copyFrom(mask))
      )
      .build()

  private fun lpmMatch(
    fieldId: Int,
    value: ByteArray,
    prefixLen: Int,
  ): P4RuntimeOuterClass.FieldMatch =
    P4RuntimeOuterClass.FieldMatch.newBuilder()
      .setFieldId(fieldId)
      .setLpm(
        P4RuntimeOuterClass.FieldMatch.LPM.newBuilder()
          .setValue(ByteString.copyFrom(value))
          .setPrefixLen(prefixLen)
      )
      .build()

  private fun rangeMatch(
    fieldId: Int = RANGE_FIELD_ID,
    low: ByteArray,
    high: ByteArray,
  ): P4RuntimeOuterClass.FieldMatch =
    P4RuntimeOuterClass.FieldMatch.newBuilder()
      .setFieldId(fieldId)
      .setRange(
        P4RuntimeOuterClass.FieldMatch.Range.newBuilder()
          .setLow(ByteString.copyFrom(low))
          .setHigh(ByteString.copyFrom(high))
      )
      .build()

  private fun optionalMatch(
    fieldId: Int = OPTIONAL_FIELD_ID,
    value: ByteArray,
  ): P4RuntimeOuterClass.FieldMatch =
    P4RuntimeOuterClass.FieldMatch.newBuilder()
      .setFieldId(fieldId)
      .setOptional(
        P4RuntimeOuterClass.FieldMatch.Optional.newBuilder().setValue(ByteString.copyFrom(value))
      )
      .build()

  private fun param(
    paramId: Int = PARAM_ID,
    value: ByteArray = bytes(PARAM_BITWIDTH / 8),
  ): P4RuntimeOuterClass.Action.Param =
    P4RuntimeOuterClass.Action.Param.newBuilder()
      .setParamId(paramId)
      .setValue(ByteString.copyFrom(value))
      .build()

  private fun action(
    actionId: Int = ACTION_ID,
    params: List<P4RuntimeOuterClass.Action.Param> = listOf(param()),
  ): P4RuntimeOuterClass.Action =
    P4RuntimeOuterClass.Action.newBuilder().setActionId(actionId).addAllParams(params).build()

  private fun ternaryAction(): P4RuntimeOuterClass.Action =
    P4RuntimeOuterClass.Action.newBuilder().setActionId(TERNARY_ACTION_ID).build()

  private fun tableUpdate(
    type: P4RuntimeOuterClass.Update.Type,
    tableId: Int,
    matches: List<P4RuntimeOuterClass.FieldMatch> = emptyList(),
    action: P4RuntimeOuterClass.Action? = null,
    priority: Int = 0,
    isDefaultAction: Boolean = false,
  ): P4RuntimeOuterClass.Update {
    val entry =
      P4RuntimeOuterClass.TableEntry.newBuilder()
        .setTableId(tableId)
        .addAllMatch(matches)
        .setPriority(priority)
        .setIsDefaultAction(isDefaultAction)
    if (action != null) {
      entry.setAction(P4RuntimeOuterClass.TableAction.newBuilder().setAction(action))
    }
    return P4RuntimeOuterClass.Update.newBuilder()
      .setType(type)
      .setEntity(P4RuntimeOuterClass.Entity.newBuilder().setTableEntry(entry))
      .build()
  }

  /** Convenience wrapper — most tests use INSERT. */
  private fun insertUpdate(
    tableId: Int,
    match: P4RuntimeOuterClass.FieldMatch,
    action: P4RuntimeOuterClass.Action,
    priority: Int = 0,
  ): P4RuntimeOuterClass.Update =
    tableUpdate(P4RuntimeOuterClass.Update.Type.INSERT, tableId, listOf(match), action, priority)

  private fun insertUpdate(
    tableId: Int,
    matches: List<P4RuntimeOuterClass.FieldMatch>,
    action: P4RuntimeOuterClass.Action,
    priority: Int = 0,
  ): P4RuntimeOuterClass.Update =
    tableUpdate(P4RuntimeOuterClass.Update.Type.INSERT, tableId, matches, action, priority)

  private fun memberUpdate(
    type: P4RuntimeOuterClass.Update.Type,
    action: P4RuntimeOuterClass.Action,
    profileId: Int = ACTION_PROFILE_ID,
    memberId: Int = 1,
  ): P4RuntimeOuterClass.Update =
    P4RuntimeOuterClass.Update.newBuilder()
      .setType(type)
      .setEntity(
        P4RuntimeOuterClass.Entity.newBuilder()
          .setActionProfileMember(
            P4RuntimeOuterClass.ActionProfileMember.newBuilder()
              .setActionProfileId(profileId)
              .setMemberId(memberId)
              .setAction(action)
          )
      )
      .build()

  private fun groupUpdate(
    type: P4RuntimeOuterClass.Update.Type,
    profileId: Int = ACTION_PROFILE_ID,
    groupId: Int = 1,
  ): P4RuntimeOuterClass.Update =
    P4RuntimeOuterClass.Update.newBuilder()
      .setType(type)
      .setEntity(
        P4RuntimeOuterClass.Entity.newBuilder()
          .setActionProfileGroup(
            P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
              .setActionProfileId(profileId)
              .setGroupId(groupId)
          )
      )
      .build()

  /** Builds an INSERT update with a one-shot ActionProfileActionSet. */
  private fun oneShotUpdate(
    tableId: Int,
    match: P4RuntimeOuterClass.FieldMatch,
    vararg actions: P4RuntimeOuterClass.Action,
  ): P4RuntimeOuterClass.Update {
    val entry =
      P4RuntimeOuterClass.TableEntry.newBuilder()
        .setTableId(tableId)
        .addMatch(match)
        .setAction(
          P4RuntimeOuterClass.TableAction.newBuilder()
            .setActionProfileActionSet(
              P4RuntimeOuterClass.ActionProfileActionSet.newBuilder()
                .addAllActionProfileActions(
                  actions.map { action ->
                    P4RuntimeOuterClass.ActionProfileAction.newBuilder()
                      .setAction(action)
                      .setWeight(1)
                      .build()
                  }
                )
            )
        )
    return P4RuntimeOuterClass.Update.newBuilder()
      .setType(P4RuntimeOuterClass.Update.Type.INSERT)
      .setEntity(P4RuntimeOuterClass.Entity.newBuilder().setTableEntry(entry))
      .build()
  }
}
