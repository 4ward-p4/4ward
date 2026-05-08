package fourward.simulator

import fourward.ArrayIndex
import fourward.BehavioralConfig
import fourward.BinaryOp
import fourward.BinaryOperator
import fourward.BitType
import fourward.Cast
import fourward.Concat
import fourward.Expr
import fourward.FieldAccess
import fourward.IntType
import fourward.Literal
import fourward.MethodCall
import fourward.MuxExpr
import fourward.NameRef
import fourward.Slice
import fourward.Type
import fourward.UnaryOp
import fourward.UnaryOperator
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Interpreter] expression evaluation.
 *
 * Tests cover literal evaluation, name references, binary/unary operators, field access, casts, and
 * header validity methods. Each test builds a minimal [Expr] proto and calls [Interpreter.evalExpr]
 * directly.
 */
class InterpreterExprTest {

  private val emptyConfig = BehavioralConfig.getDefaultInstance()

  private val emptyEnv
    get() = Environment()

  private fun interp() = interpreterExecution(emptyConfig, TableStore())

  /** Interpreter with v1model hash extern handler, for hash tests. */
  private fun interpWithHash() =
    interpreterExecution(
      emptyConfig,
      TableStore(),
      externHandler =
        ExternHandler { call, eval ->
          require(call is ExternCall.FreeFunction && call.name == "hash") {
            "unexpected extern: $call"
          }
          val algo = (eval.evalArg(1) as EnumVal).member
          val base = (eval.evalArg(2) as BitVal).bits.value
          val data = eval.evalArg(3) as StructVal
          val max = (eval.evalArg(4) as BitVal).bits.value
          val hashVal = computeHash(algo, data)
          val result = if (max > BigInteger.ZERO) base + hashVal.mod(max) else base
          val resultWidth = eval.argType(0).bit.width
          eval.writeOutArg(0, BitVal(BitVector(result, resultWidth)))
          UnitVal
        },
    )

  // ---------------------------------------------------------------------------
  // Expr builder helpers
  // ---------------------------------------------------------------------------

  /** Signed integer literal of [value] in [width] bits. */
  private fun signedBit(value: Long, width: Int): Expr =
    Expr.newBuilder()
      .setLiteral(Literal.newBuilder().setInteger(value))
      .setType(Type.newBuilder().setSignedInt(IntType.newBuilder().setWidth(width)))
      .build()

  private fun boolType(): Type = Type.newBuilder().setBoolean(true).build()

  private fun binop(op: BinaryOperator, left: Expr, right: Expr, type: Type): Expr =
    Expr.newBuilder()
      .setBinaryOp(BinaryOp.newBuilder().setOp(op).setLeft(left).setRight(right))
      .setType(type)
      .build()

  // ---------------------------------------------------------------------------
  // Literal evaluation
  // ---------------------------------------------------------------------------

  @Test
  fun `integer literal evaluates to BitVal`() {
    assertEquals(BitVal(42, 8), interp().evalExpr(bit(42, 8), emptyEnv))
  }

  @Test
  fun `signed integer literal evaluates to IntVal`() {
    val result = interp().evalExpr(signedBit(42, 8), emptyEnv)
    assertEquals(
      IntVal(SignedBitVector.fromUnsignedBits(java.math.BigInteger.valueOf(42), 8)),
      result,
    )
  }

  @Test
  fun `boolean literal true evaluates to BoolVal`() {
    assertEquals(BoolVal(true), interp().evalExpr(boolLit(true), emptyEnv))
  }

  @Test
  fun `boolean literal false evaluates to BoolVal`() {
    assertEquals(BoolVal(false), interp().evalExpr(boolLit(false), emptyEnv))
  }

  // ---------------------------------------------------------------------------
  // Name reference
  // ---------------------------------------------------------------------------

  @Test
  fun `name ref resolves variable from environment`() {
    val env = emptyEnv
    env.define("x", BitVal(7, 8))
    assertEquals(BitVal(7, 8), interp().evalExpr(nameRef("x"), env))
  }

  @Test
  fun `undefined name ref throws`() {
    assertThrows(IllegalStateException::class.java) {
      interp().evalExpr(nameRef("missing"), emptyEnv)
    }
  }

  // ---------------------------------------------------------------------------
  // Binary operations
  // ---------------------------------------------------------------------------

