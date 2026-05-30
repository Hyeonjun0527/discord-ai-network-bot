package com.discordassistant.central.relay.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** 프레임 직렬화 라운드트립·크기/타입 검증 + 옵션 화이트리스트(차수 18 커버리지). */
class FrameCodecRoundTripTest {
    @Test
    fun `라운드트립 동치 + 비ASCII 보존`() {
        val f = InferResult(requestId = "r1", text = "안녕하세요 🤖 코드 설명")
        val decoded = FrameCodec.decode(FrameCodec.encode(f))
        assertEquals(f, decoded)
        assertTrue(decoded is InferResult && decoded.text.contains("안녕하세요"))
    }

    @Test
    fun `여러 프레임 타입 라운드트립`() {
        val frames =
            listOf(
                AuthOkFrame(sessionId = "s1"),
                InferRequest(requestId = "q1", prompt = "hi", stream = true),
                ChunkFrame(requestId = "q1", delta = "부분", done = false),
                ProviderHelloFrame(models = listOf("llama3"), maxConcurrency = 2),
            )
        frames.forEach { assertEquals(it, FrameCodec.decode(FrameCodec.encode(it))) }
    }

    @Test
    fun `알 수 없는 타입은 ProtocolException`() {
        assertThrows<ProtocolException> { FrameCodec.decode("""{"type":"nonsense"}""") }
    }

    @Test
    fun `깨진 JSON 은 ProtocolException`() {
        assertThrows<ProtocolException> { FrameCodec.decode("{not valid json") }
    }

    @Test
    fun `프레임 크기 초과는 ProtocolException`() {
        val big = "x".repeat(MAX_FRAME_BYTES + 1)
        assertThrows<ProtocolException> { FrameCodec.encode(InferResult(requestId = "r", text = big)) }
    }

    @Test
    fun `옵션 화이트리스트만 통과`() {
        val filtered = filterOptions(mapOf("temperature" to 0.7, "danger" to 1, "top_p" to 0.9))
        assertEquals(setOf("temperature", "top_p"), filtered.keys)
        assertTrue(filterOptions(null).isEmpty())
    }
}
