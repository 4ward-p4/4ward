package fourward.simulator

/**
 * Packet bytes plus the number of meaningful bits they contain.
 *
 * Most packets are byte-aligned, but P4Runtime PacketOut can prepend controller metadata whose
 * width is not a multiple of eight. The transport still carries whole bytes, so callers must keep
 * the valid bit length alongside the padded byte buffer.
 */
class PacketBits private constructor(private val paddedBytes: ByteArray, val validBitLength: Int) {
  init {
    require(validBitLength in 0..paddedBytes.size * Byte.SIZE_BITS) {
      "validBitLength must be between 0 and ${paddedBytes.size * Byte.SIZE_BITS}, " +
        "got $validBitLength"
    }
  }

  val byteLength: Int
    get() = paddedBytes.size

  /** Returns a copy of the whole transport buffer, including padding past [validBitLength]. */
  fun copyPaddedBytesForTransport(): ByteArray = paddedBytes.copyOf()

  /**
   * Returns the mutable backing buffer for parser internals.
   *
   * Keep this internal so public callers cannot accidentally use the padded byte array while
   * dropping [validBitLength].
   */
  internal fun backingBytesForParser(): ByteArray = paddedBytes

  fun truncateToBytes(maxBytes: Int): PacketBits =
    if (maxBytes > 0 && maxBytes < paddedBytes.size) {
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
    "PacketBits(byteLength=${paddedBytes.size}, validBitLength=$validBitLength)"

  companion object {
    fun ofBytes(bytes: ByteArray): PacketBits = PacketBits(bytes, bytes.size * Byte.SIZE_BITS)

    fun of(bytes: ByteArray, validBitLength: Int): PacketBits = PacketBits(bytes, validBitLength)
  }
}
