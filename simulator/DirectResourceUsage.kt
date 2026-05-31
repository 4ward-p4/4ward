package fourward.simulator

import fourward.ActionDecl
import fourward.BehavioralConfig
import fourward.Expr
import fourward.Expr.KindCase as ExprKind
import fourward.Stmt
import fourward.Stmt.KindCase as StmtKind
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass
import p4.v1.P4RuntimeOuterClass.TableEntry

internal data class DirectResourceActions(
  val counterActionsByTable: Map<String, Set<String>>,
  val meterActionsByTable: Map<String, Set<String>>,
)

internal data class DirectResourceActionValidationContext(
  val actionNameById: Map<Int, String>,
  val tableActionOverrides: Map<String, Map<String, String>>,
  val tableActionProfile: Map<String, Int>,
  val writeState: TableStore.ForwardingSnapshot,
  val counterActionsByTable: Map<String, Set<String>>,
  val meterActionsByTable: Map<String, Set<String>>,
)

internal fun validateInlineDirectResourceActions(
  entry: TableEntry,
  tableName: String,
  context: DirectResourceActionValidationContext,
): WriteResult? {
  if (!entry.hasCounterData() && !entry.hasMeterConfig()) return null
  val actions = selectedActionNames(entry.action, tableName, context)
  if (actions.isEmpty()) return null

  fun checkResource(
    hasData: Boolean,
    actionsByTable: Map<String, Set<String>>,
    dataField: String,
    resourceKind: String,
  ): WriteResult? {
    if (!hasData) return null
    actionsByTable[tableName]?.let { allowedActions ->
      actions
        .firstOrNull { it !in allowedActions }
        ?.let { action ->
          return WriteResult.InvalidArgument(
            "TableEntry contained $dataField, but action '$action' for table '$tableName' " +
              "does not execute the table's direct $resourceKind"
          )
        }
    }
    return null
  }

  return checkResource(
    entry.hasCounterData(),
    context.counterActionsByTable,
    "counter_data",
    "counter",
  ) ?: checkResource(entry.hasMeterConfig(), context.meterActionsByTable, "meter_config", "meter")
}

private fun selectedActionNames(
  tableAction: P4RuntimeOuterClass.TableAction,
  tableName: String,
  context: DirectResourceActionValidationContext,
): List<String> {
  fun resolve(actionId: Int): String? =
    context.actionNameById[actionId]?.let { name ->
      context.tableActionOverrides[tableName]?.get(name) ?: name
    }

  val profileId = context.tableActionProfile[tableName]
  return when (tableAction.typeCase) {
    P4RuntimeOuterClass.TableAction.TypeCase.ACTION ->
      listOfNotNull(resolve(tableAction.action.actionId))
    P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_MEMBER_ID ->
      profileId
        ?.let { context.writeState.profileMembers[it]?.get(tableAction.actionProfileMemberId) }
        ?.let { listOfNotNull(resolve(it.action.actionId)) } ?: emptyList()
    P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_GROUP_ID -> {
      val members =
        profileId
          ?.let { context.writeState.profileGroups[it]?.get(tableAction.actionProfileGroupId) }
          ?.membersList ?: return emptyList()
      members.mapNotNull { member ->
        context.writeState.profileMembers[profileId]?.get(member.memberId)?.let {
          resolve(it.action.actionId)
        }
      }
    }
    P4RuntimeOuterClass.TableAction.TypeCase.ACTION_PROFILE_ACTION_SET ->
      tableAction.actionProfileActionSet.actionProfileActionsList.mapNotNull {
        resolve(it.action.actionId)
      }
    P4RuntimeOuterClass.TableAction.TypeCase.TYPE_NOT_SET,
    null -> emptyList()
  }
}

