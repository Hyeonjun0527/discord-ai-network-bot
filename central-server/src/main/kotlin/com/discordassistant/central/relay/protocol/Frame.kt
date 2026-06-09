package com.discordassistant.central.relay.protocol

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo

// 공유 와이어 상수(PROTOCOL_VERSION·MAX_FRAME_BYTES·MAX_PROMPT_CHARS·FrameType·ErrorCode·
// ALLOWED_OPTION_KEYS)는 SSOT `protocol/wire-contract.json` 에서 생성된 WireContractGenerated.kt
// (동일 패키지)에 있다. Python(provider-agent)과 단일 생성기로 동기화돼 drift 가 불가능하다.

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
    // 재연결·재시작에 재사용할 durable 토큰(비면 미발급). 에이전트가 저장해 다음부터 이 토큰으로 인증.
    val providerToken: String = "",
    // 인증된 토큰이 묶인 길드. 에이전트가 '이름 미상' 수동 라벨링 없이 서버명을 바로 표시하도록 내려준다.
    // (토큰-연결 시 guildName 자동화. 봇이 그 길드에 있으므로 이름을 조회 가능. 비면 에이전트가 폴백 표기.)
    val guildId: Long? = null,
    val guildName: String? = null,
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
    val stream: Boolean = false, // true 면 에이전트가 ChunkFrame 으로 점진 응답(차수 11 #142)
    val task: String = "text", // "text" | "image"(로컬 SD 이미지 생성, SD Phase 2)
    // 이미지 정책(central 소유, 에이전트가 적용만). {"translatorSystemPrompt","forcedNegative"}.
    // null 이면 와이어에서 생략(하위호환) — 에이전트가 기본 정책 사용.
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val imagePolicy: Map<String, Any?>? = null,
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

/** 에이전트 → 릴레이: 스트리밍 부분 텍스트/이미지 청크. progress(0~100)≥0 이면 진행률 상태 청크(데이터 아님). */
data class ChunkFrame(
    val requestId: String,
    val delta: String = "",
    val done: Boolean = false,
    val progress: Int = -1,
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
    // 제공 능력. 기본 ["text"]; 로컬 SD 가능 시 "image" 포함(SD Phase 1, 라우팅은 Phase 2).
    val capabilities: List<String> = listOf("text"),
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
