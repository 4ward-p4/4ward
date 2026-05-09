/*
 * Copyright 2026 4ward Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package fourward.grpc

import fourward.BehavioralConfig
import fourward.BitType
import fourward.FieldDecl
import fourward.HeaderDecl
import fourward.Type
import fourward.TypeDecl
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import p4.config.v1.P4InfoOuterClass.ControllerPacketMetadata
import p4.config.v1.P4InfoOuterClass.P4Info
import p4.config.v1.P4InfoOuterClass.Preamble
import p4.v1.P4RuntimeOuterClass.PacketMetadata

/**
 * Unit tests for [PacketHeaderCodec].
 *
 * Validates bit-packing of PacketOut metadata into binary headers and construction of PacketIn
 * metadata from port values.
 */
class PacketHeaderCodecTest {

  // =========================================================================
  // create()
  // =========================================================================

  @Test
  fun `create returns null when no controller_packet_metadata defined`() {
    val p4info = P4Info.getDefaultInstance()
    val behavioral = BehavioralConfig.getDefaultInstance()
    assertNull(PacketHeaderCodec.create(p4info, behavioral))
  }

  @Test
  fun `create builds codec from p4info and behavioral config`() {
    val (p4info, behavioral) = buildSaiLikeConfig()
    val codec = PacketHeaderCodec.create(p4info, behavioral)
    assertNotNull(codec)
    assertEquals(2, codec!!.packetOutHeaderBytes)
    // CPU port = 2^9 - 2 = 510
    assertEquals(510, codec.cpuPort)
  }

  // =========================================================================
  // serializePacketOut
  // =========================================================================

  @Test
  fun `serializePacketOut packs all-zero fields correctly`() {
    val (p4info, behavioral) = buildSaiLikeConfig()
    val codec = PacketHeaderCodec.create(p4info, behavioral)!!

    val metadata =
      listOf(
        buildMetadata(id = 1, value = byteArrayOf(0)),
        buildMetadata(id = 2, value = byteArrayOf(0)),
      )
    val header = codec.serializePacketOut(metadata)
    // 9 bits egress_port=0, 1 bit submit_to_ingress=0, 6 bits padding=0
    // = 0x0000
    assertEquals(2, header.size)
    assertArrayEquals(byteArrayOf(0, 0), header)
  }

  @Test
  @Suppress("MagicNumber")
  fun `serializePacketOut packs egress_port=1 correctly`() {
    val (p4info, behavioral) = buildSaiLikeConfig()
    val codec = PacketHeaderCodec.create(p4info, behavioral)!!

    val metadata =
      listOf(
        buildMetadata(id = 1, value = byteArrayOf(1)), // egress_port=1
        buildMetadata(id = 2, value = byteArrayOf(0)), // submit_to_ingress=0
      )
    val header = codec.serializePacketOut(metadata)
    // 9 bits: 000000001, 1 bit: 0, 6 bits padding: 000000
    // = 00000000 10000000 = [0x00, 0x80]
    assertEquals(2, header.size)
    assertArrayEquals(byteArrayOf(0x00, 0x80.toByte()), header)
  }

  @Test
  @Suppress("MagicNumber")
  fun `serializePacketOut packs submit_to_ingress=1 correctly`() {
    val (p4info, behavioral) = buildSaiLikeConfig()
    val codec = PacketHeaderCodec.create(p4info, behavioral)!!

    val metadata =
      listOf(
        buildMetadata(id = 1, value = byteArrayOf(0)),
        buildMetadata(id = 2, value = byteArrayOf(1)), // submit_to_ingress=1
      )
    val header = codec.serializePacketOut(metadata)
    // 9 bits: 000000000, 1 bit: 1, 6 bits padding: 000000
    // = 00000000 01000000 = [0x00, 0x40]
    assertEquals(2, header.size)
    assertArrayEquals(byteArrayOf(0x00, 0x40), header)
  }

