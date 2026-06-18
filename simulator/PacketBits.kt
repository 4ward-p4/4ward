package fourward.simulator

/**
 * Packet bytes plus the number of meaningful bits they contain.
 *
 * Most packets are byte-aligned, but P4Runtime PacketOut can prepend controller metadata whose
 * width is not a multiple of eight. The transport still carries whole bytes, so callers must keep
 * the valid bit length alongside the padded byte buffer. [validBitLength], not the backing array
 * size, defines the packet; bytes beyond that boundary are transport padding and must not be
 * interpreted as packet data.
 */
class PacketBits private constructor(private val paddedBytes: ByteArray, val validBitLength: Int) {
  init {
    require(validBitLength in 0..paddedBytes.size * Byte.SIZE_BITS) {
      "validBitLength must be between 0 and ${paddedBytes.size * Byte.SIZE_BITS}, " +
        "got $validBitLength"
    }
  }

  val packetByteLength: Int
    get() = (validBitLength + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS

  private val paddedByteLength: Int
    get() = paddedBytes.size

  /**
   * Returns packet bytes with non-semantic padding removed as far as a byte API can express.
   *
   * Byte-only callers cannot carry [validBitLength], so the best representation is the smallest
   * byte array that covers the semantic packet bits, with any low padding bits in the final byte
   * cleared. Use this for public observability surfaces that expose packets as bytes.
   */
  fun copySemanticBytes(): ByteArray {
    val bytes = paddedBytes.copyOf(packetByteLength)
    maskFinalPacketByteInPlace(bytes, validBitLength)
    return bytes
  }

  /**
   * Returns the mutable backing buffer for parser internals.
   *
   * Keep this internal so public callers cannot accidentally use the padded byte array while
   * dropping [validBitLength].
   */
  internal fun backingBytesForParser(): ByteArray = paddedBytes

  fun truncateToBytes(maxBytes: Int): PacketBits =
    if (maxBytes > 0 && maxBytes < packetByteLength) {
      PacketBits(paddedBytes.copyOf(maxBytes), minOf(validBitLength, maxBytes * Byte.SIZE_BITS))
    } else {
      this
    }

  override fun equals(other: Any?): Boolean =
    other is PacketBits &&
      validBitLength == other.validBitLength &&
      paddedBytes.contentEquals(other.paddedBytes)

  override fun hashCode(): Int = 31 * paddedBytes.contentHashCode() + validBitLength

  override fun toString(): String =
    "PacketBits(packetByteLength=$packetByteLength, paddedByteLength=$paddedByteLength, " +
      "validBitLength=$validBitLength)"

  companion object {
    val EMPTY: PacketBits = PacketBits(byteArrayOf(), 0)

    fun ofBytes(bytes: ByteArray): PacketBits = PacketBits(bytes, bytes.size * Byte.SIZE_BITS)

    /**
     * Builds a packet from a byte buffer whose final byte may contain transport padding.
     *
     * Padding bits need not be zero. Use [copySemanticBytes] when converting back to a byte-only
     * packet view.
     */
    fun ofPaddedBytes(bytes: ByteArray, validBitLength: Int): PacketBits =
      PacketBits(bytes, validBitLength)
  }
}

/**
 * Clears transport padding bits from the final semantic packet byte.
 *
 * Packet bit order is MSB-first, so the non-packet bits in a partial final byte are the low bits.
 */
internal fun maskFinalPacketByteInPlace(bytes: ByteArray, validBitLength: Int) {
  val finalPaddingBits = (Byte.SIZE_BITS - validBitLength % Byte.SIZE_BITS) % Byte.SIZE_BITS
  if (bytes.isNotEmpty() && finalPaddingBits != 0) {
    val finalByteMask = 0xFF shl finalPaddingBits
    bytes[bytes.lastIndex] = (bytes.last().toInt() and finalByteMask).toByte()
  }
}
