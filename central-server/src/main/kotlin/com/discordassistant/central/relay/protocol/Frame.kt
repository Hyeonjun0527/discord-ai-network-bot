package com.discordassistant.central.relay.protocol

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

/** 프로토콜 버전. 핸드셰이크에서 협상하며 major 가 다르면 비호환. */
const val PROTOCOL_VERSION: String = "1.0"

/** 단일 프레임 최대 직렬화 크기(바이트). */
const val MAX_FRAME_BYTES: Int = 1_000_000

/** 프롬프트 최대 길이(문자). */
const val MAX_PROMPT_CHARS: Int = 100_000

/** WS 프레임 type 값 (specs api.md §8, ADR 0002). */
object FrameType {
    const val AUTH = "auth"
    const val AUTH_OK = "auth_ok"
    const val AUTH_ERR = "auth_err"
    const val INFER = "infer"
    const val RESULT = "result"
    const val ERROR = "error"
    const val CHUNK = "chunk"
    const val PING = "ping"
    const val PONG = "pong"
    const val CANCEL = "cancel"
    const val PROVIDER_HELLO = "provider_hello"
    const val PROVIDER_STATUS = "provider_status"
}

/** error 프레임 코드. */
object ErrorCode {
    const val OFFLINE = "OFFLINE"
    const val TIMEOUT = "TIMEOUT"
    const val OLLAMA_ERROR = "OLLAMA_ERROR"
    const val AUTH_FAILED = "AUTH_FAILED"
    const val BUSY = "BUSY"
    const val PROTOCOL_ERROR = "PROTOCOL_ERROR"
}

/** 추론 옵션 화이트리스트. relay 가 outbound InferRequest 를 만들 때 적용한다. */
val ALLOWED_OPTION_KEYS: Set<String> =
    setOf(
        "temperature",
        "num_predict",
        "num_ctx",
        "top_p",
        "top_k",
        "stop",
        "seed",
    )

/** 화이트리스트에 있는 옵션 키만 남긴다. */
fun filterOptions(options: Map<String, Any?>?): Map<String, Any?> = options?.filterKeys { it in ALLOWED_OPTION_KEYS } ?: emptyMap()

/** LLM 응답 토큰 사용량(없으면 0). */
data class Usage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
)

/**
 * WS 프레임. `type` 디스크리미네이터로 다형 역직렬화한다(Jackson).
 *
 * 직렬화 불변식: 임의 프레임 f 에 대해 decode(encode(f)) == f (round-trip 동치). 한국어 등
 * 비ASCII 는 보존된다(Jackson 기본).
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "type",
)
@JsonSubTypes(
    JsonSubTypes.Type(value = AuthFrame::class, name = FrameType.AUTH),
    JsonSubTypes.Type(value = AuthOkFrame::class, name = FrameType.AUTH_OK),
    JsonSubTypes.Type(value = AuthErrFrame::class, name = FrameType.AUTH_ERR),
    JsonSubTypes.Type(value = InferRequest::class, name = FrameType.INFER),
    JsonSubTypes.Type(value = InferResult::class, name = FrameType.RESULT),
    JsonSubTypes.Type(value = InferError::class, name = FrameType.ERROR),
    JsonSubTypes.Type(value = ChunkFrame::class, name = FrameType.CHUNK),
    JsonSubTypes.Type(value = PingFrame::class, name = FrameType.PING),
    JsonSubTypes.Type(value = PongFrame::class, name = FrameType.PONG),
    JsonSubTypes.Type(value = CancelFrame::class, name = FrameType.CANCEL),
    JsonSubTypes.Type(value = ProviderHelloFrame::class, name = FrameType.PROVIDER_HELLO),
    JsonSubTypes.Type(value = ProviderStatusFrame::class, name = FrameType.PROVIDER_STATUS),
)
sealed class Frame {
    abstract val type: String
}

/** 에이전트 → 릴레이: 인증. token 은 toString 에서 마스킹된다. */
data class AuthFrame(
    val token: String = "",
    val protocolVersion: String = PROTOCOL_VERSION,
    val agentVersion: String = "",
    val platform: String = "",
    override val type: String = FrameType.AUTH,
) : Frame() {
    override fun toString(): String =
        "AuthFrame(token=${if (token.isNotEmpty()) "***" else ""}, " +
            "protocolVersion=$protocolVersion, agentVersion=$agentVersion, platform=$platform)"
}

data class AuthOkFrame(
    val protocolVersion: String = PROTOCOL_VERSION,
    val sessionId: String = "",
    override val type: String = FrameType.AUTH_OK,
) : Frame()

data class AuthErrFrame(
    val code: String = ErrorCode.AUTH_FAILED,
    val message: String = "",
    override val type: String = FrameType.AUTH_ERR,
) : Frame()

/** 릴레이 → 에이전트: 추론 요청. */
data class InferRequest(
    val requestId: String,
    val model: String? = null,
    val prompt: String = "",
    val options: Map<String, Any?> = emptyMap(),
    override val type: String = FrameType.INFER,
) : Frame() {
    init {
        require(prompt.length <= MAX_PROMPT_CHARS) {
            "프롬프트가 너무 깁니다(${prompt.length} > ${MAX_PROMPT_CHARS}자)"
        }
    }
}

/** 에이전트 → 릴레이: 추론 성공. */
data class InferResult(
    val requestId: String,
    val text: String = "",
    val usage: Usage = Usage(),
    override val type: String = FrameType.RESULT,
) : Frame()

/** 에이전트 → 릴레이: 추론 실패. */
data class InferError(
    val requestId: String,
    val code: String = ErrorCode.OLLAMA_ERROR,
    val message: String = "",
    override val type: String = FrameType.ERROR,
) : Frame()

/** 에이전트 → 릴레이: 스트리밍 부분 텍스트. */
data class ChunkFrame(
    val requestId: String,
    val delta: String = "",
    val done: Boolean = false,
    override val type: String = FrameType.CHUNK,
) : Frame()

data class PingFrame(
    override val type: String = FrameType.PING,
) : Frame()

data class PongFrame(
    override val type: String = FrameType.PONG,
) : Frame()

/** 릴레이 → 에이전트: 진행 중 요청 취소. */
data class CancelFrame(
    val requestId: String,
    override val type: String = FrameType.CANCEL,
) : Frame()

/** 에이전트 → 릴레이: 연결 직후 제공 능력 보고(Provider Pool, 차수 5). */
data class ProviderHelloFrame(
    val models: List<String> = emptyList(),
    val maxConcurrency: Int = 1,
    val remainingDailyRequests: Int = 0,
    override val type: String = FrameType.PROVIDER_HELLO,
) : Frame()

/** 에이전트 → 릴레이: 주기적 상태 보고. */
data class ProviderStatusFrame(
    val load: String = "idle",
    val battery: String = "",
    val online: Boolean = true,
    val busy: Boolean = false,
    override val type: String = FrameType.PROVIDER_STATUS,
) : Frame()
