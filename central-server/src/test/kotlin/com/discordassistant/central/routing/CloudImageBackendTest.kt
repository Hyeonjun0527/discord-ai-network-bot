package com.discordassistant.central.routing

import com.discordassistant.central.routing.application.CloudImageBackendSelector
import com.discordassistant.central.routing.application.CloudImageException
import com.discordassistant.central.routing.application.NoCloudImageBackend
import com.discordassistant.central.routing.application.RunPodImageBackend
import com.discordassistant.central.routing.application.RunPodResponseParser
import com.discordassistant.central.routing.application.StabilityImageBackend
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.util.Base64

/**
 * 클라우드 이미지 백엔드의 순수 부분(외부 API 호출 없음): RunPod 봉투/출력 파싱(base64→ByteArray),
 * Stability multipart 본문 구성, 비활성 기본. 실 Stability/RunPod 호출은 하지 않는다.
 */
class CloudImageBackendTest {
    private val mapper = ObjectMapper()
    private val log = LoggerFactory.getLogger(CloudImageBackendTest::class.java)

    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    // ── RunPod 출력 파싱(runpod.py _parse_output 포팅) ──────────────────────────────────

    @Test
    fun `image_base64 출력 → ByteArray 디코드`() {
        val png = byteArrayOf(1, 2, 3, 4)
        val output = mapper.readTree("""{"image_base64":"${b64(png)}"}""")
        assertArrayEquals(png, RunPodResponseParser.parseOutput(output))
    }

    @Test
    fun `image·images·문자열 호환 형태도 디코드`() {
        val png = byteArrayOf(9, 8, 7)
        assertArrayEquals(png, RunPodResponseParser.parseOutput(mapper.readTree("""{"image":"${b64(png)}"}""")))
        assertArrayEquals(png, RunPodResponseParser.parseOutput(mapper.readTree("""{"images":["${b64(png)}"]}""")))
        assertArrayEquals(png, RunPodResponseParser.parseOutput(mapper.readTree("\"${b64(png)}\"")))
    }

    @Test
    fun `data URL 접두는 벗겨서 디코드`() {
        val png = byteArrayOf(5, 5, 5)
        val output = mapper.readTree("""{"image_base64":"data:image/png;base64,${b64(png)}"}""")
        assertArrayEquals(png, RunPodResponseParser.parseOutput(output))
    }

    @Test
    fun `출력에 이미지 없으면 예외`() {
        assertThrows(CloudImageException::class.java) {
            RunPodResponseParser.parseOutput(mapper.readTree("""{"status":"done"}"""))
        }
        assertThrows(CloudImageException::class.java) { RunPodResponseParser.parseOutput(null) }
    }

    @Test
    fun `잘못된 base64 출력이면 예외(흘려보내지 않음)`() {
        val output = mapper.readTree("""{"image_base64":"!!! not base64 @@@"}""")
        assertThrows(CloudImageException::class.java) { RunPodResponseParser.parseOutput(output) }
    }

    // ── RunPod 봉투 파싱(_json_or_error 포팅) ──────────────────────────────────────────

    @Test
    fun `봉투 HTTP 200 → JSON 노드 반환`() {
        val node = RunPodResponseParser.parseEnvelope("""{"status":"COMPLETED"}""", 200, "runsync", mapper, log)
        assertEquals("COMPLETED", node.get("status").asText())
    }

    @Test
    fun `봉투 HTTP 4xx → 일반화 예외(상세 미노출)`() {
        val e =
            assertThrows(CloudImageException::class.java) {
                RunPodResponseParser.parseEnvelope("""{"error":"bad key"}""", 401, "runsync", mapper, log)
            }
        assertEquals(RunPodResponseParser.USER_ERROR_MESSAGE, e.message)
    }

    @Test
    fun `봉투 깨진 JSON → 파싱 실패 예외`() {
        assertThrows(CloudImageException::class.java) {
            RunPodResponseParser.parseEnvelope("not json", 200, "runsync", mapper, log)
        }
    }

    // ── Stability multipart 본문 구성(stability.py FormData 포팅) ──────────────────────

    @Test
    fun `multipart 본문에 모든 필드가 boundary 로 구분되어 들어간다`() {
        val boundary = "----test-boundary"
        val body =
            String(
                StabilityImageBackend.multipartBody(
                    boundary,
                    listOf("prompt" to "a cat", "aspect_ratio" to "1:1", "output_format" to "png"),
                ),
                Charsets.UTF_8,
            )
        assertTrue(body.contains("--$boundary\r\n"))
        assertTrue(body.contains("Content-Disposition: form-data; name=\"prompt\"\r\n\r\na cat\r\n"))
        assertTrue(body.contains("name=\"aspect_ratio\"\r\n\r\n1:1\r\n"))
        assertTrue(body.contains("name=\"output_format\"\r\n\r\npng\r\n"))
        assertTrue(body.endsWith("--$boundary--\r\n")) // 종료 boundary
    }

