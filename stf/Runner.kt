package fourward.stf

import com.google.protobuf.ByteString
import com.google.protobuf.TextFormat
import fourward.PipelineConfig
import fourward.simulator.Simulator
import fourward.simulator.WriteResult
import fourward.simulator.portToBytes
import java.math.BigInteger
import java.nio.file.Path
import p4.config.v1.P4InfoOuterClass
import p4.v1.P4RuntimeOuterClass

/** BMv2 STF files use `$N` for array indices; normalize to `[N]`. */
private val ARRAY_INDEX_REGEX = Regex("\\$(\\d+)")
private val INTEGER_REGEX = Regex("\\d+")

/**
 * Runs a single STF test against the 4ward simulator.
 *
 * An STF (Simple Test Framework) test consists of:
 * - A compiled PipelineConfig proto file (produced by p4c-4ward).
 * - A .stf file with table entries, input packets, and expected output packets.
 *
 * The runner:
 * 1. Creates a simulator and loads the pipeline config.
 * 2. Installs table entries.
 * 3. Sends each input packet and compares output to expected.
 * 4. Reports pass/fail.
 */
class StfRunner(private val pipelineConfigPath: Path, private val dropPortOverride: Int? = null) {

  /**
   * Runs an STF test: loads the pipeline, installs entries, sends packets, and compares output.
   *
   * Cross-port ordering is ignored; within the same port, outputs are matched FIFO (first output on
   * that port satisfies the first expect for that port). This matches BMv2's STF semantics.
   *
   * Unexpected output packets (those not matched by any `expect` directive) cause a test failure
   * when the STF file contains at least one `expect` — indicating the test makes assertions about
   * output. STF files with no `expect` directives are "send-only" tests that don't check output.
   */
  fun run(stfPath: Path): TestResult {
    val stf = StfFile.parse(stfPath)
    val config = loadPipelineConfig(pipelineConfigPath)

    val sim = Simulator()
    try {
      sim.loadPipeline(config, dropPortOverride = dropPortOverride)
    } catch (e: IllegalArgumentException) {
      return TestResult.Failure("LoadPipeline failed: ${e.message}")
    }

    try {
      installStfEntries(sim, stf, config.p4Info)
    } catch (e: IllegalStateException) {
      return TestResult.Failure(e.message ?: "WriteEntry failed")
    }

    val outputQueue = mutableListOf<ReceivedPacket>()

    for (packet in stf.packets) {
      val result = sim.processPacket(packet.ingressPort, packet.payload)
      if (System.getenv("PRINT_TRACE") != null) {
        println("--- Trace tree (port ${packet.ingressPort}) ---")
        print(TextFormat.printer().printToString(result.trace))
        println("--- End trace tree ---")
      }
      appendBestOutcome(result.possibleOutcomes, stf.expects, outputQueue)
    }

    val failures = matchOutputAgainstExpects(stf.expects, outputQueue)
    return if (failures.isEmpty()) TestResult.Pass
    else TestResult.Failure(failures.joinToString("\n"))
  }
}

/**
 * Appends the best-matching outcome's outputs to [outputQueue].
 *
 * For each outcome in [possibleOutcomes], scores how well it satisfies [expects] when combined with
 * already-accumulated outputs, and picks the outcome with the fewest failures. For the common case
 * of a single possible outcome (no alternative forks), skips scoring entirely.
 */
fun appendBestOutcome(
  possibleOutcomes: List<List<fourward.OutputPacket>>,
  expects: List<StfExpectedOutput>,
  outputQueue: MutableList<ReceivedPacket>,
) {
  val bestOutcome =
    if (possibleOutcomes.size == 1) {
      possibleOutcomes[0]
    } else {
      possibleOutcomes.minBy { outcome ->
        val candidate =
          outputQueue +
            outcome.map { ReceivedPacket(it.dataplaneEgressPort, it.payload.toByteArray()) }
        matchOutputAgainstExpects(expects, candidate).size
      }
    }
  outputQueue +=
    bestOutcome.map { ReceivedPacket(it.dataplaneEgressPort, it.payload.toByteArray()) }
}

/**
 * Matches simulator output against STF expects, returning a list of failure messages (empty =
 * pass).
 *
 * Cross-port ordering is ignored; within the same port, outputs are matched FIFO. Unexpected output
 * (not matched by any expect) is rejected only when the STF has at least one expect — otherwise the
 * test is "send-only" and doesn't make claims about output.
 */
fun matchOutputAgainstExpects(
  expects: List<StfExpectedOutput>,
  outputs: List<ReceivedPacket>,
): List<String> {
  val remaining = outputs.toMutableList()
  val failures = mutableListOf<String>()

  for (expected in expects) {
    val idx = remaining.indexOfFirst { it.egressPort == expected.port }
    if (idx < 0) {
      failures += "expected packet on port ${expected.port} but got none"
    } else {
      val actual = remaining.removeAt(idx)
      if (!actual.payload.matchesMasked(expected.payload, expected.mask, expected.exactLength)) {
        failures +=
          "port ${expected.port}: payload mismatch\n" +
            "  expected: ${expected.payload.hex(expected.mask)}\n" +
            "  actual:   ${actual.payload.hex()}"
      }
    }
  }

  // Reject unexpected output only when the STF has explicit expects — otherwise
  // the test is "send-only" and doesn't make claims about output.
  if (expects.isNotEmpty()) {
    for (unexpected in remaining) {
      failures += "unexpected packet on port ${unexpected.egressPort}: ${unexpected.payload.hex()}"
    }
  }

  return failures
}

