// Copyright 2026 4ward Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package fourward.simulator

import fourward.BehavioralConfig
import fourward.Expr
import fourward.FieldAccess
import fourward.FieldDecl
import fourward.HeaderDecl
import fourward.HeaderUnionDecl
import fourward.Literal
import fourward.MethodCall
import fourward.NameRef
import fourward.StructDecl
import fourward.Type
import fourward.TypeDecl
import fourward.VarbitType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Interpreter] extract and emit operations.
 *
 * Tests verify that header fields are correctly unpacked from packet bytes ([execExtract]) and
 * repacked into packet bytes ([execEmit]), with particular attention to sub-byte fields that share
 * a byte (e.g. IPv4's bit<4> version and ihl).
 */
class InterpreterPacketTest {

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Builds an extract or emit call: `pkt.<method>(headerVarName)`. */
  private fun packetCall(method: String, headerVarName: String): Expr =
    Expr.newBuilder()
      .setMethodCall(
        MethodCall.newBuilder()
          .setTarget(nameRef("pkt"))
          .setMethod(method)
          .addArgs(nameRef(headerVarName))
      )
      .build()

  /** Builds a header TypeDecl from a list of (fieldName, bitwidth) pairs. */
  private fun headerType(typeName: String, vararg fields: Pair<String, Int>): TypeDecl =
    TypeDecl.newBuilder()
      .setName(typeName)
      .setHeader(
        HeaderDecl.newBuilder().also { hdr ->
          for ((name, width) in fields) {
            hdr.addFields(FieldDecl.newBuilder().setName(name).setType(bitType(width)))
          }
        }
      )
      .build()

  private fun interp(packetCtx: PacketContext, vararg types: TypeDecl): Interpreter.Execution {
    val config =
      BehavioralConfig.newBuilder().also { cfg -> types.forEach { cfg.addTypes(it) } }.build()
    return interpreterExecution(config, TableStore(), packetCtx)
  }

  // ---------------------------------------------------------------------------
  // missing PacketContext
  // ---------------------------------------------------------------------------

  @Test
  fun `extract without PacketContext gives a descriptive error`() {
    val type = headerType("h_t", "f" to 8)
    val config = BehavioralConfig.newBuilder().also { cfg -> cfg.addTypes(type) }.build()
    val interp = interpreterExecution(config, TableStore()) // no PacketContext
    val env = Environment()
    env.define("hdr", HeaderVal(typeName = "h_t", valid = false))

    val ex =
      assertThrows(IllegalStateException::class.java) {
        interp.evalExpr(packetCall("extract", "hdr"), env)
      }
    assertTrue(ex.message!!.contains("PacketContext"))
  }

  // ---------------------------------------------------------------------------
  // extract
  // ---------------------------------------------------------------------------

  @Test
  fun `extract byte-aligned 16-bit field`() {
    val type = headerType("h_t", "etherType" to 16)
    val pktCtx = PacketContext(byteArrayOf(0x08, 0x00))
    val env = Environment()
    val header = HeaderVal(typeName = "h_t", valid = false)
    env.define("hdr", header)

    interp(pktCtx, type).evalExpr(packetCall("extract", "hdr"), env)

    assertTrue(header.valid)
    assertEquals(BitVal(0x0800, 16), header.fields["etherType"])
  }

  @Test
  fun `extract two sub-byte fields from a shared byte`() {
    // 0x45: upper nibble = version=4, lower nibble = ihl=5
    val type = headerType("h_t", "version" to 4, "ihl" to 4)
    val pktCtx = PacketContext(byteArrayOf(0x45))
    val env = Environment()
    val header = HeaderVal(typeName = "h_t", valid = false)
    env.define("hdr", header)

    interp(pktCtx, type).evalExpr(packetCall("extract", "hdr"), env)

    assertTrue(header.valid)
    assertEquals(BitVal(4, 4), header.fields["version"])
    assertEquals(BitVal(5, 4), header.fields["ihl"])
  }

  @Test
  fun `extract mixed-width fields spanning four bytes`() {
    // bit<4>(4) + bit<4>(5) + bit<8>(0) + bit<16>(0x0800) = 0x45 0x00 0x08 0x00
    val type = headerType("h_t", "version" to 4, "ihl" to 4, "tos" to 8, "len" to 16)
    val pktCtx = PacketContext(byteArrayOf(0x45, 0x00, 0x08, 0x00))
    val env = Environment()
    val header = HeaderVal(typeName = "h_t", valid = false)
    env.define("hdr", header)

    interp(pktCtx, type).evalExpr(packetCall("extract", "hdr"), env)

    assertEquals(BitVal(4, 4), header.fields["version"])
    assertEquals(BitVal(5, 4), header.fields["ihl"])
    assertEquals(BitVal(0, 8), header.fields["tos"])
    assertEquals(BitVal(0x0800, 16), header.fields["len"])
  }

  @Test
  fun `extract advances the packet buffer cursor`() {
    // Two sequential extracts: first grabs bytes [0x08, 0x00], second gets [0x01, 0x00].
    val type = headerType("h_t", "f" to 16)
    val pktCtx = PacketContext(byteArrayOf(0x08, 0x00, 0x01, 0x00))
    val env = Environment()
    val h1 = HeaderVal(typeName = "h_t", valid = false)
    val h2 = HeaderVal(typeName = "h_t", valid = false)
    env.define("h1", h1)
    env.define("h2", h2)

    val interp = interp(pktCtx, type)
    interp.evalExpr(packetCall("extract", "h1"), env)
    interp.evalExpr(packetCall("extract", "h2"), env)

    assertEquals(BitVal(0x0800, 16), h1.fields["f"])
    assertEquals(BitVal(0x0100, 16), h2.fields["f"])
  }

  // ---------------------------------------------------------------------------
  // emit
  // ---------------------------------------------------------------------------

  @Test
  fun `emit valid 16-bit header produces correct bytes`() {
    val type = headerType("h_t", "etherType" to 16)
    val pktCtx = PacketContext(byteArrayOf())
    val env = Environment()
    val header =
      HeaderVal(
        typeName = "h_t",
        fields = mutableMapOf("etherType" to BitVal(0x0800, 16)),
        valid = true,
      )
    env.define("hdr", header)

    interp(pktCtx, type).evalExpr(packetCall("emit", "hdr"), env)

    assertArrayEquals(byteArrayOf(0x08, 0x00), pktCtx.outputPayload())
  }

  @Test
  fun `emit invalid header produces no bytes`() {
    val type = headerType("h_t", "etherType" to 16)
    val pktCtx = PacketContext(byteArrayOf())
    val env = Environment()
    val header = HeaderVal(typeName = "h_t", valid = false)
    env.define("hdr", header)

    interp(pktCtx, type).evalExpr(packetCall("emit", "hdr"), env)

    assertArrayEquals(byteArrayOf(), pktCtx.outputPayload())
  }

  @Test
  fun `emit packs sub-byte fields into a single byte`() {
    // version=4, ihl=5 → both in the same byte → 0x45
    val type = headerType("h_t", "version" to 4, "ihl" to 4)
    val pktCtx = PacketContext(byteArrayOf())
    val env = Environment()
    val header =
      HeaderVal(
        typeName = "h_t",
        fields = mutableMapOf("version" to BitVal(4, 4), "ihl" to BitVal(5, 4)),
        valid = true,
      )
    env.define("hdr", header)

    interp(pktCtx, type).evalExpr(packetCall("emit", "hdr"), env)

    assertArrayEquals(byteArrayOf(0x45), pktCtx.outputPayload())
  }

  @Test
  fun `extract then emit round-trips the original bytes`() {
    val type = headerType("h_t", "version" to 4, "ihl" to 4, "tos" to 8, "len" to 16)
    val input = byteArrayOf(0x45, 0x00, 0x08, 0x00)
    val pktCtx = PacketContext(input)
    val env = Environment()
    val header = HeaderVal(typeName = "h_t", valid = false)
    env.define("hdr", header)

    val interp = interp(pktCtx, type)
    interp.evalExpr(packetCall("extract", "hdr"), env)
    interp.evalExpr(packetCall("emit", "hdr"), env)

    assertArrayEquals(input, pktCtx.outputPayload())
  }

  // ---------------------------------------------------------------------------
  // DeparserEmitEvent trace events
  // ---------------------------------------------------------------------------

  @Test
  fun `emit produces DeparserEmitEvent with header type and byte length`() {
    val type = headerType("ethernet_t", "dstAddr" to 48, "srcAddr" to 48, "etherType" to 16)
    val pktCtx = PacketContext(byteArrayOf())
    val env = Environment()
    val header =
      HeaderVal(
        typeName = "ethernet_t",
        fields =
          mutableMapOf(
            "dstAddr" to BitVal(0, 48),
            "srcAddr" to BitVal(0, 48),
            "etherType" to BitVal(0x0800, 16),
          ),
        valid = true,
      )
    env.define("hdr", header)

    interp(pktCtx, type).evalExpr(packetCall("emit", "hdr"), env)

    val emitEvents = pktCtx.getEvents().filter { it.hasDeparserEmit() }
    assertEquals(1, emitEvents.size)
    assertEquals("ethernet_t", emitEvents[0].deparserEmit.headerType)
    assertEquals(14, emitEvents[0].deparserEmit.byteLength)
  }

  @Test
  fun `emit invalid header produces no DeparserEmitEvent`() {
    val type = headerType("h_t", "f" to 16)
    val pktCtx = PacketContext(byteArrayOf())
    val env = Environment()
    env.define("hdr", HeaderVal(typeName = "h_t", valid = false))

    interp(pktCtx, type).evalExpr(packetCall("emit", "hdr"), env)

    assertTrue(pktCtx.getEvents().none { it.hasDeparserEmit() })
  }

  // ---------------------------------------------------------------------------
  // extract into header union members (P4 §8.20)
  // ---------------------------------------------------------------------------

  private fun headerUnionType(typeName: String, vararg members: Pair<String, String>): TypeDecl =
    TypeDecl.newBuilder()
      .setName(typeName)
      .setHeaderUnion(
        HeaderUnionDecl.newBuilder().also { u ->
          for ((name, headerTypeName) in members) {
            u.addFields(FieldDecl.newBuilder().setName(name).setType(namedType(headerTypeName)))
          }
        }
      )
      .build()

  /** Builds an extract call whose argument is a field access: `pkt.extract(unionVar.member)`. */
  private fun unionExtractCall(unionVar: String, member: String, unionTypeName: String): Expr =
    Expr.newBuilder()
      .setMethodCall(
        MethodCall.newBuilder()
          .setTarget(nameRef("pkt"))
          .setMethod("extract")
          .addArgs(
            Expr.newBuilder()
              .setFieldAccess(
                FieldAccess.newBuilder()
                  .setExpr(
                    Expr.newBuilder()
                      .setNameRef(NameRef.newBuilder().setName(unionVar))
                      .setType(namedType(unionTypeName))
                  )
                  .setFieldName(member)
              )
          )
      )
      .build()

  @Test
  fun `extract into union member invalidates siblings`() {
    // Union with two 8-bit headers: extracting into member "a" should invalidate "b".
    val hdrA = headerType("a_t", "f" to 8)
    val hdrB = headerType("b_t", "f" to 8)
    val union = headerUnionType("u_t", "a" to "a_t", "b" to "b_t")

    val memberA = HeaderVal("a_t", mutableMapOf("f" to BitVal(0, 8)), valid = false)
    val memberB = HeaderVal("b_t", mutableMapOf("f" to BitVal(0x42, 8)), valid = true)
    val unionVal = StructVal("u_t", mutableMapOf("a" to memberA, "b" to memberB))

    val pktCtx = PacketContext(byteArrayOf(0x01))
    val env = Environment()
    env.define("u", unionVal)

    interp(pktCtx, hdrA, hdrB, union).evalExpr(unionExtractCall("u", "a", "u_t"), env)

    // Member "a" should be valid with extracted data.
    assertTrue(memberA.valid)
    assertEquals(BitVal(1, 8), memberA.fields["f"])
    // Member "b" should be invalidated (P4 §8.20: one-valid-at-a-time).
    assertFalse(memberB.valid)
  }

  @Test
  fun `extract into union member when no sibling was valid`() {
    // Both members start invalid; extract should still set the target valid.
    val hdrA = headerType("a_t", "f" to 8)
    val hdrB = headerType("b_t", "f" to 8)
    val union = headerUnionType("u_t", "a" to "a_t", "b" to "b_t")

    val memberA = HeaderVal("a_t", mutableMapOf("f" to BitVal(0, 8)), valid = false)
    val memberB = HeaderVal("b_t", mutableMapOf("f" to BitVal(0, 8)), valid = false)
    val unionVal = StructVal("u_t", mutableMapOf("a" to memberA, "b" to memberB))

    val pktCtx = PacketContext(byteArrayOf(0xFF.toByte()))
    val env = Environment()
    env.define("u", unionVal)

    interp(pktCtx, hdrA, hdrB, union).evalExpr(unionExtractCall("u", "a", "u_t"), env)

    assertTrue(memberA.valid)
    assertEquals(BitVal(0xFF, 8), memberA.fields["f"])
    assertFalse(memberB.valid)
  }

  @Test
  fun `sequential union extracts swap validity`() {
    // Extract "a", then extract "b" — only "b" should be valid at the end.
    val hdrA = headerType("a_t", "f" to 8)
    val hdrB = headerType("b_t", "f" to 8)
    val union = headerUnionType("u_t", "a" to "a_t", "b" to "b_t")

    val memberA = HeaderVal("a_t", mutableMapOf("f" to BitVal(0, 8)), valid = false)
    val memberB = HeaderVal("b_t", mutableMapOf("f" to BitVal(0, 8)), valid = false)
    val unionVal = StructVal("u_t", mutableMapOf("a" to memberA, "b" to memberB))

    val pktCtx = PacketContext(byteArrayOf(0x01, 0x02))
    val env = Environment()
    env.define("u", unionVal)

    val interp = interp(pktCtx, hdrA, hdrB, union)
    interp.evalExpr(unionExtractCall("u", "a", "u_t"), env)
    interp.evalExpr(unionExtractCall("u", "b", "u_t"), env)

    // After second extract, "a" is invalidated and "b" is valid.
    assertFalse(memberA.valid)
    assertTrue(memberB.valid)
    assertEquals(BitVal(2, 8), memberB.fields["f"])
  }

  // ---------------------------------------------------------------------------
  // lookahead (P4 §12.8.2)
  // ---------------------------------------------------------------------------

  private fun structType(typeName: String, vararg fields: Pair<String, Int>): TypeDecl =
    TypeDecl.newBuilder()
      .setName(typeName)
      .setStruct(
        StructDecl.newBuilder().also { s ->
          for ((name, width) in fields) {
            s.addFields(FieldDecl.newBuilder().setName(name).setType(bitType(width)))
          }
        }
      )
      .build()

  /** Builds a lookahead call: `pkt.lookahead<T>()` with return type T. */
  private fun lookaheadCall(returnTypeName: String): Expr =
    Expr.newBuilder()
      .setMethodCall(MethodCall.newBuilder().setTarget(nameRef("pkt")).setMethod("lookahead"))
      .setType(Type.newBuilder().setNamed(returnTypeName))
      .build()

  /** Builds an advance call: `pkt.advance(bits)`. */
  private fun advanceCall(bits: Int): Expr =
    Expr.newBuilder()
      .setMethodCall(
        MethodCall.newBuilder()
          .setTarget(nameRef("pkt"))
          .setMethod("advance")
          .addArgs(
            Expr.newBuilder()
              .setLiteral(Literal.newBuilder().setInteger(bits.toLong()))
              .setType(bitType(32))
          )
      )
      .build()

  @Test
  fun `lookahead returns struct with correct field values`() {
    val type = structType("B8", "bits" to 64)
    val pktCtx = PacketContext(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08))
    val env = Environment()

    val result = interp(pktCtx, type).evalExpr(lookaheadCall("B8"), env)

    assertTrue(result is StructVal)
    val sv = result as StructVal
    assertEquals("B8", sv.typeName)
    assertEquals(BitVal(0x0102030405060708, 64), sv.fields["bits"])
  }

  @Test
  fun `lookahead does not consume packet bytes`() {
    val type = structType("S", "f" to 16)
    val pktCtx = PacketContext(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte()))
    val env = Environment()
    val interp = interp(pktCtx, type)

    // Lookahead peeks without consuming.
    val peek = interp.evalExpr(lookaheadCall("S"), env) as StructVal
    assertEquals(BitVal(0xABCD, 16), peek.fields["f"])

    // Extract should read the same bytes since lookahead didn't advance.
    val header = HeaderVal(typeName = "S", valid = false)
    env.define("hdr", header)
    // Re-use the type as a header for extraction.
    val hdrType = headerType("S", "f" to 16)
    interp(pktCtx, hdrType).evalExpr(packetCall("extract", "hdr"), env)
    assertEquals(BitVal(0xABCD, 16), header.fields["f"])
  }

  @Test
  fun `lookahead with sub-byte fields unpacks correctly`() {
    val type = structType("ipv4_peek", "version" to 4, "ihl" to 4)
    val pktCtx = PacketContext(byteArrayOf(0x45))
    val env = Environment()

    val result = interp(pktCtx, type).evalExpr(lookaheadCall("ipv4_peek"), env) as StructVal

    assertEquals(BitVal(4, 4), result.fields["version"])
    assertEquals(BitVal(5, 4), result.fields["ihl"])
  }

  @Test
  fun `lookahead on header type returns valid header`() {
    val type = headerType("h_t", "f" to 8)
    val pktCtx = PacketContext(byteArrayOf(0x42))
    val env = Environment()

    val result = interp(pktCtx, type).evalExpr(lookaheadCall("h_t"), env)

    assertTrue(result is HeaderVal)
    val hv = result as HeaderVal
    assertTrue(hv.valid)
    assertEquals(BitVal(0x42, 8), hv.fields["f"])
  }

  @Test
  fun `lookahead with bit type returns BitVal`() {
    val pktCtx = PacketContext(byteArrayOf(0xAB.toByte(), 0xCD.toByte()))
    val env = Environment()
    // lookahead<bit<16>>() — return type is bit<16>, not a named type.
    val call =
      Expr.newBuilder()
        .setMethodCall(MethodCall.newBuilder().setTarget(nameRef("pkt")).setMethod("lookahead"))
        .setType(bitType(16))
        .build()
    val result = interp(pktCtx).evalExpr(call, env)

    assertEquals(BitVal(0xABCD, 16), result)
  }

  @Test
  fun `lookahead with bit type does not consume bytes`() {
    val pktCtx = PacketContext(byteArrayOf(0x42, 0xFF.toByte()))
    val env = Environment()
    val call =
      Expr.newBuilder()
        .setMethodCall(MethodCall.newBuilder().setTarget(nameRef("pkt")).setMethod("lookahead"))
        .setType(bitType(8))
        .build()
    val interp = interp(pktCtx)

    val peek = interp.evalExpr(call, env)
    assertEquals(BitVal(0x42, 8), peek)

    // Second lookahead should return the same value — bytes not consumed.
    val peek2 = interp.evalExpr(call, env)
    assertEquals(BitVal(0x42, 8), peek2)
  }

  @Test
  fun `lookahead throws PacketTooShort when not enough bytes`() {
    val type = structType("big", "f" to 32)
    val pktCtx = PacketContext(byteArrayOf(0x01, 0x02)) // only 2 bytes, need 4
    val env = Environment()

    assertThrows(PacketTooShortException::class.java) {
      interp(pktCtx, type).evalExpr(lookaheadCall("big"), env)
    }
  }

  // ---------------------------------------------------------------------------
  // advance (P4 §12.8.3)
  // ---------------------------------------------------------------------------

  @Test
  fun `advance skips bytes then extract reads remaining`() {
    val type = headerType("h_t", "f" to 8)
    val pktCtx = PacketContext(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()))
    val env = Environment()
    val header = HeaderVal(typeName = "h_t", valid = false)
    env.define("hdr", header)

    val interp = interp(pktCtx, type)
    // Advance past first 2 bytes (16 bits).
    interp.evalExpr(advanceCall(16), env)
    // Extract should read the third byte.
    interp.evalExpr(packetCall("extract", "hdr"), env)

    assertEquals(BitVal(0xCC, 8), header.fields["f"])
  }

  @Test
  fun `advance throws PacketTooShort when not enough bytes`() {
    val type = headerType("h_t", "f" to 8)
    val pktCtx = PacketContext(byteArrayOf(0x01))
    val env = Environment()

    assertThrows(PacketTooShortException::class.java) {
      interp(pktCtx, type).evalExpr(advanceCall(16), env)
    }
  }

  // ---------------------------------------------------------------------------
  // varbit extract with zero-length varbit field
  // ---------------------------------------------------------------------------

  private fun varbitType(maxWidth: Int): Type =
    Type.newBuilder().setVarbit(VarbitType.newBuilder().setMaxWidth(maxWidth)).build()

  /** Builds a header TypeDecl with a trailing varbit field. */
  private fun headerTypeWithVarbit(
    typeName: String,
    fixedFields: List<Pair<String, Int>>,
    varbitFieldName: String,
    varbitMaxWidth: Int,
  ): TypeDecl =
    TypeDecl.newBuilder()
      .setName(typeName)
      .setHeader(
        HeaderDecl.newBuilder().also { hdr ->
          for ((name, width) in fixedFields) {
            hdr.addFields(FieldDecl.newBuilder().setName(name).setType(bitType(width)))
          }
          hdr.addFields(
            FieldDecl.newBuilder().setName(varbitFieldName).setType(varbitType(varbitMaxWidth))
          )
        }
      )
      .build()

  /** Builds a 2-arg extract call: `pkt.extract(headerVarName, varbitBits)`. */
  private fun varbitExtractCall(headerVarName: String, varbitBits: Int): Expr =
    Expr.newBuilder()
      .setMethodCall(
        MethodCall.newBuilder()
          .setTarget(nameRef("pkt"))
          .setMethod("extract")
          .addArgs(nameRef(headerVarName))
          .addArgs(
            Expr.newBuilder()
              .setLiteral(Literal.newBuilder().setInteger(varbitBits.toLong()))
              .setType(bitType(32))
          )
      )
      .build()

  @Test
  fun `extract with zero-length varbit stores field as BitVal(0, 0)`() {
    // Simplified IPv4-like header: 8-bit fixed field + varbit<320> options.
    // When varbitBits=0, the varbit field should still exist in the header's fields map.
    val type = headerTypeWithVarbit("ipv4_t", listOf("ihl" to 8), "options", 320)
    val pktCtx = PacketContext(byteArrayOf(0x05))
    val env = Environment()
    val header = HeaderVal(typeName = "ipv4_t", valid = false)
    env.define("hdr", header)

    interp(pktCtx, type).evalExpr(varbitExtractCall("hdr", 0), env)

    assertTrue(header.valid)
    assertEquals(BitVal(5, 8), header.fields["ihl"])
    // The varbit field must be present even with width 0.
    assertEquals(BitVal(0, 0), header.fields["options"])
  }

  @Test
  fun `extract with non-zero varbit stores field with correct width`() {
    // 8-bit fixed + 16-bit varbit options.
    val type = headerTypeWithVarbit("ipv4_t", listOf("ihl" to 8), "options", 320)
    val pktCtx = PacketContext(byteArrayOf(0x06, 0xAB.toByte(), 0xCD.toByte()))
    val env = Environment()
    val header = HeaderVal(typeName = "ipv4_t", valid = false)
    env.define("hdr", header)

    interp(pktCtx, type).evalExpr(varbitExtractCall("hdr", 16), env)

    assertTrue(header.valid)
    assertEquals(BitVal(6, 8), header.fields["ihl"])
    assertEquals(BitVal(0xABCD, 16), header.fields["options"])
  }
}
