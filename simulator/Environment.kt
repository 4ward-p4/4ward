package fourward.simulator

import fourward.TraceEvent
import java.io.ByteArrayOutputStream
import java.math.BigInteger

/**
 * Variable scope stack for a single packet traversal.
 *
 * Holds variable bindings (headers, metadata, local variables) organised as a stack of scopes to
 * handle nested control blocks. A new Environment is created for each [InjectPacketRequest] and
 * discarded afterwards.
 *
 * Packet-level state (input buffer, output buffer, execution trace) lives in [PacketContext].
 */
class Environment {

  private val scopes: ArrayDeque<MutableMap<String, Value>> = ArrayDeque()

  init {
    pushScope()
  } // top-level scope

  fun pushScope() {
    scopes.addLast(mutableMapOf())
  }

  fun popScope() {
    scopes.removeLast()
  }

  /** Defines a new variable in the innermost scope. */
  fun define(name: String, value: Value) {
    scopes.last()[name] = value
  }

  /**
   * Looks up a variable by name, searching from the innermost scope outward. Returns null if not
   * found.
   */
  fun lookup(name: String): Value? {
    for (scope in scopes.asReversed()) {
      scope[name]?.let {
        return it
      }
    }
    return null
  }

  /**
   * Updates an existing variable binding. Searches from the innermost scope outward, updating the
   * first match. Throws if the variable is not found.
   */
  fun update(name: String, value: Value) {
    for (scope in scopes.asReversed()) {
      if (name in scope) {
        scope[name] = value
        return
      }
    }
    error("undefined variable: $name")
  }

  /** Returns an independent deep copy of this environment (all scopes and values). */
  fun deepCopy(): Environment {
    val copy = Environment()
    copy.scopes.clear()
    for (scope in scopes) {
      // Pre-size to skip HashMap.resize on the fork-copy hot path (load factor 0.75).
      copy.scopes.addLast(
        scope.mapValuesTo(LinkedHashMap(scope.size * 4 / 3 + 1)) { it.value.deepCopy() }
      )
    }
    return copy
  }
}

/**
 * Packet-level state for a single [InjectPacketRequest].
 *
 * Holds the input packet buffer, the output (emit) buffer, and the execution trace. Created once
 * per packet in the architecture's [processPacket] and threaded through the interpreter.
 */
class PacketContext(payload: ByteArray, initialOffset: Int = 0) {

  /** Original ingress packet length in bytes (for direct counter byte counts). */
  val payloadSize: Int = payload.size

  // -------------------------------------------------------------------------
  // Packet buffer
  // -------------------------------------------------------------------------

  /** Remaining bytes in the input packet, consumed by parser extract(). */
  private val buffer: ParserCursor = ParserCursor(payload, initialOffset)

  /** Number of bytes consumed from the input buffer so far (parser extract position). */
  val bytesConsumed: Int
    get() = buffer.bytesConsumed

  /** Bit-level output buffer, written by deparser emit(). */
  private val outputBits = BitAccumulator()

  fun extractBytes(count: Int): ByteArray = buffer.read(count)

  fun extractBits(bitCount: Int): BigInteger = buffer.readBits(bitCount)

  fun peekBytes(count: Int): ByteArray = buffer.peek(count)

  fun peekBits(bitCount: Int): BigInteger = buffer.peekBits(bitCount)

  fun advanceBits(bits: Int) = buffer.advanceBits(bits)

  fun emitBits(value: BigInteger, width: Int) {
    outputBits.append(value, width)
  }

  /** Returns the accumulated output bits as bytes (zero-padded to byte boundary). */
  fun outputPayload(): ByteArray = outputBits.toByteArray()

  /** Returns all bytes not yet consumed by the parser (the un-parsed packet body). */
  fun drainRemainingInput(): ByteArray = buffer.readAll()

  /** Peeks at remaining input bytes without consuming them. */
  fun peekRemainingInput(): ByteArray = buffer.peekAll()

  // -------------------------------------------------------------------------
  // Trace
  // -------------------------------------------------------------------------

  private val traceEvents: MutableList<TraceEvent> = mutableListOf()

  fun addTraceEvent(event: TraceEvent) {
    traceEvents.add(event)
  }

  fun getEvents(): List<TraceEvent> = traceEvents.toList()
}

