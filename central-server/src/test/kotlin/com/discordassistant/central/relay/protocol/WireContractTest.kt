package com.discordassistant.central.relay.protocol

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 크로스언어 컨트랙트 테스트 (LAUNCH 차수 17). Python provider-agent 와 동일한 와이어 픽스처
 * (`wire-fixtures.json`)를 공유 검증한다 — 한쪽 변경 시 와이어 드리프트를 즉시 잡는다.
 */
class WireContractTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `와이어 픽스처 round-trip`() {
        val json = javaClass.getResource("/wire-fixtures.json")!!.readText()
        val fixtures: Map<String, Map<String, Any?>> =
            mapper.readValue(
                json,
                mapper.typeFactory.constructMapType(LinkedHashMap::class.java, String::class.java, Map::class.java),
            )
        assertEquals(13, fixtures.size, "픽스처 개수")
        for ((name, expected) in fixtures) {
            // 1) 픽스처(JSON)를 디코드할 수 있어야 한다.
            val frame = FrameCodec.decode(mapper.writeValueAsString(expected))
            // 2) 재인코딩한 맵이 정확히 같아야 한다(camelCase 와이어 일치).
            val reencoded: Map<String, Any?> =
                mapper.readValue(
                    FrameCodec.encode(frame),
                    mapper.typeFactory.constructMapType(LinkedHashMap::class.java, String::class.java, Any::class.java),
                )
            assertEquals(expected, reencoded, "와이어 불일치: $name")
        }
    }
}
