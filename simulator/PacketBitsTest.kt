package fourward.simulator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PacketBitsTest {

  @Test
  fun `valid bit length must fit in transport buffer`() {
    assertThrows(IllegalArgumentException::class.java) { PacketBits.of(byteArrayOf(0x00), 9) }
  }

  @Test
  fun `transport bytes are copied for public callers`() {
    val packet = PacketBits.of(byteArrayOf(0xA0.toByte()), 4)

    val copy = packet.copyPaddedBytesForTransport()
    copy[0] = 0x00

    assertArrayEquals(byteArrayOf(0xA0.toByte()), packet.copyPaddedBytesForTransport())
  }

  @Test
  fun `truncate caps valid bit length at truncated buffer size`() {
    val packet = PacketBits.of(byteArrayOf(0xAB.toByte(), 0xC0.toByte()), 10)

    val truncated = packet.truncateToBytes(1)

    assertEquals(8, truncated.validBitLength)
    assertArrayEquals(byteArrayOf(0xAB.toByte()), truncated.copyPaddedBytesForTransport())
  }
}