/**
 * Accumulates bits from deparser emit() calls into a continuous bit stream, producing a
 * byte-aligned output with trailing zero padding — matching real P4 target behavior.
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
      val space = 8 - pendingCount
      if (remaining >= space) {
        val shift = remaining - space
        val top =
          if (shift < 64) (bits shr shift).toLong() and ((1L shl space) - 1)
          else bits.shiftRight(shift).toLong() and ((1L shl space) - 1)
        pendingBits = (pendingBits shl space) or top
        bytes.write(pendingBits.toInt() and 0xFF)
        pendingBits = 0
        pendingCount = 0
        remaining -= space
        if (remaining > 0 && remaining < 64) {
          bits = bits.and(BigInteger.ONE.shiftLeft(remaining).subtract(BigInteger.ONE))
        }
      } else {
        val top =
          if (remaining < 64) bits.toLong() and ((1L shl remaining) - 1)
          else bits.and(BigInteger.ONE.shiftLeft(remaining).subtract(BigInteger.ONE)).toLong()
        pendingBits = (pendingBits shl remaining) or top
        pendingCount += remaining
        remaining = 0
      }
    }
  }

  fun toByteArray(): ByteArray {
    if (pendingCount > 0) {
      bytes.write((pendingBits shl (8 - pendingCount)).toInt() and 0xFF)
      pendingBits = 0
      pendingCount = 0
    }
    return bytes.toByteArray()
  }
}

/**
 * Thrown when the parser tries to extract more bytes than the packet contains.
 *
 * In v1model/BMv2, this corresponds to a `PacketTooShort` parser error. The packet is dropped
 * rather than propagating as a simulator processing failure.
 */
class PacketTooShortException(message: String) : ParserErrorException("PacketTooShort", message)

/** Thrown by the interpreter when a parser error occurs (P4 spec §12.8). */
open class ParserErrorException(val errorName: String, message: String) : Exception(message)

/**
 * Bit-level cursor over a packet buffer, used by the parser.
 *
 * Tracks a bit offset so that sub-byte header extracts (e.g., a 4-bit tag followed by a 12-bit
 * length) consume exactly the right number of bits without over-reading.
 */
@Suppress("MagicNumber")
private class ParserCursor(private val data: ByteArray, initialByteOffset: Int = 0) {
  private var bitOffset: Int = initialByteOffset * 8

  /** Number of whole bytes consumed from the start of the buffer. */
  val bytesConsumed: Int
    get() = (bitOffset + 7) / 8

  private fun remainingBits(): Int = data.size * 8 - bitOffset

  /** Returns all bytes from the current byte-aligned position to the end. */
  fun readAll(): ByteArray {
    val byteStart = (bitOffset + 7) / 8
    bitOffset = data.size * 8
    return data.copyOfRange(byteStart, data.size)
  }

  /** Returns all bytes from the current byte-aligned position without advancing. */
  fun peekAll(): ByteArray {
    val byteStart = (bitOffset + 7) / 8
    return data.copyOfRange(byteStart, data.size)
  }

  /** Reads [count] bytes from the current position (must be byte-aligned). */
  fun read(count: Int): ByteArray {
    val bitsNeeded = count * 8
    if (bitsNeeded > remainingBits()) {
      throw PacketTooShortException(
        "attempted to extract $count bytes but only ${remainingBits() / 8} remain in packet"
      )
    }
    val byteStart = bitOffset / 8
    bitOffset += bitsNeeded
    return data.copyOfRange(byteStart, byteStart + count)
  }

  /** Reads [bitCount] bits from the current position as a BigInteger, MSB-first. */
  fun readBits(bitCount: Int): BigInteger {
    if (bitCount > remainingBits()) {
      throw PacketTooShortException(
        "attempted to extract $bitCount bits but only ${remainingBits()} remain in packet"
      )
    }
    val result = peekBitsInternal(bitCount)
    bitOffset += bitCount
    return result
  }

  /** Peeks at [count] bytes without advancing (must be byte-aligned). */
  fun peek(count: Int): ByteArray {
    val bitsNeeded = count * 8
    if (bitsNeeded > remainingBits()) {
      throw PacketTooShortException(
        "lookahead: need $count bytes but only ${remainingBits() / 8} remain in packet"
      )
    }
    val byteStart = bitOffset / 8
    return data.copyOfRange(byteStart, byteStart + count)
  }

  /** Peeks at [bitCount] bits without advancing, MSB-first. */
  fun peekBits(bitCount: Int): BigInteger {
    if (bitCount > remainingBits()) {
      throw PacketTooShortException(
        "lookahead: need $bitCount bits but only ${remainingBits()} remain in packet"
      )
    }
    return peekBitsInternal(bitCount)
  }

  /** Advances the cursor by [bits] bits. */
  fun advanceBits(bits: Int) {
    if (bits > remainingBits()) {
      throw PacketTooShortException(
        "advance: need $bits bits but only ${remainingBits()} remain in packet"
      )
    }
    bitOffset += bits
  }

  private fun peekBitsInternal(bitCount: Int): BigInteger {
    if (bitCount == 0) return BigInteger.ZERO
    val startByte = bitOffset / 8
    val endByte = (bitOffset + bitCount - 1) / 8
    val raw = BigInteger(1, data.copyOfRange(startByte, endByte + 1))
    val trailingBits = (endByte + 1) * 8 - (bitOffset + bitCount)
    val shifted = raw.shiftRight(trailingBits)
    val mask = BigInteger.ONE.shiftLeft(bitCount).subtract(BigInteger.ONE)
    return shifted.and(mask)
  }
}