  @Test
  fun `ADD wraps at width boundary`() {
    assertEquals(
      BitVal(0, 8),
      interp().evalExpr(binop(BinaryOperator.ADD, bit(255, 8), bit(1, 8), bitType(8)), emptyEnv),
    )
  }

  @Test
  fun `SUB underflows with wrapping`() {
    assertEquals(
      BitVal(255, 8),
      interp().evalExpr(binop(BinaryOperator.SUB, bit(0, 8), bit(1, 8), bitType(8)), emptyEnv),
    )
  }

  @Test
  fun `EQ returns true for equal values`() {
    assertEquals(
      BoolVal(true),
      interp().evalExpr(binop(BinaryOperator.EQ, bit(5, 8), bit(5, 8), boolType()), emptyEnv),
    )
  }

  @Test
  fun `EQ returns false for unequal values`() {
    assertEquals(
      BoolVal(false),
      interp().evalExpr(binop(BinaryOperator.EQ, bit(5, 8), bit(6, 8), boolType()), emptyEnv),
    )
  }

  @Test
  fun `LT returns true when left is less than right`() {
    assertEquals(
      BoolVal(true),
      interp().evalExpr(binop(BinaryOperator.LT, bit(3, 8), bit(5, 8), boolType()), emptyEnv),
    )
  }

  @Test
  fun `BIT_AND masks bits`() {
    assertEquals(
      BitVal(0b1010_0000, 8),
      interp()
        .evalExpr(
          binop(BinaryOperator.BIT_AND, bit(0b1010_1010L, 8), bit(0b1111_0000L, 8), bitType(8)),
          emptyEnv,
        ),
    )
  }

  @Test
  fun `logical AND of two trues is true`() {
    assertEquals(
      BoolVal(true),
      interp()
        .evalExpr(binop(BinaryOperator.AND, boolLit(true), boolLit(true), boolType()), emptyEnv),
    )
  }

  @Test
  fun `logical AND with false is false`() {
    assertEquals(
      BoolVal(false),
      interp()
        .evalExpr(binop(BinaryOperator.AND, boolLit(true), boolLit(false), boolType()), emptyEnv),
    )
  }

  @Test
  fun `NEQ returns true for unequal values`() {
    assertEquals(
      BoolVal(true),
      interp().evalExpr(binop(BinaryOperator.NEQ, bit(3, 8), bit(4, 8), boolType()), emptyEnv),
    )
  }

  @Test
  fun `NEQ returns false for equal values`() {
    assertEquals(
      BoolVal(false),
      interp().evalExpr(binop(BinaryOperator.NEQ, bit(5, 8), bit(5, 8), boolType()), emptyEnv),
    )
  }

  @Test
  fun `GT returns true when left is greater`() {
    assertEquals(
      BoolVal(true),
      interp().evalExpr(binop(BinaryOperator.GT, bit(6, 8), bit(4, 8), boolType()), emptyEnv),
    )
  }

  @Test
  fun `LE returns true when left equals right`() {
    assertEquals(
      BoolVal(true),
      interp().evalExpr(binop(BinaryOperator.LE, bit(3, 8), bit(3, 8), boolType()), emptyEnv),
    )
  }

  @Test
  fun `GE returns true when left is greater or equal`() {
    assertEquals(
      BoolVal(true),
      interp().evalExpr(binop(BinaryOperator.GE, bit(7, 8), bit(7, 8), boolType()), emptyEnv),
    )
  }

  @Test
  fun `logical OR is true when either operand is true`() {
    assertEquals(
      BoolVal(true),
      interp()
        .evalExpr(binop(BinaryOperator.OR, boolLit(false), boolLit(true), boolType()), emptyEnv),
    )
  }

  @Test
  fun `logical OR is false when both are false`() {
    assertEquals(
      BoolVal(false),
      interp()
        .evalExpr(binop(BinaryOperator.OR, boolLit(false), boolLit(false), boolType()), emptyEnv),
    )
  }

  @Test
  fun `BIT_OR sets bits from either operand`() {
    assertEquals(
      BitVal(0b1111_0000, 8),
      interp()
        .evalExpr(
          binop(BinaryOperator.BIT_OR, bit(0b1010_0000L, 8), bit(0b0101_0000L, 8), bitType(8)),
          emptyEnv,
        ),
    )
  }

