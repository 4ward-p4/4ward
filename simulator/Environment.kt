package fourward.simulator

import fourward.TraceEvent
import java.io.ByteArrayOutputStream

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

  /** Output packet bytes, written by deparser emit(). */
  private val outputBuffer = ByteArrayOutputStream()

  fun extractBytes(count: Int): ByteArray = buffer.read(count)

  fun peekBytes(count: Int): ByteArray = buffer.peek(count)

  fun advanceBits(bits: Int) = buffer.advanceBits(bits)

  fun emitBytes(bytes: ByteArray) {
    outputBuffer.write(bytes)
  }

  fun outputPayload(): ByteArray = outputBuffer.toByteArray()

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
 * Thrown when the parser tries to extract more bytes than the packet contains.
 *
 * In v1model/BMv2, this corresponds to a `PacketTooShort` parser error. The packet is dropped
 * rather than propagating as a simulator processing failure.
 */
class PacketTooShortException(message: String) : ParserErrorException("PacketTooShort", message)

/** Thrown by the interpreter when a parser error occurs (P4 spec §12.8). */
open class ParserErrorException(val errorName: String, message: String) : Exception(message)

/** A simple byte-level cursor over a packet buffer, used by the parser. */
private class ParserCursor(private val data: ByteArray, initialOffset: Int = 0) {
  private var offset: Int = initialOffset

  /** Number of bytes consumed from the start of the buffer. */
  val bytesConsumed: Int
    get() = offset

  fun remaining(): Int = data.size - offset

  fun readAll(): ByteArray = data.copyOfRange(offset, data.size).also { offset = data.size }

  /** Returns all remaining bytes without advancing the cursor. */
  fun peekAll(): ByteArray = data.copyOfRange(offset, data.size)

  fun read(count: Int): ByteArray {
    if (count > remaining()) {
      throw PacketTooShortException(
        "attempted to extract $count bytes but only ${remaining()} remain in packet"
      )
    }
    return data.copyOfRange(offset, offset + count).also { offset += count }
  }

  /** Peeks at the next [count] bytes without advancing the cursor (P4 spec §12.8.2). */
  fun peek(count: Int): ByteArray {
    if (count > remaining()) {
      throw PacketTooShortException(
        "lookahead: need $count bytes but only ${remaining()} remain in packet"
      )
    }
    return data.copyOfRange(offset, offset + count)
  }

  /** Advances the cursor by [bits] bits, which must be a multiple of 8 (P4 spec §12.8.3). */
  fun advanceBits(bits: Int) {
    val bytes = bits / 8
    if (bytes > remaining()) {
      throw PacketTooShortException(
        "advance: need $bytes bytes but only ${remaining()} remain in packet"
      )
    }
    offset += bytes
  }
}
