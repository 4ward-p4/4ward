package fourward.simulator

import fourward.TraceEvent
import fourward.Type

/**
 * Describes the extern being called — either a free function or an object method.
 *
 * P4 externs come in two forms: free functions (`mark_to_drop(sm)`, dispatched via `__call__`) and
 * object methods (`my_reg.read(dst, idx)`, dispatched via the method name). The interpreter creates
 * the appropriate variant; the [ExternHandler] pattern-matches on it.
 */
sealed class ExternCall {
  /** A free-function extern call, e.g. `mark_to_drop(sm)` or `hash(...)`. */
  data class FreeFunction(val name: String) : ExternCall()

  /**
   * A method call on an extern object instance, e.g. `my_reg.read(dst, idx)`.
   *
   * @property externType the declared extern type name (e.g. "register", "counter",
   *   "direct_meter").
   * @property instanceName the P4 instance name (e.g. "my_reg").
   * @property method the method name (e.g. "read", "write", "count").
   */
  data class Method(val externType: String, val instanceName: String, val method: String) :
    ExternCall()
}

/**
 * Callback interface the interpreter provides to extern handlers for evaluating arguments and
 * writing back out/inout parameters.
 *
 * Each extern call creates a fresh [ExternEvaluator] bound to that call's arguments and the current
 * interpreter state. The architecture's [ExternHandler] uses it to read arguments by index, write
 * back out-params, and emit trace events — without needing direct access to interpreter internals.
 */
interface ExternEvaluator {
  /**
   * Returns the IR return type of the extern call (for non-void methods like PSA register.read).
   */
  fun returnType(): Type

  /** Returns the number of arguments passed to this extern call. */
  fun argCount(): Int

  /** Evaluates argument [index] and returns its runtime value. */
  fun evalArg(index: Int): Value

  /** Returns the IR type of argument [index]. */
  fun argType(index: Int): Type

  /** Writes [value] back to the lvalue at argument [index] (for out/inout params). */
  fun writeOutArg(index: Int, value: Value)

  /** Returns the zero/default value for [type] (e.g. for uninitialized register reads). */
  fun defaultValue(type: Type): Value

  /**
   * Builds a [TraceEvent.Builder] pre-populated with the current statement's source info.
   *
   * Extern handlers should use this instead of constructing [TraceEvent.newBuilder] directly, so
   * that trace events carry accurate source location information.
   */
  fun traceEventBuilder(): TraceEvent.Builder

  /** Appends a trace event to the current packet's trace. */
  fun addTraceEvent(event: TraceEvent)

  /** Peeks at remaining unparsed input bytes (for `_with_payload` checksum variants). */
  fun peekRemainingInput(): ByteArray

  /**
   * Returns context from the most recent table miss, or null if no table miss has occurred.
   *
   * Used by PNA's `add_entry` extern to determine which table to insert into and what match key
   * values to use. The interpreter records this whenever a table lookup results in a miss.
   */
  fun lastTableMiss(): TableMissContext? = null
}

/**
 * Records the table name and match key values from a table lookup that resulted in a miss.
 *
 * PNA's `add_entry` extern uses this to insert entries into the table that triggered the miss,
 * using the same match key values that caused the miss.
 */
data class TableMissContext(val tableName: String, val keyValues: List<Pair<String, Value>>)

/**
 * Architecture-provided handler for extern functions and extern object methods.
 *
 * The interpreter delegates all extern calls — free functions (e.g. `mark_to_drop`) and object
 * methods (e.g. `register.read`) — to a single [handle] method. Each P4 architecture provides its
 * own implementation covering its extern library.
 */
fun interface ExternHandler {
  /**
   * Executes the extern described by [call] using [eval] to access arguments.
   *
   * @return the call's result value ([UnitVal] for void externs).
   * @throws IllegalStateException for unrecognised externs.
   */
  fun handle(call: ExternCall, eval: ExternEvaluator): Value
}