  @Test
  fun `BIT_XOR flips bits that differ`() {
    assertEquals(
      BitVal(0b1111_1111, 8),
      interp()
        .evalExpr(
          binop(BinaryOperator.BIT_XOR, bit(0b1010_1010L, 8), bit(0b0101_0101L, 8), bitType(8)),
          emptyEnv,
        ),
    )
  }

  @Test
  fun `SHL shifts bits left`() {
    assertEquals(
      BitVal(0b1000_0000, 8),
      interp().evalExpr(binop(BinaryOperator.SHL, bit(1, 8), bit(7, 8), bitType(8)), emptyEnv),
    )
  }

  @Test
  fun `SHR shifts bits right`() {
    assertEquals(
      BitVal(1, 8),
      interp()
        .evalExpr(binop(BinaryOperator.SHR, bit(0b1000_0000L, 8), bit(7, 8), bitType(8)), emptyEnv),
    )
  }

  @Test
  fun `MUL multiplies with wrapping`() {
    // 200 * 2 = 400 → 400 mod 256 = 144
    assertEquals(
      BitVal(144, 8),
      interp().evalExpr(binop(BinaryOperator.MUL, bit(200, 8), bit(2, 8), bitType(8)), emptyEnv),
    )
  }

  @Test
  fun `DIV truncates toward zero`() {
    assertEquals(
      BitVal(3, 8),
      interp().evalExpr(binop(BinaryOperator.DIV, bit(7, 8), bit(2, 8), bitType(8)), emptyEnv),
    )
  }

  @Test
  fun `MOD returns remainder`() {
    assertEquals(
      BitVal(1, 8),
      interp().evalExpr(binop(BinaryOperator.MOD, bit(7, 8), bit(2, 8), bitType(8)), emptyEnv),
    )
  }

  @Test
  fun `ADD_SAT clamps to max on overflow`() {
    val expr = binop(BinaryOperator.ADD_SAT, bit(255, 8), bit(1, 8), bitType(8))
    assertEquals(BitVal(255, 8), interp().evalExpr(expr, emptyEnv))
  }

  @Test
  fun `SUB_SAT clamps to zero on underflow`() {
    assertEquals(
      BitVal(0, 8),
      interp().evalExpr(binop(BinaryOperator.SUB_SAT, bit(0, 8), bit(1, 8), bitType(8)), emptyEnv),
    )
  }

  // ---------------------------------------------------------------------------
  // Unary operations
  // ---------------------------------------------------------------------------

  @Test
  fun `unary NOT negates boolean`() {
    val expr =
      Expr.newBuilder()
        .setUnaryOp(UnaryOp.newBuilder().setOp(UnaryOperator.NOT).setExpr(boolLit(true)))
        .setType(boolType())
        .build()
    assertEquals(BoolVal(false), interp().evalExpr(expr, emptyEnv))
  }

  @Test
  fun `unary BIT_NOT inverts all bits`() {
    val expr =
      Expr.newBuilder()
        .setUnaryOp(UnaryOp.newBuilder().setOp(UnaryOperator.BIT_NOT).setExpr(bit(0b1010_1010L, 8)))
        .setType(bitType(8))
        .build()
    assertEquals(BitVal(0b0101_0101, 8), interp().evalExpr(expr, emptyEnv))
  }

  @Test
  fun `unary NEG negates with two's-complement wrapping`() {
    // -1 mod 256 = 255; -0 = 0
    val expr =
      Expr.newBuilder()
        .setUnaryOp(UnaryOp.newBuilder().setOp(UnaryOperator.NEG).setExpr(bit(1, 8)))
        .setType(bitType(8))
        .build()
    assertEquals(BitVal(255, 8), interp().evalExpr(expr, emptyEnv))
  }

  // ---------------------------------------------------------------------------
  // Field access
  // ---------------------------------------------------------------------------

  @Test
  fun `field access reads field from header`() {
    val env = emptyEnv
    env.define(
      "hdr",
      HeaderVal(
        typeName = "ethernet_t",
        fields = mutableMapOf("etherType" to BitVal(0x0800, 16)),
        valid = true,
      ),
    )
    val expr =
      Expr.newBuilder()
        .setFieldAccess(FieldAccess.newBuilder().setExpr(nameRef("hdr")).setFieldName("etherType"))
        .setType(bitType(16))
        .build()
    assertEquals(BitVal(0x0800, 16), interp().evalExpr(expr, env))
  }

