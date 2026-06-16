/*
 * Copyright 2026 4ward Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package fourward.grpc

import com.google.protobuf.ByteString
import fourward.BehavioralConfig
import fourward.Type
import fourward.simulator.BitAccumulator
import java.math.BigInteger
import p4.config.v1.P4InfoOuterClass.P4Info
import p4.v1.P4RuntimeOuterClass.PacketIn
import p4.v1.P4RuntimeOuterClass.PacketMetadata

/**
 * A port override that is either a raw dataplane port number or a P4Runtime port name that gets
 * resolved at pipeline load time.
 */
sealed interface PortOverride {
  data class Dataplane(val port: Int) : PortOverride

  data class P4rt(val name: String) : PortOverride

  companion object {
    /** Parses a CLI flag value: integer → [Dataplane], anything else → [P4rt]. */
    fun fromFlag(value: String): PortOverride = value.toIntOrNull()?.let(::Dataplane) ?: P4rt(value)
  }
}

/**
 * Three-state configuration for the CPU port.
 * - [Auto]: derive from the architecture dataplane port width (`2^portBits - 2`). This is the
 *   default.
 * - [Override]: use an explicit port value (dataplane integer or P4RT name).
 * - [Disabled]: no CPU port, even if the p4info has `ControllerPacketMetadata`. Packets egressing
 *   on what would have been the CPU port are treated as regular output; PacketOut is rejected.
 */
sealed interface CpuPortConfig {
  data object Auto : CpuPortConfig

  data class Override(val portOverride: PortOverride) : CpuPortConfig

  data object Disabled : CpuPortConfig

  companion object {
    /** Parses a CLI flag value: null → [Auto], "none" → [Disabled], otherwise → [Override]. */
    fun fromFlag(value: String?): CpuPortConfig =
      when {
        value == null -> Auto
        value.equals("none", ignoreCase = true) -> Disabled
        else -> Override(PortOverride.fromFlag(value))
      }
  }
}

/**
 * Converts P4Runtime PacketOut/PacketIn metadata to/from the bit-packed binary headers expected by
 * the P4 parser and deparser.
 *
 * The P4Runtime spec defines PacketOut metadata as structured fields corresponding to the
 * `@controller_header("packet_out")` header. This codec packs metadata values into a binary header
 * that gets prepended to the payload before the simulator processes it.
 */
