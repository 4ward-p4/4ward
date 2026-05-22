package fourward.grpc

import com.google.protobuf.ByteString
import io.grpc.Status
import io.grpc.StatusException
import p4.v1.P4RuntimeOuterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Spec-conformance tests for [requireFitsInBitwidth] and [canonicalize].
 *
 * The two parameterized tables ([validEncodings], [invalidEncodings]) mirror Tables 4 and 5 of the
 * P4Runtime specification §8.3 verbatim, so this file is the long-term anchor that keeps 4ward's
 * bytestring handling tied to the normative tables.
 *
 * Spec source: https://p4lang.github.io/p4runtime/spec/main/P4Runtime-Spec.html#sec-bytestrings
 */
class BytestringsTest {

  // Each row mirrors P4Runtime spec §8.3, Table 4 ("Examples of Valid Bytestring Encoding").
  // The 4ward p4runtime layer only handles unsigned bit<W> on the wire, so int<W> rows are
  // omitted (they belong to a future P4Data §8.4.3 implementation).
  private val validUnsignedEncodings: List<EncodingExample> =
    listOf(
      EncodingExample(bitwidth = 8, value = "\\x63", canonical = true),
      EncodingExample(bitwidth = 16, value = "\\x00\\x63", canonical = false),
      EncodingExample(bitwidth = 16, value = "\\x63", canonical = true),
      EncodingExample(bitwidth = 16, value = "\\x30\\x64", canonical = true),
      EncodingExample(bitwidth = 16, value = "\\x00\\x30\\x64", canonical = false),
      EncodingExample(bitwidth = 12, value = "\\x00\\x63", canonical = false),
      EncodingExample(bitwidth = 12, value = "\\x63", canonical = true),
      EncodingExample(bitwidth = 12, value = "\\x00\\x00\\x63", canonical = false),
    )

  // Each row mirrors P4Runtime spec §8.3, Table 5 ("Examples of Invalid Bytestring Encoding").
  // int<W> rows omitted; see comment on [validUnsignedEncodings].
  private val invalidUnsignedEncodings: List<InvalidExample> =
    listOf(
      InvalidExample(bitwidth = 8, value = "\\x01\\x63"),
      InvalidExample(bitwidth = 8, value = ""),
      InvalidExample(bitwidth = 16, value = "\\x01\\x00\\x63"),
      InvalidExample(bitwidth = 12, value = "\\x10\\x63"),
      InvalidExample(bitwidth = 12, value = "\\x01\\x00\\x63"),
      InvalidExample(bitwidth = 12, value = "\\x00\\x40\\x63"),
    )

  // ---------------------------------------------------------------------------
  // requireFitsInBitwidth — spec table 4 (must accept) and table 5 (must reject)
  // ---------------------------------------------------------------------------

  @Test
  fun `accepts every encoding in spec table 4`() {
    for (row in validUnsignedEncodings) {
      requireFitsInBitwidth(row.bytes, row.bitwidth, "table-4 row ${row.value}")
    }
  }

  @Test
  fun `rejects every encoding in spec table 5 with OUT_OF_RANGE`() {
    for (row in invalidUnsignedEncodings) {
      val e =
        assertThrows("table-5 row ${row.value} should be rejected", StatusException::class.java) {
          requireFitsInBitwidth(row.bytes, row.bitwidth, "table-5 row ${row.value}")
        }
      assertEquals(
        "table-5 row ${row.value} should map to OUT_OF_RANGE",
        Status.Code.OUT_OF_RANGE,
        e.status.code,
      )
    }
  }

  @Test
  fun `bitwidth 0 marks a non-integer field — accepts any value including empty`() {
    // Non-integer (string-typed) fields use bitwidth=0 in p4info; §8.3 does not apply.
    requireFitsInBitwidth(bytes("\\x01\\x02\\x03\\x04"), bitwidth = 0, "param")
    requireFitsInBitwidth(ByteString.EMPTY, bitwidth = 0, "param")
  }

  // ---------------------------------------------------------------------------
  // canonicalize — produces the spec's "shortest string that fits"
  // ---------------------------------------------------------------------------

  @Test
  fun `canonicalize produces shortest form on every spec-table-4 row`() {
    // Group rows by bitwidth-of-value so we can compare canonical pairs against non-canonical
    // pairs that encode the same integer value.
    val byInteger = validUnsignedEncodings.groupBy { it.canonicalBytes }
    for ((expectedCanonical, group) in byInteger) {
      for (row in group) {
        assertEquals(
          "canonicalize should turn ${row.value} into the canonical form",
          expectedCanonical,
          canonicalize(row.bytes),
        )
      }
    }
  }

  @Test
  fun `canonicalize is idempotent`() {
    for (row in validUnsignedEncodings) {
      val once = canonicalize(row.bytes)
      val twice = canonicalize(once)
      assertEquals("canonicalize should be idempotent", once, twice)
    }
  }

