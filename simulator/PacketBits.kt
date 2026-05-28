package fourward.simulator

/**
 * Packet bytes plus the number of meaningful bits they contain.
 *
 * Most packets are byte-aligned, but P4Runtime PacketOut can prepend controller metadata whose
 * width is not a multiple of eight. The transport still carries whole bytes, so callers must keep
 * the valid bit length alongside the padded byte buffer.
 */
class PacketBits private constructor(
  val bytes: ByteArray,
  val validBitLength: Int,
) {
  init {
    require(validBitLength in 0..bytes.size * Byte.SIZE_BITS) {
      "validBitLength must be between 0 and ${bytes.size * Byte.SIZE_BITS}, got $validBitLength"
    }
  }

  val byteLength: Int
    get() = bytes.size

  fun truncateToBytes(maxBytes: Int): PacketBits =
    if (maxBytes > 0 && maxBytes < bytes.size) {
      PacketBits(bytes.copyOf(maxBytes), minOf(validBitLength, maxBytes * Byte.SIZE_BITS))
    } else {
      this
    }

  override fun equals(other: Any?): Boolean =
    other is PacketBits &&
      validBitLength == other.validBitLength &&
      bytes.contentEquals(other.bytes)

  override fun hashCode(): Int = 31 * bytes.contentHashCode() + validBitLength

  override fun toString(): String =
    "PacketBits(byteLength=${bytes.size}, validBitLength=$validBitLength)"

  companion object {
    fun ofBytes(bytes: ByteArray): PacketBits = PacketBits(bytes, bytes.size * Byte.SIZE_BITS)

    fun of(bytes: ByteArray, validBitLength: Int): PacketBits = PacketBits(bytes, validBitLength)
  }
}
