package fourward.simulator

import com.google.protobuf.ByteString
import fourward.BehavioralConfig
import fourward.ExternInstanceDecl
import fourward.PipelineStage
import fourward.PipelineStageEvent
import fourward.TraceTree
import fourward.TypeDecl
import java.math.BigInteger
import java.util.concurrent.ThreadLocalRandom
import p4.v1.P4RuntimeOuterClass

/** Simplified parameter descriptor: just name and type. */
internal data class BlockParam(val name: String, val typeName: String)

internal fun randomInInclusiveRange(lo: BigInteger, hi: BigInteger): BigInteger {
  if (hi <= lo) return lo
  val rangeSize = hi - lo + BigInteger.ONE
  var offset: BigInteger
  do {
    offset = BigInteger(rangeSize.bitLength(), ThreadLocalRandom.current())
  } while (offset >= rangeSize)
  return lo + offset
}

/**
 * Reads the egress port from a [P4RuntimeOuterClass.Replica], supporting both `port` (bytes,
 * P4Runtime v1.4+) and the deprecated `egress_port` (uint32). On the per-packet hot path; written
 * as a plain loop to avoid the IntRange + lambda allocation of a `fold`.
 */
@Suppress("DEPRECATION")
fun replicaPort(replica: P4RuntimeOuterClass.Replica): Int {
  val port = replica.port
  if (port.isEmpty) return replica.egressPort
  var acc = 0
  for (i in 0 until port.size()) acc = (acc shl 8) or (port.byteAt(i).toInt() and 0xFF)
  return acc
}

/** Inverse of [replicaPort]: encodes [port] as a minimum-width big-endian [ByteString]. */
fun portToBytes(port: Int): ByteString {
  val bytes = ByteArray(4) { i -> (port shr ((3 - i) * 8) and 0xFF).toByte() }
  val firstNonZero = bytes.indexOfFirst { it != 0.toByte() }
  val start = if (firstNonZero < 0) 3 else firstNonZero
  return ByteString.copyFrom(bytes, start, bytes.size - start)
}

/** Architecture-level packet I/O types that are not user-visible parameters. */
internal val IO_TYPES = setOf("packet_in", "packet_out")

/** Guard against infinite recirculation loops. */
internal const val MAX_RECIRCULATIONS = 16

/**
 * Resolves a named pipeline stage; throws with [archName] context if missing or has no block name.
 * Used by architecture constructors to fail fast on misconfigured pipelines.
 */
internal fun resolveStage(
  config: BehavioralConfig,
  archName: String,
  stageName: String,
): PipelineStage =
  config.architecture.stagesList
    .first { it.name == stageName }
    .also {
      require(it.blockName.isNotEmpty()) { "$archName stage '$stageName' has no block name" }
    }

/** Builds a map from block name to parameter list across all parsers and controls. */
internal fun buildBlockParamsMap(config: BehavioralConfig): Map<String, List<BlockParam>> {
  val result = mutableMapOf<String, List<BlockParam>>()
  for (parser in config.parsersList) {
    result[parser.name] =
      parser.paramsList
        .filter { it.type.hasNamed() && it.type.named !in IO_TYPES }
        .map { BlockParam(it.name, it.type.named) }
  }
  for (control in config.controlsList) {
    result[control.name] =
      control.paramsList
        .filter { it.type.hasNamed() && it.type.named !in IO_TYPES }
        .map { BlockParam(it.name, it.type.named) }
  }
  return result
}

/**
 * Creates default values for all named types referenced by parser/control parameters.
 *
 * Returns a mutable map keyed by type name. The same value instance is shared across all parameters
 * of that type (e.g., `headers` is shared between parser `parsed_hdr` and control `hdr`), so
 * mutations in one stage are visible to subsequent stages.
 */
