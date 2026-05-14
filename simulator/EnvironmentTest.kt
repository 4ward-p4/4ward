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

import fourward.MarkToDropEvent
import fourward.TraceEvent
import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Unit tests for [Environment] (variable scoping) and [PacketContext] (packet buffer, output). */
class EnvironmentTest {

  // ---------------------------------------------------------------------------
  // Variable bindings
  // ---------------------------------------------------------------------------

  @Test
  fun `lookup returns null for undefined name`() {
    val env = Environment()
    assertNull(env.lookup("x"))
  }

  @Test
  fun `define and lookup in same scope`() {
    val env = Environment()
    env.define("x", BitVal(7, 8))
    assertEquals(BitVal(7, 8), env.lookup("x"))
  }

  @Test
  fun `inner scope shadows outer binding`() {
    val env = Environment()
    env.define("x", BitVal(1, 8))
    env.pushScope()
    env.define("x", BitVal(99, 8))
    assertEquals(BitVal(99, 8), env.lookup("x"))
    env.popScope()
    assertEquals(BitVal(1, 8), env.lookup("x"))
  }

  @Test
  fun `inner scope variable is not visible after popScope`() {
    val env = Environment()
    env.pushScope()
    env.define("inner", BitVal(5, 8))
    env.popScope()
    assertNull(env.lookup("inner"))
  }

  @Test
  fun `update modifies variable in the nearest enclosing scope`() {
    val env = Environment()
    env.define("x", BitVal(1, 8))
    env.pushScope()
    env.update("x", BitVal(2, 8))
    assertEquals(BitVal(2, 8), env.lookup("x"))
    env.popScope()
    assertEquals(BitVal(2, 8), env.lookup("x"))
  }

  @Test
  fun `update throws for undefined variable`() {
    val env = Environment()
    assertThrows(IllegalStateException::class.java) { env.update("missing", BitVal(0, 8)) }
  }

  // ---------------------------------------------------------------------------
  // Packet buffer (input)
  // ---------------------------------------------------------------------------

  @Test
  fun `extractBytes reads exactly N bytes from the front of the packet`() {
    val pktCtx = PacketContext(byteArrayOf(0x01, 0x02, 0x03, 0x04))
    assertArrayEquals(byteArrayOf(0x01, 0x02), pktCtx.extractBytes(2))
  }

  @Test
  fun `extractBytes advances the cursor so the next call gets the next bytes`() {
    val pktCtx = PacketContext(byteArrayOf(0x01, 0x02, 0x03, 0x04))
    pktCtx.extractBytes(2)
    assertArrayEquals(byteArrayOf(0x03, 0x04), pktCtx.extractBytes(2))
  }

  @Test
  fun `drainRemainingInput returns bytes not yet extracted`() {
    val pktCtx = PacketContext(byteArrayOf(0x01, 0x02, 0x03, 0x04))
    pktCtx.extractBytes(1)
    assertArrayEquals(byteArrayOf(0x02, 0x03, 0x04), pktCtx.drainRemainingInput())
  }

  @Test
  fun `extractBytes throws when fewer bytes remain than requested`() {
    val pktCtx = PacketContext(byteArrayOf(0x01))
    assertThrows(PacketTooShortException::class.java) { pktCtx.extractBytes(2) }
  }

  // ---------------------------------------------------------------------------
  // Output buffer
  // ---------------------------------------------------------------------------

  @Test
  fun `outputPayload is empty before any emitBits call`() {
    val pktCtx = PacketContext(byteArrayOf())
    assertArrayEquals(byteArrayOf(), pktCtx.outputPayload())
  }

  @Test
  @Suppress("MagicNumber")
  fun `emitBits appends bits to the output buffer`() {
    val pktCtx = PacketContext(byteArrayOf())
    // 16 bits = 0x0800
    pktCtx.emitBits(BigInteger.valueOf(0x0800), 16)
    assertArrayEquals(byteArrayOf(0x08, 0x00), pktCtx.outputPayload())
  }

  @Test
  @Suppress("MagicNumber")
  fun `multiple emitBits calls form continuous bit stream`() {
    val pktCtx = PacketContext(byteArrayOf())
    // 6 bits (0b111111) ++ 10 bits (0b1010101010) → 0b11111110_10101010 = 0xFE 0xAA
    pktCtx.emitBits(BigInteger.valueOf(0x3F), 6)
    pktCtx.emitBits(BigInteger.valueOf(0x2AA), 10)
    assertArrayEquals(byteArrayOf(0xFE.toByte(), 0xAA.toByte()), pktCtx.outputPayload())
  }

  // ---------------------------------------------------------------------------
  // Trace events
  // ---------------------------------------------------------------------------