    @Test
    fun `Stability 키 없으면 비활성(라우팅이 에이전트로 폴백)`() {
        val backend = StabilityImageBackend(apiKey = "", model = "core", timeoutSeconds = 120, baseUrl = "https://api.stability.ai")
        assertFalse(backend.isEnabled())
        assertEquals(1024 to 1024, backend.defaultResolution())
        // 비활성인데 호출하면 예외(라우팅은 isEnabled 로 먼저 분기하므로 도달하지 않음).
        assertThrows(CloudImageException::class.java) { backend.txt2img("x", 1024, 1024, "") }
    }

    @Test
    fun `NoCloudImageBackend 는 항상 비활성`() {
        assertFalse(NoCloudImageBackend.isEnabled())
        assertEquals(1024 to 1024, NoCloudImageBackend.defaultResolution())
        assertThrows(CloudImageException::class.java) { NoCloudImageBackend.txt2img("x", 1024, 1024, "") }
    }

    @Test
    fun `Stability buildFields - negative 비면 생략, sd3 면 model 명시`() {
        val core = StabilityImageBackend.buildFields("a cat", "", "core").toMap()
        assertEquals("a cat", core["prompt"])
        assertFalse(core.containsKey("negative_prompt")) // 빈 negative 는 생략
        assertEquals("1:1", core["aspect_ratio"])
        assertEquals("png", core["output_format"])
        assertFalse(core.containsKey("model")) // core 는 model 미명시

        val sd3 = StabilityImageBackend.buildFields("a dog", "blurry", "sd3").toMap()
        assertEquals("blurry", sd3["negative_prompt"])
        assertEquals("sd3.5-large", sd3["model"])
    }

    @Test
    fun `Stability handleResponse - 200 + image 면 바이트, 아니면 일반화 예외`() {
        val png = byteArrayOf(7, 7, 7)
        assertArrayEquals(png, StabilityImageBackend.handleResponse(200, "image/png", png, log))
        // 비-200 또는 비-image content-type → 일반화 예외(상세 미노출)
        val e1 =
            assertThrows(CloudImageException::class.java) {
                StabilityImageBackend.handleResponse(403, "application/json", """{"errors":["moderation"]}""".toByteArray(), log)
            }
        assertEquals(StabilityImageBackend.USER_ERROR_MESSAGE, e1.message)
        assertThrows(CloudImageException::class.java) {
            StabilityImageBackend.handleResponse(200, "application/json", "{}".toByteArray(), log)
        }
    }

    // ── RunPod payload·resolve 분기(순수, fetchStatus 람다로 HTTP 우회) ─────────────────

    @Test
    fun `RunPod buildPayload - input 필드가 워커 계약대로 구성`() {
        val node = RunPodResponseParser.buildPayload(mapper, "a cat", "blurry", 768, 512)
        val input = node.get("input")
        assertEquals("a cat", input.get("prompt").asText())
        assertEquals("blurry", input.get("negative_prompt").asText())
        assertEquals(768, input.get("width").asInt())
        assertEquals(512, input.get("height").asInt())
        assertEquals(0, input.get("seed").asInt())
    }

    @Test
    fun `RunPod resolve - runsync 즉시 완료면 폴링 없이 이미지`() {
        val png = byteArrayOf(3, 1, 4)
        val runsync = mapper.readTree("""{"status":"COMPLETED","output":{"image_base64":"${b64(png)}"}}""")
        val out =
            RunPodResponseParser.resolve(runsync, timeoutSeconds = 10, pollIntervalMs = 1) {
                error("폴링하면 안 됨(이미 완료)")
            }
        assertArrayEquals(png, out)
    }

    @Test
    fun `RunPod resolve - IN_PROGRESS 면 status 폴링 후 완료 이미지`() {
        val png = byteArrayOf(2, 7, 1, 8)
        val runsync = mapper.readTree("""{"status":"IN_PROGRESS","id":"job-1"}""")
        var calls = 0
        val out =
            RunPodResponseParser.resolve(runsync, timeoutSeconds = 10, pollIntervalMs = 1) { jobId ->
                assertEquals("job-1", jobId)
                calls++
                if (calls < 2) {
                    mapper.readTree("""{"status":"IN_PROGRESS"}""")
                } else {
                    mapper.readTree("""{"status":"COMPLETED","output":{"image_base64":"${b64(png)}"}}""")
                }
            }
        assertArrayEquals(png, out)
        assertEquals(2, calls)
    }

    @Test
    fun `RunPod resolve - FAILED 면 일반화 예외`() {
        val runsync = mapper.readTree("""{"status":"FAILED","error":"oom"}""")
        assertThrows(CloudImageException::class.java) {
            RunPodResponseParser.resolve(runsync, 10, 1) { error("폴링 없음") }
        }
    }