  @Test
  fun `canonicalize preserves zero as a single zero byte`() {
    assertEquals(bytes("\\x00"), canonicalize(bytes("\\x00")))
    assertEquals(bytes("\\x00"), canonicalize(bytes("\\x00\\x00")))
    assertEquals(bytes("\\x00"), canonicalize(bytes("\\x00\\x00\\x00")))
  }

  @Test
  fun `canonicalize returns the same instance when input is already canonical`() {
    val canonical = bytes("\\x63")
    assertSame("no copy when no change is needed", canonical, canonicalize(canonical))
  }

  @Test
  fun `canonicalize does not strip leading bytes that are non-zero`() {
    val input = bytes("\\x01\\x00")
    assertEquals(input, canonicalize(input))
  }

  @Test
  fun `every padded encoding canonicalizes to the same bytestring as the minimum form`() {
    // Property test: for a sweep of unsigned values across several common bitwidths, every
    // padding length up to bitwidth/8 + 1 collapses to the same canonical bytestring. This is
    // the wire-side guarantee that powers TableStore.sameKey: equal logical values must equal
    // post-canonicalization, regardless of how the client chose to encode them.
    val values =
      listOf(
        java.math.BigInteger.ZERO,
        java.math.BigInteger.ONE,
        java.math.BigInteger.valueOf(127),
        java.math.BigInteger.valueOf(0x100),
        java.math.BigInteger.valueOf(0xFFFF),
        java.math.BigInteger.valueOf(0x123456789ABCDEFL),
        java.math.BigInteger.ONE.shiftLeft(127), // 128-bit boundary
      )
    for (v in values) {
      val canonical = encodeMinimum(v)
      for (paddingBytes in 0..16) {
        val padded = padTo(canonical, canonical.size() + paddingBytes)
        assertEquals(
          "value $v, padded by $paddingBytes byte(s), should canonicalize identically",
          canonical,
          canonicalize(padded),
        )
      }
    }
  }

  @Test
  fun `canonicalize does not change the represented integer value`() {
    // Round-trip: padded input → canonicalize → re-pad → original value.
    for (row in validUnsignedEncodings) {
      val canonical = canonicalize(row.bytes)
      assertEquals(
        "canonicalize must preserve value for ${row.value}",
        row.canonicalBytes,
        canonical,
      )
      // And distinct values stay distinct.
      if (!row.canonical) {
        assertNotEquals(
          "non-canonical input should not equal its canonical form",
          row.bytes,
          canonical,
        )
      }
    }
  }

  // ---------------------------------------------------------------------------
  // canonicalizeMatch — RANGE and OPTIONAL branches
  // ---------------------------------------------------------------------------

  @Test
  fun `canonicalizeMatch strips leading zeros from RANGE low and high`() {
    val match =
      P4RuntimeOuterClass.FieldMatch.newBuilder()
        .setFieldId(1)
        .setRange(
          P4RuntimeOuterClass.FieldMatch.Range.newBuilder()
            .setLow(bytes("\\x00\\x0A"))
            .setHigh(bytes("\\x00\\xFF"))
        )
        .build()

    val result = canonicalizeMatch(match)

    assertEquals(bytes("\\x0A"), result.range.low)
    assertEquals(bytes("\\xFF"), result.range.high)
  }

  @Test
  fun `canonicalizeMatch returns same instance for already-canonical RANGE`() {
    val match =
      P4RuntimeOuterClass.FieldMatch.newBuilder()
        .setFieldId(1)
        .setRange(
          P4RuntimeOuterClass.FieldMatch.Range.newBuilder()
            .setLow(bytes("\\x0A"))
            .setHigh(bytes("\\xFF"))
        )
        .build()

    assertSame(match, canonicalizeMatch(match))
  }

  @Test
  fun `canonicalizeMatch strips leading zeros from OPTIONAL value`() {
    val match =
      P4RuntimeOuterClass.FieldMatch.newBuilder()
        .setFieldId(1)
        .setOptional(
          P4RuntimeOuterClass.FieldMatch.Optional.newBuilder().setValue(bytes("\\x00\\x42"))
        )
        .build()

    val result = canonicalizeMatch(match)

    assertEquals(bytes("\\x42"), result.optional.value)
  }

  @Test
  fun `canonicalizeMatch returns same instance for already-canonical OPTIONAL`() {
    val match =
      P4RuntimeOuterClass.FieldMatch.newBuilder()
        .setFieldId(1)
        .setOptional(P4RuntimeOuterClass.FieldMatch.Optional.newBuilder().setValue(bytes("\\x42")))
        .build()

    assertSame(match, canonicalizeMatch(match))
  }

  // ---------------------------------------------------------------------------
  // canonicalizeTableAction — ACTION_PROFILE_ACTION_SET branch
  // ---------------------------------------------------------------------------