  @Test
  fun `getEvents returns empty list initially`() {
    val pktCtx = PacketContext(byteArrayOf())
    assertEquals(emptyList<TraceEvent>(), pktCtx.getEvents())
  }

  @Test
  fun `addTraceEvent records events in order`() {
    val pktCtx = PacketContext(byteArrayOf())
    val event1 = TraceEvent.newBuilder().setMarkToDrop(MarkToDropEvent.getDefaultInstance()).build()
    val event2 = TraceEvent.newBuilder().setMarkToDrop(MarkToDropEvent.getDefaultInstance()).build()
    pktCtx.addTraceEvent(event1)
    pktCtx.addTraceEvent(event2)
    assertEquals(listOf(event1, event2), pktCtx.getEvents())
  }

  @Test
  fun `getEvents returns a defensive copy`() {
    val pktCtx = PacketContext(byteArrayOf())
    val event = TraceEvent.newBuilder().setMarkToDrop(MarkToDropEvent.getDefaultInstance()).build()
    pktCtx.addTraceEvent(event)
    val first = pktCtx.getEvents()
    val second = pktCtx.getEvents()
    assertEquals(first, second)
    assertNotSame(first, second)
  }

  // ---------------------------------------------------------------------------
  // BitAccumulator
  // ---------------------------------------------------------------------------

  @Test
  fun `BitAccumulator empty toByteArray returns empty`() {
    val acc = BitAccumulator()
    assertArrayEquals(byteArrayOf(), acc.toByteArray())
  }

  @Test
  fun `BitAccumulator width-0 append is a no-op`() {
    val acc = BitAccumulator()
    acc.append(BigInteger.valueOf(0xFF), 0)
    assertArrayEquals(byteArrayOf(), acc.toByteArray())
  }

  @Test
  fun `BitAccumulator single-bit append`() {
    val acc = BitAccumulator()
    acc.append(BigInteger.ONE, 1)
    // 1 bit = 0b1 → padded to 0b10000000 = 0x80
    assertArrayEquals(byteArrayOf(0x80.toByte()), acc.toByteArray())
  }

  @Test
  @Suppress("MagicNumber")
  fun `BitAccumulator wide value greater than 64 bits`() {
    val acc = BitAccumulator()
    // 72-bit value: 0xFF repeated 9 times.
    val wide = BigInteger.ONE.shiftLeft(72).subtract(BigInteger.ONE)
    acc.append(wide, 72)
    val expected = ByteArray(9) { 0xFF.toByte() }
    assertArrayEquals(expected, acc.toByteArray())
  }

  @Test
  @Suppress("MagicNumber")
  fun `BitAccumulator toByteArray is idempotent`() {
    val acc = BitAccumulator()
    acc.append(BigInteger.valueOf(0x3F), 6)
    val first = acc.toByteArray()
    val second = acc.toByteArray()
    assertArrayEquals(first, second)
  }

  // ---------------------------------------------------------------------------
  // ParserCursor — bit-level reads
  // ---------------------------------------------------------------------------

  @Test
  @Suppress("MagicNumber")
  fun `extractBits extracts sub-byte value`() {
    // Byte 0xA5 = 0b10100101. Extract top 4 bits = 0b1010 = 10.
    val pktCtx = PacketContext(byteArrayOf(0xA5.toByte()))
    assertEquals(BigInteger.valueOf(0xA), pktCtx.extractBits(4))
  }

  @Test
  @Suppress("MagicNumber")
  fun `peekBits does not advance cursor`() {
    val pktCtx = PacketContext(byteArrayOf(0xA5.toByte()))
    val first = pktCtx.peekBits(4)
    val second = pktCtx.peekBits(4)
    assertEquals(first, second)
    // extractBits should still read the same value.
    assertEquals(BigInteger.valueOf(0xA), pktCtx.extractBits(4))
  }

  @Test
  @Suppress("MagicNumber")
  fun `extractBits across byte boundaries`() {
    // Bytes: 0xA5 0xC3 = 0b10100101_11000011
    // Skip 4 bits (0b1010), then extract 8 bits: 0b01011100 = 0x5C
    val pktCtx = PacketContext(byteArrayOf(0xA5.toByte(), 0xC3.toByte()))
    pktCtx.extractBits(4) // skip first 4 bits
    assertEquals(BigInteger.valueOf(0x5C), pktCtx.extractBits(8))
  }

  @Test
  @Suppress("MagicNumber")
  fun `extractBits spanning 3 bytes`() {
    // Bytes: [0xA5, 0xC3, 0x7F] = 0b10100101_11000011_01111111
    // Skip 4 bits → remaining: 0b0101_11000011_01111111
    // Extract 16 bits: 0b0101110000110111 = 0x5C37
    val pktCtx = PacketContext(byteArrayOf(0xA5.toByte(), 0xC3.toByte(), 0x7F.toByte()))
    pktCtx.extractBits(4) // skip first 4 bits
    assertEquals(BigInteger.valueOf(0x5C37), pktCtx.extractBits(16))
  }

