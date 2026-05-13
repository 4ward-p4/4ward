/*
 * Copyright 2026 4ward Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package fourward.grpc

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

  /** Total bytes of the serialized packet_in header (stripped from deparsed payload on punt). */
  val packetInHeaderBytes: Int = (packetInFields.sumOf { it.bitWidth } + 7) / 8

  /** Packs PacketOut metadata into a bit-packed binary header. */
  fun serializePacketOut(metadata: List<PacketMetadata>): ByteArray {
    val metadataById = metadata.associateBy { it.metadataId }
    return packFields(packetOutFields, metadataById)
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

      // The deparser emits the full header type (including @padding fields). Byte
      // alignment is required so that stripping the header preserves the remaining
      // payload byte-for-byte. Validate using the behavioral config widths, which
      // include @padding fields that p4info metadata may omit.
      requireByteAligned("packet_out_header_t", packetOutType)
      requireByteAligned("packet_in_header_t", packetInType)

      return PacketHeaderCodec(packetOutFields, packetInFields, cpuPort)
    }

    @Suppress("MagicNumber")
    private fun requireByteAligned(headerName: String, fieldWidths: Map<String, Int>?) {
      if (fieldWidths == null) return
      val totalBits = fieldWidths.values.sum()
      require(totalBits % 8 == 0) {
        "@controller_header $headerName is $totalBits bits, which is not byte-aligned. " +
          "Add a @padding field to round to a multiple of 8."
      }
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