/**
 * Installs all STF-declared entries (PRE, action profile members/groups, table entries) into the
 * simulator. Throws [IllegalStateException] on write failure.
 */
fun installStfEntries(sim: Simulator, stf: StfFile, p4Info: P4InfoOuterClass.P4Info) {
  fun write(update: P4RuntimeOuterClass.Update, label: String) {
    val result = sim.writeEntry(update)
    if (result !is WriteResult.Success) {
      error("WriteEntry ($label) failed: ${result.message}")
    }
  }

  for (mirror in stf.pre.mirroringAdds) {
    write(resolveStfMirroringAdd(mirror), "mirroring")
  }
  for (mcGroup in stf.pre.mcGroupCreates) {
    write(
      resolveStfMulticastGroup(mcGroup.groupId, stf.pre.mcNodeCreates, stf.pre.mcNodeAssociates),
      "multicast",
    )
  }
  // mirroring_add_mc resolves mc group → replicas from parsed STF data, so install order is fine.
  for (mirror in stf.pre.mirroringAddMcs) {
    write(
      resolveStfMirroringAddMc(mirror, stf.pre.mcNodeCreates, stf.pre.mcNodeAssociates),
      "mirroring_mc",
    )
  }
  for (member in stf.memberDirectives) {
    write(resolveStfMember(member, p4Info), "member")
  }
  for (group in stf.groupDirectives) {
    write(resolveStfGroup(group, p4Info), "group")
  }
  for (directive in stf.tableEntries) {
    write(resolveStfTableEntry(directive, p4Info), "table")
  }
  sim.publishSnapshot()
}

/** Parses a text-format [PipelineConfig] proto from a file. */
fun loadPipelineConfig(path: Path): PipelineConfig {
  val builder = PipelineConfig.newBuilder()
  com.google.protobuf.TextFormat.merge(path.toFile().readText(), builder)
  return builder.build()
}

sealed class TestResult {
  object Pass : TestResult()

  data class Failure(val message: String) : TestResult()
}

/**
 * Runs the STF test named [testName] using the standard Bazel runfiles layout.
 *
 * Resolves `<pkg>/<testName>.txtpb` and `<pkg>/<testName>.stf` via the Bazel Runfiles library. The
 * [pkg] defaults to `e2e_tests/<testName>` (matching the per-test package layout of the regular e2e
 * tests).
 */
fun runStfTest(testName: String, pkg: String = "e2e_tests/$testName"): TestResult =
  runStf(
    fourward.bazel.repoRoot.resolve("$pkg/$testName.txtpb"),
    fourward.bazel.repoRoot.resolve("$pkg/$testName.stf"),
  )

fun runStf(configPath: Path, stfPath: Path): TestResult = StfRunner(configPath).run(stfPath)

/** Packet Replication Engine configuration parsed from STF directives. */
data class StfPreConfig(
  val mirroringAdds: List<StfMirroringAdd> = emptyList(),
  val mirroringAddMcs: List<StfMirroringAddMc> = emptyList(),
  val mcGroupCreates: List<StfMcGroupCreate> = emptyList(),
  val mcNodeCreates: List<StfMcNodeCreate> = emptyList(),
  val mcNodeAssociates: List<StfMcNodeAssociate> = emptyList(),
)

