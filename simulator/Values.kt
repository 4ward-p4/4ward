package fourward.simulator

/**
 * Runtime value types for the 4ward simulator.
 *
 * Every P4 runtime value is one of these. The sealed hierarchy maps to P4's type system: bit<N> ->
 * BitVal, int<N> -> IntVal, bool -> BoolVal, header fields -> aggregated in HeaderVal, etc.
 *
 * The simulator's interpreter produces and consumes these values; they never escape to the trace
 * (traces use byte-encoded representations for portability).
 */
sealed class Value {
  /** Returns an independent deep copy. Immutable leaf types return `this`. */
  open fun deepCopy(): Value = this
}

/** A bit<N> value. */
data class BitVal(val bits: BitVector) : Value() {
  constructor(
    value: Long,
    width: Int,
  ) : this(
    if (width <= BitVector.LONG_WIDTH) BitVector.ofLong(value, width)
    else BitVector(java.math.BigInteger.valueOf(value), width)
  )

  constructor(value: Int, width: Int) : this(BitVector.ofLong(value.toLong(), width))
}

/**
 * A compile-time integer with infinite precision (P4 spec §8.1).
 *
 * InfInt values arise from untyped integer literals in the IR (e.g. shift amounts, constants). They
 * adopt the width of the other operand when used in a binary operation.
 */
data class InfIntVal(val value: java.math.BigInteger) : Value() {
  /** Coerce to a fixed-width unsigned [BitVal]. */
  fun toBitVal(width: Int): BitVal =
    BitVal(BitVector(value.mod(java.math.BigInteger.TWO.pow(width)), width))

  /** Coerce to a fixed-width signed [IntVal]. */
  fun toIntVal(width: Int): IntVal =
    IntVal(SignedBitVector.fromUnsignedBits(value.mod(java.math.BigInteger.TWO.pow(width)), width))
}

/** An int<N> value (two's complement). */
data class IntVal(val bits: SignedBitVector) : Value()

/** A bool value. */
data class BoolVal(val value: Boolean) : Value() {
  companion object {
    val TRUE = BoolVal(true)
    val FALSE = BoolVal(false)
  }
}

/** A P4 error value (one of the named error members, e.g. "NoError"). */
data class ErrorVal(val member: String) : Value() {
  companion object {
    /** P4 spec §7.1.4: the default error value. */
    val NO_ERROR = ErrorVal("NoError")
  }
}

/** A plain (non-serializable) P4 enum value, e.g. HashAlgorithm.crc16. */
data class EnumVal(val member: String) : Value()

/** A string value, used by log_msg format strings. */
data class StringVal(val value: String) : Value()

/**
 * A header instance.
 *
 * [valid] reflects the P4 validity bit. Fields are stored by name. Extracting a header sets valid =
 * true; calling setInvalid() sets valid = false and zeros all fields.
 */
data class HeaderVal(
  val typeName: String,
  val fields: MutableMap<String, Value> = mutableMapOf(),
  var valid: Boolean = false,
) : Value() {

  /** Sets the header valid and stores new field values. */
  fun setValid(newFields: Map<String, Value>) {
    valid = true
    fields.clear()
    fields.putAll(newFields)
  }

  /**
   * Sets the header invalid and zeros all fields, as required by the P4 spec (section 8.17:
   * setInvalid resets all fields to their default values).
   */
  fun setInvalid() {
    valid = false
    // Zero all fields (P4 spec §8.17). BMv2 treats fields of an invalid
    // header as zero, so we reset them in place rather than clearing.
    fields.replaceAll { _, v ->
      when (v) {
        is BitVal -> BitVal(0L, v.bits.width)
        is IntVal -> IntVal(SignedBitVector(java.math.BigInteger.ZERO, v.bits.width))
        is BoolVal -> BoolVal(false)
        is ErrorVal -> ErrorVal.NO_ERROR
        is UnitVal -> v
        else -> error("unexpected header field type: ${v::class.simpleName}")
      }
    }
  }

  fun copy(): HeaderVal = HeaderVal(typeName, copyFields(), valid)

  /**
   * P4 headers contain only primitive fields (`bit<N>`, `int<N>`, `bool`, `varbit`), whose
   * [Value.deepCopy] returns `this`. So deep-copy is equivalent to a shallow map copy — no
   * per-entry recursion needed. For [CompactFieldMap]-backed headers (the common path via
   * [defaultValue]), this is a single [Array.copyOf] with no per-entry allocation.
   */
  override fun deepCopy(): HeaderVal = HeaderVal(typeName, copyFields(), valid)

  private fun copyFields(): MutableMap<String, Value> =
    if (fields is CompactFieldMap) fields.copy() else LinkedHashMap(fields)
}

