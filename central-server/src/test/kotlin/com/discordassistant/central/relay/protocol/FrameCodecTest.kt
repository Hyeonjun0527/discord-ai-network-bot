package com.discordassistant.central.relay.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FrameCodecTest {

    private val frames: List<Frame> = listOf(
        AuthFrame(token = "secret-abc", agentVersion = "0.1", platform = "darwin"),
        AuthOkFrame(sessionId = "s1"),
        AuthErrFrame(code = ErrorCode.AUTH_FAILED, message = "bad"),
        InferRequest(requestId = "r1", model = "llama3.1:8b", prompt = "안녕 코드 설명해줘"),
        InferResult(requestId = "r1", text = "결과입니다", usage = Usage(10, 20)),
        InferError(requestId = "r1", code = ErrorCode.TIMEOUT, message = "시간초과"),
        ChunkFrame(requestId = "r1", delta = "부분", done = false),
        PingFrame(),
        PongFrame(),
        CancelFrame(requestId = "r1"),
        ProviderHelloFrame(models = listOf("llama3.1:8b"), maxConcurrency = 1, remainingDailyRequests = 42),
        ProviderStatusFrame(load = "idle", battery = "charging", online = true, busy = false),
    )

    @Test
    fun `모든 프레임 round-trip 동치`() {
        for (f in frames) {
            assertEquals(f, FrameCodec.decode(FrameCodec.encode(f)), "round-trip 실패: $f")
        }
    }

    @Test
    fun `한국어 보존(ensure-ascii 아님)`() {
        val json = FrameCodec.encode(InferRequest(requestId = "r1", prompt = "안녕하세요"))
        assertTrue(json.contains("안녕하세요"), "한국어가 이스케이프됨: $json")
    }

    @Test
    fun `타입 디스크리미네이터로 올바른 서브타입 복원`() {
        val decoded = FrameCodec.decode(FrameCodec.encode(InferResult(requestId = "x", text = "t")))
        assertTrue(decoded is InferResult)
        assertEquals("x", (decoded as InferResult).requestId)
    }

    @Test
    fun `토큰은 toString 에서 마스킹된다`() {
        val s = AuthFrame(token = "secret-abc").toString()
        assertFalse(s.contains("secret-abc"))
        assertTrue(s.contains("***"))
    }

    @Test
    fun `알 수 없는 타입은 ProtocolException`() {
        assertThrows<ProtocolException> { FrameCodec.decode("""{"type":"nope"}""") }
    }

    @Test
    fun `잘못된 JSON 은 ProtocolException`() {
        assertThrows<ProtocolException> { FrameCodec.decode("{not json") }
    }

    @Test
    fun `옵션 화이트리스트 필터`() {
        val filtered = filterOptions(mapOf("temperature" to 0.3, "evil" to "x"))
        assertEquals(mapOf<String, Any?>("temperature" to 0.3), filtered)
    }

    @Test
    fun `프롬프트 길이 상한 초과는 예외`() {
        assertThrows<IllegalArgumentException> {
            InferRequest(requestId = "x", prompt = "a".repeat(MAX_PROMPT_CHARS + 1))
        }
    }
}