/** A parsed .stf file. */
data class StfFile(
  val tableEntries: List<StfTableDirective>,
  val memberDirectives: List<StfMemberDirective>,
  val groupDirectives: List<StfGroupDirective>,
  val pre: StfPreConfig,
  val packets: List<StfPacket>,
  val expects: List<StfExpectedOutput>,
) {
  companion object {
    /**
     * Parses an STF file. Supported directives:
     * - `packet <port> <hex bytes>` — send a packet on ingress port
     * - `expect <port> <hex bytes>` — expect a packet on egress port
     * - `add <table> [priority] <field:value>... <action(params)>` or `add <table> <field:value>...
     *   group=<gid>` — install table entry
     * - `setdefault <table> <action(params)>` — override a table's default action
     * - `member <profile> <member_id> <action(params)>` — action profile member
     * - `group <profile> <group_id> <member_id>...` — action profile group
     * - `mirroring_add <session_id> <egress_port>` — clone session (single port)
     * - `mirroring_add_mc <session_id> <mc_group_id>` — clone session → multicast group (PSA)
     * - `mirroring_get <session_id>` — informational query (ignored)
     * - `mc_mgrp_create <group_id>` — multicast group
     * - `mc_node_create <rid> <port> [<port> ...]` — multicast node
     * - `mc_node_associate <group_id> <node_handle>` — associate node with group
     * - `# comment`
     */
    fun parse(path: Path): StfFile = parse(path.toFile().readLines())

    @Suppress("CyclomaticComplexMethod")
    fun parse(lines: List<String>): StfFile {
      val directives = lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

      val tableEntries = mutableListOf<StfTableDirective>()
      val memberDirectives = mutableListOf<StfMemberDirective>()
      val groupDirectives = mutableListOf<StfGroupDirective>()
      val mirroringAdds = mutableListOf<StfMirroringAdd>()
      val mirroringAddMcs = mutableListOf<StfMirroringAddMc>()
      val mcGroupCreates = mutableListOf<StfMcGroupCreate>()
      val mcNodeCreates = mutableListOf<StfMcNodeCreate>()
      val mcNodeAssociates = mutableListOf<StfMcNodeAssociate>()
      val packets = mutableListOf<StfPacket>()
      val expects = mutableListOf<StfExpectedOutput>()

      for (line in directives) {
        val tokens = tokenizeQuoteAware(line)
        when (tokens[0].lowercase()) {
          "packet" -> {
            val port = tokens[1].toInt()
            val payload = tokens.drop(2).joinToString("").decodeHex()
            packets += StfPacket(port, payload)
          }
          "expect" -> {
            val port = tokens[1].toInt()
            val raw = tokens.drop(2).joinToString("")
            // "$" marks end-of-packet: assert actual length == expected length.
            // Without "$", trailing bytes in actual are ignored (BMv2 semantics).
            val exactLength = raw.contains('$')
            val hexStr = raw.replace("$", "")
            val (payload, mask) = decodeExpect(hexStr)
            expects += StfExpectedOutput(port, payload, mask, exactLength)
          }
          "add" -> tableEntries += parseAdd(tokens.drop(1))
          "setdefault" -> tableEntries += parseSetDefault(tokens.drop(1))
          "member" -> memberDirectives += parseMember(tokens.drop(1))
          "group" -> groupDirectives += parseGroup(tokens.drop(1))
          "mirroring_add" -> mirroringAdds += parseMirroringAdd(tokens.drop(1))
          "mirroring_add_mc" -> mirroringAddMcs += parseMirroringAddMc(tokens.drop(1))
          "mirroring_get" -> {} // Informational only — not needed for simulation.
          "mc_mgrp_create" -> mcGroupCreates += parseMcGroupCreate(tokens.drop(1))
          "mc_node_create" -> mcNodeCreates += parseMcNodeCreate(tokens.drop(1))
          "mc_node_associate" -> mcNodeAssociates += parseMcNodeAssociate(tokens.drop(1))
        }
      }

      return StfFile(
        tableEntries,
        memberDirectives,
        groupDirectives,
        StfPreConfig(
          mirroringAdds,
          mirroringAddMcs,
          mcGroupCreates,
          mcNodeCreates,
          mcNodeAssociates,
        ),
        packets,
        expects,
      )
    }

    /**
     * Parses the tokens after "add".
     *
     * Format: `TABLE [PRIORITY] FIELD:VALUE[/PREFIXLEN|&&&MASK]... ACTION([PARAMS])`
     *
     * p4testgen quotes identifiers (`"table"`, `"action"`, `"field"`), so we strip quotes
     * throughout.
     */
    private fun parseAdd(tokens: List<String>): StfAddEntry {
      require(tokens.isNotEmpty()) { "add directive missing table name" }
      val tableName = tokens[0].unquote()
      var idx = 1

      // Optional priority: a token that is a plain integer (no ':' or '(').
      var priority: Int? = null
      if (idx < tokens.size && tokens[idx].matches(INTEGER_REGEX)) {
        priority = tokens[idx].toInt()
        idx++
      }

      // Collect match fields until we hit a token containing '(' (the action) or 'group='.
      val matches = mutableListOf<StfMatchField>()
      while (idx < tokens.size && !tokens[idx].contains('(') && !tokens[idx].startsWith("group=")) {
        matches += parseMatchField(tokens[idx])
        idx++
      }

      // Check for group reference: `group=<gid>`
      if (idx < tokens.size && tokens[idx].startsWith("group=")) {
        val groupId = tokens[idx].removePrefix("group=").toInt()
        return StfAddEntry(tableName, priority, matches, groupId = groupId)
      }

      // The remaining token(s) form the action spec: name(param1, param2, ...).
      // Join remaining tokens in case the action spans multiple tokens (e.g. spaces in params).
      val actionSpec = tokens.drop(idx).joinToString(" ")
      require(actionSpec.isNotEmpty()) { "add directive missing action" }
      val (actionName, actionParams) = parseActionSpec(actionSpec)

      return StfAddEntry(
        tableName,
        priority,
        matches,
        actionName = actionName,
        actionParams = actionParams,
      )
    }

    /**
     * Parses the tokens after "setdefault".
     *
     * Format: `TABLE ACTION([PARAMS])`
     */
    private fun parseSetDefault(tokens: List<String>): StfSetDefault {
      require(tokens.isNotEmpty()) { "setdefault directive missing table name" }
      val tableName = tokens[0].unquote()
      val actionSpec = tokens.drop(1).joinToString(" ")
      require(actionSpec.isNotEmpty()) { "setdefault directive missing action" }
      val (actionName, actionParams) = parseActionSpec(actionSpec)
      return StfSetDefault(tableName, actionName, actionParams)
    }

    /** Parses `FIELD:VALUE[/PREFIXLEN]` or `FIELD:VALUE[&&&MASK]`. */
    private fun parseMatchField(token: String): StfMatchField {
      val colonIdx = token.indexOf(':')
      require(colonIdx > 0) { "invalid match field token: $token" }
      val fieldName = token.substring(0, colonIdx).unquote()
      val rest = token.substring(colonIdx + 1)

      // p4testgen uses binary wildcards: 0b1010**** where * bits are don't-care.
      // Convert to value/mask ternary representation.
      if (rest.startsWith("0b") && rest.contains('*')) {
        val (value, mask) = parseBinaryWildcard(rest)
        return StfMatchField(fieldName, MatchKind.TERNARY, value, mask = mask)
      }

      // BMv2 STF hex wildcards: 0x****0101 where each * nibble is don't-care.
      if (rest.startsWith("0x", ignoreCase = true) && rest.contains('*')) {
        val (value, mask) = parseHexWildcard(rest)
        return StfMatchField(fieldName, MatchKind.TERNARY, value, mask = mask)
      }

      return when {
        rest.contains("/") -> {
          val (value, prefixLen) = rest.split("/", limit = 2)
          StfMatchField(fieldName, MatchKind.LPM, value, prefixLen = prefixLen.toInt())
        }
        rest.contains("&&&") -> {
          val (value, mask) = rest.split("&&&", limit = 2)
          StfMatchField(fieldName, MatchKind.TERNARY, value, mask = mask)
        }
        else -> StfMatchField(fieldName, MatchKind.EXACT, rest)
      }
    }

    /**
     * Parses `actionName(param1, param2, ...)` and returns the name and param list.
     *
     * p4testgen uses named params: `"action"("p1":val1,"p2":val2)`. We strip quotes from the action
     * name and extract just the value part of each named param.
     */
    private fun parseActionSpec(spec: String): Pair<String, List<String>> {
      val parenIdx = spec.indexOf('(')
      require(parenIdx > 0) { "invalid action spec: $spec" }
      val name = spec.substring(0, parenIdx).trim().unquote()
      val paramStr = spec.substring(parenIdx + 1).trimEnd(')', ' ')
      val params =
        if (paramStr.isBlank()) emptyList()
        else paramStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
      return name to params
    }

    /** Parses: `member <profile> <member_id> <action_name> [<param>=<hex> ...]` */
    @Suppress("MagicNumber")
    private fun parseMember(tokens: List<String>): StfMemberDirective {
      require(tokens.size >= 3) { "member directive needs at least profile, id, and action" }
      val profileName = tokens[0]
      val memberId = tokens[1].toInt()
      val actionName = tokens[2]
      val params =
        tokens.drop(3).associate { token ->
          val (name, value) = token.split("=", limit = 2)
          name to value
        }
      return StfMemberDirective(profileName, memberId, actionName, params)
    }

    /** Parses: `group <profile> <group_id> <member_id> [<member_id> ...]` */
    @Suppress("MagicNumber")
    private fun parseGroup(tokens: List<String>): StfGroupDirective {
      require(tokens.size >= 3) { "group directive needs at least profile, group_id, and members" }
      val profileName = tokens[0]
      val groupId = tokens[1].toInt()
      val memberIds = tokens.drop(2).map { it.toInt() }
      return StfGroupDirective(profileName, groupId, memberIds)
    }

    /** Parses: `mirroring_add <session_id> <egress_port>` */
    private fun parseMirroringAdd(tokens: List<String>): StfMirroringAdd {
      require(tokens.size >= 2) { "mirroring_add needs session_id and egress_port" }
      return StfMirroringAdd(tokens[0].toInt(), tokens[1].toInt())
    }

    /** Parses: `mirroring_add_mc <session_id> <mc_group_id>` (PSA clone → multicast group). */
    private fun parseMirroringAddMc(tokens: List<String>): StfMirroringAddMc {
      require(tokens.size >= 2) { "mirroring_add_mc needs session_id and mc_group_id" }
      return StfMirroringAddMc(tokens[0].toInt(), tokens[1].toInt())
    }

    /** Parses: `mc_mgrp_create <group_id>` */
    private fun parseMcGroupCreate(tokens: List<String>): StfMcGroupCreate {
      require(tokens.isNotEmpty()) { "mc_mgrp_create needs group_id" }
      return StfMcGroupCreate(tokens[0].toInt())
    }

    /** Parses: `mc_node_create <rid> <port> [<port> ...]` */
    private fun parseMcNodeCreate(tokens: List<String>): StfMcNodeCreate {
      require(tokens.size >= 2) { "mc_node_create needs rid and at least one port" }
      return StfMcNodeCreate(tokens[0].toInt(), tokens.drop(1).map { it.toInt() })
    }

    /** Parses: `mc_node_associate <group_id> <node_handle>` */
    private fun parseMcNodeAssociate(tokens: List<String>): StfMcNodeAssociate {
      require(tokens.size >= 2) { "mc_node_associate needs group_id and node_handle" }
      return StfMcNodeAssociate(tokens[0].toInt(), tokens[1].toInt())
    }
  }
}

