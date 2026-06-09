package com.discordassistant.central.relay.protocol

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/** 프로토콜 위반(알 수 없는 타입·필수 필드 누락·크기 초과·JSON 오류). */
class ProtocolException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * WS 프레임 JSON 직렬화/역직렬화 (api.md §8).
 *
 * Jackson 기본은 비ASCII 를 이스케이프하지 않으므로 한국어가 보존된다. 모르는 JSON 필드는
 * 무시한다(앞으로의 호환). 알 수 없는 `type` 은 ProtocolException.
 */
object FrameCodec {
    private val mapper: ObjectMapper =
        jacksonObjectMapper().apply {
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

    fun encode(frame: Frame): String {
        val json = mapper.writeValueAsString(frame)
        val size = json.toByteArray(Charsets.UTF_8).size
        if (size > MAX_FRAME_BYTES) {
            throw ProtocolException("프레임이 너무 큽니다($size > $MAX_FRAME_BYTES bytes)")
        }
        return json
    }

    fun decode(raw: String): Frame {
        val size = raw.toByteArray(Charsets.UTF_8).size
        if (size > MAX_FRAME_BYTES) {
            throw ProtocolException("프레임이 너무 큽니다($size > $MAX_FRAME_BYTES bytes)")
        }
        return try {
            mapper.readValue(raw, Frame::class.java)
        } catch (e: InvalidTypeIdException) {
            throw ProtocolException("알 수 없는 프레임 타입: ${e.typeId}", e)
        } catch (e: JacksonException) {
            // decode 의 예상 실패는 Jackson 파싱 오류뿐 — 그 외 예외는 버그이므로 삼키지 않고 전파한다(예외 원칙 1·2).
            throw ProtocolException("프레임 디코딩 실패: ${e.message}", e)
        }
    }

    fun decode(raw: ByteArray): Frame {
        if (raw.size > MAX_FRAME_BYTES) {
            throw ProtocolException("프레임이 너무 큽니다(${raw.size} > $MAX_FRAME_BYTES bytes)")
        }
        return decode(String(raw, Charsets.UTF_8))
    }
}