/**
 * A struct instance (non-header). Structs have no validity bit. Used for P4's `struct` type,
 * including standard_metadata_t.
 */
data class StructVal(val typeName: String, val fields: MutableMap<String, Value> = mutableMapOf()) :
  Value() {
  fun copy(): StructVal = StructVal(typeName, copyFields())

  private fun copyFields(): MutableMap<String, Value> =
    if (fields is CompactFieldMap) (fields as CompactFieldMap).copy() else LinkedHashMap(fields)

  override fun deepCopy(): StructVal {
    if (fields is CompactFieldMap) {
      // Array.copyOf shares references. Leaf types (BitVal, BoolVal) are immutable and safe
      // to share; only mutable nested values need deep-copying.
      val copy = (fields as CompactFieldMap).copy()
      for ((key, value) in copy) {
        if (value is HeaderVal || value is StructVal || value is HeaderStackVal) {
          copy[key] = value.deepCopy()
        }
      }
      return StructVal(typeName, copy)
    }
    return StructVal(
      typeName,
      fields.mapValuesTo(LinkedHashMap(fields.size * 4 / 3 + 1)) { it.value.deepCopy() },
    )
  }

  /** P4 spec §8.20: a header union is valid if any member header is valid. */
  fun isUnionValid(): Boolean = fields.values.any { it is HeaderVal && it.valid }

  /** P4 spec §8.20: invalidating a header union invalidates all member headers. */
  fun invalidateUnion() {
    fields.values.forEach { if (it is HeaderVal) it.setInvalid() }
  }

  /** P4 spec §8.20: setting a union member valid invalidates all other members. */
  fun invalidateUnionExcept(keep: HeaderVal) {
    fields.values.forEach { if (it is HeaderVal && it !== keep) it.setInvalid() }
  }

  /** Overwrites a bit-valued field, preserving its IR-defined width. */
  fun setBitField(name: String, value: Long) {
    val existing = checkNotNull(fields[name] as? BitVal) { "$typeName.$name is not a bit field" }
    try {
      fields[name] = BitVal(value, existing.bits.width)
    } catch (e: IllegalArgumentException) {
      throw IllegalArgumentException("$typeName.$name: ${e.message}", e)
    }
  }

  /** Returns the bit width of a bit-valued field, as defined by the IR. */
  fun bitWidth(name: String): Int {
    val field = checkNotNull(fields[name] as? BitVal) { "$typeName.$name is not a bit field" }
    return field.bits.width
  }
}

/**
 * A header stack (fixed-size array of headers with a next/last pointer).
 *
 * Elements are typically [HeaderVal] but may be [StructVal] for header-union stacks (P4 spec
 * §8.18).
 */
data class HeaderStackVal(
  val elementTypeName: String,
  val headers: MutableList<Value>,
  var nextIndex: Int = 0,
) : Value() {
  val size: Int
    get() = headers.size

  override fun deepCopy(): HeaderStackVal =
    HeaderStackVal(elementTypeName, headers.mapTo(mutableListOf()) { it.deepCopy() }, nextIndex)
}

/** Sentinel for void returns and uninitialised variables. */
object UnitVal : Value()

/**
 * Views a [HeaderVal] or [StructVal] as a [StructVal] for read-only field-based operations
 * (hashing, checksums). Headers have the same `fields` map but carry an extra validity bit that
 * these operations don't need.
 *
 * The returned [StructVal] shares the original [HeaderVal]'s mutable field map — callers must not
 * mutate the returned struct's fields.
 */
fun Value.asStructVal(): StructVal =
  when (this) {
    is StructVal -> this
    is HeaderVal -> StructVal(typeName, fields)
    else -> error("expected struct or header, got ${this::class.simpleName}")
  }