// ---------------------------------------------------------------------------
// Table entry resolution
// ---------------------------------------------------------------------------

/** Resolves an STF table directive against the p4info to produce a P4Runtime [Update]. */
fun resolveStfTableEntry(
  directive: StfTableDirective,
  p4info: P4InfoOuterClass.P4Info,
): P4RuntimeOuterClass.Update {
  val table = findTable(directive.tableName, p4info)

  val tableEntry = P4RuntimeOuterClass.TableEntry.newBuilder().setTableId(table.preamble.id)

  // Group reference: set action_profile_group_id instead of a direct action.
  val isGroupEntry = directive is StfAddEntry && directive.groupId != null
  if (isGroupEntry) {
    tableEntry.setAction(
      P4RuntimeOuterClass.TableAction.newBuilder()
        .setActionProfileGroupId((directive as StfAddEntry).groupId!!)
    )
  } else {
    val action = findAction(directive.actionName, p4info)

    val paramsList =
      action.paramsList.mapIndexed { i, paramInfo ->
        val raw =
          directive.actionParams.find { it.extractParamName() == paramInfo.name }
            ?: directive.actionParams.getOrNull(i)
            ?: error("missing param ${paramInfo.name} for action ${directive.actionName}")
        P4RuntimeOuterClass.Action.Param.newBuilder()
          .setParamId(paramInfo.id)
          .setValue(encodeValue(raw.stripNamedParamPrefix(), paramInfo.bitwidth))
          .build()
      }

    tableEntry.setAction(
      P4RuntimeOuterClass.TableAction.newBuilder()
        .setAction(
          P4RuntimeOuterClass.Action.newBuilder()
            .setActionId(action.preamble.id)
            .addAllParams(paramsList)
        )
    )
  }

  val updateType =
    when (directive) {
      is StfAddEntry -> {
        tableEntry.addAllMatch(
          directive.matches.map { m -> resolveStfMatchField(m, table, directive.tableName) }
        )
        if (directive.priority != null) tableEntry.setPriority(directive.priority)
        P4RuntimeOuterClass.Update.Type.INSERT
      }
      is StfSetDefault -> {
        tableEntry.setIsDefaultAction(true)
        P4RuntimeOuterClass.Update.Type.MODIFY
      }
    }

  return update(
    P4RuntimeOuterClass.Entity.newBuilder().setTableEntry(tableEntry).build(),
    updateType,
  )
}