internal fun createDefaultValues(
  config: BehavioralConfig,
  typesByName: Map<String, TypeDecl>,
): MutableMap<String, Value> {
  val values = mutableMapOf<String, Value>()
  for (parser in config.parsersList) {
    for (param in parser.paramsList) {
      if (param.type.hasNamed() && param.type.named !in IO_TYPES) {
        values.getOrPut(param.type.named) { defaultValue(param.type.named, typesByName) }
      }
    }
  }
  for (control in config.controlsList) {
    for (param in control.paramsList) {
      if (param.type.hasNamed() && param.type.named !in IO_TYPES) {
        values.getOrPut(param.type.named) { defaultValue(param.type.named, typesByName) }
      }
    }
  }
  return values
}

/** Builds a flat map from extern instance name to declaration across all parsers and controls. */
internal fun buildExternInstancesMap(config: BehavioralConfig): Map<String, ExternInstanceDecl> =
  (config.parsersList.flatMap { it.externInstancesList } +
      config.controlsList.flatMap { it.externInstancesList })
    .associateBy { it.name }

/**
 * Binds a stage's parameters in the environment, mapping each param name to the shared value for
 * its type.
 *
 * PSA/PNA stages reuse parameter names (e.g. `istd`) with different types, so parameters must be
 * re-bound before each stage.
 */
internal fun bindStageParams(
  env: Environment,
  blockName: String,
  blockParams: Map<String, List<BlockParam>>,
  valueByType: Map<String, Value>,
) {
  for (param in blockParams[blockName] ?: emptyList()) {
    valueByType[param.typeName]?.let { env.define(param.name, it) }
  }
}

/** Runs a parser stage, recording trace events and routing parser errors to [onParserError]. */
internal fun runParserStage(
  interpreter: Interpreter.Execution,
  ctx: PacketContext,
  env: Environment,
  stage: PipelineStage,
  onParserError: (ParserErrorException) -> Unit,
) {
  ctx.addTraceEvent(stageEvent(stage, PipelineStageEvent.Direction.ENTER))
  try {
    interpreter.runParser(stage.blockName, env)
  } catch (e: ParserErrorException) {
    onParserError(e)
  } finally {
    ctx.addTraceEvent(stageEvent(stage, PipelineStageEvent.Direction.EXIT))
  }
}

/** Runs a control stage, recording trace events. An `exit` statement terminates only the stage. */
internal fun runControlStage(
  interpreter: Interpreter.Execution,
  ctx: PacketContext,
  env: Environment,
  stage: PipelineStage,
) {
  ctx.addTraceEvent(stageEvent(stage, PipelineStageEvent.Direction.ENTER))
  try {
    interpreter.runControl(stage.blockName, env)
  } catch (_: ExitException) {
    // exit terminates the current control, but the pipeline continues.
  } finally {
    ctx.addTraceEvent(stageEvent(stage, PipelineStageEvent.Direction.EXIT))
  }
}

/**
 * Handles an [ActionSelectorFork] by re-executing the pipeline segment for each group member.
 *
 * On the first encounter with an action selector group hit, the [Interpreter] throws
 * [ActionSelectorFork] with the list of group members and the trace events accumulated before the
 * fork point. This method builds a [fourward.Choice] [TraceTree] with one alternative per member,
 * re-executing via [reExecute] with the forced member selection added to [currentSelectors].
 *
 * The re-execution produces identical events up to the fork point (deterministic replay), so the
 * prefix is safely stripped from each alternative's trace.
 */
internal fun handleActionSelectorFork(
  fork: ActionSelectorFork,
  currentSelectors: Map<String, Int>,
  reExecute: (Map<String, Int>) -> TraceTree,
): TraceTree {
  val prefixLength = fork.eventsBeforeFork.size
  val alternatives =
    fork.members.map { member ->
      val newSelectors = currentSelectors + (fork.tableName to member.memberId)
      val subtree = reExecute(newSelectors)
      val stripped =
        TraceTree.newBuilder()
          .addAllEvents(subtree.eventsList.drop(prefixLength))
          .copyOutcome(subtree)
      fourward.Alternative.newBuilder()
        .setMemberId(member.memberId)
        .setSubtree(stripped.build())
        .build()
    }
  return buildChoiceTree(fork.eventsBeforeFork, alternatives)
}

