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

import fourward.Type
import fourward.TypeDecl
import java.math.BigInteger

/**
 * Creates a zero/default [Value] for [type].
 *
 * bit<N> → [BitVal] zero, int<N> → [IntVal] zero, bool → [BoolVal] false, named types → recursively
 * initialised header (invalid), struct, or [BitVal] zero for serializable enums. varbit<N> and
 * unrecognised types → [UnitVal].
 */
internal fun defaultValue(type: Type, types: Map<String, TypeDecl>): Value =
  when (type.kindCase) {
    Type.KindCase.BIT -> BitVal(0L, type.bit.width)
    Type.KindCase.SIGNED_INT ->
      IntVal(SignedBitVector(java.math.BigInteger.ZERO, type.signedInt.width))
    Type.KindCase.BOOLEAN -> BoolVal(false)
    Type.KindCase.NAMED -> defaultValue(type.named, types)
    Type.KindCase.HEADER_STACK ->
      HeaderStackVal(
        elementTypeName = type.headerStack.elementType,
        headers =
          MutableList(type.headerStack.size) { defaultValue(type.headerStack.elementType, types) },
      )
    Type.KindCase.VARBIT,
    Type.KindCase.ERROR,
    Type.KindCase.KIND_NOT_SET,
    null -> UnitVal // varbit<N>: variable-length; no fixed default
  }

/**
 * Creates a zero/default [Value] for the named type.
 *
 * Looks up [typeName] in [types]; returns [UnitVal] if not found. Headers are initially invalid
 * with zeroed fields; structs are recursively initialised. Serializable enums default to a zero
 * [BitVal] of their underlying width.
 */
internal fun defaultValue(typeName: String, types: Map<String, TypeDecl>): Value {
  val typeDecl = types[typeName] ?: return UnitVal
  return when (typeDecl.kindCase) {
    TypeDecl.KindCase.HEADER ->
      HeaderVal(
        typeName = typeName,
        fields =
          CompactFieldMap.of(
            typeDecl.header.fieldsList.map { f -> f.name to defaultValue(f.type, types) }
          ),
        valid = false,
      )
    TypeDecl.KindCase.STRUCT -> defaultStruct(typeName, typeDecl.struct.fieldsList, types)
    // Header union (P4 spec §8.20): represented as a StructVal so field access works
    // uniformly; per-member validity is tracked via HeaderVal.valid.
    TypeDecl.KindCase.HEADER_UNION ->
      defaultStruct(typeName, typeDecl.headerUnion.fieldsList, types)
    // Serializable enum: default to zero of the underlying bit width.
    TypeDecl.KindCase.ENUM ->
      if (typeDecl.enum.width > 0) BitVal(0L, typeDecl.enum.width) else UnitVal
    TypeDecl.KindCase.KIND_NOT_SET,
    null -> UnitVal
  }
}

private fun defaultStruct(
  typeName: String,
  fieldDecls: List<fourward.FieldDecl>,
  types: Map<String, TypeDecl>,
): StructVal =
  StructVal(
    typeName = typeName,
    fields = CompactFieldMap.of(fieldDecls.map { f -> f.name to defaultValue(f.type, types) }),
  )