  @Test
  fun `field access reads field from struct`() {
    val env = emptyEnv
    env.define(
      "meta",
      StructVal(typeName = "meta_t", fields = mutableMapOf("count" to BitVal(3, 8))),
    )
    val expr =
      Expr.newBuilder()
        .setFieldAccess(FieldAccess.newBuilder().setExpr(nameRef("meta")).setFieldName("count"))
        .setType(bitType(8))
        .build()
    assertEquals(BitVal(3, 8), interp().evalExpr(expr, env))
  }

  // ---------------------------------------------------------------------------
  // Cast
  // ---------------------------------------------------------------------------

  @Test
  fun `cast truncates to narrower width`() {
    // 0xAB (8 bits) → 4 bits = 0xB
    val expr =
      Expr.newBuilder()
        .setCast(Cast.newBuilder().setTargetType(bitType(4)).setExpr(bit(0xABL, 8)))
        .setType(bitType(4))
        .build()
    assertEquals(BitVal(0xB, 4), interp().evalExpr(expr, emptyEnv))
  }

  @Test
  fun `cast widens to larger width`() {
    val expr =
      Expr.newBuilder()
        .setCast(Cast.newBuilder().setTargetType(bitType(16)).setExpr(bit(0xABL, 8)))
        .setType(bitType(16))
        .build()
    assertEquals(BitVal(0xAB, 16), interp().evalExpr(expr, emptyEnv))
  }

  // ---------------------------------------------------------------------------
  // Slice, Concat, ArrayIndex
  // ---------------------------------------------------------------------------

  @Test
  fun `slice extracts bit range from expression`() {
    // 0xAB = 1010_1011; [7:4] = 1010 = 0xA
    val expr =
      Expr.newBuilder()
        .setSlice(Slice.newBuilder().setExpr(bit(0xABL, 8)).setHi(7).setLo(4))
        .setType(bitType(4))
        .build()
    assertEquals(BitVal(0xA, 4), interp().evalExpr(expr, emptyEnv))
  }

  @Test
  fun `concat joins two bit vectors`() {
    // 0xA (4 bits) ++ 0xB (4 bits) = 0xAB (8 bits)
    val expr =
      Expr.newBuilder()
        .setConcat(Concat.newBuilder().setLeft(bit(0xAL, 4)).setRight(bit(0xBL, 4)))
        .setType(bitType(8))
        .build()
    assertEquals(BitVal(0xAB, 8), interp().evalExpr(expr, emptyEnv))
  }

  @Test
  fun `array index reads the correct stack element`() {
    val env = emptyEnv
    val hdr0 = HeaderVal("vlan_t", mutableMapOf("vid" to BitVal(10, 12)), valid = true)
    val hdr1 = HeaderVal("vlan_t", mutableMapOf("vid" to BitVal(20, 12)), valid = true)
    env.define("stk", HeaderStackVal("vlan_t", mutableListOf(hdr0, hdr1)))
    val expr =
      Expr.newBuilder()
        .setArrayIndex(ArrayIndex.newBuilder().setExpr(nameRef("stk")).setIndex(bit(1, 8)))
        .setType(Type.newBuilder().setNamed("vlan_t"))
        .build()
    assertEquals(hdr1, interp().evalExpr(expr, env))
  }

  @Test
  fun `array index out of bounds returns default value`() {
    val env = emptyEnv
    val hdr0 = HeaderVal("vlan_t", mutableMapOf("vid" to BitVal(10, 12)), valid = true)
    env.define("stk", HeaderStackVal("vlan_t", mutableListOf(hdr0)))
    val expr =
      Expr.newBuilder()
        .setArrayIndex(ArrayIndex.newBuilder().setExpr(nameRef("stk")).setIndex(bit(5, 8)))
        .setType(Type.newBuilder().setNamed("vlan_t"))
        .build()
    // Out-of-bounds returns the type's default value (P4 spec §8.18).
    // vlan_t isn't registered in the empty config, so defaultValue returns UnitVal.
    assertEquals(UnitVal, interp().evalExpr(expr, env))
  }

  // ---------------------------------------------------------------------------
  // Header stack properties (P4 spec §8.18)
  // ---------------------------------------------------------------------------

  private fun headerStack(): Pair<Environment, HeaderStackVal> {
    val env = emptyEnv
    val hdr0 = HeaderVal("vlan_t", mutableMapOf("vid" to BitVal(10, 12)), valid = true)
    val hdr1 = HeaderVal("vlan_t", mutableMapOf("vid" to BitVal(20, 12)), valid = true)
    val stack = HeaderStackVal("vlan_t", mutableListOf(hdr0, hdr1))
    env.define("stk", stack)
    return env to stack
  }

