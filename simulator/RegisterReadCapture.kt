package fourward.simulator

import java.math.BigInteger

data class RegisterSeedDependency(val registerName: String, val index: Int, val initialValue: Value)

class CapturedRegisterSeeds<out T>
internal constructor(val result: T, val dependencies: List<RegisterSeedDependency>)

private data class RegisterReadKey(val registerName: String, val index: Int)

/**
 * Captures register state needed to replay one packet.
 *
 * Reproducers need the value that existed when packet processing began, not necessarily the value
 * observed by a later read after packet-local writes. We avoid copying the whole register array by
 * remembering the first value overwritten by each write and using that when the same index is later
 * read.
 */
internal class RegisterReadCapture {
  private val initialValues = mutableMapOf<RegisterReadKey, Value?>()
  private val dependencies = linkedMapOf<RegisterReadKey, RegisterSeedDependency>()

  fun rememberInitial(registerName: String, index: Int, value: Value?) {
    val key = RegisterReadKey(registerName, index)
    if (key !in initialValues) {
      initialValues[key] = value
    }
  }

  fun recordRead(registerName: String, index: Int, currentValue: Value?) {
    val key = RegisterReadKey(registerName, index)
    val initialValue = if (key in initialValues) initialValues[key] else currentValue
    if (initialValue == null) return
    if ((initialValue as? BitVal)?.bits?.value == BigInteger.ZERO) return
    dependencies.putIfAbsent(key, RegisterSeedDependency(registerName, index, initialValue))
  }

  fun dependencies(): List<RegisterSeedDependency> = dependencies.values.toList()
}
