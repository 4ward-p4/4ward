package fourward.simulator

import fourward.ExternInstanceDecl
import java.math.BigInteger
import java.util.zip.CRC32

// CRC-16/ARC reflected polynomial (0x8005 reflected = 0xA001).
// This matches boost::crc_16_type used by BMv2.
private const val CRC16_POLY = 0xA001
private const val BYTE_MASK = 0xFF
private const val CRC16_MASK = 0xFFFF

internal const val CSUM_WORD_BITS = 16
internal val CSUM_MASK = BigInteger.TWO.pow(CSUM_WORD_BITS).subtract(BigInteger.ONE)

/**
 * Concatenates all [BitVal] fields in a [StructVal] into a single [BitVector].
 *
 * [UnitVal] fields (from unextracted varbit<N> headers) are skipped — they have no bits to
 * contribute. This matches BMv2 which omits zero-length varbits from checksum computations.
 */
internal fun concatFields(data: StructVal): BitVector? =
  data.fields.values.mapNotNull { (it as? BitVal)?.bits }.reduceOrNull { acc, bv -> acc.concat(bv) }

/** Folds carries back into a 16-bit ones' complement value. */
internal fun foldCarries(value: BigInteger): BigInteger {
  var sum = value
  while (sum > CSUM_MASK) {
    sum = sum.and(CSUM_MASK).add(sum.shiftRight(CSUM_WORD_BITS))
  }
  return sum
}

/** Ones' complement addition of two 16-bit values with carry folding. */
internal fun onesComplementAdd(a: BigInteger, b: BigInteger): BigInteger = foldCarries(a.add(b))

/**
 * Sums all 16-bit words in [combined] using ones' complement addition.
 *
 * Pads to a 16-bit boundary. Returns the raw folded sum (not complemented). Used both by the
 * one-shot csum16 hash algorithm and by PSA's incremental [InternetChecksum][PSAArchitecture]
 * extern.
 */
internal fun sumBitWords(combined: BitVector): BigInteger {
  val totalWidth = combined.width
  val padded = ((totalWidth + CSUM_WORD_BITS - 1) / CSUM_WORD_BITS) * CSUM_WORD_BITS
  var bits = combined.value.shiftLeft(padded - totalWidth)
  var sum = BigInteger.ZERO
  for (i in 0 until padded / CSUM_WORD_BITS) {
    val word = bits.shiftRight(padded - (i + 1) * CSUM_WORD_BITS).and(CSUM_MASK)
    sum = sum.add(word)
  }
  return foldCarries(sum)
}

/**
 * Ones' complement (csum16) checksum over a bit vector.
 *
 * Pads to a 16-bit boundary, sums all 16-bit words with end-around carry, and returns the
 * complement. This is the standard Internet checksum (RFC 1071) used by IPv4, TCP, and UDP.
 */
private fun onesComplementChecksumBits(combined: BitVector): BigInteger =
  CSUM_MASK.subtract(sumBitWords(combined))

/** Ones' complement (csum16) checksum over the fields of a struct. */
fun onesComplementChecksum(data: StructVal): BigInteger {
  val combined = concatFields(data) ?: return BigInteger.ZERO
  return onesComplementChecksumBits(combined)
}

/**
 * CRC-16/ARC (IBM/LHA) — the variant used by BMv2's `HashAlgorithm.crc16`.
 *
 * Parameters: poly=0x8005, init=0, reflect_in=true, reflect_out=true, xor_out=0. Uses the reflected
 * algorithm (shift right with reflected polynomial) to avoid per-byte bit reversal.
 */
private fun crc16(data: ByteArray): Int {
  var crc = 0
  for (byte in data) {
    crc = crc xor (byte.toInt() and BYTE_MASK)
    repeat(Byte.SIZE_BITS) { crc = if (crc and 1 != 0) (crc ushr 1) xor CRC16_POLY else crc ushr 1 }
  }
  return crc and CRC16_MASK
}

/**
 * CRC-32 (ISO-HDLC) — the variant used by BMv2's `HashAlgorithm.crc32`.
 *
 * Delegates to [java.util.zip.CRC32] which implements the same algorithm (poly=0x04C11DB7,
 * init=0xFFFFFFFF, reflect_in=true, reflect_out=true, xor_out=0xFFFFFFFF).
 */
private fun crc32(data: ByteArray): Long {
  val crc = CRC32()
  crc.update(data)
  return crc.value
}