  private fun stackFieldAccess(fieldName: String): Expr =
    Expr.newBuilder()
      .setFieldAccess(FieldAccess.newBuilder().setExpr(nameRef("stk")).setFieldName(fieldName))
      .build()

  @Test
  fun `stack next returns element at nextIndex and advances`() {
    val (env, stack) = headerStack()
    val interp = interp()
    val first = interp.evalExpr(stackFieldAccess("next"), env)
    assertEquals(BitVal(10, 12), (first as HeaderVal).fields["vid"])
    assertEquals(1, stack.nextIndex)
    val second = interp.evalExpr(stackFieldAccess("next"), env)
    assertEquals(BitVal(20, 12), (second as HeaderVal).fields["vid"])
    assertEquals(2, stack.nextIndex)
  }

  @Test
  fun `stack next throws ParserErrorException on overflow`() {
    val (env, stack) = headerStack()
    stack.nextIndex = 2 // already past end
    assertThrows(ParserErrorException::class.java) {
      interp().evalExpr(stackFieldAccess("next"), env)
    }
  }

  @Test
  fun `stack last returns element before nextIndex`() {
    val (env, stack) = headerStack()
    stack.nextIndex = 2
    val last = interp().evalExpr(stackFieldAccess("last"), env)
    assertEquals(BitVal(20, 12), (last as HeaderVal).fields["vid"])
  }

  @Test
  fun `stack last clamps to zero when nextIndex is zero`() {
    val (env, _) = headerStack()
    val last = interp().evalExpr(stackFieldAccess("last"), env)
    assertEquals(BitVal(10, 12), (last as HeaderVal).fields["vid"])
  }

  @Test
  fun `stack size returns fixed stack length`() {
    val (env, _) = headerStack()
    assertEquals(BitVal(2, 32), interp().evalExpr(stackFieldAccess("size"), env))
  }

  @Test
  fun `stack lastIndex returns size minus one`() {
    val (env, _) = headerStack()
    assertEquals(BitVal(1, 32), interp().evalExpr(stackFieldAccess("lastIndex"), env))
  }

  // ---------------------------------------------------------------------------
  // Header validity method calls
  // ---------------------------------------------------------------------------

  @Test
  fun `isValid returns false for invalid header`() {
    val env = emptyEnv
    env.define("hdr", HeaderVal(typeName = "ethernet_t", valid = false))
    val expr =
      Expr.newBuilder()
        .setMethodCall(MethodCall.newBuilder().setTarget(nameRef("hdr")).setMethod("isValid"))
        .setType(boolType())
        .build()
    assertEquals(BoolVal(false), interp().evalExpr(expr, env))
  }

  @Test
  fun `isValid returns true for valid header`() {
    val env = emptyEnv
    env.define("hdr", HeaderVal(typeName = "ethernet_t", valid = true))
    val expr =
      Expr.newBuilder()
        .setMethodCall(MethodCall.newBuilder().setTarget(nameRef("hdr")).setMethod("isValid"))
        .setType(boolType())
        .build()
    assertEquals(BoolVal(true), interp().evalExpr(expr, env))
  }

  @Test
  fun `setValid marks header as valid`() {
    val env = emptyEnv
    val hdr = HeaderVal(typeName = "ethernet_t", valid = false)
    env.define("hdr", hdr)
    val expr =
      Expr.newBuilder()
        .setMethodCall(MethodCall.newBuilder().setTarget(nameRef("hdr")).setMethod("setValid"))
        .setType(boolType())
        .build()
    interp().evalExpr(expr, env)
    assertTrue(hdr.valid)
  }