/** Resolves an STF member directive to a P4Runtime [Update]. */
fun resolveStfMember(
  directive: StfMemberDirective,
  p4info: P4InfoOuterClass.P4Info,
): P4RuntimeOuterClass.Update {
  val profile = findActionProfile(directive.profileName, p4info)
  val action = findAction(directive.actionName, p4info)

  val paramsList =
    action.paramsList.map { paramInfo ->
      val raw =
        directive.params[paramInfo.name]
          ?: error("missing param ${paramInfo.name} for action ${directive.actionName}")
      P4RuntimeOuterClass.Action.Param.newBuilder()
        .setParamId(paramInfo.id)
        .setValue(encodeValue(raw, paramInfo.bitwidth))
        .build()
    }

  val member =
    P4RuntimeOuterClass.ActionProfileMember.newBuilder()
      .setActionProfileId(profile.preamble.id)
      .setMemberId(directive.memberId)
      .setAction(
        P4RuntimeOuterClass.Action.newBuilder()
          .setActionId(action.preamble.id)
          .addAllParams(paramsList)
      )
      .build()

  return update(P4RuntimeOuterClass.Entity.newBuilder().setActionProfileMember(member).build())
}

/** Resolves an STF group directive to a P4Runtime [Update]. */
fun resolveStfGroup(
  directive: StfGroupDirective,
  p4info: P4InfoOuterClass.P4Info,
): P4RuntimeOuterClass.Update {
  val profile = findActionProfile(directive.profileName, p4info)

  val group =
    P4RuntimeOuterClass.ActionProfileGroup.newBuilder()
      .setActionProfileId(profile.preamble.id)
      .setGroupId(directive.groupId)
      .addAllMembers(
        directive.memberIds.map { memberId ->
          P4RuntimeOuterClass.ActionProfileGroup.Member.newBuilder()
            .setMemberId(memberId)
            .setWeight(1)
            .build()
        }
      )
      .build()

  return update(P4RuntimeOuterClass.Entity.newBuilder().setActionProfileGroup(group).build())
}

/** Resolves a mirroring_add directive to a P4Runtime [Update] (clone session). */
fun resolveStfMirroringAdd(directive: StfMirroringAdd): P4RuntimeOuterClass.Update {
  val session =
    P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
      .setSessionId(directive.sessionId)
      .addReplicas(
        P4RuntimeOuterClass.Replica.newBuilder().setPort(portToBytes(directive.egressPort))
      )
      .build()

  return update(
    P4RuntimeOuterClass.Entity.newBuilder()
      .setPacketReplicationEngineEntry(
        P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder().setCloneSessionEntry(session)
      )
      .build()
  )
}

/**
 * Resolves a mirroring_add_mc directive (PSA: clone session → multicast group) into a P4Runtime
 * [Update]. Looks up the multicast group's replicas from the parsed STF data and creates a
 * [P4RuntimeOuterClass.CloneSessionEntry] with those replicas.
 */
fun resolveStfMirroringAddMc(
  directive: StfMirroringAddMc,
  nodes: List<StfMcNodeCreate>,
  associations: List<StfMcNodeAssociate>,
): P4RuntimeOuterClass.Update {
  val replicas = resolveReplicas(directive.mcGroupId, nodes, associations)

  val session =
    P4RuntimeOuterClass.CloneSessionEntry.newBuilder()
      .setSessionId(directive.sessionId)
      .addAllReplicas(replicas)
      .build()

  return update(
    P4RuntimeOuterClass.Entity.newBuilder()
      .setPacketReplicationEngineEntry(
        P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder().setCloneSessionEntry(session)
      )
      .build()
  )
}

/**
 * Resolves multicast STF directives into a P4Runtime [Update].
 *
 * BMv2 STF uses three directives to configure multicast: mc_mgrp_create, mc_node_create, and
 * mc_node_associate. We merge them into a single MulticastGroupEntry with all replicas.
 *
 * [nodes] is ordered by creation time; BMv2 assigns node handles as sequential indices starting at
 * 0, so mc_node_associate references nodes by list index.
 */
fun resolveStfMulticastGroup(
  groupId: Int,
  nodes: List<StfMcNodeCreate>,
  associations: List<StfMcNodeAssociate>,
): P4RuntimeOuterClass.Update {
  val replicas = resolveReplicas(groupId, nodes, associations)

  val group =
    P4RuntimeOuterClass.MulticastGroupEntry.newBuilder()
      .setMulticastGroupId(groupId)
      .addAllReplicas(replicas)
      .build()

  return update(
    P4RuntimeOuterClass.Entity.newBuilder()
      .setPacketReplicationEngineEntry(
        P4RuntimeOuterClass.PacketReplicationEngineEntry.newBuilder().setMulticastGroupEntry(group)
      )
      .build()
  )
}

