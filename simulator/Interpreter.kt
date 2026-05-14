package fourward.simulator

import fourward.ActionExecutionEvent
import fourward.AssertionEvent
import fourward.AssignmentEvent
import fourward.BehavioralConfig
import fourward.BinaryOperator
import fourward.BranchEvent
import fourward.ControlDecl
import fourward.DeparserEmitEvent
import fourward.Direction
import fourward.Expr
import fourward.Expr.KindCase as ExprKind
import fourward.FieldDecl
import fourward.KeysetExpr
import fourward.Literal
import fourward.Literal.KindCase as LiteralKind
import fourward.MethodCall
import fourward.ParserDecl
import fourward.ParserTransitionEvent
import fourward.SourceInfo
import fourward.Stmt
import fourward.Stmt.KindCase as StmtKind
import fourward.TableApplyExpr
import fourward.TableBehavior
import fourward.TableLookupEvent
import fourward.TraceEvent
import fourward.Transition
import fourward.Type
import fourward.Type.KindCase as TypeKind
import fourward.TypeDecl
import fourward.UnaryOperator
import java.math.BigInteger

/**
 * Long-lived P4 interpreter engine, built once per pipeline config.
 *
 * Holds config-derived maps (parsers, controls, actions, tables, types) that never change between
 * packets. Call [execution] for each pipeline run — the [Execution] inner class walks the IR tree
 * and is cheap to construct (just field assignments). Variable scopes live in [Environment];
 * packet-level state (input buffer, output buffer, trace) lives in [PacketContext]; program-global
 * state (table entries, extern instances) lives in [TableStore].
 *
 * The interpreter is deliberately simple: it pattern-matches on proto oneof fields and dispatches
 * to focused methods. There is no bytecode compilation or optimisation — correctness and
 * readability are the goals.
 *
 * Thread safety: all fields are `val`s holding immutable proto messages and Kotlin Maps populated
 * during construction. JMM final-field publication makes the [Interpreter] safe to share across any
 * number of [Execution] threads after construction. Mutable per-run state lives in [Execution],
 * which is created fresh for each call.
 */
class Interpreter internal constructor(config: BehavioralConfig) {
  private val parsers: Map<String, ParserDecl> = config.parsersList.associateBy { it.name }

  private val controls: Map<String, ControlDecl> = config.controlsList.associateBy { it.name }

  // Actions may be declared either at the top level or as local actions inside controls.
  // After the midend, all relevant actions end up in control.localActionsList.
  //
  // Indexing rules:
  // - Every action is indexed under name (= originalName) so that table dispatch
  //   (which resolves via p4info aliases = originalNames) always finds it. We use
  //   putIfAbsent so that a renamed duplicate (e.g. the second "do_thing" copy that
  //   the midend renames to "do_thing_1") does not overwrite the authoritative first
  //   declaration, which carries the actual action body.
  // - If the midend renamed the action (currentName != ""), it is also indexed under
  //   currentName so that direct call sites using the post-midend name can resolve it.
  private val actions: Map<String, fourward.ActionDecl> = buildMap {
    fun index(action: fourward.ActionDecl) {
      if (!containsKey(action.name)) put(action.name, action)
      if (action.currentName.isNotEmpty()) put(action.currentName, action)
    }
    for (action in config.actionsList) index(action)
    for (ctrl in config.controlsList) for (action in ctrl.localActionsList) index(action)
  }

  private val tables: Map<String, TableBehavior> = config.tablesList.associateBy { it.name }

  private val types: Map<String, fourward.TypeDecl> = config.typesList.associateBy { it.name }

  private data class TableResult(val hit: Boolean, val actionName: String)

  /** Creates a lightweight per-execution context. */
  fun execution(
    tableStore: TableStore,
    packetCtx: PacketContext? = null,
    selectorOverrides: Map<String, Int> = emptyMap(),
    externHandler: ExternHandler? = null,
  ) = Execution(tableStore, packetCtx, selectorOverrides, externHandler)

