package fourward.grpc

import com.google.protobuf.TextFormat
import fourward.PipelineConfig
import java.nio.file.Files

/**
 * Compiles a P4 source string at test time using p4c-4ward. Useful for tests that need a custom P4
 * program without a dedicated BUILD target.
 *
 * Example:
 * ```
 * val config = compileInlineP4("""
 *   #include <core.p4>
 *   #include <v1model.p4>
 *   // ... minimal program ...
 * """)
 * val harness = FourwardTestHarness()
 * harness.loadPipeline(config)
 * ```
 *
 * Requires the test's BUILD target to include:
 * ```
 * data = ["//p4c_backend:p4c-4ward", "@p4c//p4include"],
 * jvm_flags = [
 *     "-Dp4c_4ward=$(rlocationpath //p4c_backend:p4c-4ward)",
 *     "-Dp4include=$(rlocationpath @p4c//p4include:core.p4)",
 * ],
 * ```
 */
fun compileInlineP4(source: String): PipelineConfig {
  val p4cBinary = fourward.bazel.resolveRunfileProperty("p4c_4ward")
  val p4includeDir = fourward.bazel.resolveRunfileProperty("p4include").parent

  val tmpDir = Files.createTempDirectory("inline-p4")
  try {
    val srcFile = tmpDir.resolve("program.p4")
    val outFile = tmpDir.resolve("pipeline.txtpb")
    Files.writeString(srcFile, source)

    val process =
      ProcessBuilder(
          p4cBinary.toString(),
          srcFile.toString(),
          "-o",
          outFile.toString(),
          "-I",
          p4includeDir.toString(),
        )
        .redirectErrorStream(true)
        .start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()
    check(exitCode == 0) { "p4c-4ward failed (exit $exitCode):\n$output" }

    val builder = PipelineConfig.newBuilder()
    TextFormat.merge(Files.readString(outFile), builder)
    return builder.build()
  } finally {
    tmpDir.toFile().deleteRecursively()
  }
}
