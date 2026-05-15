package fourward.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/** Output format for trace trees. */
enum class OutputFormat {
  HUMAN,
  TEXTPROTO,
  JSON,
}

/** Thrown on invalid CLI arguments. Caught by [main] and reported as a usage error. */
private class UsageError(message: String) : RuntimeException(message)

private const val USAGE =
  """Usage: 4ward <command> [options]

Commands:
  compile  program.p4 -o output.txtpb   Compile a P4 program to a pipeline config.
  sim      pipeline.txtpb test.stf       Run an STF test against a compiled pipeline.
  run      program.p4 test.stf           Compile and simulate in one step.

Options:
  --format=human|textproto|json   Trace output format (default: human).
  --help                     Show this help message."""

private const val SIM_USAGE =
  """Usage: 4ward sim [--format=human|textproto|json] [--drop-port=N] <pipeline.txtpb> <test.stf>

Loads a compiled pipeline config and runs an STF test against it.
Prints the trace tree for each packet and reports PASS/FAIL.

Options:
  --format=human|textproto|json   Trace output format (default: human).
  --drop-port=N              Override the drop port (default: derived from port width)."""

private const val COMPILE_USAGE =
  """Usage: 4ward compile [options] <program.p4>

Compiles a P4 program to a pipeline config (text-format protobuf).

Options:
  -o <path>            Output file (default: <program>.txtpb).
  -I <dir>             Add include directory for P4 headers."""

private const val RUN_USAGE =
  """Usage: 4ward run [--format=human|textproto|json] [--drop-port=N] <program.p4> <test.stf>

Compiles a P4 program and runs an STF test against it in one step.

Options:
  --format=human|textproto|json   Trace output format (default: human).
  --drop-port=N              Override the drop port (default: derived from port width)."""

fun main(args: Array<String>) {
  if (args.isEmpty() || args[0] == "--help" || args[0] == "-h") {
    println(USAGE)
    exitProcess(if (args.isEmpty()) ExitCode.USAGE_ERROR else ExitCode.SUCCESS)
  }

  val exitCode =
    try {
      when (val command = args[0]) {
        "sim" -> handleSim(args.drop(1))
        "compile" -> handleCompile(args.drop(1))
        "run" -> handleRun(args.drop(1))
        else -> throw UsageError("unknown command '$command'\n$USAGE")
      }
    } catch (e: UsageError) {
      System.err.println("error: ${e.message}")
      ExitCode.USAGE_ERROR
    }
  exitProcess(exitCode)
}

private fun handleSim(args: List<String>): Int {
  if (args.any { it == "--help" || it == "-h" }) {
    println(SIM_USAGE)
    return ExitCode.SUCCESS
  }

  var format = OutputFormat.HUMAN
  var dropPort: Int? = null
  val positional = mutableListOf<String>()

  for (arg in args) {
    when {
      arg.startsWith("--format=") -> format = parseFormat(arg)
      arg.startsWith("--drop-port=") -> dropPort = parseIntFlag(arg, "--drop-port")
      arg.startsWith("-") && arg != "-" -> throw UsageError("unknown option '$arg'")
      else -> positional += arg
    }
  }

  if (positional.size != 2) {
    throw UsageError("'sim' requires exactly 2 arguments: <pipeline.txtpb> <test.stf>\n$SIM_USAGE")
  }

  return simulate(resolveUserPath(positional[0]), stfPath(positional[1]), format, dropPort)
}

private fun handleCompile(args: List<String>): Int {
  if (args.any { it == "--help" || it == "-h" }) {
    println(COMPILE_USAGE)
    return ExitCode.SUCCESS
  }

  var outputPath: String? = null
  val includeDirs = mutableListOf<String>()
  val positional = mutableListOf<String>()

  var i = 0
  while (i < args.size) {
    when {
      args[i] == "-o" -> {
        i++
        if (i >= args.size) throw UsageError("-o requires an argument")
        outputPath = args[i]
      }
      args[i] == "-I" -> {
        i++
        if (i >= args.size) throw UsageError("-I requires an argument")
        includeDirs += args[i]
      }
      args[i].startsWith("-") -> throw UsageError("unknown option '${args[i]}'")
      else -> positional += args[i]
    }
    i++
  }

  if (positional.size != 1) {
    throw UsageError("'compile' requires exactly 1 argument: <program.p4>\n$COMPILE_USAGE")
  }

  return compile(
    resolveUserPath(positional[0]),
    outputPath?.let { resolveUserPath(it) },
    includeDirs.map { resolveUserPath(it) },
  )
}

private fun handleRun(args: List<String>): Int {
  if (args.any { it == "--help" || it == "-h" }) {
    println(RUN_USAGE)
    return ExitCode.SUCCESS
  }

  var format = OutputFormat.HUMAN
  var dropPort: Int? = null
  val includeDirs = mutableListOf<String>()
  val positional = mutableListOf<String>()

  var i = 0
  while (i < args.size) {
    when {
      args[i].startsWith("--format=") -> format = parseFormat(args[i])
      args[i].startsWith("--drop-port=") -> dropPort = parseIntFlag(args[i], "--drop-port")
      args[i] == "-I" -> {
        i++
        if (i >= args.size) throw UsageError("-I requires an argument")
        includeDirs += args[i]
      }
      args[i].startsWith("-") && args[i] != "-" -> throw UsageError("unknown option '${args[i]}'")
      else -> positional += args[i]
    }
    i++
  }

  if (positional.size != 2) {
    throw UsageError("'run' requires exactly 2 arguments: <program.p4> <test.stf>\n$RUN_USAGE")
  }

  return run(
    resolveUserPath(positional[0]),
    stfPath(positional[1]),
    format,
    includeDirs.map { resolveUserPath(it) },
    dropPort,
  )
}

private fun parseIntFlag(arg: String, flag: String): Int {
  val value = arg.removePrefix("$flag=")
  return value.toIntOrNull() ?: throw UsageError("invalid value for $flag: '$value'")
}

private fun parseFormat(arg: String): OutputFormat =
  when (val f = arg.removePrefix("--format=")) {
    "human" -> OutputFormat.HUMAN
    "textproto" -> OutputFormat.TEXTPROTO
    "json" -> OutputFormat.JSON
    else -> throw UsageError("unknown format '$f'")
  }

/** Resolves an STF argument: `-` reads stdin into a temp file, anything else is a file path. */
private fun stfPath(arg: String): Path =
  if (arg == "-") {
    Files.createTempFile("4ward-", ".stf").also {
      it.toFile().deleteOnExit()
      it.toFile().writeText(System.`in`.reader().readText())
    }
  } else {
    resolveUserPath(arg)
  }

/**
 * Resolves a user-supplied path against the original working directory.
 *
 * `bazel run` changes cwd to the runfiles tree, so relative paths like `examples/passthrough.p4`
 * won't resolve without this. Bazel sets `BUILD_WORKING_DIRECTORY` to the user's actual cwd.
 */
private fun resolveUserPath(arg: String): Path {
  val p = Path.of(arg)
  if (p.isAbsolute) return p
  val bwd = System.getenv("BUILD_WORKING_DIRECTORY") ?: return p
  return Path.of(bwd).resolve(p)
}
