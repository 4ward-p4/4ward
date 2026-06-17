package fourward.simulator

import java.math.BigInteger

/**
 * A dataplane port as P4Runtime serializes it: a proto `uint32` carried by Java/Kotlin as `Int`.
 *
 * Values above `Int.MAX_VALUE` are intentionally negative at the proto boundary. Keep the raw bits
 * inside this type and choose an explicit view at each use site instead of letting signed `Int`
 * operations leak into translation or packet routing.
 */
@JvmInline
value class DataplanePort
private constructor(
  /**
   * Raw proto `uint32` bits as exposed by Java/Kotlin generated accessors.
   *
   * This is a signed `Int`; use it only when writing back to a proto field with the same generated
   * representation. Use [unsignedLong] or [unsignedBigInteger] for arithmetic and comparisons.
   */
  val protoValue: Int
) {
  val unsignedLong: Long
    get() = protoValue.toLong() and UINT32_MASK

  val unsignedBigInteger: BigInteger
    get() = BigInteger.valueOf(unsignedLong)

  override fun toString(): String = unsignedLong.toString()

  companion object {
    private const val UINT32_MASK = 0xFFFF_FFFFL
    private val UINT32_MAX = BigInteger.valueOf(UINT32_MASK)
    private val DECIMAL_LITERAL = Regex("[0-9][0-9_]*")
    private val HEX_LITERAL = Regex("0[xX][0-9a-fA-F][0-9a-fA-F_]*")

    /** Preserves the exact `uint32` bit pattern received from a generated proto accessor. */
    fun fromProto(value: Int): DataplanePort = DataplanePort(value)

    /**
     * Parses an unsigned integer literal accepted by port CLI flags, or null when [value] is not a
     * numeric literal. Decimal and `0x`/`0X` hexadecimal forms are supported, with optional `_`
     * separators matching Kotlin's literal spelling.
     */
    @Suppress("MagicNumber")
    fun fromUnsignedLiteralOrNull(value: String): DataplanePort? {
      val parsed =
        when {
          DECIMAL_LITERAL.matches(value) -> BigInteger(value.replace("_", ""), 10)
          HEX_LITERAL.matches(value) -> BigInteger(value.drop(2).replace("_", ""), 16)
          else -> return null
        }
      require(parsed <= UINT32_MAX) { "dataplane port out of uint32 range: $value" }
      return DataplanePort(parsed.toLong().toInt())
    }

    /** Constructs a port from an unsigned [Long] in `0..0xFFFF_FFFF`; throws if out of range. */
    fun fromUnsignedLong(value: Long): DataplanePort {
      require(value in 0..UINT32_MASK) { "dataplane port out of uint32 range: $value" }
      return DataplanePort(value.toInt())
    }
  }
}
