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

import fourward.EnumDecl
import fourward.FieldDecl
import fourward.HeaderDecl
import fourward.HeaderStackType
import fourward.HeaderUnionDecl
import fourward.IntType
import fourward.StructDecl
import fourward.Type
import fourward.TypeDecl
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Unit tests for [defaultValue] — type-to-default-value mapping. */
class DefaultValuesTest {

  private fun intType(width: Int): Type =
    Type.newBuilder().setSignedInt(IntType.newBuilder().setWidth(width)).build()

  private fun headerStackType(elementType: String, size: Int): Type =
    Type.newBuilder()
      .setHeaderStack(HeaderStackType.newBuilder().setElementType(elementType).setSize(size))
      .build()

  private fun field(name: String, type: Type): FieldDecl =
    FieldDecl.newBuilder().setName(name).setType(type).build()

  // ---------------------------------------------------------------------------
  // Primitive types
  // ---------------------------------------------------------------------------

  @Test
  fun `bit type defaults to zero BitVal of the correct width`() {
    assertEquals(BitVal(0L, 16), defaultValue(bitType(16), emptyMap()))
  }

  @Test
  fun `signed int type defaults to zero IntVal, not BitVal`() {
    // assertEquals already enforces the type — if defaultValue returned a BitVal, it wouldn't
    // equal an IntVal.
    assertEquals(
      IntVal(SignedBitVector(BigInteger.ZERO, 32)),
      defaultValue(intType(32), emptyMap()),
    )
  }

  @Test
  fun `boolean type defaults to false`() {
    assertEquals(
      BoolVal(false),
      defaultValue(Type.newBuilder().setBoolean(true).build(), emptyMap()),
    )
  }

  // ---------------------------------------------------------------------------
  // Named types
  // ---------------------------------------------------------------------------

  @Test
  fun `header defaults to invalid with zeroed fields`() {
    val types =
      mapOf(
        "eth_t" to
          TypeDecl.newBuilder()
            .setName("eth_t")
            .setHeader(
              HeaderDecl.newBuilder()
                .addFields(field("dstAddr", bitType(48)))
                .addFields(field("etherType", bitType(16)))
            )
            .build()
      )
    val result = defaultValue(namedType("eth_t"), types) as HeaderVal
    assertFalse("header should be invalid by default", result.valid)
    assertEquals(BitVal(0L, 48), result.fields["dstAddr"])
    assertEquals(BitVal(0L, 16), result.fields["etherType"])
  }

  @Test
  fun `header union defaults to a StructVal with all-invalid HeaderVal members`() {
    // P4 spec §8.20: a header union is represented as a StructVal where each
    // member is a HeaderVal with valid=false.
    val types =
      mapOf(
        "hdr_t" to
          TypeDecl.newBuilder()
            .setName("hdr_t")
            .setHeader(HeaderDecl.newBuilder().addFields(field("f", bitType(8))))
            .build(),
        "hu_t" to
          TypeDecl.newBuilder()
            .setName("hu_t")
            .setHeaderUnion(HeaderUnionDecl.newBuilder().addFields(field("h", namedType("hdr_t"))))
            .build(),
      )
    val result = defaultValue(namedType("hu_t"), types) as StructVal
    val member = result.fields["h"] as HeaderVal
    assertFalse("union member header should be invalid", member.valid)
  }

  @Test
  fun `header stack produces the correct number of invalid elements`() {
    val types =
      mapOf(
        "vlan_t" to
          TypeDecl.newBuilder()
            .setName("vlan_t")
            .setHeader(HeaderDecl.newBuilder().addFields(field("vid", bitType(12))))
            .build()
      )
    val stack = defaultValue(headerStackType("vlan_t", 3), types) as HeaderStackVal
    assertEquals(3, stack.headers.size)
    stack.headers.forEach { element ->
      assertFalse("stack element should be invalid", (element as HeaderVal).valid)
    }
  }

  @Test
  fun `serializable enum defaults to zero BitVal of underlying width`() {
    val types =
      mapOf(
        "Color" to
          TypeDecl.newBuilder()
            .setName("Color")
            .setEnum(EnumDecl.newBuilder().addMembers("RED").addMembers("GREEN").setWidth(8))
            .build()
      )
    val result = defaultValue(namedType("Color"), types)
    // Serializable enums serialize to their underlying bit type, not EnumVal.
    assertEquals(BitVal(0L, 8), result)
  }

  @Test
  fun `unknown type name returns UnitVal`() {
    assertEquals(UnitVal, defaultValue(namedType("nonexistent"), emptyMap()))
  }

  @Test
  fun `struct with nested header recursively initializes fields`() {
    val types =
      mapOf(
        "inner_t" to
          TypeDecl.newBuilder()
            .setName("inner_t")
            .setHeader(HeaderDecl.newBuilder().addFields(field("x", bitType(8))))
            .build(),
        "outer_t" to
          TypeDecl.newBuilder()
            .setName("outer_t")
            .setStruct(StructDecl.newBuilder().addFields(field("inner", namedType("inner_t"))))
            .build(),
      )
    val result = defaultValue(namedType("outer_t"), types) as StructVal
    val inner = result.fields["inner"] as HeaderVal
    assertFalse(inner.valid)
    assertEquals(BitVal(0L, 8), inner.fields["x"])
  }
}
