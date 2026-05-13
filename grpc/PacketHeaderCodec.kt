/*
 * Copyright 2026 4ward Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package fourward.grpc

import com.google.protobuf.ByteString
import fourward.BehavioralConfig
import fourward.Type
import p4.config.v1.P4InfoOuterClass.P4Info
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
 * - [Auto]: derive from p4info (`2^portBits - 2`). This is the default.
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
  data class FieldDef(val id: Int, val name: String, val bitWidth: Int)

  /** Total bytes of the serialized packet_out header. */
  val packetOutHeaderBytes: Int = (packetOutFields.sumOf { it.bitWidth } + 7) / 8

  /**
   * Total bits of the serialized packet_in header, derived from p4info field widths. Correctness
   * depends on p4info matching the behavioral config's `packet_in_header_t` type definition.
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
    // Fast path: byte-aligned header, no bit-shifting needed.
    if (packetInHeaderBits % 8 == 0) return payload.substring(packetInHeaderBytes)
    // Bit-level strip: remove exactly packetInHeaderBits from the front of the
    // continuous bit stream and repack the remaining bits into bytes.
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
    val outBytes = payloadBits / 8
    val result = ByteArray(outBytes)
    val skipBits = packetInHeaderBits
    for (i in 0 until outBytes) {
      val bitPos = skipBits + i * 8
      val byteIdx = bitPos / 8
      val bitOff = bitPos % 8
      var value = (bytes[byteIdx].toInt() and 0xFF) shl bitOff
      if (bitOff > 0 && byteIdx + 1 < bytes.size) {
        value = value or ((bytes[byteIdx + 1].toInt() and 0xFF) ushr (8 - bitOff))
      }
      result[i] = (value and 0xFF).toByte()
    }
    return ByteString.copyFrom(result)
  }

  /** Packs PacketOut metadata into a bit-packed binary header. */
  fun serializePacketOut(metadata: List<PacketMetadata>): ByteArray {
    val metadataById = metadata.associateBy { it.metadataId }
    return packFields(packetOutFields, metadataById)
  }

  /**
   * Packs the PacketOut header and payload into a continuous bit stream. The header fields are
   * packed at bit granularity followed immediately by the payload bits — no inter-field padding.
   * This avoids the corruption that byte-concatenation causes for non-byte-aligned headers.
   */
  @Suppress("MagicNumber")
  fun packPacketOut(metadata: List<PacketMetadata>, payload: ByteArray): ByteArray {
    val headerBits = packetOutFields.sumOf { it.bitWidth }
    if (headerBits % 8 == 0) return serializePacketOut(metadata) + payload
    val metadataById = metadata.associateBy { it.metadataId }
    val totalBits = headerBits + payload.size * 8
    val outBytes = (totalBits + 7) / 8
    val result = ByteArray(outBytes)
    // Pack header fields into bit positions 0..headerBits-1.
    var bitPos = 0
    for (field in packetOutFields) {
      val raw = metadataById[field.id]?.value?.toByteArray() ?: ByteArray(0)
      var v = 0L
      for (b in raw) v = (v shl 8) or (b.toLong() and 0xFF)
      if (field.bitWidth < Long.SIZE_BITS) v = v and ((1L shl field.bitWidth) - 1)
      for (bit in field.bitWidth - 1 downTo 0) {
        if (v and (1L shl bit) != 0L) {
          result[bitPos / 8] = (result[bitPos / 8].toInt() or (0x80 ushr (bitPos % 8))).toByte()
        }
        bitPos++
      }
    }
    // Pack payload bytes immediately after the header bits.
    for (b in payload) {
      val byteIdx = bitPos / 8
      val bitOff = bitPos % 8
      result[byteIdx] = (result[byteIdx].toInt() or ((b.toInt() and 0xFF) ushr bitOff)).toByte()
      if (bitOff > 0 && byteIdx + 1 < outBytes) {
        result[byteIdx + 1] = ((b.toInt() and 0xFF) shl (8 - bitOff)).toByte()
      }
      bitPos += 8
    }
    return result
  }

  /**
   * Builds PacketIn metadata from ingress and egress port values.
   *
   * Metadata is constructed from the simulation context rather than parsed from the deparsed
   * payload. The caller is responsible for stripping the `@controller_header("packet_in")` bytes
   * from the payload (see [packetInHeaderBytes]).
   */
  fun buildPacketInMetadata(ingressPort: Int, egressPort: Int): List<PacketMetadata> =
    packetInFields.map { field ->
      val value =
        when (field.name) {
          "ingress_port" -> ingressPort
          "target_egress_port" -> egressPort
          else -> 0
        }
      PacketMetadata.newBuilder().setMetadataId(field.id).setValue(encodeMinWidth(value)).build()
    }

  @Suppress("MagicNumber")
  private fun packFields(fields: List<FieldDef>, metadata: Map<Int, PacketMetadata>): ByteArray {
    var bits = 0L
    var totalBits = 0
    for (field in fields) {
      val value = metadata[field.id]?.value?.toByteArray() ?: ByteArray(0)
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

      // Build header-type → field-name → bitwidth map from behavioral config.
      val headerWidths = buildMap {
        for (type in behavioral.typesList) {
          if (type.hasHeader()) {
            put(type.name, type.header.fieldsList.associate { it.name to bitWidth(it.type) })
          }
        }
      }

      val packetOutType = headerWidths["packet_out_header_t"]
      val packetOutFields =
        packetOutMeta.metadataList.map { meta ->
          FieldDef(
            id = meta.id,
            name = meta.name,
            bitWidth =
              if (meta.bitwidth > 0) meta.bitwidth
              else
                packetOutType?.get(meta.name)
                  ?: error("cannot determine bitwidth for packet_out.${meta.name}"),
          )
        }

      // CPU port = 2^portBits - 2 (v1model convention; drop port is 2^W - 1).
      val portBits =
        packetOutFields.find { it.name == "egress_port" }?.bitWidth ?: DEFAULT_PORT_BITS
      val cpuPort = cpuPortOverride ?: ((1 shl portBits) - 2)

      val packetInMeta =
        p4info.controllerPacketMetadataList.find { it.preamble.name == "packet_in" }
      val packetInType = headerWidths["packet_in_header_t"]
      val packetInFields =
        packetInMeta?.metadataList?.map { meta ->
          FieldDef(
            id = meta.id,
            name = meta.name,
            bitWidth =
              if (meta.bitwidth > 0) meta.bitwidth else packetInType?.get(meta.name) ?: portBits,
          )
        } ?: emptyList()

      return PacketHeaderCodec(packetOutFields, packetInFields, cpuPort)
    }

    private const val DEFAULT_PORT_BITS = 9

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