/**
 * Resolves a multicast group's replicas from parsed STF mc_node_create and mc_node_associate
 * directives. Node handles are sequential indices into [nodes] (BMv2 convention).
 */
private fun resolveReplicas(
  groupId: Int,
  nodes: List<StfMcNodeCreate>,
  associations: List<StfMcNodeAssociate>,
): List<P4RuntimeOuterClass.Replica> =
  associations
    .filter { it.groupId == groupId }
    .flatMap { assoc ->
      val node =
        nodes.getOrNull(assoc.nodeHandle) ?: error("unknown mc node handle: ${assoc.nodeHandle}")
      node.ports.map { port ->
        P4RuntimeOuterClass.Replica.newBuilder()
          .setPort(portToBytes(port))
          .setInstance(node.rid)
          .build()
      }
    }

/** Wraps a P4Runtime Entity in an [Update] with the given update type. */
private fun update(
  entity: P4RuntimeOuterClass.Entity,
  type: P4RuntimeOuterClass.Update.Type = P4RuntimeOuterClass.Update.Type.INSERT,
): P4RuntimeOuterClass.Update =
  P4RuntimeOuterClass.Update.newBuilder().setType(type).setEntity(entity).build()

/** Returns a bare-hex all-ones mask for the given bitwidth (e.g. 9 → "01ff", 16 → "ffff"). */
fun allOnesMask(bitwidth: Int): String {
  val byteLen = (bitwidth + 7) / 8
  val value = BigInteger.ONE.shiftLeft(bitwidth).subtract(BigInteger.ONE)
  return value.toString(16).padStart(byteLen * 2, '0')
}

/** Returns a bare-hex all-zeros mask for the given bitwidth (e.g. 9 → "0000", 16 → "0000"). */
fun allZerosMask(bitwidth: Int): String = "00".repeat((bitwidth + 7) / 8)

fun findTable(name: String, p4info: P4InfoOuterClass.P4Info): P4InfoOuterClass.Table =
  p4info.tablesList.find { it.preamble.alias == name || it.preamble.name == name }
    ?: p4info.tablesList.find { it.preamble.name.endsWith(".$name") }
    ?: error(
      "unknown table: $name (available: ${p4info.tablesList.map { it.preamble.name }.sorted().joinToString(", ")})"
    )

fun findAction(name: String, p4info: P4InfoOuterClass.P4Info): P4InfoOuterClass.Action =
  p4info.actionsList.find { it.preamble.alias == name || it.preamble.name == name }
    ?: p4info.actionsList.find { it.preamble.name.endsWith(".$name") }
    ?: error(
      "unknown action: $name (available: ${p4info.actionsList.map { it.preamble.name }.sorted().joinToString(", ")})"
    )

fun findActionProfile(
  name: String,
  p4info: P4InfoOuterClass.P4Info,
): P4InfoOuterClass.ActionProfile =
  p4info.actionProfilesList.find { it.preamble.alias == name || it.preamble.name == name }
    ?: p4info.actionProfilesList.find { it.preamble.name.endsWith(".$name") }
    ?: run {
      val available = p4info.actionProfilesList.map { it.preamble.name }.sorted().joinToString(", ")
      error("unknown action profile: $name (available: $available)")
    }

private fun resolveStfMatchField(
  m: StfMatchField,
  table: P4InfoOuterClass.Table,
  tableName: String,
): P4RuntimeOuterClass.FieldMatch {
  // BMv2 STF files strip the outermost struct prefix from field names
  // (e.g. p4info "hdrs.data.f1" → STF "data.f1") and use $N for array
  // indices (p4info "extra[0].h" → STF "extra$0.h").
  val stfNorm = m.fieldName.replace(ARRAY_INDEX_REGEX, "[$1]")
  val mf =
    table.matchFieldsList.find { it.name == m.fieldName || it.name == stfNorm }
      ?: table.matchFieldsList.find {
        val suffix = it.name.substringAfter(".")
        suffix == m.fieldName || suffix == stfNorm
      }
      ?: run {
        val available = table.matchFieldsList.map { it.name }.sorted().joinToString(", ")
        error("unknown match field '${m.fieldName}' in table '$tableName' (available: $available)")
      }
  val fmBuilder = P4RuntimeOuterClass.FieldMatch.newBuilder().setFieldId(mf.id)
  val encodedValue = encodeValue(m.value, mf.bitwidth)

  // BMv2 STF values without wildcards parse as EXACT, but the table's
  // match_type may differ.  Promote to the p4info type so that
  // priority-based scoring (ternary/range) and optional semantics work.
  val p4infoType = mf.matchType
  when {
    m.kind == MatchKind.LPM ->
      fmBuilder.setLpm(
        P4RuntimeOuterClass.FieldMatch.LPM.newBuilder()
          .setValue(encodedValue)
          .setPrefixLen(m.prefixLen!!)
      )
    m.kind == MatchKind.TERNARY ||
      (m.kind == MatchKind.EXACT &&
        p4infoType == P4InfoOuterClass.MatchField.MatchType.TERNARY) -> {
      val mask = m.mask ?: "0x${allOnesMask(mf.bitwidth)}"
      fmBuilder.setTernary(
        P4RuntimeOuterClass.FieldMatch.Ternary.newBuilder()
          .setValue(encodedValue)
          .setMask(encodeValue(mask, mf.bitwidth))
      )
    }
    m.kind == MatchKind.EXACT && p4infoType == P4InfoOuterClass.MatchField.MatchType.RANGE ->
      fmBuilder.setRange(
        P4RuntimeOuterClass.FieldMatch.Range.newBuilder().setLow(encodedValue).setHigh(encodedValue)
      )
    m.kind == MatchKind.EXACT && p4infoType == P4InfoOuterClass.MatchField.MatchType.OPTIONAL ->
      fmBuilder.setOptional(
        P4RuntimeOuterClass.FieldMatch.Optional.newBuilder().setValue(encodedValue)
      )
    m.kind == MatchKind.EXACT && p4infoType == P4InfoOuterClass.MatchField.MatchType.LPM ->
      fmBuilder.setLpm(
        P4RuntimeOuterClass.FieldMatch.LPM.newBuilder()
          .setValue(encodedValue)
          .setPrefixLen(mf.bitwidth)
      )
    else ->
      fmBuilder.setExact(P4RuntimeOuterClass.FieldMatch.Exact.newBuilder().setValue(encodedValue))
  }
  return fmBuilder.build()
}