/** Converts a [StructVal]'s fields to a big-endian byte array for hashing. */
private fun structToBytes(data: StructVal): ByteArray =
  concatFields(data)?.toByteArray() ?: ByteArray(0)

/**
 * Computes a hash over [data] using the specified [algorithm].
 *
 * Supports csum16 (ones' complement), crc16, crc32, and identity — the algorithms used by BMv2's
 * simple_switch. Returns the raw hash value as a [BigInteger].
 */
fun computeHash(algorithm: String, data: StructVal): BigInteger =
  computeHashWithPayload(algorithm, data, ByteArray(0))

/**
 * Like [computeHash] but appends [payload] (the unparsed packet remainder) to the hash input.
 *
 * Used by the `_with_payload` checksum externs (v1model §14) which include the packet body beyond
 * the parsed headers in the checksum computation.
 */
fun computeHashWithPayload(algorithm: String, data: StructVal, payload: ByteArray): BigInteger =
  when (algorithm) {
    "csum16" -> {
      val fieldBits = concatFields(data)
      val payloadBits =
        if (payload.isNotEmpty()) BitVector(BigInteger(1, payload), payload.size * 8) else null
      val combined =
        listOfNotNull(fieldBits, payloadBits).reduceOrNull { a, b -> a.concat(b) }
          ?: return BigInteger.ZERO
      onesComplementChecksumBits(combined)
    }
    else -> {
      val bytes = structToBytes(data) + payload
      when (algorithm) {
        "crc16" -> BigInteger.valueOf(crc16(bytes).toLong())
        "crc32" -> BigInteger.valueOf(crc32(bytes))
        "identity" -> if (bytes.isEmpty()) BigInteger.ZERO else BigInteger(1, bytes)
        else -> error("unsupported hash algorithm: $algorithm")
      }
    }
  }

/**
 * Coerces a hash data argument to [StructVal]. The p4c midend usually wraps hash inputs in a
 * StructExpression, but single-field inputs (e.g. `get_hash(hdr.ethernet.srcAddr)`) arrive as bare
 * [BitVal]. Headers arrive as [HeaderVal].
 */
internal fun hashDataArg(value: Value): StructVal =
  when (value) {
    is BitVal -> StructVal("", mutableMapOf("_0" to value))
    else -> value.asStructVal()
  }

/**
 * Sums all 16-bit words in [data]'s concatenated fields. Returns the raw sum (not complemented).
 */
internal fun sumWords(data: StructVal): BigInteger =
  concatFields(data)?.let { sumBitWords(it) } ?: BigInteger.ZERO

/**
 * Evaluates Hash.get_hash (1-arg or 3-arg form). [algorithmMap] maps architecture-specific enum
 * members to the internal algorithm names used by [computeHash].
 */
internal fun evalGetHash(
  call: ExternCall.Method,
  eval: ExternEvaluator,
  externInstances: Map<String, ExternInstanceDecl>,
  algorithmMap: Map<String, String>,
): Value {
  val instance =
    externInstances[call.instanceName] ?: error("unknown Hash instance: ${call.instanceName}")
  val archAlgorithm =
    instance.constructorArgsList.firstOrNull()?.literal?.enumMember
      ?: error("Hash instance ${call.instanceName} missing algorithm constructor arg")
  val algorithm = algorithmMap[archAlgorithm] ?: error("unsupported hash algorithm: $archAlgorithm")
  val resultWidth = eval.returnType().bit.width

  val resultMask = BigInteger.TWO.pow(resultWidth) - BigInteger.ONE

  return if (eval.argCount() == 1) {
    // 1-arg form: get_hash(data) -> hash(data) truncated to result width
    val data = hashDataArg(eval.evalArg(0))
    val hash = computeHash(algorithm, data).and(resultMask)
    BitVal(BitVector(hash, resultWidth))
  } else {
    // 3-arg form: get_hash(base, data, max) -> (base + hash(data)) mod max
    val base = (eval.evalArg(0) as BitVal).bits.value
    val data = hashDataArg(eval.evalArg(1))
    val max = (eval.evalArg(2) as BitVal).bits.value
    val hash = computeHash(algorithm, data)
    val result = if (max > BigInteger.ZERO) (base + hash.mod(max)).and(resultMask) else base
    BitVal(BitVector(result, resultWidth))
  }
}
