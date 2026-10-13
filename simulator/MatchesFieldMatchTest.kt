package fourward.simulator

import com.google.protobuf.ByteString
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.p4.v1.FieldMatch

/** Unit tests for [matchesFieldMatch]. */
class MatchesFieldMatchTest {

  // ---------------------------------------------------------------------------
  // Exact
  // ---------------------------------------------------------------------------

  @Test
  fun `exact match succeeds on equal value`() {
    assertTrue(matchesFieldMatch(bits(0xAB, 8), exactMatch(0xAB)))
  }

  @Test
  fun `exact match fails on different value`() {
    assertFalse(matchesFieldMatch(bits(0xAB, 8), exactMatch(0xCD)))
  }

  // ---------------------------------------------------------------------------
  // Ternary
  // ---------------------------------------------------------------------------

  @Test
  fun `ternary match succeeds when masked bits equal`() {
    // value 0xA0, mask 0xF0 → matches 0xA0..0xAF.
    assertTrue(matchesFieldMatch(bits(0xA5, 8), ternaryMatch(0xA0, 0xF0)))
  }

  @Test
  fun `ternary match fails when masked bits differ`() {
    assertFalse(matchesFieldMatch(bits(0xB5, 8), ternaryMatch(0xA0, 0xF0)))
  }

  @Test
  fun `ternary match with zero mask matches anything`() {
    assertTrue(matchesFieldMatch(bits(0xFF, 8), ternaryMatch(0x00, 0x00)))
  }

  // ---------------------------------------------------------------------------
  // LPM
  // ---------------------------------------------------------------------------

  @Test
  fun `LPM match succeeds when prefix matches`() {
    // 10.0.0.0/8 matches 10.1.2.3.
    assertTrue(matchesFieldMatch(bits(0x0A010203, 32), lpmMatch(0x0A000000, 8)))
  }

  @Test
  fun `LPM match fails when prefix differs`() {
    // 10.0.0.0/8 does not match 11.1.2.3.
    assertFalse(matchesFieldMatch(bits(0x0B010203, 32), lpmMatch(0x0A000000, 8)))
  }

  @Test
  fun `LPM with prefixLen 0 matches anything`() {
    assertTrue(matchesFieldMatch(bits(0xFFFFFFFF, 32), lpmMatch(0x00000000, 0)))
  }

  @Test
  fun `LPM with full prefixLen is exact`() {
    assertTrue(matchesFieldMatch(bits(0xAB, 8), lpmMatch(0xAB, 8)))
    assertFalse(matchesFieldMatch(bits(0xAC, 8), lpmMatch(0xAB, 8)))
  }

  // ---------------------------------------------------------------------------
  // Range
  // ---------------------------------------------------------------------------

  @Test
  fun `range match succeeds when value is in range`() {
    assertTrue(matchesFieldMatch(bits(50, 8), rangeMatch(10, 100)))
  }

  @Test
  fun `range match succeeds at boundaries`() {
    assertTrue(matchesFieldMatch(bits(10, 8), rangeMatch(10, 100)))
    assertTrue(matchesFieldMatch(bits(100, 8), rangeMatch(10, 100)))
  }

  @Test
  fun `range match fails when value is out of range`() {
    assertFalse(matchesFieldMatch(bits(9, 8), rangeMatch(10, 100)))
    assertFalse(matchesFieldMatch(bits(101, 8), rangeMatch(10, 100)))
  }

  // ---------------------------------------------------------------------------
  // Optional
  // ---------------------------------------------------------------------------

  @Test
  fun `optional match succeeds on equal value`() {
    assertTrue(matchesFieldMatch(bits(0x42, 8), optionalMatch(0x42)))
  }

  @Test
  fun `optional match fails on different value`() {
    assertFalse(matchesFieldMatch(bits(0x42, 8), optionalMatch(0x43)))
  }

  // ---------------------------------------------------------------------------
  // Wildcard (unset FieldMatch)
  // ---------------------------------------------------------------------------

  @Test
  fun `unset field match is wildcard`() {
    assertTrue(matchesFieldMatch(bits(0xFF, 8), FieldMatch.getDefaultInstance()))
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun bits(value: Long, width: Int) = BitVector.ofLong(value, width)

  private fun bytes(value: Long): ByteString {
    // Encode as minimal big-endian byte string (at least 1 byte).
    val hex = value.toString(16).let { if (it.length % 2 != 0) "0$it" else it }
    return ByteString.copyFrom(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
  }

  private fun exactMatch(value: Long): FieldMatch =
    FieldMatch.newBuilder().setExact(FieldMatch.Exact.newBuilder().setValue(bytes(value))).build()

  private fun ternaryMatch(value: Long, mask: Long): FieldMatch =
    FieldMatch.newBuilder()
      .setTernary(FieldMatch.Ternary.newBuilder().setValue(bytes(value)).setMask(bytes(mask)))
      .build()

  private fun lpmMatch(value: Long, prefixLen: Int): FieldMatch =
    FieldMatch.newBuilder()
      .setLpm(FieldMatch.LPM.newBuilder().setValue(bytes(value)).setPrefixLen(prefixLen))
      .build()

  private fun rangeMatch(low: Long, high: Long): FieldMatch =
    FieldMatch.newBuilder()
      .setRange(FieldMatch.Range.newBuilder().setLow(bytes(low)).setHigh(bytes(high)))
      .build()

  private fun optionalMatch(value: Long): FieldMatch =
    FieldMatch.newBuilder()
      .setOptional(FieldMatch.Optional.newBuilder().setValue(bytes(value)))
      .build()
}