// ---------------------------------------------------------------------------
// Encoding helpers
// ---------------------------------------------------------------------------

/**
 * Encodes a decimal, 0x-prefixed hex, or dotted-decimal IPv4 string into a big-endian byte array of
 * the right width.
 */
fun encodeValue(raw: String, bitwidth: Int): ByteString {
  val value =
    when {
      raw.startsWith("0x") || raw.startsWith("0X") -> BigInteger(raw.drop(2), 16)
      raw.startsWith("0b") || raw.startsWith("0B") -> BigInteger(raw.drop(2), 2)
      raw.contains('.') -> {
        // Dotted-decimal IPv4 (e.g. "10.0.0.0"): pack octets into a 32-bit integer.
        val octets = raw.split('.')
        require(octets.size == 4) { "invalid IPv4 address in STF: $raw" }
        octets.fold(BigInteger.ZERO) { acc, octet -> acc.shiftLeft(8).add(BigInteger(octet)) }
      }
      else -> BigInteger(raw)
    }
  val byteLen = (bitwidth + 7) / 8
  val bigEndian = value.toByteArray()
  // BigInteger.toByteArray() may include a leading 0x00 sign byte.
  val result =
    ByteArray(byteLen) { i ->
      val srcIdx = bigEndian.size - byteLen + i
      if (srcIdx < 0) 0 else bigEndian[srcIdx]
    }
  return ByteString.copyFrom(result)
}

// ---------------------------------------------------------------------------
// Data types
// ---------------------------------------------------------------------------

class StfPacket(val ingressPort: Int, val payload: ByteArray)

/** An output packet received from the simulator, before matching against expects. */
class ReceivedPacket(val egressPort: Int, val payload: ByteArray)

class StfExpectedOutput(
  val port: Int,
  val payload: ByteArray,
  val mask: ByteArray,
  val exactLength: Boolean = false,
)

/** A parsed table directive (`add` or `setdefault`), before p4info resolution. */
sealed interface StfTableDirective {
  val tableName: String
  val actionName: String
  val actionParams: List<String>
}

data class StfAddEntry(
  override val tableName: String,
  val priority: Int?,
  val matches: List<StfMatchField>,
  override val actionName: String = "",
  override val actionParams: List<String> = emptyList(),
  val groupId: Int? = null,
) : StfTableDirective

data class StfSetDefault(
  override val tableName: String,
  override val actionName: String,
  override val actionParams: List<String>,
) : StfTableDirective

data class StfMatchField(
  val fieldName: String,
  val kind: MatchKind,
  val value: String,
  val prefixLen: Int? = null,
  val mask: String? = null,
)

enum class MatchKind {
  EXACT,
  LPM,
  TERNARY,
}

data class StfMemberDirective(
  val profileName: String,
  val memberId: Int,
  val actionName: String,
  val params: Map<String, String>,
)

data class StfGroupDirective(val profileName: String, val groupId: Int, val memberIds: List<Int>)

data class StfMirroringAdd(val sessionId: Int, val egressPort: Int)

data class StfMirroringAddMc(val sessionId: Int, val mcGroupId: Int)

data class StfMcGroupCreate(val groupId: Int)

data class StfMcNodeCreate(val rid: Int, val ports: List<Int>)

data class StfMcNodeAssociate(val groupId: Int, val nodeHandle: Int)

/** Strips surrounding double-quotes, if present. */
private fun String.unquote(): String = removeSurrounding("\"")

/**
 * Tokenizes a line respecting double-quoted strings. Content inside matching quotes is kept as a
 * single token (quotes included), even if it contains spaces or special characters like `&`.
 * Unquoted regions are split on whitespace as usual.
 *
 * This is needed for p4testgen STFs where field names can contain bitwise-AND expressions:
 * `"hdr.ipv4.dstAddr & 0xf":0x0`.
 */
private fun tokenizeQuoteAware(line: String): List<String> {
  val tokens = mutableListOf<String>()
  val current = StringBuilder()
  var i = 0
  while (i < line.length) {
    when {
      line[i] == '"' -> {
        // Scan to the closing quote. The quoted content may be immediately
        // followed by non-whitespace (e.g. `"field":value`), so we keep
        // accumulating into the current token after the closing quote.
        val closeIdx = line.indexOf('"', i + 1)
        if (closeIdx < 0) {
          // No closing quote — treat rest of line as one token.
          current.append(line.substring(i))
          i = line.length
        } else {
          current.append(line.substring(i, closeIdx + 1))
          i = closeIdx + 1
        }
      }
      line[i].isWhitespace() -> {
        if (current.isNotEmpty()) {
          tokens += current.toString()
          current.clear()
        }
        i++
      }
      else -> {
        current.append(line[i])
        i++
      }
    }
  }
  if (current.isNotEmpty()) tokens += current.toString()
  return tokens
}

