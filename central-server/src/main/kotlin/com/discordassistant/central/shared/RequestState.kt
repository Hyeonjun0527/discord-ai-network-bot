package com.discordassistant.central.shared

/**
 * 요청 상태머신 (specs: `DM-S-RequestState`, 10상태).
 *
 * 완료/실패/거절은 종단 상태로 되돌릴 수 없다(specs 불변 조건 9.6).
 */
enum class RequestState {
    /** Discord 에서 요청 받음. */
    RECEIVED,

    /** 서버/채널/역할 정책 확인 완료. */
    POLICY_CHECKED,

    /** 프로바이더 선택 중. */
    ROUTING,

    /** 프로바이더 대기열에 들어감. */
    QUEUED,

    /** 프로바이더 에이전트에게 전송됨. */
    SENT_TO_PROVIDER,

    /** 프로바이더가 Ollama 처리 중. */
    RUNNING,

    /** 답변 완료. */
    COMPLETED,

    /** 실패. */
    FAILED,

    /** 다른 프로바이더로 재시도 중. */
    FALLBACK_RUNNING,

    /** 권한 또는 정책상 거절. */
    REJECTED,
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == REJECTED
}