  @Test
  fun `setValid on union member invalidates siblings`() {
    val memberA = HeaderVal(typeName = "A_t", valid = true)
    val memberB = HeaderVal(typeName = "B_t", valid = false)
    val union = StructVal("U", mutableMapOf("a" to memberA, "b" to memberB))
    val env = emptyEnv
    env.define("u", union)

    // Build: u.b.setValid()  with u typed as named "U"
    val unionRef =
      Expr.newBuilder()
        .setNameRef(NameRef.newBuilder().setName("u"))
        .setType(Type.newBuilder().setNamed("U"))
        .build()
    val memberAccess =
      Expr.newBuilder()
        .setFieldAccess(FieldAccess.newBuilder().setExpr(unionRef).setFieldName("b"))
        .setType(Type.newBuilder().setNamed("B_t"))
        .build()
    val expr =
      Expr.newBuilder()
        .setMethodCall(MethodCall.newBuilder().setTarget(memberAccess).setMethod("setValid"))
        .setType(boolType())
        .build()

    // Register "U" as a header_union type so invalidateUnionSiblings fires.
    val config =
      BehavioralConfig.newBuilder()
        .addTypes(
          fourward.TypeDecl.newBuilder()
            .setName("U")
            .setHeaderUnion(fourward.HeaderUnionDecl.getDefaultInstance())
        )
        .build()
    interpreterExecution(config, TableStore()).evalExpr(expr, env)

    assertTrue(memberB.valid)
    assertFalse(memberA.valid)
  }

  // ---------------------------------------------------------------------------
  // Mux (ternary) operator
  // ---------------------------------------------------------------------------

  private fun mux(condition: Expr, thenExpr: Expr, elseExpr: Expr): Expr =
    Expr.newBuilder()
      .setMux(
        MuxExpr.newBuilder().setCondition(condition).setThenExpr(thenExpr).setElseExpr(elseExpr)
      )
      .setType(thenExpr.type)
      .build()

  @Test
  fun `mux selects then-branch when condition is true`() {
    assertEquals(
      BitVal(1, 16),
      interp().evalExpr(mux(boolLit(true), bit(1, 16), bit(2, 16)), emptyEnv),
    )
  }

  @Test
  fun `mux selects else-branch when condition is false`() {
    assertEquals(
      BitVal(2, 16),
      interp().evalExpr(mux(boolLit(false), bit(1, 16), bit(2, 16)), emptyEnv),
    )
  }

  @Test
  fun `mux works with computed condition`() {
    val gt =
      Expr.newBuilder()
        .setBinaryOp(
          BinaryOp.newBuilder().setOp(BinaryOperator.GT).setLeft(bit(5, 8)).setRight(bit(3, 8))
        )
        .setType(Type.newBuilder().setBoolean(true))
        .build()
    assertEquals(BitVal(1, 8), interp().evalExpr(mux(gt, bit(1, 8), bit(2, 8)), emptyEnv))
  }

  @Test
  fun `mux works with bool branches`() {
    assertEquals(
      BoolVal(false),
      interp().evalExpr(mux(boolLit(true), boolLit(false), boolLit(true)), emptyEnv),
    )
  }

  @Test
  fun `setInvalid marks header as invalid and clears fields`() {
    val env = emptyEnv
    val hdr =
      HeaderVal(
        typeName = "ethernet_t",
        fields = mutableMapOf("etherType" to BitVal(0x0800, 16)),
        valid = true,
      )
    env.define("hdr", hdr)
    val expr =
      Expr.newBuilder()
        .setMethodCall(MethodCall.newBuilder().setTarget(nameRef("hdr")).setMethod("setInvalid"))
        .setType(boolType())
        .build()
    interp().evalExpr(expr, env)
    assertFalse(hdr.valid)
    // setInvalid() zeros fields in place per P4 spec §8.17 (BMv2 treats them as zero)
    assertEquals(BitVal(0, 16), hdr.fields["etherType"])
  }

  // ---------------------------------------------------------------------------
  // StructExpr evaluation
  // ---------------------------------------------------------------------------

  @Test
  fun `enum member literal evaluates to EnumVal`() {
    val expr =
      Expr.newBuilder()
        .setLiteral(Literal.newBuilder().setEnumMember("crc16"))
        .setType(Type.newBuilder().setNamed("HashAlgorithm"))
        .build()
    val result = interp().evalExpr(expr, emptyEnv)
    assertEquals(EnumVal("crc16"), result)
  }

  @Test
  fun `struct expression evaluates to StructVal with evaluated fields`() {
    val expr =
      Expr.newBuilder()
        .setStructExpr(
          fourward.StructExpr.newBuilder()
            .addFields(fourward.StructExprField.newBuilder().setName("a").setValue(bit(0xAB, 8)))
            .addFields(fourward.StructExprField.newBuilder().setName("b").setValue(bit(0xCD, 8)))
        )
        .setType(Type.newBuilder().setNamed("my_tuple"))
        .build()

    val result = interp().evalExpr(expr, emptyEnv)
    val sv = result as StructVal
    assertEquals("my_tuple", sv.typeName)
    assertEquals(BitVal(0xAB, 8), sv.fields["a"])
    assertEquals(BitVal(0xCD, 8), sv.fields["b"])
  }

