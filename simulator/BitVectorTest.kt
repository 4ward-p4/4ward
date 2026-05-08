package fourward.simulator

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BitVectorTest {

  @Test
  fun `addition wraps at width boundary`() {
    val a = BitVector.ofInt(0xFF, 8)
    val b = BitVector.ofInt(1, 8)
    assertEquals(BitVector.ofInt(0, 8), a + b)
  }

  @Test
  fun `saturating addition clamps to max`() {
    val a = BitVector.ofInt(0xFE, 8)
    val b = BitVector.ofInt(5, 8)
    assertEquals(BitVector.ofInt(0xFF, 8), a.addSat(b))
  }

  @Test
  fun `saturating subtraction clamps to zero`() {
    val a = BitVector.ofInt(2, 8)
    val b = BitVector.ofInt(5, 8)
    assertEquals(BitVector.ofInt(0, 8), a.subSat(b))
  }

  @Test
  fun `slice extracts correct bits`() {
    val v = BitVector(BigInteger.valueOf(0xABCDL), 16)
    // bits [11:8] of 0xABCD = 0xB
    assertEquals(BitVector.ofInt(0xB, 4), v.slice(11, 8))
  }

  @Test
  fun `concat produces correct width and value`() {
    val hi = BitVector.ofInt(0xAB, 8)
    val lo = BitVector.ofInt(0xCD, 8)
    assertEquals(BitVector(BigInteger.valueOf(0xABCDL), 16), hi.concat(lo))
  }

  @Test
  fun `toByteArray produces big-endian bytes`() {
    val v = BitVector(BigInteger.valueOf(0xABCDL), 16)
    assertEquals(listOf(0xAB.toByte(), 0xCD.toByte()), v.toByteArray().toList())
  }

  @Test
  fun `ofBytes round-trips with toByteArray`() {
    val original = BitVector(BigInteger.valueOf(0x1234L), 16)
    val bytes = original.toByteArray()
    assertEquals(original, BitVector.ofBytes(bytes, 16))
  }

  @Test
  fun `width mismatch throws on arithmetic`() {
    val a = BitVector.ofInt(1, 8)
    val b = BitVector.ofInt(1, 16)
    assertThrows(IllegalArgumentException::class.java) { a + b }
  }

  // ---------------------------------------------------------------------------
  // Long-backed storage: narrow/wide dispatch, ofLong factory, equals/hashCode
  // ---------------------------------------------------------------------------

  @Test
  fun `ofLong constructs without BigInteger allocation`() {
    val v = BitVector.ofLong(42, 8)
    assertEquals(42L, v.longValue)
    assertEquals(8, v.width)
    // Lazy BigInteger reconstruction on access:
    assertEquals(BigInteger.valueOf(42), v.value)
  }

  @Test
  fun `ofLong rejects negative values`() {
    assertThrows(IllegalArgumentException::class.java) { BitVector.ofLong(-1, 8) }
  }

  @Test
  fun `ofLong rejects values that exceed width`() {
    assertThrows(IllegalArgumentException::class.java) { BitVector.ofLong(256, 8) }
  }

  @Test
  fun `narrow and BigInteger-constructed BitVectors are equal`() {
    val fromLong = BitVector.ofLong(0xFF, 8)
    val fromBigInt = BitVector(BigInteger.valueOf(0xFF), 8)
    assertEquals(fromBigInt, fromLong)
    assertEquals(fromLong, fromBigInt)
    assertEquals(fromBigInt.hashCode(), fromLong.hashCode())
  }

  @Test
  fun `wide BitVector (width 128) round-trips correctly`() {
    val big = BigInteger("FF020000000000000000000000010008", 16)
    val v = BitVector(big, 128)
    assertEquals(big, v.value)
    assertEquals(0L, v.longValue) // longValue is meaningless for wide
    assertEquals(128, v.width)
  }

  @Test
  fun `wide arithmetic works for width 128`() {
    val a = BitVector(BigInteger.ONE.shiftLeft(127), 128)
    val b = BitVector(BigInteger.ONE, 128)
    val sum = a + b
    assertEquals(BigInteger.ONE.shiftLeft(127).add(BigInteger.ONE), sum.value)
  }

  @Test
  fun `narrow boundary, width 63 is narrow and width 64 is wide`() {
    val narrow = BitVector.ofLong(Long.MAX_VALUE, 63) // 2^63 - 1
    assertEquals(Long.MAX_VALUE, narrow.longValue)

    val wide = BitVector(BigInteger.ONE.shiftLeft(63), 64) // 2^63 — too big for Long
    assertEquals(0L, wide.longValue) // wide: longValue is 0
    assertEquals(BigInteger.ONE.shiftLeft(63), wide.value)
  }

  @Test
  fun `subtraction underflow wraps correctly in narrow path`() {
    // 0 - 1 in 8 bits = 255 (unsigned wrap via masking)
    val zero = BitVector.ofLong(0, 8)
    val one = BitVector.ofLong(1, 8)
    assertEquals(BitVector.ofLong(255, 8), zero - one)
  }

  @Test
  fun `narrow bitwise ops preserve width`() {
    val a = BitVector.ofLong(0xAA, 8)
    val b = BitVector.ofLong(0x55, 8)
    assertEquals(BitVector.ofLong(0x00, 8), a and b)
    assertEquals(BitVector.ofLong(0xFF, 8), a or b)
    assertEquals(BitVector.ofLong(0xFF, 8), a xor b)
    assertEquals(BitVector.ofLong(0x55, 8), a.inv())
  }

  @Test
  fun `narrow shifts`() {
    val v = BitVector.ofLong(0x0F, 8)
    assertEquals(BitVector.ofLong(0xF0, 8), v.shl(4))
    assertEquals(BitVector.ofLong(0x00, 8), v.shr(4))
    // Shift that would overflow width is masked:
    assertEquals(BitVector.ofLong(0xE0, 8), BitVector.ofLong(0xFF, 8).shl(5))
  }

  // ---------------------------------------------------------------------------
  // SignedBitVector
  // ---------------------------------------------------------------------------

  @Test
  fun `signed bit vector sign-extends correctly`() {
    // 0b10000000 = 128 unsigned = -128 signed in 8 bits
    val s = SignedBitVector.fromUnsignedBits(BigInteger.valueOf(128), 8)
    assertEquals(BigInteger.valueOf(-128), s.value)
  }

  @Test
  fun `signed to unsigned reinterprets bits`() {
    val s = SignedBitVector(BigInteger.valueOf(-1), 8)
    assertEquals(BitVector.ofInt(0xFF, 8), s.toUnsigned())
  }

  @Test
  fun `signed saturating addition clamps to max`() {
    // int<16>: 32766 |+| 10 = 32767 (clamped from 32776)
    val a = SignedBitVector(BigInteger.valueOf(32766), 16)
    val b = SignedBitVector(BigInteger.valueOf(10), 16)
    assertEquals(SignedBitVector(BigInteger.valueOf(32767), 16), a.addSat(b))
  }

  @Test
  fun `signed saturating subtraction clamps to min`() {
    // int<16>: -32766 |-| 10 = -32768 (clamped from -32776)
    val a = SignedBitVector(BigInteger.valueOf(-32766), 16)
    val b = SignedBitVector(BigInteger.valueOf(10), 16)
    assertEquals(SignedBitVector(BigInteger.valueOf(-32768), 16), a.subSat(b))
  }

  @Test
  fun `signed saturation no-ops within range`() {
    val a = SignedBitVector(BigInteger.valueOf(10), 16)
    val b = SignedBitVector(BigInteger.valueOf(20), 16)
    assertEquals(SignedBitVector(BigInteger.valueOf(30), 16), a.addSat(b))
    assertEquals(SignedBitVector(BigInteger.valueOf(-10), 16), a.subSat(b))
  }

  // ===========================================================================
  // StructVal.setBitField error context
  // ===========================================================================

  @Test
  fun `setBitField overflow error includes type and field name`() {
    val s = StructVal("standard_metadata_t", mutableMapOf("ingress_port" to BitVal(0L, 8)))
    val e =
      assertThrows(IllegalArgumentException::class.java) { s.setBitField("ingress_port", 4095) }
    assert(e.message!!.contains("standard_metadata_t.ingress_port")) {
      "expected type-qualified field name, got: ${e.message}"
    }
  }
}
