package com.discordassistant.central.relay.protocol

// DO NOT EDIT — `protocol/wire-contract.json` 에서 `scripts/gen_wire_contract.py` 로 생성됨.

/** 프로토콜 버전. 핸드셰이크에서 협상하며 major 가 다르면 비호환. */
const val PROTOCOL_VERSION: String = "1.0"

/** 단일 프레임 최대 직렬화 크기(바이트). */
const val MAX_FRAME_BYTES: Int = 1000000

/** 프롬프트 최대 길이(문자). */
const val MAX_PROMPT_CHARS: Int = 100000

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
