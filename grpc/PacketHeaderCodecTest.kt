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
import fourward.StructDecl
import fourward.Type
import fourward.TypeDecl
import fourward.simulator.BitAccumulator
import fourward.simulator.DataplanePort
import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import p4.config.v1.P4InfoOuterClass.ControllerPacketMetadata
import p4.config.v1.P4InfoOuterClass.P4Info
import p4.config.v1.P4InfoOuterClass.Preamble
import p4.v1.P4RuntimeOuterClass.PacketMetadata

/**
 * Unit tests for [PacketHeaderCodec].
 *
 * Validates bit-packing of PacketOut metadata into binary headers and decoding of PacketIn metadata
 * from the deparsed `@controller_header("packet_in")` bits.
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
    // packet_in: 9-bit ingress_port + 9-bit target_egress_port = 18 bits = 3 bytes
    assertEquals(3, codec.packetInHeaderBytes)
    // CPU port = 2^9 - 2 = 510
    assertEquals(510, codec.cpuPort.protoValue)
  }

  @Test
  @Suppress("MagicNumber")
  fun `create resolves packet io headers by controller_header annotation, not type name`() {
    val p4info =
      P4Info.newBuilder()
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_out"))
            .addMetadata(metaWithBitwidth(1, "egress_port", 8))
            .addMetadata(metaWithBitwidth(2, "submit_to_ingress", 1))
        )
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_in"))
            .addMetadata(metaWithBitwidth(3, "ingress_port", 5))
            .addMetadata(metaWithBitwidth(4, "reason", 3))
        )
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("ArbitraryPacketOutName_t")
            .setHeader(
              controllerHeader(
                "packet_out",
                bitField("egress_port", 8),
                bitField("submit_to_ingress", 1),
                bitField("unused_pad", 7),
              )
            )
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("AnotherArbitraryPacketInName_t")
            .setHeader(
              controllerHeader("packet_in", bitField("ingress_port", 5), bitField("reason", 3))
            )
        )
        .build()

    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!

    assertEquals(2, codec.packetOutHeaderBytes)
    assertEquals(1, codec.packetInHeaderBytes)
    val packed =
      codec.packPacketOut(
        listOf(buildMetadata(1, byteArrayOf(0x12)), buildMetadata(2, byteArrayOf(1))),
        byteArrayOf(0x34),
      )
    assertArrayEquals(byteArrayOf(0x12, 0x80.toByte(), 0x34), packed)
  }

  @Test
  fun `create rejects packet_out metadata without packet_out controller header`() {
    val p4info =
      P4Info.newBuilder()
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_out"))
            .addMetadata(metaWithBitwidth(1, "egress_port", 8))
        )
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("PacketOut_t")
            .setHeader(HeaderDecl.newBuilder().addFields(bitField("egress_port", 8)))
        )
        .build()

    assertThrows(IllegalArgumentException::class.java) {
      PacketHeaderCodec.create(p4info, behavioral)
    }
  }

  @Test
  fun `create rejects packet_in metadata without packet_in controller header`() {
    val p4info =
      P4Info.newBuilder()
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_out"))
            .addMetadata(metaWithBitwidth(1, "egress_port", 8))
        )
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_in"))
            .addMetadata(metaWithBitwidth(2, "ingress_port", 8))
        )
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("PacketOut_t")
            .setHeader(controllerHeader("packet_out", bitField("egress_port", 8)))
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("PacketIn_t")
            .setHeader(HeaderDecl.newBuilder().addFields(bitField("ingress_port", 8)))
        )
        .addTypes(standardMetadataType())
        .build()

    assertThrows(IllegalArgumentException::class.java) {
      PacketHeaderCodec.create(p4info, behavioral)
    }
  }

  @Test
  fun `create rejects duplicate controller headers for one direction`() {
    val p4info =
      P4Info.newBuilder()
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_out"))
            .addMetadata(metaWithBitwidth(1, "egress_port", 8))
        )
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("PacketOutA_t")
            .setHeader(controllerHeader("packet_out", bitField("egress_port", 8)))
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("PacketOutB_t")
            .setHeader(controllerHeader("packet_out", bitField("egress_port", 8)))
        )
        .build()

    assertThrows(IllegalArgumentException::class.java) {
      PacketHeaderCodec.create(p4info, behavioral)
    }
  }

  // =========================================================================
  // packPacketOut
  // =========================================================================

  @Test
  @Suppress("MagicNumber")
  fun `packPacketOut bit-packs non-byte-aligned header with payload`() {
    // 5-bit packet_out header (port=1 = 0b00001) + 2-byte payload (0xAA 0xBB).
    // The annotated P4 header is 5 bits, so the payload begins immediately after bit 5.
    // Continuous bit stream: 00001_10101010_10111011_000
    // = 00001101 01010101 11011000
    // = 0x0D     0x55     0xD8
    val p4info =
      P4Info.newBuilder()
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_out"))
            .addMetadata(metaWithBitwidth(1, "port", 5))
        )
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_in"))
            .addMetadata(metaWithBitwidth(2, "ingress_port", 8))
        )
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_out_header_t")
            .setHeader(controllerHeader("packet_out", bitField("port", 5)))
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_in_header_t")
            .setHeader(controllerHeader("packet_in", bitField("ingress_port", 8)))
        )
        .build()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!

    val metadata = listOf(buildMetadata(id = 1, value = byteArrayOf(1))) // port=1
    val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
    val packed = codec.packPacketOut(metadata, payload)

    assertEquals(3, packed.size)
    assertArrayEquals(byteArrayOf(0x0D, 0x55, 0xD8.toByte()), packed)
    assertEquals(21, codec.packedPacketOutBitLength(payload.size))
  }

  @Test
  @Suppress("MagicNumber")
  fun `packPacketOut includes behavioral padding fields omitted from p4info`() {
    val (p4info, behavioral) = buildSaiLikeConfig()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!
    val metadata =
      listOf(
        buildMetadata(id = 1, value = byteArrayOf(1)), // egress_port=1
        buildMetadata(id = 2, value = byteArrayOf(1)), // submit_to_ingress=1
      )
    val payload = byteArrayOf(0x74, 0x65)

    val packed = codec.packPacketOut(metadata, payload)

    // Behavioral packet_out_header_t is 9 + 1 + 6 bits. The P4Info metadata omits unused_pad,
    // but the parser still extracts those declared header bits before the Ethernet payload.
    assertArrayEquals(byteArrayOf(0x00, 0xC0.toByte(), 0x74, 0x65), packed)
    assertEquals(32, codec.packedPacketOutBitLength(payload.size))
  }

  @Test
  @Suppress("MagicNumber")
  fun `packPacketOut includes 39-bit controller header before payload`() {
    val p4info =
      P4Info.newBuilder()
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_out"))
            .addMetadata(metaWithBitwidth(1, "egress_port", 32))
            .addMetadata(metaWithBitwidth(2, "submit_to_ingress", 1))
        )
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_out_header_t")
            .setHeader(
              controllerHeader(
                "packet_out",
                bitField("egress_port", 32),
                bitField("submit_to_ingress", 1),
                bitField("unused_pad", 6),
              )
            )
        )
        .build()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!
    val metadata =
      listOf(
        buildMetadata(id = 1, value = byteArrayOf(0, 0, 0, 1)),
        buildMetadata(id = 2, value = byteArrayOf(1)),
      )
    val payload = byteArrayOf(0x74, 0x65)

    val packed = codec.packPacketOut(metadata, payload)

    assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x80.toByte(), 0xE8.toByte(), 0xCA.toByte()), packed)
    assertEquals(55, codec.packedPacketOutBitLength(payload.size))
  }

  @Test
  fun `packPacketOut rejects missing metadata field`() {
    val (p4info, behavioral) = buildSaiLikeConfig()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!

    assertThrows(IllegalArgumentException::class.java) {
      codec.packPacketOut(listOf(buildMetadata(id = 1, value = byteArrayOf(1))), byteArrayOf())
    }
  }

  @Test
  fun `packPacketOut rejects unknown metadata field`() {
    val (p4info, behavioral) = buildSaiLikeConfig()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!

    assertThrows(IllegalArgumentException::class.java) {
      codec.packPacketOut(
        listOf(
          buildMetadata(id = 1, value = byteArrayOf(1)),
          buildMetadata(id = 2, value = byteArrayOf(0)),
          buildMetadata(id = 99, value = byteArrayOf(0)),
        ),
        byteArrayOf(),
      )
    }
  }

  @Test
  fun `packPacketOut rejects duplicate metadata field`() {
    val (p4info, behavioral) = buildSaiLikeConfig()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!

    assertThrows(IllegalArgumentException::class.java) {
      codec.packPacketOut(
        listOf(
          buildMetadata(id = 1, value = byteArrayOf(1)),
          buildMetadata(id = 1, value = byteArrayOf(2)),
          buildMetadata(id = 2, value = byteArrayOf(0)),
        ),
        byteArrayOf(),
      )
    }
  }

  @Test
  @Suppress("MagicNumber")
  fun `packPacketOut byte-aligned header matches serializePacketOut + concat`() {
    // 16-bit packet_out header (two 8-bit fields) — byte-aligned.
    val p4info =
      P4Info.newBuilder()
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_out"))
            .addMetadata(metaWithBitwidth(1, "egress_port", 8))
            .addMetadata(metaWithBitwidth(2, "submit_to_ingress", 8))
        )
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_in"))
            .addMetadata(metaWithBitwidth(3, "ingress_port", 8))
        )
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_out_header_t")
            .setHeader(
              controllerHeader(
                "packet_out",
                bitField("egress_port", 8),
                bitField("submit_to_ingress", 8),
              )
            )
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_in_header_t")
            .setHeader(controllerHeader("packet_in", bitField("ingress_port", 8)))
        )
        .build()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!

    val metadata =
      listOf(
        buildMetadata(id = 1, value = byteArrayOf(1)),
        buildMetadata(id = 2, value = byteArrayOf(0)),
      )
    val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte())
    val packed = codec.packPacketOut(metadata, payload)

    assertArrayEquals(codec.serializePacketOut(metadata) + payload, packed)
  }

  // =========================================================================
  // serializePacketOut
  // =========================================================================

  @Test
  fun `serializePacketOut packs all-zero fields correctly`() {
    val (p4info, behavioral) = buildSaiLikeConfig()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!

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
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!

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
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!

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
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!

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
  // decodePacketIn
  // =========================================================================

  @Test
  @Suppress("MagicNumber")
  fun `decodePacketIn parses metadata from the deparsed header`() {
    val (p4info, behavioral) = buildSaiLikeConfig()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!

    // Deparser output: packet_in header (ingress_port=7, target_egress_port=511) followed by a
    // 2-byte payload. target_egress_port=511 is the DROP port — distinct from any actual egress
    // port — so a value read here can only have come from the header, not reconstructed state.
    // 18 header bits + 16 payload bits = 34 bits → 5 bytes.
    val acc = BitAccumulator()
    acc.append(BigInteger.valueOf(7), 9) // ingress_port
    acc.append(BigInteger.valueOf(511), 9) // target_egress_port (DROP)
    acc.append(BigInteger.valueOf(0xBEEF), 16) // payload
    val deparsed = com.google.protobuf.ByteString.copyFrom(acc.toByteArray())

    val packetIn = codec.decodePacketIn(deparsed)

    // Fields are read positionally in p4info order: ingress_port (id=3), target_egress_port (id=4).
    assertEquals(2, packetIn.metadataCount)
    assertEquals(3, packetIn.getMetadata(0).metadataId)
    assertEquals(7, bytesToInt(packetIn.getMetadata(0).value.toByteArray()))
    assertEquals(4, packetIn.getMetadata(1).metadataId)
    assertEquals(511, bytesToInt(packetIn.getMetadata(1).value.toByteArray()))
    // Header stripped; original payload recovered.
    assertArrayEquals(byteArrayOf(0xBE.toByte(), 0xEF.toByte()), packetIn.payload.toByteArray())
  }

  @Test
  @Suppress("MagicNumber")
  fun `decodePacketIn is generic — no hardcoded field names`() {
    // A packet_in header whose fields are NOT the well-known port fields. The codec must still
    // decode their values from the header bits; nothing about specific P4 programs is baked in.
    val p4info =
      P4Info.newBuilder()
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_out"))
            .addMetadata(metaWithBitwidth(1, "egress_port", 8))
        )
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_in"))
            .addMetadata(metaWithBitwidth(2, "foo", 5))
            .addMetadata(metaWithBitwidth(3, "bar", 11))
        )
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_out_header_t")
            .setHeader(controllerHeader("packet_out", bitField("egress_port", 8)))
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_in_header_t")
            .setHeader(controllerHeader("packet_in", bitField("foo", 5), bitField("bar", 11)))
        )
        .build()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!

    // 16-bit header (foo=0x15, bar=0x2AB) + 1-byte payload.
    val acc = BitAccumulator()
    acc.append(BigInteger.valueOf(0x15), 5) // foo
    acc.append(BigInteger.valueOf(0x2AB), 11) // bar
    acc.append(BigInteger.valueOf(0xC3), 8) // payload
    val deparsed = com.google.protobuf.ByteString.copyFrom(acc.toByteArray())

    val packetIn = codec.decodePacketIn(deparsed)

    assertEquals(2, packetIn.metadataCount)
    assertEquals(0x15, bytesToInt(packetIn.getMetadata(0).value.toByteArray()))
    assertEquals(0x2AB, bytesToInt(packetIn.getMetadata(1).value.toByteArray()))
    assertArrayEquals(byteArrayOf(0xC3.toByte()), packetIn.payload.toByteArray())
  }

  @Test
  @Suppress("MagicNumber")
  fun `decodePacketIn strips behavioral padding fields omitted from p4info`() {
    val p4info =
      P4Info.newBuilder()
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_out"))
            .addMetadata(metaWithBitwidth(1, "egress_port", 8))
        )
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_in"))
            .addMetadata(metaWithBitwidth(2, "ingress_port", 9))
        )
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_out_header_t")
            .setHeader(controllerHeader("packet_out", bitField("egress_port", 8)))
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_in_header_t")
            .setHeader(
              controllerHeader("packet_in", bitField("ingress_port", 9), bitField("unused_pad", 7))
            )
        )
        .build()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!

    val acc = BitAccumulator()
    acc.append(BigInteger.valueOf(7), 9) // ingress_port
    acc.append(BigInteger.ZERO, 7) // unused_pad, not exposed in P4Info
    acc.append(BigInteger.valueOf(0xBEEF), 16) // payload
    val deparsed = com.google.protobuf.ByteString.copyFrom(acc.toByteArray())

    val packetIn = codec.decodePacketIn(deparsed)

    assertEquals(1, packetIn.metadataCount)
    assertEquals(2, packetIn.getMetadata(0).metadataId)
    assertEquals(7, bytesToInt(packetIn.getMetadata(0).value.toByteArray()))
    assertArrayEquals(byteArrayOf(0xBE.toByte(), 0xEF.toByte()), packetIn.payload.toByteArray())
  }

  @Test
  @Suppress("MagicNumber")
  fun `decodePacketIn with no packet_in controller header returns the payload verbatim`() {
    // A program that defines packet_out (so a codec exists) but no packet_in @controller_header —
    // e.g. it accepts PacketOut but never punts to the controller. There is no header to strip and
    // no metadata to parse, so the payload passes through untouched.
    val p4info =
      P4Info.newBuilder()
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_out"))
            .addMetadata(metaWithBitwidth(1, "egress_port", 8))
        )
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_out_header_t")
            .setHeader(controllerHeader("packet_out", bitField("egress_port", 8)))
        )
        .build()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!
    assertEquals(0, codec.packetInHeaderBits)

    val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
    val packetIn = codec.decodePacketIn(com.google.protobuf.ByteString.copyFrom(payload))

    assertEquals(0, packetIn.metadataCount)
    assertArrayEquals(payload, packetIn.payload.toByteArray())
  }

  // =========================================================================
  // stripPacketInHeader
  // =========================================================================

  @Test
  @Suppress("MagicNumber")
  fun `stripPacketInHeader removes byte-aligned header correctly`() {
    // 16-bit packet_in header (two 8-bit fields) — byte-aligned, exercises the fast path.
    val p4info =
      P4Info.newBuilder()
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_out"))
            .addMetadata(metaWithBitwidth(1, "egress_port", 8))
        )
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_in"))
            .addMetadata(metaWithBitwidth(2, "ingress_port", 8))
            .addMetadata(metaWithBitwidth(3, "egress_port", 8))
        )
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_out_header_t")
            .setHeader(controllerHeader("packet_out", bitField("egress_port", 8)))
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_in_header_t")
            .setHeader(
              controllerHeader("packet_in", bitField("ingress_port", 8), bitField("egress_port", 8))
            )
        )
        .build()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!
    // 16-bit header (2 bytes) + 4-byte payload = 6 bytes total.
    val deparsed =
      com.google.protobuf.ByteString.copyFrom(
        byteArrayOf(0x00, 0x00, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
      )
    val stripped = codec.stripPacketInHeader(deparsed)
    assertArrayEquals(
      byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
      stripped.toByteArray(),
    )
  }

  @Test
  @Suppress("MagicNumber")
  fun `stripPacketInHeader handles non-byte-aligned header`() {
    // 10-bit packet_in header (two 5-bit fields). The deparsed bit stream has
    // the 10 header bits followed by the payload bits, packed continuously.
    //
    // Header: 10 bits of zeros. Payload: 0xDE 0xAD = 16 bits.
    // Bit stream: 0000000000_11011110_10101101 + 6 pad zeros = 32 bits = 4 bytes.
    // = 00000000 00110111 10101011 01000000
    // = 0x00     0x37     0xAB     0x40
    //
    // After stripping 10 header bits, we should recover: 0xDE 0xAD (16 bits → 2 bytes).
    val p4info =
      P4Info.newBuilder()
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_out"))
            .addMetadata(metaWithBitwidth(1, "egress_port", 8))
        )
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_in"))
            .addMetadata(metaWithBitwidth(2, "field_a", 5))
            .addMetadata(metaWithBitwidth(3, "field_b", 5))
        )
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_out_header_t")
            .setHeader(controllerHeader("packet_out", bitField("egress_port", 8)))
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_in_header_t")
            .setHeader(
              controllerHeader("packet_in", bitField("field_a", 5), bitField("field_b", 5))
            )
        )
        .build()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!
    assertEquals(10, codec.packetInHeaderBits)

    val deparsed =
      com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x00, 0x37, 0xAB.toByte(), 0x40))
    val stripped = codec.stripPacketInHeader(deparsed)
    assertArrayEquals(
      "should recover original 0xDEAD after stripping 10-bit header",
      byteArrayOf(0xDE.toByte(), 0xAD.toByte()),
      stripped.toByteArray(),
    )
  }

  @Test
  @Suppress("MagicNumber")
  fun `stripPacketInHeader with non-byte-aligned payload rounds to whole bytes`() {
    // 6-bit packet_in header + 6-bit tag payload = 12 bits = 2 bytes (4 pad bits).
    // After stripping 6 header bits, the payload is 6 bits — but bytes can only
    // represent whole bytes. The result is 1 byte: the 6 payload bits left-aligned
    // with 2 trailing zeros.
    //
    // This matches what a real target would produce (ceil(6/8) = 1 byte), but
    // the receiver can't distinguish a 6-bit payload from an 8-bit payload.
    val p4info =
      P4Info.newBuilder()
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_out"))
            .addMetadata(metaWithBitwidth(1, "egress_port", 8))
        )
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_in"))
            .addMetadata(metaWithBitwidth(2, "field_a", 6))
        )
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_out_header_t")
            .setHeader(controllerHeader("packet_out", bitField("egress_port", 8)))
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_in_header_t")
            .setHeader(controllerHeader("packet_in", bitField("field_a", 6)))
        )
        .build()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!

    // Deparsed: 6 header bits (zeros) + 6 payload bits (0x3F = 0b111111) + 4 pad zeros
    // = 000000_11 1111_0000 = 0x03 0xF0
    val deparsed = com.google.protobuf.ByteString.copyFrom(byteArrayOf(0x03, 0xF0.toByte()))
    val stripped = codec.stripPacketInHeader(deparsed)

    // Integer division: (16 - 6) / 8 * 8 = 8 bits = 1 byte.
    // The 6 payload bits (111111) are shifted to the front: 0b11111100 = 0xFC.
    assertEquals("stripped payload should be 1 byte", 1, stripped.size())
    assertEquals(
      "6 payload bits (0x3F) left-aligned with 2 trailing zeros = 0xFC",
      0xFC,
      stripped.toByteArray()[0].toInt() and 0xFF,
    )
  }

  // =========================================================================
  // Round-trip: pack → simulate → strip = identity
  // =========================================================================

  @Test
  @Suppress("MagicNumber")
  fun `stripPacketInHeader round-trips non-byte-aligned header with BitAccumulator`() {
    // 10-bit packet_in header (two 5-bit fields) + 32-bit payload (0xDEADBEEF).
    // Total = 42 bits → ceil(42/8) = 6 bytes.
    //
    // We use BitAccumulator to pack the deparsed output exactly as the simulator
    // would: 10 header bits (zeros) followed by 32 payload bits. Stripping the
    // header should recover the original payload bytes.
    val p4info =
      P4Info.newBuilder()
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_out"))
            .addMetadata(metaWithBitwidth(1, "egress_port", 8))
        )
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_in"))
            .addMetadata(metaWithBitwidth(2, "field_a", 5))
            .addMetadata(metaWithBitwidth(3, "field_b", 5))
        )
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_out_header_t")
            .setHeader(controllerHeader("packet_out", bitField("egress_port", 8)))
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_in_header_t")
            .setHeader(
              controllerHeader("packet_in", bitField("field_a", 5), bitField("field_b", 5))
            )
        )
        .build()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 510)!!
    assertEquals(10, codec.packetInHeaderBits)

    // Simulate deparser output: 10 zero header bits + 0xDEADBEEF payload.
    val acc = BitAccumulator()
    acc.append(BigInteger.ZERO, 5) // field_a = 0
    acc.append(BigInteger.ZERO, 5) // field_b = 0
    acc.append(BigInteger.valueOf(0xDEADBEEFL), 32) // payload
    val deparsed = com.google.protobuf.ByteString.copyFrom(acc.toByteArray())
    assertEquals(6, deparsed.size()) // 42 bits → 6 bytes

    val stripped = codec.stripPacketInHeader(deparsed)
    val originalPayload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
    assertArrayEquals(
      "pack + strip should recover the original payload",
      originalPayload,
      stripped.toByteArray(),
    )
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
   * - packet_out: egress_port (9-bit, id=1), submit_to_ingress (1-bit, id=2), unused_pad (6-bit)
   * - packet_in: ingress_port (9-bit, id=3), target_egress_port (9-bit, id=4)
   * - Behavioral config with header types for field width resolution.
   */
  @Suppress("MagicNumber")
  private fun buildSaiLikeConfig(portBits: Int = 9): Pair<P4Info, BehavioralConfig> {
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
              controllerHeader(
                "packet_out",
                bitField("egress_port", 9),
                bitField("submit_to_ingress", 1),
                bitField("unused_pad", 6),
              )
            )
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_in_header_t")
            .setHeader(
              controllerHeader(
                "packet_in",
                bitField("ingress_port", 9),
                bitField("target_egress_port", 9),
              )
            )
        )
        .addTypes(standardMetadataType(portBits))
        .build()

    return p4info to behavioral
  }

  private fun meta(id: Int, name: String): ControllerPacketMetadata.Metadata =
    ControllerPacketMetadata.Metadata.newBuilder().setId(id).setName(name).build()

  private fun metaWithBitwidth(
    id: Int,
    name: String,
    bitwidth: Int,
  ): ControllerPacketMetadata.Metadata =
    ControllerPacketMetadata.Metadata.newBuilder()
      .setId(id)
      .setName(name)
      .setBitwidth(bitwidth)
      .build()

  private fun bitField(name: String, width: Int): FieldDecl =
    FieldDecl.newBuilder()
      .setName(name)
      .setType(Type.newBuilder().setBit(BitType.newBuilder().setWidth(width)))
      .build()

  private fun controllerHeader(name: String, vararg fields: FieldDecl): HeaderDecl =
    HeaderDecl.newBuilder().setControllerHeader(name).addAllFields(fields.toList()).build()

  private fun standardMetadataType(portBits: Int = 9): TypeDecl =
    TypeDecl.newBuilder()
      .setName("standard_metadata_t")
      .setStruct(StructDecl.newBuilder().addFields(bitField("ingress_port", portBits)))
      .build()

  // =========================================================================
  // CPU port override
  // =========================================================================

  @Test
  fun `cpu port override replaces derived value`() {
    val (p4info, behavioral) = buildSaiLikeConfig()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = 42)
    assertNotNull(codec)
    assertEquals(42, codec!!.cpuPort.protoValue)
  }

  @Test
  fun `cpu port override null uses default derivation`() {
    val (p4info, behavioral) = buildSaiLikeConfig()
    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = null)
    assertNotNull(codec)
    // Default: 2^9 - 2 = 510
    assertEquals(510, codec!!.cpuPort.protoValue)
  }

  @Test
  @Suppress("MagicNumber")
  fun `auto cpu port derives from architecture port width, not packet_out metadata width`() {
    val p4info =
      P4Info.newBuilder()
        .addControllerPacketMetadata(
          ControllerPacketMetadata.newBuilder()
            .setPreamble(Preamble.newBuilder().setName("packet_out"))
            .addMetadata(metaWithBitwidth(1, "egress_port", 32))
            .addMetadata(metaWithBitwidth(2, "submit_to_ingress", 1))
        )
        .build()
    val behavioral =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("packet_out_header_t")
            .setHeader(
              controllerHeader(
                "packet_out",
                bitField("egress_port", 32),
                bitField("submit_to_ingress", 1),
              )
            )
        )
        .addTypes(standardMetadataType(portBits = 9))
        .build()

    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = null)

    assertEquals(510, codec!!.cpuPort.protoValue)
  }

  @Test
  @Suppress("MagicNumber")
  fun `auto cpu port supports 32-bit architecture ports`() {
    val (p4info, behavioral) = buildSaiLikeConfig(portBits = 32)

    val codec = PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = null)

    assertEquals(0xFFFF_FFFEu.toInt(), codec!!.cpuPort.protoValue)
    assertEquals(0xFFFF_FFFEL, codec.cpuPort.unsignedLong)
  }

  @Test
  @Suppress("MagicNumber")
  fun `auto cpu port rejects architecture ports wider than dataplane port field`() {
    val (p4info, behavioral) = buildSaiLikeConfig(portBits = 33)

    assertThrows(IllegalArgumentException::class.java) {
      PacketHeaderCodec.create(p4info, behavioral, cpuPortOverride = null)
    }
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
    assertEquals(
      CpuPortConfig.Override(PortOverride.Dataplane(DataplanePort.fromUnsignedLong(510))),
      CpuPortConfig.fromFlag("510"),
    )
    assertEquals(
      CpuPortConfig.Override(PortOverride.Dataplane(DataplanePort.fromUnsignedLong(0))),
      CpuPortConfig.fromFlag("0"),
    )
  }

  @Test
  fun `fromFlag hex integer returns Override with Dataplane port`() {
    assertEquals(
      CpuPortConfig.Override(PortOverride.Dataplane(DataplanePort.fromUnsignedLong(0xFFFF_FFFEL))),
      CpuPortConfig.fromFlag("0xffff_fffe"),
    )
  }

  @Test
  fun `fromFlag non-integer returns Override with P4rt port`() {
    assertEquals(
      CpuPortConfig.Override(PortOverride.P4rt("CpuPort")),
      CpuPortConfig.fromFlag("CpuPort"),
    )
  }
}