class PacketHeaderCodec
private constructor(
  private val packetOutFields: List<FieldDef>,
  private val packetInFields: List<FieldDef>,
  /** The CPU port for PacketOut packets (v1model convention: 2^portBits - 2). */
  val cpuPort: Int,
) {
  data class FieldDef(val id: Int?, val name: String, val bitWidth: Int)

  private val packetOutHeaderBits: Int = packetOutFields.sumOf { it.bitWidth }

  /** Total bytes of the serialized packet_out header. */
  val packetOutHeaderBytes: Int = (packetOutHeaderBits + 7) / 8

  /**
   * Total bits of the serialized packet_in header, derived from @controller_header("packet_in").
   */
  val packetInHeaderBits: Int = packetInFields.sumOf { it.bitWidth }

  /** Total bytes of the serialized packet_in header (stripped from deparsed payload on punt). */
  val packetInHeaderBytes: Int = (packetInHeaderBits + 7) / 8

  /**
   * Strips the `@controller_header("packet_in")` from the front of a deparsed payload.
   *
   * The deparser emits the controller header as a continuous bit stream before the remaining packet
   * headers. This method removes exactly [packetInHeaderBits] bits from the front and returns the
   * remaining payload, byte-aligned with trailing zero padding.
   */
  @Suppress("MagicNumber")
  fun stripPacketInHeader(payload: ByteString): ByteString {
    if (packetInHeaderBits == 0) return payload
    if (packetInHeaderBits % 8 == 0) return payload.substring(packetInHeaderBytes)
    val bytes = payload.toByteArray()
    // The deparsed byte array contains: headerBits + payloadBits + trailingPadBits,
    // where trailingPadBits < 8 are zeros added by byte-alignment at the end.
    // The payload (everything after the controller header) is always a whole number
    // of bytes: it consists of deparser-emitted headers (byte-aligned by the P4
    // spec for all real targets) followed by the unparsed packet remainder (a suffix
    // of the original byte-array input). Integer division by 8 recovers the exact
    // payload byte count, discarding only the trailing pad zeros.
    val totalBits = bytes.size * 8
    val payloadBits = (totalBits - packetInHeaderBits) / 8 * 8
    check(payloadBits % 8 == 0)
    if (payloadBits <= 0) return ByteString.EMPTY
    val discardedBits = totalBits - packetInHeaderBits - payloadBits
    if (discardedBits > 0) {
      val trailingMask = (1 shl discardedBits) - 1
      check(bytes.last().toInt() and trailingMask == 0) {
        "expected $discardedBits trailing zero-pad bits, but last byte is 0x${
          "%02X".format(bytes.last())
        } — payload after @controller_header may not be byte-aligned"
      }
    }
    val acc = BitAccumulator()
    acc.appendRawBytes(bytes, packetInHeaderBits, payloadBits)
    return ByteString.copyFrom(acc.toByteArray())
  }

  /** Packs PacketOut metadata into a bit-packed binary header. */
  fun serializePacketOut(metadata: List<PacketMetadata>): ByteArray {
    validatePacketOutMetadata(metadata)
    val metadataById = metadata.associateBy { it.metadataId }
    return packFields(packetOutFields, metadataById)
  }

  /**
   * Packs the PacketOut controller header followed immediately by the byte payload.
   *
   * The P4Runtime spec defines PacketOut metadata from the P4 header annotated
   * `@controller_header("packet_out")`. The P4 parser extracts that header's declared fields, then
   * continues at the next bit. Therefore fields present in the annotated header but absent from
   * P4Info, such as `@padding` fields, are serialized as zero bits in their declared positions, and
   * no extra byte-alignment gap is inserted before the payload.
   */
  @Suppress("MagicNumber")
  fun packPacketOut(metadata: List<PacketMetadata>, payload: ByteArray): ByteArray {
    validatePacketOutMetadata(metadata)
    val metadataById = metadata.associateBy { it.metadataId }
    val acc = BitAccumulator()
    for (field in packetOutFields) {
      val raw = field.id?.let { metadataById[it]?.value?.toByteArray() } ?: ByteArray(0)
      var v = 0L
      for (b in raw) v = (v shl 8) or (b.toLong() and 0xFF)
      if (field.bitWidth < Long.SIZE_BITS) v = v and ((1L shl field.bitWidth) - 1)
      acc.append(BigInteger.valueOf(v), field.bitWidth)
    }
    acc.appendRawBytes(payload, 0, payload.size * 8)
    return acc.toByteArray()
  }

  private fun validatePacketOutMetadata(metadata: List<PacketMetadata>) {
    val expected = packetOutFields.mapNotNull { it.id }.toSet()
    val actual = metadata.map { it.metadataId }
    require(actual.size == actual.toSet().size) { "duplicate packet_out metadata ID" }
    require(actual.toSet() == expected) {
      "packet_out metadata IDs must match P4Info: expected ${expected.sorted()}, " +
        "got ${actual.sorted()}"
    }
  }

  /** Number of meaningful packet bits in [packPacketOut]'s byte-aligned output. */
  fun packedPacketOutBitLength(payloadBytes: Int): Int =
    packetOutHeaderBits + payloadBytes * Byte.SIZE_BITS

  /**
   * Decodes a deparsed payload into a [PacketIn]: parses the leading
   * `@controller_header("packet_in")` bits into metadata fields and returns the remaining packet as
   * the payload.
   *
   * The metadata is read positionally from the annotated header bit stream. Fields omitted from
   * P4Info, such as padding, are consumed but not reported. Values are never reconstructed from
   * simulator state or program-specific field names, so PacketIn reflects exactly what the P4
   * program assigned (e.g. a punted-but-dropped packet reports its intended egress port, not the
   * CPU port it physically left on).
   */
  fun decodePacketIn(deparsedPayload: ByteString): PacketIn =
    PacketIn.newBuilder()
      .setPayload(stripPacketInHeader(deparsedPayload))
      .addAllMetadata(parsePacketInMetadata(deparsedPayload))
      .build()

  /** Reads the `packet_in` controller-header fields from the front of the deparsed [payload]. */
  private fun parsePacketInMetadata(payload: ByteString): List<PacketMetadata> {
    if (packetInFields.isEmpty()) return emptyList()
    val bytes = payload.toByteArray()
    require(bytes.size * 8 >= packetInHeaderBits) {
      "deparsed payload (${bytes.size} bytes) is shorter than the " +
        "$packetInHeaderBits-bit packet_in controller header"
    }
    var bitOffset = 0
    return packetInFields.mapNotNull { field ->
      val value = readBits(bytes, bitOffset, field.bitWidth)
      bitOffset += field.bitWidth
      val id = field.id ?: return@mapNotNull null
      PacketMetadata.newBuilder().setMetadataId(id).setValue(encodeMinWidth(value)).build()
    }
  }

  /**
   * Reads [width] bits MSB-first from [bytes] starting at bit offset [bitOffset], as an unsigned.
   */
  @Suppress("MagicNumber")
  private fun readBits(bytes: ByteArray, bitOffset: Int, width: Int): BigInteger {
    var result = BigInteger.ZERO
    var pos = bitOffset
    var remaining = width
    while (remaining > 0) {
      val bitInByte = pos % 8
      val available = 8 - bitInByte
      val take = minOf(available, remaining)
      val shift = available - take
      val chunk = ((bytes[pos / 8].toInt() and 0xFF) ushr shift) and ((1 shl take) - 1)
      result = result.shiftLeft(take).or(BigInteger.valueOf(chunk.toLong()))
      pos += take
      remaining -= take
    }
    return result
  }

  @Suppress("MagicNumber")
  private fun packFields(fields: List<FieldDef>, metadata: Map<Int, PacketMetadata>): ByteArray {
    var bits = 0L
    var totalBits = 0
    for (field in fields) {
      val value = field.id?.let { metadata[it]?.value?.toByteArray() } ?: ByteArray(0)
      var v = 0L
      for (b in value) v = (v shl 8) or (b.toLong() and 0xFF)
      if (field.bitWidth < Long.SIZE_BITS) v = v and ((1L shl field.bitWidth) - 1)
      bits = (bits shl field.bitWidth) or v
      totalBits += field.bitWidth
    }
    val totalBytes = (totalBits + 7) / 8
    // Left-align: p4info excludes @padding fields, but the parser reads from
    // MSB. Shift data bits to the top of the byte-aligned output.
    val paddingBits = totalBytes * 8 - totalBits
    bits = bits shl paddingBits
    val result = ByteArray(totalBytes)
    for (i in totalBytes - 1 downTo 0) {
      result[i] = (bits and 0xFF).toByte()
      bits = bits shr 8
    }
    return result
  }

  companion object {
    /**
     * Creates a codec from the pipeline config, or null if no `controller_packet_metadata` is
     * defined (programs without `@controller_header`).
     *
     * @param cpuPortOverride If non-null, uses this value instead of deriving `2^portBits - 2`.
     */
    fun create(
      p4info: P4Info,
      behavioral: BehavioralConfig,
      cpuPortOverride: Int? = null,
    ): PacketHeaderCodec? {
      val packetOutMeta =
        p4info.controllerPacketMetadataList.find { it.preamble.name == "packet_out" } ?: return null

      val packetOutMetaByName = packetOutMeta.metadataList.associateBy { it.name }
      val packetOutType = controllerHeaderLayout(behavioral, "packet_out")
      val packetOutFieldNames = packetOutType.map { it.name }.toSet()
      val unknownPacketOutMetadata =
        packetOutMeta.metadataList.map { it.name } - packetOutFieldNames
      require(unknownPacketOutMetadata.isEmpty()) {
        "packet_out metadata field(s) not found in @controller_header(\"packet_out\"): " +
          unknownPacketOutMetadata.joinToString()
      }
      val packetOutFields =
        packetOutType.map { field ->
          FieldDef(
            id = packetOutMetaByName[field.name]?.id,
            name = field.name,
            bitWidth = field.bitWidth,
          )
        }

      val cpuPort = cpuPortOverride ?: deriveAutoCpuPort(architectureIngressPortBits(behavioral))

      val packetInMeta =
        p4info.controllerPacketMetadataList.find { it.preamble.name == "packet_in" }
      val packetInType = packetInMeta?.let { controllerHeaderLayout(behavioral, "packet_in") }
      val packetInMetaByName = packetInMeta?.metadataList?.associateBy { it.name }
      val packetInFieldNames = packetInType?.map { it.name }?.toSet() ?: emptySet()
      val unknownPacketInMetadata =
        packetInMeta?.metadataList?.map { it.name }?.minus(packetInFieldNames) ?: emptyList()
      require(unknownPacketInMetadata.isEmpty()) {
        "packet_in metadata field(s) not found in @controller_header(\"packet_in\"): " +
          unknownPacketInMetadata.joinToString()
      }
      val packetInFields =
        packetInType?.map { field ->
          FieldDef(
            id = packetInMetaByName?.get(field.name)?.id,
            name = field.name,
            bitWidth = field.bitWidth,
          )
        } ?: emptyList()

      return PacketHeaderCodec(packetOutFields, packetInFields, cpuPort)
    }

    private const val STANDARD_METADATA_TYPE = "standard_metadata_t"

    private fun controllerHeaderLayout(
      behavioral: BehavioralConfig,
      controllerHeader: String,
    ): List<FieldDef> {
      val matches =
        behavioral.typesList.filter {
          it.hasHeader() && it.header.controllerHeader == controllerHeader
        }
      require(matches.size == 1) {
        "expected exactly one header annotated @controller_header(\"$controllerHeader\"), " +
          "found ${matches.size}"
      }
      return matches.single().header.fieldsList.map { FieldDef(null, it.name, bitWidth(it.type)) }
    }

    private fun architectureIngressPortBits(behavioral: BehavioralConfig): Int {
      val standardMetadata =
        behavioral.typesList.singleOrNull { it.name == STANDARD_METADATA_TYPE && it.hasStruct() }
      require(standardMetadata != null) { "$STANDARD_METADATA_TYPE not found in behavioral IR" }
      val ingressPort =
        standardMetadata.struct.fieldsList.singleOrNull { it.name == "ingress_port" }
      require(ingressPort != null) { "$STANDARD_METADATA_TYPE has no ingress_port field" }
      return bitWidth(ingressPort.type)
    }

    private fun deriveAutoCpuPort(portBits: Int): Int {
      require(portBits in 1..Int.SIZE_BITS) {
        "automatic CPU port derivation requires a 1..32-bit dataplane port, got $portBits bits"
      }
      return BigInteger.ONE.shiftLeft(portBits).subtract(BigInteger.TWO).toInt()
    }

    private fun bitWidth(type: Type): Int =
      when (type.kindCase) {
        Type.KindCase.BIT -> type.bit.width
        Type.KindCase.SIGNED_INT -> type.signedInt.width
        Type.KindCase.BOOLEAN -> 1
        Type.KindCase.VARBIT,
        Type.KindCase.NAMED,
        Type.KindCase.HEADER_STACK,
        Type.KindCase.ERROR,
        Type.KindCase.KIND_NOT_SET,
        null -> error("unsupported type in packet header: $type")
      }
  }
}