  /**
   * Per-execution interpreter state. Cheap to create (4 field assignments). Accesses the outer
   * [Interpreter]'s config-derived maps directly via the inner class relationship.
   */
  inner class Execution(
    private val tableStore: TableStore,
    private val packetCtx: PacketContext? = null,
    private val selectorOverrides: Map<String, Int> = emptyMap(),
    private val externHandler: ExternHandler? = null,
  ) {

    /** Non-null packet context; throws a clear error if packet I/O is attempted without one. */
    private val packet: PacketContext
      get() = packetCtx ?: error("packet I/O requires a PacketContext")

    /** Context from the most recent table miss, for PNA's `add_entry` extern. */
    private var lastTableMissCtx: TableMissContext? = null

    /** Source info of the statement currently being executed, for trace events. */
    private var currentSourceInfo: SourceInfo? = null

    /** Name of the control block currently being executed, for BranchEvent. */
    private var currentControlName: String? = null

    /** Formats current P4 source location for inclusion in error messages. */
    private fun sourceContext(): String {
      val src = currentSourceInfo ?: return ""
      val loc = buildString {
        if (src.file.isNotEmpty()) append(src.file)
        if (src.line > 0) append(":${src.line}")
        if (src.column > 0) append(":${src.column}")
      }
      return if (loc.isNotEmpty()) " (at $loc)" else ""
    }

    // Pooled proto builders: reused across events within the same single-threaded Execution
    // to avoid per-event allocation of builder objects and their internal byte arrays.
    private val traceEventPool = TraceEvent.newBuilder()
    private val branchEventPool = BranchEvent.newBuilder()
    private val tableLookupPool = TableLookupEvent.newBuilder()
    private val actionExecPool = ActionExecutionEvent.newBuilder()

    /** Builds a TraceEvent with source info attached, if available. */
    private fun traceEventBuilder(sourceInfo: SourceInfo? = currentSourceInfo): TraceEvent.Builder {
      traceEventPool.clear()
      sourceInfo?.let { traceEventPool.sourceInfo = it }
      return traceEventPool
    }

    // -------------------------------------------------------------------------
    // Parser
    // -------------------------------------------------------------------------

    fun runParser(parserName: String, env: Environment) {
      val parser = parsers[parserName] ?: error("unknown parser: $parserName")
      withLocalScope(parser.localVarsList, env) { runParserState(parser, "start", env) }
    }

    private fun runParserState(parser: ParserDecl, startState: String, env: Environment) {
      // Index states by name for O(1) lookup during traversal.
      val statesByName = parser.statesList.associateBy { it.name }

      var stateName = startState
      while (stateName != "accept" && stateName != "reject") {
        val state =
          statesByName[stateName] ?: error("unknown parser state: $stateName in ${parser.name}")

        for (stmt in state.stmtsList) execStmt(stmt, env)

        val (nextState, selectValue, selectExpr) =
          when (state.transition.kindCase) {
            Transition.KindCase.SELECT -> evalSelectWithValue(state.transition.select, env)
            Transition.KindCase.NEXT_STATE,
            Transition.KindCase.KIND_NOT_SET,
            null -> Triple(state.transition.nextState, "", "")
          }

        packetCtx?.addTraceEvent(
          traceEventBuilder(if (state.hasSourceInfo()) state.sourceInfo else null)
            .setParserTransition(
              ParserTransitionEvent.newBuilder()
                .setParserName(parser.name)
                .setFromState(stateName)
                .setToState(nextState)
                .also {
                  if (selectValue.isNotEmpty()) it.setSelectValue(selectValue)
                  if (selectExpr.isNotEmpty()) it.setSelectExpression(selectExpr)
                }
            )
            .build()
        )

        stateName = nextState
      }
    }

    /**
     * Evaluates a select expression, returning (nextState, formattedValue, formattedExpression).
     */
    private fun evalSelectWithValue(
      select: fourward.SelectTransition,
      env: Environment,
    ): Triple<String, String, String> {
      val keyValues = select.keysList.map { evalExpr(it, env) }
      val formatted =
        keyValues.joinToString(", ") {
          when (it) {
            is BitVal -> "0x${it.bits.value.toString(16).uppercase()}"
            is IntVal -> "0x${it.bits.value.toString(16).uppercase()}"
            is BoolVal -> it.value.toString()
            else -> it.toString()
          }
        }
      val expression = select.keysList.joinToString(", ") { formatExpr(it) }
      // Keyset expressions in parser select are always compile-time constants; a single
      // empty environment is correct for all of them.
      val constEnv = Environment()
      for (case in select.casesList) {
        // P4 spec §12.14: a value_set replaces the entire keyset tuple for a case —
        // it cannot be mixed with non-value_set keysets. Checking only the first keyset
        // is sufficient to distinguish value_set cases from normal keyset cases.
        val firstKeyset = case.keysetsList.firstOrNull()
        if (firstKeyset != null && firstKeyset.hasValueSet()) {
          val members = tableStore.getValueSetMembers(firstKeyset.valueSet)
          if (members.any { member -> matchesValueSetMember(keyValues, member) }) {
            return Triple(case.nextState, formatted, expression)
          }
        } else if (
          keyValues.zip(case.keysetsList).all { (v, k) -> matchesKeyset(v, k, constEnv) }
        ) {
          return Triple(case.nextState, formatted, expression)
        }
      }
      // P4 spec §12.6: if no case matches and there is no default, reject.
      return Triple(select.defaultState.ifEmpty { "reject" }, formatted, expression)
    }

    /** Human-readable rendering of an IR expression (best-effort, for trace display). */
    private fun formatExpr(expr: fourward.Expr): String =
      when (expr.kindCase) {
        ExprKind.NAME_REF -> expr.nameRef.name
        ExprKind.FIELD_ACCESS ->
          "${formatExpr(expr.fieldAccess.expr)}.${expr.fieldAccess.fieldName}"
        ExprKind.ARRAY_INDEX ->
          "${formatExpr(expr.arrayIndex.expr)}[${formatExpr(expr.arrayIndex.index)}]"
        ExprKind.SLICE -> "${formatExpr(expr.slice.expr)}[${expr.slice.hi}:${expr.slice.lo}]"
        ExprKind.LITERAL -> {
          val lit = expr.literal
          when (lit.kindCase) {
            LiteralKind.INTEGER -> "0x${lit.integer.toString(16).uppercase()}"
            LiteralKind.BOOLEAN -> lit.boolean.toString()
            LiteralKind.BIG_INTEGER,
            LiteralKind.ERROR_MEMBER,
            LiteralKind.ENUM_MEMBER,
            LiteralKind.STRING_LITERAL,
            LiteralKind.KIND_NOT_SET,
            null -> lit.toString().trim()
          }
        }
        else -> "?"
      }

    private fun matchesKeyset(
      value: Value,
      keyset: fourward.KeysetExpr,
      constEnv: Environment,
    ): Boolean =
      when (keyset.kindCase) {
        KeysetExpr.KindCase.DEFAULT_CASE -> true
        KeysetExpr.KindCase.EXACT -> value == evalExpr(keyset.exact, constEnv)
        KeysetExpr.KindCase.MASK -> {
          val v = (value as BitVal).bits
          val mask = (evalExpr(keyset.mask.mask, constEnv) as BitVal).bits
          val want = (evalExpr(keyset.mask.value, constEnv) as BitVal).bits
          (v and mask) == (want and mask)
        }
        KeysetExpr.KindCase.RANGE -> {
          val v = (value as BitVal).bits
          val lo = (evalExpr(keyset.range.lo, constEnv) as BitVal).bits
          val hi = (evalExpr(keyset.range.hi, constEnv) as BitVal).bits
          v >= lo && v <= hi
        }
        KeysetExpr.KindCase.VALUE_SET ->
          error("value_set keyset should be handled before matchesKeyset${sourceContext()}")
        KeysetExpr.KindCase.KIND_NOT_SET,
        null -> error("unhandled keyset kind: $keyset${sourceContext()}")
      }

    /**
     * Matches a list of select key values against a single [ValueSetMember]'s field matches.
     *
     * Each member has one [FieldMatch] per key, in the same order as the select keys. An unset
     * FieldMatch acts as a wildcard (matches any value).
     */
    private fun matchesValueSetMember(
      keyValues: List<Value>,
      member: p4.v1.P4RuntimeOuterClass.ValueSetMember,
    ): Boolean {
      if (member.matchCount != keyValues.size) return false
      return keyValues.zip(member.matchList).all { (value, fieldMatch) ->
        matchFieldMatch(value, fieldMatch)
      }
    }

    /** Matches a single runtime value against a P4Runtime [FieldMatch]. */
    private fun matchFieldMatch(
      value: Value,
      fieldMatch: p4.v1.P4RuntimeOuterClass.FieldMatch,
    ): Boolean {
      val bits =
        when (value) {
          is BitVal -> value.bits
          is BoolVal -> if (value.value) BOOL_TRUE_BITS else BOOL_FALSE_BITS
          else -> return false
        }
      return matchesFieldMatch(bits, fieldMatch)
    }

    // -------------------------------------------------------------------------
    // Control
    // -------------------------------------------------------------------------

    fun runControl(controlName: String, env: Environment) {
      val control = controls[controlName] ?: error("unknown control: $controlName")
      currentControlName = controlName
      withLocalScope(control.localVarsList, env) { execBlock(control.applyBodyList, env) }
    }

    /**
     * Resumes from an action selector fork point: executes the selected member's action, then the
     * continuation (remaining statements captured as the fork propagated through [execBlock]). The
     * caller must pop the control scope afterwards.
     */
    fun resumeFromFork(
      fork: ActionSelectorFork,
      member: TableStore.MemberAction,
      env: Environment,
    ) {
      currentSourceInfo = fork.sourceInfo
      val tableBehavior = tables[fork.tableName] ?: error("unknown table: ${fork.tableName}")
      val resolvedName = tableBehavior.actionOverridesMap[member.actionName] ?: member.actionName
      execAction(resolvedName, member.params, env)
      for (stmts in fork.remainingStmts) {
        execBlock(stmts, env)
      }
    }

    /** Pushes a scope, defines [localVars], runs [body], then pops the scope. */
    private inline fun withLocalScope(
      localVars: List<fourward.VarDecl>,
      env: Environment,
      body: () -> Unit,
    ) {
      env.pushScope()
      try {
        for (varDecl in localVars) {
          val init =
            if (varDecl.hasInitializer()) evalExpr(varDecl.initializer, env)
            else defaultValue(varDecl.type, types)
          env.define(varDecl.name, init)
        }
        body()
      } finally {
        env.popScope()
      }
    }

    // -------------------------------------------------------------------------
    // Statements
    // -------------------------------------------------------------------------

    private fun execBlock(stmts: List<Stmt>, env: Environment) {
      var i = 0
      try {
        while (i < stmts.size) {
          execStmt(stmts[i], env)
          i++
        }
      } catch (fork: ActionSelectorFork) {
        fork.remainingStmts.addLast(stmts.subList(i + 1, stmts.size))
        throw fork
      }
    }

    private fun execStmt(stmt: Stmt, env: Environment) {
      if (stmt.hasSourceInfo()) currentSourceInfo = stmt.sourceInfo else currentSourceInfo = null
      when (stmt.kindCase) {
        StmtKind.ASSIGNMENT -> {
          val rhs = stmt.assignment.rhs
          val value = evalExpr(rhs, env)
          setLValue(stmt.assignment.lhs, value, env)
          val targetPath = exprPath(stmt.assignment.lhs)
          if (targetPath != null) {
            packetCtx?.addTraceEvent(
              traceEventBuilder()
                .setAssignment(AssignmentEvent.newBuilder().setTarget(targetPath))
                .putAllVariableValues(collectVariableValues(rhs, env))
                .setResultValue(formatValue(value))
                .build()
            )
          }
        }
        StmtKind.METHOD_CALL -> evalExpr(stmt.methodCall.call, env) // result discarded
        StmtKind.IF_STMT -> execIf(stmt.ifStmt, env)
        StmtKind.SWITCH_STMT -> execSwitch(stmt.switchStmt, env)
        StmtKind.BLOCK -> execBlock(stmt.block.stmtsList, env)
        StmtKind.EXIT -> throw ExitException()
        StmtKind.RETURN_STMT -> throw ReturnException(evalExpr(stmt.returnStmt.value, env))
        StmtKind.KIND_NOT_SET,
        null -> error("unhandled statement kind: $stmt${sourceContext()}")
      }
    }

    private fun execIf(ifStmt: fourward.IfStmt, env: Environment) {
      val condition = (evalExpr(ifStmt.condition, env) as BoolVal).value

      packetCtx?.addTraceEvent(
        traceEventBuilder()
          .setBranch(
            branchEventPool.clear().setControlName(currentControlName ?: "").setTaken(condition)
          )
          .putAllVariableValues(collectVariableValues(ifStmt.condition, env))
          .setResultValue(condition.toString())
          .build()
      )

      if (condition) {
        execBlock(ifStmt.thenBlock.stmtsList, env)
      } else {
        execBlock(ifStmt.elseBlock.stmtsList, env)
      }
    }

    private fun execSwitch(switchStmt: fourward.SwitchStmt, env: Environment) {
      val tableResult = applyTable(switchStmt.subject.tableApply.tableName, env)
      val matchedCase = switchStmt.casesList.find { it.actionName == tableResult.actionName }
      if (matchedCase != null) {
        execBlock(matchedCase.block.stmtsList, env)
      } else {
        execBlock(switchStmt.defaultBlock.stmtsList, env)
      }
    }

    // -------------------------------------------------------------------------
    // Expressions
    // -------------------------------------------------------------------------

    /**
     * Collects all variable references in [expr] and resolves their current values from [env].
     * Returns a map from P4 field paths (e.g. "standard_metadata.egress_rid") to formatted values.
     */
    private fun collectVariableValues(expr: Expr, env: Environment): Map<String, String> {
      val result = mutableMapOf<String, String>()
      collectVariableValuesRecursive(expr, env, result)
      return result
    }

    private fun collectVariableValuesRecursive(
      expr: Expr,
      env: Environment,
      out: MutableMap<String, String>,
    ) {
      when (expr.kindCase) {
        ExprKind.NAME_REF -> {
          val value = env.lookup(expr.nameRef.name)
          if (value != null) out[expr.nameRef.name] = formatValue(value)
        }
        ExprKind.FIELD_ACCESS -> {
          if (!expr.fieldAccess.expr.hasTableApply()) {
            val path = fieldAccessPath(expr.fieldAccess)
            if (path != null) {
              val value = resolveFieldAccess(expr.fieldAccess, env)
              if (value != null) out[path] = formatValue(value)
            } else {
              collectVariableValuesRecursive(expr.fieldAccess.expr, env, out)
            }
          }
        }
        ExprKind.BINARY_OP -> {
          collectVariableValuesRecursive(expr.binaryOp.left, env, out)
          collectVariableValuesRecursive(expr.binaryOp.right, env, out)
        }
        ExprKind.UNARY_OP -> collectVariableValuesRecursive(expr.unaryOp.expr, env, out)
        ExprKind.MUX -> {
          collectVariableValuesRecursive(expr.mux.condition, env, out)
          collectVariableValuesRecursive(expr.mux.thenExpr, env, out)
          collectVariableValuesRecursive(expr.mux.elseExpr, env, out)
        }
        ExprKind.CAST -> collectVariableValuesRecursive(expr.cast.expr, env, out)
        ExprKind.SLICE -> collectVariableValuesRecursive(expr.slice.expr, env, out)
        ExprKind.METHOD_CALL -> {
          for (arg in expr.methodCall.argsList) {
            collectVariableValuesRecursive(arg, env, out)
          }
        }
        ExprKind.CONCAT -> {
          collectVariableValuesRecursive(expr.concat.left, env, out)
          collectVariableValuesRecursive(expr.concat.right, env, out)
        }
        ExprKind.LITERAL,
        ExprKind.ARRAY_INDEX,
        ExprKind.STRUCT_EXPR,
        ExprKind.TABLE_APPLY,
        ExprKind.KIND_NOT_SET,
        null -> {}
      }
    }

    /**
     * Resolves a field access chain to its value without re-evaluating the full expression. Returns
     * null if any link in the chain is undefined.
     */
    private fun resolveFieldAccess(fa: fourward.FieldAccess, env: Environment): Value? {
      val target =
        when {
          fa.expr.hasNameRef() -> env.lookup(fa.expr.nameRef.name) ?: return null
          fa.expr.hasFieldAccess() -> resolveFieldAccess(fa.expr.fieldAccess, env) ?: return null
          else -> return null
        }
      return when (target) {
        is StructVal -> target.fields[fa.fieldName]
        is HeaderVal -> target.fields[fa.fieldName]
        else -> null
      }
    }

    /** Builds a dotted path from an expression (NameRef or FieldAccess chain). */
    private fun exprPath(expr: Expr): String? =
      when {
        expr.hasNameRef() -> expr.nameRef.name
        expr.hasFieldAccess() -> fieldAccessPath(expr.fieldAccess)
        else -> null
      }

    /** Builds a dotted path like "standard_metadata.egress_rid" from nested FieldAccess. */
    private fun fieldAccessPath(fa: fourward.FieldAccess): String? {
      val prefix =
        when {
          fa.expr.hasNameRef() -> fa.expr.nameRef.name
          fa.expr.hasFieldAccess() -> fieldAccessPath(fa.expr.fieldAccess) ?: return null
          else -> return null
        }
      return "$prefix.${fa.fieldName}"
    }

    private fun formatValue(value: Value): String =
      when (value) {
        is BitVal -> formatBigInt(value.bits.value)
        is BoolVal -> value.value.toString()
        is InfIntVal -> formatBigInt(value.value)
        is EnumVal -> value.member
        is ErrorVal -> value.member
        is StringVal -> "\"${value.value}\""
        else -> value.toString()
      }

    private fun formatBigInt(v: java.math.BigInteger): String =
      if (v >= java.math.BigInteger.ZERO && v <= maxDecimalOnly) {
        v.toString()
      } else {
        "$v (0x${v.toString(16)})"
      }

    // Dispatch on kindCase for exhaustive matching (compiler-enforced coverage of all oneof arms).
    fun evalExpr(expr: Expr, env: Environment): Value =
      when (expr.kindCase) {
        ExprKind.LITERAL -> evalLiteral(expr.literal, expr.type)
        ExprKind.NAME_REF ->
          env.lookup(expr.nameRef.name)
            ?: error("undefined variable: ${expr.nameRef.name}${sourceContext()}")
        ExprKind.FIELD_ACCESS -> evalFieldAccess(expr.fieldAccess, env)
        ExprKind.ARRAY_INDEX -> evalArrayIndex(expr.arrayIndex, env)
        ExprKind.SLICE -> evalSlice(expr.slice, env)
        ExprKind.CONCAT -> evalConcat(expr.concat, env)
        ExprKind.CAST -> evalCast(expr.cast, env)
        ExprKind.BINARY_OP -> evalBinaryOp(expr.binaryOp, env)
        ExprKind.UNARY_OP -> evalUnaryOp(expr.unaryOp, env)
        ExprKind.METHOD_CALL -> evalMethodCall(expr.methodCall, expr.type, env)
        ExprKind.MUX -> evalMux(expr.mux, env)
        ExprKind.STRUCT_EXPR -> evalStructExpr(expr.structExpr, expr.type, env)
        ExprKind.TABLE_APPLY -> {
          val result = applyTable(expr.tableApply.tableName, env)
          when (expr.tableApply.accessKind) {
            TableApplyExpr.AccessKind.HIT -> BoolVal(result.hit)
            TableApplyExpr.AccessKind.MISS -> BoolVal(!result.hit)
            else -> UnitVal // RESULT / default: switch context
          }
        }
        ExprKind.KIND_NOT_SET,
        null -> error("unhandled expression kind: $expr${sourceContext()}")
      }

    private fun evalLiteral(lit: Literal, type: fourward.Type): Value =
      when (lit.kindCase) {
        LiteralKind.BOOLEAN -> BoolVal(lit.boolean)
        LiteralKind.ERROR_MEMBER -> ErrorVal(lit.errorMember)
        LiteralKind.ENUM_MEMBER -> EnumVal(lit.enumMember)
        LiteralKind.STRING_LITERAL -> StringVal(lit.stringLiteral)
        LiteralKind.INTEGER -> {
          val v = BigInteger.valueOf(lit.integer)
          when (type.kindCase) {
            TypeKind.BIT -> BitVal(BitVector(v, type.bit.width))
            TypeKind.SIGNED_INT -> IntVal(SignedBitVector.fromUnsignedBits(v, type.signedInt.width))
            // Compile-time constant integer without explicit width (P4 spec §8.1).
            TypeKind.VARBIT,
            TypeKind.BOOLEAN,
            TypeKind.NAMED,
            TypeKind.HEADER_STACK,
            TypeKind.ERROR,
            TypeKind.KIND_NOT_SET,
            null -> InfIntVal(v)
          }
        }
        LiteralKind.BIG_INTEGER -> {
          val v = BigInteger(1, lit.bigInteger.toByteArray())
          when (type.kindCase) {
            TypeKind.SIGNED_INT -> IntVal(SignedBitVector.fromUnsignedBits(v, type.signedInt.width))
            TypeKind.BIT -> BitVal(BitVector(v, type.bit.width))
            TypeKind.VARBIT,
            TypeKind.BOOLEAN,
            TypeKind.NAMED,
            TypeKind.HEADER_STACK,
            TypeKind.ERROR,
            TypeKind.KIND_NOT_SET,
            null -> error("big integer literal with unexpected type: $type${sourceContext()}")
          }
        }
        LiteralKind.KIND_NOT_SET,
        null -> error("unhandled literal kind: $lit${sourceContext()}")
      }

    private fun evalFieldAccess(fa: fourward.FieldAccess, env: Environment): Value {
      // Special case: table.apply().hit / .miss — the p4c midend may restructure
      // the apply call such that the backend emits FieldAccess{TableApplyExpr, "hit"}
      // rather than TableApplyExpr{access_kind=HIT}.
      if (fa.expr.hasTableApply()) {
        val result = applyTable(fa.expr.tableApply.tableName, env)
        return when (fa.fieldName) {
          "hit" -> BoolVal(result.hit)
          "miss" -> BoolVal(!result.hit)
          else -> error("unknown field '${fa.fieldName}' on table apply result${sourceContext()}")
        }
      }
      val target = evalExpr(fa.expr, env)
      return when (target) {
        is HeaderVal ->
          target.fields[fa.fieldName]
            ?: error(
              "field ${fa.fieldName} not found in header ${target.typeName}${sourceContext()}"
            )
        is StructVal ->
          target.fields[fa.fieldName]
            ?: error(
              "field ${fa.fieldName} not found in struct ${target.typeName}${sourceContext()}"
            )
        is HeaderStackVal -> evalHeaderStackProperty(target, fa.fieldName)
        else -> error("field access on non-aggregate value: $target${sourceContext()}")
      }
    }

    /** P4 spec §8.18: header stack built-in properties. */
    private fun evalHeaderStackProperty(stack: HeaderStackVal, name: String): Value =
      when (name) {
        // P4 spec §8.18: accessing .next when the stack is full is an error that
        // transitions the parser to the reject state with error.StackOutOfBounds.
        "next" -> {
          if (stack.nextIndex >= stack.headers.size) {
            throw ParserErrorException(
              "StackOutOfBounds",
              "header stack overflow: nextIndex=${stack.nextIndex}, size=${stack.headers.size}",
            )
          }
          stack.headers[stack.nextIndex].also { stack.nextIndex++ }
        }
        "last" -> stack.headers[(stack.nextIndex - 1).coerceAtLeast(0)]
        "lastIndex" -> BitVal(stack.headers.size.toLong() - 1, STACK_PROPERTY_BITS)
        "size" -> BitVal(stack.headers.size.toLong(), STACK_PROPERTY_BITS)
        else -> error("unknown header stack property: $name${sourceContext()}")
      }

    // P4 spec §8.18: out-of-bounds reads return an invalid header with default values.
    private fun evalArrayIndex(ai: fourward.ArrayIndex, env: Environment): Value {
      val stack =
        evalExpr(ai.expr, env) as? HeaderStackVal
          ?: error("array index on non-stack value${sourceContext()}")
      val index = intValue(evalExpr(ai.index, env))
      if (index !in 0 until stack.size) return defaultValue(stack.elementTypeName, types)
      return stack.headers[index]
    }

    private fun evalSlice(slice: fourward.Slice, env: Environment): Value {
      val bits = (evalExpr(slice.expr, env) as BitVal).bits
      return BitVal(bits.slice(slice.hi, slice.lo))
    }

    private fun evalConcat(concat: fourward.Concat, env: Environment): Value {
      val left = (evalExpr(concat.left, env) as BitVal).bits
      val right = (evalExpr(concat.right, env) as BitVal).bits
      return BitVal(left.concat(right))
    }

    private fun evalCast(cast: fourward.Cast, env: Environment): Value {
      val inner = evalExpr(cast.expr, env)
      return when (cast.targetType.kindCase) {
        TypeKind.BIT -> {
          val targetWidth = cast.targetType.bit.width
          val sourceBits =
            when (inner) {
              is BitVal -> inner.bits.value
              is InfIntVal -> inner.value
              is IntVal -> inner.bits.toUnsigned().value
              is BoolVal -> if (inner.value) BigInteger.ONE else BigInteger.ZERO
              else -> error("cannot cast $inner to bit<$targetWidth>${sourceContext()}")
            }
          BitVal(BitVector(sourceBits.mod(BigInteger.TWO.pow(targetWidth)), targetWidth))
        }
        TypeKind.SIGNED_INT -> {
          val targetWidth = cast.targetType.signedInt.width
          when (inner) {
            // int<N> → int<M>: preserve the signed value (sign-extends or truncates).
            // int<N> → int<M>: sign-extend if widening, truncate if narrowing.
            is IntVal ->
              if (targetWidth >= inner.bits.width) {
                IntVal(SignedBitVector(inner.bits.value, targetWidth))
              } else {
                IntVal(
                  SignedBitVector.fromUnsignedBits(
                    inner.bits.value.mod(java.math.BigInteger.TWO.pow(targetWidth)),
                    targetWidth,
                  )
                )
              }
            else -> {
              val sourceBits =
                when (inner) {
                  is BitVal -> inner.bits.value
                  is InfIntVal -> inner.value
                  else -> error("cannot cast $inner to int<$targetWidth>${sourceContext()}")
                }
              IntVal(SignedBitVector.fromUnsignedBits(sourceBits, targetWidth))
            }
          }
        }
        TypeKind.BOOLEAN -> {
          val v =
            when (inner) {
              is BitVal -> inner.bits.value
              is InfIntVal -> inner.value
              else -> error("cannot cast $inner to bool${sourceContext()}")
            }
          BoolVal(v != BigInteger.ZERO)
        }
        TypeKind.VARBIT,
        TypeKind.NAMED,
        TypeKind.HEADER_STACK,
        TypeKind.ERROR,
        TypeKind.KIND_NOT_SET,
        null -> error("unsupported cast target type: ${cast.targetType}${sourceContext()}")
      }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun evalBinaryOp(op: fourward.BinaryOp, env: Environment): Value {
      // P4 spec §8.1: compile-time integers adopt the width of the other operand.
      val (left, right) = coerceInfInts(evalExpr(op.left, env), evalExpr(op.right, env))

      // P4 spec §8.5: shift amount is always unsigned, so left may be IntVal while right is BitVal.
      // SHR on int<N> is arithmetic (sign-extending); SHL is logical.
      if (left is IntVal && (op.op == BinaryOperator.SHL || op.op == BinaryOperator.SHR)) {
        val amount = intValue(right)
        return if (op.op == BinaryOperator.SHL) {
          IntVal(left.bits.toUnsigned().shl(amount).toSigned())
        } else {
          // Arithmetic right shift: shift the signed value, then re-wrap.
          val shifted = left.bits.value.shiftRight(amount)
          IntVal(SignedBitVector(shifted, left.bits.width))
        }
      }

      return when (op.op) {
        // Arithmetic and bitwise ops work on unsigned bits; liftBitwise rewraps as
        // the original signedness (BitVal or IntVal).
        BinaryOperator.ADD -> liftBitwise(left, right) { a, b -> a + b }
        BinaryOperator.SUB -> liftBitwise(left, right) { a, b -> a - b }
        BinaryOperator.MUL -> liftBitwise(left, right) { a, b -> a * b }
        BinaryOperator.BIT_AND -> liftBitwise(left, right) { a, b -> a and b }
        BinaryOperator.BIT_OR -> liftBitwise(left, right) { a, b -> a or b }
        BinaryOperator.BIT_XOR -> liftBitwise(left, right) { a, b -> a xor b }
        // bit<N>-only: division/modulo are unsigned-only in P4.
        BinaryOperator.DIV -> BitVal((left as BitVal).bits / (right as BitVal).bits)
        BinaryOperator.MOD -> BitVal((left as BitVal).bits % (right as BitVal).bits)
        // Saturating ops can't use liftBitwise: signed/unsigned have different clamp bounds.
        BinaryOperator.ADD_SAT ->
          when (left) {
            is BitVal -> BitVal(left.bits.addSat((right as BitVal).bits))
            is IntVal -> IntVal(left.bits.addSat((right as IntVal).bits))
            else -> error("ADD_SAT on non-fixed-width: $left${sourceContext()}")
          }
        BinaryOperator.SUB_SAT ->
          when (left) {
            is BitVal -> BitVal(left.bits.subSat((right as BitVal).bits))
            is IntVal -> IntVal(left.bits.subSat((right as IntVal).bits))
            else -> error("SUB_SAT on non-fixed-width: $left${sourceContext()}")
          }
        BinaryOperator.SHL -> BitVal((left as BitVal).bits.shl(intValue(right)))
        BinaryOperator.SHR -> BitVal((left as BitVal).bits.shr(intValue(right)))
        // Equality uses data-class structural equality (works for both bit<N> and int<N>).
        BinaryOperator.EQ -> BoolVal(left == right)
        BinaryOperator.NEQ -> BoolVal(left != right)
        // P4 spec §8.5: relational operators use signed comparison for int<N>.
        BinaryOperator.LT -> liftCompare(left, right) { it < 0 }
        BinaryOperator.GT -> liftCompare(left, right) { it > 0 }
        BinaryOperator.LE -> liftCompare(left, right) { it <= 0 }
        BinaryOperator.GE -> liftCompare(left, right) { it >= 0 }
        BinaryOperator.AND -> BoolVal((left as BoolVal).value && (right as BoolVal).value)
        BinaryOperator.OR -> BoolVal((left as BoolVal).value || (right as BoolVal).value)
        else -> error("unhandled binary operator: ${op.op}${sourceContext()}")
      }
    }

    /** Apply a [BitVector] operation to two fixed-width values, preserving signedness. */
    private inline fun liftBitwise(
      left: Value,
      right: Value,
      f: (BitVector, BitVector) -> BitVector,
    ): Value =
      when (left) {
        is BitVal -> BitVal(f(left.bits, (right as BitVal).bits))
        is IntVal ->
          IntVal(f(left.bits.toUnsigned(), (right as IntVal).bits.toUnsigned()).toSigned())
        else -> error("expected fixed-width integer operands, got: $left, $right${sourceContext()}")
      }

    /** Compare two fixed-width values, dispatching to signed or unsigned comparison. */
    private inline fun liftCompare(left: Value, right: Value, pred: (Int) -> Boolean): BoolVal =
      when (left) {
        is BitVal -> BoolVal(pred(left.bits.compareTo((right as BitVal).bits)))
        is IntVal -> BoolVal(pred(left.bits.value.compareTo((right as IntVal).bits.value)))
        else -> error("expected fixed-width integer operands for comparison${sourceContext()}")
      }

    /** Extract a small integer from a [BitVal], [IntVal], or [InfIntVal]. */
    private fun intValue(v: Value): Int =
      when (v) {
        is BitVal -> v.bits.value.toInt()
        is IntVal -> v.bits.value.toInt()
        is InfIntVal -> v.value.toInt()
        else -> error("expected integer value: $v${sourceContext()}")
      }

    /** P4 spec §8.1: InfInt adopts the width of the fixed-width operand. */
    private fun coerceInfInts(left: Value, right: Value): Pair<Value, Value> =
      when {
        left is InfIntVal && right is BitVal -> left.toBitVal(right.bits.width) to right
        right is InfIntVal && left is BitVal -> left to right.toBitVal(left.bits.width)
        left is InfIntVal && right is IntVal -> left.toIntVal(right.bits.width) to right
        right is InfIntVal && left is IntVal -> left to right.toIntVal(left.bits.width)
        else -> left to right
      }

    private fun evalUnaryOp(op: fourward.UnaryOp, env: Environment): Value {
      val inner = evalExpr(op.expr, env)
      return when (op.op) {
        // Two's-complement negation: (2^N - x) mod 2^N = (0 - x) using wrapping subtraction.
        // BitVector.ofInt(-1, width) would violate the non-negative value invariant.
        UnaryOperator.NEG ->
          when (inner) {
            is InfIntVal -> InfIntVal(inner.value.negate())
            is BitVal -> BitVal(BitVector.ofInt(0, inner.bits.width) - inner.bits)
            else -> error("NEG on non-numeric: $inner${sourceContext()}")
          }
        UnaryOperator.BIT_NOT -> BitVal((inner as BitVal).bits.inv())
        UnaryOperator.NOT -> BoolVal(!(inner as BoolVal).value)
        else -> error("unhandled unary operator: ${op.op}${sourceContext()}")
      }
    }

    private fun evalMux(mux: fourward.MuxExpr, env: Environment): Value =
      if ((evalExpr(mux.condition, env) as BoolVal).value) evalExpr(mux.thenExpr, env)
      else evalExpr(mux.elseExpr, env)

    private fun evalStructExpr(
      se: fourward.StructExpr,
      type: fourward.Type,
      env: Environment,
    ): Value {
      require(type.hasNamed()) { "StructExpr must have a named type, got: $type" }
      val typeName = type.named
      val fields =
        se.fieldsList.associateTo(mutableMapOf()) { f -> f.name to evalExpr(f.value, env) }
      return StructVal(typeName, fields)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun evalMethodCall(call: MethodCall, returnType: Type, env: Environment): Value {
      return when (call.method) {
        // Header validity methods: target is the header instance.
        "isValid" -> {
          when (val target = evalExpr(call.target, env)) {
            is HeaderVal -> BoolVal(target.valid)
            is StructVal -> BoolVal(target.isUnionValid())
            else -> error("isValid on non-header: $target${sourceContext()}")
          }
        }
        "setValid" -> {
          val target = evalExpr(call.target, env) as HeaderVal
          invalidateUnionSiblings(call.target, target, env)
          target.valid = true
          UnitVal
        }
        "setInvalid" -> {
          when (val target = evalExpr(call.target, env)) {
            is HeaderVal -> target.setInvalid()
            is StructVal -> target.invalidateUnion()
            else -> error("setInvalid on non-header: $target${sourceContext()}")
          }
          UnitVal
        }
        // P4 spec §8.18: header stack push_front/pop_front.
        "push_front" -> {
          val stack = evalExpr(call.target, env) as HeaderStackVal
          val count = intValue(evalExpr(call.argsList[0], env)).coerceAtMost(stack.size)
          // Shift elements toward higher indices; first `count` become invalid.
          for (i in (stack.size - 1) downTo count) {
            stack.headers[i] = stack.headers[i - count]
          }
          for (i in 0 until count) {
            stack.headers[i] = defaultValue(stack.elementTypeName, types)
          }
          stack.nextIndex = (stack.nextIndex + count).coerceAtMost(stack.size)
          UnitVal
        }
        "pop_front" -> {
          val stack = evalExpr(call.target, env) as HeaderStackVal
          val count = intValue(evalExpr(call.argsList[0], env)).coerceAtMost(stack.size)
          // Shift elements toward lower indices; last `count` become invalid.
          for (i in 0 until stack.size - count) {
            stack.headers[i] = stack.headers[i + count]
          }
          for (i in stack.size - count until stack.size) {
            stack.headers[i] = defaultValue(stack.elementTypeName, types)
          }
          stack.nextIndex = (stack.nextIndex - count).coerceAtLeast(0)
          UnitVal
        }
        // packet_in.extract(hdr) / packet_out.emit(hdr): target is the extern object
        // (not in env); the header is the first argument.
        "extract" -> execExtract(call, env)
        "lookahead" -> execLookahead(returnType)
        "advance" -> {
          val bits = intValue(evalExpr(call.argsList[0], env))
          packet.advanceBits(bits)
          UnitVal
        }
        "emit" -> execEmit(call, env)
        // "__call__" is used for free functions and direct action calls. Check actions first;
        // fall back to extern handling (mark_to_drop, etc.) for unrecognised names.
        "__call__" -> {
          val funcName = call.target.nameRef.name
          if (funcName in actions) execInlineActionCall(funcName, call.argsList, env)
          else execExternCall(call, env)
        }
        // Extern object methods (register.read/write, counter.count, meter.execute_meter, etc.)
        // are architecture-specific — delegate to the handler.
        else -> {
          val handler = externHandler
          if (handler != null && call.target.hasNameRef()) {
            val externCall =
              ExternCall.Method(call.target.type.named, call.target.nameRef.name, call.method)
            handler.handle(externCall, createExternEvaluator(call, env, returnType))
          } else {
            error("unhandled method call: ${call.method} on ${call.target}${sourceContext()}")
          }
        }
      }
    }

    // -------------------------------------------------------------------------
    // Table application
    // -------------------------------------------------------------------------

    private fun applyTable(tableName: String, env: Environment): TableResult {
      val tableBehavior = tables[tableName] ?: error("unknown table: $tableName")
      val keyValues = tableBehavior.keysList.map { key -> key.fieldName to evalExpr(key.expr, env) }
      val result = tableStore.lookup(tableName, keyValues)

      // Record table miss context so PNA's add_entry extern knows which table to insert into.
      if (!result.hit) lastTableMissCtx = TableMissContext(tableName, keyValues)

      // P4Runtime spec §9.3: direct counters are incremented on every table hit.
      // Skip on action selector re-executions: each branch explores a possible path, not a
      // definite one. Clone/multicast branches ARE real copies and should be counted.
      if (result.hit && result.entry != null && packetCtx != null) {
        if (selectorOverrides.isEmpty()) {
          tableStore.directCounterIncrement(tableName, result.entry, packetCtx.payloadSize)
        }
      }

      packetCtx?.addTraceEvent(
        traceEventBuilder()
          .setTableLookup(
            tableLookupPool
              .clear()
              .setTableName(tableStore.tableDisplayName(tableName))
              .setHit(result.hit)
              .setActionName(tableStore.actionDisplayName(result.actionName))
              .also { if (result.entry != null) it.setMatchedEntry(result.entry) }
          )
          .also { builder ->
            for ((fieldName, value) in keyValues) {
              builder.putVariableValues(fieldName, formatValue(value))
            }
          }
          .build()
      )

      if (result.members != null) {
        val forced = selectorOverrides[tableName]
        if (forced != null) {
          // Re-execution with a forced member (replay path for resubmit/recirculate).
          val member =
            result.members.find { it.memberId == forced }
              ?: error("forced member $forced not found in table $tableName")
          val resolvedMember =
            tableBehavior.actionOverridesMap[member.actionName] ?: member.actionName
          execAction(resolvedMember, member.params, env)
          return TableResult(result.hit, member.actionName)
        }
        // First encounter: capture state and throw to let the architecture fork.
        throw ActionSelectorFork(
          tableName,
          result.members,
          packetCtx!!.getEvents(),
          env.deepCopy(),
          packetCtx!!.bytesConsumed,
          currentSourceInfo,
        )
      }

      // Resolve per-table action specialization: the p4info uses original names,
      // but the midend may have created per-table copies with distinct bodies.
      val resolvedName = tableBehavior.actionOverridesMap[result.actionName] ?: result.actionName
      val params = result.entry?.action?.action?.paramsList ?: result.actionParams
      execAction(resolvedName, params, env)
      return TableResult(result.hit, result.actionName)
    }

    private fun execAction(
      actionName: String,
      paramProtos: List<p4.v1.P4RuntimeOuterClass.Action.Param>,
      env: Environment,
    ) {
      // NoAction is a P4 built-in no-op (P4 spec §12.7) that is implicitly available in
      // every table. It may appear in const entries without being listed in the actions block,
      // so it might not have been compiled into the IR. Handle it directly rather than
      // requiring the backend to emit an explicit empty ActionDecl for it.
      if (actionName == "NoAction") {
        packetCtx?.addTraceEvent(
          traceEventBuilder()
            .setActionExecution(actionExecPool.clear().setActionName(actionName))
            .build()
        )
        return
      }

      val actionDecl = actions[actionName] ?: error("unknown action: $actionName")

      val paramMap = mutableMapOf<String, com.google.protobuf.ByteString>()

      env.pushScope()
      try {
        actionDecl.paramsList.forEachIndexed { i, paramDecl ->
          val paramProto = paramProtos.getOrNull(i)
          val value: Value =
            if (paramProto != null) {
              val width = paramDecl.type.bit.width
              paramMap[paramDecl.name] = paramProto.value
              BitVal(BitVector.ofBytes(paramProto.value.toByteArray(), width))
            } else {
              UnitVal
            }
          env.define(paramDecl.name, value)
        }

        // Use the p4info alias (short source-level name) for trace display. The behavioral
        // IR name may be mangled by the midend (e.g. "acl_pre_ingress_ctrl_set_vrf").
        val displayName = tableStore.actionDisplayName(actionName)
        packetCtx?.addTraceEvent(
          traceEventBuilder()
            .setActionExecution(
              actionExecPool
                .clear()
                .setActionName(displayName)
                .putAllParams(paramMap.mapValues { it.value })
            )
            .build()
        )

        execBlock(actionDecl.bodyList, env)
      } finally {
        env.popScope()
      }
    }

    /**
     * Executes a direct (non-table-mediated) action call such as `do_thing(h.h.b)`.
     *
     * Binds argument values to the action's parameter names, runs the body, then writes back any
     * `inout`/`out` parameters to the corresponding call-site lvalues (call-by-value-result
     * semantics, as required by the P4 spec).
     */
    private fun execInlineActionCall(
      actionName: String,
      args: List<Expr>,
      env: Environment,
    ): Value {
      val actionDecl = actions[actionName]!!
      val argValues = args.map { evalExpr(it, env) }

      env.pushScope()
      try {
        actionDecl.paramsList.forEachIndexed { i, param ->
          env.define(param.name, argValues.getOrElse(i) { UnitVal })
        }
        try {
          execBlock(actionDecl.bodyList, env)
        } catch (_: ReturnException) {
          // P4 action return exits the body without a value; fall through to writeback.
        }
        // Write back inout/out parameters before the scope is popped.
        actionDecl.paramsList.forEachIndexed { i, param ->
          if (
            (param.direction == Direction.INOUT || param.direction == Direction.OUT) &&
              i < args.size
          ) {
            setLValue(args[i], env.lookup(param.name)!!, env)
          }
        }
      } finally {
        env.popScope()
      }
      return UnitVal
    }

    // -------------------------------------------------------------------------
    // Extern dispatch
    // -------------------------------------------------------------------------

    /**
     * Creates an [ExternEvaluator] bound to [call]'s arguments and the current interpreter state.
     */
    private fun createExternEvaluator(
      call: MethodCall,
      env: Environment,
      returnType: Type = Type.getDefaultInstance(),
    ): ExternEvaluator =
      object : ExternEvaluator {
        override fun returnType(): Type = returnType

        override fun argCount(): Int = call.argsList.size

        override fun evalArg(index: Int): Value = evalExpr(call.argsList[index], env)

        override fun argType(index: Int): Type = call.argsList[index].type

        override fun writeOutArg(index: Int, value: Value) =
          setLValue(call.argsList[index], value, env)

        override fun defaultValue(type: Type): Value = defaultValue(type, types)

        override fun traceEventBuilder(): TraceEvent.Builder = this@Execution.traceEventBuilder()

        override fun addTraceEvent(event: TraceEvent) {
          packetCtx?.addTraceEvent(event)
        }

        override fun peekRemainingInput(): ByteArray = packet.peekRemainingInput()

        override fun lastTableMiss(): TableMissContext? = lastTableMissCtx
      }

    private fun execExternCall(call: MethodCall, env: Environment): Value {
      val funcName = call.target.nameRef.name

      // verify() is a P4 core language construct (spec §12.8), not an architecture extern.
      if (funcName == "verify") {
        val condition = (evalExpr(call.argsList[0], env) as BoolVal).value
        if (!condition) {
          val err = (evalExpr(call.argsList[1], env) as ErrorVal).member
          throw ParserErrorException(err, "verify failed: $err")
        }
        return UnitVal
      }

      // assert(condition) / assume(condition): P4 spec §12.9.
      // At runtime, assume behaves identically to assert — the distinction is for
      // static analysis tools. On failure, emit a trace event and abort processing.
      if (funcName == "assert" || funcName == "assume") {
        val assertExpr = call.argsList[0]
        val condition = (evalExpr(assertExpr, env) as BoolVal).value
        packetCtx?.addTraceEvent(
          traceEventBuilder()
            .setAssertion(AssertionEvent.newBuilder().setPassed(condition))
            .putAllVariableValues(collectVariableValues(assertExpr, env))
            .setResultValue(condition.toString())
            .build()
        )
        if (!condition) throw AssertionFailureException("$funcName failed")
        return UnitVal
      }

      // All other externs are architecture-specific — delegate to the handler.
      val handler = externHandler ?: error("no extern handler for: $funcName")
      return handler.handle(ExternCall.FreeFunction(funcName), createExternEvaluator(call, env))
    }

    /** Whether [expr] is a field access into a header union. */
    private fun isUnionFieldAccess(expr: Expr): Boolean =
      expr.hasFieldAccess() &&
        expr.fieldAccess.expr.type.let { t ->
          t.hasNamed() && types[t.named]?.hasHeaderUnion() == true
        }

    /** If [expr] is a field access into a header union, enforce one-valid-at-a-time (P4 §8.20). */
    private fun invalidateUnionSiblings(expr: Expr, target: HeaderVal, env: Environment) {
      if (!isUnionFieldAccess(expr)) return
      val parent = evalExpr(expr.fieldAccess.expr, env) as StructVal
      parent.invalidateUnionExcept(target)
    }

    // -------------------------------------------------------------------------
    // Packet extract / emit
    // -------------------------------------------------------------------------

    private fun execExtract(call: MethodCall, env: Environment): Value {
      // packet_in.extract(hdr.field): the header to extract into is args[0], not the target.
      // P4's don't-care extract `p.extract<T>(_)` compiles to extract(arg) where
      // `arg` is an undeclared temporary. Create a throw-away header to consume bytes.
      val arg = call.argsList[0]
      // When the arg is a field access into a union (e.g. hdr.u.next.byte), resolve the
      // union parent once and invalidate siblings inline. This avoids re-evaluating
      // side-effecting expressions like `.next` on header stacks.
      var unionHandled = false
      val header =
        if (arg.hasNameRef() && env.lookup(arg.nameRef.name) == null && arg.type.hasNamed()) {
          defaultValue(arg.type.named, types) as? HeaderVal
            ?: error("type not found for don't-care extract: ${arg.type.named}")
        } else if (isUnionFieldAccess(arg)) {
          // Parent is a header union — resolve it once, invalidate siblings (P4 §8.20).
          val parent = evalExpr(arg.fieldAccess.expr, env) as StructVal
          val member = parent.fields[arg.fieldAccess.fieldName] as HeaderVal
          parent.invalidateUnionExcept(member)
          unionHandled = true
          member
        } else {
          evalExpr(arg, env) as HeaderVal
        }
      val headerDecl =
        (types[header.typeName] ?: error("type not found: ${header.typeName}")).header

      // The 2-argument form b.extract(hdr, varbitBits) is used when the header contains a varbit
      // field. The second argument gives the varbit field's runtime length in bits.
      val varbitBits: Int = if (call.argsCount > 1) intValue(evalExpr(call.argsList[1], env)) else 0

      // P4 spec §12.8.1: validate varbit extract constraints.
      if (varbitBits > 0) {
        if (varbitBits % 8 != 0) {
          throw ParserErrorException(
            "ParserInvalidArgument",
            "varbit extract length $varbitBits is not a multiple of 8",
          )
        }
        val varbitField = headerDecl.fieldsList.find { it.type.hasVarbit() }
        if (varbitField != null && varbitBits > varbitField.type.varbit.maxWidth) {
          throw ParserErrorException(
            "HeaderTooShort",
            "varbit extract length $varbitBits exceeds max width " +
              "${varbitField.type.varbit.maxWidth}",
          )
        }
      }

      val widths = headerDecl.fieldsList.map { fieldWireWidth(it.type, varbitBits) }
      val totalBits = widths.sum()
      val allBits = packet.extractBits(totalBits)
      val newFields = unpackFields(headerDecl.fieldsList, widths, allBits, totalBits)
      if (!unionHandled) invalidateUnionSiblings(call.argsList[0], header, env)
      header.setValid(newFields)
      return UnitVal
    }

    /** P4 spec §12.8.2: peek at packet bits and construct a value of type T without consuming. */
    private fun execLookahead(returnType: Type): Value {
      if (returnType.hasBit()) {
        val width = returnType.bit.width
        return BitVal(BitVector(packet.peekBits(width), width))
      }

      val typeName = returnType.named
      val typeDecl = types[typeName] ?: error("type not found for lookahead: $typeName")
      val fields =
        when (typeDecl.kindCase) {
          TypeDecl.KindCase.HEADER -> typeDecl.header.fieldsList
          TypeDecl.KindCase.STRUCT -> typeDecl.struct.fieldsList
          TypeDecl.KindCase.HEADER_UNION,
          TypeDecl.KindCase.ENUM,
          TypeDecl.KindCase.KIND_NOT_SET,
          null -> error("lookahead type must be a header or struct: $typeName")
        }
      val widths = fields.map { fieldWireWidth(it.type) }
      val totalBits = widths.sum()
      val allBits = packet.peekBits(totalBits)
      val newFields = unpackFields(fields, widths, allBits, totalBits)
      return if (typeDecl.hasHeader()) {
        HeaderVal(typeName, newFields, valid = true)
      } else {
        StructVal(typeName, newFields)
      }
    }

    /**
     * Unpacks a BigInteger of raw packet bits into named field values.
     *
     * Handles sub-byte fields (e.g. IPv4's bit<4> version and ihl) that share a byte by shift+mask
     * from a single MSB-first BigInteger. Used by both extract and lookahead.
     */
    private fun unpackFields(
      fields: List<FieldDecl>,
      widths: List<Int>,
      allBits: BigInteger,
      totalBits: Int,
    ): MutableMap<String, Value> {
      val result = mutableMapOf<String, Value>()
      var bitOffset = 0
      for ((field, width) in fields.zip(widths)) {
        if (width == 0) {
          // Zero-width varbit fields still need a map entry so checksum StructExprs
          // can reference them (e.g. hdr.ipv4.options when IHL=5).
          if (field.type.hasVarbit()) result[field.name] = BitVal(BitVector(BigInteger.ZERO, 0))
          continue
        }
        val mask = BigInteger.ONE.shiftLeft(width) - BigInteger.ONE
        val raw = (allBits shr (totalBits - bitOffset - width)) and mask
        result[field.name] = bitsToValue(field.type, raw, width)
        bitOffset += width
      }
      return result
    }

    /**
     * Returns the on-wire bit-width of a header field type.
     *
     * P4 spec §8.9.2: bool in a header occupies exactly 1 bit on the wire. int<N> and bit<N> occupy
     * N bits. varbit<N> occupies [varbitBits] bits (caller must supply the runtime value).
     * Serializable enums are looked up by name and use their declared underlying width.
     */
    private fun fieldWireWidth(type: Type, varbitBits: Int = 0): Int =
      when (type.kindCase) {
        TypeKind.BIT -> type.bit.width
        TypeKind.SIGNED_INT -> type.signedInt.width
        TypeKind.BOOLEAN -> 1
        TypeKind.VARBIT -> varbitBits
        TypeKind.NAMED -> {
          val decl = types[type.named]
          when (decl?.kindCase) {
            TypeDecl.KindCase.ENUM -> decl.enum.width
            TypeDecl.KindCase.HEADER,
            TypeDecl.KindCase.STRUCT,
            TypeDecl.KindCase.HEADER_UNION,
            TypeDecl.KindCase.KIND_NOT_SET,
            null -> 0
          }
        }
        TypeKind.HEADER_STACK,
        TypeKind.ERROR,
        TypeKind.KIND_NOT_SET,
        null -> 0
      }

    /**
     * Converts raw extracted bits to the appropriate [Value] for a header field.
     *
     * bit<N> → [BitVal], bool → [BoolVal], int<N> → [IntVal], varbit / enum → [BitVal].
     */
    private fun bitsToValue(type: Type, raw: BigInteger, width: Int): Value =
      when (type.kindCase) {
        TypeKind.BOOLEAN -> BoolVal(raw != BigInteger.ZERO)
        TypeKind.SIGNED_INT -> IntVal(SignedBitVector.fromUnsignedBits(raw, width))
        TypeKind.BIT,
        TypeKind.VARBIT,
        TypeKind.NAMED,
        TypeKind.HEADER_STACK,
        TypeKind.ERROR,
        TypeKind.KIND_NOT_SET,
        null -> BitVal(BitVector(raw, width))
      }

    private fun execEmit(call: MethodCall, env: Environment): Value {
      emitValue(evalExpr(call.argsList[0], env))
      return UnitVal
    }

    /**
     * Recursively emits a value to the packet output buffer.
     * - [HeaderVal]: packs all fields into bytes (MSB-first) and appends if valid.
     * - [StructVal]: iterates fields in declaration order (looking up the TypeDecl) and emits each.
     * - Other types: no-op (non-emittable values such as BoolVal in metadata structs).
     *
     * This handles both `pkt.emit(hdr.ethernet)` (single header) and `pkt.emit(hdr)` where `hdr` is
     * a struct containing multiple headers, as required by the P4 deparser model.
     */
    private fun emitValue(value: Value) {
      when (value) {
        is HeaderVal -> emitHeader(value)
        is StructVal -> {
          // Emit in the declaration order from the TypeDecl; fall back to map order if unknown.
          val typeDecl = types[value.typeName]
          val fieldDecls =
            when (typeDecl?.kindCase) {
              TypeDecl.KindCase.STRUCT -> typeDecl.struct.fieldsList
              TypeDecl.KindCase.HEADER_UNION -> typeDecl.headerUnion.fieldsList
              TypeDecl.KindCase.HEADER,
              TypeDecl.KindCase.ENUM,
              TypeDecl.KindCase.KIND_NOT_SET,
              null -> null
            }
          if (fieldDecls != null) {
            for (field in fieldDecls) {
              emitValue(value.fields[field.name] ?: continue)
            }
          } else {
            for (fieldVal in value.fields.values) emitValue(fieldVal)
          }
        }
        is HeaderStackVal -> {
          for (header in value.headers) emitValue(header)
        }
        else -> {} // BoolVal, BitVal outside a header, UnitVal — not emittable
      }
    }

    /** Packs a valid header's fields into bytes (MSB-first) and appends to the output buffer. */
    private fun emitHeader(header: HeaderVal) {
      if (!header.valid) return
      val headerDecl =
        (types[header.typeName] ?: error("type not found: ${header.typeName}")).header
      // Compute total wire bits from field declarations; varbit fields use their stored BitVal
      // width since we don't have the runtime length separately at emit time.
      val totalBits =
        headerDecl.fieldsList.sumOf { field ->
          when (val v = header.fields[field.name]) {
            is BitVal -> v.bits.width
            is BoolVal -> 1
            is IntVal -> v.bits.width
            else -> 0
          }
        }
      var packedBits = BigInteger.ZERO
      var bitOffset = 0
      for (field in headerDecl.fieldsList) {
        val fieldBits: BigInteger
        val width: Int
        when (val fieldVal = header.fields[field.name]) {
          is BitVal -> {
            fieldBits = fieldVal.bits.value
            width = fieldVal.bits.width
          }
          is BoolVal -> {
            // P4 spec §8.9.2: bool occupies 1 bit on the wire.
            fieldBits = if (fieldVal.value) BigInteger.ONE else BigInteger.ZERO
            width = 1
          }
          is IntVal -> {
            fieldBits = fieldVal.bits.toUnsigned().value
            width = fieldVal.bits.width
          }
          else -> continue // UnitVal (e.g. varbit placeholder) — skip
        }
        if (totalBits > 0 && width > 0) {
          val shift = totalBits - bitOffset - width
          packedBits = packedBits or fieldBits.shiftLeft(shift)
        }
        bitOffset += width
      }
      if (totalBits > 0) {
        packet.emitBits(packedBits, totalBits)
        packetCtx?.addTraceEvent(
          TraceEvent.newBuilder()
            .setDeparserEmit(
              DeparserEmitEvent.newBuilder()
                .setHeaderType(header.typeName)
                .setByteLength((totalBits + 7) / 8)
            )
            .build()
        )
      }
    }

    // -------------------------------------------------------------------------
    // LValue assignment
    // -------------------------------------------------------------------------

    private fun setLValue(lhs: Expr, value: Value, env: Environment) {
      // P4 assignment is copy-by-value. Headers and structs carry mutable fields maps,
      // so we copy them to prevent aliasing (e.g. `x = h.h; x.a = 2` must not modify `h.h.a`).
      val copy =
        when (value) {
          is HeaderVal -> value.copy()
          is StructVal -> value.copy()
          else -> value
        }
      when (lhs.kindCase) {
        ExprKind.NAME_REF -> {
          env.update(lhs.nameRef.name, copy)
        }
        ExprKind.FIELD_ACCESS -> {
          val target = evalExpr(lhs.fieldAccess.expr, env)
          when (target) {
            is HeaderVal -> target.fields[lhs.fieldAccess.fieldName] = copy
            is StructVal -> target.fields[lhs.fieldAccess.fieldName] = copy
            else -> error("field assignment on non-aggregate: $target${sourceContext()}")
          }
        }
        // P4 spec §8.18: out-of-bounds writes are no-ops.
        ExprKind.ARRAY_INDEX -> {
          val stack = evalExpr(lhs.arrayIndex.expr, env) as HeaderStackVal
          val index = intValue(evalExpr(lhs.arrayIndex.index, env))
          if (index in 0 until stack.size) stack.headers[index] = copy
        }
        ExprKind.SLICE -> {
          // Slice assignment: update [hi:lo] bits of the target.
          val target = evalExpr(lhs.slice.expr, env) as BitVal
          val src = value as BitVal
          val hi = lhs.slice.hi
          val lo = lhs.slice.lo
          val mask = BitVector(BitVector.maskFor(hi - lo + 1).shiftLeft(lo), target.bits.width)
          val shifted = BitVector(src.bits.value.shiftLeft(lo), target.bits.width)
          val result = (target.bits and mask.inv()) or (shifted and mask)
          setLValue(lhs.slice.expr, BitVal(result), env)
        }
        ExprKind.LITERAL,
        ExprKind.CONCAT,
        ExprKind.CAST,
        ExprKind.BINARY_OP,
        ExprKind.UNARY_OP,
        ExprKind.METHOD_CALL,
        ExprKind.TABLE_APPLY,
        ExprKind.MUX,
        ExprKind.STRUCT_EXPR,
        ExprKind.KIND_NOT_SET,
        null -> error("unhandled lvalue kind: $lhs${sourceContext()}")
      }
    }
  } // end Execution

  companion object {
    // P4 spec §8.18: header stack lastIndex/size are bit<32>.
    private const val STACK_PROPERTY_BITS = 32
    private val BOOL_TRUE_BITS = BitVector.ofInt(1, 1)
    private val BOOL_FALSE_BITS = BitVector.ofInt(0, 1)

    @Suppress("MagicNumber") private val maxDecimalOnly = java.math.BigInteger.valueOf(9)
  }
}

/** Creates a one-shot [Interpreter.Execution] — convenience for tests. */
fun interpreterExecution(
  config: BehavioralConfig,
  tableStore: TableStore,
  packetCtx: PacketContext? = null,
  selectorOverrides: Map<String, Int> = emptyMap(),
  externHandler: ExternHandler? = null,
): Interpreter.Execution =
  Interpreter(config).execution(tableStore, packetCtx, selectorOverrides, externHandler)

/**
 * Thrown by an `exit` statement; unwinds the call stack to the top of the current pipeline stage.
 */
class ExitException : Exception()

/** Thrown when assert() or assume() fails. The architecture catches this and drops the packet. */
class AssertionFailureException(message: String) : Exception(message)

/** Thrown by a `return` statement inside an action or function. */
class ReturnException(val value: Value) : Exception()

/**
 * Thrown at non-deterministic choice points to signal the architecture to fork the trace tree. The
 * architecture catches this and continues each branch from the fork point (fork-point resume)
 * instead of replaying the pipeline.
 */
sealed class ForkException(val eventsBeforeFork: List<TraceEvent>) : Exception()

/**
 * Fork at an action selector group hit — one branch per group member.
 *
 * Carries the full interpreter state at the fork point so each branch can continue from there
 * (fork-point resume) instead of replaying the entire pipeline.
 */
class ActionSelectorFork(
  val tableName: String,
  val members: List<TableStore.MemberAction>,
  eventsBeforeFork: List<TraceEvent>,
  /** Deep copy of the environment at the fork point — each branch restores from this. */
  val forkPointEnv: Environment,
  /** Parser buffer position at the fork point. */
  val bytesConsumed: Int,
  /** Source info at the fork point (the table apply statement), for trace events in branches. */
  val sourceInfo: fourward.SourceInfo? = null,
  /**
   * Continuation: remaining statements at each block nesting level (innermost first). Populated as
   * the exception propagates through [Interpreter.Execution.execBlock].
   */
  val remainingStmts: ArrayDeque<List<fourward.Stmt>> = ArrayDeque(),
) : ForkException(eventsBeforeFork)