  @Test
  @Suppress("MagicNumber")
  fun `serializePacketOut packs both fields correctly`() {
    val (p4info, behavioral) = buildSaiLikeConfig()
    val codec = PacketHeaderCodec.create(p4info, behavioral)!!

    val metadata =
      listOf(
        buildMetadata(id = 1, value = byteArrayOf(0x01, 0xFE.toByte())), // egress_port=510
        buildMetadata(id = 2, value = byteArrayOf(1)), // submit_to_ingress=1
      )
    val header = codec.serializePacketOut(metadata)
    // 9 bits: 111111110 (510), 1 bit: 1, 6 bits padding: 000000
    // = 11111111 01000000 = [0xFF, 0x40]
    assertEquals(2, header.size)
    assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x40), header)
  }

  // =========================================================================
  // buildPacketInMetadata
  // =========================================================================

  @Test
  @Suppress("MagicNumber")
  fun `buildPacketInMetadata produces correct metadata`() {
    val (p4info, behavioral) = buildSaiLikeConfig()
    val codec = PacketHeaderCodec.create(p4info, behavioral)!!

    val metadata = codec.buildPacketInMetadata(ingressPort = 510, egressPort = 1)
    assertEquals(2, metadata.size)

    // First field = ingress_port (metadata_id=3 in our test config)
    assertEquals(3, metadata[0].metadataId)
    val ingressBytes = metadata[0].value.toByteArray()
    assertEquals(510, bytesToInt(ingressBytes))

    // Second field = target_egress_port (metadata_id=4)
    assertEquals(4, metadata[1].metadataId)
    val egressBytes = metadata[1].value.toByteArray()
    assertEquals(1, bytesToInt(egressBytes))
  }

  // =========================================================================
  // Helpers
  // =========================================================================

  private fun bytesToInt(bytes: ByteArray): Int {
    var result = 0
    for (b in bytes) result = (result shl 8) or (b.toInt() and 0xFF)
    return result
  }

  private fun buildMetadata(id: Int, value: ByteArray): PacketMetadata =
    PacketMetadata.newBuilder()
      .setMetadataId(id)
      .setValue(com.google.protobuf.ByteString.copyFrom(value))
      .build()

  /**
   * Builds a SAI-like p4info + behavioral config:
   * - packet_out: egress_port (9-bit, id=1), submit_to_ingress (1-bit, id=2)
   * - packet_in: ingress_port (9-bit, id=3), target_egress_port (9-bit, id=4)
   * - Behavioral config with header types for field width resolution.
   */
  @Suppress("MagicNumber")
  private fun buildSaiLikeConfig(): Pair<P4Info, BehavioralConfig> {
    val p4info =
      P4Info.newBuilder()
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_out"))
            .addMetadata(meta(1, "egress_port"))
            .addMetadata(meta(2, "submit_to_ingress"))
        )
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_in"))
            .addMetadata(meta(3, "ingress_port"))
            .addMetadata(meta(4, "target_egress_port"))
        )
        .build()

    val behavioral =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_out_header_t")
            .setHeader(
              HeaderDecl.newBuilder()
                .addFields(bitField("egress_port", 9))
                .addFields(bitField("submit_to_ingress", 1))
                .addFields(bitField("unused_pad", 6))
            )
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_in_header_t")
            .setHeader(
              HeaderDecl.newBuilder()
                .addFields(bitField("ingress_port", 9))
                .addFields(bitField("target_egress_port", 9))
            )
        )
        .build()

    return p4info to behavioral
  }

  private fun meta(id: Int, name: String): ControllerPacketMetadata.Metadata =
    ControllerPacketMetadata.Metadata.newBuilder().setId(id).setName(name).build()

  private fun bitField(name: String, width: Int): FieldDecl =
    FieldDecl.newBuilder()
      .setName(name)
      .setType(Type.newBuilder().setBit(BitType.newBuilder().setWidth(width)))
      .build()

  // =========================================================================
  // CPU port override
  // =========================================================================

  @Test
  fun `cpu port override replaces derived value`() {
    val (p4info, behavioral) = buildSaiLikeConfig()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 42)
    assertNotNull(codec)
    assertEquals(42, codec!!.cpuPort)
  }

  @Test
  fun `cpu port override null uses default derivation`() {
    val (p4info, behavioral) = buildSaiLikeConfig()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = null)
    assertNotNull(codec)
    // Default: 2^9 - 2 = 510
    assertEquals(510, codec!!.cpuPort)
  }

  // =========================================================================
  // CpuPortConfig.fromFlag
  // =========================================================================

  @Test
  fun `fromFlag null returns Auto`() {
    assertEquals(CpuPortConfig.Auto, CpuPortConfig.fromFlag(null))
  }

  @Test
  fun `fromFlag none returns Disabled`() {
    assertEquals(CpuPortConfig.Disabled, CpuPortConfig.fromFlag("none"))
    assertEquals(CpuPortConfig.Disabled, CpuPortConfig.fromFlag("NONE"))
  }

  @Test
  fun `fromFlag integer returns Override with Dataplane port`() {
    assertEquals(CpuPortConfig.Override(PortOverride.Dataplane(510)), CpuPortConfig.fromFlag("510"))
    assertEquals(CpuPortConfig.Override(PortOverride.Dataplane(0)), CpuPortConfig.fromFlag("0"))
  }

  @Test
  fun `fromFlag non-integer returns Override with P4rt port`() {
    assertEquals(
      CpuPortConfig.Override(PortOverride.P4rt("CpuPort")),
      CpuPortConfig.fromFlag("CpuPort"),
    )
  }
}