  @Test
  fun `canonicalizeBytestrings canonicalizes action profile action set params`() {
    val actionSet =
      P4RuntimeOuterClass.ActionProfileActionSet.newBuilder()
        .addActionProfileActions(
          P4RuntimeOuterClass.ActionProfileAction.newBuilder()
            .setAction(
              P4RuntimeOuterClass.Action.newBuilder()
                .setActionId(1)
                .addParams(
                  P4RuntimeOuterClass.Action.Param.newBuilder()
                    .setParamId(1)
                    .setValue(bytes("\\x00\\x0A"))
                )
            )
            .setWeight(1)
        )
        .build()

    val entry =
      P4RuntimeOuterClass.TableEntry.newBuilder()
        .setTableId(1)
        .setAction(
          P4RuntimeOuterClass.TableAction.newBuilder().setActionProfileActionSet(actionSet)
        )
        .build()

    val update =
      P4RuntimeOuterClass.Update.newBuilder()
        .setEntity(P4RuntimeOuterClass.Entity.newBuilder().setTableEntry(entry))
        .build()

    val result = canonicalizeBytestrings(update)
    val resultParam =
      result.entity.tableEntry.action.actionProfileActionSet
        .getActionProfileActions(0)
        .action
        .getParams(0)

    assertEquals(bytes("\\x0A"), resultParam.value)
  }

  @Test
  fun `canonicalizeBytestrings returns same instance for already-canonical action set`() {
    val actionSet =
      P4RuntimeOuterClass.ActionProfileActionSet.newBuilder()
        .addActionProfileActions(
          P4RuntimeOuterClass.ActionProfileAction.newBuilder()
            .setAction(
              P4RuntimeOuterClass.Action.newBuilder()
                .setActionId(1)
                .addParams(
                  P4RuntimeOuterClass.Action.Param.newBuilder()
                    .setParamId(1)
                    .setValue(bytes("\\x0A"))
                )
            )
            .setWeight(1)
        )
        .build()

    val entry =
      P4RuntimeOuterClass.TableEntry.newBuilder()
        .setTableId(1)
        .setAction(
          P4RuntimeOuterClass.TableAction.newBuilder().setActionProfileActionSet(actionSet)
        )
        .build()

    val update =
      P4RuntimeOuterClass.Update.newBuilder()
        .setEntity(P4RuntimeOuterClass.Entity.newBuilder().setTableEntry(entry))
        .build()

    assertSame(update, canonicalizeBytestrings(update))
  }

  // ---------------------------------------------------------------------------
  // Test fixtures
  // ---------------------------------------------------------------------------

  /** A row from spec §8.3, Table 4: bitwidth, encoding, and whether it is the canonical form. */
  private data class EncodingExample(val bitwidth: Int, val value: String, val canonical: Boolean) {
    val bytes: ByteString = bytes(value)
    /** The canonical (shortest) form of [bytes] — strip leading zero bytes, leave one for zero. */
    val canonicalBytes: ByteString = canonicalize(bytes)
  }

  /** A row from spec §8.3, Table 5: bitwidth and an encoding that must be rejected. */
  private data class InvalidExample(val bitwidth: Int, val value: String) {
    val bytes: ByteString = bytes(value)
  }
}

/**
 * Encodes [value] (assumed non-negative) as the minimum-width unsigned big-endian bytestring. Used
 * in tests as the spec's reference implementation of "shortest string that fits".
 */
internal fun encodeMinimum(value: java.math.BigInteger): ByteString {
  require(value.signum() >= 0) { "expected non-negative, got $value" }
  if (value.signum() == 0) return ByteString.copyFrom(byteArrayOf(0))
  val arr = value.toByteArray()
  // BigInteger.toByteArray() prepends a sign bit byte for positive values whose high bit is set;
  // strip it so the output stays canonical (unsigned, shortest).
  return if (arr[0] == 0.toByte() && arr.size > 1) ByteString.copyFrom(arr, 1, arr.size - 1)
  else ByteString.copyFrom(arr)
}

/** Pads [value] (canonical form) to exactly [targetSize] bytes with leading zeros. */
internal fun padTo(value: ByteString, targetSize: Int): ByteString {
  if (value.size() >= targetSize) return value
  val padding = ByteArray(targetSize - value.size())
  return ByteString.copyFrom(padding).concat(value)
}

/** Decodes a backslash-x-style spec literal (e.g. `\x00\x63`) to a [ByteString]. */
internal fun bytes(literal: String): ByteString {
  if (literal.isEmpty()) return ByteString.EMPTY
  val out = mutableListOf<Byte>()
  var i = 0
  while (i < literal.length) {
    require(literal[i] == '\\' && i + 3 < literal.length && literal[i + 1] == 'x') {
      "expected \\xHH literal at position $i in '$literal'"
    }
    val hi = Character.digit(literal[i + 2], 16)
    val lo = Character.digit(literal[i + 3], 16)
    require(hi >= 0 && lo >= 0) { "invalid hex digit at position $i in '$literal'" }
    out.add(((hi shl 4) or lo).toByte())
    i += 4
  }
  return ByteString.copyFrom(out.toByteArray())
}
