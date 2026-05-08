package fourward.cli

import com.google.protobuf.TextFormat
import com.google.protobuf.util.JsonFormat
import fourward.simulator.Simulator
import fourward.stf.ReceivedPacket
import fourward.stf.StfFile
import fourward.stf.appendBestOutcome
import fourward.stf.installStfEntries
import fourward.stf.loadPipelineConfig
import fourward.stf.matchOutputAgainstExpects
import java.io.FileNotFoundException
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/**
 * `4ward sim pipeline.txtpb test.stf` — run an STF test against a pre-compiled pipeline.
 *
 * Loads the pipeline, installs table entries, sends packets, verifies expectations, and prints the
 * trace tree for each packet. Returns an exit code (does not call `exitProcess`).
 */
fun simulate(pipelinePath: Path, stfPath: Path, format: OutputFormat, dropPort: Int? = null): Int {
  val config =
    try {
      loadPipelineConfig(pipelinePath)
    } catch (e: FileNotFoundException) {
      System.err.println("error: ${e.message}")
      return ExitCode.USAGE_ERROR
    } catch (e: NoSuchFileException) {
      System.err.println("error: $pipelinePath: no such file")
      return ExitCode.USAGE_ERROR
    }
  val stf =
    try {
      StfFile.parse(stfPath)
    } catch (e: FileNotFoundException) {
      System.err.println("error: ${e.message}")
      return ExitCode.USAGE_ERROR
    } catch (e: NoSuchFileException) {
      System.err.println("error: $stfPath: no such file")
      return ExitCode.USAGE_ERROR
    }
  val sim = Simulator(dropPort)

  try {
    sim.loadPipeline(config)
  } catch (e: IllegalArgumentException) {
    System.err.println("error: failed to load pipeline: ${e.message}")
    return ExitCode.INTERNAL_ERROR
  }

  try {
    installStfEntries(sim, stf, config.p4Info)
  } catch (e: IllegalStateException) {
    System.err.println("error: ${e.message}")
    return ExitCode.INTERNAL_ERROR
  }

  val outputQueue = mutableListOf<ReceivedPacket>()
  val textProtoPrinter = TextFormat.printer()
  val jsonPrinter = JsonFormat.printer().preservingProtoFieldNames()

  for (packet in stf.packets) {
    val result = sim.processPacket(packet.ingressPort, packet.payload)
    when (format) {
      OutputFormat.HUMAN -> {
        println("packet received: port ${packet.ingressPort}, ${packet.payload.size} bytes")
        println(TraceFormatter.format(result.trace).trim().prependIndent("  "))
      }
      OutputFormat.TEXTPROTO -> {
        print(TEXTPROTO_HEADER)
        print(textProtoPrinter.printToString(result.trace))
      }
      OutputFormat.JSON -> {
        print(JSON_HEADER)
        println(jsonPrinter.print(result.trace))
      }
    }
    appendBestOutcome(result.possibleOutcomes, stf.expects, outputQueue)
  }

  val failures = matchOutputAgainstExpects(stf.expects, outputQueue)
  if (failures.isNotEmpty()) {
    System.err.println("FAIL")
    for (f in failures) System.err.println("  $f")
    return ExitCode.TEST_FAILURE
  }
  println("PASS")
  return ExitCode.SUCCESS
}

private const val PROTO_FILE = "@fourward//simulator/simulator.proto"
private const val PROTO_MESSAGE = "fourward.TraceTree"
private const val TEXTPROTO_HEADER = "# proto-file: $PROTO_FILE\n# proto-message: $PROTO_MESSAGE\n"
private const val JSON_HEADER = "// proto-file: $PROTO_FILE\n// proto-message: $PROTO_MESSAGE\n"
