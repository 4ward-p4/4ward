package fourward.bazel

import com.google.devtools.build.runfiles.Runfiles
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

private val runfiles: Runfiles = Runfiles.preload().unmapped()

/**
 * Runfile root of the main repository. Its prefix varies per build environment — `_main` under OSS
 * root builds, `fourward+` under BCR consumers, `third_party/fourward` under google3 — so
 * hardcoding any literal prefix is a portability bug. Compose with `.resolve("path/to/file")` to
 * locate specific runfiles.
 *
 * Example:
 * ```
 * val config = repoRoot.resolve("e2e_tests/basic_table/basic_table.txtpb")
 * ```
 */
val repoRoot: Path = resolveRlocation(REPO_ROOT_RLOCATIONPATH, "runfiles anchor").parent

/**
 * Resolves a runfile path supplied by BUILD via `jvm_flags = ["-D<key>=$(rlocationpath <label>)"]`.
 * Use this for files in **external** repositories (e.g. `@p4c//p4include:core.p4`) whose canonical
 * name varies per environment. For files in the main repo, use [repoRoot] + `.resolve(...)`.
 */
fun resolveRunfileProperty(key: String): Path {
  val rlocation =
    checkNotNull(System.getProperty(key)) {
      "$key system property not set. Expected BUILD to pass " +
        "-D$key=\$(rlocationpath <label>) in jvm_flags."
    }
  return resolveRlocation(rlocation, "$key ($rlocation)")
}

/**
 * Prepends a BUILD-provided `cc` shim to [pb]'s PATH if no system `cc` is found. p4c shells out to
 * `cc` for preprocessing, which doesn't exist in hermetic sandboxes (blaze/google3).
 *
 * Requires the caller's BUILD target to include `cc_shim` in `data` and pass its path via
 * `-D<shimPropertyKey>=$(rlocationpath ...)` in `jvm_flags`.
 */
fun ensureCcOnPath(pb: ProcessBuilder, shimPropertyKey: String = "cc_shim") {
  if (!hasSystemCc) {
    val shimDir = resolveRunfileProperty(shimPropertyKey).parent
    val env = pb.environment()
    env["PATH"] = "$shimDir${File.pathSeparator}${env["PATH"] ?: ""}"
  }
}

private val hasSystemCc: Boolean by lazy {
  System.getenv("PATH")?.split(File.pathSeparator).orEmpty().any { dir ->
    Files.isExecutable(Path.of(dir, "cc"))
  }
}

private fun resolveRlocation(rlocation: String, what: String): Path =
  Path.of(
    checkNotNull(runfiles.rlocation(rlocation)) {
      "$what not found in runfiles tree. Are you running inside 'bazel run' or 'bazel test'?"
    }
  )