internal fun deriveDirectResourceActions(
  behavioral: BehavioralConfig,
  p4info: P4InfoOuterClass.P4Info,
  tableNameById: Map<Int, String>,
  actionNameById: Map<Int, String>,
  tableActionOverrides: Map<String, Map<String, String>>,
): DirectResourceActions {
  val actions = behavioral.actionsList + behavioral.controlsList.flatMap { it.localActionsList }
  if (actions.isEmpty()) return DirectResourceActions(emptyMap(), emptyMap())

  if (behavioral.architecture.name == "v1model") {
    return deriveV1ModelDirectResourceActions(
      p4info,
      tableNameById,
      actionNameById,
      tableActionOverrides,
      actions,
    )
  }

  return deriveExplicitDirectResourceActions(behavioral, p4info, tableNameById, actions)
}

private fun deriveV1ModelDirectResourceActions(
  p4info: P4InfoOuterClass.P4Info,
  tableNameById: Map<Int, String>,
  actionNameById: Map<Int, String>,
  tableActionOverrides: Map<String, Map<String, String>>,
  actions: List<ActionDecl>,
): DirectResourceActions {
  val allActions = actions.flatMap { it.p4RuntimeNames() }.toSet()
  val tableActions =
    p4info.tablesList
      .mapNotNull { table ->
        val tableName = tableNameById[table.preamble.id] ?: return@mapNotNull null
        val actionNames =
          table.actionRefsList
            .mapNotNull { ref -> actionNameById[ref.id] }
            .map { actionName -> tableActionOverrides[tableName]?.get(actionName) ?: actionName }
            .toSet()
        tableName to (actionNames.ifEmpty { allActions })
      }
      .toMap()
  val directCounterTables = p4info.directCountersList.mapNotNull { tableNameById[it.directTableId] }
  val directMeterTables = p4info.directMetersList.mapNotNull { tableNameById[it.directTableId] }
  return DirectResourceActions(
    counterActionsByTable = directCounterTables.associateWith { tableActions[it].orEmpty() },
    meterActionsByTable = directMeterTables.associateWith { tableActions[it].orEmpty() },
  )
}

private fun deriveExplicitDirectResourceActions(
  behavioral: BehavioralConfig,
  p4info: P4InfoOuterClass.P4Info,
  tableNameById: Map<Int, String>,
  actions: List<ActionDecl>,
): DirectResourceActions {
  // IR extern instance names (post-midend) may differ from p4info preamble
  // names due to control-block qualification. Collect all IR names by extern
  // type so we can match them to p4info entries below.
  val irInstancesByType =
    behavioral.controlsList.flatMap { it.externInstancesList }.groupBy({ it.typeName }, { it.name })

  fun deriveForResource(
    p4infoEntries: List<Pair<P4InfoOuterClass.Preamble, Int>>,
    externType: String,
    method: String,
  ): Map<String, Set<String>> {
    val tableByInstance =
      p4infoEntries.flatMap { (preamble, directTableId) ->
        val tableName = tableNameById[directTableId] ?: return@flatMap emptyList()
        directResourceInstanceNames(preamble, irInstancesByType[externType]).map { it to tableName }
      }
    val actionsByTable = tableByInstance.map { it.second }.associateWith { mutableSetOf<String>() }
    val tableByInstanceName = tableByInstance.toMap()
    for (action in actions) {
      val names = action.p4RuntimeNames()
      for (instance in action.directResourceCalls(externType, method)) {
        tableByInstanceName[instance]?.let { tableName ->
          actionsByTable.getValue(tableName).addAll(names)
        }
      }
    }
    return actionsByTable.mapValues { it.value.toSet() }
  }

  return DirectResourceActions(
    counterActionsByTable =
      deriveForResource(
        p4info.directCountersList.map { it.preamble to it.directTableId },
        "direct_counter",
        "count",
      ),
    meterActionsByTable =
      deriveForResource(
        p4info.directMetersList.map { it.preamble to it.directTableId },
        "direct_meter",
        "read",
      ),
  )
}

private fun ActionDecl.p4RuntimeNames(): List<String> =
  listOf(name, currentName).filter { it.isNotEmpty() }

