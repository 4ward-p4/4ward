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
   * Returns a copy of the whole transport buffer, including padding past [validBitLength].
   *
   * This is for observability/debug surfaces that report the original injected buffer. Parser and
   * deparser semantics must use [validBitLength] instead of treating these bytes as all meaningful.
   */
  fun copyPaddedBytes(): ByteArray = paddedBytes.copyOf()

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
    fun ofBytes(bytes: ByteArray): PacketBits = PacketBits(bytes, bytes.size * Byte.SIZE_BITS)

    /**
     * Builds a packet from a byte buffer whose final byte may contain transport padding.
     *
     * Padding bits need not be zero. Consumers that return semantic packet bytes are responsible
     * for masking the final partial byte.
     */
    fun ofPaddedBytes(bytes: ByteArray, validBitLength: Int): PacketBits =
      PacketBits(bytes, validBitLength)
  }
}
