package fourward.e2e

import com.google.protobuf.TextFormat
import fourward.PipelineConfig
import java.io.StringReader
import java.nio.file.Files
import org.anarres.cpp.DefaultPreprocessorListener
import org.anarres.cpp.Feature
import org.anarres.cpp.InputLexerSource
import org.anarres.cpp.Preprocessor
import org.anarres.cpp.Token

/**
 * Compiles a P4 source string at test time using p4c-4ward. Useful for tests that need a custom P4
 * program without a dedicated BUILD target.
 *
 * Preprocessing (`#include`, `#define`, etc.) is handled by JCPP (a pure-Java C preprocessor), so
 * no native `cc` binary is needed at test time. The preprocessed source is passed to p4c with
 * `--nocpp`.
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
  val preprocessed = preprocess(source, p4includeDir.toString())

  val tmpDir = Files.createTempDirectory("inline-p4")
  try {
    val srcFile = tmpDir.resolve("program.p4")
    val outFile = tmpDir.resolve("pipeline.txtpb")
    Files.writeString(srcFile, preprocessed)

    val process =
      ProcessBuilder(p4cBinary.toString(), srcFile.toString(), "-o", outFile.toString(), "--nocpp")
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

/** Preprocesses P4 source using JCPP (pure-Java C preprocessor). */
private fun preprocess(source: String, includeDir: String): String {
  val pp = Preprocessor()
  pp.listener = DefaultPreprocessorListener()
  pp.addFeature(Feature.LINEMARKERS)
  pp.systemIncludePath = listOf(includeDir)
  pp.addInput(InputLexerSource(StringReader(source)))

  return pp.use {
    val out = StringBuilder()
    while (true) {
      val tok = it.token()
      if (tok.type == Token.EOF) break
      out.append(tok.text)
    }
    out.toString()
  }
}
