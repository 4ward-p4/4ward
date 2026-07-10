package fourward.e2e.bmv2

import fourward.stf.hex
import org.junit.Assert

/**
 * Asserts that 4ward and BMv2 produced the same set of output packets.
 *
 * Outputs are sorted by (port, payload hex) before comparison so cross-port ordering differences
 * — unspecified by both simulators — don't cause spurious failures.
 */
fun assertOutputsMatch(
  fourwardOutputs: List<Pair<Int, ByteArray>>,
  bmv2Outputs: List<Pair<Int, ByteArray>>,
) {
  val sortKey: (Pair<Int, ByteArray>) -> String = { (port, payload) ->
    "%04d:%s".format(port, payload.hex())
  }
  val fourwardSorted = fourwardOutputs.sortedBy(sortKey)
  val bmv2Sorted = bmv2Outputs.sortedBy(sortKey)

  val mismatches = mutableListOf<String>()
  if (fourwardSorted.size != bmv2Sorted.size) {
    mismatches.add("Output count mismatch: 4ward=${fourwardSorted.size}, bmv2=${bmv2Sorted.size}")
  }
  val n = minOf(fourwardSorted.size, bmv2Sorted.size)
  for (i in 0 until n) {
    val (fPort, fPayload) = fourwardSorted[i]
    val (bPort, bPayload) = bmv2Sorted[i]
    if (fPort != bPort || !fPayload.contentEquals(bPayload)) {
      mismatches.add(
        "Output $i differs:\n" +
          "  4ward: port=$fPort payload=${fPayload.hex()}\n" +
          "  bmv2:  port=$bPort payload=${bPayload.hex()}"
      )
    }
  }

  if (mismatches.isNotEmpty()) {
    Assert.fail(mismatches.joinToString("\n"))
  }
}