/**
 * Splits a possibly-named action param (`"name":value` or `name:value`) into its name (null if
 * positional) and value.
 */
private fun String.splitNamedParam(): Pair<String?, String> {
  // p4testgen: quoted named params like "param":value
  if (startsWith('"')) {
    val sep = indexOf("\":", 1)
    if (sep >= 0) return substring(1, sep) to substring(sep + 2)
  }
  // Unquoted named params like param:value (used in standard STF files).
  val colon = indexOf(':')
  if (colon > 0 && substring(0, colon).all { it.isLetterOrDigit() || it == '_' }) {
    return substring(0, colon) to substring(colon + 1)
  }
  return null to this
}

fun String.extractParamName(): String? = splitNamedParam().first

fun String.stripNamedParamPrefix(): String = splitNamedParam().second

/**
 * Parses a hex wildcard string like `0x****0101` into hex value and mask strings.
 *
 * Each `*` nibble becomes 0 in value and 0 in mask; each hex digit becomes its value and F in mask.
 */
private fun parseHexWildcard(hexStr: String): Pair<String, String> {
  val nibbles = hexStr.removePrefix("0x").removePrefix("0X")
  var value = BigInteger.ZERO
  var mask = BigInteger.ZERO
  for (ch in nibbles) {
    value = value.shiftLeft(4)
    mask = mask.shiftLeft(4)
    if (ch != '*') {
      value = value.or(BigInteger.valueOf(ch.digitToInt(16).toLong()))
      mask = mask.or(BigInteger.valueOf(0xF))
    }
  }
  return "0x${value.toString(16)}" to "0x${mask.toString(16)}"
}

/**
 * Parses a binary wildcard string like `0b1010****` into hex value and mask strings.
 *
 * Each `*` bit becomes a 0 in the value and a 0 in the mask; each `0`/`1` bit becomes its value in
 * the value and a 1 in the mask. Returns hex strings for consistency with the other match value
 * representations (all match values flow through [encodeValue] during p4info resolution).
 */
private fun parseBinaryWildcard(binStr: String): Pair<String, String> {
  val bits = binStr.removePrefix("0b").removePrefix("0B")
  val valueBits = StringBuilder(bits.length)
  val maskBits = StringBuilder(bits.length)
  for (ch in bits) {
    when (ch) {
      '1' -> {
        valueBits.append('1')
        maskBits.append('1')
      }
      '0' -> {
        valueBits.append('0')
        maskBits.append('1')
      }
      '*' -> {
        valueBits.append('0')
        maskBits.append('0')
      }
      else -> error("unexpected character in binary wildcard: $ch")
    }
  }
  val v = if (valueBits.isEmpty()) BigInteger.ZERO else BigInteger(valueBits.toString(), 2)
  val m = if (maskBits.isEmpty()) BigInteger.ZERO else BigInteger(maskBits.toString(), 2)
  return "0x${v.toString(16)}" to "0x${m.toString(16)}"
}

fun String.decodeHex(): ByteArray {
  val clean = replace(" ", "").lowercase()
  return ByteArray(clean.length / 2) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

/**
 * Parses a hex string that may contain `*` wildcard characters. Returns a (payload, mask) pair
 * where each nibble is independently masked: a concrete nibble gets mask 0xF, a `*` gets 0x0.
 *
 * This handles both hand-written STFs (which use `**` for a full wildcard byte) and p4testgen
 * output (which uses `*` per nibble). The `**` case is just two consecutive wildcard nibbles, so
 * the nibble-level logic handles both uniformly.
 */
private fun decodeExpect(hexStr: String): Pair<ByteArray, ByteArray> {
  val clean = hexStr.replace(" ", "").lowercase()
  require(clean.length % 2 == 0) { "odd-length hex in expect: $hexStr" }
  val n = clean.length / 2
  val payload = ByteArray(n)
  val mask = ByteArray(n)
  for (i in 0 until n) {
    val hi = clean[i * 2]
    val lo = clean[i * 2 + 1]
    val hiVal = if (hi == '*') 0 else hi.digitToInt(16)
    val loVal = if (lo == '*') 0 else lo.digitToInt(16)
    val hiMask = if (hi == '*') 0 else 0xF
    val loMask = if (lo == '*') 0 else 0xF
    payload[i] = ((hiVal shl 4) or loVal).toByte()
    mask[i] = ((hiMask shl 4) or loMask).toByte()
  }
  return payload to mask
}

/**
 * Returns true iff every non-wildcard byte (mask != 0) matches expected.
 *
 * When [exactLength] is true, actual and expected must have the same length. When false, actual may
 * be longer — trailing bytes are ignored (BMv2 STF semantics for expects without a trailing `$`).
 */
fun ByteArray.matchesMasked(expected: ByteArray, mask: ByteArray, exactLength: Boolean): Boolean {
  if (exactLength && size != expected.size) return false
  if (size < expected.size) return false
  return expected.indices.all { i ->
    (this[i].toInt() and mask[i].toInt()) == (expected[i].toInt() and mask[i].toInt())
  }
}

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/** Like [hex] but shows `**` for wildcard bytes (mask byte == 0). */
fun ByteArray.hex(mask: ByteArray): String =
  indices.joinToString("") { i -> if (mask[i] == 0.toByte()) "**" else "%02x".format(this[i]) }
