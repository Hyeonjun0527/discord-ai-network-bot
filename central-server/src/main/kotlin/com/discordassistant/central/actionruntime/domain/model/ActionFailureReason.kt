package com.discordassistant.central.actionruntime.domain.model

/**
 * 예약 행동 실행 실패의 **분류**(NEXA-P13-T009, 순수 도메인 enum). 실패 원인을 안정 코드로 분류해 **재시도 가능
 * 여부**를 도메인이 결정론적으로 판단한다 — transient 만 bounded retry, 나머지는 즉시 영구 실패([FAILED]).
 *
 * **acceptance(T009) — 영구 실패를 무한 재시도하지 않고 상태와 reason 이 남는다**:
 * - [DISCORD_TRANSIENT] 만 [isRetryable]=true(일시 오류 — 잠시 후 재시도하면 풀릴 수 있음).
 * - [PERMISSION_DENIED]/[TARGET_MISSING]/[MODEL_TIMEOUT] 은 retry 해도 같은 결과이거나(권한·대상 부재) 비용만
 *   키우므로 즉시 [ActionStatus.FAILED] 로 종결([isRetryable]=false). 상태와 이 reason 이 남아 사후 분석 가능.
 *
 * 순수성: Spring/JPA/JDA 미참조.
 */
enum class ActionFailureReason(
    /** persistence·로그 직렬화용 안정 코드. */
    val wireName: String,
    /** 이 원인이 (bounded) 재시도 가치가 있는가 — transient 만 true. */
    val isRetryable: Boolean,
) {
    /** Discord 일시 오류(5xx·rate limit·네트워크) — 잠시 후 재시도 가능. */
    DISCORD_TRANSIENT("discord_transient", isRetryable = true),

    /** 권한 없음(채널 전송 권한 박탈 등) — 재시도해도 동일. 영구 실패. */
    PERMISSION_DENIED("permission_denied", isRetryable = false),

    /** 대상 부재(채널/스레드/메시지 삭제됨) — 재시도해도 동일. 영구 실패. */
    TARGET_MISSING("target_missing", isRetryable = false),

    /** 모델(speech 생성) timeout — 비용·지연만 키움. 영구 실패로 종결(상위가 다음 결정에서 재시도). */
    MODEL_TIMEOUT("model_timeout", isRetryable = false),
}
