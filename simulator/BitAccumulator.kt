package fourward.simulator

import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Accumulates bits into a continuous bit stream, producing a byte-aligned output with trailing zero
 * padding — matching real P4 target behavior.
 *
 * Used by the deparser (emit), the output assembly (deparsedPayload), and the P4Runtime codec
 * (PacketOut packing, PacketIn stripping).
 */
@Suppress("MagicNumber")
class BitAccumulator {
  private val bytes = ByteArrayOutputStream()
  private var pendingBits = 0L
  private var pendingCount = 0

  fun append(value: BigInteger, width: Int) {
    var remaining = width
    var bits = value
    while (remaining > 0) {
      // space is always 1..8, so the shifts below are always < 64.
      val space = 8 - pendingCount
      if (remaining >= space) {
        val shift = remaining - space
        val top = bits.shiftRight(shift).toLong() and ((1L shl space) - 1)
        pendingBits = (pendingBits shl space) or top
        bytes.write(pendingBits.toInt() and 0xFF)
        pendingBits = 0
        pendingCount = 0
        remaining -= space
        if (remaining > 0) {
          bits = bits.and(BigInteger.ONE.shiftLeft(remaining).subtract(BigInteger.ONE))
        }
      } else {
        val top = bits.toLong() and ((1L shl remaining) - 1)
        pendingBits = (pendingBits shl remaining) or top
        pendingCount += remaining
        remaining = 0
      }
    }
  }

  /** Appends raw bytes from [data] starting at bit offset [bitOff] for [bitCount] bits. */
  fun appendRawBytes(data: ByteArray, bitOff: Int, bitCount: Int) {
    var pos = bitOff
    var remaining = bitCount
    while (remaining > 0) {
      val byteIdx = pos / 8
      val bitInByte = pos % 8
      val available = 8 - bitInByte
      val take = minOf(available, remaining)
      val shift = available - take
      val bits = ((data[byteIdx].toInt() and 0xFF) ushr shift) and ((1 shl take) - 1)
      append(BigInteger.valueOf(bits.toLong()), take)
      pos += take
      remaining -= take
    }
  }

  fun copy(): BitAccumulator {
    val clone = BitAccumulator()
    clone.bytes.write(bytes.toByteArray())
    clone.pendingBits = pendingBits
    clone.pendingCount = pendingCount
    return clone
  }

  fun toByteArray(): ByteArray {
    val result = bytes.toByteArray()
    if (pendingCount == 0) return result
    val padded = result.copyOf(result.size + 1)
    padded[result.size] = ((pendingBits shl (8 - pendingCount)).toInt() and 0xFF).toByte()
    return padded
  }
}