// Generates all name variants under which an action body might reference this
// direct resource. p4info uses dot-separated fully-qualified names
// ("ingress.ctrl.counter"); the IR uses midend-qualified names that may not
// match any simple normalization of the p4info name ("ctrl_ctrl_counter").
// Including matching IR instance names closes the gap.
private fun directResourceInstanceNames(
  preamble: P4InfoOuterClass.Preamble,
  irInstanceNames: List<String>?,
): Set<String> = buildSet {
  val p4infoNames = buildSet {
    for (name in listOf(preamble.name, preamble.alias)) {
      if (name.isEmpty()) continue
      add(name)
      add(name.replace('.', '_'))
      add(name.substringAfterLast('.'))
    }
  }
  addAll(p4infoNames)
  // Match IR instance names whose suffix (after a '_' word boundary) matches
  // a p4info-derived variant. This handles midend control-block qualification
  // like "ctrl_ctrl_counter" where the p4info alias is "ctrl_counter".
  irInstanceNames?.forEach { irName ->
    if (irName in p4infoNames) return@forEach
    if (p4infoNames.any { variant -> irName.endsWith("_$variant") }) {
      add(irName)
    }
  }
}

private fun ActionDecl.directResourceCalls(externType: String, method: String): Set<String> =
  buildSet {
    fun scanExpr(expr: Expr) {
      when (expr.kindCase) {
        ExprKind.FIELD_ACCESS -> scanExpr(expr.fieldAccess.expr)
        ExprKind.ARRAY_INDEX -> {
          scanExpr(expr.arrayIndex.expr)
          scanExpr(expr.arrayIndex.index)
        }
        ExprKind.SLICE -> scanExpr(expr.slice.expr)
        ExprKind.CONCAT -> {
          scanExpr(expr.concat.left)
          scanExpr(expr.concat.right)
        }
        ExprKind.CAST -> scanExpr(expr.cast.expr)
        ExprKind.BINARY_OP -> {
          scanExpr(expr.binaryOp.left)
          scanExpr(expr.binaryOp.right)
        }
        ExprKind.UNARY_OP -> scanExpr(expr.unaryOp.expr)
        ExprKind.METHOD_CALL -> {
          val call = expr.methodCall
          if (
            call.method == method &&
              call.target.hasNameRef() &&
              call.target.type.named == externType
          ) {
            add(call.target.nameRef.name)
          }
          scanExpr(call.target)
          call.argsList.forEach(::scanExpr)
        }
        ExprKind.MUX -> {
          scanExpr(expr.mux.condition)
          scanExpr(expr.mux.thenExpr)
          scanExpr(expr.mux.elseExpr)
        }
        ExprKind.STRUCT_EXPR -> expr.structExpr.fieldsList.map { it.value }.forEach(::scanExpr)
        ExprKind.LITERAL,
        ExprKind.NAME_REF,
        ExprKind.TABLE_APPLY,
        ExprKind.KIND_NOT_SET,
        null -> Unit
      }
    }

    fun scanStmt(stmt: Stmt) {
      when (stmt.kindCase) {
        StmtKind.ASSIGNMENT -> {
          scanExpr(stmt.assignment.lhs)
          scanExpr(stmt.assignment.rhs)
        }
        StmtKind.METHOD_CALL -> scanExpr(stmt.methodCall.call)
        StmtKind.IF_STMT -> {
          scanExpr(stmt.ifStmt.condition)
          stmt.ifStmt.thenBlock.stmtsList.forEach(::scanStmt)
          stmt.ifStmt.elseBlock.stmtsList.forEach(::scanStmt)
        }
        StmtKind.SWITCH_STMT -> {
          scanExpr(stmt.switchStmt.subject)
          stmt.switchStmt.casesList.flatMap { it.block.stmtsList }.forEach(::scanStmt)
          stmt.switchStmt.defaultBlock.stmtsList.forEach(::scanStmt)
        }
        StmtKind.BLOCK -> stmt.block.stmtsList.forEach(::scanStmt)
        StmtKind.RETURN_STMT -> scanExpr(stmt.returnStmt.value)
        StmtKind.EXIT,
        StmtKind.KIND_NOT_SET,
        null -> Unit
      }
    }

    bodyList.forEach(::scanStmt)
  }
