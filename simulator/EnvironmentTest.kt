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
    pktCtx.emitBits(java.math.BigInteger.valueOf(0x0800), 16)
    assertArrayEquals(byteArrayOf(0x08, 0x00), pktCtx.outputPayload())
  }

  @Test
  @Suppress("MagicNumber")
  fun `multiple emitBits calls form continuous bit stream`() {
    val pktCtx = PacketContext(byteArrayOf())
    // 6 bits: 0b111111 = 0x3F, then 10 bits: 0b1010101010 = 0x2AA
    // Continuous: 111111_1010101010 = 16 bits = 0xFAAA? Let's compute:
    // 111111_10_10101010 = 0xFAAA? No:
    // 111111 10 10101010 = byte 0: 11111110 = 0xFE, byte 1: 10101010 = 0xAA
    pktCtx.emitBits(java.math.BigInteger.valueOf(0x3F), 6)
    pktCtx.emitBits(java.math.BigInteger.valueOf(0x2AA), 10)
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
}