  // ---------------------------------------------------------------------------
  // hash extern
  // ---------------------------------------------------------------------------

  /** Builds an enum member literal, e.g. HashAlgorithm.crc16. */
  private fun enumLit(member: String, typeName: String): Expr =
    Expr.newBuilder()
      .setLiteral(Literal.newBuilder().setEnumMember(member))
      .setType(Type.newBuilder().setNamed(typeName))
      .build()

  /**
   * Builds a hash(__call__) method call expression: hash(out result, in algo, in base, in data, in
   * max)
   */
  private fun hashCall(resultRef: Expr, algo: Expr, base: Expr, data: Expr, max: Expr): Expr =
    Expr.newBuilder()
      .setMethodCall(
        MethodCall.newBuilder()
          .setTarget(Expr.newBuilder().setNameRef(NameRef.newBuilder().setName("hash")).build())
          .setMethod("__call__")
          .addArgs(resultRef)
          .addArgs(algo)
          .addArgs(base)
          .addArgs(data)
          .addArgs(max)
      )
      .build()

  @Test
  fun `hash extern computes crc16 with base and modulo`() {
    // hash(result, crc16, 0, { 16w1 }, 4) → crc16([0x00,0x01]) % 4 = 1
    val env = emptyEnv
    env.define("result", BitVal(0, 16))

    val resultRef =
      Expr.newBuilder()
        .setNameRef(NameRef.newBuilder().setName("result"))
        .setType(bitType(16))
        .build()
    val data =
      Expr.newBuilder()
        .setStructExpr(
          fourward.StructExpr.newBuilder()
            .addFields(fourward.StructExprField.newBuilder().setName("d").setValue(bit(1, 16)))
        )
        .setType(Type.newBuilder().setNamed("data_t"))
        .build()

    interpWithHash()
      .evalExpr(
        hashCall(resultRef, enumLit("crc16", "HashAlgorithm"), bit(0, 10), data, bit(4, 10)),
        env,
      )

    assertEquals(BitVal(1, 16), env.lookup("result"))
  }

  @Test
  fun `hash extern with max zero returns base`() {
    val env = emptyEnv
    env.define("result", BitVal(0, 16))

    val resultRef =
      Expr.newBuilder()
        .setNameRef(NameRef.newBuilder().setName("result"))
        .setType(bitType(16))
        .build()
    val data =
      Expr.newBuilder()
        .setStructExpr(
          fourward.StructExpr.newBuilder()
            .addFields(fourward.StructExprField.newBuilder().setName("d").setValue(bit(0xFF, 8)))
        )
        .setType(Type.newBuilder().setNamed("data_t"))
        .build()

    interpWithHash()
      .evalExpr(
        hashCall(resultRef, enumLit("csum16", "HashAlgorithm"), bit(42, 16), data, bit(0, 16)),
        env,
      )

    assertEquals(BitVal(42, 16), env.lookup("result"))
  }

  // ---------------------------------------------------------------------------
  // Header stack push_front / pop_front (P4 spec §8.18)
  // ---------------------------------------------------------------------------

  /** Config with a simple 8-bit header type for stack tests. */
  private val stackTestConfig =
    BehavioralConfig.newBuilder()
      .addTypes(
        fourward.TypeDecl.newBuilder()
          .setName("h_t")
          .setHeader(
            fourward.HeaderDecl.newBuilder()
              .addFields(
                fourward.FieldDecl.newBuilder()
                  .setName("f")
                  .setType(Type.newBuilder().setBit(BitType.newBuilder().setWidth(8)))
              )
          )
      )
      .build()

  private fun stackInterp() = interpreterExecution(stackTestConfig, TableStore())

  private fun makeStack(vararg validFields: Int?): HeaderStackVal {
    val headers =
      validFields.map { v ->
        if (v != null) HeaderVal("h_t", mutableMapOf("f" to BitVal(v.toLong(), 8)), valid = true)
        else HeaderVal("h_t", mutableMapOf("f" to BitVal(0L, 8)), valid = false)
      }
    return HeaderStackVal("h_t", headers.toMutableList())
  }