  @Test
  @Suppress("MagicNumber")
  fun `extractBits exactly 1 bit at a byte boundary`() {
    // Byte: [0x80] = 0b10000000. Extract 1 bit → should be 1.
    val pktCtx = PacketContext(byteArrayOf(0x80.toByte()))
    assertEquals(BigInteger.ONE, pktCtx.extractBits(1))
  }

  @Test
  @Suppress("MagicNumber")
  fun `extractBits of full byte at non-aligned position`() {
    // Bytes: [0xFF, 0x00] = 0b11111111_00000000
    // Skip 1 bit → remaining: 0b1111111_00000000
    // Extract 8 bits: 0b11111110 = 0xFE
    val pktCtx = PacketContext(byteArrayOf(0xFF.toByte(), 0x00))
    pktCtx.extractBits(1) // skip first bit
    assertEquals(BigInteger.valueOf(0xFE), pktCtx.extractBits(8))
  }

  // ---------------------------------------------------------------------------
  // BitAccumulator — adversarial inputs
  // ---------------------------------------------------------------------------

  @Test
  @Suppress("MagicNumber")
  fun `BitAccumulator masks value wider than declared width`() {
    // append(0xFF, 4): the value 0xFF has 8 significant bits, but only 4 bits
    // are declared. BitAccumulator masks to the lower 4 bits (0xF) internally.
    // Output: 0xF in top 4 bits → 0xF0.
    val acc = BitAccumulator()
    acc.append(BigInteger.valueOf(0xFF), 4)
    assertArrayEquals(byteArrayOf(0xF0.toByte()), acc.toByteArray())
  }

  // ---------------------------------------------------------------------------
  // Property: BitAccumulator pack → ParserCursor extract = identity
  // ---------------------------------------------------------------------------

  @Test
  @Suppress("MagicNumber")
  fun `pack then extract round-trips for byte-aligned widths`() {
    // Pack 8, 16, 32 bits → extract the same widths → values match.
    val values = listOf(0xABL to 8, 0xCDEFL to 16, 0xDEADBEEFL to 32)
    val acc = BitAccumulator()
    for ((value, width) in values) acc.append(BigInteger.valueOf(value), width)
    val bytes = acc.toByteArray()

    val ctx = PacketContext(bytes)
    for ((value, width) in values) {
      assertEquals(BigInteger.valueOf(value), ctx.extractBits(width))
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `pack then extract round-trips for non-byte-aligned widths`() {
    // Pack 3, 5, 7, 11, 6 bits (total 32 = byte-aligned) → extract → values match.
    val values = listOf(0x5L to 3, 0x1FL to 5, 0x7FL to 7, 0x7FFL to 11, 0x3FL to 6)
    val acc = BitAccumulator()
    for ((value, width) in values) acc.append(BigInteger.valueOf(value), width)
    val bytes = acc.toByteArray()

    val ctx = PacketContext(bytes)
    for ((value, width) in values) {
      assertEquals("width=$width", BigInteger.valueOf(value), ctx.extractBits(width))
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `pack then extract round-trips for non-byte-aligned total`() {
    // Pack 5, 7, 3 bits (total 15 = not byte-aligned) → extract → values match.
    // The accumulator pads to 2 bytes; the parser reads exactly 15 bits.
    val values = listOf(0x1FL to 5, 0x55L to 7, 0x7L to 3)
    val acc = BitAccumulator()
    for ((value, width) in values) acc.append(BigInteger.valueOf(value), width)
    val bytes = acc.toByteArray()
    assertEquals("ceil(15/8) = 2 bytes", 2, bytes.size)

    val ctx = PacketContext(bytes)
    for ((value, width) in values) {
      assertEquals("width=$width", BigInteger.valueOf(value), ctx.extractBits(width))
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `appendRawBytes then extract round-trips arbitrary byte data`() {
    // Pack 6 header bits + 4 raw payload bytes at bit offset 0 → extract header + payload.
    val headerValue = 0x3FL // 6 bits
    val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

    val acc = BitAccumulator()
    acc.append(BigInteger.valueOf(headerValue), 6)
    acc.appendRawBytes(payload, 0, 32)
    val bytes = acc.toByteArray()
    assertEquals("ceil(38/8) = 5 bytes", 5, bytes.size)

    val ctx = PacketContext(bytes)
    assertEquals(BigInteger.valueOf(headerValue), ctx.extractBits(6))
    // Extract the 4 payload bytes individually.
    for (b in payload) {
      assertEquals(BigInteger.valueOf(b.toLong() and 0xFF), ctx.extractBits(8))
    }
  }
}
