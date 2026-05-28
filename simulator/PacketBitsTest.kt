package fourward.simulator

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PacketBitsTest {

  @Test
  fun `valid bit length must fit in transport buffer`() {
    assertThrows(IllegalArgumentException::class.java) {
      PacketBits.ofPaddedBytes(byteArrayOf(0x00), 9)
    }
  }

  @Test
  fun `semantic bytes are copied for public callers`() {
    val packet = PacketBits.ofPaddedBytes(byteArrayOf(0xA0.toByte()), 4)

    val copy = packet.copySemanticBytes()
    copy[0] = 0x00

    assertArrayEquals(byteArrayOf(0xA0.toByte()), packet.copySemanticBytes())
  }

  @Test
  fun `semantic bytes drop whole padding bytes and mask final partial byte`() {
    val packet =
      PacketBits.ofPaddedBytes(byteArrayOf(0xAB.toByte(), 0xFF.toByte(), 0xFF.toByte()), 10)

    assertArrayEquals(byteArrayOf(0xAB.toByte(), 0xC0.toByte()), packet.copySemanticBytes())
  }

  @Test
  fun `packet byte length ignores whole padding bytes`() {
    val packet = PacketBits.ofPaddedBytes(byteArrayOf(0xAB.toByte(), 0xC0.toByte(), 0x00), 10)

    assertEquals(2, packet.packetByteLength)
  }

  @Test
  fun `truncate caps valid bit length at truncated buffer size`() {
    val packet = PacketBits.ofPaddedBytes(byteArrayOf(0xAB.toByte(), 0xC0.toByte()), 10)

    val truncated = packet.truncateToBytes(1)

    assertEquals(8, truncated.validBitLength)
    assertArrayEquals(byteArrayOf(0xAB.toByte()), truncated.copySemanticBytes())
  }
}