/**
 * Handles extern methods shared across PSA and PNA: Register, Random, Counter, Hash, Meter,
 * InternetChecksum, Digest, and Checksum.
 *
 * Returns the result [Value], or `null` if [call] is not a common extern method (the caller should
 * handle it as architecture-specific).
 */
@Suppress("LongParameterList")
internal fun handleCommonExternMethod(
  call: ExternCall.Method,
  eval: ExternEvaluator,
  tableStore: TableStore,
  externInstances: Map<String, ExternInstanceDecl>,
  checksumState: MutableMap<String, BigInteger>,
  hashAlgorithms: Map<String, String>,
): Value? =
  when (call.method) {
    "read" ->
      when (call.externType) {
        "Register" -> {
          val index = (eval.evalArg(0) as BitVal).bits.value.toInt()
          tableStore.registerRead(call.instanceName, index) ?: eval.defaultValue(eval.returnType())
        }
        "Random" -> {
          val instance = externInstances[call.instanceName]
          val lo = instance?.constructorArgsList?.getOrNull(0)?.literal?.integer ?: 0L
          val hi = instance?.constructorArgsList?.getOrNull(1)?.literal?.integer ?: 0L
          BitVal(
            BitVector(
              randomInInclusiveRange(BigInteger.valueOf(lo), BigInteger.valueOf(hi)),
              eval.returnType().bit.width,
            )
          )
        }
        else -> null
      }
    "write" -> {
      val index = (eval.evalArg(0) as BitVal).bits.value.toInt()
      tableStore.registerWrite(call.instanceName, index, eval.evalArg(1))
      UnitVal
    }
    "count" -> {
      if (call.externType == "Counter") {
        val index = (eval.evalArg(0) as BitVal).bits.value.toInt()
        tableStore.counterIncrement(call.instanceName, index, eval.ingressPacketLengthBytes())
      }
      UnitVal
    }
    "get_hash" -> evalGetHash(call, eval, externInstances, hashAlgorithms)
    "execute" -> EnumVal("GREEN")
    "clear" -> {
      checksumState[call.instanceName] = BigInteger.ZERO
      UnitVal
    }
    "add" -> {
      val data = eval.evalArg(0).asStructVal()
      val sum = checksumState.getOrDefault(call.instanceName, BigInteger.ZERO)
      checksumState[call.instanceName] = onesComplementAdd(sum, sumWords(data))
      UnitVal
    }
    "subtract" -> {
      // RFC 1624: subtract by adding the ones' complement of the data's word sum.
      val data = eval.evalArg(0).asStructVal()
      val sum = checksumState.getOrDefault(call.instanceName, BigInteger.ZERO)
      val dataSumComplement = CSUM_MASK.subtract(sumWords(data))
      checksumState[call.instanceName] = onesComplementAdd(sum, dataSumComplement)
      UnitVal
    }
    "get" -> {
      val sum = checksumState.getOrDefault(call.instanceName, BigInteger.ZERO)
      BitVal(BitVector(CSUM_MASK.subtract(sum), CSUM_WORD_BITS))
    }
    "get_state" -> {
      val sum = checksumState.getOrDefault(call.instanceName, BigInteger.ZERO)
      BitVal(BitVector(sum, CSUM_WORD_BITS))
    }
    "set_state" -> {
      checksumState[call.instanceName] = (eval.evalArg(0) as BitVal).bits.value
      UnitVal
    }
    "pack" -> UnitVal
    // PNA's Checksum<W> extern uses `update` (same semantics as InternetChecksum `add`).
    "update" -> {
      val data = eval.evalArg(0).asStructVal()
      val sum = checksumState.getOrDefault(call.instanceName, BigInteger.ZERO)
      checksumState[call.instanceName] = onesComplementAdd(sum, sumWords(data))
      UnitVal
    }
    else -> null
  }
