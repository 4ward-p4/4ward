package fourward.grpc

import fourward.ConstraintRequest
import fourward.ConstraintResponse
import fourward.LoadP4InfoRequest
import fourward.ValidateEntryRequest
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import p4.config.v1.P4InfoOuterClass.P4Info
import p4.v1.P4RuntimeOuterClass.TableEntry

/**
 * Client for the p4-constraints validator subprocess.
 *
 * Manages the constraint_validator process lifecycle and validates table entries against
 * `@entry_restriction` / `@action_restriction` annotations. The wire protocol is length-delimited
 * protobuf over stdin/stdout (same framing as the simulator).
 */
class ConstraintValidator
private constructor(
  private val process: Process,
  private val input: DataInputStream,
  private val output: DataOutputStream,
) : Closeable {

  /**
   * Validates a table entry against loaded constraints.
   *
   * @return null if the entry satisfies all constraints, or a human-readable violation explanation.
   * @throws ConstraintValidatorException if the validator process returns an error.
   */
  @Synchronized
  fun validateEntry(entry: TableEntry): String? {
    val request =
      ConstraintRequest.newBuilder()
        .setValidateEntry(ValidateEntryRequest.newBuilder().setEntry(entry))
        .build()
    val response =
      try {
        call(input, output, request)
      } catch (e: java.io.IOException) {
        throw ConstraintValidatorException(
          "constraint validator subprocess communication failed: ${e.message}",
          e,
        )
      }
    if (response.hasError()) {
      throw ConstraintValidatorException(response.error.message)
    }
    val violation = response.validateEntry.violation
    return violation.ifEmpty { null }
  }

  override fun close() {
    output.close()
    process.destroy()
    if (!process.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly().waitFor()
    }
    input.close()
  }

  companion object {
    private const val SHUTDOWN_TIMEOUT_SECONDS = 5L

    /** Sends a request and reads the response using length-delimited protobuf framing. */
    private fun call(
      input: DataInputStream,
      output: DataOutputStream,
      request: ConstraintRequest,
    ): ConstraintResponse {
      val bytes = request.toByteArray()
      output.writeInt(bytes.size)
      output.write(bytes)
      output.flush()
      val length = input.readInt()
      val respBytes = ByteArray(length)
      input.readFully(respBytes)
      return ConstraintResponse.parseFrom(respBytes)
    }

    /**
     * Creates a ConstraintValidator by spawning the validator subprocess and loading the given
     * P4Info.
     *
     * Returns null if the P4Info has no constraint annotations (no subprocess needed).
     *
     * @throws ConstraintValidatorException if the subprocess fails to load the P4Info.
     */
    fun create(p4info: P4Info, validatorBinary: Path): ConstraintValidator? {
      val process =
        ProcessBuilder(validatorBinary.toString())
          .redirectError(ProcessBuilder.Redirect.INHERIT)
          .start()
      val input = DataInputStream(process.inputStream.buffered())
      val output = DataOutputStream(process.outputStream.buffered())

      val request =
        ConstraintRequest.newBuilder()
          .setLoadP4Info(LoadP4InfoRequest.newBuilder().setP4Info(p4info))
          .build()
      val response =
        try {
          call(input, output, request)
        } catch (e: java.io.IOException) {
          process.destroyForcibly().waitFor()
          throw ConstraintValidatorException(
            "constraint validator subprocess failed during P4Info load: ${e.message}",
            e,
          )
        }

      if (response.hasError()) {
        process.destroyForcibly().waitFor()
        throw ConstraintValidatorException(
          "Failed to load P4Info into constraint validator: ${response.error.message}"
        )
      }

      val loadResponse = response.loadP4Info
      if (loadResponse.constrainedTables == 0 && loadResponse.constrainedActions == 0) {
        output.close()
        process.destroy()
        process.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        return null
      }

      return ConstraintValidator(process, input, output)
    }
  }
}

class ConstraintValidatorException(message: String, cause: Throwable? = null) :
  RuntimeException(message, cause)
