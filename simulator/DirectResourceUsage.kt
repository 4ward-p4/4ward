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

  context.counterActionsByTable[tableName]?.let { allowedActions ->
    if (entry.hasCounterData()) {
      actions
        .firstOrNull { it !in allowedActions }
        ?.let { action ->
          return WriteResult.InvalidArgument(
            "TableEntry contained counter_data, but action '$action' for table '$tableName' " +
              "does not execute the table's direct counter"
          )
        }
    }
  }
  context.meterActionsByTable[tableName]?.let { allowedActions ->
    if (entry.hasMeterConfig()) {
      actions
        .firstOrNull { it !in allowedActions }
        ?.let { action ->
          return WriteResult.InvalidArgument(
            "TableEntry contained meter_config, but action '$action' for table '$tableName' " +
              "does not execute the table's direct meter"
          )
        }
    }
  }
  return null
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

  return deriveExplicitDirectResourceActions(p4info, tableNameById, actions)
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
  p4info: P4InfoOuterClass.P4Info,
  tableNameById: Map<Int, String>,
  actions: List<ActionDecl>,
): DirectResourceActions {
  val counterTableByInstance =
    p4info.directCountersList.flatMap { counter ->
      val tableName = tableNameById[counter.directTableId] ?: return@flatMap emptyList()
      directResourceInstanceNames(counter.preamble).map { it to tableName }
    }
  val meterTableByInstance =
    p4info.directMetersList.flatMap { meter ->
      val tableName = tableNameById[meter.directTableId] ?: return@flatMap emptyList()
      directResourceInstanceNames(meter.preamble).map { it to tableName }
    }
  val counterActionsByTable =
    counterTableByInstance.map { it.second }.associateWith { mutableSetOf<String>() }
  val meterActionsByTable =
    meterTableByInstance.map { it.second }.associateWith { mutableSetOf<String>() }
  val counterTableByInstanceName = counterTableByInstance.toMap()
  val meterTableByInstanceName = meterTableByInstance.toMap()

  for (action in actions) {
    val names = action.p4RuntimeNames()
    for (instance in action.directResourceCalls("direct_counter", "count")) {
      counterTableByInstanceName[instance]?.let { tableName ->
        counterActionsByTable.getValue(tableName).addAll(names)
      }
    }
    for (instance in action.directResourceCalls("direct_meter", "read")) {
      meterTableByInstanceName[instance]?.let { tableName ->
        meterActionsByTable.getValue(tableName).addAll(names)
      }
    }
  }
  return DirectResourceActions(
    counterActionsByTable.mapValues { it.value.toSet() },
    meterActionsByTable.mapValues { it.value.toSet() },
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
  val p4infoNames = mutableSetOf<String>()
  for (name in listOf(preamble.name, preamble.alias)) {
    if (name.isEmpty()) continue
    add(name)
    p4infoNames.add(name)
    add(name.replace('.', '_'))
    p4infoNames.add(name.replace('.', '_'))
    add(name.substringAfterLast('.'))
    p4infoNames.add(name.substringAfterLast('.'))
  }
  // Match IR instance names whose suffix (after a '_' word boundary) matches
  // a p4info-derived variant. This handles midend control-block qualification
  // like "ctrl_ctrl_counter" where the p4info alias is "ctrl_counter".
  irInstanceNames?.forEach { irName ->
    if (irName in p4infoNames) return@forEach
    if (p4infoNames.any { variant -> irName.endsWith("_$variant") }) {
      add(irName)
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
