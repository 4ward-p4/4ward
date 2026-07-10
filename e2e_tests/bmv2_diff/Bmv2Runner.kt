package fourward.e2e.bmv2

import fourward.stf.MatchKind
import fourward.stf.StfAddEntry
import fourward.stf.StfFile
import fourward.stf.StfGroupDirective
import fourward.stf.StfMatchField
import fourward.stf.StfMemberDirective
import fourward.stf.StfSetDefault
import fourward.stf.StfTableDirective
import fourward.stf.allOnesMask
import fourward.stf.decodeHex
import fourward.stf.encodeValue
import fourward.stf.extractParamName
import fourward.stf.findAction
import fourward.stf.findActionProfile
import fourward.stf.findTable
import fourward.stf.hex
import fourward.stf.stripNamedParamPrefix
import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import p4.config.v1.P4InfoOuterClass

/**
 * Drives a BMv2 simple_switch subprocess via the bmv2_driver binary.
 *
 * Translates STF directives into the driver's line-based command protocol. Uses P4Info (from the
 * 4ward PipelineConfig) to resolve field names and encode match/action values with correct
 * bitwidths.
 */
class Bmv2Runner(driverBinary: Path, jsonPath: Path, private val p4Info: P4InfoOuterClass.P4Info) :
  Closeable {
  private val process: Process
  private val writer: java.io.BufferedWriter
  private val reader: java.io.BufferedReader

  /** Action selector groups installed by [installEntries], used for round-robin exploration. */
  private val selectorGroups = mutableListOf<SelectorGroup>()

  init {
    process =
      ProcessBuilder(driverBinary.toString(), jsonPath.toString())
        .redirectErrorStream(false)
        .start()
    writer = process.outputStream.bufferedWriter()
    reader = process.inputStream.bufferedReader()
    val ready = reader.readLine()
    check(ready == "READY") { "Expected READY from bmv2_driver, got: $ready" }
  }

  private fun sendCommand(cmd: String): List<String> {
    writer.write(cmd)
    writer.newLine()
    writer.flush()
    val lines = mutableListOf<String>()
    var done = false
    while (!done) {
      val line = reader.readLine()
      if (line == null) {
        done = true
      } else {
        lines.add(line)
        done = line.startsWith("OK") || line.startsWith("ERROR") || line == "DONE"
      }
    }
    return lines
  }

  private fun sendChecked(cmd: String): List<String> {
    val resp = sendCommand(cmd)
    check(resp.lastOrNull()?.startsWith("OK") == true) { "$cmd failed: ${resp.joinToString("\n")}" }
    return resp
  }

  /** Sends a command that returns a handle on success (e.g. "OK 3"), returns the handle. */
  private fun sendForHandle(cmd: String): Int =
    sendChecked(cmd).last().removePrefix("OK ").trim().toInt()

  /** Install all STF-declared entries (PRE + table entries). Throws on failure. */
  fun installEntries(stf: StfFile) {
    for (mirror in stf.pre.mirroringAdds) {
      sendChecked("MIRRORING_ADD ${mirror.sessionId} ${mirror.egressPort}")
    }

    // Create nodes before groups (BMv2 assigns sequential handles starting at 0).
    for (node in stf.pre.mcNodeCreates) {
      sendChecked("MC_NODE_CREATE ${node.rid} ${node.ports.joinToString(" ")}")
    }
    for (group in stf.pre.mcGroupCreates) {
      sendChecked("MC_MGRP_CREATE ${group.groupId}")
    }
    for (assoc in stf.pre.mcNodeAssociates) {
      sendChecked("MC_NODE_ASSOCIATE ${assoc.groupId} ${assoc.nodeHandle}")
    }

    // Action profile members and groups must be installed before table entries
    // that reference them. BMv2 auto-assigns handles sequentially; we track the
    // mapping from STF IDs to BMv2 handles so references resolve correctly.
    val memberHandles = mutableMapOf<Int, Int>()
    for (member in stf.memberDirectives) {
      memberHandles[member.memberId] = sendForHandle(translateMember(member))
    }
    val groupHandles = mutableMapOf<Int, Int>()
    for (group in stf.groupDirectives) {
      val grpHandle = sendForHandle(translateCreateGroup(group))
      groupHandles[group.groupId] = grpHandle
      val profileName = findActionProfileName(group.profileName)
      val mbrHandles = mutableListOf<Int>()
      for (memberId in group.memberIds) {
        val mbrHandle = memberHandles[memberId] ?: error("unknown member: $memberId")
        sendChecked("ACT_PROF_ADD_MEMBER_TO_GROUP $profileName $mbrHandle $grpHandle")
        mbrHandles.add(mbrHandle)
      }
      selectorGroups.add(SelectorGroup(profileName, grpHandle, mbrHandles))
    }

    for (directive in stf.tableEntries) {
      sendChecked(translateTableEntry(directive, groupHandles))
    }
  }

  /** Inject a packet and collect output packets from BMv2. */
  fun sendPacket(port: Int, payload: ByteArray): List<Pair<Int, ByteArray>> {
    val resp = sendCommand("PACKET $port ${payload.hex()}")
    return resp
      .filter { it.startsWith("OUTPUT ") }
      .map { line ->
        val parts = line.split(" ", limit = 3)
        parts[1].toInt() to parts[2].decodeHex()
      }
  }

  /**
   * Sends a packet once per member in the action selector group, collecting all outputs.
   *
   * For each iteration, temporarily reduces the group to a single member (so BMv2's hash always
   * selects it), sends the packet, collects output, then restores the group. The collected outputs
   * across all iterations match 4ward's forked exploration of the selector.
   *
   * Only supports a single action selector group. Falls back to [sendPacket] if no groups exist.
   */
  fun sendPacketExploring(port: Int, payload: ByteArray): List<Pair<Int, ByteArray>> {
    if (selectorGroups.isEmpty()) return sendPacket(port, payload)
    check(selectorGroups.size == 1) {
      "round-robin exploration only supports a single action selector group"
    }
    val group = selectorGroups[0]
    val allOutputs = mutableListOf<Pair<Int, ByteArray>>()
    for (activeIdx in group.memberHandles.indices) {
      // Remove all members except the active one.
      for ((i, mbrHandle) in group.memberHandles.withIndex()) {
        if (i != activeIdx) {
          sendChecked(
            "ACT_PROF_REMOVE_MEMBER_FROM_GROUP ${group.profileName} $mbrHandle ${group.grpHandle}"
          )
        }
      }
      allOutputs.addAll(sendPacket(port, payload))
      // Restore removed members.
      for ((i, mbrHandle) in group.memberHandles.withIndex()) {
        if (i != activeIdx) {
          sendChecked(
            "ACT_PROF_ADD_MEMBER_TO_GROUP ${group.profileName} $mbrHandle ${group.grpHandle}"
          )
        }
      }
    }
    return allOutputs
  }

  // -- Table entry translation --

  private fun translateTableEntry(
    directive: StfTableDirective,
    groupHandles: Map<Int, Int>,
  ): String =
    when (directive) {
      is StfAddEntry -> translateAdd(directive, groupHandles)
      is StfSetDefault -> translateSetDefault(directive)
    }

  @Suppress("CyclomaticComplexMethod")
  private fun translateAdd(entry: StfAddEntry, groupHandles: Map<Int, Int>): String {
    val table = findTable(entry.tableName, p4Info)

    // Encode match fields in P4Info table key order.
    val matchParts =
      table.matchFieldsList.map { mf ->
        encodeMatchForDriver(findStfMatchOrNull(entry, mf), mf, entry.tableName)
      }

    // Indirect table entry pointing to a group (action selector with group=N).
    if (entry.groupId != null) {
      val grpHandle = groupHandles[entry.groupId] ?: error("unknown group: ${entry.groupId}")
      return buildString {
        append("TABLE_ADD_GROUP ${table.preamble.name} $grpHandle")
        for (m in matchParts) append(" $m")
        if (entry.priority != null) append(" priority ${entry.priority}")
      }
    }

    val action = findAction(entry.actionName, p4Info)
    val paramParts = encodeActionParams(entry.actionParams, action)

    return buildString {
      append("TABLE_ADD ${table.preamble.name} ${action.preamble.name}")
      for (m in matchParts) append(" $m")
      append(" =>")
      for (p in paramParts) append(" $p")
      if (entry.priority != null) append(" priority ${entry.priority}")
    }
  }

  private fun translateSetDefault(entry: StfSetDefault): String {
    val table = findTable(entry.tableName, p4Info)
    val action = findAction(entry.actionName, p4Info)
    val paramParts = encodeActionParams(entry.actionParams, action)

    return buildString {
      append("TABLE_SET_DEFAULT ${table.preamble.name} ${action.preamble.name}")
      for (p in paramParts) append(" $p")
    }
  }

  // -- Action profile translation --

  private fun translateMember(member: StfMemberDirective): String {
    val profileName = findActionProfileName(member.profileName)
    val action = findAction(member.actionName, p4Info)
    val paramParts = encodeNamedParams(member.params, action)
    return buildString {
      append("ACT_PROF_ADD_MEMBER $profileName ${action.preamble.name}")
      for (p in paramParts) append(" $p")
    }
  }

  private fun translateCreateGroup(group: StfGroupDirective): String =
    "ACT_PROF_CREATE_GROUP ${findActionProfileName(group.profileName)}"

  /** Resolves an STF profile name to the BMv2-visible (p4info) name. */
  private fun findActionProfileName(stfName: String): String =
    findActionProfile(stfName, p4Info).preamble.name

  /**
   * Encodes named params (from member directives) directly, without round-tripping through strings.
   */
  private fun encodeNamedParams(
    params: Map<String, String>,
    action: P4InfoOuterClass.Action,
  ): List<String> =
    action.paramsList.map { paramInfo ->
      val raw = params[paramInfo.name] ?: error("missing ${paramInfo.name}")
      encodeValue(raw, paramInfo.bitwidth).toByteArray().hex()
    }

  private fun encodeActionParams(
    stfParams: List<String>,
    action: P4InfoOuterClass.Action,
  ): List<String> =
    action.paramsList.mapIndexed { i, paramInfo ->
      val raw =
        stfParams.find { it.extractParamName() == paramInfo.name }
          ?: stfParams.getOrNull(i)
          ?: error("missing param ${paramInfo.name} for action ${action.preamble.name}")
      encodeValue(raw.stripNamedParamPrefix(), paramInfo.bitwidth).toByteArray().hex()
    }

  /** Find the STF match field corresponding to a P4Info match field, or null if not found. */
  private fun findStfMatchOrNull(
    entry: StfAddEntry,
    mf: P4InfoOuterClass.MatchField,
  ): StfMatchField? =
    entry.matches.find { stf ->
      val norm = stf.fieldName.replace(ARRAY_INDEX_REGEX, "[$1]")
      mf.name == stf.fieldName ||
        mf.name == norm ||
        mf.name.substringAfter(".") == stf.fieldName ||
        mf.name.substringAfter(".") == norm
    }

  // Encode a match field for the bmv2_driver command format. Accepts null for absent OPTIONAL
  // fields: BMv2 compiles P4 OPTIONAL to ternary, and a wildcard OPTIONAL is all-zeros value +
  // all-zeros mask (don't-care ternary).
  private fun encodeMatchForDriver(
    stf: StfMatchField?,
    mf: P4InfoOuterClass.MatchField,
    tableName: String,
  ): String {
    if (stf == null) {
      check(mf.matchType == P4InfoOuterClass.MatchField.MatchType.OPTIONAL) {
        "no STF match for P4Info field '${mf.name}' in table '$tableName'"
      }
      val zero = "00".repeat((mf.bitwidth + 7) / 8)
      return "$zero&&&$zero"
    }
    val valueHex = encodeValue(stf.value, mf.bitwidth).toByteArray().hex()
    return when (stf.kind) {
      MatchKind.LPM -> "$valueHex/${stf.prefixLen}"
      MatchKind.TERNARY -> {
        val mask = stf.mask
        val maskHex =
          if (mask != null) encodeValue(mask, mf.bitwidth).toByteArray().hex()
          else allOnesMask(mf.bitwidth)
        "$valueHex&&&$maskHex"
      }
      MatchKind.EXACT -> {
        when (mf.matchType) {
          // If the table declares ternary/lpm but the STF value is exact, promote.
          P4InfoOuterClass.MatchField.MatchType.TERNARY -> "$valueHex&&&${allOnesMask(mf.bitwidth)}"
          P4InfoOuterClass.MatchField.MatchType.LPM -> "$valueHex/${mf.bitwidth}"
          else -> valueHex
        }
      }
    }
  }

  @Suppress("MagicNumber")
  override fun close() {
    writer.close()
    if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
  }

  private data class SelectorGroup(
    val profileName: String,
    val grpHandle: Int,
    val memberHandles: List<Int>,
  )

  companion object {
    private val ARRAY_INDEX_REGEX = Regex("\\$(\\d+)")
  }
}
