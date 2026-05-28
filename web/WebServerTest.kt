package fourward.web

import fourward.BehavioralConfig
import fourward.BitType
import fourward.FieldDecl
import fourward.HeaderDecl
import fourward.IntType
import fourward.Type
import fourward.TypeDecl
import fourward.VarbitType
import fourward.bazel.repoRoot
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebServerTest {

  @Test
  fun `extractJsonString extracts simple values`() {
    assertEquals("hello", WebServer.extractJsonString("""{"key":"hello"}""", "key"))
    assertEquals("world", WebServer.extractJsonString("""{"a":"b","key":"world"}""", "key"))
  }

  @Test
  fun `extractJsonString handles escaped characters`() {
    assertEquals("line1\nline2", WebServer.extractJsonString("""{"s":"line1\nline2"}""", "s"))
    assertEquals("a\"b", WebServer.extractJsonString("""{"s":"a\"b"}""", "s"))
  }

  @Test
  fun `extractJsonString handles unicode escapes`() {
    assertEquals("caf\u00e9", WebServer.extractJsonString("""{"s":"caf\u00e9"}""", "s"))
    assertEquals("\u4e16\u754c", WebServer.extractJsonString("""{"s":"\u4e16\u754c"}""", "s"))
  }

  @Test
  fun `extractJsonString returns null for missing key`() {
    assertNull(WebServer.extractJsonString("""{"other":"value"}""", "key"))
  }

  @Test
  fun `extractJsonInt extracts integer values`() {
    assertEquals(42, WebServer.extractJsonInt("""{"port":42}""", "port"))
    assertEquals(0, WebServer.extractJsonInt("""{"port":0}""", "port"))
  }

  @Test
  fun `extractJsonInt returns null for missing key`() {
    assertNull(WebServer.extractJsonInt("""{"other":1}""", "port"))
  }

  @Test
  fun `hexToBytes converts hex string to bytes`() {
    assertArrayEquals(
      byteArrayOf(0xFF.toByte(), 0x00, 0xAB.toByte()),
      WebServer.hexToBytes("ff00ab"),
    )
  }

  @Test
  fun `hexToBytes handles spaces and colons`() {
    assertArrayEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte()), WebServer.hexToBytes("de ad"))
    assertArrayEquals(byteArrayOf(0xBE.toByte(), 0xEF.toByte()), WebServer.hexToBytes("be:ef"))
  }

  @Test
  fun `hexToBytes handles 0x prefix`() {
    assertArrayEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte()), WebServer.hexToBytes("0xCAFE"))
  }

  @Test
  fun `jsonEscape escapes special characters`() {
    assertEquals("\"hello\"", WebServer.jsonEscape("hello"))
    assertEquals("\"a\\\"b\"", WebServer.jsonEscape("a\"b"))
    assertEquals("\"line1\\nline2\"", WebServer.jsonEscape("line1\nline2"))
  }

  @Test
  fun `errorJson produces valid JSON`() {
    assertEquals("""{"error":"oops"}""", WebServer.errorJson("oops"))
    assertEquals("""{"error":"a\"b"}""", WebServer.errorJson("a\"b"))
  }

  // ---------------------------------------------------------------------------
  // Integration: static file serving + P4 compilation via runfiles
  // ---------------------------------------------------------------------------

  private fun <T> withServer(
    p4cPath: Path? = null,
    compileTimeout: Duration = WebServer.DEFAULT_COMPILE_TIMEOUT,
    block: (port: Int) -> T,
  ): T {
    val simulator = fourward.simulator.Simulator()
    val writeMutex = kotlinx.coroutines.sync.Mutex()
    val broker =
      fourward.grpc.PacketBroker(
        { ingressPort, packet -> simulator.processPacket(ingressPort, packet) },
        writeMutex,
      )
    val service = fourward.grpc.P4RuntimeService(simulator, broker, writeMutex = writeMutex)
    val server =
      WebServer(
          simulator,
          service,
          httpPort = 0,
          p4cPath = p4cPath,
          compileTimeout = compileTimeout,
        )
        .start()
    try {
      return block(server.port())
    } finally {
      server.stop()
      service.close()
    }
  }

  @Test
  fun `serves index_html from runfiles`() = withServer { port ->
    val conn =
      java.net.URI("http://localhost:$port/").toURL().openConnection() as java.net.HttpURLConnection
    assertEquals("GET / should return 200", 200, conn.responseCode)
    val body = conn.inputStream.bufferedReader().readText()
    assertTrue("body should contain HTML", body.contains("<html"))
  }

  @Test
  fun `compile endpoint finds p4c-4ward and compiles`() = withServer { port ->
    val conn =
      java.net.URI("http://localhost:$port/api/compile-and-load").toURL().openConnection()
        as java.net.HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    val p4 =
      """
      #include <core.p4>
      #include <v1model.p4>
      header h {} struct m {} struct hdrs {}
      parser P(packet_in p, out hdrs h, inout m m,
               inout standard_metadata_t s) {
        state start { transition accept; }
      }
      control C(inout hdrs h, inout m m,
                inout standard_metadata_t s) { apply {} }
      control D(packet_out p, in hdrs h) { apply {} }
      control VC(inout hdrs h, inout m m) { apply {} }
      control CC(inout hdrs h, inout m m) { apply {} }
      V1Switch(P(), VC(), C(), C(), CC(), D()) main;
      """
        .trimIndent()
    conn.outputStream.write("""{"source":${WebServer.jsonEscape(p4)}}""".toByteArray())
    val status = conn.responseCode
    val body =
      (if (status in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText()
        ?: ""
    assertTrue("compile should succeed (got $status); body: $body", status in 200..299)
  }

  @Test
  fun `compile endpoint times out stuck p4c process`() {
    val tempDir = Files.createTempDirectory("4ward-web-test-")
    val fakeP4c = tempDir.resolve("p4c-4ward")
    try {
      Files.writeString(
        fakeP4c,
        """
        #!/bin/sh
        echo started
        sleep 5
        echo done
        """
          .trimIndent(),
      )
      fakeP4c.toFile().setExecutable(true)

      withServer(p4cPath = fakeP4c, compileTimeout = Duration.ofMillis(100)) { port ->
        val conn =
          java.net.URI("http://localhost:$port/api/compile-and-load").toURL().openConnection()
            as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.readTimeout = 3000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.write("""{"source":"ignored"}""".toByteArray())

        assertEquals(400, conn.responseCode)
        val body = conn.errorStream.bufferedReader().readText()
        assertTrue(body, body.contains("timed out"))
        assertTrue(body, body.contains("100 ms"))
      }
    } finally {
      Files.deleteIfExists(fakeP4c)
      Files.deleteIfExists(tempDir)
    }
  }

  @Test
  fun `frontend encodes and displays range match fields explicitly`() {
    val tablesJs = Files.readString(repoRoot.resolve("web/frontend/js/tables.js"))
    assertTrue(tablesJs.contains("case MATCH_TYPE.RANGE"))
    assertTrue(tablesJs.contains("fieldMatch.range = {"))
    assertTrue(tablesJs.contains("unsupported match type"))

    val encodingJs = Files.readString(repoRoot.resolve("web/frontend/js/encoding.js"))
    assertTrue(encodingJs.contains("m.range"))
    assertTrue(encodingJs.contains("m.range.low"))
    assertTrue(encodingJs.contains("m.range.high"))

    val traceRenderJs = Files.readString(repoRoot.resolve("web/frontend/js/trace-render.js"))
    assertTrue(traceRenderJs.contains("m.range"))
    assertTrue(traceRenderJs.contains("m.range.low"))
    assertTrue(traceRenderJs.contains("m.range.high"))
  }

  // ---------------------------------------------------------------------------
  // headerTypesJson
  // ---------------------------------------------------------------------------

  @Test
  fun `headerTypesJson serializes header fields with bitwidths`() {
    val config =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("ethernet_t")
            .setHeader(
              HeaderDecl.newBuilder()
                .addFields(field("dstAddr", bitType(48)))
                .addFields(field("srcAddr", bitType(48)))
                .addFields(field("etherType", bitType(16)))
            )
        )
        .build()
    assertEquals(
      """{"ethernet_t":[{"name":"dstAddr","bitwidth":48},""" +
        """{"name":"srcAddr","bitwidth":48},""" +
        """{"name":"etherType","bitwidth":16}]}""",
      WebServer.headerTypesJson(config),
    )
  }

  @Test
  fun `headerTypesJson handles multiple header types`() {
    val config =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("h1_t")
            .setHeader(HeaderDecl.newBuilder().addFields(field("f", bitType(8))))
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("h2_t")
            .setHeader(HeaderDecl.newBuilder().addFields(field("g", bitType(32))))
        )
        .build()
    assertEquals(
      """{"h1_t":[{"name":"f","bitwidth":8}],"h2_t":[{"name":"g","bitwidth":32}]}""",
      WebServer.headerTypesJson(config),
    )
  }

  @Test
  fun `headerTypesJson skips non-header types`() {
    val config =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("ethernet_t")
            .setHeader(HeaderDecl.newBuilder().addFields(field("f", bitType(8))))
        )
        .addTypes(
          TypeDecl.newBuilder()
            .setName("meta_t")
            .setStruct(fourward.StructDecl.newBuilder().addFields(field("x", bitType(16))))
        )
        .build()
    assertEquals(
      """{"ethernet_t":[{"name":"f","bitwidth":8}]}""",
      WebServer.headerTypesJson(config),
    )
  }

  @Test
  fun `headerTypesJson handles signed int and varbit types`() {
    val config =
      BehavioralConfig.newBuilder()
        .addTypes(
          TypeDecl.newBuilder()
            .setName("special_t")
            .setHeader(
              HeaderDecl.newBuilder()
                .addFields(field("signed_f", signedIntType(16)))
                .addFields(field("var_f", varbitType(320)))
            )
        )
        .build()
    assertEquals(
      """{"special_t":[{"name":"signed_f","bitwidth":16},{"name":"var_f","bitwidth":320}]}""",
      WebServer.headerTypesJson(config),
    )
  }

  @Test
  fun `headerTypesJson returns empty object for no headers`() {
    val config = BehavioralConfig.getDefaultInstance()
    assertEquals("{}", WebServer.headerTypesJson(config))
  }

  private fun field(name: String, type: Type): FieldDecl =
    FieldDecl.newBuilder().setName(name).setType(type).build()

  private fun bitType(width: Int): Type =
    Type.newBuilder().setBit(BitType.newBuilder().setWidth(width)).build()

  private fun signedIntType(width: Int): Type =
    Type.newBuilder().setSignedInt(IntType.newBuilder().setWidth(width)).build()

  private fun varbitType(maxWidth: Int): Type =
    Type.newBuilder().setVarbit(VarbitType.newBuilder().setMaxWidth(maxWidth)).build()
}