  private fun stackMethodCall(stackName: String, method: String, count: Int): Expr =
    Expr.newBuilder()
      .setMethodCall(
        MethodCall.newBuilder()
          .setTarget(nameRef(stackName))
          .setMethod(method)
          .addArgs(bit(count.toLong(), 32))
      )
      .setType(boolType())
      .build()

  @Test
  fun `push_front shifts elements up and inserts invalid headers`() {
    val stack = makeStack(0x11, 0x22, 0x33, null, null)
    val env = emptyEnv
    env.define("s", stack)

    stackInterp().evalExpr(stackMethodCall("s", "push_front", 2), env)

    // First 2 elements should be invalid (new); elements shifted up by 2.
    assertFalse((stack.headers[0] as HeaderVal).valid)
    assertFalse((stack.headers[1] as HeaderVal).valid)
    assertTrue((stack.headers[2] as HeaderVal).valid)
    assertEquals(BitVal(0x11, 8), (stack.headers[2] as HeaderVal).fields["f"])
    assertTrue((stack.headers[3] as HeaderVal).valid)
    assertEquals(BitVal(0x22, 8), (stack.headers[3] as HeaderVal).fields["f"])
    // Element at index 4 is 0x33 shifted from index 2.
    assertTrue((stack.headers[4] as HeaderVal).valid)
    assertEquals(BitVal(0x33, 8), (stack.headers[4] as HeaderVal).fields["f"])
  }

  @Test
  fun `pop_front shifts elements down and inserts invalid headers at end`() {
    val stack = makeStack(0x11, 0x22, 0x33, 0x44, 0x55)
    val env = emptyEnv
    env.define("s", stack)

    stackInterp().evalExpr(stackMethodCall("s", "pop_front", 2), env)

    // Elements shifted down by 2; last 2 should be invalid.
    assertTrue((stack.headers[0] as HeaderVal).valid)
    assertEquals(BitVal(0x33, 8), (stack.headers[0] as HeaderVal).fields["f"])
    assertTrue((stack.headers[1] as HeaderVal).valid)
    assertEquals(BitVal(0x44, 8), (stack.headers[1] as HeaderVal).fields["f"])
    assertTrue((stack.headers[2] as HeaderVal).valid)
    assertEquals(BitVal(0x55, 8), (stack.headers[2] as HeaderVal).fields["f"])
    assertFalse((stack.headers[3] as HeaderVal).valid)
    assertFalse((stack.headers[4] as HeaderVal).valid)
  }

  @Test
  fun `push_front updates nextIndex`() {
    val stack = makeStack(0x11, 0x22, null)
    stack.nextIndex = 2
    val env = emptyEnv
    env.define("s", stack)

    stackInterp().evalExpr(stackMethodCall("s", "push_front", 1), env)

    // nextIndex should increase by count, clamped to size.
    assertEquals(3, stack.nextIndex)
  }

  @Test
  fun `pop_front updates nextIndex`() {
    val stack = makeStack(0x11, 0x22, 0x33)
    stack.nextIndex = 3
    val env = emptyEnv
    env.define("s", stack)

    stackInterp().evalExpr(stackMethodCall("s", "pop_front", 2), env)

    // nextIndex should decrease by count, clamped to 0.
    assertEquals(1, stack.nextIndex)
  }

  @Test
  fun `push_front with count exceeding size clamps to size`() {
    val stack = makeStack(0x11, 0x22, 0x33)
    stack.nextIndex = 2
    val env = emptyEnv
    env.define("s", stack)

    stackInterp().evalExpr(stackMethodCall("s", "push_front", 10), env)

    // All elements should be invalid (pushed off the end).
    for (i in 0 until stack.size) {
      assertFalse((stack.headers[i] as HeaderVal).valid)
    }
    // nextIndex clamped to stack size.
    assertEquals(3, stack.nextIndex)
  }

  @Test
  fun `pop_front with count exceeding size clamps to size`() {
    val stack = makeStack(0x11, 0x22, 0x33)
    stack.nextIndex = 3
    val env = emptyEnv
    env.define("s", stack)

    stackInterp().evalExpr(stackMethodCall("s", "pop_front", 10), env)

    // All elements should be invalid (popped off the front).
    for (i in 0 until stack.size) {
      assertFalse((stack.headers[i] as HeaderVal).valid)
    }
    // nextIndex clamped to 0.
    assertEquals(0, stack.nextIndex)
  }
}