    @Test
    fun `RunPod resolve - id 없고 출력 없으면 예외`() {
        val runsync = mapper.readTree("""{"status":"IN_QUEUE"}""")
        assertThrows(CloudImageException::class.java) {
            RunPodResponseParser.resolve(runsync, 10, 1) { error("폴링 없음") }
        }
    }

    @Test
    fun `RunPod resolve - 타임아웃이면 예외`() {
        val runsync = mapper.readTree("""{"status":"IN_PROGRESS","id":"job-x"}""")
        // timeoutSeconds*1000 = 1ms 데드라인, pollInterval 1ms → 한 번 폴링 후 데드라인 초과.
        assertThrows(CloudImageException::class.java) {
            RunPodResponseParser.resolve(runsync, timeoutSeconds = 0, pollIntervalMs = 1) {
                mapper.readTree("""{"status":"IN_PROGRESS"}""")
            }
        }
    }

    // ── 실 HTTP 왕복(로컬 스텁 서버) — txt2img 의 전송/응답 경로와 셀렉터 위임을 실제로 한 번 탄다 ──

    /** [body]·[contentType]·[status] 를 모든 경로에 그대로 돌려주는 1요청짜리 로컬 스텁 서버를 띄운다. */
    private fun stubServer(
        status: Int,
        contentType: String,
        body: ByteArray,
    ): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                ex.requestBody.readBytes()
                ex.responseHeaders.add("Content-Type", contentType)
                ex.sendResponseHeaders(status, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }

    @Test
    fun `Stability txt2img - 실 HTTP 200 image 면 PNG 바이트(셀렉터 경유)`() {
        val png = byteArrayOf(11, 22, 33, 44)
        val server = stubServer(200, "image/png", png)
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val stability = StabilityImageBackend(apiKey = "k", model = "core", timeoutSeconds = 10, baseUrl = base)
            val runpod = RunPodImageBackend(apiKey = "", endpointId = "", timeoutSeconds = 10, baseUrl = base)
            val selector = CloudImageBackendSelector(stability, runpod)
            assertTrue(selector.isEnabled())
            assertEquals(1024 to 1024, selector.defaultResolution())
            // 셀렉터가 stability 로 위임 → 실제 전송/응답 분기를 한 번 탄다.
            assertArrayEquals(png, selector.txt2img("a cat", 1024, 1024, "blurry"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Stability txt2img - 실 HTTP 403 이면 일반화 예외`() {
        val server = stubServer(403, "application/json", """{"errors":["moderation"]}""".toByteArray())
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val stability = StabilityImageBackend(apiKey = "k", model = "ultra", timeoutSeconds = 10, baseUrl = base)
            assertThrows(CloudImageException::class.java) { stability.txt2img("x", 1024, 1024, "") }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `RunPod txt2img - 실 HTTP runsync 즉시 완료면 이미지(셀렉터 경유)`() {
        val png = byteArrayOf(5, 6, 7, 8)
        val body = """{"status":"COMPLETED","output":{"image_base64":"${b64(png)}"}}"""
        val server = stubServer(200, "application/json", body.toByteArray())
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            // stability 비활성 → 셀렉터가 runpod 로 위임.
            val stability = StabilityImageBackend(apiKey = "", model = "core", timeoutSeconds = 10, baseUrl = base)
            val runpod = RunPodImageBackend(apiKey = "k", endpointId = "ep", timeoutSeconds = 10, baseUrl = base)
            val selector = CloudImageBackendSelector(stability, runpod)
            assertTrue(selector.isEnabled())
            assertArrayEquals(png, selector.txt2img("a dog", 768, 512, "blurry"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `RunPod txt2img - 실 HTTP 500 이면 일반화 예외`() {
        val server = stubServer(500, "application/json", """{"error":"boom"}""".toByteArray())
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val runpod = RunPodImageBackend(apiKey = "k", endpointId = "ep", timeoutSeconds = 10, baseUrl = base)
            assertThrows(CloudImageException::class.java) { runpod.txt2img("x", 1024, 1024, "") }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `selector 둘 다 비활성이면 isEnabled=false 이고 호출 시 예외`() {
        val stability = StabilityImageBackend(apiKey = "", model = "core", timeoutSeconds = 10, baseUrl = "http://x")
        val runpod = RunPodImageBackend(apiKey = "", endpointId = "", timeoutSeconds = 10, baseUrl = "http://x")
        val selector = CloudImageBackendSelector(stability, runpod)
        assertFalse(selector.isEnabled())
        assertEquals(1024 to 1024, selector.defaultResolution())
        assertThrows(CloudImageException::class.java) { selector.txt2img("x", 1024, 1024, "") }
    }
}
